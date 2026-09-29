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
        Instrument("shakuhachi", "尺八", InstrumentKind.WIND),
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

    override fun windVariants(instrumentId: String): List<WindVariant> = when (instrumentId) {
        "dongxiao" -> listOf(
            variant("g_xiao_x8", "g_xiao", "G调洞箫", 8),
            variant("g_xiao_x6", "g_xiao", "G调洞箫", 6),
            variant("d_xiao_x8", "d_xiao", "D调洞箫", 8),
            variant("d_xiao_x6", "d_xiao", "D调洞箫", 6),
        )
        "zhudi" -> listOf(variant("d_qudi", "d_qudi", "D调曲笛", 6, backHoles = 0))
        "shakuhachi" -> listOf(
            variant("shaku_18", "shaku_18", "一尺八寸", 5, transposable = false),
        )
        else -> emptyList()
    }

    override fun windFingeringChart(
        variantId: String,
        tongyinDegree: UByte,
        scope: FingeringScope,
    ): WindChart? {
        val variant = listOf("dongxiao", "zhudi", "shakuhachi")
            .flatMap { windVariants(it) }
            .firstOrNull { it.id == variantId } ?: return null
        val degree = tongyinDegree.toInt() % 12
        val baseOffsets = if (!variant.supportsTongyin) {
            // 尺八按五声取音：乙音、甲音各五声，大甲只到甲音之上一音。
            listOf(0, 3, 5, 7, 10)
        } else if (scope == FingeringScope.SCALE) {
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
                    if (!variant.supportsTongyin && register == WindRegister.HIGH && baseSemitones > 0) {
                        return@mapNotNull null
                    }
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
        backHoles: Int = 1,
        transposable: Boolean = true,
    ) = WindVariant(
        id = id,
        displayName = "$keyName · ${holes}孔",
        keyId = keyId,
        keyName = keyName,
        holeSystemName = "${holes}孔",
        holeCount = holes.toUByte(),
        backHoleCount = backHoles.toUByte(),
        fundamentalMidi = if (keyId == "g_xiao") 55 else 50,
        fundamentalNoteName = if (keyId == "g_xiao") "G3" else "D3",
        supportsTongyin = transposable,
        supportsChromatic = transposable,
        tongyinOptions = if (transposable) {
            (0..11).map { TongyinOption(it.toUByte(), it.toString(), it in listOf(0, 2, 7)) }
        } else {
            emptyList()
        },
        defaultTongyinDegree = if (transposable) 7u else 0u,
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

    private fun makeVm(preferences: InstrumentPreferences = InstrumentPreferences.InMemory()) =
        InstrumentViewModel(
            core = FakeCoreApi(),
            stream = stream,
            savedState = savedState,
            preferences = preferences,
        )

    @Test
    fun `初始化加载乐器列表与默认吉他定弦`() {
        val vm = makeVm()
        val s = vm.uiState.value
        assertEquals(4, s.instruments.size)
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
    fun `竹笛走型号指法表：三音区、可转调、有十二音详情`() {
        val vm = makeVm()
        vm.startCapture()
        vm.selectInstrument("zhudi")
        var s = vm.uiState.value
        assertEquals(InstrumentKind.WIND, s.kind)
        assertEquals(WindFigureKind.DIZI, s.windFigure)
        assertTrue(s.supportsTongyin)
        assertTrue(s.supportsChromatic)
        assertEquals(6, s.holeCount)
        assertEquals(0, s.backHoleCount)
        assertEquals(listOf("D调曲笛"), s.keyNames)
        assertEquals(
            setOf(WindRegister.LOW, WindRegister.MIDDLE, WindRegister.HIGH),
            s.notes.map { it.register }.toSet(),
        )
        assertTrue(s.notes.all { it.holes.size == 6 })
        assertTrue(s.detailNotes.isNotEmpty())
        assertEquals("zhudi", savedState.get<String>("instrumentId"))

        val target = s.notes[2]
        stream.emitEvent(event(target.freqHz))
        s = vm.uiState.value
        assertEquals(target.fingeringId, s.notes.single { it.active }.fingeringId)
        assertEquals(target.noteName, s.targetNoteName)

        vm.stepTongyin(-3)
        assertTrue(vm.uiState.value.keyDisplay.startsWith("筒音作2"))
    }

    @Test
    fun `尺八按五声分乙甲两音区且不提供转调与十二音`() {
        val vm = makeVm()
        vm.selectInstrument("shakuhachi")
        val s = vm.uiState.value
        assertEquals(WindFigureKind.SHAKUHACHI, s.windFigure)
        assertFalse(s.supportsTongyin)
        assertFalse(s.supportsChromatic)
        assertEquals(5, s.holeCount)
        assertEquals(1, s.backHoleCount)
        assertEquals(11, s.notes.size)
        assertEquals(5, s.notes.count { it.register == WindRegister.LOW })
        assertEquals(5, s.notes.count { it.register == WindRegister.MIDDLE })
        assertEquals(1, s.notes.count { it.register == WindRegister.HIGH })
        assertTrue(s.detailNotes.isEmpty())
        assertEquals("乙音", s.windFigure.registerTitle(WindRegister.LOW))

        // 不可转调的型号忽略唱名拖动。
        vm.stepTongyin(2)
        assertEquals(s.tongyinDegree, vm.uiState.value.tongyinDegree)
        assertEquals(s.notes, vm.uiState.value.notes)
    }

    @Test
    fun `吉他琴头默认六联排、切换后持久化，尤克里里固定四弦琴头`() {
        val preferences = InstrumentPreferences.InMemory()
        val vm = makeVm(preferences)
        assertEquals(StringFigureKind.Headstock(HeadstockStyle.INLINE_6), vm.uiState.value.stringFigure)

        vm.selectHeadstockStyle(HeadstockStyle.THREE_PLUS_THREE)
        assertEquals(HeadstockStyle.THREE_PLUS_THREE, preferences.headstockStyle)
        assertEquals(
            StringFigureKind.Headstock(HeadstockStyle.THREE_PLUS_THREE),
            vm.uiState.value.stringFigure,
        )
        // 重建 ViewModel 仍读到上次的琴头样式。
        assertEquals(
            StringFigureKind.Headstock(HeadstockStyle.THREE_PLUS_THREE),
            makeVm(preferences).uiState.value.stringFigure,
        )
    }

    @Test
    fun `点弦进入手动锁定，换乐器回到自动`() {
        val vm = makeVm()
        assertNull(vm.uiState.value.selectedStringIndex)
        vm.selectString(2)
        assertEquals(2, vm.uiState.value.selectedStringIndex)
        vm.selectString(99)
        assertEquals(2, vm.uiState.value.selectedStringIndex)

        vm.selectInstrument("zhudi")
        vm.selectInstrument("guitar")
        assertEquals(SelectionMode.AUTO, vm.uiState.value.mode)
        assertNull(vm.uiState.value.selectedStringIndex)
    }

    @Test
    fun `琴头每根弦恰好对应一个弦轴`() {
        listOf(
            HeadstockLayout.INLINE_6 to 6,
            HeadstockLayout.THREE_PLUS_THREE to 6,
            HeadstockLayout.UKULELE to 4,
        ).forEach { (layout, count) ->
            assertEquals((1..count).toList(), layout.pegs.map { it.stringNumber }.sorted())
        }
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
    fun `音区列名按乐器区分且行锚定到孔心`() {
        assertEquals(
            listOf("低音", "中音", "高音"),
            WindRegister.entries.map { WindFigureKind.XIAO.registerTitle(it) },
        )
        assertEquals(
            listOf("乙音", "甲音", "大甲"),
            WindRegister.entries.map { WindFigureKind.SHAKUHACHI.registerTitle(it) },
        )
        WindFigureKind.entries.forEach { kind ->
            val count = if (kind == WindFigureKind.SHAKUHACHI) 5 else 6
            assertTrue(
                WindFigureGeometry.fraction(kind, count - 1, count) <
                    WindFigureGeometry.fraction(kind, 0, count),
            )
        }
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
        assertEquals("2", shiftedSolfege("5", -3, naturalScaleOnly = true))
        assertEquals("5", shiftedSolfege("2", 3, naturalScaleOnly = true))
        assertEquals("2", shiftedSolfege("5", -5))
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
}
