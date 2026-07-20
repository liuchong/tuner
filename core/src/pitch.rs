//! YIN 音高检测器（规则见 `docs/spec-core.md` §3）。
//!
//! 差函数 + 累积均值归一化（CMNDF）+ 抛物线插值。窗口 2048，阈值 0.15。
//! `feed` 路径零分配、零锁、零 panic：全部缓冲在构造时预分配。

/// 分析窗口长度（采样）。
pub const WINDOW: usize = 2048;
/// YIN CMNDF 阈值。
pub const THRESHOLD: f32 = 0.15;
/// 八度纠错裕量：1/2 周期处 CMNDF 与候选差距小于此值时改用更短周期。
pub const OCTAVE_MARGIN: f32 = 0.05;
/// clarity 门限（低于此值视为无效）。
pub const CLARITY_MIN: f32 = 0.6;
/// 默认噪声门限（dBFS）。
pub const NOISE_GATE_DEFAULT_DBFS: f32 = -50.0;

/// YIN 检测器。单线程使用，内部无可变性共享。
pub struct Yin {
    sample_rate: f32,
    /// 差函数 / CMNDF 缓冲（长度 WINDOW/2）。
    cmndf: Vec<f32>,
    /// 噪声门限对应的 RMS 平方门限（线性）。
    gate_rms2: f32,
}

impl Yin {
    /// 构造。`sample_rate` 必须 > 0，否则 panic（构造期，不在热路径）。
    pub fn new(sample_rate: f32) -> Self {
        assert!(sample_rate > 0.0, "sample_rate 必须为正");
        Self {
            sample_rate,
            cmndf: vec![0.0; WINDOW / 2],
            gate_rms2: 10.0_f32.powf(NOISE_GATE_DEFAULT_DBFS / 10.0),
        }
    }

    /// 设置噪声门限（dBFS）。
    pub fn set_noise_gate(&mut self, dbfs: f32) {
        self.gate_rms2 = 10.0_f32.powf(dbfs / 10.0);
    }

    /// 分析一帧。`pcm` 长度至少 WINDOW（取前 WINDOW 个采样）。
    /// 返回 `Some((freq_hz, clarity))`；静音/噪声/无基频 → `None`。
    ///
    /// 零分配、零锁、无 panic 路径。
    pub fn feed(&mut self, pcm: &[f32]) -> Option<(f32, f32)> {
        if pcm.len() < WINDOW {
            return None;
        }
        let x = &pcm[..WINDOW];
        // 能量门限：RMS 低于噪声门限 → None
        let mut energy = 0.0f32;
        for &v in x {
            energy += v * v;
        }
        if energy / (WINDOW as f32) < self.gate_rms2 {
            return None;
        }

        let half = WINDOW / 2;
        // 1) 差函数 d(tau) = sum_j (x[j] - x[j+tau])^2，tau in [1, half)
        // 2) CMNDF: d'(tau) = d(tau) / ((1/tau) * sum_{k=1..=tau} d(k))
        let cmndf = &mut self.cmndf[..half];
        cmndf[0] = 1.0;
        let mut running = 0.0f32;
        for tau in 1..half {
            let mut d = 0.0f32;
            for j in 0..half {
                let diff = x[j] - x[j + tau];
                d += diff * diff;
            }
            running += d;
            cmndf[tau] = if running > 0.0 {
                d * (tau as f32) / running
            } else {
                1.0
            };
        }

        // 3) 阈值候选 + 八度纠错（见 spec-core §3 八度纠错注）：
        //    第一遍：低于阈值的局部极小值中的最小 CMNDF（best_val）；
        //    第二遍：cmndf < best_val + OCTAVE_MARGIN 的局部极小值中
        //    取最短周期（防次谐波误判；含真周期略高于阈值的折中情形）。
        let mut best_val = f32::INFINITY;
        for t in 2..half.saturating_sub(1) {
            if cmndf[t] < THRESHOLD && cmndf[t] <= cmndf[t - 1] && cmndf[t] <= cmndf[t + 1] {
                best_val = best_val.min(cmndf[t]);
            }
        }
        if !best_val.is_finite() {
            return None;
        }
        let limit = best_val + OCTAVE_MARGIN;
        let mut tau: Option<usize> = None;
        for t in 2..half.saturating_sub(1) {
            if cmndf[t] < limit && cmndf[t] <= cmndf[t - 1] && cmndf[t] <= cmndf[t + 1] {
                tau = Some(t);
                break;
            }
        }
        let tau = tau?;

        // 4) 抛物线插值（tau 邻域三点）得初值
        let parabolic = if tau > 1 && tau < half - 1 {
            let s0 = cmndf[tau - 1];
            let s1 = cmndf[tau];
            let s2 = cmndf[tau + 1];
            let denom = 2.0 * s1 - s2 - s0;
            if denom.abs() > 1e-12 {
                tau as f32 + (s2 - s0) / (2.0 * denom)
            } else {
                tau as f32
            }
        } else {
            tau as f32
        };
        // 5) 精化：优先谐波模型联合最小二乘（近 CRLB，抗噪）；
        //    退化（奇异）时回退到波形 Gauss-Newton 位移拟合
        let coarse = self.sample_rate / parabolic;
        let freq = match harmonic_ls(x, self.sample_rate, coarse) {
            Some(f) if f.is_finite() && f > 0.0 => f,
            _ => {
                let better_tau = refine_period(x, tau, parabolic);
                if better_tau <= 0.0 {
                    return None;
                }
                self.sample_rate / better_tau
            }
        };

        let clarity = 1.0 - cmndf[tau];
        if clarity < CLARITY_MIN {
            return None;
        }
        Some((freq, clarity))
    }
}

