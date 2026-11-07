plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The engine bridge (liborcapod.so) is cross-compiled outside Gradle by
// mobile/bridge/build-android.sh, since it is a large, slow C++ build with its
// own dependency pins. Gradle only links the thin JNI layer against it.
val bridgeDir = rootProject.file(providers.gradleProperty("orcapod.bridgeDir").get())
val abis = providers.gradleProperty("orcapod.abis").get().split(',').map { it.trim() }.filter { it.isNotEmpty() }
val repoRoot = rootProject.file("../..")

android {
    namespace = "org.orcapod.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "org.orcapod.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DORCAPOD_BRIDGE_DIR=${bridgeDir.absolutePath}",
                    "-DORCAPOD_BRIDGE_HEADER_DIR=${repoRoot.resolve("mobile/bridge/engine-cxx").absolutePath}",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // 16 KB page alignment (Android 15+) needs uncompressed, aligned .so files.
            useLegacyPackaging = false
        }
    }
    androidResources {
        // Already a zip; storing it avoids a second compression pass.
        noCompress += "zip"
    }
}

// OrcaSlicer's bundled printer, filament and process profiles, zipped into the
// app's assets. The app extracts the vendors the user picks on first launch.
val generatedAssets = layout.buildDirectory.dir("generated/orcapod/assets")
val zipProfiles = tasks.register<Zip>("zipProfiles") {
    from(repoRoot.resolve("resources/profiles")) { exclude("**/*.py") }
    archiveFileName.set("profiles.zip")
    destinationDirectory.set(generatedAssets)
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

// The prebuilt engine library, packaged next to the JNI library that links it.
val generatedJniLibs = layout.buildDirectory.dir("generated/orcapod/jniLibs")
val copyBridge = tasks.register<Sync>("copyBridge") {
    for (abi in abis) {
        from(bridgeDir.resolve("$abi/liborcapod.so")) { into(abi) }
    }
    into(generatedJniLibs)
}

android.sourceSets["main"].assets.srcDir(generatedAssets)
android.sourceSets["main"].jniLibs.srcDir(generatedJniLibs)
tasks.matching { it.name.matches(Regex("merge\\w*Assets|generate\\w*Assets")) }.configureEach { dependsOn(zipProfiles) }
tasks.matching { it.name.matches(Regex("merge\\w*JniLibFolders")) }.configureEach { dependsOn(copyBridge) }

// Fail early, with the fix, instead of a linker error deep in the CMake build.
val checkBridge = tasks.register("checkBridge") {
    val libs = abis.map { bridgeDir.resolve("$it/liborcapod.so") }
    doLast {
        val missing = libs.filterNot { it.isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing engine bridge: ${missing.joinToString()}\n" +
                    "Build it first with mobile/bridge/build-android.sh " +
                    "(ORCAPOD_ANDROID_ABI selects the ABI), or point orcapod.bridgeDir at a prebuilt copy."
            )
        }
    }
}
tasks.matching { it.name.startsWith("configureCMake") }.configureEach { dependsOn(checkBridge) }
copyBridge.configure { dependsOn(checkBridge) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.documentfile)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}

// tools/host-jni-test.sh passes a host build of the JNI library and the
// profile tree; without them EngineHostTest is skipped.
tasks.withType<Test>().configureEach {
    val jniDir = providers.gradleProperty("orcapod.hostJniDir").orNull
    val profiles = providers.gradleProperty("orcapod.profilesDir").orNull
    if (jniDir != null) {
        systemProperty("java.library.path", jniDir)
        systemProperty("orcapod.hostJniDir", jniDir)
    }
    if (profiles != null) systemProperty("orcapod.profilesDir", profiles)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
