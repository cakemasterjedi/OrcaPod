# OrcaPod for Android

The Android app: import STL, 3MF, OBJ, AMF or STEP models onto a 3D build plate,
move, rotate and scale them, pick printer, filament and process profiles,
slice with OrcaSlicer's engine, preview the layers and send the G-code to
an OctoPrint, Klipper (Moonraker), PrusaLink, Duet or Bambu Lab (LAN mode)
printer (or several at once, through a print queue with a dashboard of all
your printers, where a running print can be paused, stopped or told to skip
some of its objects), share it or save it. The online model library searches Thingiverse
(with your own app token) and browses Printables, MakerWorld and other sites,
adding their downloads to the plate. See [docs/HLSD/android-app.md](../../docs/HLSD/android-app.md) for
the design.

## Build

Needs JDK 17 or newer and the Android SDK with NDK 27.2.12479018 and CMake
3.22.1 (`sdkmanager "ndk;27.2.12479018" "cmake;3.22.1" "platforms;android-35"`).

1. Build the engine library once (and again after engine changes):

   ```bash
   export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018
   mobile/bridge/build-android.sh
   ```

2. Build the app:

   ```bash
   cd mobile/android
   echo "sdk.dir=$HOME/Android/Sdk" > local.properties
   ./gradlew assembleDebug
   ```

   The APK lands in `app/build/outputs/apk/debug/OrcaPod-0.1.0.apk`; install it
   with `adb install -r` on an arm64 device running Android 8.0 or newer.

`orcapod.abis` in `gradle.properties` lists the ABIs to package and
`orcapod.bridgeDir` where their `liborcapod.so` files are. To add `x86_64`
(for an emulator), build the bridge with `ORCAPOD_ANDROID_ABI=x86_64` and
build with `-Porcapod.abis=arm64-v8a,x86_64`.

## Test

`./gradlew :app:testDebugUnitTest` runs the JVM tests, including the
printer connections against mock printers.

```bash
mobile/android/tools/host-jni-test.sh
```

builds the engine for the Linux host (the `build-host.sh` prerequisites
apply), links the app's JNI code into a host library and runs the engine
layer's tests on the JVM.

The `Android` workflow (`.github/workflows/android.yml`) runs both builds and
all of these tests on every pull request that touches the app, the engine or
the profiles, and attaches the APK to the run as `orcapod-apk`. On `main` it
also publishes the APK as the latest GitHub release, which the app's update
check reads (see the Updates section of the design doc).
