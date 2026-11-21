package com.zhiwei.qbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.bot.BotService
import com.zhiwei.qbot.bot.Runtime
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.util.KeepAlive
import kotlinx.coroutines.delay

/**
 * 权限与保活：每一项都能检测状态，也能一键跳到系统页面。
 * 状态每秒刷一次——用户从系统页返回时不用手动刷新。
 */
@Composable
fun PermsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx.findActivity()
    val cfg by Prefs.state.collectAsState()
    val snap by Runtime.snap.collectAsState()
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            tick++
        }
    }

    val notif = remember(tick) { KeepAlive.notificationsEnabled(ctx) }
    val battery = remember(tick) { KeepAlive.ignoringBattery(ctx) }
    val files = remember(tick) { KeepAlive.hasAllFiles() }
    val overlay = remember(tick) { KeepAlive.canOverlay(ctx) }

    Page(title = "权限与保活", subtitle = "少一项都可能半夜掉线", onBack = onBack) {
        CardBox {
            Text("当前机型：${KeepAlive.deviceBrand()}", fontSize = 13.sp, color = TextMain)
            Text(
                if (snap.state == Runtime.State.ONLINE) "服务在线，常驻正常" else "服务未在线，先把下面的项补齐",
                fontSize = 11.sp,
                color = if (snap.state == Runtime.State.ONLINE) Green else TextDim,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (snap.state == Runtime.State.STOPPED) {
                    Primary("启动机器人") {
                        Prefs.saveNow()
                        BotService.start(ctx)
                    }
                } else {
                    Ghost("停止机器人") { BotService.stop(ctx) }
                }
            }
        }

        SectionTitle("必须开")
        PermRow(
            title = "通知权限",
            desc = "前台常驻通知要显示，否则系统随时可能收走服务",
            ok = notif,
            action = "去开",
            onClick = { activity?.let { KeepAlive.requestNotifications(it) } }
        )
        PermRow(
            title = "电池优化白名单",
            desc = "不开的话，息屏一会儿就被系统省电掐掉",
            ok = battery,
            action = "去开",
            onClick = { activity?.let { KeepAlive.requestIgnoreBattery(it) } }
        )
        PermRow(
            title = "自启动",
            desc = "${KeepAlive.deviceBrand()} 的自启动管理页，需要手动允许",
            ok = null,
            action = "去设置",
            onClick = { activity?.let { KeepAlive.openAutostart(it) } }
        )

        SectionTitle("按需开")
        PermRow(
            title = "后台弹出界面",
            desc = "悬浮窗权限。部分 ROM 靠它才允许服务在后台拉起界面",
            ok = overlay,
            action = "去开",
            onClick = { activity?.let { KeepAlive.requestOverlay(it) } }
        )
        PermRow(
            title = "访问所有文件",
            desc = "AI 要发文件、发图时必需；只发文字可以不接",
            ok = files,
            action = "去开",
            onClick = { activity?.let { KeepAlive.requestAllFiles(it) } }
        )

        SectionTitle("说明")
        CardBox {
            Hint("· 电池白名单是「允许后台运行」，跳过去之后选「不限制/允许」。")
            Hint("· 自启动在 MIUI、ColorOS、OriginOS 上名字不一样，跳不到就手动去「设置 → 应用 → 知微QQ机器人」里找。")
            Hint("· 后台弹出界面不是必须，但如果你的机型一切屏就断，建议开上。")
            Hint("· 所有权限只用于让机器人稳定在线，应用不采集任何数据，配置只存本机。")
        }

        SectionTitle("检查一遍")
        CardBox {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        notif && battery && files -> "该开的都开了。"
                        notif && battery -> "常驻够用了，文件权限按需。"
                        else -> "还差几项，掉线大概率就是这里。"
                    },
                    fontSize = 12.sp,
                    color = TextDim,
                    modifier = Modifier.weight(1f)
                )
                TinyBtn("刷新状态") { tick++ }
            }
        }
    }
}

@Composable
private fun PermRow(
    title: String,
    desc: String,
    ok: Boolean?,
    action: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 15.sp, color = TextMain)
                val mark = when (ok) {
                    true -> "已开"
                    false -> "未开"
                    null -> "看机型"
                }
                val color = when (ok) {
                    true -> Green
                    false -> Red
                    null -> Amber
                }
                Text(
                    "  $mark",
                    fontSize = 11.sp,
                    color = color,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(desc, fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 2.dp))
        }
        TinyBtn(action, onClick = onClick)
    }
}