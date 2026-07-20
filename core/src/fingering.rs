//! 管乐器调性 + 筒音唱名 → 音阶指法表（数据见 `docs/spec-instruments.md` §4-§6）。
//!
//! 模型：筒音 MIDI = 调性主音 - 7（筒音为该调 sol/5）；音阶相对筒音的半音偏移
//! 取 [0,2,4,5,7,9,11,12,14,16,17,19,21,23,24]（七声自然音阶 + 超吹八度，15 音，
//! 覆盖约两个八度）。预设表只存 MIDI，频率按 A4 校准在使用时换算。

use crate::api::{Instrument, InstrumentKind};

/// 音阶相对筒音的半音偏移（竹笛/洞箫，15 音）。
pub const DIZI_XIAO_OFFSETS: [i32; 15] = [0, 2, 4, 5, 7, 9, 11, 12, 14, 16, 17, 19, 21, 23, 24];
/// 音阶各音指法名（竹笛/洞箫，与 OFFSETS 一一对应）。
pub const DIZI_XIAO_LABELS: [&str; 15] = [
    "筒音",
    "开第一孔",
    "开第一二孔",
    "开第一二三孔",
    "开第一二三四孔",
    "开第一二三四五孔",
    "全开",
    "筒音·超吹",
    "开第一孔·超吹",
    "开第一二孔·超吹",
    "开第一二三孔·超吹",
    "开第一二三四孔·超吹",
    "开第一二三四五孔·超吹",
    "全开·超吹",
    "筒音·超吹二",
];

/// 尺八基本音阶相对筒音的半音偏移（ro tsu re chi ha 五声 × 2 八度 + 大甲，11 音）。
pub const SHAKUHACHI_OFFSETS: [i32; 11] = [0, 3, 5, 7, 10, 12, 15, 17, 19, 22, 24];
/// 尺八指法名（1.8 寸 D 调：D F G A C）。
pub const SHAKUHACHI_LABELS: [&str; 11] = [
    "筒音(ro)",
    "ツ(tsu)",
    "レ(re)",
    "チ(chi)",
    "ハ(ha)",
    "ロ·甲(ro 高八度)",
    "ツ·甲(tsu 高八度)",
    "レ·甲(re 高八度)",
    "チ·甲(chi 高八度)",
    "ハ·甲(ha 高八度)",
    "大甲(ro 高二八度)",
];

/// 筒音唱名选项（作 5 / 作 1 / 作 2）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TongyinSolf {
    /// 筒音作 sol（5）。
    Sol5,
    /// 筒音作 do（1）。
    Do1,
    /// 筒音作 re（2）。
    Re2,
}

/// 一个管乐器调性/型号 → 一张指法表。
pub struct FingeringChartDef {
    /// chart id，如 "d_qudi_sou5"、"g_xiao_zuo1"、"shaku_1_8"。
    pub id: &'static str,
    /// 显示名，如 "D调曲笛 · 筒音作5"。
    pub display_name: &'static str,
    /// 筒音 MIDI（型号固有，与 A4 无关）。
    pub fundamental_midi: i32,
    /// 音阶半音偏移。
    pub offsets: &'static [i32],
    /// 指法名（与 offsets 一一对应）。
    pub labels: &'static [&'static str],
    /// 筒音唱名（竹笛/洞箫有效；尺八固定 None，以筒音为宫）。
    pub tongyin: Option<TongyinSolf>,
}

/// 竹笛/洞箫指法表条目构造（减少样板）。
const fn flute(
    id: &'static str,
    name: &'static str,
    fundamental: i32,
    tongyin: TongyinSolf,
) -> FingeringChartDef {
    FingeringChartDef {
        id,
        display_name: name,
        fundamental_midi: fundamental,
        offsets: &DIZI_XIAO_OFFSETS,
        labels: &DIZI_XIAO_LABELS,
        tongyin: Some(tongyin),
    }
}

// 调性主音 MIDI：C3=48 D3=50 E3=52 F3=53 G3=55；筒音 = 主音 - 7
// D 调曲笛筒音 A2=45；G 调梆笛筒音 D3=50；F 调筒音 C3=48；C 调筒音 F2=41；E 调筒音 B2=47
use TongyinSolf::{Do1, Re2, Sol5};

