import SwiftUI

/// 乐器线稿配色。iOS 从 Lumen 调色板取值，macOS 从系统语义色取值，线稿本身不依赖主题环境。
struct FigureInk {
    /// 线稿主描边。
    var line: Color
    /// 辅助线（对齐线、品丝、徽位）。
    var lineFaint: Color
    /// 墨色（闭孔、琴弦）。
    var ink: Color
    /// 弱墨色（未选中的琴弦、刻字）。
    var inkFaint: Color
    /// 线稿填充。
    var surface: Color
    /// 选中 / 实时命中。
    var accent: Color
    /// 已调准。
    var tuneIn: Color
    /// 背孔。
    var back: Color
}

/// 设计坐标 → 视图坐标的等比变换（居中放置）。
struct FigureTransform {
    let scale: CGFloat
    let origin: CGPoint

    init(fitting design: CGSize, in rect: CGRect) {
        scale = max(min(rect.width / design.width, rect.height / design.height), 0.01)
        origin = CGPoint(
            x: rect.minX + (rect.width - design.width * scale) / 2,
            y: rect.minY + (rect.height - design.height * scale) / 2
        )
    }

    func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
        CGPoint(x: origin.x + x * scale, y: origin.y + y * scale)
    }

    func point(_ p: CGPoint) -> CGPoint { point(p.x, p.y) }

    func design(_ p: CGPoint) -> CGPoint {
        CGPoint(x: (p.x - origin.x) / scale, y: (p.y - origin.y) / scale)
    }

    func path(_ build: (inout Path) -> Void) -> Path {
        var design = Path()
        build(&design)
        return design.applying(
            CGAffineTransform(translationX: origin.x, y: origin.y).scaledBy(x: scale, y: scale)
        )
    }
}

/// 弦的显示状态：已调准 > 实时命中 / 手动选中 > 常态。
enum FigureStringState {
    case idle
    case emphasized
    case inTune

    init(item: StringItemUi, selected: Bool) {
        if item.inTune && (item.active || selected) {
            self = .inTune
        } else if item.active || selected {
            self = .emphasized
        } else {
            self = .idle
        }
    }

    func color(_ ink: FigureInk) -> Color {
        switch self {
        case .idle: ink.inkFaint
        case .emphasized: ink.accent
        case .inTune: ink.tuneIn
        }
    }

    var isHighlighted: Bool { self != .idle }
}

// MARK: - 琴头

enum HeadstockSide: Sendable { case left, right }

struct HeadstockPeg: Identifiable, Sendable {
    /// 弦号（1 = 最细弦）。
    let stringNumber: Int
    /// 弦轴柱中心（设计坐标）。
    let post: CGPoint
    /// 旋钮所在侧；音高按钮与旋钮同侧，按弦轴自上而下的顺序排列。
    let keySide: HeadstockSide

    var id: Int { stringNumber }
}

/// 琴头线稿几何（设计坐标 100 × 160，琴枕在下、琴头朝上，正面视角）。
///
/// 弦从琴枕出发绕到弦轴柱内侧，同侧越靠近琴枕的弦轴接越外侧的弦，保证琴弦不交叉。
struct HeadstockLayout: Sendable {
    static let design = CGSize(width: 100, height: 160)
    static let nutY: CGFloat = 125
    static let postRadius: CGFloat = 2.6
    static let bushingRadius: CGFloat = 4.6
    static let leftKeyX: CGFloat = 6.5
    static let rightKeyX: CGFloat = 93.5
    static let keyHalfWidth: CGFloat = 4

    let pegs: [HeadstockPeg]
    /// 琴枕处各弦 x（自左向右，对应弦号 N…1）。
    let nutXs: [CGFloat]
    let neckHalfWidth: CGFloat
    let outline: @Sendable (inout Path) -> Void

    var stringCount: Int { nutXs.count }

    func nutX(stringNumber: Int) -> CGFloat {
        nutXs[stringCount - stringNumber]
    }

    func peg(stringNumber: Int) -> HeadstockPeg? {
        pegs.first { $0.stringNumber == stringNumber }
    }

    /// 弦绕上弦轴柱的位置：在弦轴柱朝向琴头中线的一侧。
    func attachPoint(_ peg: HeadstockPeg) -> CGPoint {
        let inward: CGFloat = peg.post.x < 50 ? 1 : -1
        return CGPoint(x: peg.post.x + inward * Self.postRadius, y: peg.post.y)
    }

    func keyCenter(_ peg: HeadstockPeg) -> CGPoint {
        CGPoint(x: peg.keySide == .left ? Self.leftKeyX : Self.rightKeyX, y: peg.post.y)
    }

    /// 旋钮朝外一侧的边缘中点：引线终点，贴在旋钮上。
    func keyOuterEdge(_ peg: HeadstockPeg) -> CGPoint {
        let center = keyCenter(peg)
        let outward: CGFloat = peg.keySide == .left ? -1 : 1
        return CGPoint(x: center.x + outward * Self.keyHalfWidth, y: center.y)
    }

    /// 点中弦轴、旋钮或琴弦时返回弦号。
    func hitString(at p: CGPoint) -> Int? {
        var best: (number: Int, distance: CGFloat)?
        func consider(_ number: Int, _ distance: CGFloat, limit: CGFloat) {
            guard distance <= limit else { return }
            if best == nil || distance < best!.distance { best = (number, distance) }
        }
        for peg in pegs {
            consider(peg.stringNumber, hypot(p.x - peg.post.x, p.y - peg.post.y), limit: 9)
            let key = keyCenter(peg)
            consider(peg.stringNumber, hypot(p.x - key.x, p.y - key.y), limit: 9)
            let nut = CGPoint(x: nutX(stringNumber: peg.stringNumber), y: Self.nutY)
            consider(
                peg.stringNumber,
                Self.distance(from: p, to: nut, attachPoint(peg)),
                limit: 3
            )
            if p.y > Self.nutY {
                consider(peg.stringNumber, abs(p.x - nut.x), limit: 2)
            }
        }
        return best?.number
    }

