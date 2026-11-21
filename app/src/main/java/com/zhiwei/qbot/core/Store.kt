package com.zhiwei.qbot.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

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
        require(f.length() <= limitMb * 1024L * 1024L) { "文件超过 ${limitMb}MB，先别发" }
        return f.readBytes()
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
        else -> "application/octet-stream"
    }

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