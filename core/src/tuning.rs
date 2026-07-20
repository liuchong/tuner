//! 弦乐器定弦预设数据（数据见 `docs/spec-instruments.md` §1-§3）。
//!
//! 预设表内只存 MIDI 音高（含分数表示降半音调弦），频率在使用时按 A4 校准换算。

use crate::api::{Instrument, InstrumentKind};

/// 一个弦乐器定弦预设。
pub struct TuningDef {
    /// 定弦 id（如 "standard"）。
    pub id: &'static str,
    /// 中文显示名。
    pub display_name: &'static str,
    /// 各弦 MIDI 音高（按弦号 1..=N 顺序，弦号含义见 spec-instruments）。
    pub strings: &'static [f64],
}

/// 一个乐器定义。
pub struct InstrumentDef {
    /// 乐器 id。
    pub id: &'static str,
    /// 中文显示名。
    pub display_name: &'static str,
    /// 类别。
    pub kind: InstrumentKind,
    /// 定弦预设（弦乐器）。
    pub tunings: &'static [TuningDef],
}

// ---- 吉他（spec-instruments §1，弦号 1=最细）----
// E2=40 A2=45 D3=50 G3=55 B3=59 E4=64；D2=38 G2=43 A3=57 D4=62
// 降半音：Eb2=39 Ab2=44 Db3=49 Gb3=54 Bb3=58 Eb4=63
const GUITAR_STANDARD: [f64; 6] = [64.0, 59.0, 55.0, 50.0, 45.0, 40.0];
const GUITAR_DROP_D: [f64; 6] = [64.0, 59.0, 55.0, 50.0, 45.0, 38.0];
const GUITAR_OPEN_G: [f64; 6] = [62.0, 59.0, 55.0, 50.0, 43.0, 38.0];
const GUITAR_DADGAD: [f64; 6] = [62.0, 57.0, 55.0, 50.0, 45.0, 38.0];
const GUITAR_HALF_STEP_DOWN: [f64; 6] = [63.0, 58.0, 54.0, 49.0, 44.0, 39.0];

const GUITAR_TUNINGS: [TuningDef; 5] = [
    TuningDef {
        id: "standard",
        display_name: "标准调弦",
        strings: &GUITAR_STANDARD,
    },
    TuningDef {
        id: "drop_d",
        display_name: "Drop D",
        strings: &GUITAR_DROP_D,
    },
    TuningDef {
        id: "open_g",
        display_name: "Open G",
        strings: &GUITAR_OPEN_G,
    },
    TuningDef {
        id: "dadgad",
        display_name: "DADGAD",
        strings: &GUITAR_DADGAD,
    },
    TuningDef {
        id: "half_step_down",
        display_name: "降半音",
        strings: &GUITAR_HALF_STEP_DOWN,
    },
];

// ---- 尤克里里（spec-instruments §2）----
// C4=60 D4=62 E4=64 F#4=66 G3=55 G4=67 A4=69 B4=71
const UKE_STANDARD: [f64; 4] = [69.0, 64.0, 60.0, 67.0];
const UKE_LOW_G: [f64; 4] = [69.0, 64.0, 60.0, 55.0];
const UKE_D_TUNING: [f64; 4] = [71.0, 66.0, 62.0, 69.0];
const UKE_BARITONE: [f64; 4] = [64.0, 59.0, 55.0, 50.0];

const UKULELE_TUNINGS: [TuningDef; 4] = [
    TuningDef {
        id: "standard",
        display_name: "标准 (High G)",
        strings: &UKE_STANDARD,
    },
    TuningDef {
        id: "low_g",
        display_name: "Low G",
        strings: &UKE_LOW_G,
    },
    TuningDef {
        id: "d_tuning",
        display_name: "D 调",
        strings: &UKE_D_TUNING,
    },
    TuningDef {
        id: "baritone",
        display_name: "中音 (Baritone)",
        strings: &UKE_BARITONE,
    },
];

// ---- 古琴（spec-instruments §3，弦号 1=最粗/最外侧）----
// C3=48 D3=50 Eb3=51 F3=53 G3=55 A3=57 A#3=58 C4=60 D4=62
const GUQIN_ZHENGDIAO: [f64; 7] = [48.0, 50.0, 53.0, 55.0, 57.0, 60.0, 62.0];
const GUQIN_JINWUXIAN: [f64; 7] = [48.0, 50.0, 53.0, 55.0, 58.0, 60.0, 62.0];
const GUQIN_MANJIAO: [f64; 7] = [48.0, 50.0, 51.0, 55.0, 57.0, 60.0, 62.0];

