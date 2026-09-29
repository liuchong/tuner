import SwiftUI

struct DesktopInstrumentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = InstrumentViewModel()
    @StateObject private var access = MacMicrophoneAccess()

    var body: some View {
        MacPageBackground {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("乐器调音")
                        .font(.largeTitle.bold())
                    Text("定弦和指法来自共享 Rust 预设")
                        .foregroundStyle(.secondary)
                    MicrophoneStatusBanner(access: access, onRetry: retryCapture)
                    MacCard {
                        VStack(alignment: .leading, spacing: 14) {
                            MacInstrumentSwitcher(
                                instruments: vm.instruments,
                                selectedId: vm.instrumentId,
                                onSelect: vm.selectInstrument
                            )
                            controls
                        }
                    }
                    HStack(alignment: .top, spacing: 16) {
                        figure
                            .frame(maxWidth: .infinity)
                        gauge
                            .frame(width: 340)
                    }
                }
                .padding(24)
            }
        }
        .onAppear {
            applyCaptureLifecycle(scenePhase)
        }
        .onDisappear { vm.releaseCapture() }
        .onChange(of: scenePhase) { _, phase in
            applyCaptureLifecycle(phase)
        }
    }

    private func applyCaptureLifecycle(_ phase: ScenePhase) {
        switch DesktopCaptureLifecycle.action(isWindowActive: phase == .active) {
        case .acquire:
            access.request {
                vm.startCapture()
            }
        case .release:
            vm.releaseCapture()
        }
    }

    private func retryCapture() {
        guard scenePhase == .active else { return }
        vm.releaseCapture()
        access.request {
            vm.startCapture()
        }
    }

    @ViewBuilder
    private var controls: some View {
        if vm.kind == .string {
            HStack(spacing: 16) {
                Picker("定弦", selection: Binding(
                    get: { vm.tuningId },
                    set: { value in vm.selectTuning(value) }
                )) {
                    ForEach(vm.tunings, id: \.id) { Text($0.displayName).tag($0.id) }
                }
                .frame(maxWidth: 280)
                if case .headstock(let style) = vm.stringFigure {
                    Picker("琴头", selection: Binding(
                        get: { style },
                        set: { vm.selectHeadstockStyle($0) }
                    )) {
                        ForEach(HeadstockStyle.allCases, id: \.self) {
                            Text($0.displayName).tag($0)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 200)
                    .help("Fender 式六联排 / Gibson 式三加三")
                }
                Spacer()
                Picker("识别", selection: Binding(
                    get: { vm.mode },
                    set: { value in vm.selectMode(value) }
                )) {
                    Text("自动").tag(SelectionMode.auto)
                    Text("手动").tag(SelectionMode.manual)
                }
                .pickerStyle(.segmented)
                .frame(width: 180)
            }
        } else {
            HStack(spacing: 16) {
                Picker("调性/型号", selection: Binding(
                    get: { vm.keyName },
                    set: { vm.selectKey($0) }
                )) {
                    ForEach(vm.keyNames, id: \.self) { Text($0).tag($0) }
                }
                .frame(maxWidth: 280)
                if vm.holeSystems.count > 1 {
                    Picker("孔制", selection: Binding(
                        get: { vm.holeSystem },
                        set: { vm.selectHoleSystem($0) }
                    )) {
                        ForEach(vm.holeSystems, id: \.self) { Text($0).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 160)
                }
                Spacer()
            }
        }
    }

    @ViewBuilder
    private var figure: some View {
        if vm.kind == .wind {
            MacWindFingeringCard(vm: vm)
        } else {
            MacCard {
                switch vm.stringFigure {
                case .guqin:
                    VStack(spacing: 18) {
                        HStack(spacing: 8) {
                            ForEach(vm.strings) { item in
                                MacStringButton(
                                    item: item,
                                    selected: vm.selectedStringIndex == item.index - 1,
                                    compact: true
                                ) { vm.selectString(item.index - 1) }
                                .frame(height: 64)
                            }
                        }
                        GuqinFigure(
                            strings: vm.strings,
                            selectedIndex: vm.selectedStringIndex,
                            ink: MacTheme.figureInk,
                            onSelect: vm.selectString
                        )
                        .aspectRatio(
                            GuqinGeometry.design.width / GuqinGeometry.design.height,
                            contentMode: .fit
                        )
                    }
                    .frame(minHeight: 400)
                case .headstock, .ukuleleHeadstock:
                    if let layout = HeadstockLayout.layout(for: vm.stringFigure) {
                        HeadstockPanel(
                            layout: layout,
                            strings: vm.strings,
                            selectedIndex: vm.selectedStringIndex,
                            ink: MacTheme.figureInk,
                            buttonSize: CGSize(width: 110, height: 52),
                            onSelect: vm.selectString
                        ) { item in
                            MacStringButton(
                                item: item,
                                selected: vm.selectedStringIndex == item.index - 1,
                                compact: false
                            ) { vm.selectString(item.index - 1) }
                        }
                        .frame(height: 400)
                    }
                case .none:
                    EmptyView()
                }
            }
        }
    }

    private var gauge: some View {
        MacCard {
            VStack(spacing: 10) {
                Text("目标")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(
                    readoutTarget.map { $0.replacingOccurrences(of: "#", with: "♯") }
                        ?? (vm.kind == .wind ? "按指法吹奏" : "自动识别")
                )
                .font(readoutTarget == nil ? .title3.weight(.semibold) : .largeTitle.bold())
                .foregroundStyle(
                    readoutTarget == nil
                        ? .secondary
                        : MacTheme.tuneColor(vm.centsToTarget.map { Double($0) })
                )
                .frame(height: 40)
                DesktopPitchGauge(cents: vm.centsToTarget.map { Double($0) })
                    .frame(height: 250)
                Text(vm.centsToTarget.map { String(format: "%+.1f cents", $0) } ?? "请发声")
                    .font(.title3.monospacedDigit())
                    .foregroundStyle(MacTheme.tuneColor(vm.centsToTarget.map { Double($0) }))
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var readoutTarget: String? {
        if let name = vm.targetNoteName { return name }
        guard vm.kind == .string, let index = vm.selectedStringIndex else { return nil }
        return vm.strings[safe: index]?.noteName
    }
}

/// 六种乐器等宽平铺，每个乐器一枚线稿图标。
private struct MacInstrumentSwitcher: View {
    let instruments: [Instrument]
    let selectedId: String
    let onSelect: (String) -> Void

    var body: some View {
        HStack(spacing: 8) {
            ForEach(instruments, id: \.id) { inst in
                let selected = inst.id == selectedId
                Button { onSelect(inst.id) } label: {
                    HStack(spacing: 8) {
                        InstrumentGlyph(
                            instrumentId: inst.id,
                            color: selected ? MacTheme.accent : .secondary
                        )
                        .frame(width: 22, height: 22)
                        Text(inst.displayName)
                            .font(.system(size: 13, weight: selected ? .semibold : .regular))
                            .foregroundStyle(selected ? MacTheme.accent : .primary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background(
                        selected ? MacTheme.accent.opacity(0.12) : Color.primary.opacity(0.04),
                        in: RoundedRectangle(cornerRadius: 12)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(
                                selected ? MacTheme.accent : Color.primary.opacity(0.1),
                                lineWidth: selected ? 1.5 : 1
                            )
                    )
                    .contentShape(RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(inst.displayName)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }
}

/// 弦按钮：弦号徽标 + 音名 + 唱名；古琴一排时竖排。
private struct MacStringButton: View {
    let item: StringItemUi
    let selected: Bool
    let compact: Bool
    let onClick: () -> Void

    var body: some View {
        let highlighted = item.active || selected
        let tint = item.inTune && highlighted ? MacTheme.tuneIn : (highlighted ? MacTheme.accent : Color.secondary)
        Button(action: onClick) {
            Group {
                if compact {
                    VStack(spacing: 2) {
                        badge(tint: tint)
                        noteName
                        solfege
                    }
                } else {
                    HStack(spacing: 8) {
                        badge(tint: tint)
                        VStack(alignment: .leading, spacing: 0) {
                            noteName
                            solfege
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 10)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(
                highlighted ? tint.opacity(0.14) : Color.primary.opacity(0.04),
                in: RoundedRectangle(cornerRadius: 12)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(highlighted ? tint : Color.primary.opacity(0.1), lineWidth: highlighted ? 1.5 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(item.index) 弦 \(item.noteName)，唱名 \(item.solfege)")
        .accessibilityValue(item.inTune ? "已调准" : (highlighted ? "当前弦" : "未选中"))
    }

    private func badge(tint: Color) -> some View {
        ZStack {
            Circle().stroke(tint, lineWidth: 1.2)
            if item.inTune {
                Image(systemName: "checkmark").font(.system(size: 9, weight: .bold))
            } else {
                Text("\(item.index)").font(.system(size: 11, weight: .semibold).monospacedDigit())
            }
        }
        .foregroundStyle(tint)
        .frame(width: 20, height: 20)
    }

    private var noteName: some View {
        Text(item.noteName.replacingOccurrences(of: "#", with: "♯"))
            .font(.system(size: 16, weight: .bold).monospacedDigit())
            .lineLimit(1)
    }

    private var solfege: some View {
        Text(item.solfege)
            .font(.system(size: 11))
            .foregroundStyle(.secondary)
    }
}
