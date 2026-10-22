package com.liuchong.tunar.ui.practice

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuchong.tunar.audio.CaptureHub
import com.liuchong.tunar.audio.ReferenceTonePlayer
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.tunar_core.*
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors

/** 展示与导航选择，不包含音高或统计计算。 */
object PracticePresentation {
    fun time(seconds: Double): String = String.format(Locale.ROOT, "%02d:%04.1f", seconds.toInt() / 60, seconds % 60)
    fun isCaptureRoute(route: String?) = route in setOf("tuner", "instrument", "spectrum", "practice")
}

data class PracticeUiState(
    val status: PracticeStatus,
    val config: PracticeConfig,
    val displayConfig: PracticeConfig,
    val point: PracticePoint? = null,
    val trace: List<PracticeTracePoint> = emptyList(),
    val liveTrace: List<PracticeTracePoint> = emptyList(),
    val snapshots: List<PracticeSnapshot> = emptyList(),
    val summaries: List<PracticeSummary> = emptyList(),
    val comparison: PracticeComparison? = null,
    val comparisonError: String? = null,
    val targetFrequency: Double? = null,
    val task: String = "练音",
    val normalized: Boolean = false,
    val wide: Boolean = false,
    val referencePlaying: Boolean = false,
    val error: String? = null,
    val fileMessage: String? = null,
    val captureRunning: Boolean = false,
)

private fun practiceFailureMessage(error: Exception): String = when (error) {
    is PracticeException.InvalidInput -> "目标、容差或测量输入无效，请检查后重试"
    is PracticeException.InvalidFile -> "测量文件无效，当前会话已保留"
    is PracticeException.UnsupportedVersion -> "不支持此测量文件版本，请使用兼容版本打开"
    is PracticeException.Capacity -> "已达到测量容量上限，请先保存或清空本次测量"
    is PracticeException.NoData -> "没有可信测量，请先发声或选择有效历史点"
    is PracticeException.IncompatibleGrid -> "采样率或频率范围不同，不能直接叠加"
    else -> error.localizedMessage?.takeIf { it.isNotBlank() } ?: "操作未完成，请重试"
}

