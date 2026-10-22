import AVFoundation
import Combine
import Foundation

/// 分析锁内取得的原子上下文，禁止用当前设置重新解释旧帧。
struct PracticeCaptureFrame: Sendable {
    let frame: AnalysisFrame
    let config: TunarConfig
    let generation: UInt64
    let referenceActive: Bool
}

/// 参考音输出标记；只在控制/分析线程使用，不进入音频回调。
final class ReferenceToneActivity: @unchecked Sendable {
    static let shared = ReferenceToneActivity()
    private let lock = NSLock()
    private var players = Set<ObjectIdentifier>()
    func set(_ player: AnyObject, active: Bool) {
        lock.lock(); defer { lock.unlock() }
        if active { players.insert(ObjectIdentifier(player)) } else { players.remove(ObjectIdentifier(player)) }
    }
    var isActive: Bool { lock.lock(); defer { lock.unlock() }; return !players.isEmpty }
}

enum PracticeTask: String, CaseIterable { case tune = "调音", practice = "练音", analyze = "分析" }
enum PracticeWorkspaceLayout {
    static func showsInspector(contentWidth: Double) -> Bool { contentWidth >= 1040 }
    static func noteSize(availableHeight: Double) -> Double { availableHeight < 760 ? 64 : 88 }
}

private struct PracticeUpdate: Sendable {
    let status: PracticeStatus
    let config: PracticeConfig
    let displayConfig: PracticeConfig
    let point: PracticePoint?
    let trace: [PracticeTracePoint]
    let live: [PracticeTracePoint]
    let reset: Bool
    let snapshots: [PracticeSnapshot]?
    let comparison: PracticeComparison?
    let comparisonError: String?
    let targetFrequency: Double?
    let error: String?
    let summaries: [PracticeSummary]?
}

private func practiceFailureMessage(_ error: Error) -> String {
    guard let failure = error as? PracticeError else { return error.localizedDescription }
    switch failure {
    case .InvalidInput: return "目标、容差或测量输入无效，请检查后重试"
    case .InvalidFile: return "测量文件无效，当前会话已保留"
    case .UnsupportedVersion: return "不支持此测量文件版本，请使用兼容版本打开"
    case .Capacity: return "已达到测量容量上限，请先保存或清空本次测量"
    case .NoData: return "没有可信测量，请先发声或选择有效历史点"
    case .IncompatibleGrid: return "采样率或频率范围不同，不能直接叠加"
    }
}

/// 所有会话操作在同一个工作队列排序，不在主线程编码或统计。
private final class PracticeWorkspaceWorker: @unchecked Sendable {
    let session: PracticeSession
    let queue = DispatchQueue(label: "com.liuchong.tunar.practice")
    var onUpdate: (@MainActor @Sendable (PracticeUpdate) -> Void)?
    private var traceCount: UInt32 = 0
    var normalized = false
    var wide = false

    init(session: PracticeSession) { self.session = session }

    func perform(full: Bool = true, _ action: @escaping @Sendable (PracticeSession) throws -> Void) {
        queue.async { [self] in
            var error: String?
            do { try action(session) } catch let failure { error = practiceFailureMessage(failure) }
            publish(full: full, error: error)
        }
    }

    func accept(_ packet: PracticeCaptureFrame) {
        perform(full: false) { session in
            _ = try session.observe(frame: packet.frame, generation: packet.generation,
                a4Hz: packet.config.a4Hz, temperament: packet.config.temperament,
                referenceActive: packet.referenceActive)
        }
    }

    private func publish(full: Bool, error: String?) {
        let status = session.status()
        let reset = full || status.count < traceCount
        let trace = session.tracePage(offset: reset ? 0 : traceCount, limit: 6000)
        traceCount = status.count
        var comparison: PracticeComparison?
        var comparisonError: String?
        if status.baselineId != nil {
            do { comparison = try session.comparison(normalized: normalized, wide: wide) }
            catch { comparisonError = "比较条件不同或没有当前数据，不能叠加" }
        }
        let update = PracticeUpdate(status: status, config: session.config(), displayConfig: session.displayConfig(),
            point: session.displayPoint(), trace: trace, live: session.liveTrace(), reset: reset,
            snapshots: full ? session.snapshots() : nil, comparison: comparison,
            comparisonError: comparisonError, targetFrequency: session.targetFrequency(), error: error, summaries: full ? status.selectionStartId.flatMap { start in status.selectionEndId.map { session.summarize(startId: start, endId: $0) } } ?? [] : nil)
        DispatchQueue.main.async { [self] in onUpdate?(update) }
    }
}

