package com.hector.backontrack.net

import com.hector.backontrack.Config
import com.hector.backontrack.reminder.PendingReminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ApiException(message: String) : Exception(message)

/** 一条语音记录 */
data class Capture(
    val id: String,
    val summary: String?,
    val correctedText: String?,
    val finalText: String?,
    val kind: String?,
    val asrStatus: String,
    val asrError: String?,
    val audioPath: String?,
    val createdAt: String,
    val deletedAt: String? = null,
    /**
     * 这条**说的是哪天的事**（yyyy-MM-dd）。null = 就是录音当天。
     * 饮食和日记经常隔天补记，用录音时间归位会串日期
     */
    val loggedOn: String? = null,
)

/** 记录里追加的一段语音 */
data class Segment(
    val id: String,
    val audioPath: String?,
    val finalText: String?,
    val correctedText: String?,
    val asrStatus: String,
    val createdAt: String,
)

/** 「问问题」笔记里的一轮对话。role = user（我问的）或 assistant（AI 答的） */
data class QaTurn(val role: String, val text: String, val createdAt: String)

/** 对着一条笔记下的一次指令，以及 AI 给回来的结果 */
data class NoteOutput(
    val id: String,
    val instruction: String,
    val text: String,
    val createdAt: String,
    /** 能不能拿去覆盖原文。整理/美化/翻译可以；调研那种是报告，不能 */
    val replaceable: Boolean = false,
)

/** 循环待办的一次打卡：某个时间点上完成了还是跳过了 */
data class Occurrence(val occurredAt: String, val outcome: String)

/** 用户档案：body 给 LLM 当背景，glossary 给 ASR 当听写提示 */
data class Profile(
    val body: String,
    val glossary: List<String>,
    /** AI 从日常记录里攒出来的那一半。只读 —— 用户的编辑绝不写进这里 */
    val aiBody: String = "",
)

/** 一条待办 */
data class Task(
    val id: String,
    val captureId: String?,
    val title: String,
    val dueAt: String?,
    val dueIsFuzzy: Boolean,
    val priority: String,
    val status: String,
    val snoozeCount: Int,
    val createdAt: String? = null,
    val completedAt: String? = null,
    /** 到点要不要响铃。false = 只是记一下的待办 */
    val wantsReminder: Boolean = false,
    /** null = 一次性；daily / weekly / monthly = 循环 */
    val recurKind: String? = null,
    val recurInterval: Int = 1,
    val recurWeekdays: List<Int>? = null,
    /** 库里还留着这一列，留给将来的「计划」category；目前恒为 true */
    val approved: Boolean = true,
)

/**
 * Supabase REST 客户端。
 * 刻意只用 HttpURLConnection + org.json（都是 Android 自带的）——
 * 我们需要的全是 JSON 或裸字节请求，加第三方库只会带来版本风险。
 */
object Api {

    @Volatile var accessToken: String? = null
    @Volatile var refreshToken: String? = null
    @Volatile var userId: String? = null
    @Volatile var appContext: android.content.Context? = null

    /**
     * 上一次换票是**被服务端拒了**（true），还是根本没连上（false）。
     *
     * 这两件事在结果上都是「没换成」，但含义天差地别：前者说明这套凭据真的作废了，
     * 后者过一会儿自己就好。清不清用户的登录态，全看这个区别。
     */
    @Volatile private var refreshRejected = false

    val isLoggedIn: Boolean get() = accessToken != null

    // ── 底层 ──────────────────────────────────────────────────────────────

