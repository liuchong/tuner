# Practice workspace implementation and acceptance

Baseline: `3325017`, master; clean at start. Scope is the approved P1/P2 (M11/M12) design in [practice workspace](practice-workspace.md), A1–A12. P3 stays a future candidate. No automatic goal is started.

The [Chinese construction manual](../practice-implementation.md) contains the exact before/after trees, data-flow diagrams, file-to-acceptance map and complete Rust signatures. This document tracks the same scope and acceptance state.

## Contract

`PracticeSession` owns target configuration, bounded full analysis frames, scalar trace pages, independent snapshots, one display cursor and time-weighted statistics. `PracticeConfig` locks one of a temperament step, MIDI note or absolute Hz. `display_config()` returns historical context; `target_frequency()` resolves a locked target even without an input signal. `PracticeStatus` includes state, count, review ID, pause reason, byte budget, snapshot count, baseline ID and optional selection start/end IDs.

Audio-position time, capture generation and configuration travel together. Ring slots and Android short reads retain actual cumulative capture positions; backgrounding stops hardware even if existing page subscriptions remain. Recording decimates into 10Hz audio-time buckets and pauses at 6000 points, 600 seconds or 32MiB. The byte budget reserves 24KiB for array separators, worst-case configuration escaping and the file header, so a capacity stop remains exportable. The live scalar trace retains up to 512 points/12 seconds. Review pauses recording; return-to-live does not resume. Restart, configuration, reference-output and discontinuity boundaries never join trace segments. Held and reference-output frames do not contribute to statistics. Quantiles and in-tune fractions use linear interpolation weighted by valid time, not frame counts.

JSON schema 1 contains points, immutable segments/configurations, up to eight independent snapshots, baseline and review references, current target configuration and a validated selection interval. Summarizing a range stores its ordered point IDs; native views restore those IDs after import. Decode validates the entire file before replacing current state. Native file dialogs write only after encoding succeeds and surface provider errors; filesystem replacement atomicity is provider-dependent. CSV exports scalar values and empty cells for missing values, with spreadsheet formula escaping. No PCM or audio playback is provided.

## Construction order

- [x] A: complete design, exact API and file map.
- [x] B: write Rust unit/integration tests, Apple model tests, Android presentation/metadata tests and native A1–A12 steps; do not run.
- [x] C: implement Rust, shared Apple workspace, desktop layout, mobile entries, capture packets, Android workspace/navigation and bilingual current specifications.
- [x] D: regenerate bindings/libraries, run unified builds and layered tests, inspect native behavior/layout, review diff, sign commit and push.

C01 Rust; C02 capture events; C03 Apple model/UI; C04 desktop ownership/tasks; C05 iOS entry/lifecycle; C06 Android state/UI/files/navigation; C07 test sources; C09 bilingual documentation are written. C08 bindings/libraries has been regenerated for all targets. Source completion is separate from runtime acceptance; current results and environment gaps are in the [verification report](practice-verification.md).

## Verification

Rust units are in `core/src/practice_tests.rs`; real DSP-to-session-to-file integration is in `core/tests/practice_workflow.rs`. `scripts/verification/run-rust-timed.py` reuses build products and records every test duration separately by layer. Apple XCTest results use xcresult timing; Android uses JUnit XML. Android instrumentation adds real JNI/DSP, model/file round trips and Compose cursor checks. Native A1–A12 steps are in `integration_test/practice_workspace.md` and must not be substituted by unit tests.

Build/test processes run under the controlled executor. Local logs live under the ignored `docs/verification/practice-workspace/local/`; committed evidence summaries contain no machine paths. Completion claims require current evidence, with hardware/environment gaps stated separately.

Final results:327 automated tests pass. Native acceptance and remaining environment gaps are in the [report](practice-verification.md). Git history and the remote establish signature/push status.
