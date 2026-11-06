#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 OrcaPod contributors
#
# End-to-end check of orcapod-slice: slices a 20 mm cube on two printers from
# different vendors and checks the G-code is complete, then checks that bad
# input fails loudly with a non-zero exit code.
#
# Usage: cli-smoke-test.sh path/to/orcapod-slice
set -euo pipefail

CLI="$1"
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
PROFILES="$REPO/resources/profiles"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

# 20 mm ASCII STL cube: 12 facets
cube() {
    local s=20
    {
        echo "solid cube"
        while read -r a b c; do
            echo " facet normal 0 0 0"
            echo "  outer loop"
            for v in $a $b $c; do
                IFS=, read -r x y z <<< "$v"
                echo "   vertex $((x * s)) $((y * s)) $((z * s))"
            done
            echo "  endloop"
            echo " endfacet"
        done <<'FACETS'
0,0,0 0,1,0 1,1,0
0,0,0 1,1,0 1,0,0
0,0,1 1,0,1 1,1,1
0,0,1 1,1,1 0,1,1
0,0,0 1,0,0 1,0,1
0,0,0 1,0,1 0,0,1
0,1,0 0,1,1 1,1,1
0,1,0 1,1,1 1,1,0
0,0,0 0,0,1 0,1,1
0,0,0 0,1,1 0,1,0
1,0,0 1,1,0 1,1,1
1,0,0 1,1,1 1,0,1
FACETS
        echo "endsolid cube"
    } > "$1"
}
cube "$WORK/cube.stl"

# printer|process: the process is named so the test does not depend on which
# process a printer's profile picks by default
for pair in "Bambu Lab X1 Carbon 0.4 nozzle|0.20mm Standard @BBL X1C" \
            "Prusa MK4 0.4 nozzle|0.20mm Standard @MK4"; do
    printer="${pair%%|*}"
    process="${pair#*|}"
    out="$WORK/out.gcode"
    rm -f "$out"
    "$CLI" --profiles "$PROFILES" --printer "$printer" --process "$process" -o "$out" \
        "$WORK/cube.stl" 2>"$WORK/log" || { cat "$WORK/log" >&2; fail "slicing on $printer"; }
    [ -s "$out" ] || fail "$printer: no G-code written"
    moves=$(grep -c '^G1 ' "$out" || true)
    [ "$moves" -gt 1000 ] || fail "$printer: only $moves G1 moves"
    # a 20 mm cube at 0.2 mm layers has about 100 layers; Bambu firmware marks
    # them "; CHANGE_LAYER", the others ";LAYER_CHANGE"
    layers=$(grep -cE '^; ?(LAYER_CHANGE|CHANGE_LAYER)$' "$out" || true)
    if [ "$layers" -lt 90 ] || [ "$layers" -gt 110 ]; then fail "$printer: $layers layer changes"; fi
    grep -q '; filament used' "$out" || fail "$printer: G-code has no filament summary"
    echo "ok: $printer ($moves moves, $layers layers)"
done

# two models are arranged side by side and both sliced
"$CLI" --profiles "$PROFILES" --printer "Prusa MK4 0.4 nozzle" --process "0.20mm Standard @MK4" -o "$WORK/two.gcode" \
    "$WORK/cube.stl" "$WORK/cube.stl" 2>"$WORK/log" || { cat "$WORK/log" >&2; fail "two cubes"; }
echo "ok: two cubes arranged"

# failures are loud
if "$CLI" --profiles "$PROFILES" --printer "No Such Printer" -o "$WORK/x.gcode" "$WORK/cube.stl" 2>/dev/null; then
    fail "unknown printer accepted"
fi
if "$CLI" --profiles "$PROFILES" --printer "Prusa MK4 0.4 nozzle" -o "$WORK/x.gcode" "$WORK/missing.stl" 2>/dev/null; then
    fail "missing model accepted"
fi
echo "ok: bad input rejected"
echo "CLI smoke test passed"
