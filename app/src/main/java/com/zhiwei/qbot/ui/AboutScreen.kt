package com.zhiwei.qbot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.bot.Brain
import com.zhiwei.qbot.core.AppInfo
import com.zhiwei.qbot.core.Prefs

/** 关于：版本、开源、配置的搬进搬出。 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by Prefs.state.collectAsState()
    var showExport by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: AppInfo.VERSION
        }.getOrDefault(AppInfo.VERSION)
    }

    Page(title = "关于", subtitle = "v$version", onBack = onBack) {
        CardBox {
            Text("知微QQ机器人", fontSize = 18.sp, color = TextMain, fontWeight = FontWeight.Bold)
            Text("v$version · 本地常驻 · 只走官方接口", fontSize = 11.sp, color = TextDim)
            Text(
                "把 QQ 官方机器人接到你自己的模型上：自定义人设、分句发送、菜单按钮、语音与表情。",
                fontSize = 12.sp,
                color = TextDim,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        SectionTitle("开源")
        CardBox {
            ActionRow("GitHub 仓库", AppInfo.REPO.removePrefix("https://")) {
                openUrl(ctx, AppInfo.REPO)
            }
            Line()
            ActionRow("更新日志", "每个版本改了什么") {
                openUrl(ctx, AppInfo.REPO + "/releases")
            }
            Line()
            ActionRow("QQ 开放平台", "机器人后台、文档、额度") {
                openUrl(ctx, "https://bot.q.qq.com")
            }
            Hint("代码按 MIT 许可开源；QQ 接口的使用要遵守《QQ 开放平台开发者服务协议》，别拿它刷消息。")
        }

        SectionTitle("配置")
        CardBox {
            ActionRow("导出配置", "生成一段 JSON，换设备能带走") { showExport = true }
            Line()
            ActionRow("导入配置", "粘贴 JSON 覆盖当前设置") { showImport = true }
            Line()
            ActionRow("清空对话记忆", "本机所有会话上下文") {
                Brain.clearAllHistory()
                tip(ctx, "已清空本机记忆")
            }
            Hint("Key、密钥都在导出内容里，发给别人前先想清楚。")
        }

        SectionTitle("当前状态")
        CardBox {
            ActionRow("AppID", if (cfg.appId.isBlank()) "未填" else cfg.appId) {}
            Line()
            ActionRow("模型", cfg.ai.model.ifBlank { "未填" }) {}
            Line()
            ActionRow("人设", cfg.persona.name) {}
            Line()
            ActionRow("菜单 / 表情", "${cfg.menus.size} 个 / ${cfg.stickers.size} 张") {}
        }

        SectionTitle("小心这些")
        CardBox {
            Hint("· 本应用不是外挂，不碰账号密码，不给你的 QQ 号做任何登录。")
            Hint("· 被动回复有次数上限（同一条消息最多 5 次），分句别设太多。")
            Hint("· 机器人说的话由模型生成，跑之前先自己测几句，别让它替你回重要客户。")
        }

        SectionTitle("危险区")
        CardBox {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TinyBtn("恢复默认设置", color = Red) { showReset = true }
            }
            Hint("会清掉凭证、人设、菜单、表情配置，表情图片文件不动。")
        }

        Text(
            "始源创想科技IT · 问题反馈走 GitHub Issues",
            fontSize = 10.sp,
            color = TextDim,
            modifier = Modifier.padding(top = 16.dp, start = 4.dp, bottom = 10.dp)
        )
    }

    if (showExport) {
        val json = remember { Prefs.exportJson() }
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("导出配置", fontSize = 16.sp, color = TextMain) },
            text = {
                Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(json, fontSize = 10.sp, color = TextDim)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("qbot-config", json))
                    tip(ctx, "已复制到剪贴板")
                    showExport = false
                }) { Text("复制", color = Pink) }
            },
            dismissButton = {
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "知微QQ机器人 配置")
                        putExtra(Intent.EXTRA_TEXT, json)
                    }
                    runCatching {
                        ctx.startActivity(Intent.createChooser(intent, "分享配置").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    showExport = false
                }) { Text("分享", color = TextDim) }
            }
        )
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false },
            title = { Text("导入配置", fontSize = 16.sp, color = TextMain) },
            text = {
                Column {
                    Field("粘贴 JSON", importText, { importText = it }, singleLine = false, minLines = 6)
                    Hint("会直接覆盖当前所有设置，包括凭证。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.importJson(importText).onSuccess {
                        tip(ctx, "已导入")
                        showImport = false
                        importText = ""
                    }.onFailure {
                        tip(ctx, "格式不对：${it.message}")
                    }
                }) { Text("导入", color = Pink) }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false }) { Text("取消", color = TextDim) }
            }
        )
    }

    if (showReset) {
        SimpleDialog(
            title = "恢复默认设置？",
            text = "凭证、人设、菜单、表情列表都会被清掉，表情图片文件还在。这一步没法撤销。",
            confirm = "清空",
            dismiss = "算了",
            onConfirm = {
                Prefs.resetAll()
                showReset = false
                tip(ctx, "已恢复默认")
            },
            onDismiss = { showReset = false }
        )
    }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { tip(ctx, "打不开浏览器") }
}