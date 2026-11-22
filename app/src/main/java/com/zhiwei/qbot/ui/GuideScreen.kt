package com.zhiwei.qbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 新手引导：四步跑通，每步都能点进去配，配完回来能看见「已完成」。
 * 目标很明确——让第一次用的人不查文档也能让它开口。
 */
@Composable
fun GuideScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val ctx = LocalContext.current
    val snap by Runtime.snap.collectAsState()
    var tick by remember { mutableStateOf(0) }

    val hasCredential = cfg.appId.isNotBlank() && cfg.appSecret.isNotBlank()
    val hasAi = cfg.ai.apiKey.isNotBlank() && cfg.ai.model.isNotBlank()
    val hasPerm = remember(tick) {
        KeepAlive.ignoringBattery(ctx) && KeepAlive.notificationsEnabled(ctx)
    }

    Page(title = "新手引导", subtitle = "照顺序来，别跳步", onBack = onBack) {
        CardBox {
            Text("这套工具只走 QQ 官方机器人接口，不需要外挂、不需要扫码登录。", fontSize = 13.sp, color = TextMain)
            Hint("整个流程大约 10 分钟，其中 5 分钟在 QQ 开放平台点按钮。")
        }

        StepCard(
            index = 1,
            title = "建机器人，拿 AppID / AppSecret",
            desc = "去 q.qq.com 建一个机器人应用 → 开发设置里抄下 AppID 和 AppSecret。" +
                "然后把「接收消息类型」改成全部，机器人会被拉进群才能收到群消息。",
            done = hasCredential,
            action = "去填凭证",
            onAction = { onOpen("connect") }
        )

        StepCard(
            index = 2,
            title = "填模型接口",
            desc = "任何 OpenAI 兼容接口都行：官方、中转站、本地都认。填 BaseURL + Key + 模型名，" +
                "点一下「测试一句」，能回话就说明通了。",
            done = hasAi,
            action = "去配模型",
            onAction = { onOpen("ai") }
        )

        StepCard(
            index = 3,
            title = "调人设（可以先不改）",
            desc = "默认人设就是「知微」：短句、直接、不客服腔。想换成自己的角色，" +
                "改名字、身份、性格、说话风格、禁止项就行，还能直接预览系统提示词。",
            done = true,
            action = "看看人设",
            onAction = { onOpen("persona") }
        )

        StepCard(
            index = 4,
            title = "开权限，别让它半夜掉线",
            desc = "通知、电池白名单必须开；自启动按机型手动允许；要发文件再开「访问所有文件」。",
            done = hasPerm,
            action = "去开权限",
            onAction = {
                tick++
                onOpen("perms")
            }
        )

        StepCard(
            index = 5,
            title = "启动，然后在 QQ 里说话",
            desc = "点启动后状态会变成「在线」。把机器人拉进群 @它，或者私聊它；" +
                "发 /菜单 看按钮，发 /状态 看运行情况。",
            done = snap.state == Runtime.State.ONLINE,
            action = if (snap.state == Runtime.State.ONLINE) "已在线" else "现在启动",
            onAction = {
                Prefs.saveNow()
                BotService.start(ctx)
            }
        )

        SectionTitle("可选但好玩")
        CardBox {
            ActionRow("菜单按钮", "像截图那样，一点就回") { onOpen("menus") }
            Line()
            ActionRow("表情包", "自己加图，AI 会挑着发") { onOpen("stickers") }
            Line()
            ActionRow("语音 TTS", "自配接口，发真语音") { onOpen("voice") }
            Line()
            ActionRow("回复与分句", "一句一句往外蹦，像真人") { onOpen("send") }
        }

        SectionTitle("群里的常规操作")
        CardBox {
            Hint("/菜单 —— 弹出按钮菜单（没开通按钮权限会退回文字菜单）")
            Hint("/状态 —— 看在线时长、收发计数、当前线路")
            Hint("/语音 你好呀 —— 让机器人说一句（仅管理员）")
            Hint("/表情 名字 —— 发指定表情")
            Hint("/文件 /sdcard/xxx.jpg —— 只能发图片，普通文件官方未开放")
            Hint("/分句开、/分句关 —— 临时改发送方式")
            Hint("/清除 —— 清掉这段对话的记忆")
        }

        SectionTitle("出问题了先看这里")
        CardBox {
            Hint("1. 日志里出现「获取 AccessToken 失败」→ AppID/AppSecret 抄错了，或机器人还没上线。")
            Hint("2. 日志里有 WebSocket 断开 → 网络或权限问题，先看电池白名单。")
            Hint("3. 能收到消息但没回 → 检查模型接口与人设里的禁止项；群里要 @ 它。")
            Hint("4. 发送报「被动回复次数超限」→ 分句太多，把「最多几段」调到 2~3。")
            ActionRow("打开运行日志", "所有报错的原话都在里面") { onOpen("logs") }
        }

        SectionTitle("最后")
        CardBox {
            Text("配好之后基本不用再打开这个应用，它在后台挂着就行。", fontSize = 12.sp, color = TextDim)
            Hint("想关掉：回主页点「停止机器人」，或者在通知栏点停止。")
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    desc: String,
    done: Boolean,
    action: String,
    onAction: () -> Unit
) {
    CardBox {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .background(if (done) Green else Pink, CircleShape)
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text("$index", fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.Black, fontWeight = FontWeight.Bold)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(title, fontSize = 15.sp, color = TextMain, fontWeight = FontWeight.SemiBold)
                Text(desc, fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TinyBtn(if (done) "$action（已完成）" else action, color = if (done) Green else Cyan, onClick = onAction)
                }
            }
        }
    }
}