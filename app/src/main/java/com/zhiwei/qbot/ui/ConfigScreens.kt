package com.zhiwei.qbot.ui

import android.content.Context
import android.widget.Toast
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.bot.Brain
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.net.AiApi
import com.zhiwei.qbot.net.QqApi
import com.zhiwei.qbot.net.QqApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/* ---------------- 接入 ---------------- */

@Composable
fun ConnectScreen(onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }

    Page(title = "接入与连接", subtitle = "QQ 开放平台的机器人凭证", onBack = onBack) {
        CardBox {
            Field("AppID", cfg.appId, { v ->
                Prefs.update { it.copy(appId = v.trim()) }
            }, hint = "开放平台 → 开发设置 → AppID")
            Field("AppSecret", cfg.appSecret, { v ->
                Prefs.update { it.copy(appSecret = v.trim()) }
            }, hint = "和 AppID 在一起，别外传", secret = true)
            ToggleRow("沙箱环境", "调试期可开，正式上线记得关", cfg.sandbox) { v ->
                Prefs.update { it.copy(sandbox = v) }
            }
            Hint("机器人要能在群里收消息，开放平台里的「接收消息类型」必须设为「全部」，且机器人得是群成员。")
        }

        SectionTitle("管理员")
        CardBox {
            Field("管理员 openid", cfg.owners, { v ->
                Prefs.update { it.copy(owners = v) }
            }, hint = "逗号分隔；留空则所有人可用 /语音 /文件")
            Hint("openid 不是 QQ 号。先让对方发一句「/状态」，日志里会带出来。")
        }

        SectionTitle("线路")
        CardBox {
            Field("接口域名", cfg.apiBase, { v ->
                Prefs.update { it.copy(apiBase = v.trim()) }
            }, hint = "默认 https://api.sgroup.qq.com")
            Field("备用网关", cfg.backupGateway, { v ->
                Prefs.update { it.copy(backupGateway = v.trim()) }
            }, hint = "可选。连续失败两次后切过去")
            Hint("默认走官方主线路，异常时自动重连；备用网关只在你填了的情况下才会用。")
        }

        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Primary(if (busy) "检测中…" else "测试凭证", enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            QqApi.accessToken(force = true)
                            val gateway = QqApi.gatewayUrl()
                            gateway
                        }
                    }
                    busy = false
                    result = if (r.isSuccess) {
                        "凭证有效，网关：\n${r.getOrNull()}" to "连接正常"
                    } else {
                        (r.exceptionOrNull()?.message ?: "未知错误") to "连接失败"
                    }
                }
            })
            Ghost("保存", onClick = {
                Prefs.saveNow()
                toast(ctx, "已保存")
            })
        }
    }

    result?.let { (message, title) ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(title, fontSize = 16.sp) },
            text = { Text(message, fontSize = 12.sp, color = TextDim) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("好", color = Pink) } }
        )
    }
}

/* ---------------- 人设 ---------------- */

@Composable
fun PersonaScreen(onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val p = cfg.persona
    var showPrompt by remember { mutableStateOf(false) }

    Page(title = "人设与回复", subtitle = "决定它在群里像不像个人", onBack = onBack) {
        CardBox {
            Field("名字", p.name, { v -> Prefs.update { it.copy(persona = it.persona.copy(name = v)) } })
            Field("身份", p.identity, { v -> Prefs.update { it.copy(persona = it.persona.copy(identity = v)) } }, singleLine = false, minLines = 2)
            Field("性格", p.traits, { v -> Prefs.update { it.copy(persona = it.persona.copy(traits = v)) } }, singleLine = false, minLines = 3)
            Field("说话风格", p.style, { v -> Prefs.update { it.copy(persona = it.persona.copy(style = v)) } }, singleLine = false, minLines = 3)
            Field("禁止项", p.bans, { v -> Prefs.update { it.copy(persona = it.persona.copy(bans = v)) } }, singleLine = false, minLines = 3)
            Field("开场白", p.greeting, { v -> Prefs.update { it.copy(persona = it.persona.copy(greeting = v)) } }, singleLine = false, minLines = 2)
            Field("补充设定", p.extra, { v -> Prefs.update { it.copy(persona = it.persona.copy(extra = v)) } }, hint = "例子：喜欢叫对方“笨蛋”；只聊游戏和代码", singleLine = false, minLines = 3)
            Hint("记忆会随聊天自动累积：聊熟的人会被记住，在群里被 @ 时也认得，不用每次重新自我介绍。")
        }

        SectionTitle("生效范围")
        CardBox {
            ToggleRow("群聊里回话", "被 @ 时才回", cfg.aiGroup) { v -> Prefs.update { it.copy(aiGroup = v) } }
            Line()
            ToggleRow("私聊里回话", "单聊直接回", cfg.aiC2c) { v -> Prefs.update { it.copy(aiC2c = v) } }
        }

        SectionTitle("看看实际提示词")
        CardBox {
            Hint("这段文字会连同历史对话一起发给模型。改人设优先改这里。")
            Ghost("预览完整提示词") { showPrompt = true }
        }
    }

    if (showPrompt) {
        val prompt = remember { Brain.previewPrompt() }
        AlertDialog(
            onDismissRequest = { showPrompt = false },
            title = { Text("系统提示词", fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) { Text(prompt, fontSize = 11.sp, color = TextDim) }
            },
            confirmButton = { TextButton(onClick = { showPrompt = false }) { Text("关闭", color = Pink) } }
        )
    }
}

