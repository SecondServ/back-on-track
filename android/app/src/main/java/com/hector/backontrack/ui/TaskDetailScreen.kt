package com.hector.backontrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Capture
import com.hector.backontrack.net.Occurrence
import com.hector.backontrack.net.Task
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 待办详情 —— 一个真正的提醒事项编辑器，不是笔记页。
 *
 * 待办和笔记是两种东西：笔记要读，待办要「设」。所以这里是提醒文字、优先级、
 * 日期时间、重复频率这些可改的字段，原始语音退成底部的一个附件。
 *
 * 循环待办还会显示打卡记录：每次响铃之后到底完成了还是跳过了，带时间戳 ——
 * 这是以后算完成率的原始数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(taskId: String, onBack: () -> Unit, onOpenCapture: (String) -> Unit) {
    val scope = rememberCoroutineScope()

    var task by remember { mutableStateOf<Task?>(null) }
    var capture by remember { mutableStateOf<Capture?>(null) }
    var occurrences by remember { mutableStateOf<List<Occurrence>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }

    // 编辑中的字段
    var title by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("medium") }
    var wantsReminder by remember { mutableStateOf(false) }
    var recurKind by remember { mutableStateOf<String?>(null) }
    /** 本地时区的毫秒时间戳；null = 没有截止时间 */
    var dueMs by remember { mutableStateOf<Long?>(null) }

    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    suspend fun load() {
        try {
            val t = Api.getTask(taskId)
            task = t
            if (t != null) {
                title = t.title
                priority = t.priority
                wantsReminder = t.wantsReminder
                recurKind = t.recurKind
                dueMs = t.dueAt?.let { parseIsoMs(it) }
                capture = t.captureId?.let { runCatching { Api.getCapture(it) }.getOrNull() }
                occurrences = if (t.recurKind != null)
                    runCatching { Api.listOccurrences(taskId) }.getOrDefault(emptyList())
                else emptyList()
            }
        } catch (e: Exception) {
            hint = if (e is java.io.IOException) "没网" else "读取失败：${e.message?.take(60)}"
        } finally { loading = false }
    }

    LaunchedEffect(taskId) { load() }

    fun save() {
        scope.launch {
            hint = "保存中…"
            try {
                Api.updateTask(
                    taskId = taskId,
                    title = title.trim().ifEmpty { task?.title ?: "" },
                    priority = priority,
                    dueAtIso = dueMs?.let { isoFromMs(it) },
                    wantsReminder = wantsReminder,
                    recurKind = recurKind,
                )
                load()
                hint = "已保存"
            } catch (e: Exception) {
                hint = "没存上：${e.message?.take(60)}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待办") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
                actions = {
                    TextButton(onClick = ::save, enabled = !loading) { Text("保存") }
                },
            )
        },
    ) { pad ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Label("提醒文字")
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Spacer(Modifier.height(18.dp))
            Label("优先级")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("high" to "高", "medium" to "中", "low" to "低").forEach { (v, t) ->
                    FilterChip(priority == v, { priority = v }, { Text(t) })
                }
            }

            Spacer(Modifier.height(18.dp))
            Label("什么时候")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate = true }) {
                    Text(dueMs?.let { fmtDate(it) } ?: "选日期")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { pickTime = true }, enabled = dueMs != null) {
                    Text(dueMs?.let { fmtTime(it) } ?: "选时间")
                }
                if (dueMs != null) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { dueMs = null; wantsReminder = false }) { Text("清除") }
                }
            }

            Spacer(Modifier.height(18.dp))
            Label("重复")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null to "不重复", "daily" to "每天", "weekly" to "每周", "monthly" to "每月")
                    .forEach { (v, t) ->
                        FilterChip(recurKind == v, { recurKind = v }, { Text(t) })
                    }
            }

            Spacer(Modifier.height(18.dp))
            ListItem(
                headlineContent = { Text("到点响铃提醒") },
                supportingContent = {
                    Text(
                        if (dueMs == null) "先选个时间才能提醒"
                        else "到点会像闹钟一样全屏提醒，可以选完成 / 推迟 / 跳过"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = wantsReminder,
                        enabled = dueMs != null,
                        onCheckedChange = { wantsReminder = it },
                    )
                },
            )

            hint?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            // ── 打卡记录：循环待办才有 ──────────────────────────────────
            if (recurKind != null) {
                Spacer(Modifier.height(24.dp))
                val doneN = occurrences.count { it.outcome == "done" }
                val skipN = occurrences.count { it.outcome == "skipped" }
                Label("打卡记录")
                if (occurrences.isEmpty()) {
                    Text("还没有记录。每次响铃后选「完成」或「跳过」，都会记在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "完成 $doneN 次 · 跳过 $skipN 次 · 完成率 " +
                            "${doneN * 100 / (doneN + skipN)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    occurrences.forEach { o ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (o.outcome == "done") "✓" else "✗",
                                color = if (o.outcome == "done")
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(if (o.outcome == "done") "完成" else "跳过",
                                style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text(fmtStamp(o.occurredAt),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // ── 原始语音：退成附件，但永远留着 ──────────────────────────
            capture?.let { c ->
                Spacer(Modifier.height(24.dp))
                Label("这条是怎么来的")
                c.audioPath?.let { AudioPlayer(it, label = "原始语音") }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onOpenCapture(c.id) }) { Text("看原话 / 笔记 →") }
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = dueMs ?: nowMs())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { dueMs = mergeDate(dueMs, it) }
                    pickDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("取消") } },
        ) { DatePicker(state = state) }
    }

    if (pickTime) {
        val cal = Calendar.getInstance().apply { timeInMillis = dueMs ?: nowMs() }
        val state = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    dueMs = mergeTime(dueMs ?: nowMs(), state.hour, state.minute)
                    pickTime = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("取消") } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(6.dp))
}

// ── 时间的来回转换 ────────────────────────────────────────────────────────
// 存的是 UTC 的 ISO 串，显示和编辑都按手机当地时间 —— 这两边不能混。

private fun nowMs() = System.currentTimeMillis()

private fun utcFmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }

// parseIsoMs 不在这儿定义：TaskListScreen.kt 里已经有一个 internal 的，
// 同包可见。再写一个同名的会变成「冲突的重载」，调用处直接报歧义。

private fun isoFromMs(ms: Long): String = utcFmt().format(Date(ms))

/**
 * 日期选择器给的是「那一天 UTC 零点」的毫秒数，直接用会偏掉一整天。
 * 所以只取它的年月日，时分沿用原来的（没有就默认早上 9 点）。
 */
private fun mergeDate(current: Long?, pickedUtcMidnight: Long): Long {
    val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        .apply { timeInMillis = pickedUtcMidnight }
    val old = Calendar.getInstance().apply { timeInMillis = current ?: nowMs() }
    return Calendar.getInstance().apply {
        set(Calendar.YEAR, picked.get(Calendar.YEAR))
        set(Calendar.MONTH, picked.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, picked.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, if (current != null) old.get(Calendar.HOUR_OF_DAY) else 9)
        set(Calendar.MINUTE, if (current != null) old.get(Calendar.MINUTE) else 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun mergeTime(current: Long, hour: Int, minute: Int): Long =
    Calendar.getInstance().apply {
        timeInMillis = current
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

private fun fmtDate(ms: Long) =
    SimpleDateFormat("M月d日 (E)", Locale.CHINA).format(Date(ms))

private fun fmtTime(ms: Long) = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))

private fun fmtStamp(iso: String): String = parseIsoMs(iso)
    ?.let { SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(it)) } ?: iso
