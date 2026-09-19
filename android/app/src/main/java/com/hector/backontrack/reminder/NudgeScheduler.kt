package com.hector.backontrack.reminder

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hector.backontrack.AppPrefs
import com.hector.backontrack.MainActivity
import com.hector.backontrack.R
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Session
import com.hector.backontrack.widget.TasksWidget
import com.hector.backontrack.widget.WidgetCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 「还有几件事没做」的定期轻推。
 *
 * 待办和提醒事项是两种东西：设了时间的那些到点会响，而「回国后办张手机卡」
 * 这类没有时间点的，不推一把就会在列表里躺到天荒地老 —— 这正是 PRD 里
 * 「记下来了但不做」那一半痛点。
 *
 * 频率是全局的一个设置。试过给每条待办单独设频率，但那要求语音里能区分
 * 「这件事每天都要做」和「这件事催我勤一点」—— 两句话长得太像，模型分不清，
 * 于是把催促频率当成闹钟提醒来理解。少一个字段，这个歧义就不存在了。
 *
 * 闹钟用不精确的（setAndAllowWhileIdle）：早一小时晚一小时都无所谓，
 * 没必要去占精确闹钟的配额，也更省电。
 */
object NudgeScheduler {

    /** 固定 requestCode —— 待发的轻推永远只有一个 */
    private const val REQUEST = 0x4E55
    private const val NOTIFY_ID = 0x4E56
    /** 检查的时刻：上午 10 点，不会吵到早上和半夜 */
    private const val HOUR = 10

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQUEST,
        Intent(ctx, NudgeReceiver::class.java).setAction("com.hector.backontrack.NUDGE"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 按设置的间隔排下一次；设成「关闭」时顺手把已排的撤掉 */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(ctx)
        val days = AppPrefs.nudgeDays(ctx)
        if (days <= 0) { am.cancel(pi); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextFireAt(ctx, days), pi)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(ctx))
    }

    /**
     * 下一次推送的时刻：从上一次推送算起隔 days 天的上午 10 点。
     * 从没推过就从今天算 —— 装上 App 不该等一整个周期才看见第一条。
     */
    private fun nextFireAt(ctx: Context, days: Int): Long {
        val last = AppPrefs.nudgeLastAt(ctx)
        val cal = Calendar.getInstance().apply {
            if (last > 0) timeInMillis = last
            add(Calendar.DAY_OF_YEAR, if (last > 0) days else 1)
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // 算出来已经过去了（比如很久没开 App），顺延到明天，别立刻弹一条
        while (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    internal fun notifyPending(ctx: Context, titles: List<String>) {
        Notifications.ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, REQUEST,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = titles.take(5).joinToString("\n") { "· $it" } +
            if (titles.size > 5) "\n…还有 ${titles.size - 5} 条" else ""
        val n = Notification.Builder(ctx, Notifications.NUDGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("有 ${titles.size} 条待办还没做")
            .setContentText(titles.first())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIFY_ID, n)
    }

}

/**
 * 每天检查一次：哪些待办到了各自的催促周期。
 *
 * 每一步都打日志。它的失败路径全是静默的（没登录、没网、没有到点的待办
 * 都是「什么都不发生」），不打日志的话，"通知没弹" 这件事根本无从查起 ——
 * 这个项目已经在一模一样的坑里栽过一次。
 */
class NudgeReceiver : BroadcastReceiver() {

    companion object { const val TAG = "BotNudge" }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "轻推检查开始 action=${intent.action}")
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val signedIn = Session.restoreInto(context)
                Log.i(TAG, "登录态=$signedIn")
                if (signedIn) {
                    // 只管「没有闹钟」的那些 —— 设了时间的自己会响，再推一次是打扰
                    val all = Api.listOpenTasks()
                    // 每天这一趟本来就要拉待办，顺手让小组件也新鲜一次 ——
                    // 否则用户不开 App，桌面上那几行会一直是旧的
                    TasksWidget.rebuildCache(context)
                    TasksWidget.refresh(context)
                    val titles = all.filter { !it.wantsReminder }.map { it.title }
                    Log.i(TAG, "未完成 ${all.size} 条，其中没设闹钟的 ${titles.size} 条")
                    if (titles.isNotEmpty()) {
                        NudgeScheduler.notifyPending(context, titles)
                        Log.i(TAG, "已发出轻推通知")
                    }
                    AppPrefs.setNudgeLastAt(context, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Log.e(TAG, "轻推失败", e)
            } finally {
                // 不管成没成都要排下一次，否则这条链就此断掉
                NudgeScheduler.schedule(context)
                pending.finish()
            }
        }
    }
}
