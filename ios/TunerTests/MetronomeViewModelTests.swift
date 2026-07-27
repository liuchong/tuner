import Combine
import XCTest
@testable import Tuner

/// 假节拍器引擎：记录所有调用。
final class FakeMetronomeEngine: MetronomeEngine {
    var recordedBpm = 120.0
    var beats = 4
    var unit = 4
    var recordedAccents: [TickAccent] = []
    var accentSoundLen = 0
    var normalSoundLen = 0
    var lastTapTs: UInt64 = 0
    var tapReturn = 128.0
    var started = false

    func render(frames: UInt32) -> RenderFrame {
        RenderFrame(samples: Array(repeating: 0, count: Int(frames)), ticks: [])
    }

    func setBpm(bpm: Double) { recordedBpm = bpm }
    func setTimeSignature(beats: UInt8, unit: UInt8) {
        self.beats = Int(beats)
        self.unit = Int(unit)
    }

    func setAccents(accents: [TickAccent]) { recordedAccents = accents }
    func setClickSamples(accent: [Float], normal: [Float]) {
        accentSoundLen = accent.count
        normalSoundLen = normal.count
    }

    func tap(timestampSamples: UInt64) -> Double {
        lastTapTs = timestampSamples
        return tapReturn
    }

    func start(atSample: UInt64) { started = true }
    func stop() { started = false }
    func isRunning() -> Bool { started }
}

@MainActor
final class MetronomeViewModelTests: XCTestCase {
    private let defaultsSuite = "com.liuchong.tuner.tests.metronome"
    private var engine: FakeMetronomeEngine!
    private var defaults: UserDefaults!
    private var now: UInt64 = 0

    override func setUp() {
        super.setUp()
        engine = FakeMetronomeEngine()
        defaults = UserDefaults(suiteName: defaultsSuite)
        defaults.removePersistentDomain(forName: defaultsSuite)
        now = 0
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: defaultsSuite)
        defaults = nil
        super.tearDown()
    }

    private func makeVm() -> MetronomeViewModel {
        MetronomeViewModel(
            factory: FakeFactory(engine: engine),
            player: MetronomePlayer(),
            sounds: [
                .bell: Array(repeating: 0.1, count: 100),
                .click: Array(repeating: 0.1, count: 50),
                .beep: Array(repeating: 0.1, count: 80),
            ],
            clock: { [weak self] in self?.now ?? 0 },
            defaults: defaults
        )
    }

    struct FakeFactory: MetronomeEngineFactory {
        let engine: FakeMetronomeEngine
        func create(config: MetronomeConfig) -> MetronomeEngine { engine }
    }

    func testBpmClampedAndForwarded() {
        let vm = makeVm()
        vm.setBpm(500)
        XCTAssertEqual(vm.bpm, 250, accuracy: 1e-9)
        XCTAssertEqual(engine.recordedBpm, 250, accuracy: 1e-9)
        vm.setBpm(10)
        XCTAssertEqual(vm.bpm, 30, accuracy: 1e-9)
        vm.adjustBpm(5)
        XCTAssertEqual(vm.bpm, 35, accuracy: 1e-9)
    }

    func testTapForwarding() {
        now = 1000
        let vm = makeVm()
        vm.tap()
        XCTAssertEqual(engine.lastTapTs, 44100)
        XCTAssertEqual(vm.bpm, 128, accuracy: 1e-9)
        XCTAssertEqual(engine.recordedBpm, 128, accuracy: 1e-9)
    }

    func testAccentCycle() {
        let vm = makeVm()
        XCTAssertEqual(vm.accents[0], .accent)
        vm.cycleAccent(0)
        XCTAssertEqual(vm.accents[0], .normal)
        vm.cycleAccent(0)
        XCTAssertEqual(vm.accents[0], .muted)
        vm.cycleAccent(0)
        XCTAssertEqual(vm.accents[0], .accent)
        XCTAssertEqual(engine.recordedAccents[0], .accent)
    }

    func testTimeSignatureResetsAccents() {
        let vm = makeVm()
        vm.cycleAccent(1)
        vm.setTimeSignature(beats: 6, unit: 8)
        XCTAssertEqual(vm.beatsPerBar, 6)
        XCTAssertEqual(vm.beatUnit, 8)
        XCTAssertEqual(vm.accents.count, 6)
        XCTAssertEqual(vm.accents[0], .accent)
        XCTAssertTrue(vm.accents.dropFirst().allSatisfy { $0 == .normal })
        XCTAssertEqual(engine.beats, 6)
        XCTAssertEqual(engine.unit, 8)
    }

    func testSoundInjection() {
        let vm = makeVm()
        XCTAssertEqual(engine.accentSoundLen, 100)
        XCTAssertEqual(engine.normalSoundLen, 50)
        vm.normalSound = .beep
        XCTAssertEqual(engine.normalSoundLen, 80)
        vm.accentSound = .click
        XCTAssertEqual(engine.accentSoundLen, 50)
        vm.normalSound = .woodBlock
        XCTAssertEqual(engine.normalSoundLen, 3969)
    }

    func testExistingPersistedSoundIdentifiersRemainCompatible() {
        defaults.set("beep", forKey: "metro_accent_sound")
        defaults.set("bell", forKey: "metro_normal_sound")

        let vm = makeVm()

        XCTAssertEqual(vm.accentSound, .beep)
        XCTAssertEqual(vm.normalSound, .bell)
    }

    func testUnknownPersistedSoundsFallBackToDefaults() {
        defaults.set("unknown-accent", forKey: "metro_accent_sound")
        defaults.set("unknown-normal", forKey: "metro_normal_sound")

        let vm = makeVm()

        XCTAssertEqual(vm.accentSound, .bell)
        XCTAssertEqual(vm.normalSound, .click)
    }
}
