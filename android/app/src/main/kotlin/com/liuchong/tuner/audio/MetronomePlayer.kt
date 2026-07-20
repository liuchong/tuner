package com.liuchong.tuner.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.liuchong.tuner.corebinding.MetronomeEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.tuner_core.TickAccent

/** 一次 tick 的 UI 事件（atMs 为预计呈现时刻，用于同步闪拍动画）。 */
data class TickEvent(
    val beatIndex: Int,
    val accent: TickAccent,
    val atMs: Long,
)

/** 节拍器播放器（写线程驱动 render → AudioTrack）。 */
interface MetronomePlayer {
    /** 最近的 tick 事件（tryEmit，不阻塞写线程）。 */
    val ticks: StateFlow<TickEvent?>

    /** 启动写线程（引擎须已 start）。 */
    fun startLoop(engine: MetronomeEngine)

    /** 停止写线程并等待退出。 */
    fun stopLoop()
}

/**
 * AudioTrack 播放实现（spec-audio §2）：
 * MODE_STREAM 阻塞写（天然背压，保持缓冲余量防欠载），URGENT_AUDIO 优先级；
 * tick 的 sample_offset + 已排队未播放采样换算呈现时刻后投递 UI。
 */
class AudioTrackMetronomePlayer(
    private val sampleRate: Int = 44100,
) : MetronomePlayer {

    private val _ticks = MutableStateFlow<TickEvent?>(null)
    override val ticks: StateFlow<TickEvent?> = _ticks.asStateFlow()

    @Volatile
    private var running = false
    private var thread: Thread? = null

    override fun startLoop(engine: MetronomeEngine) {
        check(!running) { "MetronomePlayer 已在运行" }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        // ≥2 个系统缓冲余量防欠载
        val track = AudioTrack(
            AudioManager.STREAM_MUSIC,
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuf * 2, 4096),
            AudioTrack.MODE_STREAM,
        )
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack 初始化失败" }
        val chunk = maxOf(track.bufferSizeInFrames, 512)

        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var writtenTotal = 0L
            try {
                track.play()
                while (running) {
                    val frame = engine.render(chunk)
                    // 先投递 tick 事件（带呈现时刻），再阻塞写
                    val queued = writtenTotal - track.playbackHeadPosition.toLong()
                    for (tick in frame.ticks) {
                        val delaySamples = queued + tick.sampleOffset.toLong()
                        val atMs = SystemClock.uptimeMillis() +
                            (delaySamples * 1000.0 / sampleRate).toLong().coerceAtLeast(0)
                        _ticks.tryEmit(TickEvent(tick.beatIndex.toInt(), tick.accent, atMs))
                    }
                    val pcm = frame.samples.toFloatArray()
                    var off = 0
                    while (off < pcm.size && running) {
                        val w = track.write(pcm, off, pcm.size - off, AudioTrack.WRITE_BLOCKING)
                        if (w < 0) {
                            Log.w(TAG, "AudioTrack write 错误: $w")
                            break
                        }
                        off += w
                        writtenTotal += w
                    }
                }
            } finally {
                track.pause()
                track.flush()
                track.release()
            }
        }.apply {
            name = "metronome-audio-writer"
            start()
        }
    }

    override fun stopLoop() {
        running = false
        thread?.join(1500)
        thread = null
        _ticks.value = null
    }

    private companion object {
        const val TAG = "MetronomePlayer"
    }
}
