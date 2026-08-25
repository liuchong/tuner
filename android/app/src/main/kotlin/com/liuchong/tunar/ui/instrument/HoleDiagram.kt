package com.liuchong.tunar.ui.instrument

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import uniffi.tunar_core.HoleMark

/** 保证大图始终按当前孔制绘制；无预览/异常短数据时按全闭补齐。 */
internal fun normalizedDiagramHoles(note: ChartNoteUi?, holeCount: Int): List<HoleMark> {
    if (holeCount <= 0) return emptyList()
    return List(holeCount) { index -> note?.holes?.getOrNull(index) ?: HoleMark.CLOSED }
}

/**
 * 完整竖向洞箫主视觉：包含吹口、整段管身、出音口和孔位。
 * 实心为闭孔、空心为开孔、半填充为半孔；第一孔轻微侧偏，背孔保持同轴并仅以专色区分。
 */
@Composable
fun HoleDiagram(
    holes: List<HoleMark>,
    backHoleCount: Int,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    // 孔位始终用中性墨色，命中信号只改变管身描边，避免整支箫刷成主色。
    val ink = MaterialTheme.colorScheme.onSurface
    val tube = MaterialTheme.colorScheme.surfaceVariant
    val tubeEdge = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }
    val backHoleInk = MaterialTheme.colorScheme.tertiary
    val description = holes.mapIndexed { index, mark ->
        val name = if (index >= holes.size - backHoleCount) "背孔" else "第${index + 1}孔"
        val state = when (mark) {
            HoleMark.CLOSED -> "闭"
            HoleMark.OPEN -> "开"
            HoleMark.HALF -> "半开"
        }
        "$name$state"
    }.joinToString(
        separator = "，",
        prefix = "完整洞箫，吹口在上，出音口在下；孔位：",
    )

    Canvas(
        modifier = modifier
            .defaultMinSize(minWidth = 88.dp, minHeight = 220.dp)
            .semantics { contentDescription = description },
    ) {
        if (holes.isEmpty()) return@Canvas
        val radius = (size.width * 0.085f).coerceIn(5.dp.toPx(), 10.dp.toPx())
        val tubeWidth = (size.width * 0.30f).coerceAtLeast(radius * 2.8f)
        val tubeX = size.width * 0.46f
        val bodyTop = size.height * 0.08f
        val bodyBottom = size.height * 0.90f
        val bodyLeft = tubeX - tubeWidth / 2f

        // 完整竹制管身。
        drawRoundRect(
            color = tube,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(tubeWidth, bodyBottom - bodyTop),
            cornerRadius = CornerRadius(tubeWidth * 0.28f),
        )
        drawRoundRect(
            color = tubeEdge,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(tubeWidth, bodyBottom - bodyTop),
            cornerRadius = CornerRadius(tubeWidth * 0.28f),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // 顶端吹口：斜切边与中央 U 形缺口。
        drawLine(
            color = tubeEdge,
            start = Offset(bodyLeft, bodyTop + radius * 0.35f),
            end = Offset(bodyLeft + tubeWidth, bodyTop - radius * 0.25f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val mouthPath = Path().apply {
            moveTo(tubeX - radius * 0.75f, bodyTop - radius * 0.15f)
            quadraticTo(
                tubeX,
                bodyTop + radius * 1.1f,
                tubeX + radius * 0.75f,
                bodyTop - radius * 0.15f,
            )
        }
        drawPath(mouthPath, color = ink, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // 底端出音口：管身延伸后以椭圆开口结束。
        drawOval(
            color = tubeEdge,
            topLeft = Offset(bodyLeft, bodyBottom - radius * 0.35f),
            size = Size(tubeWidth, radius * 1.1f),
            style = Stroke(width = 2.dp.toPx()),
        )

        val ordered = holes.indices.reversed().toList()
        ordered.forEach { index ->
            val isBack = index >= holes.size - backHoleCount
            val center = Offset(
                x = if (index == 0 && !isBack) tubeX - tubeWidth * 0.07f else tubeX,
                y = size.height * xiaoHoleCenterFraction(index, holes.size),
            )
            val holeInk = if (isBack) backHoleInk else ink
            when (holes[index]) {
                HoleMark.CLOSED -> drawCircle(holeInk, radius, center)
                HoleMark.OPEN -> drawCircle(
                    color = holeInk,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx()),
                )
                HoleMark.HALF -> {
                    drawCircle(
                        color = holeInk,
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                    drawArc(
                        color = holeInk,
                        startAngle = 0f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                    )
                }
            }
        }
    }
}
