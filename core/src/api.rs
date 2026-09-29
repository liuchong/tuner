//! UniFFI 导出层：`docs/spec-core.md` 附录 A 的一一映射，唯一对外接口。
//!
//! 命名按 Rust 惯例使用 snake_case（UniFFI 绑定生成端自动转为各语言惯例）。
//! 引擎对象由原生侧单线程调用，api 层用 Mutex 满足 UniFFI 的 &self 约定；
//! 内核（pitch/metronome）结构体内部无锁，feed/render 热路径不经过本层的锁竞争关键点。

use std::sync::{Arc, Mutex};

use crate::{
    fingering, metronome, note, pitch, reference, signal, smooth, solfege, spectrum, tuning,
};

pub use crate::signal::SignalState;

// ============================ 枚举 ============================

/// 乐器类别。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum InstrumentKind {
    /// 弦乐器。
    String,
    /// 管乐器。
    Wind,
}

/// 唱名体系。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum SolfegeSystem {
    /// 固定 Do（C=do）。
    FixedDo,
    /// 首调 Do。
    MovableDo,
    /// 简谱（1-7）。
    Numbered,
    /// 宫商角徵羽（含偏音）。
    Chinese,
}

/// 调式类别。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ModeKind {
    /// 宫调式。
    Gong,
    /// 商调式。
    Shang,
    /// 角调式。
    Jue,
    /// 徵调式。
    Zhi,
    /// 羽调式。
    Yu,
    /// 大调。
    Major,
    /// 小调。
    Minor,
}

/// 拍重音型。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum TickAccent {
    /// 重拍。
    Accent,
    /// 弱拍。
    Normal,
    /// 静音拍。
    Muted,
}

impl From<TickAccent> for metronome::Accent {
    fn from(a: TickAccent) -> Self {
        match a {
            TickAccent::Accent => metronome::Accent::Accent,
            TickAccent::Normal => metronome::Accent::Normal,
            TickAccent::Muted => metronome::Accent::Muted,
        }
    }
}

impl From<metronome::Accent> for TickAccent {
    fn from(a: metronome::Accent) -> Self {
        match a {
            metronome::Accent::Accent => TickAccent::Accent,
            metronome::Accent::Normal => TickAccent::Normal,
            metronome::Accent::Muted => TickAccent::Muted,
        }
    }
}

// ============================ 数据记录 ============================

/// 乐器元数据。
#[derive(Debug, Clone, uniffi::Record)]
pub struct Instrument {
    /// 乐器 id："guitar" | "ukulele" | "zhudi" | "dongxiao" | "shakuhachi" | "guqin"。
    pub id: String,
    /// 中文显示名。
    pub display_name: String,
    /// 类别。
    pub kind: InstrumentKind,
}

/// 一根弦的规格。
#[derive(Debug, Clone, uniffi::Record)]
pub struct StringSpec {
    /// 弦号（从 1 开始，含义见 spec-instruments）。
    pub index: u32,
    /// 音名（如 "E2"）。
    pub note_name: String,
    /// MIDI 音高（随 A4 换算/唱名重算的基准）。
    pub midi: i32,
    /// 目标频率（Hz，按当前 A4 校准换算；全局接口按 A4=440）。
    pub freq_hz: f64,
    /// 唱名（按乐器习惯调的首调简谱）。
    pub solfege: String,
}

/// 一个弦乐器定弦预设。
#[derive(Debug, Clone, uniffi::Record)]
pub struct Tuning {
    /// 定弦 id（如 "standard"、"drop_d"）。
    pub id: String,
    /// 中文显示名。
    pub display_name: String,
    /// 各弦（按弦号 1..=N 顺序）。
    pub strings: Vec<StringSpec>,
}

/// 一个孔的按放状态（孔位指法图用）。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HoleMark {
    /// 闭孔（按住）。
    Closed,
    /// 开孔（放开）。
    Open,
    /// 半开孔。
    Half,
}

/// 指法在洞箫大图旁的展示分组。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FingeringKind {
    /// 顺指：从下往上连续开孔（含全闭筒音与全开）。
    Sequential,
    /// 叉指、半孔或其他非连续指法。
    Combination,
}

/// 洞箫传统指法表使用的三个演奏音区。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum WindRegister {
    /// 缓吹（低音）。
    Low,
    /// 超吹（中音）。
    Middle,
    /// 急吹（高音）。
    High,
}

/// 指法表范围。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum FingeringScope {
    /// 七声基础孔位；洞箫每个孔位按低音/中音/高音各自的实测孔位展开，缺资料的格不返回。
    Scale,
    /// 十二基础孔位；洞箫同样按三音区实测孔位展开，其他乐器维持旧范围。
    Chromatic,
}

/// 一档筒音唱名（筒音相对宫音的半音级）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct TongyinOption {
    /// 半音级 0–11（筒音 = 宫音 + degree 半音）。7=作5、0=作1、2=作2。
    pub degree: u8,
    /// 该级的首调简谱唱名（"5"、"#4" 等）。
    pub solfege: String,
    /// 是否为该孔制的常用指法。
    pub common: bool,
}

/// 一个管乐器型号（调性/尺寸 × 孔制）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct WindVariant {
    /// 型号 id（如 "g_xiao_x8"、"shaku_1_8"）。
    pub id: String,
    /// 完整显示名（如 "G调洞箫 · 8孔"）。
    pub display_name: String,
    /// 调性/尺寸 id（同调性的不同孔制共享，如 "g_xiao"）。
    pub key_id: String,
    /// 调性/尺寸显示名（如 "G调洞箫"）。
    pub key_name: String,
    /// 孔制显示名（如 "8孔"；固定音阶类为空串）。
    pub hole_system_name: String,
    /// 孔数（固定音阶类为 0）。
    pub hole_count: u8,
    /// 末尾若干孔位于背面（拇指孔）。
    pub back_hole_count: u8,
    /// 筒音 MIDI。
    pub fundamental_midi: i32,
    /// 筒音音名。
    pub fundamental_note_name: String,
    /// 是否支持筒音唱名转调（孔制类支持 12 级）。
    pub supports_tongyin: bool,
    /// 是否支持十二音全表展开。
    pub supports_chromatic: bool,
    /// 12 档筒音唱名（升序 degree 0–11；固定音阶类为空）。
    pub tongyin_options: Vec<TongyinOption>,
    /// 默认筒音级（孔制类为作 5）。
    pub default_tongyin_degree: u8,
}

/// 孔位指法表中的一个音。
#[derive(Debug, Clone, uniffi::Record)]
pub struct WindFingering {
    /// 同一张表内唯一的稳定 id。
    pub fingering_id: i32,
    /// 当前音高相对筒音低音的半音数；不同指法可能产生同一音高。
    pub semitones: i32,
    /// 决定孔位组合的基础半音（洞箫为 0–11）。
    pub base_semitones: i32,
    /// 低音、中音或高音。
    pub register: WindRegister,
    /// 指法名（如 "开第一二三孔"、"闭第二五六七孔·超吹"）。
    pub label: String,
    /// 孔位组合，索引 0 = 第一孔（最下），末位 = 最上/背孔；固定音阶类为空。
    pub holes: Vec<HoleMark>,
    /// 指法展示分组：顺指或叉指/半孔。
    pub fingering_kind: FingeringKind,
    /// 最上方开孔的孔序索引（0 = 第一孔）；全闭/无孔位为 `None`。
    pub anchor_hole: Option<u8>,
    /// 音名。
    pub note_name: String,
    /// MIDI 音高。
    pub midi: i32,
    /// 目标频率（Hz，按 A4=440 换算）。
    pub freq_hz: f64,
    /// 唱名（按当前筒音级推出的宫音，首调简谱）。
    pub solfege: String,
    /// 是否为当前调宫调式七声的正声（否则为偏音）。
    pub in_scale: bool,
    /// 是否不是低音区。
    pub overblown: bool,
}

/// 一张孔位指法表（型号 + 筒音唱名 + 范围）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct WindChart {
    /// 型号 id。
    pub variant_id: String,
    /// 型号显示名（如 "G调洞箫 · 8孔"）。
    pub variant_name: String,
    /// 当前筒音级 0–11。
    pub tongyin_degree: u8,
    /// 当前筒音唱名（"5" 等）。
    pub tongyin_solfege: String,
    /// 宫音 pitch class。
    pub tonic_pc: u8,
    /// 宫音音名（不含八度，如 "G"）。
    pub tonic_name: String,
    /// 面板标题（如 "筒音作5 · G宫"）。
    pub key_display: String,
    /// 孔数（固定音阶类为 0）。
    pub hole_count: u8,
    /// 背孔数。
    pub back_hole_count: u8,
    /// 音阶（升序；筒音在首位，UI 可按需倒序显示）。
    pub notes: Vec<WindFingering>,
}

