package com.liuchong.tuner.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeReferenceToneOutput : ReferenceToneOutput {
    val started = CountDownLatch(1)
    val written = CountDownLatch(1)
    val reachedTargetGain = CountDownLatch(1)
    val released = CountDownLatch(1)
    var writes = 0
    @Volatile var maxAbs = 0f

    override fun play() {
        started.countDown()
    }

    override fun write(buffer: FloatArray): Int {
        writes++
        maxAbs = maxOf(maxAbs, buffer.maxOf { kotlin.math.abs(it) })
        if (maxAbs >= 0.60f) reachedTargetGain.countDown()
        written.countDown()
        return buffer.size
    }

    override fun pause() = Unit
    override fun flush() = Unit
    override fun release() {
        released.countDown()
    }
}

private class FakeReferenceToneAudioFocus : ReferenceToneAudioFocus {
    var requested = 0
    var abandoned = 0
    val abandonedSignal = CountDownLatch(1)
    private var onLoss: (() -> Unit)? = null

    override fun request(onLoss: () -> Unit): Boolean {
        requested++
        this.onLoss = onLoss
        return true
    }

    override fun abandon() {
        abandoned++
        abandonedSignal.countDown()
    }

    fun loseFocus() {
        onLoss?.invoke()
    }
}

class ReferenceTonePlayerTest {
    @Test
    fun `构造和停止静置播放器不会创建音轨或写静音`() {
        var createCount = 0
        val player = AudioTrackReferenceTonePlayer(
            outputFactory = {
                createCount++
                FakeReferenceToneOutput()
            },
        )

        player.stop()
        assertEquals(0, createCount)
        player.close()
        assertEquals(0, createCount)
    }

    @Test
    fun `第一次播放才创建音轨且关闭后释放`() {
        val output = FakeReferenceToneOutput()
        val player = AudioTrackReferenceTonePlayer(outputFactory = { output })

        player.play(440.0)
        assertTrue(output.started.await(1, TimeUnit.SECONDS))
        assertTrue(output.written.await(1, TimeUnit.SECONDS))
        assertTrue(output.writes > 0)

        player.close()
        assertTrue(output.released.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `播放淡入后峰值接近零点六五且不削波`() {
        val output = FakeReferenceToneOutput()
        val player = AudioTrackReferenceTonePlayer(outputFactory = { output })

        player.play(440.0)
        assertTrue(output.reachedTargetGain.await(1, TimeUnit.SECONDS))
        assertTrue(output.maxAbs >= 0.60f)
        assertTrue(output.maxAbs <= 0.65f)

        player.close()
    }

    @Test
    fun `音频焦点丢失后淡出并释放播放资源`() {
        val output = FakeReferenceToneOutput()
        val focus = FakeReferenceToneAudioFocus()
        val player = AudioTrackReferenceTonePlayer(
            outputFactory = { output },
            audioFocus = focus,
        )

        player.play(440.0)
        assertTrue(output.started.await(1, TimeUnit.SECONDS))
        assertEquals(1, focus.requested)

        focus.loseFocus()
        assertTrue(output.released.await(1, TimeUnit.SECONDS))
        assertTrue(focus.abandonedSignal.await(1, TimeUnit.SECONDS))
        assertTrue(focus.abandoned > 0)

        player.close()
    }
}
