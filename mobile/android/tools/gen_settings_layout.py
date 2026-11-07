#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 OrcaPod contributors
"""Extracts the desktop settings tabs' page / group / option layout.

Reads src/slic3r/GUI/Tab.cpp (TabPrint, TabFilament and TabPrinter) and
writes app/src/main/assets/settings_layout.json:

  {"process": [{"title": page, "groups": [{"title": group, "options": [key...]}]}],
   "filament": [...], "printer": [...]}

The app shows its settings editors in this order, so they read like the
desktop tabs. Labels, types and tooltips come from the engine at run time
(orca_config_option_defs); this file carries only the structure. Re-run after
Tab.cpp changes:

  python3 mobile/android/tools/gen_settings_layout.py
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.normpath(os.path.join(HERE, "..", "..", ".."))
TAB = os.path.join(REPO, "src", "slic3r", "GUI", "Tab.cpp")
PUBLISH = os.path.join(REPO, "src", "libslic3r", "PublishSettings.cpp")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "settings_layout.json")

# The functions that build each tab, in page order.
FUNCTIONS = {
    "process": ["void TabPrint::build()"],
    "filament": ["void TabFilament::build()"],
    "printer": [
        "void TabPrinter::build_fff()",
        "PageShp TabPrinter::build_kinematics_page()",
        "void TabPrinter::build_unregular_pages(",
    ],
}

PAGE = re.compile(r'add_options_page\(\s*L\("([^"]+)"\)')
GROUP = re.compile(r'new_optgroup\(\s*L\("([^"]+)"\)')
OPTION_CALL = re.compile(
    r'(?:append_single_option_line|get_option|append_option_line\(\s*optgroup\s*,|create_line_with_widget\([^,]+,)\s*\(?\s*"([a-z0-9_]+)"')
# A key list literal ("machine_max_speed_x",) feeding the loop of the next group.
BARE_KEY = re.compile(r'^\s*"([a-z0-9_]+)",?\s*$')
# A key built per axis: "machine_max_jerk_" + axis.
AXIS_KEY = re.compile(r'"([a-z0-9_]+_)"\s*\+\s*axis\b')


# Groups Tab.cpp fills in a loop over a PublishSettings.cpp list.
LOOPED = {
    "publishable_printer_retraction_options()": "const std::vector<PublishablePrinterOption>& publishable_printer_retraction_options()",
    "publishable_printer_z_hop_options()": "const std::vector<PublishablePrinterOption>& publishable_printer_z_hop_options()",
}


def body(source, signature):
    start = source.find(signature)
    if start < 0:
        sys.exit("not found in Tab.cpp: " + signature)
    i = source.index("{", start)
    depth = 0
    for j in range(i, len(source)):
        if source[j] == "{":
            depth += 1
        elif source[j] == "}":
            depth -= 1
            if depth == 0:
                return source[i:j]
    sys.exit("unbalanced body: " + signature)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def layout(source, signatures, looped):
    pages = []
    pending = []
    for signature in signatures:
        for line in strip_comments(body(source, signature)).splitlines():
            m = BARE_KEY.match(line)
            if m:
                pending.append(m.group(1))
                continue
            m = PAGE.search(line)
            if m:
                pages.append({"title": m.group(1), "groups": []})
                continue
            m = GROUP.search(line)
            if m and pages:
                pages[-1]["groups"].append({"title": m.group(1), "options": pending})
                pending = []
                continue
            if not pages or not pages[-1]["groups"]:
                continue
            options = pages[-1]["groups"][-1]["options"]
            keys = OPTION_CALL.findall(line)
            for prefix in AXIS_KEY.findall(line):
                keys += [prefix + axis for axis in "xyze"]
            for call, keys_of_call in looped.items():
                if call in line:
                    keys = keys_of_call
            for key in (k for k in keys if not k.endswith("_")):
                if key not in options:
                    options.append(key)
    # Merge pages that are rebuilt under the same title (per-extruder pages).
    merged = []
    for page in pages:
        same = next((p for p in merged if p["title"] == page["title"]), None)
        if same is None:
            merged.append(page)
            continue
        for group in page["groups"]:
            g = next((x for x in same["groups"] if x["title"] == group["title"]), None)
            if g is None:
                same["groups"].append(group)
            else:
                g["options"] += [k for k in group["options"] if k not in g["options"]]
    for page in merged:
        page["groups"] = [g for g in page["groups"] if g["options"]]
    merged = [p for p in merged if p["groups"]]
    # Desktop shows Notes last.
    merged.sort(key=lambda p: p["title"] == "Notes")
    return merged


def main():
    source = open(TAB, encoding="utf-8").read()
    publish = open(PUBLISH, encoding="utf-8").read()
    looped = {call: re.findall(r'\{\s*"([a-z0-9_]+)"', body(publish, sig)) for call, sig in LOOPED.items()}
    out = {kind: layout(source, sigs, looped) for kind, sigs in FUNCTIONS.items()}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
        f.write("\n")
    for kind, pages in out.items():
        count = sum(len(g["options"]) for p in pages for g in p["groups"])
        print(f"{kind}: {len(pages)} pages, {count} options")


if __name__ == "__main__":
    main()
