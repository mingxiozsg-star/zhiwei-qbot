package com.zhiwei.qbot.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 一条长期记忆 */
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

/** 一个人：群聊和私聊共用同一份资料，所以在群里也认得私聊聊熟的人 */
data class Person(
    val openId: String,
    var name: String = "",
    val names: MutableList<String> = ArrayList(),
    var firstSeen: Long = System.currentTimeMillis(),
    var lastSeen: Long = System.currentTimeMillis(),
    var msgCount: Int = 0,
    val notes: MutableList<Note> = ArrayList()
) {
    fun displayName(): String = name.ifBlank { names.lastOrNull().orEmpty().ifBlank { "路人" } }

    /** 深拷贝一份给 UI/提示词用，避免外面改到内存里的那份 */
    fun deepCopy(): Person = Person(
        openId = openId,
        name = name,
        names = ArrayList(names),
        firstSeen = firstSeen,
        lastSeen = lastSeen,
        msgCount = msgCount,
        notes = ArrayList(notes)
    )

    fun toJson(): JSONObject {
        val na = JSONArray()
        names.forEach { na.put(it) }
        val ta = JSONArray()
        notes.forEach { ta.put(it.toJson()) }
        return JSONObject()
            .put("openId", openId)
            .put("name", name)
            .put("names", na)
            .put("firstSeen", firstSeen)
            .put("lastSeen", lastSeen)
            .put("msgCount", msgCount)
            .put("notes", ta)
    }

    companion object {
        fun from(o: JSONObject): Person {
            val p = Person(openId = o.str("openId"))
            p.name = o.str("name")
            val na = o.arr("names")
            for (i in 0 until na.length()) na.optString(i).takeIf { it.isNotBlank() }?.let { p.names.add(it) }
            p.firstSeen = o.optLong("firstSeen", System.currentTimeMillis())
            p.lastSeen = o.optLong("lastSeen", System.currentTimeMillis())
            p.msgCount = o.num("msgCount")
            val ta = o.arr("notes")
            for (i in 0 until ta.length()) ta.optJSONObject(i)?.let { p.notes.add(Note.from(it)) }
            return p
        }
    }
}

/**
 * 长期记忆库：按「人」存，群聊和私聊共用同一份，所以同一个人在哪儿都认得。
 *
 * 三层结构：
 * 1. 短期上下文（Brain 里的滑动窗口）——只管最近几句；
 * 2. 人的资料（这里）——昵称、别名、第一次见、说过多少句；
 * 3. 记忆条目（这里）——喜欢什么、在哪个城市、答应过什么，按关键词 + 新旧排序取最相关的几条塞回提示词。
 *
 * 落盘到 filesDir/memory.json，写盘有节流，退出/崩溃前强制落盘。
 */
object Memory {

    private const val FILE_NAME = "memory.json"
    private const val MAX_PEOPLE = 300
    private const val MAX_NAMES = 6
    private const val MAX_NOTE_CHARS = 160

