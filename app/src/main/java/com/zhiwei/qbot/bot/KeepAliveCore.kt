package com.zhiwei.qbot.bot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.zhiwei.qbot.core.Logs
import java.io.File

/**
 * 保活的底层三件套。前台服务 + 电池优化白名单在国产 ROM 上必然掉线，
 * 因为掉线是三条独立链路各自断的，必须分别堵：
 *
 * 1. `PARTIAL_WAKE_LOCK` 只保证 CPU 不睡，**不保证 WiFi 不断**。
 *    息屏后 WiFi 进省电模式会掐掉连接，得再要一把 `WifiLock`，否则表现就是
 *    「服务还活着，网页能开，但 WebSocket 已经死了」。
 * 2. 进程被冻结（cached freezer）时协程的 `delay` 一起冻结，
 *    「每 20 秒巡检」实际可能几十分钟才跑一次。只有 `AlarmManager` 能穿透 Doze 把进程叫醒。
 * 3. 从最近任务划掉卡片走 `onTaskRemoved`，默认不复活。
 *
 * 锁不设超时：超时释放后不会有人来续，等于保活只保了一半。
 */
object KeepAliveCore {

    const val ACTION_KEEPALIVE = "com.zhiwei.qbot.KEEPALIVE"

    private const val ALARM_REQUEST = 2001

    /** 15 分钟：`setAndAllowWhileIdle` 在 Doze 下就是这个量级的下限，再短系统也不批 */
    private const val ALARM_INTERVAL_MS = 15 * 60 * 1000L

    /** 心跳文件：跨进程唯一可靠的存在性证明 */
    private const val BEAT_FILE = "bot.alive"

    /** 心跳超过这个时长就算死了。留足 4 个心跳周期，避免误判后重复拉起 */
    private const val BEAT_STALE_MS = 2 * 60 * 1000L

    private const val WAKE_TAG = "zhiwei:qbot:core"
    private const val WIFI_TAG = "zhiwei:qbot:wifi"

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 主服务写心跳。别的进程（守护、闹钟接收器）读它判断主服务死没死。
     * 静态变量在独立进程里是两份内存，只有文件能跨进程。
     */
    fun beat(context: Context) {
        runCatching {
            File(context.applicationContext.filesDir, BEAT_FILE).writeText(System.currentTimeMillis().toString())
        }
    }

    fun beatFile(context: Context): File = File(context.applicationContext.filesDir, BEAT_FILE)

    fun isAlive(context: Context): Boolean {
        val f = beatFile(context)
        if (!f.exists()) return false
        val at = runCatching { f.readText().trim().toLong() }.getOrNull() ?: return false
        return System.currentTimeMillis() - at < BEAT_STALE_MS
    }

    fun clearBeat(context: Context) {
        runCatching { beatFile(context).delete() }
    }

    fun lock(context: Context) {
        lockCpu(context)
        lockWifi(context)
    }

    private fun lockCpu(context: Context) {
        if (wakeLock?.isHeld == true) return
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Logs.e("CPU 锁获取失败：${it.message}") }.getOrNull()
        if (wakeLock?.isHeld == true) Logs.i("已持有 CPU 锁")
    }

    private fun lockWifi(context: Context) {
        if (wifiLock?.isHeld == true) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = runCatching {
            @Suppress("DEPRECATION")
            wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Logs.e("WiFi 锁获取失败：${it.message}") }.getOrNull()
        if (wifiLock?.isHeld == true) Logs.i("已持有 WiFi 锁")
    }

    fun unlock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    fun isLocked(): Boolean = wakeLock?.isHeld == true

    /**
     * 排下一次唤醒。
     * 用 `setAndAllowWhileIdle` 而不是 `setExactAndAllowWhileIdle`：
     * 前者能穿透 Doze，且不需要 `SCHEDULE_EXACT_ALARM` 权限，不会因为用户没授权而直接抛 SecurityException。
     */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = System.currentTimeMillis() + ALARM_INTERVAL_MS
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(app))
        }.onFailure { Logs.e("保活闹钟排程失败：${it.message}") }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        runCatching { am.cancel(pending(app)) }
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, KeepAliveReceiver::class.java).setAction(ACTION_KEEPALIVE)
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
