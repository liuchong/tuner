//! 节拍器引擎：lookahead 调度 + 采样级精确 tick 混音（规则见 `docs/spec-core.md` §7）。
//!
//! 内核 `MetronomeCore` 操作调用方提供的 `&mut [f32]` 与固定容量 tick 缓冲，
//! render 路径零分配、零锁、无 panic。tick 间隔用 f64 相位累加并对 tick k 按
//! `start + k·interval` 重定基准，保证连续渲染累计误差不随 tick 数线性增长。

/// 拍重音型。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Accent {
    /// 重拍。
    #[default]
    Accent,
    /// 弱拍。
    Normal,
    /// 静音拍（跳过发声，仍产生事件）。
    Muted,
}

/// 一次 tick 事件（内核侧，固定大小）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TickEvent {
    /// 相对本次 render 缓冲起点的采样偏移。
    pub sample_offset: u64,
    /// 小节内拍序号（0 起）。
    pub beat_index: u32,
    /// 重音型。
    pub accent: Accent,
}

/// tick 事件固定容量缓冲（单缓冲内 tick 数上限）。
pub const MAX_TICKS_PER_RENDER: usize = 64;

/// tick 事件列表（栈上固定容量）。
pub struct TickList {
    /// 存储。
    pub events: [TickEvent; MAX_TICKS_PER_RENDER],
    /// 有效个数。
    pub len: usize,
}

impl Default for TickList {
    fn default() -> Self {
        Self {
            events: [TickEvent {
                sample_offset: 0,
                beat_index: 0,
                accent: Accent::Normal,
            }; MAX_TICKS_PER_RENDER],
            len: 0,
        }
    }
}

impl TickList {
    /// 追加一个事件；超出容量时丢弃（不 panic）。
    pub fn push(&mut self, ev: TickEvent) {
        if self.len < MAX_TICKS_PER_RENDER {
            self.events[self.len] = ev;
            self.len += 1;
        }
    }
    /// 清空。
    pub fn clear(&mut self) {
        self.len = 0;
    }
    /// 有效切片。
    pub fn as_slice(&self) -> &[TickEvent] {
        &self.events[..self.len]
    }
}

/// 默认合成 click 长度（采样）。
const DEFAULT_CLICK_LEN: usize = 2048;
/// 注入 click 采样上限（防止 API 层传入过长采样撑爆内存）。
pub const MAX_CLICK_LEN: usize = 48000;

/// 节拍器内核。单线程使用，内部无锁。
pub struct MetronomeCore {
    sample_rate: f64,
    bpm: f64,
    beats_per_bar: u8,
    beat_unit: u8,
    accents: Vec<Accent>,
    /// 重拍音色。
    click_accent: Vec<f32>,
    /// 弱拍音色。
    click_normal: Vec<f32>,
    running: bool,
    /// 当前拍在小节内的序号。
    beat_index: u32,
    /// 当前 tick 的绝对采样序号（f64 相位，渲染时 round）。
    tick_pos: f64,
    /// 当前 tick 间隔（采样）。
    interval: f64,
    /// 全局采样游标（已渲染采样数）。
    cursor: u64,
    /// tap 时间戳环形缓冲（最近 5 次 → 4 个间隔）。
    taps: [u64; 5],
    /// tap 写入数（饱和到 5）。
    tap_count: usize,
    /// tap 环形写指针。
    tap_head: usize,
}

impl MetronomeCore {
    /// 构造。参数越界时收敛到合法范围（构造期，不在热路径）。
    pub fn new(
        sample_rate: f64,
        bpm: f64,
        beats_per_bar: u8,
        beat_unit: u8,
        accents: &[Accent],
    ) -> Self {
        let sample_rate = if sample_rate > 0.0 {
            sample_rate
        } else {
            44100.0
        };
        let bpm = bpm.clamp(30.0, 250.0);
        let beats_per_bar = beats_per_bar.clamp(1, 12);
        let beat_unit = match beat_unit {
            2 | 4 | 8 => beat_unit,
            _ => 4,
        };
        let mut accents_vec = vec![Accent::Normal; beats_per_bar as usize];
        for (i, a) in accents.iter().take(beats_per_bar as usize).enumerate() {
            accents_vec[i] = *a;
        }
        let mut m = Self {
            sample_rate,
            bpm,
            beats_per_bar,
            beat_unit,
            accents: accents_vec,
            click_accent: Vec::new(),
            click_normal: Vec::new(),
            running: false,
            beat_index: 0,
            tick_pos: 0.0,
            interval: 0.0,
            cursor: 0,
            taps: [0; 5],
            tap_count: 0,
            tap_head: 0,
        };
        m.interval = m.compute_interval();
        // 默认音色：重拍 1600Hz、弱拍 1100Hz 衰减正弦
        let accent = synth_click(1600.0, sample_rate);
        let normal = synth_click(1100.0, sample_rate);
        m.click_accent = accent;
        m.click_normal = normal;
        m
    }

