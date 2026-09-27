package com.ebyte.wxjev.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 首页：显示状态 + 入口。原生注入模式（Hook 在微信进程内把分析卡片插进聊天页），
 * 本 App 只负责配置，不再需要悬浮窗/前台服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        val title = TextView(this).apply {
            text = "微信 Jev 助手"
            textSize = 22f
        }
        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 24, 0, 24)
        }
        val settingsBtn = Button(this).apply {
            text = "打开设置（填 Jev API Key / 接口）"
            setOnClickListener { startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        root.addView(title)
        root.addView(status)
        root.addView(settingsBtn)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        com.ebyte.wxjev.config.Prefs.exposeToHook(this)   // 每次回到首页都确保配置对微信进程可读
        refresh()
    }

    private fun refresh() {
        status.text = buildString {
            appendLine("用法：在 LSPatch 里给微信集成本模块并重启微信，")
            appendLine("对方消息会在聊天页气泡下方以引用小窗样式显示 Jev 分析。")
            appendLine()
            appendLine("Jev API Key：" + if (hasKey()) "已配置 ✓" else "未配置 ✗（进设置页填写）")
            appendLine()
            appendLine("⚠️ Hook 微信有封号风险，仅限自有账号、自担风险使用。")
        }
    }

    private fun hasKey(): Boolean =
        com.ebyte.wxjev.config.Prefs.getApiKey(this).isNotBlank()
}
