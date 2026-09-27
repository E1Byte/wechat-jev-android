package com.ebyte.wxjev.analysis

/** 一条被分析的消息（对方发来的）。引用消息把被引原文与新话都保留，避免"吞了"。 */
data class IncomingMessage(
    val friend: String,
    val text: String,                 // 对方这次说的话
    val quotedName: String? = null,   // 被引用消息的发送者
    val quotedContent: String? = null // 被引用消息的原文
) {
    val isQuote: Boolean get() = quotedContent != null

    /** 喂给 Jev 的完整文本：引用消息时带上被引原文 + 新话。 */
    fun analysisText(): String =
        if (isQuote) "[对方引用了 $quotedName 的消息：\"$quotedContent\"]\n[对方这次说：\"$text\"]"
        else text
}

/** 一个分析维度的结果，由 Jev typed answer 归一化而来。 */
data class Decision(
    val name: String,
    val kind: String,                 // choice | noul | score
    val value: Any?,                  // choice=String; noul=Boolean; score=Double
    val confidence: Double,           // 0~1；noul 用 |p-0.5|*2 折算
    val probabilities: Map<String, Double>? = null
)

/** 一条消息的完整分析输出。error 非空表示分析失败。 */
data class AnalysisResult(
    val message: IncomingMessage,
    val decisions: Map<String, Decision> = emptyMap(),
    val error: String? = null
)
