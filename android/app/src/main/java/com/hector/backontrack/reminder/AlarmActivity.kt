package com.hector.backontrack.reminder

import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session
import com.hector.backontrack.ui.UiScale
import com.hector.backontrack.ui.theme.BackOnTrackTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Deadline 正点的全屏闹钟。
 *
 * 跟普通通知的区别是它**必须被手动处理** —— 会亮屏、盖在锁屏之上、持续响铃震动，
 * 直到用户点「完成」或「推迟」。到点这一刻如果只震一下就滑过去了，
 * 那这个 App 的核心承诺（说了它就会提醒你）就没兑现。
 */
class AlarmActivity : ComponentActivity() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏上直接显示并点亮屏幕，不需要先解锁
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val reminderId = intent.getStringExtra("id").orEmpty()
        val taskId = intent.getStringExtra("task_id").orEmpty()
        val title = intent.getStringExtra("title") ?: "待办提醒"
        val recurring = intent.getBooleanExtra("recurring", false)

        startAlarmSound()

        UiScale.ensureLoaded(this)
        setContent {
            BackOnTrackTheme {
                // 跟随主题，不再用 errorContainer（那是 Material 的「出错」红，
                // 深色主题下刺眼得像报警）。系统闹钟也是用自己的主题底色，
                // 靠大字号和响铃表达紧迫，不靠把整屏刷红。
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            SimpleDateFormat("HH:mm", Locale.US).format(Date()),
                            style = MaterialTheme.typography.displayLarge,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("到时间了", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(36.dp))
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(56.dp))

                        Button(
                            onClick = { act(taskId, reminderId, title, recurring, Act.Done) },
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                        ) { Text("已完成", style = MaterialTheme.typography.titleMedium) }

                        Spacer(Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { act(taskId, reminderId, title, recurring, Act.Snooze) },
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                        ) { Text("推迟 10 分钟") }

                        Spacer(Modifier.height(12.dp))

                        TextButton(
                            onClick = { act(taskId, reminderId, title, recurring, Act.Skip) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(if (recurring) "跳过这一次" else "跳过（保留这条待办）")
                        }
                    }
                }
            }
        }
    }

    private fun startAlarmSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setDataSource(this@AlarmActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) { /* 静音模式等情况，震动仍然有 */ }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 600, 500), 0)
        )
    }

    private fun stopAlarmSound() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        vibrator?.cancel()
    }

    private enum class Act { Done, Snooze, Skip }

    /**
     * 三个选项的落点：
     *   循环待办 —— 完成和跳过都记一条带时间戳的日志，然后滚到下一次
     *   一次性   —— 完成就勾掉；跳过什么都不改，待办继续留着
     */
    private fun act(
        taskId: String, reminderId: String, title: String, recurring: Boolean, what: Act,
    ) {
        stopAlarmSound()
        getSystemService(NotificationManager::class.java)?.cancel(reminderId.hashCode())

        if (what == Act.Snooze) {
            ReminderScheduler.snoozeLocally(applicationContext, reminderId, taskId, title, 10)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Session.restoreInto(applicationContext)) {
                    when {
                        what == Act.Snooze -> Unit   // 只是晚点再响，状态不动
                        recurring -> {
                            Api.logOccurrence(taskId, if (what == Act.Done) "done" else "skipped")
                            Api.advanceRecurring(taskId)
                        }
                        what == Act.Done -> Api.setTaskStatus(taskId, "done")
                        // 一次性待办跳过：什么都不改，它继续留在列表里
                        else -> Unit
                    }
                    Api.markReminderDelivered(reminderId)
                }
            } catch (_: Exception) {}
        }
        finish()
    }

    override fun onDestroy() {
        stopAlarmSound()
        super.onDestroy()
    }
}
