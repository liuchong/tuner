import SwiftUI

/// 乐器面板（design-system §6.6）：乐器切换条 + 型号控件 + 乐器图示 + 目标读数与表盘成组。
struct InstrumentView: View {
    @Environment(\.lumen) private var palette
    @StateObject private var vm = InstrumentViewModel()

    @State private var animatedCents: Float = 0
    @State private var appliedLaunchOverride = false

    var body: some View {
        AuroraBackground(tuneCents: vm.centsToTarget) {
            GeometryReader { geometry in
                VStack(spacing: Lumen.Spacing.md) {
                    InstrumentSwitcher(
                        instruments: vm.instruments,
                        selectedId: vm.instrumentId,
                        onSelect: vm.selectInstrument
                    )

                    controlRow

                    figureArea
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .layoutPriority(1)

                    TargetReadout(
                        targetName: readoutTarget,
                        placeholder: vm.kind == .wind ? "按指法吹奏" : "自动识别",
                        cents: vm.centsToTarget
                    )

                    HaloDial(
                        cents: vm.centsToTarget != nil ? animatedCents : nil,
                        clarity: 1
                    )
                    .frame(height: dialHeight(total: geometry.size.height))
                    .opacity(vm.centsToTarget == nil ? 1 : 0.35 + Double(vm.displayStrength) * 0.65)
                }
                .padding(.horizontal, Lumen.Spacing.xl - 4)
                .padding(.top, Lumen.Spacing.sm)
                .padding(.bottom, Lumen.Spacing.xs)
            }
        }
        .onAppear {
            if !appliedLaunchOverride, let id = LaunchOverrides.initialInstrument {
                appliedLaunchOverride = true
                vm.selectInstrument(id)
            }
            vm.startCapture()
        }
        .onDisappear { vm.releaseCapture() }
        .onChange(of: vm.centsToTarget) { _, newValue in
            withAnimation(.easeOut(duration: NeedlePresentation.followDuration)) {
                animatedCents = NeedlePresentation.clampedCents(newValue ?? 0)
            }
        }
    }

    /// 表盘随屏高伸缩；管乐指法表更高，表盘让出一部分空间。
    private func dialHeight(total: CGFloat) -> CGFloat {
        let share: CGFloat = vm.kind == .wind ? 0.21 : 0.25
        return min(max(total * share, 132), 220)
    }

    /// 有信号显示实时目标；无信号时手动锁定的弦仍给出目标，自动模式显示占位。
    private var readoutTarget: String? {
        if let name = vm.targetNoteName { return name }
        guard vm.kind == .string, let index = vm.selectedStringIndex else { return nil }
        return vm.strings[safe: index]?.noteName
    }

    @ViewBuilder
    private var controlRow: some View {
        if vm.kind == .string {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Lumen.Spacing.sm) {
                    tuningMenu
                    Spacer(minLength: 0)
                    headstockPicker(segmentPadding: 14)
                    Spacer(minLength: 0)
                    AutoModeToggle(mode: vm.mode, onSelect: vm.selectMode)
                }
                HStack(spacing: 6) {
                    tuningMenu
                    Spacer(minLength: 0)
                    headstockPicker(segmentPadding: 8)
                    Spacer(minLength: 0)
                    AutoModeToggle(mode: vm.mode, onSelect: vm.selectMode, showIcon: false)
                }
            }
        } else {
            WindVariantControls(vm: vm)
        }
    }

    @ViewBuilder
    private var figureArea: some View {
        if vm.kind == .string {
            switch vm.stringFigure {
            case .guqin:
                GuqinPanel(vm: vm, ink: palette.figureInk)
            case .headstock, .ukuleleHeadstock:
                if let layout = HeadstockLayout.layout(for: vm.stringFigure) {
                    HeadstockPanel(
                        layout: layout,
                        strings: vm.strings,
                        selectedIndex: vm.selectedStringIndex,
                        ink: palette.figureInk,
                        buttonSize: CGSize(width: 78, height: 50),
                        onSelect: vm.selectString
                    ) { item in
                        PegButton(
                            item: item,
                            selected: vm.selectedStringIndex == item.index - 1
                        ) { vm.selectString(item.index - 1) }
                    }
                    .animation(.easeInOut(duration: 0.2), value: vm.stringFigure)
                }
            case .none:
                EmptyView()
            }
        } else {
            WindFingeringPanel(vm: vm)
        }
    }

    @ViewBuilder
    private func headstockPicker(segmentPadding: CGFloat) -> some View {
        if case .headstock(let style) = vm.stringFigure {
            LumenSegmented(
                options: HeadstockStyle.allCases,
                selected: style,
                title: \.displayName,
                accessibilityTitle: \.accessibilityName,
                onSelect: vm.selectHeadstockStyle,
                horizontalPadding: segmentPadding,
                equalWidths: true
            )
            .accessibilityLabel("琴头样式")
        }
    }

    private var tuningMenu: some View {
        Menu {
            ForEach(vm.tunings, id: \.id) { tuning in
                Button(tuning.displayName) { vm.selectTuning(tuning.id) }
            }
        } label: {
            RoundedControlLabel(title: vm.tuningName)
        }
        .accessibilityLabel("定弦，\(vm.tuningName)")
    }
}

