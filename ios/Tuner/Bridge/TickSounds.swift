import Foundation

/// 节拍器音色（spec-audio §2 程序化合成 PCM，与 Android TickSounds 同参数）。
enum TickSoundKind: String, CaseIterable {
    case click
    case woodBlock
    case beep
    case claves
    case rimshot
    case snare
    case cowbell
    case hiHat
    case clap
    case shaker
    case kick
    case bell

    var label: String {
        switch self {
        case .click: return "机械节拍"
        case .woodBlock: return "木块"
        case .beep: return "电子滴声"
        case .claves: return "拍板"
        case .rimshot: return "边鼓"
        case .snare: return "小鼓"
        case .cowbell: return "牛铃"
        case .hiHat: return "踩镲"
        case .clap: return "拍手"
        case .shaker: return "沙锤"
        case .kick: return "低鼓"
        case .bell: return "铃声"
        }
    }
}

enum TickSounds {
    static let sampleRate = 44100.0

    static func synthesize(_ kind: TickSoundKind) -> [Float] {
        switch kind {
        case .click: return synthClick()
        case .woodBlock: return synthWoodBlock()
        case .beep: return synthBeep()
        case .claves: return synthClaves()
        case .rimshot: return synthRimshot()
        case .snare: return synthSnare()
        case .cowbell: return synthCowbell()
        case .hiHat: return synthHiHat()
        case .clap: return synthClap()
        case .shaker: return synthShaker()
        case .kick: return synthKick()
        case .bell: return synthBell()
        }
    }

    static func buildAll() -> [TickSoundKind: [Float]] {
        Dictionary(uniqueKeysWithValues: TickSoundKind.allCases.map { ($0, synthesize($0)) })
    }

