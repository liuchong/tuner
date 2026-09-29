package com.liuchong.tunar.ui.instrument

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.liuchong.tunar.ui.theme.LumenColors
import uniffi.tunar_core.HoleMark
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 乐器线稿配色（取自 Lumen 调色板）。 */
@Immutable
data class FigureInk(
    val line: Color,
    val lineFaint: Color,
    val ink: Color,
    val inkFaint: Color,
    val surface: Color,
    val accent: Color,
    val tuneIn: Color,
    val back: Color,
) {
    companion object {
        fun from(colors: LumenColors) = FigureInk(
            line = colors.inkSecondary.copy(alpha = colors.inkSecondary.alpha * 0.7f),
            lineFaint = colors.inkFaint.copy(alpha = colors.inkFaint.alpha * 0.55f),
            ink = colors.inkPrimary,
            inkFaint = colors.inkSecondary.copy(alpha = colors.inkSecondary.alpha * 0.65f),
            surface = colors.bgSurfaceEnd,
            accent = colors.accent,
            tuneIn = colors.tuneIn,
            back = colors.tuneNear,
        )
    }
}

/** 设计坐标 → 视图坐标的等比变换（居中放置）。 */
internal class FigureTransform(design: Size, rect: Rect) {
    val scale: Float = max(min(rect.width / design.width, rect.height / design.height), 0.01f)
    private val originX = rect.left + (rect.width - design.width * scale) / 2f
    private val originY = rect.top + (rect.height - design.height * scale) / 2f

    fun point(x: Float, y: Float) = Offset(originX + x * scale, originY + y * scale)
    fun point(p: Offset) = point(p.x, p.y)
    fun design(p: Offset) = Offset((p.x - originX) / scale, (p.y - originY) / scale)

    /** 用设计坐标构建路径，输出视图坐标。 */
    fun path(build: DesignPath.() -> Unit): Path = DesignPath(this).apply(build).path
}

internal class DesignPath(private val t: FigureTransform) {
    val path = Path()
    private fun p(x: Float, y: Float) = t.point(x, y)

    fun moveTo(x: Float, y: Float) = p(x, y).let { path.moveTo(it.x, it.y) }
    fun lineTo(x: Float, y: Float) = p(x, y).let { path.lineTo(it.x, it.y) }
    fun quadTo(cx: Float, cy: Float, x: Float, y: Float) {
        val c = p(cx, cy)
        val e = p(x, y)
        path.quadraticTo(c.x, c.y, e.x, e.y)
    }
    fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        val c1 = p(c1x, c1y)
        val c2 = p(c2x, c2y)
        val e = p(x, y)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
    }
    fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
        val tl = p(x, y)
        val rs = r * t.scale
        path.addRoundRect(
            RoundRect(tl.x, tl.y, tl.x + w * t.scale, tl.y + h * t.scale, CornerRadius(rs, rs)),
        )
    }
    fun oval(x: Float, y: Float, w: Float, h: Float) {
        val tl = p(x, y)
        path.addOval(Rect(tl.x, tl.y, tl.x + w * t.scale, tl.y + h * t.scale))
    }
    fun close() = path.close()
}

/** 弦的显示状态：已调准 > 实时命中 / 手动选中 > 常态。 */
internal enum class FigureStringState {
    IDLE, EMPHASIZED, IN_TUNE;

    fun color(ink: FigureInk): Color = when (this) {
        IDLE -> ink.inkFaint
        EMPHASIZED -> ink.accent
        IN_TUNE -> ink.tuneIn
    }

    val highlighted: Boolean get() = this != IDLE

    companion object {
        fun of(item: StringItemUi?, selected: Boolean): FigureStringState = when {
            item == null -> IDLE
            item.inTune && (item.active || selected) -> IN_TUNE
            item.active || selected -> EMPHASIZED
            else -> IDLE
        }
    }
}

// region 琴头

internal enum class HeadstockSide { LEFT, RIGHT }

internal data class HeadstockPeg(
    /** 弦号（1 = 最细弦）。 */
    val stringNumber: Int,
    /** 弦轴柱中心（设计坐标）。 */
    val post: Offset,
    /** 旋钮所在侧；音高按钮与旋钮同侧，按弦轴自上而下的顺序排列。 */
    val keySide: HeadstockSide,
)

/**
 * 琴头线稿几何（设计坐标 100 × 160，琴枕在下、琴头朝上，正面视角）。
 * 弦从琴枕出发绕到弦轴柱内侧，同侧越靠近琴枕的弦轴接越外侧的弦，保证琴弦不交叉。
 */
