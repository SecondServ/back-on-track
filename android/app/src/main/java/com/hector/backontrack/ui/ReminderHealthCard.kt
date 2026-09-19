package com.hector.backontrack.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hector.backontrack.reminder.ReminderScheduler

private data class Check(
    val label: String,
    val ok: Boolean,
    val why: String,
    val fix: (() -> Unit)?,
)

/**
 * 提醒健康检查。
 *
 * Android 上提醒失效基本都是**静默**的 —— 系统不会告诉你它吞掉了通知，
 * 用户只会觉得"这 App 不靠谱"。所以必须把这几个开关的状态摊开给用户看。
 */
@Composable
fun ReminderHealthCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { tick++ }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { tick++ }

    val checks = remember(tick) { buildChecks(ctx, notifyLauncher::launch) { settingsLauncher.launch(it) } }
    val bad = checks.filter { !it.ok }

    if (bad.isEmpty()) return  // 全绿就不占地方

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("提醒可能不会响", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "以下开关没打开，系统会静默吞掉提醒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
            )
            bad.forEach { c ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.bodyMedium)
                        Text(c.why, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f))
                    }
                    c.fix?.let {
                        FilledTonalButton(onClick = it) { Text("去开启") }
                    }
                }
            }
        }
    }
}

private fun buildChecks(
    ctx: Context,
    askNotify: (String) -> Unit,
    openSettings: (android.content.Intent) -> Unit,
): List<Check> = listOf(
    Check(
        label = "通知权限",
        ok = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED,
        why = "没有它，提醒根本弹不出来",
        fix = { askNotify(Manifest.permission.POST_NOTIFICATIONS) },
    ),
    Check(
        label = "精确闹钟",
        ok = ReminderScheduler.canScheduleExact(ctx),
        why = "没有它，提醒会被系统推迟到不确定的时间",
        fix = { openSettings(ReminderScheduler.exactAlarmSettingsIntent()) },
    ),
    Check(
        label = "不受电池优化限制",
        ok = ReminderScheduler.isIgnoringBatteryOptimizations(ctx),
        why = "开着省电限制时，手机休眠中的提醒可能整个被跳过",
        fix = { openSettings(ReminderScheduler.batteryOptimizationIntent(ctx)) },
    ),
)