    private static func distance(from p: CGPoint, to a: CGPoint, _ b: CGPoint) -> CGFloat {
        let dx = b.x - a.x
        let dy = b.y - a.y
        let lengthSquared = dx * dx + dy * dy
        guard lengthSquared > 0 else { return hypot(p.x - a.x, p.y - a.y) }
        let t = min(max(((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared, 0), 1)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private static func evenNut(count: Int, from: CGFloat, to: CGFloat) -> [CGFloat] {
        (0..<count).map { from + (to - from) * CGFloat($0) / CGFloat(count - 1) }
    }

    /// Gibson 式 3+3：低音侧自琴枕向上接 6、5、4 弦，高音侧接 1、2、3 弦。
    static let threePlusThree = HeadstockLayout(
        pegs: [
            HeadstockPeg(stringNumber: 6, post: CGPoint(x: 30, y: 96), keySide: .left),
            HeadstockPeg(stringNumber: 5, post: CGPoint(x: 30, y: 68), keySide: .left),
            HeadstockPeg(stringNumber: 4, post: CGPoint(x: 30, y: 40), keySide: .left),
            HeadstockPeg(stringNumber: 1, post: CGPoint(x: 70, y: 96), keySide: .right),
            HeadstockPeg(stringNumber: 2, post: CGPoint(x: 70, y: 68), keySide: .right),
            HeadstockPeg(stringNumber: 3, post: CGPoint(x: 70, y: 40), keySide: .right),
        ],
        nutXs: evenNut(count: 6, from: 40.5, to: 59.5),
        neckHalfWidth: 12,
        outline: { path in
            path.move(to: CGPoint(x: 37, y: 125))
            path.addCurve(
                to: CGPoint(x: 17, y: 70),
                control1: CGPoint(x: 30, y: 112),
                control2: CGPoint(x: 18, y: 96)
            )
            path.addLine(to: CGPoint(x: 16, y: 26))
            path.addQuadCurve(to: CGPoint(x: 30, y: 11), control: CGPoint(x: 16, y: 12))
            path.addQuadCurve(to: CGPoint(x: 50, y: 17), control: CGPoint(x: 44, y: 10))
            path.addQuadCurve(to: CGPoint(x: 70, y: 11), control: CGPoint(x: 56, y: 10))
            path.addQuadCurve(to: CGPoint(x: 84, y: 26), control: CGPoint(x: 84, y: 12))
            path.addLine(to: CGPoint(x: 83, y: 70))
            path.addCurve(
                to: CGPoint(x: 63, y: 125),
                control1: CGPoint(x: 82, y: 96),
                control2: CGPoint(x: 70, y: 112)
            )
            path.closeSubpath()
        }
    )

    /// Fender 式 6-in-line：弦轴全部在低音侧一列，6 弦最靠近琴枕，1 弦在最上方；
    /// 按钮同侧一列、与旋钮顺序一致。
    static let inline6 = HeadstockLayout(
        pegs: [6, 5, 4, 3, 2, 1].enumerated().map { offset, number in
            HeadstockPeg(
                stringNumber: number,
                post: CGPoint(x: 30, y: 112 - CGFloat(offset) * 18),
                keySide: .left
            )
        },
        nutXs: evenNut(count: 6, from: 40.5, to: 59.5),
        neckHalfWidth: 12,
        outline: { path in
            path.move(to: CGPoint(x: 37, y: 125))
            path.addQuadCurve(to: CGPoint(x: 21, y: 108), control: CGPoint(x: 22, y: 121))
            path.addLine(to: CGPoint(x: 21, y: 22))
            path.addQuadCurve(to: CGPoint(x: 36, y: 8), control: CGPoint(x: 21, y: 8))
            path.addCurve(
                to: CGPoint(x: 76, y: 26),
                control1: CGPoint(x: 56, y: 8),
                control2: CGPoint(x: 74, y: 14)
            )
            path.addCurve(
                to: CGPoint(x: 58, y: 58),
                control1: CGPoint(x: 78, y: 38),
                control2: CGPoint(x: 62, y: 46)
            )
            path.addCurve(
                to: CGPoint(x: 66, y: 86),
                control1: CGPoint(x: 55, y: 68),
                control2: CGPoint(x: 62, y: 76)
            )
            path.addCurve(
                to: CGPoint(x: 63, y: 125),
                control1: CGPoint(x: 70, y: 96),
                control2: CGPoint(x: 66, y: 112)
            )
            path.closeSubpath()
        }
    )

    /// 尤克里里 2+2：下排左 4 弦、右 1 弦，上排左 3 弦、右 2 弦。
    static let ukulele = HeadstockLayout(
        pegs: [
            HeadstockPeg(stringNumber: 4, post: CGPoint(x: 34, y: 96), keySide: .left),
            HeadstockPeg(stringNumber: 3, post: CGPoint(x: 34, y: 58), keySide: .left),
            HeadstockPeg(stringNumber: 1, post: CGPoint(x: 66, y: 96), keySide: .right),
            HeadstockPeg(stringNumber: 2, post: CGPoint(x: 66, y: 58), keySide: .right),
        ],
        nutXs: evenNut(count: 4, from: 43, to: 57),
        neckHalfWidth: 10,
        outline: { path in
            path.move(to: CGPoint(x: 39, y: 125))
            path.addCurve(
                to: CGPoint(x: 24, y: 92),
                control1: CGPoint(x: 32, y: 116),
                control2: CGPoint(x: 24, y: 106)
            )
            path.addLine(to: CGPoint(x: 24, y: 44))
            path.addQuadCurve(to: CGPoint(x: 38, y: 30), control: CGPoint(x: 24, y: 30))
            path.addQuadCurve(to: CGPoint(x: 62, y: 30), control: CGPoint(x: 50, y: 38))
            path.addQuadCurve(to: CGPoint(x: 76, y: 44), control: CGPoint(x: 76, y: 30))
            path.addLine(to: CGPoint(x: 76, y: 92))
            path.addCurve(
                to: CGPoint(x: 61, y: 125),
                control1: CGPoint(x: 76, y: 106),
                control2: CGPoint(x: 68, y: 116)
            )
            path.closeSubpath()
        }
    )

    static func layout(for figure: StringFigureKind) -> HeadstockLayout? {
        switch figure {
        case .headstock(.inline6): inline6
        case .headstock(.threePlusThree): threePlusThree
        case .ukuleleHeadstock: ukulele
        case .guqin, .none: nil
        }
    }
}

/// 琴头线稿：弦轴、旋钮与琴弦一一对应；点中弦轴、旋钮或琴弦即选中该弦。
struct HeadstockFigure: View {
    let layout: HeadstockLayout
    let strings: [StringItemUi]
    let selectedIndex: Int?
    let ink: FigureInk
    let transform: FigureTransform
    let onSelect: (Int) -> Void

    var body: some View {
        Canvas { ctx, _ in draw(ctx) }
            .contentShape(Rectangle())
            .gesture(
                SpatialTapGesture().onEnded { value in
                    if let number = layout.hitString(at: transform.design(value.location)) {
                        onSelect(number - 1)
                    }
                }
            )
            .accessibilityElement()
            .accessibilityLabel(accessibilityText)
    }

    private func state(of number: Int) -> FigureStringState {
        guard let item = strings[safe: number - 1] else { return .idle }
        return FigureStringState(item: item, selected: selectedIndex == number - 1)
    }

    private func draw(_ ctx: GraphicsContext) {
        let t = transform
        let s = t.scale
        let nutY = HeadstockLayout.nutY
        let neckLeft = 50 - layout.neckHalfWidth
        let neckRight = 50 + layout.neckHalfWidth
        let lineWidth = max(1.2, s * 0.8)

        let neck = t.path { p in
            p.addRect(CGRect(x: neckLeft, y: nutY, width: neckRight - neckLeft, height: 160 - nutY))
        }
        ctx.fill(neck, with: .color(ink.surface))
        ctx.stroke(neck, with: .color(ink.line), lineWidth: lineWidth)
        let fret = t.path { p in
            p.move(to: CGPoint(x: neckLeft, y: 148))
            p.addLine(to: CGPoint(x: neckRight, y: 148))
        }
        ctx.stroke(fret, with: .color(ink.lineFaint), lineWidth: max(1, s * 0.6))

        // 旋钮与轴杆先画，琴头填充会盖住轴杆在琴头内的部分。
        for peg in layout.pegs {
            let state = state(of: peg.stringNumber)
            let key = layout.keyCenter(peg)
            let shaft = t.path { p in
                p.move(to: peg.post)
                p.addLine(to: key)
            }
            ctx.stroke(shaft, with: .color(ink.line), lineWidth: max(1.5, s * 1.4))
            let knob = t.path { p in
                p.addRoundedRect(
                    in: CGRect(
                        x: key.x - HeadstockLayout.keyHalfWidth,
                        y: key.y - 5,
                        width: HeadstockLayout.keyHalfWidth * 2,
                        height: 10
                    ),
                    cornerSize: CGSize(width: 2.6, height: 2.6)
                )
            }
            ctx.fill(knob, with: .color(state.isHighlighted ? state.color(ink).opacity(0.22) : ink.surface))
            ctx.stroke(
                knob,
                with: .color(state.isHighlighted ? state.color(ink) : ink.line),
                lineWidth: state.isHighlighted ? max(1.6, s * 1.1) : lineWidth
            )
        }

        let head = t.path(layout.outline)
        ctx.fill(head, with: .color(ink.surface))
        ctx.stroke(head, with: .color(ink.line), style: StrokeStyle(lineWidth: lineWidth * 1.2, lineJoin: .round))

        let nut = t.path { p in
            p.addRoundedRect(
                in: CGRect(x: neckLeft - 1, y: nutY - 3, width: neckRight - neckLeft + 2, height: 3),
                cornerSize: CGSize(width: 1, height: 1)
            )
        }
        ctx.fill(nut, with: .color(ink.line))

        // 常态弦先画，高亮弦后画，避免被相邻弦压住。
        let ordered = layout.pegs.sorted { state(of: $0.stringNumber).isHighlighted == false && state(of: $1.stringNumber).isHighlighted }
        for peg in ordered {
            let state = state(of: peg.stringNumber)
            let x = layout.nutX(stringNumber: peg.stringNumber)
            let gauge = 0.7 + CGFloat(peg.stringNumber - 1) / CGFloat(max(layout.stringCount - 1, 1)) * 0.9
            let string = t.path { p in
                p.move(to: CGPoint(x: x, y: 160))
                p.addLine(to: CGPoint(x: x, y: nutY))
                p.addLine(to: layout.attachPoint(peg))
            }
            if state.isHighlighted {
                ctx.stroke(string, with: .color(state.color(ink).opacity(0.22)), lineWidth: gauge + 6)
            }
            ctx.stroke(
                string,
                with: .color(state.isHighlighted ? state.color(ink) : ink.inkFaint),
                style: StrokeStyle(lineWidth: state.isHighlighted ? gauge + 1.4 : gauge, lineCap: .round, lineJoin: .round)
            )
        }

        for peg in layout.pegs {
            let state = state(of: peg.stringNumber)
            let r = HeadstockLayout.bushingRadius
            let bushing = t.path { p in
                p.addEllipse(in: CGRect(x: peg.post.x - r, y: peg.post.y - r, width: r * 2, height: r * 2))
            }
            ctx.fill(bushing, with: .color(ink.surface))
            ctx.stroke(bushing, with: .color(state.isHighlighted ? state.color(ink) : ink.line), lineWidth: lineWidth)
            let pr = HeadstockLayout.postRadius
            let post = t.path { p in
                p.addEllipse(in: CGRect(x: peg.post.x - pr, y: peg.post.y - pr, width: pr * 2, height: pr * 2))
            }
            ctx.fill(post, with: .color(state.isHighlighted ? state.color(ink) : ink.inkFaint))
        }
    }

    private var accessibilityText: String {
        let parts = layout.pegs
            .sorted { $0.stringNumber < $1.stringNumber }
            .compactMap { peg -> String? in
                guard let item = strings[safe: peg.stringNumber - 1] else { return nil }
                let side = peg.keySide == .left ? "左侧" : "右侧"
                return "\(peg.stringNumber)弦\(item.noteName)在\(side)弦轴"
            }
        return "琴头弦轴对应，" + parts.joined(separator: "，")
    }
}

/// 同侧按钮的纵向中心：尽量贴近各自弦轴高度（`ideal`），相邻中心至少相隔 `spacing`。
/// 会相撞的按钮合并成簇并以簇内理想高度均值居中；整体限制在 `minY...maxY`，
/// 空间不足时均匀压缩间距。返回值与 `ideal` 顺序一致。
func spreadCenters(_ ideal: [CGFloat], spacing: CGFloat, minY: CGFloat, maxY: CGFloat) -> [CGFloat] {
    let n = ideal.count
    guard n > 0 else { return [] }
    let step = n > 1 ? max(0, min(spacing, (maxY - minY) / CGFloat(n - 1))) : 0
    let order = ideal.indices.sorted { ideal[$0] < ideal[$1] }

    var clusters: [(count: Int, sum: CGFloat)] = []
    func top(_ c: (count: Int, sum: CGFloat)) -> CGFloat {
        c.sum / CGFloat(c.count) - CGFloat(c.count - 1) * step / 2
    }
    for index in order {
        clusters.append((1, ideal[index]))
        while clusters.count >= 2 {
            let upper = clusters[clusters.count - 2]
            let lower = clusters[clusters.count - 1]
            if top(upper) + CGFloat(upper.count) * step <= top(lower) { break }
            clusters.removeLast()
            clusters[clusters.count - 1] = (upper.count + lower.count, upper.sum + lower.sum)
        }
    }

    var sorted: [CGFloat] = []
    sorted.reserveCapacity(n)
    for cluster in clusters {
        for j in 0..<cluster.count { sorted.append(top(cluster) + CGFloat(j) * step) }
    }
    for i in 0..<n {
        sorted[i] = max(sorted[i], i == 0 ? minY : sorted[i - 1] + step)
    }
    for i in stride(from: n - 1, through: 0, by: -1) {
        sorted[i] = min(sorted[i], i == n - 1 ? maxY : sorted[i + 1] - step)
    }

    var result = [CGFloat](repeating: 0, count: n)
    for (rank, index) in order.enumerated() { result[index] = sorted[rank] }
    return result
}

/// 选中按钮最上层，其次是自动识别到的弦；重叠时不会被相邻按钮遮住。
func headstockButtonZIndex(selected: Bool, active: Bool) -> Double {
    selected ? 2 : (active ? 1 : 0)
}

/// 琴头 + 音高按钮：按钮与旋钮同侧、顺序一致且互不重叠，虚线引到旋钮外缘并以圆点收尾；
/// 同侧按钮过多时自动压低按钮高度。
struct HeadstockPanel<StringButtonView: View>: View {
    let layout: HeadstockLayout
    let strings: [StringItemUi]
    let selectedIndex: Int?
    let ink: FigureInk
    let buttonSize: CGSize
    let onSelect: (Int) -> Void
    @ViewBuilder let button: (StringItemUi) -> StringButtonView

    private let gap: CGFloat = 6

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let columnWidth = buttonSize.width + gap
            let hasLeft = layout.pegs.contains { $0.keySide == .left }
            let hasRight = layout.pegs.contains { $0.keySide == .right }
            let figureLeft = hasLeft ? columnWidth : 0
            let figureRight = hasRight ? size.width - columnWidth : size.width
            let figureRect = CGRect(
                x: figureLeft,
                y: 0,
                width: max(figureRight - figureLeft, 1),
                height: size.height
            )
            let buttonHeight = fittedButtonHeight(height: size.height)
            let t = FigureTransform(fitting: HeadstockLayout.design, in: figureRect)
            let leftX = max(buttonSize.width / 2, t.point(0, 0).x - gap - buttonSize.width / 2)
            let rightX = min(
                size.width - buttonSize.width / 2,
                t.point(HeadstockLayout.design.width, 0).x + gap + buttonSize.width / 2
            )
            let ys = buttonYs(t: t, height: size.height, buttonHeight: buttonHeight)

            ZStack(alignment: .topLeading) {
                guides(t: t, leftX: leftX, rightX: rightX, ys: ys)
                HeadstockFigure(
                    layout: layout,
                    strings: strings,
                    selectedIndex: selectedIndex,
                    ink: ink,
                    transform: t,
                    onSelect: onSelect
                )
                .frame(width: size.width, height: size.height)

                ForEach(layout.pegs) { peg in
                    if let item = strings[safe: peg.stringNumber - 1] {
                        button(item)
                            .frame(width: buttonSize.width, height: buttonHeight)
                            .position(
                                x: peg.keySide == .left ? leftX : rightX,
                                y: ys[peg.stringNumber] ?? t.point(peg.post).y
                            )
                            .zIndex(headstockButtonZIndex(
                                selected: selectedIndex == peg.stringNumber - 1,
                                active: item.active
                            ))
                    }
                }
            }
        }
    }

    /// 同侧按钮放不下时压低高度（不低于 30pt），保证一列按钮互不重叠。
    private func fittedButtonHeight(height: CGFloat) -> CGFloat {
        let perSide = max(
            layout.pegs.filter { $0.keySide == .left }.count,
            layout.pegs.filter { $0.keySide == .right }.count,
            1
        )
        let fit = (height - CGFloat(perSide - 1) * 4) / CGFloat(perSide)
        return max(min(buttonSize.height, fit), 30)
    }

    /// 弦号 → 按钮中心 y；左右两列各自排开。
    private func buttonYs(t: FigureTransform, height: CGFloat, buttonHeight: CGFloat) -> [Int: CGFloat] {
        var result: [Int: CGFloat] = [:]
        for side in [HeadstockSide.left, .right] {
            let pegs = layout.pegs.filter { $0.keySide == side }
            let ys = spreadCenters(
                pegs.map { t.point($0.post).y },
                spacing: buttonHeight + 4,
                minY: buttonHeight / 2,
                maxY: max(height - buttonHeight / 2, buttonHeight / 2)
            )
            for (peg, y) in zip(pegs, ys) { result[peg.stringNumber] = y }
        }
        return result
    }

    private func guides(t: FigureTransform, leftX: CGFloat, rightX: CGFloat, ys: [Int: CGFloat]) -> some View {
        Canvas { ctx, size in
            for peg in layout.pegs {
                guard let item = strings[safe: peg.stringNumber - 1] else { continue }
                let state = FigureStringState(item: item, selected: selectedIndex == peg.stringNumber - 1)
                let y = ys[peg.stringNumber] ?? t.point(peg.post).y
                let startX = peg.keySide == .left
                    ? leftX + buttonSize.width / 2
                    : rightX - buttonSize.width / 2
                let end = t.point(layout.keyOuterEdge(peg))
                let color = state.isHighlighted ? state.color(ink) : ink.line.opacity(0.55)
                var path = Path()
                path.move(to: CGPoint(x: startX, y: y))
                path.addLine(to: CGPoint(x: startX + (end.x - startX) * 0.35, y: y))
                path.addLine(to: end)
                ctx.stroke(
                    path,
                    with: .color(color),
                    style: StrokeStyle(lineWidth: state.isHighlighted ? 1.5 : 1, dash: [3, 2.5])
                )
                let r: CGFloat = state.isHighlighted ? 2.6 : 2
                ctx.fill(
                    Path(ellipseIn: CGRect(x: end.x - r, y: end.y - r, width: r * 2, height: r * 2)),
                    with: .color(color)
                )
            }
        }
        .allowsHitTesting(false)
    }
}

// MARK: - 古琴

/// 古琴俯视线稿几何（设计坐标 300 × 80）：琴首（岳山）在右、琴尾（龙龈）在左，
/// 一弦在上（离演奏者最远），徽位沿近身一侧。
enum GuqinGeometry {
    static let design = CGSize(width: 300, height: 80)
    static let bridgeX: CGFloat = 266
    static let tailX: CGFloat = 11
    /// 十三徽相对有效弦长（自岳山量起）的位置。
    static let huiFractions: [CGFloat] = [
        1.0 / 8, 1.0 / 6, 1.0 / 5, 1.0 / 4, 1.0 / 3, 2.0 / 5, 1.0 / 2,
        3.0 / 5, 2.0 / 3, 3.0 / 4, 4.0 / 5, 5.0 / 6, 7.0 / 8,
    ]

