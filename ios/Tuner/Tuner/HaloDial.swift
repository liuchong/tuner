import SwiftUI

enum NeedlePresentation {
    static let rangeCents: Float = 50
    static let followDuration: TimeInterval = 0.05

    static func clampedCents(_ cents: Float) -> Float {
        min(max(cents, -rangeCents), rangeCents)
    }

    static func renderedCents(for cents: Float?) -> [Float] {
        guard let cents else { return [] }
        return [clampedCents(cents)]
    }
}

/// Halo 表盘（design-system §6.1）：外圈数字环 + 刻度带 + 彩色分区弧 +
/// 进度光弧 + 单根锥形光针 + 准音光池；圆心不放任何文字。
///
/// 几何不变量（构图纪律）：指针扫掠区域（弧顶 → pivot 圆点）必须完整落在
/// 本组件矩形内；读数块位于表盘区域 bottom + 16dp 之下，任何偏转角
/// （含 ±50c 满偏）都不会与读数块相交。
struct HaloDial: View {
    @Environment(\.lumen) private var palette
    @Environment(\.isLumenDark) private var isDark

    /// 当前偏差（调用方做快速无回弹动画）；nil = 无信号。
    var cents: Float?
    var clarity: Float = 1.0

    private let rangeCents = NeedlePresentation.rangeCents
    private let inTuneCents: Float = 5
    private let nearCents: Float = 15

    @State private var glowBoost: CGFloat = 1.0
    @State private var fade: CGFloat = 1.0

    nonisolated private func angle(of c: Float) -> Angle {
        let clamped = NeedlePresentation.clampedCents(c)
        return Angle(degrees: Double(270 + (clamped / NeedlePresentation.rangeCents) * 70))
    }

    private var activeColor: Color {
        Lumen.tuneColor(of: cents ?? 0, palette)
    }

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let center = CGPoint(x: w / 2, y: h * 0.88)
            let radius = min(w * 0.42, h * 0.72)
            let alpha = fade * (clarity < 0.6 ? 0.4 : 1.0)

            // 准音光池（仅 Dark）
            if isDark {
                let glowR = radius * 0.85
                ctx.fill(
                    Path(ellipseIn: CGRect(
                        x: center.x - glowR, y: center.y - glowR,
                        width: glowR * 2, height: glowR * 2
                    )),
                    with: .radialGradient(
                        Gradient(colors: [
                            palette.glowIn.opacity(0.24 * glowBoost * alpha), .clear,
                        ]),
                        center: center, startRadius: 0, endRadius: glowR
                    )
                )
            }

            // 圆心留空：极淡内环
            ctx.stroke(
                Path(ellipseIn: CGRect(
                    x: center.x - radius * 0.52, y: center.y - radius * 0.52,
                    width: radius * 1.04, height: radius * 1.04
                )),
                with: .color(palette.lineSubtle.opacity(0.4 * alpha)),
                lineWidth: 1
            )

            // 分区弧（红 ±50→15 / 琥珀 ±15→5 / 绿 ±5）
            func zoneArc(_ from: Float, _ to: Float, _ color: Color, bright: Bool = false) {
                var p = Path()
                p.addArc(
                    center: center, radius: radius,
                    startAngle: angle(of: from), endAngle: angle(of: to),
                    clockwise: false
                )
                ctx.stroke(
                    p,
                    with: .color(color.opacity((bright ? 1.0 : 0.85) * alpha)),
                    style: StrokeStyle(lineWidth: 6, lineCap: .round)
                )
            }
            zoneArc(-rangeCents, -nearCents, palette.tuneOff)
            zoneArc(nearCents, rangeCents, palette.tuneOff)
            zoneArc(-nearCents, -inTuneCents, palette.tuneNear)
            zoneArc(inTuneCents, nearCents, palette.tuneNear)
            zoneArc(-inTuneCents, inTuneCents, palette.tuneIn,
                    bright: cents != nil && abs(cents!) <= inTuneCents)

            // 进度光弧：左端 → 当前位置（端部亮尾部透）
            if let cents {
                let startA = angle(of: -rangeCents)
                let endA = angle(of: cents)
                let segments = 28
                for i in 0..<segments {
                    let a0 = startA + (endA - startA) * (Double(i) / Double(segments))
                    let a1 = startA + (endA - startA) * (Double(i) * 0.8 / Double(segments))
                    guard a1 > a0 else { continue }
                    var p = Path()
                    p.addArc(center: center, radius: radius, startAngle: a0, endAngle: a1, clockwise: false)
                    let t = Double(i) / Double(segments)
                    ctx.stroke(
                        p,
                        with: .color(activeColor.opacity((0.12 + 0.88 * t) * alpha)),
                        style: StrokeStyle(lineWidth: 6, lineCap: .round)
                    )
                }
            }

