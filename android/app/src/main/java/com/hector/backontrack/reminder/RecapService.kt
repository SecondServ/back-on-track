package com.hector.backontrack.reminder

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.hector.backontrack.R
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 夜里重算三张看板的前台服务。
 *
 * 为什么非得是服务：**广播接收器只有约 10 秒预算**，而这三次调用要跑一两分钟
 * （资讯页要联网搜）。我第一版就是直接在接收器里做的，系统一声不响地把它掐了 ——
 * 日志什么都没有，只有服务端的时间戳暴露了「根本没跑」。
 *
 * 通知设成最低优先级：它在 23:59 出现，不该把人吵醒。
 */
class RecapService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFY_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!Session.restoreInto(applicationContext)) {
                    Log.w(TAG, "没有登录态，跳过")
                    return@launch
                }
                val c = Calendar.getInstance()
                val month = "%04d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
                // 一个失败不该连累另外两个 —— 资讯页要联网搜，最容易超时
                for (section in listOf("tasks", "ideas", "meal")) {
                    runCatching { Api.recap(month, section, force = true) }
                        .onSuccess { Log.i(TAG, "$section 已重算") }
                        .onFailure { Log.e(TAG, "$section 重算失败", it) }
                }
                Log.i(TAG, "三张看板处理完毕：$month")
            } finally {
                stopSelf(startId)
            }
        }
        // 被系统杀掉也别重启：明晚的闹钟会再来一次，没必要在内存紧张时硬扛
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            android.app.NotificationChannel(
                CHANNEL, "回顾更新", NotificationManager.IMPORTANCE_MIN,
            ).apply { description = "夜里把三张看板算好，第二天打开就是现成的" },
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("正在更新回顾")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(Notification.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RecapService"
        private const val CHANNEL = "recap_refresh"
        private const val NOTIFY_ID = 0x5246

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RecapService::class.java))
        }
    }
}
