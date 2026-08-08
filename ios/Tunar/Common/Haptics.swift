#if os(iOS)
import UIKit
#else
import Foundation
#endif

/// 触觉反馈（design-system §8）：进入准音区单 tick、准音保持 500ms 双 tick。
@MainActor
final class TunarHaptics {
    static let shared = TunarHaptics()
    #if os(iOS)
    private let light = UIImpactFeedbackGenerator(style: .light)
    #endif

    var enabled: Bool = true

    private init() {
        #if os(iOS)
        light.prepare()
        #endif
    }

    func tick() {
        guard enabled else { return }
        #if os(iOS)
        light.impactOccurred()
        #endif
    }

    func doubleTick() {
        guard enabled else { return }
        #if os(iOS)
        light.impactOccurred()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.09) { [light] in
            light.impactOccurred()
        }
        #endif
    }
}
