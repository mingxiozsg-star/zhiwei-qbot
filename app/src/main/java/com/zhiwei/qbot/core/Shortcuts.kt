package com.zhiwei.qbot.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 一条可自定义的快捷指令。
 *
 * 设计要点：
 * - [trigger] 是用户实际敲的词，大小写不敏感；
 * - [action] 是动作类型，决定了接到这条指令后要干什么；
 * - [arg] 是动作参数（比如目录、文件名、要发给 AI 的话）。
 *
 * 之所以把动作做成枚举而不是「直接存一条待执行的文本」，
 * 是因为有些动作需要触发时再取参数（比如 `q` 不写目录就用默认目录），
 * 存成固定文本会导致灵活性归零。
 */
data class Shortcut(
    /** 触发词，例如 q / w / 目录 / 备份 */
    val trigger: String = "",
    /** 一句话说明，会拼进 AI 的提示词里，让模型知道这条指令能干什么 */
    val desc: String = "",
    /** 动作 */
    val action: String = ACTION_LIST_DIR,
    /** 动作参数：目录、关键词、文本等 */
    val arg: String = "",
    /** 是否启用 */
    val enabled: Boolean = true,
    /** 内置指令不允许删除（删了会让 AI 与菜单失效），可以禁用 */
    val builtin: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("trigger", trigger)
        .put("desc", desc)
        .put("action", action)
        .put("arg", arg)
        .put("enabled", enabled)
        .put("builtin", builtin)

    companion object {
        const val ACTION_LIST_DIR = "list_dir"
        const val ACTION_FIND_APK = "find_apk"
        const val ACTION_SEND_APK = "send_apk"
        const val ACTION_SEND_FILE = "send_file"
        const val ACTION_SEND_DIR = "send_dir"
        const val ACTION_ASK_AI = "ask_ai"
        const val ACTION_BACKUP = "backup"
        const val ACTION_STATUS = "status"

        val ACTIONS = listOf(
            ACTION_LIST_DIR to "列目录",
            ACTION_FIND_APK to "扫安装包",
            ACTION_SEND_APK to "发安装包",
            ACTION_SEND_FILE to "发文件",
            ACTION_SEND_DIR to "发整个目录",
            ACTION_ASK_AI to "交给 AI",
            ACTION_BACKUP to "备份数据",
            ACTION_STATUS to "查状态"
        )

        fun actionLabel(action: String): String =
            ACTIONS.firstOrNull { it.first == action }?.second ?: "交给 AI"

        fun from(o: JSONObject) = Shortcut(
            trigger = o.str("trigger"),
            desc = o.str("desc"),
            action = o.str("action", ACTION_ASK_AI),
            arg = o.str("arg"),
            enabled = o.flag("enabled", true),
            builtin = o.flag("builtin")
        )

        /**
         * 出厂默认指令。
         *
         * 之前 q/w/e/s 只写在代码里，模型完全不知道它们存在，
         * 所以你说「用 q 列一下目录」它会当成普通聊天。
         * 现在它们同时进入提示词，AI 能看到并且会照着用。
         */
        fun defaults(): List<Shortcut> = listOf(
            Shortcut("q", "列出目录里的文件（默认内部存储）", ACTION_LIST_DIR, "/sdcard", true, true),
            Shortcut("w", "扫描安装包 apk", ACTION_FIND_APK, "", true, true),
            Shortcut("e", "发送安装包", ACTION_SEND_APK, "", true, true),
            Shortcut("s", "发送文件或压缩包", ACTION_SEND_FILE, "", true, true),
            Shortcut("目录", "列出内部存储的文件", ACTION_LIST_DIR, "/sdcard", true, true),
            Shortcut("备份", "把配置和记忆打包备份", ACTION_BACKUP, "", true, true),
            Shortcut("状态", "查看运行状态", ACTION_STATUS, "", true, true)
        )
    }
}

/**
 * 快捷指令表。增删改都走这里，配置里只存一份 JSON 数组。
 */
object Shortcuts {

    private const val MAX_SHORTCUTS = 60

    /** 从配置里拿最终的可用列表；配置为空时回落到出厂默认 */
    fun of(cfg: Config): List<Shortcut> =
        cfg.shortcuts.ifEmpty { Shortcut.defaults() }

    fun enabled(cfg: Config): List<Shortcut> = of(cfg).filter { it.enabled && it.trigger.isNotBlank() }

    /** 按触发词找一条（大小写不敏感，支持中文） */
    fun match(cfg: Config, word: String): Shortcut? {
        val key = word.trim().lowercase(Locale.US)
        if (key.isEmpty()) return null
        return enabled(cfg).firstOrNull { it.trigger.trim().lowercase(Locale.US) == key }
    }

    /** 新增或覆盖同触发词的一条 */
    fun upsert(cfg: Config, item: Shortcut): Config {
        val list = of(cfg).toMutableList()
        val t = item.trigger.trim()
        if (t.isBlank()) return cfg
        val idx = list.indexOfFirst { it.trigger.equals(t, true) }
        if (idx >= 0) list[idx] = item.copy(trigger = t, builtin = list[idx].builtin) else list.add(item.copy(trigger = t))
        while (list.size > MAX_SHORTCUTS) list.removeAt(list.indexOfLast { !it.builtin })
        return cfg.copy(shortcuts = list)
    }

    /** 取消（删除）一条；内置的只禁用不删，避免功能丢失 */
    fun remove(cfg: Config, trigger: String): Config {
        val list = of(cfg).toMutableList()
        val idx = list.indexOfFirst { it.trigger.equals(trigger, true) }
        if (idx < 0) return cfg
        if (list[idx].builtin) {
            list[idx] = list[idx].copy(enabled = false)
        } else {
            list.removeAt(idx)
        }
        return cfg.copy(shortcuts = list)
    }

    /** 重新启用内置指令 */
    fun enable(cfg: Config, trigger: String): Config {
        val list = of(cfg).toMutableList()
        val idx = list.indexOfFirst { it.trigger.equals(trigger, true) }
        if (idx < 0) return cfg
        list[idx] = list[idx].copy(enabled = true)
        return cfg.copy(shortcuts = list)
    }

    /** 恢复出厂指令表（保留用户自己加的） */
    fun restoreBuiltins(cfg: Config): Config {
        val list = of(cfg).toMutableList()
        Shortcut.defaults().forEach { d ->
            val idx = list.indexOfFirst { it.trigger.equals(d.trigger, true) }
            if (idx < 0) list.add(d) else list[idx] = list[idx].copy(enabled = true, desc = d.desc, action = d.action)
        }
        return cfg.copy(shortcuts = list)
    }

    /** 给提示词用的一段说明，让模型知道有哪些指令、分别干什么 */
    fun promptBlock(cfg: Config): String {
        val list = enabled(cfg)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        list.forEach { s ->
            sb.append("· ").append(s.trigger)
            if (s.desc.isNotBlank()) sb.append(" — ").append(s.desc)
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun toJson(cfg: Config): JSONArray {
        val a = JSONArray()
        of(cfg).forEach { a.put(it.toJson()) }
        return a
    }
}
