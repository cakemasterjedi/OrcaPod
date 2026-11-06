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
host build in `build-host.sh` today, the Android NDK build next). It is grown
link-error-driven: a file is added when something in the closure needs one of
its symbols. The host build packs everything into a static archive before
linking, so translation units nothing references are never linked, as on the
mobile targets.

Boost, CGAL and Eigen are fetched at the same versions OrcaSlicer's `deps/`
build pins (`BOOST_VER`, `CGAL_VER`, `EIGEN_VER` in `engine-sources.sh`), so the
engine compiles against exactly what the desktop build uses, and no host
packages are needed apart from GMP and MPFR. The compiled parts of Boost
(filesystem, thread) and NLopt are built from source inside the closure.

## Shims and adapters

The engine assumes some dependencies the bridge does not ship. Each gap is
filled in one of two ways, and never with a silent fake success:

- **Header shims** (`shim/`) stand in for a dependency whose behavior the
  engine does not need on mobile. oneTBB is replaced by a serial
  implementation of the TBB interfaces the engine uses, so the engine runs
  single-threaded: parallelism is a speed optimization, not semantics.
  `tbbmalloc` maps to `malloc`, and Boost.Log's trivial logger is a no-op sink.
  The OCCT headers in `shim/occt-stub/` exist only so `Format/STEP.hpp`, which
  `Model.hpp` includes unconditionally, parses.
- **Loud adapters** (`engine-cxx/format_stubs.cpp`, `jpeg_stubs.cpp`) define
  engine functions whose real implementation needs a dependency that is not
  ported yet: STEP import (OCCT), textured-model import (Assimp), PNG decoding
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
shim, or a signature change in the bridge or an adapter.

The contract tests load every vendor's system profiles at once, unlike the
desktop app, which loads only installed vendors. A system process preset that
declares no compatible printers therefore becomes compatible with every
printer in the bridge, and is chosen over a printer's own default process.
The tests that check the default process selection fail when a profile has
this defect.