/// 竹笛：D/G/F/C/E 五调 × 筒音作 5/1/2，共 15 张表。
pub const ZHUDI_CHARTS: [FingeringChartDef; 15] = [
    flute("d_qudi_sou5", "D调曲笛 · 筒音作5", 45, Sol5),
    flute("d_qudi_zuo1", "D调曲笛 · 筒音作1", 45, Do1),
    flute("d_qudi_zuo2", "D调曲笛 · 筒音作2", 45, Re2),
    flute("g_bangdi_sou5", "G调梆笛 · 筒音作5", 50, Sol5),
    flute("g_bangdi_zuo1", "G调梆笛 · 筒音作1", 50, Do1),
    flute("g_bangdi_zuo2", "G调梆笛 · 筒音作2", 50, Re2),
    flute("f_dizi_sou5", "F调竹笛 · 筒音作5", 48, Sol5),
    flute("f_dizi_zuo1", "F调竹笛 · 筒音作1", 48, Do1),
    flute("f_dizi_zuo2", "F调竹笛 · 筒音作2", 48, Re2),
    flute("c_dizi_sou5", "C调竹笛 · 筒音作5", 41, Sol5),
    flute("c_dizi_zuo1", "C调竹笛 · 筒音作1", 41, Do1),
    flute("c_dizi_zuo2", "C调竹笛 · 筒音作2", 41, Re2),
    flute("e_dizi_sou5", "E调竹笛 · 筒音作5", 47, Sol5),
    flute("e_dizi_zuo1", "E调竹笛 · 筒音作1", 47, Do1),
    flute("e_dizi_zuo2", "E调竹笛 · 筒音作2", 47, Re2),
];

// 洞箫：G 调筒音 D3=50；F 调筒音 C3=48
/// 洞箫：G/F 两调 × 筒音作 5/1/2，共 6 张表。
pub const DONGXIAO_CHARTS: [FingeringChartDef; 6] = [
    flute("g_xiao_sou5", "G调洞箫 · 筒音作5", 50, Sol5),
    flute("g_xiao_zuo1", "G调洞箫 · 筒音作1", 50, Do1),
    flute("g_xiao_zuo2", "G调洞箫 · 筒音作2", 50, Re2),
    flute("f_xiao_sou5", "F调洞箫 · 筒音作5", 48, Sol5),
    flute("f_xiao_zuo1", "F调洞箫 · 筒音作1", 48, Do1),
    flute("f_xiao_zuo2", "F调洞箫 · 筒音作2", 48, Re2),
];

/// 尺八型号构造。
const fn shaku(id: &'static str, name: &'static str, fundamental: i32) -> FingeringChartDef {
    FingeringChartDef {
        id,
        display_name: name,
        fundamental_midi: fundamental,
        offsets: &SHAKUHACHI_OFFSETS,
        labels: &SHAKUHACHI_LABELS,
        tongyin: None,
    }
}

// 尺八筒音：1.8=D4=62，1.6=E4=64，2.0=C4=60，2.4=A3=57
/// 尺八：1.8 / 1.6 / 2.0 / 2.4 寸，共 4 张表。
pub const SHAKUHACHI_CHARTS: [FingeringChartDef; 4] = [
    shaku("shaku_1_8", "尺八 1.8寸（D调）", 62),
    shaku("shaku_1_6", "尺八 1.6寸（E调）", 64),
    shaku("shaku_2_0", "尺八 2.0寸（C调）", 60),
    shaku("shaku_2_4", "尺八 2.4寸（A调）", 57),
];

/// 按 id 查管乐器的全部指法表。
pub fn wind_charts(id: &str) -> Option<&'static [FingeringChartDef]> {
    match id {
        "zhudi" => Some(&ZHUDI_CHARTS),
        "dongxiao" => Some(&DONGXIAO_CHARTS),
        "shakuhachi" => Some(&SHAKUHACHI_CHARTS),
        _ => None,
    }
}

