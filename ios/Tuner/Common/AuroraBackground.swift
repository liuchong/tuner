import SwiftUI

/// 极光背景（design-system v4 §3.3）：主极光 = 当前音准语义色（无信号 accent 蓝），
/// 顶部偏左大径向渐变，随 cents 缓慢漂移、6s 呼吸；辅助 accent 极光右下 5%。
/// Light 主题用极浅渐变替代。
struct AuroraBackground<Content: View>: View {
    @Environment(\.lumen) private var palette
    @Environment(\.isLumenDark) private var isDark
    var tuneCents: Float?
    var content: () -> Content

    @State private var breathPhase = false

    private var mainColor: Color {
        if let c = tuneCents { return Lumen.tuneColor(of: c, palette) }
        return palette.accent
    }

    var body: some View {
        ZStack {
            palette.bgCanvas.ignoresSafeArea()
            if isDark {
                TimelineView(.periodic(from: .now, by: 0.1)) { timeline in
                    let t = timeline.date.timeIntervalSince1970
                    let breath = sin(2 * .pi * t / 6.0) * 0.03 // 6s 呼吸 ±3%
                    let drift = Double(tuneCents ?? 0) / 50.0 * 0.03 // ±3% 屏宽漂移
                    Canvas { ctx, size in
                        let baseAlpha = (tuneCents != nil ? 0.13 : 0.10) + breath
                        let center = CGPoint(x: size.width * (0.30 + drift), y: size.height * 0.05)
                        let mainRect = CGRect(
                            x: center.x - size.width * 1.2,
                            y: center.y - size.width * 1.2,
                            width: size.width * 2.4,
                            height: size.width * 2.4
                        )
                        ctx.fill(
                            Path(ellipseIn: mainRect),
                            with: .radialGradient(
                                Gradient(colors: [mainColor.opacity(max(0, baseAlpha)), .clear]),
                                center: center,
                                startRadius: 0,
                                endRadius: size.width * 1.2
                            )
                        )
                        let auxCenter = CGPoint(x: size.width * 0.95, y: size.height * 0.95)
                        let auxRect = CGRect(
                            x: auxCenter.x - size.width * 0.9,
                            y: auxCenter.y - size.width * 0.9,
                            width: size.width * 1.8,
                            height: size.width * 1.8
                        )
                        ctx.fill(
                            Path(ellipseIn: auxRect),
                            with: .radialGradient(
                                Gradient(colors: [palette.accent.opacity(0.05), .clear]),
                                center: auxCenter,
                                startRadius: 0,
                                endRadius: size.width * 0.9
                            )
                        )
                    }
                }
            } else {
                LinearGradient(
                    colors: [mainColor.opacity(0.04), .clear],
                    startPoint: .top,
                    endPoint: .center
                )
                .ignoresSafeArea()
            }
            content()
        }
        .animation(.easeInOut(duration: 0.3), value: tuneCents.map { Lumen.tuneColor(of: $0, palette) })
    }
}
