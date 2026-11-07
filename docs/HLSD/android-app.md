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
 ├─ PrinterViewModel      saved printers, uploads, status polling
 │   └─ PrintHost (printhost/)  one class per printer network API, plain JVM
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

## Printers

The app sends sliced G-code to printers on the network, like desktop's
physical printers and print host upload. A saved connection
(`PrinterConnection`, kept in app-private preferences and excluded from
backups because it holds API keys and access codes) names a protocol, an
address and that protocol's login. `PrintHost` is the one interface every
protocol implements: test the connection, upload with or without starting
the print, read the status, pause, resume and cancel. Each implementation
follows the matching desktop class in `src/slic3r/Utils/`:

| Type | Upload | Status and control |
| --- | --- | --- |
| OctoPrint | `POST /api/files/local` form, `print=true` | `/api/job`, `/api/printer` |
| Moonraker | `POST /server/files/upload`, then `/printer/print/start` if Moonraker did not start or queue it | `/printer/objects/query`, `/printer/print/*` |
| PrusaLink | `PUT /api/v1/files/<storage>/<name>` with `Print-After-Upload`, else OctoPrint's form; API key or digest login | `/api/v1/status`, `/api/v1/job/<id>` |
| Duet | `rr_upload` + `M32`, or the SBC's `/machine/file` + `/machine/code`; `M37` simulates | the object model (`rr_model` or `/machine/status`) |
| Bambu Lab LAN | FTPS on port 990, as a `.gcode.3mf`, then MQTT `project_file` | MQTT reports on `device/<serial>/report` |

Desktop reaches Bambu printers through Bambu's closed network plugin, which
cannot run on Android, so `BambuLanHost` speaks the printer's LAN protocol
directly: MQTT 3.1.1 over TLS on port 8883 and implicit FTPS on port 990,
both as user `bblp` with the access code from the printer's screen. Current
firmware starts LAN jobs from a `.gcode.3mf`, so the G-code is wrapped in
the smallest such package (plate 1 and its MD5) before upload. The printer's
FTP server requires the data connection to resume the control connection's
TLS session; TLS session caches are keyed by peer host and port, so the data
socket reports the control port. P1 and A1 printers report only changes, so
the host asks for a full report on connect and merges the deltas. Bambu
printers use self-signed certificates and the access code is the
credential, so their TLS is not verified; HTTP hosts verify certificates
unless the connection allows self-signed ones.

Printers are found by Bambu's UDP announcements on port 2021 and by the
`_octoprint._tcp` and `_moonraker._tcp` mDNS services; others are added by
address. Status is polled every few seconds while a printer sheet is open
(Bambu's MQTT connection stays open and pushes reports instead).

The hosts are plain JVM code over `HttpURLConnection` and sockets, with no
Android or third-party network library, so `HttpHostsTest` and
`BambuLanHostTest` run each against a mock of its printer's API, including a
TLS MQTT broker and an FTPS server that refuses data connections without
session reuse.

## Limits

- STEP import needs OCCT, which the bridge does not build; the app says so
  and asks for STL or 3MF instead.
- One filament slot: multi-material assignment is not exposed yet.
- Bambu printers are reached in LAN mode only; Bambu's cloud needs its
  closed network plugin. The AMS maps the single filament to one tray.

## Verification

`mobile/android/tools/host-jni-test.sh` builds the engine for the desktop
host, links the same `orcapod_jni.cpp` into a host JNI library and runs
`EngineHostTest` on the JVM: profiles load, a model imports, moves, undoes
and arranges, settings edit, and a plate slices, previews and cancels.
