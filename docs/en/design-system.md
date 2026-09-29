# TUNAR design system — Aurora v4.1

> Cross-platform source of truth for Android Compose and Apple SwiftUI. A UI
> implementation that conflicts with this document is a defect.

## 1. Design philosophy

**Hold stage light in your hand.** Tuning is part of playing. The screen responds to
intonation like light: coral when flat, amber when near, and mint aurora when in tune.
Richness comes from meaningful light, depth, and motion—not decoration.

Principles, in priority order:

1. **Readable at a glance:** from about 60 cm away, show flat/sharp and the amount.
   Dial graphics and text readouts never overlap.
2. **Reactive Aurora:** background color, position, and energy reflect tuning state.
3. **Rich layers, strict order:** rear aurora, middle instrument, foreground readout;
   align content to a 24 dp grid.
4. **Reward accuracy:** a tuned note receives light bloom, haptics, and a green aurora.

Avoid childish decoration, engineering-dashboard clutter, obsolete skeuomorphism, and
empty screens. Every visible effect must communicate state.

## 2. Color

### 2.1 Base palette

| Token | Dark | Light | Purpose |
|---|---|---|---|
| `bg/canvas` | `#0A0D17` | `#F6F7FA` | Page background |
| `bg/surface` | `#171C29`→`#1E2536` | `#FFFFFF` | Cards and controls |
| `bg/surface-raised` | `#232A3C` | `#FFFFFF` | Sheets and overlays |
| `ink/primary` | `#F2F5F9` | `#14181F` | Primary text |
| `ink/secondary` | `#9AA4B2` | `#5A6472` | Secondary values |
| `ink/faint` | `#525C6B` | `#A8B0BC` | Ticks and idle state |
| `line/subtle` | `#2A3242` | `#E3E7ED` | Tracks and grids |
| `accent` | `#7C9CFF` | `#3B5BDB` | Interaction |

### 2.2 Tuning semantics

| Token | Dark | Light | Rule |
|---|---|---|---|
| `tune/in` | `#34E0A1` | `#0E9F6E` | \|cents\| ≤ 5 |
| `tune/near` | `#FFC24B` | `#D97A00` | 5 < \|cents\| ≤ 15 |
| `tune/off` | `#FF6B6B` | `#E02424` | \|cents\| > 15 |

Semantic colors are reserved for intonation. Color, left/right position, and text
encode the same state so the interface remains color-blind safe. Contrast is WCAG AA.

Dark mode uses a state-colored aurora at 10–14% opacity and a constant accent aurora
at 5%. Light mode uses a very pale semantic gradient. Aurora movement is slow, limited
to ±3% screen width, and never competes with the needle.

## 3. Type and spacing

| Style | Size / weight | Use |
|---|---|---|
| `display/note` | 100 sp / Bold | Main note |
| `display/bpm` | 72 sp / Bold | Tempo |
| `readout/value` | 18 sp / Medium | Hz and cents |
| `readout/solfege` | 20 sp / Medium | Solfège |
| `label` | 14 sp / Medium | Controls |
| `caption` | 12 sp / Regular | Supporting labels |

Use a 4 dp base scale: 4/8/12/16/24/32/48. Page margins are 24 dp and the bottom bar
is 64 dp. Tap targets are at least 48 dp/pt. Panels remain 75–90% occupied: no empty
vertical area above 15% screen height and no control spacing below 8 dp. Overlays must
not reserve empty layout space when dismissed.

The tuner page retains this vertical order and its current proportions: dial, note,
solfège/key controls, measured values, compact spectrum, partials/chord, status chip.

## 4. Core components

### 4.1 Halo dial

The dial contains an outer number ring (-50/-25/0/+25/+50), a 140-degree tick and
semantic arc, a progress light arc, one solid needle, and an in-tune light pool. It
never contains note text in its center. Only the current needle is drawn; historical
needles or motion trails are forbidden. Low clarity reduces opacity. Before the first
trusted result, hide the needle; afterward, silence holds its last trusted position.

### 4.2 Readouts and key selector

The note sits 8 dp below and separately from the dial. Solfège and key are equal-height
pills. The key pill opens an anchored, raised panel with 12 tonic choices and
Major/Minor/Gong/Shang/Jue/Zhi/Yu modes. Selection persists and applies immediately.
Fixed Do disables key selection. Frequency, cents, and clarity use three compact pills.

