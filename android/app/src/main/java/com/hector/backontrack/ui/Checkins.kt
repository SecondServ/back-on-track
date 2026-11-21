package com.hector.backontrack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Checkins
import com.hector.backontrack.net.Task
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val DONE_COLOR = Color(0xFF4CAF7A)
private val SKIP_COLOR = Color(0xFFE0A03C)

/** 某一天在打卡图上的样子 */
private enum class Cell { Done, Skipped, Missed, Today, Off, Outside }

/**
 * 这一天该不该打卡。按待办现在的循环规则算，「下一次」的日期当锚点：
 *   每天 / 每 N 天 —— 跟锚点相差 N 的倍数
 *   每周几         —— 星期几在规则里；没指定星期几就跟锚点同一天、每 N 周
 *   每月           —— 跟锚点同一号（这个月没那么多天就算最后一天）
 */
private fun scheduledOn(t: Task, day: LocalDate, anchor: LocalDate): Boolean {
    val n = t.recurInterval.coerceAtLeast(1)
    return when (t.recurKind) {
        "daily" -> Math.floorMod(ChronoUnit.DAYS.between(anchor, day), n.toLong()) == 0L
        "weekly" -> {
            val wd = t.recurWeekdays
            if (!wd.isNullOrEmpty()) day.dayOfWeek.value in wd
            else day.dayOfWeek == anchor.dayOfWeek &&
                Math.floorMod(ChronoUnit.WEEKS.between(anchor, day), n.toLong()) == 0L
        }
        "monthly" -> day.dayOfMonth == minOf(anchor.dayOfMonth, day.lengthOfMonth())
        else -> false
    }
}

private class Row(val title: String, val cells: List<Cell>, val done: Int, val due: Int)

/** 把一条循环待办的打卡记录摊到这个月的每一天上 */
private fun buildRow(c: Checkins, year: Int, month: Int, zone: ZoneId): Row {
    val first = LocalDate.of(year, month, 1)
    val today = LocalDate.now(zone)
    fun dayOf(iso: String?) = iso?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
    }
    val created = dayOf(c.task.createdAt) ?: first
    val anchor = dayOf(c.task.dueAt) ?: created
    // 一天打了好几次卡（比如先跳过又补做）以最后一次为准
    val byDay = HashMap<LocalDate, String>()
    c.records.sortedBy { it.first }.forEach { (ms, outcome) ->
        byDay[Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()] = outcome
    }
    var done = 0
    var due = 0
    val cells = (0 until first.lengthOfMonth()).map { i ->
        val d = first.plusDays(i.toLong())
        val rec = byDay[d]
        val scheduled = scheduledOn(c.task, d, anchor)
        when {
            rec == "done" -> { done++; due++; Cell.Done }
            rec == "skipped" -> { due++; Cell.Skipped }
            d.isBefore(created) || d.isAfter(today) -> Cell.Outside
            !scheduled -> Cell.Off
            d == today -> Cell.Today
            else -> { due++; Cell.Missed }
        }
    }
    return Row(c.task.title, cells, done, due)
}

/**
 * 回顾页「做了什么」里的循环待办打卡图。
 *
 * 每条循环待办一行，这个月每天一个格子：完成是实心绿，跳过是橙色，
 * 该打卡却没有任何记录（闹钟没人理）是空心框，今天还没到的是主题色框，
 * 不用打卡的日子只画一个小点。
 */
@Composable
fun CheckinSection(month: String, header: @Composable (String) -> Unit) {
    var data by remember(month) { mutableStateOf<List<Checkins>?>(null) }
    LaunchedEffect(month) { data = runCatching { Api.recurringCheckins(month) }.getOrNull() }
    val list = data ?: return
    if (list.isEmpty()) return

    val (y, m) = month.split("-").map { it.toInt() }
    val zone = ZoneId.systemDefault()
    val rows = list.map { buildRow(it, y, m, zone) }
        .filter { r -> r.cells.any { it != Cell.Outside } }
    if (rows.isEmpty()) return

    header("循环待办打卡")
    rows.forEach { r ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(r.title, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                if (r.due == 0) "这个月还没到打卡的日子"
                else "完成 ${r.done} / 应打卡 ${r.due} · ${r.done * 100 / r.due}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        CheckinStrip(r.cells)
        Spacer(Modifier.height(12.dp))
    }
    Legend()
}

@Composable
private fun CheckinStrip(cells: List<Cell>) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val today = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxWidth().height(32.dp)) {
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (cells.size - 1)) / cells.size
        val h = minOf(w * 1.6f, 18.dp.toPx())
        val r = CornerRadius(2.dp.toPx())
        cells.forEachIndexed { i, c ->
            val x = i * (w + gap)
            val tl = Offset(x, 0f)
            val sz = Size(w, h)
            when (c) {
                Cell.Done -> drawRoundRect(DONE_COLOR, tl, sz, r)
                Cell.Skipped -> drawRoundRect(SKIP_COLOR, tl, sz, r)
                Cell.Missed -> drawRoundRect(dim.copy(alpha = 0.55f), tl + Offset(0.5f, 0.5f),
                    Size(w - 1f, h - 1f), r, style = Stroke(1.dp.toPx()))
                Cell.Today -> drawRoundRect(today, tl + Offset(0.5f, 0.5f),
                    Size(w - 1f, h - 1f), r, style = Stroke(1.5.dp.toPx()))
                Cell.Off -> drawCircle(dim.copy(alpha = 0.3f), 1.5.dp.toPx(), Offset(x + w / 2, h / 2))
                Cell.Outside -> Unit
            }
        }
        // 底下标几个日期，跟心情折线一样：1、8、15、22、29 号
        for (d in listOf(1, 8, 15, 22, 29)) {
            if (d > cells.size) continue
            val laid = measurer.measure("$d", TextStyle(color = dim, fontSize = 9.sp))
            val cx = (d - 1) * (w + gap) + w / 2
            drawText(laid, topLeft = Offset(
                (cx - laid.size.width / 2).coerceIn(0f, size.width - laid.size.width), h + 2.dp.toPx()))
        }
    }
}

@Composable
private fun Legend() {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    @Composable
    fun item(label: String, draw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
        Canvas(Modifier.size(10.dp)) { draw() }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = dim)
        Spacer(Modifier.width(12.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        item("完成") { drawRoundRect(DONE_COLOR, cornerRadius = CornerRadius(2.dp.toPx())) }
        item("跳过") { drawRoundRect(SKIP_COLOR, cornerRadius = CornerRadius(2.dp.toPx())) }
        item("没打卡") {
            drawRoundRect(dim.copy(alpha = 0.55f), cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(1.dp.toPx()))
        }
        item("不用打卡") { drawCircle(dim.copy(alpha = 0.3f), 1.5.dp.toPx()) }
    }
}
