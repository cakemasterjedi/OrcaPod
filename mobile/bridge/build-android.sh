#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 OrcaPod contributors
#
# Cross-compiles the headless engine and its C bridge for Android with the NDK,
# from the same compile list as the host build (engine-sources.sh):
#
#   build-android/<abi>/liborcapod.so    the engine + bridge as one shared
#                                        library for the app's jniLibs/; it
#                                        exports only the orca_* C API and
#                                        links libc++ statically
#   build-android/<abi>/orcapod-slice    the command-line slicer, linked
#                                        statically so it also runs under
#                                        qemu-user on a Linux host
#
# The engine runs multithreaded on oneTBB, cross-built with its own CMake
# project and the NDK's toolchain file; ORCAPOD_TBB=serial swaps in the
# single-threaded stand-in from shim-tbb-serial/ instead.
#
# GMP and MPFR (needed by CGAL) are built from source for the target. Boost,
# CGAL, Eigen and the other build-only sources are shared with the host build
# in mobile/bridge/cache/.
#
# Usage: build-android.sh [--test]
#   --test   run the CLI smoke test on the Android binary through qemu-aarch64
#
# Environment:
#   ANDROID_NDK_HOME (or ANDROID_NDK_ROOT)  the NDK, r26 or newer
#   ORCAPOD_ANDROID_ABI    arm64-v8a (default) or x86_64
#   ORCAPOD_ANDROID_API    minimum API level (default 26)
#   ORCAPOD_JOBS           parallel compile jobs (default 6)
#   ORCAPOD_TBB            real (default, oneTBB) or serial
#   GMP_URL, MPFR_URL      override the source download (for mirrors); the
#                          SHA-256 check applies only to the default URLs
set -euo pipefail

RUN_TESTS=0
[ "${1:-}" = "--test" ] && RUN_TESTS=1

source "$(dirname "$0")/engine-sources.sh"   # sets BRIDGE, REPO and the dep pins
source "$BRIDGE/compile-lib.sh"
engine_fetch_deps

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
[ -n "$NDK" ] && [ -d "$NDK/toolchains/llvm/prebuilt" ] ||
    { echo "FATAL: set ANDROID_NDK_HOME to an Android NDK (r26 or newer)" >&2; exit 1; }
ABI="${ORCAPOD_ANDROID_ABI:-arm64-v8a}"
API="${ORCAPOD_ANDROID_API:-26}"
case "$ABI" in
    arm64-v8a) TRIPLE=aarch64-linux-android ;;
    x86_64)    TRIPLE=x86_64-linux-android ;;
    *) echo "FATAL: unsupported ABI $ABI (arm64-v8a or x86_64)" >&2; exit 1 ;;
esac
TC="$(echo "$NDK"/toolchains/llvm/prebuilt/*)"
CC="$TC/bin/$TRIPLE$API-clang"
CXX="$CC++"
AR="$TC/bin/llvm-ar"
STRIP="$TC/bin/llvm-strip"

SRC="$REPO/src"
DEPS="$REPO/deps_src"
BUILD="$BRIDGE/build-android/$ABI"
PREFIX="$BUILD/prefix"
mkdir -p "$BUILD/gen/libslic3r" "$PREFIX"

