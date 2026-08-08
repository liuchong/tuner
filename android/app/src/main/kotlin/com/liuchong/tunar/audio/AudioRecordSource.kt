package com.liuchong.tunar.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/**
 * AudioRecord 采集实现（spec-audio §1）：
 * 单声道 float PCM、44100Hz、窗口 2048 / hop 1024，专用读线程。
 * 读线程内不做 IO/网络/大对象分配；帧回调内仅做 core.feed 与 StateFlow.update。
 */
class AudioRecordSource(
    private val sampleRate: Int = 44100,
    private val windowSize: Int = 2048,
    private val hopSize: Int = 1024,
) : PcmSource {

    @Volatile
    private var running = false
    private var thread: Thread? = null

    // 权限由调用方保证（运行时申请通过后才启动）
    @SuppressLint("MissingPermission")
    override fun start(onFrame: (FloatArray) -> Unit) {
        check(!running) { "AudioRecordSource 已在运行" }
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            maxOf(minBuf, windowSize * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }

        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            // 预分配，循环内零分配
            val window = FloatArray(windowSize)
            val hop = FloatArray(hopSize)
            var filled = 0
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(hop, 0, hopSize, AudioRecord.READ_BLOCKING)
                    if (n <= 0) continue
                    // 滑动窗口：左移 n 个采样，填入新 hop
                    System.arraycopy(window, n, window, 0, windowSize - n)
                    System.arraycopy(hop, 0, window, windowSize - n, n)
                    filled += n
                    if (filled >= windowSize) {
                        // feed 同步消费（UniFFI marshal 会拷贝），下一 hop 才覆写，安全
                        onFrame(window)
                    }
                }
            } finally {
                record.stop()
                record.release()
            }
        }.apply {
            name = "tunar-audio-capture"
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }
}
