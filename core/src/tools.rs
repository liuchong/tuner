//! 小工具页的固定音频预设。
//!
//! 与音叉不同，这些音频不随 A4 校准或平均律变化：频率是工具本身的定义。

/// 一条小工具音频预设（纯正弦波）。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ToolToneInfo {
    /// 稳定 id，客户端用于持久化与埋点。
    pub id: &'static str,
    /// 显示名。
    pub display_name: &'static str,
    /// 一行说明。
    pub summary: &'static str,
    /// 正弦波频率（Hz）。
    pub frequency_hz: f64,
}

/// 防晕车：100Hz 纯正弦波。
pub const ANTI_MOTION_SICKNESS: ToolToneInfo = ToolToneInfo {
    id: "anti_motion_sickness",
    display_name: "防晕车",
    summary: "100Hz 纯正弦波",
    frequency_hz: 100.0,
};

/// 按展示顺序排列的全部小工具音频。
pub const TOOL_TONES: [ToolToneInfo; 1] = [ANTI_MOTION_SICKNESS];

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn anti_motion_sickness_is_a_100hz_sine() {
        assert_eq!(TOOL_TONES[0].id, "anti_motion_sickness");
        assert_eq!(TOOL_TONES[0].display_name, "防晕车");
        assert_eq!(TOOL_TONES[0].frequency_hz, 100.0);
    }

    #[test]
    fn ids_are_unique_and_frequencies_audible() {
        for (i, tone) in TOOL_TONES.iter().enumerate() {
            assert!((20.0..=20_000.0).contains(&tone.frequency_hz));
            assert!(TOOL_TONES[i + 1..].iter().all(|other| other.id != tone.id));
        }
    }
}
