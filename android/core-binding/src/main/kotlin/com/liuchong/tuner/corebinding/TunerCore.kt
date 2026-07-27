package com.liuchong.tuner.corebinding

import uniffi.tuner_core.AnalysisFrame
import uniffi.tuner_core.FingeringChart
import uniffi.tuner_core.Instrument
import uniffi.tuner_core.KeyMode
import uniffi.tuner_core.MetronomeConfig
import uniffi.tuner_core.ModeKind
import uniffi.tuner_core.ReferenceTone
import uniffi.tuner_core.SolfegeSystem
import uniffi.tuner_core.TunerConfig
import uniffi.tuner_core.TunerEngine
import uniffi.tuner_core.TunerEvent
import uniffi.tuner_core.Tuning
import uniffi.tuner_core.listFingeringCharts
import uniffi.tuner_core.listInstruments
import uniffi.tuner_core.listTunings

/**
 * 调音引擎门面接口（便于 JVM 单测 mock；业务实现全在 Rust core）。
 */
interface PitchEngine : AutoCloseable {
    /** 输入一帧 PCM（长度 ≥ 2048），返回音高事件；无效输入返回 null。 */
    fun feed(pcm: List<Float>): TunerEvent?

    /** 完整分析帧：feed 事件 + 频谱 + 泛音 + 和弦（v4）。 */
    fun analyze(pcm: List<Float>): AnalysisFrame

    /** 设置 A4 校准（415–466Hz）。 */
    fun setA4(hz: Double)

    /** 设置唱名体系与调式。 */
    fun setSolfege(system: SolfegeSystem, key: KeyMode)

    /** 设置噪声门限（dBFS）。 */
    fun setNoiseGate(dbfs: Float)

    /** 设置律制（12/19/24/31，v4）。 */
    fun setTemperament(divisions: Int)

    override fun close()
}

/** 引擎工厂（app 注入真实实现，测试注入 fake）。 */
fun interface PitchEngineFactory {
    fun create(config: TunerConfig): PitchEngine
}

/**
 * core 全局函数门面（乐器预设查询、cents/唱名换算）。
 * 业务逻辑全在 Rust core；接口化便于 JVM 单测 fake。
 */
interface TunerCoreApi {
    fun instruments(): List<Instrument>

    fun tunings(instrumentId: String): List<Tuning>

    fun fingeringCharts(instrumentId: String): List<FingeringChart>

    /** 两频率间的音分差 1200·log2(freq/target)；无效输入返回 null。 */
    fun centsBetween(freqHz: Double, targetHz: Double): Double?
}

/** UniFFI 绑定之上的对外门面。 */
object TunerCore : TunerCoreApi {
    /** 真实引擎工厂。 */
    val engineFactory = PitchEngineFactory { config ->
        object : PitchEngine {
            private val inner = TunerEngine(config)

            override fun feed(pcm: List<Float>): TunerEvent? = inner.feed(pcm)
            override fun analyze(pcm: List<Float>) = inner.analyze(pcm)
            override fun setA4(hz: Double) = inner.setA4(hz)
            override fun setSolfege(system: SolfegeSystem, key: KeyMode) =
                inner.setSolfege(system, key)

            override fun setNoiseGate(dbfs: Float) = inner.setNoiseGate(dbfs)
            override fun setTemperament(divisions: Int) =
                inner.setTemperament(divisions.toUByte())

            override fun close() = inner.close()
        }
    }

    /** 默认调音器配置（C 大调、简谱、A4=440、-45dBFS、12-TET）。 */
    fun defaultConfig(sampleRate: Double = 44100.0): TunerConfig =
        TunerConfig(
            sampleRate = sampleRate,
            frameHopSamples = 1024u,
            a4Hz = 440.0,
            noiseGateDbfs = -45.0f,
            solfege = SolfegeSystem.NUMBERED,
            key = KeyMode(tonicPc = 0u, mode = ModeKind.MAJOR),
            temperament = 12u,
        )

    /** 根据当前 A4 与律制从 Rust core 获取 80–1500Hz 固定音高表。 */
    fun referenceTones(config: TunerConfig): List<ReferenceTone> {
        val engine = TunerEngine(config)
        return try {
            engine.listReferenceTones()
        } finally {
            engine.close()
        }
    }

    override fun instruments(): List<Instrument> = listInstruments()

    override fun tunings(instrumentId: String): List<Tuning> = listTunings(instrumentId)

    override fun fingeringCharts(instrumentId: String): List<FingeringChart> =
        listFingeringCharts(instrumentId)

    override fun centsBetween(freqHz: Double, targetHz: Double): Double? =
        uniffi.tuner_core.centsBetween(freqHz, targetHz)

    /** 创建节拍器引擎（见 MetronomeEngine.kt 门面）。 */
    fun createMetronome(config: MetronomeConfig): MetronomeEngine =
        uniffiMetronomeFactory.create(config)
}
