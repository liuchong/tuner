import XCTest
@testable import Tunar

final class AudioFrameRingTests: XCTestCase {
    func testProducesOverlappingPreallocatedWindows() {
        let ring = AudioFrameRing(windowSize: 2_048, hopSize: 1_024, capacity: 4)
        let samples = (0..<3_072).map(Float.init)

        samples.withUnsafeBufferPointer { pointer in
            ring.push(pointer.baseAddress!, count: pointer.count)
        }

        var windows: [[Float]] = []
        while ring.consume({ windows.append($0) }) {}

        XCTAssertEqual(windows.count, 2)
        XCTAssertEqual(windows[0].first, 0)
        XCTAssertEqual(windows[0].last, 2_047)
        XCTAssertEqual(windows[1].first, 1_024)
        XCTAssertEqual(windows[1].last, 3_071)
    }

    func testDropsNewWindowInsteadOfBlockingWhenFull() {
        let ring = AudioFrameRing(windowSize: 8, hopSize: 4, capacity: 1)
        let samples = (0..<16).map(Float.init)

        samples.withUnsafeBufferPointer { pointer in
            ring.push(pointer.baseAddress!, count: pointer.count)
        }

        var windows: [[Float]] = []
        while ring.consume({ windows.append($0) }) {}
        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0], (0..<8).map(Float.init))
    }
}
