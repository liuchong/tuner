//! FFT 频谱与泛音/和弦分析（`docs/spec-core.md` §4a）。
//!
//! Hann 窗 + 2048 点实数 FFT（radix-2，旋转因子与位逆序表构造时预计算），
//! 输出对数频率轴 60–2400Hz 共 64 bin 的 dBFS 幅值（每 bin 取覆盖频带内最大幅值，
//! -80~0 钳制）。泛音检测：局部极大 + 超噪声底 12dB（噪声底 = bin 中位数），定容 8。
//! 和弦模板匹配：先完全匹配再子集匹配。feed 路径零分配、零 panic（全部缓冲构造时预分配）。

/// FFT 点数。
pub const FFT_N: usize = 2048;
/// 对数频谱 bin 数。
pub const SPECTRUM_BINS: usize = 64;
/// 对数轴下限（Hz）。
pub const F_MIN: f32 = 60.0;
/// 对数轴上限（Hz）。
pub const F_MAX: f32 = 2400.0;
/// dBFS 下限（钳制）。
pub const DB_FLOOR: f32 = -80.0;
/// 泛音最大个数。
pub const MAX_PARTIALS: usize = 8;
/// 泛音显著峰：超过噪声底的 dB 数。
pub const PEAK_ABOVE_FLOOR_DB: f32 = 12.0;
/// 泛音匹配基频整数倍的容差（cents）。
pub const HARMONIC_TOLERANCE_CENTS: f64 = 30.0;

/// 一个检测到的泛音/独立音（内核表示，定长无分配）。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PartialInfo {
    /// 频率（Hz，抛物线精化）。
    pub freq_hz: f64,
    /// 幅值（dBFS）。
    pub magnitude_db: f32,
    /// 泛音序号：0=独立音；1=基频；2,3,4…=基频泛音。
    pub harmonic_index: u8,
    /// 独立音时的最近 12-TET MIDI（harmonic_index == 0 时有效）。
    pub midi: i32,
    /// 独立音时相对最近 12-TET 音的 cents（[-50,50)）。
    pub cents_off: f64,
}

/// 频谱分析器。全部缓冲构造时预分配。
pub struct Spectrum {
    sample_rate: f32,
    /// Hann 窗系数。
    hann: Vec<f32>,
    /// 位逆序置换表。
    rev: Vec<u32>,
    /// 旋转因子 W_N^k（k ∈ [0, N/2)），交错存 cos/sin。
    twiddles: Vec<f32>,
    /// 时域加窗缓冲（实部）。
    re: Vec<f32>,
    /// 虚部缓冲。
    im: Vec<f32>,
    /// 幅值缓冲（k ∈ [0, N/2]）。
    mag: Vec<f32>,
    /// 每个对数 bin 覆盖的 FFT bin 范围 [k_lo, k_hi)。
    bin_ranges: Vec<(u32, u32)>,
    /// 每个对数 bin 的（dB 幅值, 最大 FFT bin）。
    bins: Vec<(f32, u32)>,
    /// 输出：64 bin dBFS。
    out: Vec<f32>,
}

impl Spectrum {
    /// 构造（预计算 Hann 窗、位逆序表、旋转因子、对数 bin 范围）。
    pub fn new(sample_rate: f32) -> Self {
        assert!(sample_rate > 0.0, "sample_rate 必须为正");
        let n = FFT_N;
        // Hann 窗：w[k] = 0.5(1 - cos(2πk/(N-1)))
        let hann: Vec<f32> = (0..n)
            .map(|k| 0.5 * (1.0 - (2.0 * core::f32::consts::PI * k as f32 / (n - 1) as f32).cos()))
            .collect();
        // 位逆序表
        let bits = n.trailing_zeros();
        let rev: Vec<u32> = (0..n as u32)
            .map(|i| i.reverse_bits() >> (32 - bits))
            .collect();
        // 旋转因子 W_N^k = e^{-2πik/N}，k ∈ [0, N/2)
        let mut twiddles = Vec::with_capacity(n);
        for k in 0..n / 2 {
            let a = -2.0 * core::f32::consts::PI * k as f32 / n as f32;
            twiddles.push(a.cos());
            twiddles.push(a.sin());
        }
        // 对数 bin 范围：edge_i = F_MIN * (F_MAX/F_MIN)^(i/BINS)
        let ratio = (F_MAX / F_MIN).powf(1.0 / SPECTRUM_BINS as f32);
        let bin_width = sample_rate / n as f32;
        let bin_ranges: Vec<(u32, u32)> = (0..SPECTRUM_BINS)
            .map(|i| {
                let lo = (F_MIN * ratio.powf(i as f32) / bin_width) as u32;
                let hi = ((F_MIN * ratio.powf((i + 1) as f32) / bin_width) as u32)
                    .max(lo + 1)
                    .min(n as u32 / 2);
                (lo, hi.max(lo + 1))
            })
            .collect();
        Self {
            sample_rate,
            hann,
            rev,
            twiddles,
            re: vec![0.0; n],
            im: vec![0.0; n],
            mag: vec![0.0; n / 2 + 1],
            bin_ranges,
            bins: vec![(DB_FLOOR, 0); SPECTRUM_BINS],
            out: vec![DB_FLOOR; SPECTRUM_BINS],
        }
    }

