package com.ebyte.wxjev.config

import android.content.Context
import de.robv.android.xposed.XSharedPreferences

/**
 * 配置读写。
 *
 * 两侧访问：
 * - App 进程（设置页）：用普通 SharedPreferences 写。
 * - 微信进程（Hook）：无法访问本 App 的私有目录，用 XSharedPreferences 跨进程只读。
 *   需在 Manifest 里声明 xposedsharedprefs=true，并把 prefs 设为 MODE_WORLD_READABLE。
 */
object Prefs {
    const val PREFS_NAME = "wxjev_prefs"
    const val KEY_JEV_API_KEY = "jev_api_key"
    const val KEY_TARGET_FRIEND = "target_friend"
    const val KEY_ENABLED = "enabled"
    const val KEY_MODEL = "jev_model"
    const val KEY_DIMENSIONS = "dimensions"           // 逗号分隔，如 "intent,sentiment,needs_reply,urgency"
    const val KEY_MOCK = "mock_analyzer"              // true=用 mock 分析器(无需 key)
    const val KEY_BASE_URL = "jev_base_url"           // Jev/中转站 base，可带或不带 /v1

    val DEFAULT_DIMENSIONS = listOf("intent", "sentiment", "needs_reply", "urgency")
    const val DEFAULT_BASE_URL = "https://api.typesafe.ai"

    // ---- App 进程写侧 ----
    // targetSdk 24+ 调 MODE_WORLD_READABLE 会抛 SecurityException（非 hook 进程）；
    // LSPosed 的 xposedsharedprefs 机制会让文件对 hook 进程可读。这里做降级：
    // 优先 WORLD_READABLE（hook 可读），失败则回退 PRIVATE（自测/mock/悬浮窗同进程仍可用）。
    @Suppress("DEPRECATION", "WorldReadableFiles")
    fun appPrefs(ctx: Context) = try {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE)
    } catch (e: SecurityException) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun setApiKey(ctx: Context, key: String) =
        appPrefs(ctx).edit().putString(KEY_JEV_API_KEY, key.trim()).apply()

    fun setTargetFriend(ctx: Context, who: String) =
        appPrefs(ctx).edit().putString(KEY_TARGET_FRIEND, who.trim()).apply()

    fun setEnabled(ctx: Context, on: Boolean) =
        appPrefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()

    fun setDimensions(ctx: Context, dims: List<String>) =
        appPrefs(ctx).edit().putString(KEY_DIMENSIONS, dims.joinToString(",")).apply()

    fun setMock(ctx: Context, on: Boolean) =
        appPrefs(ctx).edit().putBoolean(KEY_MOCK, on).apply()

    fun isMock(ctx: Context): Boolean =
        appPrefs(ctx).getBoolean(KEY_MOCK, false)

    fun setBaseUrl(ctx: Context, url: String) =
        appPrefs(ctx).edit().putString(KEY_BASE_URL, url.trim()).apply()

    fun getBaseUrl(ctx: Context): String =
        appPrefs(ctx).getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.ifBlank { DEFAULT_BASE_URL } ?: DEFAULT_BASE_URL

    fun setModel(ctx: Context, m: String) =
        appPrefs(ctx).edit().putString(KEY_MODEL, m.trim()).apply()

    fun getModel(ctx: Context): String =
        appPrefs(ctx).getString(KEY_MODEL, "jev-latest")?.ifBlank { "jev-latest" } ?: "jev-latest"

    fun getApiKey(ctx: Context): String =
        appPrefs(ctx).getString(KEY_JEV_API_KEY, "") ?: ""

    fun getTargetFriend(ctx: Context): String =
        appPrefs(ctx).getString(KEY_TARGET_FRIEND, "") ?: ""

    fun isEnabled(ctx: Context): Boolean =
        appPrefs(ctx).getBoolean(KEY_ENABLED, true)

    fun getDimensions(ctx: Context): List<String> =
        parseDims(appPrefs(ctx).getString(KEY_DIMENSIONS, null))

    // ---- 微信进程读侧（Hook）----
    fun xposed(): XSharedPreferences {
        val sp = XSharedPreferences("com.ebyte.wxjev", PREFS_NAME)
        sp.makeWorldReadable()
        return sp
    }

    /**
     * 让微信进程（另一个 UID、LSPatch 非 root）能读到本 App 的 prefs 文件。
     * 根因：数据目录默认 700、prefs 文件默认 660，跨 UID 读不到 → Hook 读到空 key → 回落 mock。
     * 修法：把 data 目录、shared_prefs 目录、prefs 文件都手动置为 world 可读/可进入（chmod o+rx）。
     * App 自己是属主，有权改自己文件的权限；每次写完配置都调一次。
     */
    fun exposeToHook(ctx: Context) {
        try {
            val dataDir = java.io.File(ctx.applicationInfo.dataDir)          // /data/data/com.ebyte.wxjev
            val spDir = java.io.File(dataDir, "shared_prefs")
            val spFile = java.io.File(spDir, "$PREFS_NAME.xml")
            // 目录要 o+x 才能被外部进程“穿过”，文件要 o+r 才能读
            dataDir.setExecutable(true, false)
            dataDir.setReadable(true, false)
            spDir.setExecutable(true, false)
            spDir.setReadable(true, false)
            spFile.setReadable(true, false)
        } catch (t: Throwable) { /* 忽略：失败时最多回落 mock */ }
    }

    fun parseDims(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return DEFAULT_DIMENSIONS
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
