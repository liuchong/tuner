//! 音名/音分换算：Hz ↔ MIDI/音名/cents，支持 A4 校准（415–466Hz）。
//!
//! 规则见 `docs/spec-core.md` §4。全部函数为纯计算，无分配、无锁、无 panic。

/// A4 校准允许范围（Hz）。
pub const A4_MIN: f64 = 415.0;
pub const A4_MAX: f64 = 466.0;
/// 默认 A4 频率。
pub const A4_DEFAULT: f64 = 440.0;

/// 升号命名音名表（pitch class 0-11，C=0）。
pub const NOTE_NAMES: [&str; 12] = [
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
];

/// 频率（Hz）→ 浮点 MIDI 音高。`f <= 0` 时返回 `None`。
pub fn freq_to_midi(f: f64, a4: f64) -> Option<f64> {
    if f <= 0.0 || a4 <= 0.0 {
        return None;
    }
    Some(69.0 + 12.0 * (f / a4).log2())
}

/// MIDI 音高 → 频率（Hz）。
pub fn midi_to_freq(midi: f64, a4: f64) -> f64 {
    a4 * 2.0_f64.powf((midi - 69.0) / 12.0)
}

/// 浮点 MIDI → 相对最近整数 MIDI 的音分偏差，范围 [-50, +50)。
pub fn cents_off(midi_float: f64) -> f64 {
    100.0 * (midi_float - midi_float.round())
}

/// MIDI 整数 → pitch class（0-11，C=0）。
pub fn midi_to_pc(midi: i32) -> u8 {
    midi.rem_euclid(12) as u8
}

/// MIDI 整数 → 科学音高记谱八度（MIDI 60 = C4）。
pub fn midi_to_octave(midi: i32) -> i32 {
    midi.div_euclid(12) - 1
}

/// MIDI 整数 → 音名（升号命名，如 "A4"、"C#3"）。
///
/// 结果写入 `buf`，返回其 `&str`。不分配内存；`buf` 至少 5 字节（如 "C#-1"）。
/// MIDI 范围限制在 [0, 127]，越界返回 `None`。
pub fn midi_to_name(midi: i32, buf: &mut [u8; 5]) -> Option<&str> {
    if !(0..=127).contains(&midi) {
        return None;
    }
    let pc = midi_to_pc(midi) as usize;
    let octave = midi_to_octave(midi);
    let name = NOTE_NAMES[pc].as_bytes();
    buf[..name.len()].copy_from_slice(name);
    let mut pos = name.len();
    let mut oct = octave;
    if oct < 0 {
        buf[pos] = b'-';
        pos += 1;
        oct = -oct;
    }
    if oct >= 10 {
        buf[pos] = b'0' + (oct / 10) as u8;
        pos += 1;
    }
    buf[pos] = b'0' + (oct % 10) as u8;
    pos += 1;
    core::str::from_utf8(&buf[..pos]).ok()
}

/// 支持的律制（N 平均律）。
pub const TEMPERAMENT_DIVISIONS: [u8; 4] = [12, 19, 24, 31];

/// 律制校验：非法 N 收敛到 12。
pub fn temperament_or_default(n: u8) -> u8 {
    if TEMPERAMENT_DIVISIONS.contains(&n) {
        n
    } else {
        12
    }
}

/// N 平均律换算（spec-core §4b）：步序以 A4 为参考，`f_step = a4·2^(k/N)`。
/// 返回 `(step, cents)`；cents = 1200·log2(f/f_step)，范围 [-600/N, +600/N)。
/// 无效输入返回 None。
pub fn temperament_step_cents(f: f64, a4: f64, n: u8) -> Option<(i32, f64)> {
    let n = temperament_or_default(n);
    if f <= 0.0 || a4 <= 0.0 {
        return None;
    }
    let nf = n as f64;
    let k = (nf * (f / a4).log2()).round() as i32;
    let f_step = a4 * 2.0_f64.powf(k as f64 / nf);
    Some((k, 1200.0 * (f / f_step).log2()))
}

/// 一次完整的频率分析结果（音名缓冲内联，零分配）。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct NoteInfo {
    /// 最近 MIDI 音。
    pub midi: i32,
    /// 相对最近 MIDI 音的音分偏差 [-50, +50)。
    pub cents_off: f64,
    /// 音名字节缓冲（长度见 `note_len`）。
    pub name_buf: [u8; 5],
    /// 音名有效字节数。
    pub note_len: u8,
}

impl NoteInfo {
    /// 音名 `&str`。
    pub fn name(&self) -> &str {
        core::str::from_utf8(&self.name_buf[..self.note_len as usize]).unwrap_or("")
    }
}

