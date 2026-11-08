<div align="center">

<img alt="OrcaPod icon" src=".github/assets/orcapod-icon.svg" width="96" height="96">

# OrcaPod

**Prepare and slice 3D prints on your Android phone or tablet.**

[![Android](https://github.com/cakemasterjedi/OrcaPod/actions/workflows/android.yml/badge.svg)](https://github.com/cakemasterjedi/OrcaPod/actions/workflows/android.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue)](LICENSE.txt)
![Platform: Android 8.0+](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)

</div>

OrcaPod puts a full desktop-grade slicer in your pocket. Import a model, place
it on a 3D build plate, pick your printer, filament and print settings, and
slice it right on the device. There is no cloud service and no account: the
slicing engine runs on the phone itself, and your models never leave it.

<!-- Screenshots: add images under .github/assets/ and list them here.
<p align="center">
  <img src=".github/assets/screenshot-plate.png" width="30%">
  <img src=".github/assets/screenshot-profiles.png" width="30%">
  <img src=".github/assets/screenshot-preview.png" width="30%">
</p>
-->

## Features

- **Import models** in STL, 3MF, OBJ and AMF, from your files or other apps.
- **3D build plate** in OpenGL ES 3: move, rotate and scale objects with touch
  gestures, and arrange them on the plate.
- **Printer, filament and process profiles** for hundreds of printers from
  every major brand, plus your own custom profiles.
- **On-device slicing** on every CPU core, with progress and cancel.
- **Layer preview** of the sliced toolpaths before you print.
- **Export G-code** to a file or share it with another app, and save your
  plate as a 3MF project.

## Download

**[Download the latest OrcaPod APK](https://github.com/cakemasterjedi/OrcaPod/releases/latest/download/OrcaPod.apk)**
for an arm64 device running Android 8.0 or newer. Android asks you to allow
installs from your browser the first time.

Every build of `main` is published on the
[releases page](https://github.com/cakemasterjedi/OrcaPod/releases). All builds
are signed with the same key and numbered in order, so a newer one installs
over the app without uninstalling, keeping your projects, printers and
settings. The app checks for a newer build when it starts, and on
**Menu → Check for updates**, and installs it for you.

Builds from before shared signing (the `orcapod-debug-apk` workflow artifacts)
were each signed with a different key: uninstall one of those once before
installing a release.

## Build from source

You need JDK 17+, the Android SDK with NDK `27.2.12479018` and CMake `3.22.1`.

```bash
# 1. Build the slicing engine for Android (once, and after engine changes)
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018
mobile/bridge/build-android.sh

# 2. Build the app
cd mobile/android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/OrcaPod-0.1.0.apk
```

See [mobile/android/README.md](mobile/android/README.md) for the app and its
tests, and [mobile/bridge/README.md](mobile/bridge/README.md) for the engine
build, the C API and a command-line slicer for Linux.

## How it works

```
Jetpack Compose UI  ──  OpenGL ES 3 build plate
        │
   Kotlin engine session
        │  JNI
   liborcapod.so  ──  C API over the slicing engine (C++)
```

The app is written in Kotlin with Jetpack Compose. All slicing is done by a
native C++ engine, built without any desktop GUI and wrapped in a small C API.
The design is described in [docs/HLSD/android-app.md](docs/HLSD/android-app.md)
and [docs/HLSD/mobile-bridge.md](docs/HLSD/mobile-bridge.md).

| Path | What's there |
|---|---|
| `mobile/android/` | The Android app |
| `mobile/bridge/` | The engine's C API, build scripts and CLI |
| `src/libslic3r/` | The slicing engine |
| `resources/profiles/` | Printer, filament and process profiles |

## Contributing

Bug reports, ideas and pull requests are welcome. Please read
[CONTRIBUTING.md](CONTRIBUTING.md) first, and report security problems
privately as described in [SECURITY.md](SECURITY.md).

## Credits and license

OrcaPod stands on the shoulders of the open-source slicer community. Its
slicing engine and printer profiles come from
[OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer) by SoftFever and its
contributors, which grew out of
[Bambu Studio](https://github.com/bambulab/BambuStudio) by Bambu Lab,
[PrusaSlicer](https://github.com/prusa3d/PrusaSlicer) by Prusa Research, and
[Slic3r](https://github.com/Slic3r/Slic3r) by Alessandro Ranellucci and the
RepRap community. The engine bridge is adapted from
[OrcaPad](https://github.com/iSteeb/OrcaPad). Thank you to everyone who built
them.

OrcaPod is licensed under the **GNU Affero General Public License, version 3**,
the same license as the projects it builds on. See [LICENSE.txt](LICENSE.txt).
The original copyright notices are kept in the source files. OrcaPod is an
independent project and is not affiliated with or endorsed by OrcaSlicer,
Bambu Lab or Prusa Research.
