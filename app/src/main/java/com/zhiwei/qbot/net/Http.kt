package com.zhiwei.qbot.net

import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 全局只用一个 OkHttpClient：连接池复用，HTTP/2 优先，心跳保活。
 * 后台常驻应用最怕线程和连接疯长，这里统一收口。
 */
object Http {

    val JSON = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(75, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .protocols(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1))
            .build()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), timeoutSec: Int = 20): String {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val c = client.newBuilder().readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
        c.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${body.take(180)}")
            return body
        }
    }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap(), timeoutSec: Int = 45): ByteArray {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        val c = client.newBuilder().readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
        c.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${String(body).take(180)}")
            return body
        }
    }

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): String {
        val builder = Request.Builder().url(url).post(json.toRequestBody(JSON))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val c = client.newBuilder().readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
        c.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${body.take(180)}")
            return body
        }
    }

    fun postBytes(
        url: String,
        bytes: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 60
    ): ByteArray {
        val builder = Request.Builder().url(url).post(bytes.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val c = client.newBuilder().readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
        c.newCall(builder.build()).execute().use { resp: Response ->
            val body = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${String(body).take(180)}")
            return body
        }
    }

    fun putJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 20
    ): String {
        val builder = Request.Builder().url(url).put(json.toRequestBody(JSON))
        headers.forEach { (k, v) -> builder.header(k, v) }
        val c = client.newBuilder().readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
        c.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${body.take(180)}")
            return body
        }
    }

    fun request(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSec: Int = 30
    ): String {
        val builder = Request.Builder().url(url)
        when (method.uppercase()) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method.uppercase(), "".toRequestBody(null))
        }
        headers.forEach { (k, v) -> builder.header(k, v) }
        val c = client.newBuilder().readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
        c.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code} ${body.take(180)}")
            return body
        }
    }
}