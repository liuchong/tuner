import AVFoundation
import Foundation

/// 固定音高播放器；频率由 Rust core 提供，原生层只合成并播放正弦波。
@MainActor
protocol ReferenceTonePlaying: AnyObject {
    func play(frequencyHz: Double)
    func stop()
}

@MainActor
final class ReferenceTonePlayer: ReferenceTonePlaying {
    private let audioEngine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let sampleRate = 48_000.0
    private let gain: Float = 0.65
    private var changeToken = 0

    init() {
        audioEngine.attach(playerNode)
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        audioEngine.connect(playerNode, to: audioEngine.mainMixerNode, format: format)
    }

    func play(frequencyHz: Double) {
        guard frequencyHz.isFinite, frequencyHz > 0 else { return }
        changeToken += 1
        let token = changeToken
        fade(to: 0, token: token)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.02) { [weak self] in
            guard let self, token == self.changeToken else { return }
            self.startTone(frequencyHz)
        }
    }

    func stop() {
        changeToken += 1
        let token = changeToken
        fade(to: 0, token: token)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.02) { [weak self] in
            guard let self, token == self.changeToken else { return }
            self.playerNode.stop()
        }
    }

    private func startTone(_ frequencyHz: Double) {
        let cycles = max(1, Int((frequencyHz * 2).rounded()))
        let frameCount = max(32, Int((Double(cycles) * sampleRate / frequencyHz).rounded()))
        guard
            let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1),
            let buffer = AVAudioPCMBuffer(
                pcmFormat: format,
                frameCapacity: AVAudioFrameCount(frameCount)
            ),
            let samples = buffer.floatChannelData?[0]
        else { return }

        buffer.frameLength = AVAudioFrameCount(frameCount)
        for index in 0..<frameCount {
            samples[index] = sin(Float(index) * 2 * .pi * Float(frequencyHz / sampleRate))
        }

        do {
            if !audioEngine.isRunning { try audioEngine.start() }
            playerNode.stop()
            playerNode.scheduleBuffer(buffer, at: nil, options: .loops)
            playerNode.volume = 0
            playerNode.play()
            fade(to: gain, token: changeToken)
        } catch {
            playerNode.stop()
        }
    }

    private func fade(to target: Float, token: Int) {
        let start = playerNode.volume
        for step in 1...4 {
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(step) * 0.005) { [weak self] in
                guard let self, token == self.changeToken else { return }
                let progress = Float(step) / 4
                self.playerNode.volume = start + (target - start) * progress
            }
        }
    }
}
