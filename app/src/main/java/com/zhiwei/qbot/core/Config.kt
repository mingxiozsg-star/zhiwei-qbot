package com.zhiwei.qbot.core

import org.json.JSONArray
import org.json.JSONObject

/* ---------------- JSON 取值小工具：容忍缺字段与 null ---------------- */

internal fun JSONObject.str(key: String, def: String = ""): String =
    if (has(key) && !isNull(key)) optString(key, def) else def

internal fun JSONObject.num(key: String, def: Int = 0): Int =
    if (has(key) && !isNull(key)) optInt(key, def) else def

internal fun JSONObject.dbl(key: String, def: Double = 0.0): Double =
    if (has(key) && !isNull(key)) optDouble(key, def) else def

internal fun JSONObject.flag(key: String, def: Boolean = false): Boolean =
    if (has(key) && !isNull(key)) optBoolean(key, def) else def

internal fun JSONObject.arr(key: String): JSONArray =
    if (has(key) && !isNull(key)) optJSONArray(key) ?: JSONArray() else JSONArray()

internal fun JSONObject.obj(key: String): JSONObject =
    if (has(key) && !isNull(key)) optJSONObject(key) ?: JSONObject() else JSONObject()

/* ---------------- 人设 ---------------- */

data class Persona(
    val name: String = "知微",
    val identity: String = "全能型 AI 小助手，女生，脑子快、嘴也快",
    val traits: String = "可爱、情绪丰富：会撒娇、会得意、会闹脾气；被怼会反驳，被惹恼会骂两句但不脏到难看",
    val style: String = "短句口语，语气词多（诶、嘛、呀、哼、啦），偶尔拖长音；不客服腔、不端着",
    val bans: String = "不人身攻击、不碰政治、不涉黄、不歧视；不无脑道歉，不假装自己没情绪",
    val greeting: String = "在呢～说吧。",
    val extra: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("identity", identity)
        .put("traits", traits)
        .put("style", style)
        .put("bans", bans)
        .put("greeting", greeting)
        .put("extra", extra)

    companion object {
        fun from(o: JSONObject) = Persona(
            name = o.str("name", "知微"),
            identity = o.str("identity"),
            traits = o.str("traits"),
            style = o.str("style"),
            bans = o.str("bans"),
            greeting = o.str("greeting", "在。说事。"),
            extra = o.str("extra")
        )
    }
}

/* ---------------- 模型 ---------------- */

data class AiConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.85,
    val maxTokens: Int = 400,
    val history: Int = 10,
    val timeoutSec: Int = 60,
    val extraPrompt: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("temperature", temperature)
        .put("maxTokens", maxTokens)
        .put("history", history)
        .put("timeoutSec", timeoutSec)
        .put("extraPrompt", extraPrompt)

    companion object {
        fun from(o: JSONObject) = AiConfig(
            baseUrl = o.str("baseUrl", "https://api.openai.com/v1"),
            apiKey = o.str("apiKey"),
            model = o.str("model", "gpt-4o-mini"),
            temperature = o.dbl("temperature", 0.85),
            maxTokens = o.num("maxTokens", 400),
            history = o.num("history", 10),
            timeoutSec = o.num("timeoutSec", 60),
            extraPrompt = o.str("extraPrompt")
        )
    }
}

/* ---------------- 语音 ---------------- */

data class TtsConfig(
    val enabled: Boolean = false,
    val url: String = "",
    val method: String = "POST",
    val headers: String = "{\"Content-Type\":\"application/json\"}",
    val body: String = "{\"text\":\"{{text}}\",\"format\":\"silk\"}",
    /** url：响应里给音频链接；base64：响应里给 base64；raw：响应体直接是音频 */
    val responseType: String = "base64",
    val responsePath: String = "data.audio",
    /** 接口输出的真实格式：silk / wav / mp3 */
    val format: String = "silk",
    /** 可选：外部转码服务，POST {"audio":"base64"}，返回 silk 的 base64 或二进制 */
    val converterUrl: String = "",
    val autoVoiceRate: Int = 0,
    val maxSeconds: Int = 30
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("url", url)
        .put("method", method)
        .put("headers", headers)
        .put("body", body)
        .put("responseType", responseType)
        .put("responsePath", responsePath)
        .put("format", format)
        .put("converterUrl", converterUrl)
        .put("autoVoiceRate", autoVoiceRate)
        .put("maxSeconds", maxSeconds)

    companion object {
        fun from(o: JSONObject) = TtsConfig(
            enabled = o.flag("enabled"),
            url = o.str("url"),
            method = o.str("method", "POST"),
            headers = o.str("headers", "{\"Content-Type\":\"application/json\"}"),
            body = o.str("body", "{\"text\":\"{{text}}\",\"format\":\"silk\"}"),
            responseType = o.str("responseType", "base64"),
            responsePath = o.str("responsePath", "data.audio"),
            format = o.str("format", "silk"),
            converterUrl = o.str("converterUrl"),
            autoVoiceRate = o.num("autoVoiceRate", 0),
            maxSeconds = o.num("maxSeconds", 30)
        )
    }
}

