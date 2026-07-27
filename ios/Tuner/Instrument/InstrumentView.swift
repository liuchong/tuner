import SwiftUI

/// 乐器面板（design-system §6.6）：卡片行乐器选择 + 弦选择器/指法列表 + 目标读数与表盘成组。
struct InstrumentView: View {
    @Environment(\.lumen) private var palette
    @StateObject private var vm = InstrumentViewModel()

    @State private var animatedCents: Float = 0

    var body: some View {
        AuroraBackground(tuneCents: vm.centsToTarget) {
            VStack(spacing: 0) {
                // 乐器选择卡片行
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Lumen.Spacing.sm) {
                        ForEach(vm.instruments, id: \.id) { inst in
                            let selected = inst.id == vm.instrumentId
                            Button { vm.selectInstrument(inst.id) } label: {
                                HStack(spacing: 6) {
                                    Image(systemName: "music.note")
                                        .font(.system(size: 12))
                                    Text(inst.displayName)
                                        .font(Lumen.label)
                                        .lineLimit(1)
                                }
                                .foregroundStyle(selected ? palette.accent : palette.inkPrimary)
                                .padding(.horizontal, 14)
                                .frame(height: 48)
                                .background(
                                    selected ? palette.accent.opacity(0.10) : palette.bgSurface,
                                    in: RoundedRectangle(cornerRadius: 16)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 16)
                                        .stroke(selected ? palette.accent : palette.lineSubtle, lineWidth: 1.5)
                                )
                            }
                        }
                    }
                }

                // 控制区（弦乐：定弦+模式；管乐：调性+筒音）
                controlSection

                Spacer().frame(maxHeight: .infinity)

                // 目标读数行（与表盘组成视觉组；高度固定，有/无信号同构不跳动）
                targetReadout

                // 表盘区（圆心不放文字；读数在其上方，与本表盘成组）
                HaloDial(
                    cents: vm.centsToTarget != nil ? animatedCents : nil,
                    clarity: 1
                )
                .frame(height: 240)
                .opacity(vm.centsToTarget == nil ? 1 : 0.35 + Double(vm.displayStrength) * 0.65)

                Spacer().frame(maxHeight: .infinity)

