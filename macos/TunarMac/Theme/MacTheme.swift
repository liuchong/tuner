import SwiftUI

enum MacTheme {
    static let accent = Color(red: 0.31, green: 0.47, blue: 0.95)
    static let tuneIn = Color(red: 0.10, green: 0.66, blue: 0.47)
    static let tuneNear = Color(red: 0.95, green: 0.61, blue: 0.18)
    static let tuneOff = Color(red: 0.91, green: 0.25, blue: 0.27)

    static func tuneColor(_ cents: Double?) -> Color {
        guard let cents else { return .secondary }
        switch abs(cents) {
        case ...5: return tuneIn
        case ...15: return tuneNear
        default: return tuneOff
        }
    }

    static func heatColor(_ db: Float) -> Color {
        switch spectrumHeatBand(db) {
        case .background: Color(red: 0.04, green: 0.05, blue: 0.10)
        case .indigo: Color(red: 0.22, green: 0.29, blue: 0.67)
        case .violet: Color(red: 0.56, green: 0.35, blue: 0.78)
        case .cyan: Color(red: 0.15, green: 0.78, blue: 0.85)
        case .yellow: Color(red: 1.00, green: 0.78, blue: 0.34)
        case .red: Color(red: 0.90, green: 0.22, blue: 0.21)
        }
    }
}

struct MacPageBackground<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(nsColor: .windowBackgroundColor),
                    MacTheme.accent.opacity(0.055),
                    Color(nsColor: .windowBackgroundColor),
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            content
        }
    }
}

struct MacCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(18)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
            .overlay(
                RoundedRectangle(cornerRadius: 18)
                    .stroke(Color.primary.opacity(0.08), lineWidth: 1)
            )
    }
}

struct MetricPill: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(.body, design: .rounded).monospacedDigit())
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
        .padding(.horizontal, 13)
        .padding(.vertical, 9)
        .background(Color.primary.opacity(0.045), in: RoundedRectangle(cornerRadius: 13))
    }
}
