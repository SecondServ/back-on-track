package com.hector.backontrack

import android.app.Application
import com.hector.backontrack.net.Api

/** 让 Api 在任何入口（Activity / 闹钟广播 / 开机广播）都能拿到 Context 去续期 token */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Api.appContext = applicationContext
    }
}
