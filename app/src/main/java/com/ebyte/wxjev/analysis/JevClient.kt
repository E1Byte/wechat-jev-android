package com.ebyte.wxjev.analysis

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jev（TypeSafe System One）HTTP 客户端。用 HttpURLConnection 直连 REST API，
 * 不引三方网络库，方便别人构建、减少依赖冲突。
 *
 * 真实协议（核对官方 SDK 源码）：
 *   POST https://api.typesafe.ai/v1/systemone
 *   Header: Authorization: Bearer <API_KEY>, Content-Type: application/json
 *   Body: {"state": {...}, "model": "jev-latest", "questions": {name: {type, instructions, criteria}}}
 *   Resp: {"answers": {name: {"type":"choice","choice":..,"confidence":..,"probabilities":{..}}
 *                             {"type":"noul","noul":0.98}
 *                             {"type":"score","score":..,"confidence":..,"legend":{..},"probabilities":{..}}}}
 *
 * 置信度口径：choice/score 用 confidence；noul 只给 yes 概率，value=(noul>=0.5)，confidence=|noul-0.5|*2。
 */
class JevClient(
    private val apiKey: String,
    private val model: String = "jev-latest",
    baseUrl: String = "https://api.typesafe.ai",
    private val timeoutMs: Int = 20000
) : Analyzer {
    class JevException(msg: String) : Exception(msg)

    // 归一化 systemone 端点：
    //  - 官方 https://api.typesafe.ai            -> .../v1/systemone
    //  - 中转站 https://api.knoxstudio.ai/v1     -> .../v1/systemone
    // 规则：去掉尾部斜杠；若已以 /v1 结尾则接 /systemone，否则接 /v1/systemone。
    private val endpoint: String = run {
        val b = baseUrl.trim().trimEnd('/')
        if (b.endsWith("/v1")) "$b/systemone" else "$b/v1/systemone"
    }

    override fun analyze(message: IncomingMessage, context: String, specs: List<QuestionSpec>): AnalysisResult {
        if (apiKey.isBlank()) return AnalysisResult(message, error = "未配置 Jev API key")
        if (specs.isEmpty()) return AnalysisResult(message)

        return try {
            val body = buildBody(message, context, specs)
            val resp = post(endpoint, body)
            parse(message, specs, resp)
        } catch (e: Exception) {
            AnalysisResult(message, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun buildBody(message: IncomingMessage, context: String, specs: List<QuestionSpec>): String {
        val questions = JSONObject()
        for (s in specs) {
            val q = JSONObject().put("type", s.kind).put("instructions", s.instructions)
            when (s.kind) {
                "choice" -> {
                    val crit = JSONObject()
                    s.criteria?.forEach { (k, v) -> crit.put(k, v) }
                    q.put("criteria", crit)
                }
                "noul" -> {
                    s.criteria?.let {
                        val crit = JSONObject()
                        it["true"]?.let { d -> crit.put("true", d) }
                        it["false"]?.let { d -> crit.put("false", d) }
                        q.put("criteria", crit)
                    }
                }
                "score" -> {
                    val arr = org.json.JSONArray()
                    s.scoreCriteria?.forEach { arr.put(it) }
                    q.put("criteria", arr)
                }
            }
            questions.put(s.name, q)
        }
        val state = JSONObject()
            .put("conversation_context", context)
            .put("message_to_analyze", message.analysisText())
        return JSONObject()
            .put("state", state)
            .put("model", model)
            .put("questions", questions)
            .toString()
    }

    private fun post(urlStr: String, body: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        if (code !in 200..299) throw JevException("HTTP $code: ${text.take(200)}")
        return JSONObject(text)
    }

    private fun parse(message: IncomingMessage, specs: List<QuestionSpec>, resp: JSONObject): AnalysisResult {
        val answers = resp.optJSONObject("answers") ?: return AnalysisResult(message, error = "响应缺少 answers")
        val out = LinkedHashMap<String, Decision>()
        for (s in specs) {
            val a = answers.optJSONObject(s.name) ?: continue
            out[s.name] = toDecision(s, a) ?: continue
        }
        return AnalysisResult(message, decisions = out)
    }

    private fun toDecision(s: QuestionSpec, a: JSONObject): Decision? = when (s.kind) {
        "choice" -> {
            val choice = a.optString("choice")
            val probs = a.optJSONObject("probabilities")?.let { jsonToMap(it) }
            Decision(s.name, "choice", choice, a.optDouble("confidence", 0.0), probs)
        }
        "noul" -> {
            val yesP = a.optDouble("noul", 0.5)
            Decision(s.name, "noul", yesP >= 0.5, kotlin.math.abs(yesP - 0.5) * 2)
        }
        "score" -> {
            val probs = a.optJSONObject("probabilities")?.let { jsonToMap(it) }
            Decision(s.name, "score", a.optDouble("score", 0.0), a.optDouble("confidence", 0.0), probs)
        }
        else -> null
    }

    private fun jsonToMap(o: JSONObject): Map<String, Double> {
        val m = LinkedHashMap<String, Double>()
        o.keys().forEach { k -> m[k] = o.optDouble(k, 0.0) }
        return m
    }
}
