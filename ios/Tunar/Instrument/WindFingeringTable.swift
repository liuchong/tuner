import SwiftUI

/// 指法表尺寸（iOS 与 macOS 各一套，交互与布局规则共用）。
struct WindTableMetrics {
    var figureWidth: CGFloat
    var figureSpacing: CGFloat
    var columnSpacing: CGFloat
    var columnMinWidth: CGFloat
    var titleFont: CGFloat
    var registerFont: CGFloat
    var techniqueFont: CGFloat
    var noteFont: CGFloat
    var markFont: CGFloat
    var badgeFont: CGFloat
    var badgeMinSize: CGFloat
    var cellHeight: CGFloat
    var cellCorner: CGFloat
    var dragStep: CGFloat
    var horizontalPadding: CGFloat

    static let phone = WindTableMetrics(
        figureWidth: 92,
        figureSpacing: 6,
        columnSpacing: 4,
        columnMinWidth: 70,
        titleFont: 14,
        registerFont: 10,
        techniqueFont: 7.5,
        noteFont: 9.5,
        markFont: 6.5,
        badgeFont: 8,
        badgeMinSize: 17,
        cellHeight: 21,
        cellCorner: 5,
        dragStep: 28,
        horizontalPadding: 12
    )

    static let desktop = WindTableMetrics(
        figureWidth: 150,
        figureSpacing: 18,
        columnSpacing: 10,
        columnMinWidth: 132,
        titleFont: 15,
        registerFont: 12,
        techniqueFont: 9,
        noteFont: 12,
        markFont: 8,
        badgeFont: 10,
        badgeMinSize: 22,
        cellHeight: 26,
        cellCorner: 7,
        dragStep: 32,
        horizontalPadding: 0
    )
}

private let windRegisters: [WindRegister] = [.low, .middle, .high]

extension WindFigureKind {
    func registerTitle(_ register: WindRegister) -> String {
        switch (self, register) {
        case (.shakuhachi, .low): "乙音"
        case (.shakuhachi, .middle): "甲音"
        case (.shakuhachi, .high): "大甲"
        case (_, .low): "低音"
        case (_, .middle): "中音"
        case (_, .high): "高音"
        }
    }

    func registerTechnique(_ register: WindRegister) -> String {
        switch (self, register) {
        case (.shakuhachi, .low): "otsu"
        case (.shakuhachi, .middle): "kan"
        case (.shakuhachi, .high): "daikan"
        case (_, .low): "缓吹"
        case (_, .middle): "超吹"
        case (_, .high): "急吹"
        }
    }

    var displayName: String {
        switch self {
        case .xiao: "洞箫"
        case .dizi: "竹笛"
        case .shakuhachi: "尺八"
        }
    }
}

/// 管乐锚定指法表：左侧整支乐器线稿，右侧三音区列，每一行对齐到该指法最高开孔的孔心。
///
/// 实时命中用填充、点选钉住只描边；支持筒音转调的型号可上下拖动唱名徽标逐档转调。
struct WindFingeringTable: View {
    @ObservedObject var vm: InstrumentViewModel
    let notes: [ChartNoteUi]
    let scope: WindFingeringScope
    let title: String
    let metrics: WindTableMetrics
    let ink: FigureInk
    let onOpenDetail: (() -> Void)?
    @State private var solfegeDragOffset: CGFloat = 0
    @State private var lastDragTranslation: CGFloat = 0
    @State private var pendingSolfegeSteps = 0

    private var figure: WindFigureKind { vm.windFigure }

