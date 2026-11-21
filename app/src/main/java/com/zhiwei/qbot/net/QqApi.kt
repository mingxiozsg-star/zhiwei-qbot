package com.zhiwei.qbot.net

import android.util.Base64
import com.zhiwei.qbot.core.Config
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.flag
import com.zhiwei.qbot.core.num
import com.zhiwei.qbot.core.obj
import com.zhiwei.qbot.core.str
import org.json.JSONArray
import org.json.JSONObject

class QqApiException(val code: Int, val raw: String) : Exception("[$code] $raw")

/**
 * QQ 开放平台 v2 接口。只管三件事：拿 token、发消息/上传媒体、回应按钮交互。
 */
object QqApi {

    @Volatile private var token: String = ""
    @Volatile private var tokenExpireAt: Long = 0L
    private val lock = Any()

    private fun cfg(): Config = Prefs.current

    var lastTokenError: String = ""
        private set

    private fun base(): String {
        val c = cfg()
        return when {
            c.sandbox -> "https://sandbox.api.sgroup.qq.com"
            c.apiBase.isNotBlank() -> c.apiBase.trimEnd('/')
            else -> "https://api.sgroup.qq.com"
        }
    }

    /** 拿 AccessToken，提前 90 秒刷新，避免卡在过期边界 */
    fun accessToken(force: Boolean = false): String {
        synchronized(lock) {
            val c = cfg()
            if (!force && token.isNotEmpty() && System.currentTimeMillis() < tokenExpireAt) return token
            require(c.appId.isNotBlank()) { "还没填 AppID" }
            require(c.appSecret.isNotBlank()) { "还没填 AppSecret" }
            val payload = JSONObject().put("appId", c.appId).put("clientSecret", c.appSecret)
            val url = c.tokenUrl.ifBlank { "https://bots.qq.com/app/getAppAccessToken" }
            return try {
                val text = Http.postJson(url, payload.toString(), timeoutSec = 20)
                val json = JSONObject(text)
                val tk = json.str("access_token")
                if (tk.isBlank()) throw QqApiException(json.num("code", -1), text.take(200))
                val expires = json.num("expires_in", 7200).toLong()
                token = tk
                tokenExpireAt = System.currentTimeMillis() + (expires - 90).coerceAtLeast(60) * 1000
                lastTokenError = ""
                Logs.i("AccessToken 已刷新，有效期 ${expires}s")
                tk
            } catch (e: Exception) {
                lastTokenError = e.message ?: "取 token 失败"
                throw IllegalStateException("获取 AccessToken 失败：${e.message}")
            }
        }
    }

    private fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to "QQBot ${accessToken()}", "Content-Type" to "application/json")

    /** 网关地址，官方接口按 bot 下发，这里只是转发 */
    fun gatewayUrl(): String {
        val text = Http.get("${base()}/gateway", authHeaders(), timeoutSec = 20)
        val url = JSONObject(text).str("url")
        if (url.isBlank()) error("网关地址为空：${text.take(160)}")
        return url
    }

    private fun sendTo(path: String, msgId: String?, seq: Int, body: JSONObject): String {
        if (msgId != null) {
            body.put("msg_id", msgId)
            body.put("msg_seq", seq)
            if (!body.has("timestamp")) body.put("timestamp", System.currentTimeMillis() / 1000)
        }
        val text = Http.postJson("${base()}$path", body.toString(), authHeaders(), timeoutSec = 25)
        val json = runCatching { JSONObject(text) }.getOrElse { return "" }
        val code = json.num("code", 0)
        if (code != 0) throw QqApiException(code, json.str("message", text.take(200)))
        return json.str("id")
    }

    fun sendGroup(groupOpenId: String, msgId: String?, seq: Int, body: JSONObject): String =
        sendTo("/v2/groups/$groupOpenId/messages", msgId, seq, body)

    fun sendC2c(userOpenId: String, msgId: String?, seq: Int, body: JSONObject): String =
        sendTo("/v2/users/$userOpenId/messages", msgId, seq, body)

    /**
     * 上传富媒体。fileType：1 图片 / 2 视频 / 3 语音(silk) / 4 文件。
     * srv_send_msg=false，只拿 file_info 再自己发送。
     */
    fun uploadMedia(
        isGroup: Boolean,
        openId: String,
        fileType: Int,
        bytes: ByteArray,
        fileName: String
    ): String {
        val path = if (isGroup) "/v2/groups/$openId/files" else "/v2/users/$openId/files"
        val payload = JSONObject()
            .put("file_type", fileType)
            .put("file_data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("srv_send_msg", false)
        if (fileName.isNotBlank()) payload.put("file_name", fileName)
        val text = Http.postJson("${base()}$path", payload.toString(), authHeaders(), timeoutSec = 60)
        val json = JSONObject(text)
        val info = json.str("file_info")
        if (info.isBlank()) throw QqApiException(json.num("code", -1), text.take(200))
        return info
    }

    /** 按钮点击必须回应，否则客户端一直转圈 */
    fun ackInteraction(interactionId: String, code: Int = 0) {
        runCatching {
            val body = JSONObject().put("code", code)
            Http.putJson(
                "${base()}/interactions/$interactionId",
                body.toString(),
                authHeaders(),
                timeoutSec = 15
            )
            Logs.i("已回应按钮交互 $interactionId")
        }.onFailure { Logs.w("回应交互失败：${it.message}") }
    }

    fun deleteMessage(groupOpenId: String, messageId: String) {
        Http.request("${base()}/v2/groups/$groupOpenId/messages/$messageId", "DELETE", authHeaders())
    }

    fun probeToken(): Result<String> = runCatching { accessToken(force = true) }
}