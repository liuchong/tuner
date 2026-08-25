import SwiftUI

/// 管乐入口：只有洞箫使用新交互，竹笛与尺八保持普通指法表。
struct WindFingeringPanel: View {
    @ObservedObject var vm: InstrumentViewModel

    var body: some View {
        if vm.usesDongxiaoInteraction {
            DongxiaoFingeringPanel(vm: vm)
        } else {
            ClassicWindFingeringPanel(vm: vm)
        }
    }
}

private struct ClassicWindFingeringPanel: View {
    @Environment(\.lumen) private var palette
    @ObservedObject var vm: InstrumentViewModel

    var body: some View {
        VStack(spacing: Lumen.Spacing.sm) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Lumen.Spacing.sm) {
                    chartMenu
                    Spacer(minLength: Lumen.Spacing.sm)
                    tongyinPicker
                }
                VStack(alignment: .leading, spacing: Lumen.Spacing.sm) {
                    chartMenu
                    if !vm.classicTongyinOptions.isEmpty {
                        tongyinPicker
                    }
                }
            }

            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(vm.classicNotes) { note in
                        HStack {
                            Text(note.label)
                                .font(Lumen.label)
                                .foregroundStyle(palette.inkPrimary)
                            Spacer()
                            Text(note.noteName.replacingOccurrences(of: "#", with: "♯"))
                                .font(Lumen.label)
                                .fontWeight(note.active ? .bold : .regular)
                                .foregroundStyle(palette.inkPrimary)
                            Text(note.solfege)
                                .font(Lumen.caption)
                                .foregroundStyle(palette.inkSecondary)
                                .frame(width: 32, alignment: .trailing)
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(note.active ? palette.accent.opacity(0.10) : .clear)
                    }
                }
            }
            .frame(maxHeight: 220)
        }
    }

    private var chartMenu: some View {
        Menu {
            ForEach(vm.chartGroups, id: \.self) { group in
                Button(group) {
                    vm.selectClassicChart(group: group, tongyin: vm.classicTongyin)
                }
            }
        } label: {
            RoundedControlLabel(title: vm.chartGroup)
        }
    }

    private var tongyinPicker: some View {
        HStack(spacing: Lumen.Spacing.xs) {
            ForEach(vm.classicTongyinOptions, id: \.self) { option in
                let selected = option == vm.classicTongyin
                Button {
                    vm.selectClassicChart(group: vm.chartGroup, tongyin: option)
                } label: {
                    Text("作\(option)")
                        .font(Lumen.label)
                        .lineLimit(1)
                        .foregroundStyle(selected ? palette.bgCanvas : palette.inkPrimary)
                        .padding(.horizontal, 12)
                        .frame(height: 48)
                        .background(selected ? palette.accent : palette.bgSurface, in: Capsule())
                        .overlay(
                            Capsule().stroke(selected ? palette.accent : palette.lineSubtle)
                        )
                }
            }
        }
    }
}
