import SwiftUI

struct DesktopRootView: View {
    @State private var selection = DesktopSection.defaultSelection

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
                    DesktopTunerView {
                        selection = DesktopNavigation.openAnalysis(from: selection)
                    }
                case .instruments:
                    DesktopInstrumentView()
                case .analysis:
                    DesktopAnalysisView()
                case .metronome:
                    DesktopMetronomeView()
                case .settings:
                    DesktopSettingsView()
                }
            }
            .id(selection)
            .transition(.opacity)
            .animation(.easeInOut(duration: 0.15), value: selection)
        }
        .tint(MacTheme.accent)
    }
}
