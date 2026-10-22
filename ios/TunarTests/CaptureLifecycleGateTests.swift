import XCTest
@testable import Tunar

final class CaptureLifecycleGateTests: XCTestCase {
    func testBackgroundInvalidatesHardwareButRetainsSubscriptions() {
        let gate = CaptureLifecycleGate()
        let original = gate.acquire()!
        XCTAssertTrue(gate.setEnabled(false).stop)
        XCTAssertFalse(gate.commitIfCurrent(original) {})
        XCTAssertNil(gate.acquire())
        let resumed = gate.setEnabled(true).start!
        XCTAssertTrue(gate.commitIfCurrent(resumed) {})
        let retried = gate.restart()!
        XCTAssertFalse(gate.commitIfCurrent(resumed) {})
        XCTAssertTrue(gate.commitIfCurrent(retried) {})
        XCTAssertFalse(gate.release())
        XCTAssertTrue(gate.release())
    }

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
