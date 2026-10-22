# Practice workspace verification

Date: 2026-10-23. Baseline: `3325017`; tested source is the final implementation in the commit containing this report. Design, test sources and implementation were completed before unified verification. P1/P2 ships; P3 fingering schools, reference timbres and interval guides remain future work.

Rust owns locked targets, true cents, bounded recording, review, immutable configuration segments, time-weighted selection statistics, independent snapshots and JSON/CSV. The limits are 10Hz, 6000 points, 600 seconds and 32MiB; hitting a limit pauses without overwriting data. Returning to live does not resume recording. Reference output and guarded tail frames are excluded from scores.

Apple and Android use the shared session contract with native lifecycle and file access. The desktop provides Tune/Practice/Analyze tasks, a wide inspector or narrow details sheet, and pinned recording controls. Existing professional tools remain accessible. Files contain measurement data; PCM recording and audio playback are outside scope.

## Automated results

**327 passed, 0 failed, 0 skipped**. [Every test and duration](../verification/practice-workspace/test-results.json) is recorded separately.

| Layer | Passed | Evidence scope |
|---|---:|---|
| Rust units | 135 | Targets, states, mathematics, capacity, strict codec and existing core regression |
| Rust integration | 9 | Real synthetic PCM through DSP/session/files; +3/-7/+100/+1200 cents and A4=442 |
| iOS XCTest | 72 | Simulator; real bindings/models, background publication, capture gates and existing regression |
| macOS XCTest | 22 | Apple Silicon; layout breakpoints, lifecycle and shared model |
| Android JVM | 83 | Presentation, metadata, failure/retry and existing regression |
| Android native integration | 6 | API 36 emulator; JNI/DSP, file/model/Compose review, actual navigation/target persistence and existing fingering semantics |

Rust formatting, all-target clippy with warnings denied, doctests, Android debug assembly/lint, iOS simulator tests and unsigned device build, macOS ARM tests and Intel build pass. Generated Swift/Kotlin and all Apple slices/three Android ABIs were rebuilt. Android native libraries are generated/ignored artifacts; fresh checkouts must run the core build script first.

Rust exact-case wall times include process startup; XCTest/JUnit durations come from their frameworks. Build/preparation times are separate. These timings do not establish a performance improvement. Build/test and self-built application processes ran inside the controlled process tree and were reaped.

## Native acceptance and repairs

The desktop imported a 117-point fixture produced by real Rust DSP. It displayed +1200.0 cents for 880Hz against a locked 440Hz target, an off-scale indicator, normalized current/reference spectra and preserved historical A4=440 versus the final A4=442 segment. System save panels produced complete JSON and selected CSV: all 117 IDs/sample positions/times/cents survived; baseline and selection persisted. IDs3–19 exported17 rows with matching times. Statistics showed1.60 valid seconds,100% coverage/accuracy and +3.0-cent median/P10/P90.

[Native screenshots and detailed Chinese report](../verification/practice-workspace/report.md) and the [A1–A12 matrix](../../integration_test/practice_workspace.md) separate executed evidence from remaining conditions.

Unified verification found and repaired Swift 6 actor isolation on background packets, missing macOS selected-file entitlements, dropped/short-read capture timing, asynchronous capture failure/retry state, historical/selection restoration, reference-tail exclusion, stale cursors under rapid keyboard commands, file-metadata capacity accounting and duration-budget reset after reopening. The latter two defects were reproduced with failing regressions before correction; the final suite passes.

Final native desktop checks also reopened the saved JSON with117 points/baseline/selection intact.
Eight rapid arrow commands advanced the cursor from81 to89; corrupt JSON displayed an error without
losing the117 points; clearing retained the named snapshot. Narrow/low-height windows retained
scrollable content, fixed recording controls and a working details sheet.

## Limits of this evidence

Physical instruments and phone microphones, all OS interruption/hot-plug scenarios, all SAF provider failures, dark-mode/large-text/reduced-motion combinations and a dedicated full-capacity memory trace remain unverified. Intel macOS and physical iOS were built, not run. Synthetic fixtures, emulator permissions and lifecycle tests are not physical-device acceptance; composite checklist items remain open where evidence is incomplete.
