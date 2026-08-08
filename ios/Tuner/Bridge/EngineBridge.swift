import Foundation
// 生成的 tuner_core.swift 直接编译进 App target，类型无需 import。

private func coreCentsBetween(freq: Double, target: Double) -> Double? {
    centsBetween(freqHz: freq, targetHz: target)
}

/// 引擎门面协议（业务逻辑全在 Rust core；协议化便于 XCTest mock）。

protocol PitchEngine {
    func feed(pcm: [Float]) -> TunerEvent?
    func analyze(pcm: [Float]) -> AnalysisFrame
    func listReferenceTones() -> [ReferenceTone]
    func setA4(hz: Double)
    func setSolfege(system: SolfegeSystem, key: KeyMode)
    func setNoiseGate(dbfs: Float)
    func setTemperament(divisions: UInt8)
}

protocol MetronomeEngine {
    func render(frames: UInt32) -> RenderFrame
    func setBpm(bpm: Double)
    func setTimeSignature(beats: UInt8, unit: UInt8)
    func setAccents(accents: [TickAccent])
    func setClickSamples(accent: [Float], normal: [Float])
    func tap(timestampSamples: UInt64) -> Double
    func start(atSample: UInt64)
    func stop()
    func isRunning() -> Bool
}

protocol MetronomeEngineFactory {
    func create(config: MetronomeConfig) -> MetronomeEngine
}

protocol PitchEngineFactory {
    func create(config: TunerConfig) -> PitchEngine
}

// MARK: - UniFFI 实装

final class UniffiPitchEngine: PitchEngine {
    private let inner: TunerEngine
    init(config: TunerConfig) { inner = TunerEngine(config: config) }
    func feed(pcm: [Float]) -> TunerEvent? { inner.feed(pcm: pcm) }
    func analyze(pcm: [Float]) -> AnalysisFrame { inner.analyze(pcm: pcm) }
    func listReferenceTones() -> [ReferenceTone] { inner.listReferenceTones() }
    func setA4(hz: Double) { inner.setA4(hz: hz) }
    func setSolfege(system: SolfegeSystem, key: KeyMode) { inner.setSolfege(system: system, key: key) }
    func setNoiseGate(dbfs: Float) { inner.setNoiseGate(dbfs: dbfs) }
    func setTemperament(divisions: UInt8) { inner.setTemperament(divisions: divisions) }
}

final class UniffiMetronomeEngine: MetronomeEngine {
    private let inner: Metronome
    init(config: MetronomeConfig) { inner = Metronome(config: config) }
    func render(frames: UInt32) -> RenderFrame { inner.render(frames: frames) }
    func setBpm(bpm: Double) { inner.setBpm(bpm: bpm) }
    func setTimeSignature(beats: UInt8, unit: UInt8) { inner.setTimeSignature(beats: beats, unit: unit) }
    func setAccents(accents: [TickAccent]) { inner.setAccents(accents: accents) }
    func setClickSamples(accent: [Float], normal: [Float]) { inner.setClickSamples(accent: accent, normal: normal) }
    func tap(timestampSamples: UInt64) -> Double { inner.tap(timestampSamples: timestampSamples) }
    func start(atSample: UInt64) { inner.start(atSample: atSample) }
    func stop() { inner.stop() }
    func isRunning() -> Bool { inner.isRunning() }
}

struct UniffiFactories {
    nonisolated(unsafe) static let pitch: any PitchEngineFactory = PitchFactory()
    nonisolated(unsafe) static let metronome: any MetronomeEngineFactory = MetronomeFactory()

    struct PitchFactory: PitchEngineFactory {
        func create(config: TunerConfig) -> PitchEngine { UniffiPitchEngine(config: config) }
    }
    struct MetronomeFactory: MetronomeEngineFactory {
        func create(config: MetronomeConfig) -> MetronomeEngine { UniffiMetronomeEngine(config: config) }
    }
}

// MARK: - 全局查询（core 预设，UI 不做业务计算）

enum CorePresets {
    static func instruments() -> [Instrument] { listInstruments() }
    static func tunings(instrumentId: String) -> [Tuning] { listTunings(instrumentId: instrumentId) }
    static func fingeringCharts(instrumentId: String) -> [FingeringChart] {
        listFingeringCharts(instrumentId: instrumentId)
    }
    static func centsBetween(freq: Double, target: Double) -> Double? {
        coreCentsBetween(freq: freq, target: target)
    }
    static func referenceTones(config: TunerConfig) -> [ReferenceTone] {
        UniffiPitchEngine(config: config).listReferenceTones()
    }
}

/// 默认调音器配置（C 大调、简谱、A4=440、-45dBFS、12-TET）。
func defaultTunerConfig(sampleRate: Double = 44100.0) -> TunerConfig {
    TunerConfig(
        sampleRate: sampleRate,
        frameHopSamples: 1_024,
        a4Hz: 440.0,
        noiseGateDbfs: -45.0,
        solfege: .numbered,
        key: KeyMode(tonicPc: 0, mode: .major),
        temperament: 12
    )
}
