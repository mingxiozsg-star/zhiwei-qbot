package com.zhiwei.qbot.bot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 前台服务承载网关。specialUse 类型是刻意选的：
 * dataSync 在 Android 15 起有 6 小时/天的限制，且不允许开机自启拉起，
 * 而长期在线的机器人连接属于特殊用途，用 specialUse 才能稳定常驻。
 *
 * 这一版加了守护协程：网关不在了就自己拉起来，状态卡死就重连，
 * 不用等用户手动点。
 */
class BotService : Service() {

    companion object {
        const val ACTION_START = "com.zhiwei.qbot.START"
        const val ACTION_STOP = "com.zhiwei.qbot.STOP"
        const val ACTION_RECONNECT = "com.zhiwei.qbot.RECONNECT"
        private const val CHANNEL = "qbot_running"
        private const val NOTIFY_ID = 1001

        /**
         * 服务是否活着。给同进程内的代码用；
         * 跨进程（守护 :guard、闹钟）要读 [KeepAliveCore.isAlive] 的文件心跳，静态变量跨不过去。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            // 主服务与守护一起起来：守护在独立进程，负责主进程被回收后把它拉回来
            GuardService.start(context)
            startWithoutGuard(context)
        }

        /**
         * 只拉主服务，不动守护。
         * 守护进程内部调它，避免「守护拉起主服务 → 主服务又去拉起守护」的循环调用。
         */
        fun startWithoutGuard(context: Context) {
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
            // 先关守护，否则它 20 秒后会把刚停掉的机器人又拉起来
            GuardService.stop(context)
            runCatching {
                context.startService(Intent(context, BotService::class.java).setAction(ACTION_STOP))
            }.onFailure {
                // 服务没在跑时直接停
                context.stopService(Intent(context, BotService::class.java))
            }
        }

        fun reconnect(context: Context) {
            runCatching {
                context.startService(Intent(context, BotService::class.java).setAction(ACTION_RECONNECT))
            }.onFailure { Logs.e("重连指令没发出去：${it.message}") }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gateway: Gateway? = null
    private var stateJob: Job? = null
    private var guardJob: Job? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var foreground = false

    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createChannel()
        goForeground("正在启动…")
        Brain.reconnectHook = { reconnectGateway("应用内请求") }
        // 心跳：别的进程靠这个文件判断主服务死没死，比任何内存标记都可靠
        KeepAliveCore.beat(this)
        KeepAliveCore.schedule(this)
        startNetworkWatch()
        stateJob = scope.launch {
            Runtime.snap.map { it.state }.distinctUntilChanged().collect { updateNotification() }
        }
        guardJob = scope.launch {
            while (!stopping) {
                // 心跳与巡检同一个节拍，避免两处各自维护定时器
                KeepAliveCore.beat(this@BotService)
                delay(30_000)
                if (stopping) break
                keepAliveCheck()
            }
        }
    }

    /**
     * 从最近任务划掉卡片时走这里。
     * 不清心跳、不 stopSelf，并立刻把服务重新拉起来——用户划卡片是想清后台，不是想关机器人。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logs.w("任务被划掉，尝试复活")
        if (Prefs.current.autoStart) {
            KeepAliveCore.beat(this)
            KeepAliveCore.schedule(this)
            runCatching {
                val intent = Intent(this, BotService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            }.onFailure { Logs.e("划掉后复活失败：${it.message}") }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Logs.i("收到停止指令")
                stopEverything()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                // 优先原地踢连接：重建网关会丢掉会话，代价大；
                // 只有网关本身没了才真去重建。
                val g = gateway
                if (g != null && g.isRunning()) g.connectNow("外部请求重连") else reconnectGateway("外部请求重连")
                return START_STICKY
            }
            else -> {
                stopping = false
                ensureRunning()
                // 闹钟唤醒时 service 已经在跑，ensureRunning 会直接返回，
                // 但长连接可能已经半死（socket 还在、ACK 早停了）。
                // connectNow 只在 ACK 超过 30 秒没更新时才真重连，正好当「唤醒即体检」用。
                gateway?.connectNow("保活唤醒")
            }
        }
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
        gateway?.stop()
        gateway = Gateway { type, data -> Brain.onDispatch(type, data) }.also { it.start(scope) }
        if (cfg.wakeLock) acquireWakeLock()
        updateNotification()
        Logs.i("服务已启动")
    }

    /** 守护检查：网关死了就重建；重连卡太久也重建 */
    private fun keepAliveCheck() {
        if (stopping) return
        val cfg = Prefs.current
        if (cfg.appId.isBlank() || cfg.appSecret.isBlank()) return
        val g = gateway
        val snap = Runtime.snap.value
        when {
            g == null || !g.isRunning() -> {
                Logs.w("守护：网关没在跑，重新拉起")
                ensureRunning()
            }
            snap.state == Runtime.State.RECONNECTING &&
                System.currentTimeMillis() - snap.since > 5 * 60_000L -> {
                Logs.w("守护：重连超过 5 分钟，重建连接")
                reconnectGateway("重连超时")
            }
            snap.state == Runtime.State.STOPPED -> {
                Logs.w("守护：状态是已停止但服务还在，重新连接")
                Runtime.setState(Runtime.State.CONNECTING, "重新连接")
                reconnectGateway("状态复位")
            }
        }
    }

    private fun reconnectGateway(reason: String) {
        Logs.i("重建网关：$reason")
        val old = gateway
        gateway = null
        old?.stop()
        ensureRunning()
    }

    /**
     * 监听网络变化。
     *
     * 痛点：Gateway 的退避最多等 30 秒，Doze 下这个 delay 会被冻成几分钟甚至更久。
     * WiFi 从「息屏省电断连」里恢复时，如果还在傻等退避，表现就是「网明明回来了，机器人还是不理人」。
     * 这里在网络可用的一瞬间把连接踢醒，绕开退避。
     */
    private fun startNetworkWatch() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Logs.i("网络恢复，踢一下网关")
                KeepAliveCore.beat(this@BotService)
                gateway?.connectNow("网络恢复")
            }

