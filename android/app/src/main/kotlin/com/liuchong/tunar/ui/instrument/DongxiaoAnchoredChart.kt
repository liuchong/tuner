package com.liuchong.tunar.ui.instrument

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uniffi.tunar_core.FingeringKind
import uniffi.tunar_core.HoleMark
import uniffi.tunar_core.WindRegister
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

internal data class DongxiaoRegisterColumn(
    val register: WindRegister,
    val title: String,
)

internal val DONGXIAO_REGISTER_COLUMNS = listOf(
    DongxiaoRegisterColumn(WindRegister.LOW, "低音/缓吹"),
    DongxiaoRegisterColumn(WindRegister.MIDDLE, "中音/超吹"),
    DongxiaoRegisterColumn(WindRegister.HIGH, "高音/急吹"),
)

private data class DongxiaoBaseRow(
    val baseSemitones: Int,
    val anchorHole: Int?,
    val notesByRegister: Map<WindRegister, ChartNoteUi>,
)

/** 与大图孔心共用的纵向坐标，保证标签锚点与孔心严格一致。 */
internal fun xiaoHoleCenterFraction(holeIndex: Int, holeCount: Int): Float {
    if (holeCount <= 1) return 0.5f
    val positionFromTop = holeCount - 1 - holeIndex.coerceIn(0, holeCount - 1)
    return XIAO_HOLES_TOP + (XIAO_HOLES_BOTTOM - XIAO_HOLES_TOP) *
        positionFromTop.toFloat() / (holeCount - 1)
}

internal const val XIAO_CLOSED_ANCHOR = 0.93f
private const val XIAO_HOLES_TOP = 0.22f
private const val XIAO_HOLES_BOTTOM = 0.77f
private val REGISTER_CELL_HEIGHT = 26.dp
private val DONGXIAO_SOLFEGE_STEPS =
    listOf("1", "#1", "2", "#2", "3", "4", "#4", "5", "#5", "6", "b7", "7")
private val DONGXIAO_NATURAL_SOLFEGE_STEPS = listOf("1", "2", "3", "4", "5", "6", "7")
private val DONGXIAO_NATURAL_DEGREES = listOf(0, 2, 4, 5, 7, 9, 11)

/** 松手时按最近的一档结算；拖动期间不产生任何换调。 */
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

internal fun shiftedDongxiaoSolfege(
    solfege: String,
    steps: Int,
    naturalScaleOnly: Boolean = false,
): String {
    val values = if (naturalScaleOnly) {
        DONGXIAO_NATURAL_SOLFEGE_STEPS
    } else {
        DONGXIAO_SOLFEGE_STEPS
    }
    val index = values.indexOf(solfege)
    if (index < 0) return solfege
    return values[Math.floorMod(index + steps, values.size)]
}

private fun steppedDongxiaoTongyinDegree(
    degree: Int,
    steps: Int,
    naturalScaleOnly: Boolean,
): Int {
    if (!naturalScaleOnly) return Math.floorMod(degree + steps, 12)
    val index = DONGXIAO_NATURAL_DEGREES.indexOf(degree)
    if (index < 0) return degree
    return DONGXIAO_NATURAL_DEGREES[
        Math.floorMod(index + steps, DONGXIAO_NATURAL_DEGREES.size)
    ]
}

/**
 * 一支固定的大洞箫与低音/中音/高音三列。只有右侧音区列可横向滚动；主表和详情表各自自然持有
 * 独立的滚动与预览状态。
 */
