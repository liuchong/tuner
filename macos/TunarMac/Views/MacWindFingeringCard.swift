import SwiftUI

/// macOS 管乐主指法表；完整十二音表使用独立大尺寸 sheet。表格本身与 iOS 共用。
struct MacWindFingeringCard: View {
    @ObservedObject var vm: InstrumentViewModel
    @State private var showingDetail = false

    var body: some View {
        MacCard {
            WindFingeringTable(
                vm: vm,
                notes: vm.scaleNotes,
                scope: .scale,
                title: vm.keyDisplay,
                metrics: .desktop,
                ink: MacTheme.figureInk,
                onOpenDetail: vm.supportsChromatic ? { showingDetail = true } : nil
            )
            .frame(height: 430)
        }
        .sheet(isPresented: $showingDetail) {
            MacWindChromaticDetail(vm: vm)
                .frame(minWidth: 900, minHeight: 700)
        }
    }
}

private struct MacWindChromaticDetail: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var vm: InstrumentViewModel

    var body: some View {
        MacPageBackground {
            VStack(spacing: 16) {
                HStack {
                    Text("\(vm.windFigure.displayName)完整指法")
                        .font(.title.bold())
                    Spacer()
                    Button("关闭") { dismiss() }
                        .keyboardShortcut(.cancelAction)
                        .accessibilityHint("返回主表并保留转调和滚动位置")
                }
                WindFingeringTable(
                    vm: vm,
                    notes: vm.chromaticNotes,
                    scope: .chromatic,
                    title: "\(vm.detailKeyDisplay) · 十二孔位三音区",
                    metrics: .desktop,
                    ink: MacTheme.figureInk,
                    onOpenDetail: nil
                )
            }
            .padding(24)
        }
        .onAppear { vm.setChromaticDetailPresented(true) }
        .onDisappear { vm.setChromaticDetailPresented(false) }
    }
}
