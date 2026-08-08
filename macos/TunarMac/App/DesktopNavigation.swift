import Foundation

enum DesktopSection: String, CaseIterable, Identifiable {
    case tuner
    case instruments
    case analysis
    case metronome
    case settings

    static let defaultSelection: DesktopSection = .tuner

    var id: String { rawValue }

    var title: String {
        switch self {
        case .tuner: "调音"
        case .instruments: "乐器"
        case .analysis: "专业分析"
        case .metronome: "节拍器"
        case .settings: "设置"
        }
    }

    var systemImage: String {
        switch self {
        case .tuner: "tuningfork"
        case .instruments: "pianokeys"
        case .analysis: "waveform.path.ecg"
        case .metronome: "metronome"
        case .settings: "gearshape"
        }
    }
}

enum DesktopNavigation {
    static func openAnalysis(from _: DesktopSection) -> DesktopSection {
        .analysis
    }
}

enum DesktopLayout {
    static let twoColumnBreakpoint: Double = 1_100

    static func columns(for width: Double) -> Int {
        width >= twoColumnBreakpoint ? 2 : 1
    }
}

enum DesktopCaptureAction: Equatable {
    case acquire
    case release
}

enum DesktopCaptureLifecycle {
    static func action(isWindowActive: Bool) -> DesktopCaptureAction {
        isWindowActive ? .acquire : .release
    }
}
