package com.hector.backontrack.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hector.backontrack.AppPrefs
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Capture
import kotlinx.coroutines.launch

private fun daysLeftLabel(deletedAt: String?): String {
    val ms = deletedAt?.let { parseIsoMs(it) } ?: return ""
    val goneAt = ms + AppPrefs.TRASH_KEEP_DAYS * 86_400_000L
    val left = ((goneAt - System.currentTimeMillis()) / 86_400_000L).toInt()
    return if (left <= 0) "即将永久删除" else "$left 天后永久删除"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Capture>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    // 等待确认的永久删除：单条，或者「清空」时的全部
    var pendingDelete by remember { mutableStateOf<List<Capture>?>(null) }

    suspend fun load() {
        try { items = Api.listTrash() } catch (_: Exception) {} finally { loading = false }
    }
    LaunchedEffect(Unit) { load() }

    fun restore(c: Capture) {
        items = items.filterNot { it.id == c.id }
        scope.launch {
            try { Api.restoreCapture(c.id) } catch (_: Exception) {}
            load()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("垃圾箱") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
                actions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { pendingDelete = items }) {
                            Text("清空", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading || busy -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                items.isEmpty() -> Text(
                    "垃圾箱是空的\n在「记录」里左滑条目就能删除",
                    Modifier.align(Alignment.Center),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "删除 ${AppPrefs.TRASH_KEEP_DAYS} 天后自动永久清除，录音也会一起删掉",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(items, key = { it.id }) { c ->
                        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    c.kind?.let { KindChipPublic(it); Spacer(Modifier.width(8.dp)) }
                                    Text(c.summary ?: "（无摘要）",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f))
                                }
                                (c.correctedText ?: c.finalText)?.let {
                                    Spacer(Modifier.height(6.dp))
                                    Text(it, style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2, modifier = Modifier.padding(end = 8.dp))
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(daysLeftLabel(c.deletedAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f))
                                    TextButton(onClick = { restore(c) }) { Text("恢复") }
                                    TextButton(onClick = { pendingDelete = listOf(c) }) {
                                        Text("永久删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { targets ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(if (targets.size == 1) "永久删除这条？" else "清空垃圾箱？") },
            text = {
                Text((if (targets.size == 1) "这条记录" else "这 ${targets.size} 条记录") +
                    "和录音会被永久删除，无法恢复。")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    busy = true
                    scope.launch {
                        targets.forEach {
                            try { Api.deleteCapturePermanently(it) } catch (_: Exception) {}
                        }
                        busy = false
                        load()
                    }
                }) { Text("永久删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}
