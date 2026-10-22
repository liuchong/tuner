package com.liuchong.tunar.ui.practice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liuchong.tunar.corebinding.TunarCore
import com.liuchong.tunar.audio.CaptureHub
import com.liuchong.tunar.ui.common.AudioPermissionGate
import com.liuchong.tunar.ui.instrument.FigureInk
import com.liuchong.tunar.ui.instrument.WindFigureKind
import com.liuchong.tunar.ui.instrument.WindTubeFigure
import com.liuchong.tunar.ui.theme.LocalLumenColors
import uniffi.tunar_core.*
import java.util.Locale

private fun number(value: Double?, suffix: String = "", digits: Int = 1): String =
    value?.let { String.format(Locale.ROOT, "%.${digits}f%s", it, suffix) } ?: "—"

/** 三种任务共用一个Rust游标；屏幕仅做排版、导航与系统文件交互。 */
@Composable
fun PracticeWorkspaceScreen(model: PracticeWorkspaceViewModel, onBack: () -> Unit, onAnalysis: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    var exportCsv by remember { mutableStateOf(false) }
    var exportSelected by remember { mutableStateOf(false) }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { model.exportFile(resolver, it, false, false) }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { model.exportFile(resolver, it, exportCsv, exportSelected) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { model.importFile(resolver, it) } }
    var displayRange by remember { mutableDoubleStateOf(50.0) }
    var clear by remember { mutableStateOf(false) }
    var fileMenu by remember { mutableStateOf(false) }
    // 历史和文件操作不依赖麦克风授权；只有实时采集通过权限门。
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("练音工作区", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
            Box {
                TextButton(onClick = { fileMenu = true }) { Text("文件") }
                DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                    DropdownMenuItem(text = { Text("打开测量 JSON") }, onClick = { fileMenu = false; open.launch(arrayOf("application/json", "application/octet-stream")) })
                    DropdownMenuItem(text = { Text("保存完整测量") }, onClick = { fileMenu = false; saveJson.launch("TUNAR-measurement.json") })
                    DropdownMenuItem(text = { Text("导出全部 CSV") }, onClick = { fileMenu = false; exportCsv = true; exportSelected = false; saveCsv.launch("TUNAR-measurement.csv") })
                    DropdownMenuItem(text = { Text("导出选区 CSV") }, enabled = state.trace.size > 1, onClick = { fileMenu = false; exportCsv = true; exportSelected = true; saveCsv.launch("TUNAR-selection.csv") })
                    DropdownMenuItem(text = { Text("实时专业分析工具") }, onClick = { fileMenu = false; onAnalysis() })
                    DropdownMenuItem(text = { Text("清空本次测量") }, onClick = { fileMenu = false; clear = true })
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("调音", "练音", "分析").forEach { task -> FilterChip(selected = state.task == task, onClick = { model.task(task) }, label = { Text(task) }) }
        }
        SessionControls(state, model)
        state.fileMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.status.reviewId == null) {
                AudioPermissionGate(onGranted = model::acquire) {
                    if (state.captureRunning) Text("实时采集 · 输入电平 dBFS", style = MaterialTheme.typography.labelSmall)
                    else Row { Text("采集未就绪，请检查输入设备"); TextButton(onClick = model::retryCapture) { Text("重试") } }
                }
            }
            TargetControls(state, model)
            if (state.task != "分析") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50.0, 100.0).forEach { range -> FilterChip(selected = displayRange == range, onClick = { displayRange = range }, label = { Text("量程 ±${range.toInt()}¢") }) }
            }
            PitchReadout(state, displayRange)
            if (state.task == "练音") {
                val trace = if (state.status.reviewId == null) state.liveTrace else state.trace
                PracticeCard("音准轨迹 · cents / 秒") { TracePlot(trace, state.displayConfig.toleranceCents, state.point?.timeSeconds, false, displayRange) }
                PracticeCard("输入电平 · dBFS / 秒") { TracePlot(trace, 0.0, state.point?.timeSeconds, true) }
            }
            if (state.task == "分析") AnalysisPanel(state, model)
            TargetFingering(state.displayConfig)
            SnapshotPanel(state, model)
            if (state.task != "调音") StatisticsPanel(state, model)
        }
    }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清空本次测量？") },
        text = { Text("清除时间轴和统计，独立命名样本继续保留。") },
        confirmButton = { TextButton(onClick = { model.clear(); clear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("取消") } })
    state.error?.let { error -> AlertDialog(onDismissRequest = { model.error(null) }, title = { Text("操作未完成") }, text = { Text(error) },
        confirmButton = { TextButton(onClick = { model.error(null) }) { Text("知道了") } }) }
}

