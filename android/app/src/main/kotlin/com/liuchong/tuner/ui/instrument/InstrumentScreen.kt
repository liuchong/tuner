package com.liuchong.tuner.ui.instrument

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.createSavedStateHandle
import com.liuchong.tuner.audio.CaptureHub
import com.liuchong.tuner.corebinding.TunerCore
import com.liuchong.tuner.ui.common.AuroraBackground
import com.liuchong.tuner.ui.common.AudioPermissionGate
import com.liuchong.tuner.ui.common.StatusChip
import com.liuchong.tuner.ui.theme.LocalLumenColors
import com.liuchong.tuner.ui.theme.TunerSpacing
import com.liuchong.tuner.ui.theme.TunerTypography
import com.liuchong.tuner.ui.theme.tuneColor
import com.liuchong.tuner.ui.tuner.TunerDial
import kotlinx.coroutines.delay
import uniffi.tuner_core.InstrumentKind
import java.util.Locale

/** 乐器面板（spec-ui §2）。 */
@Composable
fun InstrumentScreen(
    viewModel: InstrumentViewModel = viewModel(initializer = {
        InstrumentViewModel(
            core = TunerCore,
            stream = CaptureHub,
            savedState = createSavedStateHandle(),
        )
    }),
) {
    AudioPermissionGate(onGranted = viewModel::startCapture) {
        val state by viewModel.uiState.collectAsStateWithLifecycle()

        AuroraBackground(tuneCents = state.centsToTarget) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // 乐器选择卡片行（design-system §6.6：图标+名称，选中 accent 10% 底+描边）
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val colors = LocalLumenColors.current
                state.instruments.forEach { inst ->
                    val selected = inst.id == state.instrumentId
                    Surface(
                        onClick = { viewModel.selectInstrument(inst.id) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) {
                            colors.accent.copy(alpha = 0.10f)
                        } else {
                            colors.bgSurface
                        },
                        modifier = Modifier.border(
                            1.5.dp,
                            if (selected) colors.accent else colors.lineSubtle,
                            RoundedCornerShape(16.dp),
                        ),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(48.dp)
                                .padding(horizontal = 14.dp),
                        ) {
                            Icon(
                                Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = if (selected) colors.accent else colors.inkSecondary,
                                modifier = Modifier.height(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                inst.displayName,
                                style = TunerTypography.label,
                                color = if (selected) colors.accent else colors.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            when (state.kind) {
                InstrumentKind.STRING -> StringInstrumentSection(state, viewModel)
                InstrumentKind.WIND -> WindInstrumentSection(state, viewModel)
            }

            // 区间距少量加权分散（design-system §5 填充率纪律，总空白 ≤10% 屏高）
            Spacer(modifier = Modifier.weight(1f))

            // 目标读数行（与表盘组成视觉组；高度固定，有/无信号同构不跳动）
            val animatedCents = remember { Animatable(0f) }
            LaunchedEffect(state.centsToTarget) {
                animatedCents.animateTo(
                    targetValue = (state.centsToTarget ?: 0f).coerceIn(-50f, 50f),
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 800f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(0.35f + 0.65f * state.displayStrength),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val colors = LocalLumenColors.current
                // 目标名：有信号取识别目标；无信号时手动模式回退到选中弦（「目标 E4 · —」）
                val targetName = state.targetNoteName
                    ?: if (state.kind == InstrumentKind.STRING) {
                        state.strings.getOrNull(state.manualIndex)?.noteName
                    } else {
                        null
                    }
                if (targetName != null) {
                    val color = state.centsToTarget?.let { tuneColor(it) } ?: colors.inkSecondary
                    Text(
                        text = "目标 ${targetName.replace("#", "♯")}",
                        style = TunerTypography.readoutSolfege,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = state.centsToTarget?.let {
                            String.format(Locale.US, "%+.1f cents", it)
                        } ?: "—",
                        style = TunerTypography.readoutValue,
                        color = color,
                    )
                } else {
                    // 未选目标时提示（占位同高）
                    Text(
                        text = "目标 · —",
                        style = TunerTypography.readoutSolfege,
                        color = colors.inkFaint,
                    )
                }
            }
            Spacer(modifier = Modifier.height(TunerSpacing.sm))
            // 表盘区（圆心不放文字；读数在其上方，与本表盘成组）
            TunerDial(
                cents = state.centsToTarget?.let { animatedCents.value },
                accessibilityText = state.targetNoteName?.let { "目标 $it" } ?: "无信号，请发声",
                modifier = Modifier
                    .fillMaxWidth()
                    .height((LocalConfiguration.current.screenHeightDp * 0.32f).dp),
            )
            // 布局不变量（同调音页）：读数块 top ≥ 表盘区域 bottom + 16dp
            Spacer(modifier = Modifier.height(16.dp))
            Spacer(modifier = Modifier.weight(1f))

            // 底部状态胶囊
            StatusChip(visible = state.centsToTarget == null)
            Spacer(modifier = Modifier.height(TunerSpacing.sm))
        }
        }
    }
}

/** 弦乐器区（定弦选择 + 模式切换 + 琴弦按钮行）。 */
@Composable
private fun StringInstrumentSection(state: InstrumentUiState, vm: InstrumentViewModel) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 380.dp
        val controls: @Composable () -> Unit = {
            SimpleDropdown(
                label = "定弦",
                value = state.tuningName,
                options = state.tunings.map { it.id to it.displayName },
                onSelect = vm::selectTuning,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val modes: @Composable () -> Unit = {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.height(48.dp)) {
                SelectionMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { vm.selectMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = i,
                            count = SelectionMode.entries.size,
                        ),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Text(
                            if (mode == SelectionMode.AUTO) "自动" else "手动",
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (compact) {
            Column {
                controls()
                Spacer(modifier = Modifier.height(8.dp))
                modes()
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) { controls() }
                modes()
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    // 琴弦横排按钮
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.strings.forEach { s ->
            StringButton(item = s, onClick = { vm.selectString(s.index - 1) })
        }
    }
}

/** 单个琴弦按钮（design-system §6.3 药丸）：弦号/音名/唱名三层；
 *  选中 accent 描边+微光，准音 tune/in 12% 填充+✓，按压 scale 0.96。 */
@Composable
private fun StringButton(item: StringItemUi, onClick: () -> Unit) {
    val colors = LocalLumenColors.current
    // 按压缩放 0.96（100ms）
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(100),
        label = "stringButtonScale",
    )
    val borderColor = when {
        item.inTune -> colors.tuneIn
        item.active -> colors.accent
        else -> colors.lineSubtle
    }
    val containerColor = when {
        item.inTune -> colors.tuneIn.copy(alpha = 0.12f)
        item.active -> colors.accent.copy(alpha = 0.10f)
        else -> colors.bgSurface
    }
    val description = if (item.inTune) {
        "${item.index} 弦 ${item.noteName}，已调准"
    } else {
        "${item.index} 弦 ${item.noteName}，未调准"
    }
    val pillShape = RoundedCornerShape(24.dp)
    Surface(
        onClick = onClick,
        shape = pillShape,
        color = containerColor,
        interactionSource = interactionSource,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(1.5.dp, borderColor, pillShape)
            .semantics { contentDescription = description },
    ) {
        // 微渐变底 + 顶部 1dp 内高光（design-system §6.4/§3.1）
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        listOf(colors.bgSurface, colors.bgSurfaceEnd),
                    ),
                )
                .drawBehind {
                    drawLine(
                        color = colors.highlightInner,
                        start = Offset(0f, 0.5f),
                        end = Offset(size.width, 0.5f),
                        strokeWidth = 1f,
                    )
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${item.index}", style = TunerTypography.caption, color = colors.inkSecondary)
                if (item.inTune) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = colors.tuneIn,
                        modifier = Modifier.height(14.dp),
                    )
                }
            }
            Text(
                item.noteName.replace("#", "♯"),
                style = TunerTypography.label,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
            )
            Text(item.solfege, style = TunerTypography.caption, color = colors.inkSecondary)
        }
    }
}

