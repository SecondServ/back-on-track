package com.hector.backontrack.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * 按住说话的录音器。
 *
 * 输出 AAC / MP4(.m4a) —— 跟我们在服务端实测通过的格式一致。
 * 同时对外暴露实时音量，给界面画波形用：这是「我在听你说」的即时反馈，
 * 代替了成本高得多的流式转录（见 PLAN.md 的取舍说明）。
 */
class Recorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean = try {
        val file = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(64_000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        outputFile = file
        startedAt = System.currentTimeMillis()
        true
    } catch (e: Exception) {
        releaseQuietly()
        false
    }

    /** 返回录音文件和时长；太短或失败返回 null */
    fun stop(): Pair<File, Int>? {
        val r = recorder ?: return null
        val file = outputFile
        val duration = (System.currentTimeMillis() - startedAt).toInt()
        recorder = null
        outputFile = null
        return try {
            r.stop()
            r.release()
            // 低于 0.5 秒基本是误触，直接丢掉
            if (file != null && file.exists() && duration >= 500) {
                file to duration
            } else {
                file?.delete(); null
            }
        } catch (e: Exception) {
            // stop() 在录音过短时会抛异常，此时文件是坏的
            r.release(); file?.delete(); null
        }
    }

    /** 当前音量，0f..1f。界面每几十毫秒取一次画波形 */
    fun amplitude(): Float = try {
        val raw = recorder?.maxAmplitude ?: 0
        (raw / 8000f).coerceIn(0f, 1f)
    } catch (e: Exception) { 0f }

    companion object {
        /**
         * 低于这个峰值就当作没说话，直接丢弃。
         *
         * 原来是 0.05，太松：安静环境下的底噪也能过关，而 ASR 面对一段几乎无声的
         * 音频会凭空幻觉出随机语言的短句 —— 实际出现过日文乱码和一句克罗地亚语，
         * 它们还被当成正经待办存了下来。宁可偶尔漏掉一句气声，也不要这种假记录。
         */
        const val SILENCE_PEAK = 0.12f
    }

    private fun releaseQuietly() {
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}