    static func stringY(_ index: Int, count: Int, x: CGFloat) -> CGFloat {
        let step = CGFloat(index) / CGFloat(max(count - 1, 1))
        let atBridge = 20 + 36 * step
        let atTail = 28 + 24 * step
        let progress = min(max((x - tailX) / (bridgeX - tailX), 0), 1)
        return atTail + (atBridge - atTail) * progress
    }

    static func huiX(_ fraction: CGFloat) -> CGFloat {
        bridgeX - fraction * (bridgeX - tailX)
    }

    static func huiY(x: CGFloat) -> CGFloat {
        x < 70 ? 62 + 5 * (x - 4) / 66 - 3.4 : 63.6
    }

    static func nearestString(to p: CGPoint, count: Int) -> Int? {
        guard count > 0, p.x >= 0, p.x <= design.width, p.y >= 0, p.y <= design.height else {
            return nil
        }
        return (0..<count).min {
            abs(stringY($0, count: count, x: p.x) - p.y) < abs(stringY($1, count: count, x: p.x) - p.y)
        }
    }

    /// 仲尼式：项、腰各有一处方折内收。
    static func outline(_ path: inout Path) {
        path.move(to: CGPoint(x: 4, y: 18))
        path.addLine(to: CGPoint(x: 70, y: 13))
        path.addLine(to: CGPoint(x: 72, y: 16))
        path.addLine(to: CGPoint(x: 82, y: 16))
        path.addLine(to: CGPoint(x: 84, y: 12.5))
        path.addLine(to: CGPoint(x: 250, y: 10))
        path.addQuadCurve(to: CGPoint(x: 256, y: 15), control: CGPoint(x: 253, y: 15))
        path.addLine(to: CGPoint(x: 258, y: 15))
        path.addQuadCurve(to: CGPoint(x: 263, y: 9), control: CGPoint(x: 261, y: 15))
        path.addLine(to: CGPoint(x: 290, y: 9))
        path.addQuadCurve(to: CGPoint(x: 297, y: 20), control: CGPoint(x: 297, y: 9))
        path.addLine(to: CGPoint(x: 297, y: 60))
        path.addQuadCurve(to: CGPoint(x: 290, y: 71), control: CGPoint(x: 297, y: 71))
        path.addLine(to: CGPoint(x: 263, y: 71))
        path.addQuadCurve(to: CGPoint(x: 258, y: 65), control: CGPoint(x: 261, y: 65))
        path.addLine(to: CGPoint(x: 256, y: 65))
        path.addQuadCurve(to: CGPoint(x: 250, y: 70), control: CGPoint(x: 253, y: 65))
        path.addLine(to: CGPoint(x: 84, y: 67.5))
        path.addLine(to: CGPoint(x: 82, y: 64))
        path.addLine(to: CGPoint(x: 72, y: 64))
        path.addLine(to: CGPoint(x: 70, y: 67))
        path.addLine(to: CGPoint(x: 4, y: 62))
        path.addQuadCurve(to: CGPoint(x: 1, y: 58), control: CGPoint(x: 1, y: 62))
        path.addLine(to: CGPoint(x: 1, y: 22))
        path.addQuadCurve(to: CGPoint(x: 4, y: 18), control: CGPoint(x: 1, y: 18))
        path.closeSubpath()
    }
}

/// 古琴线稿：当前弦加粗高亮；点按或沿琴面滑动即选中最近的弦。
struct GuqinFigure: View {
    let strings: [StringItemUi]
    let selectedIndex: Int?
    let ink: FigureInk
    let onSelect: (Int) -> Void
    @State private var dragIndex: Int?

