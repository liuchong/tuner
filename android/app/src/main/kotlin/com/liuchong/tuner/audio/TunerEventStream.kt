package com.liuchong.tuner.audio

import kotlinx.coroutines.flow.StateFlow
import uniffi.tuner_core.AnalysisFrame
import uniffi.tuner_core.TunerConfig

/**
 * 调音事件流（采集共享层）：调音面板与乐器面板共用同一引擎/采集。
 * acquire/release 引用计数启停采集（面板切换时最后一个释放者停止）。
 */
interface TunerEventStream {
    /** 最新分析帧（v4：音高事件 + 频谱 + 泛音 + 和弦；无信号时 tuner=null）。 */
    val events: StateFlow<AnalysisFrame?>

    /** 采集是否运行中。 */
    val running: StateFlow<Boolean>

    /** 当前引擎配置（唱名体系/调式/A4）。 */
    val config: StateFlow<TunerConfig>

    /** 获取采集（引用计数 +1；首个获取者启动引擎与采集线程）。 */
    fun acquire()

    /** 释放采集（引用计数 -1；归零时停止）。 */
    fun release()
}
