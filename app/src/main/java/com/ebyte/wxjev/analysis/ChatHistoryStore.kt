package com.ebyte.wxjev.analysis

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 每个好友一份持久化聊天记录 + 分析结果缓存（SQLite）。
 *
 * 目的（按用户需求）：
 *  1. 上下文分析：分析某条消息时，带上该好友最近的聊天历史，而不是只看单条气泡。
 *  2. 结果缓存：同一条消息只调一次 Jev，结果落库；下次重开聊天直接取缓存渲染，不再重复请求、不再计费。
 *
 * 运行位置：Hook 在微信进程内，用微信的 applicationContext 建库，
 * 库文件落在微信自己的 databases 目录，随微信数据持久化，跨重启有效。
 *
 * 两张表：
 *  messages(friend, text, ts)   —— 每个好友的对方发言历史（供拼上下文）
 *  cache(friend, hash, text, json, ts) —— 按 (好友, 文本hash) 缓存分析结果 JSON
 */
class ChatHistoryStore(ctx: Context) {

    private val helper = object : SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS messages(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, friend TEXT, text TEXT, ts INTEGER)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_friend ON messages(friend, id)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS cache(" +
                    "friend TEXT, hash TEXT, text TEXT, json TEXT, ts INTEGER, " +
                    "PRIMARY KEY(friend, hash))"
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {}
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase

    /** 命中缓存则返回上次分析结果（不再调 Jev）；无缓存返回 null。 */
    @Synchronized
    fun cachedResult(friend: String, msg: IncomingMessage): AnalysisResult? {
        return try {
            db.rawQuery(
                "SELECT json FROM cache WHERE friend=? AND hash=? LIMIT 1",
                arrayOf(friend, hash(msg.text))
            ).use { c -> if (c.moveToFirst()) decode(msg, c.getString(0)) else null }
        } catch (t: Throwable) { null }
    }

    /** 保存一条成功的分析结果（失败结果不缓存，避免把错误固化）。 */
    @Synchronized
    fun saveResult(friend: String, text: String, result: AnalysisResult) {
        if (result.error != null) return
        try {
            val cv = ContentValues().apply {
                put("friend", friend); put("hash", hash(text)); put("text", text)
                put("json", encode(result)); put("ts", System.currentTimeMillis())
            }
            db.insertWithOnConflict("cache", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (t: Throwable) { /* 落库失败不影响本次显示 */ }
    }

    /** 追加一条对方发言到历史（与该好友最近一条相同则跳过，避免滚动重复入库）。 */
    @Synchronized
    fun addMessage(friend: String, text: String) {
        try {
            db.rawQuery(
                "SELECT text FROM messages WHERE friend=? ORDER BY id DESC LIMIT 1",
                arrayOf(friend)
            ).use { c -> if (c.moveToFirst() && c.getString(0) == text) return }
            val cv = ContentValues().apply {
                put("friend", friend); put("text", text); put("ts", System.currentTimeMillis())
            }
            db.insert("messages", null, cv)
            // 控制单个好友历史规模：超过 500 条时裁掉最旧的
            db.execSQL(
                "DELETE FROM messages WHERE friend=? AND id NOT IN " +
                    "(SELECT id FROM messages WHERE friend=? ORDER BY id DESC LIMIT 500)",
                arrayOf(friend, friend)
            )
        } catch (t: Throwable) {}
    }

    /** 拼该好友最近 maxMessages 条历史为上下文字符串（时间正序）。 */
    @Synchronized
    fun context(friend: String, maxMessages: Int = 20): String {
        return try {
            val list = ArrayList<String>()
            db.rawQuery(
                "SELECT text FROM messages WHERE friend=? ORDER BY id DESC LIMIT ?",
                arrayOf(friend, maxMessages.toString())
            ).use { c -> while (c.moveToNext()) list.add(c.getString(0)) }
            list.reverse()
            list.joinToString("\n") { "对方: $it" }
        } catch (t: Throwable) { "" }
    }

    private fun hash(text: String): String = text.hashCode().toString()

    private fun encode(result: AnalysisResult): String {
        val arr = JSONArray()
        for ((_, d) in result.decisions) {
            val o = JSONObject()
            o.put("name", d.name)
            o.put("kind", d.kind)
            o.put("value", d.value)          // String / Boolean / Double
            o.put("confidence", d.confidence)
            d.probabilities?.let { p ->
                val po = JSONObject(); p.forEach { (k, v) -> po.put(k, v) }; o.put("probs", po)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    private fun decode(msg: IncomingMessage, json: String): AnalysisResult {
        val out = LinkedHashMap<String, Decision>()
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val kind = o.getString("kind")
            val value: Any? = when (kind) {
                "noul" -> o.optBoolean("value")
                "score" -> o.optDouble("value")
                else -> o.optString("value")
            }
            val probs = o.optJSONObject("probs")?.let { po ->
                val m = LinkedHashMap<String, Double>()
                po.keys().forEach { k -> m[k] = po.optDouble(k) }
                m
            }
            val name = o.getString("name")
            out[name] = Decision(name, kind, value, o.optDouble("confidence"), probs)
        }
        return AnalysisResult(msg, decisions = out)
    }

    companion object {
        private const val DB_NAME = "wxjev_history.db"
    }
}
