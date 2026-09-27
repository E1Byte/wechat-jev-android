# wechat-jev-android

安卓版「微信一对一好友聊天实时分析助手」。基于 **LSPosed/Xposed** 在微信进程内 Hook，
实时读取对方发来的消息，用 **TypeSafe AI 的 Jev**（System One 决策模型）分析意图/情绪/是否需回复/紧急度，
用**本地悬浮窗**把决策结果显示出来（不发微信消息、不碰对方聊天，纯本地展示）。

> ⚠️ 合规提醒：Hook 微信违反微信使用条款、有封号风险。仅限在你**自有账号 + 模拟器/测试机、自担风险**使用。
> 本项目默认只「读 + 本地显示」，不自动回复、不发送任何消息，把风险降到最低。

## 为什么这样设计

- **Jev 不是聊天模型**：吃「状态 + 带类型的问题」，吐「带类型的决策 + 置信度」（Choice/Noul/Score）。
  所以悬浮窗里显示的就是决策本身（意图=提问 82% 等），不需要第二个模型生成话术。
- **Hook TextView 而非微信混淆类**：Hook 的是 Android 框架层的 `TextView`，与微信版本无关，
  任意微信版本都能跑，别人拿去装不用改类名——这是"给别人用"的关键。
- **只读不发**：不调用微信任何发送接口，只在本地悬浮窗显示分析，最大限度降低封号风险。
- **各用各的 key**：Jev API key 在 App 设置页里自己填，存本地，谁装谁填。

## 数据流

```
微信进程内 Hook TextView.setText
  → 识别聊天气泡文本 + 方向(对方/自己) + 引用原文
  → 只取「对方发来的」消息
  → 组 state + typed questions，HTTPS 调 Jev /v1/systemone
  → 拿决策 + 置信度
  → 通过悬浮窗服务显示在屏幕上
```

## 模块结构

```
app/src/main/java/com/ebyte/wxjev/
  hook/WeChatHook.kt        Xposed 入口(IXposedHookLoadPackage)，只在 com.tencent.mm 生效
  hook/MessageHooker.kt     Hook TextView，抓聊天消息 + 判方向 + 抓引用
  hook/MsgBridge.kt         把抓到的消息发给悬浮窗服务(广播)
  analysis/JevClient.kt     HTTPS 调 Jev /v1/systemone（HttpURLConnection，无三方依赖）
  analysis/Questions.kt     typed questions 定义(意图/情绪/是否回复/紧急度)
  analysis/Decision.kt      决策数据模型 + Jev 响应归一化
  analysis/ContextStore.kt  per-好友 滚动上下文
  overlay/OverlayService.kt 悬浮窗服务：收到消息→分析→显示
  config/Prefs.kt           跨进程读配置(XSharedPreferences)，存 Jev key/开关
  ui/MainActivity.kt        激活状态 + 说明
  ui/SettingsActivity.kt    填 Jev key、选监听好友、开关维度
```

## 构建与安装

需要 Android SDK + JDK 17+。仓库自带 Gradle wrapper。

```bash
# 1) 编译 debug APK
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 2) 装到已 root + 已装 LSPosed 的设备/模拟器
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

安装后：
1. 打开 **LSPosed** → 模块 → 勾选「微信Jev助手」→ 作用域勾 **微信**（com.tencent.mm）→ 重启微信。
2. 打开本 App → 设置页填 **Jev API key**（从 https://typesafe.ai 获取）→ 授予**悬浮窗权限**。
3. 在微信里和好友聊天，对方发消息时屏幕上会弹出 Jev 分析悬浮窗。

## 状态

骨架阶段。核心 Hook/分析/悬浮窗/设置链路已实现。真实 Hook 锚点（哪些 TextView 是聊天消息、如何判方向）
在真机上用不同微信版本可能要微调，代码里已用启发式 + `# TODO(real):` 标注。
