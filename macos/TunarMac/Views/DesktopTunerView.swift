import SwiftUI

struct DesktopTunerView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = TunerViewModel()
    @StateObject private var settings = SettingsStore.shared
    @StateObject private var access = MacMicrophoneAccess()
    @StateObject private var fork = TuningForkViewModel(initialConfig: defaultTunarConfig())
    let openAnalysis: () -> Void

    private var reading: TunerReading? {
        guard case .active(let value) = vm.signal else { return nil }
        return value
    }

    var body: some View {
        MacPageBackground {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    pageHeader
                    MicrophoneStatusBanner(access: access, onRetry: retryCapture)
                    GeometryReader { proxy in
                        if DesktopLayout.columns(for: proxy.size.width) == 2 {
                            HStack(alignment: .top, spacing: 16) {
                                tunerCard
                                analysisPreview
                            }
                        } else {
                            VStack(spacing: 16) {
                                tunerCard
                                analysisPreview
                            }
                        }
                    }
                    .frame(minHeight: 570)
                }
                .padding(24)
            }
        }
        .sheet(isPresented: Binding(
            get: { fork.isOpen },
            set: { if !$0 { fork.close() } }
        )) {
            ReferenceToneSheet(viewModel: fork)
                .frame(minWidth: 620, minHeight: 520)
        }
        .onAppear {
            fork.refresh(settings.toTunarConfig())
            applyCaptureLifecycle(scenePhase)
        }
        .onDisappear {
            fork.stopForBackground()
            vm.releaseCapture()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active {
                fork.stopForBackground()
            }
            applyCaptureLifecycle(phase)
        }
        .onChange(of: settings.a4Hz) { _, _ in fork.refresh(settings.toTunarConfig()) }
        .onChange(of: settings.temperament) { _, _ in fork.refresh(settings.toTunarConfig()) }
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

    private var pageHeader: some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text("通用调音")
                    .font(.largeTitle.bold())
                Text("单指针跟随 · 最近可信音高持续保持")
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if fork.selectedStep != nil {
                Button(fork.playingStep == nil ? "继续参考音" : "停止参考音") {
                    fork.toggleSelected()
                }
            }
            Button {
                fork.open()
            } label: {
                Label("固定音高", systemImage: "tuningfork")
            }
            .buttonStyle(.borderedProminent)
            Toggle("PRO", isOn: $settings.proMode)
                .toggleStyle(.button)
        }
    }

    private var tunerCard: some View {
        MacCard {
            VStack(spacing: 12) {
                DesktopPitchGauge(cents: reading?.centsOff)
                    .frame(height: 250)
                Text(reading?.noteName.replacingOccurrences(of: "#", with: "♯") ?? "—")
                    .font(.system(size: 76, weight: .semibold, design: .rounded))
                    .foregroundStyle(MacTheme.tuneColor(reading?.centsOff))
                Text(reading?.solfege ?? "请发声")
                    .font(.title2)
                    .foregroundStyle(.secondary)
                HStack {
                    MetricPill(
                        title: "频率",
                        value: reading.map { String(format: "%.1f Hz", $0.freqHz) } ?? "—"
                    )
                    MetricPill(
                        title: "音分",
                        value: reading.map { String(format: "%+.1f cents", $0.centsOff) } ?? "—"
                    )
                    MetricPill(
                        title: "清晰度",
                        value: reading.map { String(format: "%.0f%%", $0.clarity * 100) } ?? "—"
                    )
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var analysisPreview: some View {
        Button(action: openAnalysis) {
            MacCard {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Label("实时频谱", systemImage: "waveform.path.ecg")
                            .font(.title3.bold())
                        Spacer()
                        Text("打开专业分析")
                            .foregroundStyle(MacTheme.accent)
                    }
                    MiniSpectrumPlot(
                        values: vm.displaySpectrumDb,
                        peaks: vm.displayPartials
                    )
                    .frame(height: 250)
                    HStack {
                        MetricPill(title: "输入", value: String(format: "%.1f dBFS", vm.inputLevelDbfs))
                        MetricPill(title: "和弦", value: vm.displayChord ?? "—")
                    }
                    Text("频谱只在可信 Tracking 帧更新，与指针保持同步。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
        .accessibilityLabel("打开专业分析")
    }
}

private struct ReferenceToneSheet: View {
    @ObservedObject var viewModel: TuningForkViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                VStack(alignment: .leading) {
                    Text("固定音高")
                        .font(.title.bold())
                    Text("收起本窗口后继续播放，离开调音页时停止。")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if viewModel.selectedStep != nil {
                    Button(viewModel.playingStep == nil ? "继续" : "停止") {
                        viewModel.toggleSelected()
                    }
                }
                Button("完成", action: viewModel.close)
            }
            ScrollView {
                LazyVGrid(
                    columns: [GridItem(.adaptive(minimum: 118), spacing: 10)],
                    spacing: 10
                ) {
                    ForEach(viewModel.tones, id: \.stepFromA4) { tone in
                        let playing = viewModel.playingStep == tone.stepFromA4
                        Button {
                            viewModel.toggle(tone)
                        } label: {
                            VStack(spacing: 4) {
                                Text(tone.noteName.replacingOccurrences(of: "#", with: "♯"))
                                    .font(.headline)
                                Text(String(format: "%.1f Hz", tone.frequencyHz))
                                    .font(.caption.monospacedDigit())
                            }
                            .frame(maxWidth: .infinity, minHeight: 54)
                        }
                        .buttonStyle(.bordered)
                        .tint(playing ? MacTheme.accent : nil)
                    }
                }
            }
        }
        .padding(24)
    }
}