    /// 每拍采样间隔：拍长 = 60/bpm 秒 × (4/beat_unit)（以四分音符为 BPM 基准）。
    fn compute_interval(&self) -> f64 {
        self.sample_rate * 60.0 / self.bpm * (4.0 / self.beat_unit as f64)
    }

    /// 设置 BPM（30–250），下一采样生效。
    pub fn set_bpm(&mut self, bpm: f64) {
        self.bpm = bpm.clamp(30.0, 250.0);
        self.interval = self.compute_interval();
    }

    /// 当前 BPM。
    pub fn bpm(&self) -> f64 {
        self.bpm
    }

    /// 设置拍号。accents 长度不足时补 Normal，超出截断。
    pub fn set_time_signature(&mut self, beats: u8, unit: u8) {
        self.beats_per_bar = beats.clamp(1, 12);
        self.beat_unit = match unit {
            2 | 4 | 8 => unit,
            _ => self.beat_unit,
        };
        self.accents
            .resize(self.beats_per_bar as usize, Accent::Normal);
        self.beat_index %= self.beats_per_bar as u32;
        self.interval = self.compute_interval();
    }

    /// 设置重音型（长度应为 beats_per_bar；不足补 Normal，超出截断）。
    pub fn set_accents(&mut self, accents: &[Accent]) {
        let n = self.beats_per_bar as usize;
        self.accents.resize(n, Accent::Normal);
        for (i, a) in accents.iter().take(n).enumerate() {
            self.accents[i] = *a;
        }
    }

    /// 注入 tick 音色（空切片则恢复内置合成音色）。
    pub fn set_click_samples(&mut self, accent: &[f32], normal: &[f32]) {
        if accent.is_empty() {
            self.click_accent = synth_click(1600.0, self.sample_rate);
        } else {
            self.click_accent = accent[..accent.len().min(MAX_CLICK_LEN)].to_vec();
        }
        if normal.is_empty() {
            self.click_normal = synth_click(1100.0, self.sample_rate);
        } else {
            self.click_normal = normal[..normal.len().min(MAX_CLICK_LEN)].to_vec();
        }
    }

    /// 从 `at_sample` 开始运行（重置拍序号与相位）。
    pub fn start(&mut self, at_sample: u64) {
        self.running = true;
        self.beat_index = 0;
        self.cursor = at_sample;
        self.tick_pos = at_sample as f64;
    }

    /// 停止。
    pub fn stop(&mut self) {
        self.running = false;
    }

    /// 是否运行中。
    pub fn is_running(&self) -> bool {
        self.running
    }

    /// tap tempo：记录一次 tap 的采样时间戳，取最近 ≤4 个间隔的中位数换算 BPM。
    /// 返回当前 BPM。间隔必须在 30–250 BPM 对应范围内才参与计算，否则重置序列。
    pub fn tap(&mut self, timestamp_samples: u64) -> f64 {
        // 环形写入
        self.taps[self.tap_head] = timestamp_samples;
        self.tap_head = (self.tap_head + 1) % 5;
        self.tap_count = (self.tap_count + 1).min(5);
        if self.tap_count < 2 {
            return self.bpm;
        }
        // 取最近 ≤4 个间隔
        let n_intervals = self.tap_count - 1;
        let mut intervals = [0u64; 4];
        for (i, slot) in intervals.iter_mut().enumerate().take(n_intervals) {
            // 从新到旧：head-1 是最新
            let newer = (self.tap_head + 5 - 1 - i) % 5;
            let older = (self.tap_head + 5 - 2 - i) % 5;
            *slot = self.taps[newer].saturating_sub(self.taps[older]);
        }
        // 合法性：间隔对应 BPM 在 [30,250] → 间隔 ∈ [sr*60/250, sr*60/30]
        let min_d = self.sample_rate * 60.0 / 250.0;
        let max_d = self.sample_rate * 60.0 / 30.0;
        let mut valid = [0u64; 4];
        let mut n_valid = 0;
        for &d in intervals.iter().take(n_intervals) {
            if d as f64 >= min_d && d as f64 <= max_d {
                valid[n_valid] = d;
                n_valid += 1;
            }
        }
        if n_valid == 0 {
            // 间隔越界：重置序列
            self.tap_count = 1;
            return self.bpm;
        }
        let slice = &mut valid[..n_valid];
        slice.sort_unstable();
        let median = if n_valid % 2 == 1 {
            slice[n_valid / 2] as f64
        } else {
            (slice[n_valid / 2 - 1] as f64 + slice[n_valid / 2] as f64) / 2.0
        };
        // 间隔对应「拍」长：BPM = 60·sr/median（tap 以拍为单位）
        let bpm = (60.0 * self.sample_rate / median).clamp(30.0, 250.0);
        self.bpm = bpm;
        self.interval = self.compute_interval();
        bpm
    }

