package com.liuchong.tuner.ui.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuchong.tuner.audio.TunerEventStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.tuner_core.Partial
import uniffi.tuner_core.TunerEvent

/** 一条音高读数（从 TunerEvent 映射，UI 直接消费）。 */
data class TunerReading(
    val noteName: String,
    val freqHz: Double,
    val centsOff: Double,
    val midi: Int,
    val clarity: Float,
    val solfege: String,
    /** 当前律制 N。 */
    val temperament: Int,
    /** 律制步序 k。 */
    val temperamentStep: Int,
    /** 律制偏差 cents。 */
    val temperamentCents: Double,
)

/** 信号状态：无信号（请发声）/ 有效读数。 */
sealed interface TunerSignal {
    data object Listening : TunerSignal

    data class Active(val reading: TunerReading) : TunerSignal
}

/** 调音面板 UI 状态。 */
data class TunerUiState(
    val signal: TunerSignal = TunerSignal.Listening,
    /** 64 bin 对数频谱（dBFS -80~0；无信号为空）。 */
    val spectrumDb: FloatArray = FloatArray(0),
    /** 泛音列（按幅值降序）。 */
    val partials: List<Partial> = emptyList(),
    /** 和弦名（无则 null）。 */
    val chord: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TunerUiState) return false
        return signal == other.signal &&
            spectrumDb.contentEquals(other.spectrumDb) &&
            partials == other.partials &&
            chord == other.chord
    }

    override fun hashCode(): Int {
        var r = signal.hashCode()
        r = 31 * r + spectrumDb.contentHashCode()
        r = 31 * r + partials.hashCode()
        r = 31 * r + (chord?.hashCode() ?: 0)
        return r
    }
}

/**
 * 通用调音面板 ViewModel。不含 Android 依赖（除 ViewModel 基类），可 JVM 单测。
 *
 * 业务计算（音高/唱名/平滑）全部在 Rust core；本类只做事件→状态映射与超时判断。
 * 采集由共享 TunerEventStream 提供（acquire/release 启停）。
 */
class TunerViewModel(
    private val stream: TunerEventStream,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val signalTimeoutMs: Long = 800L,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TunerUiState())
    val uiState: StateFlow<TunerUiState> = _uiState.asStateFlow()

    private var acquired = false

    @Volatile
    private var lastEventAtMs: Long = Long.MIN_VALUE

    init {
        viewModelScope.launch {
            stream.events.collect { frame ->
                val ev = frame?.tuner ?: return@collect
                lastEventAtMs = clock()
                _uiState.update {
                    it.copy(
                        signal = TunerSignal.Active(ev.toReading()),
                        spectrumDb = frame.spectrumDb.toFloatArray(),
                        partials = frame.partials,
                        chord = frame.chord,
                    )
                }
            }
        }
    }

    /** 权限已授予：接入共享采集。 */
    fun startCapture() {
        if (acquired) return
        stream.acquire()
        acquired = true
    }

    /** 由 UI 侧定时器（~100ms）调用：超过 800ms 无有效事件 → 回到「请发声」。 */
    fun onTick() {
        val last = lastEventAtMs
        if (last == Long.MIN_VALUE) return
        if (clock() - last > signalTimeoutMs &&
            _uiState.value.signal is TunerSignal.Active
        ) {
            _uiState.update {
                it.copy(
                    signal = TunerSignal.Listening,
                    spectrumDb = FloatArray(0),
                    partials = emptyList(),
                    chord = null,
                )
            }
        }
    }

    /** 提前释放采集（手动/测试）；`onCleared` 自动调用。幂等。 */
    fun releaseCapture() {
        if (acquired) {
            stream.release()
            acquired = false
        }
    }

    override fun onCleared() {
        releaseCapture()
    }

    private fun TunerEvent.toReading() = TunerReading(
        noteName = noteName,
        freqHz = freqHz,
        centsOff = centsOff,
        midi = midi,
        clarity = clarity,
        solfege = solfege,
        temperament = temperament.toInt(),
        temperamentStep = temperamentStep,
        temperamentCents = temperamentCents,
    )
}
