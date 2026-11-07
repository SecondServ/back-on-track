package com.hector.backontrack.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hector.backontrack.AppPrefs
import com.hector.backontrack.R
import com.hector.backontrack.audio.FailedClips
import com.hector.backontrack.audio.Recorder
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import java.io.File
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Capture
import com.hector.backontrack.net.QaTurn
import com.hector.backontrack.net.Segment
import com.hector.backontrack.net.Task
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 这几类笔记里，原地松手 = 对 AI 下一条口头指令（整理 / 扩写 / 调研…）。
 *
 * 结果附在正文**下面**，原话一个字不动。只给「写下来是为了再加工」的那几类
 * 开这个口子：日记和饮食是流水记录，提问本身就是对话，都不需要。
 */
private val COMMAND_KINDS = setOf("idea", "memo", "note", "content")

/**
 * 只有饮食需要单独指定「说的是哪天的事」。
 *
 * 饮食经常隔天补记（昨天忘了记，今天顺口一起说），而且要按天汇总，日期错一天
 * 整张热力图就串位。日记不需要 —— 不是天天写，正文里说「昨天干了什么」
 * 本来就读得懂，末尾标一句「写于某天」就够了。
 */
private val DATED_KINDS = setOf("meal")

/** 「写于 2026年9月18日」—— 日记末尾那一行，用的是录音时间 */
private fun writtenOn(iso: String): String {
    val src = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val d = runCatching { src.parse(iso.take(19)) }.getOrNull() ?: return ""
    val cal = java.util.Calendar.getInstance().apply { time = d }
    return "写于 %d年%d月%d日".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
    )
}

/** yyyy-MM-dd → 「9月16日」；今天/昨天直接说人话 */
private fun loggedLabel(date: String): String {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val d = runCatching { f.parse(date) }.getOrNull() ?: return date
    val cal = java.util.Calendar.getInstance().apply { time = d }
    val today = java.util.Calendar.getInstance()
    val diff = ((today.timeInMillis - cal.timeInMillis) / 86_400_000L)
    val md = "%d月%d日".format(cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH))
    return when {
        today.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR) &&
            today.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) -> "今天"
        diff in 0..1 -> "昨天 $md"
        else -> md
    }
}

/**
 * 笔记下面常驻的预设指令。前两个各分类都有，第三个因题材而异。
 *
 * 备忘暂时只有两个 —— 第三个该是什么还没想好，宁可空着也不硬凑一个。
 */
private fun presetsFor(kind: String?): List<Pair<String, String>> = buildList {
    add("tidy" to "整理")
    add("polish" to "美化")
    when (kind) {
        "content" -> add("extra" to "英文版")
        "idea" -> add("extra" to "研究")
    }
}

