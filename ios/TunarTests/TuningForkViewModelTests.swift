import XCTest
@testable import Tunar

private final class FakeReferenceTonePlayer: ReferenceTonePlaying {
    var played: [Double] = []
    var stopCount = 0

    func play(frequencyHz: Double) { played.append(frequencyHz) }
    func stop() { stopCount += 1 }
}

@MainActor
final class TuningForkViewModelTests: XCTestCase {
    private func config(a4: Double = 440, temperament: UInt8 = 12) -> TunarConfig {
        TunarConfig(
            sampleRate: 44_100,
            frameHopSamples: 1_024,
            a4Hz: a4,
            noiseGateDbfs: -50,
            solfege: .numbered,
            key: KeyMode(tonicPc: 0, mode: .major),
            temperament: temperament
        )
    }

    private func tones(_ config: TunarConfig) -> [ReferenceTone] {
        [
            ReferenceTone(
                stepFromA4: -1,
                frequencyHz: config.a4Hz * 0.95,
                temperament: config.temperament,
                noteName: "G#4",
                centsFromNote: 0
            ),
            ReferenceTone(
                stepFromA4: 0,
                frequencyHz: config.a4Hz,
                temperament: config.temperament,
                noteName: "A4",
                centsFromNote: 0
            ),
        ]
    }

    func testOpenToggleCloseAndRefresh() {
        let player = FakeReferenceTonePlayer()
        let vm = TuningForkViewModel(
            initialConfig: config(),
            toneProvider: tones,
            player: player
        )

        vm.open()
        XCTAssertTrue(vm.isOpen)
        XCTAssertEqual(vm.tones[1].frequencyHz, 440, accuracy: 1e-9)

        vm.toggle(vm.tones[1])
        XCTAssertEqual(vm.playingStep, 0)
        XCTAssertEqual(player.played.last, 440)

        vm.toggle(vm.tones[0])
        XCTAssertEqual(vm.playingStep, -1)
        XCTAssertEqual(player.played.last, vm.tones[0].frequencyHz)

        vm.close()
        XCTAssertFalse(vm.isOpen)
        XCTAssertEqual(vm.playingStep, -1)
        XCTAssertEqual(vm.selectedStep, -1)
        XCTAssertEqual(player.stopCount, 0)

        vm.toggleSelected()
        XCTAssertNil(vm.playingStep)
        XCTAssertEqual(player.stopCount, 1)

        vm.toggleSelected()
        XCTAssertEqual(vm.playingStep, -1)
        XCTAssertEqual(player.played.last, vm.tones[0].frequencyHz)

        vm.open()
        vm.refresh(config(a4: 442, temperament: 19))
        XCTAssertEqual(vm.tones[1].frequencyHz, 442, accuracy: 1e-9)
        XCTAssertTrue(vm.tones.allSatisfy { $0.temperament == 19 })
        XCTAssertNil(vm.selectedStep)
    }

    func testBackgroundStopsButKeepsSelectionForResume() {
        let player = FakeReferenceTonePlayer()
        let vm = TuningForkViewModel(
            initialConfig: config(),
            toneProvider: tones,
            player: player
        )

        vm.toggle(vm.tones[1])
        vm.stopForBackground()
        XCTAssertNil(vm.playingStep)
        XCTAssertEqual(vm.selectedStep, 0)
        XCTAssertEqual(player.stopCount, 1)
    }
}