@Composable
internal fun DongxiaoAnchoredChart(
    notes: List<ChartNoteUi>,
    holeCount: Int,
    backHoleCount: Int,
    previewFingeringId: Int?,
    tongyinDegree: Int,
    naturalScaleOnly: Boolean,
    onPreview: (Int) -> Unit,
    onStepTongyin: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeNote = notes.firstOrNull { it.active }
    val previewNote = previewFingeringId
        ?.let { selected -> notes.firstOrNull { it.fingeringId == selected } }
    val fallback = notes
        .filter { note ->
            note.register == WindRegister.LOW &&
                normalizedDiagramHoles(note, holeCount).all { it == HoleMark.CLOSED }
        }
        .minByOrNull { it.midi }
        ?: notes.firstOrNull { it.register == WindRegister.LOW }
        ?: notes.minByOrNull { it.midi }
    // 点选优先：点了哪条就画哪条；未点选（或再次点掉）时跟随实时识别。
    val displayedNote = previewNote ?: activeNote ?: fallback

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(6.dp)) {
            val diagramWidth = if (maxWidth < 420.dp) 108.dp else 132.dp
            val registerViewportWidth = (maxWidth - diagramWidth).coerceAtLeast(1.dp)
            val registerContentWidth = max(
                registerViewportWidth.value,
                DONGXIAO_REGISTER_COLUMNS.size * 92f,
            ).dp
            val guideColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
            val registerScroll = rememberScrollState()
            val density = LocalDensity.current
            // 实时命中的音区列自动向视口中心靠拢；手动滚动后除非换列不再打扰。
            LaunchedEffect(activeNote?.register, registerScroll.maxValue, registerContentWidth) {
                val columnIndex = DONGXIAO_REGISTER_COLUMNS
                    .indexOfFirst { it.register == activeNote?.register }
                if (columnIndex < 0 || registerScroll.maxValue <= 0) return@LaunchedEffect
                registerScroll.animateScrollTo(
                    registerScrollTargetPx(
                        columnIndex = columnIndex,
                        columnCount = DONGXIAO_REGISTER_COLUMNS.size,
                        contentWidthPx = with(density) { registerContentWidth.toPx() },
                        viewportWidthPx = with(density) { registerViewportWidth.toPx() },
                        maxScrollPx = registerScroll.maxValue.toFloat(),
                    ),
                )
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val startX = diagramWidth.toPx() * 0.46f
                repeat(holeCount) { index ->
                    val y = size.height * xiaoHoleCenterFraction(index, holeCount)
                    drawLine(
                        color = guideColor,
                        start = Offset(startX, y),
                        end = Offset(size.width - 4.dp.toPx(), y),
                        strokeWidth = 0.7.dp.toPx(),
                    )
                }
                val outletY = size.height * XIAO_CLOSED_ANCHOR
                drawLine(
                    color = guideColor,
                    start = Offset(startX, outletY),
                    end = Offset(size.width - 4.dp.toPx(), outletY),
                    strokeWidth = 0.7.dp.toPx(),
                )
            }

            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.width(diagramWidth).fillMaxHeight()) {
                    HoleDiagram(
                        holes = normalizedDiagramHoles(displayedNote, holeCount),
                        backHoleCount = backHoleCount,
                        highlighted = activeNote != null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        text = displayedNote?.noteName?.replace("#", "♯") ?: "筒音",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    modifier = Modifier
                        .width(registerViewportWidth)
                        .fillMaxHeight()
                        .horizontalScroll(registerScroll),
                ) {
                    DongxiaoRegisterColumns(
                        notes = notes,
                        holeCount = holeCount,
                        previewFingeringId = previewFingeringId,
                        tongyinDegree = tongyinDegree,
                        naturalScaleOnly = naturalScaleOnly,
                        onPreview = onPreview,
                        onStepTongyin = onStepTongyin,
                        modifier = Modifier.width(registerContentWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DongxiaoRegisterColumns(
    notes: List<ChartNoteUi>,
    holeCount: Int,
    previewFingeringId: Int?,
    tongyinDegree: Int,
    naturalScaleOnly: Boolean,
    onPreview: (Int) -> Unit,
    onStepTongyin: (Int) -> Unit,
    modifier: Modifier,
) {
    val rows = notes
        .groupBy { it.baseSemitones }
        .toSortedMap()
        .map { (baseSemitones, entries) ->
            DongxiaoBaseRow(
                baseSemitones = baseSemitones,
                anchorHole = entries.first().anchorHole,
                notesByRegister = entries.associateBy { it.register },
            )
        }
    val anchoredRows = rows.groupBy { it.anchorHole }
    val thresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    val settleOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var pendingSteps by remember { mutableIntStateOf(0) }
    val dragState = rememberDraggableState { delta ->
        val progress = consumeTongyinDrag(dragOffset, delta, thresholdPx)
        dragOffset = progress.residualOffsetPx
        if (progress.committedSteps != 0) {
            pendingSteps += progress.committedSteps
        }
    }
    val badgeMotionModifier = Modifier.graphicsLayer {
        translationY = dragOffset + settleOffset.value
    }
    val wheelDragModifier = Modifier
        .draggable(
            state = dragState,
            orientation = Orientation.Vertical,
            onDragStarted = {
                scope.launch {
                    settleOffset.stop()
                    settleOffset.snapTo(0f)
                }
            },
            onDragStopped = {
                val committedSteps = tongyinDragSteps(dragOffset, thresholdPx)
                val totalSteps = pendingSteps + committedSteps
                pendingSteps = 0
                if (totalSteps != 0) onStepTongyin(totalSteps)
                val snapStart = dragOffset + committedSteps * thresholdPx
                scope.launch {
                    settleOffset.snapTo(snapStart)
                    dragOffset = 0f
                    settleOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 650f),
                    )
                }
            },
        )

    Row(
        modifier = modifier
            .then(wheelDragModifier)
            .semantics {
                contentDescription = "唱名连续转轮"
                stateDescription = "当前筒音级 $tongyinDegree"
            },
    ) {
        DONGXIAO_REGISTER_COLUMNS.forEach { column ->
            RegisterColumn(
                column = column,
                anchoredRows = anchoredRows,
                holeCount = holeCount,
                previewFingeringId = previewFingeringId,
                tongyinDegree = steppedDongxiaoTongyinDegree(
                    tongyinDegree,
                    pendingSteps,
                    naturalScaleOnly,
                ),
                solfegeShift = pendingSteps,
                naturalScaleOnly = naturalScaleOnly,
                onPreview = onPreview,
                onStepTongyin = onStepTongyin,
                badgeMotionModifier = badgeMotionModifier,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun RegisterColumn(
    column: DongxiaoRegisterColumn,
    anchoredRows: Map<Int?, List<DongxiaoBaseRow>>,
    holeCount: Int,
    previewFingeringId: Int?,
    tongyinDegree: Int,
    solfegeShift: Int,
    naturalScaleOnly: Boolean,
    onPreview: (Int) -> Unit,
    onStepTongyin: (Int) -> Unit,
    badgeMotionModifier: Modifier,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier = modifier.padding(horizontal = 2.dp)) {
        Text(
            text = column.title,
            modifier = Modifier.align(Alignment.TopCenter),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )

        anchoredRows.forEach { (anchorHole, rows) ->
            val anchorFraction = anchorHole
                ?.let { xiaoHoleCenterFraction(it, holeCount) }
                ?: XIAO_CLOSED_ANCHOR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = maxHeight * anchorFraction - REGISTER_CELL_HEIGHT / 2),
            ) {
                rows.forEach { row ->
                    val note = row.notesByRegister[column.register]
                    if (note == null) {
                        Spacer(modifier = Modifier.weight(1f).height(REGISTER_CELL_HEIGHT))
                    } else {
                        RegisterFingeringCell(
                            note = note,
                            registerTitle = column.title,
                            selected = previewFingeringId == note.fingeringId,
                            tongyinDegree = tongyinDegree,
                            solfegeShift = solfegeShift,
                            naturalScaleOnly = naturalScaleOnly,
                            onPreview = { onPreview(note.fingeringId) },
                            onStepTongyin = onStepTongyin,
                            badgeMotionModifier = badgeMotionModifier,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RegisterFingeringCell(
    note: ChartNoteUi,
    registerTitle: String,
    selected: Boolean,
    tongyinDegree: Int,
    solfegeShift: Int,
    naturalScaleOnly: Boolean,
    onPreview: () -> Unit,
    onStepTongyin: (Int) -> Unit,
    badgeMotionModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    // 实时命中实心填充，点选钉住只描边，两种高亮不再互相混淆。
    val containerColor = if (note.active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    }
    val pinnedBorder = if (selected && !note.active) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
    } else {
        null
    }
    val marker = when {
        note.holes.any { it == HoleMark.HALF } -> "半"
        note.fingeringKind == FingeringKind.COMBINATION -> "叉"
        else -> null
    }
    val displayedSolfege =
        shiftedDongxiaoSolfege(note.solfege, solfegeShift, naturalScaleOnly)

    Row(
        modifier = modifier.height(REGISTER_CELL_HEIGHT).padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
            color = containerColor,
            border = pinnedBorder,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .semantics {
                    contentDescription =
                        "$registerTitle，音名${note.noteName}，指法ID ${note.fingeringId}"
                    stateDescription = when {
                        note.active -> "实时命中"
                        selected -> "已钉住"
                        else -> "未选中"
                    }
                }
                .clickable(onClick = onPreview),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = note.noteName.replace("#", "♯"),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    fontWeight = when {
                        note.active -> FontWeight.Bold
                        selected -> FontWeight.SemiBold
                        else -> FontWeight.Normal
                    },
                )
                marker?.let {
                    Text(
                        text = it,
                        fontSize = 7.sp,
                        lineHeight = 8.sp,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp),
            color = if (note.active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            border = pinnedBorder,
            modifier = Modifier
                .width(30.dp)
                .fillMaxHeight()
                .then(badgeMotionModifier)
                .semantics {
                    contentDescription =
                        "唱名徽章，$registerTitle，$displayedSolfege，指法ID ${note.fingeringId}"
                    stateDescription = "当前筒音级 $tongyinDegree"
                    customActions = listOf(
                        CustomAccessibilityAction("唱名升高半音") {
                            onStepTongyin(1)
                            true
                        },
                        CustomAccessibilityAction("唱名降低半音") {
                            onStepTongyin(-1)
                            true
                        },
                    )
                }
                .clickable(onClick = onPreview),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = displayedSolfege,
                    transitionSpec = {
                        (slideInVertically { it / 2 } + fadeIn())
                            .togetherWith(slideOutVertically { -it / 2 } + fadeOut())
                    },
                    label = "dongxiao-solfege",
                ) { solfege ->
                    Text(
                        text = solfege,
                        maxLines = 1,
                        fontSize = 10.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            note.active -> MaterialTheme.colorScheme.onPrimary
                            selected -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        },
                    )
                }
            }
        }
    }
}
