package com.liuchong.tunar.audio

/** PCM 音频源（真实实现为 AudioRecord 读线程；测试注入 fake）。 */
interface PcmSource {
    /** 启动采集；每凑满一窗（2048 采样）回调一次。 */
    fun start(onFrame: (FloatArray) -> Unit)

    fun stop()
}

/** 真实采集时间由采样计数提供；保留旧接口供测试替身和兼容源使用。 */
interface PositionedPcmSource : PcmSource {
    fun setFailureHandler(handler: () -> Unit) { }
    fun startPositioned(onFrame: (FloatArray, ULong) -> Unit)
    override fun start(onFrame: (FloatArray) -> Unit) = startPositioned { samples, _ -> onFrame(samples) }
}
