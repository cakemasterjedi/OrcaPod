# Android app

OrcaPod's Android app (`mobile/android/`) prepares and slices prints on a
phone or tablet: it imports models onto a 3D build plate, lets the user
position them, selects printer, filament and process profiles, slices with
OrcaSlicer's own engine and previews and exports the G-code. All slicing
logic is the engine's; the app is a client of the bridge's C API
([mobile-bridge.md](mobile-bridge.md)).

## Layers

```
Compose UI (ui/)          screens, sheets, panels; state in AppViewModel
 ├─ PlateView (ui/viewport)  GLSurfaceView: OpenGL ES 3 plate, gestures, picking
 └─ Engine (engine/)      Kotlin session over one presets bundle + one plater
     └─ Native / orcapod_jni.cpp   JNI: one function per bridge call
         └─ liborcapod.so  the engine and bridge, from build-android.sh
```

`liborcapod.so` is built outside Gradle by `mobile/bridge/build-android.sh`:
it is a long C++ build with its own pinned dependencies, and the app only
needs its C API. Gradle compiles the thin JNI library with CMake, links it
against the prebuilt bridge (`orcapod.bridgeDir`) and packages both.

## Engine session

The bridge serializes engine work process-wide and slicing blocks for
seconds to minutes, so `Engine` runs every bridge call on one dedicated
thread with a large stack. Only cancelling a slice runs on the caller's
thread, which the bridge allows. Every failed call surfaces as an
`OrcaException` carrying the bridge's status code; the UI branches on the
code, never on the message.

The document lives in the engine (`orca_plater`): every edit is an engine
mutation with its own undo step, and the UI re-reads a snapshot afterwards.
Snapshots deep-copy meshes, so the JNI layer skips the positions and indices
of every object whose mesh revision the app already holds, and `Engine`
reuses its cached copy. The viewport renders each instance from the engine's
own instance matrix rather than re-deriving it from Euler angles.

## Profiles

OrcaSlicer's `resources/profiles` tree is zipped into the APK's assets at
build time. On first launch the user picks the printer brands they own, and
only those vendors (plus the shared filament library and vendor-independent
files) are extracted into the engine's data directory, which keeps the
printer list short and preset loading fast, like the desktop setup wizard.
After an app update the same brands are re-extracted, since the bundled
profiles may have changed. User presets live beside them in `user/default`
and survive both.

## Settings editors

The settings screen is desktop's three tabs (process, filament, printer)
with every option, in desktop's pages and groups. The structure comes from
`assets/settings_layout.json`, which `tools/gen_settings_layout.py` extracts
from the desktop tab builders in `src/slic3r/GUI/Tab.cpp`, so the app does not
keep its own copy of the option list. Labels, types, units, ranges, enum
choices, tooltips and visibility levels come from the engine at run time
(`orca_config_option_defs`, the same `ConfigOptionDef`s desktop renders),
so options added upstream appear with their real metadata once they are in
the layout. A Simple / Advanced / Expert switch filters by the option's
mode, and search reaches every level, as on desktop.

Edits go to the engine's edited presets (`orca_presets_set_value`), so slicing
picks them up exactly as desktop does. A value is "modified" when the edited
preset differs from the saved one (`orca_presets_config_dump`, both copies);
it can be reset per option or per tab and saved as a user preset.

The same option editor serves per-object and per-part settings: the object
sheet lists the target's overrides (`orca_plater_settings_dump`) and adds any
key the engine accepts for that target (`orca_plater_settings_keys`:
object and region options for objects, region options for parts and
modifiers), seeded with the value slicing would otherwise use.

## Objects, parts and plates

The object list and object sheet expose desktop's object-list features
through the bridge: parts, negative parts, modifiers and support
blockers/enforcers (built-in shapes or loaded files) with type changes,
renames and deletes; per-part filament assignment; height range modifiers
(layer height per Z band); split, mirror, unit conversion, simplify,
subdivide, copies, clone and fill plate. The scene snapshot carries every
volume: parts are triangle ranges of the object's merged mesh, so the
viewport colours each part by its filament without a second copy of the
geometry, and the other roles carry their own meshes, drawn translucent over
the parts like desktop.

Plates are desktop's plate tabs: add, rename, lock, duplicate, reorder,
delete and per-plate settings (plate type, sequence, spiral vase, first-layer
filament order). Slicing works on the selected plate or every plate into one
file; the preview shows a single plate, offset to where it sits beside the
bed. Projects save as OrcaSlicer 3MF with plates and settings, and sliced
3MF files (G-code inside a 3MF) export per plate or for all plates.

Filament slots (preset and colour per slot) follow desktop's sidebar list;
objects and parts take a slot through the "extruder" option, which slicing
maps to a tool.

## Viewport

The plate is drawn in engine coordinates (z-up millimetres). Gestures:
a finger on the selected object drags it over a plane at the touched height,
and the move is committed to the engine on release; a finger elsewhere
orbits; two fingers pinch to zoom and pan. Taps are resolved by a CPU ray
cast against the instance meshes, which also gives the tapped face's normal
for "Lay on face".

## G-code preview

After slicing, the engine's `GCodeProcessor` parses the G-code
(`orca_gcode_toolpath`). The JNI layer keeps every extrusion, travel and wipe
as a line segment in file order, grouped by layer, with the attributes
desktop's colour schemes use (speed, actual speed, height, width, fan,
temperature, flow, layer time, acceleration, jerk, pressure advance).
Retractions, seams, tool changes, pauses and custom G-code become point
markers. Layers without extrusions are dropped.

`ToolpathGeometry` (`ui/viewport/ToolpathStyle.kt`) turns a toolpath and a
`PreviewStyle` into one line buffer and one point buffer. It does no GL work,
so the host tests cover it. The style is the colour scheme, the hidden
extrusion roles and the shown options. Range schemes use libvgcode's palette
and its linear or logarithmic interpolation. The ranges are computed over the
shown extrusions, without travels, as desktop computes them. Each segment
records how many visible segments come before it, so the layer range slider
and the move slider of the top layer each map to a single draw call.

The legend gives the time and share of each line type (tap a type to hide
it), the value scale for range schemes, or the filament used per slot. The
summary gives normal and silent time, travel time, and filament length,
weight and cost per slot.

Per-layer custom G-code follows desktop's layer slider:
- Add a pause, custom G-code or, with several filaments, a filament change
  before the top layer shown (`orca_plater_custom_gcode_*`).
- The shown slice stays until the next one, which applies the changes.

## Limits

- STEP import needs OCCT, which the bridge does not build; the app says so
  and asks for STL or 3MF instead.
- G-code goes out through the Android share sheet or a saved file; there is
  no direct printer connection.

## Verification

`mobile/android/tools/host-jni-test.sh` builds the engine for the desktop
host, links the same `orcapod_jni.cpp` into a host JNI library and runs
`EngineHostTest` on the JVM: profiles load, a model imports, moves, undoes
and arranges, settings edit, and a plate slices, previews and cancels. It
also checks that every option of the shipped settings layout is known to the
engine, and exercises parts and modifiers, per-object and per-part settings,
height ranges, plates, filament slots, slicing all plates and the project,
sliced-3MF and STL exports. The preview checks cover travels, markers,
attributes and layer heights, hidden roles, ranges and slider draw ranges, and
a pause and filament change that reach the sliced G-code.