/* ---------------- 模型分配 ---------------- */

/**
 * 按用途分配模型。
 *
 * 每一项都可以单独填 BaseURL / Key / 模型名；
 * 留空就自动回落到「普通对话」那一组，所以只想填一组的人不会被逼着填四遍。
 */
data class ModelSlot(
    /** 留空则用全局默认（普通对话） */
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: Double = 0.85,
    val maxTokens: Int = 400
) {
    val filled: Boolean get() = model.isNotBlank() && baseUrl.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("baseUrl", baseUrl)
        .put("apiKey", apiKey)
        .put("model", model)
        .put("temperature", temperature)
        .put("maxTokens", maxTokens)

    companion object {
        fun from(o: JSONObject) = ModelSlot(
            baseUrl = o.str("baseUrl"),
            apiKey = o.str("apiKey"),
            model = o.str("model"),
            temperature = o.dbl("temperature", 0.85),
            maxTokens = o.num("maxTokens", 400)
        )
    }
}

/**
 * 四类用途的模型。
 *
 * - [chat]：普通对话（必填，其它空着都回落到它）
 * - [vision]：识图模型 —— 别人发来图片时，把图片喂给它看
 * - [tts]：语音稿生成
 * - [image]：画图（生图）
 *
 * 旧配置里这一栏叫 subagent（当时误当作「师徒/复杂推理」），读取时兼容迁移。
 */
data class ModelRouting(
    val enabled: Boolean = false,
    val chat: ModelSlot = ModelSlot(),
    val vision: ModelSlot = ModelSlot(),
    val tts: ModelSlot = ModelSlot(),
    val image: ModelSlot = ModelSlot()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("chat", chat.toJson())
        .put("vision", vision.toJson())
        .put("tts", tts.toJson())
        .put("image", image.toJson())

    /**
     * 取某一用途实际生效的配置。
     * 空着的项一律回落到 [fallback]（普通对话），这就是「普通模型能顶替就不用填」。
     */
    fun effective(slot: ModelSlot, fallback: AiConfig): AiConfig {
        if (!slot.filled) return fallback
        return AiConfig(
            baseUrl = slot.baseUrl.trim(),
            apiKey = slot.apiKey.trim().ifBlank { fallback.apiKey },
            model = slot.model.trim(),
            temperature = slot.temperature,
            maxTokens = slot.maxTokens,
            history = fallback.history,
            timeoutSec = fallback.timeoutSec,
            extraPrompt = fallback.extraPrompt
        )
    }

    companion object {
        fun from(o: JSONObject): ModelRouting {
            // 兼容旧配置：v1.3.0 这一栏叫 subagent
            val visionNode = o.obj("vision").takeIf { it.length() > 0 } ?: o.obj("subagent")
            return ModelRouting(
                enabled = o.flag("enabled"),
                chat = ModelSlot.from(o.obj("chat")),
                vision = ModelSlot.from(visionNode),
                tts = ModelSlot.from(o.obj("tts")),
                image = ModelSlot.from(o.obj("image"))
            )
        }
    }
}

/* ---------------- 发送行为 ---------------- */

data class SendConfig(
    val split: Boolean = true,
    val maxSegments: Int = 3,
    val maxChars: Int = 60,
    val minGapMs: Int = 700,
    val maxGapMs: Int = 1600,
    val typingSim: Boolean = true,
    val passiveReply: Boolean = true,
    val attachMenu: Boolean = false,
    val atSender: Boolean = false,
    /** 允许发普通文件（file_type=4）；关掉后只发图片/视频/语音 */
    val allowPlainFile: Boolean = true,
    /** 普通文件也开放给非管理员 */
    val fileForAll: Boolean = false,
    /** 单个文件上限（MB），base64 上传的内存天花板 */
    val fileMaxMb: Int = 10
) {
    fun toJson(): JSONObject = JSONObject()
        .put("split", split)
        .put("maxSegments", maxSegments)
        .put("maxChars", maxChars)
        .put("minGapMs", minGapMs)
        .put("maxGapMs", maxGapMs)
        .put("typingSim", typingSim)
        .put("passiveReply", passiveReply)
        .put("attachMenu", attachMenu)
        .put("atSender", atSender)
        .put("allowPlainFile", allowPlainFile)
        .put("fileForAll", fileForAll)
        .put("fileMaxMb", fileMaxMb)

    companion object {
        fun from(o: JSONObject) = SendConfig(
            split = o.flag("split", true),
            maxSegments = o.num("maxSegments", 3),
            maxChars = o.num("maxChars", 60),
            minGapMs = o.num("minGapMs", 700),
            maxGapMs = o.num("maxGapMs", 1600),
            typingSim = o.flag("typingSim", true),
            passiveReply = o.flag("passiveReply", true),
            attachMenu = o.flag("attachMenu"),
            atSender = o.flag("atSender"),
            allowPlainFile = o.flag("allowPlainFile", true),
            fileForAll = o.flag("fileForAll"),
            fileMaxMb = o.num("fileMaxMb", 10)
        )
    }
}