/// 调式（主音 + 调式类别）。
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct KeyMode {
    /// 主音 pitch class（0-11，C=0）。
    pub tonic_pc: u8,
    /// 调式类别。
    pub mode: ModeKind,
}

/// 调音器配置。
#[derive(Debug, Clone, uniffi::Record)]
pub struct TunarConfig {
    /// 采样率（Hz）。
    pub sample_rate: f64,
    /// 相邻分析帧之间推进的采样数（默认 1024）。
    pub frame_hop_samples: u32,
    /// A4 校准（415–466Hz）。
    pub a4_hz: f64,
    /// 噪声门限（dBFS，默认 -45）。
    pub noise_gate_dbfs: f32,
    /// 唱名体系。
    pub solfege: SolfegeSystem,
    /// 调式。
    pub key: KeyMode,
    /// N 平均律（12/19/24/31，默认 12；v4 新增）。
    pub temperament: u8,
}

/// 一次音高事件。
#[derive(Debug, Clone, uniffi::Record)]
pub struct TunarEvent {
    /// 平滑后频率（Hz）。
    pub freq_hz: f64,
    /// 音名（如 "A4"）。
    pub note_name: String,
    /// 最近 MIDI 音。
    pub midi: i32,
    /// 音分偏差 [-50, +50)。
    pub cents_off: f64,
    /// 检测置信度（0-1）。
    pub clarity: f32,
    /// 唱名（按 config 唱名体系）。
    pub solfege: String,
    /// 当前律制 N（v4 新增）。
    pub temperament: u8,
    /// 最近步序 k（A4 为参考）。
    pub temperament_step: i32,
    /// 律制音分偏差 [-600/N, +600/N)。
    pub temperament_cents: f64,
}

/// 一个泛音/独立音（v4 新增）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct Partial {
    /// 频率（Hz）。
    pub freq_hz: f64,
    /// 幅值（dBFS）。
    pub magnitude_db: f32,
    /// 泛音序号：0=独立音；1=基频；2,3,4…=基频泛音。
    pub harmonic_index: u8,
    /// 独立音时的 12-TET 音名。
    pub note_name: String,
    /// 独立音时相对最近 12-TET 音的 cents。
    pub cents_off: f64,
}

/// 一次完整分析帧（v4 新增）：feed 事件 + 频谱 + 泛音 + 和弦。
#[derive(Debug, Clone, uniffi::Record)]
pub struct AnalysisFrame {
    /// 同 feed 语义（无效输入为 None）。
    pub tuner: Option<TunarEvent>,
    /// 64 bin 对数轴 60–2400Hz 幅值（dBFS -80~0）。
    pub spectrum_db: Vec<f32>,
    /// 128 bin 对数轴 20Hz–wide_spectrum_max_hz 幅值（dBFS -80~0）。
    pub wide_spectrum_db: Vec<f32>,
    /// 全频段实际频率上限（min(20kHz, sample_rate/2)）。
    pub wide_spectrum_max_hz: f64,
    /// 当前分析窗口 256 列最小值包络。
    pub waveform_min: Vec<f32>,
    /// 当前分析窗口 256 列最大值包络。
    pub waveform_max: Vec<f32>,
    /// 当前帧末端相对引擎启动时的采样位置。
    pub sample_position: u64,
    /// 实际分析采样率。
    pub sample_rate_hz: f64,
    /// 泛音列（≤8，按幅值降序）。
    pub partials: Vec<Partial>,
    /// 和弦名（如 "Cmaj"），无则为 None。
    pub chord: Option<String>,
    /// 输入信号状态。
    pub signal_state: SignalState,
    /// 当前分析窗口的 RMS 电平（dBFS）。
    pub input_level_dbfs: f32,
    /// 读数显示强度（0~1）。
    pub display_strength: f32,
    /// 当前读数是否来自断音保持。
    pub is_held: bool,
}

/// 当前平均律中的一个可播放固定音高。
#[derive(Debug, Clone, uniffi::Record)]
pub struct ReferenceTone {
    /// 相对 A4 的平均律步数。
    pub step_from_a4: i32,
    /// 固定频率（Hz）。
    pub frequency_hz: f64,
    /// 平均律等分数。
    pub temperament: u8,
    /// 最近的 12 平均律音名。
    pub note_name: String,
    /// 相对该音名的音分差。
    pub cents_from_note: f64,
}

/// 一次 tick 事件。
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct TickInfo {
    /// 相对本次 render 缓冲起点的采样偏移。
    pub sample_offset: u64,
    /// 小节内第几拍（0 起）。
    pub beat_index: u32,
    /// 重音型。
    pub accent: TickAccent,
}

/// 节拍器配置。
#[derive(Debug, Clone, uniffi::Record)]
pub struct MetronomeConfig {
    /// 采样率（Hz）。
    pub sample_rate: f64,
    /// BPM（30–250，浮点）。
    pub bpm: f64,
    /// 每小节拍数（1–12）。
    pub beats_per_bar: u8,
    /// 拍单位（2|4|8）。
    pub beat_unit: u8,
    /// 每拍重音型（长度 = beats_per_bar）。
    pub accents: Vec<TickAccent>,
}

/// 一次 render 的输出。
#[derive(Debug, Clone, uniffi::Record)]
pub struct RenderFrame {
    /// PCM（含混入的 tick 音色）。
    pub samples: Vec<f32>,
    /// 本区间内的 tick 事件。
    pub ticks: Vec<TickInfo>,
}

// ============================ 全局函数 ============================

/// 各乐器「习惯调」（全局查询接口的唱名基准，见 spec-instruments 卷首说明）。
fn habitual_key(instrument_id: &str) -> KeyMode {
    match instrument_id {
        // 古琴正调为 F 调
        "guqin" => KeyMode {
            tonic_pc: 5,
            mode: ModeKind::Gong,
        },
        // 吉他/尤克里里按 C 调
        _ => KeyMode {
            tonic_pc: 0,
            mode: ModeKind::Major,
        },
    }
}

/// 列出全部乐器。
#[uniffi::export]
pub fn list_instruments() -> Vec<Instrument> {
    let mut out: Vec<Instrument> = tuning::list_string_instruments()
        .map(tuning::instrument_meta)
        .collect();
    for id in ["zhudi", "dongxiao", "shakuhachi"] {
        if let Some(meta) = fingering::wind_instrument_meta(id) {
            out.push(meta);
        }
    }
    out
}

/// 列出某弦乐器的全部定弦（频率按 A4=440，唱名按乐器习惯调简谱）。
#[uniffi::export]
pub fn list_tunings(instrument_id: String) -> Vec<Tuning> {
    let Some(def) = tuning::find_string_instrument(&instrument_id) else {
        return Vec::new();
    };
    let key = habitual_key(&instrument_id);
    let mut name_buf = [0u8; 5];
    let mut sf_buf = [0u8; 16];
    def.tunings
        .iter()
        .map(|t| Tuning {
            id: t.id.to_string(),
            display_name: t.display_name.to_string(),
            strings: t
                .strings
                .iter()
                .enumerate()
                .map(|(i, &midi)| StringSpec {
                    index: i as u32 + 1,
                    note_name: note::midi_to_name(midi as i32, &mut name_buf)
                        .unwrap_or("")
                        .to_string(),
                    midi: midi as i32,
                    freq_hz: note::midi_to_freq(midi, note::A4_DEFAULT),
                    solfege: solfege::solfege_of(
                        SolfegeSystem::Numbered,
                        note::midi_to_pc(midi as i32),
                        key.tonic_pc,
                        key.mode,
                        &mut sf_buf,
                    )
                    .to_string(),
                })
                .collect(),
        })
        .collect()
}

/// 乐器面板唱名基准：固定简谱 + 宫调式（见 §6，不随全局唱名设置变化）。
fn panel_solfege(pc: u8, tonic_pc: u8) -> String {
    let mut buf = [0u8; 16];
    solfege::solfege_of(
        SolfegeSystem::Numbered,
        pc,
        tonic_pc,
        ModeKind::Gong,
        &mut buf,
    )
    .to_string()
}

