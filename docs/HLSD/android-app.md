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
printer list short, like the desktop setup wizard.
After an app update the same brands are re-extracted, since the bundled
profiles may have changed. User presets live beside them in `user/default`
and survive both.

### Backup and sync

User presets can leave the phone two ways, both working on the files in
`user/default/{machine,filament,process}` rather than through the engine, so
the engine only has to reload its presets afterwards (unsaved edits are
dropped then).

- **Backup file** (`ProfileBackup`): a zip with `printer/`, `filament/` and
  `process/` folders, the layout of desktop's preset bundle export. It has no
  `bundle_structure.json` on purpose: with one, desktop imports the presets
  as a read-only local bundle instead of editable user presets. Import takes
  desktop's `.orca_printer`, `.orca_filament` and `.zip` exports or a single
  preset JSON, classifies each file by its `*_settings_id` key, `type` or
  folder, names it after its `name` key (the engine loads a user preset under
  its file name), and replaces a preset of the same name.
- **Sync folder** (`ProfileSync`, `SafProfileFolder`): a folder picked with
  the Storage Access Framework, kept with a persistable permission, that a
  cloud or sync app mirrors to other devices. Presets go in an
  `OrcaPod profiles` subfolder, or straight into the picked folder when it
  already looks like a `user/default`. Sync is a three-way merge against the
  content hashes recorded at the last sync, so a deletion on one side is
  carried to the other instead of being undone. A preset changed on both
  sides keeps the newer file, and the older one is written to the folder's
  `conflicts/` so nothing is lost. It runs when the engine opens, after the
  user saves or deletes a preset, and on demand.

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
bed. Sliced 3MF files (G-code inside a 3MF) export per plate or for all
plates.

## Projects

A project is an OrcaSlicer 3MF written by the engine's own project writer
(`store_bbs_3mf`): objects, parts and modifiers with their transforms,
per-object and per-part settings, height ranges, painting, plates with their
settings, and the selected printer, filament and process with their unsaved
edits. Desktop OrcaSlicer opens the same file, and opening one replaces the
plate and adopts its settings (`load_config_model`).

The app keeps desktop's project workflow around that: the title shows the
project name with a `*` while the plate or settings differ from the last
save; "Save" writes back to the same document and "Save as" picks a new one;
replacing an unsaved plate asks first; and a recent list remembers each
project with its picture. The picture is the first plate drawn by a small
software renderer (`PlateThumbnail`, no GL context needed) and is stored in
the 3MF as desktop's `Metadata/plate_1.png`. Documents come from the Storage
Access Framework, so the app keeps a persistable permission on each one; a
document it can only read (one shared from another app) opens, but saving it
asks where to write.

