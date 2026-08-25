package com.liuchong.tunar.ui.instrument

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 覆盖应用底栏的洞箫十二音详情：单支完整洞箫配低音/中音/高音三列。 */
@Composable
fun ChromaticFingeringDialog(
    state: InstrumentUiState,
    onPreview: (Int) -> Unit,
    onStepTongyin: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "洞箫十二音指法",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text =
                                "${state.keyName} · ${state.holeSystem} · ${state.detailKeyDisplay}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭十二音指法详情")
                    }
                }

                DongxiaoAnchoredChart(
                    notes = state.detailNotes,
                    holeCount = state.holeCount,
                    backHoleCount = state.backHoleCount,
                    previewFingeringId = state.detailPreviewFingeringId,
                    tongyinDegree = state.detailTongyinDegree,
                    naturalScaleOnly = false,
                    onPreview = onPreview,
                    onStepTongyin = onStepTongyin,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 8.dp),
                )
            }
        }
    }
}