const GUQIN_TUNINGS: [TuningDef; 3] = [
    TuningDef {
        id: "zhengdiao",
        display_name: "正调",
        strings: &GUQIN_ZHENGDIAO,
    },
    TuningDef {
        id: "jinwuxian",
        display_name: "紧五弦",
        strings: &GUQIN_JINWUXIAN,
    },
    TuningDef {
        id: "manjiao",
        display_name: "慢角调",
        strings: &GUQIN_MANJIAO,
    },
];

/// 全部弦乐器预设。
pub const STRING_INSTRUMENTS: [InstrumentDef; 3] = [
    InstrumentDef {
        id: "guitar",
        display_name: "吉他",
        kind: InstrumentKind::String,
        tunings: &GUITAR_TUNINGS,
    },
    InstrumentDef {
        id: "ukulele",
        display_name: "尤克里里",
        kind: InstrumentKind::String,
        tunings: &UKULELE_TUNINGS,
    },
    InstrumentDef {
        id: "guqin",
        display_name: "古琴",
        kind: InstrumentKind::String,
        tunings: &GUQIN_TUNINGS,
    },
];

/// 按 id 查弦乐器。
pub fn find_string_instrument(id: &str) -> Option<&'static InstrumentDef> {
    STRING_INSTRUMENTS.iter().find(|d| d.id == id)
}

/// 列出弦乐器（供 api 层组装）。
pub fn list_string_instruments() -> impl Iterator<Item = &'static InstrumentDef> {
    STRING_INSTRUMENTS.iter()
}

/// 由 InstrumentDef 生成 UniFFI Instrument 元数据。
pub fn instrument_meta(def: &InstrumentDef) -> Instrument {
    Instrument {
        id: def.id.to_string(),
        display_name: def.display_name.to_string(),
        kind: def.kind,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::note::midi_to_freq;

    #[test]
    fn guitar_string_count_and_freqs() {
        let g = find_string_instrument("guitar").unwrap();
        assert_eq!(g.tunings.len(), 5);
        let std = &g.tunings[0];
        assert_eq!(std.id, "standard");
        assert_eq!(std.strings.len(), 6);
        // E2 ≈ 82.41Hz，A2=110Hz，E4 ≈ 329.63Hz（A4=440，±0.1Hz）
        assert!((midi_to_freq(std.strings[5], 440.0) - 82.4069).abs() < 0.1);
        assert!((midi_to_freq(std.strings[4], 440.0) - 110.0).abs() < 0.1);
        assert!((midi_to_freq(std.strings[0], 440.0) - 329.6276).abs() < 0.1);
        // Drop D：6 弦 D2 ≈ 73.42Hz
        assert!((midi_to_freq(g.tunings[1].strings[5], 440.0) - 73.4162).abs() < 0.1);
    }

    #[test]
    fn ukulele_freqs() {
        let u = find_string_instrument("ukulele").unwrap();
        assert_eq!(u.tunings.len(), 4);
        let std = &u.tunings[0];
        assert_eq!(std.strings.len(), 4);
        // A4=440，C4 ≈ 261.63
        assert!((midi_to_freq(std.strings[0], 440.0) - 440.0).abs() < 0.1);
        assert!((midi_to_freq(std.strings[2], 440.0) - 261.6256).abs() < 0.1);
    }

    #[test]
    fn guqin_freqs_and_a4_scaling() {
        let q = find_string_instrument("guqin").unwrap();
        assert_eq!(q.tunings.len(), 3);
        let zd = &q.tunings[0];
        assert_eq!(zd.id, "zhengdiao");
        assert_eq!(zd.strings.len(), 7);
        // 一弦 C3 ≈ 130.81Hz
        assert!((midi_to_freq(zd.strings[0], 440.0) - 130.8128).abs() < 0.1);
        // 紧五弦：五弦 A#3
        assert_eq!(q.tunings[1].strings[4], 58.0);
        // 慢角调：三弦 Eb3
        assert_eq!(q.tunings[2].strings[2], 51.0);
        // A4=442 时频率整体升高
        let f440 = midi_to_freq(zd.strings[0], 440.0);
        let f442 = midi_to_freq(zd.strings[0], 442.0);
        assert!((f442 / f440 - 442.0 / 440.0).abs() < 1e-12);
    }

    #[test]
    fn unknown_instrument_none() {
        assert!(find_string_instrument("piano").is_none());
    }
}
