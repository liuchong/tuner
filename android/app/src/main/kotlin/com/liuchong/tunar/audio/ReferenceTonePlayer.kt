package com.liuchong.tunar.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import kotlin.math.PI
import kotlin.math.sin

/** 固定音高播放器；频率值由 Rust core 提供。 */
interface ReferenceTonePlayer : AutoCloseable {
    /** 播放或平滑切换到指定固定频率。 */
    fun play(frequencyHz: Double)

    /** 约 20ms 淡出后停止发声。 */
    fun stop()

    override fun close()
}

/** AudioTrack 的最小可替换边界，确保 JVM 测试可验证惰性启动。 */
internal interface ReferenceToneOutput {
    fun play()
    fun write(buffer: FloatArray): Int
    fun pause()
    fun flush()
    fun release()
}

internal interface ReferenceToneAudioFocus {
    fun request(onLoss: () -> Unit): Boolean
    fun abandon()
}

private object NoOpReferenceToneAudioFocus : ReferenceToneAudioFocus {
    override fun request(onLoss: () -> Unit) = true
    override fun abandon() = Unit
}

private fun referenceToneAudioAttributes(): AudioAttributes =
    AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

private class AndroidReferenceToneAudioFocus(context: Context) : ReferenceToneAudioFocus {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null
    private var request: AudioFocusRequest? = null

    override fun request(onLoss: () -> Unit): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) onLoss()
        }
        legacyListener = listener
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(referenceToneAudioAttributes())
                .setOnAudioFocusChangeListener(listener)
                .build()
            request = focusRequest
            manager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            request?.let(manager::abandonAudioFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            legacyListener?.let(manager::abandonAudioFocus)
        }
        request = null
        legacyListener = null
    }
}

private class AndroidReferenceToneOutput(
    private val track: AudioTrack,
) : ReferenceToneOutput {
    override fun play() = track.play()

    override fun write(buffer: FloatArray): Int =
        track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)

    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun release() = track.release()
}

/**
 * Android `AudioTrack` 正弦播放器。
 *
 * 首次点音高时才创建音轨和写线程；停止淡出完成后释放音轨，不在静置时持续写静音。
 * 音频写线程只复用预分配缓冲；换音时先淡出旧频率，再淡入新频率，避免爆音。
 */
class AudioTrackReferenceTonePlayer internal constructor(
    private val sampleRate: Int = 48_000,
    private val outputFactory: () -> ReferenceToneOutput = {
        AndroidReferenceToneOutput(buildTrack(sampleRate))
    },
    private val audioFocus: ReferenceToneAudioFocus = NoOpReferenceToneAudioFocus,
) : ReferenceTonePlayer {
    constructor(context: Context) : this(
        audioFocus = AndroidReferenceToneAudioFocus(context.applicationContext),
    )

    @Volatile
    private var requestedFrequencyHz = 0.0

    @Volatile
    private var closed = false
    @Volatile
    private var focusHeld = false

    private val lifecycleLock = Any()
    private var thread: Thread? = null

    override fun play(frequencyHz: Double) {
        if (!frequencyHz.isFinite() || frequencyHz <= 0.0 || closed) return
        if (!focusHeld) {
            if (!audioFocus.request(::stop)) return
            focusHeld = true
        }
        requestedFrequencyHz = frequencyHz
        ensureWorker()
    }

    override fun stop() {
        requestedFrequencyHz = 0.0
    }

    override fun close() {
        val activeThread = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            requestedFrequencyHz = 0.0
            thread
        }
        activeThread?.interrupt()
        runCatching { activeThread?.join(250) }
        abandonAudioFocus()
    }

    private fun writeLoop() {
        // 本机 JVM 单元测试没有 Android 线程优先级实现；真机上仍正常设置。
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val output = runCatching(outputFactory).getOrElse {
            requestedFrequencyHz = 0.0
            workerFinished()
            return
        }
        val buffer = FloatArray(BUFFER_SAMPLES)
        val fadeStep = 1f / (sampleRate * FADE_SECONDS).toFloat()
        var activeFrequency = 0.0
        var gain = 0f
        var phase = 0.0
        try {
            output.play()
            while (!closed) {
                for (i in buffer.indices) {
                    val requested = requestedFrequencyHz
                    if (requested != activeFrequency) {
                        if (gain > 0f) {
                            gain = (gain - fadeStep).coerceAtLeast(0f)
                        } else {
                            activeFrequency = requested
                            phase = 0.0
                        }
                    } else if (activeFrequency > 0.0) {
                        gain = (gain + fadeStep).coerceAtMost(MAX_GAIN)
                    } else {
                        gain = 0f
                    }

                    buffer[i] = if (activeFrequency > 0.0 && gain > 0f) {
                        val sample = sin(phase).toFloat() * gain
                        phase += 2.0 * PI * activeFrequency / sampleRate
                        if (phase >= 2.0 * PI) phase -= 2.0 * PI
                        sample
                    } else {
                        0f
                    }
                }
                if (activeFrequency == 0.0 && gain == 0f && requestedFrequencyHz == 0.0) {
                    break
                }
                if (output.write(buffer) < 0) {
                    requestedFrequencyHz = 0.0
                    break
                }
            }
        } finally {
            runCatching { output.pause() }
            runCatching { output.flush() }
            runCatching { output.release() }
            workerFinished()
        }
    }

    private fun ensureWorker() {
        synchronized(lifecycleLock) {
            if (closed || thread?.isAlive == true) return
            thread = Thread(::writeLoop, "Tunar-ReferenceTone").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun workerFinished() {
        val restart = synchronized(lifecycleLock) {
            if (thread === Thread.currentThread()) thread = null
            !closed && requestedFrequencyHz > 0.0
        }
        if (restart) {
            ensureWorker()
        } else {
            abandonAudioFocus()
        }
    }

    private fun abandonAudioFocus() {
        if (!focusHeld) return
        focusHeld = false
        audioFocus.abandon()
    }

    private companion object {
        fun buildTrack(sampleRate: Int): AudioTrack {
            val minBytes = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            val bufferBytes = maxOf(minBytes, BUFFER_SAMPLES * Float.SIZE_BYTES * 2)
            return AudioTrack.Builder()
                .setAudioAttributes(
                    referenceToneAudioAttributes(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
        }

        const val BUFFER_SAMPLES = 512
        const val FADE_SECONDS = 0.020
        const val MAX_GAIN = 0.65f
    }
}
