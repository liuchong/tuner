import AVFoundation
import Combine

/// 一次 tick 的 UI 事件（atMs 为预计呈现时刻，用于同步闪拍动画）。
struct TickEvent {
    let beatIndex: Int
    let accent: TickAccent
    let atMs: UInt64
}

/// 节拍器播放器：AVAudioEngine + PlayerNode，后台队列定时 render 填缓冲。
/// TickInfo 经 subject 驱动 UI 闪拍（与声音对齐：offset + 在途缓冲换算）。
final class MetronomePlayer: ObservableObject, @unchecked Sendable {
    let ticks = PassthroughSubject<TickEvent, Never>()

    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var renderEngine: MetronomeEngine?
    private var timer: DispatchSourceTimer?
    private let sampleRate = 44100.0
    private var scheduledSamples: UInt64 = 0
    private var playedSamples: UInt64 = 0
    private var lastRenderCursor: UInt64 = 0
    private var isPlaying = false

    func start(engine renderEngine: MetronomeEngine) {
        guard !isPlaying else { return }
        isPlaying = true
        self.renderEngine = renderEngine
        #if os(iOS)
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch { return }
        #endif

        let av = AVAudioEngine()
        let node = AVAudioPlayerNode()
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        av.attach(node)
        av.connect(node, to: av.mainMixerNode, format: format)
        do { try av.start() } catch { isPlaying = false; return }
        node.play()
        engine = av
        player = node
        // 两个计数器必须一起归零：只清 scheduledSamples 会让重新播放时
        // scheduledSamples - playedSamples 在 UInt64 上下溢，pump 直接停摆
        scheduledSamples = 0
        playedSamples = 0

        // 后台队列：每 ~10ms 补缓冲（保持 ≥3 个在途）
        let timer = DispatchSource.makeTimerSource(queue: .global(qos: .userInitiated))
        timer.schedule(deadline: .now(), repeating: .milliseconds(10))
        timer.setEventHandler { [weak self] in self?.pump() }
        timer.resume()
        self.timer = timer
    }

    /// 已排队但尚未回放完的采样数（饱和减法）。
    private func inFlightSamples() -> UInt64 {
        scheduledSamples > playedSamples ? scheduledSamples - playedSamples : 0
    }

    private func pump() {
        guard isPlaying, let node = player, let re = renderEngine else { return }
        let chunk: UInt32 = 1024
        // 保持 ≥3 个缓冲在途。playedSamples 由回放完成回调在别的线程累加，
        // 取差值一律走饱和减法，不让计数错位把 pump 卡死。
        while inFlightSamples() < UInt64(chunk) * 3 {
            let frame = re.render(frames: chunk)
            let buf = AVAudioPCMBuffer(
                pcmFormat: AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!,
                frameCapacity: chunk
            )!
            buf.frameLength = chunk
            let dst = buf.floatChannelData![0]
            frame.samples.withUnsafeBufferPointer { ptr in
                if let base = ptr.baseAddress {
                    dst.update(from: base, count: Int(chunk))
                }
            }
            let nowMs = UInt64(ProcessInfo.processInfo.systemUptime * 1000)
            let queued = inFlightSamples()
            for tick in frame.ticks {
                let atMs = nowMs + UInt64(
                    max(0.0, Double(queued + tick.sampleOffset) / sampleRate * 1000.0)
                )
                ticks.send(
                    TickEvent(beatIndex: Int(tick.beatIndex), accent: tick.accent, atMs: atMs)
                )
            }
            let samplesThisBuffer = UInt64(chunk)
            node.scheduleBuffer(buf) { [weak self] in
                self?.playedSamples += samplesThisBuffer
            }
            scheduledSamples += samplesThisBuffer
        }
    }

    func stop() {
        isPlaying = false
        timer?.cancel()
        timer = nil
        player?.stop()
        engine?.stop()
        engine = nil
        player = nil
        renderEngine = nil
    }
}
