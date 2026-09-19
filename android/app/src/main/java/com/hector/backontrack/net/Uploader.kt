package com.hector.backontrack.net

import android.content.Context
import com.hector.backontrack.audio.PendingQueue
import kotlinx.coroutines.sync.Mutex

/** 把离线期间攒在本地的录音补传上去 */
object Uploader {

    /**
     * 同一时刻只允许一个补传在跑。
     *
     * 没有这道锁时出过事：ConnectivityManager.onAvailable 会重复回调
     * （连上、验证通过、切换网络各一次），几个 flush 并发地 list() 到同一条
     * 待传记录，在第一个把它删掉之前就各自上传了一遍 ——
     * 一条离线日记因此变成了 4 条（created_at 和时长完全相同，只是音频路径不同）。
     */
    private val lock = Mutex()

    /**
     * 返回这次补传成功的条数。
     *
     * 一条失败就停手：失败几乎总是「还是没网」，
     * 剩下的再试一遍只是白白多等几个超时。留在队列里，下次网好了再说。
     */
    suspend fun flushPending(ctx: Context, onUploaded: () -> Unit = {}): Int {
        // 拿不到锁 = 已经有一个在跑了。直接返回，排队再跑一遍只会重复上传
        if (!lock.tryLock()) return 0
        try {
            return flushLocked(ctx, onUploaded)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun flushLocked(ctx: Context, onUploaded: () -> Unit): Int {
        var done = 0
        for (item in PendingQueue.list(ctx)) {
            // list() 拿到的是快照，逐条再确认一次文件还在
            if (!item.audio.exists()) { PendingQueue.remove(item); continue }
            val id = try {
                Api.uploadCapture(
                    item.audio, item.durationMs, item.forcedKind,
                    createdAt = item.createdAt, clientTz = item.tz,
                )
            } catch (e: Exception) {
                break
            }
            // 音频和记录都已经在服务端了，本地这份可以删。
            // 之后的分析即使失败也不会丢东西 —— 详情页有「重新处理」。
            PendingQueue.remove(item)
            done++
            // 记录这时候已经在服务端了，先让界面显示出来（显示成「转写中…」）。
            // 等下面的分析跑完再刷新的话，要多等十几秒 ——
            // 用户会以为补传没成功，跑去手动下拉。
            onUploaded()
            try { Api.processCapture(id) } catch (_: Exception) {}
        }
        return done
    }
}
