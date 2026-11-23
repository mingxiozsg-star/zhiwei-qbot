package com.zhiwei.qbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import com.zhiwei.qbot.core.ModelSlot
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.net.AiApi
import com.zhiwei.qbot.net.ImageApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型按用途分配。
 *
 * 需求原话是「普通对话 / 识图 / 语音 / 画图，各自能填 API 地址；
 * 普通模型能顶替的就不用填」。所以这里每一栏都允许留空，
 * 留空即回落到全局那一组，填了才覆盖。
 */
@Composable
fun RoutingScreen(onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val r = cfg.routing
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }

    Page(title = "模型分配", subtitle = "一个用途一个模型，留空自动用主模型", onBack = onBack) {
        CardBox {
            ToggleRow("启用按用途分配", "关掉时所有请求都走「模型接口」里那一组", r.enabled) { v ->
                Prefs.update { it.copy(routing = it.routing.copy(enabled = v)) }
            }
            Hint("主模型（模型接口页）：${cfg.ai.model.ifBlank { "未填" }}")
        }

        slotSection(
            title = "普通对话",
            desc = "日常回话。这栏空着就用主模型；填了则以这里为准。",
            slot = r.chat,
            testingKey = "chat",
            testing = testing,
            onChange = { s -> Prefs.update { it.copy(routing = it.routing.copy(chat = s)) } },
            onTest = { s -> testing = "chat"; testSlot(scope, s, cfg.ai) { pair -> testing = ""; result = pair } }
        )

        slotSection(
            title = "识图模型",
            desc = "别人发图片时用它看图。空着就用主模型——主模型不支持看图的话会看不出内容。",
            slot = r.vision,
            testingKey = "vision",
            testing = testing,
            onChange = { s -> Prefs.update { it.copy(routing = it.routing.copy(vision = s)) } },
            onTest = { s -> testing = "vision"; testSlot(scope, s, cfg.ai) { pair -> testing = ""; result = pair } }
        )

        slotSection(
            title = "语音生成",
            desc = "给需要模型先把话写成语音稿的场景用；TTS 接口仍在「语音 TTS」里配。",
            slot = r.tts,
            testingKey = "tts",
            testing = testing,
            onChange = { s -> Prefs.update { it.copy(routing = it.routing.copy(tts = s)) } },
            onTest = { s -> testing = "tts"; testSlot(scope, s, cfg.ai) { pair -> testing = ""; result = pair } }
        )

        slotSection(
            title = "画图",
            desc = "生图模型走 OpenAI 兼容的图像接口时填这里。",
            slot = r.image,
            testingKey = "image",
            testing = testing,
            onChange = { s -> Prefs.update { it.copy(routing = it.routing.copy(image = s)) } },
            onTest = { s -> testing = "image"; testImageSlot(scope, s, cfg.ai) { pair -> testing = ""; result = pair } }
        )

        SectionTitle("说明")
        CardBox {
            Hint("· 规则很简单：这一栏的地址和模型名都填了才生效，否则整栏回落到主模型。")
            Hint("· Key 留空时会用主模型的 Key，适合同一个中转站里换模型名。")
            Hint("· 温度、tokens 每栏可单独调，语音/画图一般给低一点。")
            Hint("· 语音/画图两栏不生效时，画图与语音稿会退回主模型，功能不会因此消失。")
        }
    }

    result?.let { (text, title) ->
        SimpleDialog(
            title = title,
            text = text,
            confirm = "知道了",
            onConfirm = { result = null },
            onDismiss = { result = null }
        )
    }
}

private fun testSlot(
    scope: kotlinx.coroutines.CoroutineScope,
    slot: ModelSlot,
    fallback: com.zhiwei.qbot.core.AiConfig,
    done: (Pair<String, String>) -> Unit
) {
    scope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val effective = if (slot.filled) {
                    com.zhiwei.qbot.core.ModelRouting().effective(slot, fallback)
                } else {
                    fallback
                }
                AiApi.chat(
                    listOf(
                        AiApi.Msg("system", "只回一个字：好"),
                        AiApi.Msg("user", "在吗")
                    ),
                    effective
                )
            }
        }
        done(
            if (r.isSuccess) (r.getOrNull() ?: "") to "这栏能用"
            else (r.exceptionOrNull()?.message ?: "失败") to "这栏调不通"
        )
    }
}

/** 画图栏走的是另一个端点，用文字测试测不出真假，得真画一张 */
private fun testImageSlot(
    scope: kotlinx.coroutines.CoroutineScope,
    slot: ModelSlot,
    fallback: com.zhiwei.qbot.core.AiConfig,
    done: (Pair<String, String>) -> Unit
) {
    scope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val effective = if (slot.filled) {
                    com.zhiwei.qbot.core.ModelRouting().effective(slot, fallback)
                } else {
                    fallback
                }
                ImageApi.generate("a single red dot on white background", effective)
            }
        }
        done(
            if (r.isSuccess) {
                val img = r.getOrNull()
                "画出来了：${img?.ext?.uppercase()} ${(img?.bytes?.size ?: 0) / 1024}KB" to "这栏能画图"
            } else {
                (r.exceptionOrNull()?.message ?: "失败") to "这栏画不出来"
            }
        )
    }
}

@Composable
private fun slotSection(
    title: String,
    desc: String,
    slot: ModelSlot,
    testingKey: String,
    testing: String,
    onChange: (ModelSlot) -> Unit,
    onTest: (ModelSlot) -> Unit
) {
    SectionTitle(title)
    CardBox {
        Hint(desc)
        Field("BaseURL", slot.baseUrl, { v -> onChange(slot.copy(baseUrl = v.trim())) }, hint = "留空 = 用主模型")
        Field("API Key", slot.apiKey, { v -> onChange(slot.copy(apiKey = v.trim())) }, hint = "留空 = 用主模型的 Key", secret = true)
        Field("模型名", slot.model, { v -> onChange(slot.copy(model = v.trim())) }, hint = "例如 deepseek-reasoner")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Field("温度", slot.temperature.toString(), { v ->
                    v.toDoubleOrNull()?.let { d -> onChange(slot.copy(temperature = d.coerceIn(0.0, 2.0))) }
                })
            }
            Column(modifier = Modifier.weight(1f)) {
                NumField("最大 tokens", slot.maxTokens, { v -> onChange(slot.copy(maxTokens = v.coerceIn(64, 8000))) })
            }
        }
        Row(modifier = Modifier.padding(top = 4.dp)) {
            Ghost(if (testing == testingKey) "测试中…" else "测一下这栏") {
                if (testing.isBlank()) onTest(slot)
            }
        }
        Text(
            if (slot.filled) "这一栏会覆盖主模型" else "这一栏留空，实际用主模型",
            fontSize = 11.sp,
            color = TextDim,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}