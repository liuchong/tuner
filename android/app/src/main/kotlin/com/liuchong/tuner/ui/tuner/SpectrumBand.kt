package com.liuchong.tuner.ui.tuner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.theme.tuneColorOf
import uniffi.tuner_core.Partial
import kotlin.math.log10

/** 频谱轴范围（与 core spectrum.rs 一致：60–2400Hz 对数 64 bin）。 */
private const val F_MIN = 60.0
private const val F_MAX = 2400.0
private const val BINS = 64
private const val DB_MIN = -80f
private const val DB_MAX = -10f

/** dB 范围映射高度。 */
private fun dbToFrac(db: Float): Float =
    ((db - DB_MIN) / (DB_MAX - DB_MIN)).coerceIn(0f, 1f)

/** 频率 → x 坐标比例（对数轴）。 */
private fun freqToX(f: Double): Float {
    val t = (log10(f / F_MIN) / log10(F_MAX / F_MIN)).toFloat()
    return t.coerceIn(0f, 1f)
}

/**
 * 频谱分析带（design-system v4 §6.4）：64 柱对数频谱（真实 FFT 数据，禁止模拟），
 * H1–H5 泛音旗标（H1 语义色）、非泛音显著峰空心圆点；无信号灰显平线。
 */
@Composable
fun SpectrumBand(
    spectrumDb: FloatArray,
    partials: List<Partial>,
    fundamentalHz: Double?,
    cents: Float?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    val hasSignal = spectrumDb.isNotEmpty()
    val accentColor = if (cents != null) tuneColorOf(cents, colors) else colors.accent

    Canvas(modifier = modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width
        val h = size.height
        val dp = 1.dp.toPx()
        if (!hasSignal) {
            // 平线灰显
            drawLine(
                color = colors.inkFaint.copy(alpha = 0.5f),
                start = Offset(0f, h * 0.9f),
                end = Offset(w, h * 0.9f),
                strokeWidth = 1.5f * dp,
            )
            return@Canvas
        }
        val barW = w / BINS
        // 64 柱
        for (i in 0 until BINS) {
            val frac = dbToFrac(spectrumDb[i])
            val barH = frac * h * 0.82f
            drawRect(
                color = colors.accent.copy(alpha = 0.25f + 0.45f * frac),
                topLeft = Offset(i * barW + barW * 0.15f, h * 0.9f - barH),
                size = androidx.compose.ui.geometry.Size(barW * 0.7f, barH.coerceAtLeast(1f)),
            )
        }
        // H1–H5 泛音旗标
        fundamentalHz?.let { f0 ->
            for (harm in 1..5) {
                val f = f0 * harm
                if (f > F_MAX) break
                val x = freqToX(f) * w
                val isH1 = harm == 1
                val color = if (isH1) accentColor else colors.inkSecondary
                // 旗标：竖线 + 小三角
                drawLine(
                    color = color,
                    start = Offset(x, 0f),
                    end = Offset(x, h * 0.14f),
                    strokeWidth = if (isH1) 2.5f * dp else 1.5f * dp,
                )
                val tri = androidx.compose.ui.graphics.Path().apply {
                    moveTo(x, 0f)
                    lineTo(x + 6f * dp, 4f * dp)
                    lineTo(x, 8f * dp)
                    close()
                }
                drawPath(tri, color = color)
            }
        }
        // 非泛音显著峰：空心圆点
        for (p in partials) {
            if (p.harmonicIndex.toInt() == 0) {
                val x = freqToX(p.freqHz) * w
                drawCircle(
                    color = colors.inkSecondary,
                    radius = 4f * dp,
                    center = Offset(x, h * 0.22f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f * dp),
                )
            }
        }
    }
}
