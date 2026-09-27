package com.ebyte.wxjev.hook

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.ebyte.wxjev.analysis.ContextStore
import com.ebyte.wxjev.analysis.IncomingMessage
import com.ebyte.wxjev.analysis.JevClient
import com.ebyte.wxjev.analysis.MockAnalyzer
import com.ebyte.wxjev.analysis.Questions
import com.ebyte.wxjev.analysis.DecisionRenderer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.Executors

/**
 * 聊天页原生注入器（微信 8.0.78 ChattingUI 实测结构）。
 *
 * 触发：Activity.dispatchTouchEvent（ChattingUI 里点/滑都触发），节流 800ms。
 * 每次扫描消息列表可见行：
 *   - 头像(类名含 Avatar / MaskLayout)在屏幕左半 => 对方消息；右半 => 自己。
 *   - 取该行最“像气泡正文”的 TextView（非空、非时间戳、宽度最大）作为对方发言。
 *   - 该行尚未插过卡片 => 起后台线程用 Jev(或mock)分析 => 把引用小窗卡片插到气泡所在容器、气泡之后。
 * 用 View.tag 标记已处理，避免重复插入与重复计费。
 *
 * 不发消息、对方看不到，纯本地视图注入。
 */
object ChatInjector {
    private const val TAG = "wxjev-inject"
    private const val CARD_TAG = "wxjev_card"
    private const val WRAP_TAG = "wxjev_wrap"
    private const val DONE_TAG_KEY = 0x7E000001            // 存"该气泡已分析的文本"(String)，用于识别复用
    private const val LISTENER_TAG_KEY = 0x7E000002        // 标记已给消息列表挂过布局监听
    private const val CARDTEXT_TAG_KEY = 0x7E000003        // 卡片上存"它对应的消息文本"
    private val io = Executors.newFixedThreadPool(2)
    private val store = ContextStore(20)
    private var lastScan = 0L
    private const val THROTTLE = 500L

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "dispatchTouchEvent", MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ev = param.args[0] as? MotionEvent ?: return
                    // 抬手(读完/点击)与滑动(滚动看历史/新消息)都触发，覆盖"只看不点"以外的场景
                    if (ev.action != MotionEvent.ACTION_UP && ev.action != MotionEvent.ACTION_MOVE) return
                    val act = param.thisObject as? Activity ?: return
                    triggerScan(act)
                }
            }
        )
        Log.e(TAG, "注入器已装")
    }

    private fun triggerScan(act: Activity) {
        val now = System.currentTimeMillis()
        if (now - lastScan < THROTTLE) return
        lastScan = now
        try { scan(act) } catch (t: Throwable) { Log.e(TAG, "scan err: ${Log.getStackTraceString(t)}") }
    }

    private fun scan(act: Activity) {
        val root = act.window?.decorView as? ViewGroup ?: return
        val sw = act.resources.displayMetrics.widthPixels
        val sh = act.resources.displayMetrics.heightPixels

        // 用“能否找到含聊天头像的消息列表”判断是否在聊天页（LauncherUI 内嵌 Fragment 也适用），
        // 找不到就跳过——自然排除桌面/设置页/侧边栏。
        val list = findMsgList(root)
        val listRect = android.graphics.Rect()
        if (list == null || !list.getGlobalVisibleRect(listRect)) return

        // 给消息列表挂一次滚动监听：滑动/新消息导致布局变化时也重新扫描，
        // 解决"只有点一下才触发、有时不触发"的稳定性问题。
        if (list.getTag(LISTENER_TAG_KEY) != true) {
            list.setTag(LISTENER_TAG_KEY, true)
            list.viewTreeObserver.addOnScrollChangedListener {
                (list.context as? Activity)?.let { triggerScan(it) }
            }
        }

        // 群聊不生效：多信号判定，任一命中即视为群聊并跳过。
        if (isGroupChat(root, list, sw, sh)) { Log.e(TAG, "群聊，跳过"); return }

        // 当前聊天对象名（用标题做 per-好友上下文键，避免不同好友的历史串味）
        val friend = currentChatTitle(root, sh) ?: "chat"

        val bubbles = ArrayList<Pair<View, String>>()
        // 该消息列表的直接子行，用于把候选文字归属到"行"，再靠行内头像判方向
        val rows = ArrayList<View>()
        for (i in 0 until list.childCount) list.getChildAt(i)?.let { rows.add(it) }

        fun rowOf(v: View): View? {
            var cur: View? = v
            while (cur != null) {
                if (cur in rows) return cur
                cur = cur.parent as? View
            }
            return null
        }

        fun walk(v: View) {
            // 跳过我们自己注入的卡片子树（但要继续深入 wrap 容器，才能重新扫描被复用行里的气泡）
            if (v.tag == CARD_TAG) return
            val cn = v.javaClass.name
            val r = android.graphics.Rect()
            if ((cn.contains("Neat") || (v is TextView && v !is android.widget.EditText)) &&
                v.visibility == View.VISIBLE && v.width >= 80 && v.getGlobalVisibleRect(r)) {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                val left = loc[0]
                val cy = loc[1] + v.height / 2
                if (cy >= listRect.top && cy <= listRect.bottom) {
                    val row = rowOf(v) as? ViewGroup
                    // 关键：靠所在行的头像位置判方向——头像在左=对方(true)。
                    // 找不到行/头像的（时间戳、系统提示、群发送者名条）一律跳过 => 不再分析名字/自己/系统。
                    val incoming = row?.let { isIncoming(it, sw) }
                    // 双保险：文字本身也必须整体在左半（排除自己右侧气泡）
                    if (incoming == true && left < sw * 0.42) {
                        val txt = if (v is TextView) v.text?.toString()?.trim() else reflectText(v)
                        if (!txt.isNullOrEmpty() && !isNoise(txt) && txt.length <= 2000 && !isLikelyName(txt)) {
                            bubbles.add(v to txt)
                        }
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        Log.e(TAG, "对方气泡候选=${bubbles.size}")
        var injected = 0
        for ((bubble, text) in bubbles) {
            val parent = bubble.parent as? ViewGroup ?: continue
            // 关键：RecyclerView 会复用行视图。用"该气泡上次分析的文本"判重，而非一次性布尔标记：
            //  - 文本相同 => 已分析过这条，跳过；
            //  - 文本不同（视图被复用给了新消息）=> 清掉旧卡片，重新分析。
            val prev = bubble.getTag(DONE_TAG_KEY) as? String
            if (prev == text) continue
            removeStaleCards(parent)
            bubble.setTag(DONE_TAG_KEY, text)
            Log.e(TAG, "注入->'${text.take(20)}'")
            injectPlaceholderAndAnalyzeText(bubble, text, friend)
            injected++
        }
        Log.e(TAG, "本轮注入$injected")
    }

    /** 清掉某容器内我们注入过的旧卡片（视图被复用给新消息时，旧卡片必须先移除）。 */
    private fun removeStaleCards(parent: ViewGroup) {
        for (i in parent.childCount - 1 downTo 0) {
            if (parent.getChildAt(i)?.tag == CARD_TAG) parent.removeViewAt(i)
        }
    }

    /** 从控件读文字：先 TextView.getText，为空则反射已发现的字段。 */
    private fun reflectText(v: View): String? {
        (v as? TextView)?.text?.toString()?.trim()?.let { if (it.isNotEmpty()) return it }
        // 反射所有声明字段(含父类3层)，找 CharSequence/String 非空值
        var cls: Class<*>? = v.javaClass
        var depth = 0
        while (cls != null && depth < 4) {
            for (f in cls.declaredFields) {
                try {
                    val type = f.type
                    if (CharSequence::class.java.isAssignableFrom(type)) {
                        f.isAccessible = true
                        val cs = f.get(v) as? CharSequence
                        val s = cs?.toString()?.trim()
                        if (!s.isNullOrEmpty() && s.length <= 2000 && !isNoise(s)) return s
                    }
                } catch (e: Throwable) {}
            }
            cls = cls.superclass; depth++
        }
        return null
    }

    private var probed = false
    /** 一次性反射探测：dump 左半聊天区自绘控件的所有 CharSequence 字段值，定位文字字段。 */
    private fun probeNeatText(root: View, sw: Int, sh: Int) {
        if (probed) return
        var any = false
        fun walk(v: View) {
            val cn = v.javaClass.name
            val r = android.graphics.Rect()
            if (cn.contains("Neat") && v.getGlobalVisibleRect(r) && v.width >= 40) {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                var cls: Class<*>? = v.javaClass; var d = 0
                while (cls != null && d < 4) {
                    for (f in cls.declaredFields) {
                        try {
                            if (CharSequence::class.java.isAssignableFrom(f.type)) {
                                f.isAccessible = true
                                val s = (f.get(v) as? CharSequence)?.toString()?.take(20)?.replace("\n"," ")
                                if (!s.isNullOrEmpty()) {
                                    Log.e(TAG, "NEAT ${v.javaClass.simpleName} x=${loc[0]} ${cls!!.simpleName}.${f.name}='$s'")
                                    any = true
                                }
                            }
                        } catch (e: Throwable) {}
                    }
                    cls = cls.superclass; d++
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        if (!any) Log.e(TAG, "NEAT探测: 无Neat控件或无CharSequence字段")
        probed = true
    }

    private fun siblingHasCard(parent: ViewGroup): Boolean {
        for (i in 0 until parent.childCount) if (parent.getChildAt(i).tag == CARD_TAG) return true
        return false
    }

    /** 找聊天消息列表：选“直接子项中含聊天头像的行数最多”的列表（真正装消息的内层 RecyclerView）。 */
    private fun findMsgList(root: View): ViewGroup? {
        val candidates = ArrayList<ViewGroup>()
        fun walk(v: View) {
            val cn = v.javaClass.name
            if (v is ViewGroup && (v is ListView || cn.contains("RecyclerView") || cn.endsWith("ListView")) && v.childCount > 0) {
                candidates.add(v)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        var best: ViewGroup? = null
        var bestScore = 0
        for (c in candidates) {
            if (c.javaClass.name.contains("ConversationListView")) continue
            val r = android.graphics.Rect()
            if (!c.getGlobalVisibleRect(r) || c.width == 0 || c.height == 0) continue
            // 打分：直接子项中“自身含头像”的行数
            var rows = 0
            for (i in 0 until c.childCount) {
                val child = c.getChildAt(i) ?: continue
                if (containsAvatar(child)) rows++
            }
            if (rows > bestScore) { bestScore = rows; best = c }
        }
        return best
    }

    /**
     * 判断是否群聊聊天页。多信号，任一命中即认为是群聊（宁可漏分析也不误判到群里）：
     *  1. 顶部标题区出现 "群名(5)" / "(12)" 成员计数（单聊标题无括号数字）。
     *  2. 顶部标题区出现群聊关键字（"群聊"、"群通知"等）。
     *  3. 顶部出现"聊天信息(N)"之类。
     *  4. 消息列表可见行里，"对方(左侧)消息行"用到了 >=2 个不同的头像图片位置 —— 单聊对方
     *     头像恒定一致，群聊里不同成员头像不同。这里用"左侧头像行里出现了发送者昵称小条"近似：
     *     群聊每条对方消息气泡上方有独立昵称 TextView（短、无标点、在头像右侧上方）。
     */
    private fun isGroupChat(root: View, list: ViewGroup, sw: Int, sh: Int): Boolean {
        val topLimit = sh * 0.14
        var titleGroup = false
        fun walkTitle(v: View) {
            if (titleGroup) return
            if (v is TextView && v.visibility == View.VISIBLE) {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                if (loc[1] in 0..topLimit.toInt()) {
                    val t = v.text?.toString()?.trim() ?: ""
                    // 标题带成员数：结尾 "(数字)" 或 "（数字）"，如 "开发群(8)"、"群聊(23)"
                    if (Regex(""".*[(（]\d{1,4}[)）]\s*$""").matches(t)) titleGroup = true
                    if (t.contains("群聊") || t.contains("群通知") || t.contains("群公告")) titleGroup = true
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walkTitle(v.getChildAt(i))
        }
        walkTitle(root)
        if (titleGroup) return true

        // 信号4：统计对方侧消息行里"发送者昵称小条"的出现。群聊里每条对方消息上方有独立昵称。
        // 做法：遍历列表可见行，若某行含左侧头像(对方)，看该行头像右上方是否有一个短昵称 TextView。
        var senderNameRows = 0
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as? ViewGroup ?: continue
            if (isIncoming(row, sw) != true) continue
            if (rowHasSenderName(row, sw)) senderNameRows++
        }
        // 有 >=1 行出现发送者昵称，基本可判群聊（单聊对方消息从不显示昵称条）
        return senderNameRows >= 1
    }

    /**
     * 该(对方)消息行里是否存在"发送者昵称条"：一个短(<=16字符)、无句末标点、靠近行顶部、
     * 位于头像右侧的小 TextView。单聊没有这个条。
     */
    private fun rowHasSenderName(row: ViewGroup, sw: Int): Boolean {
        val rowLoc = IntArray(2); row.getLocationOnScreen(rowLoc)
        val rowTop = rowLoc[1]
        var found = false
        fun walk(v: View) {
            if (found) return
            if (v is TextView && v.visibility == View.VISIBLE && v !is android.widget.EditText) {
                val t = v.text?.toString()?.trim() ?: ""
                if (t.isNotEmpty() && t.length <= 16 && !t.contains("\n") &&
                    !t.any { it in "。！？，,.!?~…：:；;" }) {
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    // 靠近行顶部(昵称在气泡上方)、且不在最左(头像右侧)、字号小
                    if (loc[1] - rowTop in 0..40 && loc[0] > sw * 0.12 && loc[0] < sw * 0.6) {
                        found = true
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(row)
        return found
    }

    private fun containsAvatar(v: View): Boolean {
        val cn = v.javaClass.name
        if (cn.contains("Avatar") || cn.contains("MaskLayout")) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (containsAvatar(v.getChildAt(i))) return true
        return false
    }

    /** 取顶部标题栏的聊天对象名，作为 per-好友上下文键。取屏幕上部最靠上的较短可见 TextView。 */
    private fun currentChatTitle(root: View, sh: Int): String? {
        val topLimit = (sh * 0.12).toInt()
        var best: String? = null
        var bestY = Int.MAX_VALUE
        fun walk(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                if (loc[1] in 0..topLimit) {
                    val t = v.text?.toString()?.trim() ?: ""
                    if (t.isNotEmpty() && t.length in 1..24 && !isNoise(t) &&
                        t !in setOf("微信", "返回", "聊天")) {
                        if (loc[1] < bestY) { bestY = loc[1]; best = t }
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        // 去掉群成员数括号（若有），并统一为好友键
        return best?.replace(Regex("""[(（]\d{1,4}[)）]\s*$"""), "")?.trim()
    }

    /** 递归 dump 控件树：类名 + 文本(若有) + 坐标宽度，用于定位自绘气泡控件。 */
    private fun dumpTree(root: View): String {
        val sb = StringBuilder()
        fun walk(v: View, d: Int) {
            if (d > 6) return
            val txt = try { (v.javaClass.getMethod("getText").invoke(v) as? CharSequence)?.toString()?.take(20)?.replace("\n", " ") } catch (e: Exception) { null }
            val loc = IntArray(2); v.getLocationOnScreen(loc)
            sb.append("${"  ".repeat(d)}${v.javaClass.simpleName} x=${loc[0]} w=${v.width}")
            if (!txt.isNullOrEmpty()) sb.append(" TXT='$txt'")
            sb.append("\n")
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), d + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    /** 诊断：列出所有可见、文本非空、宽度>100 的 TextView（这些才可能是气泡正文），带类名+坐标。 */
    private fun dumpVisibleTexts(root: View) {
        val sw = root.resources.displayMetrics.widthPixels
        val sb = StringBuilder("可见文本控件: ")
        var cnt = 0
        fun walk(v: View) {
            if (v is TextView) {
                val t = v.text?.toString()?.trim()?.take(16)?.replace("\n", " ") ?: ""
                val r = android.graphics.Rect()
                if (t.isNotEmpty() && v.width > 100 && v.getGlobalVisibleRect(r) && !isNoise(t)) {
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    val side = if (loc[0] + v.width / 2 < sw / 2) "L" else "R"
                    sb.append("\n  <${v.javaClass.simpleName}> $side x=${loc[0]} w=${v.width} '$t'")
                    cnt++
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        Log.e(TAG, "$sb\n可见气泡候选=$cnt")
    }

    /** 头像在左半=对方(true)，右半=自己(false)，找不到头像返回 null。 */
    private fun isIncoming(row: ViewGroup, screenW: Int): Boolean? {
        var avatar: View? = null
        fun walk(v: View) {
            if (avatar != null) return
            val cn = v.javaClass.name
            if (cn.contains("Avatar") || cn.contains("MaskLayout")) {
                if (v.width in 40..260) { avatar = v; return }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(row)
        val a = avatar ?: return null
        val loc = IntArray(2); a.getLocationOnScreen(loc)
        val cx = loc[0] + a.width / 2
        return cx < screenW / 2
    }

    /** 调试：dump 一行里所有非空 TextView 的 文本/坐标/宽度，帮助定位气泡与方向。 */
    private fun dbgRow(row: ViewGroup, screenW: Int): String {
        val sb = StringBuilder("w=${row.width}")
        fun walk(v: View) {
            if (v is TextView) {
                val t = v.text?.toString()?.take(16)?.replace("\n", " ") ?: ""
                if (t.isNotEmpty()) {
                    val loc = IntArray(2); v.getLocationOnScreen(loc)
                    sb.append(" [tv x=${loc[0]} w=${v.width} '$t']")
                }
            }
            val cn = v.javaClass.name
            if (cn.contains("Avatar") || cn.contains("MaskLayout")) {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                sb.append(" {av ${v.javaClass.simpleName} x=${loc[0]} w=${v.width}}")
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(row)
        return sb.toString()
    }

    /** 该行最像气泡正文的 TextView：非空、非时间戳、可见、宽度最大。 */
    private fun findBubbleTextView(row: ViewGroup): TextView? {
        var best: TextView? = null
        var bestW = 0
        fun walk(v: View) {
            if (v is TextView) {
                val t = v.text?.toString()?.trim() ?: ""
                if (t.isNotEmpty() && !isNoise(t) && v.width > bestW && v.visibility == View.VISIBLE) {
                    bestW = v.width; best = v
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(row)
        return best
    }

    private fun isNoise(t: String): Boolean {
        // ---- 时间/日期分隔条：微信各种格式，一律不分析 ----
        if (Regex("""^\d{1,2}:\d{2}$""").matches(t)) return true
        if (Regex("""^(上午|下午|凌晨|中午|晚上|早上)?\s*\d{1,2}:\d{2}$""").matches(t)) return true
        // 昨天/星期X/日期 + 可选时间，如 "昨天 12:31""星期一 上午10:30""2026年9月26日 12:00"
        if (Regex("""^(昨天|前天|今天)\s*.*""").matches(t) && t.length <= 12) return true
        if (Regex("""^星期[一二三四五六日天].*""").matches(t) && t.length <= 14) return true
        if (Regex("""^周[一二三四五六日天].*""").matches(t) && t.length <= 14) return true
        if (Regex("""\d{1,2}月\d{1,2}日""").containsMatchIn(t) && t.length <= 20) return true
        if (Regex("""\d{4}年\d{1,2}月\d{1,2}日""").containsMatchIn(t)) return true
        if (Regex("""^\d{4}[-/]\d{1,2}[-/]\d{1,2}""").containsMatchIn(t) && t.length <= 20) return true
        // 卡片自身 / 引用样式，避免把自己注入的卡片再当消息
        if (t.startsWith("🤖")) return true
        if (t.startsWith("情绪") || t.startsWith("意图") || t.startsWith("需求") ||
            t.startsWith("潜台词") || t.startsWith("回复") || t.startsWith("关系")) return true
        // 只分析纯文本消息：过滤掉通话/系统/卡片类等非文字气泡
        val nonText = listOf(
            "语音通话", "视频通话", "通话时长", "已取消", "对方已拒绝", "对方无应答", "正在通话",
            "[图片]", "[视频]", "[语音]", "[文件]", "[位置]", "[名片]", "[链接]", "[小程序]",
            "[转账]", "[红包]", "[表情]", "[动画表情]", "[音乐]", "[聊天记录]", "拍了拍",
            "领取了你的红包", "接龙", "[实时位置]", "邀请你加入群聊", "你已添加了", "以上是打招呼的内容",
            // 添加好友 / 系统提示类
            "现在可以开始聊天了", "我通过了你的朋友验证请求", "你已经添加了", "以上是打招呼",
            "对方开启了朋友验证", "你还不是他（她）朋友", "该用户开启了", "请先添加对方为好友",
            "你添加了", "通过朋友验证", "成为朋友", "扫一扫二维码", "对方正在输入",
            "消息已发出，但被对方拒收了", "开启了朋友验证", "发送以下名片", "位置共享已结束"
        )
        for (n in nonText) if (t.contains(n)) return true
        // 通话时长/通话结束类：如 "通话时长 00:12" "已接听 01:23" "对方已挂断" "聊天时长 12:34"
        if (Regex("""\d{1,2}:\d{2}(:\d{2})?""").containsMatchIn(t) &&
            (t.contains("通话") || t.contains("接听") || t.contains("挂断") || t.contains("时长") || t.contains("对方"))) return true
        val callHints = listOf("通话时长", "已接听", "已挂断", "对方已挂断", "对方已取消",
            "对方已拒绝", "对方无应答", "已拒绝", "已取消", "未接听", "呼叫失败", "正在呼叫", "聊天时长")
        if (callHints.any { t.contains(it) }) return true
        // 红包 / 转账 / 收款 卡片（真实气泡文字，不是 [红包] 占位符）
        val payHints = listOf("微信红包", "恭喜发财", "大吉大利", "领取红包", "已被领完", "红包",
            "微信转账", "转账", "已收款", "待接收", "已存入零钱", "已退还", "请收款", "收款成功",
            "发了一个红包", "领取了红包", "你领取了", "过期退还", "24小时内未领取")
        if (payHints.any { t.contains(it) }) return true
        // 纯金额，如 "¥88.00" "￥6" "50.00元"
        if (Regex("""^[¥￥]\s*\d+(\.\d{1,2})?$""").matches(t)) return true
        if (Regex("""^\d+(\.\d{1,2})?\s*元$""").matches(t)) return true
        // 含网址/地址的一律不分析
        if (Regex("""https?://""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        if (Regex("""(^|\s)www\.\S+""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        // 任意 域名.后缀（含常见/新顶级域），带或不带路径，如 b23.tv/xxx、xhslink.com、t.cn/abc
        if (Regex("""[\w-]+\.(com|cn|net|org|cc|top|xyz|io|me|vip|tv|co|app|link|shop|site|fun|ink|wang|club|pro|gov|edu)(\.[a-z]{2})?(/\S*)?""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        // 短链/分享域名关键词
        val linkHints = listOf("b23.tv", "xhslink", "t.cn", "douyin.com", "v.douyin", "微信公众平台",
            "mp.weixin", "网页链接", "分享自", "点击查看", "长按识别", "腾讯文档", "腾讯会议", "https", "http")
        if (linkHints.any { t.contains(it) }) return true
        // 语音消息通常是 "12''" 这种时长
        if (Regex("""^\d{1,3}\s*[''\"″]$""").matches(t)) return true
        // 微信 UI/菜单常见词，防止误注入
        val ui = setOf(
            "没有更多消息了", "以下为新消息", "撤回", "查看全文", "{source}", "设为群待办", "开启自动翻译",
            "更多", "功能", "聊天", "设置", "表情", "插件", "关于微信", "帮助与关于", "帮助与反馈",
            "音视频通话", "聊天记录管理", "其他功能", "小店与卡包", "添加账号", "轻触头像以切换账号"
        )
        if (t in ui) return true
        return false
    }

    /**
     * 群聊里对方气泡上方常有一条"发送者昵称"，它是独立 TextView、也在左侧，容易被误当消息。
     * 名字特征：短(<=12字符)、单行、无句末标点、不含空格/常见句子成分。宁可漏判也不误分析。
     */
    private fun isLikelyName(t: String): Boolean {
        if (t.length > 12) return false
        if (t.contains("\n")) return false
        // 含明显"句子"信号的不算名字
        if (t.any { it in "。！？，、,.!?~…：:；;" }) return false
        if (t.contains(" ")) return false
        // 太短的问候/口语（如"在吗""好的"）不该被当名字过滤——名字通常不带这些高频对话词
        val chatty = listOf("在吗","在么","好的","好呀","哈哈","嗯嗯","收到","可以","行","来了","吃了","睡了")
        if (chatty.any { t == it }) return false
        // 2~4 个纯中文字、无标点，多半是昵称 → 过滤（会误伤极短消息，但用户明确不要名字）
        return Regex("""^[\u4e00-\u9fa5A-Za-z0-9_]{2,12}$""").matches(t) && t.length <= 5
    }

    private fun findCard(row: ViewGroup): View? {
        var found: View? = null
        fun walk(v: View) {
            if (found != null) return
            if (v.tag == CARD_TAG) { found = v; return }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(row)
        return found
    }

    private fun injectPlaceholderAndAnalyze(bubble: TextView, text: String) {
        injectPlaceholderAndAnalyzeText(bubble, text, "chat")
    }

    private fun injectPlaceholderAndAnalyzeText(bubble: View, text: String, friend: String) {
        val ctx = bubble.context
        val card = QuoteCardBuilder.build(ctx, CARD_TAG)
        card.setTag(CARDTEXT_TAG_KEY, text)

        // 把卡片放到气泡正下方、同侧（左）。两种情况：
        //  A) 气泡已在我们之前建的 wrap 里（视图被复用）：直接把新卡片加到 wrap 末尾，不再套娃。
        //  B) 首次：用一个竖向 wrap 替换气泡，wrap 里放 [原气泡, 卡片]。
        bubble.post {
            try {
                val p = bubble.parent as? ViewGroup ?: return@post
                if (siblingHasCard(p)) return@post
                if (p.tag == WRAP_TAG) {
                    p.addView(card)
                    return@post
                }
                val idx = p.indexOfChild(bubble)
                if (idx < 0) return@post
                val lp = bubble.layoutParams
                p.removeViewAt(idx)
                val wrap = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = lp
                    tag = WRAP_TAG
                }
                bubble.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                wrap.addView(bubble)
                wrap.addView(card)
                p.addView(wrap, idx)
            } catch (t: Throwable) { Log.e(TAG, "wrap err: $t") }
        }

        val msg = IncomingMessage(friend, text, null, null)
        store.add(msg)
        val specs = Questions.enabled(HookConfig.dimensionsList())
        val context = store.renderContext(friend, excludeLast = true)
        val mock = HookConfig.mock()
        val key = HookConfig.apiKey()
        val base = HookConfig.baseUrl()
        val model = HookConfig.model()

        io.execute {
            val useMock = mock || key.isBlank()
            Log.e(TAG, "分析 mock=$useMock base=$base model=$model key=${if(key.isBlank())"空" else "有"}")
            val analyzer = if (useMock) MockAnalyzer() else JevClient(key, model, base)
            val result = analyzer.analyze(msg, context, specs)
            if (result.error != null) Log.e(TAG, "分析结果 err=${result.error}")
            else Log.e(TAG, "分析结果 " + result.decisions.entries.joinToString(",") { "${it.key}=${it.value.value}" })
            card.post { QuoteCardBuilder.render(card, result) }
        }
    }
}
