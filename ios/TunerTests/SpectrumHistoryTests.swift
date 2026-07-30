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

    func testProfessionalAnalysisFreezesWideWaveformAndTraceThenResumesNewSegment() {
        let history = SpectrumHistoryBuffer(
            binCount: 4,
            wideBinCount: 6,
            waveformColumns: 3,
            waterfallBinCount: 4,
            maxRows: 8,
            frameStride: 1
        )
        history.acceptAnalysis(
            spectrumDb: [-40, -30, -20, -10],
            wideSpectrumDb: [-70, -60, -50, -40, -30, -20],
            waveformMin: [-0.5, -0.25, -0.1],
            waveformMax: [0.5, 0.25, 0.1],
            samplePosition: 1_024,
            sampleRateHz: 44_100,
            trackingMidi: 69
        )
        let wide = history.currentWideSpectrum
        let waveform = history.waveformMin
        let trace = history.pitchTrace

        history.isPaused = true
        history.acceptAnalysis(
            spectrumDb: Array(repeating: -5, count: 4),
            wideSpectrumDb: Array(repeating: -5, count: 6),
            waveformMin: Array(repeating: -1, count: 3),
            waveformMax: Array(repeating: 1, count: 3),
            samplePosition: 2_048,
            sampleRateHz: 44_100,
            trackingMidi: 70
        )

        XCTAssertEqual(history.currentWideSpectrum, wide)
        XCTAssertEqual(history.waveformMin, waveform)
        XCTAssertEqual(history.pitchTrace, trace)

        history.isPaused = false
        history.acceptAnalysis(
            spectrumDb: Array(repeating: -25, count: 4),
            wideSpectrumDb: Array(repeating: -25, count: 6),
            waveformMin: Array(repeating: -0.2, count: 3),
            waveformMax: Array(repeating: 0.2, count: 3),
            samplePosition: 3_072,
            sampleRateHz: 44_100,
            trackingMidi: 71
        )

        XCTAssertEqual(history.pitchTrace.count, 2)
        XCTAssertNotEqual(history.pitchTrace[0].segment, history.pitchTrace[1].segment)
    }

    func testHoldingAndQuietCreatePitchTraceGapWithoutFakePoints() {
        let history = SpectrumHistoryBuffer(
            binCount: 2,
            wideBinCount: 2,
            waveformColumns: 2
        )
        func accept(_ position: UInt64, midi: Float?) {
            history.acceptAnalysis(
                spectrumDb: [-40, -30],
                wideSpectrumDb: [-50, -20],
                waveformMin: [-0.2, -0.1],
                waveformMax: [0.2, 0.1],
                samplePosition: position,
                sampleRateHz: 1_000,
                trackingMidi: midi
            )
        }

        accept(1_000, midi: 69)
        accept(2_000, midi: nil)
        accept(3_000, midi: nil)
        accept(4_000, midi: 71)

        XCTAssertEqual(history.pitchTrace.count, 2)
        XCTAssertEqual(history.pitchTrace[0].timeSeconds, 1)
        XCTAssertEqual(history.pitchTrace[1].timeSeconds, 4)
        XCTAssertNotEqual(history.pitchTrace[0].segment, history.pitchTrace[1].segment)
    }
}
