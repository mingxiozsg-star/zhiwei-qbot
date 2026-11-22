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
        // 官方要求：按钮挂在 markdown 消息上（msg_type=2），text 消息带 keyboard 不生效
        val md = "**${menu.title}**\n$text"
        val body = JSONObject()
            .put("content", md)
            .put("msg_type", 2)
            .put("markdown", JSONObject().put("content", md))
            .put("keyboard", Keyboard.build(menu))
        var ok = send(conv, body).isSuccess
        if (!ok) {
            Logs.w("按钮消息被拒，退回普通文字 + 键盘")
            ok = sendText(conv, text, menu)
        }
        if (!ok) {
            Logs.w("键盘不可用，退回纯文字菜单")
            ok = sendText(conv, menuPlainText(menu))
        }
        if (ok) Logs.i("→ 菜单：${menu.title}")
        return ok
    }

    /** 没有按钮权限时的兜底文案：把按钮摊平成指令 */
    fun menuPlainText(menu: MenuDef): String {
        val sb = StringBuilder(menu.title).append("：\n")
        menu.buttons.forEach { b ->
            sb.append("· ").append(b.label)
            if (b.command.isNotBlank()) sb.append(" → ").append(b.command)
            sb.append('\n')
        }
        return sb.toString().trim()
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

    /**
     * 平台现状：file_type=4（普通文件）官方尚未开放，只有图片/视频/语音能用。
     * 所以这里只发图片，其它类型如实说明，不假装发出去。
     */
    fun sendFile(conv: Conversation, path: String): Boolean = runCatching {
        val clean = path.trim()
        val name = clean.substringAfterLast('/')
        val image = name.substringAfterLast('.', "").lowercase() in
            setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
        if (!image) {
            Logs.w("官方未开放普通文件发送：$name")
            sendText(conv, "官方接口还没开放普通文件，只能发图片、语音和视频。")
            return@runCatching false
        }
        val bytes = Store.readBytes(clean, limitMb = 12)
        sendImageBytes(conv, bytes, name)
    }.getOrElse {
        Runtime.fail()
        Logs.e("发送失败：${it.message}")
        false
    }
}