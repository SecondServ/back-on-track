package com.hector.backontrack.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hector.backontrack.AppPrefs
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 换时区之后，循环提醒跟着人走。
 *
 * 规则（2026-11 定的）：
 *   - 循环提醒（「每天晚上 10 点吃药」）跟作息走 → 到了上海就是上海的晚上 10 点
 *   - 一次性提醒（「12 号上午 11 点看医生」）绑着某地的真实时刻 → 不动
 *
 * 两个入口：系统的时区变更广播（落地那一下），以及每次打开 App 再核对一遍
 * （广播那一刻可能没网，没搬成）。搬成功了才记下新时区，没成功下次再来。
 */
object TimeZoneWatcher {
    private val lock = Mutex()

    suspend fun followIfChanged(ctx: Context) = lock.withLock {
        val now = java.util.TimeZone.getDefault().id
        val last = AppPrefs.lastTz(ctx)
        if (last == null) { AppPrefs.setLastTz(ctx, now); return@withLock }
        if (last == now) return@withLock
        if (!Session.restoreInto(ctx)) return@withLock
        try {
            Api.followTimeZone(last)
            AppPrefs.setLastTz(ctx, now)
            ReminderScheduler.syncFromServer(ctx)
        } catch (_: Exception) {
            // 多半是没网。lastTz 不更新，下次打开 App 会再搬一次
        }
    }
}

/** 系统广播：手机时区变了 */
class TimeZoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { TimeZoneWatcher.followIfChanged(context.applicationContext) }
            finally { pending.finish() }
        }
    }
}