/// 列出某管乐器的全部型号（调性/尺寸 × 孔制）。
///
/// 顺序即优先级：洞箫首项为「G 调 · 8 孔」，作为默认型号。
#[uniffi::export]
pub fn list_wind_variants(instrument_id: String) -> Vec<WindVariant> {
    let Some(variants) = fingering::wind_variants(&instrument_id) else {
        return Vec::new();
    };
    let mut name_buf = [0u8; 5];
    variants
        .iter()
        .map(|v| {
            let system = v.hole_system();
            let tongyin_options = system
                .map(|s| {
                    (0u8..12)
                        .map(|degree| TongyinOption {
                            degree,
                            solfege: panel_solfege(degree, 0),
                            common: s.common_tongyin.contains(&degree),
                        })
                        .collect()
                })
                .unwrap_or_default();
            let hole_system_name = system.map(|s| s.display_name).unwrap_or("");
            let display_name = if hole_system_name.is_empty() {
                v.key_name.to_string()
            } else {
                format!("{} · {}", v.key_name, hole_system_name)
            };
            WindVariant {
                id: v.id.to_string(),
                display_name,
                key_id: v.key_id.to_string(),
                key_name: v.key_name.to_string(),
                hole_system_name: hole_system_name.to_string(),
                hole_count: v.hole_count(),
                back_hole_count: v.back_hole_count(),
                fundamental_midi: v.fundamental_midi,
                fundamental_note_name: note::midi_to_name(v.fundamental_midi, &mut name_buf)
                    .unwrap_or("")
                    .to_string(),
                supports_tongyin: v.supports_tongyin(),
                supports_chromatic: v.supports_chromatic(),
                tongyin_options,
                default_tongyin_degree: if v.supports_tongyin() {
                    fingering::TONGYIN_SOL5
                } else {
                    0
                },
            }
        })
        .collect()
}

/// 某型号在指定筒音唱名级与范围下的孔位指法表。
///
/// `tongyin_degree` 为筒音相对宫音的半音数 0–11（7=作5、0=作1、2=作2），越界按 12 取模；
/// 固定音阶类型号（尺八）忽略该参数与 `Chromatic` 范围。未知型号返回 `None`。
#[uniffi::export]
pub fn wind_fingering_chart(
    variant_id: String,
    tongyin_degree: u8,
    scope: FingeringScope,
) -> Option<WindChart> {
    let v = fingering::find_wind_variant(&variant_id)?;
    let degree = if v.supports_tongyin() {
        tongyin_degree % 12
    } else {
        0
    };
    let tonic_pc = v.tonic_pc(degree);
    let chromatic = matches!(scope, FingeringScope::Chromatic);
    let system = v.hole_system();
    let notes = if system.is_some() {
        let scale_offsets = fingering::scale_base_offsets(degree);
        let base_offsets: &[i32] = if chromatic {
            &fingering::CHROMATIC_BASE_OFFSETS
        } else {
            &scale_offsets
        };
        base_offsets
            .iter()
            .flat_map(|&base| {
                // 每个音区取该音高的实测孔位：中音区多数与低音区同孔位，高音区自成一套；
                // 资料未覆盖的格直接不返回，客户端留空。fingering_id 取音高半音数，天然唯一。
                HOLED_REGISTERS
                    .iter()
                    .filter_map(move |&(register, shift)| {
                        let pitch = base + shift;
                        v.pattern_at_pitch(pitch)?;
                        Some(make_wind_fingering(
                            v,
                            tonic_pc,
                            base,
                            pitch,
                            pitch,
                            register,
                            base as usize,
                        ))
                    })
            })
            .collect()
    } else {
        // 固定音阶类（尺八）按八度分乙/甲/大甲三音区，base 取八度内位置以便客户端按行对齐。
        v.offsets(chromatic)
            .iter()
            .enumerate()
            .map(|(index, &off)| {
                let register = match off.div_euclid(12) {
                    0 => WindRegister::Low,
                    1 => WindRegister::Middle,
                    _ => WindRegister::High,
                };
                make_wind_fingering(v, tonic_pc, off.rem_euclid(12), off, off, register, index)
            })
            .collect()
    };
    let tongyin_solfege = panel_solfege(degree, 0);
    let tonic_name = note::NOTE_NAMES[tonic_pc as usize].to_string();
    Some(WindChart {
        variant_id: v.id.to_string(),
        variant_name: match system {
            Some(s) => format!("{} · {}", v.key_name, s.display_name),
            None => v.key_name.to_string(),
        },
        tongyin_degree: degree,
        tongyin_solfege: tongyin_solfege.clone(),
        tonic_pc,
        tonic_name: tonic_name.clone(),
        key_display: if v.supports_tongyin() {
            format!("筒音作{tongyin_solfege} · {tonic_name}宫")
        } else {
            format!("筒音为宫 · {tonic_name}宫")
        },
        hole_count: v.hole_count(),
        back_hole_count: v.back_hole_count(),
        notes,
    })
}

/// 孔制类（笛/箫）三音区与其相对低音区的半音位移；每个音区的孔位另按该音高的实测值取。
const HOLED_REGISTERS: [(WindRegister, i32); 3] = [
    (WindRegister::Low, 0),
    (WindRegister::Middle, 12),
    (WindRegister::High, 24),
];

#[allow(clippy::too_many_arguments)]
fn make_wind_fingering(
    variant: &fingering::WindVariantDef,
    tonic_pc: u8,
    base_semitones: i32,
    pitch_semitones: i32,
    fingering_id: i32,
    register: WindRegister,
    label_index: usize,
) -> WindFingering {
    let midi = variant.fundamental_midi + pitch_semitones;
    let pc = note::midi_to_pc(midi);
    // 孔位取自实际音高：中、高音区各有自己的实测孔位。
    let pattern = variant.pattern_at(pitch_semitones);
    let fingering_kind = if variant
        .hole_system()
        .is_some_and(|system| system.id == "x8")
        && pitch_semitones.rem_euclid(12) != 10
        && !pattern.contains('H')
    {
        // 八孔箫第 2、6 辅助孔常闭属于正规指法，不应因此误标为叉指。
        FingeringKind::Sequential
    } else {
        fingering::fingering_kind(pattern)
    };
    let mut name_buf = [0u8; 5];
    WindFingering {
        fingering_id,
        semitones: pitch_semitones,
        base_semitones,
        register,
        label: variant.label_at(label_index, pitch_semitones),
        holes: pattern.chars().map(hole_mark_of).collect(),
        fingering_kind,
        anchor_hole: fingering::fingering_anchor_hole(pattern),
        note_name: note::midi_to_name(midi, &mut name_buf)
            .unwrap_or("")
            .to_string(),
        midi,
        freq_hz: note::midi_to_freq(midi as f64, note::A4_DEFAULT),
        solfege: panel_solfege(pc, tonic_pc),
        in_scale: fingering::in_gong_scale(pc, tonic_pc),
        overblown: !matches!(register, WindRegister::Low),
    }
}

/// 孔位编码字符 → [`HoleMark`]。
fn hole_mark_of(c: char) -> HoleMark {
    match c {
        'O' => HoleMark::Open,
        'H' => HoleMark::Half,
        _ => HoleMark::Closed,
    }
}

/// 两频率间的音分差：1200·log2(freq/target)（§4 公式）。无效输入（≤0）返回 None。
#[uniffi::export]
pub fn cents_between(freq_hz: f64, target_hz: f64) -> Option<f64> {
    if freq_hz <= 0.0 || target_hz <= 0.0 {
        return None;
    }
    Some(1200.0 * (freq_hz / target_hz).log2())
}

/// 任意 MIDI 音的唱名（按唱名体系与调式；乐器面板弦/孔唱名随用户配置重算用）。
#[uniffi::export]
pub fn solfege_for_midi(system: SolfegeSystem, key: KeyMode, midi: i32) -> String {
    let mut buf = [0u8; 16];
    solfege::solfege_of(
        system,
        note::midi_to_pc(midi),
        key.tonic_pc,
        key.mode,
        &mut buf,
    )
    .to_string()
}

// ============================ TunarEngine ============================

/// TunarEngine 内核（无锁，单线程使用）。
struct TunarCore {
    yin: pitch::Yin,
    smoother: smooth::PitchSmoother,
    spectrum: spectrum::Spectrum,
    signal: signal::SignalTracker,
    a4: f64,
    solfege: SolfegeSystem,
    key: KeyMode,
    temperament: u8,
    sample_rate_hz: f64,
    frame_hop_samples: u64,
    sample_position: u64,
}

