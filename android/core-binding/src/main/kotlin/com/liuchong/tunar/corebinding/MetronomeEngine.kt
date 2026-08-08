package com.liuchong.tunar.corebinding

import uniffi.tunar_core.Metronome
import uniffi.tunar_core.MetronomeConfig
import uniffi.tunar_core.RenderFrame
import uniffi.tunar_core.TickAccent

/**
 * 节拍器引擎门面接口（便于 JVM 单测 mock；节奏调度全在 Rust core）。
 * 参数类型用 Kotlin 原生类型，UniFFI 的 UInt/UByte/ULong 转换封装在实现内。
 */
interface MetronomeEngine : AutoCloseable {
    /** 渲染 frames 个采样（含 tick 混入），返回 PCM 与 tick 事件。 */
    fun render(frames: Int): RenderFrame

    /** 设置 BPM（30–250），下一采样生效。 */
    fun setBpm(bpm: Double)

    /** 设置拍号。 */
    fun setTimeSignature(beats: Int, unit: Int)

    /** 设置每拍重音型（长度 = beats_per_bar）。 */
    fun setAccents(accents: List<TickAccent>)

    /** 注入重拍/弱拍音色。 */
    fun setClickSamples(accent: List<Float>, normal: List<Float>)

    /** tap tempo：输入 tap 的采样时间戳，返回当前 BPM。 */
    fun tap(timestampSamples: Long): Double

    /** 从 atSample 开始运行。 */
    fun start(atSample: Long)

    /** 停止。 */
    fun stop()

    /** 是否运行中。 */
    fun isRunning(): Boolean

    override fun close()
}

/** 节拍器引擎工厂（app 注入真实实现，测试注入 fake）。 */
fun interface MetronomeEngineFactory {
    fun create(config: MetronomeConfig): MetronomeEngine
}

/** UniFFI 真实引擎工厂。 */
val uniffiMetronomeFactory = MetronomeEngineFactory { config ->
    object : MetronomeEngine {
        private val inner = Metronome(config)

        override fun render(frames: Int): RenderFrame = inner.render(frames.toUInt())
        override fun setBpm(bpm: Double) = inner.setBpm(bpm)
        override fun setTimeSignature(beats: Int, unit: Int) =
            inner.setTimeSignature(beats.toUByte(), unit.toUByte())

        override fun setAccents(accents: List<TickAccent>) = inner.setAccents(accents)
        override fun setClickSamples(accent: List<Float>, normal: List<Float>) =
            inner.setClickSamples(accent, normal)

        override fun tap(timestampSamples: Long): Double = inner.tap(timestampSamples.toULong())
        override fun start(atSample: Long) = inner.start(atSample.toULong())
        override fun stop() = inner.stop()
        override fun isRunning(): Boolean = inner.isRunning()
        override fun close() = inner.close()
    }
}
