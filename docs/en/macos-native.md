# Native macOS 14+ app design and delivery specification

## 1. Goal and boundaries

The macOS app is TUNAR's native desktop client, not an enlarged iOS window. It provides
five complete sections—tuner, instruments, professional analysis, metronome, and
settings—while sharing the same Rust business core and UniFFI contract with Android and
iOS.

Non-goals for this milestone are recording, saving/playback/export of audio files, a
menu-bar resident app, cloud sync, manual audio-device routing, and continuous background
microphone capture. No unfinished entry point may be exposed.

## 2. Platform and build model

- Minimum system: macOS 14.
- Architectures: Apple Silicon and Intel; the Rust archives are merged into one universal
  macOS slice.
- `ios/TunarCore/tunar_core.xcframework` contains iOS device, iOS simulator, and universal
  macOS slices. UniFFI generates the Swift binding once; generated files are never edited.
- macOS uses its own bundle identifier and `UserDefaults` container. It neither migrates
  nor overwrites mobile data, while keeping the same defaults.

## 3. Desktop information architecture

The root window uses `NavigationSplitView`:

- A sidebar exposes exactly Tuner, Instruments, Analysis, Metronome, and Settings.
- A single detail area hosts the selected section; the minimum window size keeps the
  sidebar and primary controls usable.
- Selecting the tuner spectrum preview selects the same Analysis sidebar destination
  instead of opening a second window or state owner.
- Wide analysis windows place the main plot and waterfall side by side; constrained
  widths stack main plot, metrics, and waterfall vertically.

All sections share Aurora colors, radii, data capsules, and semantic states. The desktop
app does not copy the mobile bottom tab bar or phone-height ratios.

## 4. Core state and transitions

### 4.1 Capture

`CaptureHub` remains the only capture owner. Its key state is:

- `references`: number of views currently requiring capture;
- `generation`: asynchronous startup token;
- `running`: whether the audio engine was committed successfully;
- `config`: A4, noise gate, solfège, key/mode, and temperament.

The first subscriber starts capture asynchronously; later subscribers share that input.
The final release invalidates pending startup and stops the tap, engine, and analysis
worker. Navigation or an inactive window must not allow stale startup to reopen the mic.

macOS uses the `AVAudioEngine` input node without `AVAudioSession`. The system callback
only writes into a preallocated fixed ring. Rust `analyze`, Swift array construction, and
configuration synchronization run on the analysis worker.

### 4.2 Tuner and instruments

Both sections consume Rust `AnalysisFrame` values. The core owns the gate, two-frame
confirmation, hysteresis, and indefinite hold; macOS adds no clearing timeout. Reference
tone frequencies come from core `ReferenceTone` values. A tone continues after its
selection panel closes and stops when leaving Tuner or when the window becomes inactive.

The instrument page shares the line-art figure sources with iOS (headstocks, guqin, wind
drawings, and the wind table):

- The instrument switcher shows six tiles with line-art glyphs. The guitar controls add a
  "6-in-line / 3+3" headstock segmented control and an auto/manual segmented control. The
  figure card centers the headstock drawing with the string buttons on both sides, each
  joined to its peg by a dashed leader. The guqin shows a row of string buttons above the
  guqin drawing, and tapping a string in the drawing activates its button. Behavior rules
  are in spec-ui §2.1.
- The zhudi, dongxiao, and shakuhachi all use the wind table below, with the dongxiao as
  the reference. The zhudi has no hole-system switch; the shakuhachi's columns are otsu /
  kan / daikan and it offers neither solfège-drag transposition nor chromatic detail.

- Dongxiao provides G/F keys and defaults to eight holes with a six-hole switch. The
  top “Tube note as X” status is derived from the solfège mapping and is display-only.
- Its main view keeps seven base-fingering rows, while chromatic detail independently
  keeps 12. Both use **Low (soft breath)**, **Middle (overblown)**,
  and **High (forceful breath)** columns. All three columns show the fingerings core
  measures for that range (middle mostly matches low, high differs); cells the charts do
  not cover remain visually blank and non-tappable. Missing
  data must not be synthesized by copying Low holes or adding octave/fifth offsets.
- Exactly one complete large vertical dongxiao sits at left and the three range
  columns at right; fingering kinds do not create separate lanes. Eight-hole mode renders
  exactly eight holes and six-hole mode exactly six. Open, closed, and half states are
  visible. The first hole shifts sideways only slightly to resemble common modern
  dongxiao placement; back holes remain on the tube centerline and use a dedicated color.
