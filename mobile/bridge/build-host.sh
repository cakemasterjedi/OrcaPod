#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 OrcaPad contributors
# Copyright (c) 2026 OrcaPod contributors
# Adapted from OrcaPad (https://github.com/iSteeb/OrcaPad), Bridge/build-host-tests.sh.
#
# Builds the headless engine for the local Linux host from this repository's
# own OrcaSlicer sources (src/, deps_src/) and the shared compile list in
# engine-sources.sh, without wxWidgets, OpenGL or OCCT:
#
#   build-host/libengine-host.a   the engine closure + the C bridge
#   build-host/contract_tests     the bridge contract test suite
#   build-host/orcapod-slice      the command-line slicer (tools/orcapod_slice.cpp)
#
# With --test it then runs the contract tests and the CLI smoke test.
# ORCAPOD_HOST_BUILD_DIR moves the outputs elsewhere, so a differently
# configured build (the app's host JNI test) does not overwrite this one.
#
# The engine runs multithreaded on oneTBB, built from source at OrcaSlicer's
# pin. ORCAPOD_TBB=serial swaps in the single-threaded TBB stand-in from
# shim-tbb-serial/ instead (no CMake needed, slower).
#
# Host prerequisites: a C++20 compiler (clang++ by default, override with CXX),
# GMP and MPFR development packages (libgmp-dev libmpfr-dev), git, curl,
# unzip, python3, and CMake with Ninja for oneTBB. Boost, CGAL, Eigen, oneTBB
# and the other build-only sources are
# fetched at OrcaSlicer's pinned versions into mobile/bridge/cache/.
#
# -O2 -DNDEBUG matches the shipped configuration: upstream GCode.hpp enables
# extra placeholder validation in debug builds that upstream's own bundled
# presets fail.
set -euo pipefail

RUN_TESTS=0
[ "${1:-}" = "--test" ] && RUN_TESTS=1

source "$(dirname "$0")/engine-sources.sh"   # sets BRIDGE, REPO and the dep pins
engine_fetch_deps

SRC="$REPO/src"
DEPS="$REPO/deps_src"
CXX="${CXX:-clang++}"
BUILD="${ORCAPOD_HOST_BUILD_DIR:-$BRIDGE/build-host}"
mkdir -p "$BUILD/gen/libslic3r" "$BUILD/obj"

case "${ORCAPOD_TBB:-real}" in
    real)
        TBB_PREFIX="$(engine_build_tbb "$BUILD" -DCMAKE_CXX_COMPILER="$CXX" -DCMAKE_C_COMPILER="${CXX%++}")"
        TBB_FLAGS=(-isystem "$TBB_PREFIX/include")
        TBB_LIBS=("$TBB_PREFIX/lib/libtbb.a" "$TBB_PREFIX/lib/libtbbmalloc.a" -ldl)
        ;;
    serial)
        TBB_FLAGS=(-I"$BRIDGE/shim-tbb-serial")
        TBB_LIBS=()
        ;;
    *) echo "FATAL: ORCAPOD_TBB must be real or serial" >&2; exit 1 ;;
esac

# The ORCA version comes from version.inc, as the CMake build reads it.
inc_var() { sed -n "s/^set($1 \"\(.*\)\")/\1/p" "$REPO/version.inc" | head -1; }
SLIC3R_VERSION="$(inc_var SLIC3R_VERSION)"
SOFTFEVER_VERSION="$(inc_var SoftFever_VERSION)"

