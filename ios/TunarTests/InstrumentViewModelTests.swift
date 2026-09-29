import Combine
import XCTest
@testable import Tunar

/// 乐器面板 ViewModel（管乐孔位指法部分）测试。
final class InstrumentViewModelTests: XCTestCase {
    private var subject: PassthroughSubject<AnalysisFrame, Never>!

    override func setUp() {
        subject = PassthroughSubject<AnalysisFrame, Never>()
    }

    private func makeVm(instrument: String = "dongxiao") -> InstrumentViewModel {
        let vm = InstrumentViewModel(events: subject.eraseToAnyPublisher())
        vm.selectInstrument(instrument)
        return vm
    }

    func testDongxiaoDefaultsToEightHoleGKeyAndSol5() {
        let vm = makeVm()
        XCTAssertEqual(vm.kind, .wind)
        XCTAssertEqual(vm.keyNames, ["G调洞箫", "F调洞箫"])
        XCTAssertEqual(vm.keyName, "G调洞箫")
        XCTAssertEqual(vm.holeSystems, ["8孔", "6孔"])
        XCTAssertEqual(vm.holeSystem, "8孔", "8 孔为当前流行制式，应作默认")
        XCTAssertEqual(vm.holeCount, 8)
        XCTAssertEqual(vm.backHoleCount, 1)
        XCTAssertEqual(vm.tongyinDegree, 7, "默认筒音作 5")
        XCTAssertEqual(vm.keyDisplay, "筒音作5 · G宫")
    }

    func testScaleUsesMeasuredPatternForEachRegister() {
        let vm = makeVm()
        // 7 个孔位 × 低/中/高，减去指法图未覆盖的两格高音。
        XCTAssertEqual(vm.scaleNotes.count, 19)
        XCTAssertEqual(vm.scaleNotes.first?.label, "筒音")
        XCTAssertEqual(vm.scaleNotes.first?.noteName, "D4") // G 调洞箫筒音 d1
        XCTAssertEqual(vm.scaleNotes.first?.holes, Array(repeating: .closed, count: 8))
        XCTAssertEqual(Set(vm.scaleNotes.map(\.register)), [.low, .middle, .high])
        // 中音区沿用低音孔位；高音区孔位另有一套，按指法图实测。
        let closedTube = vm.scaleNotes.filter { $0.baseSemitones == 0 }
        XCTAssertEqual(closedTube.map(\.noteName), ["D4", "D5", "D6"])
        XCTAssertEqual(closedTube.map(\.register), [.low, .middle, .high])
        XCTAssertEqual(closedTube.map(\.overblown), [false, true, true])
        XCTAssertEqual(closedTube[1].holes, closedTube[0].holes)
        XCTAssertNotEqual(closedTube[2].holes, closedTube[0].holes)
        XCTAssertEqual(closedTube[2].label, "闭第二五六七孔·超吹二")
        // 指法图没有的高音格不出现，列内留空。
        XCTAssertNil(vm.scaleNotes.first { $0.baseSemitones == 9 && $0.register == .high })
        XCTAssertEqual(
            vm.scaleNotes.filter { $0.register == .low }.map(\.solfege),
            ["5", "6", "7", "1", "2", "3", "4"]
        )
        XCTAssertEqual(
            vm.scaleNotes.filter { $0.register == .low }.map(\.baseSemitones),
            [0, 2, 4, 5, 7, 9, 10]
        )
        XCTAssertEqual(vm.scaleNotes.map(\.id).min(), 0)
        XCTAssertEqual(Set(vm.scaleNotes.map(\.id)).count, 19)
    }

