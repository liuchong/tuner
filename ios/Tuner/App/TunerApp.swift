import SwiftUI

@main
struct TunerApp: App {
    @StateObject private var settings = SettingsStore.shared

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .tunerTheme(darkScheme: settings.effectiveDarkScheme)
                .onAppear { settings.applyToEngine() }
        }
    }
}

/// 底部 4 tab（design-system §6.8）
struct RootTabView: View {
    @State private var selected = 0
    var body: some View {
        TabView(selection: $selected) {
            TunerView()
                .tabItem { Label("调音", systemImage: "tuningfork") }.tag(0)
            InstrumentView()
                .tabItem { Label("乐器", systemImage: "pianokeys") }.tag(1)
            MetronomeView()
                .tabItem { Label("节拍器", systemImage: "metronome") }.tag(2)
            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape") }.tag(3)
        }
        .tint(Lumen.accent)
    }
}
