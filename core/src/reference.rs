//! 当前 A4 校准和平均律对应的固定音高表。

use crate::note;

/// 固定音高下限（Hz）。
pub const MIN_FREQUENCY_HZ: f64 = 80.0;
/// 固定音高上限（Hz）。
pub const MAX_FREQUENCY_HZ: f64 = 1500.0;

/// core 内部固定音高数据。
#[derive(Debug, Clone, PartialEq)]
pub struct ReferenceToneInfo {
    /// 相对 A4 的平均律步数。
    pub step_from_a4: i32,
    /// 固定频率（Hz）。
    pub frequency_hz: f64,
    /// 平均律等分数。
    pub temperament: u8,
    /// 最近 12 平均律音名。
    pub note_name: String,
    /// 相对最近 12 平均律音名的音分差。
    pub cents_from_note: f64,
}

/// 生成 80–1500Hz 内、按频率升序的固定音高表。
pub fn list(a4_hz: f64, temperament: u8) -> Vec<ReferenceToneInfo> {
    let a4 = a4_hz.clamp(note::A4_MIN, note::A4_MAX);
    let divisions = note::temperament_or_default(temperament);
    let div = f64::from(divisions);
    let first = (div * (MIN_FREQUENCY_HZ / a4).log2()).ceil() as i32;
    let last = (div * (MAX_FREQUENCY_HZ / a4).log2()).floor() as i32;
    let capacity = last.saturating_sub(first).saturating_add(1) as usize;
    let mut tones = Vec::with_capacity(capacity);
    for step in first..=last {
        let frequency_hz = a4 * 2.0_f64.powf(f64::from(step) / div);
        let Some(info) = note::analyze(frequency_hz, a4) else {
            continue;
        };
        tones.push(ReferenceToneInfo {
            step_from_a4: step,
            frequency_hz,
            temperament: divisions,
            note_name: info.name().to_string(),
            cents_from_note: info.cents_off,
        });
    }
    tones
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_supported_temperament_is_sorted_and_bounded() {
        for temperament in note::TEMPERAMENT_DIVISIONS {
            let tones = list(440.0, temperament);
            assert!(!tones.is_empty());
            assert!(tones.iter().all(|tone| {
                (MIN_FREQUENCY_HZ..=MAX_FREQUENCY_HZ).contains(&tone.frequency_hz)
                    && tone.temperament == temperament
            }));
            assert!(
                tones
                    .windows(2)
                    .all(|pair| pair[0].frequency_hz < pair[1].frequency_hz)
            );
        }
    }
}
