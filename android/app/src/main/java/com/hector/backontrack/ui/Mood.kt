package com.hector.backontrack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.hector.backontrack.net.MoodPoint
import java.util.Calendar

/**
 * 日记心情，五档。数字越大越开心，中间是平和。
 * 不用 emoji（他不喜欢），靠文字 + 一条从暖到冷的颜色来分。
 */
val MOOD_LABELS = mapOf(5 to "开心", 4 to "不错", 3 to "平和", 2 to "低落", 1 to "难过")

fun moodColor(m: Int): Color = when (m) {
    5 -> Color(0xFFE0A93C)
    4 -> Color(0xFF7FA88C)
    3 -> Color(0xFF9AA0A6)
    2 -> Color(0xFF7B9CC4)
    else -> Color(0xFF5A6FA8)
}

/**
 * 日记顶部的心情条：五张脸排一行，从笑到哭，当前那档实心，点哪个就改成哪个。
 * mood 为 null（还没判断出来）时五张都是空心的。
 *
 * 脸是 Canvas 画的，不是 emoji（他不喜欢 emoji）：嘴角的弯度从上扬到下撇，
 * 最难过那档多一滴眼泪。
 */
@Composable
fun MoodPicker(mood: Int?, manual: Boolean, onPick: (Int) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("心情", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    mood == null -> "还没判断，点一个"
                    manual -> "你改过"
                    else -> "AI 推断的，不对就点一下改"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(5, 4, 3, 2, 1).forEach { m ->
                MoodFace(
                    m, selected = m == mood,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable { onPick(m) }
                        .semantics { contentDescription = MOOD_LABELS.getValue(m) },
                )
            }
        }
    }
}

/** 一张脸。选中时整张脸填上该档的颜色，没选中只描边、颜色淡一些 */
@Composable
fun MoodFace(m: Int, selected: Boolean, modifier: Modifier = Modifier) {
    val c = moodColor(m)
    val ink = if (selected) Color(0xFF15181D) else c.copy(alpha = 0.75f)
    Canvas(modifier) {
        val w = size.minDimension
        val center = Offset(size.width / 2, size.height / 2)
        val r = w / 2 - 2.dp.toPx()
        val stroke = 2.dp.toPx()
        if (selected) drawCircle(c, r, center)
        else drawCircle(c.copy(alpha = 0.6f), r, center, style = Stroke(stroke))

        // 眼睛
        val eyeY = center.y - r * 0.22f
        val eyeDx = r * 0.36f
        val eyeR = r * 0.11f
        drawCircle(ink, eyeR, Offset(center.x - eyeDx, eyeY))
        drawCircle(ink, eyeR, Offset(center.x + eyeDx, eyeY))

        // 嘴：两端固定，中间控制点上下移动决定是笑还是哭
        val mouthY = center.y + r * 0.32f
        val half = r * 0.42f
        val bend = when (m) { 5 -> 0.42f; 4 -> 0.2f; 3 -> 0f; 2 -> -0.2f; else -> -0.38f } * r
        val mouth = Path().apply {
            moveTo(center.x - half, mouthY - bend / 3)
            quadraticTo(center.x, mouthY + bend, center.x + half, mouthY - bend / 3)
        }
        drawPath(mouth, ink, style = Stroke(stroke, cap = StrokeCap.Round))

        // 最难过那档：左眼下面一滴泪
        if (m == 1) {
            val tx = center.x - eyeDx
            val ty = eyeY + eyeR * 2.2f
            val tear = Path().apply {
                moveTo(tx, ty)
                quadraticTo(tx + eyeR * 1.3f, ty + eyeR * 2.4f, tx, ty + eyeR * 2.6f)
                quadraticTo(tx - eyeR * 1.3f, ty + eyeR * 2.4f, tx, ty)
            }
            drawPath(tear, if (selected) Color(0xFFDDE7F5) else c.copy(alpha = 0.75f))
        }
    }
}

/**
 * 心情折线：横轴是这个月的日子，纵轴五档。
 * 每篇日记一个点，按写的时刻摆放 —— 一天写好几篇就会在那天里上下起伏。
 * 点的颜色跟档位走，一眼看出哪几天是暖色、哪几天发冷。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun MoodLineChart(
    points: List<MoodPoint>,
    year: Int,
    month: Int,
    modifier: Modifier = Modifier,
) {
    val pts = points.filter { it.mood != null }
    if (pts.isEmpty()) return
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()

    val cal = Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
    val start = cal.timeInMillis
    val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    cal.add(Calendar.MONTH, 1)
    val end = cal.timeInMillis

    Canvas(modifier.fillMaxWidth().height(190.dp)) {
        val left = 34.dp.toPx()
        val right = 8.dp.toPx()
        val top = 10.dp.toPx()
        val bottomLabel = 20.dp.toPx()
        val plotW = size.width - left - right
        val plotH = size.height - top - bottomLabel
        fun yOf(m: Int) = top + plotH * (5 - m) / 4f
        fun xOf(t: Long) = left + plotW * ((t - start).toFloat() / (end - start))

        // 五条横线：开心 / 平和 / 难过 标字，另外两档只画虚线
        for (m in 1..5) {
            val y = yOf(m)
            drawLine(
                dim.copy(alpha = if (m == 3) 0.35f else 0.15f),
                Offset(left, y), Offset(size.width - right, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = if (m == 3) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            if (m == 5 || m == 3 || m == 1) {
                val laid = measurer.measure(MOOD_LABELS.getValue(m),
                    TextStyle(color = moodColor(m), fontSize = 10.sp))
                drawText(laid, topLeft = Offset(0f, y - laid.size.height / 2))
            }
        }

        // 折线
        val path = Path()
        pts.forEachIndexed { i, p ->
            val o = Offset(xOf(p.atMs), yOf(p.mood!!))
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, line.copy(alpha = 0.7f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        pts.forEach { p ->
            val o = Offset(xOf(p.atMs), yOf(p.mood!!))
            drawCircle(moodColor(p.mood), radius = 4.5.dp.toPx(), center = o)
        }

        // 底下的日期：1、8、15、22、29 号
        for (d in listOf(1, 8, 15, 22, 29)) {
            if (d > days) continue
            val t = start + (d - 1) * 86400000L
            val laid = measurer.measure("${d}日", TextStyle(color = dim, fontSize = 10.sp))
            drawText(laid, topLeft = Offset(
                (xOf(t) - laid.size.width / 2).coerceIn(left, size.width - laid.size.width.toFloat()),
                top + plotH + 5.dp.toPx(),
            ))
        }
    }
}
