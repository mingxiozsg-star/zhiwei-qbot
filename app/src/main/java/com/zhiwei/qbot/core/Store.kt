package com.zhiwei.qbot.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * 媒体类型：决定上传时用哪个 file_type。
 * 平台定义：1 图片 / 2 视频 / 3 语音(silk) / 4 文件。
 */
enum class MediaKind(val fileType: Int, val label: String) {
    IMAGE(1, "图片"),
    VIDEO(2, "视频"),
    VOICE(3, "语音"),
    PLAIN(4, "文件");

    companion object {
        private val images = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
        private val videos = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm", "flv", "3gp", "ts")
        private val voices = setOf("silk", "slk")

        fun of(name: String): MediaKind {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext in images -> IMAGE
                ext in videos -> VIDEO
                ext in voices -> VOICE
                else -> PLAIN
            }
        }

        fun fromFileType(type: Int): MediaKind = when (type) {
            1 -> IMAGE
            2 -> VIDEO
            3 -> VOICE
            else -> PLAIN
        }
    }
}

/**
 * 本地素材：表情包文件、临时音频/图片。全部放应用私有目录，不污染相册。
 */
object Store {

    private var ctx: Context? = null

    fun init(context: Context) {
        val app = context.applicationContext
        ctx = app
        File(app.filesDir, "stickers").mkdirs()
        File(app.cacheDir, "media").mkdirs()
    }

    fun stickerDir(): File {
        val dir = File(ctx?.filesDir ?: File("/data/local/tmp"), "stickers")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun mediaDir(): File {
        val dir = File(ctx?.cacheDir ?: File("/data/local/tmp"), "media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun importSticker(uri: Uri, name: String): Result<Sticker> = runCatching {
        val resolver = ctx?.contentResolver ?: error("应用未初始化")
        val ext = uri.lastPathSegment?.substringAfterLast('.', "jpg")?.takeIf { it.length in 2..5 } ?: "jpg"
        val target = File(stickerDir(), "st_${UUID.randomUUID().toString().take(8)}.$ext")
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "读不到图片内容" }
            target.outputStream().use { out -> input.copyTo(out) }
        }
        require(target.length() > 0) { "图片是空的" }
        Sticker(name = name.ifBlank { "表情" }, file = target.absolutePath)
    }

    fun deleteSticker(sticker: Sticker) {
        runCatching { File(sticker.file).delete() }
    }

    fun readBytes(path: String, limitMb: Int = 16): ByteArray {
        val f = File(path)
        require(f.exists()) { "文件不存在：$path" }
        require(f.isFile) { "这不是文件：$path" }
        require(f.canRead()) { "读不到文件（可能缺「所有文件访问」权限）：$path" }
        require(f.length() <= limitMb * 1024L * 1024L) {
            "文件 ${sizeText(f.length())} 超过上限 ${limitMb}MB"
        }
        return f.readBytes()
    }

    fun sizeText(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1fMB", bytes / 1048576.0)
    }

    fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "silk" -> "audio/silk"
        "mp4" -> "video/mp4"
        "txt", "log", "json", "md" -> "text/plain"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }

    /** 会动的格式（gif/webp）不要重新编码，否则动画就没了 */
    fun animatedImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("gif", "webp")

    /** 图片超过 1280px 就先压一遍，减少上传体积与失败率 */
    fun compressImage(bytes: ByteArray, maxSide: Int = 1280): ByteArray {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= maxSide && h <= maxSide && bytes.size < 900_000) return@runCatching bytes

            var sample = 1
            while (w / sample > maxSide * 2 || h / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@runCatching bytes
            val scaled = if (bmp.width > maxSide || bmp.height > maxSide) {
                val ratio = minOf(maxSide.toFloat() / bmp.width, maxSide.toFloat() / bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
            } else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            out.toByteArray()
        }.getOrDefault(bytes)
    }

    fun writeTemp(name: String, bytes: ByteArray): File {
        val f = File(mediaDir(), name)
        f.writeBytes(bytes)
        return f
    }
}