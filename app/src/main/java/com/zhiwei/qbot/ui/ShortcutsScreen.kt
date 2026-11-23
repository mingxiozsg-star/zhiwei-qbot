package com.zhiwei.qbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Shortcut
import com.zhiwei.qbot.core.Shortcuts

/**
 * 快捷指令管理。
 *
 * 以前 q / w / e / s 只活在代码里：模型不知道、用户改不了、也删不掉。
 * 现在全部落在这张表上，这里能加、能改、能停用，改完立刻对聊天生效，
 * 同时会拼进系统提示词让模型也知道。
 */
@Composable
fun ShortcutsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by Prefs.state.collectAsState()
    val list = Shortcuts.of(cfg)
    var editing by remember { mutableStateOf<Shortcut?>(null) }
    var adding by remember { mutableStateOf(false) }

    Page(title = "快捷指令", subtitle = "聊天里直接敲词就能触发", onBack = onBack) {
        CardBox {
            Hint("指令表会同时喂给模型，所以 AI 知道有哪些、能干什么；用户敲这些词时由程序直接执行，不绕模型。")
            Hint("内置指令只能停用不能删，避免菜单/提示词里引用到不存在的词。")
        }

        SectionTitle("现有 ${list.size} 条")
        CardBox {
            if (list.isEmpty()) {
                Hint("还没有指令。点下面「新增一条」开始。")
            } else {
                list.forEachIndexed { index, item ->
                    if (index > 0) Line()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.trigger + (if (item.builtin) "  · 内置" else ""),
                                fontSize = 15.sp,
                                color = if (item.enabled) TextMain else TextDim
                            )
                            Text(
                                Shortcut.actionLabel(item.action) +
                                    (if (item.arg.isNotBlank()) " · ${item.arg}" else ""),
                                fontSize = 11.sp,
                                color = TextDim,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            if (item.desc.isNotBlank()) {
                                Text(
                                    item.desc,
                                    fontSize = 11.sp,
                                    color = TextDim,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Row(
                                modifier = Modifier.padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TinyBtn(if (item.enabled) "停用" else "启用") {
                                    Prefs.update { c ->
                                        if (item.enabled) Shortcuts.remove(c, item.trigger)
                                        else Shortcuts.enable(c, item.trigger)
                                    }
                                }
                                TinyBtn("编辑") { editing = item }
                                if (!item.builtin) {
                                    TinyBtn("删除", color = Red) {
                                        Prefs.update { c -> Shortcuts.remove(c, item.trigger) }
                                        tip(ctx, "已删除「${item.trigger}」")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionTitle("操作")
        CardBox {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Primary("新增一条") { adding = true }
                Ghost("恢复内置") {
                    Prefs.update { Shortcuts.restoreBuiltins(it) }
                    tip(ctx, "内置指令已恢复并启用")
                }
            }
        }

        SectionTitle("怎么用")
        CardBox {
            Hint("· 单字母：q 看目录 · w 扫安装包 · e 发安装包 · s 发文件")
            Hint("· 中文词：目录 / 备份 / 状态，直接发这两个字也行")
            Hint("· 带参数：q /sdcard/Download、s 资料.zip")
            Hint("· 新增一条时选「交给 AI」，这条词就会原样当成一句话丢给模型。")
        }
    }

    if (adding) {
        ShortcutEditor(
            initial = Shortcut(),
            isNew = true,
            onDismiss = { adding = false },
            onSave = { item ->
                Prefs.update { c -> Shortcuts.upsert(c, item) }
                adding = false
                tip(ctx, "已保存「${item.trigger}」")
            }
        )
    }

    editing?.let { target ->
        ShortcutEditor(
            initial = target,
            isNew = false,
            onDismiss = { editing = null },
            onSave = { item ->
                Prefs.update { c -> Shortcuts.upsert(c, item) }
                editing = null
                tip(ctx, "已更新「${item.trigger}」")
            }
        )
    }
}

@Composable
private fun ShortcutEditor(
    initial: Shortcut,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Shortcut) -> Unit
) {
    var trigger by remember { mutableStateOf(initial.trigger) }
    var desc by remember { mutableStateOf(initial.desc) }
    var action by remember { mutableStateOf(initial.action) }
    var arg by remember { mutableStateOf(initial.arg) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增快捷指令" else "编辑指令", fontSize = 16.sp, color = TextMain) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Field("触发词", trigger, { trigger = it }, hint = "例如 q、目录、发日报")
                Field("说明", desc, { desc = it }, hint = "给自己和 AI 看的一句话")
                Hint("动作")
                Column {
                    Shortcut.ACTIONS.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { (value, label) ->
                                Chips(
                                    options = listOf(value to label),
                                    selected = action,
                                    onSelect = { action = value }
                                )
                            }
                        }
                    }
                }
                Field("参数", arg, { arg = it }, hint = "目录 / 关键词，可留空")
                Hint("参数留空时，用户发指令后面带的词会作为参数；比如 q /sdcard/Download。")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (trigger.isBlank()) return@TextButton
                onSave(
                    initial.copy(
                        trigger = trigger.trim(),
                        desc = desc.trim(),
                        action = action,
                        arg = arg.trim(),
                        enabled = true
                    )
                )
            }) { Text("保存", color = Pink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = TextDim) } }
    )
}
