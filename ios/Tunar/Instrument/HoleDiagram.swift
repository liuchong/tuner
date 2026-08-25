import SwiftUI

/// 管身、孔心、标签锚点共用同一组归一化坐标，避免视觉上“差不多对齐”。
enum DongxiaoHoleGeometry {
    static let topHoleFraction: CGFloat = 0.22
    static let bottomHoleFraction: CGFloat = 0.77
    static let outletFraction: CGFloat = 0.93

    static func centerY(holeIndex: Int?, holeCount: Int, height: CGFloat) -> CGFloat {
        guard let holeIndex, holeCount > 1 else { return height * outletFraction }
        let positionFromTop = holeCount - 1 - min(max(holeIndex, 0), holeCount - 1)
        let progress = CGFloat(positionFromTop) / CGFloat(holeCount - 1)
        return height * (
            topHoleFraction + (bottomHoleFraction - topHoleFraction) * progress
        )
    }
}

/// 指法区主视觉：与 Android 一致的竖向细长管身、斜切吹口和椭圆出音口。
struct HoleDiagram: View {
    @Environment(\.lumen) private var palette
    let holes: [HoleMark]
    let backHoleCount: Int
    var highlighted = false

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let radius = min(max(size.width * 0.085, 5), 10)
            let tubeWidth = max(size.width * 0.30, radius * 2.8)
            let tubeX = size.width * 0.46
            let bodyTop = size.height * 0.08
            let bodyBottom = size.height * 0.90
            let ordered = holes.indices.reversed().map { index in
                (sourceIndex: index, mark: holes[index], isBack: index >= holes.count - backHoleCount)
            }

            ZStack {
                tube(
                    tubeX: tubeX,
                    tubeWidth: tubeWidth,
                    bodyTop: bodyTop,
                    bodyBottom: bodyBottom,
                    radius: radius
                )

                ForEach(Array(ordered.enumerated()), id: \.offset) { _, hole in
                    let y = DongxiaoHoleGeometry.centerY(
                        holeIndex: hole.sourceIndex,
                        holeCount: holes.count,
                        height: size.height
                    )
                    fingerHole(
                        mark: hole.mark,
                        isBack: hole.isBack,
                        diameter: radius * 2
                    )
                    .position(
                        x: hole.sourceIndex == 0 && !hole.isBack
                            ? tubeX - tubeWidth * 0.07
                            : tubeX,
                        y: y
                    )
                }
            }
        }
        .accessibilityElement()
        .accessibilityLabel(accessibilityText)
    }

    private func tube(
        tubeX: CGFloat,
        tubeWidth: CGFloat,
        bodyTop: CGFloat,
        bodyBottom: CGFloat,
        radius: CGFloat
    ) -> some View {
        let bodyLeft = tubeX - tubeWidth / 2
        let edge = highlighted ? palette.accent : palette.lineSubtle
        return ZStack {
            RoundedRectangle(cornerRadius: tubeWidth * 0.28)
                .fill(palette.bgSurfaceEnd)
                .overlay(
                    RoundedRectangle(cornerRadius: tubeWidth * 0.28)
                        .stroke(edge, lineWidth: highlighted ? 2 : 1.5)
                )
                .frame(width: tubeWidth, height: bodyBottom - bodyTop)
                .position(x: tubeX, y: (bodyTop + bodyBottom) / 2)

            Path { path in
                path.move(to: CGPoint(x: bodyLeft, y: bodyTop + radius * 0.35))
                path.addLine(to: CGPoint(x: bodyLeft + tubeWidth, y: bodyTop - radius * 0.25))
            }
            .stroke(edge, style: StrokeStyle(lineWidth: 2, lineCap: .round))

            Path { path in
                path.move(to: CGPoint(x: tubeX - radius * 0.75, y: bodyTop - radius * 0.15))
                path.addQuadCurve(
                    to: CGPoint(x: tubeX + radius * 0.75, y: bodyTop - radius * 0.15),
                    control: CGPoint(x: tubeX, y: bodyTop + radius * 1.1)
                )
            }
            .stroke(palette.inkPrimary, style: StrokeStyle(lineWidth: 2, lineCap: .round))

            Ellipse()
                .stroke(edge, lineWidth: 2)
                .frame(width: tubeWidth, height: radius * 1.1)
                .position(x: tubeX, y: bodyBottom + radius * 0.2)
        }
    }

    @ViewBuilder
    private func fingerHole(mark: HoleMark, isBack: Bool, diameter: CGFloat) -> some View {
        // 孔位固定中性墨色，命中信号只体现在管身描边上。
        let ink = isBack ? palette.tuneNear : palette.inkPrimary
        ZStack {
            switch mark {
            case .closed:
                Circle().fill(ink)
            case .open:
                Circle()
                    .fill(palette.bgSurface)
                    .overlay(Circle().stroke(ink, lineWidth: 2))
            case .half:
                Circle()
                    .fill(palette.bgSurface)
                    .overlay(Circle().stroke(ink, lineWidth: 2))
                Circle()
                    .fill(ink)
                    .mask(alignment: .bottom) {
                        Rectangle().frame(height: diameter / 2)
                    }
            }
        }
        .frame(width: diameter, height: diameter)
    }

    private var accessibilityText: String {
        let parts = holes.enumerated().map { index, mark -> String in
            let name = index >= holes.count - backHoleCount ? "背孔" : "第\(index + 1)孔"
            switch mark {
            case .closed: return "\(name)闭"
            case .open: return "\(name)开"
            case .half: return "\(name)半开"
            }
        }
        return "\(holes.count)孔洞箫，" + parts.joined(separator: "，")
    }
}
