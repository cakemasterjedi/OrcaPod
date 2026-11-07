#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 OrcaPod contributors
#
# Runs the app's engine layer (Engine.kt + the JNI library) on the desktop JVM
# against a host build of the engine, so the JNI code is exercised without a
# device: profiles load, a model imports and moves, and a plate slices and
# previews (app/src/test/.../EngineHostTest.kt).
#
#   1. builds the engine for the host, position-independent and with the
#      serial TBB stand-in, into mobile/bridge/build-host-pic/
#      (mobile/bridge/build-host.sh with its usual prerequisites)
#   2. links liborcapod_jni.so for the host from app/src/main/cpp/orcapod_jni.cpp
#   3. runs the unit test with that library on java.library.path
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"          # mobile/android
BRIDGE="$(cd "$HERE/../bridge" && pwd)"
REPO="$(cd "$BRIDGE/../.." && pwd)"
BUILD="$BRIDGE/build-host-pic"
OUT="$HERE/app/build/host-jni"
mkdir -p "$BUILD/bin" "$OUT"

# build-host.sh takes a single compiler word, so -fPIC goes in through wrappers.
REAL_CXX="${CXX:-clang++}"
printf '#!/bin/sh\nexec %s -fPIC "$@"\n' "$REAL_CXX" > "$BUILD/bin/cc-pic++"
printf '#!/bin/sh\nexec %s -fPIC "$@"\n' "${REAL_CXX%++}" > "$BUILD/bin/cc-pic"
chmod +x "$BUILD/bin/cc-pic++" "$BUILD/bin/cc-pic"

CXX="$BUILD/bin/cc-pic++" ORCAPOD_TBB=serial ORCAPOD_HOST_BUILD_DIR="$BUILD" "$BRIDGE/build-host.sh"

# OpenCASCADE for STEP import, as build-host.sh linked it (shared by every host build).
source "$BRIDGE/engine-sources.sh"
OCCT_LIBS=()
if [ "$ORCAPOD_STEP" = real ]; then
    OCCT_LIBS=(-Wl,--start-group)
    for tk in "${OCCT_TOOLKITS[@]}"; do OCCT_LIBS+=("$BRIDGE/build-host/occt-install/lib/lib$tk.a"); done
    OCCT_LIBS+=(-Wl,--end-group)
fi

JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
"$REAL_CXX" -std=c++17 -O1 -fPIC -shared -o "$OUT/liborcapod_jni.so" \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$BRIDGE/engine-cxx" \
    "$HERE/app/src/main/cpp/orcapod_jni.cpp" \
    "$BUILD/libengine-host.a" "${OCCT_LIBS[@]}" \
    -lgmp -lmpfr -lpthread -ldl -latomic

cd "$HERE"
./gradlew :app:testDebugUnitTest --tests '*EngineHostTest*' \
    -Porcapod.hostJniDir="$OUT" -Porcapod.profilesDir="$REPO/resources/profiles" "$@"
