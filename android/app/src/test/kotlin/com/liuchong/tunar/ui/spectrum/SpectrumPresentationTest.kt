package com.liuchong.tunar.ui.spectrum

import com.liuchong.tunar.ui.tuner.TunerReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.tunar_core.Partial

class SpectrumPresentationTest {
    @Test
    fun `频率分贝和时间刻度覆盖完整量程`() {
        assertEquals(
            listOf("60", "100", "200", "500", "1k", "2.4k Hz"),
            professionalFrequencyTicks().map { it.label },
        )
        assertEquals(0f, professionalFrequencyTicks().first().fraction, 1e-6f)
        assertEquals(1f, professionalFrequencyTicks().last().fraction, 1e-6f)
        assertTrue(
            professionalFrequencyTicks()
                .zipWithNext()
                .all { (left, right) -> left.fraction < right.fraction },
        )
        assertEquals(
            listOf("0", "-20", "-40", "-60", "-80 dBFS"),
            professionalDbTicks().map { it.label },
        )
        assertEquals(
            listOf("现在", "-3秒", "-6秒", "-9秒", "-12秒"),
            professionalTimeTicks().map { it.label },
        )
    }

    @Test
    fun `全频模式刻度按实际奈奎斯特上限生成且保持对数间距`() {
        val ticks = professionalWideFrequencyTicks(20_000.0)

        assertEquals(
            listOf("20", "100", "500", "1k", "5k", "20k Hz"),
            ticks.map { it.label },
        )
        assertEquals(0f, ticks.first().fraction, 1e-6f)
        assertEquals(1f, ticks.last().fraction, 1e-6f)
        assertTrue(ticks.zipWithNext().all { (left, right) -> left.fraction < right.fraction })
        assertEquals(
            0.5,
            frequencyFraction(200.0, minHz = 20.0, maxHz = 2_000.0),
            1e-9,
        )
    }

    @Test
    fun `音高轨迹纵轴覆盖完整历史跨度并至少保留一个八度`() {
        val wide = pitchDisplayBounds(listOf(48f, 72f))
        assertEquals(46.0, wide.minimum, 1e-9)
        assertEquals(74.0, wide.maximum, 1e-9)

        val narrow = pitchDisplayBounds(listOf(69f, 70f))
        assertEquals(12.0, narrow.maximum - narrow.minimum, 1e-9)
    }

    @Test
    fun `热力颜色等级从背景连续覆盖到红色强信号`() {
        assertEquals(SpectrumHeatBand.BACKGROUND, spectrumHeatBand(-80f))
        assertEquals(SpectrumHeatBand.INDIGO, spectrumHeatBand(-68f))
        assertEquals(SpectrumHeatBand.VIOLET, spectrumHeatBand(-54f))
        assertEquals(SpectrumHeatBand.CYAN, spectrumHeatBand(-40f))
        assertEquals(SpectrumHeatBand.YELLOW, spectrumHeatBand(-22f))
        assertEquals(SpectrumHeatBand.RED, spectrumHeatBand(-5f))
    }

    @Test
    fun `摘要保留六项关键读数并取最强实际峰`() {
        val reading = TunerReading(
            noteName = "A4",
            freqHz = 440.0,
            centsOff = -1.25,
            midi = 69,
            clarity = 0.96f,
            solfege = "6",
            temperament = 12,
            temperamentStep = 0,
            temperamentCents = -1.25,
        )
        val weak = partial(880.0, -32f, 2u.toUByte(), "A5")
        val strongest = partial(440.0, -12.5f, 1u.toUByte(), "A4")

        val metrics = professionalSpectrumMetrics(
            reading = reading,
            inputLevelDbfs = -18.25f,
            partials = listOf(weak, strongest),
            chord = "A",
        )

        assertEquals("A4", metrics.note)
        assertEquals("440.0 Hz", metrics.fundamental)
        assertEquals("-1.3 cents", metrics.cents)
        assertEquals("-18.3 dBFS", metrics.inputLevel)
        assertEquals("440.0 Hz · -12.5 dB", metrics.strongestPeak)
        assertEquals("A", metrics.chord)
    }

    @Test
    fun `没有可信音高和峰值时摘要不沿用错误值`() {
        val metrics = professionalSpectrumMetrics(
            reading = null,
            inputLevelDbfs = -120f,
            partials = emptyList(),
            chord = null,
        )

        assertEquals("—", metrics.note)
        assertEquals("—", metrics.fundamental)
        assertEquals("—", metrics.cents)
        assertEquals("-120.0 dBFS", metrics.inputLevel)
        assertEquals("—", metrics.strongestPeak)
        assertEquals("—", metrics.chord)
    }

    @Test
    fun `实时频谱分贝映射为跨越非零绘图区的可见折线`() {
        val points = spectrumTracePoints(
            values = floatArrayOf(-80f, -40f, 0f),
            width = 900f,
            height = 600f,
        )

        assertEquals(3, points.size)
        assertEquals(0f, points[0].x, 1e-6f)
        assertEquals(600f, points[0].y, 1e-6f)
        assertEquals(450f, points[1].x, 1e-6f)
        assertEquals(300f, points[1].y, 1e-6f)
        assertEquals(900f, points[2].x, 1e-6f)
        assertEquals(0f, points[2].y, 1e-6f)
        assertTrue(points.zipWithNext().all { (left, right) -> left.x < right.x })
    }

    private fun partial(
        frequencyHz: Double,
        magnitudeDb: Float,
        harmonicIndex: UByte,
        noteName: String,
    ) = Partial(
        freqHz = frequencyHz,
        magnitudeDb = magnitudeDb,
        harmonicIndex = harmonicIndex,
        noteName = noteName,
        centsOff = 0.0,
    )
}
