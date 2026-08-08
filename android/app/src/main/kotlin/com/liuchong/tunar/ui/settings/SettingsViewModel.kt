package com.liuchong.tunar.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liuchong.tunar.audio.TunarConfigSink
import com.liuchong.tunar.data.AppSettings
import com.liuchong.tunar.data.SettingsRepository
import com.liuchong.tunar.data.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uniffi.tunar_core.KeyMode
import uniffi.tunar_core.SolfegeSystem

/**
 * 设置页 ViewModel：读写到 SettingsRepository（DataStore 持久化），
 * 并把设置映射为 TunarConfig 经 TunarConfigSink 即时下发运行中的引擎。
 */
class SettingsViewModel(
    private val repo: SettingsRepository,
    private val configSink: TunarConfigSink,
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            repo.settings.collect { s ->
                _settings.value = s
                configSink.applyConfig(s.toTunarConfig())
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
