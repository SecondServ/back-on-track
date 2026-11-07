package com.hector.backontrack.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hector.backontrack.audio.PendingQueue
import com.hector.backontrack.audio.Recorder
import com.hector.backontrack.net.Api
import com.hector.backontrack.reminder.ReminderScheduler
import com.hector.backontrack.net.Uploader
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TalkPhase { Idle, Recording, Working }

/**
 * 悬浮的「按住说话」。
 *
 * 刻意做成覆盖层而不是占一块布局空间：入口要随时可达，
 * 但不该常驻占掉半屏 —— 平时只有一颗按钮，按住时扇形和波形才浮上来。
 * 因为它盖在内容之上，录音时加一层暗色遮罩，保证扇形和文字在任何背景上都读得清。
 */
@Composable
fun BoxScope.TalkOverlay(
    /** 在「待办」页录音：跳过分类选择，直接建待办 */
    todoMode: Boolean,
    /** 条目刚创建（几百毫秒内）—— 列表该刷新并滚到顶部让用户看见 */
    onCaptureCreated: () -> Unit,
    /** 转写分析完成（约十秒后）—— 只刷新内容，不要再挪动列表位置 */
    onCaptureUpdated: () -> Unit,
    onWentToTasks: () -> Unit,
    /**
     * 选了「问题」：把录音交出去就完事。
     * 建笔记、跳转、转写都交给上层做 —— 这里的协程作用域一跳转就被取消了。
     */
    onAskRecorded: (java.io.File, Int) -> Unit,
    onStatus: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(context) }

    var phase by remember { mutableStateOf(TalkPhase.Idle) }
    var levels by remember { mutableStateOf(listOf<Float>()) }
    var peakLevel by remember { mutableFloatStateOf(0f) }

    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED)
    }
    val askMic = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()) { hasMic = it }

    LaunchedEffect(phase) {
        if (phase == TalkPhase.Recording) {
            levels = emptyList(); peakLevel = 0f
            while (phase == TalkPhase.Recording) {
                val amp = recorder.amplitude()
                if (amp > peakLevel) peakLevel = amp
                levels = (levels + amp).takeLast(48)
                delay(60)
            }
        }
    }

    fun begin() {
        if (!hasMic) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        if (recorder.start()) { phase = TalkPhase.Recording; onStatus(null) }
        else onStatus("录音启动失败，麦克风可能被别的 App 占用")
    }

    fun end(raw: TalkAction) {
        if (phase != TalkPhase.Recording) return
        // 在待办页原地松手 = 直接建待办，不弹分类选项
        val action = if (todoMode && raw == TalkAction.Send) TalkAction.Todo else raw
        val result = recorder.stop()

        if (action == TalkAction.Cancel) {
            result?.first?.delete(); phase = TalkPhase.Idle; onStatus("已取消"); return
        }
        if (result == null) { phase = TalkPhase.Idle; onStatus("太短了，没录上"); return }

        // 静音保护：误触录到空白时，ASR 会凭空幻觉出随机语言的短词
        if (peakLevel < Recorder.SILENCE_PEAK) {
            result.first.delete(); phase = TalkPhase.Idle; onStatus("没听到声音，已丢弃"); return
        }

        val (file, durationMs) = result
        phase = TalkPhase.Working

        // 问问题：不走上传-转写-归类那条管线。
        // 音频只是输入手段，转成文字之后就没用了 —— transcribeAudio 转完即删。
        if (action == TalkAction.Ask) {
            // 不在这里转写：松手要立刻有反应，转写的几秒钟应该发生在对话页里，
            // 而不是让人对着没反应的主界面等着。
            onStatus(null)
            phase = TalkPhase.Idle
            onAskRecorded(file, durationMs)
            return
        }

        scope.launch {
            // 分开记「传上去了没有」：传上去之后再失败的是分析，
            // 那时候再排队补传就会变成重复上传同一段话。
            var uploaded = false
            try {
                val id = Api.uploadCapture(file, durationMs, action.forcedKind)
                uploaded = true
                onStatus(when (action) {
                    TalkAction.Todo -> "已送去待办，处理中…"
                    TalkAction.Meal -> "已记为饮食，处理中…"
                    else -> null
                })
                onCaptureCreated()
                phase = TalkPhase.Idle
                Api.processCapture(id)
                onCaptureUpdated()
                try { ReminderScheduler.syncFromServer(context) } catch (_: Exception) {}
                when (action) {
                    TalkAction.Todo -> { onWentToTasks(); onStatus("待办已创建") }
                    TalkAction.Meal -> onStatus("已记入饮食")
                    else -> onStatus(null)
                }
            } catch (e: Exception) {
                if (!uploaded && PendingQueue.enqueue(context, file, durationMs, action.forcedKind)) {
                    // 没传上去，多半是没网。录音必须留住 ——
                    // 在地铁里说的那句话，不该因为没信号就消失。
                    onStatus("没传上去，录音已存在手机里，稍后自动补传")
                    onCaptureCreated()
                    // 不一定是没网（比如登录票刚好过期被拒）。过几秒自己再试一次，
                    // 不用等下次开 App 或者网络切换
                    scope.launch {
                        delay(3000)
                        val n = try { Uploader.flushPending(context) { onCaptureCreated() } }
                                catch (_: Exception) { 0 }
                        if (n > 0) { onCaptureUpdated(); onStatus("补传成功") }
                    }
                } else {
                    onStatus("处理失败：${e.message?.take(160)}")
                }
            } finally {
                file.delete(); phase = TalkPhase.Idle
            }
        }
    }

    // 录音时的暗色遮罩，让浮在内容之上的扇形始终读得清
    AnimatedVisibility(
        visible = phase == TalkPhase.Recording,
        enter = fadeIn(), exit = fadeOut(),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
    }

    Column(
        Modifier.align(Alignment.BottomCenter),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(phase == TalkPhase.Recording, enter = fadeIn(), exit = fadeOut()) {
            TalkWaveform(levels, Modifier.width(300.dp).height(36.dp))
        }
        TalkButtonWithSlide(
            recording = phase == TalkPhase.Recording,
            busy = phase == TalkPhase.Working,
            onStart = ::begin,
            onEnd = ::end,
            mode = if (todoMode) TalkMode.TodoOnly else TalkMode.Full,
        )
    }
}

/**
 * 录音时的实时波形。主屏和笔记详情页共用同一个 ——
 * 两个地方的手感必须一样，各写一份迟早会走样。
 */
@Composable
fun TalkWaveform(levels: List<Float>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        if (levels.isEmpty()) return@Canvas
        val barWidth = size.width / 48f
        val mid = size.height / 2f
        levels.forEachIndexed { i, level ->
            val h = (level * size.height).coerceAtLeast(2f)
            val x = i * barWidth + barWidth / 2f
            drawLine(color, Offset(x, mid - h / 2f), Offset(x, mid + h / 2f),
                strokeWidth = barWidth * 0.55f)
        }
    }
}
