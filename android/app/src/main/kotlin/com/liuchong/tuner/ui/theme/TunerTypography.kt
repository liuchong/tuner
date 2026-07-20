package com.liuchong.tuner.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 「Lumen / 微光」字体系统（design-system §4）。
 * 数值一律等宽数字（tabular figures），避免刷新时横向抖动。
 */
object TunerTypography {
    private const val TNUM = "tnum"

    /** 音名大字（A♯4，表盘下方读数），100sp Bold，等宽数字。 */
    val displayNote = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 100.sp,
        fontFeatureSettings = TNUM,
    )

    /** 节拍器 BPM，72sp Bold，等宽数字。 */
    val displayBpm = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 72.sp,
        fontFeatureSettings = TNUM,
    )

    /** Hz / cents 数值，18sp Medium，等宽数字。 */
    val readoutValue = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        fontFeatureSettings = TNUM,
    )

    /** 唱名行，20sp Medium。 */
    val readoutSolfege = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
    )

    /** 控件标签、弦名，14sp Medium。 */
    val label = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    )

    /** 辅助说明，12sp Regular。 */
    val caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    )
}
