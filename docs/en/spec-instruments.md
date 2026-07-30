# spec-instruments — instrument preset data

Preset rows store note/MIDI data. Frequencies are converted using the active A4
calibration. Returned preset solfège follows the instrument's customary key and does
not change with the global solfège setting; only live `TunerEvent.solfege` does.

Wind model: closed-tube MIDI = key tonic − 7. Zhudi/dongxiao offsets are
`[0,2,4,5,7,9,11,12,14,16,17,19,21,23,24]`; shakuhachi offsets are
`[0,3,5,7,10,12,15,17,19,22,24]`.

## 1. Guitar (`guitar`, six strings, string 1 highest)

| Tuning ID | Name | Strings 1→6 |
|---|---|---|
| `standard` | Standard | E4 B3 G3 D3 A2 E2 |
| `drop_d` | Drop D | E4 B3 G3 D3 A2 D2 |
| `open_g` | Open G | D4 B3 G3 D3 G2 D2 |
| `dadgad` | DADGAD | D4 A3 G3 D3 A2 D2 |
| `half_step_down` | Half-step down | Eb4 Bb3 Gb3 Db3 Ab2 Eb2 |

## 2. Ukulele (`ukulele`, four strings)

| Tuning ID | Name | Strings 1→4 |
|---|---|---|
| `standard` | Standard (High G) | A4 E4 C4 G4 |
| `low_g` | Low G | A4 E4 C4 G3 |
| `d_tuning` | D tuning | B4 F#4 D4 A4 |
| `baritone` | Baritone | E4 B3 G3 D3 |

## 3. Guqin (`guqin`, seven strings, string 1 lowest/outermost)

| Tuning ID | Name | Strings 1→7 | Customary solfège |
|---|---|---|---|
| `zhengdiao` | Orthodox tuning | C3 D3 F3 G3 A3 C4 D4 | 5 6 1 2 3 5 6 |
| `mansanxian` | Slow third string | C3 D3 F3 G3 A3 C4 D4 | F reference |
| `jinwuxian` | Tight fifth string | C3 D3 F3 G3 A#3 C4 D4 | B-flat mode |
| `manjiao` | Manjiao | C3 D3 Eb3 G3 A3 C4 D4 | lowered third |

The orthodox F reference treats F as scale degree 1: C D F G A c d =
5 6 1 2 3 5 6. `manjiao` uses Eb3 for the third string.

## 4. Zhudi (`zhudi`, six holes)

Keys: D qudi, G bangdi, F, C, and E. Each key supports tube note as scale degree 5,
1, or 2. A chart covers the closed tube through fully open and overblown notes across
approximately two octaves (15 ascending entries).

## 5. Dongxiao (`dongxiao`, eight holes)

Keys G and F; each supports tube note as scale degree 5, 1, or 2. It uses the same
ascending two-octave model as zhudi with instrument-specific fingering names.

## 6. Shakuhachi (`shakuhachi`, five holes)

Models: 1.8 (D, closed D4), 1.6 (E), 2.0 (C), and 2.4 (A). The 1.8 basic pentatonic
sequence is D F G A C (ro-tsu-re-chi-ha), including meri/kari labels and two octaves
plus the upper register for 11 entries.

## Data integrity

- Every string tuning has the correct string count and matches A4=440 note frequencies
  within ±0.1 Hz.
- Every wind instrument exposes at least two keys/models and two tube-note options;
  notes are strictly ascending without duplicate note names.
- New instruments or tunings update this specification before implementation and add
  core tests.
