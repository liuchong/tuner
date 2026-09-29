package com.liuchong.tunar.ui.tools

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuchong.tunar.audio.AudioTrackReferenceTonePlayer
import com.liuchong.tunar.corebinding.TunarCore
import com.liuchong.tunar.ui.theme.LocalLumenColors
import com.liuchong.tunar.ui.theme.TunarSpacing
import com.liuchong.tunar.ui.theme.TunarTypography
import uniffi.tunar_core.ToolTone

/** 小工具页（spec-ui §4.1）：固定频率的纯正弦波工具，前台播放。 */
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val vm: ToolsViewModel = viewModel(
        initializer = {
            ToolsViewModel(
                tones = TunarCore.toolTones(),
                player = AudioTrackReferenceTonePlayer(appContext),
            )
        },
    )
    val state by vm.uiState.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stop() }
    DisposableEffect(vm) { onDispose(vm::stop) }

    val view = LocalView.current
    val playing = state.playingId != null
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    val colors = LocalLumenColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = TunarSpacing.lg, vertical = TunarSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(TunarSpacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("小工具", style = MaterialTheme.typography.titleLarge, color = colors.inkPrimary)
        }
        state.tones.forEach { tone ->
            ToolToneCard(
                tone = tone,
                playing = state.playingId == tone.id,
                onToggle = { vm.toggle(tone) },
            )
        }
        Text(
            text = "播放时保持亮屏；离开本页或切到后台会自动停止。" +
                "音频仅作舒缓辅助，不能替代药物或医疗建议。",
            style = TunarTypography.caption,
            color = colors.inkFaint,
        )
    }
}

@Composable
private fun ToolToneCard(tone: ToolTone, playing: Boolean, onToggle: () -> Unit) {
    val colors = LocalLumenColors.current
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(20.dp),
        color = colors.bgSurface,
        border = BorderStroke(1.dp, if (playing) colors.accent else colors.lineSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${tone.displayName}，${tone.summary}，" +
                    if (playing) "正在播放" else "未播放"
            },
    ) {
        Row(
            modifier = Modifier.padding(TunarSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tone.displayName, style = MaterialTheme.typography.titleMedium, color = colors.inkPrimary)
                Text(
                    text = if (playing) "${tone.summary} · 播放中" else tone.summary,
                    style = TunarTypography.label,
                    color = if (playing) colors.accent else colors.inkSecondary,
                )
            }
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (playing) colors.accent else colors.bgSurfaceRaised,
                    contentColor = if (playing) colors.bgCanvas else colors.accent,
                ),
            ) {
                Icon(
                    if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "停止" else "播放",
                )
            }
        }
    }
}