    var body: some View {
        GeometryReader { geometry in
            let t = FigureTransform(
                fitting: GuqinGeometry.design,
                in: CGRect(origin: .zero, size: geometry.size)
            )
            Canvas { ctx, _ in draw(ctx, t: t) }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            guard let index = GuqinGeometry.nearestString(
                                to: t.design(value.location),
                                count: strings.count
                            ), index != dragIndex else { return }
                            dragIndex = index
                            onSelect(index)
                        }
                        .onEnded { _ in dragIndex = nil }
                )
        }
        .accessibilityElement()
        .accessibilityLabel("古琴七弦")
        .accessibilityValue(accessibilityValue)
        .accessibilityAdjustableAction { direction in
            let current = selectedIndex ?? strings.firstIndex(where: \.active) ?? 0
            let next = direction == .increment ? current + 1 : current - 1
            if strings.indices.contains(next) { onSelect(next) }
        }
    }

    private var accessibilityValue: String {
        guard let index = selectedIndex ?? strings.firstIndex(where: \.active),
              let item = strings[safe: index]
        else { return "未选中" }
        return "\(item.index)弦 \(item.noteName)"
    }

    private func draw(_ ctx: GraphicsContext, t: FigureTransform) {
        let s = t.scale
        let lineWidth = max(1.2, s * 1.0)
        let body = t.path(GuqinGeometry.outline)
        ctx.fill(body, with: .color(ink.surface))
        ctx.stroke(body, with: .color(ink.line), style: StrokeStyle(lineWidth: lineWidth * 1.2, lineJoin: .round))

        let bridge = t.path { p in
            p.addRoundedRect(
                in: CGRect(x: GuqinGeometry.bridgeX - 2.5, y: 13, width: 5, height: 54),
                cornerSize: CGSize(width: 2, height: 2)
            )
        }
        ctx.fill(bridge, with: .color(ink.surface))
        ctx.stroke(bridge, with: .color(ink.line), lineWidth: lineWidth)
        let tail = t.path { p in
            p.addRoundedRect(
                in: CGRect(x: GuqinGeometry.tailX - 3, y: 23, width: 3.5, height: 34),
                cornerSize: CGSize(width: 1.5, height: 1.5)
            )
        }
        ctx.stroke(tail, with: .color(ink.line), lineWidth: lineWidth)

        for (index, fraction) in GuqinGeometry.huiFractions.enumerated() {
            let x = GuqinGeometry.huiX(fraction)
            let r: CGFloat = index == 6 ? 1.9 : 1.25
            let hui = t.path { p in
                p.addEllipse(in: CGRect(x: x - r, y: GuqinGeometry.huiY(x: x) - r, width: r * 2, height: r * 2))
            }
            ctx.fill(hui, with: .color(ink.inkFaint))
        }

        let count = strings.count
        let states = strings.enumerated().map { index, item in
            FigureStringState(item: item, selected: selectedIndex == index)
        }
        let drawOrder = states.indices.sorted { !states[$0].isHighlighted && states[$1].isHighlighted }
        for index in drawOrder {
            let state = states[index]
            let gauge = 2.1 - CGFloat(index) * 0.19
            let from = CGPoint(x: GuqinGeometry.tailX, y: GuqinGeometry.stringY(index, count: count, x: GuqinGeometry.tailX))
            let to = CGPoint(x: GuqinGeometry.bridgeX + 7, y: GuqinGeometry.stringY(index, count: count, x: GuqinGeometry.bridgeX))
            let string = t.path { p in
                p.move(to: from)
                p.addLine(to: to)
            }
            if state.isHighlighted {
                ctx.stroke(string, with: .color(state.color(ink).opacity(0.2)), lineWidth: gauge + 7)
                ctx.stroke(string, with: .color(state.color(ink)), style: StrokeStyle(lineWidth: gauge + 1.8, lineCap: .round))
            } else {
                ctx.stroke(string, with: .color(ink.ink.opacity(0.55)), style: StrokeStyle(lineWidth: gauge, lineCap: .round))
            }
            let label = Text("\(index + 1)")
                .font(.system(size: max(8, 7 * s), weight: state.isHighlighted ? .bold : .medium))
                .foregroundColor(state.isHighlighted ? state.color(ink) : ink.inkFaint)
            ctx.draw(label, at: t.point(283, to.y), anchor: .center)
        }
    }
}

