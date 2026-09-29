# spec-core — shared Rust core (`tunar-core`)

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

Core returns immutable `Instrument`, `Tuning`, `StringSpec`, `WindVariant`, and
`WindChart` data described in [spec-instruments.md](spec-instruments.md). Presets
store MIDI; A4-dependent live frequency comes from the engine. Preset customary
solfège is part of the preset contract and is not rewritten by global live-display
solfège settings.

Target cents for instrument tuning use the same `cents_between` rule as universal
tuning. Native platforms may select UI rows but may not calculate musical targets.

The new wind surface models a `WindVariant` as key/size × hole system and generates a
`WindChart` for that variant, one of 12 tube-solfège degrees, and a diatonic/chromatic
scope. Each `WindFingering` carries a stable fingering ID, pitch semitones,
base-fingering semitone, register, fingering text, hole states,
`fingering_kind` (ordinary or cross/half-hole marker),
`anchor_hole` (topmost open hole),
note/MIDI/frequency, solfège, scale membership, and overblown state.

- Dongxiao exposes G/F × eight/six holes in an order that makes eight-hole the default.
  Holed variants return 12 `TongyinOption` values. `fundamental_midi` matches real
  instruments: G is `d1 = 62` and F is `c1 = 60` (fixed 2026-08-25; the presets were an
  octave low at 50/48, so playing the low range hit the middle column). UI keeps three ranges:
  **Low (soft breath)**, **Middle (overblown)**, and
  **High (forceful breath)**. Core expands every base pattern into the three ranges
  (+0/+12/+24 semitones) and takes **the pattern measured for each pitch**: eight holes
  return 19 `Scale` and 32 `Chromatic` entries, six holes fewer. Pitches the charts do not
  cover are omitted and clients leave those cells visually blank.