impl TunarCore {
    fn analyze_frame(&mut self, pcm: &[f32]) -> (Option<TunarEvent>, signal::SignalOutput, f32) {
        let input_level_dbfs = signal::input_level_dbfs(pcm);
        let detected = self.yin.feed(pcm).and_then(|(freq, clarity)| {
            let a4 = self.a4;
            self.smoother
                .feed(Some(freq), |f| note::analyze(f as f64, a4).map(|i| i.midi))
                .map(|out| signal::PitchSample {
                    freq_hz: out.freq_hz,
                    clarity,
                })
        });
        let signal = self.signal.process(detected, input_level_dbfs);
        let Some(pitch) = signal.pitch else {
            return (None, signal, input_level_dbfs);
        };
        let a4 = self.a4;
        let Some(info) = note::analyze(pitch.freq_hz as f64, a4) else {
            return (None, signal, input_level_dbfs);
        };
        let mut sf_buf = [0u8; 16];
        let sf = solfege::solfege_of(
            self.solfege,
            note::midi_to_pc(info.midi),
            self.key.tonic_pc,
            self.key.mode,
            &mut sf_buf,
        );
        let (t_step, t_cents) =
            note::temperament_step_cents(pitch.freq_hz as f64, a4, self.temperament)
                .unwrap_or((0, 0.0));
        let event = TunarEvent {
            freq_hz: pitch.freq_hz as f64,
            note_name: info.name().to_string(),
            midi: info.midi,
            cents_off: info.cents_off,
            clarity: pitch.clarity,
            solfege: sf.to_string(),
            temperament: self.temperament,
            temperament_step: t_step,
            temperament_cents: t_cents,
        };
        (Some(event), signal, input_level_dbfs)
    }

    /// 完整分析：feed 事件 + 频谱 + 泛音 + 和弦（UniFFI 边界允许分配）。
    fn analyze_full(&mut self, pcm: &[f32]) -> AnalysisFrame {
        let (tuner, signal, input_level_dbfs) = self.analyze_frame(pcm);
        let spectrum_db = self.spectrum.feed(pcm).to_vec();
        let wide_spectrum_db = self.spectrum.wide_spectrum().to_vec();
        let wide_spectrum_max_hz = self.spectrum.wide_max_hz() as f64;
        let (waveform_min, waveform_max) = waveform_envelope(pcm);
        self.sample_position = self.sample_position.saturating_add(self.frame_hop_samples);
        let f0 = tuner.as_ref().map(|t| t.freq_hz);
        let mut raw_partials = [spectrum::PartialInfo {
            freq_hz: 0.0,
            magnitude_db: spectrum::DB_FLOOR,
            harmonic_index: 0,
            midi: 0,
            cents_off: 0.0,
        }; spectrum::MAX_PARTIALS];
        let n = match f0 {
            Some(f0) => self
                .spectrum
                .detect_partials(f0, self.a4, &mut raw_partials),
            None => 0,
        };
        // 音级集合（基频 + 独立音）→ 和弦。
        // 幻影基频规则（spec §4a 补充）：泛音列中无 idx==1 的峰时，
        // idx ∈ 2..=6 的峰也计入音级（三和弦共同次谐波场景，如 C2→C/E/G 为 4/5/6 次）。
        let mut pcs = 0u16;
        if let Some(ev) = &tuner {
            pcs |= 1 << note::midi_to_pc(ev.midi);
        }
        let has_fundamental = raw_partials[..n].iter().any(|p| p.harmonic_index == 1);
        let mut name_buf = [0u8; 5];
        let partials: Vec<Partial> = raw_partials[..n]
            .iter()
            .map(|p| {
                let chord_pc = if p.harmonic_index == 0 {
                    Some(p.midi)
                } else if !has_fundamental && (2..=6).contains(&p.harmonic_index) {
                    note::analyze(p.freq_hz, self.a4).map(|i| i.midi)
                } else {
                    None
                };
                if let Some(m) = chord_pc {
                    pcs |= 1 << note::midi_to_pc(m);
                }
                Partial {
                    freq_hz: p.freq_hz,
                    magnitude_db: p.magnitude_db,
                    harmonic_index: p.harmonic_index,
                    note_name: if p.harmonic_index == 0 {
                        note::midi_to_name(p.midi, &mut name_buf)
                            .unwrap_or("")
                            .to_string()
                    } else {
                        String::new()
                    },
                    cents_off: p.cents_off,
                }
            })
            .collect();
        AnalysisFrame {
            tuner,
            spectrum_db,
            wide_spectrum_db,
            wide_spectrum_max_hz,
            waveform_min,
            waveform_max,
            sample_position: self.sample_position,
            sample_rate_hz: self.sample_rate_hz,
            partials,
            chord: spectrum::match_chord(pcs).map(|s| s.to_string()),
            signal_state: signal.state,
            input_level_dbfs,
            display_strength: signal.display_strength,
            is_held: signal.is_held,
        }
    }
}

/// 调音器引擎（UniFFI 对象）。
#[derive(uniffi::Object)]
pub struct TunarEngine {
    core: Mutex<TunarCore>,
}

#[uniffi::export]
impl TunarEngine {
    /// 构造。`config.a4_hz` 越界时收敛到 415–466。
    #[uniffi::constructor]
    pub fn new(config: TunarConfig) -> Arc<Self> {
        let a4 = config.a4_hz.clamp(note::A4_MIN, note::A4_MAX);
        let mut yin = pitch::Yin::new(config.sample_rate as f32);
        yin.set_noise_gate(config.noise_gate_dbfs - signal::GATE_HYSTERESIS_DB);
        Arc::new(Self {
            core: Mutex::new(TunarCore {
                yin,
                smoother: smooth::PitchSmoother::new(),
                spectrum: spectrum::Spectrum::new(config.sample_rate as f32),
                signal: signal::SignalTracker::new(
                    config.sample_rate,
                    config.frame_hop_samples,
                    config.noise_gate_dbfs,
                ),
                a4,
                solfege: config.solfege,
                key: config.key,
                temperament: note::temperament_or_default(config.temperament),
                sample_rate_hz: config.sample_rate,
                frame_hop_samples: u64::from(config.frame_hop_samples),
                sample_position: 0,
            }),
        })
    }

    /// 输入一帧单声道 PCM（f32 [-1,1]，长度 ≥ 2048），返回音高事件；无效输入返回 None。
    pub fn feed(&self, pcm: Vec<f32>) -> Option<TunarEvent> {
        let mut core = self.core.lock().unwrap_or_else(|e| e.into_inner());
        core.analyze_frame(&pcm).0
    }

    /// 完整分析帧：feed 事件 + 频谱 + 泛音 + 和弦（v4 新增，UniFFI 边界允许分配）。
    pub fn analyze(&self, pcm: Vec<f32>) -> AnalysisFrame {
        let mut core = self.core.lock().unwrap_or_else(|e| e.into_inner());
        core.analyze_full(&pcm)
    }

    /// 设置 A4 校准（收敛到 415–466Hz）。
    pub fn set_a4(&self, hz: f64) {
        let mut core = self.core.lock().unwrap_or_else(|e| e.into_inner());
        core.a4 = hz.clamp(note::A4_MIN, note::A4_MAX);
    }

    /// 设置唱名体系与调式。
    pub fn set_solfege(&self, system: SolfegeSystem, key: KeyMode) {
        let mut core = self.core.lock().unwrap_or_else(|e| e.into_inner());
        core.solfege = system;
        core.key = key;
    }

    /// 设置噪声门限（dBFS）。
    pub fn set_noise_gate(&self, dbfs: f32) {
        let mut core = self.core.lock().unwrap_or_else(|e| e.into_inner());
        core.yin.set_noise_gate(dbfs - signal::GATE_HYSTERESIS_DB);
        core.signal.set_noise_gate(dbfs);
    }

    /// 设置律制（N ∈ {12,19,24,31}；非法值忽略）。
    pub fn set_temperament(&self, divisions: u8) {
        if note::TEMPERAMENT_DIVISIONS.contains(&divisions) {
            let mut core = self.core.lock().unwrap_or_else(|e| e.into_inner());
            core.temperament = divisions;
        }
    }

    /// 列出当前 A4 与平均律在 80–1500Hz 内的全部固定音高。
    pub fn list_reference_tones(&self) -> Vec<ReferenceTone> {
        let core = self.core.lock().unwrap_or_else(|e| e.into_inner());
        reference::list(core.a4, core.temperament)
            .into_iter()
            .map(|tone| ReferenceTone {
                step_from_a4: tone.step_from_a4,
                frequency_hz: tone.frequency_hz,
                temperament: tone.temperament,
                note_name: tone.note_name,
                cents_from_note: tone.cents_from_note,
            })
            .collect()
    }
}

const WAVEFORM_COLUMNS: usize = 256;

/// 将当前分析窗口压缩为定宽最小/最大包络。UniFFI 分析边界允许返回向量。
fn waveform_envelope(pcm: &[f32]) -> (Vec<f32>, Vec<f32>) {
    let mut mins = vec![0.0; WAVEFORM_COLUMNS];
    let mut maxs = vec![0.0; WAVEFORM_COLUMNS];
    if pcm.is_empty() {
        return (mins, maxs);
    }
    for column in 0..WAVEFORM_COLUMNS {
        let start = column * pcm.len() / WAVEFORM_COLUMNS;
        let end = (column + 1) * pcm.len() / WAVEFORM_COLUMNS;
        if start >= end {
            continue;
        }
        let mut min_value = 1.0f32;
        let mut max_value = -1.0f32;
        for &raw in &pcm[start..end] {
            let sample = if raw.is_finite() {
                raw.clamp(-1.0, 1.0)
            } else {
                0.0
            };
            min_value = min_value.min(sample);
            max_value = max_value.max(sample);
        }
        mins[column] = min_value;
        maxs[column] = max_value;
    }
    (mins, maxs)
}