### 4.3 Compact spectrum and measured peaks

The compact strip is real core FFT data: logarithmic 60–2400 Hz, 64 measured bins,
-80 to -10 dB mapped to height. H1 uses current tuning color; measured H2+ use
secondary ink; independent peaks are hollow dots. Every flag includes frequency to one
decimal place. Labels alternate lanes and clamp inside edges. Never synthesize flags
for theoretical harmonics absent from `partials`. The entire strip opens professional
analysis without resizing the tuner layout.

### 4.4 Partials and chord

Partial chips show H2/H3… deviation from the pure harmonic; ±5 cents receives an
in-tune outline. The chord pill shows a core-provided chord when at least three pitch
classes are detected, otherwise an em dash. Quiet state dims the row.

### 4.5 Instrument controls

The instrument switcher is six equal-width tiles with no scrolling: 56 dp/pt high,
14 radius, a 24 line-art glyph above an 11 name. The current tile has a 12% `accent` fill,
a 1.5 accent outline, and accent glyph and text; other tiles use `bg/surface` with
`line/subtle`.

Tuning, key, and model dropdowns and segmented controls are 48 dp/pt high, capsule
shaped, with 14–16 dp/pt horizontal padding, one line with ellipsis, and a 6–8 dp
icon/text gap. Narrow layouts wrap the group; text never wraps inside a control and no
control is squeezed. The selected segment is solid `accent` with `bg/canvas` text. The
auto-string toggle is a capsule of the same size: solid `accent` with a waveform icon
when on, `bg/surface` with a finger icon when off. Every press scales to 0.96 for 120 ms.

String buttons are 14-radius cards with a round string-number badge, a bold note, and
small solfège. The current string has a 1.5 `accent` outline and 14% `accent` fill; an
in-tune string switches to `tune/in` and its badge becomes a check.

**Instrument line art** (guitar and ukulele headstocks, guqin, zhudi, dongxiao,
shakuhachi) is single-color line drawing in the same stroke language as the switcher
glyphs: outlines around 50% `ink/primary`, guides (frets, hui, dashed leaders) around 16%,
no fills, no realism, no shadows. The current string is drawn in accent (`tune/in` when in
tune), about 1.5–2 dp thicker, with a 20% halo of the same color; other strings stay thin.
Back holes and the dizi membrane hole use the dedicated `tune/near` color.

- Headstocks sit centered with the string buttons on both sides. A thin dashed leader
  joins each button to its peg; buttons follow their pegs vertically. Colliding buttons on
  one side are spread evenly around the mean of their peg heights (pitch = button height +
  4 dp/pt, compressed evenly only when space runs out). The selected button stacks on top,
  then the auto-detected string. The guitar has 6-in-line and 3+3 outlines; the ukulele is 2+2.
- The guqin lies horizontally below the seven string buttons and above the dial, scaled
  to the width, with string numbers at the tail end.

**Wind table**: shared by the zhudi, dongxiao, and shakuhachi. The dongxiao has G/F keys
with eight holes by default and a six-hole switch; the zhudi has a single six-hole system;
the shakuhachi has five holes. The diatonic main chart keeps seven base-fingering rows (the
basic-scale rows for the shakuhachi) and chromatic detail independently keeps 12. All use
three range columns (Low/Middle/High for the zhudi and dongxiao, otsu/kan/daikan for the
shakuhachi), each carrying the pattern measured for that range; cells the charts do not
cover are visually blank and non-tappable. The shakuhachi shows neither the solfège-drag
hint nor the 12-tone entry.

The view shows exactly one complete large vertical instrument drawing on the left as
its major visual. For the dongxiao and zhudi three columns sit on the right:
**Low (soft breath)**, **Middle (overblown)**, and **High (forceful breath)**; for the
shakuhachi they are **otsu / kan / daikan**.
Fingerings, notes, and frequencies for chart-uncovered cells must not be synthesized by
copying Low holes or adding octave/fifth offsets.

Every base-fingering row shares the exact horizontal coordinate of the topmost open
hole identified by its `anchor_hole`. A thin guide crossing the tube and three columns
makes the shared coordinate explicit.
The all-closed row anchors at the bottom outlet. A physical-hole guide without a matching
diatonic base fingering remains visibly blank; adjacent cells do not stretch or move.
Eight-hole mode renders one tube with exactly eight holes; six-hole mode renders one
tube with exactly six.

