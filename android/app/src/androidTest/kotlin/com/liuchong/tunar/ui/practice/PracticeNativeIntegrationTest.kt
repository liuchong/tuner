package com.liuchong.tunar.ui.practice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import android.net.Uri
import android.Manifest
import com.liuchong.tunar.ui.navigation.AppNav
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.liuchong.tunar.corebinding.TunarCore
import com.liuchong.tunar.ui.theme.TunarTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.tunar_core.*
import uniffi.tunar_core.PracticeRecordingState
import uniffi.tunar_core.PracticeSession
import uniffi.tunar_core.TunarEngine
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** 真实Android JNI + Rust DSP + 原生模型/文件IO；不替代物理麦克风及系统选择器验收。 */
@RunWith(AndroidJUnit4::class)
class PracticeNativeIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun inputPolicyControlsReachRealEngineAndShowCalibrationState() {
        val engine = TunarEngine(com.liuchong.tunar.corebinding.TunarCore.defaultConfig())
        try {
            var policy by androidx.compose.runtime.mutableStateOf(defaultInputPolicy())
            var status by androidx.compose.runtime.mutableStateOf(engine.inputStatus())
            compose.setContent { TunarTheme(darkTheme = false) { Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(16.dp)) {
                InputControls(policy, status, { policy = it; engine.setInputPolicy(it) }, {
                    engine.beginNoiseCalibration(false); status = engine.inputStatus()
                }, { engine.confirmNoiseEnvironment(); status = engine.inputStatus() }, {
                    engine.cancelNoiseCalibration(); status = engine.inputStatus()
                })
            } } } }
            compose.onNodeWithText("输入与响应").assertExists()
            compose.onNodeWithText("平稳").performClick()
            compose.runOnIdle { assertEquals(ResponseMode.STEADY, engine.inputPolicy().response) }
            compose.onNodeWithText("测一下环境噪声").performClick()
            compose.onNodeWithText("先不要演奏，保持2秒安静").assertExists()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                val pending=File(context.cacheDir,"input-controls.pending")
                pending.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                assertTrue(pending.renameTo(File(context.cacheDir,"input-controls.png")))
            }
            compose.onNodeWithText("取消测量").performClick()
            compose.runOnIdle { assertNotEquals(CalibrationState.MEASURING, engine.calibrationStatus().state) }
        } finally { engine.close() }
    }

    private fun measurement(): String {
        val engine = TunarEngine(TunarCore.defaultConfig())
        val session = PracticeSession()
        try {
            session.lockReference(0)
            session.start()
            repeat(100) { index ->
                val pcm = List(2048) { sample -> (0.5 * sin(2 * PI * 440 * (index * 1024 + sample) / 44100.0)).toFloat() }
                session.observe(engine.analyzeInput(pcm,InputFrameContext((index*1024+2048).toULong(),1uL,false,false)), 1uL, 440.0, 12u, false)
            }
            val trace = session.tracePage(0u, 6000u)
            session.summarize(trace[2].id, trace[15].id)
            val first = trace.first { it.valid }
            session.selectPoint(first.id)
            val snapshot = session.addSnapshot("JNI基准")
            session.setBaseline(snapshot.id)
            return session.exportJson()
        } finally { session.close(); engine.close() }
    }

    @Test fun navigationEntrypointsShareThePracticeWorkspace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.RECORD_AUDIO)
        compose.setContent { TunarTheme(darkTheme = false) { AppNav() } }
        compose.onNodeWithText("打开练音工作区").performClick()
        compose.onNodeWithText("练音工作区").assertExists()
        compose.onNodeWithText("返回", useUnmergedTree = true).performClick()
        compose.onNodeWithText("乐器", useUnmergedTree = true).performClick()
        compose.onNodeWithText("设为目标 · 练音").performClick()
        compose.onNodeWithText("练音工作区").assertExists()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("自动目标").assertIsEnabled() }.isSuccess }
        compose.onNodeWithText("目标 ", substring = true).assertExists()
        compose.onNodeWithText("返回", useUnmergedTree = true).performClick()
        compose.onNodeWithText("调音", useUnmergedTree = true).performClick()
        compose.onNodeWithText("打开练音工作区").performClick()
        compose.onNodeWithText("目标 ", substring = true).assertExists()
        compose.onNodeWithText("自动目标").assertIsEnabled()
    }

    @Test fun nativeDspReviewSnapshotAndCodecRoundTrip() {
        val reopened = PracticeSession()
        try {
            reopened.importJson(measurement())
            assertTrue(reopened.status().count > 10u)
            assertNotNull(reopened.status().selectionStartId)
            assertEquals(PracticeRecordingState.PAUSED, reopened.status().state)
            assertTrue(kotlin.math.abs(reopened.displayPoint()!!.cents!!) < 0.6)
            assertEquals(64, reopened.comparison(false, false).current.size)
            assertTrue(reopened.exportCsv(null, null).contains("reference_tone_active"))
            val count = reopened.status().count
            try { reopened.importJson("{"); fail("损坏文件应失败") } catch (_: uniffi.tunar_core.PracticeException) { }
            assertEquals(count, reopened.status().count)
            reopened.returnToLive()
            assertEquals(PracticeRecordingState.PAUSED, reopened.status().state)
        } finally { reopened.close() }
    }

    @Test fun modelFileReviewAndComposeShareOneCursor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "practice-input.json").apply { writeText(measurement()) }
        val output = File(context.cacheDir, "practice-output.json")
        val store = ViewModelStore()
        lateinit var model: PracticeWorkspaceViewModel
        compose.runOnUiThread { model = PracticeWorkspaceViewModel(); store.put("practice", model) }
        try {
            model.importFile(context.contentResolver, Uri.fromFile(input))
            compose.waitUntil(5000) { model.state.value.status.reviewId != null }
            val count = model.state.value.status.count
            assertNotNull(model.state.value.status.selectionStartId)
            assertFalse(model.state.value.summaries.isEmpty())
            val corrupt = File(context.cacheDir, "practice-corrupt.json").apply { writeText("{") }
            model.importFile(context.contentResolver, Uri.fromFile(corrupt))
            compose.waitUntil(5000) { model.state.value.error != null }
            assertTrue(model.state.value.error!!.contains("测量文件"))
            assertEquals(count, model.state.value.status.count)
            corrupt.delete()
            model.error(null)
            compose.waitUntil(5000) { model.state.value.error == null }
            compose.setContent { TunarTheme(darkTheme = false) { PracticeWorkspaceScreen(model, {}, {}) } }
            compose.onNodeWithText("练音工作区").assertExists()
            compose.onNodeWithText("分析").performClick()
            compose.waitUntil(5000) { model.state.value.task == "分析" }
            compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                val pending = File(context.cacheDir, "practice-analysis.pending")
                pending.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue(pending.renameTo(File(context.cacheDir, "practice-analysis.png")))
            }
            model.exportFile(context.contentResolver, Uri.fromFile(output), false, false)
            compose.waitUntil(5000) { model.state.value.fileMessage == "已保存测量文件" || model.state.value.error != null }
            assertNull(model.state.value.error)
            val reopened = PracticeSession()
            try { reopened.importJson(output.readText()); assertEquals(count, reopened.status().count); assertEquals(model.state.value.point!!.frame.samplePosition, reopened.displayPoint()!!.frame.samplePosition) }
            finally { reopened.close() }
            model.live()
            compose.waitUntil(5000) { model.state.value.status.reviewId == null }
            assertEquals(PracticeRecordingState.PAUSED, model.state.value.status.state)
        } finally {
            compose.runOnUiThread { store.clear() }
            input.delete(); output.delete()
        }
    }
}
