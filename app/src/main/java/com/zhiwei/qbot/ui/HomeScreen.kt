package com.zhiwei.qbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.zhiwei.qbot.net.AiApi
import com.zhiwei.qbot.net.QqApi
import com.zhiwei.qbot.util.KeepAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun stateColor(state: Runtime.State) = when (state) {
    Runtime.State.ONLINE -> Green
    Runtime.State.CONNECTING, Runtime.State.RECONNECTING -> Amber
    Runtime.State.ERROR -> Red
    else -> TextDim
}

private fun stateText(state: Runtime.State) = when (state) {
    Runtime.State.ONLINE -> "在线"
    Runtime.State.CONNECTING -> "连接中"
    Runtime.State.RECONNECTING -> "重连中"
    Runtime.State.ERROR -> "出错"
    else -> "未启动"
}

@Composable
fun HomeScreen(onOpen: (String) -> Unit, onTalk: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val snap by Runtime.snap.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checkText by remember { mutableStateOf<String?>(null) }
    val running = snap.state == Runtime.State.ONLINE ||
        snap.state == Runtime.State.CONNECTING ||
        snap.state == Runtime.State.RECONNECTING

    Page(title = "知微 QQ 机器人", subtitle = "v1.0.0 · 本地常驻 · 官方接口") {
        if (cfg.appId.isBlank() || cfg.appSecret.isBlank() || cfg.ai.apiKey.isBlank()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardBg2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen("guide") }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("还差几步就能跑起来", fontSize = 15.sp, color = Pink, fontWeight = FontWeight.Bold)
                    Text(
                        "填好 AppID / AppSecret、模型接口，再点一下启动。点这里看新手引导。",
                        fontSize = 12.sp,
                        color = TextDim,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        CardBox {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(stateColor(snap.state), RoundedCornerShape(10.dp))
                )
                Text(
                    "  " + stateText(snap.state),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = stateColor(snap.state)
                )
                Text(
                    "   " + snap.detail,
                    fontSize = 11.sp,
                    color = TextDim
                )
            }
            Text(
                text = if (snap.botName.isBlank()) cfg.persona.name else snap.botName,
                fontSize = 13.sp,
                color = TextMain,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                MiniStat("收到", snap.received.toString())
                MiniStat("发出", snap.sent.toString())
                MiniStat("失败", snap.fails.toString())
                MiniStat("在线", uptime(snap.since, snap.state))
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (running) {
                    Ghost("停止机器人") { BotService.stop(ctx) }
                } else {
                    Primary("启动机器人") {
                        Prefs.saveNow()
                        BotService.start(ctx)
                    }
                }
                Ghost("测试对话", onClick = onTalk)
                Ghost(if (checking) "自检中…" else "一键自检", onClick = {
                    if (checking) return@Ghost
                    checking = true
                    scope.launch {
                        val sb = StringBuilder()
                        val token = withContext(Dispatchers.IO) { runCatching { QqApi.accessToken(force = true) } }
                        sb.append(if (token.isSuccess) "✓ 机器人凭证\n" else "✗ 凭证：${token.exceptionOrNull()?.message}\n")
                        val gw = withContext(Dispatchers.IO) { runCatching { QqApi.gatewayUrl() } }
                        sb.append(if (gw.isSuccess) "✓ 网关地址\n" else "✗ 网关：${gw.exceptionOrNull()?.message}\n")
                        val ai = withContext(Dispatchers.IO) {
                            runCatching {
                                AiApi.chat(
                                    listOf(
                                        AiApi.Msg("system", "只回一个字：好"),
                                        AiApi.Msg("user", "在吗")
                                    ),
                                    Prefs.current.ai
                                )
                            }
                        }
                        sb.append(if (ai.isSuccess) "✓ 模型接口\n" else "✗ 模型：${ai.exceptionOrNull()?.message}\n")
                        sb.append(if (KeepAlive.ignoringBattery(ctx)) "✓ 电池白名单\n" else "✗ 电池白名单未开\n")
                        sb.append(if (KeepAlive.notificationsEnabled(ctx)) "✓ 通知权限\n" else "✗ 通知权限未开\n")
                        sb.append(if (KeepAlive.hasAllFiles()) "✓ 文件权限\n" else "△ 文件权限未开（只影响发图）")
                        checking = false
                        checkText = sb.toString()
                    }
                })
            }
            if (running) {
                Hint("后台常驻已开启。把应用加入电池白名单会更稳。")
            }
        }

        SectionTitle("配置")
        QuickTile("🔌", "接入与连接", "AppID / 密钥 / 备用线路", onClick = { onOpen("connect") })
        QuickTile("🪪", "人设与回复", "性格、语气、开场白、分句", onClick = { onOpen("persona") })
        QuickTile("⌨️", "菜单按钮", "像图片那样发按钮菜单", onClick = { onOpen("menus") })
        QuickTile("🧠", "模型接口", "OpenAI 兼容格式", onClick = { onOpen("ai") })
        QuickTile("🗣️", "语音 TTS", "自配接口，发真语音", onClick = { onOpen("voice") })
        QuickTile("🖼️", "表情包", "自己加图，AI 会挑着发", onClick = { onOpen("stickers") })

        SectionTitle("系统")
        QuickTile(
            "🛡️",
            "权限与保活",
            if (KeepAlive.ignoringBattery(ctx)) "电池白名单已开" else "电池白名单未开，点这里处理",
            onClick = { onOpen("perms") }
        )
        QuickTile("📜", "运行日志", "连接、收发、报错全在这里", onClick = { onOpen("logs") })
        QuickTile("❓", "新手引导", "四步走完就能用", onClick = { onOpen("guide") })

        Text(
            "开源地址 github.com/mingxiozsg-star · 只走 QQ 官方机器人接口",
            fontSize = 10.sp,
            color = TextDim,
            modifier = Modifier.padding(top = 18.dp, start = 4.dp)
        )
    }

    checkText?.let { text ->
        SimpleDialog(
            title = "自检结果",
            text = text,
            confirm = "知道了",
            onConfirm = { checkText = null },
            onDismiss = { checkText = null }
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    Column {
        Text(value, fontSize = 16.sp, color = TextMain, fontWeight = FontWeight.SemiBold)
        Text(label, fontSize = 10.sp, color = TextDim)
    }
}

private fun uptime(since: Long, state: Runtime.State): String {
    if (state != Runtime.State.ONLINE || since <= 0) return "-"
    val sec = (System.currentTimeMillis() - since) / 1000
    return when {
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m"
        else -> "${sec / 3600}h"
    }
}

@Composable
fun QuickTile(emoji: String, title: String, desc: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = CardBg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, fontSize = 20.sp)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontSize = 15.sp, color = TextMain)
                Text(desc, fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(top = 2.dp))
            }
            Text("›", fontSize = 18.sp, color = TextDim)
        }
    }
}