@Composable
private fun SessionControls(state: PracticeUiState, model: PracticeWorkspaceViewModel) {
    val index = state.trace.indexOfFirst { it.id == state.status.reviewId }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = state.status.state != PracticeRecordingState.COMPLETE && (state.captureRunning || state.status.state == PracticeRecordingState.RECORDING), onClick = {
                if (state.status.state == PracticeRecordingState.RECORDING) model.pause() else model.start()
            }) { Text(if (state.status.state == PracticeRecordingState.RECORDING) "暂停记录" else "开始 / 继续") }
            TextButton(onClick = model::finish, enabled = state.status.count > 0u) { Text("完成") }
            TextButton(onClick = model::live, enabled = state.status.reviewId != null) { Text("返回实时") }
            TextButton(onClick = { state.trace.getOrNull(if (index < 0) state.trace.lastIndex else index - 1)?.let { model.review(it.id) } }, enabled = state.trace.isNotEmpty()) { Text("上一帧") }
            TextButton(onClick = { state.trace.getOrNull(index + 1)?.let { model.review(it.id) } }, enabled = index >= 0 && index < state.trace.lastIndex) { Text("下一帧") }
        }
        Text("${if (state.status.reviewId == null) "实时" else "回看"} · ${state.status.count} 点 · ${PracticePresentation.time(state.point?.timeSeconds ?: 0.0)} · ${state.status.reason}", style = MaterialTheme.typography.labelSmall)
        if (state.trace.size > 1) Slider(value = if (index < 0) state.trace.lastIndex.toFloat() else index.toFloat(),
            onValueChange = { state.trace.getOrNull(it.toInt())?.let { point -> model.review(point.id) } }, valueRange = 0f..state.trace.lastIndex.toFloat())
    }
}

@Composable
private fun TargetControls(state: PracticeUiState, model: PracticeWorkspaceViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val config by CaptureHub.config.collectAsStateWithLifecycle()
    val tones = remember(config.a4Hz, config.temperament) { TunarCore.referenceTones(config) }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(if (state.targetFrequency == null) "选择锁定目标" else "目标 ${number(state.targetFrequency, " Hz")}") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    tones.forEach { tone -> DropdownMenuItem(text = { Text("${tone.noteName} · ${number(tone.frequencyHz, " Hz")}") }, onClick = { model.lockReference(tone.stepFromA4); expanded = false }) }
                }
            }
            TextButton(onClick = model::unlock, enabled = state.targetFrequency != null) { Text("自动目标") }
            TextButton(onClick = model::toggleReference, enabled = state.targetFrequency != null && state.status.reviewId == null) { Text(if (state.referencePlaying) "停止参考音" else "播放参考音") }
        }
        if (state.task == "练音") Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2.0, 5.0, 10.0, 20.0).forEach { value -> FilterChip(selected = state.config.toleranceCents == value, onClick = { model.tolerance(value) }, label = { Text("±${value.toInt()}¢") }) }
        }
    }
}

@Composable
private fun PitchReadout(state: PracticeUiState, range: Double) {
    val point = state.point
    val cents = point?.cents
    val accent = MaterialTheme.colorScheme.primary
    PracticeCard(if (state.status.reviewId == null) "实时读数" else "历史读数 · 使用当时配置") {
        Text(point?.targetName?.ifEmpty { "等待信号" } ?: "等待信号", style = if (state.task == "分析") MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayLarge)
        Text("${number(cents, " ¢", 2)} · ${number(point?.frame?.tuner?.freqHz, " Hz", 2)}", style = MaterialTheme.typography.headlineSmall)
        if (state.task != "分析") Canvas(Modifier.fillMaxWidth().height(44.dp).semantics { contentDescription = "真实偏差 ${number(cents)} 音分，表盘显示范围正负${range.toInt()}音分" }) {
            val y = size.height / 2
            drawLine(Color.Gray, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            drawLine(Color.Gray, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
            cents?.let { val x = ((it.coerceIn(-range, range) + range) / (range * 2) * size.width).toFloat(); drawCircle(accent, 5.dp.toPx(), Offset(x, y)) }
        }
        if (cents != null && kotlin.math.abs(cents) > range) Text(if (cents < 0) "← 偏低，超出表盘范围" else "偏高，超出表盘范围 →")
        Text("输入 ${number(point?.frame?.inputLevelDbfs?.toDouble(), " dBFS")} · A4 ${number(state.displayConfig.a4Hz, " Hz")} · ${state.displayConfig.temperament}平均律", style = MaterialTheme.typography.bodySmall)
        if (point?.frame?.isHeld == true) Text("保持帧 · 不计入演奏统计")
        if (point?.referenceActive == true) Text("参考音影响区间 · 不计入演奏统计")
    }
}

@Composable
private fun PracticeCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall); content()
    } }
}