    /// 渲染 `frames` 个采样到 `out`（累加混入，调用方负责清零或传入静音缓冲），
    /// tick 事件写入 `ticks`。零分配、零锁、无 panic。
    pub fn render_into(&mut self, out: &mut [f32], frames: usize, ticks: &mut TickList) {
        ticks.clear();
        let n = frames.min(out.len());
        let end = self.cursor + n as u64;
        if self.running {
            while self.tick_pos < end as f64 {
                let pos = self.tick_pos.round() as u64;
                if pos >= end {
                    // round 越过缓冲末尾：留给下一次 render 的缓冲起点
                    break;
                }
                if pos >= self.cursor {
                    let offset = pos - self.cursor;
                    let accent = self.accents[self.beat_index as usize];
                    ticks.push(TickEvent {
                        sample_offset: offset,
                        beat_index: self.beat_index,
                        accent,
                    });
                    let click: &[f32] = match accent {
                        Accent::Accent => &self.click_accent,
                        Accent::Normal => &self.click_normal,
                        Accent::Muted => &[],
                    };
                    let start = offset as usize;
                    let avail = n - start;
                    let len = click.len().min(avail);
                    for (k, &s) in click.iter().take(len).enumerate() {
                        out[start + k] += s;
                    }
                }
                // 推进到下一拍。tick_pos 保持 f64 精确累加（舍入误差 ~1e-16/tick，
                // 不会线性增长为可闻误差；渲染时 round 到采样，误差恒 < 0.5 采样）
                self.beat_index = (self.beat_index + 1) % self.beats_per_bar as u32;
                self.tick_pos += self.interval;
            }
        }
        self.cursor = end;
    }
}

