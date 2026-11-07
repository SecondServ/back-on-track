package com.hector.backontrack.audio

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 笔记页里没送出去的录音。
 *
 * 主界面那条「新建记录」的路已经有 PendingQueue 兜底，但笔记页里的几条路
 * （追加一段、对笔记下指令、追问）以前失败了就 `file.delete()` ——
 * 一段说了十分钟的日记，碰上一次 403 就整段没了，只能从头再说。
 *
 * 现在规矩是：**录音只有在服务端确认收下之后才删。** 失败就挪到这里，
 * 挂在它所属的那条笔记下面；回到这条笔记会看到「重试 / 丢弃」。
 * 放 filesDir 而不是 cacheDir —— 后者系统随时会清。
 */
object FailedClips {

    /** 失败时本来要做的事，重试时照着再做一遍 */
    enum class Mode { Append, Command, Ask, AskFirst }

    data class Clip(val audio: File, val meta: File, val durationMs: Int, val mode: Mode)

    private fun dir(ctx: Context, captureId: String) =
        File(ctx.applicationContext.filesDir, "failed/$captureId").apply { mkdirs() }

    /** 把录音存下来。返回 false = 连落盘都失败了 */
    fun save(ctx: Context, captureId: String, source: File, durationMs: Int, mode: Mode): Boolean =
        try {
            val stamp = System.currentTimeMillis()
            val d = dir(ctx, captureId)
            source.copyTo(File(d, "$stamp.m4a"), overwrite = true)
            File(d, "$stamp.json").writeText(
                JSONObject().put("duration_ms", durationMs).put("mode", mode.name).toString())
            true
        } catch (_: Exception) { false }

    /** 这条笔记下没送出去的录音，按录制先后 */
    fun list(ctx: Context, captureId: String): List<Clip> =
        dir(ctx, captureId).listFiles { f -> f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.mapNotNull { meta ->
                val audio = File(meta.parentFile, meta.name.removeSuffix(".json") + ".m4a")
                if (!audio.exists()) { meta.delete(); return@mapNotNull null }
                try {
                    val o = JSONObject(meta.readText())
                    Clip(audio, meta, o.optInt("duration_ms", 0),
                        runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.Append))
                } catch (_: Exception) { null }
            } ?: emptyList()

    fun remove(clip: Clip) { clip.audio.delete(); clip.meta.delete() }
}
