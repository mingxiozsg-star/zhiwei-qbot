package com.zhiwei.qbot.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * 配置只存在内存里，落盘做 500ms 防抖，避免输入框每敲一个字都写一次 SharedPreferences。
 */
object Prefs {

    private const val SP_NAME = "zhiwei_qbot"
    private const val KEY_CONFIG = "config"

    private var sp: SharedPreferences? = null
    private val main = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null

    private val _state = MutableStateFlow(Config())
    val state: StateFlow<Config> = _state

    val current: Config get() = _state.value

    fun init(context: Context) {
        if (sp != null) return
        val p = context.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        sp = p
        val raw = p.getString(KEY_CONFIG, null) ?: return
        runCatching { _state.value = Config.from(JSONObject(raw)) }
            .onFailure { Logs.e("配置读取失败：${it.message}") }
    }

    fun update(transform: (Config) -> Config) {
        _state.value = transform(_state.value)
        scheduleSave()
    }

    fun setOnboarded(value: Boolean) = update { it.copy(onboarded = value) }

    private fun scheduleSave() {
        saveRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { saveNow() }
        saveRunnable = r
        main.postDelayed(r, 500)
    }

    fun saveNow() {
        val p = sp ?: return
        runCatching { p.edit().putString(KEY_CONFIG, _state.value.toJson().toString()).apply() }
            .onFailure { Logs.e("配置写入失败：${it.message}") }
    }

    fun exportJson(): String = _state.value.toJson().toString(2)

    fun importJson(text: String): Result<Unit> = runCatching {
        val obj = JSONObject(text)
        _state.value = Config.from(obj)
        saveNow()
    }

    fun resetAll() {
        _state.value = Config()
        saveNow()
    }
}