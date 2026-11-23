package com.zhiwei.qbot.bot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Prefs

/**
 * `AlarmManager` 的落点：进程被 Doze 冻住时，这条闹钟是唯一能把它叫醒的东西。
 *
 * 唤醒后只做两件事：
 * 1. 确认主服务还在跑（不在就拉起）；
 * 2. 把下一次闹钟排上——闹钟是一次性的，不续就等于保活只保了 15 分钟。
 */
class KeepAliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (!Prefs.current.autoStart) return
        if (Prefs.current.appId.isBlank() || Prefs.current.appSecret.isBlank()) return
        Logs.i("保活闹钟唤醒")
        // 无论主服务在不在，都要把下一发排上，否则活不过来
        KeepAliveCore.schedule(app)
        if (!KeepAliveCore.isAlive(app)) {
            Logs.w("保活闹钟：主服务不在，拉起")
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(Intent(app, BotService::class.java).setAction(BotService.ACTION_START))
                } else {
                    app.startService(Intent(app, BotService::class.java).setAction(BotService.ACTION_START))
                }
            }.onFailure { Logs.e("保活闹钟拉起失败：${it.message}") }
        } else {
            // 服务活着但可能已经和服务器失联（进程被冻结时谁都不会发现）。
            // 走 RECONNECT：内部只在 ACK 过期时才真重连，不会无事折腾连接。
            runCatching {
                app.startService(Intent(app, BotService::class.java).setAction(BotService.ACTION_RECONNECT))
            }.onFailure { Logs.w("保活闹钟唤醒连接失败：${it.message}") }
        }
    }
}