/// 谐波个数（基频 + 5 泛音建模）。
const HARMONICS: usize = 6;
/// 线性参数个数（每谐波 cos/sin 两个）。
const LS_P: usize = 2 * HARMONICS;
/// 联合 GN 未知数个数十 ω。
const LS_Q: usize = LS_P + 1;

/// 谐波模型联合最小二乘精化：x[n] = Σ_h a_h·cos(hωn) + b_h·sin(hωn)。
///
/// 首轮固定 ω 线性解 (a,b)；后续轮做联合 Gauss-Newton（未知数 (a,b,ω)），
/// 每轮一遍采样同时累加 GᵀG / Gᵀx / ∂x̂/∂ω 列。精度接近 CRLB，约 4–6 轮收敛。
/// 矩阵奇异等退化情况返回 `None`（调用方回退）。零分配、无 panic。
// 数值内核按数学下标索引最清晰，允许 range-loop lint
#[allow(clippy::needless_range_loop)]
fn harmonic_ls(x: &[f32], sample_rate: f32, f0: f32) -> Option<f32> {
    if !(f0.is_finite() && f0 > 0.0) {
        return None;
    }
    let mut w = 2.0 * core::f64::consts::PI * f0 as f64 / sample_rate as f64;
    if !(w > 0.0 && w < core::f64::consts::PI) {
        return None;
    }
    let mut beta = [0.0f64; LS_P];
    for iter in 0..6 {
        let mut a = [[0.0f64; LS_Q]; LS_Q];
        let mut bvec = [0.0f64; LS_Q];
        let mut cr = [1.0f64; HARMONICS];
        let mut ci = [0.0f64; HARMONICS];
        let mut ur = [0.0f64; HARMONICS];
        let mut ui = [0.0f64; HARMONICS];
        for h in 0..HARMONICS {
            let wh = (h + 1) as f64 * w;
            if wh >= core::f64::consts::PI {
                return None; // 谐波超过奈奎斯特，模型不可用
            }
            ur[h] = wh.cos();
            ui[h] = -wh.sin();
        }
        for (i, &xi32) in x.iter().enumerate() {
            let xi = xi32 as f64;
            let mut col = [0.0f64; LS_P];
            let mut d = 0.0f64;
            for h in 0..HARMONICS {
                col[2 * h] = cr[h];
                col[2 * h + 1] = -ci[h]; // e^{-iωn} 虚部为 -sin
                if iter > 0 {
                    let a_h = beta[2 * h];
                    let b_h = beta[2 * h + 1];
                    d += (h + 1) as f64 * i as f64 * (b_h * cr[h] + a_h * ci[h]);
                }
                let ncr = cr[h] * ur[h] - ci[h] * ui[h];
                ci[h] = cr[h] * ui[h] + ci[h] * ur[h];
                cr[h] = ncr;
            }
            for p in 0..LS_P {
                bvec[p] += col[p] * xi;
                for q in 0..=p {
                    a[p][q] += col[p] * col[q];
                }
            }
            if iter > 0 {
                bvec[LS_P] += d * xi;
                for p in 0..LS_P {
                    a[LS_P][p] += d * col[p];
                }
                a[LS_P][LS_P] += d * d;
            }
        }
        for p in 0..LS_Q {
            for q in 0..p {
                a[q][p] = a[p][q];
            }
        }
        // rhs = bvec − A·[beta; 0]（线性化点在当前 beta）
        let n_eff = if iter == 0 { LS_P } else { LS_Q };
        let mut rhs = bvec;
        for p in 0..n_eff {
            for q in 0..LS_P {
                rhs[p] -= a[p][q] * beta[q];
            }
        }
        let delta = solve_linear(&mut a, &mut rhs, n_eff)?;
        for p in 0..LS_P {
            beta[p] += delta[p];
        }
        if iter > 0 {
            let dw = delta[LS_P].clamp(-w * 0.02, w * 0.02);
            w += dw;
            if dw.abs() < 1e-9 * w {
                break;
            }
        }
    }
    let f = w * sample_rate as f64 / (2.0 * core::f64::consts::PI);
    Some(f as f32)
}

