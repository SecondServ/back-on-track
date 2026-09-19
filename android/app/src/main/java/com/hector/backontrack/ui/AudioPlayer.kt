package com.hector.backontrack.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hector.backontrack.net.Api
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** 详情页顶部的语音回放条：下载 → 播放/暂停 → 进度 */
@Composable
fun AudioPlayer(
    audioPath: String,
    modifier: Modifier = Modifier,
    /** 这条是「原始语音」还是「追加 2」——多段并排时必须能分辨 */
    label: String = "原始语音",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { player?.release(); player = null }
    }

    LaunchedEffect(playing) {
        while (playing) {
            player?.let { p ->
                if (p.duration > 0) progress = p.currentPosition.toFloat() / p.duration
            }
            delay(100)
        }
    }

    fun toggle() {
        val p = player
        if (p != null) {
            if (p.isPlaying) { p.pause(); playing = false }
            else { p.start(); playing = true }
            return
        }
        loading = true; failed = false
        scope.launch {
            try {
                // 缓存到本地，重复播放不用再下
                val f = File(context.cacheDir, "audio_${audioPath.hashCode()}.m4a")
                if (!f.exists() || f.length() == 0L) Api.downloadAudio(audioPath, f)
                MediaPlayer().apply {
                    setDataSource(f.absolutePath)
                    prepare()
                    setOnCompletionListener { playing = false; progress = 0f }
                    start()
                    player = this
                    durationMs = duration
                    playing = true
                }
            } catch (_: Exception) {
                failed = true
            } finally { loading = false }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onClick = ::toggle, enabled = !loading) {
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (playing) "❚❚" else "▶")
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        failed -> "音频加载失败"
                        durationMs > 0 -> "$label · ${durationMs / 1000}s"
                        else -> label
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
