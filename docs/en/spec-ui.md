# spec-ui — panel behavior (Android Compose / Apple SwiftUI)

Five destinations: Tuner, Instruments, Spectrum, Metronome, and Settings. Visual and
motion rules come from [design-system.md](design-system.md); this document defines
behavior and interaction.

## 1. Universal tuner

- Preserve the existing vertical layout: dial → note → solfège/key → measured values →
  compact spectrum → partial/chord row → quiet-state chip.
- The dial shows 10-cent major and 2-cent minor ticks, segmented color arc, progress
  light, and exactly one current needle. Android uses its fast spring; iOS uses a 50 ms
  non-bouncing ease-out with one continuously replaced target. Note/readouts never
  overlap the dial.
- Readouts show note, octave, accidentals, Hz, cents, clarity, and configured solfège.
- The key pill opens an anchored 12-tonic × 7-mode panel, persists immediately, and is
  disabled for Fixed Do.
- Compact spectrum is the real 64-bin core FFT. It changes only on confirmed
  `Tracking` frames; `Holding` and the first candidate for a new note preserve the last
  confirmed spectrum, peaks, and chord. The professional page consumes every raw frame.
- Every measured peak flag shows one-decimal Hz. H1 uses tuning semantics, actual H2+
  use secondary ink, and independent peaks use hollow markers. Missing theoretical
  harmonics are never invented.
- The whole compact spectrum is an accessible tap target that selects the same Spectrum
  navigation destination as the bottom tab.
- Partial chips and chord come only from core analysis.
- Pro toggles a 12/19/24/31 temperament selector and deviation readout. It shares state
  with Settings.
- The top-left tuning-fork control exactly mirrors the top-right Pro control in size,
  margin, and position without moving main content.
- Its sheet lists all core-provided 80–1500 Hz reference tones for current A4 and
  temperament. Selecting the current item stops; selecting another fades between tones.
  Dismissing the sheet keeps playback running and exposes a same-width quick stop/resume
  control. Capture and needle analysis continue behind the sheet.
- Leaving Tuner, backgrounding, or interruption stops the tone. A4/temperament changes
  invalidate the selected tone. The sheet requests no new permission.
- Visibility obeys core `SignalState` and `display_strength`: require two valid frames
  before first display, then keep the last reading indefinitely through silence until a
  different note is confirmed by two valid frames. Native UI has no timeout reset.

## 2. Instruments

Instrument, tuning, key, model, and tube-note controls are 48 dp/pt high with 16 dp/pt
radius and one-line ellipsis. Narrow screens wrap the control group; a control itself
never wraps.

### 2.1 Strings

Guitar, ukulele, and guqin show string number, note, and preset customary solfège.
Automatic mode highlights the nearest string; manual mode locks the selected target
within ±50 cents. The compact dial shows target deviation and marks a string complete
at ±5 cents.

### 2.2 Winds — dongxiao first

Dongxiao adopts the new interaction first. Zhudi and shakuhachi retain their existing
selectors and fingering lists for this delivery.

- Dongxiao provides G/F keys and eight-/six-hole variants for each key. Eight-hole is
  the default on first entry and when no prior compatible choice exists. Changing key
  or hole system preserves the current tube-solfège mapping where possible.
- The main view consists of one complete large vertical dongxiao and three range
  columns: **Low (soft breath)**, **Middle (overblown)**, and
  **High (forceful breath)**. This does not revert to a conventional table: the large
  dongxiao remains the visual anchor, and every horizontal guide aligns its row strictly
  to the center of the fingering's topmost open hole. The diatonic main chart keeps
  seven base-fingering rows; chromatic detail independently keeps 12. All three columns show
  the measured fingerings core returns: the middle range mostly reuses low patterns while the
  high range differs. Cells the charts do not cover — above 31 semitones for eight holes, and
  the conflicting six-hole cells — remain visually blank, non-tappable, and free of
  placeholder notes. They must not be synthesized by copying low holes or adding
  octave/fifth offsets.
- Eight-hole mode renders exactly eight holes on the single tube; six-hole mode renders
  exactly six. Each base-fingering row aligns to its `anchor_hole`; the all-closed row
  anchors at the bottom outlet. Thin guides cross the tube and all three range columns.
  A physical-hole guide with no matching diatonic base fingering remains visibly blank;
  adjacent rows must not move or receive placeholder notes. Open, closed, and half-hole
  states remain visible. The first hole shifts sideways only slightly to resemble common
  modern dongxiao placement; the back hole stays on the tube centerline and uses a dedicated color.
