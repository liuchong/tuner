//! 工作线程上的练音会话：目标、有限历史、游标、快照与时间加权统计。
use crate::{
    api::{AnalysisFrame, TunarEvent},
    note,
    signal::SignalState,
};
use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, MutexGuard};

/// 单次测量文件与载荷的硬上限。
pub const MAX_FILE_BYTES: usize = 32 * 1024 * 1024;
/// 最多保存的完整测量点数。
pub const MAX_POINTS: usize = 6000;
// 覆盖6000个点/段的分隔符、最大配置字符串转义及文件头，不在临界容量留下不可保存记录。
const FILE_OVERHEAD_RESERVE: usize = 24 * 1024;
/// 记录与显示的错误；错误不改变现有已保存数据。
#[derive(Debug, Clone, uniffi::Error)]
pub enum PracticeError {
    /// 输入配置、测量帧或引用无效。
    InvalidInput,
    /// 文件结构或测量内容无效。
    InvalidFile,
    /// 文件版本不受支持。
    UnsupportedVersion,
    /// 容量上限。
    Capacity,
    /// 没有可用数据。
    NoData,
    /// 比较频谱的采集条件或坐标不兼容。
    IncompatibleGrid,
}
impl std::fmt::Display for PracticeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::InvalidInput => "无效的测量输入或选择",
            Self::InvalidFile => "测量文件无效，当前会话已保留",
            Self::UnsupportedVersion => "不支持此测量文件版本",
            Self::Capacity => "已达到测量容量上限",
            Self::NoData => "暂无可用测量数据",
            Self::IncompatibleGrid => "采样率或频率范围不同，不能直接叠加",
        })
    }
}
impl std::error::Error for PracticeError {}

