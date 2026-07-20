import Combine
import XCTest
@testable import Tuner

final class TunerViewModelTests: XCTestCase {
    private var subject: PassthroughSubject<AnalysisFrame, Never>!
    private var now: UInt64 = 0

    override func setUp() {
        subject = PassthroughSubject<AnalysisFrame, Never>()
        now = 0
    }

    private func makeVm(timeoutMs: UInt64 = 800) -> TunerViewModel {
        TunerViewModel(events: subject.eraseToAnyPublisher(), clock: { [weak self] in self?.now ?? 0 }, timeoutMs: timeoutMs)
    }

    private func a4Event() -> TunerEvent {
        TunerEvent(
            freqHz: 440.0, noteName: "A4", midi: 69, centsOff: -2.5,
            clarity: 0.95, solfege: "6",
            temperament: 12, temperamentStep: 0, temperamentCents: -2.5
        )
    }

    private func frame(_ ev: TunerEvent?) -> AnalysisFrame {
        AnalysisFrame(
            tuner: ev, spectrumDb: Array(repeating: -40, count: 64),
            partials: [], chord: nil
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
    }

    func testTimeoutBackToListening() {
        let vm = makeVm()
        subject.send(frame(a4Event()))
        pump()
        guard case .active = vm.signal else { return XCTFail() }

        now = 799
        vm.onTick()
        guard case .active = vm.signal else { return XCTFail("799ms 不应超时") }

        now = 801
        vm.onTick()
        guard case .listening = vm.signal else {
            XCTFail("801ms 应回到 Listening")
            return
        }
        XCTAssertTrue(vm.spectrumDb.isEmpty)
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
}
