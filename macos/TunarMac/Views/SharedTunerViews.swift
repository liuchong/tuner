import SwiftUI

struct DesktopPitchGauge: View {
    let cents: Double?

    var body: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height * 0.90)
            let radius = min(size.width * 0.43, size.height * 0.82)
            let start = -140.0
            let span = 100.0

            func point(_ angle: Double, radius r: Double) -> CGPoint {
                let rad = angle * .pi / 180
                return CGPoint(
                    x: center.x + cos(rad) * r,
                    y: center.y + sin(rad) * r
                )
            }
            func angle(_ value: Double) -> Double {
                start + ((max(-50, min(50, value)) + 50) / 100) * span
            }

            for mark in stride(from: -50, through: 50, by: 5) {
                let a = angle(Double(mark))
                var tick = Path()
                tick.move(to: point(a, radius: radius - (mark % 10 == 0 ? 14 : 7)))
                tick.addLine(to: point(a, radius: radius))
                context.stroke(
                    tick,
                    with: .color(Color.secondary.opacity(mark == 0 ? 0.9 : 0.35)),
                    lineWidth: mark == 0 ? 2 : 1
                )
            }

            var arc = Path()
            arc.addArc(
                center: center,
                radius: radius - 20,
                startAngle: .degrees(start),
                endAngle: .degrees(start + span),
                clockwise: false
            )
            context.stroke(arc, with: .color(Color.secondary.opacity(0.18)), lineWidth: 8)

            if let cents {
                let a = angle(cents)
                var needle = Path()
                needle.move(to: center)
                needle.addLine(to: point(a, radius: radius - 30))
                context.stroke(
                    needle,
                    with: .color(MacTheme.tuneColor(cents)),
                    style: StrokeStyle(lineWidth: 4, lineCap: .round)
                )
            }
            context.fill(
                Path(ellipseIn: CGRect(x: center.x - 7, y: center.y - 7, width: 14, height: 14)),
                with: .color(MacTheme.tuneColor(cents))
            )
        }
        .accessibilityLabel(
            cents.map { String(format: "偏差 %+.1f 音分", $0) } ?? "等待音高"
        )
    }
}

struct MiniSpectrumPlot: View {
    let values: [Float]
    let peaks: [Partial]

    var body: some View {
        Canvas { context, size in
            guard values.count > 1 else { return }
            var path = Path()
            for (index, value) in values.enumerated() {
                let x = size.width * Double(index) / Double(values.count - 1)
                let y = size.height * Double(1 - ((value + 80) / 80).clamped(to: 0...1))
                if index == 0 { path.move(to: CGPoint(x: x, y: y)) }
                else { path.addLine(to: CGPoint(x: x, y: y)) }
            }
            context.stroke(path, with: .color(MacTheme.accent), lineWidth: 2)
            for peak in peaks {
                let x = size.width * frequencyFraction(peak.freqHz)
                context.fill(
                    Path(
                        ellipseIn: CGRect(
                            x: x - 3,
                            y: size.height * 0.30,
                            width: 6,
                            height: 6
                        )
                    ),
                    with: .color(.primary)
                )
            }
        }
    }
}

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(range.upperBound, max(range.lowerBound, self))
    }
}