internal class HeadstockLayout(
    val pegs: List<HeadstockPeg>,
    /** 琴枕处各弦 x（自左向右，对应弦号 N…1）。 */
    private val nutXs: List<Float>,
    val neckHalfWidth: Float,
    val outline: DesignPath.() -> Unit,
) {
    val stringCount: Int get() = nutXs.size

    fun nutX(stringNumber: Int): Float = nutXs[stringCount - stringNumber]

    fun attachPoint(peg: HeadstockPeg): Offset {
        val inward = if (peg.post.x < 50f) 1f else -1f
        return Offset(peg.post.x + inward * POST_RADIUS, peg.post.y)
    }

    fun keyCenter(peg: HeadstockPeg) = Offset(
        if (peg.keySide == HeadstockSide.LEFT) LEFT_KEY_X else RIGHT_KEY_X,
        peg.post.y,
    )

    /** 旋钮朝外一侧的边缘中点：引线终点，贴在旋钮上。 */
    fun keyOuterEdge(peg: HeadstockPeg): Offset {
        val center = keyCenter(peg)
        val outward = if (peg.keySide == HeadstockSide.LEFT) -1f else 1f
        return Offset(center.x + outward * KEY_HALF_WIDTH, center.y)
    }

    /** 点中弦轴、旋钮或琴弦时返回弦号。 */
    fun hitString(p: Offset): Int? {
        var best: Pair<Int, Float>? = null
        fun consider(number: Int, distance: Float, limit: Float) {
            if (distance > limit) return
            if (best == null || distance < best!!.second) best = number to distance
        }
        for (peg in pegs) {
            consider(peg.stringNumber, (p - peg.post).getDistance(), 9f)
            consider(peg.stringNumber, (p - keyCenter(peg)).getDistance(), 9f)
            val nut = Offset(nutX(peg.stringNumber), NUT_Y)
            consider(peg.stringNumber, segmentDistance(p, nut, attachPoint(peg)), 3f)
            if (p.y > NUT_Y) consider(peg.stringNumber, abs(p.x - nut.x), 2f)
        }
        return best?.first
    }

    companion object {
        val DESIGN = Size(100f, 160f)
        const val NUT_Y = 125f
        const val POST_RADIUS = 2.6f
        const val BUSHING_RADIUS = 4.6f
        const val LEFT_KEY_X = 6.5f
        const val RIGHT_KEY_X = 93.5f
        const val KEY_HALF_WIDTH = 4f

        private fun segmentDistance(p: Offset, a: Offset, b: Offset): Float {
            val d = b - a
            val lengthSquared = d.x * d.x + d.y * d.y
            if (lengthSquared <= 0f) return (p - a).getDistance()
            val t = (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / lengthSquared).coerceIn(0f, 1f)
            return hypot(p.x - (a.x + t * d.x), p.y - (a.y + t * d.y))
        }

        private fun evenNut(count: Int, from: Float, to: Float) =
            List(count) { from + (to - from) * it / (count - 1) }

        /** Gibson 式 3+3：低音侧自琴枕向上接 6、5、4 弦，高音侧接 1、2、3 弦。 */
        val THREE_PLUS_THREE = HeadstockLayout(
            pegs = listOf(
                HeadstockPeg(6, Offset(30f, 96f), HeadstockSide.LEFT),
                HeadstockPeg(5, Offset(30f, 68f), HeadstockSide.LEFT),
                HeadstockPeg(4, Offset(30f, 40f), HeadstockSide.LEFT),
                HeadstockPeg(1, Offset(70f, 96f), HeadstockSide.RIGHT),
                HeadstockPeg(2, Offset(70f, 68f), HeadstockSide.RIGHT),
                HeadstockPeg(3, Offset(70f, 40f), HeadstockSide.RIGHT),
            ),
            nutXs = evenNut(6, 40.5f, 59.5f),
            neckHalfWidth = 12f,
            outline = {
                moveTo(37f, 125f)
                cubicTo(30f, 112f, 18f, 96f, 17f, 70f)
                lineTo(16f, 26f)
                quadTo(16f, 12f, 30f, 11f)
                quadTo(44f, 10f, 50f, 17f)
                quadTo(56f, 10f, 70f, 11f)
                quadTo(84f, 12f, 84f, 26f)
                lineTo(83f, 70f)
                cubicTo(82f, 96f, 70f, 112f, 63f, 125f)
                close()
            },
        )

        /** Fender 式 6-in-line：弦轴全部在低音侧一列，按钮同侧一列、与旋钮顺序一致。 */
        val INLINE_6 = HeadstockLayout(
            pegs = listOf(6, 5, 4, 3, 2, 1).mapIndexed { offset, number ->
                HeadstockPeg(
                    stringNumber = number,
                    post = Offset(30f, 112f - offset * 18f),
                    keySide = HeadstockSide.LEFT,
                )
            },
            nutXs = evenNut(6, 40.5f, 59.5f),
            neckHalfWidth = 12f,
            outline = {
                moveTo(37f, 125f)
                quadTo(22f, 121f, 21f, 108f)
                lineTo(21f, 22f)
                quadTo(21f, 8f, 36f, 8f)
                cubicTo(56f, 8f, 74f, 14f, 76f, 26f)
                cubicTo(78f, 38f, 62f, 46f, 58f, 58f)
                cubicTo(55f, 68f, 62f, 76f, 66f, 86f)
                cubicTo(70f, 96f, 66f, 112f, 63f, 125f)
                close()
            },
        )

        /** 尤克里里 2+2：下排左 4 弦、右 1 弦，上排左 3 弦、右 2 弦。 */
        val UKULELE = HeadstockLayout(
            pegs = listOf(
                HeadstockPeg(4, Offset(34f, 96f), HeadstockSide.LEFT),
                HeadstockPeg(3, Offset(34f, 58f), HeadstockSide.LEFT),
                HeadstockPeg(1, Offset(66f, 96f), HeadstockSide.RIGHT),
                HeadstockPeg(2, Offset(66f, 58f), HeadstockSide.RIGHT),
            ),
            nutXs = evenNut(4, 43f, 57f),
            neckHalfWidth = 10f,
            outline = {
                moveTo(39f, 125f)
                cubicTo(32f, 116f, 24f, 106f, 24f, 92f)
                lineTo(24f, 44f)
                quadTo(24f, 30f, 38f, 30f)
                quadTo(50f, 38f, 62f, 30f)
                quadTo(76f, 30f, 76f, 44f)
                lineTo(76f, 92f)
                cubicTo(76f, 106f, 68f, 116f, 61f, 125f)
                close()
            },
        )

        fun of(figure: StringFigureKind): HeadstockLayout? = when (figure) {
            is StringFigureKind.Headstock -> when (figure.style) {
                HeadstockStyle.INLINE_6 -> INLINE_6
                HeadstockStyle.THREE_PLUS_THREE -> THREE_PLUS_THREE
            }
            StringFigureKind.UkuleleHeadstock -> UKULELE
            StringFigureKind.Guqin, StringFigureKind.None -> null
        }
    }
}

