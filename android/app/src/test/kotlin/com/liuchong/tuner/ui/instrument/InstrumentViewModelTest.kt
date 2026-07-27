package com.liuchong.tuner.ui.instrument

import androidx.lifecycle.SavedStateHandle
import com.liuchong.tuner.corebinding.TunerCoreApi
import com.liuchong.tuner.ui.tuner.FakeStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uniffi.tuner_core.FingeringChart
import uniffi.tuner_core.FingeringNote
import uniffi.tuner_core.Instrument
import uniffi.tuner_core.InstrumentKind
import uniffi.tuner_core.KeyMode
import uniffi.tuner_core.SolfegeSystem
import uniffi.tuner_core.SignalState
import uniffi.tuner_core.StringSpec
import uniffi.tuner_core.TunerEvent
import uniffi.tuner_core.Tuning
import kotlin.math.abs
import kotlin.math.log2

/** 假 core 门面：固定预设数据；cents 公式仅测试夹具（非生产代码）。 */
private class FakeCoreApi : TunerCoreApi {
    override fun instruments() = listOf(
        Instrument("guitar", "吉他", InstrumentKind.STRING),
        Instrument("zhudi", "竹笛", InstrumentKind.WIND),
    )

    override fun tunings(instrumentId: String) = if (instrumentId == "guitar") {
        listOf(
            Tuning(
                "standard", "标准调弦",
                listOf(
                    StringSpec(1u, "E4", 64, 329.63, "3"),
                    StringSpec(2u, "B3", 59, 246.94, "7"),
                    StringSpec(3u, "G3", 55, 196.0, "5"),
                    StringSpec(4u, "D3", 50, 146.83, "2"),
                    StringSpec(5u, "A2", 45, 110.0, "6"),
                    StringSpec(6u, "E2", 40, 82.41, "3"),
                ),
            ),
        )
    } else {
        emptyList()
    }

    override fun fingeringCharts(instrumentId: String) = if (instrumentId == "zhudi") {
        listOf(
            FingeringChart(
                "d_qudi_sou5", "D调曲笛 · 筒音作5",
                listOf(
                    FingeringNote("筒音", "A2", 45, 110.0, "5"),
                    FingeringNote("开第一孔", "B2", 47, 123.47, "6"),
                    FingeringNote("开第一二孔", "C#3", 49, 138.59, "7"),
                ),
            ),
            FingeringChart(
                "d_qudi_zuo1", "D调曲笛 · 筒音作1",
                listOf(FingeringNote("筒音", "A2", 45, 110.0, "1")),
            ),
        )
    } else {
        emptyList()
    }

    override fun centsBetween(freqHz: Double, targetHz: Double): Double? =
        if (freqHz > 0 && targetHz > 0) 1200.0 * log2(freqHz / targetHz) else null
}

