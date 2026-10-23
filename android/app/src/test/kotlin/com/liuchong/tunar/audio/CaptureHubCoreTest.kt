package com.liuchong.tunar.audio

import com.liuchong.tunar.corebinding.PitchEngine
import com.liuchong.tunar.corebinding.PitchEngineFactory
import com.liuchong.tunar.corebinding.TunarCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tunar_core.*
import com.liuchong.tunar.corebinding.initialInputPolicy
import uniffi.tunar_core.KeyMode
import uniffi.tunar_core.ModeKind
import uniffi.tunar_core.SolfegeSystem
import uniffi.tunar_core.TunarConfig
import uniffi.tunar_core.TunarEvent

private class FakeEngine : PitchEngine {
    var recordedA4 = 0.0
    var solfege: SolfegeSystem? = null
    var key: KeyMode? = null
    var recordedGate = 0f
    var recordedTemperament = 0
    var closed = false
    var policy = initialInputPolicy()
    var context: InputFrameContext? = null
    var onAnalyze: (() -> Unit)? = null
    var calibrations = 0
    override fun setInputPolicy(policy:InputPolicy){this.policy=policy}
    override fun analyzeInput(pcm:List<Float>,context:InputFrameContext):AnalysisFrame{this.context=context;onAnalyze?.invoke();return analyze(pcm).copy(samplePosition=context.samplePosition)}
    override fun beginNoiseCalibration(outputActive:Boolean){calibrations++}

    override fun feed(pcm: List<Float>): TunarEvent? = null

    override fun analyze(pcm: List<Float>) = uniffi.tunar_core.AnalysisFrame(
        tuner = null,
        spectrumDb = emptyList(),
        wideSpectrumDb = emptyList(),
        wideSpectrumMaxHz = 20_000.0,
        waveformMin = emptyList(),
        waveformMax = emptyList(),
        samplePosition = 0uL,
        sampleRateHz = 48_000.0,
        partials = emptyList(),
        chord = null,
        signalState = uniffi.tunar_core.SignalState.QUIET,
        inputLevelDbfs = -120f,
        displayStrength = 0f,
        isHeld = false,
            inputDiagnostics = null, measuredFreqHz = null
        )
    override fun setA4(hz: Double) {
        recordedA4 = hz
    }

    override fun setSolfege(system: SolfegeSystem, key: KeyMode) {
        solfege = system
        this.key = key
    }

    override fun setNoiseGate(dbfs: Float) {
        recordedGate = dbfs
    }

    override fun setTemperament(divisions: Int) {
        recordedTemperament = divisions
    }

    override fun close() {
        closed = true
    }
}

private class FakeSource : PcmSource {
    var started = false
    var stopped = false

    override fun start(onFrame: (FloatArray) -> Unit) {
        started = true
    }

    override fun stop() {
        stopped = true
    }
}

class CaptureHubCoreTest {

    private fun newHub(
        engine: FakeEngine,
        source: FakeSource,
    ) = CaptureHubCore(
        engineFactory = PitchEngineFactory { engine },
        sourceFactory = { source },
        initialConfig = TunarCore.defaultConfig(),
    )

    @Test
    fun `measurement packet retains actual capture position and configuration`() {
        val engine = FakeEngine()
        var callback: ((FloatArray, ULong) -> Unit)? = null
        val source = object : PositionedPcmSource {
            override fun startPositioned(onFrame: (FloatArray, ULong) -> Unit) { callback = onFrame }
            override fun stop() { }
        }
        val hub = CaptureHubCore(PitchEngineFactory { engine }, { source }, TunarCore.defaultConfig())
        hub.acquire()
        val config = hub.config.value.copy(a4Hz = 442.0, temperament = 19u)
        hub.applyConfig(config)
        callback!!(FloatArray(2048), 8192uL)
        assertEquals(8192uL, hub.measurements.value!!.frame.samplePosition)
        assertEquals(config.copy(sampleRate = 48000.0), hub.measurements.value!!.config)
        assertEquals(hub.measurements.value!!.frame.sampleRateHz, hub.measurements.value!!.config.sampleRate, 0.0)
        val marker = Any()
        ReferenceToneActivity.set(marker, true)
        callback!!(FloatArray(2048), 9216uL)
        ReferenceToneActivity.set(marker, false)
        repeat(8) { callback!!(FloatArray(2048), (10240 + it * 1024).toULong()); assertTrue(hub.measurements.value!!.referenceActive) }
        callback!!(FloatArray(2048), 20000uL)
        assertFalse(hub.measurements.value!!.referenceActive)
        hub.release()
        callback!!(FloatArray(2048), 21024uL)
        assertEquals(20000uL, hub.measurements.value!!.frame.samplePosition)
    }