/**
 * 同侧按钮的纵向中心：尽量贴近各自弦轴高度（[ideal]），相邻中心至少相隔 [spacing]。
 * 会相撞的按钮合并成簇并以簇内理想高度均值居中；整体限制在 [minY, maxY]，
 * 空间不足时均匀压缩间距。返回值与 [ideal] 顺序一致。
 */
internal fun spreadCenters(ideal: List<Float>, spacing: Float, minY: Float, maxY: Float): List<Float> {
    val n = ideal.size
    if (n == 0) return emptyList()
    val step = if (n > 1) min(spacing, (maxY - minY) / (n - 1)).coerceAtLeast(0f) else 0f
    val order = ideal.indices.sortedBy { ideal[it] }

    class Cluster(var count: Int, var sum: Float) {
        val top get() = sum / count - (count - 1) * step / 2f
    }
    val clusters = ArrayList<Cluster>(n)
    for (index in order) {
        clusters += Cluster(1, ideal[index])
        while (clusters.size >= 2) {
            val upper = clusters[clusters.size - 2]
            val lower = clusters.last()
            if (upper.top + upper.count * step <= lower.top) break
            upper.count += lower.count
            upper.sum += lower.sum
            clusters.removeAt(clusters.size - 1)
        }
    }

    val sorted = FloatArray(n)
    var k = 0
    for (cluster in clusters) {
        repeat(cluster.count) { sorted[k++] = cluster.top + it * step }
    }
    for (i in 0 until n) {
        val floor = if (i == 0) minY else sorted[i - 1] + step
        sorted[i] = max(sorted[i], floor)
    }
    for (i in n - 1 downTo 0) {
        val ceiling = if (i == n - 1) maxY else sorted[i + 1] - step
        sorted[i] = min(sorted[i], ceiling)
    }

    val result = FloatArray(n)
    order.forEachIndexed { rank, index -> result[index] = sorted[rank] }
    return result.toList()
}

/** 选中按钮最上层，其次是自动识别到的弦；重叠时不会被相邻按钮遮住。 */
internal fun buttonZIndex(selected: Boolean, active: Boolean): Float = when {
    selected -> 2f
    active -> 1f
    else -> 0f
}

/**
 * 琴头 + 音高按钮：按钮与旋钮同侧、顺序一致，虚线引到旋钮外缘并以圆点收尾；
 * 同侧按钮过多时自动压低按钮高度。点中弦轴、旋钮或琴弦即选中该弦。
 */
@Composable
internal fun HeadstockPanel(
    layout: HeadstockLayout,
    strings: List<StringItemUi>,
    selectedIndex: Int?,
    ink: FigureInk,
    buttonWidth: Dp,
    buttonHeight: Dp,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    button: @Composable (StringItemUi) -> Unit,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val gapPx = with(density) { 6.dp.toPx() }
        val pitchGapPx = with(density) { 4.dp.toPx() }
        val buttonW = with(density) { buttonWidth.toPx() }
        val perSide = HeadstockSide.entries.maxOf { side -> layout.pegs.count { it.keySide == side } }
        val buttonH = min(
            with(density) { buttonHeight.toPx() },
            (heightPx - (perSide - 1) * pitchGapPx) / max(perSide, 1),
        ).coerceAtLeast(with(density) { 30.dp.toPx() })
        val buttonHeightDp = with(density) { buttonH.toDp() }
        val columnWidth = buttonW + gapPx
        val hasLeft = layout.pegs.any { it.keySide == HeadstockSide.LEFT }
        val hasRight = layout.pegs.any { it.keySide == HeadstockSide.RIGHT }
        val figureLeft = if (hasLeft) columnWidth else 0f
        val figureRight = if (hasRight) widthPx - columnWidth else widthPx
        val t = FigureTransform(
            HeadstockLayout.DESIGN,
            Rect(figureLeft, 0f, max(figureRight, figureLeft + 1f), heightPx),
        )
        val leftX = max(buttonW / 2f, t.point(0f, 0f).x - gapPx - buttonW / 2f)
        val rightX = min(
            widthPx - buttonW / 2f,
            t.point(HeadstockLayout.DESIGN.width, 0f).x + gapPx + buttonW / 2f,
        )
        val buttonYs = HashMap<Int, Float>(layout.pegs.size)
        HeadstockSide.entries.forEach { side ->
            val pegs = layout.pegs.filter { it.keySide == side }
            val ys = spreadCenters(
                ideal = pegs.map { t.point(it.post).y },
                spacing = buttonH + pitchGapPx,
                minY = buttonH / 2f,
                maxY = max(heightPx - buttonH / 2f, buttonH / 2f),
            )
            pegs.forEachIndexed { i, peg -> buttonYs[peg.stringNumber] = ys[i] }
        }
        fun buttonY(peg: HeadstockPeg) = buttonYs.getValue(peg.stringNumber)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(layout, t.scale, widthPx) {
                    detectTapGestures { offset ->
                        layout.hitString(t.design(offset))?.let { currentOnSelect(it - 1) }
                    }
                }
                .semantics {
                    contentDescription = layout.pegs
                        .sortedBy { it.stringNumber }
                        .mapNotNull { peg ->
                            strings.getOrNull(peg.stringNumber - 1)?.let {
                                val side = if (peg.keySide == HeadstockSide.LEFT) "左侧" else "右侧"
                                "${peg.stringNumber}弦${it.noteName}在${side}弦轴"
                            }
                        }
                        .joinToString("，", prefix = "琴头弦轴对应，")
                },
        ) {
            layout.pegs.forEach { peg ->
                val item = strings.getOrNull(peg.stringNumber - 1) ?: return@forEach
                val state = FigureStringState.of(item, selectedIndex == peg.stringNumber - 1)
                val y = buttonY(peg)
                val left = peg.keySide == HeadstockSide.LEFT
                val start = Offset(if (left) leftX + buttonW / 2f else rightX - buttonW / 2f, y)
                val end = t.point(layout.keyOuterEdge(peg))
                val color = if (state.highlighted) state.color(ink) else ink.line.copy(alpha = 0.55f)
                val elbow = Offset(start.x + (end.x - start.x) * 0.35f, y)
                val leader = Path().apply {
                    moveTo(start.x, start.y)
                    lineTo(elbow.x, elbow.y)
                    lineTo(end.x, end.y)
                }
                drawPath(
                    leader,
                    color = color,
                    style = Stroke(
                        width = (if (state.highlighted) 1.5f else 1f).dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.5.dp.toPx())),
                    ),
                )
                drawCircle(color, radius = (if (state.highlighted) 2.6f else 2f).dp.toPx(), center = end)
            }
            drawHeadstock(layout, strings, selectedIndex, ink, t)
        }

        layout.pegs.forEach { peg ->
            val item = strings.getOrNull(peg.stringNumber - 1) ?: return@forEach
            val cx = if (peg.keySide == HeadstockSide.LEFT) leftX else rightX
            val y = buttonY(peg)
            Box(
                modifier = Modifier
                    .zIndex(buttonZIndex(selected = selectedIndex == peg.stringNumber - 1, active = item.active))
                    .offset { IntOffset((cx - buttonW / 2f).roundToInt(), (y - buttonH / 2f).roundToInt()) }
                    .size(buttonWidth, buttonHeightDp),
            ) { button(item) }
        }
    }
}

