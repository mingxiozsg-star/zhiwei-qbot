package com.zhiwei.qbot.util

import com.zhiwei.qbot.core.Store
import java.io.File
import java.util.Locale

/**
 * 本地文件速查：给聊天里的 q / w / e / s 这类快捷指令用。
 *
 * 设计原则：
 * 1. 只看存储卡，不越权翻应用私有目录；
 * 2. 结果一定带完整路径，方便用户下一条直接 `e 路径` 或 `s 路径`；
 * 3. 条数有上限，聊天窗口不是文件管理器，刷屏没意义。
 */
object FileBrowser {

    /** 常用根目录：找东西先看这些，比从 / 开始递归快得多 */
    val COMMON_ROOTS = listOf(
        "/sdcard/Download",
        "/sdcard/Documents",
        "/sdcard/Pictures",
        "/sdcard/DCIM",
        "/sdcard/Movies",
        "/sdcard/Music",
        "/sdcard/Android/data",
        "/sdcard"
    )

    private const val MAX_LIST = 40

    /** 列目录：文件在前带大小，目录在后带斜杠 */
    fun listDirectory(path: String, limit: Int = MAX_LIST): String {
        val target = normalize(path)
        val dir = File(target)
        if (!dir.exists()) return "没这个路径：$target"
        if (dir.isFile) {
            return "这是一个文件：\n${describe(dir)}"
        }
        val all = dir.listFiles() ?: return "读不到这个目录（权限？）：$target"
        val dirs = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = all.filter { it.isFile }.sortedBy { it.name.lowercase() }
        if (dirs.isEmpty() && files.isEmpty()) return "目录是空的：$target"

        val sb = StringBuilder()
        sb.append("📂 ").append(target).append('\n')
        sb.append("共 ").append(dirs.size).append(" 个子目录 · ").append(files.size).append(" 个文件\n")

        var shown = 0
        for (d in dirs) {
            if (shown >= limit) break
            sb.append("📁 ").append(d.name).append("/\n")
            shown++
        }
        for (f in files) {
            if (shown >= limit) break
            sb.append(icon(f)).append(' ').append(f.name)
                .append("（").append(Store.sizeText(f.length())).append("）\n")
            shown++
        }
        val remain = dirs.size + files.size - shown
        if (remain > 0) sb.append("……还有 ").append(remain).append(" 项没列，看子目录请发：q $target/子目录名")
        return sb.toString().trim()
    }

    /**
     * 扫安装包：默认扫常用下载目录，不做全盘递归（太慢）。
     * 按修改时间倒序，新的排前面。
     */
    fun findApks(keyword: String = "", limit: Int = 20): List<File> {
        val out = ArrayList<File>()
        val seen = HashSet<String>()
        for (root in listOf("/sdcard/Download", "/sdcard/Documents", "/sdcard/Android/data", "/sdcard")) {
            scan(root, depth = 2, maxDepth = 4) { f ->
                if (out.size >= limit * 3) return@scan false
                val low = f.name.lowercase()
                if (!low.endsWith(".apk")) return@scan true
                if (keyword.isNotBlank() && !low.contains(keyword.lowercase())) return@scan true
                if (seen.add(f.absolutePath)) out.add(f)
                true
            }
            if (out.size >= limit) break
        }
        return out.sortedByDescending { it.lastModified() }.take(limit)
    }

    /** 按文件名关键词找任意文件 */
    fun search(keyword: String, base: String = "/sdcard", limit: Int = 20): List<File> {
        val key = keyword.trim()
        if (key.isBlank()) return emptyList()
        val out = ArrayList<File>()
        scan(normalize(base), depth = 0, maxDepth = 6) { f ->
            if (out.size >= limit) return@scan false
            if (f.isFile && f.name.contains(key, true)) out.add(f)
            true
        }
        return out.sortedByDescending { it.lastModified() }.take(limit)
    }

    /** 在目录里按文件名找（含模糊匹配），目录 + 文件名这种用法最常见 */
    fun findIn(dir: String, name: String, limit: Int = 10): List<File> {
        val d = File(normalize(dir))
        if (!d.exists() || !d.isDirectory) return emptyList()
        val key = name.trim()
        val list = d.listFiles()?.filter { it.isFile }.orEmpty()
        val exact = list.filter { it.name.equals(key, true) }
        if (exact.isNotEmpty()) return exact
        val partial = list.filter { it.name.contains(key, true) }
        if (partial.isNotEmpty()) return partial.take(limit)
        // 再往下找一层，很多人文件是放在子目录里的
        val deep = ArrayList<File>()
        for (sub in d.listFiles()?.filter { it.isDirectory }.orEmpty()) {
            if (deep.size >= limit) break
            sub.listFiles()?.filter { it.isFile && it.name.contains(key, true) }?.let { deep.addAll(it) }
        }
        return deep.take(limit)
    }

    /** 压缩包扫描 */
    fun findArchives(limit: Int = 20): List<File> {
        val exts = setOf("zip", "rar", "7z", "tar", "gz", "xz", "zst", "lz4", "jar", "apk", "tgz")
        val out = ArrayList<File>()
        val seen = HashSet<String>()
        for (root in listOf("/sdcard/Download", "/sdcard/Documents")) {
            scan(root, depth = 0, maxDepth = 3) { f ->
                if (out.size >= limit) return@scan false
                val ext = f.name.substringAfterLast('.', "").lowercase()
                if (f.isFile && ext in exts && seen.add(f.absolutePath)) out.add(f)
                true
            }
        }
        return out.sortedByDescending { it.lastModified() }.take(limit)
    }

