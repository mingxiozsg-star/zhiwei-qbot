package com.zhiwei.qbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.ui.AboutScreen
import com.zhiwei.qbot.ui.AiScreen
import com.zhiwei.qbot.ui.BgBottom
import com.zhiwei.qbot.ui.BottomBar
import com.zhiwei.qbot.ui.ConnectScreen
import com.zhiwei.qbot.ui.FilesScreen
import com.zhiwei.qbot.ui.GuideScreen
import com.zhiwei.qbot.ui.HomeScreen
import com.zhiwei.qbot.ui.LogsScreen
import com.zhiwei.qbot.ui.MenusScreen
import com.zhiwei.qbot.ui.PermsScreen
import com.zhiwei.qbot.ui.PersonaScreen
import com.zhiwei.qbot.ui.QbotTheme
import com.zhiwei.qbot.ui.SendScreen
import com.zhiwei.qbot.ui.SettingsScreen
import com.zhiwei.qbot.ui.SimpleDialog
import com.zhiwei.qbot.ui.StickersScreen
import com.zhiwei.qbot.ui.TalkScreen
import com.zhiwei.qbot.ui.VoiceScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContent {
            QbotTheme {
                AppRoot()
            }
        }
    }

    /** 退到后台时把防抖中的配置落盘，避免进程被回收时丢最后几笔设置 */
    override fun onStop() {
        runCatching { Prefs.saveNow() }
        super.onStop()
    }
}

/** 二级页（带返回箭头，底栏隐藏） */
private val SUB_PAGES = setOf(
    "connect", "persona", "send", "ai", "voice", "stickers", "files",
    "menus", "perms", "logs", "guide", "about"
)

@Composable
private fun AppRoot() {
    var route by rememberSaveable { mutableStateOf("home") }
    var backTo by rememberSaveable { mutableStateOf("home") }
    var askGuide by remember { mutableStateOf(!Prefs.current.onboarded) }

    val open: (String) -> Unit = { target ->
        if (target in SUB_PAGES && route !in SUB_PAGES) backTo = route
        route = target
    }

    BackHandler(enabled = route != "home") {
        route = if (route in SUB_PAGES) backTo else "home"
    }

    Column(modifier = Modifier.fillMaxSize().background(BgBottom)) {
        Box(modifier = Modifier.weight(1f)) {
            when (route) {
                "home" -> HomeScreen(onOpen = open, onTalk = { route = "talk" })
                "talk" -> TalkScreen()
                "settings" -> SettingsScreen(onOpen = open, onBack = { route = "home" })
                "connect" -> ConnectScreen(onBack = { route = backTo })
                "persona" -> PersonaScreen(onBack = { route = backTo })
                "send" -> SendScreen(onBack = { route = backTo })
                "ai" -> AiScreen(onBack = { route = backTo })
                "voice" -> VoiceScreen(onBack = { route = backTo })
                "stickers" -> StickersScreen(onBack = { route = backTo })
                "files" -> FilesScreen(onBack = { route = backTo })
                "menus" -> MenusScreen(onBack = { route = backTo })
                "perms" -> PermsScreen(onBack = { route = backTo })
                "logs" -> LogsScreen(onBack = { route = backTo })
                "guide" -> GuideScreen(onBack = { route = backTo }, onOpen = open)
                "about" -> AboutScreen(onBack = { route = backTo })
                else -> HomeScreen(onOpen = open, onTalk = { route = "talk" })
            }
        }
        if (route == "home" || route == "talk" || route == "settings") {
            BottomBar(current = route) { route = it }
        }
    }

    if (askGuide) {
        SimpleDialog(
            title = "先看一遍新手引导？",
            text = "从建机器人到让它开口，一共五步。看完就能直接配，不看你也不会卡住，但会慢一点。",
            confirm = "看引导",
            dismiss = "跳过",
            onConfirm = {
                askGuide = false
                Prefs.setOnboarded(true)
                open("guide")
            },
            onDismiss = {
                askGuide = false
                Prefs.setOnboarded(true)
            }
        )
    }
}