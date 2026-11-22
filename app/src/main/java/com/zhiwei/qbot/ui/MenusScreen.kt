package com.zhiwei.qbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.core.MenuButton
import com.zhiwei.qbot.core.MenuDef
import com.zhiwei.qbot.core.Prefs

/**
 * 菜单式聊天：就是截图里那种「消息底下挂一排按钮」。
 * 每个按钮要么开子菜单、要么跑一条 /指令、要么把文字当成用户发言丢给 AI。
 */
@Composable
fun MenusScreen(onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    var editing by remember { mutableStateOf<MenuDef?>(null) }
    var creating by remember { mutableStateOf(false) }

    Page(title = "菜单按钮", subtitle = "像图片那样，一点就回", onBack = onBack) {
        CardBox {
            Hint("按钮回调写法：")
            Hint("· menu:子菜单id —— 打开另一个菜单")
            Hint("· /状态、/语音 你好 —— 直接执行指令")
            Hint("· 其它文字 —— 当成用户说的一句话交给 AI")
            Hint("每行最多 5 个、最多 5 行，超出的会被平台截断，这里已经帮你挡了。")
            Hint("注意：按钮要挂在 markdown 消息上，且自定义按钮是开放平台的「内邀」能力、模板按钮要单独申请。没开通时本工具会自动退回纯文字菜单，功能照旧。")
        }

        cfg.menus.forEach { menu ->
            MenuPreviewCard(
                menu = menu,
                onEdit = { editing = menu },
                onDelete = {
                    Prefs.update { c -> c.copy(menus = c.menus.filter { it.id != menu.id }) }
                }
            )
        }

        Row(modifier = Modifier.padding(top = 12.dp)) {
            Primary("＋ 新建菜单") { creating = true }
        }

        SectionTitle("提示")
        CardBox {
            Hint("主菜单建议 id 保持 main：AI 回复带菜单、进群开场白都默认用它。")
            Hint("删除某个菜单后，指向它的按钮点下去只会回一句「没这个菜单」。")
        }
    }

    if (creating) {
        MenuEditor(
            initial = MenuDef(
                id = "menu${cfg.menus.size + 1}",
                title = "新菜单",
                triggers = "",
                text = "挑一个。",
                perRow = 3,
                buttons = listOf(MenuButton("按钮", "你好", 1))
            ),
            isNew = true,
            existing = cfg.menus,
            onDismiss = { creating = false },
            onSave = { menu ->
                Prefs.update { c -> c.copy(menus = c.menus + menu) }
                creating = false
            }
        )
    }

    editing?.let { menu ->
        MenuEditor(
            initial = menu,
            isNew = false,
            existing = cfg.menus,
            onDismiss = { editing = null },
            onSave = { updated ->
                Prefs.update { c -> c.copy(menus = c.menus.map { if (it.id == menu.id) updated else it }) }
                editing = null
            }
        )
    }
}

@Composable
private fun MenuPreviewCard(menu: MenuDef, onEdit: () -> Unit, onDelete: () -> Unit) {
    CardBox {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(menu.title, fontSize = 16.sp, color = TextMain, fontWeight = FontWeight.SemiBold)
                Text(
                    "id: ${menu.id} · ${menu.buttons.size} 个按钮" +
                        if (menu.triggers.isBlank()) "" else " · 触发：${menu.triggers}",
                    fontSize = 11.sp,
                    color = TextDim
                )
            }
            TinyBtn("编辑", onClick = onEdit)
        }
        if (menu.text.isNotBlank()) {
            Text(menu.text, fontSize = 12.sp, color = TextDim, modifier = Modifier.padding(top = 6.dp))
        }
        // 静态预览：按钮长什么样
        menu.buttons.take(25).chunked(menu.perRow.coerceIn(1, 5)).take(5).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { b ->
                    Text(
                        b.label,
                        fontSize = 11.sp,
                        color = if (b.style == 1) Color.Black else TextMain,
                        modifier = Modifier
                            .weight(1f)
                            .background(if (b.style == 1) Pink else CardBg2, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 7.dp)
                    )
                }
                repeat(menu.perRow.coerceIn(1, 5) - row.size) {
                    Text("", modifier = Modifier.weight(1f))
                }
            }
        }
        Row(modifier = Modifier.padding(top = 10.dp)) {
            TinyBtn("删除这个菜单", onClick = onDelete, color = Red)
        }
    }
}

