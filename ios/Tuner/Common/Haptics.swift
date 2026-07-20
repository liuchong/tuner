import UIKit

/// 触觉反馈（design-system §8）：进入准音区单 tick、准音保持 500ms 双 tick。
@MainActor
final class TunerHaptics {
    static let shared = TunerHaptics()
    private let light = UIImpactFeedbackGenerator(style: .light)

    var enabled: Bool = true

    private init() { light.prepare() }

    func tick() {
        guard enabled else { return }
        light.impactOccurred()
    }

    func doubleTick() {
        guard enabled else { return }
        light.impactOccurred()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.09) { [light] in
            light.impactOccurred()
        }
    }
}
