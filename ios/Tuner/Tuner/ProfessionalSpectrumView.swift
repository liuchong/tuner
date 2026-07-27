import AVFoundation
import SwiftUI

/// 独立专业频谱分析页：与主调音页共享采集，不创建第二路麦克风。
struct ProfessionalSpectrumView: View {
    @Environment(\.lumen) private var palette
    @StateObject private var vm = TunerViewModel()
    @StateObject private var history = SpectrumHistoryBuffer()
    @State private var permissionDenied = false

    var body: some View {
        AuroraBackground(tuneCents: nil) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: Lumen.Spacing.md) {
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("专业频谱分析仪")
                                .font(.title2.weight(.semibold))
                                .foregroundStyle(palette.inkPrimary)
                            Text("横轴频率 · 实时频谱 / 峰值保持")
                                .font(Lumen.caption)
                                .foregroundStyle(palette.inkSecondary)
                        }
                        Spacer()
                        HStack(spacing: 8) {
                            Button {
                                history.resetPeakHold()
                            } label: {
                                Text("重置峰值")
                                    .font(Lumen.caption)
                                    .foregroundStyle(palette.inkSecondary)
                                    .padding(.horizontal, 12)
                                    .frame(height: 34)
                                    .background(palette.bgSurface, in: Capsule())
                                    .fixedSize()
                            }
                            Button {
                                history.isPaused.toggle()
                            } label: {
                                Text(history.isPaused ? "▶ 继续" : "Ⅱ 暂停")
                                    .font(Lumen.caption)
                                    .foregroundStyle(
                                        history.isPaused
                                            ? palette.accent
                                            : palette.inkSecondary
                                    )
                                    .padding(.horizontal, 12)
                                    .frame(height: 34)
                                    .background(
                                        history.isPaused
                                            ? palette.accent.opacity(0.16)
                                            : palette.bgSurface,
                                        in: Capsule()
                                    )
                                    .fixedSize()
                            }
                        }
                    }

                    SpectrumCard(
                        live: history.currentSpectrum,
                        peak: history.peakSpectrum,
                        partials: vm.partials,
                        inputLevelDbfs: vm.inputLevelDbfs,
                        isPaused: history.isPaused
                    )

                    SpectrumMetricsGrid(metrics: metrics)

                    VStack(alignment: .leading, spacing: 4) {
                        Text("连续时间图谱")
                            .font(Lumen.label)
                            .foregroundStyle(palette.inkPrimary)
                        Text("最新声音在顶部 · 约 12 秒历史")
                            .font(Lumen.caption)
                            .foregroundStyle(palette.inkSecondary)
                    }

                    VStack(spacing: 6) {
                        HStack(spacing: 0) {
                            VerticalAxis(ticks: professionalTimeTicks())
                                .frame(width: 44, height: 340)
                            WaterfallChart(
                                waterfall: history.waterfallData,
                                binCount: history.waterfallBinCount,
                                maxRows: history.maxRows,
                                nextRow: history.nextRow,
                                rowCount: history.rowCount
                            )
                                .frame(height: 340)
                            HeatLegend()
                                .frame(width: 52, height: 340)
                        }
                        FrequencyAxis()
                            .padding(.leading, 44)
                            .padding(.trailing, 52)
                        HStack {
                            Text("时间 ↓")
                            Spacer()
                            Text("颜色：信号强度 dBFS")
                        }
                        .font(Lumen.caption)
                        .foregroundStyle(palette.inkFaint)
                    }
                    .padding(Lumen.Spacing.md)
                    .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 20))
                    .overlay(RoundedRectangle(cornerRadius: 20).stroke(palette.lineSubtle))

                    Text("实际峰值")
                        .font(Lumen.label)
                        .foregroundStyle(palette.inkPrimary)

                    ForEach(Array(vm.partials.enumerated()), id: \.offset) { _, partial in
                        SpectrumPeakRow(partial: partial)
                    }
                }
                .padding(Lumen.Spacing.page)
            }
        }
        .onAppear { requestPermissionAndStart() }
        .onDisappear { vm.releaseCapture() }
        .onChange(of: vm.spectrumDb) { _, spectrum in
            history.accept(spectrum)
        }
        .alert("需要麦克风权限", isPresented: $permissionDenied) {
            Button("去设置") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("请在系统设置中开启麦克风权限以使用频谱分析")
        }
    }

    private var currentReading: TunerReading? {
        guard case .active(let reading) = vm.signal else { return nil }
        return reading
    }

    private var metrics: ProfessionalSpectrumMetrics {
        professionalSpectrumMetrics(
            reading: currentReading,
            inputLevelDbfs: vm.inputLevelDbfs,
            partials: vm.partials,
            chord: vm.chord
        )
    }

    private func requestPermissionAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            vm.startCapture()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                DispatchQueue.main.async {
                    if granted { vm.startCapture() } else { permissionDenied = true }
                }
            }
        default:
            permissionDenied = true
        }
    }
}

