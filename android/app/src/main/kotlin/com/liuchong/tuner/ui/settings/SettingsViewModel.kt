package com.liuchong.tuner.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuchong.tuner.audio.TunerConfigSink
import com.liuchong.tuner.data.AppSettings
import com.liuchong.tuner.data.SettingsRepository
import com.liuchong.tuner.data.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.tuner_core.KeyMode
import uniffi.tuner_core.SolfegeSystem

/**
 * 设置页 ViewModel：读写到 SettingsRepository（DataStore 持久化），
 * 并把设置映射为 TunerConfig 经 TunerConfigSink 即时下发运行中的引擎。
 */
class SettingsViewModel(
    private val repo: SettingsRepository,
    private val configSink: TunerConfigSink,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            repo.settings.collect { s ->
                _settings.value = s
                configSink.applyConfig(s.toTunerConfig())
            }
        }
    }

    fun setA4(hz: Double) = viewModelScope.launch { repo.setA4(hz) }

    fun setSolfege(system: SolfegeSystem) =
        viewModelScope.launch { repo.setSolfege(system) }

    fun setKey(key: KeyMode) = viewModelScope.launch { repo.setKey(key) }

    fun setNoiseGate(dbfs: Float) = viewModelScope.launch { repo.setNoiseGate(dbfs) }

    fun setTheme(theme: ThemeMode) = viewModelScope.launch { repo.setTheme(theme) }

    fun setHapticsEnabled(enabled: Boolean) =
        viewModelScope.launch { repo.setHapticsEnabled(enabled) }

    fun setProMode(enabled: Boolean) = viewModelScope.launch { repo.setProMode(enabled) }

    fun setTemperament(divisions: Int) =
        viewModelScope.launch { repo.setTemperament(divisions) }
}