/// 不可变保存到配置段的目标与演奏上下文。
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct PracticeConfig {
    /// A4基准，415–466Hz。
    pub a4_hz: f64,
    /// 平均律等分数。
    pub temperament: u8,
    /// 锁定参考音步序。
    pub target_step: Option<i32>,
    /// 锁定乐器音级，频率随A4变化。
    pub target_midi: Option<i32>,
    /// 锁定绝对Hz。
    pub target_hz: Option<f64>,
    /// 目标显示名。
    pub target_name: String,
    /// 乐器身份。
    pub instrument_id: String,
    /// 型号身份。
    pub variant_id: String,
    /// 指法身份，仅用于示范，不推断实际按法。
    pub fingering_id: Option<i32>,
    /// 准音容差。
    pub tolerance_cents: f64,
}
impl Default for PracticeConfig {
    fn default() -> Self {
        Self {
            a4_hz: 440.,
            temperament: 12,
            target_step: None,
            target_midi: None,
            target_hz: None,
            target_name: String::new(),
            instrument_id: String::new(),
            variant_id: String::new(),
            fingering_id: None,
            tolerance_cents: 5.,
        }
    }
}
impl PracticeConfig {
    pub(crate) fn validate(&self) -> bool {
        self.a4_hz.is_finite()
            && (415. ..=466.).contains(&self.a4_hz)
            && [12, 19, 24, 31].contains(&self.temperament)
            && [2., 5., 10., 20.].contains(&self.tolerance_cents)
            && [
                self.target_step.is_some(),
                self.target_midi.is_some(),
                self.target_hz.is_some(),
            ]
            .iter()
            .filter(|v| **v)
            .count()
                <= 1
            && self.target_step.is_none_or(|v| (-500..=500).contains(&v))
            && self.target_midi.is_none_or(|v| (0..=127).contains(&v))
            && self
                .target_hz
                .is_none_or(|v| v.is_finite() && (20. ..=20000.).contains(&v))
            && [&self.target_name, &self.instrument_id, &self.variant_id]
                .iter()
                .all(|v| v.len() <= 256)
    }
    pub(crate) fn target(&self, event: Option<&TunarEvent>) -> (Option<f64>, String) {
        let explicit = self
            .target_hz
            .or_else(|| {
                self.target_midi
                    .map(|m| self.a4_hz * 2_f64.powf(f64::from(m - 69) / 12.))
            })
            .or_else(|| {
                self.target_step
                    .map(|s| self.a4_hz * 2_f64.powf(f64::from(s) / f64::from(self.temperament)))
            });
        let hz = explicit.or_else(|| {
            event
                .and_then(|e| note::temperament_step_cents(e.freq_hz, self.a4_hz, self.temperament))
                .map(|(s, _)| self.a4_hz * 2_f64.powf(f64::from(s) / f64::from(self.temperament)))
        });
        let label = if explicit.is_some() && !self.target_name.is_empty() {
            self.target_name.clone()
        } else {
            hz.and_then(|f| note::analyze(f, self.a4_hz))
                .map(|n| n.name().to_string())
                .unwrap_or_default()
        };
        (hz, label)
    }
}
/// 记录状态与显示游标相互独立。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PracticeRecordingState {
    /// 尚未记录。
    Idle,
    /// 正在追加。
    Recording,
    /// 暂停追加。
    Paused,
    /// 完成本次记录。
    Complete,
}
/// 同一分析帧的完整测量快照。
#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct PracticePoint {
    /// 会话内递增身份。
    pub id: u64,
    /// 连续采集段。
    pub segment_id: u32,
    /// 配置段引用。
    pub config_id: u32,
    /// 会话音频时间。
    pub time_seconds: f64,
    /// 原始分析数据，不含PCM。
    pub frame: AnalysisFrame,
    /// 该点的目标频率。
    pub target_hz: Option<f64>,
    /// 相对目标的真实音分，不折叠。
    pub cents: Option<f64>,
    /// 当时目标音名。
    pub target_name: String,
    /// 输出参考音标记。
    pub reference_active: bool,
    /// 可用于演奏统计的真实追踪点。
    pub valid: bool,
}
/// 仅传输给时间轴的标量。
#[derive(Debug, Clone, uniffi::Record)]
pub struct PracticeTracePoint {
    /// 原测量身份。
    pub id: u64,
    /// 连续段。
    pub segment_id: u32,
    /// 配置段。
    pub config_id: u32,
    /// 秒。
    pub time_seconds: f64,
    /// 原精度音分。
    pub cents: Option<f64>,
    /// 输入dBFS。
    pub input_dbfs: f32,
    /// 有效演奏点。
    pub valid: bool,
}
impl From<&PracticePoint> for PracticeTracePoint {
    fn from(p: &PracticePoint) -> Self {
        Self {
            id: p.id,
            segment_id: p.segment_id,
            config_id: p.config_id,
            time_seconds: p.time_seconds,
            cents: p.cents,
            input_dbfs: p.frame.input_level_dbfs,
            valid: p.valid,
        }
    }
}
/// 配置与引擎代次组成的不可变采集段。
#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct PracticeSegment {
    /// 段身份。
    pub id: u32,
    /// 配置身份。
    pub config_id: u32,
    /// 采集重启代次。
    pub generation: u64,
    /// 当时配置。
    pub config: PracticeConfig,
}
/// 独立命名比较样本。
#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct PracticeSnapshot {
    /// 样本身份。
    pub id: u32,
    /// 名称。
    pub name: String,
    /// 独立原始点。
    pub point: PracticePoint,
    /// 独立配置。
    pub config: PracticeConfig,
}
/// 用于原生界面的小型状态。
#[derive(Debug, Clone, uniffi::Record)]
pub struct PracticeStatus {
    /// 记录状态。
    pub state: PracticeRecordingState,
    /// 记录点数。
    pub count: u32,
    /// 回看身份；空为实时。
    pub review_id: Option<u64>,
    /// 暂停或失败原因。
    pub reason: String,
    /// 已保存JSON载荷预算。
    pub payload_bytes: u64,
    /// 命名样本数。
    pub snapshot_count: u32,
    /// 当前比较基准。
    pub baseline_id: Option<u32>,
    /// 保存选区的起点。
    pub selection_start_id: Option<u64>,
    /// 保存选区的终点。
    pub selection_end_id: Option<u64>,
}
/// 单一配置段的时间加权统计。
#[derive(Debug, Clone, uniffi::Record)]
pub struct PracticeSummary {
    /// 连续段。
    pub segment_id: u32,
    /// 选中开始秒。
    pub start_time: f64,
    /// 选中结束秒。
    pub end_time: f64,
    /// 有效覆盖秒数。
    pub valid_seconds: f64,
    /// 有效覆盖率。
    pub coverage: f64,
    /// 时间加权中位数。
    pub median_cents: Option<f64>,
    /// 时间加权P10。
    pub low_cents: Option<f64>,
    /// 时间加权P90。
    pub high_cents: Option<f64>,
    /// 有效时间内准音率。
    pub accuracy: Option<f64>,
    /// 不足1秒或5点。
    pub insufficient: bool,
}
/// 可叠加的频谱；真实测量桶，无额外FFT。
#[derive(Debug, Clone, uniffi::Record)]
pub struct PracticeComparison {
    /// 当前曲线。
    pub current: Vec<f32>,
    /// 基准曲线。
    pub reference: Vec<f32>,
    /// 是否相对峰值归一化。
    pub normalized: bool,
}