// MARK: - 管乐

enum WindFigureGeometry {
    private static let diziHoles: [CGFloat] = [0.78, 0.69, 0.60, 0.46, 0.37, 0.28]
    private static let shakuhachiHoles: [CGFloat] = [0.78, 0.68, 0.57, 0.47, 0.36]

    static func outletFraction(_ kind: WindFigureKind) -> CGFloat {
        switch kind {
        case .xiao: 0.93
        case .dizi: 0.91
        case .shakuhachi: 0.92
        }
    }

    /// 孔心的归一化纵坐标；`holeIndex == nil` 表示筒音（出音口）。
    static func fraction(_ kind: WindFigureKind, holeIndex: Int?, holeCount: Int) -> CGFloat {
        guard let holeIndex, holeCount > 0 else { return outletFraction(kind) }
        let index = min(max(holeIndex, 0), holeCount - 1)
        switch kind {
        case .dizi where holeCount == diziHoles.count:
            return diziHoles[index]
        case .shakuhachi where holeCount == shakuhachiHoles.count:
            return shakuhachiHoles[index]
        default:
            guard holeCount > 1 else { return 0.5 }
            let progress = CGFloat(holeCount - 1 - index) / CGFloat(holeCount - 1)
            return 0.22 + (0.77 - 0.22) * progress
        }
    }

