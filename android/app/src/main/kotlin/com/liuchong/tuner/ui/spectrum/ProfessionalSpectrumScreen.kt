package com.liuchong.tuner.ui.spectrum

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuchong.tuner.audio.CaptureHub
import com.liuchong.tuner.ui.common.AudioPermissionGate
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.theme.TunerTypography
import com.liuchong.tuner.ui.tuner.TunerSignal
import com.liuchong.tuner.ui.tuner.TunerViewModel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import uniffi.tuner_core.Partial

/** 独立专业频谱分析页面，共享 CaptureHub，不创建第二路麦克风。 */
@Composable
fun ProfessionalSpectrumScreen(
    viewModel: TunerViewModel = viewModel(initializer = {
        TunerViewModel(CaptureHub)
    }),
    historyViewModel: SpectrumHistory = viewModel(key = "professional-spectrum-history"),
) {
    AudioPermissionGate(onGranted = viewModel::startCapture) {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val colors = LocalLumenColors.current
        val reading = (state.signal as? TunerSignal.Active)?.reading
        val historyState by historyViewModel.state.collectAsStateWithLifecycle()
        val paused = historyState.isPaused

        LaunchedEffect(state.spectrumDb) {
            historyViewModel.accept(state.spectrumDb.asList())
        }
        val peakRows = professionalSpectrumRows(state.partials)
        val metrics = professionalSpectrumMetrics(
            reading = reading,
            inputLevelDbfs = state.inputLevelDbfs,
            partials = state.partials,
            chord = state.chord,
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "专业频谱分析",
                            style = TunerTypography.readoutSolfege,
                            color = colors.inkPrimary,
                        )
                        Text(
                            "横轴频率 · 实时频谱 / 峰值保持",
                            style = TunerTypography.caption,
                            color = colors.inkSecondary,
                        )
                    }
                    Surface(
                        onClick = {
                            historyViewModel.isPaused = !paused
                        },
                        shape = RoundedCornerShape(50),
                        color = if (paused) {
                            colors.accent.copy(alpha = 0.16f)
                        } else {
                            colors.bgSurface
                        },
                    ) {
                        Text(
                            if (paused) "▶ 继续" else "Ⅱ 暂停",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            style = TunerTypography.caption,
                            color = if (paused) colors.accent else colors.inkSecondary,
                        )
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.bgSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                String.format(
                                    Locale.US,
                                    "%s · 输入 %.1f dBFS",
                                    if (paused) "■ 已冻结" else "● 实时刷新",
                                    state.inputLevelDbfs,
                                ),
                                style = TunerTypography.caption,
                                color = if (paused) colors.inkSecondary else colors.accent,
                            )
                            Text(
                                "纵轴 dBFS",
                                style = TunerTypography.caption,
                                color = colors.inkFaint,
                            )
                        }
                        Row {
                            DbAxis(
                                modifier = Modifier
                                    .width(58.dp)
                                    .height(280.dp),
                            )
                            SpectrumLineChart(
                                live = historyState.currentSpectrum,
                                peak = historyState.peakSpectrum,
                                partials = state.partials,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(280.dp),
                            )
                        }
                        FrequencyAxis(Modifier.padding(start = 58.dp))
                    }
                }
            }

            item {
                MetricsGrid(metrics)
            }

            item {
                Column {
                    Text("连续时间图谱", style = TunerTypography.label, color = colors.inkPrimary)
                    Text(
                        "最新声音在顶部 · 约 12 秒历史",
                        style = TunerTypography.caption,
                        color = colors.inkSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = colors.bgSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                TimeAxis(
                                    modifier = Modifier
                                        .width(44.dp)
                                        .height(340.dp),
                                )
                                WaterfallChart(
                                    waterfall = historyState.waterfall,
                                    binCount = historyState.waterfallBinCount,
                                    maxRows = historyState.maxRows,
                                    nextRow = historyState.nextRow,
                                    rowCount = historyState.rowCount,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(340.dp),
                                )
                                HeatLegend(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(340.dp),
                                )
                            }
                            FrequencyAxis(
                                Modifier.padding(start = 44.dp, end = 48.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "时间 ↓",
                                    style = TunerTypography.caption,
                                    color = colors.inkFaint,
                                )
                                Text(
                                    "颜色：信号强度 dBFS",
                                    style = TunerTypography.caption,
                                    color = colors.inkFaint,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text("实际峰值", style = TunerTypography.label, color = colors.inkPrimary)
            }
            items(peakRows, key = { it.id }) { row ->
                val partial = row.partial
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val kind = if (partial.harmonicIndex > 0u) {
                        "H${partial.harmonicIndex}"
                    } else {
                        partial.noteName.ifBlank { "独立峰" }.replace("#", "♯")
                    }
                    Text(kind, modifier = Modifier.weight(1f), color = colors.inkPrimary)
                    Text(
                        String.format(Locale.US, "%.1f Hz", partial.freqHz),
                        modifier = Modifier.weight(1f),
                        color = colors.inkSecondary,
                    )
                    Text(
                        String.format(Locale.US, "%.1f dB", partial.magnitudeDb),
                        color = colors.inkSecondary,
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SpectrumLineChart(
    live: FloatArray,
    peak: FloatArray,
    partials: List<Partial>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    var cursorFraction by remember { mutableFloatStateOf(-1f) }
    val updateCursor: (Float, Float) -> Unit = { x, width ->
        cursorFraction = if (width > 0f) (x / width).coerceIn(0f, 1f) else -1f
    }

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures { point -> updateCursor(point.x, size.width.toFloat()) }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { point -> updateCursor(point.x, size.width.toFloat()) },
                        onDrag = { change, _ ->
                            updateCursor(change.position.x, size.width.toFloat())
                        },
                    )
                },
        ) {
            professionalDbTicks().forEach { tick ->
                val y = size.height * tick.fraction
                drawLine(colors.lineSubtle, Offset(0f, y), Offset(size.width, y), 1f)
            }
            professionalFrequencyTicks().forEach { tick ->
                val x = size.width * tick.fraction
                drawLine(colors.lineSubtle, Offset(x, 0f), Offset(x, size.height), 1f)
            }

            fun pathOf(values: FloatArray): Path {
                val path = Path()
                spectrumTracePoints(values, size.width, size.height)
                    .forEachIndexed { index, point ->
                        if (index == 0) {
                            path.moveTo(point.x, point.y)
                        } else {
                            path.lineTo(point.x, point.y)
                        }
                    }
                return path
            }
            if (peak.size > 1) {
                drawPath(
                    pathOf(peak),
                    colors.atmoAccent.copy(alpha = 0.9f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
                )
            }
            if (live.size > 1) {
                drawPath(
                    pathOf(live),
                    colors.accent,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()),
                )
            }
            partials.forEach { partial ->
                val x = frequencyFraction(partial.freqHz).toFloat() * size.width
                val y = size.height * (
                    1f - (
                        (partial.magnitudeDb - PROFESSIONAL_SPECTRUM_FLOOR_DB) /
                            -PROFESSIONAL_SPECTRUM_FLOOR_DB
                        ).coerceIn(0f, 1f)
                    )
                drawCircle(colors.inkPrimary, radius = 4.dp.toPx(), center = Offset(x, y))
            }
            if (cursorFraction >= 0f) {
                val x = cursorFraction * size.width
                drawLine(colors.inkPrimary, Offset(x, 0f), Offset(x, size.height), 1.5f)
            }
        }

        if (cursorFraction >= 0f && live.isNotEmpty()) {
            val index = (cursorFraction * (live.size - 1)).toInt().coerceIn(live.indices)
            val frequency = PROFESSIONAL_SPECTRUM_MIN_HZ *
                (PROFESSIONAL_SPECTRUM_MAX_HZ / PROFESSIONAL_SPECTRUM_MIN_HZ)
                    .pow(cursorFraction.toDouble())
            val nearest = partials.minByOrNull { abs(ln(it.freqHz / frequency)) }
            val note = nearest
                ?.takeIf { abs(ln(it.freqHz / frequency)) < 0.04 }
                ?.noteName
                ?.ifBlank { null }
                ?.replace("#", "♯")
                ?: "—"
            Text(
                String.format(
                    Locale.US,
                    "游标  %.1f Hz   %.1f dB   %s",
                    frequency,
                    live[index],
                    note,
                ),
                modifier = Modifier.padding(top = 6.dp),
                style = TunerTypography.caption,
                color = colors.accent,
            )
        }
    }
}

@Composable
private fun WaterfallChart(
    waterfall: FloatArray,
    binCount: Int,
    maxRows: Int,
    nextRow: Int,
    rowCount: Int,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    Box(modifier = modifier.background(colors.bgCanvas)) {
        Canvas(Modifier.fillMaxSize()) {
            if (waterfall.isEmpty() || rowCount == 0 || binCount == 0 || maxRows == 0) {
                return@Canvas
            }
            val rowHeight = size.height / maxRows
            val cellWidth = size.width / binCount
            repeat(minOf(rowCount, maxRows)) { rowIndex ->
                val sourceRow = (nextRow - 1 - rowIndex + maxRows) % maxRows
                repeat(binCount) { bin ->
                    val db = waterfall[sourceRow * binCount + bin]
                    drawRect(
                        color = spectrumHeatColor(db, colors.bgCanvas),
                        topLeft = Offset(bin * cellWidth, rowIndex * rowHeight),
                        size = Size(cellWidth + 0.5f, rowHeight + 0.5f),
                    )
                }
            }
            professionalFrequencyTicks().forEach { tick ->
                val x = size.width * tick.fraction
                drawLine(colors.lineSubtle, Offset(x, 0f), Offset(x, size.height), 1f)
            }
            professionalTimeTicks().forEach { tick ->
                val y = size.height * tick.fraction
                drawLine(colors.lineSubtle, Offset(0f, y), Offset(size.width, y), 1f)
            }
        }
    }
}

@Composable
private fun FrequencyAxis(modifier: Modifier = Modifier) {
    val colors = LocalLumenColors.current
    AxisLabels(
        ticks = professionalFrequencyTicks(),
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp),
        color = colors.inkFaint,
        horizontal = true,
    )
}

