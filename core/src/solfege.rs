//! 唱名体系与调式换算（规则见 `docs/spec-core.md` §5）。
//!
//! 首调体系（MovableDo / Numbered / Chinese）先按调式类别确定「宫/do/1 的参照音级」，
//! 再按相对参照音的半音偏移查表。参照音规则（与 §5 调式唱名序列完全等价）：
//!
//! | 调式 | 主音唱名 | 参照音（宫/do/1）= 主音 + 偏移 |
//! |---|---|---|
//! | 宫 | 宫 | +0 |
//! | 商 | 商 | -2 |
//! | 角 | 角 | -4 |
//! | 徵 | 徵 | -7 |
//! | 羽 | 羽 | +3 |
//! | 大调 | do | +0 |
//! | 小调 | la | +3 |
//!
//! 偏移查表（半音 → 唱名），偏音：变宫（7）、变徵（#4）、清角（4）、闰（b7）。

use crate::api::{ModeKind, SolfegeSystem};

/// 固定 Do 音名（绝对 pitch class 0-11，升号命名）。
const FIXED_DO: [&str; 12] = [
    "do", "#do", "re", "#re", "mi", "fa", "#fa", "sol", "#sol", "la", "#la", "si",
];
/// 首调 Do（相对参照音的半音偏移 0-11）。
const MOVABLE_DO: [&str; 12] = [
    "do", "#do", "re", "#re", "mi", "fa", "#fa", "sol", "#sol", "la", "b7", "si",
];
/// 简谱（相对参照音的半音偏移 0-11）。
const NUMBERED: [&str; 12] = [
    "1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "b7", "7",
];
/// 宫商角徵羽（相对参照音的半音偏移 0-11），含偏音。
const CHINESE: [&str; 12] = [
    "宫",
    "#宫",
    "商",
    "#商",
    "角",
    "清角(4)",
    "变徵(#4)",
    "徵",
    "#徵",
    "羽",
    "闰(b7)",
    "变宫(7)",
];

/// 各调式类别下「参照音（宫/do/1）相对主音的半音偏移」。
fn reference_offset(mode: ModeKind) -> i32 {
    match mode {
        ModeKind::Gong | ModeKind::Major => 0,
        ModeKind::Shang => -2,
        ModeKind::Jue => -4,
        ModeKind::Zhi => -7,
        ModeKind::Yu | ModeKind::Minor => 3,
    }
}

