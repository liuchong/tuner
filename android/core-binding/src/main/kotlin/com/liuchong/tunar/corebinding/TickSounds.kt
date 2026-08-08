package com.liuchong.tunar.corebinding

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
    CLICK("机械节拍"),

    /** 木块：双谐振木质敲击。 */
    WOOD_BLOCK("木块"),

    /** 电子 beep：正弦衰减。 */
    BEEP("电子滴声"),

    /** 拍板：高频双谐振短音。 */
    CLAVES("拍板"),

    /** 边鼓：高频谐振叠加短噪声。 */
    RIMSHOT("边鼓"),

    /** 小鼓：低频鼓皮叠加噪声。 */
    SNARE("小鼓"),

    /** 牛铃：两组非整数倍谐振。 */
    COWBELL("牛铃"),

    /** 踩镲：高频金属噪声。 */
    HI_HAT("踩镲"),

    /** 拍手：多次短噪声脉冲。 */
    CLAP("拍手"),

    /** 沙锤：带短起音的高频噪声。 */
    SHAKER("沙锤"),

    /** 低鼓：由高到低扫频的短鼓声。 */
    KICK("低鼓"),

    /** 铃声：多谐波碰铃感衰减。 */
    BELL("铃声"),
}

object TickSounds {
    const val SAMPLE_RATE = 44100.0

    /** 合成指定音色（每次调用生成新数组；建议缓存）。 */
    fun synthesize(kind: TickSoundKind): List<Float> = when (kind) {
        TickSoundKind.CLICK -> synthClick()
        TickSoundKind.WOOD_BLOCK -> synthWoodBlock()
        TickSoundKind.BEEP -> synthBeep()
        TickSoundKind.CLAVES -> synthClaves()
        TickSoundKind.RIMSHOT -> synthRimshot()
        TickSoundKind.SNARE -> synthSnare()
        TickSoundKind.COWBELL -> synthCowbell()
        TickSoundKind.HI_HAT -> synthHiHat()
        TickSoundKind.CLAP -> synthClap()
        TickSoundKind.SHAKER -> synthShaker()
        TickSoundKind.KICK -> synthKick()
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

    /** 木块：820Hz + 1240Hz，90ms，快速双谐振衰减。 */
    private fun synthWoodBlock(): List<Float> {
        val n = (SAMPLE_RATE * 0.09).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val env = exp(-i / (n / 6.0))
            val resonances =
                0.72 * sin(2.0 * Math.PI * 820.0 * t) +
                    0.28 * sin(2.0 * Math.PI * 1240.0 * t)
            (0.85 * resonances * env).toFloat()
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

    /** 拍板：2400Hz + 3600Hz，50ms，极短谐振。 */
    private fun synthClaves(): List<Float> {
        val n = (SAMPLE_RATE * 0.05).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val env = exp(-i / (n / 5.0))
            val resonances =
                0.62 * sin(2.0 * Math.PI * 2400.0 * t) +
                    0.38 * sin(2.0 * Math.PI * 3600.0 * t)
            (0.88 * resonances * env).toFloat()
        }
    }

    /** 边鼓：1800Hz 谐振叠加确定性噪声，60ms。 */
    private fun synthRimshot(): List<Float> {
        val n = (SAMPLE_RATE * 0.06).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val env = exp(-i / (n / 6.0))
            val body = 0.55 * sin(2.0 * Math.PI * 1800.0 * t)
            val strike = 0.45 * noise(i)
            (0.88 * (body + strike) * env).toFloat()
        }
    }

    /** 小鼓：190Hz 鼓皮叠加宽带噪声，120ms。 */
    private fun synthSnare(): List<Float> {
        val n = (SAMPLE_RATE * 0.12).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val env = exp(-i / (n / 3.5))
            val body = 0.28 * sin(2.0 * Math.PI * 190.0 * t)
            val wires = 0.72 * noise(i)
            (0.84 * (body + wires) * env).toFloat()
        }
    }

    /** 牛铃：540Hz + 845Hz 非整数倍谐振，180ms。 */
    private fun synthCowbell(): List<Float> {
        val n = (SAMPLE_RATE * 0.18).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val env = exp(-i / (n / 3.0))
            val resonances =
                0.55 * sin(2.0 * Math.PI * 540.0 * t) +
                    0.45 * sin(2.0 * Math.PI * 845.0 * t)
            (0.88 * resonances * env).toFloat()
        }
    }

    /** 踩镲：确定性噪声叠加 6kHz / 9.3kHz 金属谐振，100ms。 */
    private fun synthHiHat(): List<Float> {
        val n = (SAMPLE_RATE * 0.10).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val env = exp(-i / (n / 4.0))
            val metal =
                0.45 * noise(i) +
                    0.30 * sin(2.0 * Math.PI * 6000.0 * t) +
                    0.25 * sin(2.0 * Math.PI * 9300.0 * t)
            (0.78 * metal * env).toFloat()
        }
    }

    /** 拍手：三次紧邻噪声脉冲叠加短尾音，150ms。 */
    private fun synthClap(): List<Float> {
        val n = (SAMPLE_RATE * 0.15).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val bursts =
                decayAfter(t, 0.0, 0.006) +
                    decayAfter(t, 0.018, 0.006) +
                    decayAfter(t, 0.036, 0.007) +
                    0.35 * decayAfter(t, 0.045, 0.035)
            (0.82 * noise(i) * bursts.coerceAtMost(1.0)).toFloat()
        }
    }

    /** 沙锤：8ms 起音、短衰减的高频差分噪声，120ms。 */
    private fun synthShaker(): List<Float> {
        val n = (SAMPLE_RATE * 0.12).toInt()
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val attack = (t / 0.008).coerceIn(0.0, 1.0)
            val env = attack * exp(-i / (n / 2.5))
            val previous = if (i == 0) 0.0 else noise(i - 1)
            val highNoise = (noise(i) - previous) * 0.5
            (0.78 * highNoise * env).toFloat()
        }
    }

    /** 低鼓：120Hz 扫至 48Hz，叠加极短击槌噪声，180ms。 */
    private fun synthKick(): List<Float> {
        val duration = 0.18
        val n = (SAMPLE_RATE * duration).toInt()
        val startHz = 120.0
        val endHz = 48.0
        val sweepPerSecond = (endHz - startHz) / duration
        return List(n) { i ->
            val t = i / SAMPLE_RATE
            val phase = 2.0 * Math.PI * (startHz * t + 0.5 * sweepPerSecond * t * t)
            val bodyEnv = exp(-i / (n / 4.0))
            val strikeEnv = exp(-i / (n / 40.0))
            val body = 0.78 * sin(phase) * bodyEnv
            val strike = 0.12 * noise(i) * strikeEnv
            (body + strike).toFloat()
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

    private fun decayAfter(t: Double, offset: Double, decaySeconds: Double): Double =
        if (t < offset) 0.0 else exp(-(t - offset) / decaySeconds)

    /** 固定 xorshift 序列；两端使用同一算法，避免随机音色和测试漂移。 */
    private fun noise(index: Int): Double {
        var value = (index + 1).toUInt()
        value = value xor (value shl 13)
        value = value xor (value shr 17)
        value = value xor (value shl 5)
        return (value and 0xFFFFu).toDouble() / 32767.5 - 1.0
    }
}
