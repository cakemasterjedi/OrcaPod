#!/usr/bin/env bash
# The variables set here are read by the scripts that source this file.
# shellcheck disable=SC2034
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 OrcaPad contributors
# Copyright (c) 2026 OrcaPod contributors
#
# Incremental, parallel compile machinery shared by the engine builds. Taken
# from build-host.sh (itself mirroring OrcaPad's build-core.sh). Source it
# after engine-sources.sh, which sets REPO and BRIDGE.

# engine_gen_headers <gen dir>: the generated version headers and the libnoise
# include alias. The ORCA version comes from version.inc, as the CMake build
# reads it.
engine_gen_headers() {
    local gen="$1" vpath
    _inc_var() { sed -n "s/^set($1 \"\(.*\)\")/\1/p" "$REPO/version.inc" | head -1; }
    local slic3r_version softfever_version
    slic3r_version="$(_inc_var SLIC3R_VERSION)"
    softfever_version="$(_inc_var SoftFever_VERSION)"
    mkdir -p "$gen/libslic3r"
    # libnoise is addressed as <libnoise/noise.h>; expose it via the gen/ tree.
    ln -sfn "$BRIDGE/cache/Orca-deps-libnoise/src" "$gen/libnoise"
    for vpath in "$gen/libslic3r_version.h" "$gen/libslic3r/libslic3r_version.h"; do
        sed -e "s/@SLIC3R_VERSION@/$slic3r_version/g" \
            -e "s/@SoftFever_VERSION@/$softfever_version/g" \
            -e "s/@SLIC3R_BUILD_ID@/OrcaPod-$softfever_version/g" \
            -e 's/@SLIC3R_APP_KEY@/OrcaSlicer/g' \
            -e 's/@SLIC3R_APP_NAME@/OrcaSlicer/g' \
            -e 's/@BBL_INTERNAL_TESTING@/0/g' \
            -e 's/@ORCA_CHECK_GCODE_PLACEHOLDERS@/0/g' \
            "$REPO/src/libslic3r/libslic3r_version.h.in" > "$vpath.tmp"
        if [ ! -f "$vpath" ] || ! cmp -s "$vpath.tmp" "$vpath"; then mv "$vpath.tmp" "$vpath"; else rm -f "$vpath.tmp"; fi
    done
}

# compile_init <build dir> <everything that should force a full rebuild when
# it changes: compilers and flags>. Sets OBJ and MANIFEST.
compile_init() {
    local build="$1"; shift
    OBJ="$build/obj"
    MANIFEST="$build/objects.txt"
    JOBS="$build/jobs.sh"
    PENDING_OBJS="$build/pending-objs.txt"
    : > "$MANIFEST"; : > "$JOBS"; : > "$PENDING_OBJS"
    PENDING=0
    declare -gA SEEN_OBJS=()
    local hash
    hash="$(printf '%s\n' "$@" | sha256sum | cut -c1-16)"
    if [ -f "$build/flags.sha" ] && [ "$(cat "$build/flags.sha")" != "$hash" ]; then
        echo "==> flags/toolchain changed — full rebuild" >&2
        rm -rf "$OBJ"
    fi
    mkdir -p "$OBJ"
    echo "$hash" > "$build/flags.sha"
}

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
    local p=0 job_failed=0 cmd
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
