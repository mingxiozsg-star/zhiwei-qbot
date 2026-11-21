package com.zhiwei.qbot.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.core.Logs

/** 运行日志：连接、收发、报错都在这里，出问题先看它。 */
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val lines by Logs.lines.collectAsState()
    val ctx = LocalContext.current

    Page(
        title = "运行日志",
        subtitle = "最近 ${lines.size} 条，最多留 300 条",
        onBack = onBack,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TinyBtn("复制") {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("qbot-log", Logs.dump()))
                    tip(ctx, "已复制 ${lines.size} 条")
                }
                TinyBtn("分享") {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "知微QQ机器人 运行日志")
                        putExtra(Intent.EXTRA_TEXT, Logs.dump())
                    }
                    runCatching {
                        ctx.startActivity(Intent.createChooser(intent, "导出日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    ) {
        CardBox {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TinyBtn("清空日志", color = Red) {
                    Logs.clear()
                    tip(ctx, "已清空")
                }
                TinyBtn("刷新") { /* StateFlow 自动刷新，这里只是给个手感 */ }
            }
            Hint("日志只在内存里，关掉应用就没了；要留证据就点上面复制或分享。")
        }

        if (lines.isEmpty()) {
            CardBox {
                Text("还没有日志。启动机器人之后这里会滚起来。", fontSize = 12.sp, color = TextDim)
            }
        } else {
            CardBox {
                lines.asReversed().take(200).forEach { line ->
                    val color = when (line.level) {
                        'E' -> Red
                        'W' -> Amber
                        else -> TextDim
                    }
                    Row(modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(line.timeText(), fontSize = 10.sp, color = TextDim)
                        Text(
                            " ${line.level} ",
                            fontSize = 10.sp,
                            color = color
                        )
                        Text(line.text, fontSize = 11.sp, color = TextMain)
                    }
                }
            }
        }
    }
}