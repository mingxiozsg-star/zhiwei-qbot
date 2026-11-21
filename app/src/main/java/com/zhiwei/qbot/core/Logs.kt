package com.zhiwei.qbot.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 环形日志：只留最近 300 条，界面上能直接看到连接/发送/报错。
 */
object Logs {

    data class Line(val at: Long, val level: Char, val text: String) {
        fun timeText(): String =
            SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(at))
    }

    private const val MAX = 300
    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines

    @Synchronized
    private fun add(level: Char, text: String) {
        val line = Line(System.currentTimeMillis(), level, text)
        val next = ArrayList<Line>(_lines.value.size + 1)
        next.addAll(_lines.value)
        next.add(line)
        if (next.size > MAX) {
            _lines.value = next.subList(next.size - MAX, next.size)
        } else {
            _lines.value = next
        }
    }

    fun i(text: String) = add('I', text)
    fun w(text: String) = add('W', text)
    fun e(text: String) = add('E', text)

    fun clear() {
        _lines.value = emptyList()
    }

    fun dump(): String {
        val sb = StringBuilder()
        _lines.value.forEach { line ->
            sb.append(line.timeText()).append(' ').append(line.level).append(' ').append(line.text).append('\n')
        }
        return sb.toString()
    }
}