    fun describe(f: File): String =
        "📄 ${f.name}\n${f.absolutePath}\n${Store.sizeText(f.length())} · ${timeText(f.lastModified())}"

    fun resolve(path: String): String = normalize(path)

    /**
     * 紧凑清单：给 AI 塞进上下文用。
     * 比 listDirectory 更短，只列名字，让模型能准确说出「有哪些文件」而不是瞎猜。
     */
    fun compactList(path: String, limit: Int = 30): String {
        val target = normalize(path)
        val dir = File(target)
        if (!dir.exists()) return "路径不存在：$target"
        if (dir.isFile) return "这是文件：${dir.name}（${Store.sizeText(dir.length())}）"
        val all = runCatching { dir.listFiles() }.getOrNull() ?: return "读不到目录：$target"
        if (all.isEmpty()) return "目录是空的：$target"
        val dirs = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = all.filter { it.isFile }.sortedBy { it.name.lowercase() }
        val sb = StringBuilder("$target（${dirs.size} 个文件夹 / ${files.size} 个文件）\n")
        dirs.take(limit).forEach { sb.append("[目录] ").append(it.name).append('\n') }
        files.take(limit - dirs.size.coerceAtMost(limit)).forEach {
            sb.append("[文件] ").append(it.name).append("  ").append(Store.sizeText(it.length())).append('\n')
        }
        val shown = dirs.size.coerceAtMost(limit) + files.size.coerceAtMost((limit - dirs.size).coerceAtLeast(0))
        val remain = all.size - shown
        if (remain > 0) sb.append("……还有 $remain 项\n")
        return sb.toString().trim()
    }

    fun sizeOf(f: File): String = Store.sizeText(f.length())

    fun timeText(at: Long): String =
        runCatching {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
        }.getOrDefault("-")

    /* ---------------- 内部 ---------------- */

    /**
     * 路径归一化。
     *
     * 之前「内部存储」会直接被拼成 `/sdcard/内部存储`，于是永远「找不到」。
     * 现在把用户口语里的说法先映射成真实路径，再决定要不要补前缀。
     */
    private fun normalize(path: String): String {
        val p = path.trim().trim('"', '\'', '`').trimEnd('/')
        if (p.isEmpty()) return "/sdcard"

        // 1. 纯别名：直接翻译成根目录
        ROOT_ALIASES[p.lowercase(Locale.US)]?.let { return it }
        ROOT_ALIASES[p]?.let { return it }

        // 2. 以别名开头 + 子路径：「内部存储/Download」
        for ((alias, real) in ROOT_ALIASES) {
            if (p.startsWith(alias, true) && p.length > alias.length) {
                val rest = p.substring(alias.length).trimStart('/', '\\', ' ')
                return (real.trimEnd('/') + "/" + rest).trimEnd('/')
            }
        }

        // 3. 已经有明确前缀的原样返回
        if (p.startsWith("/sdcard") || p.startsWith("/storage") || p.startsWith("/data")
            || p.startsWith("/system") || p.startsWith("/mnt")
        ) return p

        // 4. 其它绝对路径尊重用户输入
        if (p.startsWith("/")) return p

        // 5. 相对路径挂到内部存储下
        return "/sdcard/$p"
    }

    /** 用户口语 → 真实路径 */
    private val ROOT_ALIASES: Map<String, String> = mapOf(
        "内部存储" to "/sdcard",
        "内部储存" to "/sdcard",
        "内部空间" to "/sdcard",
        "手机存储" to "/sdcard",
        "手机内存" to "/sdcard",
        "根目录" to "/sdcard",
        "存储卡" to "/sdcard",
        "sd卡" to "/sdcard",
        "sdcard" to "/sdcard",
        "下载" to "/sdcard/Download",
        "下载目录" to "/sdcard/Download",
        "文档" to "/sdcard/Documents",
        "图片" to "/sdcard/Pictures",
        "相册" to "/sdcard/DCIM",
        "照片" to "/sdcard/DCIM",
        "相机" to "/sdcard/DCIM/Camera",
        "音乐" to "/sdcard/Music",
        "视频" to "/sdcard/Movies",
        "电影" to "/sdcard/Movies",
        "桌面" to "/sdcard/Desktop"
    )

    /** 给 AI 看的能力说明里，把别名也列出来，模型才知道「内部存储」能用 */
    fun aliasHint(): String = ROOT_ALIASES.keys.take(12).joinToString("、")

    private fun icon(f: File): String = when (f.name.substringAfterLast('.', "").lowercase()) {
        "apk" -> "📦"
        "zip", "rar", "7z", "tar", "gz", "xz", "zst", "lz4" -> "🗜"
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic" -> "🖼"
        "mp4", "mov", "mkv", "avi", "webm", "3gp" -> "🎬"
        "mp3", "wav", "flac", "aac", "m4a", "ogg" -> "🎵"
        "pdf" -> "📕"
        "doc", "docx" -> "📘"
        "xls", "xlsx", "csv" -> "📗"
        "ppt", "pptx" -> "📙"
        "txt", "md", "log", "json", "xml" -> "📝"
        "silk" -> "🎙"
        else -> "📄"
    }

    /** 有深度上限的遍历，返回 false 表示调用方要提前收工 */
    private fun scan(dir: String, depth: Int, maxDepth: Int, hit: (File) -> Boolean) {
        if (depth > maxDepth) return
        val d = File(dir)
        if (!d.exists() || !d.isDirectory) return
        val children = runCatching { d.listFiles() }.getOrNull() ?: return
        for (c in children) {
            if (c.name.startsWith(".")) continue
            if (!hit(c)) return
            if (c.isDirectory) scan(c.absolutePath, depth + 1, maxDepth, hit)
        }
    }
}