/**
 * 一条语音的详情页。
 *
 * 记录不是「说完就定死」的：进来之后还能接着改 ——
 * 打字直接编辑正文，或者再录一段接到后面。
 * 语音做成一个列表（最初那段 + 后来追加的），默认收起来，
 * 因为大多数时候要看的是文字而不是重听。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CaptureDetailScreen(captureId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(context) }

    var capture by remember { mutableStateOf<Capture?>(null) }
    var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
    var segments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    // 「问问题」笔记的一问一答
    var turns by remember { mutableStateOf<List<QaTurn>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    var audioOpen by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    // 正文一直可编辑：draft 是屏幕上的内容，savedText 是服务端已有的内容。
    // 两者不等 = 有未保存的修改。
    var draft by remember { mutableStateOf<TextFieldValue?>(null) }
    var savedText by remember { mutableStateOf<String?>(null) }
    // 撤销 / 重做。自动保存会把误删立刻存上去，有它才找得回来
    val history = remember(captureId) { EditHistory() }
    // 没送出去、存在手机里等重试的录音
    var failed by remember { mutableStateOf<List<FailedClips.Clip>>(emptyList()) }
    var retrying by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    // 键盘：光标那一行始终要露在键盘上面
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottom > 0
    val cursorBringer = remember { BringIntoViewRequester() }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var editorFocused by remember { mutableStateOf(false) }
    var saveHint by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    /** 正在处理的那条指令。非空时底部显示占位卡 */
    var pending by remember { mutableStateOf<String?>(null) }
    /** 刚跑完一条 —— 用它触发结果落地后的那次滚动 */
    var justRan by remember { mutableStateOf(false) }
    // AI 按指令产出的东西。单独一串，跟正文井水不犯河水
    var outputs by remember { mutableStateOf<List<com.hector.backontrack.net.NoteOutput>>(emptyList()) }
    var recording by remember { mutableStateOf(false) }
    var peak by remember { mutableFloatStateOf(0f) }
    var levels by remember { mutableStateOf(listOf<Float>()) }
    // 提问笔记的两个中间态：语音转文字中 / AI 正在想。
    // 它们不再压在按钮上 —— 松手按钮就该回到「按住」，
    // 等待感属于对话本身，就像在微信里发出去一条消息之后那样。
    var transcribing by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    // 深度思考：默认关，跟 ChatGPT 一样由用户按需打开
    var deepThink by remember { mutableStateOf(AppPrefs.deepThink(context)) }
    // 头像认的是「服务端实际在用哪家的 key」，客户端猜不出来，由回答接口回报
    var aiProvider by remember { mutableStateOf(AppPrefs.aiProvider(context)) }
    // 正文的滚动位置提到这一层：对话是往下长的，新消息进来要能自己滚到底
    val scrollState = rememberScrollState()

    suspend fun load() {
        try {
            capture = Api.getCapture(captureId)
            tasks = Api.tasksForCapture(captureId)
            segments = Api.listSegments(captureId)
            if (capture?.kind in COMMAND_KINDS) {
                outputs = runCatching { Api.listNoteOutputs(captureId) }.getOrDefault(outputs)
            }
            if (capture?.kind == "ask") {
                turns = runCatching { Api.listQaTurns(captureId) }.getOrDefault(emptyList())
            }
            val server = capture?.correctedText
            // 用户正在改的内容不能被后台刷新冲掉；
            // 文字没变就别重建 TextFieldValue —— 否则光标会被弹回末尾，
            // 刚刚按光标位置插入的那一下就白做了。
            if (server != null && server != draft?.text &&
                (draft == null || draft?.text == savedText)
            ) {
                draft = TextFieldValue(server, TextRange(server.length))
            }
            savedText = server
        } catch (_: Exception) {
        } finally { loading = false }
    }

    /** 停手一会儿自动存，不用点保存按钮 */
    suspend fun saveNow() {
        val text = draft?.text ?: return
        if (text == savedText) return
        saveHint = "保存中…"
        try {
            Api.updateCaptureText(captureId, text)
            savedText = text
            saveHint = "已保存"
        } catch (e: Exception) {
            saveHint = "没存上：${e.message?.take(40)}"
        }
    }

    LaunchedEffect(captureId) {
        failed = FailedClips.list(context, captureId)
        load()
    }

    // 光标跟着键盘走：打字换行、键盘弹出 / 收起、语音插入之后，
    // 都把光标那一行（连同上下各一点）滚到看得见的地方。
    // imeBottom 进 key：键盘是一帧一帧升起来的，每帧都重算，最后一次落在键盘停稳之后。
    LaunchedEffect(draft?.selection, draft?.text, textLayout, imeBottom, editorFocused) {
        val d = draft ?: return@LaunchedEffect
        val l = textLayout ?: return@LaunchedEffect
        if (!editorFocused) return@LaunchedEffect
        if (l.layoutInput.text.text != d.text) return@LaunchedEffect   // 布局还没跟上新文字
        val r = l.getCursorRect(d.selection.end.coerceIn(0, d.text.length))
        val margin = with(density) { 56.dp.toPx() }
        cursorBringer.bringIntoView(Rect(r.left, r.top - margin / 2, r.right, r.bottom + margin))
    }

    // 占位笔记（先跳进来、后转写）此时还没有任何一轮，轮询等文字回填。
    // 顺便走 load()，把占位标题「语音识别中…」也换成真正的问题。
    // capture?.kind 必须进 key。第一次组合时 capture 还是 null（load() 没回来），
    // 条件不成立就直接退出了；而 key 里没有 capture，它再也不会重跑 ——
    // 于是这个专为占位笔记写的轮询，在占位笔记上从来没启动过。
    //
    // 次数也要够：转写慢的时候（长句 + 上传）轮询先放弃，页面就永远停在
    // 「正在转文字」，直到手动退出重进。5 分钟够任何一次转写跑完。
    LaunchedEffect(captureId, capture?.kind, turns.isEmpty()) {
        if (capture?.kind == "ask" && turns.isEmpty()) {
            repeat(200) {
                delay(1500)
                // 第一问在主界面那边转写，失败的话录音会存到这条笔记名下 —— 及时亮出重试
                failed = FailedClips.list(context, captureId)
                val t = runCatching { Api.listQaTurns(captureId) }.getOrDefault(emptyList())
                if (t.isNotEmpty()) { load(); return@LaunchedEffect }
            }
        }
    }

    // 最后一轮还是「我问」，说明答案还没来。追问和第一次提问都走这一条路。
    LaunchedEffect(turns) {
        val t = turns
        if (capture?.kind == "ask" && t.isNotEmpty() && t.last().role == "user" && !thinking) {
            thinking = true
            try {
                Api.answerQuestion(captureId, deepThink)?.let {
                    aiProvider = it
                    AppPrefs.setAiProvider(context, it)
                }
                turns = Api.listQaTurns(captureId)
                busy = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 答案一写进 turns，这个 effect 就被自己重启了（turns 正是它的 key），
                // 旧的那次于是「取消」退出 —— 这是正常收尾，不是失败。
                // 不把它挑出来，屏幕上就会跟着答案一起冒出一句
                // 「回答失败：The coroutine scope left the composition」。
                throw e
            } catch (e: Exception) {
                busy = "回答失败：${e.message?.take(60)}"
            } finally { thinking = false }
        }
    }

    // 还在转写就轮询，处理完自动填上，不用手动刷新。
    // 原来只轮 20 次（50 秒）—— 录得长一点、网慢一点就超时，
    // 之后页面再也不会自己去问服务端，只能退出重进才看得到结果。
    LaunchedEffect(capture?.asrStatus, capture?.correctedText) {
        val c = capture
        if (c != null && c.correctedText == null && c.asrStatus != "failed") {
            repeat(120) {
                delay(2500); load()
                if (capture?.correctedText != null) return@LaunchedEffect
            }
        }
    }

    // 对话页自动滚到底：发出去一句、AI 开始想、回答落下来，都该把最新的一句
    // 送到眼前，而不是让人每次自己拖到底。
    //
    // 不能「内容一变就立刻滚」—— 那一刻新气泡还没测量，maxValue 还是上一屏的高度，
    // 滚过去只到旧的底。所以盯着 maxValue 本身：它一变说明新内容已经算进高度了，
    // 这时候再滚才真到底（长回答分几帧铺开，也会跟着一路滚下去）。
    LaunchedEffect(captureId, capture?.kind) {
        if (capture?.kind != "ask") return@LaunchedEffect
        snapshotFlow { scrollState.maxValue }
            .distinctUntilChanged()
            .collectLatest { max -> scrollState.animateScrollTo(max) }
    }

    // 只在**你刚发出指令**时才滚到底：先滚下去看见「正在处理」，
    // 结果落下来再滚一次。
    // 不能挂在「产出数量变化」上 —— 打开笔记时产出是从 0 加载到 N，
    // 那同样是一次「变化」，于是一点进来就被弹到底，正文反而看不见了
    LaunchedEffect(pending, outputs.size) {
        if (pending != null || justRan) {
            repeat(3) {
                delay(120)
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            justRan = false
        }
    }

    LaunchedEffect(draft?.text) {
        if (draft != null && draft?.text != savedText) {
            delay(1200)
            saveNow()
        }
    }

    // 「已保存」提示两秒后自己消失
    LaunchedEffect(saveHint) {
        if (saveHint == "已保存") { delay(2000); saveHint = null }
    }

    LaunchedEffect(recording) {
        if (recording) {
            peak = 0f; levels = emptyList()
            while (recording) {
                val amp = recorder.amplitude()
                peak = maxOf(peak, amp)
                levels = (levels + amp).takeLast(48)
                delay(60)
            }
        }
    }

    fun startAppend() {
        if (recorder.start()) { recording = true; busy = null }
        else busy = "录音启动失败"
    }

    fun cancelAppend() {
        if (!recording) return
        recording = false
        recorder.stop()?.first?.delete()
        busy = "已取消"
    }

    /**
     * 点一下预设按钮 —— 跟语音下指令是同一条路，区别只是指令是预设的。
     *
     * 先 saveNow()：服务端是从**库里**读这条笔记的正文，
     * 手打的改动没存上去的话，AI 加工的就是旧文字。
     */
    fun runPreset(action: String, label: String) {
        if (busy != null || pending != null) return
        pending = label
        scope.launch {
            try {
                saveNow()
                // 服务端会回传标签；万一没回传，用按钮上的字兜底，
                // 总之不能让卡片上出现「指令：」后面空着
                val out = Api.runNoteCommand(captureId, action = action)
                outputs = outputs +
                    if (out.instruction.isBlank()) out.copy(instruction = label) else out
                justRan = true
            } catch (e: Exception) {
                busy = "${label}失败：${e.message?.take(60)}"
            } finally { pending = null }
        }
    }

    // ── 录音送出去的几条路。失败时录音不删，存进 FailedClips 等重试 ──────

    /** 追加一段：转写好的文字插在光标处 */
    suspend fun doAppend(file: File, ms: Int) {
        saveNow()          // 先把手改的内容存住，再追加
        val added = Api.appendVoiceSegment(captureId, file, ms)
        // 插在光标所在的位置，而不是永远丢到最后一段后面
        draft?.let { cur ->
            history.checkpoint(cur)
            val at = cur.selection.start.coerceIn(0, cur.text.length)
            val merged = cur.text.substring(0, at) + added + cur.text.substring(at)
            draft = TextFieldValue(merged, TextRange(at + added.length))
        }
        saveNow()
        busy = null
        // 日记又追加了一段，心情可能变了。他亲手改过的服务端会跳过
        if (capture?.kind == "diary" && capture?.moodIsManual == false) {
            runCatching { Api.inferMoods(listOf(captureId)) }
        }
        load()
    }

    /** 对笔记下指令。松手立刻滚到底、亮出占位卡 —— 结果会长在页面最下面 */
    suspend fun doCommand(file: File, ms: Int) {
        pending = "正在转文字…"
        try {
            saveNow()   // 先把手打的改动存住再走网络
            val instruction = Api.transcribeAudio(file, ms)
            if (instruction.isBlank()) throw NotHeard()
            pending = instruction
            // 调研类指令要联网搜索，可能要十几秒
            outputs = outputs + Api.runNoteCommand(captureId, instruction = instruction)
            justRan = true
        } finally { pending = null }
    }

    /** 追问：录音只是输入手段，转完即删；接着让 AI 答下一轮 */
    suspend fun doAsk(file: File, ms: Int) {
        transcribing = true
        try {
            val q = Api.transcribeAudio(file, ms)
            if (q.isBlank()) throw NotHeard()
            Api.addQaTurn(captureId, "user", q)
            turns = Api.listQaTurns(captureId)   // 触发上面的自动作答
        } finally { transcribing = false }
    }

    /** 主界面那一问没转写成功，在这里补上 */
    suspend fun doAskFirst(file: File, ms: Int) {
        transcribing = true
        try {
            val q = Api.transcribeAudio(file, ms)
            if (q.isBlank()) throw NotHeard()
            Api.fillAskQuestion(captureId, q)
            load()
        } finally { transcribing = false }
    }

    suspend fun runClip(file: File, ms: Int, mode: FailedClips.Mode) = when (mode) {
        FailedClips.Mode.Append -> doAppend(file, ms)
        FailedClips.Mode.Command -> doCommand(file, ms)
        FailedClips.Mode.Ask -> doAsk(file, ms)
        FailedClips.Mode.AskFirst -> doAskFirst(file, ms)
    }

    /**
     * 送一段刚录好的音。**只有服务端确认收下之后录音才删** ——
     * 失败（403、断网、超时…）或者中途退出了这一页，都先把录音存进手机。
     */
    fun launchClip(file: File, ms: Int, mode: FailedClips.Mode, label: String) {
        scope.launch {
            try {
                runClip(file, ms, mode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 还没传完就退出了这一页。不知道服务端收没收到，宁可多存一份
                FailedClips.save(context, captureId, file, ms, mode)
                throw e
            } catch (e: NotHeard) {
                busy = "没听清，再说一遍"
            } catch (e: Exception) {
                busy = if (FailedClips.save(context, captureId, file, ms, mode)) {
                    failed = FailedClips.list(context, captureId)
                    "${label}没传上去，录音已存在手机里，点上面的「重试」"
                } else "${label}失败：${e.message?.take(80)}"
            } finally { file.delete() }
        }
    }

    /** 把存在手机里的录音按顺序再送一遍。成功一段删一段 */
    fun retryFailed() {
        if (retrying) return
        retrying = true
        busy = "重新上传中…"
        scope.launch {
            try {
                for (clip in FailedClips.list(context, captureId)) {
                    try {
                        runClip(clip.audio, clip.durationMs, clip.mode)
                    } catch (e: NotHeard) {
                        // 转出来是空的，留着也没用
                    }
                    FailedClips.remove(clip)
                }
                busy = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                busy = "还是没传上去：${e.message?.take(60)}"
            } finally {
                retrying = false
                failed = FailedClips.list(context, captureId)
            }
        }
    }

    fun finishAppend(action: TalkAction) {
        if (!recording) return
        recording = false
        val result = recorder.stop()
        if (result == null) { busy = "太短了，没录上"; return }
        // 静音保护：空白音频会让 ASR 凭空幻觉出随机语言的短词
        if (peak < Recorder.SILENCE_PEAK) {
            result.first.delete(); busy = "没听到声音，已丢弃"; return
        }

        val (file, ms) = result
        // 提问笔记走单独一条路：松手立刻把按钮还回「按住」，
        // 转文字的等待改成对话里的一个气泡。按钮上转圈会让人以为
        // 「还不能再说话」，而其实随时都可以。
        if (capture?.kind == "ask") {
            busy = null
            launchClip(file, ms, FailedClips.Mode.Ask, "追问")
            return
        }
        // 灵感 / 备忘 / 选题：原地松手是**下指令**，不是加内容。
        // 说的话当命令用，这条笔记的正文当上下文，AI 改完整篇换上去。
        if (action == TalkAction.Send && capture?.kind in COMMAND_KINDS) {
            launchClip(file, ms, FailedClips.Mode.Command, "指令")
            return
        }

        busy = "转写中…"
        launchClip(file, ms, FailedClips.Mode.Append, "这段录音")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(capture?.summary ?: "语音记录", maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
            )
        },
    ) { pad ->
        val c = capture
        when {
            loading && c == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                CircularProgressIndicator()
            }
            c == null -> Box(Modifier.fillMaxSize().padding(pad), Alignment.Center) {
                Text("这条记录不见了", textAlign = TextAlign.Center)
            }
            // imePadding：页面底边跟着键盘往上收。不加的话（edge-to-edge 下系统不会替你缩），
            // 内容一直铺到键盘底下，正在打的那几行就被键盘盖住了
            else -> Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
              Column(
                Modifier.fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp)
                    // 给悬浮的说话键让出落脚空间，最后一段不会被压住。
                    // 打字时说话键藏起来了，只留一点呼吸空间
                    .padding(bottom = if (imeVisible) 24.dp else 140.dp),
              ) {
                // ① 语音：原始那条直接展开，追加的折叠起来。
                //    原来四条一模一样的「原始语音」排下来，根本分不清谁是谁；
                //    而且想听的基本都是第一条，为它多点一下不值得。
                val extra = segments.filter { it.audioPath != null }
                if (c.audioPath != null || extra.isNotEmpty()) {
                    c.audioPath?.let {
                        AudioPlayer(it, label = "原始语音")
                        Spacer(Modifier.height(8.dp))
                    }
                    if (extra.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().clickable { audioOpen = !audioOpen }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("追加的 ${extra.size} 段",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text(if (audioOpen) "▾" else "▸",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AnimatedVisibility(audioOpen) {
                            Column {
                                extra.forEachIndexed { i, seg ->
                                    AudioPlayer(seg.audioPath!!, label = "追加 ${i + 1}")
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // ② 分类（可改）+ 生成时间 + 复制 / 编辑
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clickable { picking = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindChipPublic(c.kind ?: "inbox")
                        Spacer(Modifier.width(4.dp))
                        Text("▾", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(formatCreatedAt(c.createdAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // 饮食/日记可以指定这条说的是哪天的事。补记过的用醒目色标出来，
                    // 免得你看着「昨天」的数据以为是今天的
                    if (c.kind in DATED_KINDS) {
                        Spacer(Modifier.width(10.dp))
                        Row(
                            Modifier.clickable { pickingDate = true },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                c.loggedOn?.let { "记的是 ${loggedLabel(it)}" } ?: "记的是当天",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (c.loggedOn != null)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(" ▾", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    saveHint?.let {
                        Spacer(Modifier.width(10.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    // 撤销 / 重做：放在日期右边、复制左边，改正文时手边就够得着。
                    // 误删一大段、自动保存之后也能退回去
                    if (c.kind != "ask" && draft != null) {
                        IconButton(
                            onClick = { draft?.let { cur -> history.undo(cur)?.let { draft = it } } },
                            enabled = history.canUndo,
                            modifier = Modifier.size(36.dp),
                        ) { Icon(painterResource(R.drawable.ic_undo), contentDescription = "撤销",
                                Modifier.size(20.dp)) }
                        IconButton(
                            onClick = { draft?.let { cur -> history.redo(cur)?.let { draft = it } } },
                            enabled = history.canRedo,
                            modifier = Modifier.size(36.dp),
                        ) { Icon(painterResource(R.drawable.ic_redo), contentDescription = "重做",
                                Modifier.size(20.dp)) }
                    }
                    if (c.kind == "ask") {
                        CopyButton(turns.joinToString("\n\n") {
                            (if (it.role == "user") "我问：" else "AI：") + it.text
                        })
                    } else {
                        draft?.let { CopyButton(it.text) }
                    }
                }

                // 没送出去的录音：存在手机里，一键重试
                if (failed.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val secs = failed.sumOf { it.durationMs } / 1000
                            Text(
                                "有 ${failed.size} 段录音没传上去（共 ${secs / 60}分${secs % 60}秒），已存在手机里",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            TextButton(onClick = ::retryFailed, enabled = !retrying) {
                                Text(if (retrying) "上传中…" else "重试")
                            }
                            TextButton(onClick = { confirmDiscard = true }, enabled = !retrying) {
                                Text("丢弃", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                // 日记的心情：AI 推断，不对就点一下改
                if (c.kind == "diary" && draft != null) {
                    Spacer(Modifier.height(10.dp))
                    MoodPicker(c.mood, c.moodIsManual) { m ->
                        capture = c.copy(mood = m, moodIsManual = true)
                        scope.launch {
                            try { Api.setMood(captureId, m) }
                            catch (e: Exception) {
                                busy = "心情没存上：${e.message?.take(40)}"
                                load()
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ③ 正文。提问笔记长得不一样：它是一段对话，不是一段可编辑的文字
                if (c.kind == "ask") {
                    DeepThinkToggle(deepThink) {
                        deepThink = it
                        AppPrefs.setDeepThink(context, it)
                    }
                    Spacer(Modifier.height(14.dp))
                    QaThread(turns, transcribing, thinking, aiProvider)
                } else if (draft != null) {
                    // 直接就能改，不用先点「编辑」——多一步按钮只是碍事
                    BasicTextField(
                        value = draft!!,
                        onValueChange = { new ->
                            draft?.let { history.onEdit(it, new) }
                            draft = new
                        },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        onTextLayout = { textLayout = it },
                        modifier = Modifier.fillMaxWidth()
                            .bringIntoViewRequester(cursorBringer)
                            .onFocusChanged { editorFocused = it.isFocused },
                    )
                    // 日记落款。只是显示，不写进正文 —— 写进去的话
                    // 重新处理一次就会多出第二行落款
                    if (c.kind == "diary") {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            writtenOn(c.createdAt),
                            Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 转写成功而分析失败时 asr_status 还是 done，
                        // 光看它会一直显示「正在转写」，把失败藏起来
                        if (c.asrStatus != "failed" && c.asrError == null) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("正在转写和整理…", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text("处理失败", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    c.asrError?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    // 原话已经在服务端了，重跑会自动跳过转写、只重做分析。
                    // 所以处理失败**不需要重说一遍** —— 服务端挂了的那几条都能这么救回来。
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = "重新处理中…"
                                try {
                                    Api.processCapture(captureId)
                                    load()
                                    busy = null
                                } catch (e: Exception) {
                                    busy = "重试失败：${e.message?.take(60)}"
                                }
                            }
                        },
                        enabled = busy != "重新处理中…",
                    ) { Text("重新处理") }
                }

                // ④ 常驻的几个预设指令。点一下跟「按住说话下指令」走同一条管道，
                //    只是指令是预先写好的（提示词在服务端，按分类各不相同）
                if (c.kind in COMMAND_KINDS && draft != null) {
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presetsFor(c.kind).forEach { (action, label) ->
                            OutlinedButton(
                                onClick = { runPreset(action, label) },
                                enabled = busy == null && pending == null,
                                contentPadding = PaddingValues(horizontal = 16.dp),
                            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }

                // ⑤ AI 按指令产出的东西 —— 挂在正文下面，正文本身不受影响
                if (outputs.isNotEmpty() || pending != null) {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    outputs.forEach { out ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                // 把当时说的那句指令留在上面 ——
                                // 过几天回看，「AI 为什么写了这些」全靠它解释
                                Text("指令：${out.instruction}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(8.dp))
                                SelectionContainer {
                                    Text(renderMarkdown(out.text),
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 改写型的产出可以直接顶掉原文。
                                    // 不怕丢：转写原话一直在下面「查看转写原话」里
                                    if (out.replaceable) {
                                        TextButton(onClick = {
                                            scope.launch {
                                                draft?.let { history.checkpoint(it) }
                                                draft = TextFieldValue(
                                                    out.text, TextRange(out.text.length))
                                                saveNow()
                                                // 已经成了正文，再留一张一模一样的卡片只是噪音
                                                runCatching { Api.deleteNoteOutput(out.id) }
                                                outputs = outputs.filterNot { it.id == out.id }
                                            }
                                        }) {
                                            Text("替换原文",
                                                style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    CopyButton(out.text)
                                    TextButton(onClick = {
                                        scope.launch {
                                            runCatching { Api.deleteNoteOutput(out.id) }
                                            outputs = outputs.filterNot { it.id == out.id }
                                        }
                                    }) {
                                        Text("删除",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }

                    // 正在处理的那条排在**最后** —— 它是刚发出的，
                    // 位置就该在列表末尾，滚到底正好看见它
                    pending?.let { label ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (label.endsWith("…")) label else "指令：$label",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    }
                }

                // ⑥ 这条语音产生的待办
                if (tasks.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text("产生的待办", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    tasks.forEach { t ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(t.title, style = MaterialTheme.typography.bodyMedium)
                                t.dueAt?.let {
                                    Text(
                                        formatDue(it) + if (t.dueIsFuzzy) "（模糊）" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // ⑤ 原话，默认折叠
                val rawAll = listOfNotNull(
                    c.finalText,
                    *segments.mapNotNull { it.finalText }.toTypedArray(),
                ).joinToString("\n")
                if (rawAll.isNotBlank() && c.kind != "ask") {
                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider()
                    TextButton(onClick = { showRaw = !showRaw }) {
                        Text(if (showRaw) "▾ 隐藏原话" else "▸ 查看转写原话")
                    }
                    AnimatedVisibility(showRaw) {
                        Column {
                            Text(
                                "这是语音识别的原始输出，未经整理。长按可以选择文字。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            SelectableText(rawAll, MaterialTheme.typography.bodyMedium,
                                MaterialTheme.colorScheme.onSurfaceVariant)
                            CopyButton(rawAll, "复制原话")
                        }
                    }
                }

              }

                // ⑥ 继续说 —— 悬浮在底部，不用滚到最后才找得到
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    busy?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                    // 跟主屏是同一条波形
                    AnimatedVisibility(recording, enter = fadeIn(), exit = fadeOut()) {
                        TalkWaveform(levels, Modifier.width(300.dp).height(36.dp))
                    }
                    // 打字的时候说话键让开：它浮在最下面，键盘一顶上来正好压在光标那几行上。
                    // 想接着说话，收起键盘就回来了
                    if (!imeVisible || recording) TalkButtonWithSlide(
                        recording = recording,
                        // 提问笔记从不锁按钮：它的等待全在对话里显示
                        // 指令的等待感在页面里（占位卡），不压在按钮上
                        busy = busy == "转写中…",
                        onStart = ::startAppend,
                        onEnd = { action ->
                            if (action == TalkAction.Cancel) cancelAppend()
                            else finishAppend(action)
                        },
                        // 这三类笔记里原地松手是下指令，按键中心要写「指令」而不是「松开」
                        mode = if (c.kind in COMMAND_KINDS) TalkMode.Command
                               else TalkMode.Append,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("丢弃没传上去的录音？") },
            text = { Text("这 ${failed.size} 段录音只存在这台手机上，丢弃后找不回来。") },
            confirmButton = {
                TextButton(onClick = {
                    failed.forEach { FailedClips.remove(it) }
                    failed = FailedClips.list(context, captureId)
                    confirmDiscard = false
                    busy = null
                }) { Text("丢弃", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("留着") } },
        )
    }

    if (pickingDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = capture?.loggedOn?.let {
                runCatching {
                    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.parse(it)?.time
                }.getOrNull()
            } ?: System.currentTimeMillis(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val ms = state.selectedDateMillis
                    pickingDate = false
                    if (ms != null) scope.launch {
                        // 选出来的是 UTC 零点，按 UTC 格式化才不会偏一天
                        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                            .format(java.util.Date(ms))
                        runCatching { Api.setLoggedOn(captureId, d) }
                        load()
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pickingDate = false
                    scope.launch {
                        runCatching { Api.setLoggedOn(captureId, null) }
                        load()
                    }
                }) { Text("就按录音当天") }
            },
        ) { DatePicker(state = state) }
    }

    if (picking) {
        KindPickerSheet(
            current = capture?.kind ?: "inbox",
            onDismiss = { picking = false },
            onPick = { k ->
                val target = capture
                picking = false
                if (target != null) scope.launch {
                    try { Api.setCaptureKind(target, k) } catch (_: Exception) {}
                    load()
                }
            },
        )
    }
}

// ── 改分类 ────────────────────────────────────────────────────────────────

/** 「待办」也在里面：从待办页点 🎙 进来时，可能需要把它改回日记或备忘 */
private val ALL_KINDS = listOf("memo", "meal", "diary", "content", "idea", "inbox", "task")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindPickerSheet(current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("改成哪个分类？", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("分类是 AI 判断的，判错了你说了算",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            ALL_KINDS.forEach { k ->
                TextButton(onClick = { onPick(k) }, modifier = Modifier.fillMaxWidth()) {
                    Text(kindLabel(k), Modifier.weight(1f))
                    if (k == current) Text("✓")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("改成「待办」会把它移到待办页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── 复制 ──────────────────────────────────────────────────────────────────

/** 复制到剪贴板。Android 13+ 系统自己会弹「已复制」，更早的版本才需要我们提示 */
private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("Back on Track", text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 长按选词、拖动两端手柄选一部分，系统菜单里复制。
 * 整段复制交给旁边的「复制」按钮 —— 长按再做整段复制就跟选文字冲突了。
 */
@Composable
private fun SelectableText(text: String, style: TextStyle, color: Color = Color.Unspecified) {
    SelectionContainer {
        Text(text, style = style, color = color, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CopyButton(text: String, label: String = "复制") {
    val context = LocalContext.current
    TextButton(
        onClick = { copyToClipboard(context, text) },
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

/**
 * 「问问题」笔记的正文：一问一答。
 *
 * 做成气泡而不是一整段文字，是因为要分得清哪句是我问的、哪句是 AI 答的 ——
 * 追问几轮之后，拼成一段就完全读不出层次了。
 */
@Composable
private fun QaThread(
    turns: List<QaTurn>,
    transcribing: Boolean,
    thinking: Boolean,
    provider: String?,
) {
    // 聊天式：我在右、AI 在左，各自带头像 —— 追问几轮之后还分得清谁说的
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        turns.forEach { t ->
            val mine = t.role == "user"
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                if (!mine) {
                    AiAvatar(provider)
                    Spacer(Modifier.width(8.dp))
                }
                Surface(
                    color = if (mine) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.widthIn(max = 280.dp),
                ) {
                    SelectionContainer {
                        Text(renderMarkdown(t.text),
                            Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
                if (mine) {
                    Spacer(Modifier.width(8.dp))
                    Avatar("我", MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.onPrimary)
                }
            }
        }

        // 刚说完的那句还在转文字 —— 做成一个「我」这边的气泡，
        // 位置就是它转完之后会待的地方，转好了原地换成文字。
        // 第一次提问（先跳进来、后转写）走的也是这一条，所以 turns 为空时同样显示。
        if (transcribing || turns.isEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在转文字…", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Avatar("我", MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.onPrimary)
            }
        }

        if (thinking) ThinkingRow(provider)
    }
}

/**
 * 「AI 正在思考…」——一行会呼吸的灰字，不是转圈，也不悬浮在屏幕上。
 *
 * 悬浮的进度条会盖住刚说完的话，而且看起来像「整个页面卡住了」；
 * 放在对话末尾、下一个气泡该出现的位置，等待才读得出是「它在写」。
 */
@Composable
private fun ThinkingRow(provider: String?) {
    val alpha by rememberInfiniteTransition(label = "thinking").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        AiAvatar(provider)
        Spacer(Modifier.width(8.dp))
        Text("AI 正在思考…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    }
}

/** 顶部的「深度思考」开关。默认关 —— 快答是常态，想深一点是例外 */
@Composable
private fun DeepThinkToggle(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            selected = on,
            onClick = { onChange(!on) },
            label = { Text("深度思考") },
            leadingIcon = { Text(if (on) "✓" else "🧠") },
        )
        Spacer(Modifier.width(10.dp))
        Text(
            if (on) "想得更透，但要多等一会儿" else "默认快答，需要时再打开",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * AI 那一侧的头像：用哪家的 key 就显示哪家的标。
 *
 * 客户端手里没有 key，判断不了，所以 provider 是服务端回答时报上来的；
 * 还没问过一次的时候（provider 为 null）退回文字头像，不瞎猜一个。
 */
@Composable
private fun AiAvatar(provider: String?) {
    val icon = when (provider) {
        "anthropic" -> R.drawable.ic_claude
        "openai" -> R.drawable.ic_openai
        else -> null
    }
    if (icon == null) {
        Avatar("AI", MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer)
        return
    }
    // 底色用各家自己的：Claude 陶土色、OpenAI 黑
    val bg = if (provider == "anthropic") Color(0xFFD97757) else Color(0xFF000000)
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null,
            tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** 纯矢量头像：一个带字的圆，不引入任何图片资源 */
@Composable
private fun Avatar(label: String, bg: Color, fg: Color) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/**
 * 把模型回答里最常见的几种 Markdown 渲染出来。
 *
 * 模型天然会写 **加粗**、列表和 [链接](url)，原样显示就是满屏的星号和方括号 ——
 * 而这个功能对标的是 ChatGPT 那种读起来顺的对话。
 *
 * 只做加粗、链接文字和列表符号三种：够用，而且不引入任何 Markdown 依赖
 * （这个项目一直是零第三方库）。
 */
private fun renderMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("""\*\*(.+?)\*\*|\[([^\]]+)\]\(([^)]+)\)""")
    src.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        // 「- 」「* 」开头的列表项换成圆点，比原样留着好看
        var rest = raw.replace(Regex("""^\s*[-*]\s+"""), "• ")
        while (true) {
            val m = pattern.find(rest) ?: break
            append(rest.substring(0, m.range.first))
            val bold = m.groupValues[1]
            if (bold.isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            } else {
                // 链接只显示文字并加下划线；小组件式的对话里放长 URL 只会碍事
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) {
                    append(m.groupValues[2])
                }
            }
            rest = rest.substring(m.range.last + 1)
        }
        append(rest)
    }
}

/** 转写回来是空的：不是网络问题，录音留着也没用 */
private class NotHeard : Exception("没听清")
