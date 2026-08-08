import XCTest
@testable import Tunar

final class TickSoundsTests: XCTestCase {
    func testTwelveSoundsUseTheConfirmedCommonOrderAndLabels() {
        let expected = [
            "click|机械节拍",
            "woodBlock|木块",
            "beep|电子滴声",
            "claves|拍板",
            "rimshot|边鼓",
            "snare|小鼓",
            "cowbell|牛铃",
            "hiHat|踩镲",
            "clap|拍手",
            "shaker|沙锤",
            "kick|低鼓",
            "bell|铃声",
        ]

        XCTAssertEqual(
            TickSoundKind.allCases.map { "\($0.rawValue)|\($0.label)" },
            expected
        )
    }

    func testAllWaveformsAreNonEmptyFiniteSafeAndDistinct() {
        let sounds = TickSounds.buildAll()

        XCTAssertEqual(Set(sounds.keys), Set(TickSoundKind.allCases))
        for samples in sounds.values {
            XCTAssertFalse(samples.isEmpty)
            XCTAssertTrue(samples.allSatisfy(\.isFinite))
            XCTAssertLessThanOrEqual(samples.map { abs($0) }.max() ?? 0, 0.95)
        }
        let waveforms = TickSoundKind.allCases.compactMap { sounds[$0] }
        for left in waveforms.indices {
            for right in waveforms.indices where right > left {
                XCTAssertNotEqual(waveforms[left], waveforms[right])
            }
        }
    }

    func testDurationsMatchTheCrossPlatformParameterTable() {
        let sampleCounts = Dictionary(
            uniqueKeysWithValues: TickSoundKind.allCases.map {
                ($0, TickSounds.synthesize($0).count)
            }
        )

        XCTAssertEqual(sampleCounts[.click], 529)
        XCTAssertEqual(sampleCounts[.woodBlock], 3969)
        XCTAssertEqual(sampleCounts[.beep], 3528)
        XCTAssertEqual(sampleCounts[.claves], 2205)
        XCTAssertEqual(sampleCounts[.rimshot], 2646)
        XCTAssertEqual(sampleCounts[.snare], 5292)
        XCTAssertEqual(sampleCounts[.cowbell], 7938)
        XCTAssertEqual(sampleCounts[.hiHat], 4410)
        XCTAssertEqual(sampleCounts[.clap], 6615)
        XCTAssertEqual(sampleCounts[.shaker], 5292)
        XCTAssertEqual(sampleCounts[.kick], 7938)
        XCTAssertEqual(sampleCounts[.bell], 11025)
    }
}
