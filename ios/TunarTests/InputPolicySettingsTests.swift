import XCTest
#if os(macOS)
@testable import TunarMac
#else
@testable import Tunar
#endif
@MainActor
final class InputPolicySettingsTests: XCTestCase {
    func testLegacyGateMigratesToManualWithoutChangingValue() {
        let name = "Tunar.InputPolicyTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set(-52.0, forKey: "noiseGateDbfs")
        let store = SettingsStore(defaults: defaults, synchronizeEngine: false)
        XCTAssertEqual(store.gateMode, .manual)
        XCTAssertEqual(store.noiseGateDbfs, -52)
        store.responseMode = .steady
        store.holdMode = .seconds5
        let restored = SettingsStore(defaults: defaults, synchronizeEngine: false)
        XCTAssertEqual(restored.responseMode, .steady)
        XCTAssertEqual(restored.holdMode, .seconds5)
        XCTAssertEqual(restored.gateMode, .manual)
    }
    func testFreshPreferencesUseAutomaticGeneralAndRetainUntilNext() {
        let name = "Tunar.InputPolicyTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let store = SettingsStore(defaults: defaults, synchronizeEngine: false)
        XCTAssertEqual(store.gateMode, .auto)
        XCTAssertEqual(store.inputProfile, .general)
        XCTAssertEqual(store.responseMode, .balanced)
        XCTAssertEqual(store.holdMode, .untilNext)
    }
}
