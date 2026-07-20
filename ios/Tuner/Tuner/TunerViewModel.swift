import Combine
import Foundation

/// 一条音高读数（从 TunerEvent 映射，UI 直接消费）。
struct TunerReading {
    let noteName: String
    let freqHz: Double
    let centsOff: Double
    let midi: Int32
    let clarity: Float
    let solfege: String
    let temperament: UInt8
    let temperamentStep: Int32
    let temperamentCents: Double
}

/// 调音面板 ViewModel（与 Android TunerViewModel 同构）。
final class TunerViewModel: ObservableObject {
    enum Signal {
        case listening
        case active(TunerReading)
    }

    @Published private(set) var signal: Signal = .listening
    @Published private(set) var spectrumDb: [Float] = []
    @Published private(set) var partials: [Partial] = []
    @Published private(set) var chord: String?

    private let events: AnyPublisher<AnalysisFrame, Never>
    private let clock: () -> UInt64
    private let timeoutMs: UInt64
    private var lastEventAtMs: UInt64?
    private var cancellables = Set<AnyCancellable>()
    private var acquired = false

    init(
        events: AnyPublisher<AnalysisFrame, Never>? = nil,
        clock: @escaping () -> UInt64 = { UInt64(ProcessInfo.processInfo.systemUptime * 1000) },
        timeoutMs: UInt64 = 800
    ) {
        self.events = events ?? CaptureHub.shared.events.eraseToAnyPublisher()
        self.clock = clock
        self.timeoutMs = timeoutMs

        self.events
            .receive(on: DispatchQueue.main)
            .sink { [weak self] frame in
                guard let self, let ev = frame.tuner else { return }
                self.lastEventAtMs = self.clock()
                self.signal = .active(Self.map(ev))
                self.spectrumDb = frame.spectrumDb
                self.partials = frame.partials
                self.chord = frame.chord
            }
            .store(in: &cancellables)
    }

    func startCapture() {
        if acquired { return }
        CaptureHub.shared.acquire()
        acquired = true
    }

    /// 由 UI 定时器（~10Hz）调用：超时回「请发声」。
    func onTick() {
        guard let last = lastEventAtMs else { return }
        if clock() - last > timeoutMs {
            if case .active = signal {
                signal = .listening
                spectrumDb = []
                partials = []
                chord = nil
            }
        }
    }

    func releaseCapture() {
        if acquired {
            CaptureHub.shared.release()
            acquired = false
        }
    }

    deinit { releaseCapture() }

    private static func map(_ ev: TunerEvent) -> TunerReading {
        TunerReading(
            noteName: ev.noteName,
            freqHz: ev.freqHz,
            centsOff: ev.centsOff,
            midi: ev.midi,
            clarity: ev.clarity,
            solfege: ev.solfege,
            temperament: ev.temperament,
            temperamentStep: ev.temperamentStep,
            temperamentCents: ev.temperamentCents
        )
    }
}
