package com.liuchong.tunar.ui.metronome

import com.liuchong.tunar.corebinding.TickSoundKind
import com.liuchong.tunar.corebinding.TickSounds
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TickSoundsTest {
    @Test
    fun `十二种音色按常用程度排序且标签一致`() {
        val expected = listOf(
            "CLICK" to "机械节拍",
            "WOOD_BLOCK" to "木块",
            "BEEP" to "电子滴声",
            "CLAVES" to "拍板",
            "RIMSHOT" to "边鼓",
            "SNARE" to "小鼓",
            "COWBELL" to "牛铃",
            "HI_HAT" to "踩镲",
            "CLAP" to "拍手",
            "SHAKER" to "沙锤",
            "KICK" to "低鼓",
            "BELL" to "铃声",
        )

        assertEquals(expected, TickSoundKind.entries.map { it.name to it.label })
    }

    @Test
    fun `全部音色波形非空有限不削波且互不相同`() {
        val sounds = TickSounds.buildAll()

        assertEquals(TickSoundKind.entries.toSet(), sounds.keys)
        sounds.forEach { (_, samples) ->
            assertTrue(samples.isNotEmpty())
            assertTrue(samples.all(Float::isFinite))
            assertTrue(samples.maxOf { abs(it) } <= 0.95f)
        }
        val waveforms = sounds.values.toList()
        for (left in waveforms.indices) {
            for (right in left + 1 until waveforms.size) {
                assertNotEquals(waveforms[left], waveforms[right])
            }
        }
    }

    @Test
    fun `音色时长与双端参数表一致`() {
        val sampleCounts = TickSoundKind.entries.associateWith {
            TickSounds.synthesize(it).size
        }

        assertEquals(529, sampleCounts[TickSoundKind.CLICK])
        assertEquals(3969, sampleCounts[TickSoundKind.WOOD_BLOCK])
        assertEquals(3528, sampleCounts[TickSoundKind.BEEP])
        assertEquals(2205, sampleCounts[TickSoundKind.CLAVES])
        assertEquals(2646, sampleCounts[TickSoundKind.RIMSHOT])
        assertEquals(5292, sampleCounts[TickSoundKind.SNARE])
        assertEquals(7938, sampleCounts[TickSoundKind.COWBELL])
        assertEquals(4410, sampleCounts[TickSoundKind.HI_HAT])
        assertEquals(6615, sampleCounts[TickSoundKind.CLAP])
        assertEquals(5292, sampleCounts[TickSoundKind.SHAKER])
        assertEquals(7938, sampleCounts[TickSoundKind.KICK])
        assertEquals(11025, sampleCounts[TickSoundKind.BELL])
    }
}