/// 可变有效规模的线性方程组求解（部分主元高斯消元）。奇异返回 `None`。
// 数值内核按数学下标索引最清晰，允许 range-loop lint
#[allow(clippy::needless_range_loop)]
fn solve_linear(a: &mut [[f64; LS_Q]; LS_Q], b: &mut [f64; LS_Q], n: usize) -> Option<[f64; LS_Q]> {
    for col in 0..n {
        let mut piv = col;
        for (r, row) in a.iter().enumerate().take(n).skip(col + 1) {
            if row[col].abs() > a[piv][col].abs() {
                piv = r;
            }
        }
        if a[piv][col].abs() < 1e-14 {
            return None;
        }
        a.swap(col, piv);
        b.swap(col, piv);
        let inv = 1.0 / a[col][col];
        for r in col + 1..n {
            let f = a[r][col] * inv;
            for c in col..n {
                a[r][c] -= f * a[col][c];
            }
            b[r] -= f * b[col];
        }
    }
    let mut x = [0.0f64; LS_Q];
    for col in (0..n).rev() {
        let mut s = b[col];
        for c in col + 1..n {
            s -= a[col][c] * x[c];
        }
        x[col] = s / a[col][col];
    }
    Some(x)
}

/// Gauss-Newton 周期精化：对原波形（线性插值到分数位移）做最小二乘拟合，
/// 求亚采样级周期。`tau0` 为阈值搜索所得整数周期，`parabolic` 为抛物线插值初值。
/// 精化失败（退化输入）时回退到抛物线值。
fn refine_period(x: &[f32], tau0: usize, parabolic: f32) -> f32 {
    let half = WINDOW / 2;
    let _ = tau0;
    let mut tau = parabolic;
    for _ in 0..4 {
        // j + tau + 1 必须 < WINDOW
        if tau < 1.0 || tau >= (WINDOW - half - 1) as f32 {
            return parabolic;
        }
        let mut num = 0.0f32;
        let mut den = 0.0f32;
        for j in 0..half {
            let t = j as f32 + tau;
            let i0 = t as usize;
            let frac = t - i0 as f32;
            let xj = x[i0] + frac * (x[i0 + 1] - x[i0]);
            let slope = x[i0 + 1] - x[i0];
            let r = xj - x[j];
            num += r * slope;
            den += slope * slope;
        }
        if den <= 1e-12 {
            return parabolic;
        }
        let delta = (-num / den).clamp(-1.0, 1.0);
        tau += delta;
        if delta.abs() < 1e-6 {
            break;
        }
    }
    tau
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 简易确定性伪随机（xorshift），避免引入依赖。
    struct XorShift(u64);
    impl XorShift {
        fn next_f32(&mut self) -> f32 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            // [0,1)
            ((self.0 >> 11) as f64 / (1u64 << 53) as f64) as f32
        }
    }

    /// 合成信号：基频 + 3 泛音 + 高斯噪声（SNR 20dB）。
    fn synth(freq: f32, sr: f32, n: usize) -> Vec<f32> {
        let mut rng = XorShift(0x9E3779B97F4A7C15);
        let mut out = vec![0.0f32; n];
        // Box-Muller 用两路均匀
        let mut noise = vec![0.0f32; n];
        let mut i = 0;
        while i < n {
            let u1 = rng.next_f32().max(1e-9);
            let u2 = rng.next_f32();
            let r = (-2.0 * u1.ln()).sqrt();
            noise[i] = r * (2.0 * core::f32::consts::PI * u2).cos();
            if i + 1 < n {
                noise[i + 1] = r * (2.0 * core::f32::consts::PI * u2).sin();
            }
            i += 2;
        }
        for (k, o) in out.iter_mut().enumerate() {
            let t = k as f32 / sr;
            *o = (2.0 * core::f32::consts::PI * freq * t).sin()
                + 0.5 * (2.0 * core::f32::consts::PI * 2.0 * freq * t).sin()
                + 0.25 * (2.0 * core::f32::consts::PI * 3.0 * freq * t).sin()
                + 0.125 * (2.0 * core::f32::consts::PI * 4.0 * freq * t).sin();
        }
        // SNR 20dB：信号功率 / 噪声功率 = 100
        let ps: f32 = out.iter().map(|v| v * v).sum::<f32>() / n as f32;
        let pn: f32 = noise.iter().map(|v| v * v).sum::<f32>() / n as f32;
        let scale = (ps / (pn * 100.0)).sqrt();
        for (o, &nz) in out.iter_mut().zip(noise.iter()) {
            *o = 0.5 * (*o + scale * nz) / 1.875;
        }
        out
    }

    fn cents_error(got: f32, want: f32) -> f32 {
        1200.0 * (got / want).log2()
    }

    #[test]
    fn detects_pure_frequencies() {
        let sr = 44100.0;
        for &f in &[80.0f32, 110.0, 164.8, 220.0, 440.0, 880.0, 1046.5, 1500.0] {
            let pcm = synth(f, sr, WINDOW * 2);
            let mut yin = Yin::new(sr);
            let (got, clarity) = yin.feed(&pcm).unwrap_or_else(|| panic!("{f}Hz 未检出"));
            let err = cents_error(got, f).abs();
            assert!(
                err <= 0.5,
                "{f}Hz: got {got}Hz, 误差 {err} cents (clarity {clarity})"
            );
        }
    }

    #[test]
    fn silence_returns_none() {
        let mut yin = Yin::new(44100.0);
        assert!(yin.feed(&vec![0.0f32; WINDOW]).is_none());
    }

    #[test]
    fn noise_returns_none() {
        let mut rng = XorShift(12345);
        let sr = 44100.0;
        let n = WINDOW;
        let mut pcm = vec![0.0f32; n];
        // 白噪声，幅度远高于门限
        let mut i = 0;
        while i < n {
            let u1 = rng.next_f32().max(1e-9);
            let u2 = rng.next_f32();
            let r = (-2.0 * u1.ln()).sqrt();
            pcm[i] = 0.5 * r * (2.0 * core::f32::consts::PI * u2).cos();
            if i + 1 < n {
                pcm[i + 1] = 0.5 * r * (2.0 * core::f32::consts::PI * u2).sin();
            }
            i += 2;
        }
        let mut yin = Yin::new(sr);
        // 白噪声即便偶发过阈值，clarity 也远低于 0.6
        if let Some((_, clarity)) = yin.feed(&pcm) {
            panic!("纯噪声不应通过 clarity 门限: clarity={clarity}");
        }
    }

    #[test]
    fn quiet_signal_gated() {
        let sr = 44100.0;
        let mut pcm = synth(440.0, sr, WINDOW);
        // 衰减到 -60dBFS 以下
        for v in pcm.iter_mut() {
            *v *= 0.0005;
        }
        let mut yin = Yin::new(sr);
        assert!(yin.feed(&pcm).is_none());
    }

    #[test]
    fn short_input_returns_none() {
        let mut yin = Yin::new(44100.0);
        assert!(yin.feed(&[0.5f32; 100]).is_none());
    }

    /// 线性扫频（啁啾）窗口信号：f(t) = f0 + rate·t。
    fn chirp_window(f0: f32, rate: f32, t_center: f32, sr: f32, noise: bool) -> Vec<f32> {
        let mut rng = XorShift(0xDEADBEEF);
        let mut out = vec![0.0f32; WINDOW];
        for (k, v) in out.iter_mut().enumerate() {
            let t = t_center + k as f32 / sr;
            // 相位 = 2π∫f = 2π(f0·t + rate·t²/2)
            let phase = 2.0 * core::f32::consts::PI * (f0 * t + rate * t * t / 2.0);
            *v = phase.sin();
            if noise {
                *v += 0.05 * (rng.next_f32() - 0.5) * 2.0;
            }
        }
        out
    }

    #[test]
    fn chirp_no_octave_jump() {
        // 200→600Hz / 20s 线性扫频：逐窗检测，误差 ≤ ±50 cents，不允许八度跳变
        let sr = 44100.0;
        let rate = (600.0 - 200.0) / 20.0; // 20 Hz/s
        let mut yin = Yin::new(sr);
        let mut prev = 0.0f32;
        for step in 0..39 {
            let tc = 0.5 + step as f32 * 0.5;
            let f_expect = 200.0 + rate * tc;
            let pcm = chirp_window(200.0, rate, tc, sr, true);
            let (got, _) = yin
                .feed(&pcm)
                .unwrap_or_else(|| panic!("t={tc}s ({f_expect:.0}Hz) 未检出"));
            let err = cents_error(got, f_expect).abs();
            assert!(
                err <= 50.0,
                "t={tc}s: 期望 {f_expect:.1}Hz，实测 {got:.1}Hz，偏差 {err:.1} cents"
            );
            // 不允许次谐波（读数低于期望的 0.75 倍）
            assert!(
                got > f_expect * 0.75,
                "t={tc}s: 疑似八度误判 {got:.1}Hz vs {f_expect:.1}Hz"
            );
            if prev > 0.0 {
                assert!(
                    got > prev * 0.95,
                    "t={tc}s: 读数非单调 {prev:.1} → {got:.1}"
                );
            }
            prev = got;
        }
    }

    #[test]
    fn strong_harmonics_no_subharmonic() {
        // 基频弱、2/3 次谐波强：不误判为更低八度
        let sr = 44100.0;
        for &f in &[110.0f32, 220.0, 330.0, 440.0] {
            let mut pcm = vec![0.0f32; WINDOW * 2];
            for (k, v) in pcm.iter_mut().enumerate() {
                let t = k as f32 / sr;
                *v = 0.25 * (2.0 * core::f32::consts::PI * f * t).sin()
                    + 1.0 * (2.0 * core::f32::consts::PI * 2.0 * f * t).sin()
                    + 0.9 * (2.0 * core::f32::consts::PI * 3.0 * f * t).sin()
                    + 0.4 * (2.0 * core::f32::consts::PI * 4.0 * f * t).sin();
                *v *= 0.3;
            }
            let mut yin = Yin::new(sr);
            let (got, _) = yin.feed(&pcm).unwrap_or_else(|| panic!("{f}Hz 未检出"));
            let err = cents_error(got, f).abs();
            assert!(
                err <= 50.0,
                "{f}Hz（弱基频强谐波）: 实测 {got}Hz，偏差 {err} cents"
            );
        }
    }

    #[test]
    fn clipped_signal_accurate() {
        // 限幅失真（clip 到 ±0.3，扬声器大音量失真场景）：基频准确
        let sr = 44100.0;
        for &f in &[164.8f32, 329.63, 440.0] {
            let mut pcm = vec![0.0f32; WINDOW * 2];
            for (k, v) in pcm.iter_mut().enumerate() {
                let t = k as f32 / sr;
                *v = (2.0 * core::f32::consts::PI * f * t).sin().clamp(-0.3, 0.3);
            }
            let mut yin = Yin::new(sr);
            let (got, clarity) = yin.feed(&pcm).unwrap_or_else(|| panic!("{f}Hz 未检出"));
            let err = cents_error(got, f).abs();
            assert!(
                err <= 50.0,
                "{f}Hz（限幅）: 实测 {got}Hz，偏差 {err} cents (clarity {clarity})"
            );
            assert!(got > f * 0.75, "{f}Hz（限幅）: 疑似八度误判 {got}Hz");
        }
    }

    #[test]
    fn works_at_48k() {
        let sr = 48000.0;
        let pcm = synth(440.0, sr, WINDOW * 2);
        let mut yin = Yin::new(sr);
        let (got, _) = yin.feed(&pcm).unwrap();
        assert!(cents_error(got, 440.0).abs() <= 0.5);
    }
}