    /// 计算一帧频谱。`pcm` 长度 ≥ FFT_N（取前 FFT_N 个采样）。
    /// 返回 64 bin dBFS（-80~0）。零分配、零 panic。
    // 位逆序重排按下标最直观，允许 range-loop lint
    #[allow(clippy::needless_range_loop)]
    pub fn feed(&mut self, pcm: &[f32]) -> &[f32] {
        let n = FFT_N;
        if pcm.len() < n {
            return &self.out;
        }
        // 加窗 + 位逆序重排
        for i in 0..n {
            self.re[self.rev[i] as usize] = pcm[i] * self.hann[i];
            self.im[i] = 0.0;
        }
        // radix-2 DIT FFT
        let mut len = 2;
        while len <= n {
            let half = len / 2;
            let step = n / len;
            let mut start = 0;
            while start < n {
                let mut j = 0;
                while j < half {
                    let t = j * step;
                    let wr = self.twiddles[2 * t];
                    let wi = self.twiddles[2 * t + 1];
                    let (a, b) = (start + j, start + j + half);
                    let tr = self.re[b] * wr - self.im[b] * wi;
                    let ti = self.re[b] * wi + self.im[b] * wr;
                    self.re[b] = self.re[a] - tr;
                    self.im[b] = self.im[a] - ti;
                    self.re[a] += tr;
                    self.im[a] += ti;
                    j += 1;
                }
                start += len;
            }
            len *= 2;
        }
        // 幅值（dBFS：幅值为 A 的正弦在 bin 中心时 ≈ 0dB）
        for k in 0..=n / 2 {
            let m = (self.re[k] * self.re[k] + self.im[k] * self.im[k]).sqrt();
            let db = 20.0 * (m * 4.0 / n as f32).max(1e-10).log10();
            self.mag[k] = db.clamp(DB_FLOOR, 0.0);
        }
        // 对数 bin：覆盖频带内最大幅值 + argmax FFT bin
        for i in 0..SPECTRUM_BINS {
            let (lo, hi) = self.bin_ranges[i];
            let mut best = DB_FLOOR;
            let mut best_k = lo;
            for k in lo..hi {
                if self.mag[k as usize] > best {
                    best = self.mag[k as usize];
                    best_k = k;
                }
            }
            self.bins[i] = (best, best_k);
            self.out[i] = best;
        }
        &self.out
    }

    /// 检测泛音（局部极大 + 超噪声底 12dB，按幅值取前 MAX_PARTIALS 个）。
    /// `f0` 为 YIN 基频（用于标 harmonic_index）；`a4` 为校准（独立音命名）。
    /// 返回写入 partials 的个数。零分配。
    pub fn detect_partials(
        &mut self,
        f0: f64,
        a4: f64,
        partials: &mut [PartialInfo; MAX_PARTIALS],
    ) -> usize {
        if !(f0.is_finite() && f0 > 0.0) {
            return 0;
        }
        // 噪声底 = bin 中位数（定长数组排序）
        let mut sorted = [DB_FLOOR; SPECTRUM_BINS];
        for (i, s) in sorted.iter_mut().enumerate() {
            *s = self.out[i];
        }
        sorted.sort_by(|a, b| a.partial_cmp(b).unwrap_or(core::cmp::Ordering::Equal));
        let floor = sorted[SPECTRUM_BINS / 2];
        let threshold = floor + PEAK_ABOVE_FLOOR_DB;
        // 收集显著峰（±2 bin 邻域极大，抑制 Hann 主瓣边缘伪峰），按幅值插入定长数组（降序）
        let mut count = 0usize;
        for i in 2..SPECTRUM_BINS - 2 {
            let (db, k) = self.bins[i];
            if db < threshold {
                continue;
            }
            let is_peak = db > self.bins[i - 1].0
                && db > self.bins[i - 2].0
                && db >= self.bins[i + 1].0
                && db >= self.bins[i + 2].0;
            if is_peak {
                let info = self.make_partial(k, db, f0, a4);
                // 按幅值降序插入
                if count < MAX_PARTIALS {
                    let mut j = count;
                    while j > 0 && partials[j - 1].magnitude_db < info.magnitude_db {
                        partials[j] = partials[j - 1];
                        j -= 1;
                    }
                    partials[j] = info;
                    count += 1;
                } else if partials[MAX_PARTIALS - 1].magnitude_db < info.magnitude_db {
                    let mut j = MAX_PARTIALS - 1;
                    while j > 0 && partials[j - 1].magnitude_db < info.magnitude_db {
                        partials[j] = partials[j - 1];
                        j -= 1;
                    }
                    partials[j] = info;
                }
            }
        }
        count
    }