    private fun open(
        path: String,
        method: String,
        contentType: String? = "application/json",
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val c = URL(Config.SUPABASE_URL + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        // 转录 + 分析要跑 10 秒上下，读超时必须给够
        c.readTimeout = 120_000
        c.setRequestProperty("apikey", Config.SUPABASE_ANON_KEY)
        c.setRequestProperty(
            "Authorization", "Bearer " + (accessToken ?: Config.SUPABASE_ANON_KEY))
        contentType?.let { c.setRequestProperty("Content-Type", it) }
        extraHeaders.forEach { (k, v) -> c.setRequestProperty(k, v) }
        return c
    }

    private fun HttpURLConnection.finish(): String {
        val code = responseCode
        val body = (if (code in 200..299) inputStream else errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        disconnect()
        if (code !in 200..299) throw ApiException("HTTP $code: ${body.take(400)}")
        return body
    }

    private suspend fun request(
        path: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = "application/json",
        extraHeaders: Map<String, String> = emptyMap(),
        allowRetry: Boolean = true,
    ): String = withContext(Dispatchers.IO) {
        try {
            val c = open(path, method, contentType, extraHeaders)
            body?.let { c.doOutput = true; c.outputStream.use { os -> os.write(it) } }
            c.finish()
        } catch (e: ApiException) {
            // access token 只有 1 小时寿命。过期就用 refresh token 换新的再试一次，
            // 否则用户每隔一小时就得重新登录 —— 对天天用的工具是不可接受的。
            if (allowRetry && e.message?.startsWith("HTTP 401") == true && refreshSession()) {
                val c2 = open(path, method, contentType, extraHeaders)
                body?.let { c2.doOutput = true; c2.outputStream.use { os -> os.write(it) } }
                c2.finish()
            } else throw e
        }
    }

    /** 用 refresh token 换一套新的。成功返回 true */
    private fun refreshSession(): Boolean {
        val rt = refreshToken ?: return false
        return try {
            val saved = accessToken
            accessToken = null   // 换票时不要带上过期的
            val c = open("/auth/v1/token?grant_type=refresh_token", "POST")
            c.doOutput = true
            c.outputStream.use {
                it.write(JSONObject().put("refresh_token", rt).toString().toByteArray())
            }
            val res = JSONObject(c.finish())
            accessToken = res.getString("access_token")
            refreshToken = if (res.isNull("refresh_token")) rt
                           else res.getString("refresh_token")
            userId = res.optJSONObject("user")?.optString("id") ?: userId
            appContext?.let { ctx ->
                Session.save(ctx, accessToken!!, refreshToken, userId ?: "")
            }
            refreshRejected = false
            true
        } catch (e: Exception) {
            // 只有服务端用 4xx 明确回绝，才算这张票真的废了；
            // 连不上、超时、5xx 都不是用户的错，不该让他重新登录
            refreshRejected = e is ApiException &&
                e.message?.startsWith("HTTP 4") == true
            false
        }
    }

    // ── 登录 ──────────────────────────────────────────────────────────────

    suspend fun signIn(email: String, password: String) {
        val payload = JSONObject()
            .put("email", email).put("password", password)
            .toString().toByteArray()
        // 登录时还没有 token，临时清空以免带上过期的
        accessToken = null
        val res = JSONObject(
            request("/auth/v1/token?grant_type=password", "POST", payload, allowRetry = false))
        accessToken = res.getString("access_token")
        refreshToken = if (res.isNull("refresh_token")) null
                       else res.getString("refresh_token")
        userId = res.getJSONObject("user").getString("id")
    }

    fun signOut() { accessToken = null; refreshToken = null; userId = null }

    /** 恢复登录态的三种结局。**只有 REJECTED 才该清掉凭据。** */
    enum class RestoreResult {
        /** 服务端认了这套凭据 */
        OK,
        /** 服务端明确说凭据无效 —— 只有这种情况才该请他重新登录 */
        REJECTED,
        /** 没网、超时、服务端 5xx —— 凭据多半还是好的，别动它 */
        OFFLINE,
    }

    /**
     * 用已保存的 token 恢复会话。
     *
     * 以前这里返回 Boolean，而调用方把 false 一律当成「凭据作废」去清磁盘。
     * 问题出在 catch 把**所有**异常都算作 false：没网、DNS 解析不了、超时、
     * 服务端 5xx，全都等同于「你的密码不对」。于是一次网络抖动就把人踹回登录页，
     * refresh token 也一并被抹掉，只能重新输密码。这个 bug 咬过好几次。
     *
     * 现在只有服务端明确拒绝才清；其余一律放行照常用 ——
     * 列表本来就会显示上次缓存的内容，真有问题也能在设置里手动登出。
     */
    suspend fun restore(token: String, refresh: String?): RestoreResult {
        accessToken = token
        refreshToken = refresh
        // 断网进来时也得有 user_id：很多地方拿它拼路径，
        // 没有的话虽然「登录着」却什么都干不了
        appContext?.let { ctx -> userId = Session.uid(ctx) ?: userId }
        return try {
            userId = JSONObject(request("/auth/v1/user", "GET")).getString("id")
            RestoreResult.OK
        } catch (e: ApiException) {
            // 401 有两种来路：换票时服务端明确回绝（凭据真废了），
            // 或者换票那一下压根没连上（refreshRejected 为 false）——后者不能清。
            // 连 refresh token 都没有的话，401 就只能靠重新登录解决了。
            val unauthorized = e.message?.startsWith("HTTP 401") == true ||
                e.message?.startsWith("HTTP 403") == true
            if (unauthorized && (refresh == null || refreshRejected)) {
                accessToken = null; refreshToken = null; userId = null
                RestoreResult.REJECTED
            } else {
                RestoreResult.OFFLINE
            }
        } catch (_: Exception) {
            // IOException 这一类：没网、DNS、超时
            RestoreResult.OFFLINE
        }
    }

    // ── 上传并处理一条录音 ────────────────────────────────────────────────

    /**
     * 第一步：上传音频 + 建 capture 记录。几百毫秒就能回。
     * 拆成两步是为了让界面能立刻把条目显示出来（状态「转写中」），
     * 而不是干等十秒才看到东西。
     */
    suspend fun uploadCapture(
        file: File, durationMs: Int, forcedKind: String? = null,
        /**
         * 说这段话的时刻。补传离线攒下的录音时必须带上 ——
         * 不带的话数据库用 now()，离线两小时的几条会全挤在恢复联网的那一秒。
         */
        createdAt: String? = null,
        clientTz: String? = null,
    ): String {
        val uid = userId ?: throw ApiException("未登录")
        val captureId = java.util.UUID.randomUUID().toString()
        // 路径必须是 {user_id}/xxx —— Storage 的权限规则靠第一层文件夹判断归属
        val objectPath = "$uid/$captureId.m4a"

        request(
            "/storage/v1/object/${Config.AUDIO_BUCKET}/$objectPath",
            "POST", file.readBytes(), "audio/m4a",
        )

        val row = JSONObject()
            .put("id", captureId)
            .put("user_id", uid)
            .put("audio_path", objectPath)
            .put("duration_ms", durationMs)
            .put("source", "android")
            .put("client_tz", clientTz ?: java.util.TimeZone.getDefault().id)
        if (createdAt != null) row.put("created_at", createdAt)
        if (forcedKind != null) {
            // 用户滑到了「待办」—— 明确告诉服务端别再自己判断
            row.put("kind", forcedKind).put("kind_is_manual", true)
        }
        request("/rest/v1/captures", "POST", JSONArray().put(row).toString().toByteArray())
        return captureId
    }

    /** 第二步：触发服务端处理（转录 + 分析）。约 10 秒 */
    suspend fun processCapture(captureId: String) {
        request(
            "/functions/v1/process-capture", "POST",
            JSONObject().put("capture_id", captureId).toString().toByteArray(),
        )
    }

    suspend fun getCapture(id: String): Capture? =
        parseCaptures(request("/rest/v1/captures?id=eq.$id&select=$CAPTURE_FIELDS", "GET"))
            .firstOrNull()

    // ── 列表 ──────────────────────────────────────────────────────────────

    private const val CAPTURE_FIELDS =
        "id,summary,corrected_text,final_text,kind,asr_status,asr_error,audio_path," +
            "created_at,deleted_at,logged_on"

    private fun parseCaptures(body: String): List<Capture> {
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun str(k: String) = if (o.isNull(k)) null else o.getString(k)
            Capture(
                id = o.getString("id"),
                summary = str("summary"),
                correctedText = str("corrected_text"),
                finalText = str("final_text"),
                kind = str("kind"),
                asrStatus = o.optString("asr_status", "pending"),
                asrError = str("asr_error"),
                audioPath = str("audio_path"),
                createdAt = o.getString("created_at"),
                deletedAt = str("deleted_at"),
                loggedOn = str("logged_on"),
            )
        }
    }

    /**
     * 「记录」列表。
     *
     * 刻意排除 kind=task —— 待办只该出现在待办页，混在记录里会把真正想回看的
     * 灵感和笔记淹掉。这些语音本身没丢：从待办点进去就能回到原始录音和转录。
     */
    suspend fun listCaptures(limit: Int = 50, newestFirst: Boolean = true): List<Capture> =
        parseCaptures(request("/rest/v1/captures?select=$CAPTURE_FIELDS" +
            // 分析失败的也要放行：kind=task 但一条待办都没生成的记录，
            // 「记录」里被 kind 过滤掉、「待办」里又没有，会彻底消失在缝里
            "&or=(kind.is.null,kind.neq.task," +
            "and(corrected_text.is.null,asr_error.not.is.null))" +
            "&deleted_at=is.null" +
            "&order=created_at." + (if (newestFirst) "desc" else "asc") +
            "&limit=$limit", "GET"))

    /**
     * 手动改分类。AI 会判错，用户说了算 —— kind_is_manual 一置上，
     * 之后再处理这条语音时 AI 也不会把它改回去。
     * 改成「待办」时顺手补一条待办，否则它在「记录」里被过滤掉、
     * 「待办」里又没有，就哪儿都看不见了。
     */
    /** 改这条笔记的归属日期。传 null = 回到「就是录音当天」 */
    suspend fun setLoggedOn(captureId: String, date: String?) {
        request("/rest/v1/captures?id=eq.$captureId", "PATCH",
            JSONObject().put("logged_on", date ?: JSONObject.NULL)
                .toString().toByteArray())
    }

    suspend fun setCaptureKind(capture: Capture, kind: String) {
        request("/rest/v1/captures?id=eq.${capture.id}", "PATCH",
            JSONObject().put("kind", kind).put("kind_is_manual", true)
                .toString().toByteArray())

        if (kind != "task") return
        val existing = JSONArray(request(
            "/rest/v1/tasks?capture_id=eq.${capture.id}&select=id", "GET"))
        if (existing.length() > 0) return
        val title = capture.summary?.takeIf { it.isNotBlank() }
            ?: (capture.correctedText ?: capture.finalText ?: "").take(30)
        request("/rest/v1/tasks", "POST", JSONArray().put(
            JSONObject()
                .put("user_id", userId)
                .put("capture_id", capture.id)
                .put("title", title.ifBlank { "（无标题）" })
                .put("priority", "medium")
                .put("status", "open")
                .put("wants_reminder", false)
        ).toString().toByteArray())
    }

    /** 某条语音产生的待办 */
    suspend fun tasksForCapture(captureId: String): List<Task> =
        parseTasks(request(
            "/rest/v1/tasks?capture_id=eq.$captureId&select=$TASK_FIELDS&order=created_at", "GET"))

    // ── 提醒 ──────────────────────────────────────────────────────────────

    /** 拉取尚未发出、且还没到点的提醒（连带任务标题） */
    suspend fun listPendingReminders(): List<PendingReminder> {
        val nowIso = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US
        ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

        // PostgREST 的内嵌查询：一次把 task 的标题带出来，省一次往返
        val body = request(
            "/rest/v1/reminders?select=id,task_id,fire_at,body,style,tasks(title,status,recur_kind)" +
                "&delivered_at=is.null&fire_at=gte.$nowIso&order=fire_at", "GET")

        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val task = o.optJSONObject("tasks")
            // 任务已完成/已放弃的，提醒就别响了
            if (task == null || task.optString("status") != "open") return@mapNotNull null
            PendingReminder(
                id = o.getString("id"),
                taskId = o.getString("task_id"),
                title = task.optString("title", "待办提醒"),
                body = if (o.isNull("body")) "" else o.getString("body"),
                fireAtMs = parseIso(o.getString("fire_at")),
                style = o.optString("style", "notify"),
                recurring = !task.isNull("recur_kind"),
            )
        }
    }

