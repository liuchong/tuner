import XCTest
@testable import Tunar

private final class FakeTonePlayer: ReferenceTonePlaying {
    var played: [Double] = []
    var stopCount = 0

    func play(frequencyHz: Double) { played.append(frequencyHz) }
    func stop() { stopCount += 1 }
}

@MainActor
final class ToolsViewModelTests: XCTestCase {
    func testCoreProvidesAntiMotionSicknessAt100Hz() {
        let tones = CorePresets.toolTones()
        XCTAssertEqual(tones.first?.id, "anti_motion_sickness")
        XCTAssertEqual(tones.first?.displayName, "防晕车")
        XCTAssertEqual(tones.first?.frequencyHz ?? 0, 100, accuracy: 1e-9)
    }

    func testToggleStartsAndStopsTone() throws {
        let player = FakeTonePlayer()
        let vm = ToolsViewModel(tones: CorePresets.toolTones(), player: player)
        let tone = try XCTUnwrap(vm.tones.first)

        vm.toggle(tone)
        XCTAssertEqual(player.played, [100])
        XCTAssertEqual(vm.playingId, tone.id)

        vm.toggle(tone)
        XCTAssertNil(vm.playingId)
        XCTAssertEqual(player.stopCount, 1)
    }

    func testStopIsIdempotentWhenIdle() throws {
        let player = FakeTonePlayer()
        let vm = ToolsViewModel(tones: CorePresets.toolTones(), player: player)
        vm.stop()
        XCTAssertEqual(player.stopCount, 0)

        vm.toggle(try XCTUnwrap(vm.tones.first))
        vm.stop()
        XCTAssertNil(vm.playingId)
        XCTAssertEqual(player.stopCount, 1)
    }
}