    private var ctx: Context? = null
    private val people = LinkedHashMap<String, Person>()
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
                if (p.openId.isNotBlank()) people[p.openId] = p
            }
            Logs.i("记忆库已载入：${people.size} 人")
        }.onFailure { Logs.e("记忆库读取失败：${it.message}") }
    }

    fun saveNow() {
        val f = file() ?: return
        synchronized(lock) {
            runCatching {
                val arr = JSONArray()
                people.values.forEach { arr.put(it.toJson()) }
                val obj = JSONObject().put("version", 1).put("people", arr)
                f.writeText(obj.toString())
                lastSaveAt = System.currentTimeMillis()
                pending = false
            }.onFailure { Logs.e("记忆库写入失败：${it.message}") }
        }
    }

    /** 写盘节流：频繁聊天时最多 15 秒落一次，退出前再强制一次 */
    private fun scheduleSave() {
        synchronized(lock) {
            pending = true
            if (System.currentTimeMillis() - lastSaveAt < 15_000) return
        }
        Thread {
            runCatching { saveNow() }
        }.apply { isDaemon = true }.start()
    }

    /* ---------------- 记录 ---------------- */

    fun touch(openId: String, name: String): Person? {
        if (openId.isBlank()) return null
        val person = synchronized(lock) {
            val now = System.currentTimeMillis()
            val p = people.getOrPut(openId) { Person(openId = openId, firstSeen = now) }
            p.lastSeen = now
            p.msgCount += 1
            if (name.isNotBlank()) {
                val clean = name.trim().take(24)
                if (p.name.isBlank()) p.name = clean
                if (p.names.none { it.equals(clean, true) }) {
                    p.names.add(clean)
                    while (p.names.size > MAX_NAMES) p.names.removeAt(0)
                }
            }
            if (people.size > MAX_PEOPLE) {
                val oldest = people.values.minByOrNull { it.lastSeen }
                if (oldest != null && oldest.openId != openId) people.remove(oldest.openId)
            }
            p.deepCopy()
        }
        scheduleSave()
        return person
    }

    fun alias(openId: String, alias: String) {
        val a = alias.trim().take(24)
        if (a.isBlank()) return
        synchronized(lock) {
            val p = people[openId] ?: return
            if (p.names.none { it.equals(a, true) }) {
                p.names.add(a)
                while (p.names.size > MAX_NAMES) p.names.removeAt(0)
            }
            if (p.name.isBlank()) p.name = a
        }
        scheduleSave()
    }

    /** 记一条。返回 false 表示重复/无意义，不重复写 */
    fun learn(openId: String, text: String, source: String = "auto"): Boolean {
        val clean = text.trim()
            .replace(Regex("\\s+"), " ")
            .trim('。', '，', ',', '.', ' ')
            .take(MAX_NOTE_CHARS)
        if (clean.length < 2) return false
        if (openId.isBlank()) return false
        val maxNotes = Prefs.current.memory.maxNotes.coerceIn(10, 300)
        synchronized(lock) {
            val p = people.getOrPut(openId) { Person(openId = openId) }
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

    fun forget(openId: String) {
        synchronized(lock) { people.remove(openId) }
        scheduleSave()
    }

    fun forgetAll() {
        synchronized(lock) { people.clear() }
        saveNow()
    }

    fun deleteNote(openId: String, index: Int): Boolean {
        synchronized(lock) {
            val p = people[openId] ?: return false
            if (index !in p.notes.indices) return false
            p.notes.removeAt(index)
        }
        scheduleSave()
        return true
    }

    /* ---------------- 读取 ---------------- */

    fun person(openId: String): Person? = synchronized(lock) { people[openId]?.deepCopy() }

    fun allPeople(): List<Person> = synchronized(lock) {
        people.values.sortedByDescending { it.lastSeen }.map { it.deepCopy() }
    }

    fun stats(): Pair<Int, Int> = synchronized(lock) {
        people.size to people.values.sumOf { it.notes.size }
    }

    /**
     * 取和这句话最相关的记忆：字面重合（中文按双字）优先，其次看新旧。
     * 永远带上最近两条，避免「明明记过却想不起来」。
     */
    fun recall(openId: String, query: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        var notes: List<Note> = emptyList()
        synchronized(lock) { notes = people[openId]?.notes?.toList() ?: emptyList() }
        if (notes.isEmpty()) return emptyList()

        val q = query.lowercase(Locale.US)
        val grams = gramsOf(q)
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

    /** 从一句话里抠出名字/喜好，规则保守，宁可漏也别乱记 */
    fun mineFromMessage(openId: String, text: String) {
        if (!Prefs.current.memory.autoLearn) return
        if (openId.isBlank()) return
        val t = text.trim().take(120)
        val rules = listOf(
            Regex("我叫\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,10})"),
            Regex("叫我\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,10})"),
            Regex("我是\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,10})(?=[，,。！？\\s]|$)"),
            Regex("我的名字(?:是|叫)\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{1,10})")
        )
        rules.forEach { re ->
            re.find(t)?.groupValues?.getOrNull(1)?.let { alias(openId, it) }
        }
        listOf(
            Regex("我喜欢\\s*([^，,。！？]{1,20})"),
            Regex("我不喜欢\\s*([^，,。！？]{1,20})"),
            Regex("我最喜欢\\s*([^，,。！？]{1,20})"),
            Regex("我讨厌\\s*([^，,。！？]{1,20})")
        ).forEach { re ->
            re.find(t)?.groupValues?.getOrNull(1)?.let { learn(openId, it, "auto") }
        }
    }

    fun summaryLine(): String {
        val (who, notes) = stats()
        return "认识 $who 人｜$notes 条记忆"
    }
}