    static func centerY(_ kind: WindFigureKind, holeIndex: Int?, holeCount: Int, height: CGFloat) -> CGFloat {
        height * fraction(kind, holeIndex: holeIndex, holeCount: holeCount)
    }
}

/// 竖向管身线稿：洞箫（斜切吹口 + U 形山口）、竹笛（吹孔 + 膜孔 + 缠线，六孔 3+3 分组）、
/// 尺八（歌口 + 竹节 + 竹根）。孔心与右侧指法行共用 [WindFigureGeometry]。
struct WindTubeFigure: View {
    let kind: WindFigureKind
    let holes: [HoleMark]
    let backHoleCount: Int
    var highlighted = false
    let ink: FigureInk

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let radius = holeRadius(width: size.width)
            let tubeX = size.width * 0.46
            ZStack {
                Canvas { ctx, size in drawTube(ctx, size: size, radius: radius) }
                ForEach(Array(holes.enumerated()), id: \.offset) { index, mark in
                    let isBack = index >= holes.count - backHoleCount
                    fingerHole(mark: mark, isBack: isBack, diameter: radius * 2)
                        .position(
                            x: kind == .xiao && index == 0 && !isBack
                                ? tubeX - tubeWidth(size.width, radius) * 0.07
                                : tubeX,
                            y: WindFigureGeometry.centerY(
                                kind,
                                holeIndex: index,
                                holeCount: holes.count,
                                height: size.height
                            )
                        )
                }
            }
        }
        .accessibilityElement()
        .accessibilityLabel(accessibilityText)
    }

    private func holeRadius(width: CGFloat) -> CGFloat {
        min(max(width * 0.085, 5), 11)
    }

    private func tubeWidth(_ width: CGFloat, _ radius: CGFloat) -> CGFloat {
        switch kind {
        case .xiao: max(width * 0.30, radius * 2.8)
        case .dizi: max(width * 0.26, radius * 2.6)
        case .shakuhachi: max(width * 0.30, radius * 2.8)
        }
    }

    private var edge: Color { highlighted ? ink.accent : ink.line }
    private var edgeWidth: CGFloat { highlighted ? 2 : 1.5 }

    private func drawTube(_ ctx: GraphicsContext, size: CGSize, radius: CGFloat) {
        switch kind {
        case .xiao: drawXiao(ctx, size: size, radius: radius)
        case .dizi: drawDizi(ctx, size: size, radius: radius)
        case .shakuhachi: drawShakuhachi(ctx, size: size, radius: radius)
        }
    }

    private func drawXiao(_ ctx: GraphicsContext, size: CGSize, radius: CGFloat) {
        let w = tubeWidth(size.width, radius)
        let x = size.width * 0.46
        let top = size.height * 0.08
        let bottom = size.height * 0.90
        let body = Path(
            roundedRect: CGRect(x: x - w / 2, y: top, width: w, height: bottom - top),
            cornerRadius: w * 0.28
        )
        ctx.fill(body, with: .color(ink.surface))
        ctx.stroke(body, with: .color(edge), lineWidth: edgeWidth)
        var cut = Path()
        cut.move(to: CGPoint(x: x - w / 2, y: top + radius * 0.35))
        cut.addLine(to: CGPoint(x: x + w / 2, y: top - radius * 0.25))
        ctx.stroke(cut, with: .color(edge), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        var notch = Path()
        notch.move(to: CGPoint(x: x - radius * 0.75, y: top - radius * 0.15))
        notch.addQuadCurve(
            to: CGPoint(x: x + radius * 0.75, y: top - radius * 0.15),
            control: CGPoint(x: x, y: top + radius * 1.1)
        )
        ctx.stroke(notch, with: .color(ink.ink), style: StrokeStyle(lineWidth: 2, lineCap: .round))
        let outlet = Path(ellipseIn: CGRect(
            x: x - w / 2, y: bottom + radius * 0.2 - radius * 0.55, width: w, height: radius * 1.1
        ))
        ctx.stroke(outlet, with: .color(edge), lineWidth: 2)
    }

    private func drawDizi(_ ctx: GraphicsContext, size: CGSize, radius: CGFloat) {
        let w = tubeWidth(size.width, radius)
        let x = size.width * 0.46
        let h = size.height
        let top = h * 0.025
        let bottom = h * 0.955
        let body = Path(
            roundedRect: CGRect(x: x - w / 2, y: top, width: w, height: bottom - top),
            cornerRadius: w * 0.4
        )
        ctx.fill(body, with: .color(ink.surface))
        ctx.stroke(body, with: .color(edge), lineWidth: edgeWidth)

        // 缠线：两端护箍与 3+3 指孔之间的一道，提示左右手分组。
        for band in [0.045, 0.115, 0.53, 0.86, 0.935] as [CGFloat] {
            for offset in [-1.6, 1.6] as [CGFloat] {
                var line = Path()
                line.move(to: CGPoint(x: x - w / 2, y: h * band + offset))
                line.addLine(to: CGPoint(x: x + w / 2, y: h * band + offset))
                ctx.stroke(line, with: .color(ink.lineFaint), lineWidth: 1)
            }
        }

        let embouchure = Path(ellipseIn: CGRect(
            x: x - radius * 0.95, y: h * 0.075 - radius * 0.7, width: radius * 1.9, height: radius * 1.4
        ))
        ctx.fill(embouchure, with: .color(ink.surface))
        ctx.stroke(embouchure, with: .color(ink.ink), lineWidth: 2)

        let membraneR = radius * 0.8
        let membrane = Path(ellipseIn: CGRect(
            x: x - membraneR, y: h * 0.165 - membraneR, width: membraneR * 2, height: membraneR * 2
        ))
        ctx.fill(membrane, with: .color(ink.back.opacity(0.28)))
        ctx.stroke(membrane, with: .color(ink.back), lineWidth: 1.2)
        var crinkle = Path()
        crinkle.move(to: CGPoint(x: x - membraneR * 0.55, y: h * 0.165))
        crinkle.addQuadCurve(
            to: CGPoint(x: x + membraneR * 0.55, y: h * 0.165),
            control: CGPoint(x: x, y: h * 0.165 - membraneR * 0.6)
        )
        ctx.stroke(crinkle, with: .color(ink.back), lineWidth: 0.8)

        let ventR = radius * 0.5
        for dx in [-w * 0.22, w * 0.22] {
            let vent = Path(ellipseIn: CGRect(
                x: x + dx - ventR, y: h * 0.885 - ventR, width: ventR * 2, height: ventR * 2
            ))
            ctx.stroke(vent, with: .color(ink.line), lineWidth: 1.2)
        }
    }

    private func drawShakuhachi(_ ctx: GraphicsContext, size: CGSize, radius: CGFloat) {
        let x = size.width * 0.46
        let h = size.height
        let topW = tubeWidth(size.width, radius)
        let bottomW = topW * 1.22
        let top = h * 0.03
        let rootStart = h * 0.86
        let bottom = h * 0.955
        var body = Path()
        body.move(to: CGPoint(x: x - topW / 2, y: top))
        body.addLine(to: CGPoint(x: x + topW / 2, y: top))
        body.addLine(to: CGPoint(x: x + bottomW / 2, y: rootStart))
        body.addQuadCurve(
            to: CGPoint(x: x + bottomW / 2 - 1, y: bottom),
            control: CGPoint(x: x + bottomW / 2 + topW * 0.22, y: (rootStart + bottom) / 2)
        )
        body.addQuadCurve(
            to: CGPoint(x: x - bottomW / 2 + 1, y: bottom),
            control: CGPoint(x: x, y: bottom + radius * 0.5)
        )
        body.addQuadCurve(
            to: CGPoint(x: x - bottomW / 2, y: rootStart),
            control: CGPoint(x: x - bottomW / 2 - topW * 0.22, y: (rootStart + bottom) / 2)
        )
        body.closeSubpath()
        ctx.fill(body, with: .color(ink.surface))
        ctx.stroke(body, with: .color(edge), style: StrokeStyle(lineWidth: edgeWidth, lineJoin: .round))

        var utaguchi = Path()
        utaguchi.move(to: CGPoint(x: x - topW * 0.26, y: top))
        utaguchi.addLine(to: CGPoint(x: x - topW * 0.12, y: top + radius * 1.1))
        utaguchi.addLine(to: CGPoint(x: x + topW * 0.12, y: top + radius * 1.1))
        utaguchi.addLine(to: CGPoint(x: x + topW * 0.26, y: top))
        ctx.stroke(utaguchi, with: .color(ink.ink), style: StrokeStyle(lineWidth: 1.8, lineJoin: .round))

        for node in [0.13, 0.255, 0.415, 0.625, 0.84] as [CGFloat] {
            let y = h * node
            let halfWidth = (topW + (bottomW - topW) * (y - top) / (rootStart - top)) / 2
            for offset in [-1.4, 1.4] as [CGFloat] {
                var line = Path()
                line.move(to: CGPoint(x: x - halfWidth, y: y + offset))
                line.addQuadCurve(
                    to: CGPoint(x: x + halfWidth, y: y + offset),
                    control: CGPoint(x: x, y: y + offset + radius * 0.35)
                )
                ctx.stroke(line, with: .color(ink.lineFaint), lineWidth: 1)
            }
        }

        let outlet = Path(ellipseIn: CGRect(
            x: x - bottomW * 0.34, y: bottom - radius * 0.45, width: bottomW * 0.68, height: radius * 0.9
        ))
        ctx.stroke(outlet, with: .color(edge), lineWidth: 1.6)
    }

    @ViewBuilder
    private func fingerHole(mark: HoleMark, isBack: Bool, diameter: CGFloat) -> some View {
        // 孔位固定中性墨色，命中信号只体现在管身描边上。
        let color = isBack ? ink.back : ink.ink
        ZStack {
            switch mark {
            case .closed:
                Circle().fill(color)
            case .open:
                Circle()
                    .fill(ink.surface)
                    .overlay(Circle().stroke(color, lineWidth: 2))
            case .half:
                Circle()
                    .fill(ink.surface)
                    .overlay(Circle().stroke(color, lineWidth: 2))
                Circle()
                    .fill(color)
                    .mask(alignment: .bottom) {
                        Rectangle().frame(height: diameter / 2)
                    }
            }
        }
        .frame(width: diameter, height: diameter)
    }

    private var instrumentName: String {
        switch kind {
        case .xiao: "洞箫"
        case .dizi: "竹笛"
        case .shakuhachi: "尺八"
        }
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
        return "\(holes.count)孔\(instrumentName)，" + parts.joined(separator: "，")
    }
}

