package com.zhiwei.qbot.bot

/**
 * 分句器：把人设回复切成像是人一条条打出来的短句。
 * 规则：先按句末标点切，太碎的合并，太长的按逗号再切，最后按上限合并。
 */
object Splitter {

    private val sentenceEnd = Regex("(?<=[。！？!?…~～\n])")
    private val softBreak = Regex("(?<=[，,；;、])")

    fun split(text: String, maxSegments: Int, maxChars: Int): List<String> {
        val clean = text
            .replace("\r", "")
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
        if (clean.isEmpty()) return emptyList()

        val merged = ArrayList<String>()
        sentenceEnd.split(clean).forEach { part ->
            var p = part.trim()
            if (p.isEmpty()) return@forEach
            if (p.length > maxChars) {
                softBreak.split(p).forEach { sub ->
                    val s = sub.trim()
                    if (s.isEmpty()) return@forEach
                    if (s.length <= maxChars) merged.add(s) else hardCut(s, maxChars).forEach { merged.add(it) }
                }
            } else {
                merged.add(p)
            }
        }

        // 太短的碎片并到前一句，避免“嗯。”“好。”这种刷屏
        val tidy = ArrayList<String>()
        merged.forEach { piece ->
            val last = tidy.lastOrNull()
            if (last != null && last.length < 6 && tidy.size > 0) {
                tidy[tidy.size - 1] = last + piece
            } else {
                tidy.add(piece)
            }
        }

        val limit = maxSegments.coerceIn(1, 8)
        if (tidy.size <= limit) return tidy

        // 超上限就把多余的并到最后一句话里
        val head = tidy.subList(0, limit - 1)
        val tail = tidy.subList(limit - 1, tidy.size).joinToString("")
        return head + tail
    }

    private fun hardCut(text: String, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val end = (i + maxChars).coerceAtMost(text.length)
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }
}