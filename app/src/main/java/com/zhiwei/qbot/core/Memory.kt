package com.zhiwei.qbot.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条长期记忆（提炼出来的结论，比如「喜欢猫」「在杭州」） */
data class Note(
    val text: String,
    val at: Long = System.currentTimeMillis(),
    val source: String = "auto"
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("at", at)
        .put("source", source)

    companion object {
        fun from(o: JSONObject) = Note(
            text = o.str("text"),
            at = o.optLong("at", System.currentTimeMillis()),
            source = o.str("source", "auto")
        )
    }
}

/**
 * 一条原始对话流水。
 * 不再靠正则「抠喜好」，而是把这个人说过的每一句都留着，
 * 需要时按相关度取回，等于把聊天记录挂在他名下。
 */
data class Msg(
    val at: Long = System.currentTimeMillis(),
    /** true = 用户说的；false = 机器人回的 */
    val fromUser: Boolean = true,
    val text: String = "",
    /** 群 / 私聊 + 群 id 尾部，用来知道他这句话是在哪儿说的 */
    val scene: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("at", at)
        .put("u", fromUser)
        .put("text", text)
        .put("scene", scene)

    companion object {
        fun from(o: JSONObject) = Msg(
            at = o.optLong("at", System.currentTimeMillis()),
            fromUser = o.flag("u", true),
            text = o.str("text"),
            scene = o.str("scene")
        )
    }
}

/**
 * 一个人。
 *
 * 关键点：QQ 群消息里的 member_openid 是「按群独立」的，
 * 同一个人在 A 群和 B 群的 openid 完全不同，只按它存必然换群失忆。
 * 所以这里用 union_openid 作主键，并把见过的所有 id 都记进 [aliasIds]，
 * 任何旧 id 再出现都能指回同一个人。
 */
data class Person(
    /** 主键：优先 union_openid（应用级唯一），没有时退回 openid */
    val openId: String,
    var name: String = "",
    val names: MutableList<String> = ArrayList(),
    /** 见过的所有平台 id（各群 member_openid、user_openid、历史旧键） */
    val aliasIds: MutableList<String> = ArrayList(),
    var firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var msgCount: Int = 0,
    val notes: MutableList<Note> = ArrayList(),
    /** 原始对话流水，最近的在后面 */
    val msgs: MutableList<Msg> = ArrayList()
) {
    fun displayName(): String = name.ifBlank { names.lastOrNull().orEmpty().ifBlank { "路人" } }

    /** 深拷贝一份给 UI/提示词用，避免外面改到内存里的那份 */
    fun deepCopy(): Person = Person(
        openId = openId,
        name = name,
        names = ArrayList(names),
        aliasIds = ArrayList(aliasIds),
        firstSeen = firstSeen,
        lastSeen = lastSeen,
        msgCount = msgCount,
        notes = ArrayList(notes),
        msgs = ArrayList(msgs)
    )

    fun toJson(): JSONObject {
        val na = JSONArray()
        names.forEach { na.put(it) }
        val aa = JSONArray()
        aliasIds.forEach { aa.put(it) }
        val ta = JSONArray()
        notes.forEach { ta.put(it.toJson()) }
        val ma = JSONArray()
        msgs.forEach { ma.put(it.toJson()) }
        return JSONObject()
            .put("openId", openId)
            .put("name", name)
            .put("names", na)
            .put("aliases", aa)
            .put("firstSeen", firstSeen)
            .put("lastSeen", lastSeen)
            .put("msgCount", msgCount)
            .put("notes", ta)
            .put("msgs", ma)
    }

    companion object {
        fun from(o: JSONObject): Person {
            val p = Person(openId = o.str("openId"))
            p.name = o.str("name")
            val na = o.arr("names")
            for (i in 0 until na.length()) na.optString(i).takeIf { it.isNotBlank() }?.let { p.names.add(it) }
            val aa = o.arr("aliases")
            for (i in 0 until aa.length()) aa.optString(i).takeIf { it.isNotBlank() }?.let { p.aliasIds.add(it) }
            p.firstSeen = o.optLong("firstSeen", System.currentTimeMillis())
            p.lastSeen = o.optLong("lastSeen", System.currentTimeMillis())
            p.msgCount = o.num("msgCount")
            val ta = o.arr("notes")
            for (i in 0 until ta.length()) ta.optJSONObject(i)?.let { p.notes.add(Note.from(it)) }
            val ma = o.arr("msgs")
            for (i in 0 until ma.length()) ma.optJSONObject(i)?.let { p.msgs.add(Msg.from(it)) }
            p.msgs.sortBy { it.at }
            return p
        }
    }
}