The large vertical diagram follows playing orientation: mouthpiece at top, open end at
bottom, and the first hole near the bottom with a very slight sideways offset matching
common modern dongxiao placement. Closed holes are solid, open holes outlined, and
half-holes half-filled. Back/thumb holes remain on the tube centerline and use a
dedicated accent color; they must not shift sideways or gain a ring that resembles
another hole. It depicts one
complete instrument as the visual anchor beside the list, not a repeated hole strip.
The diagram is core's primary fingering example, not a claim of one authoritative
fingering; instrument differences may use alternatives.

Each cell uses a fixed note name and separate solfège badge; combined strings such as
`D3·5` are forbidden. Tapping either area pins the cell's complete fingering onto the
large dongxiao over live detection; tapping the same cell again releases the pin.
Cross-fingered and half-hole patterns use only a small 叉 or 半 corner marker, never a
separate lane.

The top “Tube note as X” label is display-only. Users vertically drag a solfège badge.
Main view wraps across seven natural degrees; chromatic detail independently wraps across
12 semitone degrees. Crossing a full step updates every badge
immediately with continuous motion; one sustained drag must reach “as 2” from default
“as 5.” Release commits to the nearest discrete step and snaps; arbitrary resting
positions are forbidden. Committing updates all
solfège badges, tonic, and display-only status. Chromatic detail stays geometrically
stable; the diatonic main chart atomically refilters its base rows to preserve complete
`1–7`.

Tapping blank space in the main-view title row opens detail. A visible “Chromatic
Detail” button provides at least a 48 dp/pt target and accessible name “Open chromatic
fingering detail.” The independent 12-semitone detail reuses the same one-dongxiao plus
three-range geometry. iOS presents it full-screen, Android in a full-screen dialog,
and macOS in a large sheet with a persistent close action. Main and detail share capture
but preserve independent scroll, preview, and interaction state; a pinned cell outranks
live recognition in both.

All three range columns fit when width permits. On small phones the large dongxiao
stays visible while only the register region scrolls horizontally, or columns
auto-collapse with an explicit restore action. The register region also auto-scrolls so the
live-hit column trends toward its center, with the displacement clamped to the legal scroll
range; pinning and manual scrolling never trigger it.

### 4.6 Metronome

The BPM ring supports vertical drag with elastic bounds. The pendulum follows beat
phase and eases at endpoints. Beat dots encode accent/normal/muted. The 72 dp play
button spans the content width. Time signature and sound choices use surface cards.

### 4.7 Navigation and Pro mode

Five tabs: Tuner, Instruments, Spectrum, Metronome, Settings. Pro is a mirrored
top-right outlined pill. When enabled, it reveals a temperament selector for
12/19/24/31 equal divisions and the related deviation readout; disabling it collapses
those additions without disturbing the base layout.

### 4.8 Reference-tone entry and sheet

The top-left tuning-fork control mirrors Pro in bounding box, top position, size, and
24 dp outer margin. Playback adds an accent tint. After a tone has been selected, a
same-width 24 dp quick stop/resume pill overlays unused space without moving content.

The raised modal sheet lists every core-provided 80–1500 Hz reference tone for the
active A4 and temperament. Each item is at least 48 dp/pt, with note and one-decimal
Hz. Dismissing the sheet does not stop sound, destroy capture, or re-layout the tuner.

### 4.9 Professional spectrum

Below the card title, a compact 30 dp/pt, 10-radius segmented control switches
Spectrum / Pitch Trace / Waveform. The selected segment uses 16% accent fill. A matching
range control appears only for Spectrum. Do not imitate piano-roll bands, gray note
stripes, or a separate bottom tool bar.

The current main plot uses logarithmic 60–2400 Hz and -80–0 dBFS. Grid labels are
60/100/200/500/1k/2.4k Hz and 0/-20/-40/-60/-80 dBFS. Live and peak-hold curves use
accent colors, not tuning semantic colors. Peak hold rises only when a stronger value
arrives and resets only through the dedicated reset action.

Pause freezes the main plot and waterfall. Reset clears only peak hold and preserves
pause state. Six 52 dp/pt summary cards show note, fundamental, cents, input, strongest
measured peak, and chord in two rows with 6 dp row spacing.