/// 计算唱名。`pc` 为目标 pitch class（0-11，C=0）。
///
/// 结果写入 `buf`（至少 16 字节），返回 `&str`。不分配内存、不 panic。
pub fn solfege_of(
    system: SolfegeSystem,
    pc: u8,
    tonic_pc: u8,
    mode: ModeKind,
    buf: &mut [u8; 16],
) -> &str {
    let table: &[&str; 12] = match system {
        SolfegeSystem::FixedDo => &FIXED_DO,
        SolfegeSystem::MovableDo => &MOVABLE_DO,
        SolfegeSystem::Numbered => &NUMBERED,
        SolfegeSystem::Chinese => &CHINESE,
    };
    let idx = match system {
        // 固定 Do 与调式无关，直接用绝对 pitch class
        SolfegeSystem::FixedDo => (pc % 12) as i32,
        // 首调体系：相对参照音（宫/do/1）的偏移
        _ => {
            let reference = tonic_pc as i32 + reference_offset(mode);
            pc as i32 - reference
        }
    }
    .rem_euclid(12) as usize;
    let s = table[idx];
    let bytes = s.as_bytes();
    buf[..bytes.len()].copy_from_slice(bytes);
    core::str::from_utf8(&buf[..bytes.len()]).unwrap_or("")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::{ModeKind, SolfegeSystem};

    fn sf(system: SolfegeSystem, pc: u8, tonic: u8, mode: ModeKind) -> String {
        let mut buf = [0u8; 16];
        solfege_of(system, pc, tonic, mode, &mut buf).to_string()
    }

    // ---- 宫调式（C 宫，主音 C=0）----
    #[test]
    fn c_gong_pentatonic() {
        // 宫 商 角 徵 羽 = C D E G A（do re mi sol la）
        let cases = [
            (0u8, "宫", "do", "1"),
            (2, "商", "re", "2"),
            (4, "角", "mi", "3"),
            (7, "徵", "sol", "5"),
            (9, "羽", "la", "6"),
        ];
        for (pc, cn, mv, num) in cases {
            assert_eq!(
                sf(SolfegeSystem::Chinese, pc, 0, ModeKind::Gong),
                cn,
                "pc={pc}"
            );
            assert_eq!(sf(SolfegeSystem::MovableDo, pc, 0, ModeKind::Gong), mv);
            assert_eq!(sf(SolfegeSystem::Numbered, pc, 0, ModeKind::Gong), num);
        }
    }

    // ---- 偏音（C 宫）----
    #[test]
    fn pianyin_c_gong() {
        // 清角 F(5)、变徵 F#(6)、闰 Bb(10)、变宫 B(11)
        assert_eq!(sf(SolfegeSystem::Chinese, 5, 0, ModeKind::Gong), "清角(4)");
        assert_eq!(sf(SolfegeSystem::Chinese, 6, 0, ModeKind::Gong), "变徵(#4)");
        assert_eq!(sf(SolfegeSystem::Chinese, 10, 0, ModeKind::Gong), "闰(b7)");
        assert_eq!(sf(SolfegeSystem::Chinese, 11, 0, ModeKind::Gong), "变宫(7)");
        // 简谱对应
        assert_eq!(sf(SolfegeSystem::Numbered, 5, 0, ModeKind::Gong), "4");
        assert_eq!(sf(SolfegeSystem::Numbered, 6, 0, ModeKind::Gong), "#4");
        assert_eq!(sf(SolfegeSystem::Numbered, 10, 0, ModeKind::Gong), "b7");
        assert_eq!(sf(SolfegeSystem::Numbered, 11, 0, ModeKind::Gong), "7");
    }

    // ---- 羽调式（A 羽，主音 A=9）：羽 宫 商 角 徵 = A C D E G ----
    #[test]
    fn a_yu_pentatonic() {
        let cases = [
            (9u8, "羽", "6"),
            (0, "宫", "1"),
            (2, "商", "2"),
            (4, "角", "3"),
            (7, "徵", "5"),
        ];
        for (pc, cn, num) in cases {
            assert_eq!(
                sf(SolfegeSystem::Chinese, pc, 9, ModeKind::Yu),
                cn,
                "pc={pc}"
            );
            assert_eq!(sf(SolfegeSystem::Numbered, pc, 9, ModeKind::Yu), num);
        }
        // 偏音：A 羽下 G#(8) 相对参照 C 为 #5
        assert_eq!(sf(SolfegeSystem::Numbered, 8, 9, ModeKind::Yu), "#5");
    }

    // ---- 大调（C 大调）----
    #[test]
    fn c_major() {
        let cases = [
            (0u8, "do", "1"),
            (2, "re", "2"),
            (4, "mi", "3"),
            (5, "fa", "4"),
            (7, "sol", "5"),
            (9, "la", "6"),
            (11, "si", "7"),
        ];
        for (pc, mv, num) in cases {
            assert_eq!(sf(SolfegeSystem::MovableDo, pc, 0, ModeKind::Major), mv);
            assert_eq!(sf(SolfegeSystem::Numbered, pc, 0, ModeKind::Major), num);
        }
        // 变化音
        assert_eq!(sf(SolfegeSystem::MovableDo, 1, 0, ModeKind::Major), "#do");
        assert_eq!(sf(SolfegeSystem::MovableDo, 6, 0, ModeKind::Major), "#fa");
        assert_eq!(sf(SolfegeSystem::MovableDo, 10, 0, ModeKind::Major), "b7");
    }

    // ---- 小调（A 小调，主音 A=9）：la si do re mi fa sol ----
    #[test]
    fn a_minor_la_based() {
        let cases = [
            (9u8, "la", "6"),
            (11, "si", "7"),
            (0, "do", "1"),
            (2, "re", "2"),
            (4, "mi", "3"),
            (5, "fa", "4"),
            (7, "sol", "5"),
        ];
        for (pc, mv, num) in cases {
            assert_eq!(
                sf(SolfegeSystem::MovableDo, pc, 9, ModeKind::Minor),
                mv,
                "pc={pc}"
            );
            assert_eq!(sf(SolfegeSystem::Numbered, pc, 9, ModeKind::Minor), num);
        }
    }

    // ---- 商/角/徵调式 ----
    #[test]
    fn shang_jue_zhi_modes() {
        // D 商（主音 D=2）：商 角 徵 羽 宫 = D E G A C
        assert_eq!(sf(SolfegeSystem::Chinese, 2, 2, ModeKind::Shang), "商");
        assert_eq!(sf(SolfegeSystem::Chinese, 4, 2, ModeKind::Shang), "角");
        assert_eq!(sf(SolfegeSystem::Chinese, 7, 2, ModeKind::Shang), "徵");
        assert_eq!(sf(SolfegeSystem::Chinese, 9, 2, ModeKind::Shang), "羽");
        assert_eq!(sf(SolfegeSystem::Chinese, 0, 2, ModeKind::Shang), "宫");
        // E 角（主音 E=4）：角 徵 羽 宫 商 = E G A C D
        assert_eq!(sf(SolfegeSystem::Chinese, 4, 4, ModeKind::Jue), "角");
        assert_eq!(sf(SolfegeSystem::Chinese, 7, 4, ModeKind::Jue), "徵");
        assert_eq!(sf(SolfegeSystem::Chinese, 9, 4, ModeKind::Jue), "羽");
        // G 徵（主音 G=7）：徵 羽 宫 商 角 = G A C D E
        assert_eq!(sf(SolfegeSystem::Chinese, 7, 7, ModeKind::Zhi), "徵");
        assert_eq!(sf(SolfegeSystem::Chinese, 9, 7, ModeKind::Zhi), "羽");
        assert_eq!(sf(SolfegeSystem::Chinese, 0, 7, ModeKind::Zhi), "宫");
        assert_eq!(sf(SolfegeSystem::Numbered, 7, 7, ModeKind::Zhi), "5");
    }

    // ---- 固定 Do：与调式无关 ----
    #[test]
    fn fixed_do_independent_of_key() {
        for mode in [
            ModeKind::Gong,
            ModeKind::Shang,
            ModeKind::Jue,
            ModeKind::Zhi,
            ModeKind::Yu,
            ModeKind::Major,
            ModeKind::Minor,
        ] {
            assert_eq!(sf(SolfegeSystem::FixedDo, 0, 5, mode), "do");
            assert_eq!(sf(SolfegeSystem::FixedDo, 1, 5, mode), "#do");
            assert_eq!(sf(SolfegeSystem::FixedDo, 9, 5, mode), "la");
            assert_eq!(sf(SolfegeSystem::FixedDo, 11, 5, mode), "si");
        }
    }

    // ---- 等价音（按 pitch class 处理）----
    #[test]
    fn enharmonic_equivalence() {
        // B#4 = C5 (pc 0)，Cb5 = B4 (pc 11)
        assert_eq!(sf(SolfegeSystem::Numbered, 0, 0, ModeKind::Major), "1");
        assert_eq!(sf(SolfegeSystem::Numbered, 11, 0, ModeKind::Major), "7");
        assert_eq!(sf(SolfegeSystem::FixedDo, 0, 0, ModeKind::Major), "do");
        assert_eq!(sf(SolfegeSystem::FixedDo, 11, 0, ModeKind::Major), "si");
        // 换主音一致平移：D 宫（主音 2）下 C#(1) 为变宫
        assert_eq!(sf(SolfegeSystem::Chinese, 1, 2, ModeKind::Gong), "变宫(7)");
    }
}
