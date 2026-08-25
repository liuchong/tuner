package com.liuchong.tunar.ui.instrument

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.tunar_core.FingeringKind
import uniffi.tunar_core.HoleMark
import uniffi.tunar_core.WindRegister

@RunWith(AndroidJUnit4::class)
class DongxiaoSemanticsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun holeDiagramDescribesFrontBackAndHalfOpenHoles() {
        composeRule.setContent {
            MaterialTheme {
                HoleDiagram(
                    holes = listOf(HoleMark.HALF, HoleMark.CLOSED, HoleMark.OPEN),
                    backHoleCount = 1,
                    highlighted = false,
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(
                "完整洞箫，吹口在上，出音口在下；孔位：第1孔半开，第2孔闭，背孔开",
            )
            .fetchSemanticsNode()
    }

    @Test
    fun chromaticDialogShowsThreeRegistersAndKeepsReleaseSnapping() {
        val registers = listOf(
            WindRegister.LOW to 0,
            WindRegister.MIDDLE to 12,
            WindRegister.HIGH to 24,
        )
        val notes = (0..11).flatMap { baseSemitones ->
            registers.mapNotNull { (register, registerOffset) ->
                val semitones = registerOffset + baseSemitones
                // 指法图覆盖到相对筒音 31 半音，越界的高音格没有来源。
                if (semitones > 31) return@mapNotNull null
                val baseHoles = List(8) { index ->
                    when {
                        baseSemitones % 2 == 1 && index == 0 -> HoleMark.HALF
                        index < baseSemitones.coerceAtMost(8) -> HoleMark.OPEN
                        else -> HoleMark.CLOSED
                    }
                }
                ChartNoteUi(
                    label = "指法$baseSemitones",
                    noteName = "N${50 + semitones}",
                    midi = 50 + semitones,
                    freqHz = 100.0 + semitones,
                    solfege = "${semitones % 12}",
                    semitones = semitones,
                    fingeringId = semitones,
                    baseSemitones = baseSemitones,
                    register = register,
                    // 高音区孔位与低、中音区不同，这里以开背孔示意。
                    holes = if (register == WindRegister.HIGH) {
                        baseHoles.mapIndexed { index, mark ->
                            if (index == 7) HoleMark.OPEN else mark
                        }
                    } else {
                        baseHoles
                    },
                    fingeringKind = if (baseSemitones % 2 == 1) {
                        FingeringKind.COMBINATION
                    } else {
                        FingeringKind.SEQUENTIAL
                    },
                    anchorHole = if (baseSemitones == 0) {
                        null
                    } else {
                        (baseSemitones.coerceAtMost(8) - 1)
                    },
                )
            }
        }
        // 12 低音 + 12 中音 + 指法图覆盖到 31 半音的 8 个高音。
        assertEquals(32, notes.size)
        var totalStep = 0
        val previewFingeringId = mutableStateOf<Int?>(0)
        val displayedNotes = mutableStateOf(notes)
        composeRule.setContent {
            MaterialTheme {
                DongxiaoAnchoredChart(
                    notes = displayedNotes.value,
                    holeCount = 8,
                    backHoleCount = 1,
                    previewFingeringId = previewFingeringId.value,
                    tongyinDegree = 7,
                    naturalScaleOnly = false,
                    onPreview = { previewFingeringId.value = it },
                    onStepTongyin = { totalStep += it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule
            .onAllNodes(hasContentDescription("完整洞箫", substring = true))
            .assertCountEquals(1)
        listOf("低音/缓吹", "中音/超吹", "高音/急吹").forEach { header ->
            composeRule.onNodeWithText(header).fetchSemanticsNode()
        }

        // 同一孔位的中音、高音格子也可点选预览。
        composeRule
            .onNodeWithContentDescription("中音/超吹，音名N62，指法ID 12")
            .performClick()
        composeRule.runOnIdle {
            assertEquals(12, previewFingeringId.value)
        }

        composeRule
            .onNodeWithContentDescription("低音/缓吹，音名N51，指法ID 1")
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, previewFingeringId.value)
        }
        composeRule
            .onNodeWithContentDescription("唱名徽章，低音/缓吹，1，指法ID 1")
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, previewFingeringId.value)
        }

        composeRule.runOnIdle {
            displayedNotes.value = notes.map { it.copy(active = it.fingeringId == 0) }
        }
        composeRule.onNodeWithText("N50").fetchSemanticsNode()

        val transposeNode = composeRule
            .onNodeWithContentDescription("唱名徽章，低音/缓吹，0，指法ID 0")
            .fetchSemanticsNode()
        composeRule.runOnIdle {
            val actions: List<CustomAccessibilityAction> =
                transposeNode.config[SemanticsActions.CustomActions]
            actions.first { it.label == "唱名升高半音" }.action()
            assertEquals(1, totalStep)
        }

        val badge = composeRule
            .onNodeWithContentDescription("唱名徽章，低音/缓吹，0，指法ID 0")
        badge.performTouchInput {
            swipe(
                start = center + Offset(0f, 4f),
                end = center - Offset(0f, 4f),
                durationMillis = 100,
            )
        }
        composeRule.runOnIdle { assertEquals(1, totalStep) }

        badge.performTouchInput {
            swipe(
                start = center + Offset(0f, 30f),
                end = center - Offset(0f, 30f),
                durationMillis = 100,
            )
        }
        composeRule.runOnIdle { assertEquals(2, totalStep) }
    }

}
