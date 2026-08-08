package com.liuchong.tunar.ui.tuner

import com.liuchong.tunar.audio.ReferenceTonePlayer
import com.liuchong.tunar.corebinding.TunarCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.tunar_core.ReferenceTone
import uniffi.tunar_core.TunarConfig

private class FakeReferenceTonePlayer : ReferenceTonePlayer {
    val played = mutableListOf<Double>()
    var stopCount = 0

    override fun play(frequencyHz: Double) {
        played += frequencyHz
    }

    override fun stop() {
        stopCount++
    }

    override fun close() = stop()
}

private fun tones(config: TunarConfig) = listOf(
    ReferenceTone(-1, config.a4Hz * 0.95, config.temperament, "G#4", 0.0),
    ReferenceTone(0, config.a4Hz, config.temperament, "A4", 0.0),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TuningForkViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `打开浮窗使用当前 core 配置的固定音高表且不接管采集`() {
        val stream = FakeStream()
        val player = FakeReferenceTonePlayer()
        val vm = TuningForkViewModel(stream, ::tones, player)

        vm.open()
        assertTrue(vm.uiState.value.isOpen)
        assertEquals(2, vm.uiState.value.tones.size)
        assertEquals(440.0, vm.uiState.value.tones[1].frequencyHz, 1e-9)
        assertEquals(0, stream.acquireCount)
        assertEquals(0, stream.releaseCount)
    }

    @Test
    fun `同一音再次点击停止，切换音高调用平滑播放器`() {
        val stream = FakeStream()
        val player = FakeReferenceTonePlayer()
        val vm = TuningForkViewModel(stream, ::tones, player)
        val a4 = vm.uiState.value.tones[1]
        val lower = vm.uiState.value.tones[0]

        vm.toggle(a4)
        assertEquals(440.0, player.played.last(), 1e-9)
        assertEquals(0, vm.uiState.value.playingStep)

        vm.toggle(lower)
        assertEquals(lower.frequencyHz, player.played.last(), 1e-9)
        assertEquals(-1, vm.uiState.value.playingStep)

        vm.toggle(lower)
        assertNull(vm.uiState.value.playingStep)
        assertEquals(1, player.stopCount)
    }

    @Test
    fun `关闭浮窗继续播放且快捷按钮可停止和恢复上次音高`() {
        val stream = FakeStream()
        val player = FakeReferenceTonePlayer()
        val vm = TuningForkViewModel(stream, ::tones, player)
        vm.open()
        vm.toggle(vm.uiState.value.tones[1])

        vm.close()
        assertFalse(vm.uiState.value.isOpen)
        assertEquals(0, vm.uiState.value.playingStep)
        assertEquals(0, vm.uiState.value.selectedStep)
        assertEquals(0, player.stopCount)

        vm.toggleSelected()
        assertNull(vm.uiState.value.playingStep)
        assertEquals(1, player.stopCount)

        vm.toggleSelected()
        assertEquals(0, vm.uiState.value.playingStep)
        assertEquals(440.0, player.played.last(), 1e-9)
    }

    @Test
    fun `后台停止播放但保留选择，配置变化清除失效选择并刷新列表`() {
        val stream = FakeStream()
        val player = FakeReferenceTonePlayer()
        val vm = TuningForkViewModel(stream, ::tones, player)
        vm.toggle(vm.uiState.value.tones[1])

        vm.stopForBackground()
        assertNull(vm.uiState.value.playingStep)
        assertEquals(0, vm.uiState.value.selectedStep)
        assertEquals(1, player.stopCount)

        stream.config.value = TunarCore.defaultConfig().copy(
            a4Hz = 442.0,
            temperament = 19u,
        )
        assertEquals(442.0, vm.uiState.value.tones[1].frequencyHz, 1e-9)
        assertTrue(vm.uiState.value.tones.all { it.temperament == 19.toUByte() })
        assertNull(vm.uiState.value.selectedStep)
    }
}
