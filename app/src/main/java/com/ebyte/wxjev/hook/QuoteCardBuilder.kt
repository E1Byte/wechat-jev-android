package com.ebyte.wxjev.hook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.ebyte.wxjev.analysis.AnalysisResult
import com.ebyte.wxjev.analysis.Decision
import com.ebyte.wxjev.analysis.Questions

/**
 * 分析卡片：仿 WechatVibe 风格 —— 竖向小卡，顶部标题，下面一排彩色圆角标签(chip)。
 * 每个分析维度一个 chip，按语义着色。插在对方气泡正下方，纯本地视图。
 */
object QuoteCardBuilder {

    private fun dp(ctx: Context, d: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, d.toFloat(), ctx.resources.displayMetrics).toInt()

    /** 占位卡（分析中…）。背景透明，只显示文字。 */
    fun build(ctx: Context, tagKey: String): LinearLayout {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 3); bottomMargin = dp(ctx, 2) }
            tag = tagKey
        }
        val title = TextView(ctx).apply {
            text = "🤖 分析中…"
            setTextColor(Color.parseColor("#8A8F99"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        card.addView(title)
        return card
    }

    /** 用结构化结果刷新卡片：透明背景，每个维度单独一行；情绪可叠加显示多个。 */
    fun render(card: LinearLayout, result: AnalysisResult) {
        val ctx = card.context
        card.removeAllViews()
        card.orientation = LinearLayout.VERTICAL

        if (result.error != null) {
            card.addView(line(ctx, "🤖 " + result.error, Color.parseColor("#E64340")))
            return
        }

        for ((name, d) in result.decisions) {
            val prefix = dimIcon(name)
            if (name == "emotion" && d.probabilities != null) {
                // 情绪叠加：列出所有概率 >= 0.20 的情绪（最多 3 个），按概率降序
                val tops = d.probabilities.entries
                    .filter { it.value >= 0.20 }
                    .sortedByDescending { it.value }
                    .take(3)
                val show = if (tops.isEmpty()) listOf(java.util.AbstractMap.SimpleEntry(d.value.toString(), 1.0)) else tops
                val rowText = StringBuilder("$prefix ")
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(TextView(ctx).apply {
                    text = "$prefix "; setTextColor(Color.parseColor("#8A8F99"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                show.forEachIndexed { i, e ->
                    val emo = e.key
                    val spec = Questions.ALL["emotion"]
                    val label = (spec?.icons?.get(emo) ?: "") + emo + " ${(e.value * 100).toInt()}%"
                    if (i > 0) row.addView(sep(ctx))
                    row.addView(TextView(ctx).apply {
                        text = label; setTextColor(emotionColor(emo))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    })
                }
                card.addView(row)
            } else {
                card.addView(line(ctx, "$prefix ${labelOf(name, d)}", colorOf(name, d)))
            }
        }
    }

    private fun line(ctx: Context, text: String, color: Int) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(ctx, 1), 0, dp(ctx, 1))
    }

    private fun sep(ctx: Context) = TextView(ctx).apply {
        text = " / "; setTextColor(Color.parseColor("#C9CDD4")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    // 每个维度前缀（区分是哪一项）
    private fun dimIcon(name: String): String = when (name) {
        "emotion" -> "情绪"
        "intent" -> "意图"
        "needs" -> "需求"
        "subtext" -> "潜台词"
        "reply" -> "回复"
        "danger" -> "关系"
        else -> name
    }

    private fun emotionColor(emo: String): Int = when (emo) {
        "开心", "期待", "撒娇" -> Color.parseColor("#07C160")
        "生气", "焦虑", "委屈", "失落" -> Color.parseColor("#E64340")
        "疲惫" -> Color.parseColor("#FA9D3B")
        else -> Color.parseColor("#8A8F99")
    }

    /** 兼容旧调用：单行文本更新。 */
    fun update(card: LinearLayout, text: String) {
        val ctx = card.context
        card.removeAllViews()
        card.addView(TextView(ctx).apply {
            this.text = text
            setTextColor(Color.parseColor("#576B95"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
    }

    private fun labelOf(name: String, d: Decision): String {
        val spec = Questions.ALL[name]
        return when (d.kind) {
            "choice" -> (spec?.icons?.get(d.value.toString()) ?: "") + d.value + confSuffix(d.confidence)
            "noul" -> {
                val (yes, no) = spec?.noulLabels ?: ("是" to "否")
                (if (d.value == true) yes else no) + confSuffix(d.confidence)
            }
            "score" -> {
                val lvl = ((d.value as? Double) ?: 0.0).let { Math.round(it).toInt() }
                val levels = spec?.scoreLevels ?: emptyList()
                (spec?.scorePrefix ?: "") + (levels.getOrNull(lvl) ?: lvl.toString())
            }
            else -> "$name:${d.value}"
        }
    }

    private fun confSuffix(c: Double): String = if (c in 0.01..0.999) " ${(c * 100).toInt()}%" else ""

    private fun colorOf(name: String, d: Decision): Int {
        return when (name) {
            "emotion" -> when (d.value.toString()) {
                "开心", "期待", "撒娇" -> Color.parseColor("#07C160")
                "生气", "焦虑", "委屈", "失落" -> Color.parseColor("#E64340")
                "疲惫" -> Color.parseColor("#FA9D3B")
                else -> Color.parseColor("#8A8F99")   // 平静
            }
            "intent" -> when (d.value.toString()) {
                "试探在乎", "发泄情绪" -> Color.parseColor("#E64340")
                "有事要办", "求解释", "提问求助" -> Color.parseColor("#FA9D3B")
                else -> Color.parseColor("#576B95")   // 闲聊分享/想收尾
            }
            "needs" -> when (d.value.toString()) {
                "要道歉", "要解释" -> Color.parseColor("#E64340")
                "要你在乎" -> Color.parseColor("#FA9D3B")
                "要安排" -> Color.parseColor("#576B95")
                else -> Color.parseColor("#07C160")   // 不用做啥
            }
            "subtext" -> if (d.value == true) Color.parseColor("#E64340") else Color.parseColor("#8A8F99")
            "reply" -> when (d.value.toString()) {
                "尽快回" -> Color.parseColor("#E64340")
                "应该回" -> Color.parseColor("#FA9D3B")
                "可回可不回" -> Color.parseColor("#576B95")
                else -> Color.parseColor("#8A8F99")   // 不必回
            }
            "danger" -> {
                val lvl = ((d.value as? Double) ?: 0.0).let { Math.round(it).toInt() }
                when {
                    lvl >= 7 -> Color.parseColor("#E64340")
                    lvl >= 4 -> Color.parseColor("#FA9D3B")
                    lvl >= 2 -> Color.parseColor("#576B95")
                    else -> Color.parseColor("#07C160")
                }
            }
            else -> Color.parseColor("#576B95")
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
