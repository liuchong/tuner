package com.liuchong.tuner.ui.tuner

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.liuchong.tuner.data.SettingsRepository
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.theme.TunerSpacing
import com.liuchong.tuner.ui.theme.TunerTypography
import uniffi.tuner_core.KeyMode
import uniffi.tuner_core.ModeKind

/**
 * 调性选择面板内容（design-system v4 §6.3 浮动展开面板的内容部分）：
 * 标题 + 12 主音两行六列网格 + 调式 chips（大调/小调/宫/商/角/徵/羽）。
 */
@Composable
fun KeySelectorPanel(
    currentKey: KeyMode,
    onSelect: (KeyMode) -> Unit,
) {
    val colors = LocalLumenColors.current
    Column(modifier = Modifier.padding(TunerSpacing.lg)) {
        Text(
            "调性",
            style = MaterialTheme.typography.titleLarge,
            color = colors.inkPrimary,
        )
        Text(
            "相同音名在不同调性下显示不同唱名",
            style = TunerTypography.caption,
            color = colors.inkSecondary,
        )
        Spacer(modifier = Modifier.height(TunerSpacing.md))

        // 主音网格：6 × 2
        val tonicPc = currentKey.tonicPc.toInt()
        for (row in 0..1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(TunerSpacing.sm),
            ) {
                for (col in 0..5) {
                    val pc = row * 6 + col
                    val selected = pc == tonicPc
                    Surface(
                        onClick = { onSelect(KeyMode(pc.toUByte(), currentKey.mode)) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) colors.accent else colors.bgSurface,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = SettingsRepository.TONIC_LABELS[pc],
                            style = TunerTypography.label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) colors.bgCanvas else colors.inkPrimary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(TunerSpacing.sm))
        }

        Spacer(modifier = Modifier.height(TunerSpacing.xs))

        // 调式行（design-system §6.3 顺序）
        val modeOrder = listOf(
            ModeKind.MAJOR, ModeKind.MINOR,
            ModeKind.GONG, ModeKind.SHANG, ModeKind.JUE,
            ModeKind.ZHI, ModeKind.YU,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(TunerSpacing.sm),
        ) {
            modeOrder.forEach { mode ->
                FilterChip(
                    selected = mode == currentKey.mode,
                    onClick = { onSelect(KeyMode(currentKey.tonicPc, mode)) },
                    label = { Text(SettingsRepository.modeLabel(mode)) },
                )
            }
        }
        Spacer(modifier = Modifier.height(TunerSpacing.xs))
    }
}

/**
 * 律制选择面板内容（PRO 模式，design-system §6.9）：12 / 19 / 24 / 31 平均律。
 */
@Composable
fun TemperamentSelectorPanel(
    current: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalLumenColors.current
    Column(modifier = Modifier.padding(TunerSpacing.lg)) {
        Text(
            "律制",
            style = MaterialTheme.typography.titleLarge,
            color = colors.inkPrimary,
        )
        Text(
            "N 平均律（以 A4 为参考的级进网格）",
            style = TunerTypography.caption,
            color = colors.inkSecondary,
        )
        Spacer(modifier = Modifier.height(TunerSpacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TunerSpacing.sm),
        ) {
            SettingsRepository.TEMPERAMENT_DIVISIONS.forEach { n ->
                val selected = n == current
                Surface(
                    onClick = { onSelect(n) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) colors.accent else colors.bgSurface,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = "$n-TET",
                        style = TunerTypography.label,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) colors.bgCanvas else colors.inkPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(TunerSpacing.xs))
    }
}
