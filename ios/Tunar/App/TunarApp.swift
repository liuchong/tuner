import SwiftUI

@main
struct TunarApp: App {
    @StateObject private var settings = SettingsStore.shared

    var body: some Scene {
        WindowGroup {
            RootTabView()
                .tunarTheme(darkScheme: settings.effectiveDarkScheme)
                .onAppear { settings.applyToEngine() }
        }
    }
}

/// 底部 5 tab（design-system §6.8）
struct RootTabView: View {
    @State private var selected = LaunchOverrides.initialTab
    var body: some View {
        TabView(selection: $selected) {
            TunerView { selected = 2 }
                .tabItem { Label("调音", systemImage: "tuningfork") }.tag(0)
            InstrumentView()
                .tabItem { Label("乐器", systemImage: "pianokeys") }.tag(1)
            ProfessionalSpectrumView()
                .tabItem { Label("频谱", systemImage: "waveform.path.ecg") }.tag(2)
            MetronomeView()
                .tabItem { Label("节拍器", systemImage: "metronome") }.tag(3)
            SettingsView()
                .tabItem { Label("设置", systemImage: "gearshape") }.tag(4)
        }
        .tint(Lumen.accent)
    }
}

/// 仅 DEBUG 生效的启动参数（`-TunarInitialTab 1 -TunarInitialInstrument guqin`），
/// 用于截图自动化直达指定页面；Release 构建恒为默认值。
enum LaunchOverrides {
    static var initialTab: Int {
        #if DEBUG
        return UserDefaults.standard.integer(forKey: "TunarInitialTab")
        #else
        return 0
        #endif
    }

    static var initialInstrument: String? {
        #if DEBUG
        return UserDefaults.standard.string(forKey: "TunarInitialInstrument")
        #else
        return nil
        #endif
    }
}
