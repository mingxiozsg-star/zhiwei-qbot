package com.zhiwei.qbot

import android.app.Application
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Store

class QbotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Store.init(this)
    }
}