private fun event(freq: Double) = TunerEvent(
    freqHz = freq,
    noteName = "X",
    midi = 0,
    centsOff = 0.0,
    clarity = 0.9f,
    solfege = "",
    temperament = 12u,
    temperamentStep = 0,
    temperamentCents = 0.0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class InstrumentViewModelTest {

    private lateinit var stream: FakeStream
    private lateinit var savedState: SavedStateHandle

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        stream = FakeStream()
        savedState = SavedStateHandle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeVm() = InstrumentViewModel(
        core = FakeCoreApi(),
        stream = stream,
        savedState = savedState,
    )

    @Test
    fun `初始化加载乐器列表与默认吉他定弦`() {
        val vm = makeVm()
        val s = vm.uiState.value
        assertEquals(2, s.instruments.size)
        assertEquals("guitar", s.instrumentId)
        assertEquals(InstrumentKind.STRING, s.kind)
        assertEquals("standard", s.tuningId)
        assertEquals(6, s.strings.size)
        assertEquals("A2", s.strings[4].noteName)
        // 唱名直接用预设值（按乐器习惯调）
        assertEquals("6", s.strings[4].solfege)
        assertEquals(SelectionMode.AUTO, s.mode)
    }

    @Test
    fun `自动模式识别最接近的弦并高亮，准音打勾`() {
        val vm = makeVm()
        vm.startCapture()
        // 110.2Hz 接近 A2(110)，约 +3.1 cents
        stream.emitEvent(event(110.2))
        val s = vm.uiState.value
        val activeIdx = s.strings.indexOfFirst { it.active }
        assertEquals(4, activeIdx)
        assertEquals("A2", s.targetNoteName)
        assertTrue(abs(s.centsToTarget!! - 3.14f) < 0.1f)
        assertTrue(s.strings[4].inTune)
        // 其他弦不打勾
        assertFalse(s.strings[3].inTune)
    }

    @Test
    fun `手动模式锁定选中弦`() {
        val vm = makeVm()
        vm.startCapture()
        vm.selectString(0) // 锁定 1 弦 E4
        assertEquals(SelectionMode.MANUAL, vm.uiState.value.mode)
        // 吹/弹接近 A2 的音：高亮仍锁定在 1 弦，偏差相对 E4
        stream.emitEvent(event(110.2))
        val s = vm.uiState.value
        assertTrue(s.strings[0].active)
        assertFalse(s.strings[4].active)
        assertEquals("E4", s.targetNoteName)
        assertTrue(s.centsToTarget!! < -1000f)
    }

    @Test
    fun `保持与清空服从 core 的统一信号状态`() {
        val vm = makeVm()
        vm.startCapture()
        stream.emitEvent(event(110.2))
        assertTrue(vm.uiState.value.centsToTarget != null)

        stream.emitEvent(
            event(110.2),
            signalState = SignalState.HOLDING,
            displayStrength = 0.35f,
            isHeld = true,
        )
        assertTrue(vm.uiState.value.centsToTarget != null)
        assertEquals(0.35f, vm.uiState.value.displayStrength, 1e-6f)

        stream.emitEvent(null, signalState = SignalState.QUIET)
        assertNull(vm.uiState.value.centsToTarget)
        assertTrue(vm.uiState.value.strings.none { it.active || it.inTune })
    }

    @Test
    fun `管乐器：调性与筒音唱名选择、最近音高亮`() {
        val vm = makeVm()
        vm.startCapture()
        vm.selectInstrument("zhudi")
        var s = vm.uiState.value
        assertEquals(InstrumentKind.WIND, s.kind)
        assertEquals(listOf("D调曲笛"), s.chartGroups)
        assertEquals(listOf("5", "1"), s.tongyinOptions)
        assertEquals("5", s.tongyin)
        assertEquals(3, s.notes.size)
        assertEquals("D调曲笛", s.chartGroup)
        // 持久化
        assertEquals("zhudi", savedState.get<String>("instrumentId"))

        // 吹 123.5Hz（近 B2）→ 高亮「开第一孔」
        stream.emitEvent(event(123.5))
        s = vm.uiState.value
        assertEquals(1, s.notes.indexOfFirst { it.active })
        assertEquals("B2", s.targetNoteName)

        // 切换筒音作 1 → 列表切换
        vm.selectChart("D调曲笛", "1")
        s = vm.uiState.value
        assertEquals("1", s.tongyin)
        assertEquals(1, s.notes.size)
        assertEquals("筒音", s.notes[0].label)
    }

    @Test
    fun `预设唱名不随全局配置变化`() {
        val vm = makeVm()
        assertEquals("6", vm.uiState.value.strings[4].solfege)
        // 全局唱名设置变更不影响乐器面板的预设唱名（筒音/定弦意义锚定习惯调）
        stream.config.value = stream.config.value.copy(
            solfege = uniffi.tuner_core.SolfegeSystem.CHINESE,
        )
        assertEquals("6", vm.uiState.value.strings[4].solfege)
    }

    @Test
    fun `选择状态写入 SavedStateHandle`() {
        val vm = makeVm()
        vm.selectString(3)
        vm.selectInstrument("zhudi")
        assertEquals(3, savedState.get<Int>("stringIndex"))
        assertEquals("MANUAL", savedState.get<String>("mode"))
        assertEquals("zhudi", savedState.get<String>("instrumentId"))
    }
}
