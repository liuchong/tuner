package com.liuchong.tunar.ui.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuchong.tunar.audio.TunarEventStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.tunar_core.Partial
import uniffi.tunar_core.SignalState
import uniffi.tunar_core.TunarEvent

/** 一条音高读数（从 TunarEvent 映射，UI 直接消费）。 */
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
    /** 128 bin 全频段对数频谱。 */
    val wideSpectrumDb: FloatArray = FloatArray(0),
    /** 全频段频谱的实际频率上限。 */
    val wideSpectrumMaxHz: Double = 20_000.0,
    /** 当前分析窗口的最小值/最大值波形包络。 */
    val waveformMin: FloatArray = FloatArray(0),
    val waveformMax: FloatArray = FloatArray(0),
    /** 当前分析帧的采样位置与实际采样率。 */
    val samplePosition: ULong = 0uL,
    val sampleRateHz: Double = 48_000.0,
    /** 泛音列（按幅值降序）。 */
    val partials: List<Partial> = emptyList(),
    /** 和弦名（无则 null）。 */
    val chord: String? = null,
    /** 主调音页锁存的最近一次确认频谱。 */
    val displaySpectrumDb: FloatArray = FloatArray(0),
    /** 主调音页锁存的最近一次确认峰值。 */
    val displayPartials: List<Partial> = emptyList(),
    /** 主调音页锁存的最近一次确认和弦。 */
    val displayChord: String? = null,
    /** Rust core 的统一信号状态。 */
    val signalState: SignalState = SignalState.QUIET,
    /** 当前输入 RMS 电平。 */
    val inputLevelDbfs: Float = -120f,
    /** 保持期显示强度。 */
    val displayStrength: Float = 0f,
    /** 当前读数是否为断音保持。 */
    val isHeld: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TunerUiState) return false
        return signal == other.signal &&
            spectrumDb.contentEquals(other.spectrumDb) &&
            wideSpectrumDb.contentEquals(other.wideSpectrumDb) &&
            wideSpectrumMaxHz == other.wideSpectrumMaxHz &&
            waveformMin.contentEquals(other.waveformMin) &&
            waveformMax.contentEquals(other.waveformMax) &&
            samplePosition == other.samplePosition &&
            sampleRateHz == other.sampleRateHz &&
            partials == other.partials &&
            chord == other.chord &&
            displaySpectrumDb.contentEquals(other.displaySpectrumDb) &&
            displayPartials == other.displayPartials &&
            displayChord == other.displayChord &&
            signalState == other.signalState &&
            inputLevelDbfs == other.inputLevelDbfs &&
            displayStrength == other.displayStrength &&
            isHeld == other.isHeld
    }

    override fun hashCode(): Int {
        var r = signal.hashCode()
        r = 31 * r + spectrumDb.contentHashCode()
        r = 31 * r + wideSpectrumDb.contentHashCode()
        r = 31 * r + wideSpectrumMaxHz.hashCode()
        r = 31 * r + waveformMin.contentHashCode()
        r = 31 * r + waveformMax.contentHashCode()
        r = 31 * r + samplePosition.hashCode()
        r = 31 * r + sampleRateHz.hashCode()
        r = 31 * r + partials.hashCode()
        r = 31 * r + (chord?.hashCode() ?: 0)
        r = 31 * r + displaySpectrumDb.contentHashCode()
        r = 31 * r + displayPartials.hashCode()
        r = 31 * r + (displayChord?.hashCode() ?: 0)
        r = 31 * r + signalState.hashCode()
        r = 31 * r + inputLevelDbfs.hashCode()
        r = 31 * r + displayStrength.hashCode()
        r = 31 * r + isHeld.hashCode()
        return r
    }
}

/**
 * 通用调音面板 ViewModel。不含 Android 依赖（除 ViewModel 基类），可 JVM 单测。
 *
 * 业务计算（音高/唱名/平滑）全部在 Rust core；本类只做事件→展示状态映射。
 * 采集由共享 TunarEventStream 提供（acquire/release 启停）。
 */
class TunerViewModel(
    private val stream: TunarEventStream,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TunerUiState())
    val uiState: StateFlow<TunerUiState> = _uiState.asStateFlow()

    private var acquired = false

    init {
        viewModelScope.launch {
            stream.events.collect { frame ->
                frame ?: return@collect
                _uiState.update {
                    val confirmed = frame.signalState == SignalState.TRACKING
                    it.copy(
                        signal = frame.tuner?.let { ev ->
                            TunerSignal.Active(ev.toReading())
                        } ?: TunerSignal.Listening,
                        spectrumDb = frame.spectrumDb.toFloatArray(),
                        wideSpectrumDb = frame.wideSpectrumDb.toFloatArray(),
                        wideSpectrumMaxHz = frame.wideSpectrumMaxHz,
                        waveformMin = frame.waveformMin.toFloatArray(),
                        waveformMax = frame.waveformMax.toFloatArray(),
                        samplePosition = frame.samplePosition,
                        sampleRateHz = frame.sampleRateHz,
                        partials = frame.partials,
                        chord = frame.chord,
                        displaySpectrumDb = if (confirmed) {
                            frame.spectrumDb.toFloatArray()
                        } else {
                            it.displaySpectrumDb
                        },
                        displayPartials = if (confirmed) frame.partials else it.displayPartials,
                        displayChord = if (confirmed) frame.chord else it.displayChord,
                        signalState = frame.signalState,
                        inputLevelDbfs = frame.inputLevelDbfs,
                        displayStrength = frame.displayStrength,
                        isHeld = frame.isHeld,
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

    private fun TunarEvent.toReading() = TunerReading(
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
