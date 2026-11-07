package com.hector.backontrack.ui

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.abs

/**
 * 笔记正文的撤销 / 重做。
 *
 * 为什么要有：正文停手 1.2 秒就自动保存。以前手一滑删掉一大段，
 * 下一秒删完的版本就存上去了，再也找不回来。
 *
 * 怎么分步：不是每敲一个字算一步（那撤销五十次才退回一句话），而是
 *   - 连续打字、中间停顿不超过 1.5 秒 → 合成一步
 *   - 一下子变了不止一个字（删掉一大段、粘贴、拼音上屏、语音插入）→ 单独一步
 *   - 从「打字」换成「删字」或反过来 → 新的一步
 * 最多记 50 步。只活在这一页里，退出笔记就清空。
 */
class EditHistory(private val limit: Int = 50) {
    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastAt = 0L
    /** 1 = 在加字，-1 = 在删字，0 = 上一步是一次性的大改动，下一下必然另起一步 */
    private var lastKind = 0

    /** 栈一变它就 +1，让撤销 / 重做按钮跟着刷新可用状态 */
    var version by mutableIntStateOf(0)
        private set

    val canUndo: Boolean get() { version; return undoStack.isNotEmpty() }
    val canRedo: Boolean get() { version; return redoStack.isNotEmpty() }

    /** 用户在输入框里改了字。只动光标不算 */
    fun onEdit(before: TextFieldValue, after: TextFieldValue) {
        if (before.text == after.text) return
        val delta = after.text.length - before.text.length
        val kind = if (delta > 0) 1 else if (delta < 0) -1 else 0
        val big = abs(delta) > 1 || kind == 0
        val now = SystemClock.uptimeMillis()
        if (big || kind != lastKind || now - lastAt > 1500 || undoStack.isEmpty()) push(before)
        lastAt = now
        lastKind = if (big) 0 else kind
        redoStack.clear()
        version++
    }

    /** 程序替你改正文之前调（语音插入、替换原文），那一下也能撤回 */
    fun checkpoint(before: TextFieldValue) {
        push(before)
        lastKind = 0
        redoStack.clear()
        version++
    }

    fun undo(current: TextFieldValue): TextFieldValue? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastKind = 0
        version++
        return prev
    }

    fun redo(current: TextFieldValue): TextFieldValue? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastKind = 0
        version++
        return next
    }

    private fun push(v: TextFieldValue) {
        if (undoStack.lastOrNull()?.text == v.text) return
        undoStack.addLast(v)
        while (undoStack.size > limit) undoStack.removeFirst()
    }
}
