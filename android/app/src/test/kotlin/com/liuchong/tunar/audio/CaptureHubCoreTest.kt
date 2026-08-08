package com.liuchong.tunar.audio

import com.liuchong.tunar.corebinding.PitchEngine
import com.liuchong.tunar.corebinding.PitchEngineFactory
import com.liuchong.tunar.corebinding.TunarCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertEquals(-45f, engine.recordedGate, 1e-6f)
        assertEquals(19, engine.recordedTemperament)
        assertEquals(config, hub.config.value)
    }
}
