package com.liuchong.tunar.ui.instrument

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuchong.tunar.audio.TunarEventStream
import com.liuchong.tunar.corebinding.TunarCoreApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.tunar_core.InstrumentKind
import uniffi.tunar_core.SignalState
import uniffi.tunar_core.TunarEvent
import kotlin.math.abs

/** 选弦模式：自动（识别最近弦）/ 手动（锁定选中弦）。 */
enum class SelectionMode { AUTO, MANUAL }

/** 琴弦按钮 UI 项。 */
data class StringItemUi(
    val index: Int,
    val noteName: String,
    val midi: Int,
    val freqHz: Double,
    val solfege: String,
    val active: Boolean = false,
    val inTune: Boolean = false,
)

/** 指法音阶列表 UI 项。 */
data class ChartNoteUi(
    val label: String,
    val noteName: String,
    val midi: Int,
    val freqHz: Double,
    val solfege: String,
    val active: Boolean = false,
)

/** 乐器面板 UI 状态。 */
data class InstrumentUiState(
    val instruments: List<uniffi.tunar_core.Instrument> = emptyList(),
    val instrumentId: String = "",
    val instrumentName: String = "",
    val kind: InstrumentKind = InstrumentKind.STRING,
    // 弦乐
    val tunings: List<uniffi.tunar_core.Tuning> = emptyList(),
    val tuningId: String = "",
    val tuningName: String = "",
    val strings: List<StringItemUi> = emptyList(),
    val mode: SelectionMode = SelectionMode.AUTO,
    val manualIndex: Int = 0,
    // 管乐
    val chartGroups: List<String> = emptyList(),
    val chartGroup: String = "",
    val tongyinOptions: List<String> = emptyList(),
    val tongyin: String = "",
    val notes: List<ChartNoteUi> = emptyList(),
    // 共享：相对目标的音分偏差（null = 无信号）
    val centsToTarget: Float? = null,
    val targetNoteName: String? = null,
    /** 与主调音页相同的 core 信号状态与显示强度。 */
    val signalState: SignalState = SignalState.QUIET,
    val displayStrength: Float = 0f,
    val isHeld: Boolean = false,
)

/**
 * 乐器面板 ViewModel（spec-ui §2）。
 * 业务换算（cents/唱名/预设数据）全部走 TunarCoreApi（Rust core）；本类只做选择与映射。
 */
