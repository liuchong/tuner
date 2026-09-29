package com.liuchong.tunar.ui.instrument

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.liuchong.tunar.ui.common.AuroraBackground
import com.liuchong.tunar.ui.theme.LocalLumenColors
import com.liuchong.tunar.ui.theme.TunarTypography
import kotlinx.coroutines.launch
import uniffi.tunar_core.FingeringKind
import uniffi.tunar_core.HoleMark
import uniffi.tunar_core.WindRegister
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

private val FIGURE_WIDTH = 92.dp
private val FIGURE_SPACING = 6.dp
private val COLUMN_MIN_WIDTH = 76.dp
private val CELL_HEIGHT = 22.dp
private val CELL_SHAPE = RoundedCornerShape(5.dp)
private val SOLFEGE_STEPS = listOf("1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "b7", "7")
private val NATURAL_SOLFEGE_STEPS = listOf("1", "2", "3", "4", "5", "6", "7")
private val NATURAL_DEGREES = listOf(0, 2, 4, 5, 7, 9, 11)

/** 保证大图始终按当前孔制绘制；无预览 / 异常短数据时按全闭补齐。 */
internal fun normalizedDiagramHoles(note: ChartNoteUi?, holeCount: Int): List<HoleMark> {
    if (holeCount <= 0) return emptyList()
    return List(holeCount) { index -> note?.holes?.getOrNull(index) ?: HoleMark.CLOSED }
}

/** 松手时按最近的一档结算。 */
internal fun tongyinDragSteps(dragOffsetPx: Float, stepPx: Float): Int =
    if (stepPx > 0f) (-dragOffsetPx / stepPx).roundToInt() else 0

internal data class TongyinDragProgress(
    val residualOffsetPx: Float,
    val committedSteps: Int,
)

/** 拖动越过整档时立即循环换调，并只保留不足一档的连续位移。 */
internal fun consumeTongyinDrag(
    residualOffsetPx: Float,
    deltaPx: Float,
    stepPx: Float,
): TongyinDragProgress {
    if (stepPx <= 0f) return TongyinDragProgress(residualOffsetPx, 0)
    val total = residualOffsetPx + deltaPx
    val steps = when {
        total <= -stepPx -> floor(-total / stepPx).toInt()
        total >= stepPx -> -floor(total / stepPx).toInt()
        else -> 0
    }
    return TongyinDragProgress(
        residualOffsetPx = total + steps * stepPx,
        committedSteps = steps,
    )
}

/**
 * 让正在吹响的音区列尽量居中：算出居中所需位移后夹回 `0..maxScrollPx`，
 * 边界处只能移动到合法的最大位移，因此这是一个「趋势」而非精确居中。
 */
internal fun registerScrollTargetPx(
    columnIndex: Int,
    columnCount: Int,
    contentWidthPx: Float,
    viewportWidthPx: Float,
    maxScrollPx: Float,
): Int {
    if (columnIndex < 0 || columnCount <= 0) return 0
    val columnWidthPx = contentWidthPx / columnCount
    val columnCenterPx = (columnIndex + 0.5f) * columnWidthPx
    val desired = columnCenterPx - viewportWidthPx / 2f
    return desired.coerceIn(0f, maxScrollPx.coerceAtLeast(0f)).roundToInt()
}

internal fun shiftedSolfege(
    solfege: String,
    steps: Int,
    naturalScaleOnly: Boolean = false,
): String {
    val values = if (naturalScaleOnly) NATURAL_SOLFEGE_STEPS else SOLFEGE_STEPS
    val index = values.indexOf(solfege)
    if (index < 0) return solfege
    return values[Math.floorMod(index + steps, values.size)]
}

private fun steppedTongyinDegree(degree: Int, steps: Int, naturalScaleOnly: Boolean): Int {
    if (!naturalScaleOnly) return Math.floorMod(degree + steps, 12)
    val index = NATURAL_DEGREES.indexOf(degree)
    if (index < 0) return degree
    return NATURAL_DEGREES[Math.floorMod(index + steps, NATURAL_DEGREES.size)]
}

/** 同一孔位锚点上的一行：三个音区各一格。 */
internal data class WindChartRow(
    val baseSemitones: Int,
    val anchorHole: Int?,
    val notesByRegister: Map<WindRegister, ChartNoteUi>,
)

internal fun windChartRows(notes: List<ChartNoteUi>): List<WindChartRow> = notes
    .groupBy { it.baseSemitones }
    .toSortedMap()
    .map { (base, entries) ->
        WindChartRow(base, entries.first().anchorHole, entries.associateBy { it.register })
    }

/** 管乐主指法表卡片；十二音完整表在全屏详情中显示，不替换主表。 */
@Composable
internal fun WindFingeringPanel(
    state: InstrumentUiState,
    viewModel: InstrumentViewModel,
    modifier: Modifier = Modifier,
) {
    var showDetail by remember { mutableStateOf(false) }
    val colors = LocalLumenColors.current
    Box(
        modifier = modifier
            .background(colors.bgSurface, RoundedCornerShape(16.dp))
            .border(1.dp, colors.lineSubtle, RoundedCornerShape(16.dp))
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
    ) {
        WindFingeringTable(
            figure = state.windFigure,
            notes = state.notes,
            holeCount = state.holeCount,
            backHoleCount = state.backHoleCount,
            title = state.keyDisplay,
            previewFingeringId = state.mainPreviewFingeringId,
            tongyinDegree = state.tongyinDegree,
            transposable = state.supportsTongyin,
            naturalScaleOnly = true,
            onPreview = viewModel::previewMainFingering,
            onStepTongyin = viewModel::stepTongyin,
            onOpenDetail = if (state.supportsChromatic) ({ showDetail = true }) else null,
            modifier = Modifier.fillMaxSize(),
        )
    }
    if (showDetail) {
        WindChromaticDialog(
            state = state,
            onPreview = viewModel::previewDetailFingering,
            onStepTongyin = viewModel::stepDetailTongyin,
            onDismiss = { showDetail = false },
        )
    }
}

@Composable
private fun WindChromaticDialog(
    state: InstrumentUiState,
    onPreview: (Int) -> Unit,
    onStepTongyin: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalLumenColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        AuroraBackground(tuneCents = state.centsToTarget) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${state.windFigure.displayName}完整指法",
                            style = TunarTypography.readoutSolfege,
                            fontWeight = FontWeight.Bold,
                            color = colors.inkPrimary,
                        )
                        Text(
                            listOf(state.keyName, state.holeSystem).filter { it.isNotEmpty() }
                                .joinToString(" · "),
                            style = TunarTypography.caption,
                            color = colors.inkSecondary,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭十二音指法详情", tint = colors.inkPrimary)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 12.dp)
                        .background(colors.bgSurface, RoundedCornerShape(16.dp))
                        .border(1.dp, colors.lineSubtle, RoundedCornerShape(16.dp))
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                ) {
                    WindFingeringTable(
                        figure = state.windFigure,
                        notes = state.detailNotes,
                        holeCount = state.holeCount,
                        backHoleCount = state.backHoleCount,
                        title = "${state.detailKeyDisplay} · 十二孔位三音区",
                        previewFingeringId = state.detailPreviewFingeringId,
                        tongyinDegree = state.detailTongyinDegree,
                        transposable = state.supportsTongyin,
                        naturalScaleOnly = false,
                        onPreview = onPreview,
                        onStepTongyin = onStepTongyin,
                        onOpenDetail = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * 管乐锚定指法表：左侧整支乐器线稿，右侧三音区列，每一行对齐到该指法最高开孔的孔心。
 * 实时命中用填充、点选钉住只描边；可转调的型号上下拖动音区列逐档转调。
 */
@Composable
internal fun WindFingeringTable(
    figure: WindFigureKind,
    notes: List<ChartNoteUi>,
    holeCount: Int,
    backHoleCount: Int,
    title: String,
    previewFingeringId: Int?,
    tongyinDegree: Int,
    transposable: Boolean,
    naturalScaleOnly: Boolean,
    onPreview: (Int) -> Unit,
    onStepTongyin: (Int) -> Unit,
    onOpenDetail: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalLumenColors.current
    val ink = FigureInk.from(colors)
    val activeNote = notes.firstOrNull { it.active }
    val previewNote = previewFingeringId?.let { id -> notes.firstOrNull { it.fingeringId == id } }
    val fallback = notes
        .filter { it.register == WindRegister.LOW && normalizedDiagramHoles(it, holeCount).all { h -> h == HoleMark.CLOSED } }
        .minByOrNull { it.midi }
        ?: notes.firstOrNull { it.register == WindRegister.LOW }
        ?: notes.minByOrNull { it.midi }
    // 点选优先：点了哪条就画哪条；未点选（或再次点掉）时跟随实时识别。
    val displayedNote = previewNote ?: activeNote ?: fallback
    val rows = windChartRows(notes)
    val anchors = rows.map { it.anchorHole }.distinct()

    val thresholdPx = with(LocalDensity.current) { 28.dp.toPx() }
    val settleOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var pendingSteps by remember { mutableIntStateOf(0) }
    val dragState = rememberDraggableState { delta ->
        val progress = consumeTongyinDrag(dragOffset, delta, thresholdPx)
        dragOffset = progress.residualOffsetPx
        pendingSteps += progress.committedSteps
    }
    val displayedTitle = if (pendingSteps != 0 && title.contains(" · ")) {
        val tongyin = SOLFEGE_STEPS[steppedTongyinDegree(tongyinDegree, pendingSteps, naturalScaleOnly)]
        "筒音作$tongyin" + title.substring(title.indexOf(" · "))
    } else {
        title
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                displayedTitle,
                modifier = Modifier.weight(1f, fill = false),
                style = TunarTypography.label,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (transposable && naturalScaleOnly) {
                Text("拖动唱名转调", fontSize = 10.sp, color = ink.inkFaint, maxLines = 1)
            }
            if (onOpenDetail != null) {
                Row(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 36.dp)
                        .clickable(onClick = onOpenDetail)
                        .padding(horizontal = 8.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = "打开${figure.displayName}十二音完整指法"
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Filled.OpenInFull, contentDescription = null, tint = colors.accent, modifier = Modifier.width(14.dp))
                    Text("十二音", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.accent)
                }
            }
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val chartHeight = maxHeight
            val viewportWidth = (maxWidth - FIGURE_WIDTH - FIGURE_SPACING).coerceAtLeast(1.dp)
            val columnWidth = max(viewportWidth.value / 3f, COLUMN_MIN_WIDTH.value).dp
            val contentWidth = columnWidth * 3
            val registerScroll = rememberScrollState()
            val density = LocalDensity.current
            LaunchedEffect(activeNote?.register, registerScroll.maxValue) {
                val index = WindRegister.entries.indexOf(activeNote?.register ?: return@LaunchedEffect)
                if (registerScroll.maxValue <= 0) return@LaunchedEffect
                registerScroll.animateScrollTo(
                    registerScrollTargetPx(
                        columnIndex = index,
                        columnCount = WindRegister.entries.size,
                        contentWidthPx = with(density) { contentWidth.toPx() },
                        viewportWidthPx = with(density) { viewportWidth.toPx() },
                        maxScrollPx = registerScroll.maxValue.toFloat(),
                    ),
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val startX = FIGURE_WIDTH.toPx() * 0.68f
                anchors.forEach { anchor ->
                    val y = size.height * WindFigureGeometry.fraction(figure, anchor, holeCount)
                    drawLine(ink.lineFaint, Offset(startX, y), Offset(size.width - 4.dp.toPx(), y), 0.7.dp.toPx())
                }
            }

            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(FIGURE_SPACING)) {
                WindTubeFigure(
                    kind = figure,
                    holes = normalizedDiagramHoles(displayedNote, holeCount),
                    backHoleCount = backHoleCount,
                    highlighted = displayedNote?.active == true,
                    ink = ink,
                    modifier = Modifier.width(FIGURE_WIDTH).fillMaxHeight(),
                )
                val dragModifier = if (transposable) {
                    Modifier.draggable(
                        state = dragState,
                        orientation = Orientation.Vertical,
                        onDragStarted = {
                            scope.launch {
                                settleOffset.stop()
                                settleOffset.snapTo(0f)
                            }
                        },
                        onDragStopped = {
                            val committed = tongyinDragSteps(dragOffset, thresholdPx)
                            val total = pendingSteps + committed
                            pendingSteps = 0
                            if (total != 0) onStepTongyin(total)
                            val snapStart = dragOffset + committed * thresholdPx
                            scope.launch {
                                settleOffset.snapTo(snapStart)
                                dragOffset = 0f
                                settleOffset.animateTo(0f, spring(dampingRatio = 0.78f, stiffness = 650f))
                            }
                        },
                    )
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .width(viewportWidth)
                        .fillMaxHeight()
                        .horizontalScroll(registerScroll),
                ) {
                    Row(
                        modifier = Modifier
                            .width(contentWidth)
                            .fillMaxHeight()
                            .then(dragModifier)
                            .semantics {
                                if (transposable) {
                                    contentDescription = "唱名转轮，上下拖动转调"
                                    stateDescription = "当前筒音级 $tongyinDegree"
                                }
                            },
                    ) {
                        WindRegister.entries.forEach { register ->
                            RegisterColumn(
                                figure = figure,
                                register = register,
                                rows = rows,
                                holeCount = holeCount,
                                chartHeight = chartHeight,
                                activeRegister = activeNote?.register,
                                previewFingeringId = previewFingeringId,
                                solfegeShift = pendingSteps,
                                naturalScaleOnly = naturalScaleOnly,
                                transposable = transposable,
                                onPreview = onPreview,
                                onStepTongyin = onStepTongyin,
                                badgeOffset = { dragOffset + settleOffset.value },
                                ink = ink,
                                modifier = Modifier.width(columnWidth).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterColumn(
    figure: WindFigureKind,
    register: WindRegister,
    rows: List<WindChartRow>,
    holeCount: Int,
    chartHeight: androidx.compose.ui.unit.Dp,
    activeRegister: WindRegister?,
    previewFingeringId: Int?,
    solfegeShift: Int,
    naturalScaleOnly: Boolean,
    transposable: Boolean,
    onPreview: (Int) -> Unit,
    onStepTongyin: (Int) -> Unit,
    badgeOffset: () -> Float,
    ink: FigureInk,
    modifier: Modifier,
) {
    val isActive = activeRegister == register
    Box(modifier = modifier) {
        Column(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                figure.registerTitle(register),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) ink.accent else ink.ink.copy(alpha = 0.7f),
            )
            Text(figure.registerTechnique(register), fontSize = 8.sp, color = ink.inkFaint)
        }
        rows.groupBy { it.anchorHole }.forEach { (anchor, group) ->
            val fraction = WindFigureGeometry.fraction(figure, anchor, holeCount)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.dp)
                    .offset(y = chartHeight * fraction - CELL_HEIGHT / 2),
                horizontalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterHorizontally),
            ) {
                group.forEach { row ->
                    row.notesByRegister[register]?.let { note ->
                        RegisterCell(
                            note = note,
                            registerTitle = figure.registerTitle(register),
                            pinned = previewFingeringId == note.fingeringId && !note.active,
                            solfegeShift = solfegeShift,
                            naturalScaleOnly = naturalScaleOnly,
                            transposable = transposable,
                            onPreview = { onPreview(note.fingeringId) },
                            onStepTongyin = onStepTongyin,
                            badgeOffset = badgeOffset,
                            ink = ink,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterCell(
    note: ChartNoteUi,
    registerTitle: String,
    pinned: Boolean,
    solfegeShift: Int,
    naturalScaleOnly: Boolean,
    transposable: Boolean,
    onPreview: () -> Unit,
    onStepTongyin: (Int) -> Unit,
    badgeOffset: () -> Float,
    ink: FigureInk,
) {
    val marker = when {
        note.fingeringKind != FingeringKind.COMBINATION -> null
        note.holes.any { it == HoleMark.HALF } -> "半"
        else -> "叉"
    }
    val solfege = shiftedSolfege(note.solfege, if (transposable) solfegeShift else 0, naturalScaleOnly)
    Row(
        modifier = Modifier
            .height(CELL_HEIGHT)
            .background(if (note.active) ink.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent, CELL_SHAPE)
            .then(if (pinned) Modifier.border(1.dp, ink.accent, CELL_SHAPE) else Modifier)
            .clickable(onClick = onPreview)
            .padding(horizontal = 2.dp)
            .semantics {
                contentDescription = "$registerTitle，${note.noteName}，唱名$solfege"
                stateDescription = when {
                    note.active -> "实时命中"
                    pinned -> "已钉住"
                    else -> "未选中"
                }
                if (transposable) {
                    customActions = listOf(
                        CustomAccessibilityAction("转调升一档") { onStepTongyin(1); true },
                        CustomAccessibilityAction("转调降一档") { onStepTongyin(-1); true },
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            note.noteName.replace("#", "♯"),
            fontSize = 10.sp,
            lineHeight = 11.sp,
            fontWeight = if (note.active) FontWeight.Bold else FontWeight.SemiBold,
            color = if (note.inScale) ink.accent else ink.ink.copy(alpha = 0.65f),
            maxLines = 1,
        )
        marker?.let { Text(it, fontSize = 7.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold, color = ink.back) }
        Surface(
            shape = CELL_SHAPE,
            color = ink.surface,
            border = BorderStroke(0.8.dp, ink.accent.copy(alpha = 0.35f)),
            modifier = Modifier
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                .graphicsLayer { if (transposable) translationY = badgeOffset() },
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
                AnimatedContent(
                    targetState = solfege.replace("#", "♯"),
                    transitionSpec = {
                        (slideInVertically { it / 2 } + fadeIn()).togetherWith(slideOutVertically { -it / 2 } + fadeOut())
                    },
                    label = "wind-solfege",
                ) { value ->
                    Text(value, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.SemiBold, color = ink.accent)
                }
            }
        }
    }
}
