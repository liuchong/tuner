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

/// 指法表中的一个音（id 为 core 的指法 id：音高相对筒音的半音数，表内唯一）。
struct ChartNoteUi: Identifiable {
    let id: Int32
    let label: String
    let holes: [HoleMark]
    let baseSemitones: Int32
    let register: WindRegister
    let fingeringKind: FingeringKind
    let anchorHole: UInt8?
    let noteName: String
    let midi: Int32
    let freqHz: Double
    let solfege: String
    let inScale: Bool
    let overblown: Bool
    var active = false
}

enum WindFingeringScope {
    case scale
    case chromatic
}

/// 管乐图示外形：决定管身画法、孔心位置和音区标题。
enum WindFigureKind {
    case xiao
    case dizi
    case shakuhachi

    init(instrumentId: String) {
        switch instrumentId {
        case "zhudi": self = .dizi
        case "shakuhachi": self = .shakuhachi
        default: self = .xiao
        }
    }
}

/// 吉他琴头样式（弦乐器图示用），名称沿用行业通行叫法。
enum HeadstockStyle: String, CaseIterable {
    /// Fender 式单侧 6 弦轴。
    case inline6
    /// Gibson 式两侧各 3 弦轴。
    case threePlusThree

    var displayName: String {
        switch self {
        case .inline6: "6-in-line"
        case .threePlusThree: "3+3"
        }
    }

    var accessibilityName: String {
        switch self {
        case .inline6: "Fender 式 6-in-line 单侧琴头"
        case .threePlusThree: "Gibson 式 3+3 两侧琴头"
        }
    }
}

/// 弦乐器图示：吉他琴头（两种样式）、尤克里里 2+2 琴头、古琴俯视线稿。
enum StringFigureKind: Equatable {
    case headstock(HeadstockStyle)
    case ukuleleHeadstock
    case guqin
    case none
}

struct XiaoFingeringRow: Identifiable {
    let baseSemitones: Int32
    let anchorHole: UInt8?
    let notes: [ChartNoteUi]

    var id: Int32 { baseSemitones }

    func note(in register: WindRegister) -> ChartNoteUi? {
        notes.first { $0.register == register }
    }
}

struct XiaoFingeringAnchorGroup: Identifiable {
    let anchorHole: UInt8?
    let rows: [XiaoFingeringRow]

    var id: String { anchorHole.map(String.init) ?? "outlet" }
}

enum XiaoFingeringLayout {
    static func rows(for notes: [ChartNoteUi]) -> [XiaoFingeringRow] {
        Dictionary(grouping: notes, by: \.baseSemitones)
            .map { baseSemitones, grouped in
                XiaoFingeringRow(
                    baseSemitones: baseSemitones,
                    anchorHole: grouped.first?.anchorHole,
                    notes: grouped.sorted { $0.midi < $1.midi }
                )
            }
            .sorted { $0.baseSemitones < $1.baseSemitones }
    }

    static func anchorGroups(for notes: [ChartNoteUi]) -> [XiaoFingeringAnchorGroup] {
        Dictionary(grouping: rows(for: notes), by: \.anchorHole)
            .map { anchorHole, rows in
                XiaoFingeringAnchorGroup(
                    anchorHole: anchorHole,
                    rows: rows.sorted { $0.baseSemitones < $1.baseSemitones }
                )
            }
            .sorted { ($0.anchorHole ?? 0) < ($1.anchorHole ?? 0) }
    }
}

/// 乐器面板 ViewModel（iOS 与 macOS 共用；业务换算全走 core）。
final class InstrumentViewModel: ObservableObject {
    static let headstockStyleKey = "guitarHeadstockStyle"

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
    @Published private(set) var headstockStyle: HeadstockStyle

    // 管乐型号与转调（竹笛、洞箫、尺八共用）。
    @Published private(set) var variantId = ""
    @Published private(set) var keyNames: [String] = []
    @Published private(set) var keyName = ""
    @Published private(set) var holeSystems: [String] = []
    @Published private(set) var holeSystem = ""
    @Published private(set) var holeCount = 0
    @Published private(set) var backHoleCount = 0
    @Published private(set) var supportsTongyin = false
    @Published private(set) var supportsChromatic = false
    @Published private(set) var tongyinOptions: [TongyinOption] = []
    @Published private(set) var tongyinDegree: UInt8 = 0
    @Published private(set) var keyDisplay = ""
    @Published private(set) var detailTongyinDegree: UInt8 = 0
    @Published private(set) var detailKeyDisplay = ""