pub(crate) struct SessionCore {
    pub(crate) config: PracticeConfig,
    pub(crate) state: PracticeRecordingState,
    pub(crate) points: Vec<PracticePoint>,
    pub(crate) segments: Vec<PracticeSegment>,
    pub(crate) snapshots: Vec<PracticeSnapshot>,
    pub(crate) baseline_id: Option<u32>,
    pub(crate) review_id: Option<u64>,
    pub(crate) selection: Option<(u64, u64)>,
    latest: Option<PracticePoint>,
    latest_config: Option<PracticeConfig>,
    live: VecDeque<PracticeTracePoint>,
    last_capture: Option<(u64, u64, f64, bool)>,
    last_bucket: Option<u64>,
    segment_id: u32,
    break_pending: bool,
    next_id: u64,
    payload_bytes: usize,
    reason: String,
    max_points: usize,
    max_bytes: usize,
    max_seconds: f64,
    record_origin: Option<f64>,
}
impl Default for SessionCore {
    fn default() -> Self {
        Self {
            config: PracticeConfig::default(),
            state: PracticeRecordingState::Idle,
            points: Vec::with_capacity(MAX_POINTS),
            segments: Vec::new(),
            snapshots: Vec::new(),
            baseline_id: None,
            review_id: None,
            selection: None,
            latest: None,
            latest_config: None,
            live: VecDeque::with_capacity(130),
            last_capture: None,
            last_bucket: None,
            segment_id: 0,
            break_pending: true,
            next_id: 1,
            payload_bytes: 0,
            reason: String::new(),
            max_points: MAX_POINTS,
            max_bytes: MAX_FILE_BYTES,
            max_seconds: 600.,
            record_origin: None,
        }
    }
}
impl SessionCore {
    fn selected(&self) -> Option<&PracticePoint> {
        self.review_id
            .and_then(|id| self.points.iter().find(|p| p.id == id))
            .or(self.latest.as_ref())
    }
    fn pause(&mut self, reason: &str) {
        if self.state == PracticeRecordingState::Recording {
            self.state = PracticeRecordingState::Paused;
        }
        self.break_pending = true;
        self.last_bucket = None;
        self.reason = reason.into();
    }
    fn snapshot_config(&self, p: &PracticePoint) -> PracticeConfig {
        self.segments
            .iter()
            .find(|s| s.id == p.segment_id)
            .map(|s| s.config.clone())
            .or_else(|| self.latest_config.clone())
            .unwrap_or_else(|| self.config.clone())
    }
    fn point(
        &self,
        frame: AnalysisFrame,
        time_seconds: f64,
        reference_active: bool,
    ) -> PracticePoint {
        let (target_hz, target_name) = self.config.target(frame.tuner.as_ref());
        let cents = frame
            .tuner
            .as_ref()
            .and_then(|e| target_hz.map(|t| 1200. * (e.freq_hz / t).log2()));
        let valid = frame.signal_state == SignalState::Tracking
            && !frame.is_held
            && !reference_active
            && cents.is_some();
        PracticePoint {
            id: 0,
            segment_id: self.segment_id,
            config_id: self.segment_id,
            time_seconds,
            frame,
            target_hz,
            cents,
            target_name,
            reference_active,
            valid,
        }
    }
}

