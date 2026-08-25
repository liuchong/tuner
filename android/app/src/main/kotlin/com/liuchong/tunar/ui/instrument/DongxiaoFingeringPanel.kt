package com.liuchong.tunar.ui.instrument

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/** 洞箫专用主面板：单支完整洞箫配低音/中音/高音三列，标签按孔心引导线对齐。 */
@Composable
fun DongxiaoFingeringPanel(
    state: InstrumentUiState,
    viewModel: InstrumentViewModel,
) {
    var showDetail by remember { mutableStateOf(false) }
    val chartHeight = (LocalConfiguration.current.screenHeightDp * 0.40f)
        .dp
        .coerceIn(300.dp, 340.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimpleDropdown(
            label = "调",
            value = state.keyName,
            options = state.keyNames.map { it to it },
            onSelect = viewModel::selectWindKey,
            modifier = Modifier.weight(1f),
        )
        if (state.holeSystems.isNotEmpty()) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.height(48.dp)) {
                state.holeSystems.forEachIndexed { index, name ->
                    SegmentedButton(
                        selected = state.holeSystem == name,
                        onClick = { viewModel.selectHoleSystem(name) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = state.holeSystems.size,
                        ),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(name, maxLines = 1)
                    }
                }
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = state.keyDisplay,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { showDetail = true }) {
            Icon(Icons.Filled.OpenInFull, contentDescription = "查看完整十二音指法表")
        }
    }

    DongxiaoAnchoredChart(
        notes = state.notes,
        holeCount = state.holeCount,
        backHoleCount = state.backHoleCount,
        previewFingeringId = state.mainPreviewFingeringId,
        tongyinDegree = state.tongyinDegree,
        naturalScaleOnly = true,
        onPreview = viewModel::previewMainFingering,
        onStepTongyin = viewModel::stepTongyin,
        modifier = Modifier.fillMaxWidth().height(chartHeight),
    )

    if (showDetail) {
        ChromaticFingeringDialog(
            state = state,
            onPreview = viewModel::previewDetailFingering,
            onStepTongyin = viewModel::stepDetailTongyin,
            onDismiss = { showDetail = false },
        )
    }
}