    func testHoleSystemSwitchKeepsKeyAndTongyinButChangesFingering() {
        let vm = makeVm()
        let eightHoleThirdNote = vm.scaleNotes.first {
            $0.baseSemitones == 4 && $0.register == .low
        }!
        vm.selectHoleSystem("6孔")
        XCTAssertEqual(vm.holeSystem, "6孔")
        XCTAssertEqual(vm.holeCount, 6)
        XCTAssertEqual(vm.keyName, "G调洞箫")
        XCTAssertEqual(vm.tongyinDegree, 7)
        // 8 孔保持第 2、6 辅助孔关闭，不能退化成八孔依次开放。
        XCTAssertEqual(eightHoleThirdNote.label, "开第一三孔")
        XCTAssertEqual(
            eightHoleThirdNote.holes,
            [.open, .closed, .open, .closed, .closed, .closed, .closed, .closed]
        )
        XCTAssertEqual(eightHoleThirdNote.fingeringKind, .sequential)
        let sixHoleThirdNote = vm.scaleNotes.first {
            $0.baseSemitones == 4 && $0.register == .low
        }!
        XCTAssertEqual(sixHoleThirdNote.label, "开第一二孔")
        XCTAssertEqual(sixHoleThirdNote.noteName, eightHoleThirdNote.noteName)
        // 6 孔常用筒音只有作 5/1/2；8 孔另有作 3/6
        XCTAssertEqual(vm.tongyinOptions.filter(\.common).map(\.degree), [0, 2, 7])
        vm.selectHoleSystem("8孔")
        XCTAssertEqual(vm.tongyinOptions.filter(\.common).map(\.degree), [0, 2, 4, 7, 9])
    }

    func testKeySwitchKeepsHoleSystemAndTongyin() {
        let vm = makeVm()
        vm.selectHoleSystem("6孔")
        vm.selectTongyin(2)
        vm.selectKey("F调洞箫")
        XCTAssertEqual(vm.keyName, "F调洞箫")
        XCTAssertEqual(vm.holeSystem, "6孔")
        XCTAssertEqual(vm.tongyinDegree, 2)
        XCTAssertEqual(vm.scaleNotes.first?.noteName, "C4") // F 调洞箫筒音 c1
    }

    func testSolfegeColumnRefiltersScaleToCompleteOneThroughSevenAndKeepsChromaticStable() {
        let vm = makeVm()
        let chromaticBefore = vm.chromaticNotes
        XCTAssertEqual(
            vm.scaleNotes.filter { $0.register == .low }.map(\.solfege),
            ["5", "6", "7", "1", "2", "3", "4"]
        )
        XCTAssertEqual(
            vm.scaleNotes.filter { $0.register == .low }.map(\.baseSemitones),
            [0, 2, 4, 5, 7, 9, 10]
        )
        vm.selectTongyin(2) // 作 2
        XCTAssertEqual(vm.keyDisplay, "筒音作2 · C宫")
        XCTAssertEqual(
            vm.scaleNotes.filter { $0.register == .low }.map(\.solfege),
            ["2", "3", "4", "5", "6", "7", "1"]
        )
        XCTAssertEqual(
            vm.scaleNotes.filter { $0.register == .low }.map(\.baseSemitones),
            [0, 2, 3, 5, 7, 9, 10]
        )
        XCTAssertEqual(vm.chromaticNotes.first?.solfege, "5")
        XCTAssertEqual(vm.detailTongyinDegree, 7)
        for (a, b) in zip(chromaticBefore, vm.chromaticNotes) {
            XCTAssertEqual(a.noteName, b.noteName)
            XCTAssertEqual(a.holes, b.holes)
        }
        // 主表拒绝半音筒音档；十二音详情独立允许完整 12 档。
        XCTAssertEqual(vm.tongyinOptions.count, 12)
        vm.selectTongyin(6)
        XCTAssertEqual(vm.tongyinDegree, 2)
        vm.selectDetailTongyin(6)
        XCTAssertEqual(vm.tongyinOptions[6].solfege, "#4")
        XCTAssertEqual(
            Set(vm.scaleNotes.filter { $0.register == .low }.map(\.solfege)),
            Set(["1", "2", "3", "4", "5", "6", "7"])
        )
        XCTAssertEqual(vm.chromaticNotes.first?.solfege, "#4")
        XCTAssertEqual(vm.detailTongyinDegree, 6)
        XCTAssertEqual(vm.tongyinDegree, 2)
    }

    func testTongyinChangeKeepsActiveHighlight() {
        let vm = makeVm()
        subject.send(frame(freqHz: 293.6648))
        pump()
        let activeBefore = vm.scaleNotes.filter(\.active).map(\.id)
        XCTAssertEqual(activeBefore, [0])
        XCTAssertEqual(vm.chromaticNotes.filter(\.active).map(\.id), [0])
        XCTAssertEqual(vm.targetNoteName, "D4")
        vm.selectTongyin(0)
        XCTAssertEqual(
            vm.scaleNotes.filter(\.active).map(\.id),
            [0],
            "只换唱名不应清掉读数"
        )
        XCTAssertEqual(vm.chromaticNotes.filter(\.active).map(\.id), [0])
        XCTAssertNotNil(vm.centsToTarget)
    }

