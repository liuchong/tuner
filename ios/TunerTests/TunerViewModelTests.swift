import Combine
import XCTest
@testable import Tuner

final class TunerViewModelTests: XCTestCase {
    private var subject: PassthroughSubject<AnalysisFrame, Never>!

    override func setUp() {
        subject = PassthroughSubject<AnalysisFrame, Never>()
    }

    private func makeVm() -> TunerViewModel {
        TunerViewModel(events: subject.eraseToAnyPublisher())
    }

    private func a4Event() -> TunerEvent {
        TunerEvent(
            freqHz: 440.0, noteName: "A4", midi: 69, centsOff: -2.5,
            clarity: 0.95, solfege: "6",
            temperament: 12, temperamentStep: 0, temperamentCents: -2.5
        )
    }

    private func frame(
        _ ev: TunerEvent?,
        state: SignalState? = nil,
        strength: Float? = nil,
        held: Bool = false
    ) -> AnalysisFrame {
        AnalysisFrame(
            tuner: ev, spectrumDb: Array(repeating: -40, count: 64),
            partials: [], chord: nil,
            signalState: state ?? (ev == nil ? .quiet : .tracking),
            inputLevelDbfs: ev == nil ? -120 : -24,
            displayStrength: strength ?? (ev == nil ? 0 : 1),
            isHeld: held
        )
    }

    /// 事件经 receive(on: main) 异步投递，测试需让主队列跑一轮。
    private func pump() {
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.05))
    }

    func testEventMapsToActive() {
        let vm = makeVm()
        subject.send(frame(a4Event()))
        pump()
        guard case .active(let r) = vm.signal else {
            XCTFail("应为 Active")
            return
        }
        XCTAssertEqual(r.noteName, "A4")
        XCTAssertEqual(r.freqHz, 440.0, accuracy: 1e-9)
        XCTAssertEqual(r.centsOff, -2.5, accuracy: 1e-9)
        XCTAssertEqual(r.midi, 69)
        XCTAssertEqual(r.solfege, "6")
        XCTAssertEqual(vm.spectrumDb.count, 64)
        XCTAssertEqual(vm.displaySpectrumDb.count, 64)
    }

    func testHoldingAndQuietFollowCoreStateWithoutLocalTimeout() {
        let vm = makeVm()
        subject.send(frame(a4Event()))
        pump()
        guard case .active = vm.signal else { return XCTFail() }

        subject.send(frame(a4Event(), state: .holding, strength: 0.4, held: true))
        pump()
        guard case .active = vm.signal else { return XCTFail("保持帧应继续显示") }
        XCTAssertEqual(vm.displayStrength, 0.4, accuracy: 1e-6)
        XCTAssertTrue(vm.isHeld)
        XCTAssertEqual(vm.displaySpectrumDb, Array(repeating: -40, count: 64))

        subject.send(frame(nil, state: .quiet))
        pump()
        guard case .listening = vm.signal else {
            XCTFail("Quiet 帧应回到 Listening")
            return
        }
        XCTAssertEqual(vm.spectrumDb.count, 64)
        XCTAssertEqual(vm.displaySpectrumDb, Array(repeating: -40, count: 64))
        XCTAssertEqual(vm.displayStrength, 0)
    }

    func testNullEventDoesNotChange() {
        let vm = makeVm()
        subject.send(frame(nil))
        pump()
        guard case .listening = vm.signal else {
            XCTFail("null 事件不应改变状态")
            return
        }
    }

    func testNeedlePresentationAlwaysUsesOnlyCurrentTarget() {
        XCTAssertEqual(NeedlePresentation.renderedCents(for: -32), [-32])
        XCTAssertEqual(NeedlePresentation.renderedCents(for: 28), [28])
        XCTAssertEqual(NeedlePresentation.renderedCents(for: 7), [7])
        XCTAssertEqual(NeedlePresentation.renderedCents(for: nil), [])
    }

    func testNeedlePresentationClampsRangeAndUsesFastNonSpringDuration() {
        XCTAssertEqual(NeedlePresentation.renderedCents(for: -80), [-50])
        XCTAssertEqual(NeedlePresentation.renderedCents(for: 80), [50])
        XCTAssertEqual(NeedlePresentation.followDuration, 0.05, accuracy: 1e-9)
        XCTAssertLessThanOrEqual(NeedlePresentation.followDuration, 0.05)
    }
}
