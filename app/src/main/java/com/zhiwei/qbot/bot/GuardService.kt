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
import androidx.core.app.NotificationCompat
import com.zhiwei.qbot.MainActivity
import com.zhiwei.qbot.R
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 守护进程（跑在独立的 `:guard` 子进程里）。
 *
 * 为什么需要它：
 * 后台断连的根因是**主进程被系统回收**。主进程自己的看门狗跟着一起死，自然没人把它拉起来。
 * 所以这里再起一个独立进程，只做一件事——定期检查主进程还在不在，不在就拉起。
 * 两个进程同时被回收的概率远低于一个。
 *
 * 它自己不承载任何网络逻辑，最轻量，几乎不耗电。
 */
class GuardService : Service() {

    companion object {
        const val ACTION_START = "com.zhiwei.qbot.GUARD_START"
        const val ACTION_STOP = "com.zhiwei.qbot.GUARD_STOP"
        private const val CHANNEL = "qbot_guard"
        private const val NOTIFY_ID = 1002
        private const val CHECK_INTERVAL_MS = 20_000L
        private const val MAIN_PACKAGE = "com.zhiwei.qbot"

        /**
         * 「是否该守护」用文件记录，而不是内存变量。
         * 原因：守护跑在 :guard 独立进程，主进程里的静态字段它读不到。
         * 文件是两个进程都能看到的地方，也是唯一可靠的开关。
         */
        private const val FLAG_FILE = "guard.enabled"

        private fun flagFile(context: Context) = File(context.filesDir, FLAG_FILE)

        private fun writeFlag(context: Context, on: Boolean) {
            runCatching { flagFile(context).writeText(if (on) "1" else "0") }
        }

        /**
         * 守护是否应该存在。
         * 文件不存在时（老版本升级上来）按「开」处理，保持向后兼容。
         */
        fun wanted(context: Context): Boolean {
            val f = flagFile(context)
            if (!f.exists()) return true
            return runCatching { f.readText().trim() != "0" }.getOrDefault(true)
        }

        fun start(context: Context) {
            writeFlag(context, true)
            val intent = Intent(context, GuardService::class.java).setAction(ACTION_START)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Logs.e("守护进程启动失败：${it.message}") }
        }

        fun stop(context: Context) {
            writeFlag(context, false)
            runCatching { context.stopService(Intent(context, GuardService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var running = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        goForeground()
        acquireWakeLock()
        // 守护进程自己也排闹钟：主进程死了、闹钟接收器又在主进程时，
        // 这条独立进程里的闹钟就是最后一道保险
        KeepAliveCore.schedule(this)
        scope.launch {
            var restarts = 0
            while (running) {
                delay(CHECK_INTERVAL_MS)
                if (!running) break
                // 顺手续一次闹钟：进程被冻过之后原闹钟可能已经不在了
                KeepAliveCore.schedule(this@GuardService)
                if (!isMainServiceAlive()) {
                    // 没填凭证时拉起来也只是空转弹通知，等用户配好再管
                    val cfg = Prefs.current
                    if (cfg.appId.isBlank() || cfg.appSecret.isBlank()) continue
                    restarts++
                    Logs.w("守护：主服务不在，第 $restarts 次尝试拉起")
                    runCatching { BotService.startWithoutGuard(applicationContext) }
                        .onFailure { Logs.e("守护：拉起失败 ${it.message}") }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                releaseWakeLock()
                scope.cancel()
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                running = true
                goForeground()
            }
        }
        return START_STICKY
    }

    /**
     * 主进程里的 BotService 是否还在。
     *
     * 不用 `getRunningServices`：Android 8+ 它只返回自己进程的服务，
     * 而守护跑在 `:guard` 子进程，看到的永远只有它自己 → 恒判「主服务不在」，
     * 于是每 20 秒无脑拉起一次，反而把主进程拖进「拉起 → 被杀 → 再拉起」的循环。
     * 现在读主服务写的心跳文件，才是真的知道它还活不活。
     */
    private fun isMainServiceAlive(): Boolean = KeepAliveCore.isAlive(this)

    private fun acquireWakeLock() {
        // 守护进程也要 WiFi 锁：它要定期探活，WiFi 被掐时同样判不出真假
        KeepAliveCore.lock(this)
    }

    private fun releaseWakeLock() {
        KeepAliveCore.unlock()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "守护进程", NotificationManager.IMPORTANCE_MIN).apply {
            description = "保持机器人主进程存活"
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            11,
            Intent(this, GuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bot)
            .setContentTitle("守护中")
            .setContentText("主进程掉线会自动拉起")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, "关闭守护", stop)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFY_ID, n)
            }
        }.onFailure { Logs.e("守护前台通知失败：${it.message}") }
    }

    override fun onDestroy() {
        running = false
        releaseWakeLock()
        scope.cancel()
        // 只有「还不是用户主动关闭」的时候才自愈；否则会变成死循环重启
        if (wanted(applicationContext)) {
            runCatching { start(applicationContext) }
        }
        super.onDestroy()
    }
}