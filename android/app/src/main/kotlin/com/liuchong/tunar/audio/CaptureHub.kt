package com.liuchong.tunar.audio

import android.util.Log
import com.liuchong.tunar.corebinding.PitchEngine
import com.liuchong.tunar.corebinding.PitchEngineFactory
import com.liuchong.tunar.corebinding.TunarCore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.tunar_core.*
import com.liuchong.tunar.corebinding.emptyInputStatus
import com.liuchong.tunar.corebinding.initialInputPolicy
import uniffi.tunar_core.AnalysisFrame
import uniffi.tunar_core.TunarConfig

/** 运行期配置下发（设置变更时即时生效，无需重启采集）。 */
data class PracticeCaptureFrame(val frame: AnalysisFrame, val config: TunarConfig, val generation: ULong, val referenceActive: Boolean)

interface TunarConfigSink {
    fun applyConfig(config: TunarConfig)
    fun applyInputPolicy(policy: InputPolicy) = Unit
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
    private val _inputStatus=MutableStateFlow(emptyInputStatus())
    val inputStatus:StateFlow<InputStatus> = _inputStatus.asStateFlow()
    private var inputPolicy=initialInputPolicy()
    private var calibrationRequested=false
    private var fallbackPosition=0uL
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
        val gateChanged=_config.value.noiseGateDbfs != config.noiseGateDbfs
        _config.value = config
        // 引擎运行中：配置与分析帧上下文原子一致。
        engine?.let { e ->
            e.setA4(config.a4Hz)
            e.setSolfege(config.solfege, config.key)
            if(gateChanged)e.setNoiseGate(config.noiseGateDbfs)
            e.setTemperament(config.temperament.toInt())
        }
        }
    }

    override fun applyInputPolicy(policy:InputPolicy){synchronized(analysisLock){inputPolicy=policy;try{engine?.setInputPolicy(policy)}catch(e:Exception){inputFailure("输入设置未生效，请恢复默认后重试")}}}
    fun requestCalibration(){synchronized(analysisLock){calibrationRequested=true;val old=_inputStatus.value;_inputStatus.value=old.copy(calibration=old.calibration.copy(state=CalibrationState.MEASURING,elapsedSeconds=0.0,reason="正在停止播放，等待输入及输出保护结束…"))}}
    fun confirmCalibration(){synchronized(analysisLock){try{engine?.confirmNoiseEnvironment()}catch(e:Exception){inputFailure("没有待确认的环境测量，请重测")}}}
    fun cancelCalibration(){synchronized(analysisLock){calibrationRequested=false;engine?.cancelNoiseCalibration();engine?.let{_inputStatus.value=it.inputStatus()}}}
    private fun inputFailure(message:String){val old=_inputStatus.value;_inputStatus.value=old.copy(calibration=old.calibration.copy(state=CalibrationState.FAILED,reason=message));calibrationRequested=false}

    private fun startLocked() {
        generation += 1L
        val captureGeneration = generation
        synchronized(analysisLock) { referenceTail = 0 }
        val bootConfig = _config.value
        val eng = engineFactory.create(bootConfig)
        val src = sourceFactory()
        fallbackPosition=0uL
        try {
            eng.setInputPolicy(inputPolicy)
            // 音频读线程：analyze（音高+频谱+泛音）→ tryEmit 语义（赋值非阻塞）
            val deliver: (FloatArray, ULong?) -> Unit = { frame, position ->
                synchronized(analysisLock) {
                    if (captureGeneration == generation) {
                        val config = _config.value
                        val reference = ReferenceToneActivity.isActive
                        val affected = reference || referenceTail > 0
                        referenceTail = if (reference) 8 else maxOf(0, referenceTail - 1)
                        fallbackPosition+=bootConfig.frameHopSamples.toULong()
                        val context=InputFrameContext(position?:fallbackPosition,captureGeneration.toULong(),affected,MetronomeActivity.isActive)
                        try {
                            if(calibrationRequested && !eng.inputStatus().outputActive && !affected && !context.metronomeActive){eng.beginNoiseCalibration(false);calibrationRequested=false}
                            val result=eng.analyzeInput(frame.asList(),context)
                            if(captureGeneration==generation) {
                            val display=eng.presentationFrame() ?: if(result.inputDiagnostics!=null)result.copy(tuner=null,isHeld=false)else result
                            _events.value=display
                            _measurements.value=PracticeCaptureFrame(result,config.copy(sampleRate=result.sampleRateHz,frameHopSamples=bootConfig.frameHopSamples),captureGeneration.toULong(),affected)
                            _inputStatus.value=eng.inputStatus().let { current -> if(calibrationRequested)current.copy(calibration=current.calibration.copy(state=CalibrationState.MEASURING,reason="正在停止播放，等待输入及输出保护结束…")) else current }
                            }
                        }catch(e:Exception){if(captureGeneration==generation)inputFailure("输入时钟或环境操作失败，请重试输入；已有记录保留")}
                    }
                }
            }
            engine = eng
            source = src
            _running.value = true
            if (src is PositionedPcmSource) {
                src.setFailureHandler { if (generation == captureGeneration) { _running.value = false;_inputStatus.value=_inputStatus.value.copy(available=false);inputFailure("音频输入已中断，请重试") } }
                src.startPositioned { frame, position -> deliver(frame, position) }
            } else src.start { frame -> deliver(frame, null) }
        } catch (e: Exception) {
            // 权限被收回/设备占用等：优雅降级
            Log.w(TAG, "采集启动失败", e)
            src.stop()
            eng.close()
            engine = null; source = null
            _running.value = false
            inputFailure("麦克风启动失败，请检查权限或输入设备后重试")
        }
    }

    private fun stopLocked() {
        calibrationRequested=false
        generation += 1L
        source?.stop()
        source = null
        engine?.close()
        engine = null
        _running.value = false
        _inputStatus.value=_inputStatus.value.copy(available=false,outputActive=false,calibration=CalibrationStatus(CalibrationState.IDLE,null,0.0,"输入已变化，请重新测环境"))
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

    private var foreground=true
    private var routeEnabled=true
    private var calibrationLease=false
    private fun refreshEnabled(){impl.setEnabled(foreground&&(routeEnabled||calibrationLease))}
    fun setEnabled(value:Boolean){routeEnabled=value;refreshEnabled()}
    fun setRouteEnabled(value:Boolean){routeEnabled=value;refreshEnabled()}
    fun setForeground(value:Boolean){foreground=value;if(!value){endCalibrationCapture()};refreshEnabled()}
    val inputStatus get()=impl.inputStatus
    val calibrationRequests=kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity=1)
    fun beginCalibration(){if(!foreground)return;calibrationRequests.tryEmit(Unit);ReferenceToneActivity.stopAll();if(!calibrationLease){calibrationLease=true;refreshEnabled();impl.acquire()}else if(!impl.running.value){impl.retry()};impl.requestCalibration()}
    fun confirmCalibration()=impl.confirmCalibration()
    fun cancelCalibration()=impl.cancelCalibration()
    fun endCalibrationCapture(){if(calibrationLease){calibrationLease=false;impl.cancelCalibration();impl.release();refreshEnabled()}}
    override fun applyInputPolicy(policy:InputPolicy)=impl.applyInputPolicy(policy)
    fun retry() = impl.retry()
    val measurements get() = impl.measurements
    override val events get() = impl.events
    override val running get() = impl.running
    override val config get() = impl.config
    override fun acquire() = impl.acquire()
    override fun release() = impl.release()
    override fun applyConfig(config: TunarConfig) = impl.applyConfig(config)
}
