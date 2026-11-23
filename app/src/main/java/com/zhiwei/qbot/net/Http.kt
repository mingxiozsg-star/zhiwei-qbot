package com.zhiwei.qbot.net

import com.zhiwei.qbot.core.AppInfo
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 带状态码的 HTTP 错误：上层据此判断 token 过期（401/403）并刷新重试 */
class HttpException(val status: Int, val body: String) : IOException("HTTP $status ${body.take(180)}")

/**
 * 全局 HTTP 收口：
 * 1. 只留一个 OkHttpClient（连接池、HTTP/2、30s ping），超时不同的客户端复用同一连接池；
 * 2. 统一 UA，方便平台侧排查；
 * 3. 网络抖动自动退避重试，5xx 也重试，4xx 直接抛给上层判断。
 */
object Http {

    val JSON = "application/json; charset=utf-8".toMediaType()

    private val clients = ConcurrentHashMap<Int, OkHttpClient>()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .addInterceptor(Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", AppInfo.USER_AGENT)
                        .build()
                )
            })
            .build()
    }

    /**
     * WebSocket 专用客户端：**不设 pingInterval**。
     *
     * 之前这里设了 30 秒 pingInterval，OkHttp 会自己发协议层 ping 帧；
     * QQ 网关不按标准回 pong，OkHttp 就判定连接失效并主动 close，
     * 表现就是「每隔一阵无缘无故断一次，然后重连」。
     * 保活改由业务层 op1 心跳负责，这一层必须留空。
     */
    val wsClient: OkHttpClient by lazy {
        client.newBuilder()
            .pingInterval(0, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 按读超时缓存客户端，别每次请求都 newBuilder 造一个新对象 */
    private fun clientFor(readSec: Int): OkHttpClient {
        val sec = readSec.coerceIn(5, 300)
        clients[sec]?.let { return it }
        if (clients.size > 12) clients.clear()
        val built = client.newBuilder()
            .readTimeout(sec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(sec.coerceAtLeast(45).toLong(), TimeUnit.SECONDS)
            .build()
        clients[sec] = built
        return built
    }

    /** 执行器：5xx 与 IO 异常退避重试，4xx 立即抛出 */
    private fun exec(req: Request, readSec: Int, retries: Int): ByteArray {
        val attempts = retries.coerceIn(1, 5)
        var last: IOException? = null
        for (i in 1..attempts) {
            var again = false
            try {
                clientFor(readSec).newCall(req).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (resp.isSuccessful) return bytes
                    val text = String(bytes, Charsets.UTF_8)
                    if (resp.code >= 500 && i < attempts) {
                        last = HttpException(resp.code, text)
                        again = true
                    } else {
                        throw HttpException(resp.code, text)
                    }
                }
            } catch (e: HttpException) {
                throw e
            } catch (e: IOException) {
                last = e
                again = true
            }
            if (!again) break
            if (i < attempts) {
                runCatching { Thread.sleep(220L * i + Random.nextLong(0, 180)) }
            }
        }
        throw last ?: IOException("请求失败")
    }

    private fun buildGet(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 20,
        retries: Int = 2
    ): String = String(exec(buildGet(url, headers), timeoutSec, retries), Charsets.UTF_8)

    fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 45,
        retries: Int = 2
    ): ByteArray = exec(buildGet(url, headers), timeoutSec, retries)

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30,
        retries: Int = 2
    ): String {
        val builder = Request.Builder().url(url).post(json.toRequestBody(JSON))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return String(exec(builder.build(), timeoutSec, retries), Charsets.UTF_8)
    }

    fun postBytes(
        url: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 60,
        retries: Int = 1
    ): ByteArray {
        val builder = Request.Builder().url(url).post(bytes.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return exec(builder.build(), timeoutSec, retries)
    }

    fun putJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 20,
        retries: Int = 1
    ): String {
        val builder = Request.Builder().url(url).put(json.toRequestBody(JSON))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return String(exec(builder.build(), timeoutSec, retries), Charsets.UTF_8)
    }

    fun request(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30,
        retries: Int = 1
    ): String {
        val builder = Request.Builder().url(url)
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method.uppercase(), "".toRequestBody(null))
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        return String(exec(builder.build(), timeoutSec, retries), Charsets.UTF_8)
    }
}