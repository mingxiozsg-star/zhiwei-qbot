package com.zhiwei.qbot.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 整体备份与恢复。
 *
 * 备份内容：
 * - config.json：全部设置（含凭证、人设、模型分配、快捷指令、菜单）
 * - memory.json：记忆库（每个人的资料与全部原始对话）
 * - stickers/：表情包原文件
 *
 * 统一打成一个 zip，方便直接存网盘或发给别人。
 * 恢复时导入这个 zip 即可，不需要逐项重填。
 */
object Backup {

    private const val ENTRY_CONFIG = "config.json"
    private const val ENTRY_MEMORY = "memory.json"
    private const val ENTRY_META = "backup.json"
    private const val STICKER_PREFIX = "stickers/"
    private const val VERSION = 1

    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /** 默认备份目录：内部存储根下的 ZhiweiQBot 文件夹，用户容易找到 */
    fun defaultDir(): File {
        val dir = File("/sdcard/ZhiweiQBot")
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return dir
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * 执行备份，返回生成的 zip 文件。
     * [dir] 传空则用默认目录。
     */
    fun backup(dir: String = ""): Result<File> = runCatching {
        val target = if (dir.isBlank()) defaultDir() else File(dir).also { it.mkdirs() }
        require(target.isDirectory || target.mkdirs()) { "备份目录建不出来：${target.absolutePath}" }

        val out = File(target, "zhiwei-qbot-backup-${stamp()}.zip")

        // 先把内存里待写的配置与记忆落盘，否则备份到的是旧数据
        Prefs.saveNow()
        Memory.saveNow()

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            // 元信息
            val meta = JSONObject()
                .put("version", VERSION)
                .put("app", "zhiwei-qbot")
                .put("at", System.currentTimeMillis())
                .put("people", Memory.stats().first)
                .put("msgs", Memory.totalMsgs())
            zip.putNextEntry(ZipEntry(ENTRY_META))
            zip.write(meta.toString(2).toByteArray())
            zip.closeEntry()

            // 配置
            zip.putNextEntry(ZipEntry(ENTRY_CONFIG))
            zip.write(Prefs.current.toJson().toString(2).toByteArray())
            zip.closeEntry()

            // 记忆
            zip.putNextEntry(ZipEntry(ENTRY_MEMORY))
            zip.write(Memory.exportJson().toByteArray())
            zip.closeEntry()

            // 表情包
            val stickerDir = Store.stickerDir()
            stickerDir.listFiles()?.filter { it.isFile }?.forEach { f ->
                zip.putNextEntry(ZipEntry(STICKER_PREFIX + f.name))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        Logs.i("备份完成：${out.absolutePath}（${Store.sizeText(out.length())}）")
        out
    }

    /**
     * 从 zip 恢复。
     *
     * 注意：恢复会覆盖当前配置与记忆。
     */
    fun restore(zipFile: File): Result<String> = runCatching {
        require(zipFile.exists()) { "备份文件不存在：${zipFile.absolutePath}" }
        require(zipFile.isFile) { "这不是文件：${zipFile.absolutePath}" }

        var configText: String? = null
        var memoryText: String? = null
        var stickerCount = 0
        val stickerDir = Store.stickerDir().apply { mkdirs() }

        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            var entry: ZipEntry? = zin.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    name == ENTRY_CONFIG -> configText = zin.readBytes().toString(Charsets.UTF_8)
                    name == ENTRY_MEMORY -> memoryText = zin.readBytes().toString(Charsets.UTF_8)
                    name.startsWith(STICKER_PREFIX) && !entry.isDirectory -> {
                        val fileName = name.removePrefix(STICKER_PREFIX)
                        if (fileName.isNotBlank() && !fileName.contains('/')) {
                            val dest = File(stickerDir, fileName)
                            dest.outputStream().use { zin.copyTo(it) }
                            // 把表情路径指向恢复后的位置
                            runCatching {
                                Prefs.update { cfg ->
                                    cfg.copy(stickers = cfg.stickers.map { st ->
                                        if (st.file.endsWith(fileName)) st.copy(file = dest.absolutePath) else st
                                    })
                                }
                            }
                            stickerCount++
                        }
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }

        require(configText != null || memoryText != null) {
            "这个 zip 里没有 config.json 或 memory.json，不像是知微机器人的备份"
        }

        configText?.let { text ->
            val obj = JSONObject(text)
            Prefs.importJson(obj.toString()).getOrThrow()
        }
        memoryText?.let { text ->
            Memory.importJson(text).getOrThrow()
        }

        val people = Memory.stats().first
        val msgs = Memory.totalMsgs()
        Logs.i("恢复完成：$people 人 / $msgs 条对话 / $stickerCount 个表情")
        "恢复完成：$people 个人、$msgs 条对话、$stickerCount 个表情包。建议重启一次机器人让配置生效。"
    }

    /** 列出已有备份，新→旧 */
    fun listBackups(): List<File> {
        val dir = defaultDir()
        return dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("zhiwei-qbot-backup") && it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }
}