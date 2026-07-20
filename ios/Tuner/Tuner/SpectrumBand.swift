import SwiftUI

/// 频谱分析带（design-system §6.4）：64 柱对数频谱（真实 FFT 数据），
/// H1–H5 泛音旗标（H1 语义色）、非泛音显著峰空心圆点；无信号灰显平线。
struct SpectrumBand: View {
    @Environment(\.lumen) private var palette
    var spectrumDb: [Float]
    var partials: [Partial]
    var fundamentalHz: Double?
    var cents: Float?

    private let fMin = 60.0
    private let fMax = 2400.0
    private let bins = 64
    private let dbMin: Float = -80
    private let dbMax: Float = -10

    private func dbToFrac(_ db: Float) -> CGFloat {
        CGFloat(((db - dbMin) / (dbMax - dbMin)).clamped(to: 0...1))
    }

    private func freqToX(_ f: Double) -> CGFloat {
        CGFloat(log10(f / fMin) / log10(fMax / fMin)).clamped(to: 0...1)
    }

    var body: some View {
        Canvas { ctx, size in
            let w = size.width
            let h = size.height
            guard !spectrumDb.isEmpty else {
                var line = Path()
                line.move(to: CGPoint(x: 0, y: h * 0.9))
                line.addLine(to: CGPoint(x: w, y: h * 0.9))
                ctx.stroke(line, with: .color(palette.inkFaint.opacity(0.5)), lineWidth: 1.5)
                return
            }
            let barW = w / CGFloat(bins)
            for i in 0..<bins {
                let frac = dbToFrac(spectrumDb[i])
                let barH = frac * h * 0.82
                let rect = CGRect(
                    x: CGFloat(i) * barW + barW * 0.15,
                    y: h * 0.9 - barH,
                    width: barW * 0.7,
                    height: max(barH, 1)
                )
                ctx.fill(
                    Path(rect),
                    with: .color(palette.accent.opacity(0.25 + 0.45 * Double(frac)))
                )
            }
            // H1–H5 旗标
            if let f0 = fundamentalHz {
                for harm in 1...5 {
                    let f = f0 * Double(harm)
                    if f > fMax { break }
                    let x = freqToX(f) * w
                    let isH1 = harm == 1
                    let color = isH1 ? Lumen.tuneColor(of: cents ?? 0, palette) : palette.inkSecondary
                    var line = Path()
                    line.move(to: CGPoint(x: x, y: 0))
                    line.addLine(to: CGPoint(x: x, y: h * 0.14))
                    ctx.stroke(line, with: .color(color), lineWidth: isH1 ? 2.5 : 1.5)
                    var tri = Path()
                    tri.move(to: CGPoint(x: x, y: 0))
                    tri.addLine(to: CGPoint(x: x + 6, y: 4))
                    tri.addLine(to: CGPoint(x: x, y: 8))
                    tri.closeSubpath()
                    ctx.fill(tri, with: .color(color))
                }
            }
            // 非泛音显著峰：空心圆点
            for p in partials where p.harmonicIndex == 0 {
                let x = freqToX(p.freqHz) * w
                ctx.stroke(
                    Path(ellipseIn: CGRect(x: x - 4, y: h * 0.22 - 4, width: 8, height: 8)),
                    with: .color(palette.inkSecondary),
                    lineWidth: 1.5
                )
            }
        }
    }
}

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
