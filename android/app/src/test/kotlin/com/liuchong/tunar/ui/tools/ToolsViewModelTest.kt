package com.liuchong.tunar.ui.tools

import com.liuchong.tunar.audio.ReferenceTonePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.tunar_core.ToolTone

private class FakeTonePlayer : ReferenceTonePlayer {
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

private val antiMotion = ToolTone("anti_motion_sickness", "防晕车", "100Hz 纯正弦波", 100.0)
private val other = ToolTone("other", "其他", "200Hz 纯正弦波", 200.0)

class ToolsViewModelTest {
    @Test
    fun `点击防晕车播放 100Hz，再次点击停止`() {
        val player = FakeTonePlayer()
        val vm = ToolsViewModel(listOf(antiMotion), player)

        vm.toggle(antiMotion)
        assertEquals(listOf(100.0), player.played)
        assertEquals("anti_motion_sickness", vm.uiState.value.playingId)

        vm.toggle(antiMotion)
        assertNull(vm.uiState.value.playingId)
        assertEquals(1, player.stopCount)
    }

    @Test
    fun `切换工具直接换频率，不先停止`() {
        val player = FakeTonePlayer()
        val vm = ToolsViewModel(listOf(antiMotion, other), player)

        vm.toggle(antiMotion)
        vm.toggle(other)
        assertEquals(listOf(100.0, 200.0), player.played)
        assertEquals("other", vm.uiState.value.playingId)
        assertEquals(0, player.stopCount)
    }

    @Test
    fun `离开页面停止播放，未播放时不重复停止`() {
        val player = FakeTonePlayer()
        val vm = ToolsViewModel(listOf(antiMotion), player)

        vm.stop()
        assertEquals(0, player.stopCount)

        vm.toggle(antiMotion)
        vm.stop()
        assertNull(vm.uiState.value.playingId)
        assertEquals(1, player.stopCount)
    }
}
