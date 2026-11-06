# OrcaPod engine bridge

OrcaSlicer's slicing engine, built without the desktop GUI and wrapped in a C
API (`engine-cxx/orca_bridge.h`) for the mobile apps. See
[docs/HLSD/mobile-bridge.md](../../docs/HLSD/mobile-bridge.md) for the design.

Adapted from [OrcaPad](https://github.com/iSteeb/OrcaPad) (AGPL-3.0); the
original copyright lines are kept in each file.

## Build on Linux

Needs a C++20 compiler (`clang++` by default, set `CXX` to change it), the GMP
and MPFR development packages, `git`, `curl`, `unzip` and `python3`:

```bash
sudo apt install clang libgmp-dev libmpfr-dev
mobile/bridge/build-host.sh          # build only
mobile/bridge/build-host.sh --test   # build, then run the contract tests and the CLI test
```

The first run downloads Boost, CGAL, Eigen and a few smaller sources into
`mobile/bridge/cache/`. Outputs land in `mobile/bridge/build-host/`. Set
`ORCAPOD_JOBS` to change the number of parallel compile jobs (default 6).

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
