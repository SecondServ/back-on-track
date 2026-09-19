package com.hector.backontrack.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import com.hector.backontrack.AppPrefs

/**
 * 整体缩放。
 *
 * 大屏手机上同样的 dp 画出来就是显得肥 —— 屏幕大了，系统密度也跟着高，
 * 一个 48dp 的按钮物理尺寸没变，但它在更大的画面里占的比例没变，看着就「老人机」。
 *
 * 解法不是把每个控件的尺寸挨个改小（那会把版式比例拆散，而且改不完），
 * 而是**直接改 density**：dp 和 sp 按同一个系数缩，间距、字号、圆角、图标一起动，
 * 布局比例一点不变，只是整体小一号。
 *
 * 每台手机各存各的（只写本机 prefs，不上服务端）—— 这是设备的事，不是账号的事。
 */
object UiScale {
    private var loaded = false

    var value by mutableFloatStateOf(1f)
        private set

    /** 在 setContent **之前**调用。放进 Composable 里读盘会先闪一帧原尺寸 */
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        value = AppPrefs.uiScale(ctx)
        loaded = true
    }

    fun set(ctx: Context, scale: Float) {
        value = scale
        AppPrefs.setUiScale(ctx, scale)
    }
}
