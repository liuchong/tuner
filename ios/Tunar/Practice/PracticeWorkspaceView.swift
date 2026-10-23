import SwiftUI
import UniformTypeIdentifiers
import AVFoundation

struct PracticeMeasurementDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json, .commaSeparatedText] }
    var text: String
    init(text: String = "") { self.text = text }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents, let text = String(data: data, encoding: .utf8)
        else { throw CocoaError(.fileReadCorruptFile) }
        self.text = text
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: Data(text.utf8)) }
}

/// Apple 两端的完整测量界面；数据和数学均由同一 Rust 会话提供。
struct PracticeWorkspaceView: View {
    @ObservedObject var model: PracticeWorkspaceModel
    @ObservedObject private var hub = CaptureHub.shared
    @State private var snapshotName = ""
    @State private var showingImport = false
    @State private var showRealtimeAnalysis = false
    @State private var showingExport = false
    @State private var exportDocument = PracticeMeasurementDocument()
    @State private var exportType: UTType = .json
    @State private var showClear = false
    @State private var showTarget = false
    @State private var showInspector = false
    @State private var showWave = false
    @State private var range: Double = 50
    @State private var selectionStart: Double = 0
    @State private var selectionEnd: Double = 1

    var body: some View {
        GeometryReader { geometry in
            VStack(spacing: 12) {
                toolbar
                if hub.startFailed {
                    HStack { Text("麦克风启动失败"); Button("重试") { model.retryCapture() } }
                        .foregroundStyle(.red)
                }
                if AVCaptureDevice.authorizationStatus(for: .audio) == .denied || AVCaptureDevice.authorizationStatus(for: .audio) == .restricted {
                    HStack {
                        Text("需要麦克风权限才能实时测量，已有记录可继续回看")
                        Button("系统设置") {
                            #if os(macOS)
                            NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone")!)
                            #else
                            UIApplication.shared.open(URL(string: UIApplication.openSettingsURLString)!)
                            #endif
                        }
                        Button("重试") { model.requestCapture() }
                    }.font(.caption)
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        if PracticeWorkspaceLayout.showsInspector(contentWidth: geometry.size.width) {
                            HStack(alignment: .top, spacing: 20) {
                                mainPanels(height: geometry.size.height)
                                VStack(alignment: .leading, spacing: 20) { inspector; snapshotList }.frame(width: 280)
                            }
                        } else {
                            mainPanels(height: geometry.size.height)
                        }
                        statistics
                        if !PracticeWorkspaceLayout.showsInspector(contentWidth: geometry.size.width) { snapshotList }
                    }
                    .padding(.bottom, 4)
                }
                if model.presentation.source == .held, let summary=model.presentation.summary {
                    Text("停吹前摘要：中位 \(summary.medianCents,specifier:"%.1f")c · 波动 \(summary.lowCents,specifier:"%.1f")…\(summary.highCents,specifier:"%.1f")c · 有效\(summary.validSeconds,specifier:"%.2f")秒").font(.caption).foregroundStyle(.secondary)
                }
                if model.presentation.source == .held && model.presentation.summary == nil {Text("短音，未形成摘要").font(.caption).foregroundStyle(.secondary)}
                if let previous = model.presentation.previousMeasurement {
                    Text("前一条件结果 · \(previous.targetName) · \(previous.frame.measuredFreqHz ?? previous.frame.tuner?.freqHz ?? 0,specifier:"%.2f") Hz · \(previous.cents ?? 0,specifier:"%.1f")c").font(.caption).foregroundStyle(.secondary)
                }
                LiveInputStatusView(status:hub.inputStatus,compact:true)
                sessionControls
            }
            .padding(16)
        }
        .sheet(isPresented: $showRealtimeAnalysis) {
            #if os(macOS)
            DesktopAnalysisView().frame(minWidth: 760, minHeight: 600)
            #else
            NavigationStack { ProfessionalSpectrumView().toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { showRealtimeAnalysis = false } } } }
            #endif
        }
        .sheet(isPresented: $showInspector) { ScrollView { inspector.padding(24) }.frame(minWidth: 280, minHeight: 430) }
        .sheet(isPresented: $showTarget) { targetPicker }
        .fileImporter(isPresented: $showingImport, allowedContentTypes: [.json]) { result in
            switch result { case .success(let url): model.importFile(url); case .failure(let error): model.error = error.localizedDescription }
        }
        .fileExporter(isPresented: $showingExport, document: exportDocument, contentType: exportType,
                      defaultFilename: exportType == .json ? "吐呐测量.json" : "吐呐测量.csv") { result in
            if case .failure(let error) = result { model.error = "文件保存失败：\(error.localizedDescription)" }
        }
        .alert("测量提示", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
            Button("好") { model.error = nil }
        } message: { Text(model.error ?? "") }
        .confirmationDialog("清空本次测量？命名快照和已保存文件会保留。", isPresented: $showClear) {
            Button("清空测量", role: .destructive) { model.clear() }
        }
        .onAppear { restoreSelection() }
        .onChange(of: model.status.selectionStartId) { _, _ in restoreSelection() }
        .onChange(of: model.status.selectionEndId) { _, _ in restoreSelection() }
        .onChange(of: model.trace.count) { _, _ in restoreSelection() }
        .onChange(of: model.normalized) { _, _ in model.refreshComparison() }
        .onChange(of: model.wide) { _, _ in model.refreshComparison() }
    }

    private var toolbar: some View {
        ViewThatFits(in: .horizontal) {
            HStack { taskPicker; Spacer(); contextControls }
            VStack(alignment: .leading) { taskPicker; contextControls }
        }
    }
    private var taskPicker: some View {
        Picker("任务", selection: $model.task) {
            ForEach(PracticeTask.allCases, id: \.self) { Text($0.rawValue).tag($0) }
        }.pickerStyle(.segmented).frame(maxWidth: 330).accessibilityIdentifier("practice.task")
    }
    private var contextControls: some View {
        HStack {
            Button(model.config.targetStep == nil && model.config.targetMidi == nil && model.config.targetHz == nil ? "自动识别 · 选目标" : "目标已锁定") { showTarget = true }
                .accessibilityIdentifier("practice.target")
            Button { showInspector = true } label: { Label("详情", systemImage: "slider.horizontal.3") }
            Menu {
                Button("实时专业分析工具") { showRealtimeAnalysis = true }
                Button("打开测量 JSON") { showingImport = true }
                Button("保存完整测量 JSON") { export(csv: false) }
                Button("导出全部 CSV") { export(csv: true) }
                Button("导出选区 CSV") { export(csv: true, selection: true) }.disabled(model.trace.isEmpty)
                Divider()
                Button("清空测量", role: .destructive) { showClear = true }
            } label: { Label("文件", systemImage: "doc") }
        }.font(.callout)
    }

    @ViewBuilder private func mainPanels(height: Double) -> some View {
        VStack(spacing: 14) {
            pitchPanel(height: height)
            if model.task == .analyze {
                analysisPanel
            } else if model.task == .practice {
                tracePanel
            }
        }.frame(maxWidth: .infinity)
    }

    private func pitchPanel(height: Double) -> some View {
        VStack(spacing: 12) {
            HStack {
                Text(model.presentation.source == .held ? "上次测量 · \(model.presentation.heldAgeSeconds.map{String(format:"%.1f秒前",$0)} ?? "上次输入")" : model.status.reviewId == nil ? "实时" : "回看 \(time(model.point?.timeSeconds ?? 0))")
                if model.point?.frame.isHeld == true { Text("保持 · 不计持续发声").foregroundStyle(.secondary) }
                Spacer()
                Text("A4 \(model.displayConfig.a4Hz, specifier: "%.1f") · \(model.displayConfig.temperament)-TET")
            }.font(.caption)
            if model.task != .analyze {
                PracticeNeedle(cents: model.presentation.displayCents, range: range, tolerance: model.displayConfig.toleranceCents).frame(height: height < 760 ? 42 : 64)
            }
            HStack(alignment: .firstTextBaseline, spacing: 20) {
                Text(model.point?.targetName.isEmpty == false ? model.point!.targetName : "—")
                    .font(.system(size: model.task == .analyze ? 40 : PracticeWorkspaceLayout.noteSize(availableHeight: height), weight: .semibold, design: .rounded))
                    .lineLimit(1).minimumScaleFactor(0.6)
                VStack(alignment: .leading) {
                    Text(model.point?.cents.map { String(format: "%+.1f cents", $0) } ?? "请发声")
                        .font(model.task == .analyze ? .title3 : .title).monospacedDigit()
                    Text(model.point?.frame.tuner.map { String(format: "%.1f Hz", $0.freqHz) } ?? "—")
                        .font(.callout).foregroundStyle(.secondary).monospacedDigit()
                }
            }.accessibilityIdentifier("practice.reading")
            HStack {
                Text(model.point?.targetHz.map { String(format: "目标 %.1f Hz", $0) } ?? "自动目标")
                Spacer()
                Text(model.point.map { String(format: "输入 %.1f dBFS", $0.frame.inputLevelDbfs) } ?? "输入 — dBFS")
            }.font(.caption).monospacedDigit()
            if model.point?.referenceActive == true { Text("参考音影响区间 · 已排出演奏统计").font(.caption).foregroundStyle(.orange) }
        }
        .padding(16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }

    private var displayedTrace: [PracticeTracePoint] { model.status.reviewId == nil && model.status.count == 0 ? model.liveTrace : model.trace }
    private var tracePanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("目标音分轨迹").font(.headline)
                Spacer()
                Picker("量程", selection: $range) { Text("±50c").tag(50.0); Text("±100c").tag(100.0) }
                    .pickerStyle(.menu).fixedSize()
            }
            PracticeTracePlot(points: displayedTrace, tolerance: model.displayConfig.toleranceCents, range: range,
                              cursor: model.point?.timeSeconds).frame(height: 170)
            Text("横轴：音频时间（秒） · 纵轴：音分 · 无效信号与中断断线").font(.caption).foregroundStyle(.secondary)
            PracticeTracePlot(points: displayedTrace, tolerance: 0, range: 80, cursor: model.point?.timeSeconds, level: true)
                .frame(height: 90)
            Text("输入电平 dBFS · 未校准声压").font(.caption).foregroundStyle(.secondary)
        }
    }

    private var analysisPanel: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Picker("主图", selection: $showWave) { Text("频谱").tag(false); Text("波形包络").tag(true) }.pickerStyle(.segmented)
                Toggle("全频", isOn: $model.wide).disabled(showWave)
            }
            if let point = model.point {
                if showWave {
                    Text("当前分析窗口 · 256 列最小/最大包络").font(.caption)
                    PracticeEnvelope(minimum: point.frame.waveformMin, maximum: point.frame.waveformMax).frame(height: 150)
                    if let baseline = model.snapshots.first(where: { $0.id == model.status.baselineId }) {
                        Text("基准窗口 · 未做相位对齐，并列观察").font(.caption)
                        PracticeEnvelope(minimum: baseline.point.frame.waveformMin, maximum: baseline.point.frame.waveformMax).frame(height: 100)
                    }
                } else {
                    Toggle("相对峰值归一化（去除总体电平差异）", isOn: Binding(get: { model.normalized && model.status.baselineId != nil }, set: { model.normalized = $0 })).font(.caption).disabled(model.status.baselineId == nil)
                    PracticeSpectrumPlot(current: model.comparison?.current ?? (model.wide ? point.frame.wideSpectrumDb : point.frame.spectrumDb),
                        reference: model.comparison?.reference, minHz: model.wide ? 20 : 60,
                        maxHz: model.wide ? point.frame.wideSpectrumMaxHz : 2400).frame(height: 250)
                    Text(model.normalized && model.comparison != nil ? "实线：当前 · 虚线：基准 · 相对峰值 dB" : "实线：当前 · 虚线：基准 · dBFS").font(.caption).foregroundStyle(.secondary)
                }
                if let error = model.comparisonError { Text(error).font(.caption).foregroundStyle(.orange) }
                if !point.frame.partials.isEmpty {
                    Text(point.frame.partials.map { String(format: "H%d %.1f Hz · %.1f dBFS", $0.harmonicIndex, $0.freqHz, $0.magnitudeDb) }.joined(separator: "   "))
                        .font(.caption).textSelection(.enabled)
                }
            } else { Text("请发声，或打开已有测量").foregroundStyle(.secondary).frame(height: 180) }
        }
    }

    private var inspector: some View {
        VStack(alignment: .leading, spacing: 18) {
            InputControlsView(onCalibrationStart:{model.pause()})
            Text("目标与条件").font(.title2)
            Button("选择参考音目标") { showInspector = false; showTarget = true }
            Button("取消锁定，自动识别") { model.unlock() }
            Picker("准音容差", selection: Binding(get: { model.config.toleranceCents }, set: model.tolerance)) {
                ForEach([2.0, 5.0, 10.0, 20.0], id: \.self) { Text("±\(Int($0)) cents").tag($0) }
            }
            if let variant = CorePresets.windVariants(instrumentId: model.displayConfig.instrumentId).first(where: { $0.id == model.displayConfig.variantId }),
               let chart = CorePresets.windFingeringChart(variantId: variant.id, tongyinDegree: variant.defaultTongyinDegree,
                    scope: variant.supportsChromatic ? .chromatic : .scale),
               let note = chart.notes.first(where: { $0.fingeringId == model.displayConfig.fingeringId }) {
                HStack {
                    WindTubeFigure(kind: WindFigureKind(instrumentId: model.displayConfig.instrumentId), holes: note.holes,
                        backHoleCount: Int(variant.backHoleCount), highlighted: true, ink: figureInk)
                        .frame(width: 90, height: 250)
                    VStack(alignment: .leading) { Text(note.noteName).font(.title); Text(note.label).font(.callout) }
                }
                Text("目标主指法示范 · 不代表实测按法").font(.caption).foregroundStyle(.secondary)
            } else {
                Text(model.displayConfig.instrumentId.isEmpty ? "从乐器页预览指法，再设为练习目标" : "目标乐器：\(model.displayConfig.instrumentId)")
                    .font(.callout).foregroundStyle(.secondary)
            }
            Button(model.referencePlaying ? "停止参考音" : "试听目标音") { model.toggleReference() }
                .disabled(model.targetFrequency == nil || model.status.reviewId != nil)
            Text("参考音期间不计练音成绩；窗口失活时停止播放。").font(.caption).foregroundStyle(.secondary)
            Text("测量不保存原始音频，JSON 可重开，CSV 用于表格分析。").font(.caption)
            if showInspector { Button("关闭详情") { showInspector = false } }
        }
    }
    private var figureInk: FigureInk {
        FigureInk(line: .primary.opacity(0.5), lineFaint: .primary.opacity(0.2), ink: .primary,
                  inkFaint: .secondary, surface: .clear, accent: .accentColor, tuneIn: .green, back: .orange)
    }

    private var targetPicker: some View {
        NavigationStack {
            List {
                Button("自动识别") { model.unlock(); showTarget = false }
                let tones = CorePresets.referenceTones(config: TunarConfig(sampleRate: 48000, frameHopSamples: 1024,
                    a4Hz: model.config.a4Hz, noiseGateDbfs: -45, solfege: .numbered,
                    key: KeyMode(tonicPc: 0, mode: .major), temperament: model.config.temperament))
                ForEach(tones, id: \.stepFromA4) { tone in
                    Button { model.lockReference(step: tone.stepFromA4); showTarget = false } label: {
                        HStack { Text(tone.noteName); Spacer(); Text(String(format: "%.1f Hz · 步 %d", tone.frequencyHz, tone.stepFromA4)).foregroundStyle(.secondary) }
                    }
                }
            }.navigationTitle("锁定练习目标")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("关闭") { showTarget = false } } }
        }.frame(minWidth: 300, minHeight: 400)
    }

    private var sessionControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            ViewThatFits(in: .horizontal) {
                HStack { recordingButtons; Spacer(); Text(model.status.reason).font(.caption).foregroundStyle(.secondary) }
                VStack(alignment: .leading) { recordingButtons; Text(model.status.reason).font(.caption).foregroundStyle(.secondary) }
            }
            if !model.trace.isEmpty {
                HStack {
                    Button { model.stepReview(direction: -1) } label: { Image(systemName: "backward.frame") }
                        .accessibilityLabel("上一测量点")
                    Slider(value: Binding(get: {
                        Double(model.status.reviewId.flatMap { id in model.trace.firstIndex { $0.id == id } } ?? model.trace.count - 1)
                    }, set: { value in
                        let index = min(model.trace.count - 1, max(0, Int(value)))
                        model.review(id: model.trace[index].id)
                    }), in: 0...Double(max(1, model.trace.count - 1)), step: 1).accessibilityLabel("测量时间轴")
                    Button { model.stepReview(direction: 1) } label: { Image(systemName: "forward.frame") }
                        .accessibilityLabel("下一测量点")
                    Text(time(model.point?.timeSeconds ?? 0)).monospacedDigit().font(.caption)
                }
            }
        }.padding(.top, 10).background(.regularMaterial)
    }
    private var recordingButtons: some View {
        HStack {
            Button(model.status.state == .recording ? "暂停记录" : model.status.state == .complete ? "记录已完成" : "记录测量") {
                if model.status.state == .recording { model.pause() } else { model.requestCapture(); model.start() }
            }.buttonStyle(.borderedProminent).disabled(model.status.state == .complete || (!hub.running && model.status.state != .recording)).accessibilityIdentifier("practice.record")
            Button("完成") { model.finish() }.disabled(model.status.count == 0)
            Button("回到实时") { model.returnToLive() }.disabled(model.status.reviewId == nil)
                .accessibilityIdentifier("practice.live")
            Text("\(model.status.count) 点").font(.caption).monospacedDigit()
        }
    }
    private var statistics: some View {
        VStack(alignment: .leading, spacing: 8) {
            if model.trace.count > 1 {
                DisclosureGroup("选区统计与导出") {
                    VStack(alignment: .leading) {
                        Text("从 \(time(model.trace[selectedStartIndex].timeSeconds)) 到 \(time(model.trace[selectedEndIndex].timeSeconds))").font(.caption)
                        Slider(value: $selectionStart, in: 0...1).accessibilityLabel("选区开始")
                        Slider(value: $selectionEnd, in: 0...1).accessibilityLabel("选区结束")
                        HStack {
                            Button("分析选区") { model.summarize(startId: model.trace[selectedStartIndex].id, endId: model.trace[selectedEndIndex].id) }
                            Button("导出选区 CSV") { export(csv: true, selection: true) }
                        }
                        ForEach(Array(model.summaries.enumerated()), id: \.offset) { _, summary in
                            if summary.insufficient { Text("段 \(summary.segmentId)：数据不足（至少1秒、5个可信点）") }
                            else {
                                Text(String(format: "段 %d · 有效 %.2fs · 覆盖 %.0f%% · 准音 %.0f%%", summary.segmentId, summary.validSeconds, summary.coverage * 100, (summary.accuracy ?? 0) * 100))
                                Text(String(format: "中位 %+.1fc · P10–P90 %+.1f … %+.1fc", summary.medianCents ?? 0, summary.lowCents ?? 0, summary.highCents ?? 0))
                            }
                        }.font(.caption).monospacedDigit()
                    }.padding(.top, 8)
                }
            }
        }
    }
    private func restoreSelection() {
        guard model.trace.count > 1, let start = model.status.selectionStartId, let end = model.status.selectionEndId,
              let a = model.trace.firstIndex(where: { $0.id == start }), let b = model.trace.firstIndex(where: { $0.id == end }) else { return }
        selectionStart = Double(a) / Double(model.trace.count - 1)
        selectionEnd = Double(b) / Double(model.trace.count - 1)
    }
    private var selectedStartIndex: Int { Int(min(selectionStart, selectionEnd) * Double(max(0, model.trace.count - 1))) }
    private var selectedEndIndex: Int { Int(max(selectionStart, selectionEnd) * Double(max(0, model.trace.count - 1))) }
    private var snapshotList: some View {
        DisclosureGroup("比较快照（\(model.snapshots.count)/8）") {
            VStack(alignment: .leading) {
                HStack {
                    TextField("快照名称", text: $snapshotName).textFieldStyle(.roundedBorder)
                    Button("保存当前帧") { model.saveSnapshot(name: snapshotName); snapshotName = "" }
                        .disabled(snapshotName.trimmingCharacters(in: .whitespaces).isEmpty || model.snapshots.count >= 8)
                }
                ForEach(model.snapshots, id: \.id) { snapshot in
                    HStack {
                        Text(snapshot.name)
                        Spacer()
                        Button(model.status.baselineId == snapshot.id ? "隐藏基准" : "设为基准") {
                            model.baseline(model.status.baselineId == snapshot.id ? nil : snapshot.id); model.task = .analyze
                        }
                        Button("删除", role: .destructive) { model.removeSnapshot(snapshot.id) }
                    }
                }
            }.padding(.top, 8)
        }
    }
    private func export(csv: Bool, selection: Bool = false) {
        let start = selection && !model.trace.isEmpty ? model.trace[selectedStartIndex].id : nil
        let end = selection && !model.trace.isEmpty ? model.trace[selectedEndIndex].id : nil
        model.export(csv: csv, startId: start, endId: end) { text in
            guard let text else { return }; exportDocument = PracticeMeasurementDocument(text: text)
            exportType = csv ? .commaSeparatedText : .json; showingExport = true
        }
    }
    private func time(_ seconds: Double) -> String { String(format: "%02d:%04.1f", Int(seconds) / 60, seconds.truncatingRemainder(dividingBy: 60)) }
}

