package com.hector.backontrack.reminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PendingReminder(
    val id: String,
    val taskId: String,
    val title: String,
    val body: String,
    val fireAtMs: Long,
    /** notify = 普通通知；alarm = 全屏闹钟，必须手动处理 */
    val style: String = "notify",
    /** 循环待办：闹钟上的「完成/跳过」要记日志并滚到下一次 */
    val recurring: Boolean = false,
    /**
     * 本地自己排的闹钟（目前只有「推迟 10 分钟」）。
     * 服务端根本不知道它存在，所以同步时**不能**按「服务端没返回 = 已废弃」把它取消。
     */
    val local: Boolean = false,
    /** 这条提醒对应的 deadline。循环待办点「完成」时告诉服务端完成的是哪一次；0 = 不知道 */
    val dueAtMs: Long = 0L,
)

/**
 * 提醒的本地缓存。
 *
 * 为什么不在开机时直接联网拉：开机那一刻网络往往还没就绪，
 * 拉失败就意味着这台手机上所有提醒永久消失。缓存到本地，
 * 开机广播直接从磁盘重建闹钟，不依赖网络。
 */
object ReminderStore {
    private const val PREFS = "bot_reminders"
    private const val KEY = "pending"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, items: List<PendingReminder>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject()
                .put("id", it.id).put("task_id", it.taskId)
                .put("title", it.title).put("body", it.body)
                .put("fire_at_ms", it.fireAtMs).put("style", it.style)
                .put("recurring", it.recurring).put("local", it.local)
                .put("due_at_ms", it.dueAtMs))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun load(ctx: Context): List<PendingReminder> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PendingReminder(
                id = o.getString("id"),
                taskId = o.getString("task_id"),
                title = o.getString("title"),
                body = o.getString("body"),
                fireAtMs = o.getLong("fire_at_ms"),
                style = o.optString("style", "notify"),
                recurring = o.optBoolean("recurring", false),
                local = o.optBoolean("local", false),
                dueAtMs = o.optLong("due_at_ms", 0L),
            )
        }
    } catch (_: Exception) { emptyList() }

    fun remove(ctx: Context, id: String) =
        save(ctx, load(ctx).filterNot { it.id == id })
}
