package com.ebyte.wxjev.analysis

import java.util.ArrayDeque

/** per-好友 滚动上下文：保留最近 N 条，供分析时拼上下文字符串。内存实现，够实时一对一用。 */
class ContextStore(private val maxMessages: Int = 20) {
    private val byFriend = HashMap<String, ArrayDeque<IncomingMessage>>()

    @Synchronized
    fun add(m: IncomingMessage) {
        val dq = byFriend.getOrPut(m.friend) { ArrayDeque() }
        dq.addLast(m)
        while (dq.size > maxMessages) dq.pollFirst()
    }

    /** 拼该好友历史为上下文；excludeLast=true 时排除最新一条（即当前正在分析的这条），避免重复。 */
    @Synchronized
    fun renderContext(friend: String, excludeLast: Boolean = true): String {
        val dq = byFriend[friend] ?: return ""
        val list = dq.toList().let { if (excludeLast && it.isNotEmpty()) it.dropLast(1) else it }
        if (list.isEmpty()) return ""
        return list.joinToString("\n") { m ->
            if (m.isQuote) "对方[引用\"${m.quotedContent}\"]: ${m.text}" else "对方: ${m.text}"
        }
    }
}
