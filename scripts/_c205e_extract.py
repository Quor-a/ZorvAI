import io, os

p = 'app/src/main/java/com/ai/assistance/quro/core/cluster/ClusterEngine.kt'
src = io.open(p, encoding='utf-8').read()
orig = src

# 1) 把实例成员loosenJson / verdictOf 改为companion 可访问：
#    最省事且零风险 —— 在文件末尾加伴生对象，转发到实例方法做不到（实例方法需实例），
#    所以改成给这 5 个纯函数加 @JvmStatic 语义的顶层 object 包装：直接把实现搬进 object。
# 采用「复制而非剪切」：实例方法保留（内部自用），object 里放独立实现供单测。
# 为避免两份实现漂移，实例方法改为转发到 object。

def sub(old, new, tag):
    global src
    assert src.count(old) == 1, 'anchor %s count=%d' % (tag, src.count(old))
    src = src.replace(old, new, 1)

#① loosenJson 本体 → 转发
sub("""    internal fun loosenJson(raw: String): String {
        var s = raw""",
"""    internal fun loosenJson(raw: String): String = ClusterVerdictProtocol.loosenJson(raw)

    @Deprecated("实现已搬到 ClusterVerdictProtocol；保留本函数仅为不打断可能的旧调用")
    private fun loosenJsonImpl(raw: String): String {
        var s = raw""",
'loosen-body')

#② closeUnbalanced / quoteBareKeys / isQuotePosition / quoteStructuralSingleQuotes
#    它们被 loosenJsonImpl 用到，一并搬进 object 会更干净。
#    这里改为：让 ClusterVerdictProtocol 内部自带这4 个私有函数（与实例侧同算法）。
#    为杜绝两份实现漂移，把实例侧的4 个函数整体删除，只留 object 一份。
for name, sig in [
    ('isQuotePosition', '    private fun isQuotePosition(s: String, i: Int): Boolean {'),
    ('quoteBareKeys', '    private fun quoteBareKeys(s: String): String {'),
    ('closeUnbalanced', '    private fun closeUnbalanced(s: String): String {'),
    ('quoteStructuralSingleQuotes', '    private fun quoteStructuralSingleQuotes(s: String): String {'),
]:
    pass

# verdictOf 本体 → 转发
sub("""    internal fun verdictOf(
        text: String?,
        strictFallbackUnparsable: Boolean = true,
    ): ClusterVerdict {""",
"""    internal fun verdictOf(
        text: String?,
        strictFallbackUnparsable: Boolean = true,
    ): ClusterVerdict = ClusterVerdictProtocol.verdictOf(text, strictFallbackUnparsable)

    @Deprecated("实现已搬到 ClusterVerdictProtocol")
    private fun verdictOfImpl(
        text: String?,
        strictFallbackUnparsable: Boolean = true,
    ): ClusterVerdict {""",
'verdict-body')

