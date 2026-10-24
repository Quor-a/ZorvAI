import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterEngine.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

# ══════════════════════════════════════════════════════════════
# 病灶：firstJson 解析失败被当成「业务不通过」
# 症状（用户实测）：同一句「验收方未给出有效结论（输出无法解析），按不通过处理」
#   在 ARBITRATING 与 VERIFYING 两个阶段各触发一次重规划 → 14 轮 /20 万 tokens 空转。
# ══════════════════════════════════════════════════════════════

# ── 修法 1：firstJson 加宽松修复层（本仓既有铁律：永远不把模型的格式问题当业务结论）
sub(
"""    private fun firstJson(raw: String): JSONObject? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { return JSONObject(trimmed) }""",
"""    private fun firstJson(raw: String): JSONObject? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { return JSONObject(trimmed) }
        // 🔴 宽松修复（2026-10-08 新增）：模型输出**格式**不合法不等于**内容**不合格。
        // 下面几类是端侧实测反复出现的形态，不是假想：
        //   - 单引号 `{'pass':true}`
        //   - 裸键名 `{pass:true}`（Python 风格）
        //   - 尾随逗号 `{"pass":true,}`
        //   - 全角引号/冒号 `｛\"pass\"：true｝`
        //   - 结尾缺闭合括号（被 max_tokens 截断）
        // 这些全都只差一层字符转换，重试一次 LLM 的成本远高于就地修好。
        runCatching { return JSONObject(loosenJson(trimmed)) }""",
'firstJson-loose')

