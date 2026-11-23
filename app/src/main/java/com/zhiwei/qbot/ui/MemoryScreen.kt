package com.zhiwei.qbot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.core.Memory
import com.zhiwei.qbot.core.Prefs

/**
 * 记忆库：按人存的长期记忆，群聊私聊共用一份。
 *
 * 每个人下面有两层：
 * 1. 提炼记忆（你记住的结论，可删）；
 * 2. 原始对话（他说过的每一句，也可以单条删）。
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    val (peopleCount, noteCount) = remember(tick) { Memory.stats() }
    val msgCount = remember(tick) { Memory.totalMsgs() }
    val people = remember(tick) { Memory.allPeople() }

    var expandedId by remember { mutableStateOf<String?>(null) }
    var showMsgs by remember { mutableStateOf(false) }

    Page(
        title = "记忆库",
        subtitle = "按人记，换群也认得",
        onBack = onBack
    ) {
        val cfg = Prefs.current
        CardBox {
            ToggleRow(
                "记忆总开关",
                "关掉后不再记新东西，也不会把旧记忆塞进提示词",
                cfg.memory.enabled
            ) { v -> Prefs.update { it.copy(memory = it.memory.copy(enabled = v)) } }
            Line()
            ToggleRow("记住每一条消息", "他说过的每句话都存下来，对话一两次也有上下文", cfg.memory.rememberAll) { v ->
                Prefs.update { it.copy(memory = it.memory.copy(rememberAll = v)) }
            }
            Line()
            ToggleRow("自动记名字和喜好", "聊天里出现「我叫 / 我喜欢」这类句式时自动落库", cfg.memory.autoLearn) { v ->
                Prefs.update { it.copy(memory = it.memory.copy(autoLearn = v)) }
            }
            Line()
            NumField(
                "回放原话条数（每次塞几条相关旧对话给模型）",
                cfg.memory.recallMsgs,
                onChange = { v: Int -> Prefs.update { it.copy(memory = it.memory.copy(recallMsgs = v)) } },
                hint = "默认 8 条"
            )
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = CardBg,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "已记住 $peopleCount 人 · $noteCount 条记忆 · $msgCount 条对话",
                    fontSize = 13.sp,
                    color = TextDim
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "按人存：群聊和私聊共用同一份资料。同一个人在别的群里说话，也认得出是你。",
                    fontSize = 11.sp,
                    color = TextDim
                )
                Spacer(modifier = Modifier.height(4.dp))
                TinyBtn("刷新") { tick++ }
            }
        }

        if (people.isEmpty()) {
            Column(
                modifier = Modifier.padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有记忆", fontSize = 15.sp, color = TextMain, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                Text("在聊天里说一句就有了，不用特意教。", fontSize = 12.sp, color = TextDim)
            }
        } else {
            people.forEach { p ->
                val open = expandedId == p.openId
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                expandedId = if (open) null else p.openId
                                showMsgs = false
                            }
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        p.displayName(),
                                        fontSize = 15.sp,
                                        color = TextMain,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "${p.notes.size} 条记忆 · ${p.msgs.size} 条对话 · 上次 ${dateShort(p.lastSeen)}",
                                        fontSize = 11.sp,
                                        color = TextDim
                                    )
                                }
                                Text(if (open) "收起 ∧" else "展开 ∨", fontSize = 12.sp, color = Cyan)
                            }
                            if (open) {
                                Spacer(modifier = Modifier.height(10.dp))
                                if (p.notes.isEmpty()) {
                                    Text("（没有提炼记忆，但原话都留着）", fontSize = 12.sp, color = TextDim)
                                } else {
                                    p.notes.forEachIndexed { i, n ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 3.dp)
                                        ) {
                                            Text(
                                                "${i + 1}. ${n.text}",
                                                fontSize = 13.sp,
                                                color = TextMain,
                                                modifier = Modifier.weight(1f)
                                            )
                                            TinyBtn("删", color = Pink) {
                                                Memory.deleteNote(p.openId, i)
                                                tick++
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TinyBtn(if (showMsgs) "收起原话" else "看原话（${p.msgs.size}）") {
                                        showMsgs = !showMsgs
                                    }
                                    TinyBtn("删掉这个人", color = Pink) {
                                        Memory.forget(p.openId)
                                        expandedId = null
                                        tick++
                                    }
                                }

                                if (showMsgs) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    if (p.msgs.isEmpty()) {
                                        Text("（还没有原话，等他说一句就会记）", fontSize = 12.sp, color = TextDim)
                                    } else {
                                        p.msgs.takeLast(40).forEachIndexed { i, m ->
                                            val idx = p.msgs.size - minOf(40, p.msgs.size) + i
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 2.dp)
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        (if (m.fromUser) "他：" else "我：") + m.text,
                                                        fontSize = 12.sp,
                                                        color = if (m.fromUser) TextMain else TextDim
                                                    )
                                                    Text(
                                                        dateShort(m.at) + (if (m.scene.isNotBlank()) " · ${m.scene}" else ""),
                                                        fontSize = 10.sp,
                                                        color = TextDim
                                                    )
                                                }
                                                TinyBtn("删", color = Pink) {
                                                    Memory.deleteMsg(p.openId, idx)
                                                    tick++
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (people.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            TinyBtn("清空全部记忆", color = Pink) {
                Memory.forgetAll()
                expandedId = null
                tick++
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "提示：身份按平台下发的 union_openid 归并，同一个人在不同群里的 id 会自动认成同一人；" +
                "聊天里发 /记忆 看自己的，/忘记 删自己的。",
            fontSize = 11.sp,
            color = TextDim,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

private fun dateShort(at: Long): String =
    runCatching {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(at))
    }.getOrDefault("-")