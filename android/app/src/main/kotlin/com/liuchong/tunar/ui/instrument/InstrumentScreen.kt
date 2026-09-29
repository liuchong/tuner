package com.liuchong.tunar.ui.instrument

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.liuchong.tunar.audio.CaptureHub
import com.liuchong.tunar.corebinding.TunarCore
import com.liuchong.tunar.ui.common.AudioPermissionGate
import com.liuchong.tunar.ui.common.AuroraBackground
import com.liuchong.tunar.ui.theme.LocalLumenColors
import com.liuchong.tunar.ui.theme.LumenColors
import com.liuchong.tunar.ui.theme.TunarTypography
import com.liuchong.tunar.ui.theme.tuneColor
import com.liuchong.tunar.ui.tuner.TunerDial
import uniffi.tunar_core.InstrumentKind
import java.util.Locale

/** 乐器面板（spec-ui §2）：乐器切换条 + 型号控件 + 乐器图示 + 目标读数与表盘成组。 */
@Composable
fun InstrumentScreen(
    viewModel: InstrumentViewModel = run {
        val context = LocalContext.current.applicationContext
        viewModel(initializer = {
            InstrumentViewModel(
                core = TunarCore,
                stream = CaptureHub,
                savedState = createSavedStateHandle(),
                preferences = InstrumentPreferences.Shared(context),
            )
        })
    },
) {
    AudioPermissionGate(onGranted = viewModel::startCapture) {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val animatedCents = remember { Animatable(0f) }
        LaunchedEffect(state.centsToTarget) {
            animatedCents.animateTo(
                targetValue = (state.centsToTarget ?: 0f).coerceIn(-50f, 50f),
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 800f),
            )
        }
        val screenHeight = LocalConfiguration.current.screenHeightDp.toFloat()
        val dialHeight = (screenHeight * if (state.kind == InstrumentKind.WIND) 0.21f else 0.25f)
            .coerceIn(132f, 220f).dp

        AuroraBackground(tuneCents = state.centsToTarget) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InstrumentSwitcher(state, viewModel::selectInstrument)
                ControlRow(state, viewModel)
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    FigureArea(state, viewModel)
                }
                TargetReadout(
                    targetName = readoutTarget(state),
                    placeholder = if (state.kind == InstrumentKind.WIND) "按指法吹奏" else "自动识别",
                    cents = state.centsToTarget,
                )
                TunerDial(
                    cents = state.centsToTarget?.let { animatedCents.value },
                    accessibilityText = state.targetNoteName?.let { "目标 $it" } ?: "无信号，请发声",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(dialHeight)
                        .graphicsLayer {
                            alpha = if (state.centsToTarget == null) 1f else 0.35f + 0.65f * state.displayStrength
                        },
                )
            }
        }
    }
}

/** 有信号显示实时目标；无信号时手动锁定的弦仍给出目标，自动模式显示占位。 */
private fun readoutTarget(state: InstrumentUiState): String? =
    state.targetNoteName ?: if (state.kind == InstrumentKind.STRING) {
        state.selectedStringIndex?.let { state.strings.getOrNull(it)?.noteName }
    } else {
        null
    }

// region 切换条与控件

