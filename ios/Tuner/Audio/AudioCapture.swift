import AVFoundation
import Combine

func isUsableCaptureFormat(sampleRate: Double, channelCount: UInt32) -> Bool {
    sampleRate.isFinite && sampleRate > 0 && channelCount > 0
}

/// iOS 17 可用的 C11 原子整数薄封装。
private final class AtomicInt: @unchecked Sendable {
    private let raw: UnsafeMutableRawPointer

    init(_ initialValue: Int) {
        guard let value = tuner_atomic_int_create(Int64(initialValue)) else {
            preconditionFailure("无法创建音频原子计数器")
        }
        raw = value
    }

    func loadRelaxed() -> Int {
        Int(tuner_atomic_int_load_relaxed(raw))
    }

    func loadAcquire() -> Int {
        Int(tuner_atomic_int_load_acquire(raw))
    }

    func storeRelease(_ value: Int) {
        tuner_atomic_int_store_release(raw, Int64(value))
    }

    deinit {
        tuner_atomic_int_destroy(raw)
    }
}

/// 采集引用计数与异步启动令牌。
/// 旧启动只有在订阅仍存在且令牌未失效时才能提交，避免释放后重新打开麦克风。
final class CaptureLifecycleGate: @unchecked Sendable {
    private let lock = NSLock()
    private var references = 0
    private var generation = 0

    /// 首个订阅者返回新启动令牌；后续共享订阅者不重复启动。
    func acquire() -> Int? {
        lock.lock()
        defer { lock.unlock() }
        let needsStart = references == 0
        references += 1
        guard needsStart else { return nil }
        generation &+= 1
        return generation
    }

    /// 最后一个订阅者释放时使未完成的启动令牌失效，并要求停止当前资源。
    func release() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard references > 0 else { return false }
        references -= 1
        guard references == 0 else { return false }
        generation &+= 1
        return true
    }

    /// 令牌仍属于当前有效会话时，在同一把锁内原子提交启动结果。
    func commitIfCurrent(_ token: Int, _ body: () -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard references > 0, token == generation else { return false }
        body()
        return true
    }
}

/// 单生产者、单消费者的固定容量音频帧环。
/// `push` 只写预分配内存和原子计数，可直接用于系统音频回调。
final class AudioFrameRing: @unchecked Sendable {
    private let windowSize: Int
    private let hopSize: Int
    private let capacity: Int
    private let window: UnsafeMutablePointer<Float>
    private let slots: UnsafeMutablePointer<Float>
    private let published = AtomicInt(0)
    private let consumed = AtomicInt(0)
    private var writeIndex = 0
    private var filled = 0
    private var samplesSincePublish = 0

    init(windowSize: Int, hopSize: Int, capacity: Int) {
        precondition(windowSize > 0 && hopSize > 0 && capacity > 0)
        self.windowSize = windowSize
        self.hopSize = hopSize
        self.capacity = capacity
        window = .allocate(capacity: windowSize)
        slots = .allocate(capacity: windowSize * capacity)
        window.initialize(repeating: 0, count: windowSize)
        slots.initialize(repeating: 0, count: windowSize * capacity)
    }

    /// 满载时丢弃新快照，绝不等待消费者。
    func push(_ samples: UnsafePointer<Float>, count: Int) {
        guard count > 0 else { return }
        for sampleIndex in 0..<count {
            window[writeIndex] = samples[sampleIndex]
            writeIndex += 1
            if writeIndex == windowSize { writeIndex = 0 }

            if filled < windowSize {
                filled += 1
                if filled == windowSize {
                    publishWindow()
                    samplesSincePublish = 0
                }
            } else {
                samplesSincePublish += 1
                if samplesSincePublish == hopSize {
                    publishWindow()
                    samplesSincePublish = 0
                }
            }
        }
    }

