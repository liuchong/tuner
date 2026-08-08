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

Instrument cards show icon and name. Tuning, key, and tube-note selectors are exactly
48 dp/pt high, 16 dp/pt radius, 16 dp/pt horizontal padding, one line with ellipsis,
and an 8 dp icon/text gap. Narrow layouts wrap the group, never text inside a control.
String pills show number, note, and solfège; wind charts highlight the current row.

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

macOS presents the five destinations in a 200–240 pt sidebar. The selected row uses a
12% accent background and accent icon. Detail content remains at least 760 pt wide;
at 1100 pt and above related cards may use two columns, while narrower windows stack
them in reading order. Sidebar changes use only a 150 ms cross-fade.

## 6. Platform token mapping

| Concept | Android Compose | Apple SwiftUI |
|---|---|---|
| Color | `TunarColors` | same-named asset/token |
| Type | `TunarTypography` | `Font.system(...).monospacedDigit()` |
| Aurora | Canvas radial gradient | `RadialGradient` + `TimelineView` |
| Needle | fast spring | 50 ms ease-out |
| Sheet | `ModalBottomSheet` | `.sheet` |
| Haptics | Compose feedback | UIKit/AppKit adapter |

Android reference implementations live under `ui/theme`, `ui/common`, `ui/tuner`, and
`ui/metronome`. Apple implementations must preserve the same behavior, not necessarily
the same container layout.