// MARK: - 乐器图标

/// 乐器切换条的线稿图标（设计坐标 24 × 24）。
struct InstrumentGlyph: View {
    let instrumentId: String
    var color: Color
    var lineWidth: CGFloat = 1.5

    var body: some View {
        Canvas { ctx, size in
            let t = FigureTransform(
                fitting: CGSize(width: 24, height: 24),
                in: CGRect(origin: .zero, size: size)
            )
            let stroke = StrokeStyle(lineWidth: lineWidth, lineCap: .round, lineJoin: .round)
            ctx.stroke(t.path(shape), with: .color(color), style: stroke)
            ctx.fill(t.path(dots), with: .color(color))
        }
        .accessibilityHidden(true)
    }

    private func shape(_ p: inout Path) {
        switch instrumentId {
        // 竖立正视：吉他细腰、下箱宽、长琴颈、3+3 弦轴；尤克里里圆胖、短琴颈、2+2 弦轴。
        case "guitar":
            body(&p, segments: [
                [(12, 22.6), (15.4, 22.6), (17.2, 21), (17.2, 18.6)],
                [(17.2, 18.6), (17.2, 16.6), (15, 16.4), (15, 14.8)],
                [(15, 14.8), (15, 13.6), (15.9, 13.6), (15.9, 12.3)],
                [(15.9, 12.3), (15.9, 10.9), (14.4, 10.2), (13, 10.2)],
            ])
            line(&p, (11, 10.2), (11, 5.2))
            line(&p, (13, 10.2), (13, 5.2))
            p.addRoundedRect(in: CGRect(x: 10.1, y: 0.8, width: 3.8, height: 4.4), cornerSize: CGSize(width: 1, height: 1))
            p.addEllipse(in: CGRect(x: 10.4, y: 13.1, width: 3.2, height: 3.2))
            line(&p, (9.8, 19.6), (14.2, 19.6))
        case "ukulele":
            // 整体约为吉他的 0.78 倍、与吉他同一底线，一眼能看出是小一号的琴；尺寸小，省去琴码免得糊成一团。
            body(&p, segments: [
                [(12, 22.4), (14.34, 22.4), (15.43, 21.31), (15.43, 19.9)],
                [(15.43, 19.9), (15.43, 18.66), (14.57, 18.42), (14.57, 17.56)],
                [(14.57, 17.56), (14.57, 16.78), (15.04, 16.63), (15.04, 15.93)],
                [(15.04, 15.93), (15.04, 14.99), (13.95, 14.44), (12.78, 14.44)],
            ])
            line(&p, (11.22, 14.44), (11.22, 10.7))
            line(&p, (12.78, 14.44), (12.78, 10.7))
            p.addRoundedRect(in: CGRect(x: 10.67, y: 8.2, width: 2.65, height: 2.5), cornerSize: CGSize(width: 0.8, height: 0.8))
            p.addEllipse(in: CGRect(x: 10.99, y: 16.24, width: 2.03, height: 2.03))
        case "guqin":
            p.move(to: CGPoint(x: 2, y: 10))
            p.addLine(to: CGPoint(x: 19, y: 8.5))
            p.addQuadCurve(to: CGPoint(x: 22.5, y: 12), control: CGPoint(x: 22.5, y: 8.5))
            p.addQuadCurve(to: CGPoint(x: 19, y: 15.5), control: CGPoint(x: 22.5, y: 15.5))
            p.addLine(to: CGPoint(x: 2, y: 14))
            p.closeSubpath()
            for y in [11.0, 12.3, 13.4] as [CGFloat] {
                p.move(to: CGPoint(x: 3.5, y: y - 0.4))
                p.addLine(to: CGPoint(x: 18.5, y: y))
            }
        case "zhudi":
            p.move(to: CGPoint(x: 3, y: 19))
            p.addLine(to: CGPoint(x: 19, y: 3))
            p.move(to: CGPoint(x: 5, y: 21))
            p.addLine(to: CGPoint(x: 21, y: 5))
            p.move(to: CGPoint(x: 3, y: 19))
            p.addLine(to: CGPoint(x: 5, y: 21))
            p.move(to: CGPoint(x: 19, y: 3))
            p.addLine(to: CGPoint(x: 21, y: 5))
        case "dongxiao":
            p.addRoundedRect(in: CGRect(x: 9.5, y: 2.5, width: 5, height: 19.5), cornerSize: CGSize(width: 1.6, height: 1.6))
            p.move(to: CGPoint(x: 10.8, y: 2.6))
            p.addQuadCurve(to: CGPoint(x: 13.2, y: 2.6), control: CGPoint(x: 12, y: 5))
        default:
            p.move(to: CGPoint(x: 10, y: 2.5))
            p.addLine(to: CGPoint(x: 14, y: 2.5))
            p.addLine(to: CGPoint(x: 15, y: 18.5))
            p.addQuadCurve(to: CGPoint(x: 12, y: 22), control: CGPoint(x: 16.2, y: 22))
            p.addQuadCurve(to: CGPoint(x: 9, y: 18.5), control: CGPoint(x: 7.8, y: 22))
            p.closeSubpath()
            p.move(to: CGPoint(x: 9.6, y: 8.5))
            p.addLine(to: CGPoint(x: 14.4, y: 8.5))
            p.move(to: CGPoint(x: 9.3, y: 14.5))
            p.addLine(to: CGPoint(x: 14.7, y: 14.5))
        }
    }

