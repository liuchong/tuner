import Combine
import XCTest
#if os(macOS)
@testable import TunarMac
#else
@testable import Tunar
#endif

@MainActor
final class PracticeWorkspaceTests: XCTestCase {
    func testBackgroundAnalysisPacketPublishesOnMainActor() async {
        let source = PracticeBackgroundSource()
        let model = PracticeWorkspaceModel(events: source.subject.eraseToAnyPublisher())
        model.start()
        await model.synchronize()
        let packet = PracticeCaptureFrame(frame: PracticeTestFixtures.frame(position: 4800),
            config: defaultTunarConfig(), generation: 1, referenceActive: false)
        await Task.detached { source.subject.send(packet) }.value
        await model.synchronize()
        XCTAssertEqual(model.status.count, 1)
        XCTAssertEqual(model.point?.frame.samplePosition, 4800)
    }

    func testSharedSessionReviewDoesNotResumeRecording() async throws {
        let session = PracticeSession()
        try session.lockReference(step: 0)
        session.start()
        let frame = PracticeTestFixtures.frame(position: 4800)
        let point = try session.observe(frame: frame, generation: 1, a4Hz: 440, temperament: 12, referenceActive: false)
        let model = PracticeWorkspaceModel(session: session, events: Empty<PracticeCaptureFrame, Never>().eraseToAnyPublisher())
        model.review(id: point.id)
        await model.synchronize()
        XCTAssertEqual(model.status.reviewId, point.id)
        XCTAssertEqual(model.status.state, .paused)
        model.returnToLive()
        await model.synchronize()
        XCTAssertNil(model.status.reviewId)
        XCTAssertEqual(model.status.state, .paused)
        model.review(id: point.id)
        await model.synchronize()
        model.lockReference(step: 12)
        await model.synchronize()
        XCTAssertNil(model.status.reviewId)
        XCTAssertEqual(model.status.state, .paused)
    }

    func testNavigationSuspendsRecordingWithoutClearingHistory() async throws {
        let session = PracticeSession()
        session.start()
        _ = try session.observe(frame: PracticeTestFixtures.frame(position: 4800), generation: 1,
                                a4Hz: 440, temperament: 12, referenceActive: false)
        let model = PracticeWorkspaceModel(session: session, events: Empty<PracticeCaptureFrame, Never>().eraseToAnyPublisher())
        model.suspend()
        await model.synchronize()
        XCTAssertEqual(model.status.count, 1)
        XCTAssertEqual(model.status.state, .paused)
    }

    func testImportErrorPreservesMeasurementAndPublishesError() async throws {
        let session = PracticeSession()
        session.start()
        _ = try session.observe(frame: PracticeTestFixtures.frame(position: 4800), generation: 1,
                                a4Hz: 440, temperament: 12, referenceActive: false)
        let model = PracticeWorkspaceModel(session: session, events: Empty<PracticeCaptureFrame, Never>().eraseToAnyPublisher())
        model.importMeasurements("{")
        await model.synchronize()
        XCTAssertEqual(model.status.count, 1)
        XCTAssertTrue(model.error?.contains("测量文件") == true)
    }

    func testTargetLockUsesCoreAndKeepsPreviewIdentity() async {
        let model = PracticeWorkspaceModel(events: Empty<PracticeCaptureFrame, Never>().eraseToAnyPublisher())
        model.lockInstrument(midi: 67, instrumentId: "dongxiao", variantId: "xiao_g_8", fingeringId: 12)
        await model.synchronize()
        XCTAssertEqual(model.config.targetMidi, 67)
        XCTAssertEqual(model.config.fingeringId, 12)
    }

    func testBackgroundRetainsHistoryWhileCaptureGenerationExpires() {
        let gate = CaptureLifecycleGate()
        let old = gate.acquire()!
        XCTAssertTrue(gate.setEnabled(false).stop)
        XCTAssertFalse(gate.commitIfCurrent(old) {})
        let resumed = gate.setEnabled(true).start!
        XCTAssertNotNil(gate.restart())
        XCTAssertFalse(gate.commitIfCurrent(resumed) {})
        XCTAssertTrue(gate.release())
    }

    func testImportRestoresSelectionStatisticsAndCurrentTarget() async throws {
        let session = PracticeSession()
        session.start()
        for index in 0..<20 {
            _ = try session.observe(frame: PracticeTestFixtures.frame(position: UInt64(index * 4800)), generation: 1,
                                    a4Hz: 440, temperament: 12, referenceActive: false)
        }
        let points = session.tracePage(offset: 0, limit: 6000)
        _ = session.summarize(startId: points[2].id, endId: points[15].id)
        try session.lockReference(step: 12)
        let model = PracticeWorkspaceModel(events: Empty<PracticeCaptureFrame, Never>().eraseToAnyPublisher())
        model.importMeasurements(try session.exportJson())
        await model.synchronize()
        XCTAssertEqual(model.status.selectionStartId, points[2].id)
        XCTAssertEqual(model.status.selectionEndId, points[15].id)
        XCTAssertEqual(model.config.targetStep, 12)
        XCTAssertFalse(model.summaries.isEmpty)
    }

    func testRapidReviewCommandsUseSerializedCursor() async throws {
        let session = PracticeSession()
        session.start()
        for index in 0..<30 {
            _ = try session.observe(frame: PracticeTestFixtures.frame(position: UInt64(index * 4800)), generation: 1,
                                    a4Hz: 440, temperament: 12, referenceActive: false)
        }
        let points = session.tracePage(offset: 0, limit: 6000)
        _ = try session.selectPoint(id: points[0].id)
        let model = PracticeWorkspaceModel(session: session, events: Empty<PracticeCaptureFrame, Never>().eraseToAnyPublisher())
        await model.synchronize()
        for _ in 0..<8 { model.stepReview(direction: 1) }
        await model.synchronize()
        XCTAssertEqual(model.status.reviewId, points[8].id)
        XCTAssertEqual(model.status.state, .paused)
    }

    func testWorkspaceLayoutUsesContentWidth() {
        XCTAssertFalse(PracticeWorkspaceLayout.showsInspector(contentWidth: 900))
        XCTAssertTrue(PracticeWorkspaceLayout.showsInspector(contentWidth: 1040))
        XCTAssertEqual(PracticeWorkspaceLayout.noteSize(availableHeight: 600), 64)
    }
}

private enum PracticeTestFixtures {
    static func frame(position: UInt64) -> AnalysisFrame {
        AnalysisFrame(tuner: TunarEvent(freqHz: 440, noteName: "A4", midi: 69, centsOff: 0,
            clarity: 0.98, solfege: "6", temperament: 12, temperamentStep: 0, temperamentCents: 0),
            spectrumDb: Array(repeating: -40, count: 64), wideSpectrumDb: Array(repeating: -50, count: 128),
            wideSpectrumMaxHz: 20000, waveformMin: Array(repeating: -0.1, count: 256),
            waveformMax: Array(repeating: 0.1, count: 256), samplePosition: position, sampleRateHz: 48000,
            partials: [], chord: nil, signalState: .tracking, inputLevelDbfs: -24, displayStrength: 1, isHeld: false)
    }
}

private final class PracticeBackgroundSource: @unchecked Sendable {
    let subject = PassthroughSubject<PracticeCaptureFrame, Never>()
}