class InstrumentViewModel(
    private val core: TunarCoreApi,
    private val stream: TunarEventStream,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InstrumentUiState())
    val uiState: StateFlow<InstrumentUiState> = _uiState.asStateFlow()

    private var acquired = false

    init {
        viewModelScope.launch {
            stream.events.collect { frame ->
                frame?.let { analysis ->
                    val event = analysis.tuner
                    if (event != null) {
                        onEvent(event)
                        _uiState.update {
                            it.copy(
                                signalState = analysis.signalState,
                                displayStrength = analysis.displayStrength,
                                isHeld = analysis.isHeld,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                centsToTarget = null,
                                targetNoteName = null,
                                strings = it.strings.map { item ->
                                    item.copy(active = false, inTune = false)
                                },
                                notes = it.notes.map { item -> item.copy(active = false) },
                                signalState = analysis.signalState,
                                displayStrength = analysis.displayStrength,
                                isHeld = analysis.isHeld,
                            )
                        }
                    }
                }
            }
        }
        // 初始乐器：持久化值或第一个
        val instruments = core.instruments()
        val savedId = savedState.get<String>(KEY_INSTRUMENT)
        val initial = instruments.firstOrNull { it.id == savedId } ?: instruments.firstOrNull()
        _uiState.update { it.copy(instruments = instruments) }
        if (initial != null) selectInstrument(initial.id)
    }

    fun startCapture() {
        if (acquired) return
        stream.acquire()
        acquired = true
    }

    /** 选择乐器。 */
    fun selectInstrument(id: String) {
        val instrument = _uiState.value.instruments.firstOrNull { it.id == id } ?: return
        savedState[KEY_INSTRUMENT] = id
        when (instrument.kind) {
            InstrumentKind.STRING -> {
                val tunings = core.tunings(id)
                val savedTuning = savedState.get<String>(KEY_TUNING)
                val tuning = tunings.firstOrNull { it.id == savedTuning } ?: tunings.firstOrNull()
                _uiState.update {
                    it.copy(
                        instrumentId = id,
                        instrumentName = instrument.displayName,
                        kind = InstrumentKind.STRING,
                        tunings = tunings,
                        centsToTarget = null,
                        targetNoteName = null,
                    )
                }
                if (tuning != null) selectTuning(tuning.id)
            }
            InstrumentKind.WIND -> {
                val charts = core.fingeringCharts(id)
                // 调性/型号分组：displayName 以 " · " 分隔（"D调曲笛 · 筒音作5"）
                val groups = charts.map { it.displayName.substringBefore(" · ") }.distinct()
                val savedGroup = savedState.get<String>(KEY_GROUP)
                val group = groups.firstOrNull { it == savedGroup } ?: groups.firstOrNull().orEmpty()
                val tongyinOptions = charts
                    .filter { it.displayName.startsWith("$group · ") }
                    .map { it.displayName.substringAfter("筒音作") }
                    .distinct()
                _uiState.update {
                    it.copy(
                        instrumentId = id,
                        instrumentName = instrument.displayName,
                        kind = InstrumentKind.WIND,
                        chartGroups = groups,
                        tongyinOptions = tongyinOptions,
                        centsToTarget = null,
                        targetNoteName = null,
                    )
                }
                selectChart(group, savedState.get<String>(KEY_TONGYIN))
            }
        }
    }

    /** 选择定弦。 */
    fun selectTuning(tuningId: String) {
        val state = _uiState.value
        val tuning = state.tunings.firstOrNull { it.id == tuningId } ?: return
        savedState[KEY_TUNING] = tuningId
        _uiState.update {
            it.copy(
                tuningId = tuning.id,
                tuningName = tuning.displayName,
                // 唱名直接用预设值（按乐器习惯调，不随全局设置变化，见 spec-core §6）
                strings = tuning.strings.map { s ->
                    StringItemUi(
                        index = s.index.toInt(),
                        noteName = s.noteName,
                        midi = s.midi,
                        freqHz = s.freqHz,
                        solfege = s.solfege,
                    )
                },
                centsToTarget = null,
                targetNoteName = null,
            )
        }
    }

    /** 选择模式（自动/手动）。 */
    fun selectMode(mode: SelectionMode) {
        savedState[KEY_MODE] = mode.name
        _uiState.update { it.copy(mode = mode) }
    }

    /** 点选某弦：锁定目标并切到手动模式。 */
    fun selectString(index: Int) {
        savedState[KEY_STRING] = index
        savedState[KEY_MODE] = SelectionMode.MANUAL.name
        _uiState.update { it.copy(manualIndex = index, mode = SelectionMode.MANUAL) }
    }

    /** 选择调性/型号分组与筒音唱名。 */
    fun selectChart(group: String, tongyin: String?) {
        val charts = core.fingeringCharts(_uiState.value.instrumentId)
        val options = _uiState.value.tongyinOptions
        val ty = when {
            options.isEmpty() -> ""
            tongyin != null && options.contains(tongyin) -> tongyin
            else -> options.first()
        }
        savedState[KEY_GROUP] = group
        savedState[KEY_TONGYIN] = ty
        val chart = charts.firstOrNull { c ->
            if (options.isEmpty()) {
                c.displayName == group
            } else {
                c.displayName == "$group · 筒音作$ty"
            }
        }
        _uiState.update {
            it.copy(
                chartGroup = group,
                tongyin = ty,
                // 唱名直接用预设值（按该 chart 调性+筒音唱名，见 spec-core §6）
                notes = chart?.notes?.map { n ->
                    ChartNoteUi(
                        label = n.label,
                        noteName = n.noteName,
                        midi = n.midi,
                        freqHz = n.freqHz,
                        solfege = n.solfege,
                    )
                }.orEmpty(),
                centsToTarget = null,
                targetNoteName = null,
            )
        }
    }

    /** 事件处理：计算各目标 cents、最近目标高亮、准音标记。 */
    private fun onEvent(ev: TunarEvent) {
        val freq = ev.freqHz
        _uiState.update { state ->
            when (state.kind) {
                InstrumentKind.STRING -> {
                    if (state.strings.isEmpty()) return@update state
                    val cents = state.strings.map { s ->
                        core.centsBetween(freq, s.freqHz) ?: Double.POSITIVE_INFINITY
                    }
                    val nearest = cents.indices.minBy { abs(cents[it]) }
                    // 模式：自动跟随最近弦；手动锁定选中弦
                    val activeIdx = when (state.mode) {
                        SelectionMode.AUTO -> nearest
                        SelectionMode.MANUAL -> state.manualIndex.coerceIn(cents.indices)
                    }
                    state.copy(
                        strings = state.strings.mapIndexed { i, s ->
                            s.copy(
                                active = i == activeIdx,
                                inTune = abs(cents[i]) <= IN_TUNE_CENTS,
                            )
                        },
                        centsToTarget = cents[activeIdx].toFloat(),
                        targetNoteName = state.strings[activeIdx].noteName,
                    )
                }
                InstrumentKind.WIND -> {
                    if (state.notes.isEmpty()) return@update state
                    val cents = state.notes.map { n ->
                        core.centsBetween(freq, n.freqHz) ?: Double.POSITIVE_INFINITY
                    }
                    val nearest = cents.indices.minBy { abs(cents[it]) }
                    state.copy(
                        notes = state.notes.mapIndexed { i, n -> n.copy(active = i == nearest) },
                        centsToTarget = cents[nearest].toFloat(),
                        targetNoteName = state.notes[nearest].noteName,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        if (acquired) {
            stream.release()
            acquired = false
        }
    }

    companion object {
        /** 准音判定（|cents| ≤ 5）。 */
        const val IN_TUNE_CENTS = 5.0
        private const val KEY_INSTRUMENT = "instrumentId"
        private const val KEY_TUNING = "tuningId"
        private const val KEY_MODE = "mode"
        private const val KEY_STRING = "stringIndex"
        private const val KEY_GROUP = "chartGroup"
        private const val KEY_TONGYIN = "tongyin"
    }
}

/** IntRange 的 minBy（空集合抛异常；调用方已保证非空）。 */
private fun IntRange.minBy(selector: (Int) -> Double): Int =
    minByOrNull(selector) ?: first
