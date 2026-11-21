package com.zhiwei.qbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.bot.Brain
import com.zhiwei.qbot.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TalkMsg(val mine: Boolean, val text: String)

/**
 * 本地测试对话：只在本机走一遍「人设 + 模型 + 分句」，不经过 QQ。
 * 调人设、换模型之后先在这里试一句，比拉个群试快得多。
 */
@Composable
fun TalkScreen() {
    val cfg by Prefs.state.collectAsState()
    val msgs = remember { mutableStateListOf<TalkMsg>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    Page(
        title = "本地测试",
        subtitle = if (cfg.ai.apiKey.isBlank()) "还没填模型 Key，先去设置" else "模型：${cfg.ai.model}",
        scroll = false,
        trailing = {
            TinyBtn("清空") {
                msgs.clear()
                Brain.clearTestHistory()
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (msgs.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("在吗", "讲个冷笑话", "骂我一句", "你怎么看加班").forEach { s ->
                        TinyBtn(s) { input = s }
                    }
                }
                Text(
                    "随便说一句，看看它会怎么回。这里的分句结果就是群里实际发出去的样子。",
                    fontSize = 11.sp,
                    color = TextDim,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
            ) {
                items(msgs) { m -> Bubble(m) }
                if (busy) {
                    item { Bubble(TalkMsg(false, "……")) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("说点什么", fontSize = 13.sp, color = TextDim) },
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.weight(1f)
                )
                Primary(if (busy) "等" else "发", enabled = !busy, onClick = {
                    val text = input.trim()
                    if (text.isEmpty() || busy) return@Primary
                    input = ""
                    msgs.add(TalkMsg(true, text))
                    busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Brain.testReply(text) }
                        busy = false
                        if (r.isSuccess) {
                            val parts = r.getOrNull().orEmpty().filter { it.isNotBlank() }
                            parts.forEach { part ->
                                delay(300)
                                msgs.add(TalkMsg(false, part))
                            }
                            if (parts.isEmpty()) msgs.add(TalkMsg(false, "（模型没说话）"))
                        } else {
                            msgs.add(TalkMsg(false, "出错：${r.exceptionOrNull()?.message}"))
                            tip(ctx, "调用失败，日志里有原话")
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun Bubble(m: TalkMsg) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (m.mine) Pink else CardBg2,
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (m.mine) 14.dp else 4.dp,
                        bottomEnd = if (m.mine) 4.dp else 14.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                m.text,
                fontSize = 14.sp,
                color = if (m.mine) Color.Black else TextMain,
                fontWeight = if (m.mine) FontWeight.Normal else FontWeight.Normal
            )
        }
    }
}