    private typealias Pt = (CGFloat, CGFloat)

    private func line(_ p: inout Path, _ a: Pt, _ b: Pt) {
        p.move(to: CGPoint(x: a.0, y: a.1))
        p.addLine(to: CGPoint(x: b.0, y: b.1))
    }

    /// 以 x = 12 为轴左右对称的琴身：`segments` 为右半边自底部中点到琴颈接口的三次曲线，
    /// 左半边镜像生成。
    private func body(_ p: inout Path, segments: [[Pt]]) {
        func pt(_ v: Pt, mirrored: Bool = false) -> CGPoint {
            CGPoint(x: mirrored ? 24 - v.0 : v.0, y: v.1)
        }
        guard let first = segments.first?.first, let last = segments.last?.last else { return }
        p.move(to: pt(first))
        for seg in segments {
            p.addCurve(to: pt(seg[3]), control1: pt(seg[1]), control2: pt(seg[2]))
        }
        p.addLine(to: pt(last, mirrored: true))
        for seg in segments.reversed() {
            p.addCurve(
                to: pt(seg[0], mirrored: true),
                control1: pt(seg[2], mirrored: true),
                control2: pt(seg[1], mirrored: true)
            )
        }
        p.closeSubpath()
    }

    private func dots(_ p: inout Path) {
        func dot(_ x: CGFloat, _ y: CGFloat, _ r: CGFloat = 0.9) {
            p.addEllipse(in: CGRect(x: x - r, y: y - r, width: r * 2, height: r * 2))
        }
        switch instrumentId {
        case "guitar":
            for y in [1.7, 3.0, 4.3] as [CGFloat] {
                dot(8.6, y, 0.55)
                dot(15.4, y, 0.55)
            }
        case "ukulele":
            for y in [8.91, 10.0] as [CGFloat] {
                dot(9.58, y, 0.5)
                dot(14.42, y, 0.5)
            }
        case "zhudi":
            dot(16.6, 7.4)
            for step in 0..<3 { dot(12.6 - CGFloat(step) * 1.9, 11.4 + CGFloat(step) * 1.9, 0.75) }
        case "dongxiao":
            for step in 0..<4 { dot(12, 8.5 + CGFloat(step) * 3.2, 0.8) }
        case "guqin", "shakuhachi": break
        default:
            dot(12, 11.5, 0.75)
            dot(12, 17, 0.75)
        }
    }
}
