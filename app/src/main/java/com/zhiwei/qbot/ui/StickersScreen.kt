package com.zhiwei.qbot.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Sticker
import com.zhiwei.qbot.core.Store
import java.io.File

/**
 * 表情包自己加。文件名不动，名字和标签可以改——AI 就是靠名字和标签挑图的。
 */
@Composable
fun StickersScreen(onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val ctx = LocalContext.current
    var renameTarget by remember { mutableStateOf<Sticker?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingUri = uri
            pendingName = "表情${cfg.stickers.size + 1}"
        }
    }

    Page(title = "表情包", subtitle = "加几张，AI 会挑着发", onBack = onBack) {
        CardBox {
            Ghost("＋ 从相册选图") { picker.launch("image/*") }
            Hint("图片会复制到应用私有目录，之后把原图删掉也不影响。超过 1280px 会在发送前自动压缩。")
            Hint("AI 在提示词里只看得到名字，所以名字要像人话：无语、笑死、问号、真服了。")
        }

        if (cfg.stickers.isEmpty()) {
            SectionTitle("还没有表情")
            CardBox {
                Text("点上面加第一张。加完在这里能改名、看标签、删掉。", fontSize = 12.sp, color = TextDim)
            }
        } else {
            SectionTitle("已添加 ${cfg.stickers.size} 张")
            cfg.stickers.forEach { sticker ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val thumb = remember(sticker.file) { runCatching { decodeThumb(sticker.file) }.getOrNull() }
                    if (thumb != null) {
                        Image(
                            bitmap = thumb,
                            contentDescription = sticker.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(52.dp)
                                .height(52.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .width(52.dp)
                                .height(52.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("缺图", fontSize = 10.sp, color = Red) }
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp)
                    ) {
                        Text(sticker.name, fontSize = 15.sp, color = TextMain)
                        Text(
                            if (sticker.tags.isBlank()) "无标签" else "标签：${sticker.tags}",
                            fontSize = 11.sp,
                            color = TextDim
                        )
                    }
                    TinyBtn("改名") { renameTarget = sticker }
                    Row(modifier = Modifier.width(8.dp)) {}
                    TinyBtn("删除", color = Red) {
                        Store.deleteSticker(sticker)
                        Prefs.update { c -> c.copy(stickers = c.stickers.filter { it.file != sticker.file }) }
                        tip(ctx, "已删除")
                    }
                }
            }
        }

        SectionTitle("怎么让它发")
        CardBox {
            Hint("· 在群里发「/表情 名字」，或者点菜单里配好的按钮")
            Hint("· AI 想发时会在回复里写 [表情:名字]，你在人设里让它可以发表情就行")
            Hint("· 名字对不上会退回模糊匹配，还找不到就只发文字")
        }
    }

    // 导入时取名
    pendingUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingUri = null },
            title = { Text("给它起个名字", fontSize = 16.sp, color = TextMain) },
            text = {
                Column {
                    Field("名字", pendingName, { pendingName = it }, hint = "短一点，好记")
                    Hint("保存后可继续加标签")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (busy) return@TextButton
                    val target = uri
                    busy = true
                    val r = Store.importSticker(target, pendingName)
                    busy = false
                    r.onSuccess { st ->
                        Prefs.update { it.copy(stickers = it.stickers + st) }
                        tip(ctx, "已加入：${st.name}")
                    }.onFailure { tip(ctx, "导入失败：${it.message}") }
                    pendingUri = null
                }) { Text("加进去", color = Pink) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUri = null }) { Text("算了", color = TextDim) }
            }
        )
    }

    // 改名 / 改标签
    renameTarget?.let { target ->
        var name by remember(target) { mutableStateOf(target.name) }
        var tags by remember(target) { mutableStateOf(target.tags) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("编辑表情", fontSize = 16.sp, color = TextMain) },
            text = {
                Column {
                    Field("名字", name, { name = it })
                    Field("标签", tags, { tags = it }, hint = "逗号分隔：无语,嘲讽")
                    Hint("AI 会按名字和标签找图")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.update { c ->
                        c.copy(
                            stickers = c.stickers.map {
                                if (it.file == target.file) it.copy(name = name.trim().ifBlank { "表情" }, tags = tags.trim()) else it
                            }
                        )
                    }
                    renameTarget = null
                }) { Text("保存", color = Pink) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消", color = TextDim) }
            }
        )
    }
}

/** 列表里的缩略图，只解到 200px 左右，避免一堆大图占内存 */
private fun decodeThumb(path: String): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 240 || bounds.outHeight / sample > 240) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
}