package com.hector.backontrack.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session

/**
 * 把服务端算好的提醒，变成手机上真会响的闹钟。
 *
 * 三个 Android 特有的坑，缺一条提醒就会静默失效：
 *   1. Doze —— 必须用 setExactAndAllowWhileIdle，普通 set() 会被推迟到下次唤醒窗口
 *   2. 电池优化 —— 系统仍可能限制，要引导用户把本应用加进白名单
 *   3. 重启 —— 所有闹钟被清空，必须在 BOOT_COMPLETED 里从本地缓存重建
 */
object ReminderScheduler {

    private fun alarmManager(ctx: Context) =
        ctx.getSystemService(AlarmManager::class.java)

    private fun pendingIntent(ctx: Context, r: PendingReminder): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = "com.hector.backontrack.FIRE"
            // data 必须唯一，否则 PendingIntent 会互相覆盖
            data = android.net.Uri.parse("bot://reminder/${r.id}")
            putExtra("id", r.id)
            putExtra("task_id", r.taskId)
            putExtra("title", r.title)
            putExtra("body", r.body)
            putExtra("style", r.style)
            putExtra("recurring", r.recurring)
            putExtra("local", r.local)
            putExtra("due_at_ms", r.dueAtMs)
        }
        return PendingIntent.getBroadcast(
            ctx, r.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun canScheduleExact(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            alarmManager(ctx)?.canScheduleExactAlarms() == true
        else true

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** 跳到「忽略电池优化」的系统设置页 */
    fun batteryOptimizationIntent(ctx: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:${ctx.packageName}")
        }

    /** 安卓 14 起「全屏通知」要单独授权；没有它熄屏时闹钟只是一条通知 */
    fun canUseFullScreen(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34)
            ctx.getSystemService(android.app.NotificationManager::class.java)
                ?.canUseFullScreenIntent() == true
        else true

    fun fullScreenSettingsIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = android.net.Uri.parse("package:${ctx.packageName}")
        }

    fun exactAlarmSettingsIntent() =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)

    private fun scheduleOne(ctx: Context, r: PendingReminder) {
        val am = alarmManager(ctx) ?: return
        if (r.fireAtMs <= System.currentTimeMillis()) return
        val pi = pendingIntent(ctx, r)
        try {
            // 到点闹钟、以及截止前 2 小时内的预告（「还有半小时」）用 setAlarmClock：
            // 系统把它当成真正的闹钟，准点响。
            // 不能都用 setExactAndAllowWhileIdle —— 这台 OnePlus（ColorOS）会把它悄悄放宽成
            // 「1 小时内随便什么时候」，「还有半小时」可能比正点闹钟还晚到。
            // 早上 9 点那几档日常预告晚点无所谓，照旧用普通的，免得锁屏上「下一个闹钟」全是它们
            val nearDue = r.dueAtMs > 0 && r.dueAtMs - r.fireAtMs <= 2 * 60 * 60_000L
            if (r.style == "alarm" || nearDue) {
                val show = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, com.hector.backontrack.MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(r.fireAtMs, show), pi)
            } else {
                // setExactAndAllowWhileIdle 能穿透 Doze
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.fireAtMs, pi)
            }
        } catch (_: SecurityException) {
            // 没有精确闹钟权限时降级 —— 会被系统推迟，但总比不响强
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.fireAtMs, pi)
        }
    }

    fun cancel(ctx: Context, r: PendingReminder) {
        alarmManager(ctx)?.cancel(pendingIntent(ctx, r))
    }

    /**
     * 用一份新的提醒列表整体替换。
     *
     * 注意：服务端只返回「尚未到点」的提醒。如果某条提醒的时间刚过、
     * 但闹钟还没来得及响，直接整体替换会把它撤销掉 —— 提醒就此人间蒸发。
     * 所以保留本地那些「已排、时间还没过太久」的条目。
     */
    fun replaceAll(ctx: Context, items: List<PendingReminder>) {
        val now = System.currentTimeMillis()
        val ids = items.map { it.id }.toSet()
        val cached = ReminderStore.load(ctx)
        // 刚过点 5 分钟内、服务端没再返回的，视为「即将触发」，不动它
        val inFlight = cached.filter {
            it.id !in ids && it.fireAtMs <= now && now - it.fireAtMs < 5 * 60_000
        }
        // 「推迟 10 分钟」排的闹钟只存在于本地，服务端永远不会返回它。
        // 上面那条规则只保护「时间已经过去」的条目，而推迟按定义在未来十分钟 ——
        // 所以以前每次同步都会把它取消掉。而同步在 App 启动和每次回到前台都会跑，
        // 点完推迟主界面一回到前台它就没了，表现就是「推迟 = 直接关掉闹钟」。
        val localPending = cached.filter { it.local && it.id !in ids && it.fireAtMs > now }
        val merged = items + inFlight + localPending

        val keep = merged.map { it.id }.toSet()
        cached.filter { it.id !in keep }.forEach { cancel(ctx, it) }
        Notifications.ensureChannel(ctx)
        items.forEach { scheduleOne(ctx, it) }
        ReminderStore.save(ctx, merged)
    }

    /** 开机后从本地缓存重建（此时通常还没网） */
    fun rescheduleFromCache(ctx: Context) {
        Notifications.ensureChannel(ctx)
        val now = System.currentTimeMillis()
        val alive = ReminderStore.load(ctx).filter { it.fireAtMs > now }
        alive.forEach { scheduleOne(ctx, it) }
        ReminderStore.save(ctx, alive)
    }

    /**
     * 用户在全屏闹钟上点了「稍后再提醒」：立刻就地排一个新闹钟，
     * 不等下一次联网同步 —— 网络不通时也必须能推迟成功。
     */
    fun snoozeLocally(ctx: Context, a: AlarmInfo, minutes: Int) {
        val later = PendingReminder(
            id = "${a.id.substringBefore("-snooze-")}-snooze-${System.currentTimeMillis()}",
            taskId = a.taskId,
            title = a.title,
            body = "推迟后的提醒",
            fireAtMs = System.currentTimeMillis() + minutes * 60_000L,
            style = "alarm",
            // 推迟的是循环待办，再响时点「完成」也得按循环处理 —— 不然会把整条循环待办勾掉
            recurring = a.recurring,
            local = true,
            dueAtMs = a.dueAtMs,
        )
        Notifications.ensureChannel(ctx)
        scheduleOne(ctx, later)
        ReminderStore.save(ctx, ReminderStore.load(ctx) + later)
    }

    /** 联网同步一次：拉服务端的待发提醒，重排全部闹钟 */
    suspend fun syncFromServer(ctx: Context): Int {
        if (!Session.restoreInto(ctx)) return 0
        // 循环待办的「下一次」已经过去了（那次闹钟没人处理、或者当时没网），
        // 先让服务端把它滚到下一个还没到的时间点、排好提醒，不然它就再也不响了
        try { Api.catchUpRecurring() } catch (_: Exception) {}
        val items = Api.listPendingReminders()
        replaceAll(ctx, items)
        return items.size
    }
}