- Each cell shows a fixed note name and a separate solfège badge; concatenated text such
  as `D3·5` is forbidden. Tapping either the note area or the solfège badge previews
  that cell's complete fingering. A special base fingering may carry a small 叉 or 半
  marker, but fingering kinds must never become separate lanes.
- The large dongxiao shows only the pinned cell or the current live fingering. A pinned
  cell outranks live detection until the same cell is tapped again. Playing also
  shows cents, with the compact dial below.
- The two cell highlights must be distinguishable at a glance: a live hit is filled, a
  pinned cell is outlined only, and they never share the same background and weight;
  accessibility state reports “live hit,” “pinned,” or “unselected.”
- “Tube note as X” at the top is display-only and derived from the current solfège
  mapping; it is not a button, picker, or dropdown. Users vertically drag a solfège
  badge itself. Crossing a full step updates all badges immediately with continuous
  motion. Main view wraps across seven natural degrees; chromatic detail independently
  wraps across 12 semitone degrees. One sustained drag must reach “Tube note as 2” from
  the default “as 5.” It commits to the nearest discrete
  step on release and snaps into place; arbitrary resting positions are
  forbidden. Commit changes all badges and display-only status. Chromatic detail keeps
  fixed notes, holes, and anchors; the main view atomically refilters seven base rows so
  Low always displays complete `1 2 3 4 5 6 7`, never `#4` in place of `4`.
- Tapping blank space in the main view's title row opens separate chromatic detail,
  which preserves all 12 chromatic base rows and the same three-range layout. A visible, accessible
  “Chromatic Detail” button provides the explicit entry point; the table never
  expands in place. iOS presents it full-screen, Android as a full-screen dialog,
  and macOS as a large sheet. Every platform exposes an explicit close action and
  preserves main-view context.
- Main view and detail share the same `CaptureHub` and `AnalysisFrame`; no second
  microphone or analysis pass is created. Each owns its own large diagram, scroll
  position, cell preview, and interaction state. Closing or reopening detail must not
  overwrite main-view state; each view's pinned cell outranks live recognition until the
  same cell is tapped again.
- All three range columns remain visible when width permits. On small phones the
  dongxiao stays visible while only the register-column region scrolls horizontally,
  or register columns auto-collapse with an explicit way to restore them. Responsive
  behavior never hides the dongxiao, changes row anchors, or merges register meanings.
- The register-column region scrolls horizontally on its own so the live-hit column moves as
  close to that region's center as possible, keeping the high column from sitting offscreen.
  This is a trend algorithm: derive the displacement from column center minus viewport center,
  then clamp it to the legal scroll range, so edges only scroll as far as they legally can and
  true centering is not required. Only a change of the live-hit range triggers a scroll; cell
  pinning and manual scrolling never do.
- The main page remains tuner-first: chart height adapts to screen height and has a firm
  cap, while the complete dial, target readout, and status must not be pushed off-screen
  or reduced to fragments. Larger fingering space belongs in chromatic detail.
- The large diagrams in main and detail demonstrate core's primary fingering only.
  Instrument construction and schools may use alternatives; visible and accessibility
  copy must not claim a single authoritative fingering.
- Zhudi keeps its existing key and tube-note 5/1/2 path. Shakuhachi keeps its model and
  fixed-scale path. Neither adopts dongxiao's eight/six switch, draggable solfège badges,
  or independent chromatic detail in this delivery.

Both instrument types share the core input state machine and the universal tuner's
single-needle presentation. Native timers may not clear the target or needle.

## 3. Professional spectrum

- Enter through the Spectrum tab or the compact strip. Both routes share navigation
  state and the same `CaptureHub`.
- A compact segmented control switches the existing main plot slot between Spectrum,
  Pitch Trace, and Waveform. The views are not stacked, do not reduce the waterfall,
  and never restart capture or erase other display histories.
- Spectrum offers Musical 60–2400 Hz (64 measured bins) and Full 20 Hz–frame maximum
  (128 measured bins). Both come from one core FFT. On phone widths, Full labels are
  limited to 20/100/500/1k/5k/maximum; this does not reduce the 128 measured bins.
- The current main plot is logarithmic 60–2400 Hz by -80–0 dBFS, shows all 64 measured
  bins, grid, input level, live curve, non-decaying peak hold, and a draggable cursor
  reporting Hz/dB/note.
- Fixed labels: 60/100/200/500/1k/2.4k Hz and 0/-20/-40/-60/-80 dBFS. `0 dBFS` is the
  digital full-scale limit, never a mislabeled current level.
- Status clearly says Live or Frozen. The plot is a current frequency distribution;
  it must not scroll as if frequency were time.
