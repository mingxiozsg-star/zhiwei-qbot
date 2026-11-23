package com.zhiwei.qbot.bot

import com.zhiwei.qbot.core.Backup
import com.zhiwei.qbot.core.Config
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Memory
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Shortcut
import com.zhiwei.qbot.core.Shortcuts
import com.zhiwei.qbot.core.Sticker
import com.zhiwei.qbot.net.AiApi
import com.zhiwei.qbot.net.ImageApi
import com.zhiwei.qbot.net.QqApi
import com.zhiwei.qbot.net.TtsApi
import com.zhiwei.qbot.util.DeviceInfo
import com.zhiwei.qbot.util.FileBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 消息大脑：事件 -> 指令 or AI -> 分句 -> 发出去。
 *
 * 记忆分三层：
 * 1. 短期上下文：每个会话一份滑动窗口；
 * 2. 长期记忆：按人存（Memory），群聊私聊共用，所以私聊聊熟的人在群里也认得；
 * 3. 提示词注入：当前说话人的资料 + 和这句话最相关的几条记忆。
 *
 * 稳定性约定：
 * 1. 同一会话串行（Mutex），并发消息不会把被动回复次数搅乱；
 * 2. 全局最多 4 个模型请求（Semaphore）；
 * 3. 模型失败重试 3 次（指数退避），全失败才回一句人话；
 * 4. 发送流程整体 try/catch，单条消息出错不影响下一条。
 */
object Brain {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val histories = LinkedHashMap<String, MutableList<AiApi.Msg>>()
    private val historyLock = Any()
    private val convLocks = ConcurrentHashMap<String, Mutex>()
    private val aiGate = Semaphore(4)

    /** 由 BotService 注入：/重连 时真正重建网关 */
    var reconnectHook: (() -> Unit)? = null

    fun onDispatch(type: String, d: JSONObject) {
        when (type) {
            "GROUP_AT_MESSAGE_CREATE" -> {
                val groupId = d.optString("group_openid")
                val conv = Conversation(true, groupId, d.optString("id"))
                val author = d.optJSONObject("author")
                // union_openid 是应用级唯一，跨群稳定；member_openid 每个群都不一样
                handleMessage(
                    conv,
                    d.optString("content"),
                    author?.optString("union_openid") ?: "",
                    author?.optString("member_openid") ?: "",
                    author?.optString("member_name") ?: "群友",
                    "群·" + groupId.takeLast(4),
                    imageUrls(d)
                )
            }
            "C2C_MESSAGE_CREATE" -> {
                val author = d.optJSONObject("author")
                val userOpenId = author?.optString("user_openid") ?: ""
                val conv = Conversation(false, userOpenId, d.optString("id"))
                handleMessage(
                    conv,
                    d.optString("content"),
                    author?.optString("union_openid") ?: "",
                    userOpenId,
                    author?.optString("username") ?: "对方",
                    "私聊",
                    imageUrls(d)
                )
            }
            "AT_MESSAGE_CREATE" -> Logs.i("频道消息暂不处理（本工具只接群聊与单聊）")
            "INTERACTION_CREATE" -> handleInteraction(d)
            "GROUP_ADD_ROBOT" -> greetGroup(d.optString("group_openid"))
            else -> {
                if (Prefs.current.verbose) Logs.i("事件：$type")
            }
        }
    }

    /* ---------------- 入站 ---------------- */

