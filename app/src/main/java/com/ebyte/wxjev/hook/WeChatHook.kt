package com.ebyte.wxjev.hook

import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed/LSPatch 模块入口。只在微信主进程挂载聊天页原生注入器。
 */
class WeChatHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.tencent.mm") return
        // 只在主进程注入（聊天页在主进程）；子进程(:push/:appbrand 等)跳过
        if (lpparam.processName != "com.tencent.mm") return
        Log.e("wxjev", "handleLoadPackage 主进程")
        try {
            ChatInjector.install(lpparam)
            Log.e("wxjev", "已挂载 聊天页注入器")
            XposedBridge.log("[wxjev] 已挂载聊天页注入器")
        } catch (t: Throwable) {
            Log.e("wxjev", "挂载失败: ${Log.getStackTraceString(t)}")
            XposedBridge.log("[wxjev] 挂载失败: $t")
        }
    }
}
