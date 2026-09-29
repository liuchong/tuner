import Foundation

/// 小工具页状态（iOS / macOS 共用）：音频列表来自 core，播放复用音叉的平滑正弦播放器。
@MainActor
final class ToolsViewModel: ObservableObject {
    let tones: [ToolTone]
    @Published private(set) var playingId: String?

    private let player: ReferenceTonePlaying

    init(
        tones: [ToolTone] = CorePresets.toolTones(),
        player: ReferenceTonePlaying = ReferenceTonePlayer()
    ) {
        self.tones = tones
        self.player = player
    }

    /// 点击正在播放的工具则停止，否则切换到该工具的频率。
    func toggle(_ tone: ToolTone) {
        if playingId == tone.id {
            stop()
            return
        }
        player.play(frequencyHz: tone.frequencyHz)
        playingId = tone.id
    }

    /// 离开页面或应用进入后台时停止播放。
    func stop() {
        guard playingId != nil else { return }
        player.stop()
        playingId = nil
    }
}
