package com.zhiwei.qbot.bot

import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.MediaKind
import com.zhiwei.qbot.core.MenuDef
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Sticker
import com.zhiwei.qbot.core.Store
import com.zhiwei.qbot.net.QqApi
import com.zhiwei.qbot.net.QqApiException
import org.json.JSONObject
import java.io.File

/** 一次对话的寻址信息：群 or 单聊，回谁的哪条消息 */
data class Conversation(
    val isGroup: Boolean,
    val openId: String,
    val msgId: String?
)

/**
 * 所有发消息动作收口在这里：被动回复带 msg_id，主动消息不带；
 * 媒体先上传拿 file_info，再用 msg_type=7 发出去。
 *
 * 发文件的规则：
 * 1. 图片(1) / 视频(2) / silk 语音(3) 按类型上传；
 * 2. 其余一律按普通文件(4)上传，file_name 必须带，平台不开这项能力时会明确报错；
 * 3. 也支持直接给 http(s) 链接，用 url 上传，避开 base64 体积与内存问题。
 */
object Sender {

    /** 两条消息之间至少隔这么久，避免被平台限流 */
    private const val MIN_GAP_MS = 350L

    private val seqMap = LinkedHashMap<String, Int>()
    private var lastSendAt = 0L

    @Synchronized
    private fun nextSeq(msgId: String?): Int {
        if (msgId == null) return 0
        val n = (seqMap[msgId] ?: 0) + 1
        if (seqMap.size > 300) seqMap.clear()
        seqMap[msgId] = n
        return n
    }

