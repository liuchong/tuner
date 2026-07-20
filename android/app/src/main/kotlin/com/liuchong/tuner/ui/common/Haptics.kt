package com.liuchong.tuner.ui.common

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** 触觉反馈开关（由设置页写入，design-system §8/§9）。 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** 触觉操作集合（design-system §8）。 */
class TunerHaptics(
    private val view: View,
    private val enabled: () -> Boolean,
) {
    /** 单次轻 tick（进入准音区，边沿触发）。 */
    fun tick() {
        if (enabled()) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /** 双 tick（准音稳定保持 500ms 的成功确认）。 */
    fun doubleTick() {
        if (enabled()) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            view.postDelayed(
                { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) },
                90L,
            )
        }
    }
}

@Composable
fun rememberTunerHaptics(): TunerHaptics {
    val view = LocalView.current
    val enabled = LocalHapticsEnabled.current
    return androidx.compose.runtime.remember(view, enabled) {
        TunerHaptics(view) { enabled }
    }
}
