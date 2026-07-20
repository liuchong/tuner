import Foundation
import SwiftUI

/// 主题模式。
enum ThemeMode: String, CaseIterable {
    case system, light, dark
    var label: String {
        switch self {
        case .system: return "跟随系统"
        case .light: return "浅色"
        case .dark: return "深色"
        }
    }
}

/// 设置存储（UserDefaults 持久化，与引擎即时同步）。
@MainActor
final class SettingsStore: ObservableObject {
    static let shared = SettingsStore()

    @Published var a4Hz: Double { didSet { save("a4Hz", a4Hz); applyToEngine() } }
    @Published var solfegeSystem: SolfegeSystem {
        didSet { save("solfegeSystem", solfegeSystem.index); applyToEngine() }
    }
    @Published var keyTonicPc: Int { didSet { save("keyTonicPc", keyTonicPc); applyToEngine() } }
    @Published var keyMode: ModeKind {
        didSet { save("keyMode", keyMode.index); applyToEngine() }
    }
    @Published var noiseGateDbfs: Double {
        didSet { save("noiseGateDbfs", noiseGateDbfs); applyToEngine() }
    }
    @Published var themeMode: ThemeMode {
        didSet { save("themeMode", themeMode.rawValue); objectWillChange.send() }
    }
    @Published var hapticsEnabled: Bool {
        didSet { save("hapticsEnabled", hapticsEnabled); TunerHaptics.shared.enabled = hapticsEnabled }
    }
    @Published var proMode: Bool { didSet { save("proMode", proMode) } }
    @Published var temperament: Int {
        didSet { save("temperament", temperament); applyToEngine() }
    }

    private let defaults = UserDefaults.standard

    private init() {
        let d = UserDefaults.standard
        a4Hz = d.object(forKey: "a4Hz") as? Double ?? 440.0
        solfegeSystem = SolfegeSystem.from(index: d.object(forKey: "solfegeSystem") as? Int ?? 2)
        keyTonicPc = d.object(forKey: "keyTonicPc") as? Int ?? 0
        keyMode = ModeKind.from(index: d.object(forKey: "keyMode") as? Int ?? 5)
        noiseGateDbfs = d.object(forKey: "noiseGateDbfs") as? Double ?? -50.0
        themeMode = ThemeMode(rawValue: d.string(forKey: "themeMode") ?? "") ?? .system
        hapticsEnabled = d.object(forKey: "hapticsEnabled") as? Bool ?? true
        proMode = d.object(forKey: "proMode") as? Bool ?? false
        temperament = d.object(forKey: "temperament") as? Int ?? 12
        TunerHaptics.shared.enabled = hapticsEnabled
    }

    private func save(_ key: String, _ value: Any) {
        defaults.set(value, forKey: key)
    }

    var key: KeyMode {
        get { KeyMode(tonicPc: UInt8(keyTonicPc), mode: keyMode) }
        set {
            keyTonicPc = Int(newValue.tonicPc)
            keyMode = newValue.mode
        }
    }

    var effectiveDarkScheme: Bool {
        switch themeMode {
        case .system: return UITraitCollection.current.userInterfaceStyle == .dark
        case .light: return false
        case .dark: return true
        }
    }

    func toTunerConfig() -> TunerConfig {
        TunerConfig(
            sampleRate: CaptureHub.shared.config.sampleRate,
            a4Hz: a4Hz,
            noiseGateDbfs: Float(noiseGateDbfs),
            solfege: solfegeSystem,
            key: key,
            temperament: UInt8(temperament)
        )
    }

    /// 应用设置到运行中的引擎（即时生效）。
    func applyToEngine() {
        CaptureHub.shared.applyConfig(toTunerConfig())
    }
}

extension SolfegeSystem {
    var index: Int {
        switch self {
        case .fixedDo: return 0
        case .movableDo: return 1
        case .numbered: return 2
        case .chinese: return 3
        }
    }

    static func from(index: Int) -> SolfegeSystem {
        switch index {
        case 0: return .fixedDo
        case 1: return .movableDo
        case 2: return .numbered
        default: return .chinese
        }
    }

    var label: String {
        switch self {
        case .fixedDo: return "固定 Do"
        case .movableDo: return "首调 Do"
        case .numbered: return "简谱数字"
        case .chinese: return "宫商角徵羽"
        }
    }
}

extension ModeKind {
    var index: Int {
        switch self {
        case .gong: return 0
        case .shang: return 1
        case .jue: return 2
        case .zhi: return 3
        case .yu: return 4
        case .major: return 5
        case .minor: return 6
        }
    }

    static func from(index: Int) -> ModeKind {
        switch index {
        case 0: return .gong
        case 1: return .shang
        case 2: return .jue
        case 3: return .zhi
        case 4: return .yu
        case 5: return .major
        default: return .minor
        }
    }

    var label: String {
        switch self {
        case .gong: return "宫"
        case .shang: return "商"
        case .jue: return "角"
        case .zhi: return "徵"
        case .yu: return "羽"
        case .major: return "大调"
        case .minor: return "小调"
        }
    }
}

enum Tonic {
    static let labels = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]
}
