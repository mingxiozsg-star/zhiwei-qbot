package com.zhiwei.qbot.ui

import androidx.compose.foundation.background
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
 * 看谁被记了、记了几条、删了不要的，或者整库清空。
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val (peopleCount, noteCount) = remember { Memory.stats() }
    val people = remember { Memory.allPeople() }

    var expandedOpenId by remember { mutableStateOf<String?>(null) }

    Page(
        title = "记忆库",
        subtitle = "按人记，群聊私聊都认得",
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
            ToggleRow("自动记名字和喜好", "聊天里出现「我叫 / 我喜欢」这类句式时自动落库", cfg.memory.autoLearn) { v ->
                Prefs.update { it.copy(memory = it.memory.copy(autoLearn = v)) }
            }
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = CardBg,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    "已记住 $peopleCount 人 · $noteCount 条记忆",
                    fontSize = 13.sp,
                    color = TextDim
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "按人存：群聊和私聊看到同一份资料，在群里也认得私聊聊熟的人。",
                    fontSize = 11.sp,
                    color = TextDim
                )
            }
        }

        if (people.isEmpty()) {
            Column(
                modifier = Modifier.padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有记忆", fontSize = 15.sp, color = TextMain, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(6.dp))
                Text("在聊天里多说几句，或对机器人说「/记住 你喜欢猫」。", fontSize = 12.sp, color = TextDim)
            }
        } else {
            people.forEach { p ->
                val open = expandedOpenId == p.openId
                Column(modifier = Modifier.padding(bottom = 6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardBg,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedOpenId = if (open) null else p.openId }
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
                                        "${p.notes.size} 条记忆 · 聊过 ${p.msgCount} 句 · 上次 ${dateShort(p.lastSeen)}",
                                        fontSize = 11.sp,
                                        color = TextDim
                                    )
                                }
                                Text(if (open) "收起 ∧" else "展开 ∨", fontSize = 12.sp, color = Cyan)
                            }
                            if (open) {
                                Spacer(modifier = Modifier.height(10.dp))
                                if (p.notes.isEmpty()) {
                                    Text("（没有单独的记忆条目）", fontSize = 12.sp, color = TextDim)
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
                                                expandedOpenId = null
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TinyBtn("删掉这个人") {
                                        Memory.forget(p.openId)
                                        expandedOpenId = null
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
                expandedOpenId = null
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            "提示：记忆开关在「设置 → 人设与回复」旁边，或直接在聊天里说 /忘记 删掉自己的记忆。",
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