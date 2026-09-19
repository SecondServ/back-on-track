package com.hector.backontrack.ui

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.hector.backontrack.audio.Recorder
import com.hector.backontrack.net.Api
import com.hector.backontrack.net.Profile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 用户档案 —— 用户给 AI 写一份「我是谁」。
 *
 * 两块内容喂给管线的不同环节，所以分开填：
 *  - 专有名词：进 ASR 的听写提示。名字地名是在**转写那一步**听错的，
 *    过了那一步，后面的模型看不到音频，只能顺着错字猜，补不回来。
 *  - 档案正文：进 LLM 的 system prompt，用来理解背景、消歧义。
 *
 * 正文可以打字，也可以按住说 —— 这个 App 的前提就是说比打快。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { Recorder(context) }

    var body by remember { mutableStateOf(TextFieldValue("")) }
    var words by remember { mutableStateOf("") }
    var aiBody by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }

    var recording by remember { mutableStateOf(false) }
    var levels by remember { mutableStateOf(listOf<Float>()) }
    var peak by remember { mutableFloatStateOf(0f) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            Api.getProfile()?.let { p ->
                body = TextFieldValue(p.body, TextRange(p.body.length))
                words = p.glossary.joinToString("、")
                aiBody = p.aiBody
                create = false
            }
        } catch (e: Exception) {
            hint = if (e is java.io.IOException) "没网，连上网再打开档案"
            else "读取失败：${e.message?.take(60)}"
        } finally { loading = false }
    }

    LaunchedEffect(recording) {
        if (recording) {
            levels = emptyList(); peak = 0f
            while (recording) {
                val amp = recorder.amplitude()
                peak = maxOf(peak, amp)
                levels = (levels + amp).takeLast(48)
                delay(60)
            }
        }
    }

    fun glossary() = words.split("、", ",", "，", "\n")
        .map { it.trim() }.filter { it.isNotEmpty() }

    fun save() {
        scope.launch {
            hint = "保存中…"
            try {
                Api.saveProfile(Profile(body.text.trim(), glossary()), create)
                create = false
                hint = "已保存，下次说话就会用上"
            } catch (e: Exception) {
                hint = "没存上：${e.message?.take(60)}"
            }
        }
    }

    fun startTalk() {
        if (recorder.start()) { recording = true; hint = null } else hint = "录音启动失败"
    }

    fun finishTalk(cancelled: Boolean) {
        if (!recording) return
        recording = false
        val result = recorder.stop()
        if (cancelled) { result?.first?.delete(); hint = "已取消"; return }
        if (result == null) { hint = "太短了，没录上"; return }
        if (peak < Recorder.SILENCE_PEAK) {
            result.first.delete(); hint = "没听到声音，已丢弃"; return
        }

        val (file, ms) = result
        working = true; hint = "转写中…"
        scope.launch {
            try {
                val text = Api.transcribeAudio(file, ms)
                // 插在光标处，跟笔记页一致
                val at = body.selection.start.coerceIn(0, body.text.length)
                val sep = if (at > 0 && body.text.getOrNull(at - 1)?.isWhitespace() == false) "\n" else ""
                val merged = body.text.substring(0, at) + sep + text + body.text.substring(at)
                body = TextFieldValue(merged, TextRange(at + sep.length + text.length))
                hint = "记下了，别忘了保存"
            } catch (e: Exception) {
                hint = "转写失败：${e.message?.take(60)}"
            } finally { file.delete(); working = false }
        }
    }

    fun exportMd() {
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(java.util.Date())
        val md = buildString {
            appendLine("# 用户档案")
            appendLine()
            appendLine("<!-- 由 Back on Track 导出于 $stamp -->")
            appendLine()
            appendLine("## 常说到的名字")
            appendLine()
            glossary().forEach { appendLine("- $it") }
            appendLine()
            appendLine("## 我自己写的")
            appendLine()
            appendLine(body.text.trim().ifEmpty { "（空）" })
            if (aiBody.isNotBlank()) {
                appendLine()
                appendLine("## AI 从日常记录里整理的")
                appendLine()
                appendLine("> 以下由 AI 推断，未经本人确认。")
                appendLine()
                appendLine(aiBody.trim())
            }
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "back-on-track-profile-$stamp.md")
                put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("创建文件失败")
            context.contentResolver.openOutputStream(uri)?.use { it.write(md.toByteArray()) }
            hint = "已导出到「下载」文件夹"
        } catch (e: Exception) {
            hint = "导出失败：${e.message?.take(60)}"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("用户档案") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← 返回") } },
                actions = { TextButton(onClick = ::save, enabled = !loading) { Text("保存") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "让 AI 认识你。写过之后，它转写时能把人名地名写对，" +
                    "整理时也知道你说的项目、缩写指的是什么。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Text("常说到的名字", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "人名、地名、公司、产品名。这些是最容易听错的，" +
                    "写在这里能直接纠正转写，效果比写在下面的正文里明显得多。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = words,
                onValueChange = { words = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                placeholder = { Text("李小皮、Sicko、Foster City、Back on Track") },
                supportingText = { Text("用顿号、逗号或换行隔开") },
            )

            Spacer(Modifier.height(20.dp))
            Text("关于你", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "做什么工作、手上有哪些项目、常打交道的人、习惯用的说法。" +
                    "可以打字，也可以按住下面的键直接说 —— 文字会插在光标处。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                placeholder = {
                    Text("例：我在湾区做产品，业余做自媒体和一个叫 Back on Track 的 App。" +
                        "家里有只狗叫 Sicko。说「老板」一般指公司的直属上级。")
                },
            )

            if (aiBody.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Text("AI 整理的", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "根据你平时的记录攒出来的，只读。不准的话，在上面写一句纠正它。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(aiBody, Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }

            hint?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(20.dp))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(recording, enter = fadeIn(), exit = fadeOut()) {
                    TalkWaveform(levels, Modifier.width(300.dp).height(36.dp))
                }
                TalkButtonWithSlide(
                    recording = recording,
                    busy = working,
                    onStart = ::startTalk,
                    onEnd = { action -> finishTalk(action == TalkAction.Cancel) },
                    mode = TalkMode.Append,
                )
            }

            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = ::exportMd, modifier = Modifier.fillMaxWidth()) {
                Text("导出为 Markdown 文件")
            }
            Text(
                "导出一份带得走的档案，换别的 AI 时直接喂给它就能快速认识你。",
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
