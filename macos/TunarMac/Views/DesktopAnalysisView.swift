import SwiftUI

private enum DesktopAnalysisMode: String, CaseIterable {
    case spectrum = "频谱"
    case pitch = "音高轨迹"
    case waveform = "波形"
}

private enum DesktopSpectrumRange: String, CaseIterable {
    case musical = "乐音"
    case full = "全频"
}

struct DesktopAnalysisView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = TunerViewModel()
    @StateObject private var history = SpectrumHistoryBuffer()
    @StateObject private var access = MacMicrophoneAccess()
    @State private var mode = DesktopAnalysisMode.spectrum
    @State private var range = DesktopSpectrumRange.musical

    private var reading: TunerReading? {
        guard case .active(let value) = vm.signal else { return nil }
        return value
    }

    private var metrics: ProfessionalSpectrumMetrics {
        professionalSpectrumMetrics(
            reading: reading,
            inputLevelDbfs: vm.inputLevelDbfs,
            partials: vm.partials,
            chord: vm.chord
        )
    }

    var body: some View {
        MacPageBackground {
            GeometryReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        header
                        MicrophoneStatusBanner(access: access, onRetry: retryCapture)
                        if DesktopLayout.columns(for: proxy.size.width) == 2 {
                            HStack(alignment: .top, spacing: 16) {
                                VStack(spacing: 16) {
                                    analysisCard
                                    metricsGrid
                                }
                                waterfallCard
                            }
                        } else {
                            analysisCard
                            metricsGrid
                            waterfallCard
                        }
                        peaksCard
                    }
                    .padding(24)
                }
            }
        }
        .onAppear {
            applyCaptureLifecycle(scenePhase)
        }
        .onDisappear { vm.releaseCapture() }
        .onChange(of: scenePhase) { _, phase in
            applyCaptureLifecycle(phase)
        }
        .onChange(of: vm.samplePosition) { _, _ in
            history.acceptAnalysis(
                spectrumDb: vm.spectrumDb,
                wideSpectrumDb: vm.wideSpectrumDb,
                waveformMin: vm.waveformMin,
                waveformMax: vm.waveformMax,
                samplePosition: vm.samplePosition,
                sampleRateHz: vm.sampleRateHz,
                trackingMidi: vm.signalState == .tracking && !vm.isHeld
                    ? reading.map { Float($0.midi) }
                    : nil
            )
        }
    }

    private func applyCaptureLifecycle(_ phase: ScenePhase) {
        switch DesktopCaptureLifecycle.action(isWindowActive: phase == .active) {
        case .acquire:
            access.request {
                vm.startCapture()
            }
        case .release:
            vm.releaseCapture()
        }
    }

    private func retryCapture() {
        guard scenePhase == .active else { return }
        vm.releaseCapture()
        access.request {
            vm.startCapture()
        }
    }

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("专业声音分析")
                    .font(.largeTitle.bold())
                Text("实时频谱、音高轨迹、波形与连续时间图谱来自同一分析帧")
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button {
                history.resetPeakHold()
            } label: {
                Label("重置峰值", systemImage: "arrow.counterclockwise")
            }
            Button {
                history.isPaused.toggle()
            } label: {
                Label(
                    history.isPaused ? "继续" : "暂停",
                    systemImage: history.isPaused ? "play.fill" : "pause.fill"
                )
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private var analysisCard: some View {
        MacCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Picker("视图", selection: $mode) {
                        ForEach(DesktopAnalysisMode.allCases, id: \.self) {
                            Text($0.rawValue).tag($0)
                        }
                    }
                    .pickerStyle(.segmented)
                    if mode == .spectrum {
                        Picker("范围", selection: $range) {
                            ForEach(DesktopSpectrumRange.allCases, id: \.self) {
                                Text($0.rawValue).tag($0)
                            }
                        }
                        .pickerStyle(.segmented)
                        .frame(width: 160)
                    }
                }
                HStack {
                    Label(
                        history.isPaused ? "已冻结" : "实时刷新",
                        systemImage: history.isPaused ? "pause.circle" : "waveform"
                    )
                    .foregroundStyle(history.isPaused ? .secondary : MacTheme.accent)
                    Spacer()
                    Text(String(format: "输入 %.1f dBFS", vm.inputLevelDbfs))
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
                .font(.caption)
                analysisChart
                    .frame(minHeight: 330)
            }
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private var analysisChart: some View {
        switch mode {
        case .spectrum:
            let full = range == .full
            DesktopSpectrumChart(
                live: full ? history.currentWideSpectrum : history.currentSpectrum,
                peak: full ? history.peakWideSpectrum : history.peakSpectrum,
                partials: vm.partials,
                minHz: full ? professionalWideSpectrumMinHz : professionalSpectrumMinHz,
                maxHz: full ? vm.wideSpectrumMaxHz : professionalSpectrumMaxHz,
                ticks: full
                    ? professionalWideFrequencyTicks(maxHz: vm.wideSpectrumMaxHz)
                    : professionalFrequencyTicks()
            )
        case .pitch:
            DesktopPitchTraceChart(trace: history.pitchTrace)
        case .waveform:
            DesktopWaveformChart(
                minimum: history.waveformMin,
                maximum: history.waveformMax
            )
        }
    }

    private var metricsGrid: some View {
        LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 3),
            spacing: 10
        ) {
            MetricPill(title: "音名", value: metrics.note)
            MetricPill(title: "基频", value: metrics.fundamental)
            MetricPill(title: "音分", value: metrics.cents)
            MetricPill(title: "输入", value: metrics.inputLevel)
            MetricPill(title: "最高峰", value: metrics.strongestPeak)
            MetricPill(title: "和弦", value: metrics.chord)
        }
    }

    private var waterfallCard: some View {
        MacCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("连续时间图谱")
                    .font(.title3.bold())
                Text("最新声音在顶部 · 约 12 秒历史 · 颜色表示 dBFS 强度")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                DesktopWaterfallChart(
                    rows: history.rowsNewestFirst(),
                    columnCount: history.waterfallBinCount
                )
                .frame(minHeight: 430)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var peaksCard: some View {
        MacCard {
            VStack(alignment: .leading, spacing: 8) {
                Text("实际峰值")
                    .font(.title3.bold())
                if vm.partials.isEmpty {
                    Text("等待可识别的频谱峰")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(vm.partials.enumerated()), id: \.offset) { _, partial in
                        HStack {
                            Text(
                                partial.harmonicIndex > 0
                                    ? "H\(partial.harmonicIndex)"
                                    : partial.noteName.replacingOccurrences(of: "#", with: "♯")
                            )
                            .frame(width: 64, alignment: .leading)
                            Text(String(format: "%.1f Hz", partial.freqHz))
                                .monospacedDigit()
                            Spacer()
                            Text(String(format: "%.1f dB", partial.magnitudeDb))
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        }
                        Divider()
                    }
                }
            }
        }
    }
}

