import Foundation

/// 音叉浮窗状态。打开浮窗不会接管或停止麦克风采集。
@MainActor
final class TuningForkViewModel: ObservableObject {
    @Published private(set) var isOpen = false
    @Published private(set) var tones: [ReferenceTone]
    @Published private(set) var selectedStep: Int32?
    @Published private(set) var playingStep: Int32?

    private let toneProvider: (TunarConfig) -> [ReferenceTone]
    private let player: ReferenceTonePlaying

    init(
        initialConfig: TunarConfig,
        toneProvider: @escaping (TunarConfig) -> [ReferenceTone] = CorePresets.referenceTones,
        player: ReferenceTonePlaying = ReferenceTonePlayer()
    ) {
        tones = toneProvider(initialConfig)
        self.toneProvider = toneProvider
        self.player = player
    }

    func open() {
        isOpen = true
    }

    func toggle(_ tone: ReferenceTone) {
        if playingStep == tone.stepFromA4 {
            player.stop()
            playingStep = nil
            selectedStep = tone.stepFromA4
        } else {
            player.play(frequencyHz: tone.frequencyHz)
            selectedStep = tone.stepFromA4
            playingStep = tone.stepFromA4
        }
    }

    func refresh(_ config: TunarConfig) {
        player.stop()
        selectedStep = nil
        playingStep = nil
        tones = toneProvider(config)
    }

    func close() {
        isOpen = false
    }

    func toggleSelected() {
        guard let selectedStep else { return }
        if playingStep != nil {
            player.stop()
            playingStep = nil
            return
        }
        guard let tone = tones.first(where: { $0.stepFromA4 == selectedStep }) else { return }
        player.play(frequencyHz: tone.frequencyHz)
        playingStep = selectedStep
    }

    func stopForBackground() {
        player.stop()
        playingStep = nil
    }
}
