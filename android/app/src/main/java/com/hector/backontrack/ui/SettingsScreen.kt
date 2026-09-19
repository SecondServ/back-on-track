package com.hector.backontrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hector.backontrack.AppPrefs
import com.hector.backontrack.net.Api
import com.hector.backontrack.reminder.NudgeScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val context = LocalContext.current
    var hideDone by remember { mutableStateOf(AppPrefs.hideCompletedTasks(context)) }
    var nudgeDays by remember { mutableIntStateOf(AppPrefs.nudgeDays(context)) }
    // null = 还没从服务端读到。读不到就不给点，避免一点就把线路写成默认值
    var route by remember { mutableStateOf<String?>(null) }
    var routeErr by remember { mutableStateOf<String?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            route = Api.getAiRoute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            routeErr = e.message ?: "读取失败"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad)
                // 这一页原本不滚 —— 条目少的时候看不出来，
                // 加了「AI 线路」和「显示」两块之后，底下的垃圾箱和退出登录
                // 就被挤出屏幕（Column 空间不够时会把后面的孩子压成 0 高度）
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel("AI")
            ListItem(
                headlineContent = { Text("用户档案") },
                supportingContent = {
                    Text("告诉 AI 你是谁、常说到哪些名字，转写和整理都会更准")
                },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier.clickable { onOpenProfile() },
            )

            ListItem(
                headlineContent = { Text("AI 线路") },
                supportingContent = {
                    Text(
                        when {
                            routeErr != null -> "读不到当前设置：$routeErr"
                            route == null -> "读取中…"
                            route == "china" ->
                                "转写走硅基流动（SenseVoice），整理和问答走 DeepSeek。" +
                                    "不能联网搜 —— 资讯和问答会改成凭模型自己的知识答"
                            else ->
                                "转写走 OpenAI Whisper，整理和问答走 OpenAI，能联网搜。" +
                                    "在国内如果卡或者连不上，切到中国线路"
                        }
                    )
                },
            )
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("default" to "默认（OpenAI）", "china" to "中国（DeepSeek）").forEach { (r, t) ->
                    FilterChip(
                        selected = route == r,
                        enabled = route != null,
                        onClick = {
                            val prev = route
                            route = r          // 先动，别让他等一个来回
                            scope.launch {
                                try {
                                    Api.setAiRoute(r)
                                    routeErr = null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    route = prev   // 没存上就退回去，不能让界面骗他
                                    routeErr = e.message ?: "保存失败"
                                }
                            }
                        },
                        label = { Text(t) },
                    )
                }
            }
            Text(
                "两条线路都要能连上 Supabase 才行 —— 这个开关只换模型，换不了网络。",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("待办")
            ListItem(
                headlineContent = { Text("隐藏已完成的待办") },
                supportingContent = {
                    Text(
                        if (hideDone) "已完成的待办不在列表里显示"
                        else "显示最近 ${AppPrefs.DONE_KEEP_DAYS} 天内完成的，划线排在未完成的下面"
                    )
                },
                trailingContent = {
                    Switch(
                        checked = hideDone,
                        onCheckedChange = {
                            hideDone = it
                            AppPrefs.setHideCompletedTasks(context, it)
                        },
                    )
                },
            )

            ListItem(
                headlineContent = { Text("定期提醒没做完的待办") },
                supportingContent = {
                    Text(
                        if (nudgeDays <= 0) "已关闭"
                        else "每 $nudgeDays 天推送一次，列出还没做完的待办。" +
                            "设了闹钟的不在此列 —— 它们自己会响"
                    )
                },
            )
            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(0 to "关闭", 3 to "3 天", 7 to "7 天", 14 to "14 天").forEach { (d, t) ->
                    FilterChip(
                        selected = nudgeDays == d,
                        onClick = {
                            nudgeDays = d
                            AppPrefs.setNudgeDays(context, d)
                            NudgeScheduler.schedule(context)
                        },
                        label = { Text(t) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("显示")
            ListItem(
                headlineContent = { Text("整体缩放") },
                supportingContent = {
                    Text(
                        "屏幕大的手机上元素显得过大时往小调。" +
                            "字号、间距、图标一起缩，版式不变。只影响这台手机"
                    )
                },
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(listOf(1.10f, 1.0f, 0.95f, 0.90f, 0.85f, 0.80f)) { v ->
                    FilterChip(
                        // 浮点数别直接比 —— 存进 prefs 再读回来不保证还是同一个值
                        selected = kotlin.math.abs(UiScale.value - v) < 0.001f,
                        onClick = { UiScale.set(context, v) },
                        label = { Text("${(v * 100).toInt()}%") },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("记录")
            ListItem(
                headlineContent = { Text("垃圾箱") },
                supportingContent = {
                    Text("删除的记录保留 ${AppPrefs.TRASH_KEEP_DAYS} 天，之后自动永久清除")
                },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier.clickable { onOpenTrash() },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionLabel("账号")
            ListItem(
                headlineContent = {
                    Text("退出登录", color = MaterialTheme.colorScheme.error)
                },
                // 必须确认。改缩放会让整页重新排版，下面的内容整体上移，
                // 「退出登录」可能正好挪到手指刚点过的位置 —— 一下误触就把登录态清了
                modifier = Modifier.clickable { confirmSignOut = true },
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("退出登录？") },
            text = { Text("要重新输入邮箱和密码才能继续用。本机没上传完的录音也会留在队列里等重新登录。") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