    // ── 循环待办的完成日志 ────────────────────────────────────────────────

    /** 记一次「完成」或「跳过」。循环待办要能回看每天到底做没做 */
    suspend fun logOccurrence(taskId: String, outcome: String) {
        request("/rest/v1/task_occurrences", "POST", JSONArray().put(
            JSONObject()
                .put("user_id", userId)
                .put("task_id", taskId)
                .put("occurred_at", isoNow())
                .put("outcome", outcome)
        ).toString().toByteArray())
    }

    /** 一次把多条循环待办最近的结果拉回来，按任务分组 */
    suspend fun recentOutcomes(taskIds: List<String>, perTask: Int = 7): Map<String, List<String>> {
        if (taskIds.isEmpty()) return emptyMap()
        val body = request(
            "/rest/v1/task_occurrences?task_id=in.(${taskIds.joinToString(",")})" +
                "&select=task_id,outcome&order=occurred_at.desc&limit=200", "GET")
        val arr = JSONArray(body)
        val out = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val list = out.getOrPut(o.getString("task_id")) { mutableListOf() }
            if (list.size < perTask) list.add(o.getString("outcome"))
        }
        return out
    }

    suspend fun markReminderDelivered(id: String) {
        val nowIso = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US
        ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        request("/rest/v1/reminders?id=eq.$id", "PATCH",
            JSONObject().put("delivered_at", nowIso).toString().toByteArray())
    }

    /** Postgres 返回的时间戳形如 2026-09-11T16:00:00+00:00 */
    private fun parseIso(v: String): Long = try {
        val cleaned = v.replace("Z", "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        val pattern = if (cleaned.contains('.'))
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ" else "yyyy-MM-dd'T'HH:mm:ssZ"
        java.text.SimpleDateFormat(pattern, java.util.Locale.US).parse(cleaned)!!.time
    } catch (_: Exception) { 0L }

    // ── 待办 ──────────────────────────────────────────────────────────────

    private const val TASK_FIELDS =
        "id,capture_id,title,due_at,due_is_fuzzy,priority,status,snooze_count,created_at," +
            "completed_at,wants_reminder,recur_kind,recur_interval,recur_weekdays,approved"

    private fun parseTasks(body: String): List<Task> {
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun str(k: String) = if (o.isNull(k)) null else o.getString(k)
            Task(
                id = o.getString("id"),
                captureId = str("capture_id"),
                title = o.optString("title", ""),
                dueAt = str("due_at"),
                dueIsFuzzy = o.optBoolean("due_is_fuzzy", false),
                priority = o.optString("priority", "medium"),
                status = o.optString("status", "open"),
                snoozeCount = o.optInt("snooze_count", 0),
                createdAt = str("created_at"),
                completedAt = str("completed_at"),
                wantsReminder = o.optBoolean("wants_reminder", false),
                recurKind = str("recur_kind"),
                recurInterval = o.optInt("recur_interval", 1),
                recurWeekdays = o.optJSONArray("recur_weekdays")?.let { a ->
                    (0 until a.length()).map { a.getInt(it) }
                },
                // 老数据没有这一列，默认当成已确认
                approved = o.optBoolean("approved", true),
            )
        }
    }

    /**
     * 待办列表。未完成的按 deadline 升序（没有 deadline 的排最后），
     * 这就是 PRD 说的「结合目前所有待办进行排列」的最小实现。
     */
    suspend fun listTasks(includeDone: Boolean = false): List<Task> {
        val filter = if (includeDone) "" else "&status=eq.open"
        return parseTasks(request(
            "/rest/v1/tasks?select=$TASK_FIELDS$filter" +
                "&order=due_at.asc.nullslast,priority.desc,created_at.desc&limit=200", "GET"))
    }

    /** 未完成的待办，按 deadline 升序（没有 deadline 的排最后） */
    suspend fun listOpenTasks(): List<Task> = parseTasks(request(
        "/rest/v1/tasks?select=$TASK_FIELDS&status=eq.open&deleted_at=is.null" +
            "&order=due_at.asc.nullslast,priority.desc,created_at.desc&limit=200", "GET"))

    /** 最近 days 天内完成的待办，按完成时间倒序 —— 划掉但留着，方便回看做了什么 */
    suspend fun listDoneTasksSince(days: Int): List<Task> {
        val since = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(System.currentTimeMillis() - days * 86_400_000L))
        return parseTasks(request(
            "/rest/v1/tasks?select=$TASK_FIELDS&status=eq.done&completed_at=gte.$since" +
                "&deleted_at=is.null" +
                "&order=completed_at.desc&limit=200", "GET"))
    }

    suspend fun getTask(taskId: String): Task? = parseTasks(request(
        "/rest/v1/tasks?id=eq.$taskId&select=$TASK_FIELDS", "GET")).firstOrNull()

    /**
     * 打卡记录：这条循环待办每次提醒之后到底做没做。
     * 按时间倒序，用来回看，也用来算完成率 / 跳过率。
     */
    suspend fun listOccurrences(taskId: String, limit: Int = 90): List<Occurrence> {
        val arr = JSONArray(request(
            "/rest/v1/task_occurrences?task_id=eq.$taskId&select=occurred_at,outcome" +
                "&order=occurred_at.desc&limit=$limit", "GET"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Occurrence(o.getString("occurred_at"), o.getString("outcome"))
        }
    }

    /**
     * 详情页里改完一条待办。
     *
     * 改完必须重排提醒：时间、频率、开关任何一个动了，
     * 旧提醒都还挂在原来的时间点上 —— 不重排就是「改了但还按老时间响」。
     */
    suspend fun updateTask(
        taskId: String,
        title: String,
        priority: String,
        dueAtIso: String?,
        wantsReminder: Boolean,
        recurKind: String?,
        recurInterval: Int = 1,
        recurWeekdays: List<Int>? = null,
    ) {
        val patch = JSONObject()
            .put("title", title)
            .put("priority", priority)
            .put("due_at", dueAtIso ?: JSONObject.NULL)
            .put("wants_reminder", wantsReminder)
            .put("recur_kind", recurKind ?: JSONObject.NULL)
            .put("recur_interval", recurInterval)
            .put("recur_weekdays",
                if (recurWeekdays.isNullOrEmpty()) JSONObject.NULL else JSONArray(recurWeekdays))
        request("/rest/v1/tasks?id=eq.$taskId", "PATCH", patch.toString().toByteArray())
        rescheduleTask(taskId)
    }

    /**
     * 「问问题」笔记：**不保留录音**，只存转写出来的文字。
     *
     * 所以它不走 process-capture（那条管线是给「把随口说的话整理成结构化数据」用的）：
     * 音频在 transcribeAudio 里转完就删了，这里直接把文字落成一条 ask 记录。
     */
    suspend fun createAskCapture(question: String): String {
        val uid = userId ?: throw ApiException("未登录")
        val id = java.util.UUID.randomUUID().toString()
        request("/rest/v1/captures", "POST", JSONArray().put(
            JSONObject()
                .put("id", id)
                .put("user_id", uid)
                .put("kind", "ask")
                .put("kind_is_manual", true)   // 用户明说了这是提问，AI 不要改判
                .put("final_text", question)
                .put("corrected_text", question)
                .put("summary", question.take(24))
                .put("asr_status", "done")
                .put("source", "android")
                .put("client_tz", java.util.TimeZone.getDefault().id)
        ).toString().toByteArray())
        addQaTurn(id, "user", question)
        return id
    }

    /**
     * 先占个位，文字随后补。
     *
     * 为的是「说完立刻进对话页」：转写要几秒，不能让人对着主界面干等 ——
     * 先把笔记建出来跳进去，转写好了再回填。
     */
    suspend fun createAskPlaceholder(): String {
        val uid = userId ?: throw ApiException("未登录")
        val id = java.util.UUID.randomUUID().toString()
        request("/rest/v1/captures", "POST", JSONArray().put(
            JSONObject()
                .put("id", id)
                .put("user_id", uid)
                .put("kind", "ask")
                .put("kind_is_manual", true)
                .put("summary", "语音识别中…")
                .put("asr_status", "done")
                .put("source", "android")
                .put("client_tz", java.util.TimeZone.getDefault().id)
        ).toString().toByteArray())
        return id
    }

    /** 转写好了：回填笔记正文，并把这一轮记成「我问」 */
    suspend fun fillAskQuestion(captureId: String, question: String) {
        request("/rest/v1/captures?id=eq.$captureId", "PATCH",
            JSONObject()
                .put("final_text", question)
                .put("corrected_text", question)
                .put("summary", question.take(24))
                .toString().toByteArray())
        addQaTurn(captureId, "user", question)
    }

    suspend fun addQaTurn(captureId: String, role: String, text: String) {
        val uid = userId ?: throw ApiException("未登录")
        request("/rest/v1/qa_turns", "POST", JSONArray().put(
            JSONObject()
                .put("user_id", uid)
                .put("capture_id", captureId)
                .put("role", role)
                .put("text", text)
        ).toString().toByteArray())
    }

    suspend fun listQaTurns(captureId: String): List<QaTurn> {
        val arr = JSONArray(request(
            "/rest/v1/qa_turns?capture_id=eq.$captureId" +
                "&select=role,text,created_at&order=created_at", "GET"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            QaTurn(o.getString("role"), o.getString("text"), o.optString("created_at"))
        }
    }

    /**
     * 让服务端带着整条对话去问 LLM，并把回答写进 qa_turns。
     *
     * deep = 深度思考：让模型多想一会儿。默认关着，因为随口一问等不了那么久。
     *
     * 返回服务端实际用的是哪家（openai / anthropic）—— 客户端没有 key，
     * 猜不出来，而头像要照它来挑。
     */
    suspend fun answerQuestion(captureId: String, deep: Boolean = false): String? {
        val res = request("/functions/v1/answer-question", "POST",
            JSONObject().put("capture_id", captureId).put("deep", deep)
                .toString().toByteArray())
        return JSONObject(res).optString("provider").takeIf { it.isNotBlank() }
    }

    /**
     * 对着一条笔记下口头指令（整理 / 扩写 / 调研…），结果存成单独一条。
     *
     * **正文一个字都不动** —— 产出挂在 note_outputs 里、显示在笔记下面。
     * AI 的东西再有用也不该盖掉你自己说的话。
     */
    suspend fun runNoteCommand(
        captureId: String,
        /** 语音说出来的指令。跟 action 二选一 */
        instruction: String? = null,
        /** 笔记下面那几个按钮：tidy / polish / extra。提示词在服务端，这里只发动作名 */
        action: String? = null,
    ): NoteOutput {
        val body = JSONObject().put("capture_id", captureId)
        instruction?.let { body.put("instruction", it) }
        action?.let { body.put("action", it) }
        val o = JSONObject(request("/functions/v1/note-command", "POST",
            body.toString().toByteArray()))
        return NoteOutput(
            o.optString("id"),
            o.optString("instruction").ifBlank { instruction ?: action.orEmpty() },
            o.optString("text"),
            o.optString("created_at"),
            o.optBoolean("replaceable", false),
        )
    }

    suspend fun listNoteOutputs(captureId: String): List<NoteOutput> {
        val arr = JSONArray(request(
            "/rest/v1/note_outputs?capture_id=eq.$captureId" +
                "&select=id,instruction,text,created_at,replaceable&order=created_at", "GET"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NoteOutput(o.getString("id"), o.getString("instruction"),
                o.getString("text"), o.optString("created_at"),
                o.optBoolean("replaceable", false))
        }
    }

    suspend fun deleteNoteOutput(id: String) {
        request("/rest/v1/note_outputs?id=eq.$id", "DELETE")
    }

    /**
     * 月度回顾。month 形如 "2026-09"，按**本地时区**切分月份边界。
     *
     * 服务端会缓存 —— 这个页面是拿来每天看一眼的，每次重算等于每天
     * 为同一个月付一次钱。force=true 才重新生成。
     */
    suspend fun recap(
        month: String, section: String, force: Boolean = false,
    ): JSONObject {
        val offsetMin = java.util.TimeZone.getDefault()
            .getOffset(System.currentTimeMillis()) / 60_000
        return JSONObject(request("/functions/v1/recap", "POST",
            JSONObject()
                .put("month", month)
                .put("section", section)
                .put("offset_minutes", offsetMin)
                .put("force", force)
                .toString().toByteArray()))
    }

    /** 手动新增一条待办（小组件的「＋」走这条）。默认中优先级、不设提醒 */
    suspend fun createTask(title: String) {
        val uid = userId ?: throw ApiException("未登录")
        request("/rest/v1/tasks", "POST", JSONArray().put(
            JSONObject()
                .put("user_id", uid)
                .put("title", title)
                .put("priority", "medium")
                .put("status", "open")
        ).toString().toByteArray())
    }

    /**
     * 删掉一条待办 —— 软删除。
     *
     * 不真删：循环待办身上挂着 task_occurrences（每次完成/跳过的打卡记录，
     * 是算完成率的原始数据），真删会级联掉整段历史。误删一条循环待办
     * 等于抹掉几个月的打卡，这个代价不能接受。
     */
    suspend fun trashTask(taskId: String) {
        request("/rest/v1/tasks?id=eq.$taskId", "PATCH",
            JSONObject().put("deleted_at", isoNow()).toString().toByteArray())
    }

    suspend fun restoreTask(taskId: String) {
        request("/rest/v1/tasks?id=eq.$taskId", "PATCH",
            JSONObject().put("deleted_at", JSONObject.NULL).toString().toByteArray())
    }

    /** 推迟到新的 deadline，同时把推迟次数 +1（PRD：长期未完成要能被察觉） */
    suspend fun snoozeTask(task: Task, newDueAtIso: String) {
        val patch = JSONObject()
            .put("due_at", newDueAtIso)
            .put("snooze_count", task.snoozeCount + 1)
            .put("last_snoozed_at", isoNow())
        request("/rest/v1/tasks?id=eq.${task.id}", "PATCH", patch.toString().toByteArray())
        // 只改 due_at 不够 —— 旧提醒还挂在原来的时间点，新的 deadline 不会响。
        // 让服务端按新时间重排整套提醒。
        rescheduleTask(task.id)
    }

    /**
     * 开关「到点提醒」。
     * 关掉时服务端会撤掉还没发出的提醒；打开时按 deadline 重新排一套。
     * dueAtIso 只在原来没有时间点、需要顺便设一个时才传。
     */
    suspend fun setTaskReminder(task: Task, wants: Boolean, dueAtIso: String? = null) {
        val patch = JSONObject().put("wants_reminder", wants)
        if (dueAtIso != null) patch.put("due_at", dueAtIso)
        request("/rest/v1/tasks?id=eq.${task.id}", "PATCH", patch.toString().toByteArray())
        rescheduleTask(task.id)
    }

    /**
     * 循环待办完成了这一次：让服务端把 due_at 滚到下一次，并重排提醒。
     * 它不进「已完成」—— 下周还要做的事，划掉了反而找不着。
     */
    suspend fun advanceRecurring(taskId: String) {
        request(
            "/functions/v1/reschedule-task", "POST",
            JSONObject().put("task_id", taskId).put("advance", true)
                .put("tz", java.util.TimeZone.getDefault().id)
                .toString().toByteArray(),
        )
    }

    /** 服务端按这条待办当前的 deadline 重排提醒（撤掉没发出的旧提醒） */
    suspend fun rescheduleTask(taskId: String) {
        request(
            "/functions/v1/reschedule-task", "POST",
            JSONObject().put("task_id", taskId)
                .put("tz", java.util.TimeZone.getDefault().id)
                .toString().toByteArray(),
        )
    }

    private fun isoNow(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US
    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    suspend fun setTaskStatus(taskId: String, status: String) {
        val patch = JSONObject().put("status", status)
        // 取消勾选时把完成时间清掉，否则它还会被当成「最近完成」
        if (status == "open") patch.put("completed_at", JSONObject.NULL)
        if (status == "done") {
            patch.put("completed_at", java.text.SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US
            ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date()))
        }
        request("/rest/v1/tasks?id=eq.$taskId", "PATCH", patch.toString().toByteArray())
    }

    // ── 垃圾箱 ────────────────────────────────────────────────────────────

    /** 移到垃圾箱（软删除），30 天内可以恢复 */
    suspend fun trashCapture(id: String) {
        request("/rest/v1/captures?id=eq.$id", "PATCH",
            JSONObject().put("deleted_at", isoNow()).toString().toByteArray())
    }

    suspend fun restoreCapture(id: String) {
        request("/rest/v1/captures?id=eq.$id", "PATCH",
            JSONObject().put("deleted_at", JSONObject.NULL).toString().toByteArray())
    }

    suspend fun listTrash(): List<Capture> = parseCaptures(request(
        "/rest/v1/captures?select=$CAPTURE_FIELDS&deleted_at=not.is.null" +
            "&order=deleted_at.desc&limit=500", "GET"))

    /**
     * 永久删除：录音文件、这条语音衍生出的灵感 / 饮食 / 笔记 / 选题，最后是它本身。
     * 它产生的待办保留 —— 那是独立的事，删一条笔记不该顺手删掉你要做的事。
     */
    suspend fun deleteCapturePermanently(c: Capture) {
        c.audioPath?.let { path ->
            // 文件删不掉（比如早就不在了）不影响删记录本身
            try { request("/storage/v1/object/${Config.AUDIO_BUCKET}/$path", "DELETE") }
            catch (_: Exception) {}
        }
        for (table in listOf("ideas", "meals", "notes", "content_pieces")) {
            request("/rest/v1/$table?capture_id=eq.${c.id}", "DELETE")
        }
        request("/rest/v1/captures?id=eq.${c.id}", "DELETE")
    }

    /** 清掉删除超过 days 天的。每次打开 App 顺手跑一次，不需要服务端定时任务 */
    suspend fun purgeExpiredTrash(days: Int): Int {
        val cutoff = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(System.currentTimeMillis() - days * 86_400_000L))
        val expired = parseCaptures(request(
            "/rest/v1/captures?select=$CAPTURE_FIELDS&deleted_at=lt.$cutoff&limit=500", "GET"))
        expired.forEach { deleteCapturePermanently(it) }
        return expired.size
    }

    // ── 记录的继续编辑 ────────────────────────────────────────────────────

    /** 这条记录后来追加的语音段（不含最初那段，它在 captures.audio_path 里） */
    suspend fun listSegments(captureId: String): List<Segment> {
        val body = request(
            "/rest/v1/capture_segments?capture_id=eq.$captureId" +
                "&select=id,audio_path,final_text,corrected_text,asr_status,created_at" +
                "&order=created_at", "GET")
        val arr = JSONArray(body)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun str(k: String) = if (o.isNull(k)) null else o.getString(k)
            Segment(
                id = o.getString("id"),
                audioPath = str("audio_path"),
                finalText = str("final_text"),
                correctedText = str("corrected_text"),
                asrStatus = o.optString("asr_status", "pending"),
                createdAt = o.getString("created_at"),
            )
        }
    }

    /**
     * 追加一段语音：上传 → 服务端转写并清洗 → 返回整理好的文字。
     * 插到哪里由界面决定（光标处），服务端不碰正文。
     */
    suspend fun appendVoiceSegment(captureId: String, file: File, durationMs: Int): String {
        val uid = userId ?: throw ApiException("未登录")
        val path = "$uid/$captureId-${java.util.UUID.randomUUID()}.m4a"
        request("/storage/v1/object/${Config.AUDIO_BUCKET}/$path",
            "POST", file.readBytes(), "audio/m4a")
        val res = request("/functions/v1/append-segment", "POST",
            JSONObject().put("capture_id", captureId).put("audio_path", path)
                .put("duration_ms", durationMs).toString().toByteArray())
        return JSONObject(res).optString("text", "")
    }

    /**
     * 写回正文 —— 手打改的，和追加语音后插进光标处的，都走这里。
     * 置上 text_is_manual，之后重新处理不会覆盖用户这一版。
     */
    suspend fun updateCaptureText(captureId: String, text: String) {
        request("/rest/v1/captures?id=eq.$captureId", "PATCH",
            JSONObject().put("corrected_text", text).put("text_is_manual", true)
                .toString().toByteArray())
    }

    /**
     * 只转写、不落库的语音输入。
     *
     * 档案这种地方没有记录可挂靠，所以音频转完就删 ——
     * 它没有归属，留在桶里只会变成谁都不敢动的垃圾。
     */
    suspend fun transcribeAudio(file: File, durationMs: Int): String {
        val uid = userId ?: throw ApiException("未登录")
        val path = "$uid/scratch-${java.util.UUID.randomUUID()}.m4a"
        request("/storage/v1/object/${Config.AUDIO_BUCKET}/$path", "POST",
            file.readBytes(), "audio/m4a")
        return try {
            val res = request("/functions/v1/transcribe-audio", "POST",
                JSONObject().put("audio_path", path)
                    .put("duration_ms", durationMs).toString().toByteArray())
            JSONObject(res).optString("text", "")
        } finally {
            try { request("/storage/v1/object/${Config.AUDIO_BUCKET}/$path", "DELETE") }
            catch (_: Exception) {}
        }
    }

    /** 没建过档案返回 null —— 空档案和「还没建」要能分开，决定保存时是插还是改 */
    suspend fun getProfile(): Profile? {
        val arr = JSONArray(
            request("/rest/v1/user_profile?select=body,glossary,ai_body&limit=1", "GET"))
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        val g = o.optJSONArray("glossary")
        return Profile(
            body = if (o.isNull("body")) "" else o.getString("body"),
            glossary = (0 until (g?.length() ?: 0)).map { g!!.getString(it) },
            aiBody = if (o.isNull("ai_body")) "" else o.getString("ai_body"),
        )
    }

    /** user_id 由数据库的 auth.uid() 默认值填，客户端不用也不该知道 */
    suspend fun saveProfile(p: Profile, create: Boolean) {
        val json = JSONObject()
            .put("body", p.body)
            .put("glossary", JSONArray(p.glossary))
            .toString().toByteArray()
        if (create) request("/rest/v1/user_profile", "POST", json)
        else request("/rest/v1/user_profile", "PATCH", json)
    }

    /** 下载音频用于回放 */
    suspend fun downloadAudio(objectPath: String, dest: File) = withContext(Dispatchers.IO) {
        val c = open("/storage/v1/object/${Config.AUDIO_BUCKET}/$objectPath", "GET", null)
        val code = c.responseCode
        if (code !in 200..299) { c.disconnect(); throw ApiException("HTTP $code") }
        c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        c.disconnect()
    }
}