    /**
     * 从事件里捞出图片链接。
     *
     * QQ 的附件在 `attachments` 里，`content_type` 形如 image/jpeg；
     * `url` 有时是协议相对地址（//multimedia.nt.qq.com.cn/...），必须补 https。
     * 只取前 4 张：识图模型对多图有额外计费与长度限制，超过也没意义。
     */
    private fun imageUrls(d: JSONObject): List<String> {
        val arr = d.optJSONArray("attachments") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val type = a.optString("content_type").lowercase()
            val name = a.optString("filename").lowercase()
            val isImage = type.startsWith("image/") ||
                name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                name.endsWith(".png") || name.endsWith(".webp") || name.endsWith(".gif")
            if (!isImage) continue
            val url = a.optString("url").trim()
            if (url.isBlank()) continue
            out.add(if (url.startsWith("//")) "https:$url" else url)
            if (out.size >= 4) break
        }
        if (out.isNotEmpty()) Logs.i("收到 ${out.size} 张图片，走识图")
        return out
    }

    private fun clean(raw: String): String =
        raw.replace(Regex("<@!?[A-Za-z0-9]+>"), "")
            .replace('\u2005', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    /** 同一会话一把锁，避免两句并发把上下文和 msg_seq 搅在一起 */
    private fun lockFor(key: String): Mutex {
        if (convLocks.size > 80) convLocks.clear()
        return convLocks.computeIfAbsent(key) { Mutex() }
    }

    /**
     * 见到一个人：先把 [unionId]（跨群唯一）与 [openId]（群内唯一）归并成同一个人，
     * 再更新资料、落一条原始对话。返回合并后的主键，供提示词与后续写入复用。
     */
    private fun rememberPerson(unionId: String, openId: String, senderName: String, text: String, scene: String): String {
        if (!Prefs.current.memory.enabled) return openId
        val key = Memory.resolve(unionId, openId).ifBlank { openId }
        if (key.isBlank()) return ""
        Memory.touch(key, senderName)
        // 关键：每句话都落库，不再靠正则「抠喜好」，聊天记录本身就是记忆
        if (Prefs.current.memory.rememberAll) {
            Memory.rememberMsg(key, text, fromUser = true, scene = scene)
        }
        Memory.mineFromMessage(key, text)
        return key
    }

    private fun handleMessage(
        conv: Conversation,
        raw: String,
        unionId: String,
        openId: String,
        senderName: String,
        scene: String,
        images: List<String> = emptyList()
    ) {
        if (conv.openId.isBlank()) return
        val text = clean(raw)
        // 只发图不打字也正常：有图就不能当成空消息丢掉
        if (text.isBlank() && images.isEmpty()) return
        Logs.i("[${if (conv.isGroup) "群" else "私聊"}] ${senderName.ifBlank { "某人" }}：${text.take(60)}")
        val memText = if (images.isEmpty()) text else (text.ifBlank { "（发了 ${images.size} 张图）" } + " [图片×${images.size}]")
        val memKey = rememberPerson(unionId, openId, senderName, memText, scene)
        // 权限判定仍用平台原始 id（管理员列表里填的是 openid / union_openid）
        val ownerId = openId.ifBlank { unionId }
        scope.launch {
            lockFor(convKey(conv)).withLock {
                try {
                    // 带图就别当指令解析了，直接交给识图模型看
                    if (images.isEmpty() && routeCommand(text, conv, ownerId, senderName, memKey = memKey)) return@withLock
                    val cfg = Prefs.current
                    if (conv.isGroup && !cfg.aiGroup) return@withLock
                    if (!conv.isGroup && !cfg.aiC2c) return@withLock
                    replyWithAi(conv, text, memKey, senderName, ownerId, images)
                } catch (e: Exception) {
                    Logs.e("处理消息出错：${e.message}")
                }
            }
        }
    }

    private fun handleInteraction(d: JSONObject) {
        val interactionId = d.optString("id")
        if (interactionId.isBlank()) return
        QqApi.ackInteraction(interactionId)
        val resolved = d.optJSONObject("data")?.optJSONObject("resolved")
        val command = resolved?.optString("button_data")?.trim().orEmpty()
        val groupOpenId = d.optString("group_openid")
        val userOpenId = d.optString("user_openid").ifBlank { resolved?.optString("user_id") ?: "" }
        val unionOpenId = resolved?.optString("union_openid") ?: d.optString("union_openid")
        val isGroup = groupOpenId.isNotBlank()
        val conv = Conversation(isGroup, if (isGroup) groupOpenId else userOpenId, interactionId)
        Logs.i("按钮回调：${command.ifBlank { "(空)" }}")
        if (command.isBlank()) return
        val memKey = Memory.resolve(unionOpenId, userOpenId)
        scope.launch {
            lockFor(convKey(conv)).withLock {
                try {
                    if (routeCommand(command, conv, userOpenId, "", true)) return@withLock
                    val cfg = Prefs.current
                    if (isGroup && !cfg.aiGroup) return@withLock
                    if (!isGroup && !cfg.aiC2c) return@withLock
                    replyWithAi(conv, command, memKey, "群友", userOpenId)
                } catch (e: Exception) {
                    Logs.e("处理按钮回调出错：${e.message}")
                }
            }
        }
    }

    private fun greetGroup(groupOpenId: String) {
        if (groupOpenId.isBlank()) return
        val cfg = Prefs.current
        val main = cfg.menus.firstOrNull { it.id == "main" } ?: cfg.menus.firstOrNull()
        if (main == null) {
            Sender.sendText(Conversation(true, groupOpenId, null), cfg.persona.greeting)
        } else {
            Sender.sendText(Conversation(true, groupOpenId, null), cfg.persona.greeting, main)
        }
        Logs.i("已进群，发出开场白")
    }

    /* ---------------- 指令 ---------------- */

    private fun isOwner(senderId: String): Boolean {
        val owners = Prefs.current.owners.split(',', '，', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (owners.isEmpty()) return true
        return owners.any { it == senderId }
    }

    private fun statusText(): String {
        val s = Runtime.snap.value
        val sb = StringBuilder(DeviceInfo.statusText())
        sb.append("\n")
        sb.append("在线 ").append(DeviceInfo.duration(System.currentTimeMillis() - s.since))
        sb.append("｜线路 ").append(s.gateway.ifBlank { "-" }).append('\n')
        sb.append("收发 收 ").append(s.received).append("｜发 ").append(s.sent)
        sb.append("｜失败 ").append(s.fails).append('\n')
        sb.append("记忆 ").append(Memory.summaryLine())
        return sb.toString()
    }

    private fun myMemoryText(memKey: String): String {
        val p = Memory.person(memKey)
            ?: return "我这儿还没有你的资料，多聊几句就记住了。"
        val sb = StringBuilder()
        sb.append("我记得你：").append(p.displayName())
        if (p.names.size > 1) {
            sb.append("（也叫 ").append(p.names.filter { !it.equals(p.name, true) }.take(3).joinToString("、")).append("）")
        }
        sb.append('\n')
        sb.append("聊过 ").append(p.msgCount).append(" 句｜存了 ").append(p.msgs.size).append(" 条对话")
        sb.append("｜第一次见 ").append(dateText(p.firstSeen)).append('\n')
        if (p.aliasIds.isNotEmpty()) {
            sb.append("在 ").append(p.aliasIds.size).append(" 个身份标识下认得你（换群也认）\n")
        }
        if (p.notes.isEmpty()) {
            sb.append("提炼记忆暂时没有，但你说的每句话我都留着，随时能翻。")
        } else {
            p.notes.takeLast(10).forEachIndexed { i, n ->
                sb.append(i + 1).append(". ").append(n.text).append('\n')
            }
            if (p.notes.size > 10) sb.append("……还有 ").append(p.notes.size - 10).append(" 条")
        }
        return sb.toString().trim()
    }

    private fun dateText(at: Long): String =
        runCatching { SimpleDateFormat("MM-dd", Locale.CHINA).format(Date(at)) }.getOrDefault("-")

    private fun routeCommand(
        text: String,
        conv: Conversation,
        ownerId: String,
        senderName: String,
        fromButton: Boolean = false,
        memKey: String = ownerId
    ): Boolean {
        val cfg = Prefs.current
        val lower = text.lowercase()

        cfg.menus.firstOrNull { menu ->
            menu.triggers.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                .any { it.equals(text, true) }
        }?.let { menu ->
            Sender.sendMenu(conv, menu)
            return true
        }

        if (lower.startsWith("menu:") || lower.startsWith("菜单:")) {
            val id = text.substringAfter(':').trim()
            val menu = cfg.menus.firstOrNull { it.id == id }
            if (menu != null) Sender.sendMenu(conv, menu) else Sender.sendText(conv, "没这个菜单：$id")
            return true
        }
        // 快捷指令：q / w / e / s 与自然语言「发送某某文档」
        if (handleShortcut(text, conv, ownerId)) return true

        when {
            lower == "/状态" || lower == "状态" -> {
                Sender.sendText(conv, statusText())
                return true
            }
            lower == "/分句开" -> {
                Prefs.update { it.copy(send = it.send.copy(split = true)) }
                Sender.sendText(conv, "分句已开。")
                return true
            }
            lower == "/分句关" -> {
                Prefs.update { it.copy(send = it.send.copy(split = false)) }
                Sender.sendText(conv, "分句已关，整段发。")
                return true
            }
            lower == "/清除" || lower == "/重置" -> {
                synchronized(historyLock) { histories.remove(convKey(conv)) }
                Sender.sendText(conv, "这段对话的记忆清了。（长期记忆还在，发 /忘记 才删）")
                return true
            }
            lower == "/重载" -> {
                Prefs.saveNow()
                Memory.saveNow()
                Sender.sendText(conv, "配置已重载。")
                return true
            }
            lower == "/重连" -> {
                if (!isOwner(ownerId)) {
                    Sender.sendText(conv, "重连只有管理员能动。")
                    return true
                }
                val hook = reconnectHook
                if (hook == null) {
                    Sender.sendText(conv, "机器人没在跑，先在应用里点启动。")
                } else {
                    hook.invoke()
                    Sender.sendText(conv, "已断开重连。")
                }
                return true
            }
            lower.startsWith("/记忆库") -> {
                if (!isOwner(ownerId)) {
                    Sender.sendText(conv, "记忆库只有管理员能看。")
                    return true
                }
                val people = Memory.allPeople()
                if (people.isEmpty()) {
                    Sender.sendText(conv, "记忆库是空的。")
                    return true
                }
                val sb = StringBuilder("记忆库：").append(Memory.summaryLine()).append('\n')
                people.take(8).forEach { p ->
                    sb.append("· ").append(p.displayName()).append("（")
                        .append(p.notes.size).append(" 条记忆，")
                        .append(p.msgs.size).append(" 条对话）\n")
                }
                if (people.size > 8) sb.append("……还有 ").append(people.size - 8).append(" 人")
                Sender.sendText(conv, sb.toString().trim())
                return true
            }
            lower == "/记忆" -> {
                Sender.sendText(conv, myMemoryText(memKey))
                return true
            }
            lower.startsWith("/记住") -> {
                val what = text.substring(3).trim()
                if (what.isBlank()) {
                    Sender.sendText(conv, "写法：/记住 你喜欢猫，讨厌加班")
                    return true
                }
                val parts = what.split('，', ',', '；', ';').map { it.trim() }.filter { it.isNotEmpty() }
                var added = 0
                parts.take(3).forEach { if (Memory.learn(memKey, it, "manual")) added++ }
                Sender.sendText(
                    conv,
                    if (added > 0) "记下了（$added 条）。以后在群里也认得你。" else "这条我好像已经记过了。"
                )
                return true
            }
            lower == "/忘记" || lower == "/忘掉我" -> {
                Memory.forget(memKey)
                Sender.sendText(conv, "行，把你忘干净了。")
                return true
            }
            lower.startsWith("/语音") -> {
                if (!isOwner(ownerId)) {
                    Sender.sendText(conv, "语音只有管理员能点。")
                    return true
                }
                val say = text.removePrefix("/语音").trim()
                if (say.isBlank()) {
                    Sender.sendText(conv, "写法：/语音 要说的话")
                    return true
                }
                scope.launch { speak(conv, say, report = true) }
                return true
            }
            lower.startsWith("/画图") || lower.startsWith("/画") -> {
                val prompt = text.substringAfter(' ').trim()
                if (prompt.isBlank()) {
                    Sender.sendText(conv, "写法：/画图 一只在雨里打伞的橘猫，赛博朋克风格")
                    return true
                }
                val holder = conv
                scope.launch {
                    Sender.sendText(holder, "画一张，等我一下。")
                    drawImage(holder, prompt, Prefs.current)
                }
                return true
            }
            lower.startsWith("/表情") || lower.startsWith("/贴纸") -> {
                val name = text.substringAfter(' ').trim()
                if (name.isBlank()) {
                    val names = cfg.stickers.joinToString("、") { it.name }
                    Sender.sendText(conv, if (names.isBlank()) "表情包库是空的，先去应用里加几张。" else "有的：$names")
                    return true
                }
                val sticker = findSticker(name)
                if (sticker == null) Sender.sendText(conv, "没找到这个表情。") else scope.launch { Sender.sendSticker(conv, sticker) }
                return true
            }
            lower.startsWith("/文件") || lower.startsWith("/发送") -> {
                if (!isOwner(ownerId) && !cfg.send.fileForAll) {
                    Sender.sendText(conv, "发文件只有管理员能用。")
                    return true
                }
                val target = text.substring(3).trim()
                if (target.isBlank()) {
                    Sender.sendText(
                        conv,
                        "发本机文件：/文件 /sdcard/Download/a.pdf\n" +
                            "发一整个目录：/文件 /sdcard/Download/资料\n" +
                            "发公网文件：/文件 https://…/a.zip\n" +
                            "快捷写法：q 看文件 · w 找安装包 · e 发安装包 · s 发压缩包\n" +
                            "图片、视频、silk 语音、apk 都能发，其它按普通文件发。"
                    )
                    return true
                }
                scope.launch { Sender.sendFile(conv, target) }
                return true
            }
            lower == "/帮助" || lower == "/指令" -> {
                val main = cfg.menus.firstOrNull { it.id == "main" }
                if (main != null) {
                    Sender.sendMenu(conv, main)
                } else {
                    Sender.sendText(
                        conv,
                        "指令：/状态 /记忆 /记住 内容 /忘记 /文件 路径或目录 /语音 文本 /画图 描述 /表情 名字 /分句开 /分句关 /清除 /重连\n" +
                            "快捷：q 看文件 · w 找安装包 · e 发安装包 · s 发压缩包"
                    )
                }
                return true
            }
        }
        if (fromButton && text.isNotBlank() && !text.startsWith("/")) {
            // 按钮回调了纯文本，交给 AI 当成一句话处理
            return false
        }
        return false
    }

    /* ---------------- 快捷指令 ---------------- */

    /**
     * 快捷指令入口。
     *
     * 触发词不再写死在代码里，而是从 [Shortcuts] 表里查：
     * 用户在设置页加的、改的、禁用的，这里立刻生效，AI 提示词里看到的也是同一份表。
     *
     * 内置动作（列目录/扫 apk/发文件/发目录/备份/状态）走固定实现，
     * 自定义条目可以指定任一种动作，也可以干脆交给 AI。
     */
    private fun handleShortcut(text: String, conv: Conversation, senderId: String): Boolean {
        val raw = text.trim()
        if (raw.isEmpty()) return false
        val cfg = Prefs.current
        val word = raw.substringBefore(' ').trim()
        val arg = raw.substringAfter(' ', "").trim()
        // 中文触发词可能是整句（「目录」「备份」），所以整句也要试着匹配一次
        val hit = Shortcuts.match(cfg, word) ?: Shortcuts.match(cfg, raw)
        if (hit == null) return handleNaturalSend(raw, conv, senderId)
        val param = if (hit.trigger.equals(word, true)) arg else ""

        when (hit.action) {
            Shortcut.ACTION_LIST_DIR -> {
                val target = param.ifBlank { hit.arg.ifBlank { "/sdcard" } }
                scope.launch {
                    val body = runCatching { FileBrowser.listDirectory(target) }
                        .getOrElse { "列不出来：${it.message}" }
                    Sender.sendText(conv, body)
                }
                return true
            }
            Shortcut.ACTION_FIND_APK -> {
                if (!canSendFile(senderId)) {
                    Sender.sendText(conv, "发文件只有管理员能用。")
                    return true
                }
                val key = param.ifBlank { hit.arg }
                scope.launch {
                    val body = runCatching {
                        val list = if (key.isBlank()) FileBrowser.findApks() else FileBrowser.findApks(key)
                        if (list.isEmpty()) {
                            "没扫到安装包。" + (if (key.isBlank()) "试试指定目录：${hit.trigger} /sdcard/Download" else "换个关键词：${hit.trigger} 微信")
                        } else {
                            buildString {
                                append("📦 找到 ").append(list.size).append(" 个安装包：\n")
                                list.forEachIndexed { i, f ->
                                    append(i + 1).append(". ").append(f.name)
                                        .append("（").append(FileBrowser.sizeOf(f)).append("）\n")
                                    append("   ").append(f.absolutePath).append('\n')
                                }
                                append("发哪个就发：e ").append(list.first().absolutePath)
                            }
                        }
                    }.getOrElse { "扫安装包出错：${it.message}" }
                    Sender.sendText(conv, body)
                }
                return true
            }
            Shortcut.ACTION_SEND_APK -> {
                if (!canSendFile(senderId)) {
                    Sender.sendText(conv, "发安装包只有管理员能用。")
                    return true
                }
                val target = param.ifBlank { hit.arg }
                if (target.isBlank()) {
                    Sender.sendText(conv, "写法：${hit.trigger} /sdcard/Download/app.apk\n或：${hit.trigger} /sdcard/Download app.apk")
                    return true
                }
                scope.launch { sendByArg(conv, target, preferApk = true) }
                return true
            }
            Shortcut.ACTION_SEND_FILE -> {
                if (!canSendFile(senderId)) {
                    Sender.sendText(conv, "发文件只有管理员能用。")
                    return true
                }
                val target = param.ifBlank { hit.arg }
                if (target.isBlank()) {
                    Sender.sendText(conv, "写法：${hit.trigger} /sdcard/Download/资料.zip")
                    return true
                }
                scope.launch { sendByArg(conv, target, preferApk = false) }
                return true
            }
            Shortcut.ACTION_SEND_DIR -> {
                if (!canSendFile(senderId)) {
                    Sender.sendText(conv, "发目录只有管理员能用。")
                    return true
                }
                val target = param.ifBlank { hit.arg }
                if (target.isBlank()) {
                    Sender.sendText(conv, "写法：${hit.trigger} /sdcard/Download/某个文件夹")
                    return true
                }
                scope.launch { Sender.sendDirectory(conv, FileBrowser.resolve(target)) }
                return true
            }
            Shortcut.ACTION_BACKUP -> {
                if (!isOwner(senderId)) {
                    Sender.sendText(conv, "备份只有管理员能做。")
                    return true
                }
                scope.launch {
                    val r = runCatching { Backup.backup().getOrThrow() }
                    if (r.isSuccess) {
                        val f = r.getOrThrow()
                        Sender.sendText(conv, "已备份：\n${f.absolutePath}\n${FileBrowser.sizeOf(f)}")
                    } else {
                        Sender.sendText(conv, "备份失败：${r.exceptionOrNull()?.message}")
                    }
                }
                return true
            }
            Shortcut.ACTION_STATUS -> {
                Sender.sendText(conv, statusText())
                return true
            }
            else -> {
                // 交给 AI 的指令：把整句继续往下走
                return handleNaturalSend(raw, conv, senderId)
            }
        }
    }

    private fun canSendFile(senderId: String): Boolean =
        isOwner(senderId) || Prefs.current.send.fileForAll

    /** `e` / `s` 的参数解析：整条路径 → 目录+文件名 → 关键词搜 */
    private fun sendByArg(conv: Conversation, arg: String, preferApk: Boolean) {
        val target = arg.trim().trim('"', '\'', '`')
        if (target.startsWith("http://") || target.startsWith("https://")) {
            Sender.sendFile(conv, target)
            return
        }
        val direct = File(target)
        if (direct.exists() && direct.isFile) {
            Sender.sendFile(conv, direct.absolutePath)
            return
        }
        if (direct.exists() && direct.isDirectory) {
            Sender.sendDirectory(conv, direct.absolutePath)
            return
        }
        // 目录 + 文件名："/sdcard/Download 资料.zip"
        val parts = target.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size >= 2 && parts.first().startsWith("/")) {
            val dir = parts.first()
            val name = parts.drop(1).joinToString(" ")
            val hits = FileBrowser.findIn(dir, name)
            if (hits.isNotEmpty()) {
                if (hits.size > 1) {
                    Sender.sendText(conv, "找到 ${hits.size} 个，先发最新的：${hits.first().name}")
                }
                Sender.sendFile(conv, hits.first().absolutePath)
                return
            }
            Sender.sendText(conv, "目录里没找到「$name」：$dir\n先看看有什么：q $dir")
            return
        }
        // 只有名字：按类型去常见目录找
        val key = parts.joinToString(" ")
        val hits = if (preferApk) {
            val apks = FileBrowser.findApks(key)
            if (apks.isEmpty()) FileBrowser.search(key, limit = 10) else apks
        } else {
            val archives = FileBrowser.findArchives().filter { it.name.contains(key, true) }
            if (archives.isEmpty()) FileBrowser.search(key, limit = 10) else archives
        }
        if (hits.isEmpty()) {
            Sender.sendText(conv, "没找到「$key」。\n看目录：q /sdcard/Download\n看安装包：w")
            return
        }
        if (hits.size > 1) {
            Sender.sendText(conv, "找到 ${hits.size} 个，先发这个：${hits.first().absolutePath}")
        }
        Sender.sendFile(conv, hits.first().absolutePath)
    }

    /**
     * 自然语言发送：
     * 「发送 /sdcard/Download/a.docx」「把照片 /sdcard/DCIM/a.jpg 发我」
     * 「发个视频 b.mp4」「发送压缩包 /sdcard/Download/资料.zip」
     */
    private fun handleNaturalSend(raw: String, conv: Conversation, senderId: String): Boolean {
        val t = raw.trim()
        if (t.length > 200) return false
        val verbs = listOf("发送", "发给我", "发给我吧", "发一下", "发个", "发一个", "给我发", "传给我", "传一下", "来一份", "来一张", "来一个")
        val kinds = listOf("文档", "文件", "照片", "图片", "视频", "压缩包", "安装包", "apk", "音乐", "音频", "录音")
        val hasVerb = verbs.any { t.contains(it, true) }
        val kind = kinds.firstOrNull { t.contains(it, true) } ?: ""
        if (!hasVerb && kind.isEmpty()) return false
        if (!hasVerb) return false

        // 先看有没有像路径的东西：带 / 或带扩展名的片段
        val candidates = t.split(Regex("[\\s，,。；;：:！!？?]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.contains('/') || Regex("\\.[A-Za-z0-9]{2,5}$").containsMatchIn(it) }
        val path = candidates.firstOrNull { it.contains('/') }
            ?: candidates.firstOrNull { Regex("\\.[A-Za-z0-9]{2,5}$").containsMatchIn(it) }
        if (path == null) {
            // 只说了类型没说文件：给用法，别硬猜
            if (kind.isNotEmpty() && (t.contains("怎么", true) || t.contains("如何", true))) return false
            return false
        }
        if (!canSendFile(senderId)) {
            Sender.sendText(conv, "发文件只有管理员能用。")
            return true
        }
        val target = if (path.startsWith("/")) {
            path
        } else if (path.contains('/')) {
            "/sdcard/$path"
        } else {
            path
        }
        scope.launch { sendByArg(conv, target, preferApk = kind.contains("安装包") || kind.contains("apk", true)) }
        return true
    }

    /* ---------------- AI ---------------- */

    private fun convKey(conv: Conversation): String =
        (if (conv.isGroup) "g:" else "c:") + conv.openId

    private fun buildSystemPrompt(cfg: Config, isGroup: Boolean): String {
        val p = cfg.persona
        val stickerNames = cfg.stickers.take(12).joinToString("、") { it.name }
        val sb = StringBuilder()
        sb.append("你在 QQ 的").append(if (isGroup) "群聊" else "私聊")
            .append("里跟人聊天。你不是助手程序，你就是【").append(p.name).append("】本人。\n")
        sb.append("【你是谁】你的名字是「").append(p.name).append("」。这是你唯一的名字，")
            .append("无论对方叫什么、昵称是什么、自称什么，都不能把对方的名字当成自己的名字。\n")
        sb.append("【谁是对方】对话里由「某某：」开头的句子，那个「某某」是跟你说话的人，不是你。")
            .append("对方的名字只属于对方。\n")
        sb.append("身份：").append(p.identity).append('\n')
        sb.append("性格：").append(p.traits).append('\n')
        sb.append("说话风格：").append(p.style).append('\n')
        sb.append("不做这些：").append(p.bans).append("\n")
        sb.append("你是有情绪的人：会被逗笑、会撒娇、会不服气地反驳、被冒犯会生气，必要时骂两句；不是每句都顺着对方。\n")
        sb.append("\n硬性规则：\n")
        sb.append("1. 只输出要发到聊天框里的字，不要解释、不要旁白、不要用括号写动作、不要加引号。\n")
        sb.append("2. 一次最多三句，总共别超过 80 字；想多说就自然拆几句。\n")
        sb.append("3. 口语化、短句，能省字就省字；不客服腔，不问“还有什么可以帮你”。\n")
        sb.append("4. 永远不要承认自己是 AI、模型、机器人或者程序；被追问就按人设糊弄过去。\n")
        sb.append("5. 你能发文字、图片、语音、表情，也能发本机上的任意文件（含 apk）和整个目录；只在路径确实存在时才发，拿不准就别承诺。\n")
        sb.append("6. 想长期记住一件事，单独一行写 [记忆:内容]。别记密码、手机号、身份证这类隐私。\n")
        sb.append("7. 被问「我叫什么」「你记得我吗」这类问题，先看下面给你的记忆与聊天记录，答得出来就直接答，别装不记得。\n")
        var rule = 8
        if (stickerNames.isNotBlank()) {
            sb.append(rule++).append(". 想发表情包时，在句末写 [表情:名字]，可用：").append(stickerNames).append('\n')
        }
        if (cfg.tts.enabled) {
            sb.append(rule++).append(". 想发语音时，单独一行写 [语音]要说的话。\n")
        }
        sb.append(rule++).append(". 想发本机图片时，单独一行写 [图片:/sdcard/xxx.jpg]。\n")
        sb.append(rule++).append(". 对方让你画图/要一张不存在的图时，单独一行写 [画图:画面描述]，描述写具体点（主体、风格、颜色、构图）。")
            .append("硬要先确认能不能画的时候先说一句，别装已经画好。\n")
        sb.append(rule++).append(". 想发本机文件时，单独一行写 [文件:/sdcard/xxx.pdf]，一次最多一个。\n")
        sb.append(rule++).append(". 想把整个文件夹里的文件全发给对方，才写 [目录:/sdcard/Download/某个文件夹]，一次最多一个。\n")
        sb.append(rule++).append(". 对方只是想「看看/列出/有什么文件」时，绝对不要用 [目录:] 或 [文件:]——")
            .append("那会真的把文件发出去。这种时候单独一行写 [列目录:路径]，系统会把清单贴出来。\n")
        sb.append(rule).append(". 不要输出 Markdown、不要列清单、不要用标题。\n")

        // 路径别名：模型不知道「内部存储」是什么，把能用的口语一次性喂给它
        sb.append("\n【路径写法】本机路径一律用绝对路径，常用根目录：")
            .append("/sdcard（也可写「").append(FileBrowser.aliasHint()).append("」）\n")
        sb.append("找不到路径时不要瞎猜，先写 [列目录:/sdcard] 看真实内容。\n")

        // 快捷指令：让模型看得见、会主动推荐，而不是把它当成普通聊天
        val shortcuts = Shortcuts.promptBlock(cfg)
        if (shortcuts.isNotBlank()) {
            sb.append("\n【现在的快捷指令】对方可以直接发这些词，系统会自己处理，你不用模仿也不要复述：\n")
            sb.append(shortcuts).append('\n')
            sb.append("对方问「有什么快捷指令」时，就照上面的列表答。想让他用某条指令时，直接说指令名，别解释原理。\n")
        }

        if (p.extra.isNotBlank()) sb.append("补充设定：").append(p.extra).append('\n')
        if (cfg.ai.extraPrompt.isNotBlank()) sb.append("额外要求：").append(cfg.ai.extraPrompt).append('\n')
        return sb.toString()
    }

    /**
     * 按用途挑模型。
     *
     * 只区分「有没有图片要识图」：带图走 [ModelRouting.vision]，其余走 [ModelRouting.chat]。
     * 槽位没填时 [ModelRouting.effective] 自动回落到全局模型，所以不配也能跑。
     */
    private fun modelFor(cfg: Config, hasImages: Boolean): com.zhiwei.qbot.core.AiConfig {
        if (!cfg.routing.enabled) return cfg.ai
        val slot = if (hasImages) cfg.routing.vision else cfg.routing.chat
        return runCatching { cfg.routing.effective(slot, cfg.ai) }.getOrDefault(cfg.ai)
    }

    /** 当前说话人的资料 + 提炼记忆 + 相关原始对话，直接拼进系统提示词 */
    private fun memoryBlock(cfg: Config, senderId: String, senderName: String, text: String): String {
        if (!cfg.memory.enabled) return ""
        val p = Memory.person(senderId) ?: return ""
        val notes = Memory.recall(senderId, text, cfg.memory.injectLimit.coerceIn(0, 20))
        val sb = StringBuilder()
        sb.append("\n【现在说话的人（不是你）】").append(p.displayName().ifBlank { senderName })
        if (p.names.size > 1) {
            val others = p.names.filter { !it.equals(p.name, true) }.take(3)
            if (others.isNotEmpty()) sb.append("，也叫 ").append(others.joinToString("、"))
        }
        sb.append("｜第一次见 ").append(dateText(p.firstSeen))
        sb.append("｜已经聊过 ").append(p.msgCount).append(" 句")
        if (p.msgs.isNotEmpty()) sb.append("｜存了 ").append(p.msgs.size).append(" 条对话")
        sb.append('\n')
        sb.append("再强调一次：你的名字是「").append(cfg.persona.name).append("」，")
            .append("上面这个名字是对方的，别弄反。\n")
        if (notes.isNotEmpty()) {
            sb.append("【你记得他的事】\n")
            notes.forEachIndexed { i, n -> sb.append(i + 1).append(". ").append(n).append('\n') }
        }
        if (cfg.memory.rememberAll) {
            val history = Memory.recallMsgs(senderId, text, cfg.memory.recallMsgs.coerceIn(0, 20))
            if (history.isNotEmpty()) {
                sb.append("【你们聊过的原话（挑相关的）】\n")
                history.forEach { m ->
                    sb.append(if (m.fromUser) p.displayName() else cfg.persona.name)
                        .append("[").append(Memory.dateText(m.at)).append("]：")
                        .append(m.text.take(120)).append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun buildMessages(
        cfg: Config,
        key: String,
        text: String,
        senderId: String,
        senderName: String,
        isGroup: Boolean,
        images: List<String> = emptyList()
    ): List<AiApi.Msg> {
        val list = ArrayList<AiApi.Msg>()
        var system = buildSystemPrompt(cfg, isGroup) + memoryBlock(cfg, senderId, senderName, text)
        if (images.isNotEmpty()) {
            system += "\n【这次带了图】对方发来 ${images.size} 张图片，图片就在这条消息里。" +
                "你要真的看图说话：说出你看到了什么，不要泛泛地说“图片很好看”。" +
                "图看不清或看不懂就直说，别编内容。"
        }
        list.add(AiApi.Msg("system", system))
        synchronized(historyLock) {
            histories[key]?.takeLast(cfg.ai.history.coerceIn(0, 40))?.let { list.addAll(it) }
        }
        val body = if (isGroup) "${senderName.ifBlank { "群友" }}：${text.ifBlank { "（发了图片）" }}" else text.ifBlank { "（发了图片）" }
        // images 只挂在最后这条上：历史里的图片地址是会过期的，留着只会让下次请求 404
        list.add(AiApi.Msg("user", body, images))
        return list
    }

    private fun remember(cfg: Config, key: String, user: String, reply: String, isGroup: Boolean, senderName: String) {
        synchronized(historyLock) {
            if (histories.size > 60) {
                val first = histories.keys.firstOrNull()
                if (first != null && first != key) histories.remove(first)
            }
            val list = histories.getOrPut(key) { ArrayList() }
            list.add(AiApi.Msg("user", if (isGroup) "${senderName.ifBlank { "群友" }}：$user" else user))
            list.add(AiApi.Msg("assistant", reply))
            val cap = cfg.ai.history.coerceIn(0, 40)
            while (list.size > cap * 2) list.removeAt(0)
        }
    }

    private suspend fun replyWithAi(
        conv: Conversation,
        text: String,
        memKey: String,
        senderName: String,
        ownerId: String = memKey,
        images: List<String> = emptyList()
    ) {
        val cfg = Prefs.current
        val key = convKey(conv)
        val messages = buildMessages(cfg, key, text, memKey, senderName, conv.isGroup, images)
        val started = System.currentTimeMillis()
        val chatCfg = modelFor(cfg, images.isNotEmpty())
        var reply: String? = null
        var lastError = ""
        aiGate.withPermit {
            for (attempt in 1..3) {
                try {
                    reply = AiApi.chat(messages, chatCfg)
                    return@withPermit
                } catch (e: Exception) {
                    lastError = e.message ?: "未知错误"
                    Logs.w("模型调用失败（$attempt/3）：$lastError")
                    if (attempt < 3) delay(900L * attempt + Random.nextLong(0, 500))
                }
            }
        }
        val draft = reply
        if (draft == null) {
            Logs.e("模型最终失败：$lastError")
            Sender.sendText(conv, "等一下，脑子卡了一下。")
            return
        }
        Logs.i("模型 ${System.currentTimeMillis() - started}ms 返回 ${draft.length} 字")
        val answer = harvestMemories(draft, memKey)
        remember(cfg, key, text, answer, conv.isGroup, senderName)
        // 机器人自己说的话也挂到这个人名下，形成完整对话串
        if (cfg.memory.enabled && cfg.memory.rememberAll && memKey.isNotBlank()) {
            Memory.rememberMsg(memKey, answer, fromUser = false, scene = if (conv.isGroup) "群" else "私聊")
        }
        try {
            deliver(conv, answer, cfg, ownerId)
        } catch (e: Exception) {
            Logs.e("发送流程出错：${e.message}")
        }
    }

    /** 把模型写的 [记忆:xxx] 落库，并从要发出去的文字里删掉 */
    private fun harvestMemories(reply: String, memKey: String): String {
        if (!Prefs.current.memory.enabled) return reply
        val re = Regex("\\[记忆\\s*[:：]\\s*([^\\]]+)]")
        val found = re.findAll(reply).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        found.take(2).forEach { Memory.learn(memKey, it, "ai") }
        if (found.isEmpty()) return reply
        Logs.i("模型记了 ${found.size} 条：${found.take(2).joinToString("；")}")
        return re.replace(reply, "").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /* ---------------- 出站 ---------------- */

    private sealed class Piece {
        class Text(val text: String) : Piece()
        class StickerPiece(val name: String) : Piece()
        class VoicePiece(val text: String) : Piece()
        class FilePiece(val path: String) : Piece()
        class DirPiece(val path: String) : Piece()
        /** 只贴清单，不发文件 —— 「让它列目录却把文件发过来」的根治点 */
        class ListPiece(val path: String) : Piece()
        /** 现画一张图：走 image 槽位的生图接口，和 [FilePiece] 的「发本机图」是两回事 */
        class ImagePiece(val prompt: String) : Piece()
    }

    private val markerRe = Regex(
        "\\[(?:表情|表情包|贴纸)\\s*[:：]\\s*([^\\]]+)]" +
            "|\\[(?:文件|图片)\\s*[:：]\\s*([^\\]]+)]" +
            "|\\[目录\\s*[:：]\\s*([^\\]]+)]" +
            "|\\[语音\\]\\s*([^\\n\\[]*)" +
            "|\\[(?:列目录|目录清单|看看目录)\\s*[:：]?\\s*([^\\]\\n]*)]" +
            "|\\[(?:画图|绘图|生图|生成图片|画一张)\\s*[:：]\\s*([^\\]]+)]"
    )

    private fun parsePieces(reply: String): List<Piece> {
        val out = ArrayList<Piece>()
        var cursor = 0
        markerRe.findAll(reply).forEach { match ->
            if (match.range.first > cursor) {
                val t = reply.substring(cursor, match.range.first).trim()
                if (t.isNotEmpty()) out.add(Piece.Text(t))
            }
            val sticker = match.groupValues[1]
            val file = match.groupValues[2]
            val dir = match.groupValues[3]
            val voice = match.groupValues[4]
            val listing = match.groupValues[5]
            val drawing = match.groupValues[6]
            when {
                sticker.isNotBlank() -> out.add(Piece.StickerPiece(sticker))
                file.isNotBlank() -> out.add(Piece.FilePiece(file))
                dir.isNotBlank() -> out.add(Piece.DirPiece(dir))
                voice.isNotBlank() -> out.add(Piece.VoicePiece(voice))
                listing.isNotBlank() -> out.add(Piece.ListPiece(listing))
                drawing.isNotBlank() -> out.add(Piece.ImagePiece(drawing))
            }
            cursor = match.range.last + 1
        }
        if (cursor < reply.length) {
            val t = reply.substring(cursor).trim()
            if (t.isNotEmpty()) out.add(Piece.Text(t))
        }
        if (out.isEmpty()) out.add(Piece.Text(reply.trim()))
        return out
    }

    private suspend fun deliver(conv: Conversation, rawReply: String, cfg: Config, senderId: String) {
        val pieces = parsePieces(rawReply)
        var totalSent = 0
        val maxTotal = 10
        val textLimit = if (cfg.send.split) cfg.send.maxSegments.coerceIn(1, 8) else 8
        var textSent = 0
        var exit = false

        for (piece in pieces) {
            if (exit || totalSent >= maxTotal) break
            when (piece) {
                is Piece.Text -> {
                    val parts = if (cfg.send.split) {
                        Splitter.split(piece.text, textLimit, cfg.send.maxChars.coerceIn(20, 200))
                    } else {
                        listOf(piece.text)
                    }
                    for (part in parts) {
                        if (textSent >= textLimit || totalSent >= maxTotal) break
                        if (part.isBlank()) continue
                        val menu = if (cfg.send.attachMenu) cfg.menus.firstOrNull { it.id == "main" } else null
                        val ok = Sender.sendText(conv, part, menu)
                        totalSent++
                        textSent++
                        if (!ok) {
                            exit = true
                            break
                        }
                        delay(gapFor(part, cfg))
                    }
                }
                is Piece.StickerPiece -> {
                    val sticker = findSticker(piece.name)
                    if (sticker == null) {
                        Logs.w("表情库里没有：${piece.name}")
                    } else {
                        Sender.sendSticker(conv, sticker)
                        totalSent++
                        delay(600)
                    }
                }
                is Piece.VoicePiece -> {
                    speak(conv, piece.text, report = false)
                    totalSent++
                }
                is Piece.FilePiece -> {
                    if (isOwner(senderId) || Prefs.current.send.fileForAll) {
                        Sender.sendFile(conv, piece.path)
                    } else {
                        Logs.w("非管理员请求发文件，已忽略：${piece.path}")
                    }
                    totalSent++
                    delay(500)
                }
                is Piece.DirPiece -> {
                    if (isOwner(senderId) || Prefs.current.send.fileForAll) {
                        Sender.sendDirectory(conv, piece.path)
                    } else {
                        Logs.w("非管理员请求发目录，已忽略：${piece.path}")
                    }
                    totalSent++
                    delay(500)
                }
                is Piece.ListPiece -> {
                    val target = piece.path.ifBlank { "/sdcard" }
                    val body = runCatching { FileBrowser.compactList(target) }
                        .getOrElse { "列不出来：${it.message}" }
                    Sender.sendText(conv, body)
                    totalSent++
                    delay(300)
                }
                is Piece.ImagePiece -> {
                    val ok = drawImage(conv, piece.prompt, cfg)
                    if (ok) totalSent++
                    delay(500)
                }
            }
        }
    }

    private fun gapFor(text: String, cfg: Config): Long {
        val base = Random.nextLong(
            cfg.send.minGapMs.coerceAtLeast(0).toLong(),
            cfg.send.maxGapMs.coerceAtLeast(cfg.send.minGapMs).toLong() + 1
        )
        val typing = if (cfg.send.typingSim) (text.length * 35L).coerceAtMost(1500L) else 0L
        return base + typing
    }

    private fun findSticker(name: String): Sticker? {
        val key = name.trim().lowercase()
        val list = Prefs.current.stickers
        return list.firstOrNull { it.name.lowercase() == key }
            ?: list.firstOrNull { it.name.lowercase().contains(key) || it.tags.lowercase().contains(key) }
    }

    /** 语音：TTS 现合成现发，失败时按需回报，不刷屏 */
    private fun speak(conv: Conversation, text: String, report: Boolean) {
        val cfg = Prefs.current
        if (!cfg.tts.enabled) {
            if (report) Sender.sendText(conv, "语音没开，先去应用里把 TTS 配上。")
            return
        }
        val script = voiceScript(text, cfg)
        runCatching { TtsApi.synthesize(script, cfg.tts) }
            .onSuccess { audio -> Sender.sendVoice(conv, audio.bytes) }
            .onFailure {
                Logs.e("语音合成失败：${it.message}")
                if (report) Sender.sendText(conv, "语音没合成出来：${it.message?.take(60)}")
            }
    }

    /**
     * 语音稿：`tts` 槽位填了才生效。
     *
     * 直接念聊天文字经常带表情符号、Markdown、颜文字，合成出来是噪音；
     * 配了语音模型就先把这段过一遍，转成能念的口语。没配就原样念，零成本零风险。
     */
    private fun voiceScript(text: String, cfg: Config): String {
        if (!cfg.routing.enabled || !cfg.routing.tts.filled) return text
        val slot = runCatching { cfg.routing.effective(cfg.routing.tts, cfg.ai) }
            .getOrDefault(cfg.ai)
            .copy(temperature = 0.4, maxTokens = 200)
        val msgs = listOf(
            AiApi.Msg("system", "把下面这句改写成能直接念出来的口语：只输出要念的字，不加引号、不解释、不带表情符号和 Markdown，保持原意，别啰嗦。"),
            AiApi.Msg("user", text)
        )
        val out = runCatching { AiApi.chat(msgs, slot) }.getOrNull()?.trim()
        return if (out.isNullOrBlank()) text else out
    }

    /**
     * 现画一张图并发出。
     *
     * 走 `image` 槽位；这一栏没填就回落到主模型那组（同一个中转站常常也能生图）。
     * 任何失败都只回一句人话，不抛出去——画不出来不能把后面的消息链带崩。
     */
    private fun drawImage(conv: Conversation, prompt: String, cfg: Config): Boolean {
        val clean = prompt.trim()
        if (clean.isBlank()) return false
        val slot = runCatching { cfg.routing.effective(cfg.routing.image, cfg.ai) }
            .getOrDefault(cfg.ai)
        return runCatching { ImageApi.generate(clean, slot) }
            .onSuccess { img ->
                val name = "draw-" + System.currentTimeMillis() + "." + img.ext
                val ok = Sender.sendImageBytes(conv, img.bytes, name)
                if (ok) Logs.i("→ 画图 ${img.ext} ${img.bytes.size / 1024}KB：${clean.take(30)}")
            }
            .onFailure {
                Logs.e("画图失败：${it.message}")
                Sender.sendText(conv, "画不出来：${it.message?.take(80)}")
            }
            .isSuccess
    }

    /* ---------------- 本地测试 ---------------- */

    fun testReply(text: String): Result<List<String>> = runCatching {
        val cfg = Prefs.current
        val messages = buildMessages(cfg, "test:local", text, "test:local", "我", false)
        val reply = AiApi.chat(messages, cfg.ai)
        remember(cfg, "test:local", text, reply, false, "我")
        if (cfg.send.split) Splitter.split(reply, cfg.send.maxSegments, cfg.send.maxChars) else listOf(reply)
    }

    fun testTts(text: String): Result<ByteArray> = runCatching {
        val audio = TtsApi.synthesize(text, Prefs.current.tts)
        audio.bytes
    }

    fun clearTestHistory() {
        synchronized(historyLock) { histories.remove("test:local") }
    }

    /** 清空所有会话上下文（关于页的「清空对话记忆」） */
    fun clearAllHistory() {
        synchronized(historyLock) { histories.clear() }
    }

    /** 设置页里直接看当前生效的系统提示词 */
    fun previewPrompt(isGroup: Boolean = true): String =
        buildSystemPrompt(Prefs.current, isGroup) +
            memoryBlock(Prefs.current, "preview", "预览", "")
}