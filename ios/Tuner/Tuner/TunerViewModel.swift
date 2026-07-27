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
    @Published private(set) var displaySpectrumDb: [Float] = []
    @Published private(set) var displayPartials: [Partial] = []
    @Published private(set) var displayChord: String?
    @Published private(set) var signalState: SignalState = .quiet
    @Published private(set) var inputLevelDbfs: Float = -120
    @Published private(set) var displayStrength: Float = 0
    @Published private(set) var isHeld = false

    private let events: AnyPublisher<AnalysisFrame, Never>
    private var cancellables = Set<AnyCancellable>()
    private var acquired = false

    init(events: AnyPublisher<AnalysisFrame, Never>? = nil) {
        self.events = events ?? CaptureHub.shared.events.eraseToAnyPublisher()

        self.events
            .receive(on: DispatchQueue.main)
            .sink { [weak self] frame in
                guard let self else { return }
                self.signal = frame.tuner.map { .active(Self.map($0)) } ?? .listening
                self.spectrumDb = frame.spectrumDb
                self.partials = frame.partials
                self.chord = frame.chord
                if frame.signalState == .tracking {
                    self.displaySpectrumDb = frame.spectrumDb
                    self.displayPartials = frame.partials
                    self.displayChord = frame.chord
                }
                self.signalState = frame.signalState
                self.inputLevelDbfs = frame.inputLevelDbfs
                self.displayStrength = frame.displayStrength
                self.isHeld = frame.isHeld
            }
            .store(in: &cancellables)
    }

    func startCapture() {
        if acquired { return }
        CaptureHub.shared.acquire()
        acquired = true
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
