import SwiftUI

struct DesktopInstrumentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var vm = InstrumentViewModel()
    @StateObject private var access = MacMicrophoneAccess()

    var body: some View {
        MacPageBackground {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("乐器调音")
                        .font(.largeTitle.bold())
                    Text("定弦和指法来自共享 Rust 预设")
                        .foregroundStyle(.secondary)
                    MicrophoneStatusBanner(access: access, onRetry: retryCapture)
                    MacCard {
                        VStack(alignment: .leading, spacing: 14) {
                            Picker("乐器", selection: Binding(
                                get: { vm.instrumentId },
                                set: { value in vm.selectInstrument(value) }
                            )) {
                                ForEach(vm.instruments, id: \.id) {
                                    Text($0.displayName).tag($0.id)
                                }
                            }
                            .pickerStyle(.segmented)
                            controls
                        }
                    }
                    HStack(alignment: .top, spacing: 16) {
                        targets
                        MacCard {
                            VStack {
                                Text(
                                    vm.targetNoteName.map {
                                        "目标 " + $0.replacingOccurrences(of: "#", with: "♯")
                                    } ?? "等待目标"
                                )
                                .font(.title2.bold())
                                DesktopPitchGauge(
                                    cents: vm.centsToTarget.map { Double($0) }
                                )
                                    .frame(height: 250)
                                Text(
                                    vm.centsToTarget.map {
                                        String(format: "%+.1f cents", $0)
                                    } ?? "请发声"
                                )
                                .font(.title3.monospacedDigit())
                                .foregroundStyle(
                                    MacTheme.tuneColor(
                                        vm.centsToTarget.map { Double($0) }
                                    )
                                )
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                .padding(24)
            }
        }
        .onAppear {
            applyCaptureLifecycle(scenePhase)
        }
        .onDisappear { vm.releaseCapture() }
        .onChange(of: scenePhase) { _, phase in
            applyCaptureLifecycle(phase)
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

    @ViewBuilder
    private var controls: some View {
        if vm.kind == .string {
            HStack {
                Picker("定弦", selection: Binding(
                    get: { vm.tuningId },
                    set: { value in vm.selectTuning(value) }
                )) {
                    ForEach(vm.tunings, id: \.id) { Text($0.displayName).tag($0.id) }
                }
                Picker("识别", selection: Binding(
                    get: { vm.mode },
                    set: { value in vm.selectMode(value) }
                )) {
                    Text("自动").tag(SelectionMode.auto)
                    Text("手动").tag(SelectionMode.manual)
                }
                .pickerStyle(.segmented)
                .frame(width: 180)
            }
        } else {
            HStack {
                Picker("调性/型号", selection: Binding(
                    get: { vm.chartGroup },
                    set: { vm.selectChart(group: $0, tongyin: vm.tongyin) }
                )) {
                    ForEach(vm.chartGroups, id: \.self) { Text($0).tag($0) }
                }
                if !vm.tongyinOptions.isEmpty {
                    Picker("筒音", selection: Binding(
                        get: { vm.tongyin },
                        set: { vm.selectChart(group: vm.chartGroup, tongyin: $0) }
                    )) {
                        ForEach(vm.tongyinOptions, id: \.self) { Text("作\($0)").tag($0) }
                    }
                }
            }
        }
    }

    private var targets: some View {
        MacCard {
            ScrollView {
                LazyVStack(spacing: 8) {
                    if vm.kind == .string {
                        ForEach(vm.strings) { item in
                            Button {
                                vm.selectString(item.index - 1)
                            } label: {
                                HStack {
                                    Text("\(item.index) 弦")
                                    Text(item.noteName.replacingOccurrences(of: "#", with: "♯"))
                                        .font(.headline)
                                    Text(item.solfege).foregroundStyle(.secondary)
                                    Spacer()
                                    if item.inTune {
                                        Image(systemName: "checkmark.circle.fill")
                                            .foregroundStyle(MacTheme.tuneIn)
                                    }
                                }
                                .padding(10)
                                .background(
                                    item.active ? MacTheme.accent.opacity(0.13) : .clear,
                                    in: RoundedRectangle(cornerRadius: 10)
                                )
                            }
                            .buttonStyle(.plain)
                        }
                    } else {
                        ForEach(vm.notes) { note in
                            HStack {
                                Text(note.label)
                                Spacer()
                                Text(note.noteName.replacingOccurrences(of: "#", with: "♯"))
                                    .font(.headline)
                                Text(note.solfege)
                                    .foregroundStyle(.secondary)
                            }
                            .padding(10)
                            .background(
                                note.active ? MacTheme.accent.opacity(0.13) : .clear,
                                in: RoundedRectangle(cornerRadius: 10)
                            )
                        }
                    }
                }
            }
            .frame(minHeight: 330)
        }
        .frame(maxWidth: .infinity)
    }
}