/// 通过UniFFI共享的测量所有者；仅在分析或工作队列上追加、统计和编码。
#[derive(uniffi::Object)]
pub struct PracticeSession {
    pub(crate) core: Mutex<SessionCore>,
}
impl PracticeSession {
    fn inner(&self) -> MutexGuard<'_, SessionCore> {
        self.core.lock().unwrap_or_else(|e| e.into_inner())
    }
}
#[uniffi::export]
impl PracticeSession {
    /// 创建独立有界会话。
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            core: Mutex::new(SessionCore::default()),
        })
    }
    /// 当前配置副本。
    pub fn config(&self) -> PracticeConfig {
        self.inner().config.clone()
    }
    /// 原子设置目标和容差；历史不可变。
    pub fn configure(&self, config: PracticeConfig) -> Result<(), PracticeError> {
        if !config.validate() {
            return Err(PracticeError::InvalidInput);
        }
        let mut c = self.inner();
        if c.config != config {
            c.config = config;
            c.break_pending = true;
            c.last_bucket = None;
        }
        Ok(())
    }
    /// 锁定当前平均律参考步序。
    pub fn lock_reference(&self, step: i32) -> Result<(), PracticeError> {
        let mut c = self.config();
        c.target_step = Some(step);
        c.target_midi = None;
        c.target_hz = None;
        c.target_name = String::new();
        c.fingering_id = None;
        self.configure(c)
    }
    /// 锁定乐器音；频率换算统一在core。
    pub fn lock_instrument(
        &self,
        midi: i32,
        instrument_id: String,
        variant_id: String,
        fingering_id: Option<i32>,
    ) -> Result<(), PracticeError> {
        let mut c = self.config();
        c.target_step = None;
        c.target_hz = None;
        c.target_midi = Some(midi);
        c.target_name = String::new();
        c.instrument_id = instrument_id;
        c.variant_id = variant_id;
        c.fingering_id = fingering_id;
        self.configure(c)
    }
    /// 当前显示点的原始配置。
    pub fn display_config(&self) -> PracticeConfig {
        let c = self.inner();
        c.selected()
            .map(|p| c.snapshot_config(p))
            .unwrap_or_else(|| c.config.clone())
    }
    /// 无信号时也可取得锁定目标的Hz。
    pub fn target_frequency(&self) -> Option<f64> {
        self.inner().config.target(None).0
    }
    /// 取消目标锁定。
    pub fn unlock_target(&self) {
        let mut c = self.config();
        c.target_step = None;
        c.target_midi = None;
        c.target_hz = None;
        c.target_name.clear();
        c.fingering_id = None;
        let _ = self.configure(c);
    }
    /// 开始或继续记录；完成后需先清空开始下一次。
    pub fn start(&self) {
        let mut c = self.inner();
        if c.state == PracticeRecordingState::Complete {
            return;
        }
        c.state = PracticeRecordingState::Recording;
        c.review_id = None;
        c.break_pending = true;
        c.last_bucket = None;
        c.reason.clear();
    }
    /// 仅暂停记录，原生麦克风由原生层独立管理。
    pub fn pause(&self) {
        self.inner().pause("记录已暂停");
    }
    /// 完成本次记录并保留所有点。
    pub fn finish(&self) {
        let mut c = self.inner();
        c.pause("本次测量已完成");
        c.state = PracticeRecordingState::Complete;
    }
    /// 清空会话，保留独立命名快照。
    pub fn clear(&self) {
        let mut c = self.inner();
        let config = c.config.clone();
        let snapshots = std::mem::take(&mut c.snapshots);
        let baseline = c.baseline_id;
        *c = SessionCore::default();
        c.config = config;
        c.snapshots = snapshots;
        c.baseline_id = baseline;
    }
    /// 输入一次真实分析结果；不重新采集、不重新FFT。
    pub fn observe(
        &self,
        frame: AnalysisFrame,
        generation: u64,
        a4_hz: f64,
        temperament: u8,
        reference_active: bool,
    ) -> Result<PracticePoint, PracticeError> {
        if !valid_frame(&frame) {
            return Err(PracticeError::InvalidInput);
        }
        let mut c = self.inner();
        if c.last_capture
            .is_some_and(|(old_gen, _, _, _)| generation < old_gen)
        {
            return Err(PracticeError::InvalidInput);
        }
        let mut config = c.config.clone();
        config.a4_hz = a4_hz;
        config.temperament = temperament;
        if !config.validate() {
            return Err(PracticeError::InvalidInput);
        }
        if config != c.config {
            c.config = config;
            c.break_pending = true;
            c.last_bucket = None;
        }
        let raw_time = frame.sample_position as f64 / frame.sample_rate_hz;
        let mut time = c.latest.as_ref().map_or(0., |p| p.time_seconds);
        if let Some((old_gen, old_pos, old_rate, old_reference)) = c.last_capture {
            if generation < old_gen {
                return Err(PracticeError::InvalidInput);
            }
            if old_gen != generation
                || old_rate != frame.sample_rate_hz
                || frame.sample_position < old_pos
            {
                c.pause("采集已变化，请确认后继续记录");
                time += 0.1;
            } else {
                time += (frame.sample_position - old_pos) as f64 / frame.sample_rate_hz;
            }
            if old_reference != reference_active {
                c.break_pending = true;
                c.last_bucket = None;
            }
        }
        if c.break_pending {
            c.segment_id = c.segment_id.saturating_add(1);
            c.break_pending = false;
        }
        c.last_capture = Some((
            generation,
            frame.sample_position,
            frame.sample_rate_hz,
            reference_active,
        ));
        let mut point = c.point(frame, time, reference_active);
        let bucket = (raw_time * 10.).floor() as u64;
        if c.state == PracticeRecordingState::Recording && c.last_bucket != Some(bucket) {
            let origin = *c.record_origin.get_or_insert(time);
            if c.points.len() >= c.max_points || time - origin > c.max_seconds {
                c.pause("已达到测量点数或时长上限");
            } else {
                point.id = c.next_id;
                let segment = PracticeSegment {
                    id: c.segment_id,
                    config_id: c.segment_id,
                    generation,
                    config: c.config.clone(),
                };
                let new_segment = !c.segments.iter().any(|s| s.id == c.segment_id);
                let bytes = serde_json::to_vec(&point)
                    .map_err(|_| PracticeError::InvalidInput)?
                    .len()
                    + if new_segment {
                        serde_json::to_vec(&segment)
                            .map_err(|_| PracticeError::InvalidInput)?
                            .len()
                    } else {
                        0
                    };
                let snapshot_bytes = serde_json::to_vec(&c.snapshots)
                    .map_err(|_| PracticeError::InvalidInput)?
                    .len();
                if c.payload_bytes + bytes + snapshot_bytes + FILE_OVERHEAD_RESERVE > c.max_bytes {
                    point.id = 0;
                    c.pause("已达到测量文件载荷上限");
                } else {
                    c.next_id = c.next_id.saturating_add(1);
                    c.payload_bytes += bytes;
                    c.last_bucket = Some(bucket);
                    if new_segment {
                        c.segments.push(segment);
                    }
                    c.points.push(point.clone());
                }
            }
        }
        let trace = PracticeTracePoint::from(&point);
        c.live.push_back(trace);
        while c.live.front().is_some_and(|p| p.time_seconds < time - 12.) || c.live.len() > 512 {
            c.live.pop_front();
        }
        c.latest_config = Some(c.config.clone());
        c.latest = Some(point.clone());
        Ok(point)
    }
    /// 小型状态，适于每帧发布。
    pub fn status(&self) -> PracticeStatus {
        let c = self.inner();
        PracticeStatus {
            state: c.state,
            count: c.points.len() as u32,
            review_id: c.review_id,
            reason: c.reason.clone(),
            payload_bytes: c.payload_bytes as u64,
            snapshot_count: c.snapshots.len() as u32,
            baseline_id: c.baseline_id,
            selection_start_id: c.selection.map(|v| v.0),
            selection_end_id: c.selection.map(|v| v.1),
        }
    }
    /// 唯一显示帧；历史游标优先。
    pub fn display_point(&self) -> Option<PracticePoint> {
        self.inner().selected().cloned()
    }
    /// 选择历史点，自动暂停记录。
    pub fn select_point(&self, id: u64) -> Result<PracticePoint, PracticeError> {
        let mut c = self.inner();
        let p = c
            .points
            .iter()
            .find(|p| p.id == id)
            .cloned()
            .ok_or(PracticeError::NoData)?;
        c.pause("回看中，记录已暂停");
        c.review_id = Some(id);
        Ok(p)
    }
    /// 返回实时但不恢复记录。
    pub fn return_to_live(&self) {
        self.inner().review_id = None;
    }
    /// 有界完整点分页。
    pub fn read_page(&self, offset: u32, limit: u32) -> Vec<PracticePoint> {
        self.inner()
            .points
            .iter()
            .skip(offset as usize)
            .take(limit.min(64) as usize)
            .cloned()
            .collect()
    }
    /// 有界绘图标量分页，不拷贝频谱与包络。
    pub fn trace_page(&self, offset: u32, limit: u32) -> Vec<PracticeTracePoint> {
        self.inner()
            .points
            .iter()
            .skip(offset as usize)
            .take(limit.min(6000) as usize)
            .map(PracticeTracePoint::from)
            .collect()
    }
    /// 实时短轨迹，历史点独立保存。
    pub fn live_trace(&self) -> Vec<PracticeTracePoint> {
        self.inner().live.iter().cloned().collect()
    }
    /// 保存当前显示帧为独立命名快照。
    pub fn add_snapshot(&self, name: String) -> Result<PracticeSnapshot, PracticeError> {
        let mut c = self.inner();
        if c.snapshots.len() >= 8 {
            return Err(PracticeError::Capacity);
        }
        if name.trim().is_empty() || name.chars().count() > 80 {
            return Err(PracticeError::InvalidInput);
        }
        let point = c.selected().cloned().ok_or(PracticeError::NoData)?;
        if point.frame.signal_state != SignalState::Tracking || point.frame.is_held {
            return Err(PracticeError::NoData);
        }
        let id = c
            .snapshots
            .iter()
            .map(|s| s.id)
            .max()
            .unwrap_or(0)
            .saturating_add(1);
        let snapshot = PracticeSnapshot {
            id,
            name,
            config: c.snapshot_config(&point),
            point,
        };
        let bytes = serde_json::to_vec(&snapshot)
            .map_err(|_| PracticeError::InvalidInput)?
            .len();
        let existing = serde_json::to_vec(&c.snapshots)
            .map_err(|_| PracticeError::InvalidInput)?
            .len();
        if c.payload_bytes + existing + bytes + FILE_OVERHEAD_RESERVE > c.max_bytes {
            return Err(PracticeError::Capacity);
        }
        c.snapshots.push(snapshot.clone());
        Ok(snapshot)
    }
    /// 独立快照列表。
    pub fn snapshots(&self) -> Vec<PracticeSnapshot> {
        self.inner().snapshots.clone()
    }
    /// 删除指定快照；其他数据保持。
    pub fn remove_snapshot(&self, id: u32) {
        let mut c = self.inner();
        c.snapshots.retain(|s| s.id != id);
        if c.baseline_id == Some(id) {
            c.baseline_id = None;
        }
    }
    /// 设置或隐藏比较基准。
    pub fn set_baseline(&self, id: Option<u32>) -> Result<(), PracticeError> {
        let mut c = self.inner();
        if id.is_some_and(|id| !c.snapshots.iter().any(|s| s.id == id)) {
            return Err(PracticeError::NoData);
        }
        c.baseline_id = id;
        Ok(())
    }
    /// 返回兼容频率网格的两条曲线。
    pub fn comparison(
        &self,
        normalized: bool,
        wide: bool,
    ) -> Result<PracticeComparison, PracticeError> {
        let c = self.inner();
        let current = c.selected().ok_or(PracticeError::NoData)?;
        let reference = c
            .snapshots
            .iter()
            .find(|s| Some(s.id) == c.baseline_id)
            .ok_or(PracticeError::NoData)?;
        if current.frame.sample_rate_hz != reference.point.frame.sample_rate_hz
            || (wide
                && current.frame.wide_spectrum_max_hz != reference.point.frame.wide_spectrum_max_hz)
        {
            return Err(PracticeError::IncompatibleGrid);
        }
        let extract = |p: &PracticePoint| {
            let mut v = if wide {
                p.frame.wide_spectrum_db.clone()
            } else {
                p.frame.spectrum_db.clone()
            };
            if normalized {
                let peak = v.iter().copied().fold(f32::NEG_INFINITY, f32::max);
                for x in &mut v {
                    *x -= peak;
                }
            }
            v
        };
        Ok(PracticeComparison {
            current: extract(current),
            reference: extract(&reference.point),
            normalized,
        })
    }
    /// 分连续配置段统计，不跨中断补值。
    pub fn summarize(&self, start_id: u64, end_id: u64) -> Vec<PracticeSummary> {
        let mut c = self.inner();
        let (lo, hi) = (start_id.min(end_id), start_id.max(end_id));
        if !c.points.iter().any(|p| p.id == lo) || !c.points.iter().any(|p| p.id == hi) {
            return Vec::new();
        }
        c.selection = Some((lo, hi));
        let points: Vec<_> = c
            .points
            .iter()
            .filter(|p| p.id >= lo && p.id <= hi)
            .collect();
        let mut result = Vec::new();
        let mut begin = 0;
        while begin < points.len() {
            let segment = points[begin].segment_id;
            let mut end = begin + 1;
            while end < points.len() && points[end].segment_id == segment {
                end += 1;
            }
            let tolerance = c.snapshot_config(points[begin]).tolerance_cents;
            result.push(summarize_points(&points[begin..end], tolerance));
            begin = end;
        }
        result
    }
    /// 编码完整测量文件。
    pub fn export_json(&self) -> Result<String, PracticeError> {
        crate::measurement_file::encode(&self.inner())
    }
    /// 完整校验成功后原子替换当前会话。
    pub fn import_json(&self, contents: String) -> Result<(), PracticeError> {
        let file = crate::measurement_file::decode(&contents)?;
        let mut c = self.inner();
        let config = file
            .current_config
            .clone()
            .or_else(|| file.segments.last().map(|s| s.config.clone()))
            .unwrap_or_default();
        let latest = file.points.last().cloned();
        let next = file.points.last().map_or(1, |p| p.id.saturating_add(1));
        let segment = file.segments.iter().map(|s| s.id).max().unwrap_or(0);
        let bytes = contents.len();
        *c = SessionCore::default();
        c.config = config;
        c.points = file.points;
        c.record_origin = c.points.first().map(|point| point.time_seconds);
        c.segments = file.segments;
        c.snapshots = file.snapshots;
        c.baseline_id = file.baseline_id;
        c.selection = file.selection;
        c.review_id = file.review_id.or_else(|| c.points.first().map(|p| p.id));
        c.latest_config = Some(c.config.clone());
        c.latest = latest;
        c.next_id = next;
        c.segment_id = segment;
        c.payload_bytes = bytes;
        c.state = PracticeRecordingState::Paused;
        c.reason = "已打开测量，记录已暂停".into();
        Ok(())
    }
    /// 导出所选点区间的标量CSV；空值不填0。
    pub fn export_csv(
        &self,
        start_id: Option<u64>,
        end_id: Option<u64>,
    ) -> Result<String, PracticeError> {
        crate::measurement_file::csv(&self.inner(), start_id, end_id)
    }
}

