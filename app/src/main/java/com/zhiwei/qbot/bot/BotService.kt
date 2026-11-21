package com.zhiwei.qbot.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.zhiwei.qbot.MainActivity
import com.zhiwei.qbot.R
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.net.Gateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 前台服务承载网关。specialUse 类型是刻意选的：
 * dataSync 在 Android 15 起有 6 小时/天的限制，且不允许开机自启拉起，
 * 而长期在线的机器人连接属于特殊用途，用 specialUse 才能稳定常驻。
 */
class BotService : Service() {

    companion object {
        const val ACTION_START = "com.zhiwei.qbot.START"
        const val ACTION_STOP = "com.zhiwei.qbot.STOP"
        private const val CHANNEL = "qbot_running"
        private const val NOTIFY_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, BotService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Logs.e("启动服务失败：${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, BotService::class.java).setAction(ACTION_STOP))
            }.onFailure {
                // 服务没在跑时直接停
                context.stopService(Intent(context, BotService::class.java))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gateway: Gateway? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground("正在启动…")
        stateJob = scope.launch {
            Runtime.snap.map { it.state }.distinctUntilChanged().collect { updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Logs.i("收到停止指令")
            stopEverything()
            return START_NOT_STICKY
        }
        ensureRunning()
        return START_STICKY
    }

    private fun ensureRunning() {
        if (gateway?.isRunning() == true) return
        val cfg = Prefs.current
        if (cfg.appId.isBlank() || cfg.appSecret.isBlank()) {
            goForeground("还没填 AppID / AppSecret")
            Logs.w("未配置机器人凭证，服务空转")
            return
        }
        Runtime.setState(Runtime.State.CONNECTING, "启动中")
        gateway = Gateway { type, data -> Brain.onDispatch(type, data) }.also { it.start(scope) }
        if (cfg.wakeLock) acquireWakeLock()
        updateNotification()
        Logs.i("服务已启动")
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zhiwei:qbot").apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, "机器人运行状态", NotificationManager.IMPORTANCE_LOW).apply {
            description = "保持 QQ 机器人长连接"
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val snap = Runtime.snap.value
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentTitle(snap.botName.ifBlank { Prefs.current.persona.name } + " · " + snap.detail)
            .setContentText("收 ${snap.received}｜发 ${snap.sent}｜失败 ${snap.fails}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFY_ID, n)
            }
        }.onFailure { Logs.e("前台通知失败：${it.message}") }
        Logs.i("前台服务：$text")
    }

    private fun updateNotification() {
        if (!isForeground) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, buildNotification())
        }
    }

    private fun stopEverything() {
        gateway?.stop()
        gateway = null
        releaseWakeLock()
        stateJob?.cancel()
        scope.cancel()
        Runtime.stop()
        runCatching { stopForeground(true) }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { gateway?.stop() }
        gateway = null
        releaseWakeLock()
        Runtime.stop()
        super.onDestroy()
    }
}