/** Activity/导航作用域所有者。Rust负责会话和所有计算，独立队列负责FFI与文件IO。 */
class PracticeWorkspaceViewModel : ViewModel() {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "Tunar-Practice") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val session = PracticeSession()
    private val _state = MutableStateFlow(PracticeUiState(session.status(), session.config(), session.displayConfig()))
    val state = _state.asStateFlow()
    private var player: ReferenceTonePlayer? = null
    private var copiedCount = 0u
    private var acquired = false
    private var selection: Pair<ULong, ULong>? = null

    init {
        viewModelScope.launch(dispatcher) {
            CaptureHub.running.collect { running ->
                _state.value = _state.value.copy(captureRunning = running)
                if (!running && session.status().state == PracticeRecordingState.RECORDING) command(full = false) { session.pause() }
            }
        }
        viewModelScope.launch(dispatcher) {
            CaptureHub.measurements.collect { packet ->
                if (packet != null) command(full = false) {
                    session.observe(packet.frame, packet.generation, packet.config.a4Hz,
                        packet.config.temperament, packet.referenceActive)
                }
            }
        }
    }
    private fun command(full: Boolean = true, action: () -> Unit) {
        try { action(); refresh(full) }
        catch (error: Exception) { _state.value = _state.value.copy(error = "测量操作失败：${practiceFailureMessage(error)}") }
    }
    private fun submit(action: () -> Unit) { viewModelScope.launch(dispatcher) { command(action = action) } }
    private fun refresh(full: Boolean) {
        val status = session.status()
        val old = _state.value
        val reset = full || status.count < copiedCount
        val page = session.tracePage(if (reset) 0u else copiedCount, 6000u)
        copiedCount = status.count
        var comparison: PracticeComparison? = null
        var compareError: String? = null
        if (status.baselineId != null) {
            try { comparison = session.comparison(old.normalized, old.wide) }
            catch (_: Exception) { compareError = "采样率或频率范围不同，不能直接叠加" }
        }
        val config = session.config()
        val changed = old.config.a4Hz != config.a4Hz || old.config.temperament != config.temperament ||
            old.config.targetStep != config.targetStep || old.config.targetMidi != config.targetMidi || old.config.targetHz != config.targetHz
        if (changed) player?.stop()
        _state.value = old.copy(status = status, config = config, displayConfig = session.displayConfig(),
            point = session.displayPoint(), trace = if (reset) page else old.trace + page,
            liveTrace = session.liveTrace(), snapshots = if (full) session.snapshots() else old.snapshots,
            comparison = comparison, comparisonError = compareError, targetFrequency = session.targetFrequency(),
            referencePlaying = if (changed) false else old.referencePlaying,
            summaries = if (full) status.selectionStartId?.let { start -> status.selectionEndId?.let { session.summarize(start, it) } }.orEmpty() else old.summaries)
        selection = status.selectionStartId?.let { start -> status.selectionEndId?.let { start to it } }
    }
    fun setPlayer(value: ReferenceTonePlayer) { player = value }
    fun retryCapture() { if (!acquired) acquire() else CaptureHub.retry() }
    fun acquire() { if (!acquired) { CaptureHub.acquire(); acquired = true } }
    fun suspend() {
        player?.stop()
        if (acquired) { CaptureHub.release(); acquired = false }
        submit { session.pause(); _state.value = _state.value.copy(referencePlaying = false) }
    }
    fun start() = submit { session.start() }
    fun pause() = submit { session.pause() }
    fun finish() = submit { session.finish() }
    fun clear() = submit { session.clear(); selection = null; _state.value = _state.value.copy(summaries = emptyList()) }
    fun review(id: ULong) = submit { player?.stop(); _state.value = _state.value.copy(referencePlaying = false); session.selectPoint(id) }
    fun live() = submit { session.returnToLive() }
    fun task(value: String) = submit { _state.value = _state.value.copy(task = value) }
    fun lockReference(step: Int) = submit { session.lockReference(step); session.returnToLive() }
    fun lockInstrument(midi: Int, instrument: String, variant: String, fingering: Int?) = submit {
        session.lockInstrument(midi, instrument, variant, fingering)
        session.returnToLive()
    }
    fun unlock() = submit { session.unlockTarget(); session.returnToLive() }
    fun tolerance(value: Double) = submit { session.configure(session.config().copy(toleranceCents = value)) }
    fun snapshot(name: String) = submit { session.addSnapshot(name) }
    fun baseline(id: UInt?) = submit { session.setBaseline(id); _state.value = _state.value.copy(task = "分析") }
    fun removeSnapshot(id: UInt) = submit { session.removeSnapshot(id) }
    fun normalize(value: Boolean) = submit { _state.value = _state.value.copy(normalized = value) }
    fun wide(value: Boolean) = submit { _state.value = _state.value.copy(wide = value) }
    fun summarize(start: ULong, end: ULong) = submit {
        selection = start to end
        _state.value = _state.value.copy(summaries = session.summarize(start, end))
    }
    fun error(message: String?) = submit { _state.value = _state.value.copy(error = message) }
    fun toggleReference() = submit {
        val old = _state.value
        if (old.referencePlaying) player?.stop()
        else if (old.status.reviewId == null) old.targetFrequency?.let { player?.play(it) }
        _state.value = old.copy(referencePlaying = !old.referencePlaying && old.targetFrequency != null && old.status.reviewId == null)
    }
    fun importFile(resolver: ContentResolver, uri: Uri) = submit {
        _state.value = _state.value.copy(fileMessage = null)
        player?.stop()
        val text = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 32 * 1024 * 1024) { "测量文件超过容量上限" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        } ?: throw java.io.IOException("无法打开文件")
        session.importJson(text)
        selection = null
        _state.value = _state.value.copy(referencePlaying = false, summaries = emptyList(), fileMessage = "已打开测量文件")
    }
    fun exportFile(resolver: ContentResolver, uri: Uri, csv: Boolean, selected: Boolean) = submit {
        _state.value = _state.value.copy(fileMessage = null)
        // 完整编码成功后才打开新建文档，不能因非法数据覆盖既有文件。
        val text = if (csv) session.exportCsv(if (selected) selection?.first else null, if (selected) selection?.second else null)
            else session.exportJson()
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)); it.flush() }
            ?: throw java.io.IOException("无法写入文件")
        _state.value = _state.value.copy(fileMessage = "已保存测量文件")
    }
    override fun onCleared() {
        player?.close()
        if (acquired) CaptureHub.release()
        executor.execute { session.destroy() }
        dispatcher.close()
    }
}
