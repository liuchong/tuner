package com.liuchong.tunar.ui.metronome

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuchong.tunar.audio.MetronomePlayer
import com.liuchong.tunar.audio.TickEvent
import com.liuchong.tunar.corebinding.MetronomeEngine
import com.liuchong.tunar.corebinding.MetronomeEngineFactory
import com.liuchong.tunar.corebinding.TickSoundKind
import com.liuchong.tunar.corebinding.TickSounds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.tunar_core.MetronomeConfig
import uniffi.tunar_core.TickAccent

/** 节拍器面板 UI 状态。 */
data class MetronomeUiState(
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val beatUnit: Int = 4,
    val accents: List<TickAccent> = defaultAccents(4),
    val accentSound: TickSoundKind = TickSoundKind.BELL,
    val normalSound: TickSoundKind = TickSoundKind.CLICK,
    val playing: Boolean = false,
    /** 当前闪拍（-1 = 无）；flashSeq 用于触发一次性动画。 */
    val currentBeat: Int = -1,
    val flashSeq: Long = 0L,
)

/** 默认重音型：首拍重拍，其余弱拍。 */
fun defaultAccents(beats: Int): List<TickAccent> =
    List(beats) { if (it == 0) TickAccent.ACCENT else TickAccent.NORMAL }

/** 常用拍号集（spec-ui §3：1/4–12/8）。 */
val COMMON_TIME_SIGNATURES: List<Pair<Int, Int>> = listOf(
    1 to 4, 2 to 4, 3 to 4, 4 to 4, 5 to 4, 6 to 4,
    3 to 8, 6 to 8, 9 to 8, 12 to 8,
)

/**
 * 节拍器面板 ViewModel。节奏调度全在 Rust core 引擎；
 * 本类只做参数映射、选择状态与闪拍事件转发。
 */