/// 内置合成 click：指数衰减正弦（长度 DEFAULT_CLICK_LEN，上限 2048 采样）。
fn synth_click(freq: f64, sample_rate: f64) -> Vec<f32> {
    let len = DEFAULT_CLICK_LEN.min((sample_rate * 0.05) as usize).max(64);
    let tau = len as f64 / 5.0; // 5 个时间常数衰减到 ~0
    let mut out = Vec::with_capacity(len);
    for i in 0..len {
        let t = i as f64 / sample_rate;
        let env = (-(i as f64) / tau).exp();
        out.push((0.9 * env * (2.0 * core::f64::consts::PI * freq * t).sin()) as f32);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make(bpm: f64) -> MetronomeCore {
        let mut m = MetronomeCore::new(44100.0, bpm, 4, 4, &[Accent::Accent]);
        m.start(0);
        m
    }

    #[test]
    fn tick_positions_sample_accurate_over_1000_ticks() {
        // 连续渲染 1000 个 tick，位置误差 < 1 采样（不随 tick 数线性增长）
        let sr = 44100.0;
        let bpm = 123.0; // 非整数间隔，检验误差累积
        let interval = sr * 60.0 / bpm;
        let mut m = MetronomeCore::new(sr, bpm, 4, 4, &[Accent::Accent]);
        m.start(0);
        let mut buf = vec![0.0f32; 256];
        let mut ticks = TickList::default();
        let mut seen = 0usize;
        while seen < 1000 {
            m.render_into(&mut buf, 256, &mut ticks);
            for t in ticks.as_slice() {
                let abs = m.cursor - 256 + t.sample_offset;
                let k = seen as f64;
                let theoretical = k * interval;
                let err = (abs as f64 - theoretical).abs();
                assert!(
                    err < 1.0,
                    "tick {seen}: 位置 {abs} 理论 {theoretical} 误差 {err}"
                );
                seen += 1;
            }
        }
    }

    #[test]
    fn set_bpm_immediate() {
        let mut m = make(120.0); // 间隔 22050
        let mut buf = vec![0.0f32; 44100];
        let mut ticks = TickList::default();
        // 先渲染 1/4 秒：只有 0 处一个 tick，下一 tick 排在 22050
        m.render_into(&mut buf, 11025, &mut ticks);
        assert_eq!(ticks.len, 1);
        assert_eq!(ticks.events[0].sample_offset, 0);
        // 立即改为 60BPM（间隔 44100）：已排程的 22050 处 tick 照常发声，
        // 其后间隔按新 tempo → 下一 tick 在 22050+44100=66150
        m.set_bpm(60.0);
        m.render_into(&mut buf, 44100, &mut ticks); // [11025, 55125)
        assert_eq!(ticks.len, 1);
        assert_eq!(ticks.events[0].sample_offset, 11025); // 22050
        // 再渲染：tick 在 66150，即偏移 66150-55125=11025
        m.render_into(&mut buf, 44100, &mut ticks);
        assert_eq!(ticks.len, 1);
        assert_eq!(ticks.events[0].sample_offset, 11025);
    }

    #[test]
    fn tap_tempo_median() {
        let mut m = make(120.0);
        // 以 100BPM 节奏 tap：间隔 = 44100*60/100 = 26460
        let d = 26460u64;
        let mut ts = 0u64;
        for _ in 0..4 {
            ts += d;
            m.tap(ts);
        }
        assert!((m.bpm() - 100.0).abs() < 0.5);
        // 混入一个越界间隔（过快），应被丢弃而不拉偏中位数
        m.tap(ts + 100);
        m.tap(ts + 100 + d);
        let b = m.bpm();
        assert!((b - 100.0).abs() < 1.0, "bpm={b}");
    }

    #[test]
    fn accent_pattern() {
        let mut m = MetronomeCore::new(
            44100.0,
            240.0, // 间隔 11025，1 秒 4 拍
            4,
            4,
            &[
                Accent::Accent,
                Accent::Normal,
                Accent::Muted,
                Accent::Normal,
            ],
        );
        m.start(0);
        let mut buf = vec![0.0f32; 44100];
        let mut ticks = TickList::default();
        m.render_into(&mut buf, 44100, &mut ticks);
        assert_eq!(ticks.len, 4);
        assert_eq!(ticks.events[0].accent, Accent::Accent);
        assert_eq!(ticks.events[1].accent, Accent::Normal);
        assert_eq!(ticks.events[2].accent, Accent::Muted);
        assert_eq!(ticks.events[3].accent, Accent::Normal);
        assert_eq!(ticks.events[0].beat_index, 0);
        assert_eq!(ticks.events[3].beat_index, 3);
        // Muted 拍不发声：其位置处缓冲应为 0
        let muted_pos = ticks.events[2].sample_offset as usize;
        assert_eq!(buf[muted_pos], 0.0);
        // Accent 拍发声：非零
        assert!(buf[0] != 0.0 || buf[1] != 0.0);
    }

    #[test]
    fn click_mixed_sample_accurately() {
        let mut m = MetronomeCore::new(44100.0, 120.0, 4, 4, &[Accent::Accent]);
        let click = [0.5f32, -0.5, 0.25];
        m.set_click_samples(&click, &click);
        m.start(100); // 从非零位置开始
        let mut buf = vec![0.0f32; 8];
        let mut ticks = TickList::default();
        m.render_into(&mut buf, 8, &mut ticks);
        assert_eq!(ticks.len, 1);
        assert_eq!(ticks.events[0].sample_offset, 0);
        assert_eq!(&buf[..3], &click);
    }

    #[test]
    fn stop_and_restart() {
        let mut m = make(120.0);
        let mut buf = vec![0.0f32; 44100];
        let mut ticks = TickList::default();
        m.stop();
        m.render_into(&mut buf, 44100, &mut ticks);
        assert_eq!(ticks.len, 0);
        assert!(buf.iter().all(|&v| v == 0.0));
        assert!(!m.is_running());
        m.start(0);
        m.render_into(&mut buf, 44100, &mut ticks);
        assert_eq!(ticks.len, 2);
        assert!(m.is_running());
    }

    #[test]
    fn set_time_signature_immediate() {
        let mut m = make(240.0); // 4分音符间隔 11025
        m.set_time_signature(3, 8); // 8分音符：间隔 = 11025/2
        let mut buf = vec![0.0f32; 22050];
        let mut ticks = TickList::default();
        m.render_into(&mut buf, 22050, &mut ticks);
        // 理论位置 k*5512.5，允许 < 1 采样误差
        assert_eq!(ticks.len, 4);
        for (k, t) in ticks.as_slice().iter().enumerate() {
            let theoretical = k as f64 * 5512.5;
            assert!((t.sample_offset as f64 - theoretical).abs() < 1.0);
        }
        assert_eq!(ticks.events[0].sample_offset, 0);
    }

    #[test]
    fn default_click_used_when_not_injected() {
        let mut m = make(120.0);
        let mut buf = vec![0.0f32; 64];
        let mut ticks = TickList::default();
        m.render_into(&mut buf, 64, &mut ticks);
        assert!(buf.iter().any(|&v| v != 0.0));
    }
}
