# spec-core — shared Rust core (`tuner-core`)

The core is the only home for DSP and product rules. Android, iOS, and macOS call it
through generated UniFFI bindings and must not reproduce pitch, notation, temperament,
instrument-target, chord, or metronome calculations.

## 1. Responsibilities

- Pitch detection and confidence.
- Note, MIDI, cents, solfège, key/mode, and equal-temperament conversion.
- Noise-gate and persistent-reading state machine.
- FFT spectrum, measured partials, and chord recognition.
- Reference-tone frequency tables.
- Instrument preset and fingering data.
- Sample-position metronome scheduling and tap tempo.

Native layers own UI, microphone/speaker devices, permissions, lifecycle, haptics, and
platform storage adapters.

## 2. Modules

| Module | Responsibility |
|---|---|
| `pitch` | YIN detection and harmonic refinement |
| `note` | MIDI/frequency/cents/note naming |
| `solfege` | solfège systems and key modes |
| `spectrum` | FFT bins, measured peaks, partial/chord analysis |
| `instrument` | tunings and fingering charts |
| `metronome` | sample-accurate beat state |
| `api` | stable UniFFI-facing facade and dictionaries |

Audio hot paths are platform-independent, use the configured sample rate, allocate
nothing, take no locks, and contain no panic/unwrap path.

## 3. Pitch detection

Input is mono floating-point PCM. Default configuration uses a 2048-sample window and
1024-sample hop at the device rate. YIN searches 60–2000 Hz and accepts a candidate
only when confidence and level pass configured rules. A harmonic-model least-squares
refinement rejects common octave errors and targets ±0.5 cent for synthetic
fundamental-plus-three-harmonic signals at 20 dB SNR over 80–1500 Hz.

Invalid, empty, non-finite, too-short, or below-gate input returns no new candidate and
never panics.

## 4. Musical conversion

For A4 calibration `a4`, frequency from MIDI is:

`f = a4 × 2^((midi - 69) / 12)`

Cents between frequencies are `1200 × log2(f / reference)`. Note names use 12-TET
spelling and include octave. Equivalent boundary spellings are covered by tests.

### 4.1 Spectrum and partials

`analyze` returns 64 logarithmic bins from 60 to 2400 Hz in dBFS (-80 to 0), up to
eight measured peaks, optional chord, input level, and the tuner state. Peak frequency
is refined around its FFT bin. `harmonic_index=0` means an independent measured peak;
2, 3, … means a measured partial associated with the fundamental. The UI must never
generate theoretical partials absent from this list.

Chord recognition requires energy in at least three pitch classes and returns a stable
short symbol such as `Cmaj` or `Am7`, otherwise no chord.

The professional views extend the same analysis pass without a second FFT:

- `spectrum_db`: existing 64 bins over 60–2400 Hz for musical detail.
- `wide_spectrum_db`: 128 logarithmic bins from 20 Hz to
  `min(20000 Hz, sample_rate/2)`.
- `waveform_min` and `waveform_max`: 256 equal-width buckets over the current PCM
  window, containing the finite minimum and maximum. Non-finite samples become zero.
- `sample_position`: advances by the configured analysis hop and marks the current
  frame end since engine creation.
- `sample_rate_hz` and `wide_spectrum_max_hz` make both axes explicit without native
  layers assuming a device rate.

Waveform envelopes and display histories do not participate in pitch detection.

### 4.2 Equal temperaments

Supported divisions are 12, 19, 24, and 31. A4 is the reference step. For frequency
`f`, nearest step is `round(N × log2(f/a4))`; step frequency is
`a4 × 2^(step/N)`. `temperament_cents` is the signed deviation from that step in
`[-600/N, +600/N)`. Invalid division requests retain a safe supported value.

### 4.3 Gate and persistent reading

States are `Quiet`, `Acquiring`, `Tracking`, and `Holding`.

1. A new note needs two consecutive valid frames within the candidate tolerance.
2. `Tracking` updates the reading while level is above the open threshold.
3. A 3 dB hysteresis separates gate open/close behavior.
4. After a trusted reading, a below-gate frame enters `Holding` and returns that last
   reading at full display strength indefinitely.
5. Holding is replaced only when another candidate passes two-frame confirmation.
6. Before the first trusted reading, quiet input remains `Quiet` with no tuner event.

Default `noise_gate_dbfs` is -45 dBFS and valid settings are -60 to -30 dBFS. Native
layers have no additional timeout that clears core state.

### 4.4 Reference-tone table

The table uses current A4 and temperament, includes every step whose frequency is
80–1500 Hz, is strictly frequency-ordered, and provides step offset, frequency,
temperament, nearest 12-TET note name, and cents from that note. Native playback uses
these frequencies verbatim.

## 5. Solfège and modes

Systems:

- Fixed Do: pitch class C is Do.
- Movable Do: scale degree follows tonic and mode.
- Numbered: degrees 1–7.
- Chinese: Gong/Shang/Jue/Zhi/Yu naming where applicable.

Modes are Major, Minor, Gong, Shang, Jue, Zhi, and Yu over 12 tonic pitch classes.
Out-of-scale chromatic tones receive a deterministic accidental representation.

## 6. Instruments

Core returns immutable `Instrument`, `Tuning`, `StringSpec`, `FingeringChart`, and
`FingeringNote` data described in [spec-instruments.md](spec-instruments.md). Presets
store MIDI; A4-dependent live frequency comes from the engine. Preset customary
solfège is part of the preset contract and is not rewritten by global live-display
solfège settings.

