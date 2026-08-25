import SwiftUI

private let dongxiaoRegisters: [WindRegister] = [
    .low,
    .middle,
    .high,
]

private extension WindRegister {
    var title: String {
        switch self {
        case .low: "低音"
        case .middle: "中音"
        case .high: "高音"
        }
    }

    var technique: String {
        switch self {
        case .low: "缓吹"
        case .middle: "超吹"
        case .high: "急吹"
        }
    }
}

/// 洞箫七声主表。十二音表只在独立详情中显示。
struct DongxiaoFingeringPanel: View {
    @Environment(\.lumen) private var palette
    @ObservedObject var vm: InstrumentViewModel
    @State private var showingDetail = false

    var body: some View {
        VStack(spacing: Lumen.Spacing.sm) {
            variantRow
            DongxiaoFingeringCard(
                vm: vm,
                notes: vm.scaleNotes,
                scope: .scale,
                title: vm.keyDisplay,
                diagramHeight: 280,
                onOpenDetail: { showingDetail = true }
            )
        }
        .fullScreenCover(isPresented: $showingDetail) {
            DongxiaoChromaticDetail(vm: vm)
        }
    }

    private var variantRow: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Lumen.Spacing.sm) {
                keyMenu
                Spacer(minLength: Lumen.Spacing.sm)
                holeSystemPicker
            }
            VStack(alignment: .leading, spacing: Lumen.Spacing.sm) {
                keyMenu
                holeSystemPicker
            }
        }
    }

    private var keyMenu: some View {
        Menu {
            ForEach(vm.keyNames, id: \.self) { name in
                Button(name) { vm.selectKey(name) }
            }
        } label: {
            RoundedControlLabel(title: vm.keyName)
        }
    }

    @ViewBuilder
    private var holeSystemPicker: some View {
        if vm.holeSystems.count > 1 {
            HStack(spacing: 0) {
                ForEach(vm.holeSystems, id: \.self) { name in
                    let selected = name == vm.holeSystem
                    Button { vm.selectHoleSystem(name) } label: {
                        Text(name)
                            .font(Lumen.label)
                            .lineLimit(1)
                            .foregroundStyle(selected ? palette.bgCanvas : palette.inkPrimary)
                            .padding(.horizontal, 16)
                            .frame(height: 48)
                            .background(selected ? palette.accent : palette.bgSurface)
                    }
                }
            }
            .clipShape(Capsule())
            .overlay(Capsule().stroke(palette.lineSubtle, lineWidth: 1))
            .accessibilityLabel("洞箫孔制")
        }
    }
}

