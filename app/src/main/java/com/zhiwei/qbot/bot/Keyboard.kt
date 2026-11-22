package com.zhiwei.qbot.bot

import com.zhiwei.qbot.core.MenuButton
import com.zhiwei.qbot.core.MenuDef
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 内嵌键盘（消息按钮）。
 * - 回调按钮 action.type=2，点击后由平台推 INTERACTION_CREATE，data 就是我们的指令；
 * - 链接按钮 action.type=0，data 是网址；
 * - 每行最多 5 个，最多 5 行，这里做硬截断防止接口报错。
 */
object Keyboard {

    fun build(menu: MenuDef): JSONObject {
        val rows = JSONArray()
        val perRow = menu.perRow.coerceIn(1, 5)
        val buttons = menu.buttons.take(25)
        buttons.chunked(perRow).take(5).forEachIndexed { rowIndex, rowButtons ->
            val row = JSONObject()
            val arr = JSONArray()
            rowButtons.forEachIndexed { i, b ->
                arr.put(button(b, rowIndex * 5 + i))
            }
            row.put("buttons", arr)
            rows.put(row)
        }
        val content = JSONObject().put("rows", rows)
        return JSONObject().put("content", content)
    }

    private fun button(btn: MenuButton, index: Int): JSONObject {
        val label = btn.label.ifBlank { "按钮" }.take(10)
        val render = JSONObject()
            .put("label", label)
            .put("visited_label", label)
            .put("style", if (btn.style == 1) 1 else 0)
        val permission = JSONObject().put("type", 2)
        // 0 跳转 / 1 回调（点一下就走 INTERACTION_CREATE）/ 2 指令（插到输入框）
        val action = if (btn.link) {
            JSONObject()
                .put("type", 0)
                .put("permission", permission)
                .put("data", btn.command)
                .put("unsupport_tips", "请升级 QQ 到最新版本")
        } else {
            JSONObject()
                .put("type", 1)
                .put("permission", permission)
                .put("data", btn.command)
                .put("unsupport_tips", "请升级 QQ 到最新版本")
        }
        return JSONObject()
            .put("id", "b${index}_${btn.command.hashCode()}")
            .put("render_data", render)
            .put("action", action)
    }
}