Target cents for instrument tuning use the same `cents_between` rule as universal
tuning. Native platforms may select UI rows but may not calculate musical targets.

## 7. Metronome

Tempo range is 30–250 BPM. Beat unit supports 2, 4, or 8 and bar length 1–12. Every
render call returns PCM plus exact `sample_offset` tick events. Over 1000 ticks,
position error is <1 sample. Tempo changes affect the next scheduling decision without
resetting the bar; muted beats emit timing events but no click samples. Tap tempo
ignores invalid/outlier intervals and requires at least two taps.

## 8. Test baseline

`cargo test` must remain green and cover:

- 80–1500 Hz synthetic sweep at ±0.5 cent under the defined harmonic/noise fixture.
- Note/cents and enharmonic boundaries.
- Every solfège system, tonic, and mode.
- Gate acquisition, hysteresis, indefinite holding, and replacement by a new note.
- Spectrum bounds, measured partials, chords, and silence.
- All temperament tables and ordered 80–1500 Hz reference tones.
- Instrument counts, ordering, MIDI/frequency, and customary solfège.
- 1000 metronome ticks, tempo changes, accents/mutes, tap outliers, and finite samples.

## Appendix A — UniFFI contract

The checked-in UDL/generated surface is the only native API. Rust uses snake_case;
generated bindings map names to platform conventions. The contract contains:

```text
enum InstrumentKind {
  Guitar, Ukulele, Guqin, Zhudi, Dongxiao, Shakuhachi
}

dictionary Instrument {
  string id;
  string display_name;
  InstrumentKind kind;
}

dictionary StringSpec {
  u8 number;
  i32 midi;
  string note_name;
  f64 freq_hz;
  string solfege;
}

dictionary Tuning {
  string id;
  string display_name;
  sequence<StringSpec> strings;
}

dictionary FingeringNote {
  string fingering;
  i32 midi;
  string note_name;
  f64 freq_hz;
  string solfege;
}

dictionary FingeringChart {
  string id;
  string display_name;
  sequence<FingeringNote> notes;
}

enum SolfegeSystem { FixedDo, MovableDo, Numbered, Chinese }
enum ModeKind { Gong, Shang, Jue, Zhi, Yu, Major, Minor }

dictionary KeyMode {
  u8 tonic_pc;
  ModeKind mode;
}

dictionary TunerConfig {
  f64 sample_rate;
  u32 frame_hop_samples;
  f64 a4_hz;
  f32 noise_gate_dbfs;
  SolfegeSystem solfege;
  KeyMode key;
  u8 temperament;
}

enum SignalState { Quiet, Acquiring, Tracking, Holding }

dictionary TunerEvent {
  f64 freq_hz;
  string note_name;
  i32 midi;
  f64 cents_off;
  f32 clarity;
  string solfege;
  u8 temperament;
  i32 temperament_step;
  f64 temperament_cents;
}

dictionary Partial {
  f64 freq_hz;
  f32 magnitude_db;
  u8 harmonic_index;
  string note_name;
  f64 cents_off;
}

dictionary AnalysisFrame {
  TunerEvent? tuner;
  sequence<f32> spectrum_db;
  sequence<f32> wide_spectrum_db;
  f64 wide_spectrum_max_hz;
  sequence<f32> waveform_min;
  sequence<f32> waveform_max;
  u64 sample_position;
  f64 sample_rate_hz;
  sequence<Partial> partials;
  string? chord;
  SignalState signal_state;
  f32 input_level_dbfs;
  f32 display_strength;
  boolean is_held;
}

dictionary ReferenceTone {
  i32 step_from_a4;
  f64 frequency_hz;
  u8 temperament;
  string note_name;
  f64 cents_from_note;
}

interface TunerEngine {
  constructor(TunerConfig config);
  TunerEvent? feed(sequence<f32> pcm);
  AnalysisFrame analyze(sequence<f32> pcm);
  void set_a4(f64 hz);
  void set_solfege(SolfegeSystem system, KeyMode key);
  void set_noise_gate(f32 dbfs);
  void set_temperament(u8 divisions);
  sequence<ReferenceTone> list_reference_tones();
}

enum TickAccent { Accent, Normal, Muted }

dictionary TickInfo {
  u64 sample_offset;
  u32 beat_index;
  TickAccent accent;
}

dictionary MetronomeConfig {
  f64 sample_rate;
  f64 bpm;
  u8 beats_per_bar;
  u8 beat_unit;
  sequence<TickAccent> accents;
}

dictionary RenderFrame {
  sequence<f32> samples;
  sequence<TickInfo> ticks;
}

interface Metronome {
  constructor(MetronomeConfig config);
  RenderFrame render(u32 frames);
  void set_bpm(f64 bpm);
  void set_time_signature(u8 beats, u8 unit);
  void set_accents(sequence<TickAccent> accents);
  void set_click_samples(sequence<f32> accent, sequence<f32> normal);
  f64 tap(u64 timestamp_samples);
  void start(u64 at_sample);
  void stop();
  boolean is_running();
}
```

Global queries expose instruments, tunings, fingering charts, cents conversion, and
solfège conversion as defined by the checked-in UDL. Any signature/type change first
updates this appendix in English and `../spec-core.md` in Chinese, then regenerates all
bindings; generated binding files are never hand-edited.
