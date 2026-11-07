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

The settings sheet edits a handful of common process and filament options on
top of the selected presets through the engine's edited-preset mechanism, so
slicing picks them up exactly as desktop does; they can be saved as user
presets.

## Screen layout

On a phone the viewport fills the screen and everything else floats over it:
the preset bar at the top, the selected object's controls and the plate
actions at the bottom, and the print settings in a bottom sheet. A window
that is at least 840 dp wide, or at least 600 dp wide in landscape, and at
least 480 dp tall (tablets, unfolded foldables, desktop-mode windows) puts
the viewport next to a fixed side pane instead, so nothing covers the
model: presets on top, an Objects tab (the selected object's controls and a
list of every instance, tap one to select it) or a Settings tab (the same
settings list as the sheet), and the slice controls at the bottom. The
choice follows the window's current size, so it switches on rotation, fold
and split screen. A phone in landscape keeps the phone layout because a
pane under 480 dp tall cannot hold its tabs and controls.

## Viewport

The plate is drawn in engine coordinates (z-up millimetres). Gestures:
a finger on the selected object drags it over a plane at the touched height,
and the move is committed to the engine on release; a finger elsewhere
orbits; two fingers pinch to zoom and pan. Taps are resolved by a CPU ray
cast against the instance meshes, which also gives the tapped face's normal
for "Lay on face". After slicing, the G-code is parsed by the engine's
`GCodeProcessor` (`orca_gcode_toolpath`) and the extrusion moves are drawn as
lines coloured by extrusion role, up to the layer on the slider.

## Limits

- STEP import needs OCCT, which the bridge does not build; the app says so
  and asks for STL or 3MF instead.
- One filament slot: multi-material assignment is not exposed yet.
- G-code goes out through the Android share sheet or a saved file; there is
  no direct printer connection.

## Verification

`mobile/android/tools/host-jni-test.sh` builds the engine for the desktop
host, links the same `orcapod_jni.cpp` into a host JNI library and runs
`EngineHostTest` on the JVM: profiles load, a model imports, moves, undoes
and arranges, settings edit, and a plate slices, previews and cancels.
