package com.hector.backontrack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 回顾页的图表，全部用 Canvas 手绘。
 *
 * 不引第三方图表库：这个项目一直是零依赖，而我们要的只有柱状和环形两种，
 * 自己画几十行就够，还省掉一个会随 Compose 版本失配的依赖。
 */

/** 一组够用的配色。优先跟随主题，不够了再用固定色补上 */
@Composable
fun chartColors(): List<Color> = listOf(
    MaterialTheme.colorScheme.primary,
    MaterialTheme.colorScheme.tertiary,
    Color(0xFFE0A93C),
    Color(0xFF7FA88C),
    Color(0xFFE5734F),
    MaterialTheme.colorScheme.secondary,
)

/**
 * 柱状图。
 *
 * 没有记录的那天也要留一根空柱 —— 缺天会让人看不出节奏，
 * 「连着三天没记」和「三天记了一条」在图上必须长得不一样。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun BarChart(
    values: List<Int>,
    /** 每根柱子底下的标签，只画得下几个就隔着画 */
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 150.dp,
) {
    if (values.isEmpty()) return
    val bar = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)

    Canvas(modifier.fillMaxWidth().height(height)) {
        val labelH = 18.dp.toPx()
        val plotH = size.height - labelH
        val gap = 3.dp.toPx()
        val w = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)

        values.forEachIndexed { i, v ->
            val h = if (v == 0) 2.dp.toPx() else plotH * v / max
            val x = i * (w + gap)
            drawRoundRect(
                color = if (v == 0) dim.copy(alpha = 0.18f) else bar,
                topLeft = Offset(x, plotH - h),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 3),
            )
        }

        // 标签太密就隔着画：宽度不够时字会叠在一起，反而全都读不出来
        val step = if (values.size > 12) (values.size / 6).coerceAtLeast(1) else 1
        labels.forEachIndexed { i, s ->
            if (i % step != 0) return@forEachIndexed
            val laid = measurer.measure(s, TextStyle(color = dim, fontSize = 10.sp))
            drawText(laid, topLeft = Offset(
                i * (w + gap) + w / 2 - laid.size.width / 2,
                plotH + 4.dp.toPx(),
            ))
        }
    }
}

/**
 * 环形图 + 图例。
 *
 * 用环而不是实心饼：中间那个洞正好放总数，一眼就知道「一共多少」，
 * 而实心饼把这个信息挤到了外面。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun DonutChart(
    slices: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    centerLabel: String? = null,
) {
    val data = slices.filter { it.second > 0 }
    if (data.isEmpty()) return
    val colors = chartColors()
    val total = data.sumOf { it.second }.coerceAtLeast(1)

    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 26.dp.toPx()
                val inset = stroke / 2
                var start = -90f
                data.forEachIndexed { i, (_, v) ->
                    val sweep = 360f * v / total
                    drawArc(
                        color = colors[i % colors.size],
                        startAngle = start,
                        // 留一丝缝，相邻两段颜色接近时也分得开
                        sweepAngle = (sweep - 1.5f).coerceAtLeast(0.5f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                    start += sweep
                }
            }
            centerLabel?.let {
                Text(it, style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.width(18.dp))
        Column {
            data.forEachIndexed { i, (name, v) ->
                Row(
                    Modifier.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape)
                        .background(colors[i % colors.size]))
                    Spacer(Modifier.width(8.dp))
                    Text("$name  $v",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * 打卡热力图：一个月排成日历格，记得越多颜色越深。
 *
 * 比柱状图更适合看「有没有断」—— 连续几天空着会在图上连成一片灰，
 * 一眼就看得出来，而柱状图里空柱容易被忽略。
 */