- Every base row aligns strictly to the center of the topmost open hole identified by
  its `anchor_hole`; all-closed anchors at the bottom outlet. A thin guide crosses the
  tube and three columns, while a physical-hole guide with no
  matching diatonic pattern remains blank. Cross-fingered or half-hole patterns use only
  a small 叉 or 半 marker in their cells.
- Every cell has a fixed note name and separate solfège badge; `D3·5` is forbidden.
  Tapping either area pins the complete fingering over live detection until the same cell
  is tapped again. Vertically dragging a badge updates continuously whenever it crosses
  a full step. Main wraps seven natural degrees; detail independently wraps 12 semitone
  degrees. Both can reach “as 2” from default “as 5” in one sustained gesture. Release
  commits and snaps; main atomically refilters complete `1–7`, while detail remains stable.
- Blank title-row space or the “Chromatic Detail” button opens independent
  12-semitone chromatic detail in a large sheet rather than expanding the main view. It
  reuses the same one-dongxiao plus three-range geometry. A close action remains visible.
- Main and detail reuse one `CaptureHub` and the same `AnalysisFrame`; presenting the
  sheet creates no parallel capture session or analysis pass. Each preserves independent
  scroll position, cell preview, and interaction state. Dismissing detail restores the unchanged main-view
  context; a pinned cell outranks live recognition in both views until the same cell is tapped again.
- Both views scroll their three-column region horizontally so the live-hit column trends toward
  that region's center, with the displacement clamped to the legal scroll range. Pinning and
  manual scrolling never trigger the automatic scroll.
- Main and detail each show exactly one complete large diagram using core's primary
  fingering, without claiming one authoritative semitone fingering; instrument
  construction and schools may use alternatives.
- Zhudi and shakuhachi retain their old interaction and do not show dongxiao's
  eight/six switch, draggable solfège badges, or independent chromatic sheet.

### 4.3 Professional analysis

Analysis shares `CaptureHub` and the same `AnalysisFrame` with the tuner:

- the main plot selects musical/full spectrum, roughly 12 seconds of pitch history, or
  the current-window waveform;
- fixed peak hold only rises and Reset clears only the held peaks;
- Pause freezes main plot, peaks, pitch history, and waterfall; pitch resumes as a new
  segment;
- the waterfall keeps scales, a level legend, and 256 history rows;
- no second FFT and no audio recording are introduced.

### 4.4 Metronome and settings

Rust `Metronome` owns scheduling, tap tempo, and beat accents. macOS only plays rendered
samples with `AVAudioEngine` and presents ticks. The twelve-sound order and strong/weak
defaults match mobile.

Settings use the desktop app's own `UserDefaults`: A4 440Hz, -45dBFS gate, 12-TET,
system theme, and enabled haptic preference by default. On Macs without haptic hardware
that option is a silent no-op. Changes are applied to the shared capture engine
immediately.

## 5. Permission and fallback

- The first capture section requests microphone permission. Denial shows the reason and
  an Open System Settings action.
- A 0Hz/zero-channel input format or engine startup failure aborts that attempt, releases
  resources, and leaves a retryable UI. An invalid tap is never installed.
- Unavailable speaker output stops reference tone or metronome playback without affecting
  microphone analysis or the rest of the UI.

## 6. Verification assets

- Existing Rust synthetic-signal, temperament, preset, and metronome tests remain the
  cross-platform business truth.
- macOS unit tests cover the five destinations and default selection, desktop layout
  decisions, spectrum scale/peak hold, capture format, startup-token behavior, and
  dongxiao eight-hole default/six-hole switch, one-large-diagram plus Low/Middle/High
  columns, per-range measured returns (19/32 entries for eight holes), blank
  unsynthesized chart-uncovered cells,
  topmost-open-hole anchors and blank guides, tap-over-live priority, snap-only
  transpose geometry stability, and independent detail state.
- Build acceptance covers the universal Apple Silicon/Intel archive, the macOS app, and
  the macOS test target.
- Manual acceptance covers permission allow/deny, all five sections, A4 loopback,
  analysis pause/reset, and metronome playback. Dongxiao additionally verifies the
  large sheet, shared capture with independent main/detail state, and no regression in
  the existing zhudi/shakuhachi paths.