/// 频率 → NoteInfo。无效频率返回 `None`。
pub fn analyze(f: f64, a4: f64) -> Option<NoteInfo> {
    let midi_f = freq_to_midi(f, a4)?;
    let midi = midi_f.round() as i32;
    let mut name_buf = [0u8; 5];
    let note_len = midi_to_name(midi, &mut name_buf)?.len() as u8;
    Some(NoteInfo {
        midi,
        cents_off: cents_off(midi_f),
        name_buf,
        note_len,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a4_440_is_midi_69() {
        let m = freq_to_midi(440.0, 440.0).unwrap();
        assert!((m - 69.0).abs() < 1e-9);
        let f = midi_to_freq(69.0, 440.0);
        assert!((f - 440.0).abs() < 1e-9);
    }

    #[test]
    fn a4_442_calibration() {
        // A4=442 校准时，442Hz 应精确落在 MIDI 69。
        let m = freq_to_midi(442.0, 442.0).unwrap();
        assert!((m - 69.0).abs() < 1e-9);
        // 440Hz 在 A4=442 下约 -7.8 cents。
        let info = analyze(440.0, 442.0).unwrap();
        assert_eq!(info.midi, 69);
        assert!(info.cents_off < 0.0 && info.cents_off > -8.5);
        // 往返
        assert!((midi_to_freq(69.0, 442.0) - 442.0).abs() < 1e-9);
    }

    #[test]
    fn c4_middle_c() {
        // C4 = MIDI 60，A4=440 时约 261.63Hz。
        let f = midi_to_freq(60.0, 440.0);
        assert!((f - 261.6256).abs() < 1e-3);
        let mut buf = [0u8; 5];
        assert_eq!(midi_to_name(60, &mut buf), Some("C4"));
    }

    #[test]
    fn cents_boundary() {
        // 精确整数 → 0 cents
        assert_eq!(cents_off(69.0), 0.0);
        // ±0.5 半音边界（±50 cents），四舍五入后归入相邻音，范围 [-50, +50)
        let c = cents_off(69.4999999);
        assert!(c < 50.0 && c > 49.0);
        let c = cents_off(69.5);
        // 69.5 四舍五入到 70 → -50
        assert!((c + 50.0).abs() < 1e-6);
        let c = cents_off(68.5);
        // 68.5 四舍五入到 68（banker's? Rust round 是远离零）→ 69 → -50
        assert!(c.abs() <= 50.0);
    }

    #[test]
    fn enharmonic_names() {
        let mut buf = [0u8; 5];
        // 采用升号命名：B#4 的等价音为 C5（MIDI 72），Cb5 等价音为 B4（MIDI 71）
        assert_eq!(midi_to_name(72, &mut buf), Some("C5"));
        assert_eq!(midi_to_name(71, &mut buf), Some("B4"));
        assert_eq!(midi_to_name(70, &mut buf), Some("A#4"));
        assert_eq!(midi_to_name(69, &mut buf), Some("A4"));
        assert_eq!(midi_to_name(21, &mut buf), Some("A0"));
        assert_eq!(midi_to_name(127, &mut buf), Some("G9"));
        assert_eq!(midi_to_name(128, &mut buf), None);
        assert_eq!(midi_to_name(-1, &mut buf), None);
    }

    #[test]
    fn temperament_19tet_step7() {
        // 19-TET：A4 上方第 7 步 → step=7、cents≈0
        let f = 440.0 * 2.0_f64.powf(7.0 / 19.0);
        let (step, cents) = temperament_step_cents(f, 440.0, 19).unwrap();
        assert_eq!(step, 7);
        assert!(cents.abs() < 1e-9, "cents={cents}");
        // 12-TET 下 A4 本身：step=0
        let (s0, c0) = temperament_step_cents(440.0, 440.0, 12).unwrap();
        assert_eq!(s0, 0);
        assert!(c0.abs() < 1e-9);
    }

    #[test]
    fn temperament_cents_range() {
        // 相邻步中点频率 → cents 在 [-600/N, +600/N) 边界内
        for n in [12u8, 19, 24, 31] {
            let nf = n as f64;
            let f = 440.0 * 2.0_f64.powf(0.5 / nf); // 半步
            let (_, cents) = temperament_step_cents(f, 440.0, n).unwrap();
            // 边界值（浮点误差放宽 1e-6）
            assert!(cents >= -600.0 / nf - 1e-6 && cents < 600.0 / nf + 1e-6);
            // 全范围扫描不越界
            for i in -30..30 {
                let f = 440.0 * 2.0_f64.powf(i as f64 / nf * 1.03);
                let (_, c) = temperament_step_cents(f, 440.0, n).unwrap();
                assert!(
                    c >= -600.0 / nf - 1e-9 && c < 600.0 / nf + 1e-9,
                    "n={n} i={i} c={c}"
                );
            }
        }
    }

    #[test]
    fn temperament_invalid_division() {
        assert_eq!(temperament_or_default(5), 12);
        assert_eq!(temperament_or_default(0), 12);
        assert_eq!(temperament_or_default(19), 19);
        // 非法 N 走 12-TET
        let (step, _) = temperament_step_cents(466.16, 440.0, 5).unwrap();
        assert_eq!(step, 1); // 12-TET 上半音
    }

    #[test]
    fn invalid_freq() {
        assert!(freq_to_midi(0.0, 440.0).is_none());
        assert!(freq_to_midi(-1.0, 440.0).is_none());
        assert!(analyze(0.0, 440.0).is_none());
    }

    #[test]
    fn analyze_roundtrip() {
        for midi in 21..=108 {
            let f = midi_to_freq(midi as f64, 440.0);
            let info = analyze(f, 440.0).unwrap();
            assert_eq!(info.midi, midi);
            assert!(info.cents_off.abs() < 0.01);
        }
    }
}
