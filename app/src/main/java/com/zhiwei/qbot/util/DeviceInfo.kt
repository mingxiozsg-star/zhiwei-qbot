package com.zhiwei.qbot.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.zhiwei.qbot.core.AppInfo
import java.util.Locale

/**
 * 真设备状态：机型、系统、电量、内存、存储、运行时长。
 * /状态 不再只回一句「在线」，而是把这些确切数字摊开。
 */
object DeviceInfo {

    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
    }

    fun appContext(): Context? = ctx

    /** 多行状态文本，给 /状态 用 */
    fun statusText(): String {
        val sb = StringBuilder()
        sb.append("在跑，没掉线。\n")
        sb.append("版本 ").append(AppInfo.VERSION)
        sb.append("｜").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        sb.append("系统 Android ").append(Build.VERSION.RELEASE)
            .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")

        battery()?.let { (pct, charging) ->
            sb.append("电量 ").append(pct).append('%')
            sb.append(if (charging) "（充电中）" else "（未充电）").append('\n')
        }

        memory()?.let { (avail, total) ->
            sb.append("内存 可用 ").append(gb(avail)).append(" / 共 ").append(gb(total)).append('\n')
        }

        storage()?.let { (avail, total) ->
            sb.append("存储 可用 ").append(gb(avail)).append(" / 共 ").append(gb(total)).append('\n')
        }

        sb.append("设备已运行 ").append(duration(SystemClock.elapsedRealtime()))
        sb.append("｜进程 ").append(Runtime.getRuntime().availableProcessors()).append(" 核")
        return sb.toString().trimEnd()
    }

    /** 一行精简版，给通知/日志用 */
    fun shortLine(): String {
        val b = battery()
        val pct = b?.first ?: -1
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
            if (pct >= 0) " · 电量 $pct%" else ""
    }

    private fun battery(): Pair<Int, Boolean>? {
        val c = ctx ?: return null
        val intent: Intent = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return null
        val pct = (level * 100 / scale).coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    private fun memory(): Pair<Long, Long>? {
        val c = ctx ?: return null
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(info) }.onFailure { return null }
        return info.availMem to info.totalMem
    }

    private fun storage(): Pair<Long, Long>? = runCatching {
        val st = StatFs(Environment.getDataDirectory().absolutePath)
        st.availableBytes to st.totalBytes
    }.getOrNull()

    private fun gb(bytes: Long): String =
        String.format(Locale.US, "%.1fGB", bytes / 1073741824.0)

    fun duration(ms: Long): String {
        if (ms <= 0) return "-"
        val sec = ms / 1000
        return when {
            sec < 60 -> "${sec}s"
            sec < 3600 -> "${sec / 60}m"
            sec < 86400 -> "${sec / 3600}h${(sec % 3600) / 60}m"
            else -> "${sec / 86400}d${(sec % 86400) / 3600}h"
        }
    }
}