private struct SpectrumCard: View {
    @Environment(\.lumen) private var palette
    let live: [Float]
    let peak: [Float]
    let partials: [Partial]
    let inputLevelDbfs: Float
    let isPaused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(
                    String(
                        format: "%@ · 输入 %.1f dBFS",
                        isPaused ? "■ 已冻结" : "● 实时刷新",
                        inputLevelDbfs
                    )
                )
                .foregroundStyle(isPaused ? palette.inkSecondary : palette.accent)
                Spacer()
                Text("纵轴 dBFS")
                    .foregroundStyle(palette.inkFaint)
            }
            .font(Lumen.caption)

            HStack(spacing: 0) {
                VerticalAxis(ticks: professionalDbTicks())
                    .frame(width: 58, height: 280)
                SpectrumLineChart(live: live, peak: peak, partials: partials)
                    .frame(height: 280)
            }
            FrequencyAxis()
                .padding(.leading, 58)
        }
        .padding(Lumen.Spacing.md)
        .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 20))
        .overlay(RoundedRectangle(cornerRadius: 20).stroke(palette.lineSubtle))
    }
}

private struct SpectrumLineChart: View {
    @Environment(\.lumen) private var palette
    let live: [Float]
    let peak: [Float]
    let partials: [Partial]
    @State private var cursorFraction: CGFloat?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            GeometryReader { geometry in
                Canvas { context, size in
                    for tick in professionalDbTicks() {
                        let y = size.height * CGFloat(tick.fraction)
                        context.stroke(
                            Path { path in
                                path.move(to: CGPoint(x: 0, y: y))
                                path.addLine(to: CGPoint(x: size.width, y: y))
                            },
                            with: .color(palette.lineSubtle),
                            lineWidth: 0.5
                        )
                    }
                    for tick in professionalFrequencyTicks() {
                        let x = size.width * CGFloat(tick.fraction)
                        context.stroke(
                            Path { path in
                                path.move(to: CGPoint(x: x, y: 0))
                                path.addLine(to: CGPoint(x: x, y: size.height))
                            },
                            with: .color(palette.lineSubtle),
                            lineWidth: 0.5
                        )
                    }
                    if peak.count > 1 {
                        context.stroke(
                            spectrumPath(peak, size: size),
                            with: .color(palette.accent.opacity(0.38)),
                            lineWidth: 1.5
                        )
                    }
                    if live.count > 1 {
                        context.stroke(
                            spectrumPath(live, size: size),
                            with: .color(palette.accent),
                            lineWidth: 2
                        )
                    }
                    for partial in partials {
                        let x = frequencyFraction(partial.freqHz) * size.width
                        let y = dbY(partial.magnitudeDb, height: size.height)
                        context.fill(
                            Path(
                                ellipseIn: CGRect(
                                    x: x - 3,
                                    y: y - 3,
                                    width: 6,
                                    height: 6
                                )
                            ),
                            with: .color(palette.inkPrimary)
                        )
                    }
                    if let cursorFraction {
                        let x = cursorFraction * size.width
                        context.stroke(
                            Path { path in
                                path.move(to: CGPoint(x: x, y: 0))
                                path.addLine(to: CGPoint(x: x, y: size.height))
                            },
                            with: .color(palette.inkPrimary),
                            lineWidth: 1
                        )
                    }
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            cursorFraction = min(
                                1,
                                max(0, value.location.x / max(geometry.size.width, 1))
                            )
                        }
                )
            }

            if let cursorFraction, !live.isEmpty {
                Text(cursorLabel(cursorFraction))
                    .font(Lumen.caption)
                    .foregroundStyle(palette.accent)
            }
        }
    }

    private func cursorLabel(_ fraction: CGFloat) -> String {
        let frequency = professionalSpectrumMinHz
            * pow(professionalSpectrumMaxHz / professionalSpectrumMinHz, Double(fraction))
        let index = min(live.count - 1, max(0, Int(fraction * CGFloat(live.count - 1))))
        let nearest = partials.min {
            abs(log($0.freqHz / frequency)) < abs(log($1.freqHz / frequency))
        }
        let note: String
        if let nearest, abs(log(nearest.freqHz / frequency)) < 0.04, !nearest.noteName.isEmpty {
            note = nearest.noteName.replacingOccurrences(of: "#", with: "♯")
        } else {
            note = "—"
        }
        return String(format: "游标  %.1f Hz   %.1f dB   %@", frequency, live[index], note)
    }
}