pub(crate) fn valid_frame(f: &AnalysisFrame) -> bool {
    f.sample_rate_hz.is_finite()
        && (8000. ..=192000.).contains(&f.sample_rate_hz)
        && f.wide_spectrum_max_hz.is_finite()
        && f.wide_spectrum_max_hz > 20.
        && f.wide_spectrum_max_hz <= f.sample_rate_hz / 2.
        && f.spectrum_db.len() == 64
        && f.wide_spectrum_db.len() == 128
        && f.waveform_min.len() == 256
        && f.waveform_max.len() == 256
        && f.spectrum_db
            .iter()
            .chain(&f.wide_spectrum_db)
            .all(|v| v.is_finite() && (-160. ..=0.).contains(v))
        && f.waveform_min
            .iter()
            .chain(&f.waveform_max)
            .all(|v| v.is_finite() && (-1. ..=1.).contains(v))
        && f.waveform_min
            .iter()
            .zip(&f.waveform_max)
            .all(|(lo, hi)| lo <= hi)
        && f.input_level_dbfs.is_finite()
        && f.display_strength.is_finite()
        && (0. ..=1.).contains(&f.display_strength)
        && f.partials.len() <= 8
        && f.partials.iter().all(|p| {
            p.freq_hz.is_finite()
                && p.freq_hz > 0.
                && p.magnitude_db.is_finite()
                && p.cents_off.is_finite()
                && p.note_name.len() <= 64
        })
        && f.chord.as_ref().is_none_or(|s| s.len() <= 64)
        && f.tuner.as_ref().is_none_or(|e| {
            e.freq_hz.is_finite()
                && e.freq_hz > 0.
                && e.clarity.is_finite()
                && (0. ..=1.).contains(&e.clarity)
                && e.cents_off.is_finite()
                && e.temperament_cents.is_finite()
                && e.note_name.len() <= 64
                && e.solfege.len() <= 64
        })
}

