package com.liuchong.tunar.ui.instrument

import androidx.lifecycle.SavedStateHandle
import com.liuchong.tunar.corebinding.TunarCoreApi
import com.liuchong.tunar.ui.tuner.FakeStream
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
import uniffi.tunar_core.FingeringKind
import uniffi.tunar_core.FingeringChart
import uniffi.tunar_core.FingeringNote
import uniffi.tunar_core.FingeringScope
import uniffi.tunar_core.HoleMark
import uniffi.tunar_core.Instrument
import uniffi.tunar_core.InstrumentKind
import uniffi.tunar_core.KeyMode
import uniffi.tunar_core.SolfegeSystem
import uniffi.tunar_core.SignalState
import uniffi.tunar_core.StringSpec
import uniffi.tunar_core.TunarEvent
import uniffi.tunar_core.Tuning
import uniffi.tunar_core.TongyinOption
import uniffi.tunar_core.WindChart
import uniffi.tunar_core.WindFingering
import uniffi.tunar_core.WindRegister
import uniffi.tunar_core.WindVariant
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/** 假 core 门面：固定预设数据；cents 公式仅测试夹具（非生产代码）。 */
private class FakeCoreApi : TunarCoreApi {
    override fun instruments() = listOf(
        Instrument("guitar", "吉他", InstrumentKind.STRING),
        Instrument("zhudi", "竹笛", InstrumentKind.WIND),
        Instrument("dongxiao", "洞箫", InstrumentKind.WIND),
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
                    FingeringNote("筒音", "A4", 69, 440.0, "5"),
                    FingeringNote("开第一孔", "B4", 71, 493.88, "6"),
                    FingeringNote("开第一二孔", "C#5", 73, 554.37, "7"),
                ),
            ),
            FingeringChart(
                "d_qudi_zuo1", "D调曲笛 · 筒音作1",
                listOf(FingeringNote("筒音", "A4", 69, 440.0, "1")),
            ),
        )
    } else {
        emptyList()
    }

    override fun windVariants(instrumentId: String): List<WindVariant> =
        if (instrumentId == "dongxiao") {
            listOf(
                variant("g_xiao_x8", "g_xiao", "G调洞箫", 8),
                variant("g_xiao_x6", "g_xiao", "G调洞箫", 6),
                variant("d_xiao_x8", "d_xiao", "D调洞箫", 8),
                variant("d_xiao_x6", "d_xiao", "D调洞箫", 6),
            )
        } else {
            emptyList()
        }

    override fun windFingeringChart(
        variantId: String,
        tongyinDegree: UByte,
        scope: FingeringScope,
    ): WindChart? {
        val variant = windVariants("dongxiao").firstOrNull { it.id == variantId } ?: return null
        val degree = tongyinDegree.toInt() % 12
        val baseOffsets = if (scope == FingeringScope.SCALE) {
            listOf(0, 2, 4, 5, 7, 9, 11)
                .map { (it - degree).floorMod(12) }
                .sorted()
        } else {
            (0..11).toList()
        }
        val registers = listOf(
            WindRegister.LOW to 0,
            WindRegister.MIDDLE to 12,
            WindRegister.HIGH to 24,
        )
        val baseMidi = if (variant.keyId == "g_xiao") 55 else 50
        return WindChart(
            variantId = variant.id,
            variantName = variant.displayName,
            tongyinDegree = degree.toUByte(),
            tongyinSolfege = degree.toString(),
            tonicPc = (baseMidi - degree).floorMod(12).toUByte(),
            tonicName = if (variant.keyId == "g_xiao") "G" else "D",
            keyDisplay = "筒音作$degree · ${if (variant.keyId == "g_xiao") "G" else "D"}宫",
            holeCount = variant.holeCount,
            backHoleCount = variant.backHoleCount,
            notes = baseOffsets.flatMap { baseSemitones ->
                registers.mapNotNull { (register, registerOffset) ->
                    val semitones = baseSemitones + registerOffset
                    // 指法图只覆盖到相对筒音 31 半音，越界的高音格没有来源。
                    if (semitones > 31) return@mapNotNull null
                    val holeCount = variant.holeCount.toInt()
                    val baseHoles = List(holeCount) { index ->
                        when {
                            baseSemitones % 2 == 1 && index == 0 -> HoleMark.HALF
                            baseSemitones > 0 &&
                                index < baseSemitones % holeCount -> HoleMark.OPEN
                            else -> HoleMark.CLOSED
                        }
                    }
                    // 中音区沿用低音孔位；高音区孔位自成一套，这里以开背孔示意。
                    val holes = if (register == WindRegister.HIGH) {
                        baseHoles.mapIndexed { index, mark ->
                            if (index == holeCount - 1) HoleMark.OPEN else mark
                        }
                    } else {
                        baseHoles
                    }
                    WindFingering(
                        fingeringId = semitones,
                        semitones = semitones,
                        baseSemitones = baseSemitones,
                        register = register,
                        label = if (baseSemitones == 0) "筒音" else "指法$baseSemitones",
                        holes = holes,
                        fingeringKind = if (baseSemitones % 2 == 1) {
                            FingeringKind.COMBINATION
                        } else {
                            FingeringKind.SEQUENTIAL
                        },
                        anchorHole = holes
                            .indexOfLast { it != HoleMark.CLOSED }
                            .takeIf { it >= 0 }
                            ?.toUByte(),
                        noteName = "N${baseMidi + semitones}",
                        midi = baseMidi + semitones,
                        freqHz = 440.0 * 2.0.pow((baseMidi + semitones - 69) / 12.0),
                        solfege = (semitones + degree).floorMod(12).toString(),
                        inScale = baseSemitones in baseOffsets,
                        overblown = register != WindRegister.LOW,
                    )
                }
            },
        )
    }

    override fun centsBetween(freqHz: Double, targetHz: Double): Double? =
        if (freqHz > 0 && targetHz > 0) 1200.0 * log2(freqHz / targetHz) else null

    private fun variant(
        id: String,
        keyId: String,
        keyName: String,
        holes: Int,
    ) = WindVariant(
        id = id,
        displayName = "$keyName · ${holes}孔",
        keyId = keyId,
        keyName = keyName,
        holeSystemName = "${holes}孔",
        holeCount = holes.toUByte(),
        backHoleCount = 1u,
        fundamentalMidi = if (keyId == "g_xiao") 55 else 50,
        fundamentalNoteName = if (keyId == "g_xiao") "G3" else "D3",
        supportsTongyin = true,
        supportsChromatic = true,
        tongyinOptions = (0..11).map {
            TongyinOption(it.toUByte(), it.toString(), it in listOf(0, 2, 7))
        },
        defaultTongyinDegree = 7u,
    )
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus

