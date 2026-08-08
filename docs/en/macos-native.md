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
- `ios/TunerCore/tuner_core.xcframework` contains iOS device, iOS simulator, and universal
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
  decisions, spectrum scale/peak hold, capture format, and startup-token behavior.
- Build acceptance covers the universal Apple Silicon/Intel archive, the macOS app, and
  the macOS test target.
- Manual acceptance covers permission allow/deny, all five sections, A4 loopback,
  analysis pause/reset, and metronome playback.

