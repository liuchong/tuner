package com.liuchong.tuner.ui.tuner

import com.liuchong.tuner.audio.TunerEventStream
import com.liuchong.tuner.corebinding.TunerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.tuner_core.AnalysisFrame
import uniffi.tuner_core.TunerConfig
import uniffi.tuner_core.TunerEvent

/** 假事件流：手动注入分析帧，记录 acquire/release。 */
class FakeStream(config: TunerConfig = TunerCore.defaultConfig()) : TunerEventStream {
    private val eventsFlow = MutableStateFlow<AnalysisFrame?>(null)
    override val events: StateFlow<AnalysisFrame?> = eventsFlow
    override val running = MutableStateFlow(false)
    override val config = MutableStateFlow(config)
    var acquireCount = 0
        private set
    var releaseCount = 0
        private set

    override fun acquire() {
        acquireCount++
        running.value = true
    }

    override fun release() {
        releaseCount++
        running.value = false
    }

    fun emit(frame: AnalysisFrame?) {
        eventsFlow.value = frame
    }

    /** 便捷：注入只有音高事件的帧。 */
    fun emitEvent(ev: TunerEvent?) {
        emit(
            ev?.let {
                AnalysisFrame(
                    tuner = it,
                    spectrumDb = FloatArray(64).toList(),
                    partials = emptyList(),
                    chord = null,
                )
            },
        )
    }
}

private fun a4Event() = TunerEvent(
    freqHz = 440.0,
    noteName = "A4",
    midi = 69,
    centsOff = -2.5,
    clarity = 0.95f,
    solfege = "6",
    temperament = 12u,
    temperamentStep = 0,
    temperamentCents = -2.5,
)

@OptIn(ExperimentalCoroutinesApi::class)
class TunerViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `授权后接入采集，事件映射为 Active 状态`() {
        val stream = FakeStream()
        val vm = TunerViewModel(stream = stream, clock = { 0L })

        assertEquals(0, stream.acquireCount)
        vm.startCapture()
        assertEquals(1, stream.acquireCount)

        stream.emitEvent(a4Event())
        val signal = vm.uiState.value.signal
        assertTrue(signal is TunerSignal.Active)
        val reading = (signal as TunerSignal.Active).reading
        assertEquals("A4", reading.noteName)
        assertEquals(440.0, reading.freqHz, 1e-9)
        assertEquals(-2.5, reading.centsOff, 1e-9)
        assertEquals(69, reading.midi)
        assertEquals("6", reading.solfege)
    }

    @Test
    fun `800ms 无信号回到 Listening（请发声）`() {
        val stream = FakeStream()
        var now = 0L
        val vm = TunerViewModel(stream = stream, clock = { now })

        vm.startCapture()
        stream.emitEvent(a4Event())
        assertTrue(vm.uiState.value.signal is TunerSignal.Active)

        now = 799L
        vm.onTick()
        assertTrue(vm.uiState.value.signal is TunerSignal.Active)

        now = 801L
        vm.onTick()
        assertEquals(TunerSignal.Listening, vm.uiState.value.signal)
    }

    @Test
    fun `无效帧（null 事件）不改变状态`() {
        val stream = FakeStream()
        val vm = TunerViewModel(stream = stream, clock = { 0L })

        vm.startCapture()
        stream.emitEvent(null)
        assertEquals(TunerSignal.Listening, vm.uiState.value.signal)
    }

    @Test
    fun `分析帧映射频谱泛音和弦状态`() {
        val stream = FakeStream()
        val vm = TunerViewModel(stream = stream, clock = { 0L })
        vm.startCapture()
        val partial = uniffi.tuner_core.Partial(
            freqHz = 880.0,
            magnitudeDb = -12f,
            harmonicIndex = 2u,
            noteName = "",
            centsOff = 0.0,
        )
        val spectrum = FloatArray(64) { -40f }.toList()
        stream.emit(
            AnalysisFrame(
                tuner = a4Event(),
                spectrumDb = spectrum,
                partials = listOf(partial),
                chord = "Amaj",
            ),
        )
        val s = vm.uiState.value
        assertTrue(s.signal is TunerSignal.Active)
        assertEquals(64, s.spectrumDb.size)
        assertEquals(-40f, s.spectrumDb[0], 1e-6f)
        assertEquals(1, s.partials.size)
        assertEquals(2, s.partials[0].harmonicIndex.toInt())
        assertEquals("Amaj", s.chord)
        // 超时后清空
        var now = 0L
        val vm2 = TunerViewModel(stream = stream, clock = { now })
        vm2.startCapture()
        stream.emit(
            AnalysisFrame(
                tuner = a4Event(),
                spectrumDb = spectrum,
                partials = listOf(partial),
                chord = "Amaj",
            ),
        )
        now = 900L
        vm2.onTick() // 900 > 800ms → 超时清空
        assertEquals(0, vm2.uiState.value.spectrumDb.size)
        assertTrue(vm2.uiState.value.partials.isEmpty())
        assertEquals(null, vm2.uiState.value.chord)
    }

    @Test
    fun `onCleared 释放采集`() {
        val stream = FakeStream()
        val vm = TunerViewModel(stream = stream, clock = { 0L })

        vm.startCapture()
        assertEquals(1, stream.acquireCount)
        // 重复 startCapture 不重复 acquire
        vm.startCapture()
        assertEquals(1, stream.acquireCount)

        vm.releaseCapture()
        assertEquals(1, stream.releaseCount)
    }
}
