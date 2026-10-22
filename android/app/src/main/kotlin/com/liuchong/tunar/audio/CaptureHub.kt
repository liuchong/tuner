package com.liuchong.tunar.audio

import android.util.Log
import com.liuchong.tunar.corebinding.PitchEngine
import com.liuchong.tunar.corebinding.PitchEngineFactory
import com.liuchong.tunar.corebinding.TunarCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.tunar_core.AnalysisFrame
import uniffi.tunar_core.TunarConfig

/** 运行期配置下发（设置变更时即时生效，无需重启采集）。 */
data class PracticeCaptureFrame(val frame: AnalysisFrame, val config: TunarConfig, val generation: ULong, val referenceActive: Boolean)

interface TunarConfigSink {
    fun applyConfig(config: TunarConfig)
}

/**
 * 采集共享核心（可 JVM 单测）：一个 TunarEngine + 一个 PcmSource，
 * acquire/release 引用计数启停；applyConfig 对运行中的引擎即时下发
 * setA4 / setSolfege / setNoiseGate。
 */
class CaptureHubCore(
    private val engineFactory: PitchEngineFactory,
    private val sourceFactory: () -> PcmSource,
    initialConfig: TunarConfig,
) : TunarEventStream, TunarConfigSink {

    private val _events = MutableStateFlow<AnalysisFrame?>(null)
    override val events: StateFlow<AnalysisFrame?> = _events.asStateFlow()

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _config = MutableStateFlow(initialConfig)
    override val config: StateFlow<TunarConfig> = _config.asStateFlow()

    private val _measurements = MutableStateFlow<PracticeCaptureFrame?>(null)
    val measurements: StateFlow<PracticeCaptureFrame?> = _measurements.asStateFlow()
    @Volatile private var generation = 0L
    private val analysisLock = Any()
    private var refs = 0
    private var enabled = true
    private var referenceTail = 0
    private var engine: PitchEngine? = null
    private var source: PcmSource? = null

    @Synchronized
    override fun acquire() {
        if (refs == 0 && enabled) startLocked()
        refs++
    }

    @Synchronized
    override fun release() {
        if (refs > 0) refs--
        if (refs == 0) stopLocked()
    }

    @Synchronized
    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) stopLocked() else if (refs > 0) startLocked()
    }

    @Synchronized
    fun retry() { if (enabled && refs > 0) { stopLocked(); startLocked() } }

    @Synchronized
    override fun applyConfig(config: TunarConfig) {
        synchronized(analysisLock) {
        _config.value = config
        // 引擎运行中：配置与分析帧上下文原子一致。
        engine?.let { e ->
            e.setA4(config.a4Hz)
            e.setSolfege(config.solfege, config.key)
            e.setNoiseGate(config.noiseGateDbfs)
            e.setTemperament(config.temperament.toInt())
        }
        }
    }

    private fun startLocked() {
        generation += 1L
        val captureGeneration = generation
        synchronized(analysisLock) { referenceTail = 0 }
        val bootConfig = _config.value
        val eng = engineFactory.create(bootConfig)
        val src = sourceFactory()
        try {
            // 音频读线程：analyze（音高+频谱+泛音）→ tryEmit 语义（赋值非阻塞）
            val deliver: (FloatArray, ULong?) -> Unit = { frame, position ->
                synchronized(analysisLock) {
                    if (captureGeneration == generation) {
                        val config = _config.value
                        val analyzed = eng.analyze(frame.asList())
                        val result = if (position == null) analyzed else analyzed.copy(samplePosition = position)
                        val reference = ReferenceToneActivity.isActive
                        val affected = reference || referenceTail > 0
                        referenceTail = if (reference) 8 else maxOf(0, referenceTail - 1)
                        _events.value = result
                        _measurements.value = PracticeCaptureFrame(result, config.copy(sampleRate = result.sampleRateHz, frameHopSamples = bootConfig.frameHopSamples), captureGeneration.toULong(), affected)
                    }
                }
            }
            engine = eng
            source = src
            _running.value = true
            if (src is PositionedPcmSource) {
                src.setFailureHandler { if (generation == captureGeneration) _running.value = false }
                src.startPositioned { frame, position -> deliver(frame, position) }
            } else src.start { frame -> deliver(frame, null) }
        } catch (e: Exception) {
            // 权限被收回/设备占用等：优雅降级
            Log.w(TAG, "采集启动失败", e)
            src.stop()
            eng.close()
            engine = null; source = null
            _running.value = false
        }
    }

    private fun stopLocked() {
        generation += 1L
        source?.stop()
        source = null
        engine?.close()
        engine = null
        _running.value = false
    }

    private companion object {
        const val TAG = "CaptureHub"
    }
}

/**
 * 采集共享枢纽（单例）：各面板 ViewModel 通过 acquire/release 共享。
 */
object CaptureHub : TunarEventStream, TunarConfigSink {
    private val impl = CaptureHubCore(
        engineFactory = TunarCore.engineFactory,
        sourceFactory = { AudioRecordSource() },
        initialConfig = TunarCore.defaultConfig(),
    )

    fun setEnabled(value: Boolean) = impl.setEnabled(value)
    fun retry() = impl.retry()
    val measurements get() = impl.measurements
    override val events get() = impl.events
    override val running get() = impl.running
    override val config get() = impl.config
    override fun acquire() = impl.acquire()
    override fun release() = impl.release()
    override fun applyConfig(config: TunarConfig) = impl.applyConfig(config)
}
