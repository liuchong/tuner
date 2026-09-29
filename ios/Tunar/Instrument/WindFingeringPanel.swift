import SwiftUI

/// 管乐型号控件：调性 / 尺寸下拉 + 孔制分段（仅洞箫有两种孔制）。
struct WindVariantControls: View {
    @ObservedObject var vm: InstrumentViewModel

    var body: some View {
        HStack(spacing: Lumen.Spacing.sm) {
            Menu {
                ForEach(vm.keyNames, id: \.self) { name in
                    Button(name) { vm.selectKey(name) }
                }
            } label: {
                RoundedControlLabel(title: vm.keyName)
            }
            .accessibilityLabel("\(vm.windFigure.displayName)型号，\(vm.keyName)")
            Spacer(minLength: 0)
            if vm.holeSystems.count > 1 {
                LumenSegmented(
                    options: vm.holeSystems,
                    selected: vm.holeSystem,
                    title: { $0 },
                    onSelect: vm.selectHoleSystem
                )
                .accessibilityLabel("\(vm.windFigure.displayName)孔制")
            }
        }
    }
}

/// 管乐主指法表卡片；十二音完整表在全屏详情中显示，不替换主表。
struct WindFingeringPanel: View {
    @Environment(\.lumen) private var palette
    @ObservedObject var vm: InstrumentViewModel
    @State private var showingDetail = false

    var body: some View {
        WindFingeringTable(
            vm: vm,
            notes: vm.scaleNotes,
            scope: .scale,
            title: vm.keyDisplay,
            metrics: .phone,
            ink: palette.figureInk,
            onOpenDetail: vm.supportsChromatic ? { showingDetail = true } : nil
        )
        .padding(.horizontal, Lumen.Spacing.sm)
        .padding(.bottom, Lumen.Spacing.sm)
        .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(palette.lineSubtle, lineWidth: 1))
        .fullScreenCover(isPresented: $showingDetail) {
            WindChromaticDetail(vm: vm)
        }
    }
}

private struct WindChromaticDetail: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.lumen) private var palette
    @ObservedObject var vm: InstrumentViewModel

    var body: some View {
        NavigationStack {
            AuroraBackground(tuneCents: vm.centsToTarget) {
                WindFingeringTable(
                    vm: vm,
                    notes: vm.chromaticNotes,
                    scope: .chromatic,
                    title: "\(vm.detailKeyDisplay) · 十二孔位三音区",
                    metrics: .phone,
                    ink: palette.figureInk,
                    onOpenDetail: nil
                )
                .padding(.horizontal, Lumen.Spacing.sm)
                .padding(.bottom, Lumen.Spacing.sm)
                .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(palette.lineSubtle, lineWidth: 1))
                .padding(Lumen.Spacing.page)
            }
            .navigationTitle("\(vm.windFigure.displayName)完整指法")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                        .foregroundStyle(palette.accent)
                        .accessibilityHint("返回主表并保留转调和滚动位置")
                }
            }
        }
        .onAppear { vm.setChromaticDetailPresented(true) }
        .onDisappear { vm.setChromaticDetailPresented(false) }
    }
}
