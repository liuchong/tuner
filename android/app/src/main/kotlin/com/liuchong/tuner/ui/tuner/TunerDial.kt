package com.liuchong.tuner.ui.tuner

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.theme.tuneColorOf
import kotlin.math.cos
import kotlin.math.sin

/** 表盘量程（±cents）。 */
private const val RANGE_CENTS = 50f

/** 准音中心区（±cents）。 */
private const val IN_TUNE_CENTS = 5f

/** 接近区（±cents）。 */
private const val NEAR_CENTS = 15f

/** Halo 几何：140° 顶部对称弧（design-system v3.0 §6.1）。 */
private const val ARC_SPAN = 140f
private const val ARC_START = 270f - ARC_SPAN / 2f

/** 运动残影帧数与 alpha（30%/15%/7%）。 */
private val TRAIL_ALPHAS = floatArrayOf(0.30f, 0.15f, 0.07f)

/** 数字环标注。 */
private val DIAL_LABELS = listOf("−50", "−25", "0", "+25", "+50")

/** cents → 表盘角度（Canvas 角度：0°=三点钟方向，顺时针为正）。 */
private fun centsToAngle(cents: Float): Float =
    270f + cents.coerceIn(-RANGE_CENTS, RANGE_CENTS) / RANGE_CENTS * (ARC_SPAN / 2f)

/**
 * Halo 表盘（design-system v3.0 §6.1）：同心三层——数字环 / 刻度带+分区弧 /
 * 进度光弧；圆心留空（仅极淡内环）；光针+残影；准音光池光涌；置信度联动。
 *
 * 构图纪律：表盘内不放任何文字读数（读数区在表盘正下方，见 §6.2）。
 *
 * @param cents 当前偏差（调用方做弹簧动画）；null = 无信号（400ms 回中灰显）
 * @param clarity 检测置信度（低时指针 alpha 40%）
 * @param accessibilityText 屏幕阅读器文案
 */
