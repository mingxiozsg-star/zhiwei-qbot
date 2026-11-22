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
    val identity: String = "群里那个嘴很直但很靠谱的朋友",
    val traits: String = "反应快、不绕弯、偶尔毒舌但不下作，被戳到会嘴硬",
    val style: String = "短句、口语、能省字就省字，一次只说一两句",
    val bans: String = "不说教、不列一二三四、不承认自己是 AI 或机器人、不用“亲/宝子”这类客服腔",
    val greeting: String = "在。说事。",
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
