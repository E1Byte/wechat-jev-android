package com.ebyte.wxjev.config

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * 跨进程配置通道（LSPatch 非 root 下的可靠方案）。
 *
 * 为什么不用 XSharedPreferences / chmod：现代 Android 的 SELinux 禁止一个 app 读另一个
 * app 的私有数据目录（DAC 权限放开也没用）。而 ContentProvider 是系统认可的 app 间 IPC，
 * SELinux 允许 —— 微信进程里的 Hook 通过 contentResolver.query 就能拿到本 App 的配置。
 *
 * 提供者进程被查询时会自动拉起，无需本 App 常驻。
 */
class ConfigProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.ebyte.wxjev.config"
        val URI: Uri = Uri.parse("content://$AUTHORITY/config")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val ctx = context!!
        val cols = arrayOf("key", "base", "model", "mock", "dims", "enabled", "target")
        val c = MatrixCursor(cols)
        c.addRow(arrayOf(
            Prefs.getApiKey(ctx),
            Prefs.getBaseUrl(ctx),
            Prefs.getModel(ctx),
            if (Prefs.isMock(ctx)) "1" else "0",
            Prefs.getDimensions(ctx).joinToString(","),
            if (Prefs.isEnabled(ctx)) "1" else "0",
            Prefs.getTargetFriend(ctx)
        ))
        return c
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
}