// ============================ Metronome ============================

/// Metronome 内核 + 预分配输出缓冲（api 层持有，render 路径复用）。
struct MetronomeState {
    core: metronome::MetronomeCore,
    buf: Vec<f32>,
    ticks: metronome::TickList,
}

/// 节拍器（UniFFI 对象）。
#[derive(uniffi::Object)]
pub struct Metronome {
    state: Mutex<MetronomeState>,
}

#[uniffi::export]
impl Metronome {
    /// 构造。
    #[uniffi::constructor]
    pub fn new(config: MetronomeConfig) -> Arc<Self> {
        let accents: Vec<metronome::Accent> = config.accents.iter().map(|&a| a.into()).collect();
        Arc::new(Self {
            state: Mutex::new(MetronomeState {
                core: metronome::MetronomeCore::new(
                    config.sample_rate,
                    config.bpm,
                    config.beats_per_bar,
                    config.beat_unit,
                    &accents,
                ),
                buf: Vec::new(),
                ticks: metronome::TickList::default(),
            }),
        })
    }

    /// 渲染 `frames` 个采样（含精确混入的 tick 音色），返回 PCM 与 tick 事件。
    /// UniFFI 边界允许分配（marshal 开销主导）；引擎内核零分配。
    pub fn render(&self, frames: u32) -> RenderFrame {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let n = frames as usize;
        st.buf.clear();
        st.buf.resize(n, 0.0);
        let MetronomeState { core, buf, ticks } = &mut *st;
        core.render_into(buf, n, ticks);
        RenderFrame {
            samples: st.buf.clone(),
            ticks: st
                .ticks
                .as_slice()
                .iter()
                .map(|t| TickInfo {
                    sample_offset: t.sample_offset,
                    beat_index: t.beat_index,
                    accent: t.accent.into(),
                })
                .collect(),
        }
    }

    /// 设置 BPM（30–250），下一采样生效。
    pub fn set_bpm(&self, bpm: f64) {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        st.core.set_bpm(bpm);
    }

    /// 设置拍号。
    pub fn set_time_signature(&self, beats: u8, unit: u8) {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        st.core.set_time_signature(beats, unit);
    }

    /// 设置每拍重音型。
    pub fn set_accents(&self, accents: Vec<TickAccent>) {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let a: Vec<metronome::Accent> = accents.iter().map(|&x| x.into()).collect();
        st.core.set_accents(&a);
    }

    /// 注入重拍/弱拍音色（由原生层提供；传空则恢复内置合成音色）。
    pub fn set_click_samples(&self, accent: Vec<f32>, normal: Vec<f32>) {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        st.core.set_click_samples(&accent, &normal);
    }

    /// tap tempo：输入 tap 的采样时间戳，返回当前 BPM。
    pub fn tap(&self, timestamp_samples: u64) -> f64 {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        st.core.tap(timestamp_samples)
    }

    /// 从 `at_sample` 开始运行。
    pub fn start(&self, at_sample: u64) {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        st.core.start(at_sample);
    }

    /// 停止。
    pub fn stop(&self) {
        let mut st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        st.core.stop();
    }

