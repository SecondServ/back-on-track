package com.hector.backontrack.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hector.backontrack.AppPrefs
import com.hector.backontrack.reminder.NudgeScheduler

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            SectionLabel("AI")
            ListItem(
                headlineContent = { Text("用户档案") },
                supportingContent = {
                    Text("告诉 AI 你是谁、常说到哪些名字，转写和整理都会更准")
                },
                trailingContent = { Text("›", style = MaterialTheme.typography.titleLarge) },
                modifier = Modifier.clickable { onOpenProfile() },
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
                modifier = Modifier.clickable { onSignOut() },
            )
        }
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