/** 六种乐器等宽平铺，不需要横向滚动；每个乐器一枚线稿图标。 */
@Composable
private fun InstrumentSwitcher(state: InstrumentUiState, onSelect: (String) -> Unit) {
    val colors = LocalLumenColors.current
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.instruments.forEach { inst ->
            val selected = inst.id == state.instrumentId
            PressableSurface(
                onClick = { onSelect(inst.id) },
                shape = RoundedCornerShape(14.dp),
                color = if (selected) colors.accent.copy(alpha = 0.12f) else colors.bgSurface,
                border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) colors.accent else colors.lineSubtle),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .semantics {
                        this.selected = selected
                        contentDescription = inst.displayName
                    },
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    InstrumentGlyph(
                        instrumentId = inst.id,
                        color = if (selected) colors.accent else colors.inkSecondary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        inst.displayName,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) colors.accent else colors.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlRow(state: InstrumentUiState, vm: InstrumentViewModel) {
    if (state.kind == InstrumentKind.WIND) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PillDropdown(
                value = state.keyName,
                options = state.keyNames.map { it to it },
                onSelect = vm::selectWindKey,
                accessibilityLabel = "${state.windFigure.displayName}型号，${state.keyName}",
            )
            Spacer(modifier = Modifier.weight(1f))
            if (state.holeSystems.size > 1) {
                LumenSegmented(
                    options = state.holeSystems,
                    selected = state.holeSystem,
                    title = { it },
                    onSelect = vm::selectHoleSystem,
                )
            }
        }
        return
    }
    val headstock = (state.stringFigure as? StringFigureKind.Headstock)?.style
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tuning: @Composable () -> Unit = {
            PillDropdown(
                value = state.tuningName,
                options = state.tunings.map { it.id to it.displayName },
                onSelect = vm::selectTuning,
                accessibilityLabel = "定弦，${state.tuningName}",
            )
        }
        val compact = headstock != null && maxWidth < 380.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) { tuning() }
            if (headstock != null) {
                LumenSegmented(
                    options = HeadstockStyle.entries,
                    selected = headstock,
                    title = { it.displayName },
                    accessibilityTitle = { it.accessibilityName },
                    onSelect = vm::selectHeadstockStyle,
                    horizontalPadding = if (compact) 8.dp else 14.dp,
                    equalWidths = true,
                )
            }
            AutoModeToggle(state.mode, vm::selectMode, showIcon = !compact)
        }
    }
}

/** 按下轻微缩放的平铺按钮。 */
@Composable
private fun PressableSurface(
    onClick: () -> Unit,
    shape: Shape,
    color: androidx.compose.ui.graphics.Color,
    border: BorderStroke?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "pressScale")
    Surface(
        onClick = onClick,
        shape = shape,
        color = color,
        border = border,
        interactionSource = interaction,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        content = content,
    )
}

