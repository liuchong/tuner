import SwiftUI

/// 主页面频谱分析带：只标记 Rust core 实际捕捉到的峰值，并显示实际 Hz。
struct SpectrumBand: View {
    @Environment(\.lumen) private var palette
    var spectrumDb: [Float]
    var partials: [Partial]
    var cents: Float?

    private let fMin = 60.0
    private let fMax = 2400.0
    private let dbMin: Float = -80
    private let dbMax: Float = 0

    private func dbToFrac(_ db: Float) -> CGFloat {
        CGFloat(((db - dbMin) / (dbMax - dbMin)).clamped(to: 0...1))
    }

    private func freqToX(_ frequency: Double) -> CGFloat {
        CGFloat(log10(frequency / fMin) / log10(fMax / fMin)).clamped(to: 0...1)
    }

    var body: some View {
        Canvas { context, size in
            let plotBottom = size.height * 0.94
            if spectrumDb.isEmpty {
                var line = Path()
                line.move(to: CGPoint(x: 0, y: plotBottom))
                line.addLine(to: CGPoint(x: size.width, y: plotBottom))
                context.stroke(line, with: .color(palette.inkFaint.opacity(0.5)), lineWidth: 1.5)
                return
            }

            let barWidth = size.width / CGFloat(spectrumDb.count)
            for (index, db) in spectrumDb.enumerated() {
                let fraction = dbToFrac(db)
                let barHeight = fraction * size.height * 0.72
                let rect = CGRect(
                    x: CGFloat(index) * barWidth + barWidth * 0.15,
                    y: plotBottom - barHeight,
                    width: max(1, barWidth * 0.7),
                    height: max(1, barHeight)
                )
                context.fill(
                    Path(rect),
                    with: .color(palette.accent.opacity(0.25 + 0.45 * Double(fraction)))
                )
            }

            for (index, partial) in partials.enumerated()
            where partial.freqHz >= fMin && partial.freqHz <= fMax {
                let x = freqToX(partial.freqHz) * size.width
                let isFundamental = partial.harmonicIndex == 1
                let color = isFundamental
                    ? Lumen.tuneColor(of: cents ?? 0, palette)
                    : palette.inkSecondary
                let labelY = CGFloat(index % 2) * 13 + 5

                var stem = Path()
                stem.move(to: CGPoint(x: x, y: labelY + 10))
                stem.addLine(to: CGPoint(x: x, y: plotBottom))
                context.stroke(stem, with: .color(color.opacity(0.75)), lineWidth: isFundamental ? 2 : 1)

                if partial.harmonicIndex == 0 {
                    context.stroke(
                        Path(
                            ellipseIn: CGRect(
                                x: x - 4,
                                y: plotBottom - 4,
                                width: 8,
                                height: 8
                            )
                        ),
                        with: .color(color),
                        lineWidth: 1.5
                    )
                } else {
                    var flag = Path()
                    flag.move(to: CGPoint(x: x, y: labelY + 10))
                    flag.addLine(to: CGPoint(x: x + 6, y: labelY + 14))
                    flag.addLine(to: CGPoint(x: x, y: labelY + 18))
                    flag.closeSubpath()
                    context.fill(flag, with: .color(color))
                }

                let label = Text(String(format: "%.1f Hz", partial.freqHz))
                    .font(.system(size: 9, weight: isFundamental ? .semibold : .regular))
                    .foregroundStyle(color)
                let alignedX = min(max(x + 4, 28), size.width - 28)
                context.draw(label, at: CGPoint(x: alignedX, y: labelY), anchor: .top)
            }
        }
        .accessibilityLabel("实时频谱，\(partials.count) 个捕捉峰值")
    }
}

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
