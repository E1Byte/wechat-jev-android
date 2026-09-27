package com.ebyte.wxjev.analysis

/** 分析器契约：Jev 与 mock 都实现它，OverlayService 按配置二选一。 */
interface Analyzer {
    fun analyze(message: IncomingMessage, context: String, specs: List<QuestionSpec>): AnalysisResult
}