    @Synchronized
    private fun pace() {
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastSendAt)
        if (wait in 1 until MIN_GAP_MS) runCatching { Thread.sleep(wait) }
        lastSendAt = System.currentTimeMillis()
    }

    private fun send(conv: Conversation, body: JSONObject): Result<String> = runCatching {
        pace()
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

    /* ---------------- 媒体 ---------------- */

    /** 上传 + 发送 msg_type=7，失败只记日志，由调用方决定怎么跟用户说 */
    private fun sendMedia(
        conv: Conversation,
        fileType: Int,
        bytes: ByteArray?,
        fileName: String,
        url: String = ""
    ): Result<Unit> = runCatching {
        val info = QqApi.uploadMedia(conv.isGroup, conv.openId, fileType, bytes, fileName, url)
        val body = JSONObject()
            .put("msg_type", 7)
            .put("media", JSONObject().put("file_info", info))
        send(conv, body).getOrThrow()
        Unit
    }.onFailure {
        val label = MediaKind.fromFileType(fileType).label
        Logs.e("发${label}失败（${fileName.ifBlank { url }}）：${it.message}")
    }

    fun sendSticker(conv: Conversation, sticker: Sticker): Boolean {
        val bytes = runCatching {
            val raw = Store.readBytes(sticker.file, limitMb = 8)
            if (Store.animatedImage(sticker.file)) raw else Store.compressImage(raw)
        }.getOrElse {
            Runtime.fail()
            Logs.e("表情读取失败：${it.message}")
            return false
        }
        val ok = sendMedia(conv, MediaKind.IMAGE.fileType, bytes, sticker.file.substringAfterLast('/')).isSuccess
        if (ok) Logs.i("→ 表情包：${sticker.name}")
        return ok
    }

    fun sendImageBytes(conv: Conversation, bytes: ByteArray, name: String = "image.jpg"): Boolean {
        val data = if (Store.animatedImage(name)) bytes else Store.compressImage(bytes)
        val ok = sendMedia(conv, MediaKind.IMAGE.fileType, data, name).isSuccess
        if (ok) Logs.i("→ 图片 ${Store.sizeText(data.size.toLong())}")
        return ok
    }

    /** 语音：必须是 silk。上传时文件名要带 .silk，否则平台判定失败。 */
    fun sendVoice(conv: Conversation, silk: ByteArray): Boolean {
        val ok = sendMedia(conv, MediaKind.VOICE.fileType, silk, "voice.silk").isSuccess
        if (ok) Logs.i("→ 语音 ${silk.size / 1024}KB")
        return ok
    }

    /**
     * 发本机文件：按扩展名自动分流。
     * 图片走图片、视频走视频、silk 走语音，其余按普通文件（file_type=4）发。
     */
    fun sendFile(conv: Conversation, rawPath: String): Boolean {
        val target = rawPath.trim().trim('"', '\'', '`', ' ')
        if (target.isBlank()) {
            return fail(conv, "给个路径，比如 /sdcard/Download/a.pdf")
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return sendFileByUrl(conv, target)
        }
        val file = File(target)
        if (!file.exists()) return fail(conv, "找不到这个文件：$target")
        if (file.isDirectory) return fail(conv, "这是文件夹，不是文件：$target")
        if (!file.canRead()) return fail(conv, "读不到它，先去权限页开「所有文件访问」：$target")

        return when (val kind = MediaKind.of(file.name)) {
            MediaKind.IMAGE -> {
                if (file.length() > IMAGE_MAX_MB * MB) {
                    fail(conv, "图片 ${Store.sizeText(file.length())} 超过 ${IMAGE_MAX_MB}MB 上限。")
                } else {
                    runCatching { Store.readBytes(file.path, IMAGE_MAX_MB) }
                        .map { sendImageBytes(conv, it, file.name) }
                        .getOrElse { fail(conv, "发图失败：${it.message}") }
                }
            }
            MediaKind.VIDEO -> sendLocalMedia(conv, file, kind)
            MediaKind.VOICE -> {
                if (file.name.substringAfterLast('.', "").lowercase() != "silk") {
                    fail(conv, "语音只认 silk 文件；mp3/wav 这类当普通文件发。")
                } else {
                    runCatching { Store.readBytes(file.path, VOICE_MAX_MB) }
                        .map { sendVoice(conv, it) }
                        .getOrElse { fail(conv, "发语音失败：${it.message}") }
                }
            }
            MediaKind.PLAIN -> sendLocalMedia(conv, file, kind)
        }
    }

    /** 视频/普通文件共用：限体积、读字节、上传、失败给一句人话 */
    private fun sendLocalMedia(conv: Conversation, file: File, kind: MediaKind): Boolean {
        val cfg = Prefs.current.send
        if (kind == MediaKind.PLAIN && !cfg.allowPlainFile) {
            return fail(conv, "普通文件发送在「设置 → 文件发送」里是关着的。")
        }
        val maxMb = cfg.fileMaxMb.coerceIn(1, MAX_FILE_MB)
        if (file.length() > maxMb * MB) {
            return fail(
                conv,
                "文件 ${Store.sizeText(file.length())} 超过上限 ${maxMb}MB。"
                    + "要么在设置里调大，要么把链接直接发给我：/文件 https://…"
            )
        }
        val bytes = runCatching { Store.readBytes(file.path, maxMb) }
            .getOrElse { return fail(conv, "读文件失败：${it.message}") }
        val ok = sendMedia(conv, kind.fileType, bytes, file.name).isSuccess
        if (ok) {
            Logs.i("→ ${kind.label} ${file.name} ${Store.sizeText(file.length())}")
            return true
        }
        return if (kind == MediaKind.PLAIN) {
            fail(
                conv,
                "平台没放行这个文件（file_type=4）。图片、语音、视频不受影响；"
                    + "普通文件需要开放平台给你的机器人开这项能力。"
            )
        } else {
            fail(conv, "视频没发出去，建议用 mp4 再试；大文件可以发链接。")
        }
    }

    /** 直接给公网链接：让平台自己去拉，适合大文件，不占本机内存 */
    private fun sendFileByUrl(conv: Conversation, url: String): Boolean {
        val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "file" }
        val kind = MediaKind.of(name)
        val cfg = Prefs.current.send
        if (kind == MediaKind.PLAIN && !cfg.allowPlainFile) {
            return fail(conv, "普通文件发送在「设置 → 文件发送」里是关着的。")
        }
        if (kind == MediaKind.VOICE) {
            return fail(conv, "语音链接也会被平台拒（只认 silk 文件上传），换个类型吧。")
        }
        val ok = sendMedia(conv, kind.fileType, null, name, url).isSuccess
        if (ok) {
            Logs.i("→ ${kind.label}（链接）$name")
            return true
        }
        return fail(conv, "链接没发出去：平台拉不到这个地址，或该类型没开通。")
    }

    private fun fail(conv: Conversation, message: String): Boolean {
        Logs.e(message)
        sendText(conv, message)
        return false
    }

    private const val MB = 1024L * 1024L
    private const val IMAGE_MAX_MB = 15
    private const val VOICE_MAX_MB = 10

    /** 普通文件硬上限：base64 之后体积会涨 1/3，太大容易内存紧张 */
    private const val MAX_FILE_MB = 30
}