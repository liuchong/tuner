import AVFoundation
import Combine
#if os(macOS)
import AppKit
#else
import UIKit
#endif

func isUsableCaptureFormat(sampleRate: Double, channelCount: UInt32) -> Bool {
    sampleRate.isFinite && sampleRate > 0 && channelCount > 0
}

/// iOS 17 可用的 C11 原子整数薄封装。
private final class AtomicInt: @unchecked Sendable {
    private let raw: UnsafeMutableRawPointer

    init(_ initialValue: Int) {
        guard let value = tunar_atomic_int_create(Int64(initialValue)) else {
            preconditionFailure("无法创建音频原子计数器")
        }
        raw = value
    }

    func loadRelaxed() -> Int {
        Int(tunar_atomic_int_load_relaxed(raw))
    }

    func loadAcquire() -> Int {
        Int(tunar_atomic_int_load_acquire(raw))
    }

    func storeRelease(_ value: Int) {
        tunar_atomic_int_store_release(raw, Int64(value))
    }

    deinit {
        tunar_atomic_int_destroy(raw)
    }
}

/// 采集引用计数与异步启动令牌。
/// 旧启动只有在订阅仍存在且令牌未失效时才能提交，避免释放后重新打开麦克风。
final class CaptureLifecycleGate: @unchecked Sendable {
    private let lock = NSLock()
    private var references = 0
    private var enabled = true
    private var generation = 0

    /// 首个订阅者返回新启动令牌；后续共享订阅者不重复启动。
    func acquire() -> Int? {
        lock.lock()
        defer { lock.unlock() }
        let needsStart = references == 0 && enabled
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

    /// 保留订阅身份但停止硬件；恢复时生成新令牌。
    func setEnabled(_ value: Bool) -> (stop: Bool, start: Int?) {
        lock.lock(); defer { lock.unlock() }
        guard enabled != value else { return (false, nil) }
        enabled = value; generation &+= 1
        return (references > 0 && !value, references > 0 && value ? generation : nil)
    }

    /// 重试不丢共享订阅；使旧资源和未提交启动失效。
    func restart() -> Int? {
        lock.lock(); defer { lock.unlock() }
        guard enabled, references > 0 else { return nil }
        generation &+= 1
        return generation
    }

    /// 令牌仍属于当前有效会话时，在同一把锁内原子提交启动结果。
    func commitIfCurrent(_ token: Int, _ body: () -> Void) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard enabled, references > 0, token == generation else { return false }
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
    private let positions: UnsafeMutablePointer<UInt64>
    private var totalSamples: UInt64 = 0
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
        positions = .allocate(capacity: capacity)
        positions.initialize(repeating: 0, count: capacity)
        slots = .allocate(capacity: windowSize * capacity)
        window.initialize(repeating: 0, count: windowSize)
        slots.initialize(repeating: 0, count: windowSize * capacity)
    }