- Upper ranges are not mechanical transpositions and must be transcribed cell by cell.
  Eight-hole data comes from the [eight-hole xiao chart](https://simumis.com/posts/xiao/)
  (32 columns, 0–31 semitones): the middle range reuses low patterns except at 22
  semitones, and the high range (24–31) has its own patterns. Six-hole data keeps only the
  values on which the five tube-note columns of
  [donsiau.net](http://donsiau.net/notedr.htm) agree; that chart notes extreme-high
  fingerings vary by instrument, so the conflicting 18, 23, 28, and 30 semitones stay
  blank. Core must not fill gaps beyond the charts by copying low holes or adding
  octave/fifth offsets to synthesize holes, note names, or frequencies. Inaccessible paid
  Quark pages are not verified evidence.
- Eight-hole `holes` use the primary form with auxiliary holes 2 and 6 closed. When the
  source chart permits either state, core selects `Closed`; it must not fall back to
  opening all eight holes in sequence. Normal auxiliary-hole closure remains ordinary,
  while only genuinely crossed patterns return `Combination`; half holes remain explicit.
- Main clients pass only natural degrees `[0,2,4,5,7,9,11]` to `Scale` and wrap across
  seven positions. Detail independently uses all 12 degrees with `Chromatic`; the two
  views must not share one mutable tube-degree state.
- For one variant, changing `tongyin_degree` keeps all returned `Chromatic` fingering IDs,
  holes, and note names stable while changing solfège/tonic fields. `Scale` refilters
  seven sorted bases as `(gong-scale step - tongyin_degree) mod 12`, so Low
  always contains complete `1–7`: as 5 uses `[0,2,4,5,7,9,10]`, and as 2 uses
  `[0,2,3,5,7,9,10]`.
- UI renders only one current target's `holes` on one complete large dongxiao.
  Each emitted `WindFingering` becomes a tappable base-row × range-column cell; a range
  with no entry for that row stays blank and non-tappable. Base rows align strictly to the
  center of the topmost open hole exposed by `anchor_hole`; all-closed anchors at the
  outlet, and a physical guide without a diatonic base pattern remains blank.
  `fingering_kind` may produce only a small 叉 or 半 marker, never a separate lane.
  Note name and solfège remain separate fields and tap areas. Tapping a cell pins its
  complete fingering to the diagram over live detection; tapping it again releases the pin.
- `holes` is core's primary fingering example, not a claim of one authoritative
  semitone fingering. Instrument and school differences may use alternatives.
- Zhudi, dongxiao, and shakuhachi all use this surface and the same client table: one
  complete instrument drawing plus three range columns (2026-09-29). The six-hole zhudi has
  all six holes on the front and opens holes in sequence like the six-hole xiao, except that
  10 semitones (the "4" when the tube sounds 5) uses the common dizi cross fingering
  "open holes 1, 2, 3 and 6". In the middle range, 12 semitones opens only hole 6 and 22
  semitones switches to a cross fingering; the high range lists only the consistently
  documented 24 semitones and leaves the other high cells empty. The shakuhachi is a
  fixed-scale variant: five holes (four front, one back), split by octave into otsu / kan /
  daikan, with 11 `Scale` entries. Its chromatic tones come from meri/kari embouchure
  shading rather than hole patterns, so `supports_tongyin` and `supports_chromatic` are
  both false, `tongyin_degree` and `Chromatic` are ignored, and clients offer neither the
  solfège-drag transposition nor the 12-tone detail.

Legacy API removal (2026-09-29): `list_fingering_charts`, `FingeringChart`, and
`FingeringNote` are deleted. All three clients now build their wind panels from
`list_wind_variants` plus `wind_fingering_chart`. The old shape only carried the
tube-degree 5/1/2 lists and could not express holes, ranges, or chromatic detail, so
keeping it would leave two diverging wind paths.

## 7. Metronome

Tempo range is 30–250 BPM. Beat unit supports 2, 4, or 8 and bar length 1–12. Every
render call returns PCM plus exact `sample_offset` tick events. Over 1000 ticks,
position error is <1 sample. Tempo changes affect the next scheduling decision without
resetting the bar; muted beats emit timing events but no click samples. Tap tempo
ignores invalid/outlier intervals and requires at least two taps.

Clicks are voices that continue across render calls (revised 2026-08-26). Timbres are
routinely longer than one render buffer — the bell is 250 ms (11025 samples) while the
Apple pump asks for 1024 frames at a time — so each tick claims one of twelve fixed voice
slots (no allocation on the hot path) and its remaining tail is mixed from the start of
each following buffer until the sample ends. Voices overlap when a timbre outlasts the
tick interval; when all slots are busy the most decayed voice (largest `pos`) is stolen.
`stop()` mutes immediately and drops every tail, matching platform pause, which discards
already-queued buffers. Previously each click was clipped to `min(sample length, frames
left in buffer)`, so long timbres such as the bell were cut down to a single thud.

## 8. Test baseline

`cargo test` must remain green and cover:

- 80–1500 Hz synthetic sweep at ±0.5 cent under the defined harmonic/noise fixture.
- Note/cents and enharmonic boundaries.
- Every solfège system, tonic, and mode.
- Gate acquisition, hysteresis, indefinite holding, and replacement by a new note.
- Spectrum bounds, measured partials, chords, and silence.
- All temperament tables and ordered 80–1500 Hz reference tones.
- Instrument counts, ordering, MIDI/frequency, and customary solfège; dongxiao G/F ×
  eight/six holes, eight-hole default, closed-tube pitch `D4`/62 for G and `C4`/60 for F,
  12 tube positions, three ranges expanded from
  measured patterns (19 `Scale` and 32 `Chromatic` entries for eight holes), middle-range
  holes matching the low range except at 22 semitones while the high range differs,
  chart-uncovered cells omitted with no synthetic frequencies or fingerings, single-large-diagram
  target priority, stable row anchors and blank guides, solfège-only transposition
  stability; six-hole zhudi patterns (the 10-semitone cross fingering, the 12/22-semitone
  middle-range exceptions, only 24 semitones in the high range) and range solfège; and
  shakuhachi five holes, 11 entries split otsu/kan/daikan by octave, with no transposition
  or chromatic detail.
- 1000 metronome ticks, tempo changes, accents/mutes, tap outliers, and finite samples.

## Appendix A — UniFFI contract

The checked-in UDL/generated surface is the only native API. Rust uses snake_case;
generated bindings map names to platform conventions. The contract contains:

```text
namespace tunar_core {
  sequence<Instrument> list_instruments();
  sequence<Tuning> list_tunings(string instrument_id);
  sequence<WindVariant> list_wind_variants(string instrument_id);
  WindChart? wind_fingering_chart(
    string variant_id,
    u8 tongyin_degree,
    FingeringScope scope
  );
  f64? cents_between(f64 freq_hz, f64 target_hz);
  string solfege_for_midi(SolfegeSystem system, KeyMode key, i32 midi);
}

enum InstrumentKind {
  String, Wind
}

dictionary Instrument {
  string id;
  string display_name;
  InstrumentKind kind;
}

dictionary StringSpec {
  u32 index;
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

enum HoleMark { Closed, Open, Half }
enum FingeringScope { Scale, Chromatic }
enum FingeringKind { Sequential, Combination }
enum WindRegister { Low, Middle, High }

dictionary TongyinOption {
  u8 degree;
  string solfege;
  boolean common;
}

dictionary WindVariant {
  string id;
  string display_name;
  string key_id;
  string key_name;
  string hole_system_name;
  u8 hole_count;
  u8 back_hole_count;
  i32 fundamental_midi;
  string fundamental_note_name;
  boolean supports_tongyin;
  boolean supports_chromatic;
  sequence<TongyinOption> tongyin_options;
  u8 default_tongyin_degree;
}

dictionary WindFingering {
  i32 fingering_id;
  i32 semitones;
  i32 base_semitones;
  WindRegister register;
  string label;
  sequence<HoleMark> holes;
  FingeringKind fingering_kind;
  u8? anchor_hole;
  string note_name;
  i32 midi;
  f64 freq_hz;
  string solfege;
  boolean in_scale;
  boolean overblown;
}

dictionary WindChart {
  string variant_id;
  string variant_name;
  u8 tongyin_degree;
  string tongyin_solfege;
  u8 tonic_pc;
  string tonic_name;
  string key_display;
  u8 hole_count;
  u8 back_hole_count;
  // Ordered by base fingering, each with its per-range measured pattern; clients leave
  // cells the charts do not cover blank.
  sequence<WindFingering> notes;
}

enum SolfegeSystem { FixedDo, MovableDo, Numbered, Chinese }
enum ModeKind { Gong, Shang, Jue, Zhi, Yu, Major, Minor }

dictionary KeyMode {
  u8 tonic_pc;
  ModeKind mode;
}

dictionary TunarConfig {
  f64 sample_rate;
  u32 frame_hop_samples;
  f64 a4_hz;
  f32 noise_gate_dbfs;
  SolfegeSystem solfege;
  KeyMode key;
  u8 temperament;
}

enum SignalState { Quiet, Acquiring, Tracking, Holding }

dictionary TunarEvent {
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
  TunarEvent? tuner;
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

interface TunarEngine {
  constructor(TunarConfig config);
  TunarEvent? feed(sequence<f32> pcm);
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

The 2026-08-25 contract adds `HoleMark`, `FingeringScope`, `FingeringKind`,
`WindRegister`, `TongyinOption`, `WindVariant`, `WindFingering`, `WindChart`,
`list_wind_variants`, and
`wind_fingering_chart`.

2026-09-29 removes `list_fingering_charts`, `FingeringChart`, and `FingeringNote` (see the
end of §6). Shakuhachi variants now carry their own hole table, report `hole_count = 5` and
`back_hole_count = 1`, and fill `register` by octave; the zhudi uses a dedicated six-hole
dizi pattern table.

Global queries and object methods are defined by the checked-in UniFFI surface. Any
signature/type change first updates this appendix in English and `../spec-core.md` in
Chinese, then regenerates all bindings; generated binding files are never hand-edited.