            // 外环刻度带
            let tickR = radius * 1.10
            var track = Path()
            track.addArc(
                center: center, radius: tickR,
                startAngle: angle(of: -rangeCents), endAngle: angle(of: rangeCents),
                clockwise: false
            )
            ctx.stroke(track, with: .color(palette.lineSubtle), lineWidth: 1.5)

            var c: Float = -rangeCents
            while c <= rangeCents {
                let a = angle(of: c)
                let isZero = c == 0
                let isMajor = Int(c) % 10 == 0
                let len: CGFloat = isZero ? 16 : (isMajor ? 12 : 6)
                let outer = CGPoint(
                    x: center.x + tickR * cos(CGFloat(a.radians)),
                    y: center.y + tickR * sin(CGFloat(a.radians))
                )
                let inner = CGPoint(
                    x: center.x + (tickR - len) * cos(CGFloat(a.radians)),
                    y: center.y + (tickR - len) * sin(CGFloat(a.radians))
                )
                var seg = Path()
                seg.move(to: inner)
                seg.addLine(to: outer)
                let col = isZero
                    ? palette.inkPrimary.opacity(0.9 * alpha)
                    : palette.inkFaint.opacity((isMajor ? 1.0 : 0.4) * alpha)
                ctx.stroke(seg, with: .color(col), lineWidth: (isMajor || isZero) ? 1.5 : 1)
                c += 2
            }

            // 数字环（−50/−25/0/+25/+50）
            let labels = [("−50", -rangeCents), ("−25", -25 as Float), ("0", 0 as Float),
                          ("+25", 25 as Float), ("+50", rangeCents)]
            for (text, lc) in labels {
                let a = angle(of: lc)
                let p = CGPoint(
                    x: center.x + (tickR + 14) * cos(CGFloat(a.radians)),
                    y: center.y + (tickR + 14) * sin(CGFloat(a.radians))
                )
                ctx.draw(
                    Text(text).font(Lumen.caption).foregroundColor(palette.inkFaint.opacity(alpha)),
                    at: p, anchor: .center
                )
            }

            // 单光针：只绘制当前目标，nil 时不绘制。
            for currentCents in NeedlePresentation.renderedCents(for: cents) {
                drawNeedle(ctx: ctx, center: center, radius: radius,
                           angle: angle(of: currentCents), color: activeColor, alpha: alpha)
            }
        }
        .onChange(of: cents) { _, newValue in
            withAnimation(.linear(duration: 0.4)) {
                fade = newValue == nil ? 0.4 : 1.0
            }
            if newValue != nil && abs(newValue!) <= inTuneCents {
                withAnimation(.easeInOut(duration: 0.14)) {
                    glowBoost = 1.6
                } completion: {
                    withAnimation(.easeInOut(duration: 0.14)) {
                        glowBoost = 1.0
                    }
                }
            }
        }
    }

    private func drawNeedle(
        ctx: GraphicsContext, center: CGPoint, radius: CGFloat,
        angle: Angle, color: Color, alpha: CGFloat
    ) {
        guard alpha > 0 else { return }
        let dirX = cos(CGFloat(angle.radians))
        let dirY = sin(CGFloat(angle.radians))
        let tipR = radius * 0.88
        let baseR = radius * 0.10
        let halfW: CGFloat = 3.5
        let perpX = -dirY
        let perpY = dirX
        var path = Path()
        path.move(to: CGPoint(x: center.x + dirX * tipR, y: center.y + dirY * tipR))
        path.addLine(to: CGPoint(
            x: center.x + dirX * baseR + perpX * halfW,
            y: center.y + dirY * baseR + perpY * halfW
        ))
        path.addLine(to: CGPoint(
            x: center.x + dirX * baseR - perpX * halfW,
            y: center.y + dirY * baseR - perpY * halfW
        ))
        path.closeSubpath()
        ctx.fill(path, with: .color(color.opacity(alpha)))
        ctx.fill(
            Path(ellipseIn: CGRect(x: center.x - 5, y: center.y - 5, width: 10, height: 10)),
            with: .color(color.opacity(alpha))
        )
    }
}
