# Input implementation ledger

The [authoritative construction ledger](../input-implementation.md) binds M13 to baseline `67ecbd6`, its exact APIs, file map, tests and completion checkboxes.

All production changes were written before starting the unified verification phase. The shared Rust core owns thresholds, audio-time states, pitch confidence, display smoothing, retention and file provenance. Android, iOS and macOS own audio, permissions, preferences and native presentation.

Current execution results and physical-device gaps belong in the [validation report](input-verification.md).

Validation fixes include fixed-deadline 20 Hz calibration sampling, explicit input-recovery text, a 16-slot Apple ring for hardware batches, and generation checks before native delivery. Stopped callbacks cannot restore live status or resume recording.