@Composable
fun BottomBar(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBg)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        Tab("🏠", "主页", current == "home") { onSelect("home") }
        Tab("💬", "测试", current == "talk") { onSelect("talk") }
        Tab("⚙️", "设置", current == "settings") { onSelect("settings") }
    }
}

@Composable
private fun Tab(emoji: String, label: String, active: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 4.dp)
    ) {
        Text(emoji, fontSize = 17.sp)
        Text(label, fontSize = 10.sp, color = if (active) Pink else TextDim)
    }
}

@Composable
fun SettingsScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    Page(title = "设置", subtitle = "所有数据都存在本机", onBack = onBack) {
        SectionTitle("运行")
        CardBox {
            ActionRow("接入与连接", "AppID、密钥、备用网关") { onOpen("connect") }
            Line()
            ActionRow("权限与保活", "电池白名单、自启动、所有文件") { onOpen("perms") }
            Line()
            ActionRow("运行日志", "最近的收发与报错") { onOpen("logs") }
        }

        SectionTitle("机器人")
        CardBox {
            ActionRow("人设与回复", cfg.persona.name) { onOpen("persona") }
            Line()
            ActionRow("回复与分句", if (cfg.send.split) "分句已开 · 最多 ${cfg.send.maxSegments} 段" else "整段发送") { onOpen("send") }
            Line()
            ActionRow("模型接口", cfg.ai.model) { onOpen("ai") }
            Line()
            ActionRow("菜单按钮", "${cfg.menus.size} 个菜单") { onOpen("menus") }
        }

        SectionTitle("内容")
        CardBox {
            ActionRow("语音 TTS", if (cfg.tts.enabled) "已开启" else "未开启") { onOpen("voice") }
            Line()
            ActionRow("表情包", "${cfg.stickers.size} 张") { onOpen("stickers") }
        }

        SectionTitle("其他")
        CardBox {
            ActionRow("新手引导", "重新看一遍怎么配") { onOpen("guide") }
            Line()
            ActionRow("关于与开源", "版本、许可、联系方式") { onOpen("about") }
        }
    }
}