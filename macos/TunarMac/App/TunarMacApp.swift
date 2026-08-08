import SwiftUI

@main
struct TunarMacApp: App {
    @StateObject private var settings = SettingsStore.shared

    var body: some Scene {
        WindowGroup {
            DesktopRootView()
                .preferredColorScheme(settings.preferredColorScheme)
                .onAppear { settings.applyToEngine() }
                .frame(minWidth: 980, minHeight: 680)
        }
        .defaultSize(width: 1_280, height: 820)
        .windowResizability(.contentMinSize)
    }
}

private extension SettingsStore {
    var preferredColorScheme: ColorScheme? {
        switch themeMode {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}