extension Lumen.Palette {
    /// 乐器线稿配色：主描边弱于正文，琴弦与孔位用墨色，命中与调准沿用语义色。
    var figureInk: FigureInk {
        FigureInk(
            line: inkSecondary.opacity(0.7),
            lineFaint: inkFaint.opacity(0.55),
            ink: inkPrimary,
            inkFaint: inkSecondary.opacity(0.65),
            surface: bgSurfaceEnd,
            accent: accent,
            tuneIn: tuneIn,
            back: tuneNear
        )
    }
}

// MARK: - 乐器切换条

/// 六种乐器方形平铺、两端对齐，不需要横向滚动；每个乐器一枚线稿图标。
struct InstrumentSwitcher: View {
    @Environment(\.lumen) private var palette
    let instruments: [Instrument]
    let selectedId: String
    let onSelect: (String) -> Void

    private let tile: CGFloat = 48

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(instruments.enumerated()), id: \.element.id) { offset, inst in
                if offset > 0 { Spacer(minLength: 4) }
                let selected = inst.id == selectedId
                Button { onSelect(inst.id) } label: {
                    VStack(spacing: 1) {
                        InstrumentGlyph(
                            instrumentId: inst.id,
                            color: selected ? palette.accent : palette.inkSecondary
                        )
                        .frame(width: 22, height: 22)
                        Text(inst.displayName)
                            .font(.system(size: 10, weight: selected ? .semibold : .medium))
                            .foregroundStyle(selected ? palette.accent : palette.inkPrimary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .frame(width: tile, height: tile)
                    .background(
                        selected ? palette.accent.opacity(0.12) : palette.bgSurface,
                        in: RoundedRectangle(cornerRadius: 14)
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .stroke(selected ? palette.accent : palette.lineSubtle, lineWidth: selected ? 1.5 : 1)
                    )
                    .contentShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(PressScaleButtonStyle())
                .accessibilityLabel(inst.displayName)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }
}

/// 按下轻微缩放，给平铺按钮一个物理反馈。
struct PressScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

// MARK: - 通用控件

/// 48pt 高的胶囊分段选择（design-system §6.6）。
struct LumenSegmented<Option: Hashable>: View {
    @Environment(\.lumen) private var palette
    let options: [Option]
    let selected: Option
    let title: (Option) -> String
    var accessibilityTitle: ((Option) -> String)?
    let onSelect: (Option) -> Void
    var horizontalPadding: CGFloat = 12
    /// 各段等宽（取最宽一段），文字居中；用于只有两三项、字数差距大的切换。
    var equalWidths = false

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options, id: \.self) { option in
                let isSelected = option == selected
                Button { onSelect(option) } label: {
                    Text(title(option))
                        .font(Lumen.label)
                        .lineLimit(1)
                        .fixedSize()
                        .foregroundStyle(isSelected ? palette.bgCanvas : palette.inkPrimary)
                        .padding(.horizontal, horizontalPadding)
                        .frame(maxWidth: equalWidths ? .infinity : nil)
                        .frame(height: 48)
                        .background(isSelected ? palette.accent : palette.bgSurface)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(accessibilityTitle?(option) ?? title(option))
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .fixedSize(horizontal: equalWidths, vertical: false)
        .clipShape(Capsule())
        .overlay(Capsule().stroke(palette.lineSubtle, lineWidth: 1))
        .animation(.easeInOut(duration: 0.15), value: selected)
    }
}

/// 48pt 高圆角下拉触发器（design-system §6.6）。
struct RoundedControlLabel: View {
    @Environment(\.lumen) private var palette
    let title: String

    var body: some View {
        HStack(spacing: 8) {
            Text(title)
                .font(Lumen.label)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Image(systemName: "chevron.down")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(palette.inkSecondary)
        }
        .foregroundStyle(palette.inkPrimary)
        .padding(.horizontal, 16)
        .frame(height: 48)
        .background(palette.bgSurface, in: Capsule())
        .overlay(Capsule().stroke(palette.lineSubtle, lineWidth: 1))
    }
}

/// 自动选弦开关：点亮即自动识别最近的弦；点任意弦进入手动锁定，开关随之熄灭，再点回到自动。
struct AutoModeToggle: View {
    @Environment(\.lumen) private var palette
    let mode: SelectionMode
    let onSelect: (SelectionMode) -> Void
    var showIcon = true

    var body: some View {
        let isAuto = mode == .auto
        Button { onSelect(isAuto ? .manual : .auto) } label: {
            HStack(spacing: 6) {
                if showIcon {
                    Image(systemName: isAuto ? "waveform" : "hand.point.up.left")
                        .font(.system(size: 13, weight: .semibold))
                }
                Text(isAuto ? "自动" : "手动")
                    .font(Lumen.label)
                    .fixedSize()
            }
            .foregroundStyle(isAuto ? palette.bgCanvas : palette.inkPrimary)
            .padding(.horizontal, showIcon ? 14 : 12)
            .frame(height: 48)
            .background(isAuto ? palette.accent : palette.bgSurface, in: Capsule())
            .overlay(Capsule().stroke(isAuto ? palette.accent : palette.lineSubtle, lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(PressScaleButtonStyle())
        .animation(.easeInOut(duration: 0.15), value: isAuto)
        .accessibilityLabel("自动选弦")
        .accessibilityValue(isAuto ? "开，自动识别最近的弦" : "关，手动锁定选中的弦")
        .accessibilityAddTraits(.isToggle)
    }
}

/// 目标读数（与表盘成组）：左侧目标音名，右侧音分偏差；无信号时右侧提示发声。高度固定不跳动。
struct TargetReadout: View {
    @Environment(\.lumen) private var palette
    let targetName: String?
    let placeholder: String
    let cents: Float?

    var body: some View {
        let color = cents.map { Lumen.tuneColor(of: $0, palette) } ?? palette.inkSecondary
        HStack(alignment: .center, spacing: Lumen.Spacing.md) {
            VStack(alignment: .leading, spacing: 0) {
                Text("目标")
                    .font(Lumen.caption)
                    .foregroundStyle(palette.inkFaint)
                if let targetName {
                    Text(targetName.replacingOccurrences(of: "#", with: "♯"))
                        .font(.system(size: 28, weight: .bold).monospacedDigit())
                        .foregroundStyle(color)
                        .contentTransition(.numericText())
                } else {
                    Text(placeholder)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(palette.inkSecondary)
                        .frame(height: 34, alignment: .leading)
                }
            }
            Spacer(minLength: 0)
            if let cents {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(String(format: "%+.1f", cents))
                        .font(.system(size: 28, weight: .semibold).monospacedDigit())
                        .contentTransition(.numericText(value: Double(cents)))
                    Text("cents")
                        .font(Lumen.caption)
                        .foregroundStyle(palette.inkSecondary)
                }
                .foregroundStyle(color)
                .transition(.opacity)
            } else {
                HStack(spacing: 6) {
                    Image(systemName: "mic")
                        .font(.system(size: 12, weight: .medium))
                    Text("请发声")
                        .font(Lumen.caption)
                }
                .foregroundStyle(palette.inkSecondary)
                .padding(.horizontal, 14)
                .frame(height: 32)
                .background(palette.bgSurface, in: Capsule())
                .overlay(Capsule().stroke(palette.lineSubtle, lineWidth: 1))
                .transition(.opacity)
            }
        }
        .frame(height: 52)
        .animation(.easeInOut(duration: 0.2), value: cents == nil)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - 弦按钮

private struct StringButtonColors {
    let border: Color
    let fill: Color
    let number: Color

    init(item: StringItemUi, selected: Bool, palette: Lumen.Palette) {
        if item.inTune && (item.active || selected) {
            border = palette.tuneIn
            fill = palette.tuneIn.opacity(0.14)
            number = palette.tuneIn
        } else if item.active || selected {
            border = palette.accent
            fill = palette.accent.opacity(0.14)
            number = palette.accent
        } else {
            border = palette.lineSubtle
            fill = palette.bgSurface
            number = palette.inkSecondary
        }
    }
}

/// 琴头两侧的音高按钮：弦号徽标 + 音名 + 唱名。
struct PegButton: View {
    @Environment(\.lumen) private var palette
    let item: StringItemUi
    let selected: Bool
    let onClick: () -> Void

    private var noteText: some View {
        Text(item.noteName.replacingOccurrences(of: "#", with: "♯"))
            .font(.system(size: 16, weight: .bold).monospacedDigit())
            .foregroundStyle(palette.inkPrimary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
    }

    private var solfegeText: some View {
        Text(item.solfege)
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(palette.inkSecondary)
            .lineLimit(1)
    }

    var body: some View {
        let colors = StringButtonColors(item: item, selected: selected, palette: palette)
        Button(action: onClick) {
            HStack(spacing: 7) {
                ZStack {
                    Circle().stroke(colors.number, lineWidth: 1.2)
                    if item.inTune {
                        Image(systemName: "checkmark")
                            .font(.system(size: 9, weight: .bold))
                    } else {
                        Text("\(item.index)")
                            .font(.system(size: 11, weight: .semibold).monospacedDigit())
                    }
                }
                .foregroundStyle(colors.number)
                .frame(width: 20, height: 20)
                ViewThatFits(in: .vertical) {
                    VStack(alignment: .leading, spacing: 0) {
                        noteText
                        solfegeText
                    }
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        noteText
                        solfegeText
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.leading, 9)
            .padding(.trailing, 6)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(colors.fill, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(colors.border, lineWidth: 1.5))
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("\(item.index) 弦 \(item.noteName)，唱名 \(item.solfege)")
        .accessibilityValue(item.inTune ? "已调准" : (item.active || selected ? "当前弦" : "未选中"))
    }
}

/// 古琴一排七弦的音高按钮：竖排弦号 / 音名 / 唱名。
struct StringButton: View {
    @Environment(\.lumen) private var palette
    let item: StringItemUi
    let selected: Bool
    let onClick: () -> Void

    var body: some View {
        let colors = StringButtonColors(item: item, selected: selected, palette: palette)
        Button(action: onClick) {
            VStack(spacing: 1) {
                Group {
                    if item.inTune {
                        Image(systemName: "checkmark")
                            .font(.system(size: 9, weight: .bold))
                    } else {
                        Text("\(item.index)")
                            .font(.system(size: 10, weight: .semibold).monospacedDigit())
                    }
                }
                .foregroundStyle(colors.number)
                .frame(height: 12)
                Text(item.noteName.replacingOccurrences(of: "#", with: "♯"))
                    .font(.system(size: 15, weight: .bold).monospacedDigit())
                    .foregroundStyle(palette.inkPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(item.solfege)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(palette.inkSecondary)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 60)
            .background(colors.fill, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(colors.border, lineWidth: 1.5))
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("\(item.index) 弦 \(item.noteName)，唱名 \(item.solfege)")
        .accessibilityValue(item.inTune ? "已调准" : (item.active || selected ? "当前弦" : "未选中"))
    }
}

/// 古琴：七弦按钮一排，下方琴面线稿与按钮双向联动。
private struct GuqinPanel: View {
    @ObservedObject var vm: InstrumentViewModel
    let ink: FigureInk

    var body: some View {
        VStack(spacing: Lumen.Spacing.lg) {
            HStack(spacing: 5) {
                ForEach(vm.strings) { item in
                    StringButton(
                        item: item,
                        selected: vm.selectedStringIndex == item.index - 1
                    ) { vm.selectString(item.index - 1) }
                }
            }
            GuqinFigure(
                strings: vm.strings,
                selectedIndex: vm.selectedStringIndex,
                ink: ink,
                onSelect: { index in
                    guard vm.selectedStringIndex != index else { return }
                    TunarHaptics.shared.tick()
                    vm.selectString(index)
                }
            )
            .aspectRatio(GuqinGeometry.design.width / GuqinGeometry.design.height, contentMode: .fit)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}
