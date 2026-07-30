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

/// 指法表中的一个音。
#[derive(Debug, Clone, uniffi::Record)]
pub struct FingeringNote {
    /// 指法/孔位名（如 "筒音"、"开第一二四孔"）。
    pub label: String,
    /// 音名。
    pub note_name: String,
    /// MIDI 音高（随 A4 换算/唱名重算的基准）。
    pub midi: i32,
    /// 目标频率（Hz，按 A4=440 换算）。
    pub freq_hz: f64,
    /// 唱名（按该调性的首调简谱）。
    pub solfege: String,
}

/// 一张指法表（调性 + 筒音唱名）。
#[derive(Debug, Clone, uniffi::Record)]
pub struct FingeringChart {
    /// chart id（如 "d_qudi_sou5"）。
    pub id: String,
    /// 显示名（如 "D调曲笛 · 筒音作5"）。
    pub display_name: String,
    /// 音阶（升序，约两个八度）。
    pub notes: Vec<FingeringNote>,
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
pub struct TunerConfig {
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
pub struct TunerEvent {
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
    pub tuner: Option<TunerEvent>,
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

/// 列出某管乐器的全部指法表（频率按 A4=440，唱名按各调性首调简谱）。
#[uniffi::export]
pub fn list_fingering_charts(instrument_id: String) -> Vec<FingeringChart> {
    let Some(charts) = fingering::wind_charts(&instrument_id) else {
        return Vec::new();
    };
    let mut name_buf = [0u8; 5];
    let mut sf_buf = [0u8; 16];
    charts
        .iter()
        .map(|c| {
            let tonic_pc = fingering::tonic_pc_of(c.fundamental_midi, c.tongyin);
            FingeringChart {
                id: c.id.to_string(),
                display_name: c.display_name.to_string(),
                notes: c
                    .offsets
                    .iter()
                    .zip(c.labels.iter())
                    .map(|(&off, &label)| {
                        let midi = c.fundamental_midi + off;
                        FingeringNote {
                            label: label.to_string(),
                            note_name: note::midi_to_name(midi, &mut name_buf)
                                .unwrap_or("")
                                .to_string(),
                            midi,
                            freq_hz: note::midi_to_freq(midi as f64, note::A4_DEFAULT),
                            solfege: solfege::solfege_of(
                                SolfegeSystem::Numbered,
                                note::midi_to_pc(midi),
                                tonic_pc,
                                ModeKind::Gong,
                                &mut sf_buf,
                            )
                            .to_string(),
                        }
                    })
                    .collect(),
            }
        })
        .collect()
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

// ============================ TunerEngine ============================

/// TunerEngine 内核（无锁，单线程使用）。
struct TunerCore {
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

impl TunerCore {
    fn analyze_frame(&mut self, pcm: &[f32]) -> (Option<TunerEvent>, signal::SignalOutput, f32) {
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
        let event = TunerEvent {
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
pub struct TunerEngine {
    core: Mutex<TunerCore>,
}

#[uniffi::export]
impl TunerEngine {
    /// 构造。`config.a4_hz` 越界时收敛到 415–466。
    #[uniffi::constructor]
    pub fn new(config: TunerConfig) -> Arc<Self> {
        let a4 = config.a4_hz.clamp(note::A4_MIN, note::A4_MAX);
        let mut yin = pitch::Yin::new(config.sample_rate as f32);
        yin.set_noise_gate(config.noise_gate_dbfs - signal::GATE_HYSTERESIS_DB);
        Arc::new(Self {
            core: Mutex::new(TunerCore {
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
    pub fn feed(&self, pcm: Vec<f32>) -> Option<TunerEvent> {
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

    fn default_config() -> TunerConfig {
        TunerConfig {
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
    fn list_fingering_charts_zhudi() {
        let charts = list_fingering_charts("zhudi".to_string());
        assert_eq!(charts.len(), 15);
        let d5 = charts.iter().find(|c| c.id == "d_qudi_sou5").unwrap();
        assert_eq!(d5.display_name, "D调曲笛 · 筒音作5");
        assert_eq!(d5.notes.len(), 15);
        // 筒音 A2 = 110Hz，唱名作 5
        assert_eq!(d5.notes[0].note_name, "A2");
        assert!((d5.notes[0].freq_hz - 110.0).abs() < 0.1);
        assert_eq!(d5.notes[0].solfege, "5");
        assert_eq!(d5.notes[0].label, "筒音");
        // 升序
        for w in d5.notes.windows(2) {
            assert!(w[0].freq_hz < w[1].freq_hz);
        }
        assert!(list_fingering_charts("suona".to_string()).is_empty());
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
        // StringSpec/FingeringNote 的 midi 与 freq_hz（A4=440）、note_name 一致
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
        for c in list_fingering_charts("zhudi".to_string()) {
            for n in c.notes {
                let f = crate::note::midi_to_freq(n.midi as f64, 440.0);
                assert!((f - n.freq_hz).abs() < 1e-9);
            }
        }
    }

    #[test]
    fn tuner_engine_feed_synth() {
        // 合成 440Hz 正弦（多帧以通过平滑与滞回）
        let sr = 44100.0f32;
        let engine = TunerEngine::new(default_config());
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
        let engine = TunerEngine::new(default_config());
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
        engine: &TunerEngine,
        pcm: &[f32],
        max_frames: usize,
    ) -> Option<TunerEvent> {
        let mut last = None;
        for _ in 0..max_frames {
            last = engine.feed(pcm.to_vec());
        }
        last
    }

    #[test]
    fn analyze_chord_c_major_and_single_tone() {
        let engine = TunerEngine::new(default_config());
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
        let engine = TunerEngine::new(default_config());
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
        let engine = TunerEngine::new(config);
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
        let engine = TunerEngine::new(config);
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
        let engine = TunerEngine::new(default_config());
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
        let engine = TunerEngine::new(default_config());
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