    var body: some View {
        VStack(spacing: 0) {
            titleRow
            GeometryReader { geometry in
                let size = geometry.size
                ZStack(alignment: .topLeading) {
                    alignmentGuides(size: size)
                    HStack(alignment: .top, spacing: metrics.figureSpacing) {
                        if let diagramNote {
                            WindTubeFigure(
                                kind: figure,
                                holes: diagramNote.holes,
                                backHoleCount: vm.backHoleCount,
                                highlighted: diagramNote.active,
                                ink: ink
                            )
                            .frame(width: metrics.figureWidth)
                            .frame(maxHeight: .infinity)
                            .animation(.easeInOut(duration: 0.16), value: diagramNote.id)
                        }
                        registerGrid(
                            width: max(size.width - metrics.figureWidth - metrics.figureSpacing, 1),
                            height: size.height
                        )
                    }
                }
                .frame(width: size.width, height: size.height)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(figure.displayName)\(scope == .scale ? "主" : "十二音")指法表")
    }

    private var titleRow: some View {
        HStack(spacing: 8) {
            Text(displayedTitle)
                .font(.system(size: metrics.titleFont, weight: .semibold))
                .foregroundStyle(ink.accent)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .contentTransition(.numericText())
                .animation(.easeOut(duration: 0.18), value: displayedTitle)
            Spacer(minLength: 4)
            if vm.supportsTongyin && scope == .scale {
                Text("拖动唱名转调")
                    .font(.system(size: metrics.techniqueFont + 2))
                    .foregroundStyle(ink.inkFaint)
                    .lineLimit(1)
            }
            if let onOpenDetail {
                Button(action: onOpenDetail) {
                    Label("十二音", systemImage: "arrow.up.left.and.arrow.down.right")
                        .font(.system(size: metrics.titleFont - 2, weight: .medium))
                        .foregroundStyle(ink.accent)
                        .labelStyle(.titleAndIcon)
                        .padding(.horizontal, 8)
                        .frame(minHeight: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("打开\(figure.displayName)十二音完整指法")
                .accessibilityHint("显示十二孔位的低中高三个音区，资料缺失处留空")
            }
        }
        .padding(.horizontal, metrics.horizontalPadding)
        .padding(.top, 8)
        .padding(.bottom, 6)
    }

    private func registerGrid(width: CGFloat, height: CGFloat) -> some View {
        let columnWidth = max(metrics.columnMinWidth, (width - metrics.columnSpacing * 2) / 3)
        let overflow = columnWidth * 3 + metrics.columnSpacing * 2 > width + 0.5
        // 实时命中的音区列尽量滚到视口中心；越界时系统夹到合法位移，只表达趋势。
        return ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: overflow) {
                HStack(spacing: metrics.columnSpacing) {
                    ForEach(windRegisters, id: \.self) { register in
                        registerColumn(register, width: columnWidth, height: height)
                            .id(register)
                    }
                }
                .frame(minWidth: width, minHeight: height, alignment: .leading)
            }
            .scrollBounceBehavior(.basedOnSize)
            .onChange(of: activeRegister) { _, register in
                centerRegister(register, proxy: proxy)
            }
            .onAppear { centerRegister(activeRegister, proxy: proxy) }
        }
    }

    private var activeRegister: WindRegister? {
        notes.first { $0.active }?.register
    }

    private func centerRegister(_ register: WindRegister?, proxy: ScrollViewProxy) {
        guard let register else { return }
        withAnimation(.easeInOut(duration: 0.22)) {
            proxy.scrollTo(register, anchor: .center)
        }
    }

    private func registerColumn(
        _ register: WindRegister,
        width: CGFloat,
        height: CGFloat
    ) -> some View {
        let isActive = activeRegister == register
        return ZStack(alignment: .top) {
            VStack(spacing: 0) {
                Text(figure.registerTitle(register))
                    .font(.system(size: metrics.registerFont, weight: .semibold))
                    .foregroundStyle(isActive ? ink.accent : ink.ink.opacity(0.7))
                Text(figure.registerTechnique(register))
                    .font(.system(size: metrics.techniqueFont, weight: .medium))
                    .foregroundStyle(ink.inkFaint)
            }
            .frame(width: width)
            .padding(.top, 2)

            ForEach(XiaoFingeringLayout.anchorGroups(for: notes)) { group in
                HStack(spacing: 1) {
                    ForEach(group.rows) { row in
                        if let note = row.note(in: register) {
                            registerCell(note)
                        }
                    }
                }
                .frame(width: width - 2)
                .position(
                    x: width / 2,
                    y: WindFigureGeometry.centerY(
                        figure,
                        holeIndex: group.anchorHole.map(Int.init),
                        holeCount: vm.holeCount,
                        height: height
                    )
                )
            }
        }
        .frame(width: width, height: height)
    }

    private func registerCell(_ note: ChartNoteUi) -> some View {
        HStack(spacing: 2) {
            Text(note.noteName.replacingOccurrences(of: "#", with: "♯"))
                .font(.system(size: metrics.noteFont, weight: note.active ? .bold : .semibold))
                .foregroundStyle(note.inScale ? ink.accent : ink.ink.opacity(0.65))
                .lineLimit(1)
                .minimumScaleFactor(0.75)

            if let mark = techniqueMark(note) {
                Text(mark)
                    .font(.system(size: metrics.markFont, weight: .bold))
                    .foregroundStyle(ink.back)
            }

            solfegeBadge(note)
        }
        .padding(.horizontal, 2)
        .frame(height: metrics.cellHeight)
        .background(
            note.active ? ink.accent.opacity(0.14) : .clear,
            in: RoundedRectangle(cornerRadius: metrics.cellCorner)
        )
        .overlay(
            RoundedRectangle(cornerRadius: metrics.cellCorner)
                .stroke(ink.accent, lineWidth: isPinned(note) ? 1 : 0)
        )
        .contentShape(Rectangle())
        .onTapGesture { vm.selectFingeringPreview(note.id, scope: scope) }
        .accessibilityElement(children: .contain)
        .accessibilityValue(accessibilityState(note))
    }

    @ViewBuilder
    private func solfegeBadge(_ note: ChartNoteUi) -> some View {
        let badge = Text(
            shiftedSolfege(note.solfege, by: pendingSolfegeSteps)
                .replacingOccurrences(of: "#", with: "♯")
        )
        .font(.system(size: metrics.badgeFont, weight: .semibold))
        .foregroundStyle(ink.accent)
        .contentTransition(.numericText())
        .animation(.easeInOut(duration: 0.16), value: pendingSolfegeSteps)
        .frame(minWidth: metrics.badgeMinSize, minHeight: metrics.badgeMinSize)
        .background(ink.surface, in: RoundedRectangle(cornerRadius: metrics.cellCorner))
        .overlay(
            RoundedRectangle(cornerRadius: metrics.cellCorner)
                .stroke(ink.accent.opacity(0.35), lineWidth: 0.8)
        )

        if vm.supportsTongyin {
            badge
                .offset(y: solfegeDragOffset)
                .gesture(solfegeDragGesture)
                .accessibilityElement()
                .accessibilityLabel(
                    "唱名\(shiftedSolfege(note.solfege, by: pendingSolfegeSteps))，"
                        + "筒音作\(shiftedSolfege(currentTongyinSolfege, by: pendingSolfegeSteps))"
                )
                .accessibilityHint("上下拖动时逐档循环转调，松手吸附到最近唱名档")
                .accessibilityAdjustableAction { direction in
                    commitTongyinSteps(direction == .increment ? 1 : -1)
                }
        } else {
            badge
        }
    }

    private var diagramNote: ChartNoteUi? {
        switch scope {
        case .scale: vm.scaleDiagramNote
        case .chromatic: vm.chromaticDiagramNote
        }
    }

    private func isPinned(_ note: ChartNoteUi) -> Bool {
        guard !note.active else { return false }
        switch scope {
        case .scale: return note.id == vm.scalePreviewNoteId
        case .chromatic: return note.id == vm.chromaticPreviewNoteId
        }
    }

    private func accessibilityState(_ note: ChartNoteUi) -> String {
        if note.active { return "实时命中" }
        return isPinned(note) ? "已钉住" : "未选中"
    }

    private func techniqueMark(_ note: ChartNoteUi) -> String? {
        guard note.fingeringKind == .combination else { return nil }
        return note.holes.contains(.half) ? "半" : "叉"
    }

    private var currentTongyinSolfege: String {
        vm.tongyinOptions.first { $0.degree == currentTongyinDegree }?.solfege ?? ""
    }

    private var currentTongyinDegree: UInt8 {
        scope == .scale ? vm.tongyinDegree : vm.detailTongyinDegree
    }

    private var displayedTitle: String {
        guard pendingSolfegeSteps != 0,
              let separator = title.range(of: " · ")
        else { return title }
        return "筒音作\(shiftedSolfege(currentTongyinSolfege, by: pendingSolfegeSteps))"
            + String(title[separator.lowerBound...])
    }

    private var solfegeDragGesture: some Gesture {
        let step = metrics.dragStep
        return DragGesture(minimumDistance: 6)
            .onChanged { value in
                let delta = value.translation.height - lastDragTranslation
                lastDragTranslation = value.translation.height
                var offset = solfegeDragOffset + delta
                var steps = 0
                while offset <= -step {
                    offset += step
                    steps += 1
                }
                while offset >= step {
                    offset -= step
                    steps -= 1
                }
                solfegeDragOffset = offset
                if steps != 0 {
                    TunarHaptics.shared.tick()
                    pendingSolfegeSteps += steps
                }
            }
            .onEnded { _ in
                let steps = Int((-solfegeDragOffset / step).rounded())
                let totalSteps = pendingSolfegeSteps + steps
                let snapStart = solfegeDragOffset + CGFloat(steps) * step
                lastDragTranslation = 0
                pendingSolfegeSteps = 0
                if totalSteps != 0 {
                    TunarHaptics.shared.tick()
                    withAnimation(.easeInOut(duration: 0.14)) {
                        commitTongyinSteps(totalSteps)
                    }
                }
                solfegeDragOffset = snapStart
                withAnimation(.snappy(duration: 0.18)) {
                    solfegeDragOffset = 0
                }
            }
    }

    private func commitTongyinSteps(_ steps: Int) {
        switch scope {
        case .scale: vm.stepTongyin(steps)
        case .chromatic: vm.stepDetailTongyin(steps)
        }
    }

    private func shiftedSolfege(_ solfege: String, by steps: Int) -> String {
        guard vm.supportsTongyin else { return solfege }
        let values = scope == .scale
            ? ["1", "2", "3", "4", "5", "6", "7"]
            : ["1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "b7", "7"]
        guard let index = values.firstIndex(of: solfege) else { return solfege }
        return values[(index + steps % values.count + values.count) % values.count]
    }

    private func alignmentGuides(size: CGSize) -> some View {
        Path { path in
            let startX = metrics.figureWidth * 0.68
            let endX = size.width - 4
            for group in XiaoFingeringLayout.anchorGroups(for: notes) {
                let y = WindFigureGeometry.centerY(
                    figure,
                    holeIndex: group.anchorHole.map(Int.init),
                    holeCount: vm.holeCount,
                    height: size.height
                )
                path.move(to: CGPoint(x: startX, y: y))
                path.addLine(to: CGPoint(x: endX, y: y))
            }
        }
        .stroke(ink.lineFaint, style: StrokeStyle(lineWidth: 0.7))
        .allowsHitTesting(false)
    }
}