/// 窗口/导航所有者；不计算业务音高、容差或统计。
@MainActor
final class PracticeWorkspaceModel: ObservableObject {
    @Published private(set) var status: PracticeStatus
    @Published private(set) var config: PracticeConfig
    @Published private(set) var displayConfig: PracticeConfig
    @Published private(set) var point: PracticePoint?
    @Published private(set) var trace: [PracticeTracePoint] = []
    @Published private(set) var liveTrace: [PracticeTracePoint] = []
    @Published private(set) var snapshots: [PracticeSnapshot] = []
    @Published private(set) var summaries: [PracticeSummary] = []
    @Published private(set) var comparison: PracticeComparison?
    @Published private(set) var comparisonError: String?
    @Published private(set) var targetFrequency: Double?
    @Published var error: String?
    @Published var task: PracticeTask = .practice
    @Published var normalized = false
    @Published var wide = false
    @Published private(set) var referencePlaying = false
    private let usesSharedCapture: Bool
    private let player: ReferenceTonePlaying
    private let worker: PracticeWorkspaceWorker
    private var cancellables = Set<AnyCancellable>()
    private var acquired = false
    private var captureWanted = false

    init(session: PracticeSession = PracticeSession(), events: AnyPublisher<PracticeCaptureFrame, Never>? = nil,
         player: ReferenceTonePlaying = ReferenceTonePlayer()) {
        status = session.status(); config = session.config(); displayConfig = session.displayConfig()
        point = session.displayPoint(); targetFrequency = session.targetFrequency()
        self.usesSharedCapture = events == nil
        self.player = player
        worker = PracticeWorkspaceWorker(session: session)
        worker.onUpdate = { [weak self] update in self?.apply(update) }
        let worker = worker
        (events ?? CaptureHub.shared.measurements.eraseToAnyPublisher())
            .sink { @Sendable [weak worker] packet in worker?.accept(packet) }.store(in: &cancellables)
        if events == nil {
            CaptureHub.shared.$running.removeDuplicates().receive(on: DispatchQueue.main)
                .sink { [weak self] running in
                    guard let self, !running else { return }
                    self.stopReference()
                    self.worker.perform(full: false) { session in if session.status().state == .recording { session.pause() } }
                }.store(in: &cancellables)
        }
        worker.perform { _ in }
    }

    private func apply(_ update: PracticeUpdate) {
        let changed = config.a4Hz != update.config.a4Hz || config.temperament != update.config.temperament
            || config.targetStep != update.config.targetStep || config.targetMidi != update.config.targetMidi || config.targetHz != update.config.targetHz
        status = update.status; config = update.config; displayConfig = update.displayConfig; point = update.point
        if update.reset { trace = update.trace } else { trace.append(contentsOf: update.trace) }
        liveTrace = update.live
        if let snapshots = update.snapshots { self.snapshots = snapshots }
        if let summaries = update.summaries { self.summaries = summaries }
        comparison = update.comparison; comparisonError = update.comparisonError
        targetFrequency = update.targetFrequency
        if let error = update.error { self.error = "测量操作失败：\(error)" }
        if changed { stopReference() }
    }