/**
 * 长期记忆库：按「人」存，群聊和私聊共用同一份，换群也不丢。
 *
 * 四层结构：
 * 1. 短期上下文（Brain 的滑动窗口）——只顾最近几句的连贯；
 * 2. 原始流水（这里的 msgs）——他说过的每句话都留着，这是「记住所有东西」的地基；
 * 3. 人的资料（这里的 name/names/aliasIds）——昵称、别名、各群 id、首见时间、句数；
 * 4. 提炼记忆（这里的 notes）——喜欢什么、在哪个城市、答应过什么。
 *
 * 落盘到 filesDir/memory.json，写盘有节流，退出/崩溃前强制落盘。
 */
object Memory {

    private const val FILE_NAME = "memory.json"
    private const val MAX_PEOPLE = 300
    private const val MAX_NAMES = 8
    private const val MAX_NOTE_CHARS = 200
    private const val MAX_MSG_CHARS = 500
    private const val MAX_MSGS_PER_PERSON = 200
    private const val MAX_MSGS_TOTAL = 12_000

    private var ctx: Context? = null
    private val people = LinkedHashMap<String, Person>()

    /** 倒排索引：任何见过的 id（含旧键）→ 当前主键 */
    private val idIndex = HashMap<String, String>()

    private val lock = Any()
    private var lastSaveAt = 0L
    private var pending = false

    fun init(context: Context) {
        synchronized(lock) {
            if (ctx != null) return
            ctx = context.applicationContext
            load()
        }
    }

    private fun file(): File? = ctx?.let { File(it.filesDir, FILE_NAME) }

