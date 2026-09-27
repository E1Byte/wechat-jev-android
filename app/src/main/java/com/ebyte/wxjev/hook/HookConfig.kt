package com.ebyte.wxjev.hook

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.net.Uri
import android.util.Log

/**
 * 微信进程侧读配置。
 *
 * 关键：LSPatch 非 root 下，XSharedPreferences / chmod 都读不到别的 app 私有目录（SELinux 拦）。
 * 改用 ContentProvider —— 微信进程用 contentResolver 查询本 App 的 ConfigProvider，
 * 这是 SELinux 允许的标准 app 间 IPC。查询时系统会自动拉起本 App 进程。
 * 每次读都实时查询，保证 App 里改了设置即时生效。
 */
object HookConfig {
    private val URI: Uri = Uri.parse("content://com.ebyte.wxjev.config/config")

    private data class Cfg(
        val key: String, val base: String, val model: String,
        val mock: Boolean, val dims: String, val enabled: Boolean, val target: String
    )

    @SuppressLint("Recycle")
    private fun read(): Cfg {
        val def = Cfg("", "https://api.typesafe.ai", "jev-latest", false, "", true, "")
        return try {
            val ctx = AndroidAppHelper.currentApplication() ?: return def
            val c = ctx.contentResolver.query(URI, null, null, null, null) ?: return def
            c.use {
                if (!it.moveToFirst()) return def
                fun col(n: String) = it.getColumnIndex(n).let { i -> if (i >= 0) it.getString(i) ?: "" else "" }
                Cfg(
                    key = col("key"),
                    base = col("base").ifBlank { "https://api.typesafe.ai" },
                    model = col("model").ifBlank { "jev-latest" },
                    mock = col("mock") == "1",
                    dims = col("dims"),
                    enabled = col("enabled") != "0",
                    target = col("target")
                )
            }
        } catch (t: Throwable) {
            Log.e("wxjev", "读配置失败: $t")
            def
        }
    }

    fun enabled(): Boolean = read().enabled
    fun mock(): Boolean = read().mock
    fun apiKey(): String = read().key
    fun baseUrl(): String = read().base
    fun model(): String = read().model
    fun targetFriend(): String = read().target
    fun dimensionsList(): List<String> {
        val raw = read().dims
        if (raw.isBlank()) return com.ebyte.wxjev.analysis.Questions.DEFAULT
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
