import SwiftUI

/// 「Aurora / 极光」设计 token（design-system v4 §3/§4/§5）。
enum Lumen {
    // MARK: 色彩（Dark-first；浅色同构派生见 EnvironmentValues.isLumenDark）
    static func palette(dark: Bool) -> Palette { dark ? .dark : .light }

    struct Palette {
        let bgCanvas: Color
        let bgSurface: Color
        let bgSurfaceEnd: Color
        let bgSurfaceRaised: Color
        let inkPrimary: Color
        let inkSecondary: Color
        let inkFaint: Color
        let lineSubtle: Color
        let highlightInner: Color
        let tuneIn: Color
        let tuneNear: Color
        let tuneOff: Color
        let accent: Color
        let glowIn: Color
        let glowNear: Color
        let glowOff: Color

        static let dark = Palette(
            bgCanvas: Color(hex: 0x0A0D17),
            bgSurface: Color(hex: 0x171C29),
            bgSurfaceEnd: Color(hex: 0x1E2536),
            bgSurfaceRaised: Color(hex: 0x232A3C),
            inkPrimary: Color(hex: 0xF2F5F9),
            inkSecondary: Color(hex: 0x9AA4B2),
            inkFaint: Color(hex: 0x525C6B),
            lineSubtle: Color(hex: 0x2A3242),
            highlightInner: Color.white.opacity(0.05),
            tuneIn: Color(hex: 0x34E0A1),
            tuneNear: Color(hex: 0xFFC24B),
            tuneOff: Color(hex: 0xFF6B6B),
            accent: Color(hex: 0x7C9CFF),
            glowIn: Color(hex: 0x34E0A1),
            glowNear: Color(hex: 0xFFC24B),
            glowOff: Color(hex: 0xFF6B6B)
        )

        static let light = Palette(
            bgCanvas: Color(hex: 0xF6F7FA),
            bgSurface: .white,
            bgSurfaceEnd: Color(hex: 0xF6F7FA),
            bgSurfaceRaised: .white,
            inkPrimary: Color(hex: 0x14181F),
            inkSecondary: Color(hex: 0x5A6472),
            inkFaint: Color(hex: 0xA8B0BC),
            lineSubtle: Color(hex: 0xE3E7ED),
            highlightInner: Color.white.opacity(0.6),
            tuneIn: Color(hex: 0x0E9F6E),
            tuneNear: Color(hex: 0xD97A00),
            tuneOff: Color(hex: 0xE02424),
            accent: Color(hex: 0x3B5BDB),
            glowIn: Color(hex: 0x0E9F6E),
            glowNear: Color(hex: 0xD97A00),
            glowOff: Color(hex: 0xE02424)
        )
    }

    static var accent: Color { palette(dark: true).accent }

    /// 偏差 → 语义色（|c|≤5 准 / 5–15 近 / >15 偏）
    static func tuneColor(of cents: Float, _ p: Palette) -> Color {
        let a = abs(cents)
        if a <= 5 { return p.tuneIn }
        if a <= 15 { return p.tuneNear }
        return p.tuneOff
    }

    // MARK: 字体（等宽数字）
    static let displayNote = Font.system(size: 100, weight: .bold).monospacedDigit()
    static let displayBpm = Font.system(size: 72, weight: .bold).monospacedDigit()
    static let readoutValue = Font.system(size: 18, weight: .medium).monospacedDigit()
    static let readoutSolfege = Font.system(size: 20, weight: .medium)
    static let label = Font.system(size: 14, weight: .medium)
    static let caption = Font.system(size: 12, weight: .regular)

    // MARK: 间距（4dp 基数）
    enum Spacing {
        static let xs: CGFloat = 4
        static let sm: CGFloat = 8
        static let md: CGFloat = 12
        static let lg: CGFloat = 16
        static let xl: CGFloat = 24
        static let xxl: CGFloat = 32
        static let page: CGFloat = 24
    }
}

extension Color {
    init(hex: UInt32, alpha: Double = 1.0) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: alpha
        )
    }
}

// MARK: - 主题环境

private struct LumenPaletteKey: EnvironmentKey {
    static let defaultValue = Lumen.Palette.dark
}

extension EnvironmentValues {
    var lumen: Lumen.Palette {
        get { self[LumenPaletteKey.self] }
        set { self[LumenPaletteKey.self] = newValue }
    }
}

private struct LumenIsDarkKey: EnvironmentKey {
    static let defaultValue = true
}

extension EnvironmentValues {
    var isLumenDark: Bool {
        get { self[LumenIsDarkKey.self] }
        set { self[LumenIsDarkKey.self] = newValue }
    }
}

extension View {
    /// 应用 Lumen 主题（三态由调用方计算）。
    func tunerTheme(darkScheme: Bool) -> some View {
        let palette = Lumen.palette(dark: darkScheme)
        return self
            .environment(\.lumen, palette)
            .environment(\.isLumenDark, darkScheme)
            .environment(\.colorScheme, darkScheme ? .dark : .light)
            .background(palette.bgCanvas.ignoresSafeArea())
    }
}