    /* ---------------- 读写 ---------------- */

    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        runCatching {
            val obj = JSONObject(f.readText())
            val arr = obj.arr("people")
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i)?.let { Person.from(it) } ?: continue
                if (p.openId.isBlank()) continue
                people[p.openId] = p
                idIndex[p.openId] = p.openId
                p.aliasIds.forEach { a -> if (a.isNotBlank()) idIndex[a] = p.openId }
            }
            val (who, notes) = stats()
            Logs.i("记忆库已载入：$who 人｜$notes 条记忆｜${totalMsgsLocked()} 条对话")
        }.onFailure { Logs.e("记忆库读取失败：${it.message}") }
    }

    fun saveNow() {
        val f = file() ?: return
        synchronized(lock) {
            runCatching {
                val arr = JSONArray()
                people.values.forEach { arr.put(it.toJson()) }
                val obj = JSONObject().put("version", 2).put("people", arr)
                f.writeText(obj.toString())
                lastSaveAt = System.currentTimeMillis()
                pending = false
            }.onFailure { Logs.e("记忆库写入失败：${it.message}") }
        }
    }

    /** 导出完整记忆库（备份用） */
    fun exportJson(): String = synchronized(lock) {
        val arr = JSONArray()
        people.values.forEach { arr.put(it.toJson()) }
        JSONObject().put("version", 2).put("people", arr).toString(2)
    }

    /** 从备份导入，整体替换当前记忆库 */
    fun importJson(text: String): Result<Unit> = runCatching {
        val obj = JSONObject(text)
        val arr = obj.optJSONArray("people") ?: JSONArray()
        synchronized(lock) {
            people.clear()
            idIndex.clear()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i)?.let { Person.from(it) } ?: continue
                if (p.openId.isBlank()) continue
                people[p.openId] = p
                idIndex[p.openId] = p.openId
                p.aliasIds.forEach { a -> if (a.isNotBlank()) idIndex[a] = p.openId }
            }
        }
        saveNow()
    }

    /** 写盘节流：频繁聊天时最多 8 秒落一次，退出前再强制一次 */
    private fun scheduleSave() {
        synchronized(lock) {
            pending = true
            if (System.currentTimeMillis() - lastSaveAt < 8_000) return
        }
        Thread {
            runCatching { saveNow() }
        }.apply { isDaemon = true }.start()
    }

    /** 有待写内容就立刻落盘（退到后台、崩溃前调用） */
    fun flushIfPending() {
        val need = synchronized(lock) { pending }
        if (need) saveNow()
    }

    /* ---------------- 身份归并 ---------------- */

    /**
     * 把 [primary]（优先 union_openid）与 [alt]（群内 member_openid 等）解析成同一个人。
     * 两边此前若有各自独立的档案，这里合并成一份，历史记忆不会丢。
     */
    fun resolve(primary: String, alt: String = ""): String {
        val p = primary.trim()
        val a = alt.trim()
        if (p.isBlank() && a.isBlank()) return ""
        synchronized(lock) {
            val byPrimary = if (p.isNotBlank()) idIndex[p] else null
            val byAlt = if (a.isNotBlank()) idIndex[a] else null
            val key = when {
                byPrimary != null && byAlt != null && byPrimary != byAlt -> {
                    val keep = mergeInto(byPrimary, byAlt)
                    keep
                }
                byPrimary != null -> byPrimary
                byAlt != null -> {
                    val keep = byAlt
                    if (p.isNotBlank() && p != keep) aliasInternal(p, keep)
                    keep
                }
                p.isNotBlank() -> p
                else -> a
            }
            if (p.isNotBlank() && p != key) aliasInternal(p, key)
            if (a.isNotBlank() && a != key) aliasInternal(a, key)
            return key
        }
    }

    private fun aliasInternal(id: String, key: String) {
        if (id.isBlank() || id == key) return
        idIndex[id] = key
        people[key]?.let { p ->
            if (p.aliasIds.none { it == id }) p.aliasIds.add(id)
        }
    }

    /** 把 drop 并进 keep，返回 keep 键 */
    private fun mergeInto(keepKey: String, dropKey: String): String {
        val keep = people[keepKey]
        val drop = people.remove(dropKey)
        if (keep == null || drop == null) {
            idIndex[dropKey] = keepKey
            return keepKey
        }
        keep.firstSeen = minOf(keep.firstSeen, drop.firstSeen)
        keep.lastSeen = maxOf(keep.lastSeen, drop.lastSeen)
        keep.msgCount += drop.msgCount
        if (keep.name.isBlank()) keep.name = drop.name
        drop.names.forEach { n ->
            if (keep.names.none { it.equals(n, true) }) keep.names.add(n)
        }
        while (keep.names.size > MAX_NAMES) keep.names.removeAt(0)
        keep.notes.addAll(drop.notes)
        keep.notes.sortBy { it.at }
        keep.msgs.addAll(drop.msgs)
        keep.msgs.sortBy { it.at }
        trimLocked(keep)
        drop.aliasIds.forEach { a -> if (a.isNotBlank()) { idIndex[a] = keepKey; if (keep.aliasIds.none { it == a }) keep.aliasIds.add(a) } }
        idIndex[dropKey] = keepKey
        if (keep.aliasIds.none { it == dropKey }) keep.aliasIds.add(dropKey)
        keep.aliasIds.removeAll { it == keepKey }
        Logs.i("合并同一人档案：${dropKey.takeLast(6)} → ${keepKey.takeLast(6)}")
        return keepKey
    }

    /* ---------------- 记录 ---------------- */

    /** 见到一个人：更新名字/别名/计数，返回合并后的档案 */
    fun touch(key: String, name: String = ""): Person? {
        if (key.isBlank()) return null
        val person = synchronized(lock) {
            val now = System.currentTimeMillis()
            val p = people.getOrPut(key) { Person(openId = key, firstSeen = now) }
            idIndex[key] = key
            p.lastSeen = now
            p.msgCount += 1
            if (name.isNotBlank()) {
                val clean = name.trim().take(32)
                if (p.name.isBlank()) p.name = clean
                if (p.names.none { it.equals(clean, true) }) {
                    p.names.add(clean)
                    while (p.names.size > MAX_NAMES) p.names.removeAt(0)
                }
            }
            trimPeopleLocked(except = key)
            p.deepCopy()
        }
        scheduleSave()
        return person
    }

    /** 兼容旧调用：primary = 主键，alt = 备选 id */
    @Deprecated("用 touch(key, name) 或 touch(primary, alt, name)")
    fun touch(openId: String, name: String, ignored: Boolean): Person? = touch(openId, name)

    /**
     * 全量消息记忆：他说一句就落一条。
     * 不管有没有「我叫/我喜欢」这种句式，聊天记录本身就在。
     */
    fun rememberMsg(key: String, text: String, fromUser: Boolean, scene: String = ""): Boolean {
        val body = text.trim().replace(Regex("\\s+"), " ").take(MAX_MSG_CHARS)
        if (key.isBlank() || body.length < 1) return false
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val p = people.getOrPut(key) { Person(openId = key, firstSeen = now) }
            idIndex[key] = key
            p.lastSeen = now
            p.msgs.add(Msg(now, fromUser, body, scene.take(24)))
            trimLocked(p)
            trimPeopleLocked(except = key)
        }
        scheduleSave()
        return true
    }

    fun alias(key: String, alias: String) {
        val a = alias.trim().take(32)
        if (a.isBlank()) return
        synchronized(lock) {
            val p = people[key] ?: return
            if (p.names.none { it.equals(a, true) }) {
                p.names.add(a)
                while (p.names.size > MAX_NAMES) p.names.removeAt(0)
            }
            if (p.name.isBlank()) p.name = a
        }
        scheduleSave()
    }

    /** 记一条提炼记忆。返回 false 表示重复/无意义，不重复写 */
    fun learn(key: String, text: String, source: String = "auto"): Boolean {
        val clean = text.trim()
            .replace(Regex("\\s+"), " ")
            .trim('。', '，', ',', '.', ' ')
            .take(MAX_NOTE_CHARS)
        if (clean.length < 2) return false
        if (key.isBlank()) return false
        val maxNotes = Prefs.current.memory.maxNotes.coerceIn(10, 400)
        synchronized(lock) {
            val p = people.getOrPut(key) { Person(openId = key) }
            val dup = p.notes.any {
                it.text.equals(clean, true) || it.text.contains(clean) || clean.contains(it.text)
            }
            if (dup) return false
            p.notes.add(Note(clean, System.currentTimeMillis(), source))
            while (p.notes.size > maxNotes) p.notes.removeAt(0)
        }
        scheduleSave()
        return true
    }

    fun forget(key: String) {
        synchronized(lock) {
            val p = people.remove(key)
            idIndex.remove(key)
            p?.aliasIds?.forEach { idIndex.remove(it) }
        }
        scheduleSave()
    }

    fun forgetAll() {
        synchronized(lock) {
            people.clear()
            idIndex.clear()
        }
        saveNow()
    }

    fun deleteNote(key: String, index: Int): Boolean {
        synchronized(lock) {
            val p = people[key] ?: return false
            if (index !in p.notes.indices) return false
            p.notes.removeAt(index)
        }
        scheduleSave()
        return true
    }

    fun deleteMsg(key: String, index: Int): Boolean {
        synchronized(lock) {
            val p = people[key] ?: return false
            if (index !in p.msgs.indices) return false
            p.msgs.removeAt(index)
        }
        scheduleSave()
        return true
    }

    /* ---------------- 读取 ---------------- */

    fun person(key: String): Person? = synchronized(lock) {
        val k = idIndex[key] ?: key
        people[k]?.deepCopy()
    }

    fun allPeople(): List<Person> = synchronized(lock) {
        people.values.sortedByDescending { it.lastSeen }.map { it.deepCopy() }
    }

    fun stats(): Pair<Int, Int> = synchronized(lock) {
        people.size to people.values.sumOf { it.notes.size }
    }

    fun totalMsgs(): Int = synchronized(lock) { totalMsgsLocked() }

    private fun totalMsgsLocked(): Int = people.values.sumOf { it.msgs.size }

    /**
     * 取和这句话最相关的记忆：字面重合（中文按双字）优先，其次看新旧。
     * 永远带上最近两条，避免「明明记过却想不起来」。
     */
    fun recall(key: String, query: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        var notes: List<Note> = emptyList()
        synchronized(lock) {
            val k = idIndex[key] ?: key
            notes = people[k]?.notes?.toList() ?: emptyList()
        }
        if (notes.isEmpty()) return emptyList()

        val grams = gramsOf(query.lowercase(Locale.US))
        val now = System.currentTimeMillis()
        val scored = notes.map { note ->
            val t = note.text.lowercase(Locale.US)
            var score = 0.0
            grams.forEach { g -> if (t.contains(g)) score += 2.0 }
            val days = ((now - note.at) / 86_400_000L).coerceAtLeast(0)
            score += (14.0 - days).coerceIn(0.0, 14.0) / 7.0
            note to score
        }
        val picked = LinkedHashSet<String>()
        scored.sortedByDescending { it.second }.take(limit).forEach { picked.add(it.first.text) }
        notes.takeLast(2).forEach { picked.add(it.text) }
        return picked.toList()
    }

    /**
     * 取原始对话里跟他这句话最相关的几条，补上「提炼记忆没覆盖到」的细节。
     * 相关度用双字重合 + 时间近因，不依赖任何外部检索库。
     */
    fun recallMsgs(key: String, query: String, limit: Int): List<Msg> {
        if (limit <= 0) return emptyList()
        var all: List<Msg> = emptyList()
        synchronized(lock) {
            val k = idIndex[key] ?: key
            all = people[k]?.msgs?.toList() ?: emptyList()
        }
        if (all.isEmpty()) return emptyList()
        val grams = gramsOf(query.lowercase(Locale.US))
        val now = System.currentTimeMillis()
        val scored = all.mapIndexed { idx, m ->
            val t = m.text.lowercase(Locale.US)
            var score = 0.0
            grams.forEach { g -> if (t.contains(g)) score += 2.0 }
            val days = ((now - m.at) / 86_400_000L).coerceAtLeast(0)
            score += (7.0 - days).coerceIn(0.0, 7.0) / 7.0
            // 越新越容易被挑到，但不至于把旧的关键对话全挤掉
            score += idx.toDouble() / all.size
            m to score
        }
        val picked = LinkedHashSet<Msg>()
        scored.sortedByDescending { it.second }.take(limit).forEach { picked.add(it.first) }
        // 再补最近三条，保证「刚刚说过的」一定在
        all.takeLast(3).forEach { picked.add(it) }
        return picked.sortedBy { it.at }
    }

    fun recentMsgs(key: String, limit: Int): List<Msg> {
        if (limit <= 0) return emptyList()
        return synchronized(lock) {
            val k = idIndex[key] ?: key
            people[k]?.msgs?.takeLast(limit)?.toList() ?: emptyList()
        }
    }

    /** 中文按相邻两字切，英文按词切，用来算字面重合 */
    private fun gramsOf(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val cjk = Regex("[\\u4e00-\\u9fa5]")
        val chars = text.filter { cjk.matches(it.toString()) }
        for (i in 0 until chars.length - 1) out.add(chars.substring(i, i + 2))
        text.split(Regex("[^a-zA-Z0-9]+")).forEach {
            if (it.length >= 2) out.add(it)
        }
        return out.distinct().take(24)
    }

    /** 从一句话里抠出名字/喜好，规则保守；真正的地基是 rememberMsg 的全量流水 */
    fun mineFromMessage(key: String, text: String) {
        if (!Prefs.current.memory.autoLearn) return
        if (key.isBlank()) return
        val t = text.trim().take(200)
        val rules = listOf(
            Regex("我叫\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,10})"),
            Regex("叫我\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,10})"),
            Regex("我的名字(?:是|叫)\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,10})"),
            Regex("我是\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,10})(?=[，,。！？\\s]|$)")
        )
        rules.forEach { re ->
            re.find(t)?.groupValues?.getOrNull(1)?.let { alias(key, it) }
        }
        listOf(
            Regex("我喜欢\\s*([^，,。！？]{1,20})"),
            Regex("我不喜欢\\s*([^，,。！？]{1,20})"),
            Regex("我最喜欢\\s*([^，,。！？]{1,20})"),
            Regex("我讨厌\\s*([^，,。！？]{1,20})")
        ).forEach { re ->
            re.find(t)?.groupValues?.getOrNull(1)?.let { learn(key, it, "auto") }
        }
    }

    /* ---------------- 容量控制 ---------------- */

    private fun trimLocked(p: Person) {
        val maxNotes = Prefs.current.memory.maxNotes.coerceIn(10, 400)
        while (p.notes.size > maxNotes) p.notes.removeAt(0)
        while (p.msgs.size > MAX_MSGS_PER_PERSON) p.msgs.removeAt(0)
    }

    /** 总人数 / 总流水超上限时，从最久没说话的人开始裁 */
    private fun trimPeopleLocked(except: String) {
        if (people.size > MAX_PEOPLE) {
            people.values.sortedBy { it.lastSeen }.firstOrNull { it.openId != except }?.let {
                idIndex.remove(it.openId)
                it.aliasIds.forEach { a -> idIndex.remove(a) }
                people.remove(it.openId)
            }
        }
        var total = totalMsgsLocked()
        if (total <= MAX_MSGS_TOTAL) return
        val ordered = people.values.filter { it.openId != except }.sortedBy { it.lastSeen }
        for (p in ordered) {
            if (total <= MAX_MSGS_TOTAL) break
            val drop = (p.msgs.size - p.msgs.size / 2).coerceAtLeast(1)
            repeat(minOf(drop, p.msgs.size)) { p.msgs.removeAt(0) }
            total = totalMsgsLocked()
        }
    }

    /* ---------------- 文本 ---------------- */

    fun summaryLine(): String {
        val (who, notes) = stats()
        val msgs = totalMsgs()
        return "认识 $who 人｜$notes 条记忆｜$msgs 条对话"
    }

    fun dateText(at: Long): String =
        runCatching { SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(at)) }.getOrDefault("-")
}
