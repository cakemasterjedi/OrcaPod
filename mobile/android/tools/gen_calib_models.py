#!/usr/bin/env python3
"""Builds the app's calibration models from desktop's resources/calib.

The mobile engine has no Draco decoder, so the Draco (.drc) test models are
decoded here into plain 3MF meshes, the same vertices and triangles desktop's
load_drc reads, named as desktop names them. The 3MF models are copied as
they are. Needs DracoPy (pip install DracoPy).

Usage: gen_calib_models.py [repo root]
"""
import shutil
import sys
import zipfile
from pathlib import Path

import DracoPy

MODELS = {
    "pressure_advance/pressure_advance_test.drc": "pa_line.3mf",
    "pressure_advance/tower_with_seam.drc": "pa_tower.3mf",
    "temperature_tower/temperature_tower.drc": "temperature_tower.3mf",
    "volumetric_speed/SpeedTestStructure.drc": "volumetric_speed.3mf",
    "vfa/vfa.drc": "vfa.3mf",
    "retraction/retraction_tower.drc": "retraction_tower.3mf",
    "input_shaping/ringing_tower.drc": "ringing_tower.3mf",
    "input_shaping/fast_tower_test.drc": "fast_tower.3mf",
    "cornering/SCV-V2.drc": "scv_v2.3mf",
    "filament_flow/flowrate-test-pass1.3mf": "flowrate_pass1.3mf",
    "filament_flow/flowrate-test-pass2.3mf": "flowrate_pass2.3mf",
    "filament_flow/Orca-LinearFlow.3mf": "flowrate_yolo.3mf",
    "filament_flow/Orca-LinearFlow_fine.3mf": "flowrate_yolo_fine.3mf",
}


CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
 <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
 <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>
"""

RELS = """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>
"""


def write_3mf(mesh, name: str, path: Path) -> None:
    points = mesh.points.reshape(-1, 3)
    faces = mesh.faces.reshape(-1, 3)
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">',
        " <resources>",
        f'  <object id="1" type="model" name="{name}">',
        "   <mesh>",
        "    <vertices>",
    ]
    lines += [f'     <vertex x="{p[0]:.6g}" y="{p[1]:.6g}" z="{p[2]:.6g}"/>' for p in points]
    lines += ["    </vertices>", "    <triangles>"]
    lines += [f'     <triangle v1="{f[0]}" v2="{f[1]}" v3="{f[2]}"/>' for f in faces]
    lines += ["    </triangles>", "   </mesh>", "  </object>", " </resources>",
              ' <build>', '  <item objectid="1"/>', ' </build>', "</model>", ""]
    # Fixed entry dates keep the output identical from run to run.
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, text in (("[Content_Types].xml", CONTENT_TYPES), ("_rels/.rels", RELS),
                           ("3D/3dmodel.model", "\n".join(lines))):
            z.writestr(zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)), text,
                       compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[3])
    source = root / "resources" / "calib"
    target = root / "mobile" / "android" / "app" / "src" / "main" / "assets" / "calib"
    target.mkdir(parents=True, exist_ok=True)
    for name, out in MODELS.items():
        if name.endswith(".drc"):
            write_3mf(DracoPy.decode((source / name).read_bytes()), Path(name).name, target / out)
        else:
            shutil.copyfile(source / name, target / out)
        print(f"{name} -> {out}")


if __name__ == "__main__":
    main()
