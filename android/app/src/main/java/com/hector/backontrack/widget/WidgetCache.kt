package com.hector.backontrack.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 小组件要显示的待办，缓存在本地。
 *
 * 小组件跑在桌面启动器的进程里，**不能也不该联网** —— 它没有登录态，
 * 也不该为了画几行字去发请求。所以由 App 在拉完待办列表时把结果写下来，
 * 小组件只读这份缓存。
 *
 * 存的是创建时间而不是「躺了几天」：天数会随着时间变，存进去第二天就不准了。
 */
object WidgetCache {
    private const val PREFS = "bot_widget"
    private const val KEY = "rows"
    /** 早期版本只存标题，键名是 titles。换格式时没清，留在设备上成了孤儿数据 */
    private const val LEGACY_KEY = "titles"

    /** createdAt 是 UTC 的 ISO 串，显示时现算「躺了多久」 */
    data class Row(
        /** 点圈勾掉时要拿它去改状态，所以缓存里必须带上 */
        val id: String,
        val title: String,
        val priority: String,
        val createdAt: String?,
        /** 详情弹窗要显示它 */
        val dueAt: String? = null,
        /**
         * 已完成。它不会从小组件上消失，而是划着线沉到底部 ——
         * 点错了再点一下就能翻回来。
         * 点一下先把它画成「实心圈 + 对勾」，等服务端确认后再消失 ——
         * 直接消失的话，点下去只看见东西没了，不像「勾上了」。
         */
        val done: Boolean = false,
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, rows: List<Row>) {
        val arr = JSONArray()
        rows.forEach {
            arr.put(JSONObject()
                .put("i", it.id)
                .put("t", it.title)
                .put("p", it.priority)
                .put("c", it.createdAt ?: JSONObject.NULL)
                .put("u", it.dueAt ?: JSONObject.NULL)
                .put("d", it.done))
        }
        prefs(ctx).edit()
            .putString(KEY, arr.toString())
            .remove(LEGACY_KEY)
            .apply()
    }

    fun load(ctx: Context): List<Row> = try {
        val arr = JSONArray(prefs(ctx).getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Row(
                id = o.optString("i"),
                title = o.optString("t"),
                priority = o.optString("p", "medium"),
                createdAt = if (o.isNull("c")) null else o.optString("c"),
                dueAt = if (o.isNull("u")) null else o.optString("u"),
                done = o.optBoolean("d", false),
            )
        }
    } catch (_: Exception) { emptyList() }

    /** 躺了多久。当天记的就说「今天」，免得一律显示「0 天」 */
    fun ageLabel(createdAt: String?): String {
        val ms = parseIso(createdAt) ?: return ""
        val days = ((System.currentTimeMillis() - ms) / 86_400_000L).toInt()
        return if (days <= 0) "今天" else "$days 天"
    }

    private fun parseIso(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching {
            val body = iso.substringBefore('.').removeSuffix("Z").replace("+00:00", "")
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(body)!!.time
        }.getOrNull()
    }
}
