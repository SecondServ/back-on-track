package com.hector.backontrack.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 每天 23:59 把三张看板重算一遍。
 *
 * 为什么是夜里：回顾看的是「今天为止」，白天算出来的到晚上就旧了；
 * 而且这三次调用里有一次要联网搜（资讯页），耗时一分多钟 ——
 * 放在你睡前跑完，第二天早上打开就是现成的，不用对着转圈等。
 *
 * 用不精确闹钟（setAndAllowWhileIdle）：晚几分钟无所谓，
 * 没必要去占精确闹钟的配额，也更省电。
 */
object RecapScheduler {

    private const val REQUEST = 0x5245
    private const val TAG = "RecapScheduler"
    private const val HOUR = 23
    private const val MINUTE = 59

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQUEST,
        Intent(ctx, RecapReceiver::class.java).setAction("com.hector.backontrack.RECAP"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        // 必须是**精确**闹钟：Android 12 起，只有精确闹钟触发的广播
        // 才被允许在后台拉起前台服务。用不精确的，服务起不来
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFireAt(), pendingIntent(ctx))
    }

    /** 今天的 23:59；已经过了就排到明天 */
    private fun nextFireAt(): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    /** "2026-09" —— 本地月份 */
    private fun currentMonth(): String {
        val c = Calendar.getInstance()
        return "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    class RecapReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val app = ctx.applicationContext
            // 先把明晚排上：万一服务起不来，链条也不会断在这儿
            schedule(app)
            // 真正的活交给前台服务 —— 接收器里干不完（约 10 秒就被掐）
            RecapService.start(app)
        }
    }
}
