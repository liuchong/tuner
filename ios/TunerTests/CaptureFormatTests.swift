import XCTest
@testable import Tuner

final class CaptureFormatTests: XCTestCase {
    func testRejectsMissingHardwareInputFormatBeforeInstallingTap() {
        XCTAssertFalse(isUsableCaptureFormat(sampleRate: 0, channelCount: 2))
        XCTAssertFalse(isUsableCaptureFormat(sampleRate: 48_000, channelCount: 0))
        XCTAssertFalse(isUsableCaptureFormat(sampleRate: .nan, channelCount: 1))
        XCTAssertTrue(isUsableCaptureFormat(sampleRate: 48_000, channelCount: 1))
    }
}
