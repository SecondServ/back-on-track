package com.hector.backontrack.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 重启后所有 AlarmManager 闹钟都会被系统清空。
 * 不重建的话，用户重启一次手机，之前排好的提醒就全部静默消失 ——
 * 这是提醒类 App 最经典也最难被发现的失效方式。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                ReminderScheduler.rescheduleFromCache(context)
                // 轻推也是 AlarmManager 排的，重启同样会被清空
                NudgeScheduler.schedule(context)
                // 重启后不重排，这个定时就永远断了
                RecapScheduler.schedule(context)
            }
        }
    }
}