    func testKeyAndHoleChangesRefreshBothListsWithoutDroppingReading() {
        let vm = makeVm()
        subject.send(frame(freqHz: 293.6648))
        pump()
        vm.selectHoleSystem("6孔")
        XCTAssertEqual(vm.scaleNotes.filter(\.active).map(\.id), [0])
        XCTAssertEqual(vm.chromaticNotes.filter(\.active).map(\.id), [0])
        XCTAssertNotNil(vm.centsToTarget)
        vm.selectKey("F调洞箫")
        // 六孔指法表在列间冲突处留空，格数少于八孔。
        XCTAssertEqual(vm.scaleNotes.count, 18)
        XCTAssertEqual(vm.chromaticNotes.count, 28)
        XCTAssertFalse(vm.chromaticNotes.filter(\.active).isEmpty)
        XCTAssertNotNil(vm.centsToTarget)
    }

    func testScaleAndChromaticListsAlwaysCoexist() {
        let vm = makeVm()
        XCTAssertEqual(vm.scaleNotes.count, 19)
        // 12 低音 + 12 中音 + 指法图覆盖到 31 半音的 8 个高音。
        XCTAssertEqual(vm.chromaticNotes.count, 32)
        let halfHole = vm.chromaticNotes.first { $0.baseSemitones == 1 && $0.register == .low }
        XCTAssertEqual(halfHole?.label, "第一孔半开")
        XCTAssertEqual(halfHole?.holes.first, .half)
        XCTAssertEqual(halfHole?.inScale, false, "G 宫下 #5 为偏音")
        let fork = vm.chromaticNotes.first { $0.baseSemitones == 10 && $0.register == .low }
        XCTAssertEqual(fork?.label, "闭第二五六七孔")
        XCTAssertEqual(fork?.inScale, true, "G 宫下 C 为正声")
        XCTAssertEqual(fork?.overblown, false)
        // 22 半音的中音区换用另一种叉口，不是低音叉口的超吹。
        let forkMiddle = vm.chromaticNotes.first { $0.baseSemitones == 10 && $0.register == .middle }
        XCTAssertEqual(forkMiddle?.label, "闭第三四五六八孔·超吹")
        XCTAssertNotEqual(forkMiddle?.holes, fork?.holes)
        XCTAssertEqual(forkMiddle?.overblown, true)
    }

    func testFingeringLayoutGroupsThreeRegistersAtEachCoreAnchor() {
        let vm = makeVm()
        let rows = XiaoFingeringLayout.rows(for: vm.chromaticNotes)
        XCTAssertEqual(rows.count, 12)
        let outlet = rows.first { $0.baseSemitones == 0 }
        XCTAssertNil(outlet?.anchorHole)
        XCTAssertEqual(outlet?.notes.map(\.id), [0, 12, 24])
        XCTAssertEqual(rows.first { $0.baseSemitones == 1 }?.anchorHole, 0)
        XCTAssertTrue(
            rows.first { $0.baseSemitones == 1 }?.notes.allSatisfy {
                $0.fingeringKind == .combination
            } == true
        )
        XCTAssertEqual(rows.first { $0.baseSemitones == 10 }?.anchorHole, 7)
        XCTAssertEqual(rows.first { $0.baseSemitones == 11 }?.anchorHole, 7)

        vm.selectHoleSystem("6孔")
        let sixHole = XiaoFingeringLayout.rows(for: vm.chromaticNotes)
        XCTAssertEqual(sixHole.first { $0.baseSemitones == 3 }?.anchorHole, 1)
        XCTAssertEqual(sixHole.first { $0.baseSemitones == 11 }?.anchorHole, 5)
    }

    func testSolfegeTransposeDoesNotMoveFingeringAnchors() {
        let vm = makeVm()
        let before = XiaoFingeringLayout.rows(for: vm.chromaticNotes).map {
            "\($0.id):\($0.anchorHole.map(Int.init) ?? -1)"
        }

        vm.selectDetailTongyin(2)

        let after = XiaoFingeringLayout.rows(for: vm.chromaticNotes).map {
            "\($0.id):\($0.anchorHole.map(Int.init) ?? -1)"
        }
        XCTAssertEqual(after, before)
        XCTAssertEqual(vm.chromaticNotes.first?.solfege, "2")
        XCTAssertEqual(vm.tongyinDegree, 7)
    }

