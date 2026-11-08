# OrcaPod engine bridge

OrcaSlicer's slicing engine, built without the desktop GUI and wrapped in a C
API (`engine-cxx/orca_bridge.h`) for the mobile apps. See
[docs/HLSD/mobile-bridge.md](../../docs/HLSD/mobile-bridge.md) for the design.

Adapted from [OrcaPad](https://github.com/iSteeb/OrcaPad) (AGPL-3.0); the
original copyright lines are kept in each file.

## Build on Linux

Needs a C++20 compiler (`clang++` by default, set `CXX` to change it), the GMP
and MPFR development packages, CMake and Ninja (for oneTBB), `git`, `curl`,
`unzip` and `python3`:

```bash
sudo apt install clang cmake ninja-build libgmp-dev libmpfr-dev
mobile/bridge/build-host.sh          # build only
mobile/bridge/build-host.sh --test   # build, then run the contract tests and the CLI test
```

The first run downloads Boost, CGAL, Eigen, oneTBB, OpenCASCADE and a few smaller
sources into `mobile/bridge/cache/`, and builds OpenCASCADE's STEP toolkits once
(about 20 minutes on four cores; `ORCAPOD_STEP=stub` skips it and builds without
STEP import). Outputs land in `mobile/bridge/build-host/`. Set
`ORCAPOD_JOBS` to change the number of parallel compile jobs (default 6).
`ORCAPOD_TBB=serial` builds the engine single-threaded, without oneTBB, and
`ORCAPOD_HOST_BUILD_DIR` puts the outputs in another directory.

## Slice from the command line

```bash
B=mobile/bridge/build-host/orcapod-slice
$B --profiles resources/profiles --list printer
$B --profiles resources/profiles --printer "Prusa MK4 0.4 nozzle" --list process
$B --profiles resources/profiles --printer "Prusa MK4 0.4 nozzle" \
   --process "0.20mm Standard @MK4" -o cube.gcode cube.stl
```

Without `--filament` or `--process`, the printer's own defaults are used.
Several models are arranged on the plate; `--arrange` arranges a single one
too.

## Build for Android

Needs the Android NDK (r26 or newer), plus the host tools above (CMake and
Ninja included, for oneTBB) except GMP and MPFR, which are built for the
target:

```bash
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018
mobile/bridge/build-android.sh          # build only
mobile/bridge/build-android.sh --test   # also run the CLI smoke test under qemu-aarch64
```

Outputs land in `mobile/bridge/build-android/<abi>/`: `liborcapod.so`, the
engine and C API for the app's `jniLibs/<abi>/`, and a static `orcapod-slice`.
`ORCAPOD_ANDROID_ABI` picks `arm64-v8a` (default) or `x86_64`, and
`ORCAPOD_ANDROID_API` the minimum API level (default 26),
`ORCAPOD_TBB=serial` builds the engine single-threaded, without oneTBB, and
`ORCAPOD_STEP=stub` builds it without OpenCASCADE (no STEP import). GMP and MPFR come
from ftp.gnu.org and are checked against their pinned SHA-256; point `GMP_URL`
and `MPFR_URL` at a mirror if that host is unreachable. The `--test` run needs
`qemu-user` on an x86_64 host.