private struct PracticeNeedle: View {
    let cents: Double?
    let range: Double
    let tolerance: Double
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Capsule().fill(Color.secondary.opacity(0.1)).frame(height: 10)
                Rectangle().fill(Color.green.opacity(0.2)).frame(width: geometry.size.width * min(range, tolerance) / range, height: 10)
                Rectangle().fill(Color.accentColor).frame(width: 3, height: 32)
                    .position(x: geometry.size.width * (min(range, max(-range, cents ?? 0)) / range + 1) / 2, y: geometry.size.height / 2)
                HStack { Text("−\(Int(range))"); Spacer(); Text("0"); Spacer(); Text("+\(Int(range))") }
                    .font(.caption2).foregroundStyle(.secondary).offset(y: 22)
            }
        }.accessibilityLabel(cents.map { String(format: "音准 %+.1f cents", $0) } ?? "等待声音")
        .overlay(alignment: .trailing) { if let cents, cents > range { Text("→").foregroundStyle(.orange) } }
        .overlay(alignment: .leading) { if let cents, cents < -range { Text("←").foregroundStyle(.orange) } }
        .opacity(cents == nil ? 0.35 : 1)
    }
}

struct PracticeTracePlot: View {
    let points: [PracticeTracePoint]
    let tolerance: Double
    let range: Double
    let cursor: Double?
    var level = false
    var body: some View {
        Canvas { context, size in
            let left: CGFloat = 40, right = max(41, size.width - 12), top: CGFloat = 12, bottom = max(13, size.height - 24)
            let start = points.first?.timeSeconds ?? 0, end = max(start + 0.1, points.last?.timeSeconds ?? 12)
            let x: (Double) -> CGFloat = { left + ($0 - start) / (end - start) * (right - left) }
            let y: (Double) -> CGFloat = { value in
                let fraction = level ? (min(0, max(-80, value)) + 80) / 80 : (min(range, max(-range, value)) + range) / (range * 2)
                return bottom - fraction * (bottom - top)
            }
            if !level {
                context.fill(Path(CGRect(x: left, y: y(tolerance), width: right - left, height: y(-tolerance) - y(tolerance))), with: .color(.green.opacity(0.12)))
            }
            let ticks = level ? [-80.0, -40, 0] : [-range, 0, range]
            for value in ticks {
                var line = Path(); line.move(to: CGPoint(x: left, y: y(value))); line.addLine(to: CGPoint(x: right, y: y(value)))
                context.stroke(line, with: .color(.secondary.opacity(0.2)), lineWidth: 1)
                context.draw(Text(String(format: "%.0f", value)).font(.caption2).foregroundColor(.secondary), at: CGPoint(x: 18, y: y(value)))
            }
            for i in 0...3 {
                let t = start + (end - start) * Double(i) / 3
                context.draw(Text(String(format: "%.1fs", t)).font(.caption2).foregroundColor(.secondary), at: CGPoint(x: x(t), y: size.height - 8))
            }
            var path = Path(); var previous: PracticeTracePoint?
            for p in points {
                let value = level ? Double(p.inputDbfs) : p.cents
                guard let value, level || p.valid else { previous = nil; continue }
                let at = CGPoint(x: x(p.timeSeconds), y: y(value))
                if let previous, previous.segmentId == p.segmentId, p.timeSeconds - previous.timeSeconds <= 0.250001 { path.addLine(to: at) }
                else { path.move(to: at) }
                previous = p
            }
            context.stroke(path, with: .color(.accentColor), lineWidth: 1.8)
            if let cursor, cursor >= start, cursor <= end {
                var mark = Path(); mark.move(to: CGPoint(x: x(cursor), y: top)); mark.addLine(to: CGPoint(x: x(cursor), y: bottom))
                context.stroke(mark, with: .color(.secondary), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
            }
        }.accessibilityLabel(level ? "真实输入电平轨迹，单位dBFS" : "相对目标的真实音分轨迹，静音和中断处断开")
    }
}

struct PracticeSpectrumPlot: View {
    let current: [Float]
    let reference: [Float]?
    let minHz: Double
    let maxHz: Double
    var body: some View {
        Canvas { context, size in
            let left: CGFloat = 36, right = max(37, size.width - 18), top: CGFloat = 14, bottom = max(15, size.height - 24)
            let y: (Float) -> CGFloat = { bottom - CGFloat((min(0, max(-80, $0)) + 80) / 80) * (bottom - top) }
            for db in [-80, -40, 0] {
                var line = Path(); line.move(to: CGPoint(x: left, y: y(Float(db)))); line.addLine(to: CGPoint(x: right, y: y(Float(db))))
                context.stroke(line, with: .color(.secondary.opacity(0.15)), lineWidth: 1)
                context.draw(Text("\(db)").font(.caption2).foregroundColor(.secondary), at: CGPoint(x: 16, y: y(Float(db))))
            }
            let values: [[Float]] = reference.map { [current, $0] } ?? [current]
            for (series, bins) in values.enumerated() where bins.count > 1 {
                var path = Path()
                for (index, db) in bins.enumerated() {
                    let p = CGPoint(x: left + CGFloat(index) / CGFloat(bins.count - 1) * (right - left), y: y(db))
                    if index == 0 { path.move(to: p) } else { path.addLine(to: p) }
                }
                context.stroke(path, with: .color(series == 0 ? .accentColor : .secondary), style: StrokeStyle(lineWidth: 1.8, dash: series == 0 ? [] : [5, 4]))
            }
            for i in 0...3 {
                let value = minHz * pow(maxHz / minHz, Double(i) / 3)
                context.draw(Text(value >= 1000 ? String(format: "%.1fkHz", value / 1000) : String(format: "%.0fHz", value)).font(.caption2).foregroundColor(.secondary),
                             at: CGPoint(x: left + CGFloat(i) / 3 * (right - left), y: size.height - 8))
            }
        }.accessibilityLabel("真实对数频谱，横轴Hz，纵轴dBFS；实线当前，虚线基准")
    }
}

private struct PracticeEnvelope: View {
    let minimum: [Float]
    let maximum: [Float]
    var body: some View {
        Canvas { context, size in
            let count = min(minimum.count, maximum.count)
            guard count > 1 else { return }
            var path = Path()
            for index in 0..<count {
                let x = CGFloat(index) / CGFloat(count - 1) * size.width
                path.move(to: CGPoint(x: x, y: size.height * CGFloat(1 - maximum[index]) / 2))
                path.addLine(to: CGPoint(x: x, y: size.height * CGFloat(1 - minimum[index]) / 2))
            }
            context.stroke(path, with: .color(.accentColor), lineWidth: 1)
        }.accessibilityLabel("分析窗口最小最大包络，幅度负一到正一，不是音频录音")
    }
}