/* ---------------- 记忆 ---------------- */

data class MemoryConfig(
    /** 总开关：关掉后不记新东西，也不往提示词里塞旧记忆 */
    val enabled: Boolean = true,
    /** 每个人最多留多少条 */
    val maxNotes: Int = 400,
    /** 每次最多塞几条进提示词 */
    val injectLimit: Int = 12,
    /** 从聊天里自动抠名字/喜好 */
    val autoLearn: Boolean = true,
    /** 每一条消息都落一条记忆（推荐开） */
    val rememberAll: Boolean = true,
    /** 每次最多塞几条原始对话进提示词 */
    val recallMsgs: Int = 8
) {
    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("maxNotes", maxNotes)
        .put("injectLimit", injectLimit)
        .put("autoLearn", autoLearn)
        .put("rememberAll", rememberAll)
        .put("recallMsgs", recallMsgs)

    companion object {
        fun from(o: JSONObject) = MemoryConfig(
            enabled = o.flag("enabled", true),
            maxNotes = o.num("maxNotes", 400),
            injectLimit = o.num("injectLimit", 12),
            autoLearn = o.flag("autoLearn", true),
            rememberAll = o.flag("rememberAll", true),
            recallMsgs = o.num("recallMsgs", 8)
        )
    }
}

/* ---------------- 菜单 ---------------- */

data class MenuButton(
    val label: String = "按钮",
    /** 回调指令：menu:xxx 打开子菜单；/xxx 执行指令；其它当作用户发言交给 AI */
    val command: String = "",
    /** 1 蓝色主按钮，0 灰色线框 */
    val style: Int = 1,
    /** true 则当链接按钮，command 视为网址 */
    val link: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("command", command)
        .put("style", style)
        .put("link", link)

    companion object {
        fun from(o: JSONObject) = MenuButton(
            label = o.str("label", "按钮"),
            command = o.str("command"),
            style = o.num("style", 1),
            link = o.flag("link")
        )
    }
}

data class MenuDef(
    val id: String = "main",
    val title: String = "主菜单",
    /** 逗号分隔的触发词 */
    val triggers: String = "/菜单,菜单",
    val text: String = "点下面的按钮，别打字。",
    val perRow: Int = 3,
    val buttons: List<MenuButton> = emptyList()
) {
    fun toJson(): JSONObject {
        val a = JSONArray()
        buttons.forEach { a.put(it.toJson()) }
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("triggers", triggers)
            .put("text", text)
            .put("perRow", perRow)
            .put("buttons", a)
    }

    companion object {
        fun from(o: JSONObject): MenuDef {
            val list = ArrayList<MenuButton>()
            val a = o.arr("buttons")
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { list.add(MenuButton.from(it)) }
            return MenuDef(
                id = o.str("id", "main"),
                title = o.str("title", "菜单"),
                triggers = o.str("triggers"),
                text = o.str("text"),
                perRow = o.num("perRow", 3).coerceIn(1, 5),
                buttons = list
            )
        }
    }
}

/* ---------------- 表情包 ---------------- */

data class Sticker(
    val name: String,
    val file: String,
    val tags: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("file", file)
        .put("tags", tags)

    companion object {
        fun from(o: JSONObject) = Sticker(
            name = o.str("name", "表情"),
            file = o.str("file"),
            tags = o.str("tags")
        )
    }
}

/* ---------------- 总配置 ---------------- */

