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
printer list short, like the desktop setup wizard.
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

## Repair and booleans

Each object's mesh statistics (`orca_plater_mesh_errors`, desktop's object
list warning icon) are read with every scene refresh: the object list marks
objects with non-manifold edges or errors fixed on import, and offers to
repair the ticked objects or every object with errors left. The object
sheet, each part's menu and the selected object's toolbar repair too.
Repair is desktop's "Fix model" (`ModelVolume::repair`, see
[mesh-repair.md](mesh-repair.md)) over every volume of the object or one
part; a mesh that needs nothing leaves no undo step, and the notice lists
what was repaired, what failed and what needed no repair.

Booleans are desktop's Mesh Boolean gizmo between two volumes of one
object: union, difference or intersection through MCUT. The result replaces
the first volume with its role, settings and filament, named like desktop's
("<part> - union"); a union removes the tool, the other operations remove it
when "Delete input" is on. Desktop gives the result the source volume's
offset; the bridge keeps the offset `add_volume` computes from the result's
centre instead, so the result stays exactly where the operands were.
Booleans between separate objects work the same way after assembling them
into one object.

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
triangle's range in the merged mesh. It sends one engine stroke
(`orca_plater_volume_paint_sphere` / `_fill`) per finger step, and each
stroke is one undo step. After each stroke the app reads the painted
triangles back (`orca_plater_volume_paint`) and draws them as an overlay with
desktop's enforcer and blocker colours, or the filament's colour.

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

- STEP import needs OCCT, which the bridge does not build; the app says so
  and asks for STL or 3MF instead.
- Bambu printers are reached in LAN mode only; Bambu's cloud needs its
  closed network plugin. The AMS maps the single filament to one tray.
- Prints the app did not send appear in its history only for Klipper.

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
a pause and filament change that reach the sliced G-code. Cut, paint and
layer height checks cover straight and tilted cuts with connectors, brush and
fill strokes, and manual, adaptive, smoothed and reset profiles. The measure check picks a
point and a plane, measures between them, and carries features through a
scaled instance. The calibration check sets up a temperature tower, a
flow rate pass, a PA pattern and a cornering test and ends the test; the
bridge contract tests also check each tower's height, that PA tower and
pattern G-code steps PA, the input shaping and cornering printer settings,
and the dialogs' range checks. The repair check fills an open cube's hole
and undoes it, and the boolean check subtracts a bar from a cube; the
contract tests also run all three boolean operations and their failures.
