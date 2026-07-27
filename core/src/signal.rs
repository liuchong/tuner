//! 调音输入状态机：门限滞回、连续帧确认和断音保持。
//!
//! `process` 路径不分配、不加锁、不使用平台时钟。

use crate::pitch;

/// 门限关闭值比开启值低的固定差值。
pub const GATE_HYSTERESIS_DB: f32 = 3.0;

/// 输入信号状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SignalState {
    /// 无可信信号。
    Quiet,
    /// 已有一帧可信音高，等待第二帧确认。
    Acquiring,
    /// 正在持续追踪可信音高。
    Tracking,
    /// 信号消失后保留最后读数。
    Holding,
}

/// 状态机内部保存的最小音高数据。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PitchSample {
    /// 频率（Hz）。
    pub freq_hz: f32,
    /// YIN 置信度。
    pub clarity: f32,
}

/// 单帧状态机输出。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SignalOutput {
    /// 当前状态。
    pub state: SignalState,
    /// 应显示的当前或最后有效音高。
    pub pitch: Option<PitchSample>,
    /// 0~1 显示强度。
    pub display_strength: f32,
    /// 当前是否为断音保持读数。
    pub is_held: bool,
}

/// 调音输入状态机。
pub struct SignalTracker {
    state: SignalState,
    open_gate_dbfs: f32,
    pending_valid_frames: u8,
    pending_pitch: Option<PitchSample>,
    last_pitch: Option<PitchSample>,
}

impl SignalTracker {
    /// 构造状态机。采样率和 hop 保留在接口中，以兼容既有 core 调用合同。
    pub fn new(_sample_rate: f64, _frame_hop_samples: u32, open_gate_dbfs: f32) -> Self {
        Self {
            state: SignalState::Quiet,
            open_gate_dbfs,
            pending_valid_frames: 0,
            pending_pitch: None,
            last_pitch: None,
        }
    }

    /// 更新开启门限；关闭门限固定为其下 3dB。
    pub fn set_noise_gate(&mut self, dbfs: f32) {
        self.open_gate_dbfs = dbfs;
    }

    /// 消费一帧检测结果。热路径不分配。
    pub fn process(
        &mut self,
        detected: Option<PitchSample>,
        input_level_dbfs: f32,
    ) -> SignalOutput {
        let required_gate = match self.state {
            SignalState::Tracking => self.open_gate_dbfs - GATE_HYSTERESIS_DB,
            SignalState::Quiet | SignalState::Acquiring | SignalState::Holding => {
                self.open_gate_dbfs
            }
        };
        let valid = if input_level_dbfs >= required_gate {
            detected
        } else {
            None
        };

        match self.state {
            SignalState::Quiet => match valid {
                Some(pitch) => {
                    self.state = SignalState::Acquiring;
                    self.pending_valid_frames = 1;
                    self.pending_pitch = Some(pitch);
                    self.output(None, 0.0, false)
                }
                None => self.output(None, 0.0, false),
            },
            SignalState::Acquiring => match valid {
                Some(pitch) => {
                    self.pending_valid_frames = self.pending_valid_frames.saturating_add(1);
                    self.pending_pitch = Some(pitch);
                    if self.pending_valid_frames >= 2 {
                        self.state = SignalState::Tracking;
                        self.last_pitch = self.pending_pitch;
                        self.pending_pitch = None;
                        self.pending_valid_frames = 0;
                        self.output(Some(pitch), 1.0, false)
                    } else {
                        self.output(None, 0.0, false)
                    }
                }
                None => {
                    self.reset_to_quiet();
                    self.output(None, 0.0, false)
                }
            },
            SignalState::Tracking => match valid {
                Some(pitch) => {
                    self.last_pitch = Some(pitch);
                    self.output(Some(pitch), 1.0, false)
                }
                None => {
                    self.state = SignalState::Holding;
                    self.pending_valid_frames = 0;
                    self.pending_pitch = None;
                    self.hold_output()
                }
            },
            SignalState::Holding => match valid {
                Some(pitch) => {
                    self.pending_valid_frames = self.pending_valid_frames.saturating_add(1);
                    self.pending_pitch = Some(pitch);
                    if self.pending_valid_frames >= 2 {
                        self.state = SignalState::Tracking;
                        self.last_pitch = self.pending_pitch;
                        self.pending_pitch = None;
                        self.pending_valid_frames = 0;
                        self.output(Some(pitch), 1.0, false)
                    } else {
                        self.hold_output()
                    }
                }
                None => {
                    self.pending_valid_frames = 0;
                    self.pending_pitch = None;
                    self.hold_output()
                }
            },
        }
    }

    fn hold_output(&self) -> SignalOutput {
        self.output(self.last_pitch, 1.0, true)
    }

    fn output(
        &self,
        pitch: Option<PitchSample>,
        display_strength: f32,
        is_held: bool,
    ) -> SignalOutput {
        SignalOutput {
            state: self.state,
            pitch,
            display_strength,
            is_held,
        }
    }

    fn reset_to_quiet(&mut self) {
        self.state = SignalState::Quiet;
        self.pending_valid_frames = 0;
        self.pending_pitch = None;
        self.last_pitch = None;
    }
}

/// 计算 YIN 分析窗口的 RMS dBFS；短输入或静音返回 -120dBFS。
pub fn input_level_dbfs(pcm: &[f32]) -> f32 {
    if pcm.len() < pitch::WINDOW {
        return -120.0;
    }
    let mut energy = 0.0f32;
    for &sample in &pcm[..pitch::WINDOW] {
        energy += sample * sample;
    }
    let rms2 = energy / pitch::WINDOW as f32;
    if rms2 <= 1.0e-12 {
        -120.0
    } else {
        (10.0 * rms2.log10()).max(-120.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PITCH: PitchSample = PitchSample {
        freq_hz: 440.0,
        clarity: 0.95,
    };

    #[test]
    fn loud_invalid_signal_never_creates_a_reading() {
        let mut tracker = SignalTracker::new(44_100.0, 1_024, -50.0);
        for _ in 0..8 {
            let output = tracker.process(None, -20.0);
            assert_eq!(output.state, SignalState::Quiet);
            assert!(output.pitch.is_none());
        }
    }

    #[test]
    fn holding_recovery_requires_two_frames_and_keeps_old_pitch_between_them() {
        let mut tracker = SignalTracker::new(44_100.0, 1_024, -50.0);
        let _ = tracker.process(Some(PITCH), -30.0);
        let _ = tracker.process(Some(PITCH), -30.0);
        assert_eq!(tracker.process(None, -120.0).state, SignalState::Holding);
        let candidate = PitchSample {
            freq_hz: 493.883,
            clarity: 0.96,
        };
        let first = tracker.process(Some(candidate), -30.0);
        assert_eq!(first.state, SignalState::Holding);
        assert_eq!(first.pitch, Some(PITCH));
        let recovered = tracker.process(Some(candidate), -30.0);
        assert_eq!(recovered.state, SignalState::Tracking);
        assert_eq!(recovered.pitch, Some(candidate));
    }
}