    // 主表与详情始终同时存在，详情不会替换主表。
    @Published private(set) var scaleNotes: [ChartNoteUi] = []
    @Published private(set) var chromaticNotes: [ChartNoteUi] = []
    @Published private(set) var isChromaticDetailPresented = false
    @Published private(set) var scalePreviewNoteId: Int32?
    @Published private(set) var chromaticPreviewNoteId: Int32?

    // 共享调音状态
    @Published private(set) var centsToTarget: Float?
    @Published private(set) var targetNoteName: String?
    @Published private(set) var signalState: SignalState = .quiet
    @Published private(set) var displayStrength: Float = 0
    @Published private(set) var isHeld = false

    var windFigure: WindFigureKind { WindFigureKind(instrumentId: instrumentId) }

    var stringFigure: StringFigureKind {
        switch instrumentId {
        case "guitar": .headstock(headstockStyle)
        case "ukulele": .ukuleleHeadstock
        case "guqin": .guqin
        default: .none
        }
    }

    /// 手动模式下锁定的弦；自动模式没有锁定弦，图示只跟随实时命中。
    var selectedStringIndex: Int? { mode == .manual ? manualIndex : nil }

    /// 点选优先：点了哪条就显示哪条；未点选（或再点一次取消）时跟随实时识别，
    /// 无信号时回落最低筒音。
    var scaleDiagramNote: ChartNoteUi? {
        scalePreviewNoteId.flatMap { id in scaleNotes.first { $0.id == id } }
            ?? scaleNotes.first(where: \.active)
            ?? scaleNotes.first
    }

    var chromaticDiagramNote: ChartNoteUi? {
        chromaticPreviewNoteId.flatMap { id in chromaticNotes.first { $0.id == id } }
            ?? chromaticNotes.first(where: \.active)
            ?? chromaticNotes.first
    }

    private var windVariants: [WindVariant] = []
    private var lastDetectedFrequency: Double?
    private let events: AnyPublisher<AnalysisFrame, Never>
    private let defaults: UserDefaults
    private var acquired = false
    private var cancellables = Set<AnyCancellable>()

    init(events: AnyPublisher<AnalysisFrame, Never>? = nil, defaults: UserDefaults = .standard) {
        self.events = events ?? CaptureHub.shared.events.eraseToAnyPublisher()
        self.defaults = defaults
        headstockStyle = defaults.string(forKey: Self.headstockStyleKey)
            .flatMap(HeadstockStyle.init(rawValue:)) ?? .inline6
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
        guard !acquired else { return }
        CaptureHub.shared.acquire()
        acquired = true
    }

    func releaseCapture() {
        guard acquired else { return }
        CaptureHub.shared.release()
        acquired = false
    }

    func selectInstrument(_ id: String) {
        guard let inst = instruments.first(where: { $0.id == id }) else { return }
        instrumentId = id
        instrumentName = inst.displayName
        clearReading()
        resetWindState()

        switch inst.kind {
        case .string:
            kind = .string
            mode = .auto
            manualIndex = 0
            tunings = CorePresets.tunings(instrumentId: id)
            if let tuning = tunings.first { selectTuning(tuning.id) }
        case .wind:
            kind = .wind
            windVariants = CorePresets.windVariants(instrumentId: id)
            keyNames = windVariants.map(\.keyName).removingDuplicates()
            if let first = windVariants.first { applyWindVariant(first.id, degree: nil) }
        }
    }

    func selectTuning(_ id: String) {
        guard let tuning = tunings.first(where: { $0.id == id }) else { return }
        tuningId = id
        tuningName = tuning.displayName
        strings = tuning.strings.map { string in
            StringItemUi(
                index: Int(string.index),
                noteName: string.noteName,
                midi: string.midi,
                freqHz: string.freqHz,
                solfege: string.solfege
            )
        }
        manualIndex = min(manualIndex, max(strings.count - 1, 0))
        clearReading()
    }

    func selectMode(_ mode: SelectionMode) { self.mode = mode }

    /// 点按弦按钮、弦轴或琴弦：锁定该弦并切到手动模式。
    func selectString(_ index: Int) {
        guard strings.indices.contains(index) else { return }
        manualIndex = index
        mode = .manual
    }

    func selectHeadstockStyle(_ style: HeadstockStyle) {
        guard headstockStyle != style else { return }
        headstockStyle = style
        defaults.set(style.rawValue, forKey: Self.headstockStyleKey)
    }

    // MARK: - 管乐型号与转调