private fun event(freq: Double) = TunarEvent(
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
        assertEquals(3, s.instruments.size)
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

        // 吹 493.9Hz（近 B4）→ 高亮「开第一孔」
        stream.emitEvent(event(493.9))
        s = vm.uiState.value
        assertEquals(1, s.notes.indexOfFirst { it.active })
        assertEquals("B4", s.targetNoteName)

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
            solfege = uniffi.tunar_core.SolfegeSystem.CHINESE,
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

    @Test
    fun `洞箫默认八孔并按低中高三音区各自的实测孔位展开`() {
        val vm = makeVm()
        vm.selectInstrument("dongxiao")

        val state = vm.uiState.value
        assertEquals("G调洞箫", state.keyName)
        assertEquals("8孔", state.holeSystem)
        assertEquals(8, state.holeCount)
        assertEquals(7, state.tongyinDegree)
        assertEquals(12, state.windTongyinOptions.size)
        // 7 孔位 × 3 音区，减去指法图未覆盖的高音格。
        assertEquals(19, state.notes.size)
        assertEquals(32, state.detailNotes.size)
        assertTrue(state.notes.all { it.holes.size == 8 })
        assertTrue(state.detailNotes.all { it.holes.size == 8 })
        assertEquals("筒音", state.notes.first().label)
        assertEquals(0, state.notes.first().semitones)
        assertEquals(31, state.notes.maxOf { it.semitones })
        assertNull(state.mainPreviewFingeringId) // 未点选时跟随实时识别
        assertNull(state.detailPreviewFingeringId)
        assertEquals(19, state.notes.map { it.fingeringId }.distinct().size)
        assertEquals(32, state.detailNotes.map { it.fingeringId }.distinct().size)
        assertEquals(
            setOf(WindRegister.LOW, WindRegister.MIDDLE, WindRegister.HIGH),
            state.notes.map { it.register }.toSet(),
        )
        // 中音区沿用低音孔位，高音区孔位与之不同。
        val closedTube = state.notes.filter { it.baseSemitones == 0 }
        assertEquals(listOf(0, 12, 24), closedTube.map { it.semitones })
        assertEquals(closedTube[0].holes, closedTube[1].holes)
        assertTrue(closedTube[2].holes != closedTube[0].holes)
        assertEquals(listOf(false, true, true), closedTube.map { it.overblown })
        assertEquals(
            listOf(0, 2, 4, 5, 7, 9, 10),
            state.notes
                .filter { it.register == WindRegister.LOW }
                .map { it.baseSemitones },
        )
        assertTrue(state.notes.first().holes.all { it == HoleMark.CLOSED })
    }

    @Test
    fun `洞箫点选优先于实时采音且再点一次回到实时`() {
        val vm = makeVm()
        vm.startCapture()
        vm.selectInstrument("dongxiao")

        vm.previewMainFingering(7)
        var state = vm.uiState.value
        assertEquals(7, state.mainPreviewFingeringId)
        assertNull(state.detailPreviewFingeringId)

        vm.previewDetailFingering(1)
        state = vm.uiState.value
        assertEquals(7, state.mainPreviewFingeringId)
        assertEquals(1, state.detailPreviewFingeringId)

        // 实时采音只改列高亮，不再顶掉点选的孔位。
        val liveTarget = state.detailNotes.single { it.fingeringId == 11 }
        stream.emitEvent(event(liveTarget.freqHz))
        state = vm.uiState.value
        assertTrue(state.notes.any { it.active })
        assertEquals(11, state.detailNotes.single { it.active }.fingeringId)
        assertEquals(7, state.mainPreviewFingeringId)
        assertEquals(1, state.detailPreviewFingeringId)

        // 再点同一条取消点选，大图交回实时识别。
        vm.previewMainFingering(7)
        assertNull(vm.uiState.value.mainPreviewFingeringId)

        stream.emitEvent(null, signalState = SignalState.QUIET)
        state = vm.uiState.value
        assertTrue(state.notes.none { it.active })
        assertTrue(state.detailNotes.none { it.active })
        assertNull(state.mainPreviewFingeringId)
        assertEquals(1, state.detailPreviewFingeringId)
    }

    @Test
    fun `洞箫换唱名重筛完整七声且保持十二音详情稳定`() {
        val vm = makeVm()
        vm.startCapture()
        vm.selectInstrument("dongxiao")
        val target = vm.uiState.value.notes[4]
        stream.emitEvent(event(target.freqHz))
        val before = vm.uiState.value
        val detailIds = before.detailNotes.map { it.fingeringId }
        val detailHoles = before.detailNotes.map { it.holes }

        vm.stepTongyin(1)
        val after = vm.uiState.value

        assertEquals(9, after.tongyinDegree)
        assertTrue(after.keyDisplay != before.keyDisplay)
        assertTrue(before.notes.map { it.baseSemitones } != after.notes.map { it.baseSemitones })
        assertEquals(
            setOf(0, 2, 4, 5, 7, 9, 11),
            after.notes
                .filter { it.register == WindRegister.LOW }
                .map { it.solfege.toInt() }
                .toSet(),
        )
        assertEquals(detailIds, after.detailNotes.map { it.fingeringId })
        assertEquals(detailHoles, after.detailNotes.map { it.holes })
        assertTrue(after.notes.any { it.active })
        assertTrue(after.centsToTarget != null)

        repeat(2) { vm.stepTongyin(1) }
        assertEquals(0, vm.uiState.value.tongyinDegree)
    }

    @Test
    fun `洞箫切换调与孔制刷新两张表并保留调音状态`() {
        val vm = makeVm()
        vm.startCapture()
        vm.selectInstrument("dongxiao")
        stream.emitEvent(event(vm.uiState.value.notes[3].freqHz))
        val activeBefore = vm.uiState.value.notes.single { it.active }.fingeringId

        vm.selectHoleSystem("6孔")
        var state = vm.uiState.value
        assertEquals(6, state.holeCount)
        assertEquals(19, state.notes.size)
        assertEquals(32, state.detailNotes.size)
        assertTrue(state.notes.all { it.holes.size == 6 })
        assertTrue(state.detailNotes.all { it.holes.size == 6 })
        assertEquals(activeBefore, state.notes.single { it.active }.fingeringId)
        assertTrue(state.centsToTarget != null)

        vm.selectWindKey("D调洞箫")
        state = vm.uiState.value
        assertEquals("D调洞箫", state.keyName)
        assertEquals("6孔", state.holeSystem)
        assertEquals(19, state.notes.size)
        assertEquals(32, state.detailNotes.size)
        assertTrue(state.notes.any { it.active })
        assertTrue(state.detailNotes.any { it.active })
        assertTrue(state.centsToTarget != null)
    }

    @Test
    fun `布局使用低中高三音区列并与洞箫孔心同轴`() {
        assertEquals(
            listOf("低音/缓吹", "中音/超吹", "高音/急吹"),
            DONGXIAO_REGISTER_COLUMNS.map { it.title },
        )
        assertEquals(WindRegister.entries, DONGXIAO_REGISTER_COLUMNS.map { it.register })
        assertTrue(xiaoHoleCenterFraction(7, 8) < xiaoHoleCenterFraction(0, 8))
    }

    @Test
    fun `音区列自动居中按趋势夹在合法位移内`() {
        // 视口 276px、内容 828px（三列各 276px）：低音列已在左边界，不再左移。
        assertEquals(
            0,
            registerScrollTargetPx(0, 3, 828f, 276f, 552f),
        )
        // 中音列正好能居中。
        assertEquals(
            276,
            registerScrollTargetPx(1, 3, 828f, 276f, 552f),
        )
        // 高音列居中需要 552px，恰好是最大位移。
        assertEquals(
            552,
            registerScrollTargetPx(2, 3, 828f, 276f, 552f),
        )
        // 内容窄到不需要滚动时保持原位；未命中任何列也不动。
        assertEquals(0, registerScrollTargetPx(2, 3, 300f, 300f, 0f))
        assertEquals(0, registerScrollTargetPx(-1, 3, 828f, 276f, 552f))
    }

    @Test
    fun `唱名拖动越过整档连续结算且松手吸附最近档`() {
        val forward = consumeTongyinDrag(0f, -74f, 32f)
        assertEquals(2, forward.committedSteps)
        assertEquals(-10f, forward.residualOffsetPx)
        val toDegreeTwo = consumeTongyinDrag(0f, 106f, 32f)
        assertEquals(-3, toDegreeTwo.committedSteps)
        assertEquals(10f, toDegreeTwo.residualOffsetPx)
        assertEquals("2", shiftedDongxiaoSolfege("5", -3, naturalScaleOnly = true))
        assertEquals("5", shiftedDongxiaoSolfege("2", 3, naturalScaleOnly = true))
        assertEquals("2", shiftedDongxiaoSolfege("5", -5))
        assertEquals(0, tongyinDragSteps(-15f, 32f))
        assertEquals(1, tongyinDragSteps(-17f, 32f))
        assertEquals(2, tongyinDragSteps(-49f, 32f))
        assertEquals(-1, tongyinDragSteps(17f, 32f))
    }

    @Test
    fun `G调默认作五可连续滑到筒音作二`() {
        val vm = makeVm()
        vm.selectInstrument("dongxiao")
        assertEquals(7, vm.uiState.value.tongyinDegree)
        vm.selectTongyinDegree(1)
        assertEquals(7, vm.uiState.value.tongyinDegree)
        vm.selectDetailTongyinDegree(1)
        assertEquals(7, vm.uiState.value.tongyinDegree)
        assertEquals(1, vm.uiState.value.detailTongyinDegree)

        vm.stepTongyin(-3)

        assertEquals(2, vm.uiState.value.tongyinDegree)
        assertEquals(1, vm.uiState.value.detailTongyinDegree)
        assertTrue(vm.uiState.value.keyDisplay.startsWith("筒音作2"))
        assertEquals(
            listOf(0, 2, 3, 5, 7, 9, 10),
            vm.uiState.value.notes
                .filter { it.register == WindRegister.LOW }
                .map { it.baseSemitones },
        )
    }

    @Test
    fun `旧管乐保存状态可迁移到洞箫新选择键`() {
        savedState = SavedStateHandle(
            mapOf(
                "instrumentId" to "dongxiao",
                "chartGroup" to "D调洞箫",
                "tongyin" to "2",
            ),
        )
        val state = makeVm().uiState.value

        assertEquals("dongxiao", state.instrumentId)
        assertEquals("D调洞箫", state.keyName)
        assertEquals("8孔", state.holeSystem)
        assertEquals(2, state.tongyinDegree)
        assertEquals("D调洞箫", savedState.get<String>("windKeyName"))
        assertEquals("8孔", savedState.get<String>("windHoleSystem"))
        assertEquals(2, savedState.get<Int>("windTongyinDegree"))
    }
}
