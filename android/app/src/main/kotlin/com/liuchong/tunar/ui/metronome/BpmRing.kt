package com.liuchong.tunar.ui.metronome

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.liuchong.tunar.ui.theme.LocalLumenColors
import com.liuchong.tunar.ui.theme.TunarTypography
import kotlin.math.cos
import kotlin.math.sin

/** BPM 量程。 */
private const val BPM_MIN = 30f
private const val BPM_MAX = 250f

/** 环形几何：240°（design-system §6.5）。 */
private const val RING_START = 150f
private const val RING_SWEEP = 240f

private fun bpmToAngle(bpm: Float): Float =
    RING_START + (bpm.coerceIn(BPM_MIN, BPM_MAX) - BPM_MIN) / (BPM_MAX - BPM_MIN) * RING_SWEEP

/**
 * BPM 环（design-system §6.5）：BPM 大字居中，外圈 240° 环形刻度
 * （每 10 BPM 一刻度，当前位置亮点）。
 */
@Composable
fun BpmRing(bpm: Double, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    val colors = LocalLumenColors.current
    Box(modifier = modifier.fillMaxWidth().aspectRatio(1.5f)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w / 2f, h * 0.92f)
            val radius = minOf(w / 2f, h * 0.92f) * 0.9f
            val dp = 1.dp.toPx()

            fun polar(angleDeg: Float, r: Float): Offset {
                val rad = Math.toRadians(angleDeg.toDouble())
                return Offset(
                    center.x + (r * cos(rad)).toFloat(),
                    center.y + (r * sin(rad)).toFloat(),
                )
            }

            // 轨道弧
            drawArc(
                color = colors.lineSubtle,
                startAngle = RING_START,
                sweepAngle = RING_SWEEP,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = 1.5f * dp),
            )
            // 每 10 BPM 一刻度
            var mark = BPM_MIN
            while (mark <= BPM_MAX) {
                val angle = bpmToAngle(mark)
                drawLine(
                    color = colors.inkFaint,
                    start = polar(angle, radius - 6f * dp),
                    end = polar(angle, radius),
                    strokeWidth = 1f * dp,
                )
                mark += 10f
            }
            // 当前位置亮点
            drawCircle(
                color = colors.accent,
                radius = 5f * dp,
                center = polar(bpmToAngle(bpm.toFloat()), radius),
            )
            drawCircle(
                color = colors.accent.copy(alpha = 0.3f),
                radius = 9f * dp,
                center = polar(bpmToAngle(bpm.toFloat()), radius),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center),
        ) {
            Text(
                text = "${bpm.toInt()}",
                style = TunarTypography.displayBpm,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}
