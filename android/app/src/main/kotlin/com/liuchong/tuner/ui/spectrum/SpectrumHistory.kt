package com.liuchong.tuner.ui.spectrum

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uniffi.tuner_core.Partial

/**
 * 专业频谱的固定容量展示历史。
 *
 * 这是平台展示状态，不参与音高判断。所有数组在构造时分配；每两个分析帧写入一行，
 * 最新行绘制在瀑布图顶部。显示列由真实频谱桶插值得到，只用于细化色块。
 */
data class SpectrumHistoryState(
    val currentSpectrum: FloatArray = FloatArray(0),
    val peakSpectrum: FloatArray = FloatArray(0),
    val waterfall: FloatArray = FloatArray(0),
    val waterfallBinCount: Int = 0,
    val maxRows: Int = 0,
    val nextRow: Int = 0,
    val rowCount: Int = 0,
    val isPaused: Boolean = false,
)

class SpectrumHistory(
    private val binCount: Int = 64,
    private val waterfallBinCount: Int = 96,
    private val maxRows: Int = 256,
    private val frameStride: Int = 2,
) : ViewModel() {
    private val waterfall = FloatArray(waterfallBinCount * maxRows)
    private val live = FloatArray(binCount) { DB_FLOOR }
    private val peak = FloatArray(binCount) { DB_FLOOR }
    private var nextRow = 0
    private var rowCount = 0
    private var frameCount = 0

    private val _state = MutableStateFlow(SpectrumHistoryState())
    val state: StateFlow<SpectrumHistoryState> = _state.asStateFlow()

    var isPaused: Boolean = false
        set(value) {
            field = value
            publish()
        }

    fun accept(spectrumDb: List<Float>) {
        if (isPaused || spectrumDb.size != binCount) return
        for (index in 0 until binCount) {
            val value = spectrumDb[index].coerceIn(DB_FLOOR, 0f)
            live[index] = value
            peak[index] = maxOf(value, peak[index])
        }
        frameCount++
        var waterfallChanged = false
        if (frameCount % frameStride == 0) {
            val offset = nextRow * waterfallBinCount
            writeInterpolatedRow(offset)
            nextRow = (nextRow + 1) % maxRows
            rowCount = minOf(rowCount + 1, maxRows)
            waterfallChanged = true
        }
        publish(waterfallChanged)
    }

    fun currentSpectrum(): List<Float> = live.toList()

    fun peakSpectrum(): List<Float> = peak.toList()

    fun rowsNewestFirst(): List<List<Float>> = List(rowCount) { age ->
        val row = (nextRow - 1 - age + maxRows) % maxRows
        val offset = row * waterfallBinCount
        waterfall.copyOfRange(offset, offset + waterfallBinCount).toList()
    }

    private fun writeInterpolatedRow(destinationOffset: Int) {
        if (waterfallBinCount == 1 || binCount == 1) {
            waterfall[destinationOffset] = live[0]
            return
        }
        val sourceSpan = (binCount - 1).toFloat()
        val destinationSpan = (waterfallBinCount - 1).toFloat()
        for (column in 0 until waterfallBinCount) {
            val sourcePosition = column * sourceSpan / destinationSpan
            val lower = sourcePosition.toInt().coerceAtMost(binCount - 1)
            val upper = (lower + 1).coerceAtMost(binCount - 1)
            val fraction = sourcePosition - lower
            waterfall[destinationOffset + column] =
                live[lower] + (live[upper] - live[lower]) * fraction
        }
    }

    private fun publish(waterfallChanged: Boolean = false) {
        val previous = _state.value
        _state.value = SpectrumHistoryState(
            currentSpectrum = live.copyOf(),
            peakSpectrum = peak.copyOf(),
            waterfall = if (waterfallChanged) waterfall.copyOf() else previous.waterfall,
            waterfallBinCount = waterfallBinCount,
            maxRows = maxRows,
            nextRow = nextRow,
            rowCount = rowCount,
            isPaused = isPaused,
        )
    }

    private companion object {
        const val DB_FLOOR = -80f
    }
}

/** Android 列表的防御性唯一行标识，绝不以频率本身作为 Compose key。 */
data class ProfessionalSpectrumRow(
    val id: Int,
    val partial: Partial,
)

fun professionalSpectrumRows(partials: List<Partial>): List<ProfessionalSpectrumRow> =
    partials.mapIndexed { index, partial ->
        ProfessionalSpectrumRow(id = index, partial = partial)
    }
