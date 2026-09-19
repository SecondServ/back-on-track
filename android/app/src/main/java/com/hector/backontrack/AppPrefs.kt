package com.hector.backontrack

import android.content.Context

/** 用户偏好（只存本机，不同步） */
object AppPrefs {
    private const val PREFS = "bot_prefs"
    private const val KEY_HIDE_DONE = "hide_completed_tasks"
    private const val KEY_SORT_DESC = "capture_sort_desc"
    private const val KEY_PROFILE_PROMPTED = "profile_prompted"
    private const val KEY_NUDGE_DAYS = "nudge_days"
    private const val KEY_NUDGE_LAST = "nudge_last_at"
    private const val KEY_DEEP_THINK = "deep_think"
    private const val KEY_AI_PROVIDER = "ai_provider"

    /** 已完成的待办只显示这么多天内的 —— 再久远的划线条目只会把列表拉长，没有回看价值 */
    const val DONE_KEEP_DAYS = 30

    /** 垃圾箱里的记录保留多少天，过期由 App 打开时永久清除 */
    const val TRASH_KEEP_DAYS = 30

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hideCompletedTasks(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HIDE_DONE, false)

    fun setHideCompletedTasks(ctx: Context, hide: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_HIDE_DONE, hide).apply()

    /** 是否已经请他建过档案。只问一次，拒绝了就不再打扰 */
    fun profilePrompted(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PROFILE_PROMPTED, false)

    fun setProfilePrompted(ctx: Context, done: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_PROFILE_PROMPTED, done).apply()

    /**
     * 没设提醒的待办，每隔几天推送一次「还没做完」。0 = 关掉。
     * 默认 7 天 —— 「记下来了但不做」是 PRD 里的另一半痛点，
     * 不推一把，没有时间点的待办就会在列表里躺到天荒地老。
     */
    fun nudgeDays(ctx: Context): Int = prefs(ctx).getInt(KEY_NUDGE_DAYS, 7)

    fun setNudgeDays(ctx: Context, days: Int) =
        prefs(ctx).edit().putInt(KEY_NUDGE_DAYS, days).apply()

    fun nudgeLastAt(ctx: Context): Long = prefs(ctx).getLong(KEY_NUDGE_LAST, 0L)

    fun setNudgeLastAt(ctx: Context, at: Long) =
        prefs(ctx).edit().putLong(KEY_NUDGE_LAST, at).apply()

    /**
     * 问问题时让模型多想一会儿。**默认关**：随口一问要的是快，
     * 需要深想的场合由用户自己在对话顶部打开。
     */
    fun deepThink(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DEEP_THINK, false)

    fun setDeepThink(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_DEEP_THINK, on).apply()

    /**
     * 服务端回答用的是哪家模型（openai / anthropic）。
     * key 只在服务端，客户端无从知道，所以由回答接口回报一次、记在本地，
     * 下次一进对话就能画对头像，不用等回答回来才换。
     */
    fun aiProvider(ctx: Context): String? = prefs(ctx).getString(KEY_AI_PROVIDER, null)

    fun setAiProvider(ctx: Context, provider: String) =
        prefs(ctx).edit().putString(KEY_AI_PROVIDER, provider).apply()

    fun captureSortDesc(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SORT_DESC, true)

    fun setCaptureSortDesc(ctx: Context, desc: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SORT_DESC, desc).apply()
}
