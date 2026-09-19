package com.hector.backontrack.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hector.backontrack.ui.UiScale
import com.hector.backontrack.ui.theme.BackOnTrackTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 点小组件上的文字，弹出来的待办详情。
 *
 * 只读、不改状态 —— 改状态是圈的事。分开就是为了防误触：
 * 一整行都能勾掉的话，手指蹭一下就把没做的事标成做完了，而小组件上
 * 放不下「撤销」。
 *
 * 数据直接读本地缓存，不联网：弹窗要立刻出来，为几行字等一个往返不值得。
 */
class TaskPeekActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(TasksWidget.EXTRA_ID)
        val row = WidgetCache.load(applicationContext).firstOrNull { it.id == id }

        if (row == null) { finish(); return }

        UiScale.ensureLoaded(this)
        setContent {
            BackOnTrackTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(row.title) },
                    text = {
                        Column {
                            Line("状态", if (row.done) "已完成" else "未完成")
                            Line("优先级", when (row.priority) {
                                "high" -> "高"
                                "low" -> "低"
                                else -> "中"
                            })
                            Line("截止时间", row.dueAt?.let { fmt(it) } ?: "没有截止时间")
                            Line("记于", WidgetCache.ageLabel(row.createdAt).ifEmpty { "—" })
                        }
                    },
                    confirmButton = { TextButton(onClick = { finish() }) { Text("关闭") } },
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.width(76.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium)
    }
}

/** 存的是 UTC，显示按手机当地时间 */
private fun fmt(iso: String): String = runCatching {
    val body = iso.substringBefore('.').removeSuffix("Z").replace("+00:00", "")
    val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(utc.parse(body)!!.time))
}.getOrDefault(iso)
