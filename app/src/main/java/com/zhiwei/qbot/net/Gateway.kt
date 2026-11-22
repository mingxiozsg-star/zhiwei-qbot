package com.zhiwei.qbot.net

import com.zhiwei.qbot.bot.Runtime
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.num
import com.zhiwei.qbot.core.obj
import com.zhiwei.qbot.core.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * QQ 官方机器人 WebSocket 网关。
 *
 * 稳定性策略：
 * 1. 心跳按服务端下发的 interval 发，op11 与任何下行帧都算 ACK；
 * 2. 独立看门狗：长时间收不到任何数据就强制重连（心跳协程死了也能兜住）；
 * 3. 断线指数退避（1/2/4/8/16/30 秒）带抖动，连续失败切备用网关；
 * 4. 只认「当前这把 socket」的回调，旧连接的 onFailure 不会把新连接踩掉；
 * 5. op7 / op9 单独处理，op9 自动刷新 token；续连失败自动回到全新鉴权。
 */
class Gateway(private val onDispatch: (String, JSONObject) -> Unit) {

    private var scope: CoroutineScope? = null
    private var socket: WebSocket? = null
    private var hbJob: Job? = null
    private var watchJob: Job? = null
    private val opening = AtomicBoolean(false)

    @Volatile private var running = false
    @Volatile private var lastSeq: Int = 0
    @Volatile private var lastAck: Long = 0L
    @Volatile private var currentUrl: String = ""
    @Volatile private var attempt: Int = 0
    @Volatile private var stopping = false
    @Volatile private var lastReconnectAt: Long = 0L
    @Volatile private var sessionId: String = ""
    @Volatile private var resumeUrl: String = ""
    @Volatile private var intervalMs: Long = 41_250L

    fun isRunning(): Boolean = running

    fun start(host: CoroutineScope) {
        if (running) return
        scope = host
        running = true
        stopping = false
        attempt = 0
        lastSeq = 0
        lastAck = System.currentTimeMillis()
        open()
        startWatchdog()
    }

    fun stop() {
        running = false
        stopping = true
        hbJob?.cancel()
        hbJob = null
        watchJob?.cancel()
        watchJob = null
        runCatching { socket?.close(1000, "bye") }
        socket = null
        Runtime.setState(Runtime.State.STOPPED, "已停止")
        Logs.i("网关已断开")
    }

    private fun send(text: String): Boolean = runCatching {
        socket?.send(text) ?: false
    }.getOrDefault(false)