    /// 消费线程调用；数组构造发生在工作线程，不在音频回调。
    @discardableResult
    func consume(_ body: ([Float]) -> Void) -> Bool {
        let readSequence = consumed.loadRelaxed()
        let writeSequence = published.loadAcquire()
        guard readSequence < writeSequence else { return false }
        let slotIndex = readSequence % capacity
        let start = slots.advanced(by: slotIndex * windowSize)
        body(Array(UnsafeBufferPointer(start: start, count: windowSize)))
        consumed.storeRelease(readSequence + 1)
        return true
    }

    private func publishWindow() {
        let writeSequence = published.loadRelaxed()
        let readSequence = consumed.loadAcquire()
        guard writeSequence - readSequence < capacity else { return }

        let slotIndex = writeSequence % capacity
        let destination = slots.advanced(by: slotIndex * windowSize)
        for index in 0..<windowSize {
            destination[index] = window[(writeIndex + index) % windowSize]
        }
        published.storeRelease(writeSequence + 1)
    }

    deinit {
        window.deinitialize(count: windowSize)
        slots.deinitialize(count: windowSize * capacity)
        window.deallocate()
        slots.deallocate()
    }
}

/// Rust 分析运行在独立线程；更新设置用的锁不会进入系统音频回调。
private final class AnalysisWorker: @unchecked Sendable {
    let ring: AudioFrameRing
    private let running = AtomicInt(0)
    private let engineLock = NSLock()
    private let engine: PitchEngine
    private let queue = DispatchQueue(label: "com.liuchong.tuner.audio-analysis")
    private let onFrame: @Sendable (AnalysisFrame) -> Void

    init(config: TunerConfig, onFrame: @escaping @Sendable (AnalysisFrame) -> Void) {
        ring = AudioFrameRing(
            windowSize: 2_048,
            hopSize: Int(config.frameHopSamples),
            capacity: 4
        )
        engine = UniffiFactories.pitch.create(config: config)
        self.onFrame = onFrame
    }

    func start() {
        running.storeRelease(1)
        queue.async { [self] in
            while running.loadAcquire() == 1 {
                var result: AnalysisFrame?
                let consumed = ring.consume { samples in
                    engineLock.lock()
                    result = engine.analyze(pcm: samples)
                    engineLock.unlock()
                }
                if let result {
                    onFrame(result)
                } else if !consumed {
                    Thread.sleep(forTimeInterval: 0.002)
                }
            }
        }
    }

    func applyConfig(_ config: TunerConfig) {
        engineLock.lock()
        engine.setA4(hz: config.a4Hz)
        engine.setSolfege(system: config.solfege, key: config.key)
        engine.setNoiseGate(dbfs: config.noiseGateDbfs)
        engine.setTemperament(divisions: config.temperament)
        engineLock.unlock()
    }

    func stop() {
        running.storeRelease(0)
    }
}

/// 后台完成初始化、等待提交到主线程的一组采集资源。
private final class PendingCaptureStart: @unchecked Sendable {
    let audioEngine: AVAudioEngine
    let input: AVAudioInputNode
    let format: AVAudioFormat
    let worker: AnalysisWorker

    init(
        audioEngine: AVAudioEngine,
        input: AVAudioInputNode,
        format: AVAudioFormat,
        worker: AnalysisWorker
    ) {
        self.audioEngine = audioEngine
        self.input = input
        self.format = format
        self.worker = worker
    }

    func discard() {
        input.removeTap(onBus: 0)
        audioEngine.stop()
        worker.stop()
    }
}

/// 采集共享枢纽（单例）：一个采集源，经预分配环形缓冲送往 Rust 分析线程。
/// acquire/release 引用计数确保调音、乐器和专业频谱共用同一路麦克风。
final class CaptureHub: ObservableObject, @unchecked Sendable {
    static let shared = CaptureHub()

    @Published private(set) var running = false
    @Published private(set) var startFailed = false
    let events = PassthroughSubject<AnalysisFrame, Never>()

    private let lifecycle = CaptureLifecycleGate()
    private var worker: AnalysisWorker?
    private var audioEngine: AVAudioEngine?
    private(set) var config: TunerConfig
    private let audioQueue = DispatchQueue(label: "com.liuchong.tuner.audio-setup")

