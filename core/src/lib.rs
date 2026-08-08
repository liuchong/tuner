//! tunar-core：跨平台调音器 + 节拍器共享核心。
//!
//! 模块划分与 `docs/spec-core.md` §2 一致；对外接口只有 `api` 模块（UniFFI 合同见附录 A）。

pub mod api;
pub mod fingering;
pub mod metronome;
pub mod note;
pub mod pitch;
pub mod reference;
pub mod signal;
pub mod smooth;
pub mod solfege;
pub mod spectrum;
pub mod tuning;

uniffi::setup_scaffolding!();
