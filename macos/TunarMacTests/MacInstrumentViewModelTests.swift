import Combine
import XCTest
@testable import TunarMac

final class MacInstrumentViewModelTests: XCTestCase {
    func testDongxiaoOwnsFixedScaleAndChromaticLists() {
        let subject = PassthroughSubject<AnalysisFrame, Never>()
        let vm = InstrumentViewModel(events: subject.eraseToAnyPublisher())
        vm.selectInstrument("dongxiao")

        XCTAssertEqual(vm.windFigure, .xiao)
        XCTAssertTrue(vm.supportsTongyin)
        XCTAssertEqual(vm.holeSystem, "8孔")
        // 三音区按实测孔位展开，指法图未覆盖的高音格留空。
        XCTAssertEqual(vm.scaleNotes.count, 19)
        XCTAssertEqual(vm.chromaticNotes.count, 32)
        XCTAssertEqual(Set(vm.scaleNotes.map(\.register)), [.low, .middle, .high])

        let chromaticNames = vm.chromaticNotes.map(\.noteName)
        vm.selectTongyin(9)
        XCTAssertEqual(
            Set(vm.scaleNotes.filter { $0.register == .low }.map(\.solfege)),
            Set(["1", "2", "3", "4", "5", "6", "7"])
        )
        XCTAssertEqual(vm.chromaticNotes.map(\.noteName), chromaticNames)
        XCTAssertEqual(vm.tongyinDegree, 9)
        vm.selectTongyin(8)
        XCTAssertEqual(vm.tongyinDegree, 9)
        vm.selectDetailTongyin(8)
        XCTAssertEqual(vm.detailTongyinDegree, 8)
        XCTAssertEqual(vm.tongyinDegree, 9)
    }

    func testMainAndDetailKeepIndependentDiagramPreviews() {
        let subject = PassthroughSubject<AnalysisFrame, Never>()
        let vm = InstrumentViewModel(events: subject.eraseToAnyPublisher())
        vm.selectInstrument("dongxiao")

        XCTAssertEqual(vm.scaleDiagramNote?.id, 0)
        XCTAssertEqual(vm.scaleDiagramNote?.holes, Array(repeating: .closed, count: 8))
        vm.selectFingeringPreview(7, scope: .scale)
        vm.selectFingeringPreview(1, scope: .chromatic)
        XCTAssertEqual(vm.scaleDiagramNote?.id, 7)
        XCTAssertEqual(vm.chromaticDiagramNote?.id, 1)

        vm.selectHoleSystem("6孔")
        XCTAssertEqual(vm.scaleDiagramNote?.holes.count, 6)
        XCTAssertEqual(vm.chromaticDiagramNote?.holes.count, 6)
    }

    func testRowsGroupThreeRegistersAtCoreAnchors() {
        let subject = PassthroughSubject<AnalysisFrame, Never>()
        let vm = InstrumentViewModel(events: subject.eraseToAnyPublisher())
        vm.selectInstrument("dongxiao")

        let rows = XiaoFingeringLayout.rows(for: vm.chromaticNotes)
        XCTAssertEqual(rows.count, 12)
        let outlet = rows.first { $0.baseSemitones == 0 }
        XCTAssertNil(outlet?.anchorHole)
        XCTAssertEqual(outlet?.notes.map(\.id), [0, 12, 24])
        XCTAssertEqual(rows.first { $0.baseSemitones == 1 }?.anchorHole, 0)
        XCTAssertEqual(rows.first { $0.baseSemitones == 10 }?.anchorHole, 7)
    }

    func testZhudiAndShakuhachiShareTheAnchoredChart() {
        let subject = PassthroughSubject<AnalysisFrame, Never>()
        let vm = InstrumentViewModel(events: subject.eraseToAnyPublisher())

        vm.selectInstrument("zhudi")
        XCTAssertEqual(vm.windFigure, .dizi)
        XCTAssertTrue(vm.supportsTongyin)
        XCTAssertEqual(vm.holeCount, 6)
        XCTAssertEqual(Set(vm.scaleNotes.map(\.register)), [.low, .middle, .high])
        XCTAssertFalse(vm.chromaticNotes.isEmpty)

        vm.selectInstrument("shakuhachi")
        XCTAssertEqual(vm.windFigure, .shakuhachi)
        XCTAssertFalse(vm.supportsTongyin)
        XCTAssertEqual(vm.holeCount, 5)
        XCTAssertEqual(vm.backHoleCount, 1)
        XCTAssertEqual(vm.scaleNotes.count, 11)
        XCTAssertTrue(vm.chromaticNotes.isEmpty)
    }

    func testHeadstockStyleSwitchesGuitarFigure() {
        let suite = "MacInstrumentViewModelTests.headstock"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        let subject = PassthroughSubject<AnalysisFrame, Never>()
        let vm = InstrumentViewModel(events: subject.eraseToAnyPublisher(), defaults: defaults)

        vm.selectInstrument("guitar")
        XCTAssertEqual(vm.stringFigure, .headstock(.inline6))
        vm.selectHeadstockStyle(.threePlusThree)
        XCTAssertEqual(vm.stringFigure, .headstock(.threePlusThree))
        vm.selectInstrument("guqin")
        XCTAssertEqual(vm.stringFigure, .guqin)
    }
}
