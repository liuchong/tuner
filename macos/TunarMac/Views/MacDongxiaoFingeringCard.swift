import SwiftUI

private enum MacDongxiaoHoleGeometry {
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

private let macDongxiaoRegisters: [WindRegister] = [
    .low,
    .middle,
    .high,
]

private extension WindRegister {
    var macTitle: String {
        switch self {
        case .low: "低音"
        case .middle: "中音"
        case .high: "高音"
        }
    }

    var macTechnique: String {
        switch self {
        case .low: "缓吹"
        case .middle: "超吹"
        case .high: "急吹"
        }
    }
}

/// macOS 洞箫七声主表；完整十二音表使用独立大尺寸 sheet。
struct MacDongxiaoFingeringCard: View {
    @ObservedObject var vm: InstrumentViewModel
    @State private var showingDetail = false

    var body: some View {
        MacDongxiaoTable(
            vm: vm,
            notes: vm.scaleNotes,
            scope: .scale,
            title: vm.keyDisplay,
            diagramHeight: 410,
            onOpenDetail: { showingDetail = true }
        )
        .sheet(isPresented: $showingDetail) {
            MacDongxiaoDetail(vm: vm)
                .frame(minWidth: 900, minHeight: 700)
        }
    }
}

private struct MacDongxiaoTable: View {
    @ObservedObject var vm: InstrumentViewModel
    let notes: [ChartNoteUi]
    let scope: DongxiaoFingeringScope
    let title: String
    let diagramHeight: CGFloat
    let onOpenDetail: (() -> Void)?
    @State private var solfegeDragOffset: CGFloat = 0
    @State private var lastDragTranslation: CGFloat = 0
    @State private var pendingSolfegeSteps = 0

