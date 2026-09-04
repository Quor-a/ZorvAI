package com.ai.assistance.quro.core.ui.dynamicui

/**
 * A2UI 多语言解释器（总装）。
 *
 * 把 ①③④ 层（Catalog 校验 / JSONL 信封 / 指针绑定）与第⑤层（QuroUiDslParser 解析 +
 * QuroUiRenderer 渲染）串成一条端到端流水线：
 *
 *   模型输出 ──▶ [interpret] ──▶ QuroUiParseResult
 *       ├─ 若是 JSONL 信封：A2uiSession 增量 apply → Catalog 校验 → 指针绑定 → 节点树
 *       └─ 若是 quro-ui DSL：QuroUiDslParser 解析 → Catalog 校验 → 节点树
 *   节点树 ──▶ QuroUiRenderer（Compose 原生渲染，数据不是代码）
 *
 * 六层栈回忆：Schema 定契约(①Catalog) → Prompt 教用法(②System Prompt) →
 * JSONL 传数据(③Envelope) → Pointer 做绑定(④) → Kotlin 当翻译(⑤Parser/Renderer) →
 * 原生控件出像素(⑥Compose)。本解释器实现 ①③④⑤，②⑥ 由系统提示词与渲染器承担。
 */
object A2uiInterpreter {

    /** 判定一段文本是否像 A2UI JSONL 信封。 */
    fun looksLikeEnvelope(text: String): Boolean = text.lines().any { l ->
        val t = l.trim()
        t.startsWith("{") && t.contains("\"type\"") && run {
            t.contains("createSurface") || t.contains("updateComponents") ||
                    t.contains("updateDataModel") || t.contains("deleteSurface")
        }
    }

    /** 统一解释入口：返回标准 [QuroUiParseResult]，可直接交给 QuroUiRenderer。 */
    fun interpret(source: String): QuroUiParseResult {
        if (looksLikeEnvelope(source)) {
            val root = A2uiSession().apply { applyJsonl(source) }.primaryRoot()
            return if (root != null) QuroUiParseResult.Success(root, source)
            else QuroUiParseResult.Failure(source, "A2UI 信封未产出有效节点")
        }
        return when (val r = QuroUiDslParser.parseBlock(source)) {
            is QuroUiParseResult.Success -> {
                val v = QuroUiCatalog.validate(r.root)
                QuroUiParseResult.Success(v.root, r.rawJson)
            }
            is QuroUiParseResult.Failure -> r
        }
    }

    /** 仅解释 JSONL 信封，返回主 surface 的根节点（失败返回 null）。 */
    fun interpretJsonl(text: String): QuroUiNode? =
        A2uiSession().apply { applyJsonl(text) }.primaryRoot()
}