@Composable
private fun DbAxis(modifier: Modifier = Modifier) {
    val colors = LocalLumenColors.current
    AxisLabels(
        ticks = professionalDbTicks(),
        modifier = modifier,
        color = colors.inkFaint,
        horizontal = false,
    )
}

@Composable
private fun TimeAxis(modifier: Modifier = Modifier) {
    val colors = LocalLumenColors.current
    AxisLabels(
        ticks = professionalTimeTicks(),
        modifier = modifier,
        color = colors.inkFaint,
        horizontal = false,
    )
}

@Composable
private fun AxisLabels(
    ticks: List<SpectrumAxisTick>,
    modifier: Modifier,
    color: Color,
    horizontal: Boolean,
) {
    val paint = remember(color, horizontal) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = if (horizontal) 10.sp.value else 9.sp.value
        }
    }
    Canvas(modifier) {
        paint.textSize = (if (horizontal) 10.sp else 9.sp).toPx()
        drawIntoCanvas { canvas ->
            ticks.forEachIndexed { index, tick ->
                if (horizontal) {
                    paint.textAlign = when (index) {
                        0 -> Paint.Align.LEFT
                        ticks.lastIndex -> Paint.Align.RIGHT
                        else -> Paint.Align.CENTER
                    }
                    canvas.nativeCanvas.drawText(
                        tick.label,
                        size.width * tick.fraction,
                        paint.textSize,
                        paint,
                    )
                } else {
                    paint.textAlign = Paint.Align.LEFT
                    val baseline = (
                        size.height * tick.fraction + paint.textSize * 0.35f
                        ).coerceIn(paint.textSize, size.height)
                    canvas.nativeCanvas.drawText(tick.label, 0f, baseline, paint)
                }
            }
        }
    }
}

