package com.hector.backontrack.reminder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hector.backontrack.ui.UiScale
import com.hector.backontrack.ui.theme.BackOnTrackTheme
import java.text.SimpleDateFormat
import java.util.*

/**
 * Deadline 正点的全屏闹钟界面。
 *
 * 跟普通通知的区别是它**必须被手动处理** —— 会亮屏、盖在锁屏之上，直到点「完成」或「推迟」。
 * 铃声和震动不在这里：由 AlarmService 在到点那一刻就开始放，界面开没开都在响。
 * 这里只负责显示和三个按钮。
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 锁屏上直接显示并点亮屏幕，不需要先解锁
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val alarm = AlarmInfo.from(intent) ?: run { finish(); return }

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
                            alarm.title,
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(56.dp))

                        Button(
                            onClick = { act(alarm, AlarmActions.DONE) },
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                        ) { Text("已完成", style = MaterialTheme.typography.titleMedium) }

                        Spacer(Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { act(alarm, AlarmActions.SNOOZE) },
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                        ) { Text("推迟 10 分钟") }

                        Spacer(Modifier.height(12.dp))

                        TextButton(
                            onClick = { act(alarm, AlarmActions.SKIP) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(if (alarm.recurring) "跳过这一次" else "跳过（保留这条待办）")
                        }
                    }
                }
            }
        }
    }

    private fun act(a: AlarmInfo, what: String) {
        AlarmActions.handle(this, a, what)
        finish()
    }
}