private struct WaterfallChart: View {
    @Environment(\.lumen) private var palette
    let waterfall: [Float]
    let binCount: Int
    let maxRows: Int
    let nextRow: Int
    let rowCount: Int

    var body: some View {
        Canvas { context, size in
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(palette.bgCanvas))
            guard !waterfall.isEmpty, binCount > 0, maxRows > 0 else { return }
            let rowHeight = size.height / CGFloat(maxRows)
            let cellWidth = size.width / CGFloat(binCount)
            for rowIndex in 0..<min(rowCount, maxRows) {
                let sourceRow = (nextRow - 1 - rowIndex + maxRows) % maxRows
                for bin in 0..<binCount {
                    let db = waterfall[sourceRow * binCount + bin]
                    context.fill(
                        Path(
                            CGRect(
                                x: CGFloat(bin) * cellWidth,
                                y: CGFloat(rowIndex) * rowHeight,
                                width: cellWidth + 0.5,
                                height: rowHeight + 0.5
                            )
                        ),
                        with: .color(spectrumHeatColor(db, background: palette.bgCanvas))
                    )
                }
            }
            for tick in professionalFrequencyTicks() {
                let x = size.width * CGFloat(tick.fraction)
                context.stroke(
                    Path { path in
                        path.move(to: CGPoint(x: x, y: 0))
                        path.addLine(to: CGPoint(x: x, y: size.height))
                    },
                    with: .color(palette.lineSubtle),
                    lineWidth: 0.5
                )
            }
            for tick in professionalTimeTicks() {
                let y = size.height * CGFloat(tick.fraction)
                context.stroke(
                    Path { path in
                        path.move(to: CGPoint(x: 0, y: y))
                        path.addLine(to: CGPoint(x: size.width, y: y))
                    },
                    with: .color(palette.lineSubtle),
                    lineWidth: 0.5
                )
            }
        }
    }
}

private struct FrequencyAxis: View {
    @Environment(\.lumen) private var palette

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .topLeading) {
                ForEach(Array(professionalFrequencyTicks().enumerated()), id: \.offset) {
                    index,
                    tick in
                    Text(tick.label)
                        .frame(width: 48, alignment: axisTextAlignment(index, count: 6))
                        .offset(
                            x: horizontalLabelOffset(
                                fraction: tick.fraction,
                                index: index,
                                count: 6,
                                width: geometry.size.width,
                                labelWidth: 48
                            )
                        )
                }
            }
        }
        .font(Lumen.caption)
        .foregroundStyle(palette.inkFaint)
        .frame(height: 18)
    }
}

private struct VerticalAxis: View {
    @Environment(\.lumen) private var palette
    let ticks: [SpectrumAxisTick]

    var body: some View {
        GeometryReader { geometry in
            ForEach(Array(ticks.enumerated()), id: \.offset) { index, tick in
                Text(tick.label)
                    .font(.system(size: 9))
                    .foregroundStyle(palette.inkFaint)
                    .lineLimit(1)
                    .position(
                        x: geometry.size.width / 2,
                        y: verticalLabelPosition(
                            fraction: tick.fraction,
                            index: index,
                            count: ticks.count,
                            height: geometry.size.height
                        )
                    )
            }
        }
    }
}

private struct HeatLegend: View {
    @Environment(\.lumen) private var palette

