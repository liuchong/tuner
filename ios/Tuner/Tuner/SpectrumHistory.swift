import Foundation

/// 专业频谱的固定容量展示历史；不参与音高判断。
final class SpectrumHistoryBuffer: ObservableObject {
    let binCount: Int
    let waterfallBinCount: Int
    let maxRows: Int
    let frameStride: Int

    @Published var isPaused = false
    @Published private(set) var revision = 0

    private(set) var waterfallData: [Float]
    private(set) var currentSpectrum: [Float]
    private(set) var peakSpectrum: [Float]
    private(set) var nextRow = 0
    private(set) var rowCount = 0
    private var frameCount = 0

    init(
        binCount: Int = 64,
        waterfallBinCount: Int = 96,
        maxRows: Int = 256,
        frameStride: Int = 2
    ) {
        self.binCount = binCount
        self.waterfallBinCount = waterfallBinCount
        self.maxRows = maxRows
        self.frameStride = frameStride
        waterfallData = Array(repeating: -80, count: waterfallBinCount * maxRows)
        currentSpectrum = Array(repeating: -80, count: binCount)
        peakSpectrum = Array(repeating: -80, count: binCount)
    }

    func accept(_ spectrumDb: [Float]) {
        guard !isPaused, spectrumDb.count == binCount else { return }
        for index in 0..<binCount {
            let value = min(0, max(-80, spectrumDb[index]))
            currentSpectrum[index] = value
            peakSpectrum[index] = max(value, peakSpectrum[index])
        }
        frameCount += 1
        if frameCount.isMultiple(of: frameStride) {
            let offset = nextRow * waterfallBinCount
            writeInterpolatedRow(at: offset)
            nextRow = (nextRow + 1) % maxRows
            rowCount = min(rowCount + 1, maxRows)
        }
        revision &+= 1
    }

    func rowsNewestFirst() -> [[Float]] {
        (0..<rowCount).map { age in
            let row = (nextRow - 1 - age + maxRows) % maxRows
            let offset = row * waterfallBinCount
            return Array(waterfallData[offset..<(offset + waterfallBinCount)])
        }
    }

    private func writeInterpolatedRow(at destinationOffset: Int) {
        guard waterfallBinCount > 1, binCount > 1 else {
            waterfallData[destinationOffset] = currentSpectrum[0]
            return
        }
        let sourceSpan = Float(binCount - 1)
        let destinationSpan = Float(waterfallBinCount - 1)
        for column in 0..<waterfallBinCount {
            let sourcePosition = Float(column) * sourceSpan / destinationSpan
            let lower = min(Int(sourcePosition), binCount - 1)
            let upper = min(lower + 1, binCount - 1)
            let fraction = sourcePosition - Float(lower)
            waterfallData[destinationOffset + column] =
                currentSpectrum[lower]
                + (currentSpectrum[upper] - currentSpectrum[lower]) * fraction
        }
    }
}