    /// 是否运行中。
    pub fn is_running(&self) -> bool {
        let st = self.state.lock().unwrap_or_else(|e| e.into_inner());
        st.core.is_running()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn default_config() -> TunarConfig {
        TunarConfig {
            sample_rate: 44100.0,
            frame_hop_samples: 1024,
            a4_hz: 440.0,
            noise_gate_dbfs: -50.0,
            solfege: SolfegeSystem::Numbered,
            key: KeyMode {
                tonic_pc: 0,
                mode: ModeKind::Major,
            },
            temperament: 12,
        }
    }

    #[test]
    fn list_all_instruments() {
        let instruments = list_instruments();
        assert_eq!(instruments.len(), 6);
        let ids: Vec<&str> = instruments.iter().map(|i| i.id.as_str()).collect();
        for want in [
            "guitar",
            "ukulele",
            "guqin",
            "zhudi",
            "dongxiao",
            "shakuhachi",
        ] {
            assert!(ids.contains(&want), "缺少 {want}");
        }
    }

    #[test]
    fn list_tunings_guitar() {
        let tunings = list_tunings("guitar".to_string());
        assert_eq!(tunings.len(), 5);
        let std = &tunings[0];
        assert_eq!(std.id, "standard");
        assert_eq!(std.strings.len(), 6);
        assert_eq!(std.strings[0].index, 1);
        assert_eq!(std.strings[0].note_name, "E4");
        assert!((std.strings[0].freq_hz - 329.6276).abs() < 0.1);
        assert_eq!(std.strings[5].note_name, "E2");
        assert!((std.strings[5].freq_hz - 82.4069).abs() < 0.1);
        // 未知乐器 → 空
        assert!(list_tunings("piano".to_string()).is_empty());
    }

    #[test]
    fn list_tunings_guitar_solfege_habit_key() {
        // 吉他标准调弦（C 调习惯）：EADGBE → 简谱 3 7 5 2 6 3
        let tunings = list_tunings("guitar".to_string());
        let std = tunings.iter().find(|t| t.id == "standard").unwrap();
        let sfs: Vec<&str> = std.strings.iter().map(|s| s.solfege.as_str()).collect();
        assert_eq!(sfs, ["3", "7", "5", "2", "6", "3"]);
    }

    #[test]
    fn list_tunings_guqin_solfege() {
        let tunings = list_tunings("guqin".to_string());
        assert_eq!(tunings.len(), 3);
        let zd = &tunings[0];
        // 正调（F 调）：唱名 5 6 1 2 3 5 6
        let sfs: Vec<&str> = zd.strings.iter().map(|s| s.solfege.as_str()).collect();
        assert_eq!(sfs, ["5", "6", "1", "2", "3", "5", "6"]);
    }

    #[test]
    fn zhudi_chart_expands_three_registers_with_dizi_fingerings() {
        // D 调曲笛 · 筒音作 5：筒音 a1 = A4 = 440Hz
        let chart =
            wind_fingering_chart("d_qudi_d6".to_string(), 7, FingeringScope::Scale).unwrap();
        assert_eq!(chart.hole_count, 6);
        assert_eq!(chart.back_hole_count, 0);
        assert_eq!(chart.key_display, "筒音作5 · D宫");
        let first = low_note(&chart, 0);
        assert_eq!(first.note_name, "A4");
        assert!((first.freq_hz - 440.0).abs() < 0.1);
        assert_eq!(first.solfege, "5");
        assert_eq!(first.label, "筒音");
        // 七声主表按筒音筛选：作 5 的第 7 个音是 4（叉口），不再出现 #4。
        let low: Vec<&str> = chart
            .notes
            .iter()
            .filter(|note| note.register == WindRegister::Low)
            .map(|note| note.solfege.as_str())
            .collect();
        assert_eq!(low, ["5", "6", "7", "1", "2", "3", "4"]);
        let fork = low_note(&chart, 10);
        assert_eq!(
            fork.holes,
            vec![
                HoleMark::Open,
                HoleMark::Open,
                HoleMark::Open,
                HoleMark::Closed,
                HoleMark::Closed,
                HoleMark::Open,
            ]
        );
        assert_eq!(fork.fingering_kind, FingeringKind::Combination);
        // 作 2 同样不出现 #4 / #1。
        let re2 = wind_fingering_chart("d_qudi_d6".to_string(), 2, FingeringScope::Scale).unwrap();
        let re2_low: Vec<&str> = re2
            .notes
            .iter()
            .filter(|note| note.register == WindRegister::Low)
            .map(|note| note.solfege.as_str())
            .collect();
        assert_eq!(re2_low, ["2", "3", "4", "5", "6", "7", "1"]);
        // 中音筒音开第六孔作泛音孔；高音只收录资料一致的 24 半音。
        let middle_root = chart
            .notes
            .iter()
            .find(|note| note.semitones == 12)
            .unwrap();
        assert_eq!(middle_root.register, WindRegister::Middle);
        assert_eq!(middle_root.holes[5], HoleMark::Open);
        let high: Vec<i32> = chart
            .notes
            .iter()
            .filter(|note| note.register == WindRegister::High)
            .map(|note| note.semitones)
            .collect();
        assert_eq!(high, [24]);
        let mut ids: Vec<i32> = chart.notes.iter().map(|note| note.fingering_id).collect();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), chart.notes.len());
        assert!(wind_fingering_chart("suona".to_string(), 7, FingeringScope::Scale).is_none());
    }

    #[test]
    fn list_wind_variants_dongxiao() {
        let variants = list_wind_variants("dongxiao".to_string());
        assert_eq!(variants.len(), 4);
        let first = &variants[0];
        assert_eq!(first.id, "g_xiao_x8");
        assert_eq!(first.display_name, "G调洞箫 · 8孔");
        assert_eq!(first.hole_system_name, "8孔");
        assert_eq!(first.hole_count, 8);
        assert_eq!(first.back_hole_count, 1);
        assert_eq!(first.fundamental_note_name, "D4");
        assert!(first.supports_tongyin);
        assert!(first.supports_chromatic);
        assert_eq!(first.default_tongyin_degree, 7);
        // 12 档筒音唱名，简谱顺序，8 孔常用 5/1/2/3/6
        let sfs: Vec<&str> = first
            .tongyin_options
            .iter()
            .map(|o| o.solfege.as_str())
            .collect();
        assert_eq!(
            sfs,
            [
                "1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "b7", "7"
            ]
        );
        let common: Vec<u8> = first
            .tongyin_options
            .iter()
            .filter(|o| o.common)
            .map(|o| o.degree)
            .collect();
        assert_eq!(common, [0, 2, 4, 7, 9]);
        // 6 孔常用只有 5/1/2
        let x6 = variants.iter().find(|v| v.id == "g_xiao_x6").unwrap();
        assert_eq!(x6.hole_count, 6);
        assert_eq!(x6.tongyin_options.iter().filter(|o| o.common).count(), 3);
        // 尺八无筒音档
        let shaku = list_wind_variants("shakuhachi".to_string());
        assert_eq!(shaku.len(), 4);
        assert_eq!(shaku[0].display_name, "尺八 1.8寸（D调）");
        assert!(!shaku[0].supports_tongyin);
        assert!(shaku[0].tongyin_options.is_empty());
        assert_eq!(shaku[0].hole_count, 5);
        assert_eq!(shaku[0].back_hole_count, 1);
        assert!(list_wind_variants("suona".to_string()).is_empty());
    }

    /// 取某个基础孔位的低音区条目（三音区展开后按 base 定位更稳）。
    fn low_note(chart: &WindChart, base_semitones: i32) -> &WindFingering {
        chart
            .notes
            .iter()
            .find(|note| {
                note.base_semitones == base_semitones && matches!(note.register, WindRegister::Low)
            })
            .expect("低音区应包含该孔位")
    }

    #[test]
    fn wind_fingering_chart_uses_measured_patterns_for_each_register() {
        // G 调 8 孔洞箫 · 筒音作 5：筒音 d1 = D4，唱名 5，宫音 G
        let chart =
            wind_fingering_chart("g_xiao_x8".to_string(), 7, FingeringScope::Scale).unwrap();
        // 7 孔位 × 3 音区，减去指法图未覆盖的两格高音（33、34 半音）。
        assert_eq!(chart.notes.len(), 19);
        assert_eq!(chart.tongyin_solfege, "5");
        assert_eq!(chart.tonic_name, "G");
        assert_eq!(chart.key_display, "筒音作5 · G宫");
        assert_eq!(chart.hole_count, 8);
        let first = &chart.notes[0];
        assert_eq!(first.fingering_id, 0);
        assert_eq!(first.semitones, 0);
        assert_eq!(first.base_semitones, 0);
        assert_eq!(first.register, WindRegister::Low);
        assert_eq!(first.note_name, "D4");
        assert_eq!(first.solfege, "5");
        assert_eq!(first.label, "筒音");
        assert_eq!(first.holes, vec![HoleMark::Closed; 8]);
        assert_eq!(first.fingering_kind, FingeringKind::Sequential);
        assert_eq!(first.anchor_hole, None);
        assert!(first.in_scale);
        assert!(!first.overblown);
        // 中音区（超吹）多数沿用同一孔位。
        let middle = &chart.notes[1];
        assert_eq!(middle.register, WindRegister::Middle);
        assert_eq!(middle.base_semitones, 0);
        assert_eq!(middle.semitones, 12);
        assert_eq!(middle.note_name, "D5");
        assert_eq!(middle.label, "筒音·超吹");
        assert_eq!(middle.holes, first.holes);
        assert_eq!(middle.anchor_hole, first.anchor_hole);
        assert!(middle.overblown);
        // 高音区（急吹）孔位与低、中音区不同，按指法图实测取值。
        let high = &chart.notes[2];
        assert_eq!(high.register, WindRegister::High);
        assert_eq!(high.semitones, 24);
        assert_eq!(high.note_name, "D6");
        assert_ne!(high.holes, first.holes);
        assert_eq!(high.label, "闭第二五六七孔·超吹二");
        assert_eq!(high.anchor_hole, Some(7));
        assert!(high.overblown);
        // 指法图未覆盖的高音格不返回，客户端留空。
        assert!(
            chart
                .notes
                .iter()
                .all(|note| note.base_semitones != 9 || note.register != WindRegister::High)
        );
        let mut ids: Vec<i32> = chart.notes.iter().map(|note| note.fingering_id).collect();
        ids.sort_unstable();
        ids.dedup();
        assert_eq!(ids.len(), chart.notes.len());
        assert_eq!(
            chart
                .notes
                .iter()
                .filter(|note| matches!(note.register, WindRegister::Low))
                .count(),
            7
        );
        assert_eq!(
            chart
                .notes
                .iter()
                .filter(|note| matches!(note.register, WindRegister::Low))
                .map(|note| note.solfege.as_str())
                .collect::<Vec<_>>(),
            ["5", "6", "7", "1", "2", "3", "4"]
        );
        assert_eq!(
            chart
                .notes
                .iter()
                .filter(|note| matches!(note.register, WindRegister::Low))
                .map(|note| note.base_semitones)
                .collect::<Vec<_>>(),
            [0, 2, 4, 5, 7, 9, 10]
        );
        let regular_aux_closed = low_note(&chart, 4); // 相对筒音 4 半音
        assert_eq!(
            regular_aux_closed.holes,
            vec![
                HoleMark::Open,
                HoleMark::Closed,
                HoleMark::Open,
                HoleMark::Closed,
                HoleMark::Closed,
                HoleMark::Closed,
                HoleMark::Closed,
                HoleMark::Closed,
            ]
        );
        assert_eq!(regular_aux_closed.fingering_kind, FingeringKind::Sequential);
        assert_eq!(regular_aux_closed.anchor_hole, Some(2));
        // 十二音详情同样按三音区展开：12 低音 + 12 中音 + 指法图覆盖到 31 半音的 8 个高音。
        let full =
            wind_fingering_chart("g_xiao_x8".to_string(), 7, FingeringScope::Chromatic).unwrap();
        assert_eq!(full.notes.len(), 32);
        let middle_fork = full
            .notes
            .iter()
            .find(|note| note.base_semitones == 10 && note.register == WindRegister::Middle)
            .expect("22 半音应有中音指法");
        assert_ne!(
            middle_fork.holes,
            low_note(&full, 10).holes,
            "22 半音的中音叉口与低音叉口不同"
        );
        let semi1 = low_note(&full, 1);
        assert_eq!(semi1.base_semitones, 1);
        assert_eq!(semi1.register, WindRegister::Low);
        assert_eq!(semi1.label, "第一孔半开");
        assert_eq!(semi1.holes[0], HoleMark::Half);
        assert_eq!(semi1.fingering_kind, FingeringKind::Combination);
        assert_eq!(semi1.anchor_hole, Some(0));
        assert!(!semi1.in_scale); // #5 是偏音
        // 叉口指法（4）：闭第二五六七孔
        let fork = low_note(&full, 10);
        assert_eq!(fork.base_semitones, 10);
        assert_eq!(fork.label, "闭第二五六七孔");
        assert_eq!(fork.fingering_kind, FingeringKind::Combination);
        assert_eq!(fork.anchor_hole, Some(7));
        assert!(fork.in_scale); // G 宫下 C 为正声
    }

    #[test]
    fn wind_fingering_chart_keeps_chromatic_stable_and_refilters_scale_by_tongyin() {
        // 十二音详情始终稳定；七声主表按当前筒音档重筛，确保完整显示 1–7。
        let a =
            wind_fingering_chart("g_xiao_x8".to_string(), 7, FingeringScope::Chromatic).unwrap();
        let b =
            wind_fingering_chart("g_xiao_x8".to_string(), 2, FingeringScope::Chromatic).unwrap();
        assert_eq!(a.notes.len(), b.notes.len());
        for (x, y) in a.notes.iter().zip(b.notes.iter()) {
            assert_eq!(x.note_name, y.note_name);
            assert_eq!(x.label, y.label);
            assert_eq!(x.holes, y.holes);
        }
        assert_eq!(b.tongyin_solfege, "2");
        assert_eq!(b.notes[0].solfege, "2");
        assert_eq!(b.tonic_name, "C"); // 筒音 D 作 2 → 宫 = C
        assert_eq!(b.key_display, "筒音作2 · C宫");
        let scale =
            wind_fingering_chart("g_xiao_x8".to_string(), 2, FingeringScope::Scale).unwrap();
        assert_eq!(
            scale
                .notes
                .iter()
                .filter(|note| matches!(note.register, WindRegister::Low))
                .map(|note| note.solfege.as_str())
                .collect::<Vec<_>>(),
            ["2", "3", "4", "5", "6", "7", "1"]
        );
        assert_eq!(
            scale
                .notes
                .iter()
                .filter(|note| matches!(note.register, WindRegister::Low))
                .map(|note| note.base_semitones)
                .collect::<Vec<_>>(),
            [0, 2, 3, 5, 7, 9, 10]
        );
        // 越界筒音级按 12 取模，等价于作 5
        let wrapped =
            wind_fingering_chart("g_xiao_x8".to_string(), 19, FingeringScope::Scale).unwrap();
        assert_eq!(wrapped.tongyin_degree, 7);
        assert_eq!(wrapped.tongyin_solfege, "5");
    }

    #[test]
    fn wind_fingering_chart_shakuhachi_ignores_tongyin_and_splits_octaves() {
        let chart =
            wind_fingering_chart("shaku_1_8".to_string(), 5, FingeringScope::Chromatic).unwrap();
        // 固定音阶类：忽略筒音级与十二音范围
        assert_eq!(chart.notes.len(), 11);
        assert_eq!(chart.tongyin_degree, 0);
        assert_eq!(chart.tonic_name, "D");
        assert_eq!(chart.key_display, "筒音为宫 · D宫");
        assert_eq!(chart.hole_count, 5);
        assert_eq!(chart.back_hole_count, 1);
        let ro = &chart.notes[0];
        assert_eq!(ro.label, "筒音(ro)");
        assert_eq!(ro.holes, vec![HoleMark::Closed; 5]);
        assert_eq!(ro.register, WindRegister::Low);
        // 乙 5 音、甲 5 音、大甲 1 音；base 取八度内位置以便对齐行。
        let count = |register| {
            chart
                .notes
                .iter()
                .filter(|n| n.register == register)
                .count()
        };
        assert_eq!(count(WindRegister::Low), 5);
        assert_eq!(count(WindRegister::Middle), 5);
        assert_eq!(count(WindRegister::High), 1);
        let ha_kan = chart.notes.iter().find(|n| n.semitones == 22).unwrap();
        assert_eq!(ha_kan.base_semitones, 10);
        assert_eq!(ha_kan.register, WindRegister::Middle);
        assert_eq!(
            ha_kan.holes,
            vec![
                HoleMark::Closed,
                HoleMark::Open,
                HoleMark::Open,
                HoleMark::Open,
                HoleMark::Closed,
            ]
        );
        let daikan = chart.notes.last().unwrap();
        assert_eq!(daikan.register, WindRegister::High);
        assert_eq!(daikan.holes, vec![HoleMark::Open; 5]);
        assert!(wind_fingering_chart("nope".to_string(), 0, FingeringScope::Scale).is_none());
    }

    #[test]
    fn cents_between_basics() {
        // 八度 = 1200 cents，半音 = 100 cents，同频 = 0
        assert!((cents_between(880.0, 440.0).unwrap() - 1200.0).abs() < 1e-9);
        assert!((cents_between(466.16, 440.0).unwrap() - 100.0).abs() < 0.1);
        assert_eq!(cents_between(440.0, 440.0), Some(0.0));
        // 无效输入 → None
        assert_eq!(cents_between(0.0, 440.0), None);
        assert_eq!(cents_between(440.0, -1.0), None);
    }

    #[test]
    fn solfege_for_midi_basics() {
        let c_major = KeyMode {
            tonic_pc: 0,
            mode: ModeKind::Major,
        };
        // A4(69) 在 C 大调简谱 = 6；C4(60) = 1
        assert_eq!(solfege_for_midi(SolfegeSystem::Numbered, c_major, 69), "6");
        assert_eq!(solfege_for_midi(SolfegeSystem::Numbered, c_major, 60), "1");
        // F(65) 在 C 宫 Chinese = 清角(4)；B(71) = 变宫(7)
        let c_gong = KeyMode {
            tonic_pc: 0,
            mode: ModeKind::Gong,
        };
        assert_eq!(
            solfege_for_midi(SolfegeSystem::Chinese, c_gong, 65),
            "清角(4)"
        );
        assert_eq!(
            solfege_for_midi(SolfegeSystem::Chinese, c_gong, 71),
            "变宫(7)"
        );
        // 固定 Do 与调式无关：C=do
        assert_eq!(solfege_for_midi(SolfegeSystem::FixedDo, c_gong, 60), "do");
        // 负 MIDI 防御（pc 取模不 panic）
        let _ = solfege_for_midi(SolfegeSystem::Numbered, c_major, -1);
    }

    #[test]
    fn midi_fields_consistent_with_freq_and_name() {
        // StringSpec/WindFingering 的 midi 与 freq_hz（A4=440）、note_name 一致
        for t in list_tunings("guitar".to_string()) {
            for s in t.strings {
                let f = crate::note::midi_to_freq(s.midi as f64, 440.0);
                assert!((f - s.freq_hz).abs() < 1e-9);
                let mut buf = [0u8; 5];
                assert_eq!(
                    crate::note::midi_to_name(s.midi, &mut buf),
                    Some(s.note_name.as_str())
                );
            }
        }
        for v in list_wind_variants("zhudi".to_string()) {
            let chart = wind_fingering_chart(v.id, 7, FingeringScope::Chromatic).unwrap();
            for n in chart.notes {
                let f = crate::note::midi_to_freq(n.midi as f64, 440.0);
                assert!((f - n.freq_hz).abs() < 1e-9);
            }
        }
    }

    #[test]
    fn tuner_engine_feed_synth() {
        // 合成 440Hz 正弦（多帧以通过平滑与滞回）
        let sr = 44100.0f32;
        let engine = TunarEngine::new(default_config());
        let mut pcm = vec![0.0f32; 2048];
        let mut last = None;
        for frame in 0..8 {
            for (i, v) in pcm.iter_mut().enumerate() {
                let t = (frame * 2048 + i) as f32 / sr;
                *v = 0.5 * (2.0 * core::f32::consts::PI * 440.0 * t).sin();
            }
            last = engine.feed(pcm.clone());
        }
        let ev = last.expect("应检出 440Hz");
        assert_eq!(ev.midi, 69);
        assert_eq!(ev.note_name, "A4");
        assert!(ev.cents_off.abs() < 1.0);
        assert!(ev.clarity > 0.6);
        assert_eq!(ev.solfege, "6"); // C 大调简谱中 A = la = 6
        // 静音后无限保持最后读数，直到下一次达到阈值的新音高替换。
        assert!(engine.feed(vec![0.0f32; 2048]).is_some());
        let mut after_hold = Some(ev);
        for _ in 0..60 {
            after_hold = engine.feed(vec![0.0f32; 2048]);
        }
        let held = after_hold.expect("静音不应清空最后一次有效读数");
        assert_eq!(held.midi, 69);
        assert_eq!(held.note_name, "A4");
    }

    #[test]
    fn tuner_engine_setters() {
        let engine = TunarEngine::new(default_config());
        engine.set_a4(442.0);
        engine.set_noise_gate(-40.0);
        engine.set_solfege(
            SolfegeSystem::Chinese,
            KeyMode {
                tonic_pc: 2,
                mode: ModeKind::Gong,
            },
        );
        // D 宫下 A(9) 为 徵
        let sr = 44100.0f32;
        let mut pcm = vec![0.0f32; 2048];
        let mut last = None;
        for frame in 0..8 {
            for (i, v) in pcm.iter_mut().enumerate() {
                let t = (frame * 2048 + i) as f32 / sr;
                *v = 0.5 * (2.0 * core::f32::consts::PI * 442.0 * t).sin();
            }
            last = engine.feed(pcm.clone());
        }
        let ev = last.unwrap();
        assert_eq!(ev.midi, 69);
        assert!(ev.cents_off.abs() < 1.0);
        assert_eq!(ev.solfege, "徵");
    }

    /// 合成多正弦信号（测试辅助）。
    fn synth(components: &[(f32, f32)], frames: usize) -> Vec<f32> {
        let sr = 44100.0f32;
        let mut out = vec![0.0f32; frames];
        for (i, v) in out.iter_mut().enumerate() {
            let t = i as f32 / sr;
            *v = components
                .iter()
                .map(|(f, a)| a * (2.0 * core::f32::consts::PI * f * t).sin())
                .sum();
        }
        out
    }

    /// 连续 feed 多帧直至检出（平滑滞回需要）。
    fn feed_until_event(
        engine: &TunarEngine,
        pcm: &[f32],
        max_frames: usize,
    ) -> Option<TunarEvent> {
        let mut last = None;
        for _ in 0..max_frames {
            last = engine.feed(pcm.to_vec());
        }
        last
    }

    #[test]
    fn analyze_chord_c_major_and_single_tone() {
        let engine = TunarEngine::new(default_config());
        // C 大三和弦：多喂几帧让平滑器稳定
        let chord_pcm = synth(&[(261.63, 0.5), (329.63, 0.5), (392.0, 0.5)], 2048);
        let mut frame = engine.analyze(chord_pcm.clone());
        for _ in 0..4 {
            frame = engine.analyze(chord_pcm.clone());
        }
        assert_eq!(frame.spectrum_db.len(), 64);
        assert!(frame.tuner.is_some(), "三音合成应有检出");
        let chord = frame.chord.as_deref().unwrap_or("(none)");
        assert!(
            chord.starts_with('C') && chord.contains("maj"),
            "C+E+G 应识别为 C maj 类: {chord}"
        );
        // 单音 → 无和弦
        let single = synth(&[(440.0, 0.8)], 2048);
        let mut frame1 = engine.analyze(single.clone());
        for _ in 0..4 {
            frame1 = engine.analyze(single.clone());
        }
        assert_eq!(frame1.chord, None, "单音不应有和弦: {:?}", frame1.chord);
    }

    #[test]
    fn analyze_partials_harmonics() {
        let engine = TunarEngine::new(default_config());
        let pcm = synth(
            &[(220.0, 1.0), (440.0, 0.6), (660.0, 0.4), (880.0, 0.25)],
            2048,
        );
        let mut frame = engine.analyze(pcm.clone());
        for _ in 0..4 {
            frame = engine.analyze(pcm.clone());
        }
        let idxs: Vec<u8> = frame.partials.iter().map(|p| p.harmonic_index).collect();
        assert!(idxs.contains(&2), "缺 H2: {idxs:?}");
        assert!(idxs.contains(&3), "缺 H3: {idxs:?}");
        assert!(idxs.contains(&4), "缺 H4: {idxs:?}");
    }

    #[test]
    fn analyze_returns_wide_spectrum_waveform_and_monotonic_position() {
        let mut config = default_config();
        config.frame_hop_samples = 800;
        let engine = TunarEngine::new(config);
        let pcm = synth(&[(440.0, 0.45), (10_000.0, 0.35)], 2048);

        let first = engine.analyze(pcm.clone());
        let second = engine.analyze(pcm);

        assert_eq!(first.spectrum_db.len(), 64);
        assert_eq!(first.wide_spectrum_db.len(), 128);
        assert_eq!(first.waveform_min.len(), 256);
        assert_eq!(first.waveform_max.len(), 256);
        assert_eq!(first.sample_rate_hz, 44_100.0);
        assert_eq!(first.wide_spectrum_max_hz, 20_000.0);
        assert_eq!(first.sample_position, 800);
        assert_eq!(second.sample_position, 1_600);
        assert!(
            first
                .waveform_min
                .iter()
                .chain(first.waveform_max.iter())
                .all(|value| value.is_finite())
        );
        assert!(
            first
                .waveform_min
                .iter()
                .zip(first.waveform_max.iter())
                .all(|(min, max)| min <= max)
        );

        let ratio = first.wide_spectrum_max_hz / 20.0;
        let expected_bin = ((10_000.0_f64 / 20.0).ln() / ratio.ln() * 128.0)
            .floor()
            .clamp(0.0, 127.0) as usize;
        let peak_db = first.wide_spectrum_db[expected_bin];
        let neighborhood_floor = first.wide_spectrum_db
            [expected_bin.saturating_sub(3)..=(expected_bin + 3).min(127)]
            .iter()
            .copied()
            .filter(|value| *value < peak_db)
            .fold(spectrum::DB_FLOOR, f32::max);
        assert!(
            peak_db > -20.0 && peak_db > neighborhood_floor,
            "10kHz 应形成可辨峰: bin={expected_bin}, db={peak_db}, neighbor={neighborhood_floor}"
        );
    }

    #[test]
    fn analyze_clamps_wide_spectrum_to_nyquist_and_sanitizes_waveform() {
        let mut config = default_config();
        config.sample_rate = 32_000.0;
        config.frame_hop_samples = 800;
        let engine = TunarEngine::new(config);
        let mut pcm = vec![0.0f32; 2048];
        pcm[0] = f32::NAN;
        pcm[1] = f32::INFINITY;
        pcm[2] = f32::NEG_INFINITY;

        let frame = engine.analyze(pcm);

        assert_eq!(frame.wide_spectrum_max_hz, 16_000.0);
        assert!(frame.wide_spectrum_db.iter().all(|value| value.is_finite()));
        assert!(
            frame
                .waveform_min
                .iter()
                .chain(frame.waveform_max.iter())
                .all(|value| value.is_finite())
        );
    }

    #[test]
    fn engine_set_temperament() {
        let engine = TunarEngine::new(default_config());
        // 19-TET：A4 上方第 7 步频率
        let f = 440.0 * 2.0_f64.powf(7.0 / 19.0);
        engine.set_temperament(19);
        let pcm = synth(&[(f as f32, 0.8)], 2048);
        let ev = feed_until_event(&engine, &pcm, 8).expect("应检出");
        assert_eq!(ev.temperament, 19);
        assert_eq!(ev.temperament_step, 7);
        assert!(
            ev.temperament_cents.abs() < 5.0,
            "cents={}",
            ev.temperament_cents
        );
        // 非法值忽略
        engine.set_temperament(5);
        let ev2 = feed_until_event(&engine, &pcm, 2).unwrap();
        assert_eq!(ev2.temperament, 19, "非法值不应改变律制");
    }

    #[test]
    fn analyze_performance_under_3x_feed() {
        use std::time::Instant;
        let engine = TunarEngine::new(default_config());
        let pcm = synth(&[(440.0, 0.8), (880.0, 0.4)], 2048);
        // 预热
        for _ in 0..3 {
            engine.feed(pcm.clone());
            engine.analyze(pcm.clone());
        }
        let t_feed = Instant::now();
        for _ in 0..30 {
            engine.feed(pcm.clone());
        }
        let feed_us = t_feed.elapsed().as_micros() as f64;
        let t_analyze = Instant::now();
        for _ in 0..30 {
            engine.analyze(pcm.clone());
        }
        let analyze_us = t_analyze.elapsed().as_micros() as f64;
        let ratio = analyze_us / feed_us;
        println!("analyze/feed 耗时比 = {ratio:.2}（feed {feed_us}µs, analyze {analyze_us}µs）");
        assert!(ratio < 3.0, "analyze/feed 耗时比 {ratio:.2} ≥ 3")
    }

    #[test]
    fn metronome_api_roundtrip() {
        let m = Metronome::new(MetronomeConfig {
            sample_rate: 44100.0,
            bpm: 120.0,
            beats_per_bar: 4,
            beat_unit: 4,
            accents: vec![
                TickAccent::Accent,
                TickAccent::Normal,
                TickAccent::Normal,
                TickAccent::Normal,
            ],
        });
        assert!(!m.is_running());
        m.start(0);
        assert!(m.is_running());
        let frame = m.render(44100);
        assert_eq!(frame.samples.len(), 44100);
        assert_eq!(frame.ticks.len(), 2);
        assert_eq!(frame.ticks[0].sample_offset, 0);
        assert_eq!(frame.ticks[0].accent, TickAccent::Accent);
        assert_eq!(frame.ticks[1].sample_offset, 22050);
        assert_eq!(frame.ticks[1].accent, TickAccent::Normal);
        // PCM 中确实混入了 click
        assert!(frame.samples.iter().any(|&v| v != 0.0));
        m.set_bpm(60.0);
        m.set_time_signature(3, 4);
        m.set_accents(vec![
            TickAccent::Accent,
            TickAccent::Muted,
            TickAccent::Normal,
        ]);
        m.set_click_samples(vec![0.1, 0.2], vec![0.05]);
        m.tap(0);
        let bpm = m.tap(26460); // 间隔 26460 采样 = 100BPM
        assert!((bpm - 100.0).abs() < 0.5);
        m.stop();
        assert!(!m.is_running());
        let frame = m.render(1024);
        assert_eq!(frame.ticks.len(), 0);
        assert!(frame.samples.iter().all(|&v| v == 0.0));
    }
}