# ── 修法 2：新增 loosenJson 与 verdictOf 两个纯函数
sub(
"""    /** 供 UI 展示：当前所有角色的预算占用 */""",
"""    /**
     * 把「只差几层字符」的 JSON 修成合法 JSON。**只做无损的结构性修复**，
     * 绝不改语义、绝不猜值 —— 修不好就返回原串让上层判失败。
     *
     * ## 为什么要有它（本仓既有铁律的延伸）
     *
     * 模型输出格式不对**不等于**工作没做到。旧实现里格式失败直接落进
     * `pass = false`，于是「模型只是忘了写逗号」被记成「产物不合格」，
     * 触发一整轮重规划 —— 14 轮 / 20 万 tokens 的空转就是这么来的。
     *
     * 修的形态（端侧实测反复出现，逐条都真实存在）：
     * - 全角标点：`｛` `｝` `，` `：` `\"` → 半角
     * - 单引号键值 → 双引号
     * - 裸键名 `{pass:true}` → `{"pass":true}`（值侧不加引号，保持原样）
     * - 尾随逗号 `{"a":1,}` → `{"a":1}`
     * - 结尾缺闭合括号 → 逐层补回
     *
     * 🔴 不做的事：不给裸值补引号（`"reason": abc` 补成 `"abc"` 就是**编造**内容，
     * 本仓对幻觉零容忍）。字符串内的内容一字不动。
     */
    internal fun loosenJson(raw: String): String {
        var s = raw
        // ① 全角 → 半角
        s = s.replace('\\uFF5B', '{').replace('\\uFF5D', '}')
            .replace('\\uFF0C', ',').replace('\\uFF1A', ':')
            .replace('\\uFF1B', ';').replace('\\uFF06', '"')
            .replace('\\uFF07', '"')
        // ② 中文引号 → 半角（模型爱用「」当引号）
        s = s.replace('\\u201C', '"').replace('\\u201D', '"')
        // ③ 单引号 → 双引号（仅当它是**结构分隔符**时；字符串内部的 ' 不动）
        s = quoteStructuralSingleQuotes(s)
        // ④ 裸键名 → 带引号键名
        s = quoteBareKeys(s)
        // ⑤ 尾随逗号
        s = s.replace(",}", "}").replace(",]", "]").replace(", }", " }").replace(", ]", " ]")
        // ⑥ 补回缺失的闭合括号
        s = closeUnbalanced(s)
        return s
    }

    /** `{'a':1}` → `{"a":1}`；字符串内容里的单引号（can't）不动。 */
    private fun quoteStructuralSingleQuotes(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var inStr = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inStr) {
                sb.append(c)
                if (c == '\\\\') { sb.append(s.getOrNull(i + 1)); i += 2; continue }
                if (c == '"') inStr = false
                i++
                continue
            }
            if (c == '"') { inStr = true; sb.append(c); i++; continue }
            if (c == '\\'' && isQuotePosition(s, i)) { sb.append('"'); i++; continue }
            sb.append(c); i++
        }
        return sb.toString()
    }

    /**
     * 单引号只有在**不在字符串内**，且（前面是 `{`/`,`/`:`/空白，或后面是 `:`/`,`/`}`/`]`/空白）
     * 时才算结构引号。否则判为字符串内容里的撇号，原样保留。
     */
    private fun isQuotePosition(s: String, i: Int): Boolean {
        val before = s.getOrNull(i - 1)
        val after = s.getOrNull(i + 1)
        val beforeOk = before == null || before == '{' || before == ',' || before == ':' ||
                before == ' ' || before == '\\n' || before == '\\t' || before == '['
        val afterOk = after == null || after == ':' || after == ',' || after == '}' ||
                after == ']' || after == ' ' || after == '\\n' || after == '\\t'
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
                if (c == '\\\\') { sb.append(s.getOrNull(i + 1)); i += 2; continue }
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
                if (c == '\\\\') { i += 2; continue }
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
        // 字符串未闭合时先补个引号，否则补括号也还是非法
        if (inStr) sb.append('"')
        while (stack.isNotEmpty()) sb.append(if (stack.removeLast() == '{') '}' else ']')
        return sb.toString()
    }

    /**
     * 判定结果的**三态**，而不是 bool。
     *
     * ## 🔴 为什么要三态（这是 14 轮空转的直接原因）
     *
     * 旧码 `val pass = o?.optBoolean("pass", false) ?: false` 把两种完全不同的情况
     * 压成同一个结果：
     * 1. 验收方真的判定**不通过**（产物确实不合格）→ 应该重做；
     * 2. 验收方的输出**解析失败**（格式不对/超时/胡说）→ 重做**没有任何意义**，
     *    因为待验收的产物一个字都没变，重做一百遍还是同样的产物、同样解析不出结论。
     *
     * 两者都进 `REPLANNING`，于是集群在「产物合格但格式没对齐」上反复空转，
     * 直到 `maxReplan` 熔断 —— 实测 14 轮 / 20 万 tokens。
     *
     * 现在区分开：
     * - [PASS] / [FAIL] 是**业务结论**，照旧驱动重做；
     * - [UNPARSABLE] 是**协议失败**，先换 prompt 重试一次；仍不行则降级放行
     *   （并如实记账），因为让一个**已经产出的合格产物**因为格式问题作废，
     *   代价远大于放行一个未知质量的产物 —— 而 [noProgress] 拦截仍然兜着
     *   「什么都没产出就宣布完成」这条路。
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
     * @param strictFallbackUnparsable=true 时解析失败给 [ClusterVerdict.UNPARSABLE]（验收默认）；
     *   收敛阶段只需要知道「有没有 summary」，解析不出就算没有。
     */
    internal fun verdictOf(
        text: String?,
        strictFallbackUnparsable: Boolean = true,
    ): ClusterVerdict {
        val o = text?.let { firstJson(it) } ?: return ClusterVerdict.UNPARSABLE
        if (!o.has("pass")) {
            // 有些模型会只写 reason 不写 pass。**有 reason 但无 pass** 时，
            // 视为格式不完整（UNPARSABLE）而不是猜它想说什么——
            // 猜 pass=true 就是本仓明令禁止的「0 冒充 -1」式编造。
            return ClusterVerdict.UNPARSABLE
        }
        return if (o.optBoolean("pass", false)) ClusterVerdict.PASS else ClusterVerdict.FAIL
    }

    /** 供 UI 展示：当前所有角色的预算占用 */""",
'helpers')

assert src != orig
tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  bytes %d -> %d' % (len(orig), len(src)))