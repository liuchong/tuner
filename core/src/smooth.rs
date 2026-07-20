//! 音高平滑：中值滤波（窗口 5）+ EMA（α≈0.3）+ 音符滞回（连续 2 帧确认）。
//!
//! 固定容量环形缓冲，零分配、零锁、无 panic 路径。

/// 中值滤波窗口长度。
pub const MEDIAN_WINDOW: usize = 5;
/// EMA 平滑系数。
pub const EMA_ALPHA: f32 = 0.3;
/// 音符切换需要的连续确认帧数。
pub const HYSTERESIS_FRAMES: u8 = 2;

/// 平滑输出：平滑后频率 + 滞回后的 MIDI 音符。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SmoothOutput {
    /// 平滑后频率（Hz）。
    pub freq_hz: f32,
    /// 滞回确认的 MIDI 音符（未确认时为当前稳定音符）。
    pub midi: i32,
    /// 该音符是否刚完成切换（本帧为确认帧）。
    pub note_changed: bool,
}

/// 音高平滑器。单线程使用。
pub struct PitchSmoother {
    /// 中值滤波环形缓冲（存原始频率）。
    window: [f32; MEDIAN_WINDOW],
    /// 环形写指针。
    head: usize,
    /// 已写入帧数（饱和计数）。
    count: usize,
    /// EMA 状态。
    ema: f32,
    /// 中值排序暂存。
    sorted: [f32; MEDIAN_WINDOW],
    /// 当前稳定音符。
    stable_midi: Option<i32>,
    /// 候选音符与连续计数。
    candidate: Option<(i32, u8)>,
}

impl Default for PitchSmoother {
    fn default() -> Self {
        Self::new()
    }
}

impl PitchSmoother {
    /// 构造（全零初始化，无堆分配）。
    pub fn new() -> Self {
        Self {
            window: [0.0; MEDIAN_WINDOW],
            head: 0,
            count: 0,
            ema: 0.0,
            sorted: [0.0; MEDIAN_WINDOW],
            stable_midi: None,
            candidate: None,
        }
    }

    /// 输入一帧检测结果。`freq` 为 None 表示本帧无有效音高（不更新滤波状态）。
    /// `midi_of` 把平滑频率映射为 MIDI 音（由调用方提供，便于携带 A4 校准）。
    pub fn feed(
        &mut self,
        freq: Option<f32>,
        mut midi_of: impl FnMut(f32) -> Option<i32>,
    ) -> Option<SmoothOutput> {
        let f = freq?;
        if !(f.is_finite() && f > 0.0) {
            return None;
        }
        // 中值滤波
        self.window[self.head] = f;
        self.head = (self.head + 1) % MEDIAN_WINDOW;
        self.count = (self.count + 1).min(MEDIAN_WINDOW);
        self.sorted = self.window;
        let n = self.count;
        let slice = &mut self.sorted[..n];
        slice.sort_by(|a, b| a.partial_cmp(b).unwrap_or(core::cmp::Ordering::Equal));
        let median = slice[n / 2];
        // EMA（仅用于上报频率/cents 平滑，不参与音符判定，避免收敛延迟拖慢响应）
        self.ema = if self.count == 1 {
            median
        } else {
            EMA_ALPHA * median + (1.0 - EMA_ALPHA) * self.ema
        };
        // 音符滞回（基于中值滤波结果，响应快且抗野值）
        let midi = midi_of(median)?;
        let note_changed = match self.stable_midi {
            None => {
                self.stable_midi = Some(midi);
                self.candidate = None;
                true
            }
            Some(stable) if stable == midi => {
                self.candidate = None;
                false
            }
            Some(_) => match self.candidate {
                Some((cand, cnt)) if cand == midi => {
                    if cnt + 1 >= HYSTERESIS_FRAMES {
                        self.stable_midi = Some(midi);
                        self.candidate = None;
                        true
                    } else {
                        self.candidate = Some((cand, cnt + 1));
                        false
                    }
                }
                _ => {
                    self.candidate = Some((midi, 1));
                    false
                }
            },
        };
        Some(SmoothOutput {
            freq_hz: self.ema,
            midi: self.stable_midi?,
            note_changed,
        })
    }

    /// 重置（换乐器/停用时调用）。
    pub fn reset(&mut self) {
        *self = Self::new();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn midi_of(f: f32) -> Option<i32> {
        Some((69.0 + 12.0 * (f as f64 / 440.0).log2()).round() as i32)
    }

    #[test]
    fn impulse_rejected_by_median() {
        let mut s = PitchSmoother::new();
        // 稳定 440 中出现一次 880 脉冲，中值应滤掉
        let mut last = None;
        for i in 0..8 {
            let f = if i == 3 { 880.0 } else { 440.0 };
            last = s.feed(Some(f), midi_of);
        }
        let out = last.unwrap();
        assert_eq!(out.midi, 69);
        assert!((out.freq_hz - 440.0).abs() < 5.0);
    }

    #[test]
    fn ema_converges() {
        let mut s = PitchSmoother::new();
        let mut out = None;
        for _ in 0..20 {
            out = s.feed(Some(440.0), midi_of);
        }
        assert!((out.unwrap().freq_hz - 440.0).abs() < 1e-3);
    }

    #[test]
    fn hysteresis_requires_two_frames() {
        let mut s = PitchSmoother::new();
        for _ in 0..10 {
            s.feed(Some(440.0), midi_of);
        }
        // 切到 494Hz（B4, MIDI 71）。中值窗口 5 需 3 帧翻转，翻转后还需连续 2 帧确认。
        let mut outputs = Vec::new();
        for _ in 0..6 {
            outputs.push(s.feed(Some(494.0), midi_of).unwrap());
        }
        // 前 3 帧：中值尚未翻转或刚翻转（候选计数 1），音符仍为 A4
        assert_eq!(outputs[0].midi, 69);
        assert!(!outputs[0].note_changed);
        assert_eq!(outputs[2].midi, 69);
        assert!(!outputs[2].note_changed);
        // 第 4 帧：候选连续第 2 帧，确认切换
        assert_eq!(outputs[3].midi, 71);
        assert!(outputs[3].note_changed);
        // 之后稳定
        assert_eq!(outputs[4].midi, 71);
        assert!(!outputs[4].note_changed);
    }

    #[test]
    fn single_frame_blip_does_not_switch() {
        let mut s = PitchSmoother::new();
        for _ in 0..10 {
            s.feed(Some(440.0), midi_of);
        }
        // 2 帧野值（不足以翻转窗口 5 的中值）后立即回到 440：不应切换
        s.feed(Some(494.0), midi_of);
        s.feed(Some(494.0), midi_of);
        for _ in 0..5 {
            let out = s.feed(Some(440.0), midi_of).unwrap();
            assert_eq!(out.midi, 69);
        }
    }

    #[test]
    fn none_passthrough() {
        let mut s = PitchSmoother::new();
        assert!(s.feed(None, midi_of).is_none());
        assert!(s.feed(Some(f32::NAN), midi_of).is_none());
        assert!(s.feed(Some(-1.0), midi_of).is_none());
    }
}
