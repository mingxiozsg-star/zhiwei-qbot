package com.zhiwei.qbot.net

import com.zhiwei.qbot.core.AiConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容格式的对话接口。中转站、one-api、本地 vLLM 都能直接填 BaseURL。
 */
object AiApi {

    data class Msg(val role: String, val content: String, val images: List<String> = emptyList())

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) return base
        if (base.endsWith("/v1")) return "$base/chat/completions"
        return "$base/v1/chat/completions"
    }

    private fun headers(cfg: AiConfig): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        if (cfg.apiKey.isNotBlank()) put("Authorization", "Bearer ${cfg.apiKey.trim()}")
    }

    fun chat(messages: List<Msg>, cfg: AiConfig): String {
        require(cfg.baseUrl.isNotBlank()) { "还没填模型接口地址" }
        require(cfg.model.isNotBlank()) { "还没填模型名" }
        val arr = JSONArray()
        messages.forEach { m ->
            val node = JSONObject().put("role", m.role)
            if (m.images.isEmpty()) {
                node.put("content", m.content)
            } else {
                // 多模态：OpenAI 兼容格式，文字 + image_url 混排。识图模型靠这个看图片
                val parts = JSONArray()
                if (m.content.isNotBlank()) {
                    parts.put(JSONObject().put("type", "text").put("text", m.content))
                }
                m.images.forEach { url ->
                    parts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", url))
                    )
                }
                node.put("content", parts)
            }
            arr.put(node)
        }
        val body = JSONObject()
            .put("model", cfg.model.trim())
            .put("messages", arr)
            .put("temperature", cfg.temperature)
            .put("max_tokens", cfg.maxTokens)
            .put("stream", false)

        val text = Http.postJson(
            endpoint(cfg.baseUrl),
            body.toString(),
            headers(cfg),
            timeoutSec = cfg.timeoutSec.coerceIn(10, 180)
        )
        val json = JSONObject(text)
        val choices = json.optJSONArray("choices")
            ?: throw IllegalStateException("模型返回里没有 choices：${text.take(200)}")
        if (choices.length() == 0) throw IllegalStateException("模型没有返回任何内容")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("模型返回格式不对：${text.take(200)}")
        val contentNode = message.opt("content")
        val content = when (contentNode) {
            is String -> contentNode
            is JSONArray -> buildString {
                for (i in 0 until contentNode.length()) {
                    val part = contentNode.optJSONObject(i) ?: continue
                    val t = part.optString("text")
                    if (t.isNotEmpty()) append(t)
                }
            }
            else -> ""
        }
        if (content.isBlank()) throw IllegalStateException("模型返回了空内容")
        return content.trim()
    }

    fun listModels(cfg: AiConfig): List<String> {
        val base = cfg.baseUrl.trim().trimEnd('/')
        val url = if (base.endsWith("/chat/completions")) base.removeSuffix("/chat/completions") + "/models" else "$base/models"
        val text = Http.get(url, headers(cfg), timeoutSec = 25)
        val json = JSONObject(text)
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until data.length()) {
            data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out
    }
}