private fun DrawScope.drawHeadstock(
    layout: HeadstockLayout,
    strings: List<StringItemUi>,
    selectedIndex: Int?,
    ink: FigureInk,
    t: FigureTransform,
) {
    fun state(number: Int) =
        FigureStringState.of(strings.getOrNull(number - 1), selectedIndex == number - 1)
    val s = t.scale
    val nutY = HeadstockLayout.NUT_Y
    val neckLeft = 50f - layout.neckHalfWidth
    val neckRight = 50f + layout.neckHalfWidth
    val lineWidth = max(1.2f * density, s * 0.8f)

    val neck = t.path {
        moveTo(neckLeft, nutY)
        lineTo(neckRight, nutY)
        lineTo(neckRight, 160f)
        lineTo(neckLeft, 160f)
        close()
    }
    drawPath(neck, ink.surface)
    drawPath(neck, ink.line, style = Stroke(lineWidth))
    drawLine(ink.lineFaint, t.point(neckLeft, 148f), t.point(neckRight, 148f), max(density, s * 0.6f))

    layout.pegs.forEach { peg ->
        val state = state(peg.stringNumber)
        val key = layout.keyCenter(peg)
        drawLine(ink.line, t.point(peg.post), t.point(key), max(1.5f * density, s * 1.4f))
        val knob = t.path {
            roundRect(key.x - HeadstockLayout.KEY_HALF_WIDTH, key.y - 5f, HeadstockLayout.KEY_HALF_WIDTH * 2f, 10f, 2.6f)
        }
        drawPath(knob, if (state.highlighted) state.color(ink).copy(alpha = 0.22f) else ink.surface)
        drawPath(
            knob,
            if (state.highlighted) state.color(ink) else ink.line,
            style = Stroke(if (state.highlighted) max(1.6f * density, s * 1.1f) else lineWidth),
        )
    }

    val head = t.path(layout.outline)
    drawPath(head, ink.surface)
    drawPath(head, ink.line, style = Stroke(lineWidth * 1.2f, join = StrokeJoin.Round))
    drawPath(t.path { roundRect(neckLeft - 1f, nutY - 3f, neckRight - neckLeft + 2f, 3f, 1f) }, ink.line)

    layout.pegs
        .sortedBy { if (state(it.stringNumber).highlighted) 1 else 0 }
        .forEach { peg ->
            val state = state(peg.stringNumber)
            val x = layout.nutX(peg.stringNumber)
            val gauge = (0.7f + (peg.stringNumber - 1f) / max(layout.stringCount - 1, 1) * 0.9f) * density
            val string = t.path {
                moveTo(x, 160f)
                lineTo(x, nutY)
                val attach = layout.attachPoint(peg)
                lineTo(attach.x, attach.y)
            }
            if (state.highlighted) {
                drawPath(string, state.color(ink).copy(alpha = 0.22f), style = Stroke(gauge + 6f * density))
            }
            drawPath(
                string,
                if (state.highlighted) state.color(ink) else ink.inkFaint,
                style = Stroke(
                    width = if (state.highlighted) gauge + 1.4f * density else gauge,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

    layout.pegs.forEach { peg ->
        val state = state(peg.stringNumber)
        val center = t.point(peg.post)
        val r = HeadstockLayout.BUSHING_RADIUS * s
        drawCircle(ink.surface, r, center)
        drawCircle(if (state.highlighted) state.color(ink) else ink.line, r, center, style = Stroke(lineWidth))
        drawCircle(
            if (state.highlighted) state.color(ink) else ink.inkFaint,
            HeadstockLayout.POST_RADIUS * s,
            center,
        )
    }
}

// endregion

// region 古琴

/**
 * 古琴俯视线稿几何（设计坐标 300 × 80）：琴首（岳山）在右、琴尾（龙龈）在左，
 * 一弦在上（离演奏者最远），徽位沿近身一侧。
 */
internal object GuqinGeometry {
    val DESIGN = Size(300f, 80f)
    const val BRIDGE_X = 266f
    const val TAIL_X = 11f

    /** 十三徽相对有效弦长（自岳山量起）的位置。 */
    val HUI_FRACTIONS = listOf(
        1f / 8, 1f / 6, 1f / 5, 1f / 4, 1f / 3, 2f / 5, 1f / 2,
        3f / 5, 2f / 3, 3f / 4, 4f / 5, 5f / 6, 7f / 8,
    )

    fun stringY(index: Int, count: Int, x: Float): Float {
        val step = index.toFloat() / max(count - 1, 1)
        val atBridge = 20f + 36f * step
        val atTail = 28f + 24f * step
        val progress = ((x - TAIL_X) / (BRIDGE_X - TAIL_X)).coerceIn(0f, 1f)
        return atTail + (atBridge - atTail) * progress
    }

    fun huiX(fraction: Float) = BRIDGE_X - fraction * (BRIDGE_X - TAIL_X)

    fun huiY(x: Float) = if (x < 70f) 62f + 5f * (x - 4f) / 66f - 3.4f else 63.6f

    fun nearestString(p: Offset, count: Int): Int? {
        if (count <= 0 || p.x < 0f || p.x > DESIGN.width || p.y < 0f || p.y > DESIGN.height) return null
        return (0 until count).minByOrNull { abs(stringY(it, count, p.x) - p.y) }
    }

    /** 仲尼式：项、腰各有一处方折内收。 */
    val outline: DesignPath.() -> Unit = {
        moveTo(4f, 18f)
        lineTo(70f, 13f)
        lineTo(72f, 16f)
        lineTo(82f, 16f)
        lineTo(84f, 12.5f)
        lineTo(250f, 10f)
        quadTo(253f, 15f, 256f, 15f)
        lineTo(258f, 15f)
        quadTo(261f, 15f, 263f, 9f)
        lineTo(290f, 9f)
        quadTo(297f, 9f, 297f, 20f)
        lineTo(297f, 60f)
        quadTo(297f, 71f, 290f, 71f)
        lineTo(263f, 71f)
        quadTo(261f, 65f, 258f, 65f)
        lineTo(256f, 65f)
        quadTo(253f, 65f, 250f, 70f)
        lineTo(84f, 67.5f)
        lineTo(82f, 64f)
        lineTo(72f, 64f)
        lineTo(70f, 67f)
        lineTo(4f, 62f)
        quadTo(1f, 62f, 1f, 58f)
        lineTo(1f, 22f)
        quadTo(1f, 18f, 4f, 18f)
        close()
    }
}

/** 古琴线稿：当前弦加粗高亮；点按或沿琴面滑动即选中最近的弦。 */
@Composable
internal fun GuqinFigure(
    strings: List<StringItemUi>,
    selectedIndex: Int?,
    ink: FigureInk,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val count = strings.size
    val measurer = rememberTextMeasurer()
    val current = selectedIndex ?: strings.indexOfFirst { it.active }.takeIf { it >= 0 }
    val stateText = current?.let { strings.getOrNull(it) }?.let { "${it.index}弦 ${it.noteName}" } ?: "未选中"

    Canvas(
        modifier = modifier
            .pointerInput(count) {
                awaitEachGesture {
                    var last: Int? = null
                    fun pick(position: Offset) {
                        val t = FigureTransform(GuqinGeometry.DESIGN, Rect(Offset.Zero, size.toSize()))
                        val index = GuqinGeometry.nearestString(t.design(position), count) ?: return
                        if (index != last) {
                            last = index
                            currentOnSelect(index)
                        }
                    }
                    pick(awaitFirstDown().position)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        pick(change.position)
                        change.consume()
                    }
                }
            }
            .semantics {
                contentDescription = "古琴七弦"
                stateDescription = stateText
                customActions = listOf(
                    CustomAccessibilityAction("下一弦") {
                        val next = (current ?: -1) + 1
                        if (next in strings.indices) currentOnSelect(next)
                        true
                    },
                    CustomAccessibilityAction("上一弦") {
                        val prev = (current ?: 1) - 1
                        if (prev in strings.indices) currentOnSelect(prev)
                        true
                    },
                )
            },
    ) {
        val t = FigureTransform(GuqinGeometry.DESIGN, Rect(Offset.Zero, size))
        val s = t.scale
        val lineWidth = max(1.2f * density, s)
        val body = t.path(GuqinGeometry.outline)
        drawPath(body, ink.surface)
        drawPath(body, ink.line, style = Stroke(lineWidth * 1.2f, join = StrokeJoin.Round))

        val bridge = t.path { roundRect(GuqinGeometry.BRIDGE_X - 2.5f, 13f, 5f, 54f, 2f) }
        drawPath(bridge, ink.surface)
        drawPath(bridge, ink.line, style = Stroke(lineWidth))
        drawPath(
            t.path { roundRect(GuqinGeometry.TAIL_X - 3f, 23f, 3.5f, 34f, 1.5f) },
            ink.line,
            style = Stroke(lineWidth),
        )

        GuqinGeometry.HUI_FRACTIONS.forEachIndexed { index, fraction ->
            val x = GuqinGeometry.huiX(fraction)
            drawCircle(
                ink.inkFaint,
                (if (index == 6) 1.9f else 1.25f) * s,
                t.point(x, GuqinGeometry.huiY(x)),
            )
        }

        val states = strings.mapIndexed { index, item ->
            FigureStringState.of(item, selectedIndex == index)
        }
        states.indices.sortedBy { if (states[it].highlighted) 1 else 0 }.forEach { index ->
            val state = states[index]
            val gauge = (2.1f - index * 0.19f) * density
            val from = t.point(GuqinGeometry.TAIL_X, GuqinGeometry.stringY(index, count, GuqinGeometry.TAIL_X))
            val toY = GuqinGeometry.stringY(index, count, GuqinGeometry.BRIDGE_X)
            val to = t.point(GuqinGeometry.BRIDGE_X + 7f, toY)
            if (state.highlighted) {
                drawLine(state.color(ink).copy(alpha = 0.2f), from, to, gauge + 7f * density)
                drawLine(state.color(ink), from, to, gauge + 1.8f * density, cap = StrokeCap.Round)
            } else {
                drawLine(ink.ink.copy(alpha = 0.55f), from, to, gauge, cap = StrokeCap.Round)
            }
            val label = measurer.measure(
                "${index + 1}",
                TextStyle(
                    fontSize = max(8f, 7f * s / density).sp,
                    fontWeight = if (state.highlighted) FontWeight.Bold else FontWeight.Medium,
                    color = if (state.highlighted) state.color(ink) else ink.inkFaint,
                ),
            )
            val anchor = t.point(283f, toY)
            drawText(
                label,
                topLeft = Offset(anchor.x - label.size.width / 2f, anchor.y - label.size.height / 2f),
            )
        }
    }
}

private fun androidx.compose.ui.unit.IntSize.toSize() = Size(width.toFloat(), height.toFloat())

// endregion

// region 管乐

internal object WindFigureGeometry {
    private val DIZI_HOLES = listOf(0.78f, 0.69f, 0.60f, 0.46f, 0.37f, 0.28f)
    private val SHAKUHACHI_HOLES = listOf(0.78f, 0.68f, 0.57f, 0.47f, 0.36f)

    fun outletFraction(kind: WindFigureKind) = when (kind) {
        WindFigureKind.XIAO -> 0.93f
        WindFigureKind.DIZI -> 0.91f
        WindFigureKind.SHAKUHACHI -> 0.92f
    }

    /** 孔心的归一化纵坐标；`holeIndex == null` 表示筒音（出音口）。 */
    fun fraction(kind: WindFigureKind, holeIndex: Int?, holeCount: Int): Float {
        if (holeIndex == null || holeCount <= 0) return outletFraction(kind)
        val index = holeIndex.coerceIn(0, holeCount - 1)
        return when {
            kind == WindFigureKind.DIZI && holeCount == DIZI_HOLES.size -> DIZI_HOLES[index]
            kind == WindFigureKind.SHAKUHACHI && holeCount == SHAKUHACHI_HOLES.size ->
                SHAKUHACHI_HOLES[index]
            holeCount <= 1 -> 0.5f
            else -> 0.22f + (0.77f - 0.22f) * (holeCount - 1 - index).toFloat() / (holeCount - 1)
        }
    }
}

/**
 * 竖向管身线稿：洞箫（斜切吹口 + U 形山口）、竹笛（吹孔 + 膜孔 + 缠线）、尺八（歌口 + 竹节 + 竹根）。
 * 孔心与右侧指法行共用 [WindFigureGeometry]。实心闭孔、空心开孔、下半填充为半孔，背孔用专色。
 */
@Composable
internal fun WindTubeFigure(
    kind: WindFigureKind,
    holes: List<HoleMark>,
    backHoleCount: Int,
    highlighted: Boolean,
    ink: FigureInk,
    modifier: Modifier = Modifier,
) {
    val description = holes.mapIndexed { index, mark ->
        val name = if (index >= holes.size - backHoleCount) "背孔" else "第${index + 1}孔"
        name + when (mark) {
            HoleMark.CLOSED -> "闭"
            HoleMark.OPEN -> "开"
            HoleMark.HALF -> "半开"
        }
    }.joinToString("，", prefix = "${holes.size}孔${kind.displayName}，")

    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val radius = (size.width * 0.085f).coerceIn(5.dp.toPx(), 11.dp.toPx())
        val tubeWidth = when (kind) {
            WindFigureKind.DIZI -> max(size.width * 0.26f, radius * 2.6f)
            else -> max(size.width * 0.30f, radius * 2.8f)
        }
        val x = size.width * 0.46f
        val edge = if (highlighted) ink.accent else ink.line
        val edgeWidth = (if (highlighted) 2f else 1.5f).dp.toPx()
        when (kind) {
            WindFigureKind.XIAO -> drawXiao(x, tubeWidth, radius, edge, edgeWidth, ink)
            WindFigureKind.DIZI -> drawDizi(x, tubeWidth, radius, edge, edgeWidth, ink)
            WindFigureKind.SHAKUHACHI -> drawShakuhachi(x, tubeWidth, radius, edge, edgeWidth, ink)
        }
        holes.forEachIndexed { index, mark ->
            val isBack = index >= holes.size - backHoleCount
            val center = Offset(
                x = if (kind == WindFigureKind.XIAO && index == 0 && !isBack) x - tubeWidth * 0.07f else x,
                y = size.height * WindFigureGeometry.fraction(kind, index, holes.size),
            )
            drawFingerHole(mark, center, radius, if (isBack) ink.back else ink.ink, ink.surface)
        }
    }
}

private fun DrawScope.drawFingerHole(mark: HoleMark, center: Offset, radius: Float, color: Color, surface: Color) {
    val ring = 2.dp.toPx()
    when (mark) {
        HoleMark.CLOSED -> drawCircle(color, radius, center)
        HoleMark.OPEN -> {
            drawCircle(surface, radius, center)
            drawCircle(color, radius - ring / 2f, center, style = Stroke(ring))
        }
        HoleMark.HALF -> {
            drawCircle(surface, radius, center)
            clipRect(top = center.y, bottom = center.y + radius) { drawCircle(color, radius, center) }
            drawCircle(color, radius - ring / 2f, center, style = Stroke(ring))
        }
    }
}

private fun DrawScope.drawXiao(x: Float, w: Float, radius: Float, edge: Color, edgeWidth: Float, ink: FigureInk) {
    val top = size.height * 0.08f
    val bottom = size.height * 0.90f
    drawRoundRect(ink.surface, Offset(x - w / 2f, top), Size(w, bottom - top), CornerRadius(w * 0.28f))
    drawRoundRect(edge, Offset(x - w / 2f, top), Size(w, bottom - top), CornerRadius(w * 0.28f), style = Stroke(edgeWidth))
    drawLine(
        edge,
        Offset(x - w / 2f, top + radius * 0.35f),
        Offset(x + w / 2f, top - radius * 0.25f),
        2.dp.toPx(),
        cap = StrokeCap.Round,
    )
    val notch = Path().apply {
        moveTo(x - radius * 0.75f, top - radius * 0.15f)
        quadraticTo(x, top + radius * 1.1f, x + radius * 0.75f, top - radius * 0.15f)
    }
    drawPath(notch, ink.ink, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    drawOval(
        edge,
        Offset(x - w / 2f, bottom + radius * 0.2f - radius * 0.55f),
        Size(w, radius * 1.1f),
        style = Stroke(2.dp.toPx()),
    )
}

private fun DrawScope.drawDizi(x: Float, w: Float, radius: Float, edge: Color, edgeWidth: Float, ink: FigureInk) {
    val h = size.height
    val top = h * 0.025f
    val bottom = h * 0.955f
    drawRoundRect(ink.surface, Offset(x - w / 2f, top), Size(w, bottom - top), CornerRadius(w * 0.4f))
    drawRoundRect(edge, Offset(x - w / 2f, top), Size(w, bottom - top), CornerRadius(w * 0.4f), style = Stroke(edgeWidth))

    // 缠线：两端护箍与 3+3 指孔之间的一道，提示左右手分组。
    for (band in listOf(0.045f, 0.115f, 0.53f, 0.86f, 0.935f)) {
        for (offset in listOf(-1.6f, 1.6f)) {
            val y = h * band + offset.dp.toPx()
            drawLine(ink.lineFaint, Offset(x - w / 2f, y), Offset(x + w / 2f, y), 1.dp.toPx())
        }
    }

    val embouchureTopLeft = Offset(x - radius * 0.95f, h * 0.075f - radius * 0.7f)
    val embouchureSize = Size(radius * 1.9f, radius * 1.4f)
    drawOval(ink.surface, embouchureTopLeft, embouchureSize)
    drawOval(ink.ink, embouchureTopLeft, embouchureSize, style = Stroke(2.dp.toPx()))

    val membraneR = radius * 0.8f
    val membraneCenter = Offset(x, h * 0.165f)
    drawCircle(ink.back.copy(alpha = 0.28f), membraneR, membraneCenter)
    drawCircle(ink.back, membraneR, membraneCenter, style = Stroke(1.2.dp.toPx()))
    val crinkle = Path().apply {
        moveTo(x - membraneR * 0.55f, membraneCenter.y)
        quadraticTo(x, membraneCenter.y - membraneR * 0.6f, x + membraneR * 0.55f, membraneCenter.y)
    }
    drawPath(crinkle, ink.back, style = Stroke(0.8.dp.toPx()))

    val ventR = radius * 0.5f
    for (dx in listOf(-w * 0.22f, w * 0.22f)) {
        drawCircle(ink.line, ventR, Offset(x + dx, h * 0.885f), style = Stroke(1.2.dp.toPx()))
    }
}

private fun DrawScope.drawShakuhachi(x: Float, topW: Float, radius: Float, edge: Color, edgeWidth: Float, ink: FigureInk) {
    val h = size.height
    val bottomW = topW * 1.22f
    val top = h * 0.03f
    val rootStart = h * 0.86f
    val bottom = h * 0.955f
    val body = Path().apply {
        moveTo(x - topW / 2f, top)
        lineTo(x + topW / 2f, top)
        lineTo(x + bottomW / 2f, rootStart)
        quadraticTo(x + bottomW / 2f + topW * 0.22f, (rootStart + bottom) / 2f, x + bottomW / 2f - 1f, bottom)
        quadraticTo(x, bottom + radius * 0.5f, x - bottomW / 2f + 1f, bottom)
        quadraticTo(x - bottomW / 2f - topW * 0.22f, (rootStart + bottom) / 2f, x - bottomW / 2f, rootStart)
        close()
    }
    drawPath(body, ink.surface)
    drawPath(body, edge, style = Stroke(edgeWidth, join = StrokeJoin.Round))

    val utaguchi = Path().apply {
        moveTo(x - topW * 0.26f, top)
        lineTo(x - topW * 0.12f, top + radius * 1.1f)
        lineTo(x + topW * 0.12f, top + radius * 1.1f)
        lineTo(x + topW * 0.26f, top)
    }
    drawPath(utaguchi, ink.ink, style = Stroke(1.8.dp.toPx(), join = StrokeJoin.Round))

    for (node in listOf(0.13f, 0.255f, 0.415f, 0.625f, 0.84f)) {
        val y = h * node
        val halfWidth = (topW + (bottomW - topW) * (y - top) / (rootStart - top)) / 2f
        for (offset in listOf(-1.4f, 1.4f)) {
            val oy = y + offset.dp.toPx()
            val line = Path().apply {
                moveTo(x - halfWidth, oy)
                quadraticTo(x, oy + radius * 0.35f, x + halfWidth, oy)
            }
            drawPath(line, ink.lineFaint, style = Stroke(1.dp.toPx()))
        }
    }
    drawOval(
        edge,
        Offset(x - bottomW * 0.34f, bottom - radius * 0.45f),
        Size(bottomW * 0.68f, radius * 0.9f),
        style = Stroke(1.6.dp.toPx()),
    )
}

// endregion

// region 乐器图标

/** 乐器切换条的线稿图标（设计坐标 24 × 24）。 */
@Composable
internal fun InstrumentGlyph(instrumentId: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val t = FigureTransform(Size(24f, 24f), Rect(Offset.Zero, size))
        val stroke = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(t.path { glyphShape(instrumentId) }, color, style = stroke)
        glyphDots(instrumentId).forEach { (x, y, r) -> drawCircle(color, r * t.scale, t.point(x, y)) }
    }
}

private fun DesignPath.glyphShape(id: String) {
    when (id) {
        // 竖立正视：吉他细腰、下箱宽、长琴颈、3+3 弦轴；尤克里里圆胖、短琴颈、2+2 弦轴。
        "guitar" -> {
            moveTo(12f, 22.6f)
            cubicTo(15.4f, 22.6f, 17.2f, 21f, 17.2f, 18.6f)
            cubicTo(17.2f, 16.6f, 15f, 16.4f, 15f, 14.8f)
            cubicTo(15f, 13.6f, 15.9f, 13.6f, 15.9f, 12.3f)
            cubicTo(15.9f, 10.9f, 14.4f, 10.2f, 13f, 10.2f)
            lineTo(11f, 10.2f)
            cubicTo(9.6f, 10.2f, 8.1f, 10.9f, 8.1f, 12.3f)
            cubicTo(8.1f, 13.6f, 9f, 13.6f, 9f, 14.8f)
            cubicTo(9f, 16.4f, 6.8f, 16.6f, 6.8f, 18.6f)
            cubicTo(6.8f, 21f, 8.6f, 22.6f, 12f, 22.6f)
            close()
            moveTo(11f, 10.2f); lineTo(11f, 5.2f)
            moveTo(13f, 10.2f); lineTo(13f, 5.2f)
            roundRect(10.1f, 0.8f, 3.8f, 4.4f, 1f)
            oval(10.4f, 13.1f, 3.2f, 3.2f)
            moveTo(9.8f, 19.6f); lineTo(14.2f, 19.6f)
        }
        "ukulele" -> {
            moveTo(12f, 22.4f)
            cubicTo(15f, 22.4f, 16.4f, 21f, 16.4f, 19.2f)
            cubicTo(16.4f, 17.6f, 15.3f, 17.3f, 15.3f, 16.2f)
            cubicTo(15.3f, 15.2f, 15.9f, 15f, 15.9f, 14.1f)
            cubicTo(15.9f, 12.9f, 14.5f, 12.2f, 13f, 12.2f)
            lineTo(11f, 12.2f)
            cubicTo(9.5f, 12.2f, 8.1f, 12.9f, 8.1f, 14.1f)
            cubicTo(8.1f, 15f, 8.7f, 15.2f, 8.7f, 16.2f)
            cubicTo(8.7f, 17.3f, 7.6f, 17.6f, 7.6f, 19.2f)
            cubicTo(7.6f, 21f, 9f, 22.4f, 12f, 22.4f)
            close()
            moveTo(11f, 12.2f); lineTo(11f, 7.4f)
            moveTo(13f, 12.2f); lineTo(13f, 7.4f)
            roundRect(10.3f, 4.2f, 3.4f, 3.2f, 1f)
            oval(10.7f, 14.5f, 2.6f, 2.6f)
            moveTo(10.5f, 19.9f); lineTo(13.5f, 19.9f)
        }
        "guqin" -> {
            moveTo(2f, 10f)
            lineTo(19f, 8.5f)
            quadTo(22.5f, 8.5f, 22.5f, 12f)
            quadTo(22.5f, 15.5f, 19f, 15.5f)
            lineTo(2f, 14f)
            close()
            for (y in listOf(11f, 12.3f, 13.4f)) {
                moveTo(3.5f, y - 0.4f)
                lineTo(18.5f, y)
            }
        }
        "zhudi" -> {
            moveTo(3f, 19f); lineTo(19f, 3f)
            moveTo(5f, 21f); lineTo(21f, 5f)
            moveTo(3f, 19f); lineTo(5f, 21f)
            moveTo(19f, 3f); lineTo(21f, 5f)
        }
        "dongxiao" -> {
            roundRect(9.5f, 2.5f, 5f, 19.5f, 1.6f)
            moveTo(10.8f, 2.6f)
            quadTo(12f, 5f, 13.2f, 2.6f)
        }
        else -> {
            moveTo(10f, 2.5f)
            lineTo(14f, 2.5f)
            lineTo(15f, 18.5f)
            quadTo(16.2f, 22f, 12f, 22f)
            quadTo(7.8f, 22f, 9f, 18.5f)
            close()
            moveTo(9.6f, 8.5f); lineTo(14.4f, 8.5f)
            moveTo(9.3f, 14.5f); lineTo(14.7f, 14.5f)
        }
    }
}

private fun glyphDots(id: String): List<Triple<Float, Float, Float>> = when (id) {
    "guitar" -> listOf(1.7f, 3f, 4.3f).flatMap { y -> listOf(Triple(8.6f, y, 0.55f), Triple(15.4f, y, 0.55f)) }
    "ukulele" -> listOf(5.1f, 6.5f).flatMap { y -> listOf(Triple(8.9f, y, 0.6f), Triple(15.1f, y, 0.6f)) }
    "zhudi" -> listOf(Triple(16.6f, 7.4f, 0.9f)) +
        (0 until 3).map { Triple(12.6f - it * 1.9f, 11.4f + it * 1.9f, 0.75f) }
    "dongxiao" -> (0 until 4).map { Triple(12f, 8.5f + it * 3.2f, 0.8f) }
    "guqin", "shakuhachi" -> emptyList()
    else -> listOf(Triple(12f, 11.5f, 0.75f), Triple(12f, 17f, 0.75f))
}

// endregion