- Pause freezes the live curve, peak hold, and waterfall. Resume continues from new
  frames.
- Reset Peak clears only every peak bucket to -80 dBFS. It preserves the live curve,
  waterfall, six summaries, actual peak list, and pause state.
- Peak flags and list use only measured `partials`, show Hz/dB/harmonic or note, and
  support duplicate frequencies without using frequency as the sole row identity.
- Six 52 dp/pt summary cards show note, fundamental, cents, input, strongest measured
  peak, and chord; missing values use an em dash, while input always shows a value.
- The waterfall shares the frequency axis, places newest row at top, retains 256 rows,
  appends every two frames, and visually interpolates 64 measured bins to 96 columns.
  It shows Now/-3/-6/-9/-12 s, matching frequency ticks, and a -80–0 dBFS color legend.
- Pitch Trace retains about 12 seconds. Its x-axis is time and y-axis uses numeric MIDI
  pitch labels. Only non-held `Tracking` frames append a point positioned by
  `sample_position/sample_rate_hz`. Quiet, Acquiring, Holding, and the first frame after
  resume insert a gap; lines connect only adjacent real tracking points.
- Waveform shows the current 256-column min/max envelope with a zero line, -1/0/+1
  amplitude labels, and millisecond ticks. It is a live window, not a recording.
- Pause freezes all three main views, peak hold, pitch trace, and waterfall. Resume
  begins a disconnected trace segment.
- Non-goals: no recording, audio files, playback, timeline scrubbing, or export. No
  disabled placeholder controls or public half-implemented API are exposed.

## 4. Metronome

- BPM 30–250 through vertical drag, slider, ±1/±5, or tap tempo (effective after two
  taps).
- Time signatures cover common 1/4–12/8 patterns. Tapping a beat cycles
  Accent → Normal → Muted.
- Strong and weak sounds are independently selected in this order: Mechanical,
  Wood Block, Electronic Beep, Claves, Rimshot, Snare, Cowbell, Hi-hat, Clap, Shaker,
  Kick, Bell. Unknown saved values fall back safely to Bell/Mechanical defaults.
- Pendulum, flash, and bar progress are driven by `TickInfo`.
- Playback uses the large start/stop button and native background lifetime.

## 5. Settings

- A4 calibration: 415–466 Hz in 1 Hz steps, displayed to one decimal.
- Solfège: Fixed Do, Movable Do, Numbered, Chinese five-tone naming.
- Key: 12 tonics × seven modes; same state as the tuner key pill.
- Pro and temperament: same state as tuner controls.
- Noise gate: -60 to -30 dBFS, default -45 dBFS only when no value exists. Raising it
  requires a louder signal. Changes apply immediately to universal and instrument tuner.
- Theme: System, Light, Dark.
- Haptics: enabled by default.

## 6. Common requirements

Both themes are complete. Permission denial, missing input, audio route change, and
interruption degrade without a crash. Behavior changes update this file in English and
`../spec-ui.md` in Chinese; visual changes update both design-system files.

## 7. macOS 14+ desktop behavior

- The desktop app keeps the complete Tuner, Instruments, Analysis, Metronome, and
  Settings destinations but presents them in a `NavigationSplitView` sidebar instead
  of a mobile bottom tab bar.
- Selecting the tuner spectrum preview selects the same Analysis sidebar destination.
  Every destination shares one state graph and `CaptureHub`; no parallel window or
  analysis session is created.
- Wide content areas may place tuner data beside its preview and the analysis main plot
  beside the waterfall. Narrower windows stack them in reading order. A layout breakpoint
  never resets data, history, or capture lifetime.
- Window inactivity and navigation release capture subscriptions according to the same
  generation-token/final-subscriber rules as iOS.
- The reference-tone chooser uses a desktop overlay or sheet. Dismissal keeps playback
  running; leaving Tuner or making the window inactive stops it.
- Dongxiao main and detail each use a desktop side-by-side layout: one complete large
  vertical dongxiao plus right-hand Low, Middle, and High columns. All three columns are
  populated with measured fingerings; only chart-uncovered cells stay blank. Blank
  title-row space or the “Chromatic Detail” button opens independent chromatic detail
  in a large sheet. Sheet and main view share
  capture/frames while preserving independent scroll position, cell preview, and interaction state;
  dismissing the sheet does not reset the main view. Both regions also auto-center the live-hit
  column horizontally as a trend.
- Microphone denial exposes an explanation and Open System Settings action. Missing
  input keeps a retryable UI and never crashes.
- See [macos-native.md](macos-native.md) for the complete state, fallback, and build
  contract.