@Composable
fun Heatmap(
    year: Int, month: Int,          // month: 1-12
    counts: Map<Int, Int>,          // 几号 -> 条数
    modifier: Modifier = Modifier,
) {
    val cal = java.util.Calendar.getInstance().apply {
        clear(); set(year, month - 1, 1)
    }
    val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    // Calendar 里周日=1，这里要周一开头，所以往前挪一位
    val firstCol = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    val max = (counts.values.maxOrNull() ?: 0).coerceAtLeast(1)
    val base = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val hot = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = dim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Spacer(Modifier.height(4.dp))
        val cells = firstCol + daysInMonth
        val rows = (cells + 6) / 7
        repeat(rows) { r ->
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                repeat(7) { c ->
                    val day = r * 7 + c - firstCol + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp)) {
                        if (day in 1..daysInMonth) {
                            val n = counts[day] ?: 0
                            // 有记录的格子按浓度上色；没记录的留一层很淡的底，
                            // 这样「这天没记」和「这天不在本月」还能分得开
                            val t = if (n == 0) 0f else 0.35f + 0.65f * n / max
                            Box(
                                Modifier.fillMaxSize()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
                                    .background(if (n == 0) base else hot.copy(alpha = t)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("$day",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (n == 0) dim
                                            else MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 半圆仪表盘。
 *
 * 用它而不是再来一个数字：指针停在哪一段，好坏是**看**出来的，
 * 不需要先知道「70 分算高还是低」。
 */
@Composable
fun Gauge(
    score: Int,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val s = score.coerceIn(0, 100)
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    // 颜色本身就是一层信息：红 / 橙 / 绿，不用读数字也知道大概
    val color = when {
        s >= 70 -> Color(0xFF7FA88C)
        s >= 45 -> Color(0xFFE0A93C)
        else -> Color(0xFFE5734F)
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxWidth().height(200.dp)) {
                val stroke = 22.dp.toPx()
                val d = minOf(size.width - stroke, (size.height - stroke) * 2f)
                val left = (size.width - d) / 2
                drawArc(
                    color = track, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(left, stroke / 2), size = Size(d, d),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = color, startAngle = 180f, sweepAngle = 180f * s / 100f,
                    useCenter = false,
                    topLeft = Offset(left, stroke / 2), size = Size(d, d),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$s", style = MaterialTheme.typography.displaySmall, color = color)
                Text("健康指数", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        caption?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/**
 * 矩形树图：吃得越多，方块越大。
 *
 * 取代之前那个词云 —— 字号缩放在条目一多的时候就是一团乱，
 * 而面积是能直接比较的：一眼看出「炸鸡那块比沙拉大一倍」。
 *
 * 用的是经典的 squarify 算法：贪心地把条目凑成一行，凑到「再加一个会让
 * 长宽比变差」为止。朴素的切分法会退化成一条条细长条，那就白做了。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun Treemap(
    items: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 260.dp,
) {
    val data = items.filter { it.second > 0 }.sortedByDescending { it.second }
    if (data.isEmpty()) return
    val colors = chartColors()
    val measurer = rememberTextMeasurer()
    val onColor = MaterialTheme.colorScheme.onPrimary

    Canvas(modifier.fillMaxWidth().height(height)) {
        val tiles = squarify(
            data.map { it.second.toFloat() }, 0f, 0f, size.width, size.height)
        tiles.forEachIndexed { i, r ->
            val pad = 1.5f
            drawRoundRect(
                color = colors[i % colors.size],
                topLeft = Offset(r[0] + pad, r[1] + pad),
                size = Size((r[2] - pad * 2).coerceAtLeast(0f),
                            (r[3] - pad * 2).coerceAtLeast(0f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            )
            // 尽量把字放上去：先按格子大小挑字号，还塞不下就截断名字。
            // 直接跳过会留下一堆空白方块 —— 那等于什么都没告诉人
            val (name, n) = data[i]
            val availW = r[2] - 8
            val availH = r[3] - 6
            var laid: androidx.compose.ui.text.TextLayoutResult? = null
            for (fs in listOf(15.sp, 13.sp, 11.sp, 9.sp)) {
                var text = "$name\n$n"
                var m = measurer.measure(text, TextStyle(color = onColor, fontSize = fs))
                var cut = name.length
                while (m.size.width > availW && cut > 2) {
                    cut--
                    text = name.take(cut) + "…\n$n"
                    m = measurer.measure(text, TextStyle(color = onColor, fontSize = fs))
                }
                if (m.size.width <= availW && m.size.height <= availH) { laid = m; break }
            }
            laid?.let {
                drawText(it, topLeft = Offset(
                    r[0] + (r[2] - it.size.width) / 2,
                    r[1] + (r[3] - it.size.height) / 2,
                ))
            }
        }
    }
}

/** 返回每个条目的 [x, y, w, h] */
private fun squarify(
    values: List<Float>, x0: Float, y0: Float, w0: Float, h0: Float,
): List<FloatArray> {
    val out = ArrayList<FloatArray>(values.size)
    val total = values.sum().coerceAtLeast(0.0001f)
    val scale = (w0 * h0) / total
    val v = values.map { it * scale }

    var x = x0; var y = y0; var w = w0; var h = h0; var i = 0
    while (i < v.size) {
        val side = minOf(w, h).coerceAtLeast(0.0001f)
        var rowSum = 0f
        var best = Float.MAX_VALUE
        var mx = 0f
        var mn = Float.MAX_VALUE
        var j = i
        while (j < v.size) {
            val s = rowSum + v[j]
            val nmx = maxOf(mx, v[j])
            val nmn = minOf(mn, v[j])
            // 这一行的最差长宽比：再加一个还变差，就收手
            val worst = maxOf(side * side * nmx / (s * s), s * s / (side * side * nmn))
            if (j > i && worst > best) break
            rowSum = s; mx = nmx; mn = nmn; best = worst; j++
        }
        if (w >= h) {
            val rw = rowSum / h
            var cy = y
            for (k in i until j) {
                val rh = v[k] / rowSum * h
                out.add(floatArrayOf(x, cy, rw, rh))
                cy += rh
            }
            x += rw; w -= rw
        } else {
            val rh = rowSum / w
            var cx = x
            for (k in i until j) {
                val rw = v[k] / rowSum * w
                out.add(floatArrayOf(cx, y, rw, rh))
                cx += rw
            }
            y += rh; h -= rh
        }
        i = j
    }
    return out
}

/**
 * 排行榜：名次 + 名字 + 一条按比例的横条 + 次数。
 *
 * 比纯文字列表多一条横条，是因为「3 次和 1 次」在文字里要读数字才知道差多少，
 * 横条一眼就比出来了。
 */
@Composable
fun RankBars(items: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val data = items.filter { it.second > 0 }
    if (data.isEmpty()) return
    val max = data.maxOf { it.second }.coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth()) {
        data.forEachIndexed { i, (name, n) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", Modifier.width(22.dp),
                    style = MaterialTheme.typography.labelMedium, color = dim)
                Text(name, Modifier.weight(0.42f),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Box(Modifier.weight(0.48f).height(10.dp)) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth(n.toFloat() / max)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp))
                            .background(bar)
                    )
                }
                Text("$n", Modifier.width(28.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = dim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

/**
 * 任务日历：哪天完成了什么、哪天有什么到期。
 *
 * 比热力图多一层信息 —— 热力图只说「这天有几件」，日历能点开看**是哪几件**。
 * 绿点 = 那天完成的，橙点 = 那天到期还没做的。
 */
@Composable
fun TaskCalendar(
    year: Int, month: Int,
    doneByDay: Map<Int, List<String>>,
    dueByDay: Map<Int, List<String>>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cal = java.util.Calendar.getInstance().apply { clear(); set(year, month - 1, 1) }
    val daysInMonth = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    val firstCol = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7   // 周一开头
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val doneColor = MaterialTheme.colorScheme.primary
    val dueColor = Color(0xFFE0A93C)
    val selBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach {
                Text(it, Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = dim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Spacer(Modifier.height(4.dp))
        val rows = (firstCol + daysInMonth + 6) / 7
        repeat(rows) { r ->
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                repeat(7) { c ->
                    val day = r * 7 + c - firstCol + 1
                    Box(Modifier.weight(1f).aspectRatio(0.95f).padding(2.dp)) {
                        if (day in 1..daysInMonth) {
                            val nDone = doneByDay[day]?.size ?: 0
                            val nDue = dueByDay[day]?.size ?: 0
                            Column(
                                Modifier.fillMaxSize()
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                    .background(
                                        if (day == selected) selBg
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                            .copy(alpha = 0.08f)
                                    )
                                    .clickable { onSelect(day) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("$day", style = MaterialTheme.typography.labelMedium,
                                    color = if (nDone > 0 || nDue > 0)
                                        MaterialTheme.colorScheme.onSurface else dim)
                                Spacer(Modifier.height(2.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    repeat(minOf(nDone, 3)) {
                                        Box(Modifier.size(4.dp).clip(CircleShape)
                                            .background(doneColor))
                                    }
                                    repeat(minOf(nDue, 2)) {
                                        Box(Modifier.size(4.dp).clip(CircleShape)
                                            .background(dueColor))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(doneColor))
            Text("  当天完成", style = MaterialTheme.typography.labelSmall, color = dim)
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(6.dp).clip(CircleShape).background(dueColor))
            Text("  当天到期未做", style = MaterialTheme.typography.labelSmall, color = dim)
        }
    }
}
