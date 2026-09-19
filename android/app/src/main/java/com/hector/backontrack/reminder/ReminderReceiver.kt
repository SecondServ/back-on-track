package com.hector.backontrack.reminder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hector.backontrack.MainActivity
import android.util.Log
import com.hector.backontrack.R
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** 闹钟到点 → 弹通知 → 回报服务端已送达 */
class ReminderReceiver : BroadcastReceiver() {

    companion object { const val TAG = "BotReminder" }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Log.i(TAG, "闹钟触发 id=$id")
        val title = intent.getStringExtra("title") ?: "待办提醒"
        val body = intent.getStringExtra("body").orEmpty()
        val taskId = intent.getStringExtra("task_id").orEmpty()
        val isAlarm = intent.getStringExtra("style") == "alarm"

        Notifications.ensureChannel(context)

        // deadline 正点 → 全屏闹钟，必须手动处理
        if (isAlarm) {
            val full = Intent(context, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("id", id); putExtra("task_id", taskId); putExtra("title", title)
            }
            val fullPi = PendingIntent.getActivity(
                context, id.hashCode(), full,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // 通知本身也发一份 —— 系统不允许全屏时（权限被关、正在通话）
            // 会退化成抬头通知，不至于什么都没有
            val n = Notification.Builder(context, Notifications.ALARM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText("到时间了")
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi)
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(id.hashCode(), n)
            try { context.startActivity(full) } catch (_: Exception) {}
            Log.i(TAG, "全屏闹钟 id=$id")
            ReminderStore.remove(context, id)
            return
        }

        val open = PendingIntent.getActivity(
            context, id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val n: Notification = Notification.Builder(context, Notifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        try {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(id.hashCode(), n)
            Log.i(TAG, "已弹通知 id=$id title=$title")
        } catch (e: Exception) {
            // notify() 失败是静默的，不打日志就永远查不出来
            Log.e(TAG, "弹通知失败 id=$id", e)
        }

        // 通知已经弹了，本地缓存里就该清掉，免得重启后重复排
        ReminderStore.remove(context, id)

        // 回报服务端。goAsync 让广播多活一会儿，够发一个请求
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Session.restoreInto(context)) Api.markReminderDelivered(id)
            } catch (_: Exception) {
                // 网络失败不影响通知本身，下次同步会自然纠正
            } finally {
                pending.finish()
            }
        }
    }
}