/* ---------------- 分句与发送 ---------------- */

@Composable
fun SendScreen(onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val s = cfg.send
    Page(title = "回复与分句", subtitle = "像真人那样一条条往外蹦", onBack = onBack) {
        CardBox {
            ToggleRow("分句发送", "按句号问号切开，分段发", s.split) { v ->
                Prefs.update { it.copy(send = it.send.copy(split = v)) }
            }
            Line()
            ToggleRow("模拟打字停顿", "按字数加一点延迟，更像人", s.typingSim) { v ->
                Prefs.update { it.copy(send = it.send.copy(typingSim = v)) }
            }
            Line()
            ToggleRow("被动回复", "用 msg_id 回复，不占用主动消息额度", s.passiveReply) { v ->
                Prefs.update { it.copy(send = it.send.copy(passiveReply = v)) }
            }
            Line()
            ToggleRow("每条回复带主菜单", "AI 回话后面挂按钮", s.attachMenu) { v ->
                Prefs.update { it.copy(send = it.send.copy(attachMenu = v)) }
            }
        }

        SectionTitle("节奏")
        CardBox {
            NumField("最多几段", s.maxSegments, { v ->
                Prefs.update { it.copy(send = it.send.copy(maxSegments = v.coerceIn(1, 8))) }
            }, hint = "建议 2~3，太多会被平台限流")
            NumField("每段最多字数", s.maxChars, { v ->
                Prefs.update { it.copy(send = it.send.copy(maxChars = v.coerceIn(20, 200))) }
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    NumField("最小间隔 ms", s.minGapMs, { v ->
                        Prefs.update { it.copy(send = it.send.copy(minGapMs = v.coerceAtLeast(0))) }
                    })
                }
                Column(modifier = Modifier.weight(1f)) {
                    NumField("最大间隔 ms", s.maxGapMs, { v ->
                        Prefs.update { it.copy(send = it.send.copy(maxGapMs = v.coerceAtLeast(0))) }
                    })
                }
            }
        }

        SectionTitle("限制")
        CardBox {
            Hint("QQ 对同一条消息的被动回复最多 5 次。分句段数加媒体一起别超过 5，超出的会被平台拒绝并写进日志。")
        }
    }
}

/* ---------------- 模型 ---------------- */