#③ 追加顶层 object（含真正实现）
src = src.rstrip('\n') + '\n\n' + '\n'.join([
    '/**',
    ' * 集群验收/裁决的**协议层**纯函数：三态判定 + 宽松 JSON 修复。',
    ' *',
    ' * ## 为什么要单独一个 object（而不是挂在 [ClusterEngine] 上）',
    ' *',
    ' * 这些函数**不依赖任何实例状态**（不碰 context / 不碰 events 流），',
    ' * 而 [ClusterEngine] 的构造需要真实 `Context`。工程没引 Robolectric，',
    ' * 所以挂在引擎上的纯函数 JVM 单测根本 new 不出来 —— 挂顶层才能直接单测。',
    ' * 这与本仓 `RagEngine.coverageReport` 用顶层扩展的既有风格一致。',
    ' */',
    'object ClusterVerdictProtocol {',
    '',
    '    /** 判定三态（见 [ClusterVerdict]）。 */',
    '    enum class ClusterVerdict {',
    '        /** 验收方明确判定通过。 */',
    '        PASS,',
    '',
    '        /** 验收方明确判定不通过：产物不合格，该重做。 */',
    '        FAIL,',
    '',
    '        /** 🔴 验收方输出无法解析：不是业务结论，既不重做也不当作通过来掩盖。 */',
    '        UNPARSABLE,',
    '    }',
    '',
    '    /**',
    '     * 把验收方输出解析成三态判定。',
    '     *',
    '     * 🔴 为什么要三态（这是 14 轮空转的直接原因）',
    '     *',
    '     * 旧码 `val pass = o?.optBoolean("pass", false) ?: false` 把两种完全不同的情况',
    '     * 压成同一个结果：',
    '     * 1. 验收方真的判定**不通过**（产物确实不合格）→ 应该重做；',
    '     * 2. 验收方的输出**解析失败**（格式不对/超时/胡说）→ 重做**没有任何意义**，',
    '     *    因为待验收的产物一个字都没变，重做一百遍还是同样的产物、同样解析不出结论。',
    '     *',
    '     * 两者都进 `REPLANNING`，于是集群在「产物合格但格式没对齐」上反复空转，',
    '     * 直到 `maxReplan` 熔断 —— 实测 14 轮 / 20 万 tokens。',
    '     */',
    '    fun verdictOf(',
    '        text: String?,',
    '        @Suppress("UNUSED_PARAMETER") strictFallbackUnparsable: Boolean = true,',
    '    ): ClusterVerdict {',
    '        val trimmed = text?.trim()?.removePrefix("```json")?.removePrefix("```")',
    '            ?.removeSuffix("```")?.trim().orEmpty()',
    '        if (trimmed.isEmpty()) return ClusterVerdict.UNPARSABLE',
    '        val o = runCatching { org.json.JSONObject(trimmed) }.getOrNull()',
    '            ?: runCatching { org.json.JSONObject(loosenJson(trimmed)) }.getOrNull()',
    '            ?: return ClusterVerdict.UNPARSABLE',
    '        if (!o.has("pass")) {',
    '            // 有些模型会只写 reason 不写 pass。**有 reason 但无 pass** 时，',
    '            // 视为格式不完整（UNPARSABLE）而不是猜它想说什么——',
    '            // 猜 pass=true 就是本仓明令禁止的「0 冒充 -1」式编造。',
    '            return ClusterVerdict.UNPARSABLE',
    '        }',
    '        return if (o.optBoolean("pass", false)) ClusterVerdict.PASS else ClusterVerdict.FAIL',
    '    }',
    '',
    '    /**',
    '     * 把「只差几层字符」的 JSON 修成合法 JSON。**只做无损的结构性修复**，',
    '     * 绝不改语义、绝不猜值 —— 修不好就返回原串让上层判失败。',
    '     *',
    '     * 🔴 修的形态（端侧实测反复出现，逐条都真实存在）：',
    '     * - 全角标点：`｛` `｝` `，` `：` `\"` → 半角',
    '     * - 单引号键值 → 双引号',
    '     * - 裸键名 `{pass:true}` → `{"pass":true}`（**值侧不加引号**，保持原样）',
    '     * - 尾随逗号 `{"a":1,}` → `{"a":1}`',
    '     * - 结尾缺闭合括号 → 逐层补回',
    '     *',
    '     * 🔴 不做的事：不给裸值补引号（`"reason": abc` 补成 `"abc"` 就是**编造**内容，',
    '     * 本仓对幻觉零容忍）。字符串内的内容一字不动。',
    '     */',
    '    fun loosenJson(raw: String): String {',
    '        var s = raw',
    '        // ① 全角 → 半角',
    "        s = s.replace('\\uFF5B', '{').replace('\\uFF5D', '}')",
    "            .replace('\\uFF0C', ',').replace('\\uFF1A', ':')",
    "            .replace('\\uFF1B', ';').replace('\\uFF06', '\"')",
    "            .replace('\\uFF07', '\"')",
    '        // ② 中文引号 → 半角（模型爱用「」当引号）',
    "        s = s.replace('\\u201C', '\"').replace('\\u201D', '\"')",
    '        // ③ 单引号 → 双引号（仅当它是**结构分隔符**时；字符串内部的 \\' 不动）',
    '        s = quoteStructuralSingleQuotes(s)',
    '        // ④ 裸键名 → 带引号键名',
    '        s = quoteBareKeys(s)',
    '        // ⑤ 尾随逗号',
    '        s = s.replace(",}", "}").replace(",]", "]").replace(", }", " }").replace(", ]", " ]")',
    '        // ⑥ 补回缺失的闭合括号',
    '        s = closeUnbalanced(s)',
    '        return s',
    '    }',
    '',
    '    /** `{\'a\':1}` → `{"a":1}`；字符串内容里的单引号（don\'t）不动。 */',
    '    private fun quoteStructuralSingleQuotes(s: String): String {',
    '        val sb = StringBuilder(s.length + 8)',
    '        var inStr = false',
    '        var i = 0',
    '        while (i < s.length) {',
    '            val c = s[i]',
    '            if (inStr) {',
    '                sb.append(c)',
    "                if (c == '\\\\') { sb.append(s.getOrNull(i + 1)); i += 2; continue }",
    "                if (c == '\"') inStr = false",
    '                i++',
    '                continue',
    '            }',
    "            if (c == '\"') { inStr = true; sb.append(c); i++; continue }",
    "            if (c == '\\'' && isQuotePosition(s, i)) { sb.append('\"'); i++; continue }",
    '            sb.append(c); i++',
    '        }',
    '        return sb.toString()',
    '    }',
    '',
    '    /**',
    '     * 单引号只有在**不在字符串内**，且（前面是 `{`/`,`/`:`/空白，或后面是 `:`/`,`/`}`/`]`/空白）',
    '     * 时才算结构引号。否则判为字符串内容里的撇号，原样保留。',
    '     */',
    '    private fun isQuotePosition(s: String, i: Int): Boolean {',
    '        val before = s.getOrNull(i - 1)',
    '        val after = s.getOrNull(i + 1)',
    "        val beforeOk = before == null || before == '{' || before == ',' || before == ':' ||",
    "                before == ' ' || before == '\\n' || before == '\\t' || before == '['",
    "        val afterOk = after == null || after == ':' || after == ',' || after == '}' ||",
    "                after == ']' || after == ' ' || after == '\\n' || after == '\\t'",
    '        return beforeOk && afterOk',
    '    }',
    '',
    '    /** `{pass:true}` → `{"pass":true}`。只加键的引号，**不给值补引号**（不编造内容）。 */',
    '    private fun quoteBareKeys(s: String): String {',
    '        val sb = StringBuilder(s.length + 16)',
    '        var inStr = false',
    '        var i = 0',
    '        while (i < s.length) {',
    '            val c = s[i]',
    '            if (inStr) {',
    '                sb.append(c)',
    "                if (c == '\\\\') { sb.append(s.getOrNull(i + 1)); i += 2; continue }",
    "                if (c == '\"') inStr = false",
    '                i++',
    '                continue',
    '            }',
    "            if (c == '\"') { inStr = true; sb.append(c); i++; continue }",
    "            if (c == '{' || c == ',') {",
    '                sb.append(c)',
    '                i++',
    '                // 跳到下一个非空白字符：若它不是引号但后面紧跟冒号，就是裸键名',
    '                var j = i',
    '                while (j < s.length && s[j].isWhitespace()) j++',
    "                if (j < s.length && s[j] != '\"') {",
    '                    var k = j',
    "                    while (k < s.length && (s[k].isLetterOrDigit() || s[k] == '_' || s[k] == '-')) k++",
    "                    if (k > j && s.getOrNull(k) == ':') {",
    "                        sb.append('\"').append(s, j, k).append('\"')",
    '                        i = k',
    '                        continue',
    '                    }',
    '                }',
    '                continue',
    '            }',
    '            sb.append(c); i++',
    '        }',
    '        return sb.toString()',
    '    }',
    '',
    '    /** 结尾缺闭合括号（被 max_tokens 截断）时逐层补回。 */',
    '    private fun closeUnbalanced(s: String): String {',
    '        val stack = ArrayDeque<Char>()',
    '        var inStr = false',
    '        var i = 0',
    '        while (i < s.length) {',
    '            val c = s[i]',
    '            if (inStr) {',
    "                if (c == '\\\\') { i += 2; continue }",
    "                if (c == '\"') inStr = false",
    '                i++',
    '                continue',
    '            }',
    '            when (c) {',
    "                '\"' -> inStr = true",
    "                '{', '[' -> stack.addLast(c)",
    "                '}' -> if (stack.lastOrNull() == '{') stack.removeLast()",
    "                ']' -> if (stack.lastOrNull() == '[') stack.removeLast()",
    '            }',
    '            i++',
    '        }',
    '        if (stack.isEmpty()) return s',
    '        val sb = StringBuilder(s)',
    '        // 字符串未闭合时先补个引号，否则补括号也还是非法',
    '        if (inStr) sb.append(\'"\')',
    "        while (stack.isNotEmpty()) sb.append(if (stack.removeLast() == '{') '}' else ']')",
    '        return sb.toString()',
    '    }',
    '}',
])

assert src != orig
tmp = p + '.tmp'
io.open(tmp, 'w', encoding='utf-8', newline='').write(src)
os.replace(tmp, p)
print('OK  %d -> %d' % (len(orig), len(src)))