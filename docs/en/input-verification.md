# Input policy validation

Baseline `67ecbd6`; the delivered commit contains the final implementation and [full report](../verification/input-behavior/report.md). All implementation preceded unified validation. Findings from that phase were fixed and affected checks rerun.

**374 tests passed, none failed or skipped:** Rust 155 unit/23 integration; iOS 76; macOS 26; Android 87 JVM/7 native JNI and Compose. [Per-case timings and results](../verification/input-behavior/test-results.json) separate process wall time from XCTest/JUnit framework time. The release performance run reuses a test ID and is not counted twice.

All generated platform libraries, Android debug APK/lint, unsigned iOS device build, and macOS Apple Silicon/Intel builds succeeded. Rust fmt/clippy/doc checks passed. Android lint retained 45 dependency/build/style warnings with no errors. Xcode command/result-bundle success is recorded separately from controlled-run cleanup: leftover helpers were reclaimed, with cleanup complete, rather than treating an outer exit 125 as a test success.

Release analysis of 64 windows at 44.1 kHz measured approximately 1.00 ms median, 1.11 ms P95 and 1.13 ms maximum against a 23.22 ms hop. These are local DSP timings, not physical end-to-end latency or a guarantee on every device.

Native desktop validation found 4800-sample hardware batches. A fixed 16-slot ring and bounded startup drain preserve hops; both Apple targets test a 10000-sample batch. Cold calibration from settings completed on the current macOS input. Calibration/progress/cancel, preference retention, lease handoff and stale-callback rejection were checked. Response was restored to Balanced. [Desktop settings](../verification/input-behavior/desktop-settings.jpg), [desktop practice](../verification/input-behavior/desktop-practice.jpg), [Android controls](../verification/input-behavior/android-input.png) and [Android review](../verification/input-behavior/android-analysis.png) record their stated native/test-fixture boundaries.

Remaining physical acceptance includes instruments across 80–1500 Hz, attack/breath/reverb, room/gain false positives and misses, real phone permission/device changes, headphone/speaker pickup, physical display latency and the full large-text/reduced-motion matrix. Periodic uncalibrated hum may still resemble a note; calibration requests confirmation for persistent tones. Speaker metronome use displays risk rather than claiming echo cancellation. Valid measurement duration is not breath duration.

The iOS simulator build and 76 native tests pass. This host has no Simulator.app GUI, so interactive simulator screenshots were unavailable and are not counted as passed.

The final import fix restores cached configuration from the recorded frame segment. A different current target keeps the old reading as a previous-condition result. The existing round-trip test now checks the last frame and returning to live as well.
