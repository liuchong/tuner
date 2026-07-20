import Combine
import Foundation

extension TickAccent {
    var persistName: String {
        switch self {
        case .accent: return "accent"
        case .normal: return "normal"
        case .muted: return "muted"
        }
    }

    static func from(persistName: String) -> TickAccent {
        switch persistName {
        case "accent": return .accent
        case "muted": return .muted
        default: return .normal
        }
    }
}

/// 节拍器面板 ViewModel（与 Android MetronomeViewModel 同构；节奏调度全在 core）。
@MainActor
final class MetronomeViewModel: ObservableObject {
    static let bpmMin = 30.0
    static let bpmMax = 250.0

    @Published var bpm: Double = 120.0 {
        didSet { apply { $0.setBpm(bpm: bpm) }; save() }
    }
    @Published var beatsPerBar: Int = 4 { didSet { save() } }
    @Published var beatUnit: Int = 4 { didSet { save() } }
    @Published var accents: [TickAccent] = MetronomeViewModel.defaultAccents(4) {
        didSet { apply { $0.setAccents(accents: accents) }; save() }
    }
    @Published var accentSound: TickSoundKind = .bell { didSet { applySounds(); save() } }
    @Published var normalSound: TickSoundKind = .click { didSet { applySounds(); save() } }
    @Published private(set) var playing = false
    @Published private(set) var currentBeat = -1
    @Published private(set) var flashSeq: Int = 0

    private let factory: MetronomeEngineFactory
    private let player: MetronomePlayer
    private let sounds: [TickSoundKind: [Float]]
    private let clock: () -> UInt64
    private var engine: MetronomeEngine?
    private var cancellables = Set<AnyCancellable>()

    init(
        factory: MetronomeEngineFactory = UniffiFactories.metronome,
        player: MetronomePlayer = MetronomePlayer(),
        sounds: [TickSoundKind: [Float]] = TickSounds.buildAll(),
        clock: @escaping () -> UInt64 = { UInt64(ProcessInfo.processInfo.systemUptime * 1000) }
    ) {
        self.factory = factory
        self.player = player
        self.sounds = sounds
        self.clock = clock
        restore()
        engine = factory.create(config: currentConfig())
        applySounds()

        player.ticks
            .receive(on: DispatchQueue.main)
            .sink { [weak self] ev in
                guard let self else { return }
                let waitMs = Int64(ev.atMs) - Int64(self.clock())
                if waitMs > 0 {
                    Task { [weak self] in
                        try? await Task.sleep(nanoseconds: UInt64(waitMs) * 1_000_000)
                        self?.currentBeat = ev.beatIndex
                        self?.flashSeq += 1
                    }
                } else {
                    self.currentBeat = ev.beatIndex
                    self.flashSeq += 1
                }
            }
            .store(in: &cancellables)
    }

    static func defaultAccents(_ beats: Int) -> [TickAccent] {
        (0..<beats).map { $0 == 0 ? TickAccent.accent : TickAccent.normal }
    }

    static let commonTimeSignatures: [(Int, Int)] = [
        (1, 4), (2, 4), (3, 4), (4, 4), (5, 4), (6, 4),
        (3, 8), (6, 8), (9, 8), (12, 8),
    ]

    private func currentConfig() -> MetronomeConfig {
        MetronomeConfig(
            sampleRate: TickSounds.sampleRate,
            bpm: bpm,
            beatsPerBar: UInt8(beatsPerBar),
            beatUnit: UInt8(beatUnit),
            accents: accents
        )
    }

    private func apply(_ f: (MetronomeEngine) -> Void) {
        engine.map(f)
    }

    private func applySounds() {
        apply {
            $0.setClickSamples(
                accent: sounds[accentSound] ?? TickSounds.synthesize(accentSound),
                normal: sounds[normalSound] ?? TickSounds.synthesize(normalSound)
            )
        }
    }

    func setBpm(_ v: Double) {
        bpm = v.clamped(to: Self.bpmMin...Self.bpmMax)
    }

    func adjustBpm(_ delta: Double) { setBpm(bpm + delta) }

    func tap() {
        let tsSamples = UInt64(Double(clock()) * TickSounds.sampleRate / 1000.0)
        engine.map { setBpm($0.tap(timestampSamples: tsSamples)) }
    }

    func setTimeSignature(beats: Int, unit: Int) {
        beatsPerBar = beats
        beatUnit = unit
        accents = Self.defaultAccents(beats)
        apply { $0.setTimeSignature(beats: UInt8(beats), unit: UInt8(unit)) }
    }

    func cycleAccent(_ index: Int) {
        guard accents.indices.contains(index) else { return }
        accents[index] = switch accents[index] {
        case .accent: .normal
        case .normal: .muted
        case .muted: .accent
        }
    }

    func togglePlay() {
        if playing { pause() } else { play() }
    }

    func play() {
        guard !playing else { return }
        engine.map { $0.start(atSample: 0) }
        engine.map { player.start(engine: $0) }
        playing = true
    }

    func pause() {
        guard playing else { return }
        player.stop()
        engine.map { $0.stop() }
        playing = false
        currentBeat = -1
    }

    // MARK: 持久化（UserDefaults）

    private func save() {
        let d = UserDefaults.standard
        d.set(bpm, forKey: "metro_bpm")
        d.set(beatsPerBar, forKey: "metro_beats")
        d.set(beatUnit, forKey: "metro_unit")
        d.set(accents.map(\.persistName), forKey: "metro_accents")
        d.set(accentSound.rawValue, forKey: "metro_accent_sound")
        d.set(normalSound.rawValue, forKey: "metro_normal_sound")
    }

    private func restore() {
        let d = UserDefaults.standard
        bpm = (d.object(forKey: "metro_bpm") as? Double ?? 120.0).clamped(to: Self.bpmMin...Self.bpmMax)
        beatsPerBar = d.object(forKey: "metro_beats") as? Int ?? 4
        beatUnit = d.object(forKey: "metro_unit") as? Int ?? 4
        if let raw = d.array(forKey: "metro_accents") as? [String] {
            let a = raw.map(TickAccent.from(persistName:))
            if a.count == beatsPerBar { accents = a }
        }
        accentSound = TickSoundKind(rawValue: d.string(forKey: "metro_accent_sound") ?? "") ?? .bell
        normalSound = TickSoundKind(rawValue: d.string(forKey: "metro_normal_sound") ?? "") ?? .click
    }
}