private struct DesktopSpectrumChart: View {
    let live: [Float]
    let peak: [Float]
    let partials: [Partial]
    let minHz: Double
    let maxHz: Double
    let ticks: [SpectrumAxisTick]

    var body: some View {
        GeometryReader { proxy in
            let plot = CGRect(
                x: 54,
                y: 8,
                width: max(1, proxy.size.width - 66),
                height: max(1, proxy.size.height - 34)
            )
            Canvas { context, _ in
                for tick in professionalDbTicks() {
                    let y = plot.minY + plot.height * tick.fraction
                    var line = Path()
                    line.move(to: CGPoint(x: plot.minX, y: y))
                    line.addLine(to: CGPoint(x: plot.maxX, y: y))
                    context.stroke(line, with: .color(.secondary.opacity(0.16)), lineWidth: 1)
                    context.draw(
                        Text(tick.label).font(.caption2).foregroundStyle(.secondary),
                        at: CGPoint(x: plot.minX - 7, y: y),
                        anchor: .trailing
                    )
                }
                for tick in ticks {
                    let x = plot.minX + plot.width * tick.fraction
                    var line = Path()
                    line.move(to: CGPoint(x: x, y: plot.minY))
                    line.addLine(to: CGPoint(x: x, y: plot.maxY))
                    context.stroke(line, with: .color(.secondary.opacity(0.12)), lineWidth: 1)
                    context.draw(
                        Text(tick.label).font(.caption2).foregroundStyle(.secondary),
                        at: CGPoint(x: x, y: plot.maxY + 7),
                        anchor: .top
                    )
                }
                drawSpectrum(
                    values: peak,
                    in: plot,
                    color: MacTheme.tuneOff.opacity(0.65),
                    context: &context
                )
                drawSpectrum(
                    values: live,
                    in: plot,
                    color: MacTheme.accent,
                    context: &context
                )
                for partial in partials where partial.freqHz >= minHz && partial.freqHz <= maxHz {
                    let x = plot.minX + plot.width * frequencyFraction(
                        partial.freqHz,
                        minHz: minHz,
                        maxHz: maxHz
                    )
                    let y = plot.minY + plot.height
                        * Double((-partial.magnitudeDb / 80).clamped(to: 0...1))
                    context.fill(
                        Path(ellipseIn: CGRect(x: x - 3, y: y - 3, width: 6, height: 6)),
                        with: .color(.primary)
                    )
                }
            }
        }
    }

