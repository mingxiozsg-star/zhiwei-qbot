package com.zhiwei.qbot.core

/**
 * 版本与仓库信息的唯一来源：UI、User-Agent、关于页都从这里取，
 * 免得改版本时到处找字符串。
 */
object AppInfo {

    const val VERSION = "1.3.0"

    const val REPO = "https://github.com/mingxiozsg-star/zhiwei-qbot"
    const val OWNER = "https://github.com/mingxiozsg-star"

    const val USER_AGENT = "ZhiweiQBot/$VERSION (Android)"
}
