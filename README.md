# wechat-jev-android

安卓版「微信一对一好友聊天实时分析助手」。基于 **LSPosed/Xposed** 在微信进程内 Hook，
实时读取**对方发来的纯文本消息**，用 **TypeSafe AI 的 Jev**（System One 决策模型）分析
情绪 / 意图 / 真实需求 / 潜台词 / 该不该回 / 关系危险度，然后在**对方气泡正下方本地注入一张
仿引用小卡片**把结果显示出来——**不发任何微信消息、对方看不到、不碰聊天内容**。

> ⚠️ 合规提醒：Hook 微信违反微信使用条款、**有封号风险**。仅限在你**自有账号、自担风险**下使用。
> 本项目只「读 + 本地显示」，不自动回复、不发送任何消息，把风险降到最低。作者不对任何账号损失负责。

## 特点

- **决策模型而非聊天模型**：Jev 吃「状态 + 带类型的问题」，吐「带类型的决策 + 置信度」
  （Choice / Noul / Score）。卡片里显示的就是决策本身（如 `情绪 😠生气65%`），不需要第二个模型生成话术。
- **只分析对方、只分析纯文本**：靠消息行内头像位置判方向（头像在左=对方），
  过滤掉自己发的、名字、时间戳、通话记录、红包/转账、网址/分享卡片、添加好友系统提示等；**群聊自动不生效**。
- **情绪可叠加**：同一条消息可同时显示多种情绪及各自概率（如 `😠生气65% / 🥺委屈40%`），每个维度单独一行。
- **各用各的 key**：Jev API key 在 App 设置页自己填，存本地、通过 ContentProvider 跨进程给微信侧读，**不入代码、不入日志**。
- **无三方网络依赖**：`HttpURLConnection` 直连，`JevClient` 端点自动归一化到 `/v1/systemone`（官方 `api.typesafe.ai` 或自建中转站均可）。

## 数据流

```
微信进程内 Hook 聊天页
  → 扫描消息列表，按行内头像位置判方向，只取「对方发来的」纯文本气泡
  → 结合 per-好友滚动上下文，组 state + typed questions
  → HTTPS 调 Jev /v1/systemone，拿回带类型决策 + 置信度
  → 在该气泡正下方本地注入一张仿引用卡片，逐行显示各维度结果
```

## 模块结构

```
app/src/main/java/com/ebyte/wxjev/
  hook/WeChatHook.kt        Xposed 入口(IXposedHookLoadPackage)，只在 com.tencent.mm 生效
  hook/ChatInjector.kt      聊天页扫描：判方向、过滤非文本/群聊、注入结果卡片
  hook/QuoteCardBuilder.kt  本地画仿引用小卡片（透明底、每维度一行）
  hook/HookConfig.kt        微信侧读配置（跨进程查 ContentProvider，含缓存）
  analysis/Analyzer.kt      分析器接口（JevClient / MockAnalyzer 两实现）
  analysis/JevClient.kt     HTTPS 调 Jev /v1/systemone，端点归一化，无三方依赖
  analysis/MockAnalyzer.kt  离线关键词启发式，用于无 key 时自测链路
  analysis/Questions.kt     typed questions 定义（情绪/意图/需求/潜台词/该不该回/关系危险度）
  analysis/Decision.kt      决策数据模型 + Jev 响应归一化
  analysis/DecisionRenderer.kt 决策 → 卡片文字（图标 + 概率分布）
  analysis/ContextStore.kt  per-好友滚动上下文
  config/ConfigProvider.kt  暴露配置给微信进程的 ContentProvider
  config/Prefs.kt           本地配置存取（key / base_url / model / 维度开关 / mock 开关）
  ui/MainActivity.kt        激活状态 + 说明
  ui/SettingsActivity.kt    填 Jev key / 接口地址 / 模型，开关维度
```

## 构建

需要 Android SDK + JDK 17+，仓库自带 Gradle wrapper。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 安装与使用

前提：一台可用 LSPosed 环境的设备（Root + LSPosed，或用 **LSPatch** 免 Root 对微信打补丁）。

1. 安装本 APK。
2. 用 **LSPosed**（勾选模块 → 作用域勾微信 `com.tencent.mm` → 重启微信），
   或用 **LSPatch** 对微信重新修补后安装打补丁的微信。
3. 打开本 App → 设置页填 **Jev API key**（官方从 https://typesafe.ai 获取，或填自建中转站地址）→ 取消勾选「使用 Mock 分析」。
4. 在微信里进一对一聊天页，对方发纯文本消息时，气泡下方会出现 Jev 分析卡片。

> MIUI/HyperOS 等系统需给本 App 开「自启动 + 省电无限制」，否则微信进程可能读不到 key 而回落到 Mock。

## 已知限制

- 微信自绘气泡在不同版本控件结构不同，方向判断/文本抓取是启发式的，换版本可能要微调。
- 卡片注入依赖聊天页视图结构，微信大改版后需要适配。
- Jev 分析质量取决于模型与所填上下文；离线 Mock 仅用于验证链路，非真实分析。

## 许可

[MIT](LICENSE)