    func requestCapture() {
        captureWanted = true
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: acquireCapture()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
                Task { @MainActor in
                    guard let self, self.captureWanted else { return }
                    if granted { self.acquireCapture() } else { self.error = "请在系统设置中允许麦克风访问" }
                }
            }
        default: error = "请在系统设置中允许麦克风访问"
        }
    }
    func acquireCapture() { guard !acquired else { return }; CaptureHub.shared.acquire(); acquired = true }
    func releaseCapture() { guard acquired else { return }; CaptureHub.shared.release(); acquired = false }
    func retryCapture() {
        if !acquired { requestCapture() } else { CaptureHub.shared.retryCapture() }
    }
    func start() {
        guard !usesSharedCapture || CaptureHub.shared.running else { error = "采集未就绪，请先允许麦克风访问或重试输入设备"; return }
        worker.perform { $0.start() }
    }
    func pause() { worker.perform { $0.pause() } }
    func finish() { worker.perform { $0.finish() } }
    func clear() { summaries = []; worker.perform { $0.clear() } }
    func review(id: UInt64) { stopReference(); worker.perform { _ = try $0.selectPoint(id: id) } }
    func returnToLive() { worker.perform { $0.returnToLive() } }
    func lockReference(step: Int32) { worker.perform { try $0.lockReference(step: step); $0.returnToLive() } }
    func unlock() { worker.perform { $0.unlockTarget(); $0.returnToLive() } }
    func lockInstrument(midi: Int32, instrumentId: String, variantId: String, fingeringId: Int32?) {
        worker.perform { try $0.lockInstrument(midi: midi, instrumentId: instrumentId, variantId: variantId, fingeringId: fingeringId); $0.returnToLive() }
    }
    func tolerance(_ value: Double) {
        worker.perform { session in var config = session.config(); config.toleranceCents = value; try session.configure(config: config) }
    }
    func saveSnapshot(name: String) { worker.perform { _ = try $0.addSnapshot(name: name) } }
    func baseline(_ id: UInt32?) { worker.perform { try $0.setBaseline(id: id) } }
    func removeSnapshot(_ id: UInt32) { worker.perform { $0.removeSnapshot(id: id) } }
    func refreshComparison() {
        let normalized = normalized, wide = wide, worker = worker
        worker.queue.async { worker.normalized = normalized; worker.wide = wide }
        worker.perform { _ in }
    }
    func summarize(startId: UInt64, endId: UInt64) {
        worker.perform { _ = $0.summarize(startId: startId, endId: endId) }
    }
    func stepReview(direction: Int, seconds: Bool = false) {
        stopReference()
        worker.perform { session in
            let points = session.tracePage(offset: 0, limit: 6000)
            guard !points.isEmpty else { return }
            let index = session.status().reviewId.flatMap { id in points.firstIndex { $0.id == id } } ?? points.count - 1
            let destination: Int
            if seconds {
                let time = points[index].timeSeconds + Double(direction)
                destination = points.indices.min(by: { abs(points[$0].timeSeconds - time) < abs(points[$1].timeSeconds - time) }) ?? index
            } else { destination = min(points.count - 1, max(0, index + direction)) }
            _ = try session.selectPoint(id: points[destination].id)
        }
    }
    func toggleReference() {
        if referencePlaying { stopReference(); return }
        guard status.reviewId == nil, let targetFrequency else { return }
        player.play(frequencyHz: targetFrequency); referencePlaying = true
    }
    func stopReference() { player.stop(); referencePlaying = false }
    func suspend() { captureWanted = false; stopReference(); pause(); releaseCapture() }
    func importMeasurements(_ contents: String) { stopReference(); worker.perform { try $0.importJson(contents: contents) } }
    func importFile(_ url: URL) {
        stopReference()
        worker.perform { session in
            let access = url.startAccessingSecurityScopedResource(); defer { if access { url.stopAccessingSecurityScopedResource() } }
            let handle = try FileHandle(forReadingFrom: url); defer { try? handle.close() }
            let data = try handle.read(upToCount: 32 * 1024 * 1024 + 1) ?? Data()
            guard data.count <= 32 * 1024 * 1024, let text = String(data: data, encoding: .utf8) else { throw PracticeError.InvalidFile }
            try session.importJson(contents: text)
        }
    }
    func export(csv: Bool, startId: UInt64? = nil, endId: UInt64? = nil,
                completion: @escaping @MainActor @Sendable (String?) -> Void) {
        let worker = worker
        worker.queue.async { [weak self] in
            do {
                let text = try csv ? worker.session.exportCsv(startId: startId, endId: endId) : worker.session.exportJson()
                DispatchQueue.main.async { completion(text) }
            } catch {
                let message = practiceFailureMessage(error)
                DispatchQueue.main.async { self?.error = "保存失败：\(message)"; completion(nil) }
            }
        }
    }
    /// 等待队列及主线程发布完成，供原生集成测试使用。
    func synchronize() async {
        await withCheckedContinuation { continuation in
            worker.queue.async { DispatchQueue.main.async { continuation.resume() } }
        }
    }
}