@Composable
fun TunerDial(
    cents: Float?,
    clarity: Float = 1f,
    accessibilityText: String = "音高表盘",
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    val activeColor = tuneColorOf(cents ?: 0f, colors)

    // 准音光涌：进入准音区时 glow 增强至 1.6 倍（280ms FastOutSlowIn）
    val glowBoost = remember { Animatable(1f) }
    val inTuneNow = cents != null && kotlin.math.abs(cents) <= IN_TUNE_CENTS
    LaunchedEffect(inTuneNow) {
        if (inTuneNow) {
            glowBoost.animateTo(1.6f, tween(140, easing = FastOutSlowInEasing))
            glowBoost.animateTo(1.0f, tween(140, easing = FastOutSlowInEasing))
        }
    }

    // 运动残影：保存最近 3 帧角度
    var trail by remember { mutableStateOf(listOf<Float>()) }
    LaunchedEffect(cents) {
        if (cents != null) trail = (listOf(cents) + trail).take(TRAIL_ALPHAS.size + 1).drop(1)
    }

    // 无信号淡出（400ms 线性）
    val fade = remember { Animatable(1f) }
    LaunchedEffect(cents == null) {
        fade.animateTo(if (cents == null) 0.4f else 1f, tween(400))
    }

    // 置信度联动：clarity 低时 alpha 40%
    val confidence = if (clarity < 0.6f) 0.4f else 1f
    val alpha = fade.value * confidence

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = accessibilityText },
    ) {
        val w = size.width
        val h = size.height
        // 几何不变量（构图纪律）：指针扫掠区域（弧顶 → pivot 圆点）必须完整落在
        // 本组件矩形内——pivot 在 0.88h、半径 ≤ 0.72h，针尾数字环留出顶部空间；
        // 读数块位于表盘区域 bottom + 16dp 之下，任何偏转角度（含 ±50c 满偏）
        // 都不会与读数块相交。
        val center = Offset(w / 2f, h * 0.88f)
        val radius = minOf(w * 0.42f, h * 0.72f)
        val dp = 1.dp.toPx()

        fun polar(angleDeg: Float, r: Float): Offset {
            val rad = Math.toRadians(angleDeg.toDouble())
            return Offset(
                center.x + (r * cos(rad)).toFloat(),
                center.y + (r * sin(rad)).toFloat(),
            )
        }

        fun arcRect(r: Float) = androidx.compose.ui.geometry.Rect(
            center.x - r, center.y - r, center.x + r, center.y + r,
        )

        // ---- 准音光池（仅 Dark）：中心 ±5c 下方径向渐变 ----
        if (colors.isDark) {
            val glowRadius = radius * 0.85f
            val glowAlpha = 0.24f * glowBoost.value * alpha
            drawCircle(
                brush = Brush.radialGradient(
                    0f to colors.glowIn.copy(alpha = glowAlpha),
                    1f to colors.glowIn.copy(alpha = 0f),
                    center = center,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = center,
            )
        }

        // ---- 圆心留空：极淡内环（line/subtle α40%）----
        drawCircle(
            color = colors.lineSubtle.copy(alpha = 0.4f * alpha),
            radius = radius * 0.52f,
            center = center,
            style = Stroke(width = 1f * dp),
        )

        // ---- 中环分区弧（6dp 圆头）：红 ±50→15 / 琥珀 ±15→5 / 绿 ±5 ----
        fun zoneArc(from: Float, to: Float, color: Color, bright: Boolean = false) {
            drawArc(
                color = color.copy(alpha = (if (bright) 1f else 0.85f) * alpha),
                startAngle = centsToAngle(from),
                sweepAngle = centsToAngle(to) - centsToAngle(from),
                useCenter = false,
                topLeft = arcRect(radius).topLeft,
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 6f * dp, cap = StrokeCap.Round),
            )
        }
        zoneArc(-RANGE_CENTS, -NEAR_CENTS, colors.tuneOff)
        zoneArc(NEAR_CENTS, RANGE_CENTS, colors.tuneOff)
        zoneArc(-NEAR_CENTS, -IN_TUNE_CENTS, colors.tuneNear)
        zoneArc(IN_TUNE_CENTS, NEAR_CENTS, colors.tuneNear)
        zoneArc(-IN_TUNE_CENTS, IN_TUNE_CENTS, colors.tuneIn, bright = inTuneNow)

        // ---- 进度光弧：从左端到当前位置，端部亮尾部透（分段渐隐）----
        if (cents != null) {
            val targetAngle = centsToAngle(cents)
            val startAngle = centsToAngle(-RANGE_CENTS)
            val segments = 28
            val span = targetAngle - startAngle
            if (span > 0.5f) {
                for (i in 0 until segments) {
                    val a0 = startAngle + span * i / segments
                    val a1 = startAngle + span * (i + 0.8f) / segments
                    val t = i.toFloat() / segments
                    drawArc(
                        color = activeColor.copy(alpha = (0.12f + 0.88f * t) * alpha),
                        startAngle = a0,
                        sweepAngle = a1 - a0,
                        useCenter = false,
                        topLeft = arcRect(radius).topLeft,
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = 6f * dp, cap = StrokeCap.Round),
                    )
                }
            }
        }

        // ---- 外环刻度带：轨道 + 刻度（0c 加长加亮）----
        val tickR = radius * 1.10f
        drawArc(
            color = colors.lineSubtle,
            startAngle = ARC_START,
            sweepAngle = ARC_SPAN,
            useCenter = false,
            topLeft = arcRect(tickR).topLeft,
            size = Size(tickR * 2, tickR * 2),
            style = Stroke(width = 1.5f * dp),
        )
        var c = -RANGE_CENTS
        while (c <= RANGE_CENTS) {
            val angle = centsToAngle(c)
            val isZero = c.toInt() == 0
            val isMajor = c.toInt() % 10 == 0
            val len = when {
                isZero -> 16f * dp
                isMajor -> 12f * dp
                else -> 6f * dp
            }
            drawLine(
                color = when {
                    isZero -> colors.inkPrimary.copy(alpha = 0.9f * alpha)
                    isMajor -> colors.inkFaint.copy(alpha = alpha)
                    else -> colors.inkFaint.copy(alpha = 0.4f * alpha)
                },
                start = polar(angle, tickR - len),
                end = polar(angle, tickR),
                strokeWidth = if (isMajor || isZero) 1.5f * dp else 1f * dp,
            )
            c += 2f
        }

        // ---- 数字环（−50/−25/0/+25/+50，caption，ink/faint）----
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                (0.8f * alpha * 255).toInt(),
                (colors.inkFaint.red * 255).toInt(),
                (colors.inkFaint.green * 255).toInt(),
                (colors.inkFaint.blue * 255).toInt(),
            )
            textSize = 11f * dp
            textAlign = android.graphics.Paint.Align.CENTER
        }
        DIAL_LABELS.forEachIndexed { i, label ->
            val lc = -RANGE_CENTS + i * 25f
            val p = polar(centsToAngle(lc), tickR + 14f * dp)
            drawContext.canvas.nativeCanvas.drawText(
                label, p.x, p.y + labelPaint.textSize / 3f, labelPaint,
            )
        }

        // ---- 运动残影（最近 3 帧，30%/15%/7% alpha）----
        trail.forEachIndexed { i, trailCents ->
            val a = TRAIL_ALPHAS.getOrElse(i) { 0f }
            drawNeedle(centsToAngle(trailCents), center, radius, activeColor, a * alpha, dp)
        }

        // ---- 光针（锥形 + pivot 圆点）----
        drawNeedle(centsToAngle(cents ?: 0f), center, radius, activeColor, alpha, dp)
    }
}

/** 锥形指针（三角形针体 + 根部 pivot 圆点）。 */
private fun DrawScope.drawNeedle(
    angleDeg: Float,
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
    dp: Float,
) {
    if (alpha <= 0f) return
    val rad = Math.toRadians(angleDeg.toDouble())
    val dirX = cos(rad).toFloat()
    val dirY = sin(rad).toFloat()
    val tipR = radius * 0.88f
    val baseR = radius * 0.10f
    val halfW = 3.5f * dp
    val perpX = -dirY
    val perpY = dirX
    val path = Path().apply {
        moveTo(center.x + dirX * tipR, center.y + dirY * tipR)
        lineTo(
            center.x + dirX * baseR + perpX * halfW,
            center.y + dirY * baseR + perpY * halfW,
        )
        lineTo(
            center.x + dirX * baseR - perpX * halfW,
            center.y + dirY * baseR - perpY * halfW,
        )
        close()
    }
    drawPath(path, color = color.copy(alpha = alpha))
    drawCircle(color = color.copy(alpha = alpha), radius = 5f * dp, center = center)
}
