package com.hector.backontrack.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.Context

object Notifications {
    /** 普通递进提醒 */
    const val CHANNEL_ID = "reminders"
    /** deadline 正点的闹钟级提醒，走独立渠道才能单独配响铃和绕过勿扰 */
    const val ALARM_CHANNEL_ID = "reminder_alarm"
    /** 「还有几件事没做」的定期轻推。单独一个渠道，用户可以只关它 */
    const val NUDGE_CHANNEL_ID = "nudge"

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return

        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "待办提醒", NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Deadline 临近时的递进式预告"
                    enableVibration(true)
                }
            )
        }

        if (mgr.getNotificationChannel(NUDGE_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    NUDGE_CHANNEL_ID, "待办轻推", NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "每隔几天提醒一次：还有哪些待办没做完"
                }
            )
        }

        if (mgr.getNotificationChannel(ALARM_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    ALARM_CHANNEL_ID, "到点闹钟", NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Deadline 正点的闹钟，会亮屏响铃直到手动处理"
                    enableVibration(true)
                    setBypassDnd(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            )
        }
    }
}
