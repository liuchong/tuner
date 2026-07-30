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
import androidx.compose.runtime.mutableStateOf
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
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import uniffi.tuner_core.Partial
import uniffi.tuner_core.SignalState

private enum class ProfessionalViewMode {
    SPECTRUM,
    PITCH,
    WAVEFORM,
}

private enum class SpectrumRange {
    MUSICAL,
    WIDE,
}

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
        var viewMode by remember { mutableStateOf(ProfessionalViewMode.SPECTRUM) }
        var spectrumRange by remember { mutableStateOf(SpectrumRange.MUSICAL) }

        LaunchedEffect(state.samplePosition) {
            historyViewModel.acceptAnalysis(
                spectrumDb = state.spectrumDb.asList(),
                wideSpectrumDb = state.wideSpectrumDb.asList(),
                waveformMin = state.waveformMin.asList(),
                waveformMax = state.waveformMax.asList(),
                samplePosition = state.samplePosition,
                sampleRateHz = state.sampleRateHz,
                trackingMidi = reading
                    ?.takeIf {
                        state.signalState == SignalState.TRACKING && !state.isHeld
                    }
                    ?.midi
                    ?.toFloat(),
            )
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
                            "实时频谱 · 音高轨迹 · 波形",
                            style = TunerTypography.caption,
                            color = colors.inkSecondary,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            onClick = historyViewModel::resetPeakHold,
                            shape = RoundedCornerShape(50),
                            color = colors.bgSurface,
                        ) {
                            Text(
                                "重置峰值",
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 9.dp,
                                ),
                                style = TunerTypography.caption,
                                color = colors.inkSecondary,
                                maxLines = 1,
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
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 9.dp,
                                ),
                                style = TunerTypography.caption,
                                color = if (paused) colors.accent else colors.inkSecondary,
                                maxLines = 1,
                            )
                        }
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
                                when (viewMode) {
                                    ProfessionalViewMode.SPECTRUM -> "纵轴 dBFS"
                                    ProfessionalViewMode.PITCH -> "纵轴 MIDI 音高"
                                    ProfessionalViewMode.WAVEFORM -> "纵轴振幅"
                                },
                                style = TunerTypography.caption,
                                color = colors.inkFaint,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        CompactSelector(
                            choices = listOf(
                                ProfessionalViewMode.SPECTRUM to "频谱",
                                ProfessionalViewMode.PITCH to "音高轨迹",
                                ProfessionalViewMode.WAVEFORM to "波形",
                            ),
                            selected = viewMode,
                            onSelected = { viewMode = it },
                        )
                        if (viewMode == ProfessionalViewMode.SPECTRUM) {
                            Spacer(Modifier.height(6.dp))
                            CompactSelector(
                                choices = listOf(
                                    SpectrumRange.MUSICAL to "乐音",
                                    SpectrumRange.WIDE to "全频",
                                ),
                                selected = spectrumRange,
                                onSelected = { spectrumRange = it },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        when (viewMode) {
                            ProfessionalViewMode.SPECTRUM -> {
                                val wide = spectrumRange == SpectrumRange.WIDE
                                val minHz = if (wide) {
                                    PROFESSIONAL_WIDE_SPECTRUM_MIN_HZ
                                } else {
                                    PROFESSIONAL_SPECTRUM_MIN_HZ
                                }
                                val maxHz = if (wide) {
                                    state.wideSpectrumMaxHz
                                } else {
                                    PROFESSIONAL_SPECTRUM_MAX_HZ
                                }
                                val ticks = if (wide) {
                                    professionalWideFrequencyTicks(maxHz)
                                } else {
                                    professionalFrequencyTicks()
                                }
                                Row {
                                    DbAxis(
                                        modifier = Modifier
                                            .width(58.dp)
                                            .height(280.dp),
                                    )
                                    SpectrumLineChart(
                                        live = if (wide) {
                                            historyState.currentWideSpectrum
                                        } else {
                                            historyState.currentSpectrum
                                        },
                                        peak = if (wide) {
                                            historyState.peakWideSpectrum
                                        } else {
                                            historyState.peakSpectrum
                                        },
                                        partials = state.partials,
                                        minHz = minHz,
                                        maxHz = maxHz,
                                        ticks = ticks,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(280.dp),
                                    )
                                }
                                FrequencyAxis(
                                    ticks = ticks,
                                    modifier = Modifier.padding(start = 58.dp),
                                )
                            }

                            ProfessionalViewMode.PITCH -> {
                                val bounds = pitchDisplayBounds(
                                    historyState.pitchTrace.map { it.midi },
                                )
                                Row {
                                    PitchAxis(
                                        bounds = bounds,
                                        modifier = Modifier
                                            .width(58.dp)
                                            .height(280.dp),
                                    )
                                    PitchTraceChart(
                                        trace = historyState.pitchTrace,
                                        bounds = bounds,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(280.dp),
                                    )
                                }
                                TraceTimeAxis(Modifier.padding(start = 58.dp))
                            }

                            ProfessionalViewMode.WAVEFORM -> {
                                Row {
                                    AmplitudeAxis(
                                        modifier = Modifier
                                            .width(58.dp)
                                            .height(280.dp),
                                    )
                                    WaveformChart(
                                        minimum = historyState.waveformMin,
                                        maximum = historyState.waveformMax,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(280.dp),
                                    )
                                }
                                WaveformTimeAxis(
                                    sampleRateHz = state.sampleRateHz,
                                    modifier = Modifier.padding(start = 58.dp),
                                )
                            }
                        }
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
                                modifier = Modifier.padding(start = 44.dp, end = 48.dp),
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
    minHz: Double,
    maxHz: Double,
    ticks: List<SpectrumAxisTick>,
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
            ticks.forEach { tick ->
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
                if (partial.freqHz !in minHz..maxHz) return@forEach
                val x = frequencyFraction(partial.freqHz, minHz, maxHz).toFloat() * size.width
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
            val frequency = minHz *
                (maxHz / minHz)
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
private fun <T> CompactSelector(
    choices: List<Pair<T, String>>,
    selected: T,
    onSelected: (T) -> Unit,
) {
    val colors = LocalLumenColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        choices.forEach { (value, label) ->
            Surface(
                onClick = { onSelected(value) },
                modifier = Modifier
                    .weight(1f)
                    .height(30.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (selected == value) {
                    colors.accent.copy(alpha = 0.16f)
                } else {
                    colors.bgCanvas.copy(alpha = 0.65f)
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = TunerTypography.caption,
                        color = if (selected == value) colors.accent else colors.inkSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PitchTraceChart(
    trace: List<PitchTracePoint>,
    bounds: PitchDisplayBounds,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    Canvas(modifier.background(colors.bgCanvas)) {
        repeat(5) { index ->
            val y = size.height * index / 4f
            drawLine(colors.lineSubtle, Offset(0f, y), Offset(size.width, y), 1f)
        }
        repeat(5) { index ->
            val x = size.width * index / 4f
            drawLine(colors.lineSubtle, Offset(x, 0f), Offset(x, size.height), 1f)
        }
        if (trace.isEmpty()) return@Canvas
        val latest = trace.last().timeSeconds
        val earliest = latest - 12.0
        trace.zipWithNext().forEach { (left, right) ->
            if (left.segment != right.segment) return@forEach
            val x1 = ((left.timeSeconds - earliest) / 12.0).toFloat() * size.width
            val x2 = ((right.timeSeconds - earliest) / 12.0).toFloat() * size.width
            val y1 = (1.0 - (left.midi - bounds.minimum) / (bounds.maximum - bounds.minimum))
                .toFloat() * size.height
            val y2 = (1.0 - (right.midi - bounds.minimum) / (bounds.maximum - bounds.minimum))
                .toFloat() * size.height
            drawLine(
                color = colors.accent,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = 2.dp.toPx(),
            )
        }
        trace.lastOrNull()?.let { point ->
            val x = ((point.timeSeconds - earliest) / 12.0).toFloat() * size.width
            val y = (1.0 - (point.midi - bounds.minimum) / (bounds.maximum - bounds.minimum))
                .toFloat() * size.height
            drawCircle(colors.atmoAccent, 4.dp.toPx(), Offset(x, y))
        }
    }
}

@Composable
private fun WaveformChart(
    minimum: FloatArray,
    maximum: FloatArray,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    Canvas(modifier.background(colors.bgCanvas)) {
        repeat(5) { index ->
            val y = size.height * index / 4f
            drawLine(colors.lineSubtle, Offset(0f, y), Offset(size.width, y), 1f)
        }
        repeat(5) { index ->
            val x = size.width * index / 4f
            drawLine(colors.lineSubtle, Offset(x, 0f), Offset(x, size.height), 1f)
        }
        val count = min(minimum.size, maximum.size)
        if (count < 2) return@Canvas
        val envelope = Path()
        for (index in 0 until count) {
            val x = size.width * index / (count - 1f)
            val y = size.height * (0.5f - maximum[index].coerceIn(-1f, 1f) * 0.45f)
            if (index == 0) envelope.moveTo(x, y) else envelope.lineTo(x, y)
        }
        for (index in count - 1 downTo 0) {
            val x = size.width * index / (count - 1f)
            val y = size.height * (0.5f - minimum[index].coerceIn(-1f, 1f) * 0.45f)
            envelope.lineTo(x, y)
        }
        envelope.close()
        drawPath(
            envelope,
            brush = Brush.verticalGradient(
                listOf(
                    colors.atmoAccent.copy(alpha = 0.7f),
                    colors.accent.copy(alpha = 0.18f),
                ),
            ),
        )
        drawLine(
            colors.accent.copy(alpha = 0.8f),
            Offset(0f, size.height / 2f),
            Offset(size.width, size.height / 2f),
            1f,
        )
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
private fun FrequencyAxis(
    ticks: List<SpectrumAxisTick> = professionalFrequencyTicks(),
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    AxisLabels(
        ticks = ticks,
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp),
        color = colors.inkFaint,
        horizontal = true,
    )
}

@Composable
private fun PitchAxis(
    bounds: PitchDisplayBounds,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    val center = ((bounds.minimum + bounds.maximum) / 2.0).roundToInt()
    AxisLabels(
        ticks = listOf(
            SpectrumAxisTick(0f, "${bounds.maximum.roundToInt()}"),
            SpectrumAxisTick(0.5f, "$center"),
            SpectrumAxisTick(1f, "${bounds.minimum.roundToInt()}"),
        ),
        modifier = modifier,
        color = colors.inkFaint,
        horizontal = false,
    )
}

@Composable
private fun AmplitudeAxis(modifier: Modifier = Modifier) {
    val colors = LocalLumenColors.current
    AxisLabels(
        ticks = listOf(
            SpectrumAxisTick(0.05f, "+1"),
            SpectrumAxisTick(0.5f, "0"),
            SpectrumAxisTick(0.95f, "-1"),
        ),
        modifier = modifier,
        color = colors.inkFaint,
        horizontal = false,
    )
}

@Composable
private fun TraceTimeAxis(modifier: Modifier = Modifier) {
    val colors = LocalLumenColors.current
    AxisLabels(
        ticks = listOf(
            SpectrumAxisTick(0f, "-12秒"),
            SpectrumAxisTick(0.5f, "-6秒"),
            SpectrumAxisTick(1f, "现在"),
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp),
        color = colors.inkFaint,
        horizontal = true,
    )
}

@Composable
private fun WaveformTimeAxis(
    sampleRateHz: Double,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    val durationMs = if (sampleRateHz.isFinite() && sampleRateHz > 0.0) {
        2_048.0 / sampleRateHz * 1_000.0
    } else {
        0.0
    }
    AxisLabels(
        ticks = listOf(
            SpectrumAxisTick(0f, String.format(Locale.US, "-%.0f ms", durationMs)),
            SpectrumAxisTick(0.5f, String.format(Locale.US, "-%.0f ms", durationMs / 2.0)),
            SpectrumAxisTick(1f, "现在"),
        ),
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