@Composable
private fun TracePlot(points: List<PracticeTracePoint>, tolerance: Double, cursor: Double?, level: Boolean, range: Double = 50.0) {
    val accent = MaterialTheme.colorScheme.primary
    Text(if (level) "0 / −40 / −80 dBFS" else "+${range.toInt()} / 0 / −${range.toInt()}¢ · 容差 ±${tolerance.toInt()}¢", style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(if (level) 80.dp else 140.dp).semantics { contentDescription = if (level) "独立输入电平时间轴" else "真实音分时间轴，静音和中断断线" }) {
        val start = points.firstOrNull()?.timeSeconds ?: 0.0
        val end = maxOf(start + 0.1, points.lastOrNull()?.timeSeconds ?: 12.0)
        fun x(time: Double) = ((time - start) / (end - start) * size.width).toFloat()
        fun y(value: Double) = (if (level) -value.coerceIn(-80.0, 0.0) / 80 else (range - value.coerceIn(-range, range)) / (range * 2)).toFloat() * size.height
        if (!level) drawRect(Color.Green.copy(alpha = 0.10f), Offset(0f, y(tolerance)), androidx.compose.ui.geometry.Size(size.width, y(-tolerance) - y(tolerance)))
        listOf(0f, .5f, 1f).forEach { drawLine(Color.Gray.copy(alpha = .3f), Offset(0f, size.height * it), Offset(size.width, size.height * it)) }
        val path = Path(); var previous: PracticeTracePoint? = null
        points.forEach { point ->
            val value = if (level) point.inputDbfs.toDouble() else point.cents
            if (value == null || (!level && !point.valid)) previous = null
            else {
                val old = previous
                if (old != null && old.segmentId == point.segmentId && point.timeSeconds - old.timeSeconds <= .250001) path.lineTo(x(point.timeSeconds), y(value))
                else path.moveTo(x(point.timeSeconds), y(value))
                previous = point
            }
        }
        drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        cursor?.takeIf { it >= start && it <= end }?.let { drawLine(Color.Gray, Offset(x(it), 0f), Offset(x(it), size.height), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(number(points.firstOrNull()?.timeSeconds, " s")); Text(number(points.lastOrNull()?.timeSeconds, " s")) }
}

@Composable
private fun AnalysisPanel(state: PracticeUiState, model: PracticeWorkspaceViewModel) {
    PracticeCard("频谱比较 · 实线当前 / 虚线基准") {
        Row { TextButton(enabled = state.status.baselineId != null, onClick = { model.normalize(!state.normalized) }) { Text(if (state.normalized && state.status.baselineId != null) "相对峰值" else "绝对 dBFS") }; TextButton(onClick = { model.wide(!state.wide) }) { Text(if (state.wide) "宽频" else "主频段") } }
        state.comparisonError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val bins = state.comparison?.current ?: if (state.wide) state.point?.frame?.wideSpectrumDb.orEmpty() else state.point?.frame?.spectrumDb.orEmpty()
        SpectrumPlot(bins, state.comparison?.reference)
        Text(if (state.wide) "20 Hz — ${number(state.point?.frame?.wideSpectrumMaxHz, " Hz")} · 对数坐标" else "60 Hz — 2400 Hz · 对数坐标", style = MaterialTheme.typography.labelSmall)
        Text(if (state.normalized && state.comparison != null) "纵轴相对峰值 −80…0 dB" else "纵轴 −80…0 dBFS", style = MaterialTheme.typography.labelSmall)
        Text("当前分析窗口包络（−1…+1）")
        state.point?.frame?.let { Envelope(it.waveformMin, it.waveformMax) }
        state.snapshots.firstOrNull { it.id == state.status.baselineId }?.point?.frame?.let { Text("基准分析窗口包络（−1…+1）"); Envelope(it.waveformMin, it.waveformMax) }
        state.point?.frame?.partials?.forEach { partial -> Text("${partial.noteName}  ${number(partial.freqHz, " Hz")}  ${number(partial.magnitudeDb.toDouble(), " dBFS")}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SpectrumPlot(current: List<Float>, reference: List<Float>?) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        listOfNotNull(current, reference).forEachIndexed { series, values ->
            if (values.size > 1) {
                val path = Path()
                values.forEachIndexed { index, db -> val x = index.toFloat() / (values.size - 1) * size.width; val y = -db.coerceIn(-80f, 0f) / 80 * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                drawPath(path, if (series == 0) accent else Color.Gray, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), pathEffect = if (series == 0) null else PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
            }
        }
    }
}

@Composable
private fun Envelope(minimum: List<Float>, maximum: List<Float>) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val count = minOf(minimum.size, maximum.size)
        if (count > 1) for (index in 0 until count) {
            val x = index.toFloat() / (count - 1) * size.width
            drawLine(accent, Offset(x, (1 - maximum[index]) / 2 * size.height), Offset(x, (1 - minimum[index]) / 2 * size.height))
        }
    }
}

@Composable
private fun SnapshotPanel(state: PracticeUiState, model: PracticeWorkspaceViewModel) {
    var name by remember { mutableStateOf("") }
    PracticeCard("命名比较样本 · ${state.snapshots.size}/8") {
        OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("样本名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { model.snapshot(name); name = "" }, enabled = name.isNotBlank() && state.snapshots.size < 8 && state.point != null) { Text("保存当前帧") }
        state.snapshots.forEach { snapshot -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { model.baseline(if (state.status.baselineId == snapshot.id) null else snapshot.id) }) { Text("${if (state.status.baselineId == snapshot.id) "✓ " else ""}${snapshot.name}") }
            TextButton(onClick = { model.removeSnapshot(snapshot.id) }) { Text("删除") }
        } }
    }
}

@Composable
private fun StatisticsPanel(state: PracticeUiState, model: PracticeWorkspaceViewModel) {
    var from by remember { mutableFloatStateOf(0f) }; var to by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(state.status.selectionStartId, state.status.selectionEndId, state.trace.size) {
        if (state.trace.size > 1) {
            val start = state.trace.indexOfFirst { it.id == state.status.selectionStartId }
            val end = state.trace.indexOfFirst { it.id == state.status.selectionEndId }
            if (start >= 0 && end >= 0) { from = start.toFloat() / state.trace.lastIndex; to = end.toFloat() / state.trace.lastIndex }
        }
    }
    PracticeCard("选区统计 · 按有效时间加权") {
        if (state.trace.size < 2) Text("记录后选择起止点；有效时间不足1秒或5点时不评分。") else {
            val last = state.trace.lastIndex
            val a = state.trace[(from * last).toInt().coerceIn(0, last)]
            val b = state.trace[(to * last).toInt().coerceIn(0, last)]
            Text("${PracticePresentation.time(a.timeSeconds)} — ${PracticePresentation.time(b.timeSeconds)}")
            Slider(value = from, onValueChange = { from = it.coerceAtMost(to) }, onValueChangeFinished = { model.summarize(a.id, b.id) })
            Slider(value = to, onValueChange = { to = it.coerceAtLeast(from) }, onValueChangeFinished = { model.summarize(a.id, b.id) })
            TextButton(onClick = { model.summarize(a.id, b.id) }) { Text("统计此选区") }
        }
        state.summaries.forEach { summary ->
            Text("段 ${summary.segmentId} · 覆盖 ${number(summary.coverage * 100, "%")} · 有效 ${number(summary.validSeconds, " s")}")
            Text(if (summary.insufficient) "有效数据不足，不评分" else "准音率 ${number(summary.accuracy?.times(100), "%")} · 中位 ${number(summary.medianCents, "¢")} · P10/P90 ${number(summary.lowCents)} / ${number(summary.highCents)}¢")
        }
    }
}

@Composable
private fun TargetFingering(config: PracticeConfig) {
    val variant = remember(config.instrumentId, config.variantId) { listWindVariants(config.instrumentId).firstOrNull { it.id == config.variantId } }
    val fingering = remember(config.variantId, config.fingeringId) {
        variant?.let { windFingeringChart(it.id, it.defaultTongyinDegree, (if (it.supportsChromatic) FingeringScope.CHROMATIC else FingeringScope.SCALE))?.notes?.firstOrNull { note -> note.fingeringId == config.fingeringId } }
    }
    if (variant != null && fingering != null) PracticeCard("目标指法示范 · ${variant.displayName}") {
        Text(fingering.label)
        WindTubeFigure(WindFigureKind.of(config.instrumentId), fingering.holes, variant.backHoleCount.toInt(), true,
            FigureInk.from(LocalLumenColors.current), Modifier.fillMaxWidth().height(180.dp))
        Text("示范所选目标指法，不代表实际按法识别。", style = MaterialTheme.typography.labelSmall)
    }
}
