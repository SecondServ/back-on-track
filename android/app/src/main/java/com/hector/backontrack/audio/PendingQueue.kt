package com.hector.backontrack.audio

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * 没网时的录音队列。
 *
 * 原来上传失败就直接 `file.delete()` —— 在地铁上、信号差的地方说的那段话，
 * 松手那一刻就没了，只留一句「处理失败」。这违背了这个 App 存在的理由
 * （「想到了记不下来」），所以录音一旦录下来就必须留住：先落盘，联网再补传。
 *
 * 放 filesDir 而不是 cacheDir：cacheDir 系统随时可以清掉，
 * 而这里躺着的是用户还没来得及被保存的话，清了就真没了。
 */
object PendingQueue {

    private fun dir(ctx: Context) =
        File(ctx.applicationContext.filesDir, "pending").apply { mkdirs() }

    /**
     * 一条待上传的录音。
     *
     * createdAt 记的是**说话的时刻**，不是上传的时刻 —— 补传时必须把它带给服务端，
     * 否则离线两小时攒下的几条会全部挤在恢复联网的那一秒，时间线就乱了。
     */
    data class Item(
        val audio: File,
        val meta: File,
        val durationMs: Int,
        val forcedKind: String?,
        val createdAt: String,
        val tz: String,
    )

    /** 把录音挪进队列。返回 false 表示连落盘都失败了，这时才真的没救 */
    fun enqueue(ctx: Context, source: File, durationMs: Int, forcedKind: String?): Boolean = try {
        val stamp = System.currentTimeMillis()
        val audio = File(dir(ctx), "$stamp.m4a")
        source.copyTo(audio, overwrite = true)
        val meta = File(dir(ctx), "$stamp.json")
        meta.writeText(
            JSONObject()
                .put("duration_ms", durationMs)
                .put("forced_kind", forcedKind ?: JSONObject.NULL)
                .put("created_at", Instant.now().toString())
                .put("tz", java.util.TimeZone.getDefault().id)
                .toString()
        )
        true
    } catch (e: Exception) {
        false
    }

    /** 按说话时间从早到晚 —— 补传要保持原来的顺序 */
    fun list(ctx: Context): List<Item> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.mapNotNull { meta ->
                try {
                    val audio = File(meta.parentFile, meta.name.removeSuffix(".json") + ".m4a")
                    if (!audio.exists()) { meta.delete(); return@mapNotNull null }
                    val o = JSONObject(meta.readText())
                    Item(
                        audio = audio,
                        meta = meta,
                        durationMs = o.optInt("duration_ms", 0),
                        forcedKind = if (o.isNull("forced_kind")) null
                        else o.optString("forced_kind").ifEmpty { null },
                        createdAt = o.optString("created_at"),
                        tz = o.optString("tz", java.util.TimeZone.getDefault().id),
                    )
                } catch (e: Exception) {
                    meta.delete(); null
                }
            } ?: emptyList()

    fun remove(item: Item) {
        item.audio.delete(); item.meta.delete()
    }

    fun count(ctx: Context): Int =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.size ?: 0
}
