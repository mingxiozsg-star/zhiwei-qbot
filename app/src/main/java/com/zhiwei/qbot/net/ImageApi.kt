package com.zhiwei.qbot.net

import android.util.Base64
import com.zhiwei.qbot.core.AiConfig
import org.json.JSONObject

/**
 * OpenAI 兼容的图片生成接口。
 *
 * 之所以单独一个类：生图和对话不是同一个端点（/v1/images/generations vs /v1/chat/completions），
 * 参数表也不一样（size / n / response_format），混在 [AiApi] 里会让两边都难受。
 *
 * 两种常见返回都吃：
 * - b64_json：直接拿到图，省一次下载；
 * - url：拿链接再下来。
 */
object ImageApi {

    data class Image(val bytes: ByteArray, val ext: String)

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/images/generations")) return base
        if (base.endsWith("/v1")) return "$base/images/generations"
        return "$base/v1/images/generations"
    }

    private fun headers(cfg: AiConfig): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        if (cfg.apiKey.isNotBlank()) put("Authorization", "Bearer ${cfg.apiKey.trim()}")
    }

    /**
     * 生成一张图。
     *
     * [size] 留空用接口默认（多数中转站认 1024x1024）；
     * [style] 会拼到提示词前面，用来固定画风。
     */
    fun generate(prompt: String, cfg: AiConfig, size: String = ""): Image {
        require(cfg.baseUrl.isNotBlank()) { "还没填画图模型接口地址" }
        require(cfg.model.isNotBlank()) { "还没填画图模型名" }
        require(prompt.isNotBlank()) { "画什么？给个提示词" }

        val body = JSONObject()
            .put("model", cfg.model.trim())
            .put("prompt", prompt.trim())
            .put("n", 1)
            // 显式要 base64：很多中转站对 url 返回的是临时签名地址，过期就取不到了
            .put("response_format", "b64_json")
        if (size.isNotBlank()) body.put("size", size)

        val text = Http.postJson(
            endpoint(cfg.baseUrl),
            body.toString(),
            headers(cfg),
            // 生图慢，给自己更多余量；Http 内部已限制到 300s 上限
            timeoutSec = cfg.timeoutSec.coerceIn(60, 300),
            retries = 1
        )
        return parse(text)
    }

    private fun parse(text: String): Image {
        val json = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalStateException("画图接口返回的不是 JSON：${text.take(200)}") }

        // 有些中转站把错误塞在 200 里
        json.optJSONObject("error")?.let {
            val msg = it.optString("message").ifBlank { it.toString() }
            throw IllegalStateException("画图接口报错：$msg")
        }

        val data = json.optJSONArray("data")
            ?: throw IllegalStateException("画图接口没有返回 data：${text.take(200)}")
        val first = data.optJSONObject(0)
            ?: throw IllegalStateException("画图接口返回的 data 是空的")

        val b64 = first.optString("b64_json")
        if (b64.isNotBlank()) {
            val clean = b64.substringAfter("base64,", b64).trim()
            val bytes = runCatching { Base64.decode(clean, Base64.DEFAULT) }
                .getOrElse { throw IllegalStateException("画图接口给的 base64 解不开：${it.message}") }
            require(bytes.isNotEmpty()) { "画图接口返回了空图片" }
            return Image(bytes, sniffExt(bytes))
        }

        val url = first.optString("url")
        if (url.isNotBlank()) {
            val bytes = Http.getBytes(url, timeoutSec = 90)
            require(bytes.isNotEmpty()) { "图片链接下载回来是空的" }
            return Image(bytes, sniffExt(bytes, url))
        }

        throw IllegalStateException("画图接口既没给 b64_json 也没给 url")
    }

    /**
     * 猜扩展名：先看文件头，看不出来再看链接后缀。
     * 表情/图片发送要靠文件名判类型，名字带错后缀会被平台按错误类型拒掉。
     */
    private fun sniffExt(bytes: ByteArray, url: String = ""): String {
        if (bytes.size >= 8) {
            val png = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            if (png) return "png"
            val jpg = bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            if (jpg) return "jpg"
            val webp = bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
            if (webp) return "webp"
        }
        val tail = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        return if (tail in setOf("png", "jpg", "jpeg", "webp")) {
            if (tail == "jpeg") "jpg" else tail
        } else {
            "png"
        }
    }
}