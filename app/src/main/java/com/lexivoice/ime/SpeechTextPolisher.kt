package com.lexivoice.ime

/**
 * 语音识别文本后处理：
 * 1. 清除 SenseVoice 标签与空白噪声；
 * 2. 去除重复语气词/重复短语（"嗯嗯嗯"、"那个那个"、"我今天我今天"）；
 * 3. 按语气与连接词补标点（问句用？，感叹用！，句末用。，长句补，）。
 *
 * 完全本地规则，不依赖云端或额外模型。
 */
object SpeechTextPolisher {

    private val tagRegex = Regex("<\\|[^|>]*\\|>")
    private val zeroWidthRegex = Regex("[\\u200B-\\u200D\\uFEFF]")
    private val spaceRegex = Regex("\\s+")
    private val leadingFillerRegex = Regex("^(嗯|呃|啊|哦|那个|这个|就是说|怎么说呢|你知道)[，,、.。!！？?\\s]*")
    private val connectivesBefore = Regex("(?<![，。！？、；])(但是|不过|所以|因为|而且|然后|另外|其实)(?=[\\u4e00-\\u9fa5])")
    private val questionWords = Regex("什么|怎么|为什么|哪里|哪儿|谁|多少|什么时候|是不是|有没有|能不能|可不可以|吗|呢")
    private val exclaimWords = Regex("太|真|好|哇|哎呀|居然|竟然")
    private val endPunct = setOf('。', '！', '？', '，', '、', '；', ';', '!', '?', ',')

    private val fillerChars = listOf("嗯", "呃", "啊", "呀", "哦", "唉", "诶")

    fun polish(raw: String): String {
        var s = tagRegex.replace(raw, "")
        s = zeroWidthRegex.replace(s, "")
        s = spaceRegex.replace(s, " ").trim()

        // 连续重复语气字："嗯嗯嗯" → "嗯"
        for (f in fillerChars) s = s.replace(Regex("$f{2,}"), f)

        // 连续重复短语："那个那个"、"我今天我今天" → 保留一次
        s = s.replace(Regex("([\\u4e00-\\u9fa5]{2,4})\\1+")) { it.groupValues[1] }

        // 开头堆叠的口头禅去掉
        var prev: String
        do {
            prev = s
            s = leadingFillerRegex.replace(s, "")
        } while (s != prev)

        if (s.isEmpty()) return ""

        // 连接词前补逗号
        s = connectivesBefore.replace(s, "，$1")

        // 长句且没有任何标点时，在语气词/中间位置补一个逗号
        if (s.length >= 16 && s.none { it in endPunct }) {
            val particle = listOf('啊', '呢', '吧', '嘛', '呀', '哦', '嗯')
            var split = -1
            for (i in 10 until minOf(s.length - 1, 18)) {
                if (s[i] in particle) split = i + 1
            }
            if (split < 0) split = minOf(12, s.length - 1)
            s = s.substring(0, split) + "，" + s.substring(split)
        }

        // 句末标点
        val last = s.last()
        when {
            last in endPunct && last !in setOf('，', ',', '、') -> { /* 已有句末标点 */ }
            last in setOf('，', ',', '、') -> s = s.dropLast(1) + sentenceEnd(s.dropLast(1))
            else -> s += sentenceEnd(s)
        }
        return s
    }

    private fun sentenceEnd(text: String): String {
        val tail = text.takeLast(8)
        if (questionWords.containsMatchIn(tail)) return "？"
        if (exclaimWords.containsMatchIn(text)) return "！"
        return "。"
    }
}
