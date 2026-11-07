# Android app

OrcaPod's Android app (`mobile/android/`) prepares and slices prints on a
phone or tablet: it imports models (STL, 3MF, OBJ, AMF and STEP) onto a 3D build plate, lets the user
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

The settings sheet edits a handful of common process and filament options on
top of the selected presets through the engine's edited-preset mechanism, so
slicing picks them up exactly as desktop does; they can be saved as user
presets.

## Viewport

The plate is drawn in engine coordinates (z-up millimetres). Gestures:
a finger on the selected object drags it over a plane at the touched height,
and the move is committed to the engine on release; a finger elsewhere
orbits; two fingers pinch to zoom and pan. Taps are resolved by a CPU ray
cast against the instance meshes, which also gives the tapped face's normal
for "Lay on face". After slicing, the G-code is parsed by the engine's
`GCodeProcessor` (`orca_gcode_toolpath`) and the extrusion moves are drawn as
lines coloured by extrusion role, up to the layer on the slider.

## STEP import

STEP files go through the desktop's STEP import dialog. The file is read
once (`Engine.loadStep`) and the dialog then shows the triangle count for the
chosen linear and angular deflection, recounting as the values change and
cancelling a count that a newer value supersedes. It also offers Split
compound, assembly or single object, Keep original names and Sort parts by
name, with the desktop's coupling: keeping the names of a part that holds
several bodies needs Split compound, and sorting needs kept names. One answer
covers every STEP file of an import, and "Remember my choice" and "Don't ask
again" store the choices as the defaults, like the desktop's
`linear_deflection`, `angle_deflection` and `is_split_compound` preferences.

Reading and meshing a large file take a while, so both show their progress in
the busy row with a Cancel button, which stops the engine work and ends the
import. Once meshed, names that a 3MF project cannot hold open the desktop's
"Unsupported characters in names" dialog: remove the characters or replace
each with some text, with a live preview of every renamed part and object. Its
rule is stored under the desktop's `import_names_cleanup_*` keys; a saved
replacement that is no longer usable opens the dialog again, and a file whose
names are not valid Unicode is not imported. The overflow menu turns either
dialog back on.

## Limits

- One filament slot: multi-material assignment is not exposed yet.
- G-code goes out through the Android share sheet or a saved file; there is
  no direct printer connection.

## Verification

`mobile/android/tools/host-jni-test.sh` builds the engine for the desktop
host, links the same `orcapod_jni.cpp` into a host JNI library and runs
`EngineHostTest` on the JVM: profiles load, a model imports, moves, undoes
and arranges, settings edit, a plate slices, previews and cancels, and a
STEP file loads, counts its triangles, meshes and imports.
