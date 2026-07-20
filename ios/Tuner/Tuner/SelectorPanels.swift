import SwiftUI

/// 调性选择面板内容（design-system §6.3 浮动展开面板的内容部分）：
/// 标题 + 12 主音两行六列 + 调式 chips（大调/小调/宫/商/角/徵/羽）。
struct KeySelectorPanel: View {
    @Environment(\.lumen) private var palette
    var currentKey: KeyMode
    var onSelect: (KeyMode) -> Void

    private let modeOrder: [ModeKind] = [.major, .minor, .gong, .shang, .jue, .zhi, .yu]

    var body: some View {
        VStack(alignment: .leading, spacing: Lumen.Spacing.md) {
            Text("调性")
                .font(.title3.bold())
                .foregroundStyle(palette.inkPrimary)
            Text("相同音名在不同调性下显示不同唱名")
                .font(Lumen.caption)
                .foregroundStyle(palette.inkSecondary)

            ForEach(0..<2, id: \.self) { row in
                HStack(spacing: Lumen.Spacing.sm) {
                    ForEach(0..<6, id: \.self) { col in
                        let pc = UInt8(row * 6 + col)
                        let selected = pc == currentKey.tonicPc
                        Button {
                            onSelect(KeyMode(tonicPc: pc, mode: currentKey.mode))
                        } label: {
                            Text(Tonic.labels[Int(pc)])
                                .font(Lumen.label)
                                .fontWeight(selected ? .bold : .regular)
                                .foregroundStyle(selected ? palette.bgCanvas : palette.inkPrimary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(selected ? palette.accent : palette.bgSurface,
                                            in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Lumen.Spacing.sm) {
                    ForEach(modeOrder, id: \.self) { mode in
                        let selected = mode == currentKey.mode
                        Button {
                            onSelect(KeyMode(tonicPc: currentKey.tonicPc, mode: mode))
                        } label: {
                            Text(mode.label)
                                .font(Lumen.label)
                                .foregroundStyle(selected ? palette.bgCanvas : palette.inkPrimary)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(selected ? palette.accent : palette.bgSurface,
                                            in: Capsule())
                        }
                    }
                }
            }
        }
        .padding(Lumen.Spacing.lg)
    }
}

/// 律制选择面板内容（PRO 模式，design-system §6.9）：12 / 19 / 24 / 31 平均律。
struct TemperamentSelectorPanel: View {
    @Environment(\.lumen) private var palette
    var current: Int
    var onSelect: (Int) -> Void

    private let divisions = [12, 19, 24, 31]

    var body: some View {
        VStack(alignment: .leading, spacing: Lumen.Spacing.md) {
            Text("律制")
                .font(.title3.bold())
                .foregroundStyle(palette.inkPrimary)
            Text("N 平均律（以 A4 为参考的级进网格）")
                .font(Lumen.caption)
                .foregroundStyle(palette.inkSecondary)
            HStack(spacing: Lumen.Spacing.sm) {
                ForEach(divisions, id: \.self) { n in
                    let selected = n == current
                    Button {
                        onSelect(n)
                    } label: {
                        Text("\(n)-TET")
                            .font(Lumen.label)
                            .fontWeight(selected ? .bold : .regular)
                            .foregroundStyle(selected ? palette.bgCanvas : palette.inkPrimary)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(selected ? palette.accent : palette.bgSurface,
                                        in: RoundedRectangle(cornerRadius: 12))
                    }
                }
            }
        }
        .padding(Lumen.Spacing.lg)
    }
}

/// 选择器胶囊（accent 10% 底 + accent 文字）。
struct SelectorCapsule: View {
    @Environment(\.lumen) private var palette
    var text: String
    var enabled = true
    var selected = false
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(text)
                .font(Lumen.readoutSolfege)
                .foregroundStyle(palette.accent)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(palette.accent.opacity(enabled ? 0.10 : 0.04), in: Capsule())
                .overlay(
                    Capsule().stroke(selected ? palette.accent : .clear, lineWidth: 1.5)
                )
        }
        .opacity(enabled ? 1 : 0.4)
        .accessibilityLabel(text)
    }
}

/// 数据/唱名胶囊容器。
struct CapsuleContent<Content: View>: View {
    @Environment(\.lumen) private var palette
    @ViewBuilder var content: () -> Content

    var body: some View {
        HStack { content() }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(palette.bgSurface, in: Capsule())
    }
}