fn summarize_points(points: &[&PracticePoint], tolerance: f64) -> PracticeSummary {
    let first = points[0];
    let last = points[points.len() - 1];
    let mut spans = Vec::new();
    let mut valid_seconds = 0.;
    let mut in_tune = 0.;
    for pair in points.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        let dt = b.time_seconds - a.time_seconds;
        if !a.valid
            || !b.valid
            || a.reference_active
            || b.reference_active
            || dt <= 0.
            || dt > 0.250001
        {
            continue;
        }
        if let (Some(x), Some(y)) = (a.cents, b.cents) {
            spans.push((x, y, dt));
            valid_seconds += dt;
            let (low, high) = (x.min(y), x.max(y));
            let fraction = if high - low < 1e-12 {
                if low.abs() <= tolerance { 1. } else { 0. }
            } else {
                ((high.min(tolerance) - low.max(-tolerance)) / (high - low)).clamp(0., 1.)
            };
            in_tune += dt * fraction;
        }
    }
    let insufficient = valid_seconds < 0.999999
        || points
            .iter()
            .filter(|p| p.valid && !p.reference_active)
            .count()
            < 5;
    let quantile = |q: f64| {
        if insufficient {
            return None;
        }
        let mut low = spans
            .iter()
            .map(|(a, b, _)| a.min(*b))
            .fold(f64::INFINITY, f64::min);
        let mut high = spans
            .iter()
            .map(|(a, b, _)| a.max(*b))
            .fold(f64::NEG_INFINITY, f64::max);
        for _ in 0..64 {
            let mid = (low + high) / 2.;
            let weight: f64 = spans
                .iter()
                .map(|(a, b, dt)| {
                    let (l, h) = (a.min(*b), a.max(*b));
                    dt * if h - l < 1e-12 {
                        if mid >= l { 1. } else { 0. }
                    } else {
                        ((mid - l) / (h - l)).clamp(0., 1.)
                    }
                })
                .sum();
            if weight < valid_seconds * q {
                low = mid
            } else {
                high = mid
            }
        }
        Some((low + high) / 2.)
    };
    PracticeSummary {
        segment_id: first.segment_id,
        start_time: first.time_seconds,
        end_time: last.time_seconds,
        valid_seconds,
        coverage: if last.time_seconds > first.time_seconds {
            valid_seconds / (last.time_seconds - first.time_seconds)
        } else {
            0.
        },
        median_cents: quantile(0.5),
        low_cents: quantile(0.1),
        high_cents: quantile(0.9),
        accuracy: if insufficient {
            None
        } else {
            Some(in_tune / valid_seconds)
        },
        insufficient,
    }
}

#[cfg(test)]
#[path = "practice_tests.rs"]
mod tests;