    /// 由 FFT bin 构造 PartialInfo（抛物线精化频率）。
    fn make_partial(&self, k: u32, db: f32, f0: f64, a4: f64) -> PartialInfo {
        let bin_width = self.sample_rate / FFT_N as f32;
        // 抛物线插值（相邻 bin 幅值）
        let k = k as usize;
        let frac = if k > 0 && k < FFT_N / 2 {
            let s0 = self.mag[k - 1];
            let s1 = self.mag[k];
            let s2 = self.mag[k + 1];
            let denom = 2.0 * s1 - s2 - s0;
            if denom.abs() > 1e-9 {
                (k as f32 + (s2 - s0) / (2.0 * denom)).clamp(k as f32 - 0.5, k as f32 + 0.5)
            } else {
                k as f32
            }
        } else {
            k as f32
        };
        let freq = (frac * bin_width) as f64;
        // 泛音匹配：|cents(freq, k·f0)| ≤ 30，k ∈ 1..=8
        let mut harmonic = 0u8;
        for idx in 1..=8u8 {
            let cents = 1200.0 * (freq / (f0 * idx as f64)).log2();
            if cents.abs() <= HARMONIC_TOLERANCE_CENTS {
                harmonic = idx;
                break;
            }
        }
        let (midi, cents_off) = if harmonic == 0 {
            match crate::note::analyze(freq, a4) {
                Some(info) => (info.midi, info.cents_off),
                None => (0, 0.0),
            }
        } else {
            (0, 0.0)
        };
        PartialInfo {
            freq_hz: freq,
            magnitude_db: db,
            harmonic_index: harmonic,
            midi,
            cents_off,
        }
    }
}

/// 和弦模板（相对根音的半音偏移，按音数降序便于长子集优先）。
const CHORD_TEMPLATES: &[(&str, &[u8])] = &[
    ("maj7", &[0, 4, 7, 11]),
    ("m7", &[0, 3, 7, 10]),
    ("7", &[0, 4, 7, 10]),
    ("add9", &[0, 2, 4, 7]),
    ("maj", &[0, 4, 7]),
    ("min", &[0, 3, 7]),
    ("sus4", &[0, 5, 7]),
    ("5", &[0, 7]),
];

/// 和弦匹配：独立音级（含基频）≥3 时先完全匹配再子集匹配。
/// `pcs` 为 pitch class 位掩码（bit i = pc i）。返回和弦名（如 "Cmaj"）。
pub fn match_chord(pcs: u16) -> Option<&'static str> {
    if pcs.count_ones() < 3 {
        return None;
    }
    let mut subset_best: Option<&'static str> = None;
    for root in 0..12u16 {
        // 模板音级掩码（相对 root 平移）
        for &(suffix, intervals) in CHORD_TEMPLATES {
            let mut mask = 0u16;
            for &iv in intervals {
                mask |= 1 << ((root + iv as u16) % 12);
            }
            // 完全匹配：直接命中
            if mask == pcs {
                return Some(chord_name(root, suffix));
            }
            // 子集匹配：模板 ⊆ 音集（记录第一个，模板已按大小降序）
            if subset_best.is_none() && mask & pcs == mask {
                subset_best = Some(chord_name(root, suffix));
            }
        }
    }
    subset_best
}