@Composable
fun AiScreen(onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val ai = cfg.ai
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>?>(null) }
    var testResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }

    Page(title = "模型接口", subtitle = "OpenAI 兼容格式", onBack = onBack) {
        CardBox {
            Field("BaseURL", ai.baseUrl, { v -> Prefs.update { it.copy(ai = it.ai.copy(baseUrl = v.trim())) } }, hint = "https://api.openai.com/v1")
            Field("API Key", ai.apiKey, { v -> Prefs.update { it.copy(ai = it.ai.copy(apiKey = v.trim())) } }, secret = true)
            Field("模型名", ai.model, { v -> Prefs.update { it.copy(ai = it.ai.copy(model = v.trim())) } }, hint = "gpt-4o-mini / deepseek-chat / qwen-plus")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Field("温度", ai.temperature.toString(), { v ->
                        v.toDoubleOrNull()?.let { d -> Prefs.update { it.copy(ai = it.ai.copy(temperature = d.coerceIn(0.0, 2.0))) } }
                    })
                }
                Column(modifier = Modifier.weight(1f)) {
                    NumField("最大 tokens", ai.maxTokens, { v ->
                        Prefs.update { it.copy(ai = it.ai.copy(maxTokens = v.coerceIn(64, 4000))) }
                    })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    NumField("记忆条数", ai.history, { v ->
                        Prefs.update { it.copy(ai = it.ai.copy(history = v.coerceIn(0, 40))) }
                    }, hint = "0 = 不记上下文")
                }
                Column(modifier = Modifier.weight(1f)) {
                    NumField("超时秒", ai.timeoutSec, { v ->
                        Prefs.update { it.copy(ai = it.ai.copy(timeoutSec = v.coerceIn(10, 180))) }
                    })
                }
            }
            Field("额外要求", ai.extraPrompt, { v -> Prefs.update { it.copy(ai = it.ai.copy(extraPrompt = v)) } }, hint = "追加到系统提示词末尾", singleLine = false, minLines = 3)
        }

        Row(modifier = Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Ghost(if (busy) "请求中…" else "拉取模型列表", onClick = {
                if (busy) return@Ghost
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { AiApi.listModels(Prefs.current.ai) } }
                    busy = false
                    if (r.isSuccess) models = r.getOrNull().orEmpty().ifEmpty { listOf("（接口没返回模型）") }
                    else testResult = (r.exceptionOrNull()?.message ?: "失败") to "拉取失败"
                }
            })
            Primary("测试一句", enabled = !busy, onClick = {
                if (busy) return@Primary
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            AiApi.chat(
                                listOf(
                                    AiApi.Msg("system", "你只需要回一句很短的话。"),
                                    AiApi.Msg("user", "在吗")
                                ),
                                Prefs.current.ai
                            )
                        }
                    }
                    busy = false
                    testResult = if (r.isSuccess) (r.getOrNull() ?: "") to "模型回话了" else (r.exceptionOrNull()?.message ?: "失败") to "调用失败"
                }
            })
        }
        Hint("接口地址支持中转站；如果填的地址末尾是 /chat/completions 会直接使用。")
    }

    models?.let { list ->
        AlertDialog(
            onDismissRequest = { models = null },
            title = { Text("可用模型", fontSize = 16.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp)
                ) {
                    list.take(60).forEach { name ->
                        Text(
                            name,
                            fontSize = 12.sp,
                            color = Cyan,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .then(Modifier)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { models = null }) { Text("关闭", color = Pink) } }
        )
    }

    testResult?.let { (text, title) ->
        AlertDialog(
            onDismissRequest = { testResult = null },
            title = { Text(title, fontSize = 16.sp) },
            text = { Text(text, fontSize = 12.sp, color = TextDim) },
            confirmButton = { TextButton(onClick = { testResult = null }) { Text("好", color = Pink) } }
        )
    }
}

/* ---------------- 文件发送 ---------------- */

@Composable
fun FilesScreen(onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val s = cfg.send

    Page(title = "文件发送", subtitle = "图片、视频、语音，以及任何类型的文件", onBack = onBack) {
        CardBox {
            ToggleRow(
                "允许发普通文件",
                "pdf / zip / apk / docx 这类按 file_type=4 发；平台没开这项能力时会明确报错",
                s.allowPlainFile
            ) { v ->
                Prefs.update { it.copy(send = it.send.copy(allowPlainFile = v)) }
            }
            Line()
            ToggleRow("非管理员也能发", "关掉时只有管理员能用 /文件", s.fileForAll) { v ->
                Prefs.update { it.copy(send = it.send.copy(fileForAll = v)) }
            }
            Line()
            NumField("单文件上限 MB", s.fileMaxMb, { v ->
                Prefs.update { it.copy(send = it.send.copy(fileMaxMb = v.coerceIn(1, 30))) }
            }, hint = "默认 10；base64 上传，调太大容易内存紧张")
        }

        SectionTitle("怎么发")
        CardBox {
            Hint("本机文件：/文件 /sdcard/Download/报告.pdf")
            Hint("公网文件：/文件 https://example.com/a.zip（平台自己去拉，适合大文件）")
            Hint("让 AI 发：提示词里写 [文件:/sdcard/xxx.pdf]，路径必须真实存在。")
            Hint("让 AI 发一整个目录：写 [目录:/sdcard/Download/某个文件夹]，会逐个发，并汇报发了几个。")
            Hint("图片、视频、silk 语音会自动按对应类型发，不用改写法。")
        }

        SectionTitle("说明")
        CardBox {
            Hint("普通文件走官方 file_type=4，能不能发取决于开放平台给你的机器人开没开这项能力；被拒时日志里有平台的原始错误码。")
            Hint("gif / webp 不重新编码，动画保留；其它图片会压到 1280px 再发。")
            Hint("同一条消息的被动回复最多 5 次，文字段数加媒体一起算。")
        }
    }
}