CFLAGS=(
    -std=c++17 -O2 -DNDEBUG -Wno-deprecated-declarations
    # ORDER MATTERS: shim-host FIRST (host md5 override), then the shared
    # platform shims (boost/log, boost/nowide, occt stub) and TBB, and only then
    # the real Boost headers.
    -I"$BRIDGE/shim-host"
    -I"$BRIDGE/shim" -I"$BRIDGE/shim/occt-stub"
    "${TBB_FLAGS[@]}"
    -I"$SRC" -I"$SRC/libslic3r" -I"$BUILD/gen"
    -I"$DEPS" -I"$DEPS/admesh"
    -I"$DEPS/qhull/src"
    -I"$DEPS/glu-libtess/include"
    -I"$DEPS/libigl" -I"$BRIDGE/cache/Orca-deps-libnoise/src"
    -I"$DEPS/libnest2d/include"
    -I"$DEPS/clipper2/Clipper2Lib/include" -I"$DEPS/expat"
    -I"$DEPS/miniz"
    -I"$BRIDGE/cache/cereal/include"
    -isystem "$BOOST_INC" -isystem "$CGAL_INC" -isystem "$EIGEN_INC"
    -I"$BRIDGE/engine-cxx"
    -DBOOST_ALL_NO_LIB -DBOOST_FILESYSTEM_NO_DEPRECATED
    -DSLIC3R_APP_KEY='"OrcaSlicer"' -DSLIC3R_APP_NAME='"OrcaSlicer"'
)
CONLY_FLAGS=(
    -O2 -DNDEBUG -Wno-deprecated-declarations
    -include limits.h
    -I"$DEPS" -I"$DEPS/qhull/src" -I"$DEPS/glu-libtess/include"
    -I"$DEPS/libigl" -I"$BRIDGE/cache/Orca-deps-libnoise/src"
    -I"$DEPS/libnest2d/include"
    -I"$DEPS/clipper2/Clipper2Lib/include" -I"$DEPS/expat"
    -I"$DEPS/miniz"
)

# --- generated version header ---
# libnoise is addressed as <libnoise/noise.h>; expose it via the gen/ tree.
ln -sfn "$BRIDGE/cache/Orca-deps-libnoise/src" "$BUILD/gen/libnoise"
for vpath in "$BUILD/gen/libslic3r_version.h" "$BUILD/gen/libslic3r/libslic3r_version.h"; do
    sed -e "s/@SLIC3R_VERSION@/$SLIC3R_VERSION/g" \
        -e "s/@SoftFever_VERSION@/$SOFTFEVER_VERSION/g" \
        -e "s/@SLIC3R_BUILD_ID@/OrcaPod-$SOFTFEVER_VERSION/g" \
        -e 's/@SLIC3R_APP_KEY@/OrcaSlicer/g' \
        -e 's/@SLIC3R_APP_NAME@/OrcaSlicer/g' \
        -e 's/@BBL_INTERNAL_TESTING@/0/g' \
        -e 's/@ORCA_CHECK_GCODE_PLACEHOLDERS@/0/g' \
        "$SRC/libslic3r/libslic3r_version.h.in" > "$vpath.tmp"
    if [ ! -f "$vpath" ] || ! cmp -s "$vpath.tmp" "$vpath"; then mv "$vpath.tmp" "$vpath"; else rm -f "$vpath.tmp"; fi
done

# --- incremental compile machinery (mirrors build-core.sh) ---
OBJ="$BUILD/obj"
MANIFEST="$BUILD/objects.txt"
: > "$MANIFEST"

FLAGS_HASH="$(printf '%s\n' "${CFLAGS[@]}" "${CONLY_FLAGS[@]}" "$CXX" | sha256sum | cut -c1-16)"
if [ -f "$BUILD/flags.sha" ] && [ "$(cat "$BUILD/flags.sha")" != "$FLAGS_HASH" ]; then
    echo "==> flags/toolchain changed — full rebuild" >&2
    rm -rf "$OBJ"
fi
mkdir -p "$OBJ"
echo "$FLAGS_HASH" > "$BUILD/flags.sha"

needs_build() { # needs_build <src> <obj> — true if recompile required
    local src="$1" o="$2"
    [ -f "$o" ] || return 0
    [ "$src" -nt "$o" ] && return 0
    local d="$o.d" p
    [ -f "$d" ] || return 0
    while IFS= read -r p; do
        [ -z "$p" ] && continue
        [ -e "$p" ] && [ "$p" -nt "$o" ] && return 0
    done < <(sed '1s/[^:]*://' "$d" | tr '\\ ' '\n' | sed '/^$/d')
    return 1
}

JOBS="$BUILD/jobs.sh"
PENDING_OBJS="$BUILD/pending-objs.txt"
: > "$JOBS"
: > "$PENDING_OBJS"
PENDING=0
declare -A SEEN_OBJS=()