@Composable
private fun HeatLegend(modifier: Modifier = Modifier) {
    val colors = LocalLumenColors.current
    Row(modifier = modifier.padding(start = 6.dp)) {
        Canvas(
            Modifier
                .width(10.dp)
                .fillMaxHeight(),
        ) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFFE53935),
                        Color(0xFFFFC857),
                        Color(0xFF26C6DA),
                        Color(0xFF8E5AC7),
                        Color(0xFF3949AB),
                        colors.bgCanvas,
                    ),
                ),
            )
        }
        AxisLabels(
            ticks = professionalDbTicks(),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 3.dp),
            color = colors.inkFaint,
            horizontal = false,
        )
    }
}

private fun spectrumHeatColor(db: Float, background: Color): Color {
    val stops = listOf(
        -80f to background,
        -68f to Color(0xFF3949AB),
        -54f to Color(0xFF8E5AC7),
        -40f to Color(0xFF26C6DA),
        -22f to Color(0xFFFFC857),
        -5f to Color(0xFFE53935),
        0f to Color(0xFFFFF3D0),
    )
    val value = db.coerceIn(-80f, 0f)
    val upperIndex = stops.indexOfFirst { value <= it.first }.coerceAtLeast(1)
    val lower = stops[upperIndex - 1]
    val upper = stops[upperIndex]
    val fraction = ((value - lower.first) / (upper.first - lower.first)).coerceIn(0f, 1f)
    return lerp(lower.second, upper.second, fraction)
}

@Composable
private fun MetricsGrid(metrics: ProfessionalSpectrumMetrics) {
    val rows = listOf(
        listOf(
            "音名" to metrics.note,
            "基频" to metrics.fundamental,
            "音分" to metrics.cents,
        ),
        listOf(
            "输入" to metrics.inputLevel,
            "最强峰" to metrics.strongestPeak,
            "和弦" to metrics.chord,
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (label, value) ->
                    MetricTile(
                        label = label,
                        value = value,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    Surface(
        modifier = modifier.height(52.dp),
        color = colors.bgSurface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                label,
                style = TunerTypography.caption,
                color = colors.inkFaint,
                maxLines = 1,
            )
            Text(
                if (label == "最强峰") value.replace(" · ", "\n") else value,
                style = TunerTypography.caption,
                color = if (label == "和弦") colors.accent else colors.inkPrimary,
                maxLines = if (label == "最强峰") 2 else 1,
            )
        }
    }
}