    private func drawSpectrum(
        values: [Float],
        in plot: CGRect,
        color: Color,
        context: inout GraphicsContext
    ) {
        guard values.count > 1 else { return }
        var path = Path()
        for (index, value) in values.enumerated() {
            let x = plot.minX + plot.width * Double(index) / Double(values.count - 1)
            let y = plot.minY + plot.height
                * Double((-value / 80).clamped(to: 0...1))
            if index == 0 { path.move(to: CGPoint(x: x, y: y)) }
            else { path.addLine(to: CGPoint(x: x, y: y)) }
        }
        context.stroke(path, with: .color(color), lineWidth: 1.8)
    }
}

private struct DesktopPitchTraceChart: View {
    let trace: [SpectrumHistoryBuffer.PitchTracePoint]

    var body: some View {
        GeometryReader { proxy in
            let plot = CGRect(x: 48, y: 8, width: proxy.size.width - 60, height: proxy.size.height - 34)
            let bounds = pitchDisplayBounds(trace.map(\.midi))
            Canvas { context, _ in
                for fraction in stride(from: 0.0, through: 1.0, by: 0.25) {
                    let y = plot.minY + plot.height * fraction
                    var grid = Path()
                    grid.move(to: CGPoint(x: plot.minX, y: y))
                    grid.addLine(to: CGPoint(x: plot.maxX, y: y))
                    context.stroke(grid, with: .color(.secondary.opacity(0.16)), lineWidth: 1)
                    let midi = bounds.maximum - (bounds.maximum - bounds.minimum) * fraction
                    context.draw(
                        Text(String(format: "%.0f", midi)).font(.caption2).foregroundStyle(.secondary),
                        at: CGPoint(x: plot.minX - 7, y: y),
                        anchor: .trailing
                    )
                }
                guard let now = trace.last?.timeSeconds else { return }
                var path = Path()
                var previousSegment: Int?
                for point in trace {
                    let x = plot.minX + plot.width
                        * ((point.timeSeconds - (now - 12)) / 12).clamped(to: 0...1)
                    let fraction = (
                        (Double(point.midi) - bounds.minimum)
                            / (bounds.maximum - bounds.minimum)
                    ).clamped(to: 0...1)
                    let y = plot.maxY - plot.height * fraction
                    if previousSegment == point.segment {
                        path.addLine(to: CGPoint(x: x, y: y))
                    } else {
                        path.move(to: CGPoint(x: x, y: y))
                    }
                    previousSegment = point.segment
                }
                context.stroke(path, with: .color(MacTheme.tuneIn), lineWidth: 2)
                for (fraction, label) in [(0.0, "-12秒"), (0.5, "-6秒"), (1.0, "现在")] {
                    context.draw(
                        Text(label).font(.caption2).foregroundStyle(.secondary),
                        at: CGPoint(x: plot.minX + plot.width * fraction, y: plot.maxY + 7),
                        anchor: .top
                    )
                }
            }
        }
    }
}

