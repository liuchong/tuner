import Combine
import Foundation

/// 选弦模式：自动（识别最近弦）/ 手动（锁定选中弦）。
enum SelectionMode: String { case auto, manual }

struct StringItemUi: Identifiable {
    let index: Int
    let noteName: String
    let midi: Int32
    let freqHz: Double
    let solfege: String
    var active = false
    var inTune = false
    var id: Int { index }
}

struct ChartNoteUi: Identifiable {
    let label: String
    let noteName: String
    let midi: Int32
    let freqHz: Double
    let solfege: String
    var active = false
    var id: String { label }
}

/// 乐器面板 ViewModel（与 Android InstrumentViewModel 同构；业务换算全走 core）。
final class InstrumentViewModel: ObservableObject {
    @Published private(set) var instruments: [Instrument] = []
    @Published private(set) var instrumentId = ""
    @Published private(set) var instrumentName = ""
    @Published private(set) var kind: InstrumentKind = .string
    // 弦乐
    @Published private(set) var tunings: [Tuning] = []
    @Published private(set) var tuningId = ""
    @Published private(set) var tuningName = ""
    @Published private(set) var strings: [StringItemUi] = []
    @Published private(set) var mode: SelectionMode = .auto
    @Published private(set) var manualIndex = 0
    // 管乐
    @Published private(set) var chartGroups: [String] = []
    @Published private(set) var chartGroup = ""
    @Published private(set) var tongyinOptions: [String] = []
    @Published private(set) var tongyin = ""
    @Published private(set) var notes: [ChartNoteUi] = []
    // 共享
    @Published private(set) var centsToTarget: Float?
    @Published private(set) var targetNoteName: String?
    @Published private(set) var signalState: SignalState = .quiet
    @Published private(set) var displayStrength: Float = 0
    @Published private(set) var isHeld = false

    private let events: AnyPublisher<AnalysisFrame, Never>
    private var acquired = false
    private var cancellables = Set<AnyCancellable>()

    init(events: AnyPublisher<AnalysisFrame, Never>? = nil) {
        self.events = events ?? CaptureHub.shared.events.eraseToAnyPublisher()
        instruments = CorePresets.instruments()
        if let first = instruments.first { selectInstrument(first.id) }
        self.events
            .receive(on: DispatchQueue.main)
            .sink { [weak self] frame in
                guard let self else { return }
                self.signalState = frame.signalState
                self.displayStrength = frame.displayStrength
                self.isHeld = frame.isHeld
                if let ev = frame.tuner {
                    self.onEvent(ev)
                } else {
                    self.clearReading()
                }
            }
            .store(in: &cancellables)
    }

    func startCapture() {
        if acquired { return }
        CaptureHub.shared.acquire()
        acquired = true
    }

    func releaseCapture() {
        if acquired {
            CaptureHub.shared.release()
            acquired = false
        }
    }

    func selectInstrument(_ id: String) {
        guard let inst = instruments.first(where: { $0.id == id }) else { return }
        instrumentId = id
        instrumentName = inst.displayName
        switch inst.kind {
        case .string:
            kind = .string
            tunings = CorePresets.tunings(instrumentId: id)
            centsToTarget = nil
            targetNoteName = nil
            if let t = tunings.first { selectTuning(t.id) }
        case .wind:
            kind = .wind
            let charts = CorePresets.fingeringCharts(instrumentId: id)
            chartGroups = charts
                .map { $0.displayName.components(separatedBy: " · ").first ?? $0.displayName }
            let uniqueGroups = chartGroups.removingDuplicates()
            chartGroups = uniqueGroups
            chartGroup = uniqueGroups.first ?? ""
            tongyinOptions = charts
                .filter { $0.displayName.hasPrefix("\(chartGroup) · ") }
                .map { $0.displayName.components(separatedBy: "筒音作").last ?? "" }
                .removingDuplicates()
            centsToTarget = nil
            targetNoteName = nil
            selectChart(group: chartGroup, tongyin: nil)
        }
    }

    func selectTuning(_ id: String) {
        guard let tuning = tunings.first(where: { $0.id == id }) else { return }
        tuningId = id
        tuningName = tuning.displayName
        strings = tuning.strings.map { s in
            StringItemUi(
                index: Int(s.index), noteName: s.noteName, midi: s.midi,
                freqHz: s.freqHz, solfege: s.solfege
            )
        }
        centsToTarget = nil
        targetNoteName = nil
    }

    func selectMode(_ m: SelectionMode) { mode = m }

    func selectString(_ index: Int) {
        manualIndex = index
        mode = .manual
    }

    func selectChart(group: String, tongyin: String?) {
        let charts = CorePresets.fingeringCharts(instrumentId: instrumentId)
        let ty: String
        if tongyinOptions.isEmpty { ty = "" }
        else if let t = tongyin, tongyinOptions.contains(t) { ty = t }
        else { ty = tongyinOptions.first ?? "" }
        chartGroup = group
        self.tongyin = ty
        let chart = charts.first {
            tongyinOptions.isEmpty
                ? $0.displayName == group
                : $0.displayName == "\(group) · 筒音作\(ty)"
        }
        notes = (chart?.notes ?? []).map { n in
            ChartNoteUi(
                label: n.label, noteName: n.noteName, midi: n.midi,
                freqHz: n.freqHz, solfege: n.solfege
            )
        }
        centsToTarget = nil
        targetNoteName = nil
    }

    private func onEvent(_ ev: TunarEvent) {
        let freq = ev.freqHz
        switch kind {
        case .string:
            guard !strings.isEmpty else { return }
            let cents = strings.map { CorePresets.centsBetween(freq: freq, target: $0.freqHz) ?? .infinity }
            let nearest = cents.enumerated().min { abs($0.element) < abs($1.element) }!.offset
            let activeIdx: Int
            switch mode {
            case .auto: activeIdx = nearest
            case .manual: activeIdx = min(manualIndex, cents.count - 1)
            }
            strings = strings.enumerated().map { i, s in
                var s = s
                s.active = i == activeIdx
                s.inTune = abs(cents[i]) <= 5
                return s
            }
            centsToTarget = Float(cents[activeIdx])
            targetNoteName = strings[activeIdx].noteName
        case .wind:
            guard !notes.isEmpty else { return }
            let cents = notes.map { CorePresets.centsBetween(freq: freq, target: $0.freqHz) ?? .infinity }
            let nearest = cents.enumerated().min { abs($0.element) < abs($1.element) }!.offset
            notes = notes.enumerated().map { i, n in
                var n = n
                n.active = i == nearest
                return n
            }
            centsToTarget = Float(cents[nearest])
            targetNoteName = notes[nearest].noteName
        }
    }

    private func clearReading() {
        centsToTarget = nil
        targetNoteName = nil
        strings = strings.map { var s = $0; s.active = false; s.inTune = false; return s }
        notes = notes.map { var n = $0; n.active = false; return n }
    }
}

extension Array where Element: Equatable {
    /// 保序去重（不依赖 Hashable）。
    func removingDuplicates() -> [Element] {
        var result: [Element] = []
        for e in self where !result.contains(e) {
            result.append(e)
        }
        return result
    }
}
