package com.liuchong.tunar.ui.metronome

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuchong.tunar.audio.AudioTrackMetronomePlayer
import com.liuchong.tunar.audio.MetronomeService
import com.liuchong.tunar.ui.common.AuroraBackground
import com.liuchong.tunar.corebinding.TickSoundKind
import com.liuchong.tunar.corebinding.TickSounds
import com.liuchong.tunar.corebinding.uniffiMetronomeFactory
import com.liuchong.tunar.ui.theme.LocalLumenColors
import com.liuchong.tunar.ui.theme.TunarTypography
import uniffi.tunar_core.TickAccent

/** 节拍器面板（spec-ui §3）。 */
@Composable
fun MetronomeScreen(
    viewModel: MetronomeViewModel = viewModel(initializer = {
        MetronomeViewModel(
            engineFactory = uniffiMetronomeFactory,
            player = AudioTrackMetronomePlayer(),
            sounds = TickSounds.buildAll(),
            savedState = createSavedStateHandle(),
        )
    }),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 通知栏停止按钮 → 停止播放
    DisposableEffect(Unit) {
        MetronomeService.onStopRequested = { viewModel.pause() }
        onDispose { MetronomeService.onStopRequested = null }
    }

    // Android 13+ 通知权限（拒绝时播放不受影响，仅通知栏不显示）
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    fun onPlayToggle() {
        val starting = viewModel.togglePlay()
        if (starting) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MetronomeService.start(context, viewModel.uiState.value.bpm)
        } else {
            MetronomeService.stop(context)
        }
    }

    AuroraBackground(tuneCents = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // BPM 环 + 垂直拨轮（design-system §6.5）
        var dragAccum by remember { mutableDoubleStateOf(0.0) }
        Box(
            modifier = Modifier.pointerInput(Unit) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    dragAccum += -dragAmount
                    while (dragAccum >= 16.0) {
                        viewModel.adjustBpm(1)
                        dragAccum -= 16.0
                    }
                    while (dragAccum <= -16.0) {
                        viewModel.adjustBpm(-1)
                        dragAccum += 16.0
                    }
                }
            },
        ) {
            BpmRing(bpm = state.bpm) {
                Text(
                    text = "BPM（上下拖动调节）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 摆锤（播放时摆动，停止归中）
        Pendulum(
            playing = state.playing,
            bpm = state.bpm,
            beatUnit = state.beatUnit,
        )

        // 滑杆 + 步进 + tap
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { viewModel.adjustBpm(-5) }) { Text("-5") }
            OutlinedButton(onClick = { viewModel.adjustBpm(-1) }) { Text("-1") }
            Slider(
                value = state.bpm.toFloat(),
                onValueChange = { viewModel.setBpm(it.toDouble()) },
                valueRange = 30f..250f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            OutlinedButton(onClick = { viewModel.adjustBpm(1) }) { Text("+1") }
            OutlinedButton(onClick = { viewModel.adjustBpm(5) }) { Text("+5") }
        }
        OutlinedButton(onClick = { viewModel.tap() }) { Text("TAP 测速") }

        Spacer(modifier = Modifier.height(8.dp))

        // 拍号选择
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            COMMON_TIME_SIGNATURES.forEach { (beats, unit) ->
                FilterChip(
                    selected = state.beatsPerBar == beats && state.beatUnit == unit,
                    onClick = { viewModel.setTimeSignature(beats, unit) },
                    label = { Text("$beats/$unit") },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 重音圆点行（点击循环 重拍/普通/静音；当前拍放大高亮）
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.accents.forEachIndexed { i, accent ->
                val isCurrent = state.playing && state.currentBeat == i
                val colors = LocalLumenColors.current
                // 重拍实心 / 普通半透 / 静音空心（形状+填充双重编码）
                val dotModifier = Modifier
                    .size(if (isCurrent) 18.dp else 12.dp)
                    .clip(CircleShape)
                    .clickable { viewModel.cycleAccent(i) }
                when (accent) {
                    TickAccent.ACCENT -> Box(
                        dotModifier.background(
                            if (isCurrent) colors.accent else colors.inkPrimary,
                        ),
                    )
                    TickAccent.NORMAL -> Box(
                        dotModifier.background(
                            (if (isCurrent) colors.accent else colors.inkPrimary)
                                .copy(alpha = 0.4f),
                        ),
                    )
                    TickAccent.MUTED -> Box(
                        dotModifier.border(
                            1.5.dp,
                            if (isCurrent) colors.accent else colors.inkSecondary,
                            CircleShape,
                        ),
                    )
                }
            }
        }
        // 小节进度
        LinearProgressIndicator(
            progress = {
                if (state.playing && state.currentBeat >= 0) {
                    (state.currentBeat + 1).toFloat() / state.beatsPerBar
                } else {
                    0f
                }
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )

        // 音色选择（重拍/弱拍）
        Row(modifier = Modifier.fillMaxWidth()) {
            SoundDropdown(
                label = "重拍音色",
                value = state.accentSound,
                onSelect = viewModel::setAccentSound,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            SoundDropdown(
                label = "弱拍音色",
                value = state.normalSound,
                onSelect = viewModel::setNormalSound,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 播放/停止大按钮（design-system §6.4：全宽 72dp、圆角 28dp，播放中 tune/off 红）
        Button(
            onClick = { onPlayToggle() },
            modifier = Modifier.fillMaxWidth().height(72.dp),
            shape = RoundedCornerShape(28.dp),
            colors = if (state.playing) {
                ButtonDefaults.buttonColors(containerColor = LocalLumenColors.current.tuneOff)
            } else {
                ButtonDefaults.buttonColors()
            },
        ) {
            Text(
                text = if (state.playing) "停止" else "播放",
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
    }
}

/** 音色下拉。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SoundDropdown(
    label: String,
    value: TickSoundKind,
    onSelect: (TickSoundKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = value.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TickSoundKind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.label) },
                    onClick = {
                        onSelect(kind)
                        expanded = false
                    },
                )
            }
        }
    }
}
