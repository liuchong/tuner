package com.liuchong.tunar.ui.metronome

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.liuchong.tunar.ui.theme.LocalLumenColors
import kotlin.math.sin

/** 最大摆角（度）。 */
private const val MAX_ANGLE = 26.0

/**
 * 摆锤（design-system §6.5/§7）：倒三角摆杆绕顶部支点随拍相位往复摆动
 * （正弦插值，端点自然微顿）；播放时启动，停止时缓动归中。
 */
@Composable
fun Pendulum(
    playing: Boolean,
    bpm: Double,
    beatUnit: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing, bpm, beatUnit) {
        if (playing) {
            val periodMs = 60000.0 / bpm * (4.0 / beatUnit)
            var t0 = -1L
            while (true) {
                withFrameNanos { now ->
                    if (t0 < 0L) t0 = now
                    val tMs = (now - t0) / 1_000_000.0
                    angle = (MAX_ANGLE * sin(2.0 * Math.PI * tMs / periodMs)).toFloat()
                }
            }
        } else {
            // 停止时缓动归中（300ms）
            androidx.compose.animation.core.animate(
                initialValue = angle,
                targetValue = 0f,
                animationSpec = tween(300),
            ) { value, _ -> angle = value }
        }
    }

    Canvas(modifier = modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width
        val h = size.height
        val dp = 1.dp.toPx()
        val pivot = Offset(w / 2f, 8f * dp)
        val rodLen = h * 0.72f

        val rad = Math.toRadians(angle.toDouble())
        val dirX = sin(rad).toFloat()
        val dirY = kotlin.math.cos(rad).toFloat()
        val bob = Offset(pivot.x + dirX * rodLen, pivot.y + dirY * rodLen)

        // 摆幅参考弧（静置态提示）
        drawArc(
            color = colors.lineSubtle,
            startAngle = 90f - MAX_ANGLE.toFloat(),
            sweepAngle = (2f * MAX_ANGLE).toFloat(),
            useCenter = false,
            topLeft = Offset(pivot.x - rodLen, pivot.y - rodLen),
            size = androidx.compose.ui.geometry.Size(rodLen * 2, rodLen * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f * dp),
        )

        // 摆杆（倒三角）
        val perpX = dirY
        val perpY = -dirX
        val baseW = 3f * dp
        val path = Path().apply {
            moveTo(bob.x, bob.y)
            lineTo(pivot.x + perpX * baseW, pivot.y + perpY * baseW)
            lineTo(pivot.x - perpX * baseW, pivot.y - perpY * baseW)
            close()
        }
        val rodColor = if (playing) colors.accent else colors.inkFaint
        drawPath(path, color = rodColor)
        // 摆锤球
        drawCircle(color = rodColor, radius = 7f * dp, center = bob)
        // 支点
        drawCircle(color = colors.inkSecondary, radius = 3f * dp, center = pivot)
    }
}
