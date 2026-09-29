import SwiftUI

/// 小工具页（spec-ui §4.1）：固定频率的纯正弦波工具；离开本页或窗口失去前台即停止。
struct DesktopToolsView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = ToolsViewModel()

    var body: some View {
        MacPageBackground {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("小工具")
                        .font(.largeTitle.bold())
                    Text("固定频率的辅助音频，不随 A4 校准变化")
                        .foregroundStyle(.secondary)
                    ForEach(vm.tones, id: \.id) { tone in
                        toneCard(tone)
                    }
                    Text("离开本页或切到其他应用会自动停止。音频仅作舒缓辅助，不能替代药物或医疗建议。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .padding(28)
                .frame(maxWidth: 760, alignment: .leading)
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { vm.stop() }
        }
        .onDisappear(perform: vm.stop)
    }

    private func toneCard(_ tone: ToolTone) -> some View {
        let playing = vm.playingId == tone.id
        return MacCard {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(tone.displayName)
                        .font(.title3.weight(.semibold))
                    Text(playing ? "\(tone.summary) · 播放中" : tone.summary)
                        .foregroundStyle(playing ? MacTheme.accent : .secondary)
                }
                Spacer(minLength: 0)
                Button {
                    vm.toggle(tone)
                } label: {
                    Label(playing ? "停止" : "播放", systemImage: playing ? "stop.fill" : "play.fill")
                        .frame(minWidth: 72)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .accessibilityIdentifier("tools.\(tone.id)")
            }
        }
    }
}
