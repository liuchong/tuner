import Foundation

let professionalSpectrumMinHz = 60.0
let professionalSpectrumMaxHz = 2_400.0
let professionalWideSpectrumMinHz = 20.0
let professionalSpectrumFloorDb: Float = -80

struct SpectrumAxisTick: Equatable {
    let fraction: Double
    let label: String
}

struct PitchDisplayBounds: Equatable {
    let minimum: Double
    let maximum: Double
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
        SpectrumAxisTick(
            fraction: frequencyFraction(
                frequency,
                minHz: professionalSpectrumMinHz,
                maxHz: professionalSpectrumMaxHz
            ),
            label: label
        )
    }
}

func professionalWideFrequencyTicks(maxHz: Double) -> [SpectrumAxisTick] {
    let validMax = maxHz.isFinite && maxHz > professionalWideSpectrumMinHz
        ? maxHz : professionalWideSpectrumMinHz * 2
    var frequencies = [professionalWideSpectrumMinHz]
    frequencies.append(
        contentsOf: [100, 500, 1_000, 5_000].filter { $0 < validMax }
    )
    if frequencies.last != validMax {
        frequencies.append(validMax)
    }
    return frequencies.enumerated().map { index, frequency in
        SpectrumAxisTick(
            fraction: frequencyFraction(
                frequency,
                minHz: professionalWideSpectrumMinHz,
                maxHz: validMax
            ),
            label: frequencyLabel(
                frequency,
                suffix: index == frequencies.indices.last
            )
        )
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

func pitchDisplayBounds(_ midiValues: [Float]) -> PitchDisplayBounds {
    let finite = midiValues.filter(\.isFinite)
    guard let rawMin = finite.min(), let rawMax = finite.max() else {
        return PitchDisplayBounds(minimum: 63, maximum: 75)
    }
    let center = Double(rawMin + rawMax) / 2
    let span = max(12, Double(rawMax - rawMin) + 4)
    return PitchDisplayBounds(
        minimum: center - span / 2,
        maximum: center + span / 2
    )
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

func frequencyFraction(
    _ frequency: Double,
    minHz: Double = professionalSpectrumMinHz,
    maxHz: Double = professionalSpectrumMaxHz
) -> Double {
    min(
        1,
        max(
            0,
            log(
                min(maxHz, max(minHz, frequency)) / minHz
            ) / log(maxHz / minHz)
        )
    )
}

private func frequencyLabel(_ frequency: Double, suffix: Bool) -> String {
    let base: String
    if frequency >= 1_000 {
        let kilo = frequency / 1_000
        base = kilo.rounded() == kilo
            ? "\(Int(kilo))k"
            : String(format: "%.1fk", kilo)
    } else {
        base = "\(Int(frequency))"
    }
    return suffix ? base + " Hz" : base
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