                StatusChip(visible: vm.centsToTarget == nil)
                    .animation(.easeInOut(duration: 0.2), value: vm.centsToTarget == nil)
            }
            .padding(Lumen.Spacing.page)
        }
        .onAppear { vm.startCapture() }
        .onDisappear { vm.releaseCapture() }
        .onChange(of: vm.centsToTarget) { _, newValue in
            withAnimation(.spring(response: 0.18, dampingFraction: 0.72)) {
                animatedCents = newValue ?? 0
            }
        }
    }

    @ViewBuilder
    private var controlSection: some View {
        if vm.kind == .string {
            VStack(spacing: Lumen.Spacing.sm) {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: Lumen.Spacing.sm) {
                        tuningMenu
                        Spacer(minLength: Lumen.Spacing.sm)
                        modePicker
                    }
                    VStack(alignment: .leading, spacing: Lumen.Spacing.sm) {
                        tuningMenu
                        modePicker
                    }
                }
                // 弦按钮横排
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: Lumen.Spacing.sm) {
                        ForEach(vm.strings) { s in
                            StringButton(item: s) { vm.selectString(s.index - 1) }
                        }
                    }
                }
            }
        } else {
            VStack(spacing: Lumen.Spacing.sm) {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: Lumen.Spacing.sm) {
                        chartMenu
                        Spacer(minLength: Lumen.Spacing.sm)
                        tongyinPicker
                    }
                    VStack(alignment: .leading, spacing: Lumen.Spacing.sm) {
                        chartMenu
                        if !vm.tongyinOptions.isEmpty {
                            tongyinPicker
                        }
                    }
                }
                // 指法音阶列表
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(vm.notes) { n in
                            HStack {
                                Text(n.label)
                                    .font(Lumen.label)
                                    .foregroundStyle(palette.inkPrimary)
                                Spacer()
                                Text(n.noteName.replacingOccurrences(of: "#", with: "♯"))
                                    .font(Lumen.label)
                                    .fontWeight(n.active ? .bold : .regular)
                                    .foregroundStyle(palette.inkPrimary)
                                Text(n.solfege)
                                    .font(Lumen.caption)
                                    .foregroundStyle(palette.inkSecondary)
                                    .frame(width: 32, alignment: .trailing)
                            }
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(n.active ? palette.accent.opacity(0.10) : .clear)
                        }
                    }
                }
                .frame(maxHeight: 220)
            }
        }
    }

    private var modePicker: some View {
        HStack(spacing: 0) {
            ForEach([SelectionMode.auto, SelectionMode.manual], id: \.self) { m in
                let selected = vm.mode == m
                Button { vm.selectMode(m) } label: {
                    Text(m == .auto ? "自动" : "手动")
                        .font(Lumen.label)
                        .lineLimit(1)
                        .foregroundStyle(selected ? palette.bgCanvas : palette.inkPrimary)
                        .padding(.horizontal, 14)
                        .frame(height: 48)
                        .background(selected ? palette.accent : palette.bgSurface)
                }
            }
        }
        .clipShape(Capsule())
        .overlay(Capsule().stroke(palette.lineSubtle, lineWidth: 1))
    }

    private var tuningMenu: some View {
        Menu {
            ForEach(vm.tunings, id: \.id) { tuning in
                Button(tuning.displayName) { vm.selectTuning(tuning.id) }
            }
        } label: {
            RoundedControlLabel(title: vm.tuningName)
        }
    }

    private var chartMenu: some View {
        Menu {
            ForEach(vm.chartGroups, id: \.self) { group in
                Button(group) { vm.selectChart(group: group, tongyin: vm.tongyin) }
            }
        } label: {
            RoundedControlLabel(title: vm.chartGroup)
        }
    }

    private var tongyinPicker: some View {
        HStack(spacing: Lumen.Spacing.xs) {
            ForEach(vm.tongyinOptions, id: \.self) { option in
                let selected = option == vm.tongyin
                Button { vm.selectChart(group: vm.chartGroup, tongyin: option) } label: {
                    Text("作\(option)")
                        .font(Lumen.label)
                        .lineLimit(1)
                        .foregroundStyle(selected ? palette.bgCanvas : palette.inkPrimary)
                        .padding(.horizontal, 12)
                        .frame(height: 48)
                        .background(selected ? palette.accent : palette.bgSurface, in: Capsule())
                        .overlay(Capsule().stroke(selected ? palette.accent : palette.lineSubtle))
                }
            }
        }
    }

    @ViewBuilder
    private var targetReadout: some View {
        let targetName = vm.targetNoteName
            ?? (vm.kind == .string ? vm.strings[safe: vm.manualIndex]?.noteName : nil)
        HStack {
            if let targetName {
                Text("目标 \(targetName.replacingOccurrences(of: "#", with: "♯"))")
                    .font(Lumen.readoutSolfege)
                    .fontWeight(.bold)
                    .foregroundStyle(
                        vm.centsToTarget.map { Lumen.tuneColor(of: $0, palette) }
                            ?? palette.inkSecondary
                    )
                Text(vm.centsToTarget.map { String(format: "%+.1f cents", $0) } ?? "—")
                    .font(Lumen.readoutValue)
                    .foregroundStyle(
                        vm.centsToTarget.map { Lumen.tuneColor(of: $0, palette) }
                            ?? palette.inkSecondary
                    )
            } else {
                Text("目标 · —")
                    .font(Lumen.readoutSolfege)
                    .foregroundStyle(palette.inkFaint)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.bottom, Lumen.Spacing.sm)
    }
}

private struct RoundedControlLabel: View {
    @Environment(\.lumen) private var palette
    let title: String

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(Lumen.label)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Image(systemName: "chevron.down")
                .font(.caption2)
        }
        .foregroundStyle(palette.inkPrimary)
        .padding(.horizontal, 16)
        .frame(height: 48)
        .background(palette.bgSurface, in: Capsule())
        .overlay(Capsule().stroke(palette.lineSubtle, lineWidth: 1))
    }
}

/// 单个琴弦按钮（design-system §6.3 药丸）。
struct StringButton: View {
    @Environment(\.lumen) private var palette
    var item: StringItemUi
    var onClick: () -> Void

    var body: some View {
        let borderColor: Color = item.inTune ? palette.tuneIn
            : item.active ? palette.accent : palette.lineSubtle
        let containerColor: Color = item.inTune ? palette.tuneIn.opacity(0.12)
            : item.active ? palette.accent.opacity(0.10) : palette.bgSurface
        Button(action: onClick) {
            VStack(spacing: 2) {
                HStack(spacing: 2) {
                    Text("\(item.index)")
                        .font(Lumen.caption)
                        .foregroundStyle(palette.inkSecondary)
                    if item.inTune {
                        Image(systemName: "checkmark")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(palette.tuneIn)
                    }
                }
                Text(item.noteName.replacingOccurrences(of: "#", with: "♯"))
                    .font(Lumen.label)
                    .fontWeight(.bold)
                    .foregroundStyle(palette.inkPrimary)
                Text(item.solfege)
                    .font(Lumen.caption)
                    .foregroundStyle(palette.inkSecondary)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                LinearGradient(
                    colors: [palette.bgSurface, palette.bgSurfaceEnd],
                    startPoint: .top, endPoint: .bottom
                ),
                in: RoundedRectangle(cornerRadius: 24)
            )
            .background(containerColor, in: RoundedRectangle(cornerRadius: 24))
            .overlay(
                RoundedRectangle(cornerRadius: 24).stroke(borderColor, lineWidth: 1.5)
            )
            .overlay(alignment: .top) {
                palette.highlightInner
                    .frame(height: 1)
                    .padding(.horizontal, 12)
            }
        }
        .scaleEffect(1.0)
        .accessibilityLabel("\(item.index) 弦 \(item.noteName)，\(item.inTune ? "已调准" : "未调准")")
    }
}

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
