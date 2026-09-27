package com.ebyte.wxjev.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ebyte.wxjev.analysis.Questions
import com.ebyte.wxjev.config.Prefs

/**
 * 设置页：填 Jev API key、监听好友、勾选分析维度。写入 WORLD_READABLE 偏好供 Hook 进程读取。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }

        root.addView(TextView(this).apply { text = "Jev API Key（从 typesafe.ai 获取）"; textSize = 14f })
        val keyEdit = EditText(this).apply {
            setText(Prefs.getApiKey(this@SettingsActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            hint = "粘贴你的 Jev API Key"
        }
        root.addView(keyEdit)

        val mockBox = CheckBox(this).apply {
            text = "使用 Mock 分析（无需 Key，先验证链路）"
            isChecked = Prefs.isMock(this@SettingsActivity)
        }
        root.addView(mockBox)

        root.addView(TextView(this).apply { text = "Jev 接口地址（官方或中转站，可留空用默认）"; textSize = 14f; setPadding(0, 24, 0, 0) })
        val baseEdit = EditText(this).apply {
            setText(Prefs.getBaseUrl(this@SettingsActivity))
            hint = "如 https://api.knoxstudio.ai/v1"
        }
        root.addView(baseEdit)

        root.addView(TextView(this).apply { text = "模型（默认 jev-latest）"; textSize = 14f; setPadding(0, 24, 0, 0) })
        val modelEdit = EditText(this).apply {
            setText(Prefs.getModel(this@SettingsActivity))
            hint = "jev-latest"
        }
        root.addView(modelEdit)

        root.addView(TextView(this).apply { text = "监听好友（备注名/昵称，可留空=不限）"; textSize = 14f; setPadding(0, 32, 0, 0) })
        val friendEdit = EditText(this).apply {
            setText(Prefs.getTargetFriend(this@SettingsActivity))
            hint = "如：老王"
        }
        root.addView(friendEdit)

        root.addView(TextView(this).apply { text = "分析维度"; textSize = 14f; setPadding(0, 32, 0, 8) })
        val enabled = Prefs.getDimensions(this).toSet()
        val boxes = Questions.ALL.map { (name, spec) ->
            CheckBox(this).apply {
                text = "$name — ${spec.instructions}"
                isChecked = name in enabled
                tag = name
            }.also { root.addView(it) }
        }

        val save = Button(this).apply {
            text = "保存"
            setOnClickListener {
                Prefs.setApiKey(this@SettingsActivity, keyEdit.text.toString())
                Prefs.setTargetFriend(this@SettingsActivity, friendEdit.text.toString())
                Prefs.setMock(this@SettingsActivity, mockBox.isChecked)
                Prefs.setBaseUrl(this@SettingsActivity, baseEdit.text.toString())
                Prefs.setModel(this@SettingsActivity, modelEdit.text.toString())
                val dims = boxes.filter { it.isChecked }.map { it.tag as String }
                Prefs.setDimensions(this@SettingsActivity, dims.ifEmpty { Prefs.DEFAULT_DIMENSIONS })
                Prefs.setEnabled(this@SettingsActivity, true)
                Prefs.exposeToHook(this@SettingsActivity)   // 让微信进程能读到本 App 的配置
                Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(save)

        val scroll = android.widget.ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }
}