    /// 换调性时保留孔制、筒音级和当前调音高亮。
    func selectKey(_ name: String) {
        guard kind == .wind else { return }
        let candidates = windVariants.filter { $0.keyName == name }
        guard !candidates.isEmpty else { return }
        let target = candidates.first { $0.holeSystemName == holeSystem } ?? candidates[0]
        applyWindVariant(target.id, degree: tongyinDegree)
    }

    /// 孔制切换（洞箫 8 孔 / 6 孔）保留调性、筒音级和当前调音高亮。
    func selectHoleSystem(_ name: String) {
        guard kind == .wind,
              let target = windVariants.first(where: {
                  $0.keyName == keyName && $0.holeSystemName == name
              })
        else { return }
        applyWindVariant(target.id, degree: tongyinDegree)
    }

    private let naturalTongyinDegrees: [UInt8] = [0, 2, 4, 5, 7, 9, 11]

    /// 主表筒音只允许作自然音阶 1–7；尺八无筒音档，忽略。
    func selectTongyin(_ degree: UInt8) {
        guard supportsTongyin,
              tongyinDegree != degree,
              naturalTongyinDegrees.contains(degree)
        else { return }
        tongyinDegree = degree
        reloadWindCharts()
    }

    func stepTongyin(_ steps: Int) {
        guard let index = naturalTongyinDegrees.firstIndex(of: tongyinDegree) else { return }
        let next = (index + steps % naturalTongyinDegrees.count + naturalTongyinDegrees.count)
            % naturalTongyinDegrees.count
        selectTongyin(naturalTongyinDegrees[next])
    }

    /// 十二音详情保留完整 12 档，并与主表状态独立。
    func selectDetailTongyin(_ degree: UInt8) {
        guard supportsTongyin,
              detailTongyinDegree != degree,
              tongyinOptions.contains(where: { $0.degree == degree })
        else { return }
        detailTongyinDegree = degree
        reloadWindCharts()
    }

    func stepDetailTongyin(_ steps: Int) {
        let next = (Int(detailTongyinDegree) + steps % 12 + 12) % 12
        selectDetailTongyin(UInt8(next))
    }

    /// 主表和详情分别记住用户点选的孔位；再点同一条取消点选，回到实时识别。
    func selectFingeringPreview(_ id: Int32, scope: WindFingeringScope) {
        guard kind == .wind else { return }
        switch scope {
        case .scale:
            guard scaleNotes.contains(where: { $0.id == id }) else { return }
            scalePreviewNoteId = scalePreviewNoteId == id ? nil : id
        case .chromatic:
            guard chromaticNotes.contains(where: { $0.id == id }) else { return }
            chromaticPreviewNoteId = chromaticPreviewNoteId == id ? nil : id
        }
    }

    /// 详情打开时按十二音目标校音；主页面始终只按主表选择目标。
    func setChromaticDetailPresented(_ presented: Bool) {
        guard supportsChromatic else { return }
        isChromaticDetailPresented = presented
        if let frequency = lastDetectedFrequency {
            updateWindTarget(frequency: frequency)
        }
    }

    private func applyWindVariant(_ id: String, degree: UInt8?) {
        guard let variant = windVariants.first(where: { $0.id == id }) else { return }
        let hadVariant = !variantId.isEmpty
        variantId = variant.id
        keyName = variant.keyName
        holeSystem = variant.holeSystemName
        holeSystems = windVariants
            .filter { $0.keyId == variant.keyId }
            .map(\.holeSystemName)
            .filter { !$0.isEmpty }
            .removingDuplicates()
        supportsTongyin = variant.supportsTongyin
        supportsChromatic = variant.supportsChromatic
        tongyinOptions = variant.tongyinOptions
        tongyinDegree = degree.flatMap { requested in
            variant.supportsTongyin && naturalTongyinDegrees.contains(requested) ? requested : nil
        } ?? variant.defaultTongyinDegree
        if !hadVariant {
            detailTongyinDegree = tongyinDegree
        }
        reloadWindCharts()
    }

