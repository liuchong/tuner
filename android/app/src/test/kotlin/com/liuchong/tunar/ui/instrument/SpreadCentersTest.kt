package com.liuchong.tunar.ui.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadCentersTest {
    private fun assertNoOverlap(ys: List<Float>, spacing: Float) {
        val sorted = ys.sorted()
        sorted.zipWithNext().forEach { (a, b) -> assertTrue("$a..$b", b - a >= spacing - 1e-3f) }
    }

    @Test
    fun `间距足够时保持在弦轴高度`() {
        val ys = spreadCenters(listOf(50f, 150f, 250f), spacing = 54f, minY = 25f, maxY = 300f)
        assertEquals(listOf(50f, 150f, 250f), ys)
    }

    @Test
    fun `相撞的按钮以理想高度均值为中心排开`() {
        val ys = spreadCenters(listOf(100f, 140f, 180f), spacing = 54f, minY = 25f, maxY = 400f)
        assertNoOverlap(ys, 54f)
        assertEquals(140f, ys.average().toFloat(), 1e-3f)
        assertEquals(86f, ys[0], 1e-3f)
    }

    @Test
    fun `返回顺序与输入一致，贴边时整体推回范围内`() {
        val ys = spreadCenters(listOf(40f, 10f, 20f), spacing = 54f, minY = 25f, maxY = 400f)
        assertNoOverlap(ys, 54f)
        assertTrue(ys.all { it in 25f..400f })
        assertTrue(ys[1] < ys[2] && ys[2] < ys[0])
        assertEquals(25f, ys[1], 1e-3f)
    }

    @Test
    fun `空间不足时均匀压缩且不越界`() {
        val ys = spreadCenters(listOf(50f, 60f, 70f), spacing = 54f, minY = 25f, maxY = 105f)
        assertEquals(listOf(25f, 65f, 105f), ys)
    }

    @Test
    fun `选中按钮层级最高，其次是自动识别的弦`() {
        assertTrue(buttonZIndex(selected = true, active = false) > buttonZIndex(selected = false, active = true))
        assertTrue(buttonZIndex(selected = false, active = true) > buttonZIndex(selected = false, active = false))
    }
}
