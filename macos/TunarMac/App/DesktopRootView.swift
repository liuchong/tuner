import SwiftUI

struct DesktopRootView: View {
    @State private var selection = DesktopSection.defaultSelection
    @StateObject private var practice = PracticeWorkspaceModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var pendingTask: PracticeTask?

    var body: some View {
        NavigationSplitView {
            List(DesktopSection.allCases, selection: $selection) { section in
                Label(section.title, systemImage: section.systemImage)
                    .tag(section)
                    .padding(.vertical, 5)
                    .accessibilityIdentifier("sidebar.\(section.rawValue)")
            }
            .navigationTitle("吐呐")
            .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 240)
        } detail: {
            Group {
                switch selection {
                case .tuner:
                    DesktopPracticeWorkspace(model: practice)
                case .instruments:
                    DesktopInstrumentView(onPractice: { midi, instrument, variant, fingering in
                        practice.lockInstrument(midi: midi, instrumentId: instrument, variantId: variant, fingeringId: fingering)
                        pendingTask = .practice
                        selection = .tuner
                    })
                case .analysis:
                    DesktopPracticeWorkspace(model: practice)
                case .metronome:
                    DesktopMetronomeView()
                case .tools:
                    DesktopToolsView()
                case .settings:
                    DesktopSettingsView()
                }
            }
            .id(selection)
            .transition(.opacity)
            .animation(.easeInOut(duration: 0.15), value: selection)
        }
        .tint(MacTheme.accent)
        .onAppear { practice.task = .tune; updateCapture() }
        .onChange(of: selection) { _, value in
            if let pendingTask { practice.task = pendingTask; self.pendingTask = nil }
            else if value == .tuner { practice.task = .tune }
            else if value == .analysis { practice.task = .analyze }
            updateCapture()
        }
        .onChange(of: scenePhase) { _, _ in updateCapture() }
        .onDisappear { practice.suspend() }
    }
    private func updateCapture() {
        if scenePhase == .active && [.tuner, .instruments, .analysis].contains(selection) { practice.requestCapture() }
        else { practice.suspend() }
    }
}
