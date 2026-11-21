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

        // deadline 正点 → 闹钟：立刻开始响铃震动（AlarmService），同时请求全屏界面。
        // 熄屏 / 锁屏时系统会直接亮屏弹出全屏界面；亮屏用着手机时是一条带按钮的横幅。
        // 两种情况铃声都已经在响，不用先点进去。
        if (isAlarm) {
            val alarm = AlarmInfo(
                id, taskId, title,
                recurring = intent.getBooleanExtra("recurring", false),
                dueAtMs = intent.getLongExtra("due_at_ms", 0L),
            )
            try {
                AlarmService.start(context, alarm)
            } catch (e: Exception) {
                // 前台服务起不来（极少见）：退回只发一条全屏通知，至少有个东西出来
                Log.e(TAG, "闹钟服务启动失败，退回普通通知 id=$id", e)
                context.getSystemService(NotificationManager::class.java)
                    ?.notify(id.hashCode(), AlarmService.buildNotification(context, alarm, true))
            }
            Log.i(TAG, "到点闹钟 id=$id recurring=${alarm.recurring}")
            ReminderStore.remove(context, id)

            // 循环待办：这一次已经响了，马上让服务端滚到下一次、排好下一轮提醒。
            // 以前要等他在闹钟上点「完成 / 跳过」才滚 —— 哪次没点，这条循环待办就永远停在那天，
            // 再也不响。推迟出来的那次（local）不用再滚，第一次响的时候已经滚过了
            if (alarm.recurring && !intent.getBooleanExtra("local", false)) {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (Session.restoreInto(context)) {
                            Api.rescheduleTask(taskId)
                            ReminderScheduler.syncFromServer(context)
                        }
                    } catch (e: Exception) {
                        // 没网：下次打开 App 同步时会补滚
                        Log.e(TAG, "循环待办滚到下一次失败 id=$id", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
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