    var body: some View {
        MacCard {
            VStack(spacing: 0) {
                titleRow
                GeometryReader { geometry in
                    ZStack(alignment: .topLeading) {
                        alignmentGuides(width: geometry.size.width)
                        HStack(alignment: .top, spacing: 18) {
                            if let diagramNote {
                                MacFullDongxiaoDiagram(
                                    holes: diagramNote.holes,
                                    backHoleCount: vm.backHoleCount,
                                    highlighted: diagramNote.active
                                )
                                .frame(width: 150)
                                .frame(maxHeight: .infinity)
                                .animation(.easeInOut(duration: 0.16), value: diagramNote.id)
                            }
                            registerGrid(
                                width: max(geometry.size.width - 168, 1),
                                height: geometry.size.height
                            )
                        }
                    }
                    .frame(width: geometry.size.width, height: geometry.size.height)
                }
                .frame(height: diagramHeight)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("洞箫七声指法表")
    }

    private var titleRow: some View {
        HStack {
            Text(displayedTitle)
                .font(.headline)
                .foregroundStyle(MacTheme.accent)
                .contentTransition(.numericText())
                .animation(.easeOut(duration: 0.18), value: displayedTitle)
            Spacer()
            if let onOpenDetail {
                Button(action: onOpenDetail) {
                    Label("十二音详情", systemImage: "arrow.up.left.and.arrow.down.right")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("打开洞箫十二音完整指法")
                .accessibilityHint("在大尺寸窗口显示十二孔位的低中高三个音区，资料缺失处留空")
            }
        }
        .padding(.bottom, 10)
        .contentShape(Rectangle())
        .onTapGesture { onOpenDetail?() }
    }

    private func registerGrid(width: CGFloat, height: CGFloat) -> some View {
        let spacing: CGFloat = 10
        let columnWidth = max(132, (width - spacing * 2) / 3)
        // 实时命中的音区列尽量滚到视口中心；越界时系统夹到合法位移，只表达趋势。
        return ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: width < 478) {
                HStack(spacing: spacing) {
                    ForEach(macDongxiaoRegisters, id: \.self) { register in
                        registerColumn(register, width: columnWidth, height: height)
                            .id(register)
                    }
                }
                .frame(minWidth: width, minHeight: height, alignment: .leading)
            }
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
        ZStack(alignment: .top) {
            VStack(spacing: 1) {
                Text(register.macTitle)
                    .font(.caption.weight(.semibold))
                Text(register.macTechnique)
                    .font(.system(size: 9, weight: .medium))
                    .foregroundStyle(.tertiary)
            }
            .foregroundStyle(.secondary)
            .frame(width: width)
            .padding(.top, 5)

            ForEach(XiaoFingeringLayout.anchorGroups(for: notes)) { group in
                HStack(spacing: 3) {
                    ForEach(group.rows) { row in
                        if let note = row.note(in: register) {
                            registerCell(note)
                        }
                    }
                }
                .frame(width: width - 6)
                .position(
                    x: width / 2,
                    y: MacDongxiaoHoleGeometry.centerY(
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
        HStack(spacing: 4) {
            Text(note.noteName.replacingOccurrences(of: "#", with: "♯"))
                .font(.system(size: 12, weight: note.active ? .bold : .semibold))
                .foregroundStyle(note.inScale ? MacTheme.accent : .secondary)
                .contentShape(Rectangle())
                .onTapGesture { preview(note) }

            if let mark = techniqueMark(note) {
                Text(mark)
                    .font(.system(size: 8, weight: .bold))
                    .foregroundStyle(.orange)
            }

            Text(
                shiftedSolfege(note.solfege, by: pendingSolfegeSteps)
                    .replacingOccurrences(of: "#", with: "♯")
            )
                .font(.system(size: 10, weight: .semibold))
                .foregroundStyle(MacTheme.accent)
                .contentTransition(.numericText())
                .animation(.easeInOut(duration: 0.16), value: pendingSolfegeSteps)
                .frame(minWidth: 22, minHeight: 20)
                .background(Color(nsColor: .controlBackgroundColor), in: RoundedRectangle(cornerRadius: 6))
                .overlay(
                    RoundedRectangle(cornerRadius: 6)
                        .stroke(MacTheme.accent.opacity(0.35), lineWidth: 1)
                )
                .offset(y: solfegeDragOffset)
                .contentShape(Rectangle())
                .onTapGesture { preview(note) }
                .gesture(solfegeDragGesture)
                .accessibilityLabel(
                    "唱名\(shiftedSolfege(note.solfege, by: pendingSolfegeSteps))，"
                        + "筒音作\(shiftedSolfege(currentTongyinSolfege, by: pendingSolfegeSteps))"
                )
                .accessibilityHint("点按预览；上下拖动时逐档循环，松手吸附到最近唱名档")
                .accessibilityAdjustableAction { direction in
                    commitTongyinSteps(direction == .increment ? 1 : -1)
                }
        }
        .padding(.horizontal, 5)
        .frame(height: 26)
        .background(rowBackground(note), in: RoundedRectangle(cornerRadius: 7))
        .overlay(
            RoundedRectangle(cornerRadius: 7)
                .stroke(MacTheme.accent, lineWidth: isPinned(note) ? 1 : 0)
        )
        .accessibilityValue(accessibilityState(note))
    }

    private var diagramNote: ChartNoteUi? {
        switch scope {
        case .scale: vm.scaleDiagramNote
        case .chromatic: vm.chromaticDiagramNote
        }
    }

    /// 实时命中用填充，点选钉住只描边，避免两种高亮看起来相同。
    private func rowBackground(_ note: ChartNoteUi) -> Color {
        note.active ? MacTheme.accent.opacity(0.13) : .clear
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

    private func preview(_ note: ChartNoteUi) {
        vm.selectFingeringPreview(note.id, scope: scope)
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
        DragGesture(minimumDistance: 6)
            .onChanged { value in
                let delta = value.translation.height - lastDragTranslation
                lastDragTranslation = value.translation.height
                var offset = solfegeDragOffset + delta
                var steps = 0
                while offset <= -32 {
                    offset += 32
                    steps += 1
                }
                while offset >= 32 {
                    offset -= 32
                    steps -= 1
                }
                solfegeDragOffset = offset
                if steps != 0 {
                    pendingSolfegeSteps += steps
                }
            }
            .onEnded { _ in
                let steps = Int((-solfegeDragOffset / 32).rounded())
                let totalSteps = pendingSolfegeSteps + steps
                let snapStart = solfegeDragOffset + CGFloat(steps) * 32
                lastDragTranslation = 0
                pendingSolfegeSteps = 0
                if totalSteps != 0 {
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
        let values = scope == .scale
            ? ["1", "2", "3", "4", "5", "6", "7"]
            : ["1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "b7", "7"]
        guard let index = values.firstIndex(of: solfege) else { return solfege }
        return values[(index + steps % values.count + values.count) % values.count]
    }

    private func alignmentGuides(width: CGFloat) -> some View {
        Path { path in
            for index in 0..<vm.holeCount {
                let y = MacDongxiaoHoleGeometry.centerY(
                    holeIndex: index,
                    holeCount: vm.holeCount,
                    height: diagramHeight
                )
                path.move(to: CGPoint(x: 75, y: y))
                path.addLine(to: CGPoint(x: width - 8, y: y))
            }
            let outletY = MacDongxiaoHoleGeometry.centerY(
                holeIndex: nil,
                holeCount: vm.holeCount,
                height: diagramHeight
            )
            path.move(to: CGPoint(x: 75, y: outletY))
            path.addLine(to: CGPoint(x: width - 8, y: outletY))
        }
        .stroke(
            Color.primary.opacity(0.12),
            style: StrokeStyle(lineWidth: 0.8)
        )
    }
}

private struct MacFullDongxiaoDiagram: View {
    let holes: [HoleMark]
    let backHoleCount: Int
    let highlighted: Bool

    var body: some View {
        GeometryReader { geometry in
            let size = geometry.size
            let radius = min(max(size.width * 0.085, 6), 11)
            let bodyWidth = max(size.width * 0.30, radius * 2.8)
            let centerX = size.width * 0.46
            let top = size.height * 0.08
            let bottom = size.height * 0.90
            let ordered = holes.indices.reversed().map { index in
                (sourceIndex: index, mark: holes[index], isBack: index >= holes.count - backHoleCount)
            }

            ZStack {
                RoundedRectangle(cornerRadius: bodyWidth * 0.28)
                    .fill(Color(nsColor: .controlBackgroundColor))
                    .overlay(
                        RoundedRectangle(cornerRadius: bodyWidth * 0.28)
                            .stroke(
                                highlighted ? MacTheme.accent.opacity(0.9) : Color.primary.opacity(0.22),
                                lineWidth: highlighted ? 2 : 1.5
                            )
                    )
                    .frame(width: bodyWidth, height: bottom - top)
                    .position(x: centerX, y: (top + bottom) / 2)

                Path { path in
                    let left = centerX - bodyWidth / 2
                    path.move(to: CGPoint(x: left, y: top + radius * 0.35))
                    path.addLine(to: CGPoint(x: left + bodyWidth, y: top - radius * 0.25))
                }
                .stroke(Color.primary.opacity(0.4), style: StrokeStyle(lineWidth: 2, lineCap: .round))

                Path { path in
                    path.move(to: CGPoint(x: centerX - radius * 0.75, y: top - radius * 0.15))
                    path.addQuadCurve(
                        to: CGPoint(x: centerX + radius * 0.75, y: top - radius * 0.15),
                        control: CGPoint(x: centerX, y: top + radius * 1.1)
                    )
                }
                .stroke(
                    highlighted ? MacTheme.accent : Color.primary.opacity(0.7),
                    style: StrokeStyle(lineWidth: 2, lineCap: .round)
                )

                Ellipse()
                    .stroke(Color.primary.opacity(0.4), lineWidth: 2)
                    .frame(width: bodyWidth, height: radius * 1.1)
                    .position(x: centerX, y: bottom + radius * 0.2)

                ForEach(Array(ordered.enumerated()), id: \.offset) { _, hole in
                    fingerHole(hole.mark, isBack: hole.isBack, diameter: radius * 2)
                        .position(
                            x: hole.sourceIndex == 0 && !hole.isBack
                                ? centerX - bodyWidth * 0.07
                                : centerX,
                            y: MacDongxiaoHoleGeometry.centerY(
                                holeIndex: hole.sourceIndex,
                                holeCount: holes.count,
                                height: size.height
                            )
                        )
                }
            }
        }
        .accessibilityElement()
        .accessibilityLabel("\(holes.count)孔洞箫当前指法")
    }

    @ViewBuilder
    private func fingerHole(_ mark: HoleMark, isBack: Bool, diameter: CGFloat) -> some View {
        // 孔位固定中性墨色，命中信号只体现在管身描边上。
        let ink = isBack ? Color.orange : Color.primary
        ZStack {
            switch mark {
            case .closed:
                Circle().fill(ink)
            case .open:
                Circle()
                    .fill(Color(nsColor: .controlBackgroundColor))
                    .overlay(Circle().stroke(ink, lineWidth: 2))
            case .half:
                Circle()
                    .fill(Color(nsColor: .controlBackgroundColor))
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
}

private struct MacDongxiaoDetail: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var vm: InstrumentViewModel

    var body: some View {
        MacPageBackground {
            VStack(spacing: 16) {
                HStack {
                    Text("洞箫完整指法")
                        .font(.title.bold())
                    Spacer()
                    Button("关闭") { dismiss() }
                        .keyboardShortcut(.cancelAction)
                        .accessibilityHint("返回七声主表并保留转调和滚动位置")
                }
                MacDongxiaoTable(
                    vm: vm,
                    notes: vm.chromaticNotes,
                    scope: .chromatic,
                    title: "\(vm.detailKeyDisplay) · 十二孔位三音区",
                    diagramHeight: 540,
                    onOpenDetail: nil
                )
            }
            .padding(24)
        }
        .onAppear { vm.setChromaticDetailPresented(true) }
        .onDisappear { vm.setChromaticDetailPresented(false) }
    }
}