Android may kill a backgrounded app, so when the activity stops with unsaved
changes the app writes a recovery copy of the project to its private storage
(desktop's project backup). The next start offers to restore it; it comes
back still marked unsaved. Importing a 3MF that carries project settings asks,
like desktop, whether to open it as a project or import only its objects.

Filament slots (preset and colour per slot) follow desktop's sidebar list;
objects and parts take a slot through the "extruder" option, which slicing
maps to a tool.

## Cut

The cut tool is desktop's cut gizmo:
- A plane through the selected instance, at a height and with a tilt about
  X and Y, drawn translucent over the objects.
- Keep options: upper part (flipped onto the cut face), lower part, both as
  parts, or both as objects.
- Plug, dowel or snap connectors, placed by tapping the plane.

Connectors stay in plane coordinates while the plane moves, as on desktop.
On "Perform cut" they become the engine's cut connectors, stored as desktop
stores them (the world point minus the instance offset). The cut
(`orca_plater_object_cut`) then drills them into both faces in one undo step.

## Painting

The painter is desktop's painter gizmos on one object. Each layer is a
`FacetsAnnotation` that slicing reads:
- support enforcers and blockers
- seam enforcers and blockers
- multi-material colour (one state per filament)
- fuzzy skin

Its tools are a sphere brush, smart fill by angle and bucket fill.

While painting, a finger on the object paints and a finger elsewhere turns
the view. The viewport finds the part under the finger from the hit
triangle's range in the merged mesh. A fill is one engine call
(`orca_plater_volume_paint_fill`) and one undo step.

A brush stroke is one undo step too, like desktop's one snapshot per mouse
drag. The finger's dabs queue up in the view model while the engine is busy
and go to `orca_plater_volume_paint_stroke` together, one call per run of
dabs on the same part. Every call after the stroke's first passes
`continue_stroke`, which adds the dabs to the step the first call made, as
long as nothing else has changed the document in between. This keeps a
stroke to one copy of the model for undo, which matters on a phone where
copying a large model for every dab would stall the brush. The view model
adds dabs between finger samples further apart than half the brush radius,
so a fast finger draws a solid line; the engine casts each dab along its
view ray onto the surface and skips dabs that miss the part. Brushing over
paint already in the brush's state changes nothing and is not an error.

After each batch the app reads the painted triangles back
(`orca_plater_volume_paint`) and draws them as an overlay with desktop's
enforcer and blocker colours, or the filament's colour. The rest of the
scene is read again once the finger lifts.

Painted support enforcers do nothing while the object's supports are off,
which the engine reports as a slicing warning. The painter shows the same
warning while support enforcers are painted on an object with supports
off, with a button that turns supports on for that object only, as a
per-object setting, so the process preset stays as it is.

The painted layers live on the model's volumes, so a saved project
(`orca_project_save`, desktop's `store_bbs_3mf`) carries them in the 3MF
the same way desktop does, and opening a desktop project brings its paint
along.

## Variable layer height

The layer height editor is desktop's layers editing on one object. It works
on `ModelObject::layer_height_profile`, flat (z, layer height) pairs, where
an empty profile means the process layer height (and any height ranges).
The panel draws the profile as desktop's thickness bar: object height
upwards, layer height to the right, with the process layer height dashed.

Its edits are desktop's:
- Adaptive (`layer_height_profile_adaptive`) at a quality from -0.1 to 1.
- Smooth (`smooth_height_profile`) over a radius of layers, optionally
  keeping local minima.
- Thicker, thinner or smooth at a picked height over a band
  (`adjust_layer_height_profile`). One press is four of desktop's timer steps.
- Reset, which clears the profile.

Smooth and manual edits on an object without a profile start from
`layer_height_profile_from_ranges`, as desktop does. Each edit is one undo
step and makes the slice stale.

## Measure

The measure tool is desktop's measure gizmo on one object. A tap on the
object picks the feature under the finger (`orca_measure_pick`): a point, an
edge, a circle or a plane. The engine's `Measure::Measuring` segments the
object's merged mesh, the same mesh the viewport picks against, so the tapped
triangle and the object-space hit go to it unchanged. Building it is slow, so
the engine keeps one per object and mesh revision.

Like desktop, the app measures in world space: it carries both features
through the instance matrix (normals through the cofactor matrix, which also
scales plane areas and circle radii) before `orca_measure_between`. The panel
shows desktop's distance, per-axis distance and angle, and the viewport draws
the features and the dimension line over the objects.

## Calibration

The Calibration menu is desktop's: temperature tower, flow rate (passes 1
and 2, and Orca YOLO with its perfectionist version), pressure advance tower
line and pattern, max volumetric speed, VFA, retraction, input shaping
frequency and damping, and cornering. The dialog takes desktop's default
ranges, by filament type for the temperature tower, by extruder type for
pressure advance, and by firmware for cornering, which tests junction
deviation instead of jerk on Marlin 2 printers that set one. The input
shaper types it offers follow the printer's G-code flavor, as desktop's do.

`orca_plater_start_calibration` is desktop's `Plater::calib_*` in the
engine. It replaces the document with the test model, makes desktop's edits
to the edited print, filament and printer presets and to the objects'
settings, and cuts the model to the tested range. It keeps the test's
`Calib_Params` on the plater, so every slice of it goes through
`Print::set_calib_params` and G-code generation steps the temperature, PA,
speed, retraction, shaper frequency or damping, or cornering up the model.

The PA pattern has no test model. As on desktop, the plate gets one cube
handle per speed and acceleration pair, and the plater keeps a
`CalibPressureAdvancePattern` built from its own `Calib_Params`, which the
pattern holds by reference. Each slice writes the pattern's G-code into the
plate's custom G-code, where desktop's GUI plater puts it. Clearing the plate, opening a project or
"End test" makes the plater ordinary again.

The engine has no Draco decoder, so `mobile/android/tools/gen_calib_models.py`
turns desktop's `.drc` test models into plain 3MF meshes in `assets/calib`,
next to copies of the flow rate 3MFs. The app copies the chosen model out of
the assets before the engine reads it.
The engine loads every vendor under `system/`, and parsing a vendor's preset
tree dominates startup, so only one installed brand sits there at a time: the
brand of the printer in use. The others are parked in `orca-brands/`, outside
the data directory. Picking another brand in the printer picker moves the
bundles between the two (renames on one filesystem, finished by the next run
if cut short), reloads the engine's presets and restores the printer, filament
and process last used with that brand. User presets whose parent belongs to a
parked brand are skipped by the engine until that brand is loaded again; their
files are left untouched.

The settings sheet edits a handful of common process and filament options on
top of the selected presets through the engine's edited-preset mechanism, so
slicing picks them up exactly as desktop does; they can be saved as user
presets.

## Screen layout

On a phone the viewport fills the screen and everything else floats over it:
the preset bar and plate strip at the top, the selected object's controls
and the plate actions at the bottom, and the object list and settings in a
sheet and a full-screen dialog. A window that is at least 840 dp wide, or at
least 600 dp wide in landscape, and at least 480 dp tall (tablets, unfolded
foldables, desktop-mode windows) puts the viewport next to a fixed side pane
instead, so the controls don't cover the model: presets on top, an Objects
tab (the selected object's controls above the object list, tap an object to
select it) or a Settings tab (the same settings as the dialog), and the
slice, preview and tool panels at the bottom. The choice follows the
window's current size, so it switches on rotation, fold and split screen. A
phone in landscape keeps the phone layout because a
pane under 480 dp tall cannot hold its tabs and controls.

## Viewport

The plate is drawn in engine coordinates (z-up millimetres). Gestures:
a finger on the selected object drags it over a plane at the touched height,
and the move is committed to the engine on release; a finger elsewhere
orbits; two fingers pinch to zoom and pan. Taps are resolved by a CPU ray
cast against the instance meshes, which also gives the tapped face's normal
for "Lay on face".

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
## Model library

The library screen (`library/`, `ui/LibraryScreen.kt`) brings models in
from the web, through two routes chosen by what each site offers:

- **Thingiverse** has an official REST API, so it gets a native search grid
  and a file picker. The API needs an app token; OrcaPod ships none, and the
  user pastes their own (created free at thingiverse.com/apps/create), kept
  in the app's private preferences.
- **Printables, MakerWorld, MyMiniFactory, Cults** have no public API for
  searching and downloading (MyMiniFactory's needs a registered OAuth app),
  so they open in an in-app WebView where the user browses and signs in as in
  a browser. The WebView's download listener re-fetches each download with
  the page's cookies, or, for files a page builds in a `blob:` URL, has the
  page read the blob and hand it back through a JavaScript interface that
  only accepts the nonce issued for that download.

Downloads land in a per-download cache directory. Zip archives are unpacked
flat (entry paths are reduced to base names, so nothing escapes the
directory) to their model files; a 3MF is kept whole. One model file goes
straight to the plate, several open a picker. Credentials (the API token,
site cookies) are not forwarded when a download redirects to another host.
A web link shared to the app from a browser opens the library on that page.

## Limits

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
and arranges, settings edit, and a plate slices, previews and cancels. It
also checks that every option of the shipped settings layout is known to the
engine, and exercises parts and modifiers, per-object and per-part settings,
height ranges, plates, filament slots, slicing all plates and the project,
sliced-3MF and STL exports. The project check saves a plate with a moved,
rotated and scaled object, object and modifier settings, a height range, a
second plate with its own settings, two filaments and an edited process
value, then clears all of it and checks that opening the 3MF brings each
back. The preview checks cover travels, markers,
attributes and layer heights, hidden roles, ranges and slider draw ranges, and
a pause and filament change that reach the sliced G-code. Cut, paint and
layer height checks cover straight and tilted cuts with connectors, brush and
fill strokes, and manual, adaptive, smoothed and reset profiles. The measure check picks a
point and a plane, measures between them, and carries features through a
scaled instance. The calibration check sets up a temperature tower, a
flow rate pass, a PA pattern and a cornering test and ends the test; the
bridge contract tests also check each tower's height, that PA tower and
pattern G-code steps PA, the input shaping and cornering printer settings,
and the dialogs' range checks. A STEP file loads, counts its
triangles, meshes and imports.