    func testLargeDiagramDefaultsToFundamentalAndKeepsSeparatePreviews() {
        let vm = makeVm()
        XCTAssertEqual(vm.scaleDiagramNote?.id, 0)
        XCTAssertEqual(vm.scaleDiagramNote?.holes, Array(repeating: .closed, count: 8))
        XCTAssertEqual(vm.chromaticDiagramNote?.id, 0)

        vm.selectFingeringPreview(7, scope: .scale)
        vm.selectFingeringPreview(1, scope: .chromatic)
        XCTAssertEqual(vm.scalePreviewNoteId, 7)
        XCTAssertEqual(vm.chromaticPreviewNoteId, 1)
        XCTAssertEqual(vm.scaleDiagramNote?.id, 7)
        XCTAssertEqual(vm.chromaticDiagramNote?.id, 1)

        vm.selectHoleSystem("6孔")
        XCTAssertEqual(vm.scaleDiagramNote?.id, 7)
        XCTAssertEqual(vm.scaleDiagramNote?.holes.count, 6)
        XCTAssertEqual(vm.chromaticDiagramNote?.id, 1)
        XCTAssertEqual(vm.chromaticDiagramNote?.holes.count, 6)
    }

    func testTapWinsOverLiveDetectionAndSecondTapReturnsToIt() {
        let vm = makeVm()
        subject.send(frame(freqHz: 293.6648))
        pump()
        XCTAssertEqual(vm.scaleDiagramNote?.id, 0, "未点选时跟随实时识别")

        vm.selectFingeringPreview(7, scope: .scale)
        XCTAssertEqual(vm.scaleDiagramNote?.id, 7, "点选后大图固定显示该指法")
        subject.send(frame(freqHz: 293.6648))
        pump()
        XCTAssertEqual(vm.scaleDiagramNote?.id, 7, "实时采音不再顶掉点选")

        vm.selectFingeringPreview(7, scope: .scale)
        XCTAssertNil(vm.scalePreviewNoteId, "再点同一条取消点选")
        XCTAssertEqual(vm.scaleDiagramNote?.id, 0)
        XCTAssertTrue(vm.scaleDiagramNote?.active == true)
    }

    func testMainPageTunesScaleAndDetailTunesChromaticNotes() {
        let vm = makeVm()
        subject.send(frame(freqHz: 311.1270)) // D♯4，七声主表中不存在
        pump()
        XCTAssertFalse(vm.isChromaticDetailPresented)
        let mainActive = vm.scaleNotes.filter(\.active).map(\.id)
        XCTAssertEqual(mainActive.count, 1)
        XCTAssertTrue([Int32(0), Int32(2)].contains(mainActive[0]))
        XCTAssertNotEqual(mainActive, [1])

        vm.setChromaticDetailPresented(true)
        XCTAssertEqual(vm.chromaticNotes.filter(\.active).map(\.id), [1])
        XCTAssertEqual(vm.targetNoteName, "D#4")

        vm.setChromaticDetailPresented(false)
        XCTAssertEqual(vm.scaleNotes.filter(\.active).map(\.id), mainActive)
    }

    func testShakuhachiUsesAnchoredChartSplitByOctaveWithoutTongyin() {
        let vm = makeVm(instrument: "shakuhachi")
        XCTAssertEqual(vm.windFigure, .shakuhachi)
        XCTAssertFalse(vm.supportsTongyin, "尺八按五声音阶，不做筒音转调")
        XCTAssertFalse(vm.supportsChromatic)
        XCTAssertEqual(vm.holeCount, 5)
        XCTAssertEqual(vm.backHoleCount, 1)
        XCTAssertTrue(vm.chromaticNotes.isEmpty)
        XCTAssertEqual(vm.scaleNotes.count, 11)
        XCTAssertEqual(vm.scaleNotes.first?.holes, Array(repeating: .closed, count: 5))
        XCTAssertEqual(vm.scaleNotes.filter { $0.register == .low }.count, 5, "乙音五声")
        XCTAssertEqual(vm.scaleNotes.filter { $0.register == .middle }.count, 5, "甲音五声")
        XCTAssertEqual(vm.scaleNotes.filter { $0.register == .high }.count, 1)
        XCTAssertTrue(vm.scaleNotes.allSatisfy { $0.holes.count == 5 })
    }

