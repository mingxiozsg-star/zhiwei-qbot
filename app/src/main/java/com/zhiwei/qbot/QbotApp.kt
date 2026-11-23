package com.zhiwei.qbot

import android.app.Application
import android.os.Process
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Memory
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Store
import com.zhiwei.qbot.util.DeviceInfo

class QbotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
        Memory.init(this)
        DeviceInfo.init(this)
        installCrashHandler()
    }

    /**
     * 崩溃前先把配置与记忆落盘、把原因写进日志，再把现场交还给系统默认处理。
     * 后台常驻应用最怕的是「配置丢了还查不到为什么」。
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Logs.e("崩溃：${thread.name} → ${error.javaClass.simpleName}: ${error.message}")
                Prefs.saveNow()
                Memory.saveNow()
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }
}