/** 48dp 高的胶囊分段选择（design-system §6.6）。 */
@Composable
internal fun <T> LumenSegmented(
    options: List<T>,
    selected: T,
    title: (T) -> String,
    onSelect: (T) -> Unit,
    accessibilityTitle: (T) -> String = title,
    horizontalPadding: Dp = 14.dp,
    /** 各段等宽（取最宽一段），文字居中；用于只有两三项、字数差距大的切换。 */
    equalWidths: Boolean = false,
) {
    val colors = LocalLumenColors.current
    Row(
        modifier = Modifier
            .height(48.dp)
            .then(if (equalWidths) Modifier.width(IntrinsicSize.Max) else Modifier)
            .background(colors.bgSurface, CircleShape)
            .border(1.dp, colors.lineSubtle, CircleShape),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val bg by animateColorAsState(
                if (isSelected) colors.accent else colors.bgSurface.copy(alpha = 0f),
                tween(150),
                label = "segment",
            )
            Surface(
                onClick = { onSelect(option) },
                shape = CircleShape,
                color = bg,
                modifier = Modifier
                    .height(48.dp)
                    .then(if (equalWidths) Modifier.weight(1f) else Modifier)
                    .semantics {
                        role = Role.Tab
                        this.selected = isSelected
                        contentDescription = accessibilityTitle(option)
                    },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = horizontalPadding)) {
                    Text(
                        title(option),
                        style = TunarTypography.label,
                        color = if (isSelected) colors.bgCanvas else colors.inkPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 48dp 高胶囊下拉触发器。 */
@Composable
private fun PillDropdown(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    accessibilityLabel: String,
) {
    val colors = LocalLumenColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        PressableSurface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = colors.bgSurface,
            border = BorderStroke(1.dp, colors.lineSubtle),
            modifier = Modifier
                .height(48.dp)
                .semantics {
                    role = Role.DropdownList
                    contentDescription = accessibilityLabel
                },
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    value,
                    style = TunarTypography.label,
                    color = colors.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.inkSecondary, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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

/** 自动选弦开关：点亮即自动识别最近的弦；点任意弦进入手动锁定，开关随之熄灭，再点回到自动。 */
@Composable
private fun AutoModeToggle(mode: SelectionMode, onSelect: (SelectionMode) -> Unit, showIcon: Boolean = true) {
    val colors = LocalLumenColors.current
    val isAuto = mode == SelectionMode.AUTO
    PressableSurface(
        onClick = { onSelect(if (isAuto) SelectionMode.MANUAL else SelectionMode.AUTO) },
        shape = CircleShape,
        color = if (isAuto) colors.accent else colors.bgSurface,
        border = BorderStroke(1.dp, if (isAuto) colors.accent else colors.lineSubtle),
        modifier = Modifier
            .height(48.dp)
            .semantics {
                role = Role.Switch
                contentDescription = "自动选弦"
                stateDescription = if (isAuto) "开，自动识别最近的弦" else "关，手动锁定选中的弦"
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (showIcon) 14.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val tint = if (isAuto) colors.bgCanvas else colors.inkPrimary
            if (showIcon) {
                Icon(
                    if (isAuto) Icons.Filled.GraphicEq else Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(if (isAuto) "自动" else "手动", style = TunarTypography.label, color = tint, maxLines = 1)
        }
    }
}

// endregion

// region 图示区

@Composable
private fun FigureArea(state: InstrumentUiState, vm: InstrumentViewModel) {
    val colors = LocalLumenColors.current
    val ink = FigureInk.from(colors)
    if (state.kind == InstrumentKind.WIND) {
        WindFingeringPanel(state = state, viewModel = vm, modifier = Modifier.fillMaxSize())
        return
    }
    when (val figure = state.stringFigure) {
        StringFigureKind.Guqin -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                state.strings.forEach { item ->
                    GuqinStringButton(
                        item = item,
                        selected = state.selectedStringIndex == item.index - 1,
                        onClick = { vm.selectString(item.index - 1) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                GuqinFigure(
                    strings = state.strings,
                    selectedIndex = state.selectedStringIndex,
                    ink = ink,
                    onSelect = vm::selectString,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(GuqinGeometry.DESIGN.width / GuqinGeometry.DESIGN.height),
                )
            }
        }
        is StringFigureKind.Headstock, StringFigureKind.UkuleleHeadstock -> {
            val layout = HeadstockLayout.of(figure) ?: return
            HeadstockPanel(
                layout = layout,
                strings = state.strings,
                selectedIndex = state.selectedStringIndex,
                ink = ink,
                buttonWidth = 78.dp,
                buttonHeight = 50.dp,
                onSelect = vm::selectString,
                modifier = Modifier.fillMaxSize(),
            ) { item ->
                PegButton(
                    item = item,
                    selected = state.selectedStringIndex == item.index - 1,
                    onClick = { vm.selectString(item.index - 1) },
                )
            }
        }
        StringFigureKind.None -> Unit
    }
}

private data class StringButtonColors(
    val border: androidx.compose.ui.graphics.Color,
    val fill: androidx.compose.ui.graphics.Color,
    val number: androidx.compose.ui.graphics.Color,
)

private fun stringButtonColors(item: StringItemUi, selected: Boolean, colors: LumenColors): StringButtonColors {
    val highlighted = item.active || selected
    return when {
        item.inTune && highlighted -> StringButtonColors(colors.tuneIn, colors.tuneIn.copy(alpha = 0.14f), colors.tuneIn)
        highlighted -> StringButtonColors(colors.accent, colors.accent.copy(alpha = 0.14f), colors.accent)
        else -> StringButtonColors(colors.lineSubtle, colors.bgSurface, colors.inkSecondary)
    }
}

private fun stringDescription(item: StringItemUi, selected: Boolean) = when {
    item.inTune -> "已调准"
    item.active || selected -> "当前弦"
    else -> "未选中"
}

/** 琴头两侧的音高按钮：弦号徽标 + 音名 + 唱名。 */
@Composable
private fun PegButton(item: StringItemUi, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalLumenColors.current
    val c = stringButtonColors(item, selected, colors)
    PressableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = c.fill,
        border = BorderStroke(1.5.dp, c.border),
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "${item.index} 弦 ${item.noteName}，唱名 ${item.solfege}"
                stateDescription = stringDescription(item, selected)
            },
    ) {
        BoxWithConstraints {
            val singleLine = maxHeight < 44.dp
            Row(
                modifier = Modifier.fillMaxSize().padding(start = if (singleLine) 7.dp else 9.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (singleLine) 5.dp else 7.dp),
            ) {
                StringBadge(item, c.number, if (singleLine) 18.dp else 20.dp)
                val note: @Composable () -> Unit = {
                    Text(
                        item.noteName.replace("#", "♯"),
                        fontSize = if (singleLine) 15.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.inkPrimary,
                        maxLines = 1,
                    )
                }
                val solfege: @Composable () -> Unit = {
                    Text(
                        item.solfege,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.inkSecondary,
                        maxLines = 1,
                    )
                }
                if (singleLine) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        note()
                        solfege()
                    }
                } else {
                    Column {
                        note()
                        solfege()
                    }
                }
            }
        }
    }
}

/** 古琴一排七弦的音高按钮：竖排弦号 / 音名 / 唱名。 */
@Composable
private fun GuqinStringButton(item: StringItemUi, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = LocalLumenColors.current
    val c = stringButtonColors(item, selected, colors)
    PressableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = c.fill,
        border = BorderStroke(1.5.dp, c.border),
        modifier = modifier
            .height(60.dp)
            .semantics {
                contentDescription = "${item.index} 弦 ${item.noteName}，唱名 ${item.solfege}"
                stateDescription = stringDescription(item, selected)
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (item.inTune) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = c.number, modifier = Modifier.size(12.dp))
            } else {
                Text("${item.index}", fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = c.number)
            }
            Text(
                item.noteName.replace("#", "♯"),
                fontSize = 15.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                color = colors.inkPrimary,
                maxLines = 1,
            )
            Text(item.solfege, fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, color = colors.inkSecondary)
        }
    }
}

