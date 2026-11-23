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
        // 开机/更新后先把保活闹钟排上。
        // 只靠 startService 是不够的：进程一旦被系统回收，没有闹钟就再也没人叫醒它。
        KeepAliveCore.schedule(context.applicationContext)
        runCatching { BotService.start(context) }
            .onFailure { Logs.w("开机自启失败：${it.message}") }
    }
}