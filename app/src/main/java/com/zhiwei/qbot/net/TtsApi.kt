package com.zhiwei.qbot.net

import android.util.Base64
import com.zhiwei.qbot.core.TtsConfig
import com.zhiwei.qbot.core.dbl
import com.zhiwei.qbot.core.num
import com.zhiwei.qbot.core.obj
import com.zhiwei.qbot.core.str
import org.json.JSONObject

/**
 * 自定义 TTS：请求模板、响应取值、音频格式全部由用户配置，不绑定任何一家服务。
 *
 * 约定：
 * - body 里的 {{text}} / {{format}} 会被替换（已做 JSON 转义）；
 * - responseType=base64 时按 responsePath 取 base64；=url 时取链接再下载；=raw 时响应体就是音频；
 * - QQ 只收 silk 语音，非 silk 需要 converterUrl 转码，否则会明确报错而不是假装发了语音。
 */
object TtsApi {

    data class Audio(val bytes: ByteArray, val format: String)

    fun synthesize(text: String, cfg: TtsConfig): Audio {
        require(cfg.url.isNotBlank()) { "还没填 TTS 接口地址" }
        val headers = parseHeaders(cfg.headers)
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        val body = cfg.body
            .replace("{{text}}", escaped)
            .replace("{{format}}", cfg.format)
        val raw = when (cfg.method.uppercase()) {
            "GET" -> Http.get(
                if (cfg.url.contains("{{text}}")) cfg.url.replace("{{text}}", java.net.URLEncoder.encode(text, "UTF-8")) else cfg.url,
                headers,
                timeoutSec = 45
            ).toByteArray()
            else -> Http.postJson(cfg.url, body, headers, timeoutSec = 45).toByteArray()
        }

        val audio = when (cfg.responseType) {
            "raw" -> raw
            "url" -> {
                val link = pick(readJson(raw), cfg.responsePath)
                if (link.isBlank()) error("TTS 响应里找不到音频地址（path=${cfg.responsePath}）")
                Http.getBytes(link, timeoutSec = 45)
            }
            else -> {
                val b64 = pick(readJson(raw), cfg.responsePath)
                if (b64.isBlank()) error("TTS 响应里找不到 base64 音频（path=${cfg.responsePath}）")
                decodeBase64(b64)
            }
        }
        require(audio.isNotEmpty()) { "TTS 返回了空音频" }
        return if (cfg.format.equals("silk", true)) {
            Audio(audio, "silk")
        } else {
            val converted = convertToSilk(audio, cfg)
                ?: error("TTS 输出的是 ${cfg.format}，QQ 只接受 silk；请在语音设置里填“转码服务地址”，或换一个直接输出 silk 的 TTS 接口")
            Audio(converted, "silk")
        }
    }

    private fun convertToSilk(audio: ByteArray, cfg: TtsConfig): ByteArray? {
        if (cfg.converterUrl.isBlank()) return null
        return runCatching {
            val payload = JSONObject()
                .put("audio", Base64.encodeToString(audio, Base64.NO_WRAP))
                .put("from", cfg.format)
                .put("to", "silk")
            val resp = Http.postBytes(
                cfg.converterUrl,
                payload.toString().toByteArray(),
                "application/json",
                timeoutSec = 60
            )
            // 转码服务可能返回纯二进制，也可能返回 {"audio":"base64"}
            runCatching { readJson(resp) }.getOrNull()
                ?.let { json -> pick(json, "audio").takeIf { it.isNotBlank() }?.let { decodeBase64(it) } }
                ?: resp
        }.getOrNull()
    }

    private fun readJson(bytes: ByteArray): JSONObject = JSONObject(String(bytes))

    private fun decodeBase64(text: String): ByteArray {
        val clean = text.substringAfter("base64,", text).trim()
        return Base64.decode(clean, Base64.DEFAULT)
    }

    /** 支持 data.audio、result.0.url 这种点路径 */
    private fun pick(json: JSONObject, path: String): String {
        if (path.isBlank()) return ""
        var node: Any = json
        path.split('.').forEach { key ->
            node = when (val cur = node) {
                is JSONObject -> cur.opt(key) ?: return ""
                is org.json.JSONArray -> {
                    val idx = key.toIntOrNull() ?: return ""
                    cur.opt(idx) ?: return ""
                }
                else -> return ""
            }
        }
        return when (val v = node) {
            is String -> v
            is Number, is Boolean -> v.toString()
            else -> ""
        }
    }

    private fun parseHeaders(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(text)
            val map = HashMap<String, String>()
            obj.keys().forEach { k ->
                val v = obj.str(k)
                if (v.isNotBlank()) map[k] = v
            }
            map
        }.getOrElse { emptyMap() }
    }
}