package com.liuchong.tuner.ui.spectrum

import com.liuchong.tuner.ui.tuner.TunerReading
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import uniffi.tuner_core.Partial

internal const val PROFESSIONAL_SPECTRUM_MIN_HZ = 60.0
internal const val PROFESSIONAL_SPECTRUM_MAX_HZ = 2_400.0
internal const val PROFESSIONAL_WIDE_SPECTRUM_MIN_HZ = 20.0
internal const val PROFESSIONAL_SPECTRUM_FLOOR_DB = -80f

internal data class SpectrumAxisTick(
    val fraction: Float,
    val label: String,
)

internal data class SpectrumTracePoint(
    val x: Float,
    val y: Float,
)

internal data class PitchDisplayBounds(
    val minimum: Double,
    val maximum: Double,
)

internal enum class SpectrumHeatBand {
    BACKGROUND,
    INDIGO,
    VIOLET,
    CYAN,
    YELLOW,
    RED,
}

internal data class ProfessionalSpectrumMetrics(
    val note: String,
    val fundamental: String,
    val cents: String,
    val inputLevel: String,
    val strongestPeak: String,
    val chord: String,
)

internal fun professionalFrequencyTicks(): List<SpectrumAxisTick> =
    listOf(
        60.0 to "60",
        100.0 to "100",
        200.0 to "200",
        500.0 to "500",
        1_000.0 to "1k",
        2_400.0 to "2.4k Hz",
    ).map { (frequency, label) ->
        SpectrumAxisTick(
            frequencyFraction(
                frequency,
                PROFESSIONAL_SPECTRUM_MIN_HZ,
                PROFESSIONAL_SPECTRUM_MAX_HZ,
            ).toFloat(),
            label,
        )
    }

internal fun professionalWideFrequencyTicks(maxHz: Double): List<SpectrumAxisTick> {
    val validMax = maxHz.takeIf {
        it.isFinite() && it > PROFESSIONAL_WIDE_SPECTRUM_MIN_HZ
    } ?: PROFESSIONAL_WIDE_SPECTRUM_MIN_HZ * 2.0
    val frequencies = buildList {
        add(PROFESSIONAL_WIDE_SPECTRUM_MIN_HZ)
        listOf(100.0, 500.0, 1_000.0, 5_000.0)
            .filterTo(this) { it < validMax }
        if (last() != validMax) add(validMax)
    }
    return frequencies.mapIndexed { index, frequency ->
        SpectrumAxisTick(
            frequencyFraction(
                frequency,
                PROFESSIONAL_WIDE_SPECTRUM_MIN_HZ,
                validMax,
            ).toFloat(),
            frequencyLabel(frequency, suffix = index == frequencies.lastIndex),
        )
    }
}

internal fun professionalDbTicks(): List<SpectrumAxisTick> =
    listOf(
        0f to "0",
        -20f to "-20",
        -40f to "-40",
        -60f to "-60",
        -80f to "-80 dBFS",
    ).map { (db, label) ->
        SpectrumAxisTick(
            fraction = ((-db) / -PROFESSIONAL_SPECTRUM_FLOOR_DB).coerceIn(0f, 1f),
            label = label,
        )
    }

internal fun professionalTimeTicks(): List<SpectrumAxisTick> =
    listOf("现在", "-3秒", "-6秒", "-9秒", "-12秒")
        .mapIndexed { index, label -> SpectrumAxisTick(index / 4f, label) }

internal fun spectrumHeatBand(db: Float): SpectrumHeatBand =
    when {
        db <= -76f -> SpectrumHeatBand.BACKGROUND
        db <= -62f -> SpectrumHeatBand.INDIGO
        db <= -48f -> SpectrumHeatBand.VIOLET
        db <= -32f -> SpectrumHeatBand.CYAN
        db <= -14f -> SpectrumHeatBand.YELLOW
        else -> SpectrumHeatBand.RED
    }

internal fun spectrumTracePoints(
    values: FloatArray,
    width: Float,
    height: Float,
): List<SpectrumTracePoint> {
    if (values.size < 2 || width <= 0f || height <= 0f) return emptyList()
    val lastIndex = values.lastIndex.toFloat()
    return values.mapIndexed { index, db ->
        SpectrumTracePoint(
            x = width * index / lastIndex,
            y = height * (
                1f - (
                    (db - PROFESSIONAL_SPECTRUM_FLOOR_DB) /
                        -PROFESSIONAL_SPECTRUM_FLOOR_DB
                    ).coerceIn(0f, 1f)
                ),
        )
    }
}

internal fun pitchDisplayBounds(midiValues: List<Float>): PitchDisplayBounds {
    val finite = midiValues.filter(Float::isFinite)
    if (finite.isEmpty()) return PitchDisplayBounds(minimum = 63.0, maximum = 75.0)
    val rawMin = finite.min().toDouble()
    val rawMax = finite.max().toDouble()
    val center = (rawMin + rawMax) / 2.0
    val span = max(12.0, rawMax - rawMin + 4.0)
    return PitchDisplayBounds(
        minimum = center - span / 2.0,
        maximum = center + span / 2.0,
    )
}

internal fun professionalSpectrumMetrics(
    reading: TunerReading?,
    inputLevelDbfs: Float,
    partials: List<Partial>,
    chord: String?,
): ProfessionalSpectrumMetrics {
    val strongestPeak = partials.maxByOrNull { it.magnitudeDb }
    return ProfessionalSpectrumMetrics(
        note = reading?.noteName?.replace("#", "♯") ?: "—",
        fundamental = reading?.let { format("%.1f Hz", it.freqHz) } ?: "—",
        cents = reading?.let { format("%+.1f cents", it.centsOff) } ?: "—",
        inputLevel = format("%.1f dBFS", inputLevelDbfs),
        strongestPeak = strongestPeak?.let {
            format("%.1f Hz · %.1f dB", it.freqHz, it.magnitudeDb)
        } ?: "—",
        chord = chord ?: "—",
    )
}

internal fun frequencyFraction(
    frequencyHz: Double,
    minHz: Double = PROFESSIONAL_SPECTRUM_MIN_HZ,
    maxHz: Double = PROFESSIONAL_SPECTRUM_MAX_HZ,
): Double =
    (ln(
        frequencyHz.coerceIn(
            minHz,
            maxHz,
        ) / minHz,
    ) / ln(maxHz / minHz))
        .coerceIn(0.0, 1.0)

private fun frequencyLabel(frequency: Double, suffix: Boolean): String {
    val base = if (frequency >= 1_000.0) {
        val kilo = frequency / 1_000.0
        if (kilo % 1.0 == 0.0) "${kilo.toInt()}k" else format("%.1fk", kilo)
    } else {
        frequency.toInt().toString()
    }
    return if (suffix) "$base Hz" else base
}

private fun format(pattern: String, vararg arguments: Any): String =
    String.format(Locale.US, pattern, *arguments)
