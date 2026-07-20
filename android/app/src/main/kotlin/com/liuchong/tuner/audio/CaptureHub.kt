package com.liuchong.tuner.audio

import android.util.Log
import com.liuchong.tuner.corebinding.PitchEngine
import com.liuchong.tuner.corebinding.PitchEngineFactory
import com.liuchong.tuner.corebinding.TunerCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.tuner_core.AnalysisFrame
import uniffi.tuner_core.TunerConfig

/** 运行期配置下发（设置变更时即时生效，无需重启采集）。 */
interface TunerConfigSink {
    fun applyConfig(config: TunerConfig)
}

/**
 * 采集共享核心（可 JVM 单测）：一个 TunerEngine + 一个 PcmSource，
 * acquire/release 引用计数启停；applyConfig 对运行中的引擎即时下发
 * setA4 / setSolfege / setNoiseGate。
 */
class CaptureHubCore(
    private val engineFactory: PitchEngineFactory,
    private val sourceFactory: () -> PcmSource,
    initialConfig: TunerConfig,
) : TunerEventStream, TunerConfigSink {

    private val _events = MutableStateFlow<AnalysisFrame?>(null)
    override val events: StateFlow<AnalysisFrame?> = _events.asStateFlow()

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _config = MutableStateFlow(initialConfig)
    override val config: StateFlow<TunerConfig> = _config.asStateFlow()

    private var refs = 0
    private var engine: PitchEngine? = null
    private var source: PcmSource? = null

    @Synchronized
    override fun acquire() {
        if (refs == 0) startLocked()
        refs++
    }

    @Synchronized
    override fun release() {
        if (refs > 0) refs--
        if (refs == 0) stopLocked()
    }

    @Synchronized
    override fun applyConfig(config: TunerConfig) {
        _config.value = config
        // 引擎运行中：即时下发，无需重启采集
        engine?.let { e ->
            e.setA4(config.a4Hz)
            e.setSolfege(config.solfege, config.key)
            e.setNoiseGate(config.noiseGateDbfs)
            e.setTemperament(config.temperament.toInt())
        }
    }

    private fun startLocked() {
        val eng = engineFactory.create(_config.value)
        val src = sourceFactory()
        try {
            // 音频读线程：analyze（音高+频谱+泛音）→ tryEmit 语义（赋值非阻塞）
            src.start { frame ->
                _events.value = eng.analyze(frame.asList())
            }
            engine = eng
            source = src
            _running.value = true
        } catch (e: Exception) {
            // 权限被收回/设备占用等：优雅降级
            Log.w(TAG, "采集启动失败", e)
            eng.close()
            _running.value = false
        }
    }

    private fun stopLocked() {
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
object CaptureHub : TunerEventStream, TunerConfigSink {
    private val impl = CaptureHubCore(
        engineFactory = TunerCore.engineFactory,
        sourceFactory = { AudioRecordSource() },
        initialConfig = TunerCore.defaultConfig(),
    )

    override val events get() = impl.events
    override val running get() = impl.running
    override val config get() = impl.config
    override fun acquire() = impl.acquire()
    override fun release() = impl.release()
    override fun applyConfig(config: TunerConfig) = impl.applyConfig(config)
}
