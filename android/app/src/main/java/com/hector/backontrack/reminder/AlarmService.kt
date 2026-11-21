package com.hector.backontrack.reminder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.hector.backontrack.R

/** 一个到点闹钟需要带着走的信息：在通知、全屏界面、通知按钮之间传来传去 */
data class AlarmInfo(
    val id: String,
    val taskId: String,
    val title: String,
    val recurring: Boolean,
    /** 这次闹钟对应的那次 deadline。循环待办「完成」时告诉服务端完成的是哪一次 */
    val dueAtMs: Long,
) {
    fun into(i: Intent): Intent = i.putExtra("id", id).putExtra("task_id", taskId)
        .putExtra("title", title).putExtra("recurring", recurring).putExtra("due_at_ms", dueAtMs)

    companion object {
        fun from(i: Intent): AlarmInfo? {
            val id = i.getStringExtra("id") ?: return null
            return AlarmInfo(
                id, i.getStringExtra("task_id").orEmpty(),
                i.getStringExtra("title") ?: "待办提醒",
                i.getBooleanExtra("recurring", false), i.getLongExtra("due_at_ms", 0L),
            )
        }
    }
}

/**
 * 到点闹钟的「响铃」部分。
 *
 * 以前铃声是全屏界面自己放的。可全屏界面并不总能弹出来：
 *   - 亮屏、正在用手机时，安卓只给一条顶部横幅，不会直接盖满屏幕
 *   - 系统没给「全屏通知」权限时，熄屏也只是一条通知
 * 这两种情况下界面没开，铃也就不响 —— 表现为「先弹通知，点进去才响」。
 *
 * 现在铃声和震动放在这个前台服务里，到点立刻开始，跟界面开没开无关；
 * 通知上带「完成 / 推迟 / 跳过」按钮，亮屏时不用点进去也能处理。
 * 响 10 分钟没人管就停下铃声，通知留着。
 */
class AlarmService : Service() {

    companion object {
        private const val TAG = "BotAlarm"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val RING_MS = 10 * 60_000L

        fun start(ctx: Context, a: AlarmInfo) {
            val i = a.into(Intent(ctx, AlarmService::class.java).setAction(ACTION_START))
            ctx.startForegroundService(i)
        }

        /** 处理完了（不管点的是哪个按钮）：停铃、撤通知 */
        fun stop(ctx: Context, alarmId: String) {
            ctx.getSystemService(NotificationManager::class.java)?.cancel(alarmId.hashCode())
            try {
                ctx.startService(Intent(ctx, AlarmService::class.java)
                    .setAction(ACTION_STOP).putExtra("id", alarmId))
            } catch (_: Exception) {
                // 服务早就停了（比如响满 10 分钟自己停的），没什么要停的
            }
        }

        /** 全屏界面的 PendingIntent。通知点一下、以及系统允许全屏时都打开它 */
        fun fullScreenIntent(ctx: Context, a: AlarmInfo): PendingIntent {
            val full = a.into(Intent(ctx, AlarmActivity::class.java)).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            return PendingIntent.getActivity(ctx, a.id.hashCode(), full,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun actionIntent(ctx: Context, a: AlarmInfo, act: String): PendingIntent {
            val i = a.into(Intent(ctx, AlarmActionReceiver::class.java).setAction(act))
                // data 不同，三个按钮的 PendingIntent 才不会互相覆盖
                .setData(android.net.Uri.parse("bot://alarm/${a.id}/$act"))
            return PendingIntent.getBroadcast(ctx, (a.id + act).hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun buildNotification(ctx: Context, a: AlarmInfo, ringing: Boolean): Notification {
            val full = fullScreenIntent(ctx, a)
            val b = Notification.Builder(ctx, Notifications.ALARM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(a.title)
                .setContentText(if (ringing) "到时间了" else "到时间了（铃声已停，还没处理）")
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(full)
                .addAction(Notification.Action.Builder(null, "完成",
                    actionIntent(ctx, a, AlarmActions.DONE)).build())
                .addAction(Notification.Action.Builder(null, "推迟 10 分钟",
                    actionIntent(ctx, a, AlarmActions.SNOOZE)).build())
                .addAction(Notification.Action.Builder(null, "跳过",
                    actionIntent(ctx, a, AlarmActions.SKIP)).build())
            // 只有在响的时候才要求全屏；铃声停了之后再更新通知，不要又把屏幕点亮一次
            if (ringing) b.setFullScreenIntent(full, true)
            return b.build()
        }
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var current: AlarmInfo? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { ringTimedOut() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val a = AlarmInfo.from(intent) ?: return START_NOT_STICKY
                begin(a)
            }
            ACTION_STOP -> {
                val id = intent.getStringExtra("id")
                // 只停正在响的那一个；别的闹钟的按钮不该把它关掉
                if (current == null || id == null || id == current?.id) finishAll()
            }
            else -> if (current == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(a: AlarmInfo) {
        Notifications.ensureChannel(this)
        // 上一个还在响：它的通知留着（不响了，等他处理），换成这一个响
        current?.let { prev ->
            getSystemService(NotificationManager::class.java)
                ?.notify(prev.id.hashCode(), buildNotification(this, prev, ringing = false))
        }
        current = a
        startForeground(a.id.hashCode(), buildNotification(this, a, ringing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        startRinging()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, RING_MS)
        Log.i(TAG, "开始响铃 id=${a.id}")
    }

    private fun startRinging() {
        stopRinging()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setDataSource(this@AlarmService, uri)
                // 走「闹钟」音量，静音模式下也会响 —— 跟系统闹钟一样
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "放铃声失败，只剩震动", e)
        }
        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 500), 0))
    }

    private fun stopRinging() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    /** 响满 10 分钟没人理：停铃，通知留着等他回来处理 */
    private fun ringTimedOut() {
        val a = current ?: return
        stopRinging()
        stopForeground(STOP_FOREGROUND_DETACH)
        getSystemService(NotificationManager::class.java)
            ?.notify(a.id.hashCode(), buildNotification(this, a, ringing = false))
        current = null
        stopSelf()
        Log.i(TAG, "响满 10 分钟，停铃 id=${a.id}")
    }

    private fun finishAll() {
        handler.removeCallbacks(timeout)
        stopRinging()
        current = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopRinging()
        super.onDestroy()
    }
}