compile_one() { # compile_one <src> <obj> <compiler+flags...>
    local src="$1" o="$2"; shift 2
    if [[ -n "${SEEN_OBJS[$o]+present}" ]]; then
        echo "FATAL: duplicate compile source maps to $o" >&2
        exit 1
    fi
    SEEN_OBJS[$o]=1
    if needs_build "$src" "$o"; then
        {
            printf '%q ' "$@" -MD -MF "$o.d" -c "$src" -o "$o"
            printf ' 2>%q\n' "$o.err"
        } >> "$JOBS"
        echo "$o" >> "$PENDING_OBJS"
        PENDING=$((PENDING + 1))
    fi
    echo "$o" >> "$MANIFEST"
}

run_jobs_parallel() {
    [ "$PENDING" -gt 0 ] || return 0
    local maxjobs="${ORCAPOD_JOBS:-6}"
    echo "==> compiling $PENDING files, $maxjobs parallel (niced)" >&2
    local p=0 job_failed=0
    while IFS= read -r cmd; do
        nice -n 15 bash -c "$cmd" &
        p=$((p + 1))
        if (( p >= maxjobs )); then
            if ! wait -n; then job_failed=1; fi
            p=$((p - 1))
        fi
    done < "$JOBS"
    while (( p > 0 )); do
        if ! wait -n; then job_failed=1; fi
        p=$((p - 1))
    done
    local failed=0 o
    while IFS= read -r o; do
        [ -n "$o" ] || continue
        if [ ! -f "$o" ]; then
            echo "FAILED (no object): ${o}" >&2; head -8 "${o}.err" 2>/dev/null >&2; failed=1
        elif grep -q ' error:' "${o}.err" 2>/dev/null; then
            echo "FAILED (compiler error): ${o}" >&2; grep -m8 ' error:' "${o}.err" >&2; failed=1
        else
            rm -f "${o}.err"
        fi
    done < "$PENDING_OBJS"
    [ "$failed" -eq 0 ] && [ "$job_failed" -eq 0 ] || exit 1
    return 0
}

while IFS=$'\t' read -r kind src extra_str; do
    [ -n "${src:-}" ] || continue
    extra_args=()
    [ -n "${extra_str:-}" ] && read -ra extra_args <<< "$extra_str"
    rel="${src#"$REPO"/}"
    o="$OBJ/${rel//\//_}.o"
    if [ "$kind" = "cxx" ]; then
        compile_one "$src" "$o" "$CXX" "${CFLAGS[@]}" "${extra_args[@]}"
    else
        compile_one "$src" "$o" "${CXX%++}" "${CONLY_FLAGS[@]}" "${extra_args[@]}"
    fi
done < <(engine_sources)

# the executables' own translation units
compile_one "$BRIDGE/tools/contract_tests.cpp" "$OBJ/contract_tests.o" "$CXX" "${CFLAGS[@]}"
compile_one "$BRIDGE/tools/orcapod_slice.cpp" "$OBJ/orcapod_slice.o" "$CXX" "${CFLAGS[@]}"

run_jobs_parallel

# --- link (archive-style) ---
# Linking every raw object would surface undefined references inside members
# nothing uses (dead TUs such as Thread.cpp); packing them into a static
# archive first links only the referenced members, as the mobile builds do.
mapfile -t OBJS < <(grep -v -e 'contract_tests\.o$' -e 'orcapod_slice\.o$' "$MANIFEST")
[ "${#OBJS[@]}" -gt 0 ] || { echo "FATAL: no objects" >&2; exit 1; }
HOST_ARCHIVE="$BUILD/libengine-host.a"
rm -f "$HOST_ARCHIVE"
ar rcs "$HOST_ARCHIVE" "${OBJS[@]}"
LIBS=("${TBB_LIBS[@]}" -lpthread -lm -lgmp -lmpfr -latomic)
"$CXX" -o "$BUILD/contract_tests" "$OBJ/contract_tests.o" "$HOST_ARCHIVE" "${LIBS[@]}"
"$CXX" -o "$BUILD/orcapod-slice" "$OBJ/orcapod_slice.o" "$HOST_ARCHIVE" "${LIBS[@]}"
echo "==> built $BUILD/contract_tests and $BUILD/orcapod-slice" >&2

[ "$RUN_TESTS" -eq 1 ] || exit 0

export ORCAPAD_TEST_PROFILES_DIR="$REPO/resources/profiles"
echo "==> running contract tests" >&2
"$BUILD/contract_tests"
echo "==> running CLI smoke test" >&2
"$BRIDGE/tools/cli-smoke-test.sh" "$BUILD/orcapod-slice"
