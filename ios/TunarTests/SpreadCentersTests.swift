import XCTest
@testable import Tunar

final class SpreadCentersTests: XCTestCase {
    private func assertNoOverlap(_ ys: [CGFloat], spacing: CGFloat, file: StaticString = #filePath, line: UInt = #line) {
        let sorted = ys.sorted()
        for (a, b) in zip(sorted, sorted.dropFirst()) {
            XCTAssertGreaterThanOrEqual(b - a, spacing - 1e-6, file: file, line: line)
        }
    }

    func testKeepsPegHeightsWhenRoomy() {
        XCTAssertEqual(spreadCenters([50, 150, 250], spacing: 54, minY: 25, maxY: 300), [50, 150, 250])
    }

    func testCollidingButtonsCenterOnIdealMean() {
        let ys = spreadCenters([100, 140, 180], spacing: 54, minY: 25, maxY: 400)
        assertNoOverlap(ys, spacing: 54)
        XCTAssertEqual(ys[0], 86, accuracy: 1e-6)
        XCTAssertEqual(ys[1], 140, accuracy: 1e-6)
    }

    func testPreservesInputOrderAndClampsToEdge() {
        let ys = spreadCenters([40, 10, 20], spacing: 54, minY: 25, maxY: 400)
        assertNoOverlap(ys, spacing: 54)
        XCTAssertEqual(ys[1], 25, accuracy: 1e-6)
        XCTAssertTrue(ys[1] < ys[2] && ys[2] < ys[0])
    }

    func testCompressesEvenlyWhenShortOfSpace() {
        XCTAssertEqual(spreadCenters([50, 60, 70], spacing: 54, minY: 25, maxY: 105), [25, 65, 105])
    }

    func testSelectedButtonIsTopmost() {
        XCTAssertGreaterThan(
            headstockButtonZIndex(selected: true, active: false),
            headstockButtonZIndex(selected: false, active: true)
        )
        XCTAssertGreaterThan(
            headstockButtonZIndex(selected: false, active: true),
            headstockButtonZIndex(selected: false, active: false)
        )
    }
}
