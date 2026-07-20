package com.liuchong.tuner.audio

import com.liuchong.tuner.corebinding.PitchEngine
import com.liuchong.tuner.corebinding.PitchEngineFactory
import com.liuchong.tuner.corebinding.TunerCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tuner_core.KeyMode
import uniffi.tuner_core.ModeKind
import uniffi.tuner_core.SolfegeSystem
import uniffi.tuner_core.TunerConfig
import uniffi.tuner_core.TunerEvent

private class FakeEngine : PitchEngine {
    var recordedA4 = 0.0
    var solfege: SolfegeSystem? = null
    var key: KeyMode? = null
    var recordedGate = 0f
    var recordedTemperament = 0
    var closed = false

    override fun feed(pcm: List<Float>): TunerEvent? = null

    override fun analyze(pcm: List<Float>) = uniffi.tuner_core.AnalysisFrame(
        tuner = null,
        spectrumDb = emptyList(),
        partials = emptyList(),
        chord = null,
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
        initialConfig = TunerCore.defaultConfig(),
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

        val config = TunerConfig(
            sampleRate = 44100.0,
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
