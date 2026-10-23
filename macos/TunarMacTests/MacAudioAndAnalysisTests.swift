import XCTest
@testable import TunarMac

final class MacAudioAndAnalysisTests: XCTestCase {
    func testLargeHardwareBatchPreservesEveryHop() {
        let ring=AudioFrameRing(windowSize:2048,hopSize:1024,capacity:16)
        let samples=Array(repeating:Float(0.1),count:10000)
        samples.withUnsafeBufferPointer { ring.push($0.baseAddress!,count:$0.count) }
        var positions:[UInt64]=[]
        while ring.consumeStamped({_,position in positions.append(position)}) {}
        XCTAssertEqual(positions,(2...9).map{UInt64($0*1024)})
        XCTAssertEqual(ring.maximumBatchSize,10000)
    }

    func testMacCaptureAcceptsOnlyUsableInputFormats() {
        XCTAssertTrue(isUsableCaptureFormat(sampleRate: 48_000, channelCount: 1))
        XCTAssertFalse(isUsableCaptureFormat(sampleRate: 0, channelCount: 1))
        XCTAssertFalse(isUsableCaptureFormat(sampleRate: 48_000, channelCount: 0))
        XCTAssertFalse(isUsableCaptureFormat(sampleRate: .nan, channelCount: 1))
    }

    func testMacCaptureRejectsExpiredAsynchronousStart() {
        let gate = CaptureLifecycleGate()
        let token = gate.acquire()

        XCTAssertNotNil(token)
        XCTAssertTrue(gate.release())
        XCTAssertFalse(gate.commitIfCurrent(token ?? -1) {})
    }

    func testCaptureFailureOffersRetryOnlyAfterPermissionIsGranted() {
        XCTAssertTrue(
            DesktopMicrophoneRecovery.shouldShowRetry(
                permissionGranted: true,
                captureStartFailed: true
            )
        )
        XCTAssertFalse(
            DesktopMicrophoneRecovery.shouldShowRetry(
                permissionGranted: false,
                captureStartFailed: true
            )
        )
        XCTAssertFalse(
            DesktopMicrophoneRecovery.shouldShowRetry(
                permissionGranted: true,
                captureStartFailed: false
            )
        )
    }

    func testDesktopAnalysisPauseAndResetKeepTheirDocumentedBoundaries() {
        let history = SpectrumHistoryBuffer(
            binCount: 2,
            wideBinCount: 2,
            waveformColumns: 2,
            waterfallBinCount: 3,
            maxRows: 4,
            frameStride: 1
        )
        history.acceptAnalysis(
            spectrumDb: [-30, -20],
            wideSpectrumDb: [-35, -25],
            waveformMin: [-0.5, -0.2],
            waveformMax: [0.4, 0.7],
            samplePosition: 48_000,
            sampleRateHz: 48_000,
            trackingMidi: 69
        )
        let rowsBeforePause = history.rowsNewestFirst()

        history.isPaused = true
        history.acceptAnalysis(
            spectrumDb: [-5, -4],
            wideSpectrumDb: [-3, -2],
            waveformMin: [-1, -1],
            waveformMax: [1, 1],
            samplePosition: 96_000,
            sampleRateHz: 48_000,
            trackingMidi: 72
        )

        XCTAssertEqual(history.rowsNewestFirst(), rowsBeforePause)
        XCTAssertEqual(history.currentSpectrum, [-30, -20])
        XCTAssertEqual(history.pitchTrace.map(\.midi), [69])

        history.resetPeakHold()

        XCTAssertEqual(history.currentSpectrum, [-30, -20])
        XCTAssertEqual(history.peakSpectrum, [-80, -80])
        XCTAssertEqual(history.rowsNewestFirst(), rowsBeforePause)
        XCTAssertTrue(history.isPaused)
    }
}
