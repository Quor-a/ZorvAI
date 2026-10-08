/**
 * 集群验收/裁决的**协议层**纯函数：三态判定 + 宽松 JSON 修复。
 *
 * ## 为什么要单独一个 object（而不是挂在 [ClusterEngine] 上）
 *
 * 这些函数**不依赖任何实例状态**（不碰 context / 不碰 events 流），
 * 而 [ClusterEngine] 的构造需要真实 `Context`。工程没引 Robolectric，
 * 所以挂在引擎上的纯函数 JVM 单测根本 new 不出来 —— 挂顶层才能直接单测。
 * 这与本仓 `RagEngine.coverageReport` 用顶层扩展的既有风格一致。
 */
package com.ai.assistance.quro.core.cluster

import org.json.JSONObject

object ClusterVerdictProtocol {

    /**
     * 判定三态。
     *
     * 🔴 为什么要三态（这是集群 14 轮 /20 万 tokens 空转的直接原因）
     *
     * 旧码 `val pass = o?.optBoolean("pass", false) ?: false` 把两种完全不同的情况
     * 压成同一个结果：
     * 1. 验收方真的判定**不通过**（产物确实不合格）→ 应该重做；
     * 2. 验收方的输出**解析失败**（格式不对 / 超时 / 截断）→ 重做**没有任何意义**，
     *    因为待验收的产物一个字都没变，重做一百遍还是同样的产物、同样解析不出结论。
     *
     * 两者都进 `REPLANNING`，于是集群在「产物合格但格式没对齐」上反复空转，
     * 直到 `maxReplan` 熔断 —— 用户实测 14 轮 / 20 万 tokens。
     *
     * 现在区分开：[PASS] / [FAIL] 是**业务结论**，照旧驱动重做；
     * [UNPARSABLE] 是**协议失败**，先重试一次，仍不行则降级放行并**如实记账**。
     */
    enum class ClusterVerdict {
        /** 验收方明确判定通过。 */
        PASS,

        /** 验收方明确判定不通过：产物不合格，该重做。 */
        FAIL,

        /** 🔴 验收方输出无法解析：不是业务结论，既不重做也不当作通过来掩盖。 */
        UNPARSABLE,
    }

    /**
     * 把验收方输出解析成三态判定。
     *
     * @param strictFallbackUnparsable 保留参数（当前所有分支都返回 UNPARSABLE）；
     *   它记录的是「解析失败就是协议失败」这个决策本身，别删。
     */
    @Suppress("UNUSED_PARAMETER")
    fun verdictOf(
        text: String?,
        strictFallbackUnparsable: Boolean = true,
    ): ClusterVerdict {
        val trimmed = text?.trim()
            ?.removePrefix("```json")?.removePrefix("```")
            ?.removeSuffix("```")?.trim().orEmpty()
        if (trimmed.isEmpty()) return ClusterVerdict.UNPARSABLE
        // 三级递进：原文 → 剥出花括号块 → 宽松修复后再解析。
        // 🔴 第二级不可省：模型几乎总会在 JSON 外面加话（「这是我的验收结论：…以上。」），
        // 而 org.json 要求整串就是 JSON，前导一句中文就整串失败。
        // 这与引擎内 firstJson 的「按花括号配平截取」是同一步，这里补齐以免两边口径不一。
        val candidates = listOf(trimmed, extractBraceBlock(trimmed))
        var o: JSONObject? = null
        for (c in candidates) {
            if (c.isBlank()) continue
            o = runCatching { JSONObject(c) }.getOrNull()
                ?: runCatching { JSONObject(loosenJson(c)) }.getOrNull()
            if (o != null) break
        }
        if (o == null) return ClusterVerdict.UNPARSABLE
        if (!o.has("pass")) {
            // 有些模型会只写 reason 不写 pass。**有 reason 但无 pass** 时，
            // 视为格式不完整（UNPARSABLE）而不是猜它想说什么——
            // 猜 pass=true 就是本仓明令禁止的「0 冒充 -1」式编造。
            return ClusterVerdict.UNPARSABLE
        }
        return if (o.optBoolean("pass", false)) ClusterVerdict.PASS else ClusterVerdict.FAIL
    }