@Composable
private fun MenuEditor(
    initial: MenuDef,
    isNew: Boolean,
    existing: List<MenuDef>,
    onDismiss: () -> Unit,
    onSave: (MenuDef) -> Unit
) {
    var id by remember(initial) { mutableStateOf(initial.id) }
    var title by remember(initial) { mutableStateOf(initial.title) }
    var triggers by remember(initial) { mutableStateOf(initial.triggers) }
    var text by remember(initial) { mutableStateOf(initial.text) }
    var perRow by remember(initial) { mutableStateOf(initial.perRow) }
    val buttons = remember(initial) { initial.buttons.toMutableStateList() }
    var error by remember(initial) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建菜单" else "编辑菜单", fontSize = 16.sp, color = TextMain) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Field("id", id, { id = it.trim() }, hint = "英文，子菜单靠它跳")
                Field("标题", title, { title = it })
                Field("触发词", triggers, { triggers = it }, hint = "逗号分隔：/菜单,菜单")
                Field("菜单上那句话", text, { text = it }, singleLine = false, minLines = 2)
                NumField("每行几个", perRow, { perRow = it.coerceIn(1, 5) })

                Text("按钮", fontSize = 13.sp, color = Pink, modifier = Modifier.padding(top = 10.dp))
                buttons.forEachIndexed { index, b ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text("第 ${index + 1} 个", fontSize = 12.sp, color = TextDim, modifier = Modifier.weight(1f))
                            TinyBtn("上移") {
                                if (index > 0) {
                                    val prev = buttons[index - 1]
                                    buttons[index - 1] = b
                                    buttons[index] = prev
                                }
                            }
                            Row(modifier = Modifier.padding(start = 6.dp)) {
                                TinyBtn("删除", color = Red) { buttons.removeAt(index) }
                            }
                        }
                        Field("文字", b.label, { v -> buttons[index] = b.copy(label = v) })
                        Field(
                            "点击后",
                            b.command,
                            { v -> buttons[index] = buttons[index].copy(command = v) },
                            hint = "menu:tools 或 /状态 或 随便一句话"
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Chips(
                                listOf("1" to "蓝色", "0" to "线框"),
                                b.style.toString()
                            ) { v -> buttons[index] = buttons[index].copy(style = v.toIntOrNull() ?: 1) }
                            Row(modifier = Modifier.padding(start = 8.dp)) {
                                TinyBtn(if (b.link) "链接：开" else "链接：关") {
                                    buttons[index] = buttons[index].copy(link = !b.link)
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    Ghost("＋ 加按钮") { buttons.add(MenuButton("按钮", "你好", 1)) }
                }

                if (error.isNotBlank()) Hint(error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val finalId = id.ifBlank { "menu" }.replace(" ", "")
                val conflict = existing.any { it.id == finalId && (isNew || it.id != initial.id) }
                if (conflict) {
                    error = "id「$finalId」已经用过了，换一个。"
                    return@TextButton
                }
                if (buttons.isEmpty()) {
                    error = "至少留一个按钮。"
                    return@TextButton
                }
                onSave(
                    MenuDef(
                        id = finalId,
                        title = title.ifBlank { "菜单" },
                        triggers = triggers.trim(),
                        text = text.ifBlank { "挑一个。" },
                        perRow = perRow.coerceIn(1, 5),
                        buttons = buttons.take(25)
                    )
                )
            }) { Text("保存", color = Pink) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextDim) }
        }
    )
}