package com.ebyte.wxjev.analysis

/**
 * Mock 分析器 —— 无需 Jev API key，用关键词启发式模拟决策形态，产出结构与 JevClient 完全一致。
 * 用于在没有 key / 没接通微信时验证「消息→分析→悬浮窗显示」整条链路。
 */
class MockAnalyzer : Analyzer {

    override fun analyze(message: IncomingMessage, context: String, specs: List<QuestionSpec>): AnalysisResult {
        val text = message.analysisText()
        val out = LinkedHashMap<String, Decision>()
        for (s in specs) {
            when (s.name) {
                "intent" -> out[s.name] = intent(text)
                "sentiment" -> out[s.name] = sentiment(text)
                "needs_reply" -> out[s.name] = needsReply(text)
                "urgency" -> out[s.name] = urgency(text)
            }
        }
        return AnalysisResult(message, decisions = out)
    }

    private fun has(t: String, vararg ks: String) = ks.any { t.contains(it) }

    private fun intent(t: String): Decision {
        val (label, c) = when {
            has(t, "?", "？", "吗", "呢", "怎么", "如何", "为什么", "多少") -> "提问" to 0.82
            has(t, "帮", "请", "麻烦", "能否") -> "请求" to 0.7
            has(t, "生气", "失望", "烦", "差", "投诉", "退") -> "抱怨" to 0.68
            has(t, "http", "分享", "看看", "这个") -> "分享" to 0.6
            else -> "闲聊" to 0.55
        }
        return Decision("intent", "choice", label, c, mapOf(label to c))
    }

    private fun sentiment(t: String): Decision {
        val (label, c) = when {
            has(t, "生气", "失望", "烦", "差", "投诉", "😡") -> "负面" to 0.75
            has(t, "谢谢", "感谢", "太好了", "喜欢", "棒", "赞", "哈哈", "👍") -> "正面" to 0.78
            else -> "中性" to 0.6
        }
        return Decision("sentiment", "choice", label, c, mapOf(label to c))
    }

    private fun needsReply(t: String): Decision {
        val yes = has(t, "?", "？", "吗", "呢", "帮", "请", "麻烦", "能否", "怎么", "如何")
        val p = if (yes) 0.85 else 0.2
        return Decision("needs_reply", "noul", p >= 0.5, kotlin.math.abs(p - 0.5) * 2, null)
    }

    private fun urgency(t: String): Decision {
        val (score, c) = when {
            has(t, "急", "尽快", "马上", "立刻", "现在", "赶紧", "催", "今天必须") -> 3.0 to 0.8
            has(t, "?", "？", "吗", "帮", "请") -> 1.5 to 0.6
            else -> 0.4 to 0.7
        }
        return Decision("urgency", "score", score, c, null)
    }
}