    private fun open() {
        if (!running) return
        val s = scope ?: return
        if (!opening.compareAndSet(false, true)) return
        s.launch(Dispatchers.IO) {
            try {
                Runtime.setState(
                    if (attempt == 0) Runtime.State.CONNECTING else Runtime.State.RECONNECTING,
                    if (attempt == 0) "正在获取网关…" else "第 $attempt 次重连…"
                )
                val cfg = Prefs.current
                val canResume = sessionId.isNotBlank() && lastSeq > 0 && resumeUrl.isNotBlank()
                val useBackup = !canResume && attempt >= 2 && cfg.backupGateway.isNotBlank()
                val url = when {
                    canResume -> resumeUrl
                    useBackup -> cfg.backupGateway.trim()
                    else -> QqApi.gatewayUrl()
                }
                if (url != currentUrl) {
                    Logs.i("线路[${if (canResume) "续连" else if (useBackup) "备用" else "主"}] $url")
                    currentUrl = url
                }
                val req = Request.Builder().url(url).build()
                socket = Http.client.newWebSocket(req, listener)
            } catch (e: Exception) {
                Logs.e("取网关失败：${e.message}")
                reconnect("取网关失败")
            } finally {
                opening.set(false)
            }
        }
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!running || webSocket !== socket) {
                runCatching { webSocket.close(1000, "stale") }
                return
            }
            try {
                lastAck = System.currentTimeMillis()
                val token = QqApi.accessToken()
                if (sessionId.isNotBlank() && lastSeq > 0) {
                    val resume = JSONObject()
                        .put("op", 6)
                        .put(
                            "d",
                            JSONObject()
                                .put("token", "QQBot $token")
                                .put("session_id", sessionId)
                                .put("seq", lastSeq)
                        )
                    Logs.i("WebSocket 已建立，尝试续连 seq=$lastSeq")
                    if (!webSocket.send(resume.toString())) reconnect("续连包发送失败")
                    return
                }
                Logs.i("WebSocket 已建立，发送鉴权")
                val props = JSONObject()
                    .put("\$os", "Android")
                    .put("\$browser", "ZhiweiQBot")
                    .put("\$device", "android")
                val d = JSONObject()
                    .put("token", "QQBot $token")
                    .put("intents", QqApi.INTENT_GROUP_C2C or QqApi.INTENT_INTERACTION)
                    .put("shard", JSONArray().put(0).put(1))
                    .put("properties", props)
                val identify = JSONObject().put("op", 2).put("d", d)
                if (!webSocket.send(identify.toString())) {
                    reconnect("鉴权包发送失败")
                }
            } catch (e: Exception) {
                Logs.e("鉴权失败：${e.message}")
                reconnect("鉴权失败")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== socket) return
            lastAck = System.currentTimeMillis()
            try {
                val json = JSONObject(text)
                if (json.has("s") && !json.isNull("s")) lastSeq = json.num("s", lastSeq)
                when (json.num("op", -1)) {
                    10 -> {
                        val interval = json.obj("d").num("heartbeat_interval", 41250).toLong()
                        intervalMs = interval.coerceIn(5_000L, 120_000L)
                        Logs.i("心跳间隔 ${intervalMs}ms")
                        startHeartbeat(intervalMs)
                    }
                    11 -> Unit
                    0 -> {
                        val type = json.str("t")
                        val data = json.obj("d")
                        if (type == "READY") {
                            attempt = 0
                            sessionId = data.str("session_id")
                            resumeUrl = data.str("resume_gateway_url")
                            val name = data.obj("user").str("username", Prefs.current.persona.name)
                            Runtime.botName(name)
                            Runtime.connected(currentUrl)
                            Logs.i("机器人已上线：$name")
                        } else if (type == "RESUMED") {
                            attempt = 0
                            Runtime.connected(currentUrl)
                            Logs.i("已续连，事件不丢")
                        } else if (type.isNotBlank()) {
                            Runtime.received()
                            try {
                                onDispatch(type, data)
                            } catch (e: Exception) {
                                Logs.e("处理事件 $type 出错：${e.message}")
                            }
                        }
                    }
                    7 -> reconnect("服务端要求重连")
                    9 -> {
                        Logs.w("会话失效，刷新 token 后重连")
                        sessionId = ""
                        resumeUrl = ""
                        lastSeq = 0
                        runCatching { QqApi.accessToken(force = true) }
                        reconnect("会话失效")
                    }
                }
            } catch (e: Exception) {
                Logs.w("网关消息解析失败：${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opening.set(false)
            if (webSocket !== socket) return
            if (stopping) return
            Logs.w("WebSocket 断开：${t.message}")
            reconnect("连接断开")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== socket) return
            if (stopping) return
            if (code != 1000) {
                Logs.w("WebSocket 关闭：$code $reason")
                reconnect("连接关闭")
            }
        }
    }

    private fun startHeartbeat(interval: Long) {
        hbJob?.cancel()
        val s = scope ?: return
        lastAck = System.currentTimeMillis()
        hbJob = s.launch(Dispatchers.IO) {
            while (running) {
                delay(interval)
                if (!running) break
                val gap = System.currentTimeMillis() - lastAck
                if (gap > interval * 2 + 15_000) {
                    reconnect("心跳超时（${gap / 1000}s 无响应）")
                    break
                }
                val payload = JSONObject().put("op", 1)
                if (lastSeq > 0) payload.put("d", lastSeq) else payload.put("d", JSONObject.NULL)
                if (!send(payload.toString())) {
                    reconnect("心跳发送失败")
                    break
                }
            }
        }
    }

    /** 独立看门狗：心跳协程本身挂了、或者半死不活时，这条兜底 */
    private fun startWatchdog() {
        watchJob?.cancel()
        val s = scope ?: return
        watchJob = s.launch(Dispatchers.IO) {
            while (running) {
                delay(20_000)
                if (!running) break
                if (currentUrl.isBlank()) continue
                val gap = System.currentTimeMillis() - lastAck
                val limit = max(intervalMs * 2 + 30_000, 150_000)
                if (gap > limit) {
                    reconnect("看门狗：${gap / 1000}s 没有数据")
                }
            }
        }
    }

    private fun reconnect(reason: String) {
        if (!running) return
        val now = System.currentTimeMillis()
        if (now - lastReconnectAt < 800) return
        lastReconnectAt = now
        hbJob?.cancel()
        hbJob = null
        val old = socket
        socket = null
        runCatching { old?.cancel() }
        attempt += 1
        Runtime.setState(Runtime.State.RECONNECTING, reason)
        Runtime.fail()
        val wait = (1000L shl min(attempt, 5)).coerceAtMost(30_000L) + Random.nextLong(0, 800)
        Logs.w("$reason → ${wait}ms 后第 $attempt 次重连")
        val s = scope ?: return
        s.launch(Dispatchers.IO) {
            delay(wait)
            open()
        }
    }
}