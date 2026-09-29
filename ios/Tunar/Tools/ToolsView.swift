import SwiftUI
import UIKit

/// 小工具页（spec-ui §4.1）：固定频率的纯正弦波工具，前台播放，播放时保持亮屏。
struct ToolsView: View {
    @Environment(\.lumen) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = ToolsViewModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Lumen.Spacing.lg) {
                ForEach(vm.tones, id: \.id) { tone in
                    ToolToneCard(tone: tone, playing: vm.playingId == tone.id) {
                        vm.toggle(tone)
                    }
                }
                Text("播放时保持亮屏；离开本页或切到后台会自动停止。音频仅作舒缓辅助，不能替代药物或医疗建议。")
                    .font(Lumen.caption)
                    .foregroundStyle(palette.inkFaint)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(Lumen.Spacing.page)
        }
        .background(palette.bgCanvas.ignoresSafeArea())
        .navigationTitle("小工具")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: vm.playingId) { _, id in
            UIApplication.shared.isIdleTimerDisabled = id != nil
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { vm.stop() }
        }
        .onDisappear {
            vm.stop()
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }
}

private struct ToolToneCard: View {
    @Environment(\.lumen) private var palette
    let tone: ToolTone
    let playing: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: Lumen.Spacing.md) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(tone.displayName)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(palette.inkPrimary)
                    Text(playing ? "\(tone.summary) · 播放中" : tone.summary)
                        .font(Lumen.label)
                        .foregroundStyle(playing ? palette.accent : palette.inkSecondary)
                }
                Spacer(minLength: 0)
                Image(systemName: playing ? "stop.fill" : "play.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(playing ? palette.bgCanvas : palette.accent)
                    .frame(width: 52, height: 52)
                    .background(playing ? palette.accent : palette.bgSurfaceRaised, in: Circle())
            }
            .padding(16)
            .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 18))
            .overlay(
                RoundedRectangle(cornerRadius: 18)
                    .stroke(playing ? palette.accent : palette.lineSubtle, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(tone.displayName)，\(tone.summary)")
        .accessibilityValue(playing ? "正在播放" : "未播放")
        .accessibilityHint(playing ? "轻点停止" : "轻点播放")
    }
}
