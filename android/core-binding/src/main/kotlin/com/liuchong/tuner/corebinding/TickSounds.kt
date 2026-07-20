package com.liuchong.tuner.corebinding

import kotlin.math.exp
import kotlin.math.sin

/**
 * 节拍器音色（spec-audio §2）：程序化合成的 PCM 采样数据。
 *
 * 注：这是「资源数据」而非业务逻辑（节奏调度全在 Rust core）；
 * 44100Hz 单声道 float，长度约 100–250ms。
 */
enum class TickSoundKind(val label: String) {
    /** 机械 click：短促方波衰减。 */
    CLICK("机械 click"),

    /** 电子 beep：正弦衰减。 */
    BEEP("电子 beep"),

    /** 铃声：多谐波碰铃感衰减。 */
    BELL("铃声"),
}

object TickSounds {
    const val SAMPLE_RATE = 44100.0

    /** 合成指定音色（每次调用生成新数组；建议缓存）。 */
    fun synthesize(kind: TickSoundKind): List<Float> = when (kind) {
        TickSoundKind.CLICK -> synthClick()
        TickSoundKind.BEEP -> synthBeep()
        TickSoundKind.BELL -> synthBell()
    }

    /** 全部音色缓存（app 启动时构建一次）。 */
    fun buildAll(): Map<TickSoundKind, List<Float>> =
        TickSoundKind.entries.associateWith { synthesize(it) }

    /** 机械 click：2000Hz 方波，12ms，快速指数衰减。 */
    private fun synthClick(): List<Float> {
        val n = (SAMPLE_RATE * 0.012).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val square = if (sin(2.0 * Math.PI * 2000.0 * t) >= 0) 1.0 else -1.0
            val env = exp(-i / (n / 4.0))
            (0.8 * square * env).toFloat()
        }
    }

    /** 电子 beep：880Hz 正弦，80ms，指数衰减。 */
    private fun synthBeep(): List<Float> {
        val n = (SAMPLE_RATE * 0.08).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val env = exp(-i / (n / 5.0))
            (0.7 * sin(2.0 * Math.PI * 880.0 * t) * env).toFloat()
        }
    }

    /** 铃声：基频 1568Hz + 泛音列（碰铃感），250ms 指数衰减。 */
    private fun synthBell(): List<Float> {
        val n = (SAMPLE_RATE * 0.25).toInt()
        // 基频 + 非谐波泛音（幅度递减）
        val partials = listOf(1568.0 to 1.0, 2093.0 to 0.55, 2637.0 to 0.32, 3520.0 to 0.18)
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val env = exp(-i / (n / 4.0))
            val v = partials.sumOf { (f, a) -> a * sin(2.0 * Math.PI * f * t) } / 2.05
            (0.85 * v * env).toFloat()
        }
    }
}
