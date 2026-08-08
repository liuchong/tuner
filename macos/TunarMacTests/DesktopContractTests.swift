import XCTest
@testable import TunarMac

final class DesktopContractTests: XCTestCase {
    func testFiveDestinationsUseTunerAsDefault() {
        XCTAssertEqual(DesktopSection.allCases.count, 5)
        XCTAssertEqual(DesktopSection.allCases.map(\.title), [
            "调音", "乐器", "专业分析", "节拍器", "设置",
        ])
        XCTAssertEqual(DesktopSection.defaultSelection, .tuner)
    }

    func testSpectrumPreviewRoutesToSharedAnalysisDestination() {
        var selection = DesktopSection.defaultSelection
        selection = DesktopNavigation.openAnalysis(from: selection)
        XCTAssertEqual(selection, .analysis)
    }

    func testDesktopLayoutUsesDocumentedBreakpoint() {
        XCTAssertEqual(DesktopLayout.columns(for: 1_280), 2)
        XCTAssertEqual(DesktopLayout.columns(for: 1_099), 1)
    }

    func testCaptureRunsOnlyWhileWindowIsActive() {
        XCTAssertEqual(DesktopCaptureLifecycle.action(isWindowActive: true), .acquire)
        XCTAssertEqual(DesktopCaptureLifecycle.action(isWindowActive: false), .release)
    }
}
