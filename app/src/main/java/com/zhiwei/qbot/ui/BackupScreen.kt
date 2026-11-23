package com.zhiwei.qbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.core.Backup
import com.zhiwei.qbot.core.Memory
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 整体备份与恢复。
 *
 * 备份内容：全部设置（含凭证/人设/模型分配/快捷指令/菜单）、完整记忆库、
 * 所有表情原图，打成一个 zip 丢到 /sdcard/ZhiweiQBot，换机或重装时解压式恢复。
 */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by Prefs.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var backups by remember { mutableStateOf(Backup.listBackups()) }
    var pendingRestore by remember { mutableStateOf<File?>(null) }
    var manualPath by remember { mutableStateOf("") }

    Page(title = "备份与恢复", subtitle = "配置 + 记忆 + 表情，一包带走", onBack = onBack) {
        CardBox {
            Text("现在就要备份的数据", fontSize = 15.sp, color = TextMain)
            Text(
                "${Memory.stats().first} 个人 · ${Memory.totalMsgs()} 条对话 · ${cfg.stickers.size} 张表情",
                fontSize = 12.sp,
                color = TextDim,
                modifier = Modifier.padding(top = 4.dp)
            )
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Primary(if (busy) "处理中…" else "立即备份", enabled = !busy) {
                    if (busy) return@Primary
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Backup.backup() }
                        busy = false
                        backups = Backup.listBackups()
                        message = if (r.isSuccess) {
                            val f = r.getOrThrow()
                            "已生成：\n${f.absolutePath}\n${Store.sizeText(f.length())}" to "备份完成"
                        } else {
                            (r.exceptionOrNull()?.message ?: "失败") to "备份失败"
                        }
                    }
                }
                Ghost("刷新列表") { backups = Backup.listBackups() }
            }
            Hint("默认存到 /sdcard/ZhiweiQBot，文件名带时间戳，不会互相覆盖。")
        }

        SectionTitle("已有备份 ${backups.size} 个")
        CardBox {
            if (backups.isEmpty()) {
                Hint("还没有备份文件。点上面的「立即备份」生成第一个。")
            } else {
                backups.forEachIndexed { index, f ->
                    if (index > 0) Line()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                            Text(f.name, fontSize = 13.sp, color = TextMain)
                            Text(
                                Store.sizeText(f.length()),
                                fontSize = 11.sp,
                                color = TextDim,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        TinyBtn("恢复", color = Amber) { pendingRestore = f }
                    }
                }
            }
        }

        SectionTitle("从任意路径恢复")
        CardBox {
            Field("备份文件路径", manualPath, { manualPath = it }, hint = "/sdcard/Download/xxx.zip")
            Ghost("用这个文件恢复") {
                val f = File(manualPath.trim())
                if (manualPath.isBlank()) {
                    tip(ctx, "先填路径")
                } else if (!f.exists()) {
                    tip(ctx, "文件不存在")
                } else {
                    pendingRestore = f
                }
            }
            Hint("可以从下载目录、网盘同步目录里挑一个 zip，不限于默认备份目录。")
        }

        SectionTitle("说明")
        CardBox {
            Hint("· 恢复会覆盖当前配置与记忆库，表情图片按同名覆盖，不做合并。")
            Hint("· 备份里含 AppSecret 与模型 Key，发给别人前先想清楚。")
            Hint("· 恢复完建议重启一次机器人，让新配置与快捷指令重新生效。")
        }
    }

    message?.let { (text, title) ->
        SimpleDialog(
            title = title,
            text = text,
            confirm = "知道了",
            onConfirm = { message = null },
            onDismiss = { message = null }
        )
    }

    pendingRestore?.let { file ->
        SimpleDialog(
            title = "恢复这个备份？",
            text = "文件：${file.name}\n\n当前的设置和记忆会被整体替换，这一步没法撤销。",
            confirm = "覆盖恢复",
            dismiss = "算了",
            onConfirm = {
                pendingRestore = null
                if (busy) return@SimpleDialog
                busy = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Backup.restore(file) }
                    busy = false
                    message = if (r.isSuccess) {
                        (r.getOrNull() ?: "已恢复") to "恢复完成"
                    } else {
                        (r.exceptionOrNull()?.message ?: "失败") to "恢复失败"
                    }
                }
            },
            onDismiss = { pendingRestore = null }
        )
    }
}