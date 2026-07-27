import XCTest
@testable import Tuner

final class CaptureLifecycleGateTests: XCTestCase {
    func testExpiredStartCannotCommitAfterFinalRelease() {
        let gate = CaptureLifecycleGate()

        let token = gate.acquire()
        XCTAssertNotNil(token)
        XCTAssertTrue(gate.release())

        var committed = false
        XCTAssertFalse(gate.commitIfCurrent(token!) { committed = true })
        XCTAssertFalse(committed)
    }

    func testSharedSubscribersOnlyStopAfterFinalRelease() {
        let gate = CaptureLifecycleGate()

        let token = gate.acquire()
        XCTAssertNil(gate.acquire())
        XCTAssertFalse(gate.release())

        var committed = false
        XCTAssertTrue(gate.commitIfCurrent(token!) { committed = true })
        XCTAssertTrue(committed)
        XCTAssertTrue(gate.release())
    }
}
