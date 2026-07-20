import AVFoundation
import Combine

/// 采集共享枢纽（单例）：一个 PitchEngine + AVAudioEngine 输入，
/// 窗口 2048 / hop 1024 → analyze() → 事件流（AnalysisFrame）。
/// 与 Android CaptureHub 同构；acquire/release 引用计数启停。
final class CaptureHub: ObservableObject, @unchecked Sendable {
    nonisolated(unsafe) static let shared = CaptureHub()

    @Published private(set) var running = false
    let events = PassthroughSubject<AnalysisFrame, Never>()

    private var refs = 0
    private var engine: PitchEngine?
    private var audioEngine: AVAudioEngine?
    private(set) var config: TunerConfig

    private init() {
        config = defaultTunerConfig()
    }

    func acquire() {
        if refs == 0 { startLocked() }
        refs += 1
    }

    func release() {
        if refs > 0 { refs -= 1 }
        if refs == 0 { stopLocked() }
    }

    /// 设置变更即时下发（无需重启采集）。
    func applyConfig(_ newConfig: TunerConfig) {
        config = newConfig
        engine?.setA4(hz: newConfig.a4Hz)
        engine?.setSolfege(system: newConfig.solfege, key: newConfig.key)
        engine?.setNoiseGate(dbfs: newConfig.noiseGateDbfs)
        engine?.setTemperament(divisions: newConfig.temperament)
    }

    private let audioQueue = DispatchQueue(label: "com.liuchong.tuner.audio-setup")

    private func startLocked() {
        // 会话配置 + installTap 放专用串行队列：installTap 内部是同步 RPC，
        // 在主线程执行一旦超时直接 abort（真机/模拟器都发生过），且会卡死 UI。
        audioQueue.async { [weak self] in self?.setupAndStart() }
    }

    private func setupAndStart() {
        let eng = UniffiFactories.pitch.create(config: config)
        let av = AVAudioEngine()
        let input = av.inputNode
        let session = AVAudioSession.sharedInstance()
        do {
            #if targetEnvironment(simulator)
            // 模拟器 HAL 不支持 .measurement 模式：SetProperty RPC 超时直接 abort
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            #else
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker])
            #endif
            try session.setPreferredSampleRate(44100)
            try session.setActive(true)
        } catch { return }
        var format = input.outputFormat(forBus: 0)
        if format.sampleRate <= 0 || format.channelCount == 0 {
            // 底 48k 单声道（与 core 默认采样率一致）
            guard let fallback = AVAudioFormat(standardFormatWithSampleRate: 48000, channels: 1) else { return }
            format = fallback
        }
        // 采样率以设备实际为准；窗口 2048 / hop 1024 滑动缓冲（音频线程不做 IO/锁）
        var pending = [Float]()
        pending.reserveCapacity(4096)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            guard let self, let channelData = buffer.floatChannelData else { return }
            let frames = Int(buffer.frameLength)
            pending.append(contentsOf: UnsafeBufferPointer(start: channelData[0], count: frames))
            while pending.count >= 2048 {
                let frame = eng.analyze(pcm: Array(pending[..<2048]))
                self.events.send(frame)
                pending.removeFirst(1024)
            }
        }
        do {
            try av.start()
            engine = eng
            audioEngine = av
            DispatchQueue.main.async { [weak self] in self?.running = true }
        } catch {
            input.removeTap(onBus: 0)
        }
    }

    private func stopLocked() {
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine?.stop()
        audioEngine = nil
        engine = nil
        DispatchQueue.main.async { [weak self] in self?.running = false }
    }
}
