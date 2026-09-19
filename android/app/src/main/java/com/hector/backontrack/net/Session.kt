package com.hector.backontrack.net

import android.content.Context

/**
 * 登录态持久化。
 *
 * 必须落盘：开机广播、闹钟广播都发生在**全新进程**里，
 * 内存中的 token 早就没了。没有这个，重启后所有提醒都会静默失效。
 */
object Session {
    private const val PREFS = "bot_session"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_UID = "user_id"
    private const val KEY_REFRESH = "refresh_token"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, token: String, refresh: String?, userId: String) {
        prefs(ctx).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_REFRESH, refresh)
            .putString(KEY_UID, userId)
            .apply()
        Api.accessToken = token
        Api.refreshToken = refresh
        Api.userId = userId
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        Api.signOut()
    }

    fun token(ctx: Context): String? = prefs(ctx).getString(KEY_TOKEN, null)
    fun refresh(ctx: Context): String? = prefs(ctx).getString(KEY_REFRESH, null)

    /** 断网启动时拿它顶上，免得「登录着却没有 user_id」 */
    fun uid(ctx: Context): String? = prefs(ctx).getString(KEY_UID, null)

    /** 在后台组件里恢复登录态。返回是否拿到了 token */
    fun restoreInto(ctx: Context): Boolean {
        Api.appContext = ctx.applicationContext
        if (Api.accessToken != null) return true
        val p = prefs(ctx)
        val t = p.getString(KEY_TOKEN, null) ?: return false
        Api.accessToken = t
        Api.refreshToken = p.getString(KEY_REFRESH, null)
        Api.userId = p.getString(KEY_UID, null)
        return true
    }
}