    @Test
    fun `production context precedes analysis and input policy survives restart`() {
        val engine=FakeEngine();var deliver:((FloatArray,ULong)->Unit)?=null
        val source=object:PositionedPcmSource {override fun startPositioned(onFrame:(FloatArray,ULong)->Unit){deliver=onFrame};override fun stop(){}}
        val hub=CaptureHubCore(PitchEngineFactory{engine},{source},TunarCore.defaultConfig())
        val policy=initialInputPolicy().copy(response=ResponseMode.STEADY,hold=HoldMode.SECONDS15)
        hub.applyInputPolicy(policy);hub.acquire();assertEquals(policy,engine.policy)
        hub.requestCalibration();deliver!!(FloatArray(2048),8192uL)
        assertEquals(1,engine.calibrations);assertEquals(8192uL,engine.context!!.samplePosition)
        hub.setEnabled(false);assertFalse(hub.inputStatus.value.available);hub.setEnabled(true)
        assertEquals(policy,engine.policy);hub.release()
    }

    @Test
    fun `a stopped capture cannot publish a frame that was being analyzed`() {
        val engine=FakeEngine();var callback:((FloatArray,ULong)->Unit)?=null
        val source=object:PositionedPcmSource {override fun startPositioned(onFrame:(FloatArray,ULong)->Unit){callback=onFrame};override fun stop(){}}
        val hub=CaptureHubCore(PitchEngineFactory{engine},{source},TunarCore.defaultConfig())
        hub.acquire();engine.onAnalyze={hub.setEnabled(false)};callback!!(FloatArray(2048),8192uL)
        assertFalse(hub.running.value);assertFalse(hub.inputStatus.value.available);assertTrue(hub.measurements.value==null)
    }

    @Test
    fun `background stops hardware while retaining references`() {
        val engine = FakeEngine()
        val source = FakeSource()
        val hub = newHub(engine, source)
        hub.acquire()
        hub.setEnabled(false)
        assertFalse(hub.running.value)
        assertTrue(source.stopped)
        hub.acquire()
        assertFalse(hub.running.value)
        hub.setEnabled(true)
        assertTrue(hub.running.value)
        hub.release()
        assertTrue(hub.running.value)
        hub.release()
        assertFalse(hub.running.value)
    }

    @Test
    fun `source failure cannot be overwritten by startup and retry retains references`() {
        var starts = 0
        val source = object : PositionedPcmSource {
            var failure: (() -> Unit)? = null
            override fun setFailureHandler(handler: () -> Unit) { failure = handler }
            override fun startPositioned(onFrame: (FloatArray, ULong) -> Unit) {
                starts++
                if (starts == 1) failure!!()
            }
            override fun stop() { }
        }
        val hub = CaptureHubCore(PitchEngineFactory { FakeEngine() }, { source }, TunarCore.defaultConfig())
        hub.acquire(); hub.acquire()
        assertFalse(hub.running.value)
        hub.retry()
        assertEquals(2, starts)
        assertTrue(hub.running.value)
        hub.release()
        assertTrue(hub.running.value)
        hub.release()
        assertFalse(hub.running.value)
    }

    @Test
    fun `acquire 启动采集 release 归零停止`() {
        val engine = FakeEngine()
        val source = FakeSource()
        val hub = newHub(engine, source)

        hub.acquire()
        hub.acquire()
        assertTrue(source.started)
        assertTrue(hub.running.value)

        hub.release()
        assertFalse(source.stopped) // 还有 1 个引用
        hub.release()
        assertTrue(source.stopped)
        assertTrue(engine.closed)
        assertFalse(hub.running.value)
    }

    @Test
    fun `applyConfig 对运行中引擎即时下发`() {
        val engine = FakeEngine()
        val source = FakeSource()
        val hub = newHub(engine, source)
        hub.acquire()

        val config = TunarConfig(
            sampleRate = 44100.0,
            frameHopSamples = 1024u,
            a4Hz = 442.0,
            noiseGateDbfs = -45f,
            solfege = SolfegeSystem.CHINESE,
            key = KeyMode(tonicPc = 5u, mode = ModeKind.GONG),
            temperament = 19u,
        )
        hub.applyConfig(config)

        assertEquals(442.0, engine.recordedA4, 1e-9)
        assertEquals(SolfegeSystem.CHINESE, engine.solfege)
        assertEquals(ModeKind.GONG, engine.key?.mode)
        assertEquals(0f, engine.recordedGate, 1e-6f) // Unchanged compatibility config must not switch Auto to Manual.
        hub.applyConfig(config.copy(noiseGateDbfs=-52f))
        assertEquals(-52f,engine.recordedGate,1e-6f)
        assertEquals(19, engine.recordedTemperament)
        assertEquals(config.copy(noiseGateDbfs=-52f), hub.config.value)
    }
}
