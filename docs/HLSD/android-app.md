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
 ├─ PrinterViewModel      printer screens over PrinterFleet
 │   └─ PrinterFleet (data/)   process-wide: saved printers, one host each, the queue
 │       ├─ PrintQueue (printhost/)  per-printer lines of plates, status of every printer
 │       └─ PrintHost (printhost/)   one class per printer network API, plain JVM
 ├─ QueueService          foreground service while the queue has work
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

Bambu printers need no Bambu account: LAN only mode and the access code are
enough, and the serial number, which names the MQTT topics, is read from the
printer's TLS certificate (its common name) when the user leaves it empty.
Firmware with Bambu's authorization control accepts jobs and commands from
other apps only in Developer mode; the printer reports a refused command on
its report topic, and the app waits briefly for that report after each job,
pause, resume or stop so the refusal reaches the user with that hint. H2
printers report nozzle temperatures per extruder, packed into one value.

Printers are found by Bambu's UDP announcements on port 2021 and by the
`_octoprint._tcp` and `_moonraker._tcp` mDNS services; others are added by
address. Status is polled every few seconds while a printer sheet is open
(Bambu's MQTT connection stays open and pushes reports instead).

`PrinterFleet` holds the saved connections and one open `PrintHost` per
printer for the whole process, so the queue and the printer screens share a
connection (Bambu printers accept only a few MQTT clients). A changed name or
remembered preset keeps the connection; any other change reconnects.

### Print queue

Every send goes through `PrintQueue`, whether to one printer or several. The
sliced G-code is copied once into `files/print-queue/` and each chosen
printer gets its own item pointing at it, so slicing the next plate does not
change what is queued. Items are kept in `print-queue.json`; an upload cut
off by the app's death goes again from the start on the next launch.

Each printer works through its items in order, one at a time, and printers
run in parallel. An upload-only item goes as soon as the printer's earlier
items are sent. An item that starts a print goes only when the printer last
reported idle, finished or cancelled within the last 30 seconds, and not
while it waits for its bed: whenever a print on it ends, the queue's own or
one started elsewhere, and a print is queued next, the finished part is still
on the bed, so the printer is marked as needing its bed cleared and nothing
starts until the user confirms. Sending a plate to a printer with nothing else
queued counts as that confirmation.

A started item follows the print: once the printer has reported it running,
a later finished or idle report ends it as finished, cancelled as cancelled
and an error as failed. Printers can report the previous job's end, or idle,
for a while after a start, so those reports count only after the print was
seen running; one never seen running within ten minutes ends as finished
with a note. Outcomes go into `PrintLog` like any other send.

The queue polls the printers that have open items every 10 seconds, and every
saved printer every 3 seconds while the dashboard is open. The dashboard is
a grid of printer cards, one column on a phone and more on a tablet, each
with the printer's status, pause, resume, stop, camera and its line of plates
(reorder, cancel, retry, remove). Printers that need someone come first.

While items are open, `QueueService` runs as a foreground service of type
`connectedDevice` (printers on the local network; no daily time limit) with a
partial wake lock, renewed as items change, so uploads and polling go on with
the screen off. It shows a summary notification and stops when nothing is
left. Prints that end or fail and beds to clear raise an alert that opens the
dashboard. Android 13 and later ask for the notification permission on the
first send.

### Camera and history

`PrintHost.camera()` describes the printer's camera as either JPEG frames
(`CameraFeed`) or an RTSP stream. OctoPrint (`/api/settings`) and Moonraker
(`/server/webcams/list`) publish MJPEG stream and snapshot URLs, often as
paths: OctoPrint's resolve against its own origin like the browser page
would, Moonraker's against the host's default port, where Mainsail or Fluidd
serve the webcam rather than Moonraker's API port. MJPEG is split into frames
by the JPEG start and end markers, so parts with or without a length work; a
snapshot URL alone is polled. Bambu P1 and A1 printers send JPEG frames over
TLS on port 6000 after an 80-byte login; X1 and H2 printers stream H.264 over
RTSPS on port 322, which Media3's RTSP player plays over a TLS socket
factory. Which one a Bambu printer has is told by whether port 322 is open.
A camera URL on the connection overrides all of this for any printer.

Only Moonraker keeps a job history the app can read
(`/server/history/list`). So that every printer has one, `PrintLog` records
each job the app sends, and while a printer is watched, the entry of the job
it is printing follows the print to finished, cancelled or failed, matched by
file name stem (printers report "cube", "cube.gcode" or "cube.gcode.3mf").

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
- Prints the app did not send appear in its history only for Klipper.
- The queue sends one plate per item; it cannot split a plate across
  printers or pick a free printer by itself, and it never starts a print on
  a bed it saw a print end on without the user's confirmation.

## Verification

`mobile/android/tools/host-jni-test.sh` builds the engine for the desktop
host, links the same `orcapod_jni.cpp` into a host JNI library and runs
`EngineHostTest` on the JVM: profiles load, a model imports, moves, undoes
and arranges, settings edit, and a plate slices, previews and cancels.
