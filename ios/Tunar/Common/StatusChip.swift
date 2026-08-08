import SwiftUI

/// 状态胶囊（design-system §6.5b）：无信号时淡入「mic + 请发声」小胶囊。
struct StatusChip: View {
    @Environment(\.lumen) private var palette
    var visible: Bool

    var body: some View {
        if visible {
            HStack(spacing: Lumen.Spacing.sm) {
                Image(systemName: "mic")
                    .font(.system(size: 12))
                    .foregroundStyle(palette.inkFaint)
                Text("请发声")
                    .font(Lumen.caption)
                    .foregroundStyle(palette.inkSecondary)
            }
            .padding(.horizontal, Lumen.Spacing.lg)
            .frame(height: 36)
            .background(palette.bgSurface, in: Capsule())
            .transition(.opacity)
        }
    }
}
