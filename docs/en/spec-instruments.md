# spec-instruments — instrument preset data

Preset rows store note/MIDI data. Frequencies are converted using the active A4
calibration. Returned preset solfège follows the instrument's customary key and does
not change with the global solfège setting; only live `TunarEvent.solfege` does.

For holed winds, the variant fixes the closed-tube MIDI. Dongxiao uses three traditional
breath ranges: **Low (soft breath)**, **Middle (overblown)**, and
**High (forceful breath)**. The diatonic main chart dynamically filters seven base
semitones for its natural tube-note degree; chromatic detail independently uses `0–11`.
Each base hole pattern expands into up to three entries — Low (+0), Middle (+12), and
High (+24 semitones) — and each entry carries **the pattern the chart measures for that
pitch**. Pitches the charts do not cover are omitted and the client leaves the cell blank,
so the eight-hole xiao returns 19 `Scale` entries and 32 `Chromatic` entries (12 low, 12
middle, and the 8 high notes covered up to 31 semitones); the six-hole xiao returns fewer.
Shakuhachi retains `[0,3,5,7,10,12,15,17,19,22,24]`.

Upper ranges are not mechanical transpositions of the low range. The eight-hole data is
transcribed cell by cell from the
[eight-hole xiao chart](https://simumis.com/posts/xiao/) (32 columns, 0–31 semitones above
the closed tube): the middle range reuses the low patterns except at 22 semitones, which
takes a different fork, and the high range (24–31) has its own patterns. The six-hole data
comes from the five tube-note columns of
[donsiau.net](http://donsiau.net/notedr.htm), keeping only values the columns agree on;
that chart itself notes that extreme-high fingerings vary by instrument, so the conflicting
18, 23, 28, and 30 semitones stay blank. Anything past the charts (above 31 semitones for
eight holes) and any conflicting cell must never be synthesized. Semitone-by-semitone
patterns are likewise not claimed as the single authoritative answer: schools, instrument
construction, range, and performance practice may use alternatives.

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

The closed tube sits a perfect fourth below the third hole, which is the traditional
key-defining hole (`小工调`), and a dizi sounds one octave above a xiao of the same key
name: D qudi `a1 = A4` (69, 440 Hz), G bangdi `d2 = D5` (74, 587 Hz, whose third hole is
the defining `g2 = 784 Hz`), F `c2 = C5` (72), C `g1 = G4` (67, 392 Hz), and E alto dizi
`b1 = B4` (71). Source: Zhao Songting's flute frequency study as quoted in
[this article on the contrabass dizi](https://www.huain.com/article/guanzi/2022/0521/929.html).
Fixed 2026-08-25: all five keys were an octave low, and C wrongly used a fifth below the
tonic (F), so its tube note was not the key's degree 5 at all.

Current delivery boundary: zhudi keeps its existing key/tube-note selectors and
fingering-list interaction. It does not adopt the dongxiao-first hole-system switch,
draggable solfège badges, or separate chromatic detail yet. Core may expose the new
data surface without changing this client path.

## 5. Dongxiao (`dongxiao`, G/F, eight-hole first)

The new interaction ships for dongxiao first:

- G and F keys each provide eight-hole and six-hole variants. Eight-hole is the
  default; users may switch to six-hole.
- Closed-tube pitch follows real instruments: G is `d1 = D4` (MIDI 62) and F is
  `c1 = C4` (MIDI 60), so the three ranges span `d1–a3` (D4–A6) in G. Device fix on
  2026-08-25: the presets previously used D3/C3, one octave low, so playing the low
  range highlighted the middle column and the middle range highlighted the high column.
- Every holed variant exposes 12 closed-tube solfège positions:
  `1 #1 2 #2 3 4 #4 5 #5 6 b7 7`. Degree 5 is the default. Common markers aid
  navigation but do not disable the other positions. The top “Tube note as X” text
  only reports the current mapping and is never a selection control.
- The instrument-tuning main view has seven base-fingering rows and three columns:
  **Low (soft breath)**, **Middle (overblown)**, and
  **High (forceful breath)**. The columns sound +0, +12, and +24 semitones, and each cell
  carries the pattern the chart measures for that pitch: the middle range mostly reuses the
  low pattern while the high range differs, and cells the charts do not cover stay blank.
  Every populated cell is tappable. The
  seven base rows are filtered from the chromatic set for the current
  tube degree so Low always contains complete `1–7`. Tube note
  as 5 uses base semitones `[0,2,4,5,7,9,10]`, including `4` rather than `#4`.
- Separate chromatic detail independently has 12 base-fingering rows and the same three
  columns. It opens from blank title-row space or the explicit “Chromatic Detail” button
  rather than expanding the main view in place.
- Main and detail each render only the current fingering's `holes` on one complete
  large vertical dongxiao at left. The three range columns sit at right; fingering
  kinds do not create separate lanes. Special base fingerings may show a
  small 叉 or 半 marker in the corresponding cell.
- Eight-hole base patterns follow common auxiliary-hole practice: holes 2 and 6 are
  supplemental, and a source chart's “open or closed” positions use the closed form.
  Most diatonic fingerings therefore keep holes 2 and 6 closed instead of opening all
  eight in sequence. This normal auxiliary-hole closure must not be mislabeled as 叉.
  References: [Dongxiao Fingerings](http://donsiau.net/notedr.htm) and
  [Eight-hole Dongxiao Fingering Chart](https://simumis.com/posts/xiao/).
- Every base row aligns to its `anchor_hole`, with the all-closed row anchored at the
  bottom outlet and a thin guide crossing the tube and three columns. Every populated
  row aligns strictly to the center of its topmost open hole. A physical-hole
  guide with no matching diatonic pattern remains blank. An eight-hole variant renders
  exactly eight holes and a six-hole variant exactly six. Open, closed, and half states
  remain visible. The first hole shifts sideways only slightly to resemble common modern
  dongxiao placement; back holes stay on the tube centerline and use a dedicated color.
- Each cell has a fixed note name and a separate solfège badge; `D3·5`-style combined
  text is forbidden. Tapping either area pins that cell's complete fingering onto the
  diagram and wins over trusted live detection; tapping the same cell again releases the
  pin so the diagram follows live detection again, falling back to the lowest all-closed
  tube note when there is no signal. The two highlights must be distinguishable: a live hit
  is filled while a pinned cell is outlined only, and accessibility state reports
  “live hit” or “pinned” respectively. Hole marks always use neutral ink; a live hit only
  recolors the tube outline and never repaints the whole instrument in the accent color.
- The three-column area scrolls horizontally on its own so the live-hit range column moves
  as close to the area's center as possible, keeping the high column from staying offscreen.
  This is a trend algorithm: compute the displacement needed for centering, then clamp it to
  the legal scroll range, so at the edges it only scrolls as far as it legally can. Only a
  change of the live-hit range triggers it; pinning and manual scrolling never do, so it
  cannot fight the user for the scroll position.
- Users vertically drag a solfège badge. Main view wraps independently across the seven
  natural tube-note degrees `1–7`; chromatic detail wraps across all 12 semitone degrees.
  Crossing a full step updates all badges immediately with continuous motion; one sustained
  gesture must reach “Tube note as 2” from the default “as 5.”
  Release commits to the nearest discrete step and snaps; arbitrary resting positions are
  forbidden. Commit updates all badges, tonic, and display-only status. Chromatic detail
  keeps stable notes and holes, while the main chart atomically refilters its seven rows
  to preserve complete `1–7`.

## 6. Shakuhachi (`shakuhachi`, five holes)

Models: 1.8 (D, closed D4), 1.6 (E), 2.0 (C), and 2.4 (A). The 1.8 basic pentatonic
sequence is D F G A C (ro-tsu-re-chi-ha), including meri/kari labels and two octaves
plus the upper register for 11 entries.

Current delivery boundary: shakuhachi keeps its existing model and fixed-scale list.
It has no hole diagram, draggable 12-position solfège mapping, or dongxiao chromatic detail.

## Data integrity

- Every string tuning has the correct string count and matches A4=440 note frequencies
  within ±0.1 Hz.
- Dongxiao exposes G/F × eight/six holes, defaults to eight holes, and returns 12
  tube-solfège positions. Each range carries its own measured pattern and uncovered
  pitches are omitted: eight holes return 19 `Scale` and 32 `Chromatic` entries, six holes
  fewer. `fingering_id` is unique within each returned chart, equals the entry's pitch in
  semitones, and base semitones are ordered. A middle entry shares `holes` with its low
  entry (except at 22 semitones) while a high entry differs. Clients use a Low/Middle/High
  grid, keep uncovered cells blank, and never synthesize missing entries.
- Zhudi and shakuhachi retain their existing model and scale integrity; the dongxiao
  data extension does not change their interaction boundary.
- Main and detail each show exactly one complete large diagram traceable to core's
  primary fingering while allowing documented alternatives for instrument differences.
- New instruments or tunings update this specification before implementation and add
  core tests.
