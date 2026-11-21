package com.zhiwei.qbot.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast

/** 页面里随手用的轻提示 */
fun tip(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** 从 Compose 的 Context 里翻出 Activity，权限跳转必须要它 */
fun Context.findActivity(): Activity? {
    var cur: Context? = this
    while (cur is ContextWrapper) {
        if (cur is Activity) return cur
        cur = cur.baseContext
    }
    return null
}
