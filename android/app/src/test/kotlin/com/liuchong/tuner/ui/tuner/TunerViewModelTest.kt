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
import uniffi.tuner_core.SignalState
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
    fun emitEvent(
        ev: TunerEvent?,
        signalState: SignalState = if (ev == null) SignalState.QUIET else SignalState.TRACKING,
        displayStrength: Float = if (ev == null) 0f else 1f,
        isHeld: Boolean = false,
    ) {
        emit(
            AnalysisFrame(
                tuner = ev,
                spectrumDb = FloatArray(64).toList(),
                partials = emptyList(),
                chord = null,
                signalState = signalState,
                inputLevelDbfs = if (ev == null) -120f else -24f,
                displayStrength = displayStrength,
                isHeld = isHeld,
            ),
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
        val vm = TunerViewModel(stream = stream)

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
    fun `保持与清空完全服从 core 状态，不使用本地超时`() {
        val stream = FakeStream()
        val vm = TunerViewModel(stream = stream)

        vm.startCapture()
        stream.emitEvent(a4Event())
        assertTrue(vm.uiState.value.signal is TunerSignal.Active)

        stream.emitEvent(
            a4Event(),
            signalState = SignalState.HOLDING,
            displayStrength = 0.4f,
            isHeld = true,
        )
        assertTrue(vm.uiState.value.signal is TunerSignal.Active)
        assertEquals(0.4f, vm.uiState.value.displayStrength, 1e-6f)
        assertTrue(vm.uiState.value.isHeld)

        stream.emitEvent(null, signalState = SignalState.QUIET)
        assertEquals(TunerSignal.Listening, vm.uiState.value.signal)
        assertEquals(0f, vm.uiState.value.displayStrength, 1e-6f)
    }

    @Test
    fun `无效帧（null 事件）不改变状态`() {
        val stream = FakeStream()
        val vm = TunerViewModel(stream = stream)

        vm.startCapture()
        stream.emitEvent(null)
        assertEquals(TunerSignal.Listening, vm.uiState.value.signal)
    }

    @Test
    fun `分析帧映射频谱泛音和弦状态`() {
        val stream = FakeStream()
        val vm = TunerViewModel(stream = stream)
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
                signalState = SignalState.TRACKING,
                inputLevelDbfs = -18f,
                displayStrength = 1f,
                isHeld = false,
            ),
        )
        val s = vm.uiState.value
        assertTrue(s.signal is TunerSignal.Active)
        assertEquals(64, s.spectrumDb.size)
        assertEquals(-40f, s.spectrumDb[0], 1e-6f)
        assertEquals(1, s.partials.size)
        assertEquals(2, s.partials[0].harmonicIndex.toInt())
        assertEquals("Amaj", s.chord)
        assertEquals(-40f, s.displaySpectrumDb[0], 1e-6f)
        assertEquals(1, s.displayPartials.size)
        assertEquals("Amaj", s.displayChord)
        assertEquals(-18f, s.inputLevelDbfs, 1e-6f)

        // Holding 帧继续更新专业页原始频谱，但主调音频谱锁存上一次确认帧。
        val holdingSpectrum = FloatArray(64) { -75f }.toList()
        stream.emit(
            AnalysisFrame(
                tuner = a4Event(),
                spectrumDb = holdingSpectrum,
                partials = emptyList(),
                chord = null,
                signalState = SignalState.HOLDING,
                inputLevelDbfs = -75f,
                displayStrength = 1f,
                isHeld = true,
            ),
        )
        assertTrue(vm.uiState.value.signal is TunerSignal.Active)
        assertEquals(64, vm.uiState.value.spectrumDb.size)
        assertTrue(vm.uiState.value.partials.isEmpty())
        assertEquals(null, vm.uiState.value.chord)
        assertEquals(-75f, vm.uiState.value.spectrumDb[0], 1e-6f)
        assertEquals(-40f, vm.uiState.value.displaySpectrumDb[0], 1e-6f)
        assertEquals(1, vm.uiState.value.displayPartials.size)
        assertEquals("Amaj", vm.uiState.value.displayChord)
    }

    @Test
    fun `onCleared 释放采集`() {
        val stream = FakeStream()
        val vm = TunerViewModel(stream = stream)

        vm.startCapture()
        assertEquals(1, stream.acquireCount)
        // 重复 startCapture 不重复 acquire
        vm.startCapture()
        assertEquals(1, stream.acquireCount)

        vm.releaseCapture()
        assertEquals(1, stream.releaseCount)
    }
}