/// 和弦名拼接（定长缓冲，返回 &'static 由调用方转换）。
fn chord_name(root: u16, suffix: &'static str) -> &'static str {
    // 常见组合的静态表避免分配（root 0-11 × 模板后缀）
    match (root, suffix) {
        (0, "maj") => "Cmaj",
        (0, "min") => "Cmin",
        (0, "5") => "C5",
        (0, "7") => "C7",
        (0, "maj7") => "Cmaj7",
        (0, "m7") => "Cm7",
        (0, "sus4") => "Csus4",
        (0, "add9") => "Cadd9",
        (2, "maj") => "Dmaj",
        (2, "min") => "Dmin",
        (2, "5") => "D5",
        (2, "7") => "D7",
        (2, "maj7") => "Dmaj7",
        (2, "m7") => "Dm7",
        (2, "sus4") => "Dsus4",
        (2, "add9") => "Dadd9",
        (4, "maj") => "Emaj",
        (4, "min") => "Emin",
        (4, "5") => "E5",
        (4, "7") => "E7",
        (4, "maj7") => "Emaj7",
        (4, "m7") => "Em7",
        (4, "sus4") => "Esus4",
        (4, "add9") => "Eadd9",
        (5, "maj") => "Fmaj",
        (5, "min") => "Fmin",
        (5, "5") => "F5",
        (5, "7") => "F7",
        (5, "maj7") => "Fmaj7",
        (5, "m7") => "Fm7",
        (5, "sus4") => "Fsus4",
        (5, "add9") => "Fadd9",
        (7, "maj") => "Gmaj",
        (7, "min") => "Gmin",
        (7, "5") => "G5",
        (7, "7") => "G7",
        (7, "maj7") => "Gmaj7",
        (7, "m7") => "Gm7",
        (7, "sus4") => "Gsus4",
        (7, "add9") => "Gadd9",
        (9, "maj") => "Amaj",
        (9, "min") => "Amin",
        (9, "5") => "A5",
        (9, "7") => "A7",
        (9, "maj7") => "Amaj7",
        (9, "m7") => "Am7",
        (9, "sus4") => "Asus4",
        (9, "add9") => "Aadd9",
        (1, "maj") => "C#maj",
        (1, "min") => "C#min",
        (1, "5") => "C#5",
        (1, "7") => "C#7",
        (1, "maj7") => "C#maj7",
        (1, "m7") => "C#m7",
        (1, "sus4") => "C#sus4",
        (1, "add9") => "C#add9",
        (3, "maj") => "D#maj",
        (3, "min") => "D#min",
        (3, "5") => "D#5",
        (3, "7") => "D#7",
        (3, "maj7") => "D#maj7",
        (3, "m7") => "D#m7",
        (3, "sus4") => "D#sus4",
        (3, "add9") => "D#add9",
        (6, "maj") => "F#maj",
        (6, "min") => "F#min",
        (6, "5") => "F#5",
        (6, "7") => "F#7",
        (6, "maj7") => "F#maj7",
        (6, "m7") => "F#m7",
        (6, "sus4") => "F#sus4",
        (6, "add9") => "F#add9",
        (8, "maj") => "G#maj",
        (8, "min") => "G#min",
        (8, "5") => "G#5",
        (8, "7") => "G#7",
        (8, "maj7") => "G#maj7",
        (8, "m7") => "G#m7",
        (8, "sus4") => "G#sus4",
        (8, "add9") => "G#add9",
        (10, "maj") => "A#maj",
        (10, "min") => "A#min",
        (10, "5") => "A#5",
        (10, "7") => "A#7",
        (10, "maj7") => "A#maj7",
        (10, "m7") => "A#m7",
        (10, "sus4") => "A#sus4",
        (10, "add9") => "A#add9",
        (11, "maj") => "Bmaj",
        (11, "min") => "Bmin",
        (11, "5") => "B5",
        (11, "7") => "B7",
        (11, "maj7") => "Bmaj7",
        (11, "m7") => "Bm7",
        (11, "sus4") => "Bsus4",
        (11, "add9") => "Badd9",
        _ => "?",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SR: f32 = 44100.0;

    /// 合成多正弦叠加信号。
    fn synth(components: &[(f32, f32)], n: usize) -> Vec<f32> {
        let mut out = vec![0.0f32; n];
        for (i, v) in out.iter_mut().enumerate() {
            let t = i as f32 / SR;
            *v = components
                .iter()
                .map(|(f, a)| a * (2.0 * core::f32::consts::PI * f * t).sin())
                .sum();
        }
        out
    }

    #[test]
    fn spectrum_peak_bin_at_440() {
        let pcm = synth(&[(440.0, 1.0)], FFT_N * 2);
        let mut sp = Spectrum::new(SR);
        let bins = sp.feed(&pcm).to_vec();
        // 峰值 bin 应对应 440Hz 的对数位置
        let ratio = (F_MAX / F_MIN).powf(1.0 / SPECTRUM_BINS as f32);
        let expect = (440.0f32 / F_MIN).log(ratio) as usize;
        let (peak, _) = bins
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.partial_cmp(b.1).unwrap())
            .unwrap();
        assert!(
            (peak as i32 - expect as i32).abs() <= 1,
            "峰值 bin {peak}，期望 {expect} 附近"
        );
        assert!(bins[peak] > -10.0, "满幅正弦应接近 0dB: {}", bins[peak]);
    }

    #[test]
    fn partials_harmonics_labeled() {
        // 基频 220 + 3 泛音
        let pcm = synth(
            &[(220.0, 1.0), (440.0, 0.6), (660.0, 0.4), (880.0, 0.25)],
            FFT_N * 2,
        );
        let mut sp = Spectrum::new(SR);
        sp.feed(&pcm);
        let mut partials = [PartialInfo {
            freq_hz: 0.0,
            magnitude_db: -80.0,
            harmonic_index: 0,
            midi: 0,
            cents_off: 0.0,
        }; MAX_PARTIALS];
        let n = sp.detect_partials(220.0, 440.0, &mut partials);
        assert!(n >= 4, "应检出 ≥4 个泛音，实际 {n}");
        let idxs: Vec<u8> = partials[..4].iter().map(|p| p.harmonic_index).collect();
        // 前 4 强应为 1/2/3/4 次泛音
        for (k, want) in [(1u8, 1u8), (2, 2), (3, 3), (4, 4)] {
            assert!(idxs.contains(&want), "缺少 {k} 次泛音标注: {idxs:?}");
        }
    }

    #[test]
    fn chord_c_major() {
        // C4 + E4 + G4 三音
        let pcm = synth(&[(261.63, 0.6), (329.63, 0.6), (392.0, 0.6)], FFT_N * 2);
        let mut sp = Spectrum::new(SR);
        sp.feed(&pcm);
        let mut partials = [PartialInfo {
            freq_hz: 0.0,
            magnitude_db: -80.0,
            harmonic_index: 0,
            midi: 0,
            cents_off: 0.0,
        }; MAX_PARTIALS];
        // f0 取 C4（YIN 会检出三音之一）
        let n = sp.detect_partials(261.63, 440.0, &mut partials);
        assert!(n >= 3, "应检出 ≥3 个音，实际 {n}");
        // 收集音级（基频 C 也计入）
        let mut pcs = 1u16 << 0; // C
        for p in partials.iter().take(n) {
            if p.harmonic_index == 0 {
                pcs |= 1 << crate::note::midi_to_pc(p.midi);
            }
        }
        let chord = match_chord(pcs);
        assert!(chord.is_some(), "应识别出和弦: pcs={pcs:b}");
        let name = chord.unwrap();
        assert!(
            name.starts_with('C') && name.contains("maj"),
            "应为 C maj 类: {name}"
        );
    }

    #[test]
    fn chord_single_tone_none() {
        // 单音 A4 → 音级数 < 3 → None
        let pcs = 1u16 << 9;
        assert!(match_chord(pcs).is_none());
        // 双音 C+G → None
        assert!(match_chord((1 << 0) | (1 << 7)).is_none());
    }

    #[test]
    fn chord_templates_exact_and_subset() {
        // 完全匹配：C E G → Cmaj
        assert_eq!(match_chord((1 << 0) | (1 << 4) | (1 << 7)), Some("Cmaj"));
        // 子集匹配：C E G B → 有 Cmaj7 完全匹配优先
        let mask = (1 << 0) | (1 << 4) | (1 << 7) | (1 << 11);
        assert_eq!(match_chord(mask), Some("Cmaj7"));
        // 子集：C E G A（无 maj7 模板）→ add9? 模板 add9 = 0,2,4,7 不含 A(9)…
        // C E G + D → Cadd9 完全匹配
        let mask2 = (1 << 0) | (1 << 2) | (1 << 4) | (1 << 7);
        assert_eq!(match_chord(mask2), Some("Cadd9"));
    }
}
