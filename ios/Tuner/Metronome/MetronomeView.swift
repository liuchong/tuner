import SwiftUI

/// BPM 环（design-system §6.7）：大字居中 + 外圈 240° 刻度环（当前位亮点）。
struct BpmRing: View {
    @Environment(\.lumen) private var palette
    var bpm: Double
    var onDrag: (Double) -> Void

    private let bpmMin = 30.0
    private let bpmMax = 250.0

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let center = CGPoint(x: w / 2, y: h * 0.92)
            let radius = min(w / 2, h * 0.92) * 0.9

            func polar(_ angleDeg: Double, _ r: Double) -> CGPoint {
                let rad = angleDeg * .pi / 180
                return CGPoint(
                    x: center.x + r * cos(rad),
                    y: center.y + r * sin(rad)
                )
            }

            func bpmToAngle(_ v: Double) -> Double {
                150 + (v.clamped(to: bpmMin...bpmMax) - bpmMin) / (bpmMax - bpmMin) * 240
            }

            var track = Path()
            track.addArc(
                center: center, radius: radius,
                startAngle: Angle(degrees: 150), endAngle: Angle(degrees: 390),
                clockwise: false
            )
            ctx.stroke(track, with: .color(palette.lineSubtle), lineWidth: 1.5)

            var mark = bpmMin
            while mark <= bpmMax {
                let a = bpmToAngle(mark)
                var seg = Path()
                seg.move(to: polar(a, radius - 6))
                seg.addLine(to: polar(a, radius))
                ctx.stroke(seg, with: .color(palette.inkFaint), lineWidth: 1)
                mark += 10
            }

            let p = polar(bpmToAngle(bpm), radius)
            ctx.fill(
                Path(ellipseIn: CGRect(x: p.x - 9, y: p.y - 9, width: 18, height: 18)),
                with: .color(palette.accent.opacity(0.3))
            )
            ctx.fill(
                Path(ellipseIn: CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10)),
                with: .color(palette.accent)
            )
        }
        .gesture(
            DragGesture()
                .onChanged { value in
                    onDrag(-value.translation.height / 16)
                }
        )
    }
}

/// 摆锤（design-system §6.7）：倒三角摆杆随拍相位正弦摆动、端点微顿、停止归中。
struct Pendulum: View {
    @Environment(\.lumen) private var palette
    var playing: Bool
    var bpm: Double
    var beatUnit: Int

    @State private var angle: Double = 0
    @State private var startTime = ProcessInfo.processInfo.systemUptime

    private let maxAngle = 26.0
    private var periodSec: Double { 60.0 / bpm * (4.0 / Double(beatUnit)) }

    var body: some View {
        Group {
            if playing {
                TimelineView(.animation) { timeline in
                    let t = timeline.date.timeIntervalSince1970 - startTime
                    canvasBody(angle: maxAngle * sin(2.0 * .pi * t / periodSec))
                }
            } else {
                canvasBody(angle: angle)
            }
        }
        .onChange(of: playing) { _, newValue in
            if newValue {
                startTime = ProcessInfo.processInfo.systemUptime
            } else {
                withAnimation(.easeOut(duration: 0.3)) { angle = 0 }
            }
        }
    }

    private func canvasBody(angle: Double) -> some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            let pivot = CGPoint(x: w / 2, y: 8)
            let rodLen = h * 0.72

            // 摆幅参考弧
            var refArc = Path()
            refArc.addArc(
                center: pivot, radius: rodLen,
                startAngle: Angle(degrees: 90 - maxAngle),
                endAngle: Angle(degrees: 90 + maxAngle),
                clockwise: false
            )
            ctx.stroke(refArc, with: .color(palette.lineSubtle), lineWidth: 1)

            let rad = angle * .pi / 180
            let dirX = sin(rad)
            let dirY = cos(rad)
            let bob = CGPoint(x: pivot.x + dirX * rodLen, y: pivot.y + dirY * rodLen)

            let rodColor = playing ? palette.accent : palette.inkFaint
            // 倒三角摆杆
            let baseW: Double = 3
            let perpX = dirY
            let perpY = -dirX
            var path = Path()
            path.move(to: bob)
            path.addLine(to: CGPoint(x: pivot.x + perpX * baseW, y: pivot.y + perpY * baseW))
            path.addLine(to: CGPoint(x: pivot.x - perpX * baseW, y: pivot.y - perpY * baseW))
            path.closeSubpath()
            ctx.fill(path, with: .color(rodColor))
            // 摆锤球
            ctx.fill(
                Path(ellipseIn: CGRect(x: bob.x - 7, y: bob.y - 7, width: 14, height: 14)),
                with: .color(rodColor)
            )
            // 支点
            ctx.fill(
                Path(ellipseIn: CGRect(x: pivot.x - 3, y: pivot.y - 3, width: 6, height: 6)),
                with: .color(palette.inkSecondary)
            )
        }
    }
}
