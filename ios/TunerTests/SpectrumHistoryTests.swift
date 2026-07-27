import XCTest
@testable import Tuner

final class SpectrumHistoryTests: XCTestCase {
    func testDefaultAddsNewestRowEverySecondFrameAndKeeps256Rows() {
        let history = SpectrumHistoryBuffer()

        for frame in 0..<520 {
            history.accept(Array(repeating: -Float(frame % 80), count: 64))
        }

        let rows = history.rowsNewestFirst()
        XCTAssertEqual(rows.count, 256)
        XCTAssertEqual(rows.first?.count, 96)
        XCTAssertEqual(rows.first?[0], -39)
        XCTAssertEqual(rows.last?[0], -9)
    }

    func testPeakHoldOnlyMovesForStrongerSignalAndNeverDriftsDown() {
        let history = SpectrumHistoryBuffer(
            binCount: 4,
            waterfallBinCount: 4,
            maxRows: 8,
            frameStride: 1
        )
        history.accept([-20, -30, -40, -50])

        for _ in 0..<200 {
            history.accept([-70, -70, -70, -70])
        }

        XCTAssertEqual(history.peakSpectrum, [-20, -30, -40, -50])

        history.accept([-10, -35, -39, -80])
        XCTAssertEqual(history.peakSpectrum, [-10, -30, -39, -50])
    }

    func testWaterfallLinearlyInterpolatesMeasuredBinsIntoFinerDisplayColumns() {
        let history = SpectrumHistoryBuffer(
            binCount: 2,
            waterfallBinCount: 3,
            maxRows: 2,
            frameStride: 1
        )

        history.accept([-80, 0])

        XCTAssertEqual(history.rowsNewestFirst().first, [-80, -40, 0])
    }

    func testPauseFreezesLivePeakAndWaterfallState() {
        let history = SpectrumHistoryBuffer(
            binCount: 4,
            waterfallBinCount: 4,
            maxRows: 8,
            frameStride: 1
        )
        history.accept([-30, -20, -10, -40])
        let live = history.currentSpectrum
        let peaks = history.peakSpectrum
        let rows = history.rowsNewestFirst()

        history.isPaused = true
        history.accept([-5, -5, -5, -5])

        XCTAssertEqual(history.currentSpectrum, live)
        XCTAssertEqual(history.peakSpectrum, peaks)
        XCTAssertEqual(history.rowsNewestFirst(), rows)
    }

    func testResetClearsOnlyPeakHoldAndPreservesLiveWaterfallAndPause() {
        let history = SpectrumHistoryBuffer(
            binCount: 4,
            waterfallBinCount: 4,
            maxRows: 8,
            frameStride: 1
        )
        history.accept([-20, -30, -40, -50])
        let live = history.currentSpectrum
        let rows = history.rowsNewestFirst()
        history.isPaused = true

        history.resetPeakHold()

        XCTAssertEqual(history.peakSpectrum, [-80, -80, -80, -80])
        XCTAssertEqual(history.currentSpectrum, live)
        XCTAssertEqual(history.rowsNewestFirst(), rows)
        XCTAssertTrue(history.isPaused)

        history.isPaused = false
        history.accept([-60, -50, -40, -30])
        XCTAssertEqual(history.peakSpectrum, [-60, -50, -40, -30])
    }
}
