package com.liuchong.tunar.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 「Lumen / 微光」色彩 token（design-system §3）。
 * Dark-first；Light 同构派生，不使用光效色（白底光晕显脏）。
 */
data class LumenColors(
    // 背景
    val bgCanvas: Color,
    val bgSurface: Color,
    /** bg/surface 微渐变终点色（v2.0 §3.1）。 */
    val bgSurfaceEnd: Color,
    val bgSurfaceRaised: Color,
    // 文字
    val inkPrimary: Color,
    val inkSecondary: Color,
    val inkFaint: Color,
    // 线条
    val lineSubtle: Color,
    /** 控件顶部 1dp 内高光（v2.0 §3.1，触感来源）。 */
    val highlightInner: Color,
    // 语义色（仅音准反馈）
    val tuneIn: Color,
    val tuneNear: Color,
    val tuneOff: Color,
    // 交互强调
    val accent: Color,
    // 光效色（仅 Dark 使用）
    val glowIn: Color,
    val glowNear: Color,
    val glowOff: Color,
    /** 后景氛围光（atmo/accent α4%，v2.0 §3.3）。 */
    val atmoAccent: Color,
) {
    /** 是否为深色主题（决定是否绘制光效）。 */
    val isDark: Boolean get() = this === LumenDark
}

/** Dark 调色板（design-system §3.1/§3.2/§3.3）。 */
val LumenDark = LumenColors(
    bgCanvas = Color(0xFF0A0D17),
    bgSurface = Color(0xFF171C29),
    bgSurfaceEnd = Color(0xFF1E2536),
    bgSurfaceRaised = Color(0xFF232A3C),
    inkPrimary = Color(0xFFF2F5F9),
    inkSecondary = Color(0xFF9AA4B2),
    inkFaint = Color(0xFF525C6B),
    lineSubtle = Color(0xFF2A3242),
    highlightInner = Color.White.copy(alpha = 0.05f),
    tuneIn = Color(0xFF34E0A1),
    tuneNear = Color(0xFFFFC24B),
    tuneOff = Color(0xFFFF6B6B),
    accent = Color(0xFF7C9CFF),
    glowIn = Color(0xFF34E0A1),
    glowNear = Color(0xFFFFC24B),
    glowOff = Color(0xFFFF6B6B),
    atmoAccent = Color(0xFF7C9CFF),
)

/** Light 调色板（光效 token 保留定义但不应使用）。 */
val LumenLight = LumenColors(
    bgCanvas = Color(0xFFF6F7FA),
    bgSurface = Color(0xFFFFFFFF),
    bgSurfaceEnd = Color(0xFFF6F7FA),
    bgSurfaceRaised = Color(0xFFFFFFFF),
    inkPrimary = Color(0xFF14181F),
    inkSecondary = Color(0xFF5A6472),
    inkFaint = Color(0xFFA8B0BC),
    lineSubtle = Color(0xFFE3E7ED),
    highlightInner = Color.White.copy(alpha = 0.6f),
    tuneIn = Color(0xFF0E9F6E),
    tuneNear = Color(0xFFD97A00),
    tuneOff = Color(0xFFE02424),
    accent = Color(0xFF3B5BDB),
    glowIn = Color(0xFF0E9F6E),
    glowNear = Color(0xFFD97A00),
    glowOff = Color(0xFFE02424),
    atmoAccent = Color(0xFF3B5BDB),
)

val LocalLumenColors = staticCompositionLocalOf { LumenDark }

/** 偏差 → 语义色（三分区：|c|≤5 准 / 5–15 近 / >15 偏）。design-system §3.2。 */
fun tuneColorOf(cents: Float, colors: LumenColors): Color {
    val a = kotlin.math.abs(cents)
    return when {
        a <= 5f -> colors.tuneIn
        a <= 15f -> colors.tuneNear
        else -> colors.tuneOff
    }
}

/** 当前主题的偏差语义色（Composable 便捷版）。 */
@androidx.compose.runtime.Composable
fun tuneColor(cents: Float): Color = tuneColorOf(cents, LocalLumenColors.current)
