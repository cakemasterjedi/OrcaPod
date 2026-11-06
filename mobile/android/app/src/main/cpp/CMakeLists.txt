# JNI layer between the Kotlin app and the engine bridge's C API.
cmake_minimum_required(VERSION 3.22.1)
project(orcapod_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ORCAPOD_BRIDGE_DIR OR NOT ORCAPOD_BRIDGE_HEADER_DIR)
    message(FATAL_ERROR "ORCAPOD_BRIDGE_DIR and ORCAPOD_BRIDGE_HEADER_DIR must be set (app/build.gradle.kts passes them)")
endif()

# liborcapod.so from mobile/bridge/build-android.sh: the whole engine, with
# libc++ linked in and only the orca_* C API exported. NO_SONAME links it as
# -lorcapod so the app's library records a plain "liborcapod.so" dependency.
add_library(orcapod SHARED IMPORTED)
set_target_properties(orcapod PROPERTIES
    IMPORTED_LOCATION "${ORCAPOD_BRIDGE_DIR}/${ANDROID_ABI}/liborcapod.so"
    IMPORTED_NO_SONAME TRUE
    INTERFACE_INCLUDE_DIRECTORIES "${ORCAPOD_BRIDGE_HEADER_DIR}")

add_library(orcapod_jni SHARED orcapod_jni.cpp)
target_compile_options(orcapod_jni PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(orcapod_jni PRIVATE orcapod log)
target_link_options(orcapod_jni PRIVATE -Wl,-z,max-page-size=16384)
