import XCTest
@testable import Tunar

final class SpectrumPresentationTests: XCTestCase {
    func testFrequencyDbAndTimeTicksCoverTheWholeScale() {
        XCTAssertEqual(
            professionalFrequencyTicks().map(\.label),
            ["60", "100", "200", "500", "1k", "2.4k Hz"]
        )
        XCTAssertEqual(professionalFrequencyTicks().first!.fraction, 0, accuracy: 0.000_001)
        XCTAssertEqual(professionalFrequencyTicks().last!.fraction, 1, accuracy: 0.000_001)
        XCTAssertTrue(
            zip(professionalFrequencyTicks(), professionalFrequencyTicks().dropFirst())
                .allSatisfy { $0.fraction < $1.fraction }
        )
        XCTAssertEqual(
            professionalDbTicks().map(\.label),
            ["0", "-20", "-40", "-60", "-80 dBFS"]
        )
        XCTAssertEqual(
            professionalTimeTicks().map(\.label),
            ["现在", "-3秒", "-6秒", "-9秒", "-12秒"]
        )
    }

    func testWideFrequencyTicksUseActualNyquistLimitAndLogSpacing() {
        let ticks = professionalWideFrequencyTicks(maxHz: 20_000)

        XCTAssertEqual(
            ticks.map(\.label),
            ["20", "100", "500", "1k", "5k", "20k Hz"]
        )
        XCTAssertEqual(ticks.first!.fraction, 0, accuracy: 0.000_001)
        XCTAssertEqual(ticks.last!.fraction, 1, accuracy: 0.000_001)
        XCTAssertTrue(zip(ticks, ticks.dropFirst()).allSatisfy { $0.fraction < $1.fraction })
        XCTAssertEqual(
            frequencyFraction(200, minHz: 20, maxHz: 2_000),
            0.5,
            accuracy: 0.000_000_001
        )
    }

    func testPitchDisplayBoundsCoverHistoryAndKeepAtLeastOneOctave() {
        let wide = pitchDisplayBounds([48, 72])
        XCTAssertEqual(wide.minimum, 46, accuracy: 0.000_001)
        XCTAssertEqual(wide.maximum, 74, accuracy: 0.000_001)

        let narrow = pitchDisplayBounds([69, 70])
        XCTAssertEqual(narrow.maximum - narrow.minimum, 12, accuracy: 0.000_001)
    }

    func testHeatBandsCoverBackgroundThroughRedStrongSignal() {
        XCTAssertEqual(spectrumHeatBand(-80), .background)
        XCTAssertEqual(spectrumHeatBand(-68), .indigo)
        XCTAssertEqual(spectrumHeatBand(-54), .violet)
        XCTAssertEqual(spectrumHeatBand(-40), .cyan)
        XCTAssertEqual(spectrumHeatBand(-22), .yellow)
        XCTAssertEqual(spectrumHeatBand(-5), .red)
    }

    func testSummaryKeepsSixMetricsAndUsesStrongestActualPeak() {
        let reading = TunerReading(
            noteName: "A4",
            freqHz: 440,
            centsOff: -1.25,
            midi: 69,
            clarity: 0.96,
            solfege: "6",
            temperament: 12,
            temperamentStep: 0,
            temperamentCents: -1.25
        )
        let weak = partial(880, -32, 2, "A5")
        let strongest = partial(440, -12.5, 1, "A4")

        let metrics = professionalSpectrumMetrics(
            reading: reading,
            inputLevelDbfs: -18.25,
            partials: [weak, strongest],
            chord: "A"
        )

        XCTAssertEqual(metrics.note, "A4")
        XCTAssertEqual(metrics.fundamental, "440.0 Hz")
        XCTAssertEqual(metrics.cents, "-1.3 cents")
        XCTAssertEqual(metrics.inputLevel, "-18.3 dBFS")
        XCTAssertEqual(metrics.strongestPeak, "440.0 Hz · -12.5 dB")
        XCTAssertEqual(metrics.chord, "A")
    }

    func testSummaryUsesPlaceholdersWithoutTrustedPitchOrPeaks() {
        let metrics = professionalSpectrumMetrics(
            reading: nil,
            inputLevelDbfs: -120,
            partials: [],
            chord: nil
        )

        XCTAssertEqual(metrics.note, "—")
        XCTAssertEqual(metrics.fundamental, "—")
        XCTAssertEqual(metrics.cents, "—")
        XCTAssertEqual(metrics.inputLevel, "-120.0 dBFS")
        XCTAssertEqual(metrics.strongestPeak, "—")
        XCTAssertEqual(metrics.chord, "—")
    }

    private func partial(
        _ frequencyHz: Double,
        _ magnitudeDb: Float,
        _ harmonicIndex: UInt8,
        _ noteName: String
    ) -> Partial {
        Partial(
            freqHz: frequencyHz,
            magnitudeDb: magnitudeDb,
            harmonicIndex: harmonicIndex,
            noteName: noteName,
            centsOff: 0
        )
    }
}
