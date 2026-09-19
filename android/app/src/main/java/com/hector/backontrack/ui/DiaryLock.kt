package com.hector.backontrack.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal

/**
 * 日记加锁。
 *
 * 用系统的锁屏凭据（指纹 / 面容 / 手机密码），**不自己造一套密码**：
 * 自建密码意味着要存、要校验、要考虑改密和找回，每一步都是能出错的地方；
 * 而系统这条路上，App 从头到尾拿不到任何凭据，只收一个「过了没过」。
 *
 * 代价是它跟解锁手机是同一套凭据 —— 如果需要「手机已解锁也打不开日记」，
 * 那就得换成独立密码，是另一套做法。
 */
fun promptDiaryUnlock(activity: Activity, onResult: (Boolean) -> Unit): Boolean {
    return try {
        BiometricPrompt.Builder(activity)
            .setTitle("日记已加锁")
            .setSubtitle("用指纹或手机密码解锁")
            .setAllowedAuthenticators(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
            .build()
            .authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(
                        result: BiometricPrompt.AuthenticationResult,
                    ) = onResult(true)

                    // 用户取消、按返回、验证失败次数过多，都走这里。
                    // 一律当成「没通过」——宁可多验一次，也不能默认放行
                    override fun onAuthenticationError(code: Int, msg: CharSequence) =
                        onResult(false)
                },
            )
        true
    } catch (_: Exception) {
        // 连验证框都没起来（设备没设锁屏、系统不支持等）。
        // 返回 false 让调用方能把话说出来 —— 点了没反应最难排查
        false
    }
}

/**
 * 从 Compose 的 LocalContext 里把 Activity 剥出来。
 *
 * `LocalContext.current as? Activity` 经常拿到 null：那个 Context 往往是
 * 包了一层的 ContextThemeWrapper，不是 Activity 本身。
 * 之前就是栽在这儿 —— 点「日记」完全没反应，验证框根本没被调起。
 */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
