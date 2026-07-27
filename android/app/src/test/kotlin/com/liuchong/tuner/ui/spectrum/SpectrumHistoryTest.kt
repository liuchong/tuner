package com.liuchong.tuner.ui.spectrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tuner_core.Partial

class SpectrumHistoryTest {
    @Test
    fun `默认每两帧写入最新一行且最多保留二百五十六行`() {
        val history = SpectrumHistory()

        repeat(520) { frame ->
            history.accept(List(64) { -(frame % 80).toFloat() })
        }

        val rows = history.rowsNewestFirst()
        assertEquals(256, rows.size)
        assertEquals(96, rows.first().size)
        assertEquals(-39f, rows.first()[0], 1e-6f)
        assertEquals(-9f, rows.last()[0], 1e-6f)
    }

    @Test
    fun `峰值保持只被更强信号刷新不会随时间下沉`() {
        val history = SpectrumHistory(
            binCount = 4,
            waterfallBinCount = 4,
            maxRows = 8,
            frameStride = 1,
        )
        history.accept(listOf(-20f, -30f, -40f, -50f))

        repeat(200) {
            history.accept(listOf(-70f, -70f, -70f, -70f))
        }

        assertEquals(listOf(-20f, -30f, -40f, -50f), history.peakSpectrum())

        history.accept(listOf(-10f, -35f, -39f, -80f))
        assertEquals(listOf(-10f, -30f, -39f, -50f), history.peakSpectrum())
    }

    @Test
    fun `瀑布图把真实频谱桶线性插值为更细显示列`() {
        val history = SpectrumHistory(
            binCount = 2,
            waterfallBinCount = 3,
            maxRows = 2,
            frameStride = 1,
        )

        history.accept(listOf(-80f, 0f))

        assertEquals(listOf(-80f, -40f, 0f), history.rowsNewestFirst().single())
    }

    @Test
    fun `暂停后实时曲线峰值线和瀑布图同时冻结`() {
        val history = SpectrumHistory(
            binCount = 4,
            waterfallBinCount = 4,
            maxRows = 8,
            frameStride = 1,
        )
        history.accept(listOf(-30f, -20f, -10f, -40f))
        val live = history.currentSpectrum()
        val peaks = history.peakSpectrum()
        val rows = history.rowsNewestFirst()

        history.isPaused = true
        history.accept(listOf(-5f, -5f, -5f, -5f))

        assertEquals(live, history.currentSpectrum())
        assertEquals(peaks, history.peakSpectrum())
        assertEquals(rows, history.rowsNewestFirst())
    }

    @Test
    fun `重置只清空峰值保持并保留实时瀑布和暂停状态`() {
        val history = SpectrumHistory(
            binCount = 4,
            waterfallBinCount = 4,
            maxRows = 8,
            frameStride = 1,
        )
        history.accept(listOf(-20f, -30f, -40f, -50f))
        val live = history.currentSpectrum()
        val rows = history.rowsNewestFirst()
        history.isPaused = true

        history.resetPeakHold()

        assertEquals(listOf(-80f, -80f, -80f, -80f), history.peakSpectrum())
        assertEquals(live, history.currentSpectrum())
        assertEquals(rows, history.rowsNewestFirst())
        assertTrue(history.isPaused)
        assertTrue(history.state.value.isPaused)

        history.isPaused = false
        history.accept(listOf(-60f, -50f, -40f, -30f))
        assertEquals(listOf(-60f, -50f, -40f, -30f), history.peakSpectrum())
    }

    @Test
    fun `重复频率仍生成唯一列表行标识`() {
        val duplicate = Partial(
            freqHz = 96.8994140625,
            magnitudeDb = -20f,
            harmonicIndex = 0u,
            noteName = "G2",
            centsOff = 0.0,
        )

        val rows = professionalSpectrumRows(listOf(duplicate, duplicate))

        assertEquals(2, rows.size)
        assertNotEquals(rows[0].id, rows[1].id)
        assertTrue(rows.all { it.partial.freqHz == duplicate.freqHz })
    }
}
