# Mobile Engine Bridge — High Level Design

## Why it exists

OrcaPod's mobile apps reuse OrcaSlicer's slicing engine (`src/libslic3r`) rather
than reimplementing any of it. The desktop build cannot be reused as it is: it
links wxWidgets and OpenGL, builds a large dependency tree through `deps/`
(oneTBB, OCCT, OpenVDB, Assimp, libpng and more), and exposes the engine
through C++ classes that a Kotlin/JNI or Swift client cannot call directly.

`mobile/bridge/` is the layer in between. It compiles a GUI-free subset of the
engine with a minimal set of dependencies and wraps it in a C API
(`engine-cxx/orca_bridge.h`) that any client language can call. The design
and most of the code come from [OrcaPad](https://github.com/iSteeb/OrcaPad),
an iPadOS port of OrcaSlicer under the same AGPL-3.0 license; files carry
both projects' copyright lines.

## The three-module rule

```text
mobile client  ──▶  bridge (C API)  ──▶  engine (src/, deps_src/)
```

Dependencies only point right.

- The **engine** is this repository's own `src/libslic3r` and `deps_src/`. The
  bridge never edits or forks it. Portability fixes that are generally useful
  belong in the engine itself, upstream, not in the bridge.
- The **bridge** is the only code that includes engine headers. It contains
  every C++ exception before it crosses the C boundary, owns the lifetime of
  every handle, string and buffer it returns, and reports failures as typed
  status codes (`orca_status_code`). Callers branch on codes, never on
  diagnostic text.
- A **client** sees only `orca_bridge.h`. The `orcapod-slice` command-line
  tool is one such client and doubles as an end-to-end check.

## What gets compiled

There is no CMake project for the bridge. `engine-sources.sh` is the single
list of translation units, shared by every build of the bridge (the Linux
host build in `build-host.sh` and the Android NDK build in `build-android.sh`;
`compile-lib.sh` holds their incremental compile machinery). It is grown
link-error-driven: a file is added when something in the closure needs one of
its symbols. The host build packs everything into a static archive before
linking, so translation units nothing references are never linked, as on the
mobile targets.

Boost, CGAL, Eigen and oneTBB are fetched at the same versions OrcaSlicer's
`deps/` build pins (`BOOST_VER`, `CGAL_VER`, `EIGEN_VER`, `TBB_VER` in
`engine-sources.sh`), so the engine compiles against exactly what the desktop
build uses, and no host packages are needed apart from GMP and MPFR. The
compiled parts of Boost (filesystem, thread) and NLopt are built from source
inside the closure. oneTBB is the one dependency built with its own CMake
project (`engine_build_tbb`), because it is the engine's source of
parallelism: profile loading and every slicing step run on all cores through
it, as on the desktop.

OpenCASCADE is the other one (`engine_build_occt`), for STEP import. It is
fetched at the desktop's `deps/OCCT` tag (`OCCT_VER`) and gets the same patch
and CMake options, with one difference: instead of whole modules, only the
toolkits `Format/STEP.cpp` needs are built (`TKDESTEP`, `TKXCAF`, `TKMesh` and
what they depend on, `OCCT_TOOLKITS`), which skips the other formats' readers. The
toolkits are static archives, so `Format/STEP.cpp` is compiled into the
closure as on the desktop and only the code STEP import reaches is linked.
`ORCAPOD_STEP=stub` builds without OpenCASCADE: `engine-cxx/step_stubs.cpp`
then replaces `Format/STEP.cpp` and STEP import fails loudly.

STEP import mirrors the desktop's two-stage flow. `orca_step_open` reads and
parses the file once and keeps the document in an `orca_step` handle, so the
import dialog can ask `orca_step_triangle_count` how many facets a meshing
quality gives (cancellable from another thread with `orca_step_cancel`)
without reading the file again, and `orca_plater_import_step` then meshes it
into the document with the dialog's choices: linear and angular deflection,
Split compound, Keep original names and assembly or single object.
`orca_plater_import_model` takes STEP files too, with the desktop defaults.

## Android

`build-android.sh` cross-compiles the same closure with the NDK's clang for
one ABI (`arm64-v8a` by default) and a minimum API level (26 by default).
GMP and MPFR have no Android packages, so it builds them from source as
static libraries at OrcaSlicer's `deps/` pins. oneTBB is cross-built through
the same `engine_build_tbb` as the host, with the NDK's CMake toolchain file,
so the engine runs on all cores on the phone too. The result is one
self-contained `liborcapod.so` for the app's `jniLibs/`: libc++ is linked in
statically, so the app needs no `libc++_shared.so`, the only exported symbols
are the `orca_*` C API (`android/exports.map`), and its segments are aligned to
16 KB pages, which Android 15 devices require. The app's JNI layer sits on
top of that C API and is not part of the bridge ([android-app.md](android-app.md)).

Two target differences need compile flags rather than code changes.
`char` is unsigned on Arm Linux, while the engine assumes it is signed
(`BuildVolume_Type : char` holds `-1`), so the build passes `-fsigned-char`, as
OrcaSlicer's own CMake build does. The NDK's libc++ has no `std::atomic_ref`,
so Boost.Filesystem is told to use `boost::atomic_ref` instead.

The same archive also links a static `orcapod-slice`, which runs on a Linux
host under `qemu-aarch64`, so `build-android.sh --test` runs the CLI smoke
test on the Arm code without a device. A static Bionic executable needs a
TLS segment aligned to 64 bytes, which `android/tls_align.c` provides; the
shared library does not need it. libc++ on Android resolves the temporary
directory from `TMPDIR`, falling back to `/data/local/tmp`, so the app should
set `TMPDIR` to its cache directory before calling into the engine.

## Shims and adapters

The engine assumes some dependencies the bridge does not ship. Each gap is
filled in one of two ways, and never with a silent fake success:

- **Header shims** (`shim/`) stand in for a dependency whose behavior the
  engine does not need on mobile. Boost.Log's trivial logger is a no-op sink.
  `shim-tbb-serial/` is a single-threaded implementation of the TBB
  interfaces the engine uses, selected with `ORCAPOD_TBB=serial` for a
  platform where oneTBB is not built yet. Parallelism is a speed
  optimization, not semantics, so both builds produce the same output.
  The OCCT headers in `shim/occt-stub/` exist only so `Format/STEP.hpp`, which
  `Model.hpp` includes unconditionally, parses in an `ORCAPOD_STEP=stub`
  build.
- **Loud adapters** (`engine-cxx/format_stubs.cpp`, `jpeg_stubs.cpp`) define
  engine functions whose real implementation needs a dependency that is not
  ported yet: textured-model import (Assimp), PNG decoding
  for texture displacement maps (libpng), mesh booleans and UV
  parameterization (CGAL mesh processing, MCUT) and JPEG thumbnails. Each one
  throws, which the bridge turns into a typed failure, so a missing feature
  is reported rather than producing wrong output. Porting a dependency means
  replacing its adapter with the real source file.
- `engine-cxx/utils_subset.cpp` carries the few functions of
  `libslic3r/utils.cpp` the closure needs. `utils.cpp` itself sets up
  Boost.Log sinks and Boost.Locale, which the mobile builds do not ship. The
  functions are copied from upstream without behavior changes and must follow
  it when it changes.

## Keeping up with upstream

Because the engine is this repository's own OrcaSlicer tree, every merge from
upstream can move the closure. The contract tests (`tools/contract_tests.cpp`)
are how drift is caught: they exercise every bridge entry point against the
real bundled profiles, slicing included. After a merge, run
`mobile/bridge/build-host.sh --test`; the usual fixes are adding a new
translation unit to `engine-sources.sh`, a new TBB interface to the serial
shim (checked with `ORCAPOD_TBB=serial`), or a signature change in the
bridge or an adapter.

The contract tests load every vendor's system profiles at once, unlike the
desktop app, which loads only installed vendors. A system process preset that
declares no compatible printers therefore becomes compatible with every
printer in the bridge, and is chosen over a printer's own default process.
The tests that check the default process selection fail when a profile has
this defect.
