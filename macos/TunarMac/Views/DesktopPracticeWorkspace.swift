import SwiftUI

/// 桌面窗口共享的工作区，不为任务切换创建新的测量对象。
struct DesktopPracticeWorkspace: View {
    @ObservedObject var model: PracticeWorkspaceModel
    var body: some View {
        MacPageBackground { PracticeWorkspaceView(model: model) }
            .background {
                // 原生菜单命令响应焦点；文本编辑时不抢占方向键。
                PracticeKeyboardCommands(model: model)
            }
    }
}

private struct PracticeKeyboardCommands: NSViewRepresentable {
    let model: PracticeWorkspaceModel
    func makeNSView(context: Context) -> CommandObserver { CommandObserver(model: model) }
    func updateNSView(_ nsView: CommandObserver, context: Context) { nsView.model = model }
    static func dismantleNSView(_ nsView: CommandObserver, coordinator: ()) { nsView.stop() }

    final class CommandObserver: NSView {
        var model: PracticeWorkspaceModel
        private var monitor: Any?
        init(model: PracticeWorkspaceModel) {
            self.model = model
            super.init(frame: .zero)
            monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
                guard let self, let window = self.window, window.isKeyWindow,
                      !(window.firstResponder is NSTextView), !(window.firstResponder is NSTextField),
                      window.attachedSheet == nil,
                      event.modifierFlags.intersection([.command, .control, .option]).isEmpty else { return event }
                switch event.keyCode {
                case 49:
                    guard !event.isARepeat else { return nil }
                    if self.model.status.state == .recording { self.model.pause() } else { self.model.start() }
                case 123: self.model.stepReview(direction: -1, seconds: event.modifierFlags.contains(.shift))
                case 124: self.model.stepReview(direction: 1, seconds: event.modifierFlags.contains(.shift))
                default: return event
                }
                return nil
            }
        }
        required init?(coder: NSCoder) { nil }
        func stop() { if let monitor { NSEvent.removeMonitor(monitor); self.monitor = nil } }
    }
}
