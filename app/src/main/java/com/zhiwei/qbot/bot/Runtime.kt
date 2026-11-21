package com.zhiwei.qbot.bot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局运行状态，UI 直接订阅，不需要在界面里再维护一套连接逻辑。
 */
object Runtime {

    enum class State { STOPPED, CONNECTING, ONLINE, RECONNECTING, ERROR }

    data class Snapshot(
        val state: State = State.STOPPED,
        val detail: String = "未启动",
        val since: Long = 0L,
        val received: Int = 0,
        val sent: Int = 0,
        val fails: Int = 0,
        val gateway: String = "",
        val botName: String = ""
    )

    private val _snap = MutableStateFlow(Snapshot())
    val snap: StateFlow<Snapshot> = _snap

    @Synchronized
    fun setState(state: State, detail: String) {
        val old = _snap.value
        _snap.value = old.copy(
            state = state,
            detail = detail,
            since = if (state == old.state) old.since else System.currentTimeMillis()
        )
    }

    @Synchronized
    fun connected(gateway: String) {
        val old = _snap.value
        _snap.value = old.copy(
            state = State.ONLINE,
            detail = "已连接",
            since = System.currentTimeMillis(),
            gateway = gateway
        )
    }

    @Synchronized
    fun botName(name: String) {
        _snap.value = _snap.value.copy(botName = name)
    }

    @Synchronized
    fun received() {
        _snap.value = _snap.value.copy(received = _snap.value.received + 1)
    }

    @Synchronized
    fun sent(count: Int = 1) {
        _snap.value = _snap.value.copy(sent = _snap.value.sent + count)
    }

    @Synchronized
    fun fail() {
        _snap.value = _snap.value.copy(fails = _snap.value.fails + 1)
    }

    @Synchronized
    fun stop() {
        _snap.value = Snapshot()
    }
}