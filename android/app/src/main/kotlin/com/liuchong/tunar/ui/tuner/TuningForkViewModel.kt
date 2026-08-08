package com.liuchong.tunar.ui.tuner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuchong.tunar.audio.ReferenceTonePlayer
import com.liuchong.tunar.audio.TunarEventStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.tunar_core.ReferenceTone
import uniffi.tunar_core.TunarConfig

/** 音叉浮窗状态。 */
data class TuningForkUiState(
    val isOpen: Boolean = false,
    val tones: List<ReferenceTone> = emptyList(),
    /** 最近一次由用户选择的音级；停止后保留，便于快捷恢复。 */
    val selectedStep: Int? = null,
    val playingStep: Int? = null,
)

/**
 * 音叉浮窗控制器。只控制浮窗和平台播放器，不 acquire/release 麦克风采集。
 */
class TuningForkViewModel(
    stream: TunarEventStream,
    private val toneProvider: (TunarConfig) -> List<ReferenceTone>,
    private val player: ReferenceTonePlayer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        TuningForkUiState(tones = toneProvider(stream.config.value)),
    )
    val uiState: StateFlow<TuningForkUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            stream.config.collect { config ->
                val tones = toneProvider(config)
                if (_uiState.value.playingStep != null) {
                    player.stop()
                }
                _uiState.update {
                    it.copy(tones = tones, selectedStep = null, playingStep = null)
                }
            }
        }
    }

    fun open() {
        _uiState.update { it.copy(isOpen = true) }
    }

    fun close() {
        _uiState.update { it.copy(isOpen = false) }
    }

    fun toggle(tone: ReferenceTone) {
        if (_uiState.value.playingStep == tone.stepFromA4) {
            player.stop()
            _uiState.update { it.copy(selectedStep = tone.stepFromA4, playingStep = null) }
        } else {
            player.play(tone.frequencyHz)
            _uiState.update {
                it.copy(selectedStep = tone.stepFromA4, playingStep = tone.stepFromA4)
            }
        }
    }

    /** 快捷停止或恢复最近一次选择的固定音高。 */
    fun toggleSelected() {
        val state = _uiState.value
        val selectedStep = state.selectedStep ?: return
        if (state.playingStep != null) {
            player.stop()
            _uiState.update { it.copy(playingStep = null) }
            return
        }
        val tone = state.tones.firstOrNull { it.stepFromA4 == selectedStep } ?: return
        player.play(tone.frequencyHz)
        _uiState.update { it.copy(playingStep = selectedStep) }
    }

    fun stopForBackground() {
        player.stop()
        _uiState.update { it.copy(playingStep = null) }
    }

    override fun onCleared() {
        player.close()
    }
}