private struct DesktopWaveformChart: View {
    let minimum: [Float]
    let maximum: [Float]

    var body: some View {
        GeometryReader { proxy in
            let plot = CGRect(x: 48, y: 8, width: proxy.size.width - 60, height: proxy.size.height - 34)
            Canvas { context, _ in
                for (fraction, label) in [(0.0, "+1"), (0.5, "0"), (1.0, "-1")] {
                    let y = plot.minY + plot.height * fraction
                    var line = Path()
                    line.move(to: CGPoint(x: plot.minX, y: y))
                    line.addLine(to: CGPoint(x: plot.maxX, y: y))
                    context.stroke(line, with: .color(.secondary.opacity(0.16)), lineWidth: 1)
                    context.draw(
                        Text(label).font(.caption2).foregroundStyle(.secondary),
                        at: CGPoint(x: plot.minX - 7, y: y),
                        anchor: .trailing
                    )
                }
                guard minimum.count == maximum.count, minimum.count > 1 else { return }
                var area = Path()
                for index in minimum.indices {
                    let x = plot.minX + plot.width * Double(index) / Double(minimum.count - 1)
                    let y = plot.midY - plot.height * Double(maximum[index].clamped(to: -1...1)) / 2
                    if index == minimum.startIndex { area.move(to: CGPoint(x: x, y: y)) }
                    else { area.addLine(to: CGPoint(x: x, y: y)) }
                }
                for index in minimum.indices.reversed() {
                    let x = plot.minX + plot.width * Double(index) / Double(minimum.count - 1)
                    let y = plot.midY - plot.height * Double(minimum[index].clamped(to: -1...1)) / 2
                    area.addLine(to: CGPoint(x: x, y: y))
                }
                area.closeSubpath()
                context.fill(area, with: .color(MacTheme.accent.opacity(0.28)))
                context.stroke(area, with: .color(MacTheme.accent), lineWidth: 1)
            }
        }
    }
}

private struct DesktopWaterfallChart: View {
    let rows: [[Float]]
    let columnCount: Int

    var body: some View {
        GeometryReader { proxy in
            let plot = CGRect(
                x: 52,
                y: 8,
                width: max(1, proxy.size.width - 100),
                height: max(1, proxy.size.height - 34)
            )
            Canvas { context, _ in
                let rowHeight = plot.height / Double(max(rows.count, 256))
                let columnWidth = plot.width / Double(max(columnCount, 1))
                for (rowIndex, row) in rows.enumerated() {
                    for (column, value) in row.enumerated() {
                        context.fill(
                            Path(
                                CGRect(
                                    x: plot.minX + Double(column) * columnWidth,
                                    y: plot.minY + Double(rowIndex) * rowHeight,
                                    width: columnWidth + 0.5,
                                    height: rowHeight + 0.5
                                )
                            ),
                            with: .color(MacTheme.heatColor(value))
                        )
                    }
                }
                for tick in professionalTimeTicks() {
                    let y = plot.minY + plot.height * tick.fraction
                    context.draw(
                        Text(tick.label).font(.caption2).foregroundStyle(.secondary),
                        at: CGPoint(x: plot.minX - 7, y: y),
                        anchor: .trailing
                    )
                }
                for tick in professionalFrequencyTicks() {
                    context.draw(
                        Text(tick.label).font(.caption2).foregroundStyle(.secondary),
                        at: CGPoint(
                            x: plot.minX + plot.width * tick.fraction,
                            y: plot.maxY + 7
                        ),
                        anchor: .top
                    )
                }
                for index in 0..<5 {
                    let fraction = Double(index) / 4
                    let db = Float(-80 + index * 20)
                    let y = plot.minY + plot.height * fraction
                    context.fill(
                        Path(CGRect(x: plot.maxX + 14, y: y, width: 14, height: 24)),
                        with: .color(MacTheme.heatColor(db))
                    )
                    context.draw(
                        Text("\(Int(db))").font(.caption2).foregroundStyle(.secondary),
                        at: CGPoint(x: plot.maxX + 33, y: y + 12),
                        anchor: .leading
                    )
                }
            }
        }
    }
}