private struct DongxiaoFingeringCard: View {
    @Environment(\.lumen) private var palette
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
        VStack(spacing: 0) {
            titleRow
            GeometryReader { geometry in
                ZStack(alignment: .topLeading) {
                    alignmentGuides(width: geometry.size.width)
                    HStack(alignment: .top, spacing: 6) {
                        if let diagramNote {
                            HoleDiagram(
                                holes: diagramNote.holes,
                                backHoleCount: vm.backHoleCount,
                                highlighted: diagramNote.active
                            )
                            .frame(width: 92)
                            .frame(maxHeight: .infinity)
                            .padding(.leading, 4)
                            .animation(.easeInOut(duration: 0.16), value: diagramNote.id)
                        }

                        registerGrid(
                            width: max(geometry.size.width - 102, 1),
                            height: geometry.size.height
                        )
                    }
                }
            }
            .frame(height: diagramHeight)
            .padding(.horizontal, 8)
            .padding(.bottom, 8)
        }
        .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16).stroke(palette.lineSubtle, lineWidth: 1)
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel("洞箫七声指法表")
    }

    private var titleRow: some View {
        HStack(spacing: 8) {
            Text(displayedTitle)
                .font(Lumen.label)
                .fontWeight(.semibold)
                .foregroundStyle(palette.accent)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .contentTransition(.numericText())
                .animation(.easeOut(duration: 0.18), value: displayedTitle)
            Spacer()
            if let onOpenDetail {
                Button(action: onOpenDetail) {
                    Label("十二音详情", systemImage: "arrow.up.left.and.arrow.down.right")
                        .font(Lumen.caption)
                        .foregroundStyle(palette.accent)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("打开洞箫十二音完整指法")
                .accessibilityHint("全屏显示十二孔位的低中高三个音区，资料缺失处留空")
            }
        }
        .padding(.horizontal, horizontalPadding)
        .padding(.top, 10)
        .padding(.bottom, 7)
        .contentShape(Rectangle())
        .onTapGesture { onOpenDetail?() }
    }

    private func registerGrid(width: CGFloat, height: CGFloat) -> some View {
        let spacing: CGFloat = 4
        let columnWidth = max(70, (width - spacing * 2) / 3)
        // 实时命中的音区列尽量滚到视口中心；越界时系统夹到合法位移，只表达趋势。
        return ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: width < 260) {
                HStack(spacing: spacing) {
                    ForEach(dongxiaoRegisters, id: \.self) { register in
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
        ZStack(alignment: .top) {
            VStack(spacing: 0) {
                Text(register.title)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(palette.inkSecondary)
                Text(register.technique)
                    .font(.system(size: 7.5, weight: .medium))
                    .foregroundStyle(palette.inkFaint)
            }
            .frame(width: width)
            .padding(.top, 3)

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
                    y: DongxiaoHoleGeometry.centerY(
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
                .font(.system(size: 9, weight: note.active ? .bold : .semibold))
                .foregroundStyle(note.inScale ? palette.accent : palette.inkSecondary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .contentShape(Rectangle())
                .onTapGesture { preview(note) }

            if let mark = techniqueMark(note) {
                Text(mark)
                    .font(.system(size: 6.5, weight: .bold))
                    .foregroundStyle(palette.tuneNear)
            }

            Text(
                shiftedSolfege(note.solfege, by: pendingSolfegeSteps)
                    .replacingOccurrences(of: "#", with: "♯")
            )
                .font(.system(size: 7.5, weight: .semibold))
                .foregroundStyle(palette.accent)
                .contentTransition(.numericText())
                .animation(.easeInOut(duration: 0.16), value: pendingSolfegeSteps)
                .frame(minWidth: 16, minHeight: 16)
                .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 5))
                .overlay(
                    RoundedRectangle(cornerRadius: 5)
                        .stroke(palette.accent.opacity(0.35), lineWidth: 0.8)
                )
                .offset(y: solfegeDragOffset)
                .contentShape(Rectangle())
                .onTapGesture { preview(note) }
                .gesture(solfegeDragGesture)
                .accessibilityLabel(
                    "唱名\(shiftedSolfege(note.solfege, by: pendingSolfegeSteps))，"
                        + "筒音作\(shiftedSolfege(currentTongyinSolfege, by: pendingSolfegeSteps))"
                )
                .accessibilityHint("点按预览指法；上下拖动时逐档循环，松手吸附到最近唱名档")
                .accessibilityAdjustableAction { direction in
                    commitTongyinSteps(direction == .increment ? 1 : -1)
                }
        }
        .padding(.horizontal, 2)
        .frame(height: 20)
        .background(rowBackground(note), in: RoundedRectangle(cornerRadius: 5))
        .overlay(
            RoundedRectangle(cornerRadius: 5)
                .stroke(palette.accent, lineWidth: isPinned(note) ? 1 : 0)
        )
        .accessibilityElement(children: .contain)
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
        note.active ? palette.accent.opacity(0.13) : .clear
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
                while offset <= -28 {
                    offset += 28
                    steps += 1
                }
                while offset >= 28 {
                    offset -= 28
                    steps -= 1
                }
                solfegeDragOffset = offset
                if steps != 0 {
                    TunarHaptics.shared.tick()
                    pendingSolfegeSteps += steps
                }
            }
            .onEnded { _ in
                let steps = Int((-solfegeDragOffset / 28).rounded())
                let totalSteps = pendingSolfegeSteps + steps
                let snapStart = solfegeDragOffset + CGFloat(steps) * 28
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
        let values = scope == .scale
            ? ["1", "2", "3", "4", "5", "6", "7"]
            : ["1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "b7", "7"]
        guard let index = values.firstIndex(of: solfege) else { return solfege }
        return values[(index + steps % values.count + values.count) % values.count]
    }

    private func alignmentGuides(width: CGFloat) -> some View {
        Path { path in
            for index in 0..<vm.holeCount {
                let y = DongxiaoHoleGeometry.centerY(
                    holeIndex: index,
                    holeCount: vm.holeCount,
                    height: diagramHeight
                )
                path.move(to: CGPoint(x: 62, y: y))
                path.addLine(to: CGPoint(x: width - 4, y: y))
            }
            let outletY = DongxiaoHoleGeometry.centerY(
                holeIndex: nil,
                holeCount: vm.holeCount,
                height: diagramHeight
            )
            path.move(to: CGPoint(x: 62, y: outletY))
            path.addLine(to: CGPoint(x: width - 4, y: outletY))
        }
        .stroke(
            palette.lineSubtle.opacity(0.85),
            style: StrokeStyle(lineWidth: 0.7)
        )
    }

    private let horizontalPadding: CGFloat = 12
}

private struct DongxiaoChromaticDetail: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.lumen) private var palette
    @ObservedObject var vm: InstrumentViewModel

    var body: some View {
        NavigationStack {
            AuroraBackground(tuneCents: vm.centsToTarget) {
                DongxiaoFingeringCard(
                    vm: vm,
                    notes: vm.chromaticNotes,
                    scope: .chromatic,
                    title: "\(vm.detailKeyDisplay) · 十二孔位三音区",
                    diagramHeight: 440,
                    onOpenDetail: nil
                )
                .padding(Lumen.Spacing.page)
            }
            .navigationTitle("洞箫完整指法")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                        .foregroundStyle(palette.accent)
                        .accessibilityHint("返回七声主表并保留转调和滚动位置")
                }
            }
        }
        .onAppear { vm.setChromaticDetailPresented(true) }
        .onDisappear { vm.setChromaticDetailPresented(false) }
    }
}
