package com.liuchong.tunar.ui.instrument

import android.content.Context
import uniffi.tunar_core.WindRegister

/** 吉他琴头样式，名称用行业通称。 */
enum class HeadstockStyle(val displayName: String, val accessibilityName: String) {
    INLINE_6("6-in-line", "六联排琴头（Fender 式）"),
    THREE_PLUS_THREE("3+3", "三加三琴头（Gibson 式）"),
}

/** 弦乐器页的线稿类型。 */
sealed interface StringFigureKind {
    data class Headstock(val style: HeadstockStyle) : StringFigureKind
    data object UkuleleHeadstock : StringFigureKind
    data object Guqin : StringFigureKind
    data object None : StringFigureKind
}

/** 管乐线稿类型：决定管身画法、孔位纵向分布和音区命名。 */
enum class WindFigureKind(val displayName: String) {
    XIAO("洞箫"),
    DIZI("竹笛"),
    SHAKUHACHI("尺八"),
    ;

    fun registerTitle(register: WindRegister): String = when (this) {
        SHAKUHACHI -> when (register) {
            WindRegister.LOW -> "乙音"
            WindRegister.MIDDLE -> "甲音"
            WindRegister.HIGH -> "大甲"
        }
        else -> when (register) {
            WindRegister.LOW -> "低音"
            WindRegister.MIDDLE -> "中音"
            WindRegister.HIGH -> "高音"
        }
    }

    fun registerTechnique(register: WindRegister): String = when (this) {
        SHAKUHACHI -> when (register) {
            WindRegister.LOW -> "otsu"
            WindRegister.MIDDLE -> "kan"
            WindRegister.HIGH -> "daikan"
        }
        else -> when (register) {
            WindRegister.LOW -> "缓吹"
            WindRegister.MIDDLE -> "超吹"
            WindRegister.HIGH -> "急吹"
        }
    }

    companion object {
        fun of(instrumentId: String): WindFigureKind = when (instrumentId) {
            "zhudi" -> DIZI
            "shakuhachi" -> SHAKUHACHI
            else -> XIAO
        }
    }
}

/** 乐器页需要跨启动保留的偏好。 */
interface InstrumentPreferences {
    var headstockStyle: HeadstockStyle

    class InMemory(override var headstockStyle: HeadstockStyle = HeadstockStyle.INLINE_6) :
        InstrumentPreferences

    class Shared(context: Context) : InstrumentPreferences {
        private val prefs = context.applicationContext
            .getSharedPreferences("instrument", Context.MODE_PRIVATE)

        override var headstockStyle: HeadstockStyle
            get() = prefs.getString(KEY_HEADSTOCK, null)
                ?.let { saved -> HeadstockStyle.entries.firstOrNull { it.name == saved } }
                ?: HeadstockStyle.INLINE_6
            set(value) {
                prefs.edit().putString(KEY_HEADSTOCK, value.name).apply()
            }

        private companion object {
            const val KEY_HEADSTOCK = "guitarHeadstockStyle"
        }
    }
}
