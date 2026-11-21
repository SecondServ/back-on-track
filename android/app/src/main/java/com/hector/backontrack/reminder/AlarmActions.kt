package com.hector.backontrack.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟上的三个按钮：完成 / 推迟 10 分钟 / 跳过。
 * 全屏界面和通知上的按钮走同一套逻辑，结果才一致。
 *
 *   循环待办 —— 完成和跳过都记一条日志，然后滚到下一次
 *   一次性   —— 完成就勾掉；跳过什么都不改，待办继续留着
 *   推迟     —— 本地立刻排一个 10 分钟后的闹钟，状态不动
 */
object AlarmActions {
    const val DONE = "com.hector.backontrack.ALARM_DONE"
    const val SNOOZE = "com.hector.backontrack.ALARM_SNOOZE"
    const val SKIP = "com.hector.backontrack.ALARM_SKIP"

    /** onDone：联网那部分跑完时调用。通知按钮那边靠它决定广播什么时候结束 */
    fun handle(ctx: Context, a: AlarmInfo, act: String, onDone: () -> Unit = {}) {
        val app = ctx.applicationContext
        AlarmService.stop(app, a.id)

        if (act == SNOOZE) {
            ReminderScheduler.snoozeLocally(app, a, 10)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Session.restoreInto(app)) {
                    when {
                        act == SNOOZE -> Unit
                        a.recurring -> {
                            Api.logOccurrence(a.taskId, if (act == DONE) "done" else "skipped")
                            Api.advanceRecurring(a.taskId, a.dueAtMs.takeIf { it > 0 })
                        }
                        act == DONE -> Api.setTaskStatus(a.taskId, "done")
                        else -> Unit
                    }
                    if (!a.id.contains("-snooze-")) Api.markReminderDelivered(a.id)
                    if (act != SNOOZE) ReminderScheduler.syncFromServer(app)
                }
            } catch (e: Exception) {
                Log.e("BotAlarm", "处理闹钟按钮失败 act=$act", e)
            } finally {
                onDone()
            }
        }
    }
}

/** 通知上的按钮点下去之后到这里 */
class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = AlarmInfo.from(intent) ?: return
        val act = intent.action ?: return
        val pending = goAsync()
        AlarmActions.handle(context, a, act) { pending.finish() }
    }
}
