package com.ebyte.wxjev.analysis

/** 把 AnalysisResult 渲染成悬浮窗要显示的一行文本。数据驱动 Questions.display，加维度不用改这里。 */
object DecisionRenderer {
    fun render(result: AnalysisResult, showConfidence: Boolean = true, lowThreshold: Double = 0.6): String? {
        if (result.error != null) return null      // 失败不显示（错误只记日志）
        if (result.decisions.isEmpty()) return null
        val parts = result.decisions.map { (name, d) -> fmt(name, d, showConfidence, lowThreshold) }
        return "🤖 " + parts.joinToString(" | ")
    }

    private fun fmt(name: String, d: Decision, showConf: Boolean, low: Double): String {
        val label = label(name, d)
        if (!showConf) return label
        val flag = if (d.confidence < low) "?" else ""
        val pct = (d.confidence * 100).toInt()
        return "$label($pct%$flag)"
    }

    private fun label(name: String, d: Decision): String {
        val spec = Questions.ALL[name]
        return when (d.kind) {
            "choice" -> (spec?.icons?.get(d.value.toString()) ?: "") + d.value
            "noul" -> {
                val (yes, no) = spec?.noulLabels ?: ("是" to "否")
                if (d.value == true) yes else no
            }
            "score" -> {
                val lvl = ((d.value as? Double) ?: 0.0).let { Math.round(it).toInt() }
                val levels = spec?.scoreLevels ?: emptyList()
                val lvlName = levels.getOrNull(lvl) ?: lvl.toString()
                (spec?.scorePrefix ?: "") + lvlName
            }
            else -> "$name:${d.value}"
        }
    }
}
