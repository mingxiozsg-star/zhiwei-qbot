package com.zhiwei.qbot.bot

import com.zhiwei.qbot.core.Config
import com.zhiwei.qbot.core.Logs
import com.zhiwei.qbot.core.Memory
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Sticker
import com.zhiwei.qbot.net.AiApi
import com.zhiwei.qbot.net.QqApi
import com.zhiwei.qbot.net.TtsApi
import com.zhiwei.qbot.util.DeviceInfo
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
                val conv = Conversation(true, d.optString("group_openid"), d.optString("id"))
                val author = d.optJSONObject("author")
                handleMessage(
                    conv,
                    d.optString("content"),
                    author?.optString("member_openid") ?: "",
                    author?.optString("member_name") ?: "群友"
                )
            }
            "C2C_MESSAGE_CREATE" -> {
                val author = d.optJSONObject("author")
                val conv = Conversation(false, author?.optString("user_openid") ?: "", d.optString("id"))
                handleMessage(
                    conv,
                    d.optString("content"),
                    author?.optString("user_openid") ?: "",
                    author?.optString("username") ?: "对方"
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

    private fun rememberPerson(senderId: String, senderName: String, text: String) {
        if (!Prefs.current.memory.enabled) return
        if (senderId.isBlank()) return
        Memory.touch(senderId, senderName)
        Memory.mineFromMessage(senderId, text)
    }

    private fun handleMessage(conv: Conversation, raw: String, senderId: String, senderName: String) {
        if (conv.openId.isBlank()) return
        val text = clean(raw)
        if (text.isBlank()) return
        Logs.i("[${if (conv.isGroup) "群" else "私聊"}] ${senderName.ifBlank { "某人" }}：${text.take(60)}")
        rememberPerson(senderId, senderName, text)
        scope.launch {
            lockFor(convKey(conv)).withLock {
                try {
                    if (routeCommand(text, conv, senderId, senderName)) return@withLock
                    val cfg = Prefs.current
                    if (conv.isGroup && !cfg.aiGroup) return@withLock
                    if (!conv.isGroup && !cfg.aiC2c) return@withLock
                    replyWithAi(conv, text, senderId, senderName)
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
        val isGroup = groupOpenId.isNotBlank()
        val conv = Conversation(isGroup, if (isGroup) groupOpenId else userOpenId, interactionId)
        Logs.i("按钮回调：${command.ifBlank { "(空)" }}")
        if (command.isBlank()) return
        scope.launch {
            lockFor(convKey(conv)).withLock {
                try {
                    if (routeCommand(command, conv, userOpenId, "", true)) return@withLock
                    val cfg = Prefs.current
                    if (isGroup && !cfg.aiGroup) return@withLock
                    if (!isGroup && !cfg.aiC2c) return@withLock
                    replyWithAi(conv, command, userOpenId, "群友")
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

    private fun myMemoryText(senderId: String): String {
        val p = Memory.person(senderId)
            ?: return "我这儿还没有你的资料，多聊几句就记住了。"
        val sb = StringBuilder()
        sb.append("我记得你：").append(p.displayName())
        if (p.names.size > 1) {
            sb.append("（也叫 ").append(p.names.filter { !it.equals(p.name, true) }.take(3).joinToString("、")).append("）")
        }
        sb.append('\n')
        sb.append("聊过 ").append(p.msgCount).append(" 句｜第一次见 ")
            .append(dateText(p.firstSeen)).append('\n')
        if (p.notes.isEmpty()) {
            sb.append("暂时没记下别的。想让我记，就说「/记住 你喜欢猫」。")
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
        senderId: String,
        senderName: String,
        fromButton: Boolean = false
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
                Sender.sendText(conv, "配置已重载。")
                return true
            }
            lower == "/重连" -> {
                if (!isOwner(senderId)) {
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
                if (!isOwner(senderId)) {
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
                    sb.append("· ").append(p.displayName()).append("（").append(p.notes.size).append(" 条，")
                        .append(p.msgCount).append(" 句）\n")
                }
                if (people.size > 8) sb.append("……还有 ").append(people.size - 8).append(" 人")
                Sender.sendText(conv, sb.toString().trim())
                return true
            }
            lower == "/记忆" -> {
                Sender.sendText(conv, myMemoryText(senderId))
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
                parts.take(3).forEach { if (Memory.learn(senderId, it, "manual")) added++ }
                Sender.sendText(
                    conv,
                    if (added > 0) "记下了（$added 条）。以后在群里也认得你。" else "这条我好像已经记过了。"
                )
                return true
            }
            lower == "/忘记" || lower == "/忘掉我" -> {
                Memory.forget(senderId)
                Sender.sendText(conv, "行，把你忘干净了。")
                return true
            }
            lower.startsWith("/语音") -> {
                if (!isOwner(senderId)) {
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
                if (!isOwner(senderId) && !cfg.send.fileForAll) {
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
                            "图片、视频、silk 语音、apk 都能发，其它按普通文件发。"
                    )
                    return true
                }
                scope.launch { Sender.sendFile(conv, target) }
                return true
            }
            lower == "/帮助" || lower == "/指令" -> {
                val main = cfg.menus.firstOrNull { it.id == "main" }
                if (main != null) Sender.sendMenu(conv, main)
                else Sender.sendText(
                    conv,
                    "指令：/状态 /记忆 /记住 内容 /忘记 /文件 路径或目录 /语音 文本 /表情 名字 /分句开 /分句关 /清除 /重连"
                )
                return true
            }
        }
        if (fromButton && text.isNotBlank() && !text.startsWith("/")) {
            // 按钮回调了纯文本，交给 AI 当成一句话处理
            return false
        }
        return false
    }

    /* ---------------- AI ---------------- */

    private fun convKey(conv: Conversation): String =
        (if (conv.isGroup) "g:" else "c:") + conv.openId

    private fun buildSystemPrompt(cfg: Config, isGroup: Boolean): String {
        val p = cfg.persona
        val stickerNames = cfg.stickers.take(12).joinToString("、") { it.name }
        val sb = StringBuilder()
        sb.append("你在 QQ 的").append(if (isGroup) "群聊" else "私聊")
            .append("里跟人聊天。你不是助手程序，你就是").append(p.name).append("本人。\n")
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
        var rule = 7
        if (stickerNames.isNotBlank()) {
            sb.append(rule++).append(". 想发表情包时，在句末写 [表情:名字]，可用：").append(stickerNames).append('\n')
        }
        if (cfg.tts.enabled) {
            sb.append(rule++).append(". 想发语音时，单独一行写 [语音]要说的话。\n")
        }
        sb.append(rule++).append(". 想发本机图片时，单独一行写 [图片:/sdcard/xxx.jpg]。\n")
        sb.append(rule++).append(". 想发本机文件时，单独一行写 [文件:/sdcard/xxx.pdf]，一次最多一个。\n")
        sb.append(rule++).append(". 想发一整个目录时，单独一行写 [目录:/sdcard/Download/某个文件夹]。\n")
        sb.append(rule).append(". 不要输出 Markdown、不要列清单、不要用标题。\n")
        if (p.extra.isNotBlank()) sb.append("补充设定：").append(p.extra).append('\n')
        if (cfg.ai.extraPrompt.isNotBlank()) sb.append("额外要求：").append(cfg.ai.extraPrompt).append('\n')
        return sb.toString()
    }

    /** 当前说话人的资料 + 相关记忆，直接拼进系统提示词 */
    private fun memoryBlock(cfg: Config, senderId: String, senderName: String, text: String): String {
        if (!cfg.memory.enabled) return ""
        val p = Memory.person(senderId) ?: return ""
        val notes = Memory.recall(senderId, text, cfg.memory.injectLimit.coerceIn(0, 20))
        val sb = StringBuilder()
        sb.append("\n【当前说话的人】").append(p.displayName().ifBlank { senderName })
        sb.append("（openid 尾号 ").append(senderId.takeLast(6)).append("）")
        if (p.names.size > 1) {
            val others = p.names.filter { !it.equals(p.name, true) }.take(3)
            if (others.isNotEmpty()) sb.append("，也叫 ").append(others.joinToString("、"))
        }
        sb.append("｜第一次见 ").append(dateText(p.firstSeen))
        sb.append("｜已经聊过 ").append(p.msgCount).append(" 句\n")
        if (notes.isNotEmpty()) {
            sb.append("【你记得他】\n")
            notes.forEachIndexed { i, n -> sb.append(i + 1).append(". ").append(n).append('\n') }
        }
        return sb.toString()
    }

    private fun buildMessages(
        cfg: Config,
        key: String,
        text: String,
        senderId: String,
        senderName: String,
        isGroup: Boolean
    ): List<AiApi.Msg> {
        val list = ArrayList<AiApi.Msg>()
        val system = buildSystemPrompt(cfg, isGroup) + memoryBlock(cfg, senderId, senderName, text)
        list.add(AiApi.Msg("system", system))
        synchronized(historyLock) {
            histories[key]?.takeLast(cfg.ai.history.coerceIn(0, 40))?.let { list.addAll(it) }
        }
        list.add(AiApi.Msg("user", if (isGroup) "${senderName.ifBlank { "群友" }}：$text" else text))
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

    private suspend fun replyWithAi(conv: Conversation, text: String, senderId: String, senderName: String) {
        val cfg = Prefs.current
        val key = convKey(conv)
        val messages = buildMessages(cfg, key, text, senderId, senderName, conv.isGroup)
        val started = System.currentTimeMillis()
        var reply: String? = null
        var lastError = ""
        aiGate.withPermit {
            for (attempt in 1..3) {
                try {
                    reply = AiApi.chat(messages, cfg.ai)
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
        val answer = harvestMemories(draft, senderId)
        remember(cfg, key, text, answer, conv.isGroup, senderName)
        try {
            deliver(conv, answer, cfg, senderId)
        } catch (e: Exception) {
            Logs.e("发送流程出错：${e.message}")
        }
    }

    /** 把模型写的 [记忆:xxx] 落库，并从要发出去的文字里删掉 */
    private fun harvestMemories(reply: String, senderId: String): String {
        if (!Prefs.current.memory.enabled) return reply
        val re = Regex("\\[记忆\\s*[:：]\\s*([^\\]]+)]")
        val found = re.findAll(reply).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        found.take(2).forEach { Memory.learn(senderId, it, "ai") }
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
    }

    private val markerRe = Regex(
        "\\[(?:表情|表情包|贴纸)\\s*[:：]\\s*([^\\]]+)]" +
            "|\\[(?:文件|图片)\\s*[:：]\\s*([^\\]]+)]" +
            "|\\[目录\\s*[:：]\\s*([^\\]]+)]" +
            "|\\[语音\\]\\s*([^\\n\\[]*)"
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
            when {
                sticker.isNotBlank() -> out.add(Piece.StickerPiece(sticker))
                file.isNotBlank() -> out.add(Piece.FilePiece(file))
                dir.isNotBlank() -> out.add(Piece.DirPiece(dir))
                voice.isNotBlank() -> out.add(Piece.VoicePiece(voice))
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
        runCatching { TtsApi.synthesize(text, cfg.tts) }
            .onSuccess { audio -> Sender.sendVoice(conv, audio.bytes) }
            .onFailure {
                Logs.e("语音合成失败：${it.message}")
                if (report) Sender.sendText(conv, "语音没合成出来：${it.message?.take(60)}")
            }
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