    private init() {
        config = defaultTunerConfig()
    }

    func acquire() {
        guard let token = lifecycle.acquire() else { return }
        DispatchQueue.main.async { [weak self] in
            self?.startFailed = false
        }
        startLocked(token: token, initialConfig: config)
    }

    func release() {
        if lifecycle.release() { stopLocked() }
    }

    /// 设置变更即时下发（无需重启采集）。
    func applyConfig(_ newConfig: TunerConfig) {
        config = newConfig
        worker?.applyConfig(newConfig)
    }

    private func startLocked(token: Int, initialConfig: TunerConfig) {
        audioQueue.async { [weak self] in
            self?.setupAndStart(token: token, initialConfig: initialConfig)
        }
    }

    private func setupAndStart(token: Int, initialConfig: TunerConfig) {
        let av = AVAudioEngine()
        let input = av.inputNode
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        do {
            #if targetEnvironment(simulator)
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            #else
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker])
            #endif
            try session.setPreferredSampleRate(44_100)
            try session.setActive(true)
        } catch {
            publishStartFailure(token: token)
            return
        }
        #endif

        let format = input.outputFormat(forBus: 0)
        guard isUsableCaptureFormat(
            sampleRate: format.sampleRate,
            channelCount: format.channelCount
        ) else {
            // 输入节点没有有效硬件格式时不能用自造格式安装 tap；AVFAudio 会直接抛
            // Objective-C 异常而不是 Swift Error。等待下一次页面获取采集资源时重试。
            #if os(iOS)
            try? session.setActive(false)
            #endif
            publishStartFailure(token: token)
            return
        }

        let actualConfig = TunerConfig(
            sampleRate: format.sampleRate,
            frameHopSamples: initialConfig.frameHopSamples,
            a4Hz: initialConfig.a4Hz,
            noiseGateDbfs: initialConfig.noiseGateDbfs,
            solfege: initialConfig.solfege,
            key: initialConfig.key,
            temperament: initialConfig.temperament
        )
        let analysisWorker = AnalysisWorker(config: actualConfig) { [weak self] frame in
            self?.events.send(frame)
        }
        let ring = analysisWorker.ring

        input.installTap(onBus: 0, bufferSize: 1_024, format: format) { buffer, _ in
            guard let channelData = buffer.floatChannelData else { return }
            ring.push(channelData[0], count: Int(buffer.frameLength))
        }
        let pending = PendingCaptureStart(
            audioEngine: av,
            input: input,
            format: format,
            worker: analysisWorker
        )

        do {
            try av.start()
            DispatchQueue.main.async { [weak self] in
                guard let self else {
                    pending.discard()
                    return
                }
                let committed = self.lifecycle.commitIfCurrent(token) {
                    let latest = self.config
                    let committedConfig = TunerConfig(
                        sampleRate: pending.format.sampleRate,
                        frameHopSamples: latest.frameHopSamples,
                        a4Hz: latest.a4Hz,
                        noiseGateDbfs: latest.noiseGateDbfs,
                        solfege: latest.solfege,
                        key: latest.key,
                        temperament: latest.temperament
                    )
                    pending.worker.applyConfig(committedConfig)
                    pending.worker.start()
                    self.config = committedConfig
                    self.worker = pending.worker
                    self.audioEngine = pending.audioEngine
                    self.running = true
                    self.startFailed = false
                }
                if !committed {
                    pending.discard()
                }
            }
        } catch {
            pending.discard()
            publishStartFailure(token: token)
        }
    }

    private func publishStartFailure(token: Int) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            _ = self.lifecycle.commitIfCurrent(token) {
                self.running = false
                self.startFailed = true
            }
        }
    }

    private func stopLocked() {
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine?.stop()
        worker?.stop()
        audioEngine = nil
        worker = nil
        DispatchQueue.main.async { [weak self] in self?.running = false }
    }
}