    /// 机械 click：2000Hz 方波，12ms，快速指数衰减。
    private static func synthClick() -> [Float] {
        let n = Int(sampleRate * 0.012)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let square: Double = sin(2.0 * .pi * 2000.0 * t) >= 0 ? 1.0 : -1.0
            let env = exp(-Double(i) / (Double(n) / 4.0))
            return Float(0.8 * square * env)
        }
    }

    /// 木块：820Hz + 1240Hz，90ms，快速双谐振衰减。
    private static func synthWoodBlock() -> [Float] {
        let n = Int(sampleRate * 0.09)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let env = exp(-Double(i) / (Double(n) / 6.0))
            let resonances =
                0.72 * sin(2.0 * .pi * 820.0 * t)
                + 0.28 * sin(2.0 * .pi * 1240.0 * t)
            return Float(0.85 * resonances * env)
        }
    }

    /// 电子 beep：880Hz 正弦，80ms，指数衰减。
    private static func synthBeep() -> [Float] {
        let n = Int(sampleRate * 0.08)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let env = exp(-Double(i) / (Double(n) / 5.0))
            return Float(0.7 * sin(2.0 * .pi * 880.0 * t) * env)
        }
    }

    /// 拍板：2400Hz + 3600Hz，50ms，极短谐振。
    private static func synthClaves() -> [Float] {
        let n = Int(sampleRate * 0.05)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let env = exp(-Double(i) / (Double(n) / 5.0))
            let resonances =
                0.62 * sin(2.0 * .pi * 2400.0 * t)
                + 0.38 * sin(2.0 * .pi * 3600.0 * t)
            return Float(0.88 * resonances * env)
        }
    }

    /// 边鼓：1800Hz 谐振叠加确定性噪声，60ms。
    private static func synthRimshot() -> [Float] {
        let n = Int(sampleRate * 0.06)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let env = exp(-Double(i) / (Double(n) / 6.0))
            let body = 0.55 * sin(2.0 * .pi * 1800.0 * t)
            let strike = 0.45 * noise(i)
            return Float(0.88 * (body + strike) * env)
        }
    }

    /// 小鼓：190Hz 鼓皮叠加宽带噪声，120ms。
    private static func synthSnare() -> [Float] {
        let n = Int(sampleRate * 0.12)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let env = exp(-Double(i) / (Double(n) / 3.5))
            let body = 0.28 * sin(2.0 * .pi * 190.0 * t)
            let wires = 0.72 * noise(i)
            return Float(0.84 * (body + wires) * env)
        }
    }

    /// 牛铃：540Hz + 845Hz 非整数倍谐振，180ms。
    private static func synthCowbell() -> [Float] {
        let n = Int(sampleRate * 0.18)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let env = exp(-Double(i) / (Double(n) / 3.0))
            let resonances =
                0.55 * sin(2.0 * .pi * 540.0 * t)
                + 0.45 * sin(2.0 * .pi * 845.0 * t)
            return Float(0.88 * resonances * env)
        }
    }

    /// 踩镲：确定性噪声叠加 6kHz / 9.3kHz 金属谐振，100ms。
    private static func synthHiHat() -> [Float] {
        let n = Int(sampleRate * 0.10)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let env = exp(-Double(i) / (Double(n) / 4.0))
            let metal =
                0.45 * noise(i)
                + 0.30 * sin(2.0 * .pi * 6000.0 * t)
                + 0.25 * sin(2.0 * .pi * 9300.0 * t)
            return Float(0.78 * metal * env)
        }
    }

    /// 拍手：三次紧邻噪声脉冲叠加短尾音，150ms。
    private static func synthClap() -> [Float] {
        let n = Int(sampleRate * 0.15)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let bursts =
                decayAfter(t, offset: 0.0, decaySeconds: 0.006)
                + decayAfter(t, offset: 0.018, decaySeconds: 0.006)
                + decayAfter(t, offset: 0.036, decaySeconds: 0.007)
                + 0.35 * decayAfter(t, offset: 0.045, decaySeconds: 0.035)
            return Float(0.82 * noise(i) * min(bursts, 1.0))
        }
    }

    /// 沙锤：8ms 起音、短衰减的高频差分噪声，120ms。
    private static func synthShaker() -> [Float] {
        let n = Int(sampleRate * 0.12)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let attack = min(max(t / 0.008, 0.0), 1.0)
            let env = attack * exp(-Double(i) / (Double(n) / 2.5))
            let previous = i == 0 ? 0.0 : noise(i - 1)
            let highNoise = (noise(i) - previous) * 0.5
            return Float(0.78 * highNoise * env)
        }
    }

    /// 低鼓：120Hz 扫至 48Hz，叠加极短击槌噪声，180ms。
    private static func synthKick() -> [Float] {
        let duration = 0.18
        let n = Int(sampleRate * duration)
        let startHz = 120.0
        let endHz = 48.0
        let sweepPerSecond = (endHz - startHz) / duration
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let phase = 2.0 * Double.pi
                * (startHz * t + 0.5 * sweepPerSecond * t * t)
            let bodyEnv = exp(-Double(i) / (Double(n) / 4.0))
            let strikeEnv = exp(-Double(i) / (Double(n) / 40.0))
            let body = 0.78 * sin(phase) * bodyEnv
            let strike = 0.12 * noise(i) * strikeEnv
            return Float(body + strike)
        }
    }

    /// 铃声：基频 1568Hz + 泛音列（碰铃感），250ms 指数衰减。
    private static func synthBell() -> [Float] {
        let n = Int(sampleRate * 0.25)
        let partials: [(Double, Double)] = [
            (1568.0, 1.0), (2093.0, 0.55), (2637.0, 0.32), (3520.0, 0.18),
        ]
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let env = exp(-Double(i) / (Double(n) / 4.0))
            let v = partials.reduce(0.0) { $0 + $1.1 * sin(2.0 * .pi * $1.0 * t) } / 2.05
            return Float(0.85 * v * env)
        }
    }

    private static func decayAfter(
        _ time: Double,
        offset: Double,
        decaySeconds: Double
    ) -> Double {
        time < offset ? 0.0 : exp(-(time - offset) / decaySeconds)
    }

    /// 固定 xorshift 序列；两端使用同一算法，避免随机音色和测试漂移。
    private static func noise(_ index: Int) -> Double {
        var value = UInt32(index + 1)
        value ^= value << 13
        value ^= value >> 17
        value ^= value << 5
        return Double(value & 0xFFFF) / 32767.5 - 1.0
    }
}