            override fun onLost(network: Network) {
                Logs.w("网络断开，等待恢复")
                Runtime.setState(Runtime.State.RECONNECTING, "网络断开")
            }
        }
        // 用 registerDefaultNetworkCallback：不关心具体是 WiFi 还是流量，
        // 只要系统默认网络换了就该重连，交给系统判断比手写 NetworkRequest 更准
        val ok = runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess
        if (ok) {
            netCallback = cb
        } else {
            Logs.w("网络监听注册失败，退化为纯看门狗")
        }
    }

    private fun stopNetworkWatch() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        }
    }

    private fun acquireWakeLock() {
        // 统一走 KeepAliveCore：CPU 锁 + WiFi 锁一起拿。
        // 之前只拿 PARTIAL_WAKE_LOCK，息屏后 WiFi 会进省电模式掐掉长连接，
        // 这才是「给了所有权限还是掉线」的真因之一。
        KeepAliveCore.lock(this)
    }

    private fun releaseWakeLock() {
        KeepAliveCore.unlock()
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
        val reconnectIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BotService::class.java).setAction(ACTION_RECONNECT),
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
            .addAction(0, "重连", reconnectIntent)
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
            foreground = true
        }.onFailure { Logs.e("前台通知失败：${it.message}") }
        Logs.i("前台服务：$text")
    }

    private fun updateNotification() {
        if (!foreground) return
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFY_ID, buildNotification())
        }
    }

    private fun stopEverything() {
        stopping = true
        isRunning = false
        stopNetworkWatch()
        gateway?.stop()
        gateway = null
        foreground = false
        releaseWakeLock()
        KeepAliveCore.cancel(this)
        KeepAliveCore.clearBeat(this)
        Brain.reconnectHook = null
        stateJob?.cancel()
        guardJob?.cancel()
        scope.cancel()
        Runtime.stop()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopping = true
        isRunning = false
        stopNetworkWatch()
        guardJob?.cancel()
        runCatching { gateway?.stop() }
        gateway = null
        releaseWakeLock()
        // 只有用户明确点了停止才清心跳；被系统杀掉时不能清，
        // 否则守护进程会以为「用户关了」，再也不拉起
        if (!Prefs.current.autoStart) {
            KeepAliveCore.cancel(this)
            KeepAliveCore.clearBeat(this)
        }
        Brain.reconnectHook = null
        Runtime.stop()
        super.onDestroy()
    }
}