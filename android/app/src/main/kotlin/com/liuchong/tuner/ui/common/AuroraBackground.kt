package com.liuchong.tuner.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.theme.tuneColorOf

/**
 * 极光背景（design-system v3.0 §3.3）：
 * 主极光（顶部偏左径向渐变，颜色 = 当前音准语义色，无信号时 accent 蓝；
 * 中心点随 cents 水平漂移 ±3% 屏宽（弹簧 60/0.9），亮度 6s 呼吸 ±3%）
 * + 辅助极光（右下 accent α5%，恒定）。
 * Light 主题不用极光，用极浅语义色渐变背景（α4%）替代。
 *
 * @param tuneCents 当前偏差（null = 无信号/中性面板，主极光用 accent 蓝）
 */
@Composable
fun AuroraBackground(
    tuneCents: Float?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalLumenColors.current

    // 主极光颜色：语义色 300ms 交叉渐变；无信号用 accent
    val mainColor by animateColorAsState(
        targetValue = if (tuneCents != null) tuneColorOf(tuneCents, colors) else colors.accent,
        animationSpec = tween(300),
        label = "auroraColor",
    )
    // 水平漂移（±3% 屏宽，弹簧 60/0.9）
    val drift = remember { Animatable(0f) }
    LaunchedEffect(tuneCents) {
        if (tuneCents != null) {
            drift.animateTo(
                (tuneCents / 50f).coerceIn(-1f, 1f),
                spring(dampingRatio = 0.9f, stiffness = 60f),
            )
        }
    }
    // 6s 呼吸（α ±3%）
    val breath by rememberInfiniteTransition(label = "auroraBreath").animateFloat(
        initialValue = -0.03f,
        targetValue = 0.03f,
        animationSpec = infiniteRepeatable(tween(3000), RepeatMode.Reverse),
        label = "breath",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (colors.isDark) {
                // 主极光：顶部偏左，随 cents 漂移
                val cx = w * 0.30f + drift.value * w * 0.03f
                val cy = h * 0.05f
                val baseAlpha = if (tuneCents != null) 0.13f else 0.10f
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to mainColor.copy(alpha = (baseAlpha + breath).coerceIn(0f, 1f)),
                        1f to Color.Transparent,
                        center = Offset(cx, cy),
                        radius = w * 1.2f,
                    ),
                    radius = w * 1.2f,
                    center = Offset(cx, cy),
                )
                // 辅助极光：右下 accent α5%
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to colors.accent.copy(alpha = 0.05f),
                        1f to Color.Transparent,
                        center = Offset(w * 0.95f, h * 0.95f),
                        radius = w * 0.9f,
                    ),
                    radius = w * 0.9f,
                    center = Offset(w * 0.95f, h * 0.95f),
                )
            } else {
                // Light：极浅语义色渐变背景（α4%）
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to mainColor.copy(alpha = 0.04f),
                        0.5f to Color.Transparent,
                    ),
                )
            }
        }
        content()
    }
}