data class Config(
    val appId: String = "",
    val appSecret: String = "",
    val owners: String = "",
    val sandbox: Boolean = false,
    val apiBase: String = "https://api.sgroup.qq.com",
    val tokenUrl: String = "https://bots.qq.com/app/getAppAccessToken",
    val backupGateway: String = "",
    val gatewayPrefix: String = "",
    val persona: Persona = Persona(),
    val ai: AiConfig = AiConfig(),
    val tts: TtsConfig = TtsConfig(),
    val send: SendConfig = SendConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val routing: ModelRouting = ModelRouting(),
    val shortcuts: List<Shortcut> = emptyList(),
    val menus: List<MenuDef> = defaultMenus(),
    val stickers: List<Sticker> = emptyList(),
    val aiGroup: Boolean = true,
    val aiC2c: Boolean = true,
    val autoStart: Boolean = true,
    val wakeLock: Boolean = true,
    val notifyOnMessage: Boolean = false,
    val verbose: Boolean = true,
    val onboarded: Boolean = false
) {
    fun toJson(): JSONObject {
        val ma = JSONArray()
        menus.forEach { ma.put(it.toJson()) }
        val sa = JSONArray()
        stickers.forEach { sa.put(it.toJson()) }
        return JSONObject()
            .put("appId", appId)
            .put("appSecret", appSecret)
            .put("owners", owners)
            .put("sandbox", sandbox)
            .put("apiBase", apiBase)
            .put("tokenUrl", tokenUrl)
            .put("backupGateway", backupGateway)
            .put("gatewayPrefix", gatewayPrefix)
            .put("persona", persona.toJson())
            .put("ai", ai.toJson())
            .put("tts", tts.toJson())
            .put("send", send.toJson())
            .put("memory", memory.toJson())
            .put("routing", routing.toJson())
            .put("shortcuts", Shortcuts.toJson(this@Config))
            .put("menus", ma)
            .put("stickers", sa)
            .put("aiGroup", aiGroup)
            .put("aiC2c", aiC2c)
            .put("autoStart", autoStart)
            .put("wakeLock", wakeLock)
            .put("notifyOnMessage", notifyOnMessage)
            .put("verbose", verbose)
            .put("onboarded", onboarded)
    }

    companion object {
        fun from(o: JSONObject): Config {
            val menus = ArrayList<MenuDef>()
            val ma = o.arr("menus")
            for (i in 0 until ma.length()) ma.optJSONObject(i)?.let { menus.add(MenuDef.from(it)) }
            val stickers = ArrayList<Sticker>()
            val sa = o.arr("stickers")
            for (i in 0 until sa.length()) sa.optJSONObject(i)?.let { stickers.add(Sticker.from(it)) }
            val shortcuts = ArrayList<Shortcut>()
            val sca = o.arr("shortcuts")
            for (i in 0 until sca.length()) sca.optJSONObject(i)?.let { shortcuts.add(Shortcut.from(it)) }
            return Config(
                appId = o.str("appId"),
                appSecret = o.str("appSecret"),
                owners = o.str("owners"),
                sandbox = o.flag("sandbox"),
                apiBase = o.str("apiBase", "https://api.sgroup.qq.com"),
                tokenUrl = o.str("tokenUrl", "https://bots.qq.com/app/getAppAccessToken"),
                backupGateway = o.str("backupGateway"),
                gatewayPrefix = o.str("gatewayPrefix"),
                persona = Persona.from(o.obj("persona")),
                ai = AiConfig.from(o.obj("ai")),
                tts = TtsConfig.from(o.obj("tts")),
                send = SendConfig.from(o.obj("send")),
                memory = MemoryConfig.from(o.obj("memory")),
                routing = ModelRouting.from(o.obj("routing")),
                shortcuts = shortcuts,
                menus = if (menus.isEmpty()) defaultMenus() else menus,
                stickers = stickers,
                aiGroup = o.flag("aiGroup", true),
                aiC2c = o.flag("aiC2c", true),
                autoStart = o.flag("autoStart", true),
                wakeLock = o.flag("wakeLock", true),
                notifyOnMessage = o.flag("notifyOnMessage"),
                verbose = o.flag("verbose", true),
                onboarded = o.flag("onboarded")
            )
        }

        fun defaultMenus(): List<MenuDef> = listOf(
            MenuDef(
                id = "main",
                title = "主菜单",
                triggers = "/菜单,菜单,帮助",
                text = "点按钮，别打字。",
                perRow = 3,
                buttons = listOf(
                    MenuButton("随便聊聊", "你好", 1),
                    MenuButton("功能", "menu:tools", 0),
                    MenuButton("状态", "/状态", 0)
                )
            ),
            MenuDef(
                id = "tools",
                title = "功能菜单",
                triggers = "",
                text = "挑一个。",
                perRow = 3,
                buttons = listOf(
                    MenuButton("说句话", "/语音你好呀", 1),
                    MenuButton("表情包", "/表情", 0),
                    MenuButton("返回", "menu:main", 0)
                )
            )
        )
    }
}
