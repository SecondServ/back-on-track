package com.hector.backontrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.hector.backontrack.AppPrefs
import com.hector.backontrack.net.Api
import com.hector.backontrack.widget.TasksWidget
import com.hector.backontrack.widget.WidgetCache
import com.hector.backontrack.net.Task
import com.hector.backontrack.reminder.ReminderScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ── 时间工具 ──────────────────────────────────────────────────────────────

internal fun parseIsoMs(iso: String): Long? = try {
    val cleaned = iso.replace("Z", "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
    val pattern = if (cleaned.contains('.'))
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSZ" else "yyyy-MM-dd'T'HH:mm:ssZ"
    SimpleDateFormat(pattern, Locale.US).parse(cleaned)!!.time
} catch (_: Exception) { null }

/**
 * 按**本地**日期算第几天。
 * 之前直接用 ms / 86400000 算的是 UTC 日界 —— 在太平洋时间，
 * 下午五点以后的「今天 18:00」会被显示成「明天 18:00」。
 */
private fun localDayIndex(ms: Long): Long {
    val tz = TimeZone.getDefault()
    return Math.floorDiv(ms + tz.getOffset(ms), 86_400_000L)
}

private fun daysFromToday(ms: Long) = localDayIndex(ms) - localDayIndex(System.currentTimeMillis())

/** 把 ISO 时间戳格式化成「今天 18:00」这类人话 */
fun formatDue(iso: String): String {
    val ms = parseIsoMs(iso) ?: return iso.take(16)
    val date = Date(ms)
    val days = daysFromToday(ms)
    val hm = SimpleDateFormat("HH:mm", Locale.US).format(date)
    return when {
        days < 0L -> "已过期 · ${SimpleDateFormat("M月d日", Locale.CHINA).format(date)}"
        days == 0L -> "今天 $hm"
        days == 1L -> "明天 $hm"
        days in 2L..6L -> "${SimpleDateFormat("EEEE", Locale.CHINA).format(date)} $hm"
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(date)
    }
}

/** 「今天 23:52」「昨天 09:10」「9月11日 23:52」—— 记录和待办都用它显示生成时间 */
internal fun formatCreatedAt(iso: String?): String {
    val ms = iso?.let { parseIsoMs(it) } ?: return ""
    val d = Date(ms)
    val hm = SimpleDateFormat("HH:mm", Locale.US).format(d)
    return when (daysFromToday(ms)) {
        0L -> "今天 $hm"
        -1L -> "昨天 $hm"
        else -> SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(d)
    }
}

private fun formatDoneAt(iso: String): String {
    val ms = parseIsoMs(iso) ?: return "已完成"
    val date = Date(ms)
    val hm = SimpleDateFormat("HH:mm", Locale.US).format(date)
    return "完成于 " + when (daysFromToday(ms)) {
        0L -> "今天 $hm"
        -1L -> "昨天 $hm"
        else -> SimpleDateFormat("M月d日", Locale.CHINA).format(date)
    }
}

private fun isOverdue(iso: String?): Boolean =
    iso != null && (parseIsoMs(iso) ?: Long.MAX_VALUE) < System.currentTimeMillis()

private fun nowIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

// ── 列表 ──────────────────────────────────────────────────────────────────

/** 底部弹出的时间选择用在两个地方，意图不同 */
private enum class SheetMode { Snooze, SetReminderTime }
private data class SheetTarget(val task: Task, val mode: SheetMode)

/** 待办的筛选。有时间和有提醒不是一回事：可以有截止时间但不响铃 */
private enum class TaskFilter(val label: String) {
    All("全部"), Reminding("有提醒"), High("高优先级")
}

private fun priorityRank(p: String) = when (p) { "high" -> 2; "medium" -> 1; else -> 0 }

/**
 * 排序：先按优先级，同级里等得久的排前面。
 * 不用截止时间当主序 —— 那样一堆没时间的待办会永远沉底，越拖越看不见。
 */
private fun sortTasks(list: List<Task>) = list.sortedWith(
    compareByDescending<Task> { priorityRank(it.priority) }.thenBy { it.createdAt ?: "" }
)

private val WEEKDAY = listOf("一", "二", "三", "四", "五", "六", "日")

/** 「每天」「每周三」「每两周」「每月」 */
fun recurLabel(task: Task): String? {
    val every = if (task.recurInterval > 1) "每${task.recurInterval}" else "每"
    return when (task.recurKind) {
        "daily" -> "${every}天"
        "weekly" -> task.recurWeekdays?.takeIf { it.isNotEmpty() }
            ?.let { "每周" + it.sorted().joinToString("") { d -> WEEKDAY[d - 1] } }
            ?: "${every}周"
        "monthly" -> "${every}月"
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    reloadKey: Int = 0,
    onOpenCapture: (String) -> Unit = {},
    /** 点待办进的是「待办详情」——那是个提醒事项编辑器，不是笔记 */
    onOpenTask: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<List<Task>>(emptyList()) }
    var done by remember { mutableStateOf<List<Task>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    var filter by remember { mutableStateOf(TaskFilter.All) }
    var recurOpen by remember { mutableStateOf(true) }
    // 循环待办最近几次是完成还是跳过
    var outcomes by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var refreshing by remember { mutableStateOf(false) }
    // 刚删掉的那条，给几秒「撤销」的机会
    var justTrashed by remember { mutableStateOf<Task?>(null) }

    suspend fun load() {
        try {
            open = Api.listOpenTasks()
            done = if (AppPrefs.hideCompletedTasks(context)) emptyList()
                   else Api.listDoneTasksSince(AppPrefs.DONE_KEEP_DAYS)
            outcomes = Api.recentOutcomes(open.filter { it.recurKind != null }.map { it.id })
            // 顺手喂给桌面小组件 —— 它不能联网，只能读这份缓存。
            // 统一走 rebuildCache：未完成的在上、最近完成的划着线在下，
            // 各处写法一致，不会出现「从 App 刷一下，划线的就没了」。
            TasksWidget.rebuildCache(context)
            TasksWidget.refresh(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // reloadKey 一变这个 effect 就重启，旧的那次被「取消」是正常收尾。
            // 跟记录页同一类坑：把取消当异常吞掉，列表就会停在旧数据上
            throw e
        } catch (_: Exception) {
        } finally { loading = false }
    }
    LaunchedEffect(reloadKey) { load() }

    LaunchedEffect(justTrashed) {
        if (justTrashed != null) { delay(5000); justTrashed = null }
    }

    /** 软删除：先在界面上拿掉让操作跟手，再落库；失败就重新拉一次自然纠正 */
    fun trash(t: Task) {
        open = open.filterNot { it.id == t.id }
        done = done.filterNot { it.id == t.id }
        justTrashed = t
        scope.launch {
            try { Api.trashTask(t.id) } catch (_: Exception) { load() }
        }
    }

    fun undo() {
        val t = justTrashed ?: return
        justTrashed = null
        scope.launch {
            try { Api.restoreTask(t.id) } catch (_: Exception) {}
            load()
        }
    }

    /**
     * 勾选 / 取消勾选。
     * 勾掉的不消失，划线后排到未完成的下面 —— 看得见自己做了什么，才好 track。
     * 先改本地让界面立刻响应，再落库；失败了重新拉一次自然纠正。
     */
    fun toggleDone(t: Task) {
        // 循环待办：完成这一次就滚到下一次，不进「已完成」
        if (t.status == "open" && t.recurKind != null) {
            scope.launch {
                try {
                    // 跟闹钟上点「已完成」走同一条路，日志才不会缺口
                    Api.logOccurrence(t.id, "done")
                    Api.advanceRecurring(t.id)
                    ReminderScheduler.syncFromServer(context)
                } catch (_: Exception) {}
                load()
            }
            return
        }
        val wasOpen = t.status == "open"
        if (wasOpen) {
            open = open.filterNot { it.id == t.id }
            if (!AppPrefs.hideCompletedTasks(context)) {
                done = listOf(t.copy(status = "done", completedAt = nowIso())) + done
            }
        } else {
            done = done.filterNot { it.id == t.id }
            open = open + t.copy(status = "open", completedAt = null)
        }
        scope.launch {
            try {
                Api.setTaskStatus(t.id, if (wasOpen) "done" else "open")
                // 完成了就别再响；恢复了就把还没到点的提醒重新排上
                ReminderScheduler.syncFromServer(context)
            } catch (_: Exception) {}
            load()
        }
    }

    /** 铃铛：开 = 到点响，关 = 只是记一下 */
    fun toggleReminder(t: Task) {
        // 想开提醒却没有时间点，先问什么时候响
        if (!t.wantsReminder && t.dueAt == null) {
            sheet = SheetTarget(t, SheetMode.SetReminderTime); return
        }
        val wants = !t.wantsReminder
        open = open.map { if (it.id == t.id) it.copy(wantsReminder = wants) else it }
        scope.launch {
            try {
                Api.setTaskReminder(t, wants)
                ReminderScheduler.syncFromServer(context)
            } catch (_: Exception) {}
            load()
        }
    }

    val shownOpen = sortTasks(
        when (filter) {
            TaskFilter.All -> open
            TaskFilter.Reminding -> open.filter { it.wantsReminder }
            TaskFilter.High -> open.filter { it.priority == "high" }
        }
    )
    val oneTime = shownOpen.filter { it.recurKind == null }
    val recurring = shownOpen.filter { it.recurKind != null }

    Column(modifier.fillMaxSize()) {
        if (open.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(TaskFilter.entries.toList()) { f ->
                    FilterChip(f == filter, { filter = f }, { Text(f.label) })
                }
            }
        }

        justTrashed?.let {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已删除「${it.title}」", Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1)
                    TextButton(onClick = ::undo) {
                        Text("撤销", color = MaterialTheme.colorScheme.inversePrimary)
                    }
                }
            }
        }

        // PullToRefreshBox 本身是个 Box，但**宽度得自己撑满**：
        // 在 Column 里 weight 只管高度，宽度仍是包裹内容。加载时里面只有一个转圈，
        // Box 就缩成了 40dp 宽贴在左边，圈在窄盒子里「居中」，看着就是靠左。
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                open.isEmpty() && done.isEmpty() -> Text(
                    "没有待办\n说一句「提醒我…」就会出现在这里",
                    Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    // 底部留白，最后一条不会被悬浮按钮压住
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 130.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (open.isEmpty()) {
                        item(key = "all-done") {
                            Text(
                                "未完成的都做完了 🎉",
                                Modifier.animateItem().fillMaxWidth().padding(vertical = 12.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (shownOpen.isEmpty()) {
                        item(key = "filtered-empty") {
                            Text(
                                "这个筛选下没有待办",
                                Modifier.animateItem().fillMaxWidth().padding(vertical = 12.dp),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(oneTime, key = { it.id }) { t ->
                        SwipeToReveal(
                            actionLabel = "删除",
                            onAction = { trash(t) },
                            modifier = Modifier.animateItem(),
                        ) { revealed, close ->
                            TaskRow(t, { toggleDone(t) }, { toggleReminder(t) },
                                { sheet = SheetTarget(t, SheetMode.Snooze) },
                                { if (revealed) close() else onOpenTask(t.id) },
                                emptyList())
                        }
                    }
                    // 循环的放在下面并且可以收起来 —— 它们每周期都会回来，
                    // 天天占着列表顶部只会淹掉真正要做的事
                    if (recurring.isNotEmpty()) {
                        item(key = "h-recur") {
                            Row(
                                Modifier.animateItem().fillMaxWidth()
                                    .clickable { recurOpen = !recurOpen }
                                    .padding(top = 14.dp, bottom = 2.dp, start = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("循环 · ${recurring.size}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Text(if (recurOpen) "▾" else "▸",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (recurOpen) {
                            items(recurring, key = { it.id }) { t ->
                                SwipeToReveal(
                                    actionLabel = "删除",
                                    onAction = { trash(t) },
                                    modifier = Modifier.animateItem(),
                                ) { revealed, close ->
                                    TaskRow(t, { toggleDone(t) }, { toggleReminder(t) },
                                        { sheet = SheetTarget(t, SheetMode.Snooze) },
                                        { if (revealed) close() else onOpenTask(t.id) },
                                        outcomes[t.id].orEmpty())
                                }
                            }
                        }
                    }
                    if (done.isNotEmpty()) {
                        item(key = "h-done") {
                            SectionHeader("已完成 · ${done.size}", null, Modifier.animateItem())
                        }
                        items(done, key = { it.id }) { t ->
                            SwipeToReveal(
                                actionLabel = "删除",
                                onAction = { trash(t) },
                                modifier = Modifier.animateItem(),
                            ) { revealed, close ->
                                TaskRow(t, { toggleDone(t) }, {}, {},
                                    { if (revealed) close() else onOpenTask(t.id) },
                                    emptyList())
                            }
                        }
                    }
                }
            }
        }
    }

    sheet?.let { target ->
        TimePickSheet(
            title = when (target.mode) {
                SheetMode.Snooze -> "推迟「${target.task.title}」"
                SheetMode.SetReminderTime -> "什么时候提醒你？"
            },
            onDismiss = { sheet = null },
            onPick = { iso ->
                val t = target.task
                val mode = target.mode
                sheet = null
                scope.launch {
                    try {
                        when (mode) {
                            SheetMode.Snooze -> Api.snoozeTask(t, iso)
                            SheetMode.SetReminderTime -> Api.setTaskReminder(t, true, iso)
                        }
                        ReminderScheduler.syncFromServer(context)
                    } catch (_: Exception) {}
                    load()
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, hint: String?, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 14.dp, bottom = 2.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        hint?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    onToggleDone: () -> Unit,
    onToggleReminder: () -> Unit,
    onSnooze: () -> Unit,
    onOpenSource: (() -> Unit)?,
    recentOutcomes: List<String>,
    modifier: Modifier = Modifier,
) {
    val isDone = task.status == "done"
    val overdue = !isDone && isOverdue(task.dueAt)
    // 待办不出现在「记录」里，所以必须能从这儿回到原始录音
    val base = modifier.fillMaxWidth().alpha(if (isDone) 0.55f else 1f)
    val cardModifier = if (onOpenSource != null) base.clickable { onOpenSource() } else base

    Card(shape = RoundedCornerShape(12.dp), modifier = cardModifier) {
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isDone, onCheckedChange = { onToggleDone() })
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (isDone) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isDone) {
                        PriorityDot(task.priority)
                        Spacer(Modifier.width(6.dp))
                    }
                    val wantsButNoTime = !isDone && task.wantsReminder && task.dueAt == null
                    Text(
                        when {
                            isDone -> task.completedAt?.let { formatDoneAt(it) } ?: "已完成"
                            task.dueAt != null ->
                                formatDue(task.dueAt) + if (task.dueIsFuzzy) " ·大概" else ""
                            task.wantsReminder -> "要提醒，但还没设时间"
                            else -> "没有截止时间"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            overdue || wantsButNoTime -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    // PRD：被推迟多次的任务要能被察觉
                    if (!isDone && task.snoozeCount >= 2) {
                        Spacer(Modifier.width(8.dp))
                        Text("已推迟 ${task.snoozeCount} 次",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    recurLabel(task)?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("🔁 $it", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    if (onOpenSource != null) {
                        Spacer(Modifier.width(8.dp))
                        Text("🎙", style = MaterialTheme.typography.labelSmall)
                    }
                }
                // 什么时候记下来的 —— 单独一行，免得跟截止时间挤在一起看串
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "记于 " + formatCreatedAt(task.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    // 循环待办最近几次做没做 —— 新的在左边
                    if (recentOutcomes.isNotEmpty()) {
                        Spacer(Modifier.width(10.dp))
                        Text("最近", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        Spacer(Modifier.width(4.dp))
                        recentOutcomes.forEach { o ->
                            Text(
                                if (o == "done") "✓" else "✗",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (o == "done") Color(0xFF2E7D32)
                                        else MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(end = 3.dp),
                            )
                        }
                    }
                }
            }
            if (!isDone) {
                // 铃铛：开 = 到点响，关 = 只是记一下
                IconButton(onClick = onToggleReminder) {
                    Text(
                        if (task.wantsReminder) "🔔" else "🔕",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.alpha(if (task.wantsReminder) 1f else 0.4f),
                    )
                }
                // 没有时间点就没什么可推迟的
                if (task.dueAt != null) TextButton(onClick = onSnooze) { Text("推迟") }
            }
        }
    }
}

@Composable
private fun PriorityDot(priority: String) {
    val color = when (priority) {
        "high" -> Color(0xFFD32F2F)
        "low" -> Color(0xFF9E9E9E)
        else -> Color(0xFFF9A825)
    }
    Surface(color = color, shape = RoundedCornerShape(50), content = {},
        modifier = Modifier.size(7.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickSheet(title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val utc = remember {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
    val options = remember {
        listOf<Pair<String, Calendar.() -> Unit>>(
            "5 分钟后" to { add(Calendar.MINUTE, 5) },
            "1 小时后" to { add(Calendar.HOUR_OF_DAY, 1) },
            "24 小时后" to { add(Calendar.HOUR_OF_DAY, 24) },
            "下周同一时间" to { add(Calendar.DAY_OF_YEAR, 7) },
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            options.forEach { (label, shift) ->
                val target = Calendar.getInstance().apply(shift).time
                TextButton(
                    onClick = { onPick(utc.format(Calendar.getInstance().apply(shift).time)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label, Modifier.weight(1f))
                    // 顺带显示推到几点，免得心算
                    Text(
                        formatDue(utc.format(target)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