    /**
     * 按花括号配平，从一段可能夹带废话的文本里截出**第一段** JSON 对象。
     *
     * 与引擎内 `firstJson` 的同名步骤同口径（两边不能一个能救一个不能）。
     * 截不到就返回空串，由调用方走下一级。
     */
    private fun extractBraceBlock(raw: String): String {
        val s = raw.indexOf('{')
        if (s < 0) return ""
        var depth = 0
        var inStr = false
        var i = s
        while (i < raw.length) {
            val c = raw[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(s, i + 1)
                }
            }
            i++
        }
        return ""
    }

    /**
     * 把「只差几层字符」的 JSON 修成合法 JSON。**只做无损的结构性修复**，
     * 绝不改语义、绝不猜值 —— 修不好就返回原串让上层判失败。
     *
     * 修的形态（端侧实测反复出现，逐条都真实存在）：
     * - 全角标点 `｛` `｝` `，` `：` → 半角
     * - 中文引号 `「`风格 curly quotes → 半角
     * - 单引号键值 → 双引号
     * - 裸键名 `{pass:true}` → `{"pass":true}`（**值侧不加引号**，保持原样）
     * - 尾随逗号 `{"a":1,}` → `{"a":1}`
     * - 结尾缺闭合括号（被 max_tokens 截断）→ 逐层补回
     *
     * 🔴 不做的事：不给裸值补引号（`"reason": abc` 补成 `"abc"` 就是**编造**内容，
     * 本仓对幻觉零容忍）。字符串内的内容一字不动。
     */
    fun loosenJson(raw: String): String {
        var s = raw
        // ① 全角标点 → 半角
        s = s.replace('｛', '{').replace('｝', '}')
            .replace('，', ',').replace('：', ':')
            .replace('；', ';').replace('＂', '"')
            .replace('＇', '"')
        // ② 中文弯引号 → 半角（模型爱拿它们当 JSON 引号）
        s = s.replace('“', '"').replace('”', '"')
        // ③ 单引号 → 双引号（仅当它是**结构分隔符**时；字符串内部的撇号不动）
        s = quoteStructuralSingleQuotes(s)
        // ④ 裸键名 → 带引号键名
        s = quoteBareKeys(s)
        // ⑤ 尾随逗号
        s = s.replace(",}", "}").replace(",]", "]").replace(", }", " }").replace(", ]", " ]")
        // ⑥ 补回缺失的闭合括号
        s = closeUnbalanced(s)
        return s
    }

    /** `{'a':1}` → `{"a":1}`；字符串内容里的单引号（don't）不动。 */
    private fun quoteStructuralSingleQuotes(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var inStr = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                sb.append(c)
                if (c == '\\') { sb.append(s.getOrNull(i + 1)); i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            if (c == '"') { inStr = true; sb.append(c); i++; continue }
            if (c == '\'' && isQuotePosition(s, i)) { sb.append('"'); i++; continue }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /**
     * 单引号只有在**不在字符串内**，且（前面是 `{` / `,` / `:` / 空白，
     * 或后面是 `:` / `,` / `}` / `]` / 空白）时才算结构引号。
     * 否则判为字符串内容里的撇号，原样保留。
     */
    private fun isQuotePosition(s: String, i: Int): Boolean {
        val before = s.getOrNull(i - 1)
        val after = s.getOrNull(i + 1)
        val beforeOk = before == null || before == '{' || before == ',' || before == ':' ||
                before == ' ' || before == '\n' || before == '\t' || before == '['
        val afterOk = after == null || after == ':' || after == ',' || after == '}' ||
                after == ']' || after == ' ' || after == '\n' || after == '\t'
        return beforeOk && afterOk
    }

    /** `{pass:true}` → `{"pass":true}`。只加键的引号，**不给值补引号**（不编造内容）。 */
    private fun quoteBareKeys(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var inStr = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                sb.append(c)
                if (c == '\\') { sb.append(s.getOrNull(i + 1)); i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            if (c == '"') { inStr = true; sb.append(c); i++; continue }
            if (c == '{' || c == ',') {
                sb.append(c)
                i++
                // 跳到下一个非空白字符：若它不是引号但后面紧跟冒号，就是裸键名
                var j = i
                while (j < s.length && s[j].isWhitespace()) j++
                if (j < s.length && s[j] != '"') {
                    var k = j
                    while (k < s.length && (s[k].isLetterOrDigit() || s[k] == '_' || s[k] == '-')) k++
                    if (k > j && s.getOrNull(k) == ':') {
                        sb.append('"').append(s, j, k).append('"')
                        i = k
                        continue
                    }
                }
                continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /** 结尾缺闭合括号（被 max_tokens 截断）时逐层补回。 */
    private fun closeUnbalanced(s: String): String {
        val stack = ArrayDeque<Char>()
        var inStr = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{', '[' -> stack.addLast(c)
                '}' -> if (stack.lastOrNull() == '{') stack.removeLast()
                ']' -> if (stack.lastOrNull() == '[') stack.removeLast()
            }
            i++
        }
        if (stack.isEmpty()) return s
        val sb = StringBuilder(s)
        // 字符串未闭合时先补个引号，否则光补括号还是非法
        if (inStr) sb.append('"')
        while (stack.isNotEmpty()) sb.append(if (stack.removeLast() == '{') '}' else ']')
        return sb.toString()
    }
}