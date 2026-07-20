package com.liuchong.tuner.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuchong.tuner.audio.CaptureHub
import com.liuchong.tuner.data.DataStoreSettingsRepository
import com.liuchong.tuner.data.SettingsRepository
import com.liuchong.tuner.data.ThemeMode
import java.util.Locale
import uniffi.tuner_core.KeyMode
import uniffi.tuner_core.ModeKind
import uniffi.tuner_core.SolfegeSystem

/** 设置页（spec-ui §4）。与 MainActivity 共享 activity 作用域 ViewModel。 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = run {
        val owner = LocalContext.current as androidx.activity.ComponentActivity
        val appContext = owner.applicationContext
        viewModel(
            viewModelStoreOwner = owner,
            initializer = {
                SettingsViewModel(
                    repo = DataStoreSettingsRepository(appContext),
                    configSink = CaptureHub,
                )
            },
        )
    },
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // A4 校准（415–466，步进 1Hz，显示 0.1）
        Section(title = "A4 校准") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { viewModel.setA4(s.a4Hz - 1) }) { Text("-1") }
                Slider(
                    value = s.a4Hz.toFloat(),
                    onValueChange = { viewModel.setA4(it.toDouble()) },
                    valueRange = 415f..466f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                OutlinedButton(onClick = { viewModel.setA4(s.a4Hz + 1) }) { Text("+1") }
            }
            Text(
                text = String.format(Locale.US, "%.1f Hz", s.a4Hz),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 唱名体系（下拉：「宫商角徵羽」长文在分段按钮中放不下）
        Section(title = "唱名体系") {
            SimpleDropdown(
                label = "唱名体系",
                value = SettingsRepository.solfegeLabel(s.solfege),
                options = SolfegeSystem.entries.map { SettingsRepository.solfegeLabel(it) },
                enabled = true,
                onSelect = { label ->
                    viewModel.setSolfege(
                        SolfegeSystem.entries.first {
                            SettingsRepository.solfegeLabel(it) == label
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 调式（固定 Do 时禁用）
        val keyEnabled = s.solfege != SolfegeSystem.FIXED_DO
        Section(title = "调式" + if (keyEnabled) "" else "（固定 Do 时无需设置）") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SimpleDropdown(
                    label = "主音",
                    value = SettingsRepository.TONIC_LABELS[s.key.tonicPc.toInt()],
                    options = SettingsRepository.TONIC_LABELS,
                    enabled = keyEnabled,
                    onSelect = { label ->
                        viewModel.setKey(
                            KeyMode(
                                tonicPc = SettingsRepository.TONIC_LABELS.indexOf(label).toUByte(),
                                mode = s.key.mode,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                SimpleDropdown(
                    label = "调式类别",
                    value = SettingsRepository.modeLabel(s.key.mode),
                    options = ModeKind.entries.map { SettingsRepository.modeLabel(it) },
                    enabled = keyEnabled,
                    onSelect = { label ->
                        viewModel.setKey(
                            KeyMode(
                                tonicPc = s.key.tonicPc,
                                mode = ModeKind.entries.first {
                                    SettingsRepository.modeLabel(it) == label
                                },
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 灵敏度（噪声门限 -60 ~ -30 dBFS）
        Section(title = "灵敏度（噪声门限）") {
            Slider(
                value = s.noiseGateDbfs,
                onValueChange = { viewModel.setNoiseGate(it) },
                valueRange = -60f..-30f,
            )
            Text(
                text = String.format(Locale.US, "%.0f dBFS（越高越不敏感）", s.noiseGateDbfs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 专业版模式（PRO 角标，与通用面板同源）
        Section(title = "专业版模式") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "PRO（律制选择 / 频谱分析增强）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = s.proMode,
                    onCheckedChange = { viewModel.setProMode(it) },
                )
            }
        }

        // 律制（PRO 开启时可见）
        if (s.proMode) {
            Section(title = "律制") {
                SimpleDropdown(
                    label = "平均律",
                    value = "${s.temperament}-TET",
                    options = SettingsRepository.TEMPERAMENT_DIVISIONS.map { "$it-TET" },
                    enabled = true,
                    onSelect = { label ->
                        viewModel.setTemperament(label.removeSuffix("-TET").toInt())
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 触觉反馈（默认开，design-system §8）
        Section(title = "触觉反馈") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "准音震动提示（进入准音区 / 准音保持）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = s.hapticsEnabled,
                    onCheckedChange = { viewModel.setHapticsEnabled(it) },
                )
            }
        }

        // 主题
        Section(title = "主题") {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = s.theme == mode,
                        onClick = { viewModel.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = i,
                            count = ThemeMode.entries.size,
                        ),
                    ) {
                        Text(mode.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    label: String,
    value: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { expanded = it && enabled },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
