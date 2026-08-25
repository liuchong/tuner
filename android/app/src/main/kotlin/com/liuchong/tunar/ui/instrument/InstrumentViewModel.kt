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
import uniffi.tunar_core.FingeringKind
import uniffi.tunar_core.InstrumentKind
import uniffi.tunar_core.FingeringScope
import uniffi.tunar_core.HoleMark
import uniffi.tunar_core.SignalState
import uniffi.tunar_core.TongyinOption
import uniffi.tunar_core.TunarEvent
import uniffi.tunar_core.WindFingering
import uniffi.tunar_core.WindRegister
import uniffi.tunar_core.WindVariant
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
    val semitones: Int = midi,
    val fingeringId: Int = semitones,
    val baseSemitones: Int = semitones,
    val register: WindRegister = WindRegister.LOW,
    val holes: List<HoleMark> = emptyList(),
    val fingeringKind: FingeringKind = FingeringKind.SEQUENTIAL,
    val anchorHole: Int? = null,
    val inScale: Boolean = true,
    val overblown: Boolean = false,
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
    // 洞箫新版指法面板
    val windVariants: List<WindVariant> = emptyList(),
    val variantId: String = "",
    val keyNames: List<String> = emptyList(),
    val keyName: String = "",
    val holeSystems: List<String> = emptyList(),
    val holeSystem: String = "",
    val holeCount: Int = 0,
    val backHoleCount: Int = 0,
    val windTongyinOptions: List<TongyinOption> = emptyList(),
    val tongyinDegree: Int = 0,
    val keyDisplay: String = "",
    val detailTongyinDegree: Int = 0,
    val detailKeyDisplay: String = "",
    val detailNotes: List<ChartNoteUi> = emptyList(),
    val mainPreviewFingeringId: Int? = null,
    val detailPreviewFingeringId: Int? = null,
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
    private var lastFrequencyHz: Double? = null

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
                                detailNotes = it.detailNotes.map { item -> item.copy(active = false) },
                                signalState = analysis.signalState,
                                displayStrength = analysis.displayStrength,
                                isHeld = analysis.isHeld,
                            )
                        }
                        lastFrequencyHz = null
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
        lastFrequencyHz = null
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
                if (id == DONGXIAO_ID) {
                    selectDongxiao(instrument.id, instrument.displayName)
                } else {
                    selectLegacyWind(instrument.id, instrument.displayName)
                }
            }
        }
    }

    private fun selectLegacyWind(id: String, displayName: String) {
        val charts = core.fingeringCharts(id)
        // 旧版普通管乐 UI：displayName 以 " · " 分隔（"D调曲笛 · 筒音作5"）
        val groups = charts.map { it.displayName.substringBefore(" · ") }.distinct()
        val savedGroup = savedState.get<String>(KEY_GROUP)
        val group = groups.firstOrNull { it == savedGroup } ?: groups.firstOrNull().orEmpty()
        val options = charts
            .filter { it.displayName.startsWith("$group · ") }
            .map { it.displayName.substringAfter("筒音作") }
            .distinct()
        _uiState.update {
            it.copy(
                instrumentId = id,
                instrumentName = displayName,
                kind = InstrumentKind.WIND,
                chartGroups = groups,
                tongyinOptions = options,
                windVariants = emptyList(),
                variantId = "",
                keyNames = emptyList(),
                keyName = "",
                holeSystems = emptyList(),
                holeSystem = "",
                windTongyinOptions = emptyList(),
                detailNotes = emptyList(),
                detailTongyinDegree = 0,
                detailKeyDisplay = "",
                mainPreviewFingeringId = null,
                detailPreviewFingeringId = null,
                centsToTarget = null,
                targetNoteName = null,
            )
        }
        selectChart(group, savedState.get<String>(KEY_TONGYIN))
    }

    private fun selectDongxiao(id: String, displayName: String) {
        val variants = core.windVariants(id)
        val keyNames = variants.map { it.keyName }.distinct()
        val savedKey = savedState.get<String>(KEY_WIND_KEY)
            ?: savedState.get<String>(KEY_GROUP)
        val keyName = keyNames.firstOrNull { it == savedKey } ?: keyNames.firstOrNull().orEmpty()
        val candidates = variants.filter { it.keyName == keyName }
        val savedVariantId = savedState.get<String>(KEY_VARIANT)
        val savedHoleSystem = savedState.get<String>(KEY_HOLE_SYSTEM)
        val variant = variants.firstOrNull { it.id == savedVariantId }
            ?: candidates.firstOrNull { it.holeSystemName == savedHoleSystem }
            ?: candidates.firstOrNull { it.holeCount.toInt() == DEFAULT_HOLE_COUNT }
            ?: candidates.firstOrNull()
            ?: variants.firstOrNull()

        _uiState.update {
            it.copy(
                instrumentId = id,
                instrumentName = displayName,
                kind = InstrumentKind.WIND,
                windVariants = variants,
                keyNames = keyNames,
                chartGroups = emptyList(),
                tongyinOptions = emptyList(),
                mainPreviewFingeringId = null,
                detailPreviewFingeringId = null,
                centsToTarget = null,
                targetNoteName = null,
            )
        }
        if (variant != null) {
            val savedDegree = savedState.get<Int>(KEY_TONGYIN_DEGREE)
                ?: migratedTongyinDegree(variant)
            applyWindVariant(variant.id, savedDegree)
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

    /** 洞箫换调，尽量保留孔制与筒音唱名。 */
    fun selectWindKey(name: String) {
        val state = _uiState.value
        if (state.instrumentId != DONGXIAO_ID) return
        val candidates = state.windVariants.filter { it.keyName == name }
        val target = candidates.firstOrNull { it.holeSystemName == state.holeSystem }
            ?: candidates.firstOrNull { it.holeCount.toInt() == DEFAULT_HOLE_COUNT }
            ?: candidates.firstOrNull()
            ?: return
        applyWindVariant(target.id, state.tongyinDegree)
    }

    /** 洞箫切换 8 孔 / 6 孔，保留调性与筒音唱名。 */
    fun selectHoleSystem(name: String) {
        val state = _uiState.value
        if (state.instrumentId != DONGXIAO_ID) return
        val target = state.windVariants.firstOrNull {
            it.keyName == state.keyName && it.holeSystemName == name
        } ?: return
        applyWindVariant(target.id, state.tongyinDegree)
    }

    /** 主表筒音只选择自然唱名 1–7。 */
    fun selectTongyinDegree(degree: Int) {
        val state = _uiState.value
        if (state.instrumentId != DONGXIAO_ID || state.windTongyinOptions.isEmpty()) return
        val normalized = degree.floorMod(TONGYIN_STEPS)
        if (normalized == state.tongyinDegree) return
        if (normalized !in NATURAL_TONGYIN_DEGREES) return
        savedState[KEY_TONGYIN_DEGREE] = normalized
        reloadDongxiaoCharts(state.variantId, normalized, state.detailTongyinDegree)
    }

    /** 主表唱名列按七个自然音级循环。 */
    fun stepTongyin(delta: Int) {
        val current = _uiState.value.tongyinDegree
        val index = NATURAL_TONGYIN_DEGREES.indexOf(current)
        if (index < 0) return
        selectTongyinDegree(
            NATURAL_TONGYIN_DEGREES[(index + delta).floorMod(NATURAL_TONGYIN_DEGREES.size)]
        )
    }

    /** 十二音详情独立选择完整 12 个半音档。 */
    fun selectDetailTongyinDegree(degree: Int) {
        val state = _uiState.value
        if (state.instrumentId != DONGXIAO_ID || state.windTongyinOptions.isEmpty()) return
        val normalized = degree.floorMod(TONGYIN_STEPS)
        if (normalized == state.detailTongyinDegree) return
        savedState[KEY_DETAIL_TONGYIN_DEGREE] = normalized
        reloadDongxiaoCharts(state.variantId, state.tongyinDegree, normalized)
    }

    fun stepDetailTongyin(delta: Int) {
        selectDetailTongyinDegree(_uiState.value.detailTongyinDegree + delta)
    }

    /** 主表点选预览：点选后大图固定显示该指法，再点同一条回到实时识别。 */
    fun previewMainFingering(fingeringId: Int) {
        _uiState.update { state ->
            if (
                state.instrumentId != DONGXIAO_ID ||
                state.notes.none { it.fingeringId == fingeringId }
            ) {
                state
            } else {
                state.copy(
                    mainPreviewFingeringId = fingeringId
                        .takeIf { it != state.mainPreviewFingeringId },
                )
            }
        }
    }

    /** 详情表点选预览；与主表预览互不覆盖，同样再点一次回到实时识别。 */
    fun previewDetailFingering(fingeringId: Int) {
        _uiState.update { state ->
            if (
                state.instrumentId != DONGXIAO_ID ||
                state.detailNotes.none { it.fingeringId == fingeringId }
            ) {
                state
            } else {
                state.copy(
                    detailPreviewFingeringId = fingeringId
                        .takeIf { it != state.detailPreviewFingeringId },
                )
            }
        }
    }

    private fun applyWindVariant(variantId: String, preferredDegree: Int?) {
        val state = _uiState.value
        val variant = state.windVariants.firstOrNull { it.id == variantId } ?: return
        val options = variant.tongyinOptions
        val degree = preferredDegree
            ?.floorMod(TONGYIN_STEPS)
            ?.takeIf { it in NATURAL_TONGYIN_DEGREES }
            ?: variant.defaultTongyinDegree.toInt()
        val detailDegree = if (state.variantId.isEmpty()) {
            savedState.get<Int>(KEY_DETAIL_TONGYIN_DEGREE)
                ?.floorMod(TONGYIN_STEPS)
                ?: degree
        } else {
            state.detailTongyinDegree
        }
        val holeSystems = state.windVariants
            .filter { it.keyId == variant.keyId }
            .map { it.holeSystemName }
            .filter { it.isNotEmpty() }
            .distinct()

        savedState[KEY_WIND_KEY] = variant.keyName
        savedState[KEY_HOLE_SYSTEM] = variant.holeSystemName
        savedState[KEY_VARIANT] = variant.id
        savedState[KEY_TONGYIN_DEGREE] = degree
        savedState[KEY_DETAIL_TONGYIN_DEGREE] = detailDegree
        _uiState.update {
            it.copy(
                variantId = variant.id,
                keyName = variant.keyName,
                holeSystems = holeSystems,
                holeSystem = variant.holeSystemName,
                holeCount = variant.holeCount.toInt(),
                backHoleCount = variant.backHoleCount.toInt(),
                windTongyinOptions = options,
                tongyinDegree = degree,
                detailTongyinDegree = detailDegree,
            )
        }
        reloadDongxiaoCharts(variant.id, degree, detailDegree)
    }

    private fun reloadDongxiaoCharts(
        variantId: String,
        degree: Int,
        detailDegree: Int,
    ) {
        val scale = core.windFingeringChart(
            variantId = variantId,
            tongyinDegree = degree.toUByte(),
            scope = FingeringScope.SCALE,
        )
        val detail = core.windFingeringChart(
            variantId = variantId,
            tongyinDegree = detailDegree.toUByte(),
            scope = FingeringScope.CHROMATIC,
        )
        _uiState.update { state ->
            if (scale == null) {
                state.copy(
                    notes = emptyList(),
                    detailNotes = emptyList(),
                    keyDisplay = "",
                    detailKeyDisplay = "",
                    mainPreviewFingeringId = null,
                    detailPreviewFingeringId = null,
                )
            } else {
                val activeScale = state.notes
                    .filter { it.active }
                    .mapTo(mutableSetOf()) { it.fingeringId }
                val activeDetail = state.detailNotes
                    .filter { it.active }
                    .mapTo(mutableSetOf()) { it.fingeringId }
                val scaleNotes = scale.notes.map { it.toUi(activeScale.contains(it.fingeringId)) }
                val detailNotes = (detail ?: scale).notes.map {
                    it.toUi(activeDetail.contains(it.fingeringId))
                }
                val next = state.copy(
                    variantId = scale.variantId,
                    holeCount = scale.holeCount.toInt(),
                    backHoleCount = scale.backHoleCount.toInt(),
                    tongyinDegree = scale.tongyinDegree.toInt(),
                    keyDisplay = scale.keyDisplay,
                    detailTongyinDegree = (detail ?: scale).tongyinDegree.toInt(),
                    detailKeyDisplay = (detail ?: scale).keyDisplay,
                    notes = scaleNotes,
                    detailNotes = detailNotes,
                    // 未点选时保持 null，大图跟随实时识别（无信号时回落全闭筒音）。
                    mainPreviewFingeringId = state.mainPreviewFingeringId
                        ?.takeIf { selected -> scaleNotes.any { it.fingeringId == selected } },
                    detailPreviewFingeringId = state.detailPreviewFingeringId
                        ?.takeIf { selected -> detailNotes.any { it.fingeringId == selected } },
                )
                lastFrequencyHz?.let { applyWindReading(next, it) } ?: next
            }
        }
    }

    private fun migratedTongyinDegree(variant: WindVariant): Int? {
        val oldTongyin = savedState.get<String>(KEY_TONGYIN) ?: return null
        return variant.tongyinOptions
            .firstOrNull { it.solfege == oldTongyin }
            ?.degree
            ?.toInt()
    }

    /** 事件处理：计算各目标 cents、最近目标高亮、准音标记。 */
    private fun onEvent(ev: TunarEvent) {
        val freq = ev.freqHz
        lastFrequencyHz = freq
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
                    if (state.instrumentId == DONGXIAO_ID) {
                        applyWindReading(state, freq)
                    } else {
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
    }

    private fun applyWindReading(state: InstrumentUiState, freq: Double): InstrumentUiState {
        if (state.notes.isEmpty()) return state
        val scaleCents = state.notes.map { n ->
            core.centsBetween(freq, n.freqHz) ?: Double.POSITIVE_INFINITY
        }
        val scaleNearest = scaleCents.indices.minBy { abs(scaleCents[it]) }
        val detailCents = state.detailNotes.map { n ->
            core.centsBetween(freq, n.freqHz) ?: Double.POSITIVE_INFINITY
        }
        val detailNearest = if (detailCents.isEmpty()) -1 else detailCents.indices.minBy {
            abs(detailCents[it])
        }
        return state.copy(
            notes = state.notes.mapIndexed { i, n -> n.copy(active = i == scaleNearest) },
            detailNotes = state.detailNotes.mapIndexed { i, n ->
                n.copy(active = i == detailNearest)
            },
            centsToTarget = scaleCents[scaleNearest].toFloat(),
            targetNoteName = state.notes[scaleNearest].noteName,
        )
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
        private const val KEY_WIND_KEY = "windKeyName"
        private const val KEY_HOLE_SYSTEM = "windHoleSystem"
        private const val KEY_VARIANT = "windVariantId"
        private const val KEY_TONGYIN_DEGREE = "windTongyinDegree"
        private const val KEY_DETAIL_TONGYIN_DEGREE = "windDetailTongyinDegree"
        private const val DONGXIAO_ID = "dongxiao"
        private const val DEFAULT_HOLE_COUNT = 8
        private const val TONGYIN_STEPS = 12
        private val NATURAL_TONGYIN_DEGREES = listOf(0, 2, 4, 5, 7, 9, 11)
    }
}

/** IntRange 的 minBy（空集合抛异常；调用方已保证非空）。 */
private fun IntRange.minBy(selector: (Int) -> Double): Int =
    minByOrNull(selector) ?: first

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

private fun WindFingering.toUi(active: Boolean): ChartNoteUi = ChartNoteUi(
    label = label,
    noteName = noteName,
    midi = midi,
    freqHz = freqHz,
    solfege = solfege,
    semitones = semitones,
    fingeringId = fingeringId,
    baseSemitones = baseSemitones,
    register = register,
    holes = holes,
    fingeringKind = fingeringKind,
    anchorHole = anchorHole?.toInt(),
    inScale = inScale,
    overblown = overblown,
    active = active,
)