    func testZhudiUsesThreeRegisterChartWithTongyin() {
        let vm = makeVm(instrument: "zhudi")
        XCTAssertEqual(vm.windFigure, .dizi)
        XCTAssertTrue(vm.supportsTongyin)
        XCTAssertTrue(vm.supportsChromatic)
        XCTAssertEqual(vm.holeCount, 6)
        XCTAssertTrue(vm.keyDisplay.hasPrefix("筒音作5"))
        XCTAssertEqual(Set(vm.scaleNotes.map(\.register)), [.low, .middle, .high])
        XCTAssertTrue(vm.scaleNotes.allSatisfy { $0.holes.count == 6 })
        XCTAssertEqual(
            vm.scaleNotes.filter { $0.register == .low }.map(\.solfege),
            ["5", "6", "7", "1", "2", "3", "4"]
        )
        vm.selectTongyin(2)
        XCTAssertTrue(vm.keyDisplay.hasPrefix("筒音作2"))
        XCTAssertFalse(
            vm.scaleNotes.contains { $0.solfege.contains("#") },
            "七声主表按转调重新取音，不出现变化音"
        )
        XCTAssertFalse(vm.chromaticNotes.isEmpty)
    }

    func testGuitarHeadstockStyleDefaultsToInlineAndPersists() {
        let suite = "InstrumentViewModelTests.headstock"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }

        let vm = InstrumentViewModel(events: subject.eraseToAnyPublisher(), defaults: defaults)
        vm.selectInstrument("guitar")
        XCTAssertEqual(vm.stringFigure, .headstock(.inline6))
        vm.selectHeadstockStyle(.threePlusThree)
        XCTAssertEqual(vm.stringFigure, .headstock(.threePlusThree))

        let reopened = InstrumentViewModel(events: subject.eraseToAnyPublisher(), defaults: defaults)
        reopened.selectInstrument("guitar")
        XCTAssertEqual(reopened.stringFigure, .headstock(.threePlusThree))
        reopened.selectInstrument("ukulele")
        XCTAssertEqual(reopened.stringFigure, .ukuleleHeadstock)
        reopened.selectInstrument("guqin")
        XCTAssertEqual(reopened.stringFigure, .guqin)
    }

    func testTappingStringLocksManualAndInstrumentSwitchReturnsToAuto() {
        let vm = makeVm(instrument: "guqin")
        XCTAssertNil(vm.selectedStringIndex)
        vm.selectString(3)
        XCTAssertEqual(vm.mode, .manual)
        XCTAssertEqual(vm.selectedStringIndex, 3)
        vm.selectString(99)
        XCTAssertEqual(vm.selectedStringIndex, 3, "越界索引不改变锁定")
        vm.selectInstrument("guitar")
        XCTAssertEqual(vm.mode, .auto)
        XCTAssertNil(vm.selectedStringIndex)
    }

    func testHeadstockLayoutsMapEveryStringToOnePeg() {
        for (layout, count) in [
            (HeadstockLayout.inline6, 6), (.threePlusThree, 6), (.ukulele, 4),
        ] {
            XCTAssertEqual(layout.pegs.map(\.stringNumber).sorted(), Array(1...count))
            for peg in layout.pegs {
                XCTAssertEqual(layout.hitString(at: peg.post), peg.stringNumber)
            }
        }
    }

    /// 事件经 receive(on: main) 异步投递，测试需让主队列跑一轮。
    private func pump() {
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.05))
    }

    private func frame(freqHz: Double) -> AnalysisFrame {
        AnalysisFrame(
            tuner: TunarEvent(
                freqHz: freqHz, noteName: "", midi: 0, centsOff: 0,
                clarity: 0.95, solfege: "",
                temperament: 12, temperamentStep: 0, temperamentCents: 0
            ),
            spectrumDb: Array(repeating: -40, count: 64),
            wideSpectrumDb: Array(repeating: -55, count: 128),
            wideSpectrumMaxHz: 20_000,
            waveformMin: Array(repeating: -0.25, count: 256),
            waveformMax: Array(repeating: 0.5, count: 256),
            samplePosition: 12_288,
            sampleRateHz: 48_000,
            partials: [], chord: nil,
            signalState: .tracking,
            inputLevelDbfs: -24,
            displayStrength: 1,
            isHeld: false
        )
    }
}