# --- GMP and MPFR for the target (OrcaSlicer's deps/ pins) ---
GMP_VER=6.3.0
MPFR_VER=4.2.2
build_autotools_dep() { # <name> <url> <sha256|-> <sentinel lib> [configure args...]
    local name="$1" url="$2" sha="$3" lib="$4"; shift 4
    [ -f "$PREFIX/lib/$lib" ] && return 0
    local dl="$BRIDGE/cache/$(basename "$url")"
    if [ ! -f "$dl" ]; then
        echo "==> downloading $(basename "$url") into mobile/bridge/cache/" >&2
        curl -fsSL "$url" -o "$dl.part" && mv "$dl.part" "$dl"
    fi
    if [ "$sha" != "-" ] && ! echo "$sha  $dl" | sha256sum -c --quiet -; then
        echo "FATAL: $dl does not match its pinned SHA-256" >&2; rm -f "$dl"; return 1
    fi
    local work="$BUILD/$name-src"
    rm -rf "$work"; mkdir -p "$work"
    tar -xf "$dl" -C "$work" --strip-components=1
    # Debian's DFSG source tarballs (a common mirror) drop doc/, which
    # configure still lists; stand in an empty one.
    if [ ! -f "$work/doc/Makefile.in" ] && grep -q 'doc/Makefile' "$work/configure"; then
        mkdir -p "$work/doc"
        printf 'all install install-strip uninstall clean distclean check:\n\t@:\n' > "$work/doc/Makefile.in"
    fi
    echo "==> building $name for $ABI" >&2
    (cd "$work" &&
        ./configure --host="$TRIPLE" --prefix="$PREFIX" --enable-static --disable-shared \
            --with-pic CC="$CC" AR="$AR" RANLIB="$TC/bin/llvm-ranlib" "$@" > configure.log 2>&1 &&
        make -j"${ORCAPOD_JOBS:-6}" > make.log 2>&1 &&
        make install > install.log 2>&1) ||
        { echo "FATAL: $name build failed, see $work/*.log" >&2; return 1; }
}
DEFAULT_GMP_URL="https://ftp.gnu.org/gnu/gmp/gmp-$GMP_VER.tar.xz"
DEFAULT_MPFR_URL="https://ftp.gnu.org/gnu/mpfr/mpfr-$MPFR_VER.tar.bz2"
GMP_SHA=a3c2b80201b89e68616f4ad30bc66aee4927c3ce50e33929ca819d5c43538898
MPFR_SHA=9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b
[ "${GMP_URL:-$DEFAULT_GMP_URL}" = "$DEFAULT_GMP_URL" ] || GMP_SHA=-
[ "${MPFR_URL:-$DEFAULT_MPFR_URL}" = "$DEFAULT_MPFR_URL" ] || MPFR_SHA=-
build_autotools_dep gmp "${GMP_URL:-$DEFAULT_GMP_URL}" "$GMP_SHA" libgmp.a
build_autotools_dep mpfr "${MPFR_URL:-$DEFAULT_MPFR_URL}" "$MPFR_SHA" libmpfr.a --with-gmp="$PREFIX"

case "${ORCAPOD_TBB:-real}" in
    real)
        # oneTBB 2021.5 strips "-Werror" from the compile flags when not
        # strict, which mangles the NDK's -Werror=format-security; the NDK
        # option turns that flag into -Wno-error=format-security instead.
        TBB_PREFIX="$(engine_build_tbb "$BUILD" \
            -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
            -DANDROID_DISABLE_FORMAT_STRING_CHECKS=ON)"
        TBB_FLAGS=(-isystem "$TBB_PREFIX/include")
        TBB_LIBS=("$TBB_PREFIX/lib/libtbb.a" "$TBB_PREFIX/lib/libtbbmalloc.a")
        ;;
    serial)
        TBB_FLAGS=(-I"$BRIDGE/shim-tbb-serial")
        TBB_LIBS=()
        ;;
    *) echo "FATAL: ORCAPOD_TBB must be real or serial" >&2; exit 1 ;;
esac

# --- compile flags: the host build's, plus the target's GMP/MPFR ---
# -fsigned-char: char is unsigned on Arm Linux/Android but upstream assumes
# signed (BuildVolume_Type : char holds -1); OrcaSlicer's own CMake build passes
# it too. BOOST_FILESYSTEM_NO_CXX20_ATOMIC_REF: the NDK's libc++ has no
# std::atomic_ref yet, so Boost.Filesystem falls back to boost::atomic_ref, as
# its CMake configure check would choose.
# STEP import: OpenCASCADE cross-built with the NDK toolchain.
case "$ORCAPOD_STEP" in
    real)
        OCCT_PREFIX="$(engine_build_occt "$BUILD" \
            -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
            -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static)"
        OCCT_FLAGS=(-isystem "$OCCT_PREFIX/include/occt")
        OCCT_LIBS=(-Wl,--start-group)
        for tk in "${OCCT_TOOLKITS[@]}"; do OCCT_LIBS+=("$OCCT_PREFIX/lib/lib$tk.a"); done
        OCCT_LIBS+=(-Wl,--end-group)
        ;;
    stub)
        OCCT_FLAGS=(-I"$BRIDGE/shim/occt-stub" -DORCAPOD_STEP_STUB)
        OCCT_LIBS=()
        ;;
esac

