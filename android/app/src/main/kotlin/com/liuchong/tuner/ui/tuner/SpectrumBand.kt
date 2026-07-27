package com.liuchong.tuner.ui.tuner

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.theme.tuneColorOf
import java.util.Locale
import kotlin.math.log10
import uniffi.tuner_core.Partial

/** 频谱轴范围（与 core spectrum.rs 一致：60–2400Hz 对数 64 bin）。 */
private const val F_MIN = 60.0
private const val F_MAX = 2400.0
private const val BINS = 64
private const val DB_MIN = -80f
private const val DB_MAX = 0f

private fun dbToFrac(db: Float): Float =
    ((db - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0f, 1f)

private fun freqToX(f: Double): Float {
    val t = (log10(f / F_MIN) / log10(F_MAX / F_MIN)).toFloat()
    return t.coerceIn(0f, 1f)
}

/**
 * 真实频谱带：只标记 core `partials` 中实际捕捉到的峰，并显示一位小数 Hz。
 * 标签交替使用两行且在边缘内收，避免密集峰值彼此完全遮挡。
 */
@Composable
fun SpectrumBand(
    spectrumDb: FloatArray,
    partials: List<Partial>,
    cents: Float?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalLumenColors.current
    val hasSpectrum = spectrumDb.isNotEmpty()
    val h1Color = if (cents != null) tuneColorOf(cents, colors) else colors.accent
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val interactionModifier = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .semantics { contentDescription = "打开专业频谱分析" }
            .clickable(onClick = onClick)
    }

    Canvas(
        modifier = modifier
            .then(interactionModifier)
            .fillMaxWidth()
            .height(96.dp),
    ) {
        val width = size.width
        val height = size.height
        val oneDp = 1.dp.toPx()
        if (!hasSpectrum) {
            drawLine(
                color = colors.inkFaint.copy(alpha = 0.5f),
                start = Offset(0f, height * 0.9f),
                end = Offset(width, height * 0.9f),
                strokeWidth = 1.5f * oneDp,
            )
            return@Canvas
        }

        val count = minOf(BINS, spectrumDb.size)
        val barWidth = width / BINS
        for (index in 0 until count) {
            val fraction = dbToFrac(spectrumDb[index])
            val barHeight = fraction * height * 0.72f
            drawRect(
                color = colors.accent.copy(alpha = 0.25f + 0.45f * fraction),
                topLeft = Offset(
                    index * barWidth + barWidth * 0.15f,
                    height * 0.94f - barHeight,
                ),
                size = androidx.compose.ui.geometry.Size(
                    barWidth * 0.7f,
                    barHeight.coerceAtLeast(1f),
                ),
            )
        }

        textPaint.textSize = 10.dp.toPx()
        textPaint.textAlign = Paint.Align.CENTER
        partials.forEachIndexed { index, partial ->
            if (partial.freqHz !in F_MIN..F_MAX) return@forEachIndexed
            val x = (freqToX(partial.freqHz) * width).coerceIn(34.dp.toPx(), width - 34.dp.toPx())
            val harmonic = partial.harmonicIndex.toInt()
            val isFundamental = harmonic == 1
            val color = if (isFundamental) h1Color else colors.inkSecondary
            val labelY = if (index % 2 == 0) 11.dp.toPx() else 25.dp.toPx()
            val flagTop = labelY + 3.dp.toPx()
            val flagBottom = height * 0.34f

            drawLine(
                color = color,
                start = Offset(x, flagTop),
                end = Offset(x, flagBottom),
                strokeWidth = if (isFundamental) 2.5f * oneDp else 1.5f * oneDp,
            )
            if (harmonic == 0) {
                drawCircle(
                    color = color,
                    radius = 4.dp.toPx(),
                    center = Offset(x, flagBottom),
                    style = Stroke(width = 1.5f * oneDp),
                )
            } else {
                val triangle = Path().apply {
                    moveTo(x, flagTop)
                    lineTo(x + 6.dp.toPx(), flagTop + 4.dp.toPx())
                    lineTo(x, flagTop + 8.dp.toPx())
                    close()
                }
                drawPath(triangle, color)
            }

            val prefix = when {
                harmonic > 0 -> "H$harmonic"
                partial.noteName.isNotBlank() -> partial.noteName.replace("#", "♯")
                else -> "峰"
            }
            textPaint.color = color.toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                "$prefix ${String.format(Locale.US, "%.1f", partial.freqHz)} Hz",
                x,
                labelY,
                textPaint,
            )
        }
    }
}