    var body: some View {
        HStack(spacing: 3) {
            LinearGradient(
                colors: [
                    Color(red: 0.90, green: 0.22, blue: 0.21),
                    Color(red: 1.00, green: 0.78, blue: 0.34),
                    Color(red: 0.15, green: 0.78, blue: 0.85),
                    Color(red: 0.56, green: 0.35, blue: 0.78),
                    Color(red: 0.22, green: 0.29, blue: 0.67),
                    palette.bgCanvas,
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(width: 10)
            VerticalAxis(ticks: professionalDbTicks())
        }
        .padding(.leading, 6)
    }
}

private struct SpectrumMetricsGrid: View {
    @Environment(\.lumen) private var palette
    let metrics: ProfessionalSpectrumMetrics

    private var entries: [(String, String)] {
        [
            ("音名", metrics.note),
            ("基频", metrics.fundamental),
            ("音分", metrics.cents),
            ("输入", metrics.inputLevel),
            ("最强峰", metrics.strongestPeak),
            ("和弦", metrics.chord),
        ]
    }

    var body: some View {
        LazyVGrid(
            columns: Array(
                repeating: GridItem(.flexible(), spacing: Lumen.Spacing.sm),
                count: 3
            ),
            spacing: 6
        ) {
            ForEach(Array(entries.enumerated()), id: \.offset) { _, entry in
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.0)
                        .font(Lumen.caption)
                        .foregroundStyle(palette.inkFaint)
                    Text(entry.0 == "最强峰" ? entry.1.replacingOccurrences(of: " · ", with: "\n") : entry.1)
                        .font(Lumen.caption)
                        .foregroundStyle(entry.0 == "和弦" ? palette.accent : palette.inkPrimary)
                        .lineLimit(entry.0 == "最强峰" ? 2 : 1)
                        .minimumScaleFactor(0.68)
                }
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .frame(
                    maxWidth: .infinity,
                    minHeight: 52,
                    maxHeight: 52,
                    alignment: .leading
                )
                .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 16))
            }
        }
    }
}

private struct SpectrumPeakRow: View {
    @Environment(\.lumen) private var palette
    let partial: Partial

    var body: some View {
        HStack {
            Text(
                partial.harmonicIndex > 0
                    ? "H\(partial.harmonicIndex)"
                    : partial.noteName.isEmpty
                        ? "独立峰"
                        : partial.noteName.replacingOccurrences(of: "#", with: "♯")
            )
            .font(Lumen.label)
            .foregroundStyle(palette.inkPrimary)
            .frame(width: 56, alignment: .leading)
            Text(String(format: "%.1f Hz", partial.freqHz))
                .font(.system(.body, design: .monospaced))
                .foregroundStyle(palette.inkPrimary)
            Spacer()
            Text(String(format: "%.1f dB", partial.magnitudeDb))
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(palette.inkSecondary)
        }
        .padding(.horizontal, 14)
        .frame(height: 44)
        .background(palette.bgSurface, in: RoundedRectangle(cornerRadius: 12))
    }
}

private func spectrumPath(_ values: [Float], size: CGSize) -> Path {
    Path { path in
        for (index, db) in values.enumerated() {
            let x = size.width * CGFloat(index) / CGFloat(max(1, values.count - 1))
            let point = CGPoint(x: x, y: dbY(db, height: size.height))
            if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
    }
}

private func dbY(_ db: Float, height: CGFloat) -> CGFloat {
    let fraction = CGFloat(
        min(
            1,
            max(
                0,
                (db - professionalSpectrumFloorDb) / -professionalSpectrumFloorDb
            )
        )
    )
    return height * (1 - fraction)
}

private func spectrumHeatColor(_ db: Float, background: Color) -> Color {
    switch spectrumHeatBand(db) {
    case .background: return background
    case .indigo: return Color(red: 0.22, green: 0.29, blue: 0.67)
    case .violet: return Color(red: 0.56, green: 0.35, blue: 0.78)
    case .cyan: return Color(red: 0.15, green: 0.78, blue: 0.85)
    case .yellow: return Color(red: 1.00, green: 0.78, blue: 0.34)
    case .red: return Color(red: 0.90, green: 0.22, blue: 0.21)
    }
}

private func horizontalLabelOffset(
    fraction: Double,
    index: Int,
    count: Int,
    width: CGFloat,
    labelWidth: CGFloat
) -> CGFloat {
    if index == 0 { return 0 }
    if index == count - 1 { return max(0, width - labelWidth) }
    return max(0, min(width - labelWidth, width * CGFloat(fraction) - labelWidth / 2))
}

private func axisTextAlignment(_ index: Int, count: Int) -> Alignment {
    if index == 0 { return .leading }
    if index == count - 1 { return .trailing }
    return .center
}

private func verticalLabelPosition(
    fraction: Double,
    index: Int,
    count: Int,
    height: CGFloat
) -> CGFloat {
    if index == 0 { return 6 }
    if index == count - 1 { return max(6, height - 6) }
    return height * CGFloat(fraction)
}