    /// 满载时丢弃新快照，绝不等待消费者。
    func push(_ samples: UnsafePointer<Float>, count: Int) {
        guard count > 0 else { return }
        for sampleIndex in 0..<count {
            totalSamples &+= 1
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
    func consume(_ body: ([Float]) -> Void) -> Bool { consumeStamped { samples, _ in body(samples) } }

    /// 时间戳随槽位发布，满载丢帧后不压缩音频时间。
    @discardableResult
    func consumeStamped(_ body: ([Float], UInt64) -> Void) -> Bool {
        let readSequence = consumed.loadRelaxed()
        let writeSequence = published.loadAcquire()
        guard readSequence < writeSequence else { return false }
        let slotIndex = readSequence % capacity
        let start = slots.advanced(by: slotIndex * windowSize)
        body(Array(UnsafeBufferPointer(start: start, count: windowSize)), positions[slotIndex])
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
        positions[slotIndex] = totalSamples
        published.storeRelease(writeSequence + 1)
    }

    deinit {
        positions.deinitialize(count: capacity)
        positions.deallocate()
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
    private let onFrame: @Sendable (PracticeCaptureFrame) -> Void
    private var referenceTail = 0
    private var activeConfig: TunarConfig
    private let generation: UInt64

    init(config: TunarConfig, generation: UInt64, onFrame: @escaping @Sendable (PracticeCaptureFrame) -> Void) {
        self.activeConfig = config
        self.generation = generation
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
                var result: PracticeCaptureFrame?
                let consumed = ring.consumeStamped { samples, position in
                    engineLock.lock()
                    var frame = engine.analyze(pcm: samples)
                    frame.samplePosition = position
                    let reference = ReferenceToneActivity.shared.isActive
                    let affected = reference || referenceTail > 0
                    // 覆盖输入窗及最多4个待分析槽，停止播放后留出保守余响保护。
                    referenceTail = reference ? 8 : max(0, referenceTail - 1)
                    result = PracticeCaptureFrame(frame: frame, config: activeConfig, generation: generation, referenceActive: affected)
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

    func applyConfig(_ config: TunarConfig) {
        engineLock.lock()
        var applied = config
        applied.sampleRate = activeConfig.sampleRate
        applied.frameHopSamples = activeConfig.frameHopSamples
        activeConfig = applied
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
    let measurements = PassthroughSubject<PracticeCaptureFrame, Never>()

    private let lifecycle = CaptureLifecycleGate()
    private var worker: AnalysisWorker?
    private var audioEngine: AVAudioEngine?
    private(set) var config: TunarConfig
    private let audioQueue = DispatchQueue(label: "com.liuchong.tuner.audio-setup")
    private var activityObservers = Set<AnyCancellable>()

    private init() {
        config = defaultTunarConfig()
        #if os(macOS)
        let inactive = NSApplication.didResignActiveNotification
        let active = NSApplication.didBecomeActiveNotification
        #else
        let inactive = UIApplication.willResignActiveNotification
        let active = UIApplication.didBecomeActiveNotification
        #endif
        NotificationCenter.default.publisher(for: inactive).receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.setEnabled(false) }.store(in: &activityObservers)
        NotificationCenter.default.publisher(for: active).receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.setEnabled(true) }.store(in: &activityObservers)
        NotificationCenter.default.publisher(for: .AVAudioEngineConfigurationChange).receive(on: DispatchQueue.main)
            .sink { [weak self] notification in
                guard let self, let engine = notification.object as? AVAudioEngine,
                      engine === self.audioEngine, !engine.isRunning else { return }
                self.retryCapture()
            }.store(in: &activityObservers)
        #if os(iOS)
        NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification).receive(on: DispatchQueue.main)
            .sink { [weak self] notification in
                let type = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init(rawValue:))
                if type == .began { self?.setEnabled(false) }
                else if type == .ended {
                    Task { @MainActor [weak self] in
                        if UIApplication.shared.applicationState == .active { self?.setEnabled(true) }
                    }
                }
            }.store(in: &activityObservers)
        #endif
    }

    private func setEnabled(_ value: Bool) {
        let change = lifecycle.setEnabled(value)
        if change.stop { stopLocked() }
        if let token = change.start { startLocked(token: token, initialConfig: config) }
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

    /// 保留所有页面的订阅，显式重建一次硬件。
    func retryCapture() {
        guard let token = lifecycle.restart() else { return }
        stopLocked()
        startFailed = false
        startLocked(token: token, initialConfig: config)
    }

    /// 设置变更即时下发（无需重启采集）。
    func applyConfig(_ newConfig: TunarConfig) {
        config = newConfig
        worker?.applyConfig(newConfig)
    }

    private func startLocked(token: Int, initialConfig: TunarConfig) {
        audioQueue.async { [weak self] in
            self?.setupAndStart(token: token, initialConfig: initialConfig)
        }
    }

    private func setupAndStart(token: Int, initialConfig: TunarConfig) {
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

        let actualConfig = TunarConfig(
            sampleRate: format.sampleRate,
            frameHopSamples: initialConfig.frameHopSamples,
            a4Hz: initialConfig.a4Hz,
            noiseGateDbfs: initialConfig.noiseGateDbfs,
            solfege: initialConfig.solfege,
            key: initialConfig.key,
            temperament: initialConfig.temperament
        )
        let analysisWorker = AnalysisWorker(config: actualConfig, generation: UInt64(token)) { [weak self] packet in
            self?.events.send(packet.frame)
            self?.measurements.send(packet)
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
                    let committedConfig = TunarConfig(
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