CFLAGS=(
    -std=c++17 -O2 -DNDEBUG -fPIC -fsigned-char -Wno-deprecated-declarations
    -DBOOST_FILESYSTEM_NO_CXX20_ATOMIC_REF
    # ORDER MATTERS: the portable md5 shim (shim-host) first, then the shared
    # platform shims (boost/log, boost/nowide, occt stub) and TBB, and only
    # then the real Boost headers.
    -I"$BRIDGE/shim-host"
    -I"$BRIDGE/shim" "${OCCT_FLAGS[@]}"
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
    -isystem "$PREFIX/include"
    -isystem "$BOOST_INC" -isystem "$CGAL_INC" -isystem "$EIGEN_INC"
    -I"$BRIDGE/engine-cxx"
    -DBOOST_ALL_NO_LIB -DBOOST_FILESYSTEM_NO_DEPRECATED
    -DSLIC3R_APP_KEY='"OrcaSlicer"' -DSLIC3R_APP_NAME='"OrcaSlicer"'
)
CONLY_FLAGS=(
    -O2 -DNDEBUG -fPIC -fsigned-char -Wno-deprecated-declarations
    -include limits.h
    -I"$DEPS" -I"$DEPS/qhull/src" -I"$DEPS/glu-libtess/include"
    -I"$DEPS/libigl" -I"$BRIDGE/cache/Orca-deps-libnoise/src"
    -I"$DEPS/libnest2d/include"
    -I"$DEPS/clipper2/Clipper2Lib/include" -I"$DEPS/expat"
    -I"$DEPS/miniz"
)

engine_gen_headers "$BUILD/gen"
compile_init "$BUILD" "$CXX" "${CFLAGS[@]}" "${CONLY_FLAGS[@]}"

while IFS=$'\t' read -r kind src extra_str; do
    [ -n "${src:-}" ] || continue
    extra_args=()
    [ -n "${extra_str:-}" ] && read -ra extra_args <<< "$extra_str"
    rel="${src#"$REPO"/}"
    o="$OBJ/${rel//\//_}.o"
    if [ "$kind" = "cxx" ]; then
        compile_one "$src" "$o" "$CXX" "${CFLAGS[@]}" "${extra_args[@]}"
    else
        compile_one "$src" "$o" "$CC" "${CONLY_FLAGS[@]}" "${extra_args[@]}"
    fi
done < <(engine_sources)
compile_one "$BRIDGE/tools/orcapod_slice.cpp" "$OBJ/orcapod_slice.o" "$CXX" "${CFLAGS[@]}"

run_jobs_parallel

# --- link ---
# Archive first so only referenced members are linked (see build-host.sh).
mapfile -t OBJS < <(grep -v -e 'orcapod_slice\.o$' "$MANIFEST")
ARCHIVE="$BUILD/libengine.a"
rm -f "$ARCHIVE"
"$AR" rcs "$ARCHIVE" "${OBJS[@]}"
LIBS=("${OCCT_LIBS[@]}" "${TBB_LIBS[@]}" "$PREFIX/lib/libmpfr.a" "$PREFIX/lib/libgmp.a" -ldl -lm)

# The app loads one self-contained library: libc++ is linked in statically and
# only the bridge's C API is exported. --whole-archive keeps every orca_*
# entry point, since nothing inside the library calls most of them.
"$CXX" -shared -o "$BUILD/liborcapod.so" -static-libstdc++ \
    -Wl,--whole-archive "$OBJ/mobile_bridge_engine-cxx_orca_bridge.cpp.o" -Wl,--no-whole-archive \
    "$ARCHIVE" "${LIBS[@]}" \
    -Wl,--version-script="$BRIDGE/android/exports.map" \
    -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1
"$STRIP" --strip-unneeded "$BUILD/liborcapod.so"

"$CC" -fno-emulated-tls -c -o "$OBJ/tls_align.o" "$BRIDGE/android/tls_align.c"
"$CXX" -static -o "$BUILD/orcapod-slice" "$OBJ/orcapod_slice.o" "$OBJ/tls_align.o" "$ARCHIVE" "${LIBS[@]}"
echo "==> built $BUILD/liborcapod.so and $BUILD/orcapod-slice" >&2

[ "$RUN_TESTS" -eq 1 ] || exit 0

case "$ABI" in
    arm64-v8a) QEMU=qemu-aarch64 ;;
    x86_64)    QEMU= ;;
esac
if [ -n "$QEMU" ] && ! command -v "$QEMU" > /dev/null; then
    echo "FATAL: --test needs $QEMU (apt install qemu-user)" >&2; exit 1
fi
RUNNER="$BUILD/orcapod-slice-run"
# libc++ on Android falls back to /data/local/tmp when TMPDIR is unset; on a
# host that path does not exist (an app sets TMPDIR to its cache dir).
printf '#!/bin/sh\nTMPDIR="${TMPDIR:-/tmp}" exec %s %q "$@"\n' "$QEMU" "$BUILD/orcapod-slice" > "$RUNNER"
chmod +x "$RUNNER"
echo "==> running CLI smoke test on the $ABI binary" >&2
"$BRIDGE/tools/cli-smoke-test.sh" "$RUNNER"