@Composable
private fun StringBadge(item: StringItemUi, tint: androidx.compose.ui.graphics.Color, size: Dp) {
    Box(
        modifier = Modifier.size(size).border(1.2.dp, tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (item.inTune) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = tint, modifier = Modifier.size(11.dp))
        } else {
            Text("${item.index}", fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = tint)
        }
    }
}

// endregion

/** 目标读数（与表盘成组）：左侧目标音名，右侧音分偏差；无信号时右侧提示发声。高度固定不跳动。 */
@Composable
private fun TargetReadout(targetName: String?, placeholder: String, cents: Float?) {
    val colors = LocalLumenColors.current
    val color = cents?.let { tuneColor(it) } ?: colors.inkSecondary
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("目标", style = TunarTypography.caption, color = colors.inkFaint)
            if (targetName != null) {
                Text(
                    targetName.replace("#", "♯"),
                    fontSize = 28.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            } else {
                Text(
                    placeholder,
                    fontSize = 18.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkSecondary,
                )
            }
        }
        if (cents != null) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    String.format(Locale.US, "%+.1f", cents),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                Text("cents", style = TunarTypography.caption, color = colors.inkSecondary, modifier = Modifier.padding(bottom = 5.dp))
            }
        } else {
            Row(
                modifier = Modifier
                    .height(32.dp)
                    .background(colors.bgSurface, CircleShape)
                    .border(1.dp, colors.lineSubtle, CircleShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = colors.inkSecondary, modifier = Modifier.size(14.dp))
                Text("请发声", style = TunarTypography.caption, color = colors.inkSecondary)
            }
        }
    }
}