/** 管乐器区（调性/筒音唱名选择 + 指法音阶列表）。 */
@Composable
private fun WindInstrumentSection(state: InstrumentUiState, vm: InstrumentViewModel) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 380.dp
        val selector: @Composable () -> Unit = {
            SimpleDropdown(
                label = if (state.tongyinOptions.isEmpty()) "型号" else "调性",
                value = state.chartGroup,
                options = state.chartGroups.map { it to it },
                onSelect = { vm.selectChart(it, state.tongyin) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val tongyin: @Composable () -> Unit = {
            if (state.tongyinOptions.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.height(48.dp)) {
                    state.tongyinOptions.forEachIndexed { i, ty ->
                        SegmentedButton(
                            selected = state.tongyin == ty,
                            onClick = { vm.selectChart(state.chartGroup, ty) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = i,
                                count = state.tongyinOptions.size,
                            ),
                            modifier = Modifier.height(48.dp),
                        ) {
                            Text("作$ty", maxLines = 1)
                        }
                    }
                }
            }
        }
        if (compact && state.tongyinOptions.isNotEmpty()) {
            Column {
                selector()
                Spacer(modifier = Modifier.height(8.dp))
                tongyin()
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) { selector() }
                tongyin()
            }
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    // 指法音阶列表
    LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        items(state.notes) { n ->
            val bg = when {
                n.active -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surface
            }
            Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n.label, modifier = Modifier.weight(1f))
                    Text(
                        n.noteName.replace("#", "♯"),
                        fontWeight = if (n.active) FontWeight.Bold else FontWeight.Normal,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(n.solfege)
                }
            }
        }
    }
}

/** 简单下拉选择。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleDropdown(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = LocalLumenColors.current
    Box(modifier = modifier) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            Surface(
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(16.dp),
                color = colors.bgSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.lineSubtle),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$label · ",
                        style = TunerTypography.caption,
                        color = colors.inkSecondary,
                        maxLines = 1,
                    )
                    Text(
                        value,
                        modifier = Modifier.weight(1f),
                        style = TunerTypography.label,
                        color = colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            }
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