The waterfall uses the same frequency axis, newest row at top, time labels
Now/-3/-6/-9/-12 s, frequency ticks along the bottom, and a -80–0 dBFS legend at
right. Its palette is canvas → indigo `#3949AB` → purple `#8E5AC7` → cyan `#26C6DA`
→ yellow `#FFC857` → red `#E53935`. It displays 96 columns × 256 rows: 64 measured
bins are visually interpolated to 96 columns and one row is appended every two
analysis frames. Interpolation is not additional measurement.

Pitch Trace uses solid accent points and 2 dp connecting lines only for real tracking
frames; gaps remain empty. A thin in-tune guide may use `tune/in`, but the background
does not use large gray keyboard bands. Waveform uses a 28% accent envelope, a 1.5 dp
outline, and a subtle zero line. All three views share the same 280 dp/pt bounding box
and switch with a 150 ms cross-fade.

## 5. Motion, haptics, and accessibility

All transitions are ≤400 ms. Android needle uses stiffness 800/damping 0.72; iOS uses
50 ms ease-out with one continuously replaced target and no spring rebound. Aurora
color transitions are 300 ms and breathing is 6 s. Panel changes cross-fade for 150 ms.

Give one light tick when entering the in-tune region and two after 500 ms stable. Do
not vibrate when leaving. Haptics can be disabled. Screen readers announce note and
cents, controls expose current values, dynamic type supports 130%, and all information
has non-color encoding.

macOS presents six destinations (the five mobile tabs plus Tools) in a 200–240 pt sidebar. The selected row uses a
12% accent background and accent icon. Detail content remains at least 760 pt wide;
at 1100 pt and above related cards may use two columns, while narrower windows stack
them in reading order. Sidebar changes use only a 150 ms cross-fade.

## 5b. App icon

The mark reads as sonar plus tuner: a tapered needle rises from a pivot to exact 12
o'clock (the in-tune, zero-cent datum) while three concentric echo arcs spread outward.

- **Single source of geometry**: `android/design/icon-mark.svg` on a 1024 grid. Pivot at
  `(512,598)`; arcs at `r=190/285/380`, each sweeping 18° to 120° so together they form a
  240° gauge ring open at the bottom, with a ±18° slot at the top for the needle. Stroke
  widths `44/36/26` and opacities `1/0.74/0.42` decay outward. The bounding box is
  symmetric about the canvas center, so the mark stays centered under any mask.
- **Color**: inner arc `tune/in` `#34E0A1`, outer two `accent` `#7C9CFF`, needle a vertical
  `#7DF3C8 → #F2F5F9` gradient. The plate is a `#22315C → #121A2E → #080B14` gradient with
  an `accent` aurora halo over the arcs and a `tune/in` glow at the pivot. Arcs and needle
  use brand color only — no shadow, outline, or gloss.
- **Variants**: every other SVG only scales the mark about `(512,512)` and swaps the plate:
  Android adaptive foreground `0.71` (inside the central 66 dp safe circle, so no mask can
  clip it), legacy square `1.06`, legacy round `0.98`, iOS full-bleed `1.08`, macOS `0.85`
  (824 rounded square centered with a transparent margin).
- **Generation**: after editing any SVG, run `scripts/generate-icons.sh`. It emits the
  Android mipmaps (plus adaptive background drawable and the 512 Play Store icon), the iOS
  1024 icon, and the macOS 16–1024 set. Never hand-edit the PNGs.

## 6. Platform token mapping

| Concept | Android Compose | Apple SwiftUI |
|---|---|---|
| Color | `TunarColors` | same-named asset/token |
| Type | `TunarTypography` | `Font.system(...).monospacedDigit()` |
| Aurora | Canvas radial gradient | `RadialGradient` + `TimelineView` |
| Needle | fast spring | 50 ms ease-out |
| General sheet | `ModalBottomSheet` | `.sheet` |
| Wind chromatic detail | full-screen dialog | iOS full-screen; macOS large `.sheet` |
| Haptics | Compose feedback | UIKit/AppKit adapter |

Android reference implementations live under `ui/theme`, `ui/common`, `ui/tuner`, and
`ui/metronome`. Apple implementations must preserve the same behavior, not necessarily
the same container layout.
