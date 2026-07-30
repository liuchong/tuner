import Foundation

/// 专业频谱的固定容量展示历史；不参与音高判断。
final class SpectrumHistoryBuffer: ObservableObject {
    let binCount: Int
    let wideBinCount: Int
    let waveformColumns: Int
    let waterfallBinCount: Int
    let maxRows: Int
    let frameStride: Int

    @Published var isPaused = false {
        didSet {
            if oldValue && !isPaused { needsTraceBreak = true }
        }
    }
    @Published private(set) var revision = 0

    private(set) var waterfallData: [Float]
    private(set) var currentSpectrum: [Float]
    private(set) var peakSpectrum: [Float]
    private(set) var currentWideSpectrum: [Float]
    private(set) var peakWideSpectrum: [Float]
    private(set) var waveformMin: [Float]
    private(set) var waveformMax: [Float]
    private(set) var pitchTrace: [PitchTracePoint] = []
    private(set) var nextRow = 0
    private(set) var rowCount = 0
    private var frameCount = 0
    private var traceSegment = 0
    private var needsTraceBreak = false

    struct PitchTracePoint: Equatable {
        let timeSeconds: Double
        let midi: Float
        let segment: Int
    }

    init(
        binCount: Int = 64,
        wideBinCount: Int = 128,
        waveformColumns: Int = 256,
        waterfallBinCount: Int = 96,
        maxRows: Int = 256,
        frameStride: Int = 2
    ) {
        self.binCount = binCount
        self.wideBinCount = wideBinCount
        self.waveformColumns = waveformColumns
        self.waterfallBinCount = waterfallBinCount
        self.maxRows = maxRows
        self.frameStride = frameStride
        waterfallData = Array(repeating: -80, count: waterfallBinCount * maxRows)
        currentSpectrum = Array(repeating: -80, count: binCount)
        peakSpectrum = Array(repeating: -80, count: binCount)
        currentWideSpectrum = Array(repeating: -80, count: wideBinCount)
        peakWideSpectrum = Array(repeating: -80, count: wideBinCount)
        waveformMin = Array(repeating: 0, count: waveformColumns)
        waveformMax = Array(repeating: 0, count: waveformColumns)
    }

    func accept(_ spectrumDb: [Float]) {
        guard !isPaused, spectrumDb.count == binCount else { return }
        acceptSpectrum(spectrumDb)
        advanceWaterfall()
        revision &+= 1
    }

    func acceptAnalysis(
        spectrumDb: [Float],
        wideSpectrumDb: [Float],
        waveformMin: [Float],
        waveformMax: [Float],
        samplePosition: UInt64,
        sampleRateHz: Double,
        trackingMidi: Float?
    ) {
        guard !isPaused,
              spectrumDb.count == binCount,
              wideSpectrumDb.count == wideBinCount,
              waveformMin.count == waveformColumns,
              waveformMax.count == waveformColumns else { return }
        acceptSpectrum(spectrumDb)
        for index in 0..<wideBinCount {
            let value = min(0, max(-80, wideSpectrumDb[index]))
            currentWideSpectrum[index] = value
            peakWideSpectrum[index] = max(value, peakWideSpectrum[index])
        }
        self.waveformMin = waveformMin.map { $0.isFinite ? $0 : 0 }
        self.waveformMax = waveformMax.map { $0.isFinite ? $0 : 0 }
        acceptPitch(
            samplePosition: samplePosition,
            sampleRateHz: sampleRateHz,
            trackingMidi: trackingMidi
        )
        advanceWaterfall()
        revision &+= 1
    }

    private func acceptSpectrum(_ spectrumDb: [Float]) {
        for index in 0..<binCount {
            let value = min(0, max(-80, spectrumDb[index]))
            currentSpectrum[index] = value
            peakSpectrum[index] = max(value, peakSpectrum[index])
        }
    }

    private func advanceWaterfall() {
        frameCount += 1
        if frameCount.isMultiple(of: frameStride) {
            let offset = nextRow * waterfallBinCount
            writeInterpolatedRow(at: offset)
            nextRow = (nextRow + 1) % maxRows
            rowCount = min(rowCount + 1, maxRows)
        }
    }

    private func acceptPitch(
        samplePosition: UInt64,
        sampleRateHz: Double,
        trackingMidi: Float?
    ) {
        guard sampleRateHz.isFinite, sampleRateHz > 0,
              let trackingMidi, trackingMidi.isFinite else {
            needsTraceBreak = true
            return
        }
        if needsTraceBreak {
            traceSegment += 1
            needsTraceBreak = false
        }
        let time = Double(samplePosition) / sampleRateHz
        pitchTrace.append(PitchTracePoint(timeSeconds: time, midi: trackingMidi, segment: traceSegment))
        let cutoff = time - 12
        if let firstValid = pitchTrace.firstIndex(where: { $0.timeSeconds >= cutoff }),
           firstValid > pitchTrace.startIndex {
            pitchTrace.removeFirst(firstValid)
        }
    }

    func rowsNewestFirst() -> [[Float]] {
        (0..<rowCount).map { age in
            let row = (nextRow - 1 - age + maxRows) % maxRows
            let offset = row * waterfallBinCount
            return Array(waterfallData[offset..<(offset + waterfallBinCount)])
        }
    }

    /// 只清空峰值保持；实时频谱、瀑布图和暂停状态均保留。
    func resetPeakHold() {
        peakSpectrum = Array(repeating: -80, count: binCount)
        peakWideSpectrum = Array(repeating: -80, count: wideBinCount)
        revision &+= 1
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