/// 管乐器显示名。
pub fn wind_display_name(id: &str) -> Option<&'static str> {
    match id {
        "zhudi" => Some("竹笛"),
        "dongxiao" => Some("洞箫"),
        "shakuhachi" => Some("尺八"),
        _ => None,
    }
}

/// 管乐器元数据。
pub fn wind_instrument_meta(id: &str) -> Option<Instrument> {
    wind_display_name(id).map(|name| Instrument {
        id: id.to_string(),
        display_name: name.to_string(),
        kind: InstrumentKind::Wind,
    })
}

/// 筒音唱名 → 调式主音 pitch class（用于唱名显示）。
///
/// 筒音作 5（sol）：宫 = 筒音 - 7；作 1（do）：宫 = 筒音；作 2（re）：宫 = 筒音 - 2。
/// 尺八等五声乐器以筒音为宫（+0）。
pub fn tonic_pc_of(fundamental_midi: i32, tongyin: Option<TongyinSolf>) -> u8 {
    let offset = match tongyin {
        Some(TongyinSolf::Sol5) => -7,
        Some(TongyinSolf::Do1) => 0,
        Some(TongyinSolf::Re2) => -2,
        None => 0,
    };
    (fundamental_midi + offset).rem_euclid(12) as u8
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zhudi_chart_count_and_unique_ids() {
        assert_eq!(ZHUDI_CHARTS.len(), 15); // 5 调 × 3 筒音
        let mut ids: Vec<_> = ZHUDI_CHARTS.iter().map(|c| c.id).collect();
        ids.sort();
        ids.dedup();
        assert_eq!(ids.len(), 15);
    }

    #[test]
    fn dongxiao_chart_count() {
        assert_eq!(DONGXIAO_CHARTS.len(), 6); // 2 调 × 3 筒音
    }

    #[test]
    fn shakuhachi_chart_count() {
        assert_eq!(SHAKUHACHI_CHARTS.len(), 4);
    }

    #[test]
    fn charts_ascending_no_dup() {
        for id in ["zhudi", "dongxiao", "shakuhachi"] {
            for chart in wind_charts(id).unwrap() {
                assert_eq!(chart.offsets.len(), chart.labels.len());
                for w in chart.offsets.windows(2) {
                    assert!(w[0] < w[1], "{} 音阶非严格升序", chart.id);
                }
            }
        }
    }

    #[test]
    fn zhudi_d_qudi_fundamental() {
        // D 调曲笛：筒音 = D 下方纯五度 = A2 (MIDI 45) = 110Hz
        let d5 = ZHUDI_CHARTS.iter().find(|c| c.id == "d_qudi_sou5").unwrap();
        assert_eq!(d5.fundamental_midi, 45);
        let f = crate::note::midi_to_freq(45.0, 440.0);
        assert!((f - 110.0).abs() < 0.1);
    }

    #[test]
    fn shakuhachi_1_8_scale() {
        // 1.8 寸：筒音 D4=62，ro tsu re chi ha = D F G A C
        let s = SHAKUHACHI_CHARTS
            .iter()
            .find(|c| c.id == "shaku_1_8")
            .unwrap();
        let midis: Vec<i32> = s.offsets.iter().map(|o| s.fundamental_midi + o).collect();
        assert_eq!(&midis[..5], &[62, 65, 67, 69, 72]); // D4 F4 G4 A4 C5
    }

    #[test]
    fn tonic_pc_mapping() {
        // D 调曲笛作 5：宫 = D (pc 2)
        assert_eq!(tonic_pc_of(45, Some(TongyinSolf::Sol5)), 2);
        // D 调曲笛作 1：宫 = A (pc 9)
        assert_eq!(tonic_pc_of(45, Some(TongyinSolf::Do1)), 9);
        // D 调曲笛作 2：宫 = G (pc 7)
        assert_eq!(tonic_pc_of(45, Some(TongyinSolf::Re2)), 7);
        // 尺八 1.8：宫 = D
        assert_eq!(tonic_pc_of(62, None), 2);
    }

    #[test]
    fn unknown_wind_none() {
        assert!(wind_charts("suona").is_none());
        assert!(wind_display_name("suona").is_none());
    }
}
