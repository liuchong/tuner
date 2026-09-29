package com.liuchong.tunar.ui.tools

import androidx.lifecycle.ViewModel
import com.liuchong.tunar.audio.ReferenceTonePlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import uniffi.tunar_core.ToolTone

/** 小工具页状态。同一时刻最多播放一个工具音频。 */
data class ToolsUiState(
    val tones: List<ToolTone> = emptyList(),
    val playingId: String? = null,
)

/** 小工具页控制器：音频列表来自 core，播放复用音叉的平滑正弦播放器。 */
class ToolsViewModel(
    tones: List<ToolTone>,
    private val player: ReferenceTonePlayer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ToolsUiState(tones = tones))
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    /** 点击正在播放的工具则停止，否则切换到该工具的频率。 */
    fun toggle(tone: ToolTone) {
        if (_uiState.value.playingId == tone.id) {
            stop()
            return
        }
        player.play(tone.frequencyHz)
        _uiState.update { it.copy(playingId = tone.id) }
    }

    /** 离开页面或应用进入后台时停止播放。 */
    fun stop() {
        if (_uiState.value.playingId == null) return
        player.stop()
        _uiState.update { it.copy(playingId = null) }
    }

    override fun onCleared() {
        player.close()
    }
}
