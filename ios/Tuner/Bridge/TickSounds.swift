import Foundation

/// 节拍器音色（spec-audio §2 程序化合成 PCM，与 Android TickSounds 同参数）。
enum TickSoundKind: String, CaseIterable {
    case click, beep, bell

    var label: String {
        switch self {
        case .click: return "机械 click"
        case .beep: return "电子 beep"
        case .bell: return "铃声"
        }
    }
}

enum TickSounds {
    static let sampleRate = 44100.0

    static func synthesize(_ kind: TickSoundKind) -> [Float] {
        switch kind {
        case .click: return synthClick()
        case .beep: return synthBeep()
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

    /// 电子 beep：880Hz 正弦，80ms，指数衰减。
    private static func synthBeep() -> [Float] {
        let n = Int(sampleRate * 0.08)
        return (0..<n).map { i in
            let t = Double(i) / sampleRate
            let env = exp(-Double(i) / (Double(n) / 5.0))
            return Float(0.7 * sin(2.0 * .pi * 880.0 * t) * env)
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
}
