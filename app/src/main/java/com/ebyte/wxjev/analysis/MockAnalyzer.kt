package com.ebyte.wxjev.analysis

/**
 * Mock 分析器 —— 无需 Jev API key，用关键词启发式模拟决策形态，产出结构与 JevClient 完全一致。
 * 维度名与 Questions.ALL 对齐（emotion/intent/needs/subtext/reply/danger），
 * 用于在没有 key / 没接通微信时验证「消息→分析→卡片显示」整条链路。
 */
class MockAnalyzer : Analyzer {

    override fun analyze(message: IncomingMessage, context: String, specs: List<QuestionSpec>): AnalysisResult {
        val text = message.analysisText()
        val out = LinkedHashMap<String, Decision>()
        for (s in specs) {
            when (s.name) {
                "emotion" -> out[s.name] = emotion(text)
                "intent" -> out[s.name] = intent(text)
                "needs" -> out[s.name] = needs(text)
                "subtext" -> out[s.name] = subtext(text)
                "reply" -> out[s.name] = reply(text)
                "danger" -> out[s.name] = danger(text)
            }
        }
        return AnalysisResult(message, decisions = out)
    }

    private fun has(t: String, vararg ks: String) = ks.any { t.contains(it) }

    private fun emotion(t: String): Decision {
        val probs = LinkedHashMap<String, Double>()
        if (has(t, "谢谢", "太好了", "喜欢", "哈哈", "开心", "赞", "👍", "😄")) probs["开心"] = 0.7
        if (has(t, "生气", "烦", "什么意思", "呵呵", "无语", "😡")) probs["生气"] = 0.65
        if (has(t, "算了", "随便", "无所谓", "嗯", "哦", "在忙")) probs["疲惫"] = 0.6
        if (has(t, "你是不是", "又忘", "忙人", "都不")) probs["委屈"] = 0.55
        if (has(t, "急", "怎么办", "担心", "怕")) probs["焦虑"] = 0.6
        if (has(t, "呀", "嘛", "啦", "~", "宝")) probs["撒娇"] = 0.5
        if (probs.isEmpty()) probs["平静"] = 0.6
        val top = probs.maxByOrNull { it.value }!!
        return Decision("emotion", "choice", top.key, top.value, probs)
    }

    private fun intent(t: String): Decision {
        val (label, c) = when {
            has(t, "你是不是", "又忘", "忙人", "算了", "记得") -> "试探在乎" to 0.7
            has(t, "帮", "请", "麻烦", "能不能", "怎么", "如何", "?", "？", "吗") -> "提问求助" to 0.72
            has(t, "生气", "烦", "无语", "投诉", "差") -> "发泄情绪" to 0.68
            has(t, "几点", "时间", "安排", "定") -> "有事要办" to 0.65
            has(t, "为什么", "怎么回事", "解释") -> "求解释" to 0.66
            else -> "闲聊分享" to 0.55
        }
        return Decision("intent", "choice", label, c, mapOf(label to c))
    }

    private fun needs(t: String): Decision {
        val (label, c) = when {
            has(t, "对不起", "道歉", "错了") -> "要道歉" to 0.6
            has(t, "为什么", "解释", "怎么回事") -> "要解释" to 0.62
            has(t, "几点", "时间", "安排", "定", "计划") -> "要安排" to 0.63
            has(t, "你是不是", "又忘", "记得", "在乎", "忙人") -> "要你在乎" to 0.6
            else -> "不用做啥" to 0.55
        }
        return Decision("needs", "choice", label, c, mapOf(label to c))
    }

    private fun subtext(t: String): Decision {
        val yes = has(t, "你是不是", "又忘", "忙人", "算了", "随便", "呵呵", "无所谓", "都行", "行吧")
        val p = if (yes) 0.8 else 0.25
        return Decision("subtext", "noul", p >= 0.5, kotlin.math.abs(p - 0.5) * 2)
    }

    private fun reply(t: String): Decision {
        val (label, c) = when {
            has(t, "急", "尽快", "马上", "现在", "?", "？", "吗", "在吗") -> "尽快回" to 0.75
            has(t, "帮", "请", "怎么", "为什么") -> "应该回" to 0.65
            has(t, "嗯", "哦", "好的", "收到") -> "可回可不回" to 0.6
            else -> "可回可不回" to 0.55
        }
        return Decision("reply", "choice", label, c, mapOf(label to c))
    }

    private fun danger(t: String): Decision {
        val score = when {
            has(t, "分手", "结束", "别回", "拉黑", "算了吧") -> 8.0
            has(t, "生气", "无语", "呵呵", "失望", "够了") -> 6.0
            has(t, "你是不是", "又忘", "忙人", "都不") -> 4.0
            has(t, "随便", "无所谓", "哦", "嗯") -> 2.0
            else -> 0.5
        }
        return Decision("danger", "score", score, 0.7, null)
    }
}
