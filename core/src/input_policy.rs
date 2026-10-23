//! 输入策略、音频时间状态与环境测量；无平台时钟，运行期固定容量。
use crate::signal::{PitchSample, SignalState};
use serde::{Deserialize, Serialize};

/// 门限来源。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum GateMode {
    /// 根据本次环境测量解析。
    Auto,
    /// 用户手动值。
    Manual,
}
/// 环境灵敏度档位。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum InputProfile {
    /// 轻声。
    Soft,
    /// 通用。
    General,
    /// 较吵环境。
    Noisy,
}
/// 仅影响显示滤波。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum ResponseMode {
    /// 迅速。
    Fast,
    /// 均衡。
    Balanced,
    /// 平稳。
    Steady,
}
/// 临时读数保留，不删除测量记录。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum HoldMode {
    /// 不保留。
    Off,
    /// 五秒。
    Seconds5,
    /// 十五秒。
    Seconds15,
    /// 直到下次可信发声。
    UntilNext,
}
/// 持久化用户偏好；业务解析在core。
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct InputPolicy {
    /// 自动/手动。
    pub gate_mode: GateMode,
    /// 采集灵敏度。
    pub profile: InputProfile,
    /// 手动开启dBFS。
    pub manual_open_dbfs: f32,
    /// 手动滞回差dB。
    pub manual_hysteresis_db: f32,
    /// 指针响应。
    pub response: ResponseMode,
    /// 断音保留。
    pub hold: HoldMode,
}
impl Default for InputPolicy {
    fn default() -> Self {
        Self {
            gate_mode: GateMode::Auto,
            profile: InputProfile::General,
            manual_open_dbfs: -45.,
            manual_hysteresis_db: 3.,
            response: ResponseMode::Balanced,
            hold: HoldMode::UntilNext,
        }
    }
}
impl InputPolicy {
    pub(crate) fn valid(&self) -> bool {
        self.manual_open_dbfs.is_finite()
            && (-80. ..=-12.).contains(&self.manual_open_dbfs)
            && self.manual_hysteresis_db.is_finite()
            && (3. ..=12.).contains(&self.manual_hysteresis_db)
    }
    fn thresholds(&self, floor: Option<f32>) -> (f32, f32) {
        let (open, h) = if self.gate_mode == GateMode::Manual {
            (self.manual_open_dbfs, self.manual_hysteresis_db)
        } else {
            match (self.profile, floor) {
                (InputProfile::Soft, Some(n)) => (n + 6., 3.),
                (InputProfile::General, Some(n)) => (n + 10., 6.),
                (InputProfile::Noisy, Some(n)) => (n + 14., 6.),
                (InputProfile::Soft, None) => (-55., 3.),
                (InputProfile::General, None) => (-45., 3.),
                (InputProfile::Noisy, None) => (-35., 6.),
            }
        };
        let open = open.clamp(-80., -12.);
        (open, open - h)
    }
    fn decision_eq(&self, p: &Self) -> bool {
        self.gate_mode == p.gate_mode
            && self.profile == p.profile
            && self.manual_open_dbfs == p.manual_open_dbfs
            && self.manual_hysteresis_db == p.manual_hysteresis_db
    }
}
/// 不可变的已解析判定条件；不含呈现偏好。
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct ResolvedInputPolicy {
    /// 算法策略版本。
    pub policy_version: u32,
    /// 模式。
    pub gate_mode: GateMode,
    /// 档位。
    pub profile: InputProfile,
    /// 原始手动值。
    pub manual_open_dbfs: f32,
    /// 原始手动差。
    pub manual_hysteresis_db: f32,
    /// 实际开启。
    pub open_dbfs: f32,
    /// 实际关闭。
    pub close_dbfs: f32,
    /// 本采集背景。
    pub noise_floor_dbfs: Option<f32>,
    /// 判定修订。
    pub policy_revision: u32,
    /// 校准修订。
    pub calibration_revision: u32,
}
impl ResolvedInputPolicy {
    pub(crate) fn valid(&self) -> bool {
        let policy = InputPolicy {
            gate_mode: self.gate_mode,
            profile: self.profile,
            manual_open_dbfs: self.manual_open_dbfs,
            manual_hysteresis_db: self.manual_hysteresis_db,
            ..InputPolicy::default()
        };
        let (open, close) = policy.thresholds(self.noise_floor_dbfs);
        self.policy_version == 1
            && self.policy_revision > 0
            && policy.valid()
            && self
                .noise_floor_dbfs
                .is_none_or(|v| v.is_finite() && (-120. ..=0.).contains(&v))
            && self.open_dbfs.is_finite()
            && self.close_dbfs.is_finite()
            && (self.open_dbfs - open).abs() < 0.001
            && (self.close_dbfs - close).abs() < 0.001
    }
}
/// 输入诊断；不以音分是否接近目标判断可信度。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum InputReason {
    /// 正常可信输入。
    None,
    /// 低于门限。
    BelowGate,
    /// 周期证据不足。
    NoPeriodicPitch,
    /// 八度候选未确认。
    AmbiguousPitch,
    /// 起音确认。
    Acquiring,
    /// 测环境中。
    Calibrating,
    /// 短暂缺口。
    Dropout,
    /// 数字削波。
    Clipped,
    /// 参考音输出影响。
    OutputContaminated,
    /// 输入不可用。
    InputUnavailable,
}
/// 随同一个采样窗口发布的判定信息。
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct InputDiagnostics {
    /// 失效或提示原因。
    pub reason: InputReason,
    /// 周期质量，不是概率。
    pub clarity: Option<f32>,
    /// 削波样本比例。
    pub clipped_fraction: f32,
    /// 同窗口实际判定条件。
    pub policy: ResolvedInputPolicy,
    /// 本机节拍器可能回采，不整段屏蔽。
    pub metronome_risk: bool,
    /// 当时显示偏好。
    pub response: ResponseMode,
    /// 当时保持偏好。
    pub hold: HoldMode,
}
impl InputDiagnostics {
    pub(crate) fn valid(&self) -> bool {
        self.policy.valid()
            && self
                .clarity
                .is_none_or(|v| v.is_finite() && (0. ..=1.).contains(&v))
            && self.clipped_fraction.is_finite()
            && (0. ..=1.).contains(&self.clipped_fraction)
    }
}
/// 原生提供的实际帧末位置与输出上下文。
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct InputFrameContext {
    /// 实际采样位置。
    pub sample_position: u64,
    /// 输入代次。
    pub capture_generation: u64,
    /// 参考音及原生保护尾。
    pub reference_active: bool,
    /// 本机节拍器活动。
    pub metronome_active: bool,
}
/// 环境测量阶段。
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum CalibrationState {
    /// 未测。
    Idle,
    /// 正在采集。
    Measuring,
    /// 检出持续音调，需要确认是环境声。
    NeedsQuietConfirmation,
    /// 已测。
    Ready,
    /// 未成功，旧值在同上下文保留。
    Failed,
}
/// 可呈现的校准状态。
#[derive(Debug, Clone, uniffi::Record)]
pub struct CalibrationStatus {
    /// 阶段。
    pub state: CalibrationState,
    /// 候选或成功背景。
    pub floor_dbfs: Option<f32>,
    /// 音频时间进度。
    pub elapsed_seconds: f64,
    /// 可恢复提示。
    pub reason: String,
}
/// 整体实时输入状态，与保持卡分开。
#[derive(Debug, Clone, uniffi::Record)]
pub struct InputStatus {
    /// 是否有输入。
    pub available: bool,
    /// 当前RMS。
    pub input_level_dbfs: f32,
    /// 当前诊断。
    pub diagnostics: Option<InputDiagnostics>,
    /// 环境测量。
    pub calibration: CalibrationStatus,
    /// 含保护尾的本机输出，用于校准编排。
    pub output_active: bool,
}
/// 输入配置/时钟错误。
#[derive(Debug, Clone, uniffi::Error)]
pub enum InputPolicyError {
    /// 参数无效。
    InvalidPolicy,
    /// 时间或帧无效。
    InvalidFrame,
    /// 先停止本机输出。
    OutputActive,
    /// 没有等待确认的校准。
    NoCalibration,
}
impl std::fmt::Display for InputPolicyError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::InvalidPolicy => "输入设置无效，请恢复默认后重试",
            Self::InvalidFrame => "输入时钟或采样帧无效",
            Self::OutputActive => "请先停止播放，等待输出保护结束再测环境",
            Self::NoCalibration => "没有待确认的环境测量",
        })
    }
}
impl std::error::Error for InputPolicyError {}
/// 平台一致的初始偏好。
#[uniffi::export]
pub fn default_input_policy() -> InputPolicy {
    InputPolicy::default()
}
/// 平台启动时的空状态，不需创建音频硬件。
#[uniffi::export]
pub fn default_input_status() -> InputStatus {
    InputStatus {
        available: false,
        input_level_dbfs: -120.,
        diagnostics: None,
        calibration: CalibrationStatus {
            state: CalibrationState::Idle,
            floor_dbfs: None,
            elapsed_seconds: 0.,
            reason: "尚未按环境测量".into(),
        },
        output_active: false,
    }
}
/// 保持可见性纯函数，共用相同语义。
pub(crate) fn hold_visible(mode: HoldMode, age: f64) -> bool {
    match mode {
        HoldMode::Off => false,
        HoldMode::Seconds5 => age < 5.,
        HoldMode::Seconds15 => age < 15.,
        HoldMode::UntilNext => true,
    }
}
pub(crate) struct InputDecision {
    pub measured: Option<PitchSample>,
    pub display: Option<PitchSample>,
    pub state: SignalState,
    pub diagnostics: InputDiagnostics,
    pub display_midi: Option<i32>,
}
/// 分析队列所有者。固定校准槽，不读取平台时钟。
pub(crate) struct InputRuntime {
    pub(crate) a4_hz: f64,
    policy: InputPolicy,
    resolved: ResolvedInputPolicy,
    sample_rate: f64,
    hop: u64,
    last_context: Option<InputFrameContext>,
    cal_state: CalibrationState,
    cal_start: Option<u64>,
    cal_last: Option<u64>,
    cal_levels: [f32; 128],
    cal_count: usize,
    cal_elapsed: f64,
    cal_floor: Option<f32>,
    cal_message: &'static str,
    cal_tonal_since: Option<u64>,
    cal_tonal: bool,
    pending: Option<u64>,
    last_accepted: Option<PitchSample>,
    last_valid_position: Option<u64>,
    tracking: bool,
    octave: Option<(u64, u64, f32)>,
    log_display: Option<f64>,
    display_midi: Option<i32>,
    name_candidate: Option<(i32, u64)>,
    last_output: Option<u64>,
    last_diagnostics: Option<InputDiagnostics>,
    level: f32,
}
impl InputRuntime {
    pub(crate) fn new(sample_rate: f64, hop: u32, gate: f32) -> Self {
        let p = InputPolicy::default();
        Self {
            a4_hz: 440.,
            policy: p,
            resolved: ResolvedInputPolicy {
                policy_version: 1,
                gate_mode: p.gate_mode,
                profile: p.profile,
                manual_open_dbfs: gate.clamp(-80., -12.),
                manual_hysteresis_db: 3.,
                open_dbfs: -45.,
                close_dbfs: -48.,
                noise_floor_dbfs: None,
                policy_revision: 1,
                calibration_revision: 0,
            },
            sample_rate,
            hop: u64::from(hop),
            last_context: None,
            cal_state: CalibrationState::Idle,
            cal_start: None,
            cal_last: None,
            cal_levels: [0.; 128],
            cal_count: 0,
            cal_elapsed: 0.,
            cal_floor: None,
            cal_message: "尚未按环境测量",
            cal_tonal_since: None,
            cal_tonal: false,
            pending: None,
            last_accepted: None,
            last_valid_position: None,
            tracking: false,
            octave: None,
            log_display: None,
            display_midi: None,
            name_candidate: None,
            last_output: None,
            last_diagnostics: None,
            level: -120.,
        }
    }
    pub(crate) fn policy(&self) -> InputPolicy {
        self.policy
    }
    #[cfg(test)]
    pub(crate) fn resolved(&self) -> ResolvedInputPolicy {
        self.resolved
    }
    fn resolve(&mut self) {
        let p = self.policy;
        let (open, close) = p.thresholds(self.resolved.noise_floor_dbfs);
        self.resolved.gate_mode = p.gate_mode;
        self.resolved.profile = p.profile;
        self.resolved.manual_open_dbfs = p.manual_open_dbfs;
        self.resolved.manual_hysteresis_db = p.manual_hysteresis_db;
        self.resolved.open_dbfs = open;
        self.resolved.close_dbfs = close;
    }
    pub(crate) fn set_policy(&mut self, p: InputPolicy) -> Result<(), InputPolicyError> {
        if !p.valid() {
            return Err(InputPolicyError::InvalidPolicy);
        }
        let changed = !self.policy.decision_eq(&p);
        self.policy = p;
        if changed {
            self.resolved.policy_revision = self.resolved.policy_revision.saturating_add(1);
            self.resolve();
            self.pending = None;
            self.tracking = false;
        }
        Ok(())
    }
    pub(crate) fn validate_context(&self, c: InputFrameContext) -> Result<(), InputPolicyError> {
        if let Some(old) = self.last_context
            && (c.capture_generation < old.capture_generation
                || (c.capture_generation == old.capture_generation
                    && c.sample_position <= old.sample_position))
        {
            return Err(InputPolicyError::InvalidFrame);
        }
        Ok(())
    }
    pub(crate) fn reset(&mut self) {
        let a4 = self.a4_hz;
        let policy = self.policy;
        let revision = self.resolved.policy_revision.saturating_add(1);
        let calrev = self.resolved.calibration_revision;
        *self = Self::new(self.sample_rate, self.hop as u32, policy.manual_open_dbfs);
        self.a4_hz = a4;
        self.policy = policy;
        self.resolved.policy_revision = revision;
        self.resolved.calibration_revision = calrev;
        self.resolve();
        self.cal_message = "输入已变化，请重新测环境";
    }
    pub(crate) fn pre_gate(&self) -> f32 {
        if self.cal_state == CalibrationState::Measuring {
            -120.
        } else {
            self.resolved.close_dbfs
        }
    }
    pub(crate) fn output_active(&self) -> bool {
        self.last_context.is_some_and(|c| {
            c.reference_active
                || c.metronome_active
                || self.last_output.is_some_and(|p| {
                    c.sample_position.saturating_sub(p) as f64 / self.sample_rate < 0.2
                })
        })
    }
    pub(crate) fn begin_calibration(&mut self, output: bool) -> Result<(), InputPolicyError> {
        if output || self.output_active() {
            return Err(InputPolicyError::OutputActive);
        }
        self.cal_state = CalibrationState::Measuring;
        self.cal_start = None;
        self.cal_last = None;
        self.cal_count = 0;
        self.cal_elapsed = 0.;
        self.cal_floor = None;
        self.cal_tonal_since = None;
        self.cal_tonal = false;
        self.cal_message = "先不要演奏，保持2秒安静";
        self.tracking = false;
        self.pending = None;
        Ok(())
    }
    pub(crate) fn cancel_calibration(&mut self) {
        self.cal_state = if self.resolved.noise_floor_dbfs.is_some() {
            CalibrationState::Ready
        } else {
            CalibrationState::Idle
        };
        self.cal_message = "已取消，原环境设置已保留";
        self.cal_floor = self.resolved.noise_floor_dbfs;
    }
    pub(crate) fn confirm_environment(&mut self) -> Result<(), InputPolicyError> {
        if self.cal_state != CalibrationState::NeedsQuietConfirmation {
            return Err(InputPolicyError::NoCalibration);
        }
        self.apply_floor();
        Ok(())
    }
    fn fail_cal(&mut self, message: &'static str) {
        self.cal_state = CalibrationState::Failed;
        self.cal_message = message;
    }
    fn apply_floor(&mut self) {
        if let Some(n) = self.cal_floor {
            let margin = match self.policy.profile {
                InputProfile::Soft => 6.,
                InputProfile::General => 10.,
                InputProfile::Noisy => 14.,
            };
            if n + margin > -12. {
                self.fail_cal("环境或输入增益太高，旧设置保留；请降低输入或换位置重测");
                return;
            }
            self.resolved.noise_floor_dbfs = Some(n);
            self.resolved.policy_revision = self.resolved.policy_revision.saturating_add(1);
            self.resolved.calibration_revision =
                self.resolved.calibration_revision.saturating_add(1);
            self.resolve();
            self.cal_state = CalibrationState::Ready;
            self.cal_message = if n + margin < -80. {
                "环境已测，开启门限受下界限制"
            } else {
                "环境已测；记录仍暂停，继续需手动操作"
            };
        }
    }
    fn calibrate(
        &mut self,
        pitch: Option<PitchSample>,
        level: f32,
        clip: f32,
        c: InputFrameContext,
        gap: bool,
    ) {
        if self.cal_state != CalibrationState::Measuring {
            return;
        }
        if c.reference_active || c.metronome_active {
            self.fail_cal("播放影响了环境测量，请停止播放后重测");
            return;
        }
        if gap {
            self.fail_cal("采集不连续，请重新测环境");
            return;
        }
        let start = *self.cal_start.get_or_insert(c.sample_position);
        self.cal_elapsed = c.sample_position.saturating_sub(start) as f64 / self.sample_rate;
        if clip >= 0.01 {
            self.fail_cal("输入过强，请降低输入后重测");
            return;
        }
        if pitch.is_some_and(|p| p.clarity >= 0.85) {
            let at = *self.cal_tonal_since.get_or_insert(c.sample_position);
            if c.sample_position.saturating_sub(at) as f64 / self.sample_rate >= 0.2 {
                self.cal_tonal = true;
            }
        } else {
            self.cal_tonal_since = None;
        }
        if self.cal_last.is_none_or(|next| c.sample_position >= next) {
            if self.cal_count < 128 {
                self.cal_levels[self.cal_count] = level;
                self.cal_count += 1;
            }
            self.cal_last = Some(
                self.cal_last
                    .unwrap_or(c.sample_position)
                    .saturating_add((self.sample_rate / 20.).round() as u64),
            );
        }
        if self.cal_elapsed >= 2. && self.cal_count >= 35 {
            let mut sorted = self.cal_levels;
            sorted[..self.cal_count].sort_unstable_by(f32::total_cmp);
            let at = |q: f64| sorted[((self.cal_count - 1) as f64 * q).round() as usize];
            self.cal_floor = Some(at(0.8));
            if at(0.95) - at(0.2) > 12. {
                self.fail_cal("环境变化过大，原设置保留，请安静后重测");
            } else if self.cal_tonal {
                self.cal_state = CalibrationState::NeedsQuietConfirmation;
                self.cal_message = "检测到持续音调，请确认没有演奏；若确为环境声可采用";
            } else {
                self.apply_floor();
            }
        } else if self.cal_elapsed >= 5. {
            self.fail_cal("有效输入不足，请重测");
        }
    }
    pub(crate) fn calibration_status(&self) -> CalibrationStatus {
        CalibrationStatus {
            state: self.cal_state,
            floor_dbfs: self.cal_floor.or(self.resolved.noise_floor_dbfs),
            elapsed_seconds: self.cal_elapsed,
            reason: self.cal_message.into(),
        }
    }
    pub(crate) fn status(&self, available: bool) -> InputStatus {
        InputStatus {
            available,
            input_level_dbfs: self.level,
            diagnostics: self.last_diagnostics,
            calibration: self.calibration_status(),
            output_active: self.output_active(),
        }
    }
    pub(crate) fn generation(&self) -> Option<u64> {
        self.last_context.map(|c| c.capture_generation)
    }
    pub(crate) fn process(
        &mut self,
        detected: Option<PitchSample>,
        level: f32,
        clip: f32,
        c: InputFrameContext,
    ) -> Result<InputDecision, InputPolicyError> {
        self.validate_context(c)?;
        let old = self.last_context;
        if old.is_some_and(|o| o.capture_generation != c.capture_generation) {
            self.reset();
        }
        let gap = old.is_some_and(|o| {
            o.capture_generation == c.capture_generation
                && c.sample_position.saturating_sub(o.sample_position)
                    > self.hop.saturating_mul(3) / 2
        });
        if gap {
            self.pending = None;
            self.tracking = false;
            self.log_display = None;
            self.octave = None;
        }
        if c.reference_active || c.metronome_active {
            self.last_output = Some(c.sample_position);
        }
        self.level = level;
        self.calibrate(detected, level, clip, c, gap);
        let mut reason = InputReason::None;
        let gate = if self.tracking {
            self.resolved.close_dbfs
        } else {
            self.resolved.open_dbfs
        };
        let clarity = if self.tracking { 0.75 } else { 0.85 };
        let candidate =
            detected.filter(|p| p.freq_hz.is_finite() && p.freq_hz > 0. && p.clarity >= clarity);
        if self.cal_state == CalibrationState::Measuring
            || self.cal_state == CalibrationState::NeedsQuietConfirmation
        {
            reason = InputReason::Calibrating;
        } else if clip >= 0.01 {
            reason = InputReason::Clipped;
        } else if level < gate {
            reason = InputReason::BelowGate;
        } else if candidate.is_none() {
            reason = InputReason::NoPeriodicPitch;
        }
        let mut measured = None;
        if reason == InputReason::None {
            if let Some(p) = candidate {
                let octave_jump = self.last_accepted.is_some_and(|last| {
                    let d = (1200. * (f64::from(p.freq_hz) / f64::from(last.freq_hz)).log2()).abs();
                    (d - 1200.).abs() < 80.
                });
                let mut ambiguous = false;
                if self.tracking && octave_jump {
                    let (start, stable, freq) =
                        self.octave
                            .unwrap_or((c.sample_position, c.sample_position, p.freq_hz));
                    let coherent = (1200. * (p.freq_hz / freq).log2()).abs() < 35.;
                    let stable = if coherent { stable } else { c.sample_position };
                    self.octave = Some((start, stable, p.freq_hz));
                    let duration =
                        c.sample_position.saturating_sub(stable) as f64 / self.sample_rate;
                    ambiguous = duration < 0.08;
                    if c.sample_position.saturating_sub(start) as f64 / self.sample_rate >= 0.12
                        && ambiguous
                    {
                        self.tracking = false;
                        self.pending = None;
                        self.octave = None;
                    }
                } else {
                    self.octave = None;
                }
                if ambiguous {
                    reason = InputReason::AmbiguousPitch;
                } else if !self.tracking {
                    let since = *self.pending.get_or_insert(c.sample_position);
                    if c.sample_position.saturating_sub(since) as f64 / self.sample_rate + 1e-9
                        >= 0.06
                    {
                        measured = Some(p);
                        self.tracking = true;
                        self.pending = None;
                        self.log_display = None;
                    } else {
                        reason = InputReason::Acquiring;
                    }
                } else {
                    measured = Some(p);
                }
            }
        } else {
            self.pending = None;
            self.tracking = false;
            self.octave = None;
            if reason != InputReason::Calibrating
                && reason != InputReason::Clipped
                && self.last_valid_position.is_some_and(|p| {
                    c.sample_position.saturating_sub(p) as f64 / self.sample_rate <= 0.1
                })
            {
                reason = InputReason::Dropout;
            }
        }
        let mut display = None;
        if let Some(p) = measured {
            let jump = self
                .last_accepted
                .is_some_and(|old| (1200. * (p.freq_hz / old.freq_hz).log2()).abs() >= 80.);
            let value = f64::from(p.freq_hz).ln();
            let dt = old.map_or(self.hop as f64 / self.sample_rate, |o| {
                c.sample_position.saturating_sub(o.sample_position) as f64 / self.sample_rate
            });
            let tau = match self.policy.response {
                ResponseMode::Fast => 0.04,
                ResponseMode::Balanced => 0.08,
                ResponseMode::Steady => 0.14,
            };
            let filtered = if jump {
                value
            } else {
                self.log_display.map_or(value, |last| {
                    last + (1. - (-dt / tau).exp()) * (value - last)
                })
            };
            self.log_display = Some(filtered);
            display = Some(PitchSample {
                freq_hz: filtered.exp() as f32,
                clarity: p.clarity,
            });
            self.last_accepted = Some(p);
            self.last_valid_position = Some(c.sample_position);
            let midi = (69. + 12. * (f64::from(p.freq_hz) / self.a4_hz).log2()).round() as i32;
            if let Some(stable) = self.display_midi {
                let d = (1200.
                    * (f64::from(p.freq_hz)
                        / (self.a4_hz * 2_f64.powf(f64::from(stable - 69) / 12.)))
                    .log2())
                .abs();
                if midi != stable && d >= 55. {
                    let (candidate, at) = self.name_candidate.unwrap_or((midi, c.sample_position));
                    if candidate == midi
                        && c.sample_position.saturating_sub(at) as f64 / self.sample_rate >= 0.06
                    {
                        self.display_midi = Some(midi);
                        self.name_candidate = None;
                    } else {
                        self.name_candidate = Some((
                            midi,
                            if candidate == midi {
                                at
                            } else {
                                c.sample_position
                            },
                        ));
                    }
                } else {
                    self.name_candidate = None;
                }
            } else {
                self.display_midi = Some(midi);
            }
        }
        if display.is_none()
            && let Some(age) = self
                .last_valid_position
                .map(|p| c.sample_position.saturating_sub(p) as f64 / self.sample_rate)
            && hold_visible(self.policy.hold, age)
        {
            display = self.last_accepted;
        }
        if c.reference_active && reason == InputReason::None {
            reason = InputReason::OutputContaminated;
        }
        let state = if measured.is_some() {
            SignalState::Tracking
        } else if self.last_accepted.is_some() {
            SignalState::Holding
        } else if reason == InputReason::Acquiring {
            SignalState::Acquiring
        } else {
            SignalState::Quiet
        };
        let diagnostics = InputDiagnostics {
            reason,
            clarity: detected.map(|p| p.clarity),
            clipped_fraction: clip,
            policy: self.resolved,
            metronome_risk: c.metronome_active,
            response: self.policy.response,
            hold: self.policy.hold,
        };
        self.last_context = Some(c);
        self.last_diagnostics = Some(diagnostics);
        Ok(InputDecision {
            measured,
            display,
            state,
            diagnostics,
            display_midi: self.display_midi,
        })
    }
}

#[cfg(test)]
#[path = "input_policy_tests.rs"]
mod tests;
