package com.liuchong.tunar.ui.instrument

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.tunar_core.FingeringKind
import uniffi.tunar_core.HoleMark
import uniffi.tunar_core.WindRegister

@RunWith(AndroidJUnit4::class)
class WindChartSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val registers = listOf(
        WindRegister.LOW to 0,
        WindRegister.MIDDLE to 12,
        WindRegister.HIGH to 24,
    )

    /** 七声 × 三音区；高音区只覆盖到相对筒音 31 半音。 */
    private fun chart(holeCount: Int): List<ChartNoteUi> =
        listOf(0, 2, 4, 5, 7, 9, 11).flatMap { base ->
            registers.mapNotNull { (register, offset) ->
                val semitones = base + offset
                if (semitones > 31) return@mapNotNull null
                val opened = (base / 2).coerceAtMost(holeCount)
                ChartNoteUi(
                    label = "指法$base",
                    noteName = "N${50 + semitones}",
                    midi = 50 + semitones,
                    freqHz = 100.0 + semitones,
                    solfege = listOf("1", "2", "3", "4", "5", "6", "7")[listOf(0, 2, 4, 5, 7, 9, 11).indexOf(base)],
                    semitones = semitones,
                    fingeringId = semitones,
                    baseSemitones = base,
                    register = register,
                    holes = List(holeCount) { if (it < opened) HoleMark.OPEN else HoleMark.CLOSED },
                    fingeringKind = if (base == 5) FingeringKind.COMBINATION else FingeringKind.SEQUENTIAL,
                    anchorHole = (opened - 1).takeIf { it >= 0 },
                )
            }
        }

    @Test
    fun tubeFigureDescribesFrontBackAndHalfOpenHoles() {
        composeRule.setContent {
            MaterialTheme {
                WindTubeFigure(
                    kind = WindFigureKind.XIAO,
                    holes = listOf(HoleMark.HALF, HoleMark.CLOSED, HoleMark.OPEN),
                    backHoleCount = 1,
                    highlighted = false,
                    ink = FigureInk.from(com.liuchong.tunar.ui.theme.LumenDark),
                )
            }
        }
        composeRule
            .onNodeWithContentDescription("3孔洞箫，第1孔半开，第2孔闭，背孔开")
            .fetchSemanticsNode()
    }

    @Test
    fun xiaoChartPreviewsCellsAndTransposesByDragOrAction() {
        var totalStep = 0
        val preview = mutableStateOf<Int?>(null)
        composeRule.setContent {
            MaterialTheme {
                WindFingeringTable(
                    figure = WindFigureKind.XIAO,
                    notes = chart(8),
                    holeCount = 8,
                    backHoleCount = 1,
                    title = "筒音作5 · G宫",
                    previewFingeringId = preview.value,
                    tongyinDegree = 7,
                    transposable = true,
                    naturalScaleOnly = true,
                    onPreview = { preview.value = if (preview.value == it) null else it },
                    onStepTongyin = { totalStep += it },
                    onOpenDetail = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onAllNodes(hasContentDescription("8孔洞箫", substring = true))
            .assertCountEquals(1)
        listOf("低音", "中音", "高音").forEach { composeRule.onNodeWithText(it).fetchSemanticsNode() }
        composeRule.onNodeWithContentDescription("打开洞箫十二音完整指法").fetchSemanticsNode()

        composeRule.onNodeWithContentDescription("中音，N64，唱名2").performClick()
        composeRule.runOnIdle { assertEquals(14, preview.value) }
        composeRule.onNodeWithContentDescription("中音，N64，唱名2").performClick()
        composeRule.runOnIdle { assertEquals(null, preview.value) }

        val cell = composeRule.onNodeWithContentDescription("低音，N50，唱名1").fetchSemanticsNode()
        composeRule.runOnIdle {
            val actions: List<CustomAccessibilityAction> = cell.config[SemanticsActions.CustomActions]
            actions.first { it.label == "转调升一档" }.action()
            assertEquals(1, totalStep)
        }

        val wheel = composeRule.onNodeWithContentDescription("唱名转轮，上下拖动转调")
        wheel.performTouchInput {
            swipe(start = center + Offset(0f, 2f), end = center - Offset(0f, 2f), durationMillis = 100)
        }
        composeRule.runOnIdle { assertEquals(1, totalStep) }

        wheel.performTouchInput {
            val step = 28f * density
            swipe(start = center + Offset(0f, step * 0.8f), end = center - Offset(0f, step * 0.8f), durationMillis = 200)
        }
        composeRule.runOnIdle { assertTrue("上拖超过一档应升调，实际 $totalStep", totalStep > 1) }
    }

    @Test
    fun shakuhachiChartUsesItsOwnRegisterNamesAndCannotTranspose() {
        composeRule.setContent {
            MaterialTheme {
                WindFingeringTable(
                    figure = WindFigureKind.SHAKUHACHI,
                    notes = chart(5),
                    holeCount = 5,
                    backHoleCount = 1,
                    title = "一尺八寸",
                    previewFingeringId = null,
                    tongyinDegree = 0,
                    transposable = false,
                    naturalScaleOnly = true,
                    onPreview = {},
                    onStepTongyin = {},
                    onOpenDetail = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        listOf("乙音", "甲音", "大甲").forEach { composeRule.onNodeWithText(it).fetchSemanticsNode() }
        composeRule
            .onAllNodes(hasContentDescription("唱名转轮", substring = true))
            .assertCountEquals(0)
        composeRule
            .onAllNodes(hasContentDescription("十二音完整指法", substring = true))
            .assertCountEquals(0)
    }
}