class MetronomeViewModel(
    private val engineFactory: MetronomeEngineFactory,
    private val player: MetronomePlayer,
    private val sounds: Map<TickSoundKind, List<Float>>,
    private val savedState: SavedStateHandle,
    private val clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(restoreState())
    val uiState: StateFlow<MetronomeUiState> = _uiState.asStateFlow()

    private var engine: MetronomeEngine? = null

    init {
        // 引擎常驻（tap tempo 在未播放时也要工作）
        engine = engineFactory.create(currentConfig()).also { e ->
            applySounds(e, _uiState.value.accentSound, _uiState.value.normalSound)
        }
        // 闪拍事件：延时到呈现时刻再更新状态（与声音同步）
        viewModelScope.launch {
            player.ticks.collect { ev: TickEvent? ->
                if (ev != null) {
                    val wait = ev.atMs - clock()
                    if (wait > 0) delay(wait)
                    _uiState.update {
                        it.copy(currentBeat = ev.beatIndex, flashSeq = it.flashSeq + 1)
                    }
                }
            }
        }
    }

    private fun restoreState(): MetronomeUiState {
        val beats = savedState.get<Int>(KEY_BEATS) ?: 4
        val accents = savedState.get<Array<String>>(KEY_ACCENTS)
            ?.map { TickAccent.valueOf(it) }
            ?.takeIf { it.size == beats }
            ?: defaultAccents(beats)
        return MetronomeUiState(
            bpm = (savedState.get<Double>(KEY_BPM) ?: 120.0).coerceIn(BPM_MIN, BPM_MAX),
            beatsPerBar = beats,
            beatUnit = savedState.get<Int>(KEY_UNIT) ?: 4,
            accents = accents,
            accentSound = restoreSound(KEY_ACCENT_SOUND, TickSoundKind.BELL),
            normalSound = restoreSound(KEY_NORMAL_SOUND, TickSoundKind.CLICK),
        )
    }

    private fun restoreSound(key: String, fallback: TickSoundKind): TickSoundKind {
        val savedName = savedState.get<String>(key) ?: return fallback
        return TickSoundKind.entries.firstOrNull { it.name == savedName } ?: fallback
    }

    private fun currentConfig() = _uiState.value.let {
        MetronomeConfig(
            sampleRate = TickSounds.SAMPLE_RATE,
            bpm = it.bpm,
            beatsPerBar = it.beatsPerBar.toUByte(),
            beatUnit = it.beatUnit.toUByte(),
            accents = it.accents,
        )
    }

    /** 设置 BPM（钳制 30–250），引擎即时生效。 */
    fun setBpm(bpm: Double) {
        val clamped = bpm.coerceIn(BPM_MIN, BPM_MAX)
        savedState[KEY_BPM] = clamped
        _uiState.update { it.copy(bpm = clamped) }
        engine?.setBpm(clamped)
    }

    /** 步进调整。 */
    fun adjustBpm(delta: Int) = setBpm(_uiState.value.bpm + delta)

    /** tap tempo（≥2 次生效；core 取最近 ≤4 间隔中位数）。 */
    fun tap() {
        val tsSamples = (clock() * TickSounds.SAMPLE_RATE / 1000.0).toLong()
        engine?.let { e -> setBpm(e.tap(tsSamples)) }
    }

    /** 设置拍号；重音型重置为默认（首拍重拍）。 */
    fun setTimeSignature(beats: Int, unit: Int) {
        val accents = defaultAccents(beats)
        savedState[KEY_BEATS] = beats
        savedState[KEY_UNIT] = unit
        savedState[KEY_ACCENTS] = accents.map { it.name }.toTypedArray()
        _uiState.update { it.copy(beatsPerBar = beats, beatUnit = unit, accents = accents) }
        engine?.setTimeSignature(beats, unit)
        engine?.setAccents(accents)
    }

    /** 点击某拍圆点：重拍 → 普通 → 静音 循环。 */
    fun cycleAccent(index: Int) {
        val current = _uiState.value.accents
        if (index !in current.indices) return
        val next = current.mapIndexed { i, a ->
            if (i == index) {
                when (a) {
                    TickAccent.ACCENT -> TickAccent.NORMAL
                    TickAccent.NORMAL -> TickAccent.MUTED
                    TickAccent.MUTED -> TickAccent.ACCENT
                }
            } else {
                a
            }
        }
        savedState[KEY_ACCENTS] = next.map { it.name }.toTypedArray()
        _uiState.update { it.copy(accents = next) }
        engine?.setAccents(next)
    }

    /** 重拍音色。 */
    fun setAccentSound(kind: TickSoundKind) {
        savedState[KEY_ACCENT_SOUND] = kind.name
        _uiState.update { it.copy(accentSound = kind) }
        engine?.let { e -> applySounds(e, kind, _uiState.value.normalSound) }
    }

    /** 弱拍音色。 */
    fun setNormalSound(kind: TickSoundKind) {
        savedState[KEY_NORMAL_SOUND] = kind.name
        _uiState.update { it.copy(normalSound = kind) }
        engine?.let { e -> applySounds(e, _uiState.value.accentSound, kind) }
    }

    private fun applySounds(e: MetronomeEngine, accent: TickSoundKind, normal: TickSoundKind) {
        val a = sounds[accent] ?: TickSounds.synthesize(accent)
        val n = sounds[normal] ?: TickSounds.synthesize(normal)
        e.setClickSamples(a, n)
    }

    /** 播放/停止切换。返回 true 表示开始播放（供 UI 启动保活 Service）。 */
    fun togglePlay(): Boolean = if (_uiState.value.playing) {
        pause()
        false
    } else {
        play()
        true
    }

    fun play() {
        if (_uiState.value.playing) return
        engine?.start(0)
        engine?.let { player.startLoop(it) }
        _uiState.update { it.copy(playing = true, currentBeat = -1) }
    }

    fun pause() {
        if (!_uiState.value.playing) return
        player.stopLoop()
        engine?.stop()
        _uiState.update { it.copy(playing = false, currentBeat = -1) }
    }

    override fun onCleared() {
        player.stopLoop()
        engine?.close()
        engine = null
    }

    companion object {
        const val BPM_MIN = 30.0
        const val BPM_MAX = 250.0
        private const val KEY_BPM = "bpm"
        private const val KEY_BEATS = "beats"
        private const val KEY_UNIT = "unit"
        private const val KEY_ACCENTS = "accents"
        private const val KEY_ACCENT_SOUND = "accentSound"
        private const val KEY_NORMAL_SOUND = "normalSound"
    }
}