    private func reloadWindCharts() {
        guard let scale = CorePresets.windFingeringChart(
            variantId: variantId,
            tongyinDegree: tongyinDegree,
            scope: .scale
        ) else {
            scaleNotes = []
            chromaticNotes = []
            keyDisplay = ""
            return
        }
        let chromatic = supportsChromatic
            ? CorePresets.windFingeringChart(
                variantId: variantId,
                tongyinDegree: detailTongyinDegree,
                scope: .chromatic
            )
            : nil

        let scaleActive = Set(scaleNotes.filter(\.active).map(\.id))
        let chromaticActive = Set(chromaticNotes.filter(\.active).map(\.id))
        keyDisplay = scale.keyDisplay
        detailKeyDisplay = chromatic?.keyDisplay ?? ""
        holeCount = Int(scale.holeCount)
        backHoleCount = Int(scale.backHoleCount)
        scaleNotes = makeChartNotes(scale.notes, activeIds: scaleActive)
        chromaticNotes = makeChartNotes(chromatic?.notes ?? [], activeIds: chromaticActive)
        if let id = scalePreviewNoteId,
           !scaleNotes.contains(where: { $0.id == id }) {
            scalePreviewNoteId = nil
        }
        if let id = chromaticPreviewNoteId,
           !chromaticNotes.contains(where: { $0.id == id }) {
            chromaticPreviewNoteId = nil
        }

        if let frequency = lastDetectedFrequency {
            updateWindTarget(frequency: frequency)
        }
    }

    private func makeChartNotes(
        _ notes: [WindFingering],
        activeIds: Set<Int32>
    ) -> [ChartNoteUi] {
        notes.map { note in
            ChartNoteUi(
                id: note.fingeringId,
                label: note.label,
                holes: note.holes,
                baseSemitones: note.baseSemitones,
                register: note.register,
                fingeringKind: note.fingeringKind,
                anchorHole: note.anchorHole,
                noteName: note.noteName,
                midi: note.midi,
                freqHz: note.freqHz,
                solfege: note.solfege,
                inScale: note.inScale,
                overblown: note.overblown,
                active: activeIds.contains(note.fingeringId)
            )
        }
    }

    private func resetWindState() {
        windVariants = []
        variantId = ""
        keyNames = []
        keyName = ""
        holeSystems = []
        holeSystem = ""
        holeCount = 0
        backHoleCount = 0
        supportsTongyin = false
        supportsChromatic = false
        tongyinOptions = []
        tongyinDegree = 0
        keyDisplay = ""
        detailTongyinDegree = 0
        detailKeyDisplay = ""
        scaleNotes = []
        chromaticNotes = []
        isChromaticDetailPresented = false
        scalePreviewNoteId = nil
        chromaticPreviewNoteId = nil
    }

    private func onEvent(_ event: TunarEvent) {
        let frequency = event.freqHz
        switch kind {
        case .string:
            guard !strings.isEmpty else { return }
            lastDetectedFrequency = nil
            let cents = strings.map {
                CorePresets.centsBetween(freq: frequency, target: $0.freqHz) ?? .infinity
            }
            let nearest = cents.enumerated().min {
                abs($0.element) < abs($1.element)
            }!.offset
            let activeIndex: Int
            switch mode {
            case .auto: activeIndex = nearest
            case .manual: activeIndex = min(manualIndex, cents.count - 1)
            }
            strings = strings.enumerated().map { index, item in
                var item = item
                item.active = index == activeIndex
                item.inTune = abs(cents[index]) <= 5
                return item
            }
            centsToTarget = Float(cents[activeIndex])
            targetNoteName = strings[activeIndex].noteName
        case .wind:
            lastDetectedFrequency = frequency
            updateWindTarget(frequency: frequency)
        }
    }

    private func updateWindTarget(frequency: Double) {
        let targetNotes = isChromaticDetailPresented ? chromaticNotes : scaleNotes
        guard !targetNotes.isEmpty else { return }
        let cents = targetNotes.map {
            CorePresets.centsBetween(freq: frequency, target: $0.freqHz) ?? .infinity
        }
        let nearest = cents.enumerated().min {
            abs($0.element) < abs($1.element)
        }!.offset
        let activeId = targetNotes[nearest].id
        chromaticNotes = chromaticNotes.map {
            var note = $0
            note.active = note.id == activeId
            return note
        }
        scaleNotes = scaleNotes.map {
            var note = $0
            note.active = note.id == activeId
            return note
        }
        centsToTarget = Float(cents[nearest])
        targetNoteName = targetNotes[nearest].noteName
    }

    private func clearReading() {
        lastDetectedFrequency = nil
        centsToTarget = nil
        targetNoteName = nil
        strings = strings.map {
            var item = $0
            item.active = false
            item.inTune = false
            return item
        }
        scaleNotes = scaleNotes.map {
            var note = $0
            note.active = false
            return note
        }
        chromaticNotes = chromaticNotes.map {
            var note = $0
            note.active = false
            return note
        }
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

extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
