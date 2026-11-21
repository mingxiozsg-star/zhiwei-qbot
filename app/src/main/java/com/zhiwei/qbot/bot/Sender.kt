package com.zhiwei.qbot.bot

import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.MenuDef
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Sticker
import com.zhiwei.qbot.core.Store
import com.zhiwei.qbot.net.QqApi
import com.zhiwei.qbot.net.QqApiException
import org.json.JSONObject

/** 一次对话的寻址信息：群 or 单聊，回谁的哪条消息 */
data class Conversation(
    val isGroup: Boolean,
    val openId: String,
    val msgId: String?
)

/**
 * 所有发消息动作收口在这里：被动回复带 msg_id，主动消息不带；
 * 媒体先上传拿 file_info，再用 msg_type=7 发出去。
 */
object Sender {

    private val seqMap = HashMap<String, Int>()

    @Synchronized
    private fun nextSeq(msgId: String?): Int {
        if (msgId == null) return 0
        val n = (seqMap[msgId] ?: 0) + 1
        if (seqMap.size > 300) seqMap.clear()
        seqMap[msgId] = n
        return n
    }

    private fun send(conv: Conversation, body: JSONObject): Result<String> = runCatching {
        val msgId = if (Prefs.current.send.passiveReply) conv.msgId else null
        val seq = nextSeq(msgId)
        if (msgId != null && seq > 5) throw IllegalStateException("同一条消息的被动回复次数超限（5 次）")
        val id = if (conv.isGroup) {
            QqApi.sendGroup(conv.openId, msgId, seq, body)
        } else {
            QqApi.sendC2c(conv.openId, msgId, seq, body)
        }
        Runtime.sent()
        id
    }.onFailure {
        Runtime.fail()
        if (it is QqApiException) {
            Logs.e("发送失败 [${it.code}] ${it.message}")
        } else {
            Logs.e("发送失败：${it.message}")
        }
    }

    fun sendText(conv: Conversation, text: String, menu: MenuDef? = null): Boolean {
        if (text.isBlank() && menu == null) return false
        val body = JSONObject()
            .put("content", text.ifBlank { " " })
            .put("msg_type", 0)
        if (menu != null) body.put("keyboard", Keyboard.build(menu))
        val ok = send(conv, body).isSuccess
        if (ok) Logs.i("→ ${text.take(40)}")
        return ok
    }

    fun sendMenu(conv: Conversation, menu: MenuDef): Boolean {
        val text = menu.text.ifBlank { menu.title }
        return sendText(conv, text, menu)
    }

    fun sendSticker(conv: Conversation, sticker: Sticker): Boolean = runCatching {
        val bytes = Store.compressImage(Store.readBytes(sticker.file, limitMb = 8))
        val info = QqApi.uploadMedia(conv.isGroup, conv.openId, 1, bytes, "sticker.jpg")
        val body = JSONObject()
            .put("msg_type", 7)
            .put("media", JSONObject().put("file_info", info))
        send(conv, body).getOrThrow()
        Logs.i("→ 表情包：${sticker.name}")
        true
    }.getOrElse {
        Runtime.fail()
        Logs.e("发表情失败：${it.message}")
        false
    }

    fun sendImageBytes(conv: Conversation, bytes: ByteArray, name: String = "image.jpg"): Boolean = runCatching {
        val info = QqApi.uploadMedia(conv.isGroup, conv.openId, 1, Store.compressImage(bytes), name)
        val body = JSONObject().put("msg_type", 7).put("media", JSONObject().put("file_info", info))
        send(conv, body).getOrThrow()
        true
    }.getOrElse {
        Runtime.fail()
        Logs.e("发图失败：${it.message}")
        false
    }

    /** 语音：必须是 silk。上传时文件名要带 .silk，否则平台判定失败。 */
    fun sendVoice(conv: Conversation, silk: ByteArray): Boolean = runCatching {
        val info = QqApi.uploadMedia(conv.isGroup, conv.openId, 3, silk, "voice.silk")
        val body = JSONObject().put("msg_type", 7).put("media", JSONObject().put("file_info", info))
        send(conv, body).getOrThrow()
        Logs.i("→ 语音 ${silk.size / 1024}KB")
        true
    }.getOrElse {
        Runtime.fail()
        Logs.e("发语音失败：${it.message}")
        false
    }

    /** 普通文件走 file_type=4；平台对普通文件开放程度有限，失败会在日志里给原话。 */
    fun sendFile(conv: Conversation, path: String): Boolean = runCatching {
        val bytes = Store.readBytes(path.trim(), limitMb = 12)
        val name = path.substringAfterLast('/')
        val info = QqApi.uploadMedia(conv.isGroup, conv.openId, 4, bytes, name)
        val body = JSONObject().put("msg_type", 7).put("media", JSONObject().put("file_info", info))
        send(conv, body).getOrThrow()
        Logs.i("→ 文件 $name")
        true
    }.getOrElse {
        Runtime.fail()
        Logs.e("发文件失败：${it.message}")
        false
    }
}