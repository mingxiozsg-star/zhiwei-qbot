package com.zhiwei.qbot.bot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!Prefs.current.autoStart) return
        if (Prefs.current.appId.isBlank() || Prefs.current.appSecret.isBlank()) return
        Logs.i("收到 $action，尝试自动启动")
        runCatching { BotService.start(context) }
            .onFailure { Logs.w("开机自启失败：${it.message}") }
    }
}