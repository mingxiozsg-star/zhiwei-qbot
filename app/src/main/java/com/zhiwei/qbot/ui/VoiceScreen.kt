package com.zhiwei.qbot.ui

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhiwei.qbot.bot.Brain
import com.zhiwei.qbot.core.Prefs
import com.zhiwei.qbot.core.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TTS：接口、请求模板、响应取值、输出格式全部由用户自己配。
 * 这一页只做三件事：配好、能测、能试听。
 */
@Composable
fun VoiceScreen(onBack: () -> Unit) {
    val cfg by Prefs.state.collectAsState()
    val t = cfg.tts
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var testText by remember { mutableStateOf("你好，我是知微。") }
    var testInfo by remember { mutableStateOf("") }
    var testFile by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    Page(title = "语音 TTS", subtitle = "接口自己找，这里只管调通", onBack = onBack) {
        CardBox {
            ToggleRow("启用语音", "关掉后 AI 不会发 [语音] 标记", t.enabled) { v ->
                Prefs.update { it.copy(tts = it.tts.copy(enabled = v)) }
            }
            Line()
            Field("接口地址", t.url, { v -> Prefs.update { it.copy(tts = it.tts.copy(url = v.trim())) } }, hint = "https://your-tts.example.com/speech")
            Chips(listOf("POST" to "POST", "GET" to "GET"), t.method) { v ->
                Prefs.update { it.copy(tts = it.tts.copy(method = v)) }
            }
            Field("请求头 JSON", t.headers, { v -> Prefs.update { it.copy(tts = it.tts.copy(headers = v)) } }, singleLine = false, minLines = 2)
            Field("请求体模板", t.body, { v -> Prefs.update { it.copy(tts = it.tts.copy(body = v)) } }, singleLine = false, minLines = 3)
            Hint("{{text}} 换成要说的话（已做 JSON 转义），{{format}} 换成下面选的格式。GET 方式则把 {{text}} 写进地址里。")
        }

        SectionTitle("响应怎么取")
        CardBox {
            Chips(
                listOf("base64" to "base64", "url" to "音频链接", "raw" to "直接是音频"),
                t.responseType
            ) { v -> Prefs.update { it.copy(tts = it.tts.copy(responseType = v)) } }
            Field("取值路径", t.responsePath, { v -> Prefs.update { it.copy(tts = it.tts.copy(responsePath = v.trim())) } }, hint = "data.audio / result.0.url")
            Hint("路径用点分隔，数组写数字下标，比如 data.list.0.url。选 raw 时这里留空。")
            Line()
            Chips(listOf("silk" to "silk", "wav" to "wav", "mp3" to "mp3"), t.format) { v ->
                Prefs.update { it.copy(tts = it.tts.copy(format = v)) }
            }
            NumField("最长秒数", t.maxSeconds, { v ->
                Prefs.update { it.copy(tts = it.tts.copy(maxSeconds = v.coerceIn(5, 120))) }
            }, hint = "超长的文本会被截断")
        }

        SectionTitle("QQ 只认 silk")
        CardBox {
            Field("转码服务（可选）", t.converterUrl, { v ->
                Prefs.update { it.copy(tts = it.tts.copy(converterUrl = v.trim())) }
            }, hint = "POST {audio, from, to}，返回 silk")
            Hint("接口直接吐 silk 最省事。吐的是 wav/mp3 又没填转码服务，发送时会明确报错，不会假装发出去。")
        }

        SectionTitle("试一试")
        CardBox {
            Field("测试文本", testText, { v -> testText = v }, singleLine = false, minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Primary(if (busy) "合成中…" else "合成语音", enabled = !busy, onClick = {
                    if (busy) return@Primary
                    busy = true
                    testInfo = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { Brain.testTts(testText) }
                        busy = false
                        if (r.isSuccess) {
                            val bytes = r.getOrNull() ?: ByteArray(0)
                            val f = runCatching {
                                Store.writeTemp("tts_test.${Prefs.current.tts.format}", bytes)
                            }.getOrNull()
                            testFile = f
                            testInfo = "合成成功：${bytes.size / 1024} KB" +
                                if (Prefs.current.tts.format != "silk") "（可试听）" else "（silk 无法在本机试听）"
                        } else {
                            testInfo = "失败：${r.exceptionOrNull()?.message}"
                        }
                    }
                })
                val f = testFile
                if (f != null && Prefs.current.tts.format != "silk") {
                    Ghost("试听", onClick = {
                        runCatching {
                            player?.release()
                            player = MediaPlayer().apply {
                                setDataSource(f.absolutePath)
                                prepare()
                                start()
                            }
                        }.onFailure { tip(ctx, "放不出来：${it.message}") }
                    })
                }
            }
            if (testInfo.isNotBlank()) Hint(testInfo)
        }
    }
}
