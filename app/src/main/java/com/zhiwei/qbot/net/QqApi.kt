package com.zhiwei.qbot.net

import android.util.Base64
import com.zhiwei.qbot.core.Config
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.flag
import com.zhiwei.qbot.core.num
import com.zhiwei.qbot.core.obj
import com.zhiwei.qbot.core.str
import org.json.JSONObject

class QqApiException(val code: Int, val raw: String) : Exception("[$code] $raw")

/**
 * QQ 开放平台 v2 接口。只管四件事：拿 token、发消息、上传媒体、回应按钮。
 *
 * 稳定性约定：
 * 1. 401/403 或 11244 这类鉴权失败，自动刷新 AccessToken 再重试一次；
 * 2. 网络层重试交给 Http，发送/上传各允许 2 次尝试（同 msg_seq，平台按 msg_seq 去重）；
 * 3. 任何错误都把平台原始信息带出来，不吞。
 */
object QqApi {

    /** 群聊/单聊消息事件 */
    const val INTENT_GROUP_C2C = 1 shl 25
    /** 按钮点击等交互事件：不订阅这个，菜单点了后台收不到 */
    const val INTENT_INTERACTION = 1 shl 26

    @Volatile private var token: String = ""
    @Volatile private var tokenExpireAt: Long = 0L
    private val lock = Any()

    var lastTokenError: String = ""
        private set

    private fun cfg(): Config = Prefs.current

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
                val text = Http.postJson(url, payload.toString(), timeoutSec = 20, retries = 3)
                val json = JSONObject(text)
                val tk = json.str("access_token")
                if (tk.isBlank()) throw QqApiException(json.num("code", -1), text.take(200))
                val expires = json.num("expires_in", 7200).toLong().coerceAtLeast(120)
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

    private fun authHeaders(tk: String): Map<String, String> =
        mapOf("Authorization" to "QQBot $tk", "Content-Type" to "application/json")

    private fun isAuthFailure(e: Exception): Boolean = when (e) {
        is HttpException -> e.status == 401 || e.status == 403
        is QqApiException -> e.code in AUTH_CODES
        else -> false
    }

    private val AUTH_CODES = setOf(11241, 11242, 11243, 11244, 11245, 40001)

    /**
     * 带 token 的调用：鉴权类错误自动刷新一次再重试，其余错误照常抛。
     * token 传进 lambda，避免刷新后仍用旧值。
     */
    private fun <T> authed(block: (String) -> T): T {
        return try {
            block(accessToken())
        } catch (e: Exception) {
            if (!isAuthFailure(e)) throw e
            Logs.w("鉴权失败（${e.message}），刷新 AccessToken 后重试")
            block(accessToken(force = true))
        }
    }

    /** 网关地址，官方接口按 bot 下发，这里只是转发 */
    fun gatewayUrl(): String = authed { tk ->
        val text = Http.get("${base()}/gateway", authHeaders(tk), timeoutSec = 20, retries = 2)
        val url = JSONObject(text).str("url")
        if (url.isBlank()) error("网关地址为空：${text.take(160)}")
        url
    }

    private fun sendTo(path: String, msgId: String?, seq: Int, body: JSONObject): String = authed { tk ->
        val payload = JSONObject(body.toString())
        if (msgId != null) {
            payload.put("msg_id", msgId)
            payload.put("msg_seq", seq)
            if (!payload.has("timestamp")) payload.put("timestamp", System.currentTimeMillis() / 1000)
        }
        val text = Http.postJson("${base()}$path", payload.toString(), authHeaders(tk), timeoutSec = 30, retries = 2)
        val json = runCatching { JSONObject(text) }.getOrElse { return@authed "" }
        val code = json.num("code", 0)
        if (code != 0) throw QqApiException(code, json.str("message", text.take(200)))
        json.str("id")
    }

    fun sendGroup(groupOpenId: String, msgId: String?, seq: Int, body: JSONObject): String =
        sendTo("/v2/groups/$groupOpenId/messages", msgId, seq, body)

    fun sendC2c(userOpenId: String, msgId: String?, seq: Int, body: JSONObject): String =
        sendTo("/v2/users/$userOpenId/messages", msgId, seq, body)

    /**
     * 上传富媒体，返回 file_info。
     * fileType：1 图片 / 2 视频 / 3 语音(silk) / 4 文件。
     * 两种取件方式二选一：file_data(base64) 或 url(公网链接，适合大文件)。
     * srv_send_msg=false：只拿 file_info，再由调用方自己发，这样能挂 msg_id 做被动回复。
     */
    fun uploadMedia(
        isGroup: Boolean,
        openId: String,
        fileType: Int,
        bytes: ByteArray? = null,
        fileName: String = "",
        url: String = ""
    ): String = authed { tk ->
        val path = if (isGroup) "/v2/groups/$openId/files" else "/v2/users/$openId/files"
        val payload = JSONObject()
            .put("file_type", fileType)
            .put("srv_send_msg", false)
        if (url.isNotBlank()) {
            payload.put("url", url)
        } else {
            payload.put("file_data", Base64.encodeToString(bytes ?: ByteArray(0), Base64.NO_WRAP))
        }
        if (fileName.isNotBlank()) payload.put("file_name", fileName)
        val text = Http.postJson(
            "${base()}$path",
            payload.toString(),
            authHeaders(tk),
            timeoutSec = 120,
            retries = 2
        )
        val json = runCatching { JSONObject(text) }
            .getOrElse { error("上传返回不是 JSON：${text.take(160)}") }
        val info = json.str("file_info").ifBlank { json.str("file_uuid") }
        if (info.isBlank()) {
            throw QqApiException(json.num("code", -1), json.str("message", text.take(200)))
        }
        info
    }

    /** 按钮点击必须回应，否则客户端一直转圈 */
    fun ackInteraction(interactionId: String, code: Int = 0) {
        runCatching {
            val body = JSONObject().put("code", code)
            authed { tk ->
                Http.putJson(
                    "${base()}/interactions/$interactionId",
                    body.toString(),
                    authHeaders(tk),
                    timeoutSec = 15,
                    retries = 2
                )
            }
            Logs.i("已回应按钮交互 $interactionId")
        }.onFailure { Logs.w("回应交互失败：${it.message}") }
    }

    fun deleteMessage(groupOpenId: String, messageId: String) {
        runCatching {
            authed { tk ->
                Http.request(
                    "${base()}/v2/groups/$groupOpenId/messages/$messageId",
                    "DELETE",
                    authHeaders(tk),
                    timeoutSec = 20
                )
            }
        }.onFailure { Logs.w("撤回消息失败：${it.message}") }
    }

    fun probeToken(): Result<String> = runCatching { accessToken(force = true) }
}