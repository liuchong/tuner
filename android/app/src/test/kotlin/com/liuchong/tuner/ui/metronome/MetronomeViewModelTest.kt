package com.liuchong.tuner.ui.metronome

import androidx.lifecycle.SavedStateHandle
import com.liuchong.tuner.audio.MetronomePlayer
import com.liuchong.tuner.audio.TickEvent
import com.liuchong.tuner.corebinding.MetronomeEngine
import com.liuchong.tuner.corebinding.MetronomeEngineFactory
import com.liuchong.tuner.corebinding.TickSoundKind
import com.liuchong.tuner.corebinding.TickSounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.tuner_core.MetronomeConfig
import uniffi.tuner_core.RenderFrame
import uniffi.tuner_core.TickAccent

/** 假节拍器引擎：记录所有调用。 */
private class FakeMetronomeEngine : MetronomeEngine {
    var recordedBpm = 120.0
    var beats = 4
    var unit = 4
    var recordedAccents: List<TickAccent> = emptyList()
    var accentSoundLen = 0
    var normalSoundLen = 0
    var lastTapTs = 0L
    var tapReturn = 128.0
    var started = false
    var closed = false

    override fun render(frames: Int): RenderFrame =
        RenderFrame(List(frames) { 0f }, emptyList())

    override fun setBpm(bpm: Double) {
        this.recordedBpm = bpm
    }

    override fun setTimeSignature(beats: Int, unit: Int) {
        this.beats = beats
        this.unit = unit
    }

    override fun setAccents(accents: List<TickAccent>) {
        this.recordedAccents = accents
    }

    override fun setClickSamples(accent: List<Float>, normal: List<Float>) {
        accentSoundLen = accent.size
        normalSoundLen = normal.size
    }

    override fun tap(timestampSamples: Long): Double {
        lastTapTs = timestampSamples
        return tapReturn
    }

    override fun start(atSample: Long) {
        started = true
    }

    override fun stop() {
        started = false
    }

    override fun isRunning(): Boolean = started
    override fun close() {
        closed = true
    }
}

/** 假播放器：记录启停，手动注入 tick。 */
private class FakePlayer : MetronomePlayer {
    private val ticksFlow = MutableStateFlow<TickEvent?>(null)
    override val ticks: StateFlow<TickEvent?> = ticksFlow
    var loopRunning = false

    override fun startLoop(engine: MetronomeEngine) {
        loopRunning = true
    }

    override fun stopLoop() {
        loopRunning = false
    }

    fun emitTick(beat: Int, atMs: Long = 0L) {
        ticksFlow.value = TickEvent(beat, TickAccent.NORMAL, atMs)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MetronomeViewModelTest {

    private lateinit var engine: FakeMetronomeEngine
    private lateinit var player: FakePlayer

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        engine = FakeMetronomeEngine()
        player = FakePlayer()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm(
        savedState: SavedStateHandle = SavedStateHandle(),
        nowMs: () -> Long = { 0L },
    ) = MetronomeViewModel(
        engineFactory = MetronomeEngineFactory { engine },
        player = player,
        sounds = mapOf(
            TickSoundKind.BELL to List(100) { 0.1f },
            TickSoundKind.CLICK to List(50) { 0.1f },
            TickSoundKind.BEEP to List(80) { 0.1f },
        ),
        savedState = savedState,
        clock = nowMs,
    )

    @Test
    fun `BPM 钳制在 30-250 并即时下发引擎`() {
        val vm = makeVm()
        vm.setBpm(500.0)
        assertEquals(250.0, vm.uiState.value.bpm, 1e-9)
        assertEquals(250.0, engine.recordedBpm, 1e-9)
        vm.setBpm(10.0)
        assertEquals(30.0, vm.uiState.value.bpm, 1e-9)
        vm.adjustBpm(5)
        assertEquals(35.0, vm.uiState.value.bpm, 1e-9)
    }

    @Test
    fun `tap tempo 转发采样时间戳并更新 BPM`() {
        var now = 1000L
        val vm = makeVm(nowMs = { now })
        vm.tap()
        // 1000ms → 44100 采样
        assertEquals(44100L, engine.lastTapTs)
        assertEquals(128.0, vm.uiState.value.bpm, 1e-9)
        assertEquals(128.0, engine.recordedBpm, 1e-9)
    }

    @Test
    fun `重音圆点循环 重拍-普通-静音`() {
        val vm = makeVm()
        assertEquals(TickAccent.ACCENT, vm.uiState.value.accents[0])
        vm.cycleAccent(0)
        assertEquals(TickAccent.NORMAL, vm.uiState.value.accents[0])
        vm.cycleAccent(0)
        assertEquals(TickAccent.MUTED, vm.uiState.value.accents[0])
        vm.cycleAccent(0)
        assertEquals(TickAccent.ACCENT, vm.uiState.value.accents[0])
        // 引擎同步
        assertEquals(TickAccent.ACCENT, engine.recordedAccents[0])
    }

    @Test
    fun `拍号切换重置重音型为默认并下发`() {
        val vm = makeVm()
        vm.cycleAccent(1) // 改为重拍
        vm.setTimeSignature(6, 8)
        val s = vm.uiState.value
        assertEquals(6, s.beatsPerBar)
        assertEquals(8, s.beatUnit)
        assertEquals(6, s.accents.size)
        assertEquals(TickAccent.ACCENT, s.accents[0])
        assertTrue(s.accents.drop(1).all { it == TickAccent.NORMAL })
        assertEquals(6, engine.beats)
        assertEquals(8, engine.unit)
    }

    @Test
    fun `音色选择注入引擎`() {
        val vm = makeVm()
        // 初始默认：重拍铃声 100、弱拍 click 50
        assertEquals(100, engine.accentSoundLen)
        assertEquals(50, engine.normalSoundLen)
        vm.setNormalSound(TickSoundKind.BEEP)
        assertEquals(80, engine.normalSoundLen)
        vm.setAccentSound(TickSoundKind.CLICK)
        assertEquals(50, engine.accentSoundLen)
    }

    @Test
    fun `播放停止驱动引擎与播放器，tick 事件更新闪拍`() {
        val vm = makeVm()
        assertFalse(vm.uiState.value.playing)
        vm.play()
        assertTrue(vm.uiState.value.playing)
        assertTrue(engine.started)
        assertTrue(player.loopRunning)

        player.emitTick(2)
        assertEquals(2, vm.uiState.value.currentBeat)

        vm.pause()
        assertFalse(vm.uiState.value.playing)
        assertFalse(engine.started)
        assertFalse(player.loopRunning)
        assertEquals(-1, vm.uiState.value.currentBeat)
    }

    @Test
    fun `状态从 SavedStateHandle 恢复`() {
        val saved = SavedStateHandle().apply {
            set("bpm", 90.0)
            set("beats", 3)
            set("unit", 4)
            set("accents", arrayOf("ACCENT", "MUTED", "NORMAL"))
            set("accentSound", "BEEP")
        }
        val vm = makeVm(savedState = saved)
        val s = vm.uiState.value
        assertEquals(90.0, s.bpm, 1e-9)
        assertEquals(3, s.beatsPerBar)
        assertEquals(listOf(TickAccent.ACCENT, TickAccent.MUTED, TickAccent.NORMAL), s.accents)
        assertEquals(TickSoundKind.BEEP, s.accentSound)
    }
}
