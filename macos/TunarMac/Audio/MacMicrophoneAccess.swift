import AppKit
import AVFoundation
import SwiftUI

@MainActor
final class MacMicrophoneAccess: ObservableObject {
    enum State {
        case unknown
        case granted
        case denied
    }

    @Published private(set) var state: State = .unknown

    func request(onGranted: @escaping @MainActor @Sendable () -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            state = .granted
            onGranted()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { [weak self] granted in
                Task { @MainActor in
                    self?.state = granted ? .granted : .denied
                    if granted { onGranted() }
                }
            }
        default:
            state = .denied
        }
    }

    func openSystemSettings() {
        guard let url = URL(
            string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone"
        ) else { return }
        NSWorkspace.shared.open(url)
    }
}

enum DesktopMicrophoneRecovery {
    static func shouldShowRetry(
        permissionGranted: Bool,
        captureStartFailed: Bool
    ) -> Bool {
        permissionGranted && captureStartFailed
    }
}

struct MicrophoneStatusBanner: View {
    @ObservedObject var access: MacMicrophoneAccess
    @ObservedObject private var capture = CaptureHub.shared
    let onRetry: () -> Void

    var body: some View {
        if access.state == .denied {
            HStack(spacing: 12) {
                Image(systemName: "mic.slash")
                Text("麦克风权限未开启，调音和分析暂不可用。")
                Spacer()
                Button("打开系统设置", action: access.openSystemSettings)
            }
            .padding(12)
            .background(.orange.opacity(0.13), in: RoundedRectangle(cornerRadius: 12))
        } else if DesktopMicrophoneRecovery.shouldShowRetry(
            permissionGranted: access.state == .granted,
            captureStartFailed: capture.startFailed
        ) {
            HStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle")
                Text("当前输入设备暂时不可用，未启动麦克风采集。")
                Spacer()
                Button("重新尝试", action: onRetry)
            }
            .padding(12)
            .background(.orange.opacity(0.13), in: RoundedRectangle(cornerRadius: 12))
        }
    }
}
