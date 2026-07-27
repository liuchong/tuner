import Foundation

let professionalSpectrumMinHz = 60.0
let professionalSpectrumMaxHz = 2_400.0
let professionalSpectrumFloorDb: Float = -80

struct SpectrumAxisTick: Equatable {
    let fraction: Double
    let label: String
}

enum SpectrumHeatBand: Equatable {
    case background
    case indigo
    case violet
    case cyan
    case yellow
    case red
}

struct ProfessionalSpectrumMetrics: Equatable {
    let note: String
    let fundamental: String
    let cents: String
    let inputLevel: String
    let strongestPeak: String
    let chord: String
}

func professionalFrequencyTicks() -> [SpectrumAxisTick] {
    [
        (60, "60"),
        (100, "100"),
        (200, "200"),
        (500, "500"),
        (1_000, "1k"),
        (2_400, "2.4k Hz"),
    ].map { frequency, label in
        SpectrumAxisTick(fraction: frequencyFraction(frequency), label: label)
    }
}

func professionalDbTicks() -> [SpectrumAxisTick] {
    [
        (0, "0"),
        (-20, "-20"),
        (-40, "-40"),
        (-60, "-60"),
        (-80, "-80 dBFS"),
    ].map { db, label in
        SpectrumAxisTick(
            fraction: min(1, max(0, Double(-db) / Double(-professionalSpectrumFloorDb))),
            label: label
        )
    }
}

func professionalTimeTicks() -> [SpectrumAxisTick] {
    ["现在", "-3秒", "-6秒", "-9秒", "-12秒"].enumerated().map { index, label in
        SpectrumAxisTick(fraction: Double(index) / 4, label: label)
    }
}

func spectrumHeatBand(_ db: Float) -> SpectrumHeatBand {
    switch db {
    case ...(-76): return .background
    case ...(-62): return .indigo
    case ...(-48): return .violet
    case ...(-32): return .cyan
    case ...(-14): return .yellow
    default: return .red
    }
}

func professionalSpectrumMetrics(
    reading: TunerReading?,
    inputLevelDbfs: Float,
    partials: [Partial],
    chord: String?
) -> ProfessionalSpectrumMetrics {
    let strongestPeak = partials.max { $0.magnitudeDb < $1.magnitudeDb }
    return ProfessionalSpectrumMetrics(
        note: reading?.noteName.replacingOccurrences(of: "#", with: "♯") ?? "—",
        fundamental: reading.map { oneDecimal($0.freqHz, suffix: " Hz") } ?? "—",
        cents: reading.map { oneDecimal($0.centsOff, prefix: "+", suffix: " cents") } ?? "—",
        inputLevel: oneDecimal(Double(inputLevelDbfs), suffix: " dBFS"),
        strongestPeak: strongestPeak.map {
            oneDecimal($0.freqHz, suffix: " Hz")
                + " · "
                + oneDecimal(Double($0.magnitudeDb), suffix: " dB")
        } ?? "—",
        chord: chord ?? "—"
    )
}

func frequencyFraction(_ frequency: Double) -> Double {
    min(
        1,
        max(
            0,
            log(
                min(professionalSpectrumMaxHz, max(professionalSpectrumMinHz, frequency))
                    / professionalSpectrumMinHz
            ) / log(professionalSpectrumMaxHz / professionalSpectrumMinHz)
        )
    )
}

private func oneDecimal(
    _ value: Double,
    prefix: String = "",
    suffix: String
) -> String {
    let rounded = (value * 10).rounded(.toNearestOrAwayFromZero) / 10
    let sign = prefix == "+" && rounded >= 0 ? "+" : ""
    return sign + String(format: "%.1f", rounded) + suffix
}
