package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.QuroToolCall

/**
 * 端侧工具调用**容错解析层**（N10）。
 *
 * ## 为什么需要单独一层
 *
 * 端侧模型（MNN / llama.cpp 上的 1B~8B 量化模型）与云端模型最大的差别不是"不会调工具"，
 * 而是"调工具的**书写**极不规范"。实测常见形态：
 *
 * ```
 * <tool_call>{name: get_current_time, arguments: {}}</tool_call>                     ← 裸键名 + 裸值
 * <tool_call>{'name':'read_file','arguments':{'path':'/sdcard/a.txt'}}</tool_call>  ← 单引号
 * <tool_call>{"name":"read_file","arguments":{"path":"/a.txt",}}</tool_call>        ← 尾随逗号 + 缺 }
 * <tool_call>read_file(path="/a.txt")</tool_call>                                   ← 函数式调用
 * <tool_call>{"name":"read_file","path":"/a.txt"}</tool_call>                       ← 参数摊平在顶层
 * [TOOL_CALLS] [{"name":"get_battery","arguments":{}}]                              ← Mistral 风格
 * <tool_call>{"name":"get_current_time","arguments":{}}</tool_call   ← 缺闭合标签（被 max_tokens 截断）
 * <tool_call>{"name":"get_current_time","arguments":{}}             ← 上一行 + 缺 }
 * ```
 *
 * 旧实现（[QuroLocalToolsCodec.parseDetailed] 的原逻辑）只认**严格合法 JSON**：先用正则找
 * `<tool_call>`…`</tool_call>`，再对段内做花括号配对扫描，最后交给 `JSONObject()`。
 * 上面这些形态**全部落空**，而且三条路径连失败原因都记不下来（它们压根没找到候选），
 * 于是上层只能给出那句无用的兜底文案：
 * 「文本中出现工具调用特征，但没有找到可解析的 JSON 对象」。
 *
 * 用户看到的后果就是：**模型明明想调工具，却什么都不发生**，气泡里还留着一坨
 * `<tool_call>` 原文（"工具调用在对话框显示在标签"）。
 *
 * ## 本层的策略
 *
 * 不复用"修文本再喂 JSONObject"的脆链路，而是写一个**宽松 JSON 解析器**
 * （[LooseJson]）：它直接把"像 JSON 的东西"解析成树，天然容忍裸键名、单引号、
 * 尾随逗号、缺闭合括号、字符串里裸换行、全角标点、`True/None/undefined`。
 * 树解析成功后再由本层自己序列化成规范 JSON 字符串。
 *
 * 在此之上再补三类**结构化**兜底（不是正则拼接）：
 * 1. 多标签族：`<tool_call>` / `<tool_calls>` / `<function_call>` / `<|tool_call|>` / `[TOOL_CALLS]`；
 * 2. 参数形态归一：`arguments` / `parameters` / `args` / `input` / 参数摊平到顶层；
 * 3. **工具名纠错**：模型把 `get_current_time` 写成 `get_current_times`、`GetCurrentTime`
 *    这类小偏差，按已知工具名做归一化匹配 + 编辑距离纠正，而不是直接判"工具不存在"。
 *
 * ## 为什么"工具名纠错"是必须的而不是可选的
 *
 * 端侧模型对下发的工具名几乎没有逐字复制能力，尤其在下划线/大小写/复数上反复出错。
 * 一个字符的偏差在下游就是 `NOT_FOUND`，整个任务链断掉。而工具的**语义**是明确的、
 * 候选集是**封闭**的（就是本轮下发的这十几个），所以做最近邻纠正是有理论依据的，
 * 不是猜。
 *
 * @see QuroLocalToolsCodec
 */
object QuroToolCallRepair {

    /**
     * 解析结果。
     *
     * @property calls 解析出的工具调用；空列表表示本轮没有可执行的调用。
     * @property sawMarker 文本中是否出现过工具调用特征（用于区分"模型没想调工具"
     *   与"想调但没解析出来"——这两种情况的用户提示完全不同）。
     * @property repairs 本次实际应用过的修复动作（诊断用；正常输出为空列表）。
     * @property diagnostic 解析过程中值得记录的情况：成功时是**修复动作**摘要
     *   （如"检测到未闭合的 &lt;tool_call&gt;"），失败时是**失败原因**。
     *   完全干净的一次解析为 null。
     */
    data class Outcome(
        val calls: List<QuroToolCall>,
        val sawMarker: Boolean,
        val repairs: List<String> = emptyList(),
        val diagnostic: String? = null,
    ) {
        /** 是否从"不规范输出"中抢救出了可执行的调用。 */
        val recovered: Boolean get() = calls.isNotEmpty()
    }

    /** 单个候选片段最多扫描的字符数，防异常长输出把 CPU 打满（模型输出受 max_tokens 约束，正常远小于此）。 */
    private const val MAX_SCAN_CHARS = 200_000

    /** 未闭合标签的诊断文案（既有上层契约依赖其中的「未闭合」三字，勿改措辞）。 */
    private const val NOTE_UNCLOSED = "检测到未闭合的 <tool_call>（生成可能被最大长度截断），已尽力解析"

    /** 标签族：(开标签, 闭标签)。顺序即优先级——先窄后宽。 */
    private val TAG_PAIRS: List<Pair<String, String>> = listOf(
        "<tool_call>" to "</tool_call>",
        "<function_call>" to "</function_call>",
        "<|tool_call|>" to "<|/tool_call|>",
        "<tool_call_begin>" to "<tool_call_end>",
        "<tool_calls>" to "</tool_calls>",
        "<function_calls>" to "</function_calls>",
    )

    /** Mistral / DeepSeek 风格的整体哨兵标签（其后到文本结尾都是调用内容）。 */
    private const val TOOL_CALLS_SENTINEL = "[TOOL_CALLS]"

    /** 出现即视为"模型想调工具"的强特征标签（不含 `{`/`[` 这类弱特征）。 */
    private val STRONG_MARKERS: List<String> = listOf(
        "<tool_call>", "<tool_calls>", "<function_call>", "<function_calls>",
        "<|tool_call|>", "<tool_call_begin>", TOOL_CALLS_SENTINEL,
        "\"tool_calls\"", "'tool_calls'",
    )

    /** `arguments` 字段的各种别名，按优先级排列。 */
    private val ARG_ALIASES: List<String> = listOf(
        "arguments", "parameters", "args", "params", "input",
        "action_input", "tool_input", "function_arguments", "argument",
    )

    /**
     * 对象里**不是工具参数**的键。
     *
     * ⚠️ 必须含 [ARG_ALIASES]：否则 `{"arguments":{"a":1}}`（模型漏写 name）会被
     * "工具名作键"策略误当成「工具名 = arguments」，产出一个名叫 `arguments` 的假工具调用
     * ——比解析失败更糟，因为下游会真的去执行它。
     */
    private val RESERVED_KEYS: Set<String> =
        setOf("name", "tool_name", "tool", "type", "id", "function", "index") + ARG_ALIASES

    /** 被视为"非字符串字面量"的裸词（宽松解析时不做补引号处理）。 */
    private val LITERAL_WORDS: Set<String> = setOf(
        "true", "false", "null", "none", "undefined", "nan", "inf", "-inf", "infinity",
    )

    // ---------------------------------------------------------------------------------------
    // 对外入口
    // ---------------------------------------------------------------------------------------

    /**
     * 从模型原始输出中提取工具调用。
     *
     * @param raw 模型原始输出（可能夹带正文、思考段、Markdown 围栏）。
     * @param knownNames 本轮**实际下发**的工具名集合；非空时启用名称纠错。
     *   传空集合表示"不纠正，完全按模型给的名字走"。
     * @return 解析结果；本函数**绝不抛异常**。
     */
    fun extract(raw: String, knownNames: Collection<String> = emptyList()): Outcome {
        if (raw.isBlank()) return Outcome(emptyList(), sawMarker = false)

        val text = if (raw.length > MAX_SCAN_CHARS) raw.take(MAX_SCAN_CHARS) else raw
        val repairs = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        val sawMarker = detectMarker(text)

        // 按"候选类别"逐层尝试：同一层内的候选**全部**处理完才判断成败。
        // 逐候选 break 会漏掉多段 `<tool_call>`（只解析第一个就返回），
        // 那是并行工具调用的常规形态，绝不能丢。
        var calls: List<QuroToolCall> = emptyList()
        for (klass in candidateClasses(text, repairs)) {
            val acc = mutableListOf<QuroToolCall>()
            for (candidate in klass) {
                callsFromJsonish(candidate, acc.size, repairs, reasons)?.let { acc.addAll(it) }
            }
            if (acc.isNotEmpty()) {
                calls = acc
                break
            }
        }

        if (calls.isEmpty()) {
            val diagnostic = when {
                reasons.isNotEmpty() -> reasons.distinct().joinToString("；")
                sawMarker -> describeFailure(text)
                else -> null
            }
            return Outcome(emptyList(), sawMarker, repairs.distinct(), diagnostic)
        }

        val corrected = calls.map { applyNameCorrection(it, knownNames, repairs) }
        val deduped = dedupeByIdentity(corrected)
        val notes = repairs.distinct()
        return Outcome(
            calls = deduped,
            sawMarker = true,
            repairs = notes,
            // 成功时 diagnostic 承载"修复动作"：上层把它写进诊断日志，
            // 是判断"这次调用到底是干净产出还是被抢救回来的"的唯一依据。
            diagnostic = notes.takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
    }

    /**
     * 从可见正文中**剥离全部工具调用标记**，保证 `<tool_call>` 这类原文绝不上屏。
     *
     * 这是"工具调用在对话框显示在标签"的直接修复点。旧实现在解析失败时把原文原样
     * 推给 UI 再追加一句警告，于是用户看到的是「一坨 JSON + 一句抱歉」。
     *
     * ⚠️ 只剥"能确证是工具调用"的片段，保守优先：
     * · 命中 [TAG_PAIRS] 的成对标签（含只开不闭的截断层，剥到文本结尾）；
     * · `[TOOL_CALLS]` 之后的全部内容；
     * · 围栏代码块，且块内容**本身就是一个可解析的调用对象**（普通 ```json 数据块不动）。
     *
     * @param raw 面向用户的候选正文。
     * @return 剥离后的正文（已 trim、已折叠 3 行以上空行）；无可剥离内容时原样返回。
     */
    fun sanitizeVisible(raw: String): String {
        if (raw.isBlank()) return raw
        if (!detectMarker(raw)) return raw

        var out = raw

        // 1) 成对/未闭合标签：含只开不闭（被 max_tokens 截断）的形态，剥到文本结尾。
        //    先剥内层（tool_call）再剥外层（tool_calls），否则外层先吃掉整段，
        //    内层标签的剥离就变成了空操作——结果一样，但诊断不再准确。
        for ((open, close) in TAG_PAIRS) {
            out = stripTaggedSpans(out, open, close)
        }

        // 2) [TOOL_CALLS] 哨兵：其后到结尾整体是调用体。
        val sentinelIdx = out.indexOf(TOOL_CALLS_SENTINEL, ignoreCase = true)
        if (sentinelIdx >= 0) out = out.substring(0, sentinelIdx)

        // 3) 围栏代码块：仅当块内容确实是工具调用时才剥，避免误删正文里的 JSON 示例。
        out = stripFencedCallBlocks(out)

        // 4) 清理残留的孤立标签（模型把开闭标签写反/重复时的兜底）。
        for ((open, close) in TAG_PAIRS) {
            out = out.replace(open, " ").replace(close, " ")
        }

        // 5) 折叠空行：剥掉片段后常留下连续空行，UI 上就是一片莫名空白。
        out = out.replace(Regex("[ \\t]+\\n"), "\n")
        out = out.replace(Regex("\\n{3,}"), "\n\n")

        return out.trim()
    }

    // ---------------------------------------------------------------------------------------
    // 候选收集
    // ---------------------------------------------------------------------------------------

    /**
     * 按优先级把候选片段分成若干**类别**，逐类尝试、首个产出的类别胜出。
     *
     * 分级而不是拉平成一个列表，是因为不同类别的"可信度"差别极大：
     * 标签内的内容几乎必然是调用体，而正文里裸的 `xxx(yyy)` 极可能是噪声。
     * 拉平后一旦低可信类别先命中，就会用误触发覆盖掉高可信类别。
     */
    private fun candidateClasses(text: String, repairs: MutableList<String>): List<List<String>> {
        val unfenced = stripFences(text)
        val trimmed = unfenced.trim()
        val classes = mutableListOf<List<String>>()

        // ① [TOOL_CALLS] 哨兵之后整体
        val sentinelIdx = text.indexOf(TOOL_CALLS_SENTINEL, ignoreCase = true)
        if (sentinelIdx >= 0) {
            val tail = text.substring(sentinelIdx + TOOL_CALLS_SENTINEL.length).trim()
            if (tail.isNotEmpty()) {
                repairs.add("识别 [TOOL_CALLS] 哨兵")
                classes.add(listOf(tail))
            }
        }

        // ② 各标签族的段内容（多段全部保留）
        val fromTags = mutableListOf<String>()
        for ((open, close) in TAG_PAIRS) {
            fromTags.addAll(taggedSegments(text, open, close, repairs))
        }
        val nonBlankTags = fromTags.filter { it.isNotBlank() }
        if (nonBlankTags.isNotEmpty()) classes.add(nonBlankTags)

        // ③ 整段就是一个 JSON 对象/数组（含 ```json 围栏包裹）
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        ) {
            classes.add(listOf(trimmed))
        }

        // ④ 正文夹带的对象：`好的，我来调用工具：{"name":"get_battery","arguments":{}}`
        //    这是端侧模型的高频形态（先解释一句再给 JSON），没有标签也没有围栏，
        //    整体又不是纯 JSON，靠"首尾是 { }"判不出来。
        //    ⚠️ 判定收紧：必须**同时**出现 name 类键与 arguments 类键才收，否则会把模型
        //    正文里正常返回的结构化数据误判成工具调用（误触发的危害大于漏触发）。
        val fromProse = braceObjectCandidates(unfenced).filter { looksLikeCallObject(it) }
        if (fromProse.isNotEmpty()) classes.add(fromProse)

        // ⑤ 函数式调用 name(args)：优先级最低——正文里"函数名(参数)"样的普通文本
        //    极易被误当调用，只在前面全部落空时才尝试。
        val fromFunctions = functionSyntaxSegments(unfenced)
        if (fromFunctions.isNotEmpty()) classes.add(fromFunctions)

        return classes
    }

    /** 取文本中某个标签族的所有段内容（闭标签缺失时，取到文本结尾并留下诊断）。 */
    private fun taggedSegments(
        text: String,
        open: String,
        close: String,
        repairs: MutableList<String>,
    ): List<String> {
        val out = mutableListOf<String>()
        var cursor = 0
        while (cursor < text.length) {
            val openIdx = text.indexOf(open, cursor, ignoreCase = true)
            if (openIdx < 0) break
            val contentStart = openIdx + open.length
            val closeIdx = text.indexOf(close, contentStart, ignoreCase = true)
            if (closeIdx >= 0) {
                out.add(text.substring(contentStart, closeIdx))
                cursor = closeIdx + close.length
            } else {
                if (open == "<tool_call>") repairs.add(NOTE_UNCLOSED)
                out.add(text.substring(contentStart))
                break
            }
        }
        return out
    }

    /** 字符串感知的花括号配对扫描，返回所有**顶层**对象文本。 */
    private fun braceObjectCandidates(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var quote = ' '
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> inString = false
                }
                continue
            }
            when (c) {
                '"', '\'' -> {
                    inString = true; quote = c
                }

                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }

                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(text.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }

    /** 对象文本是否"看起来是一个工具调用"（name 键 + arguments 键同时存在）。 */
    private fun looksLikeCallObject(obj: String): Boolean {
        val hasName = obj.contains("\"name\"") || obj.contains("'name'") ||
            obj.contains("name:") || obj.contains("tool_name")
        if (!hasName) return false
        return ARG_ALIASES.any { alias ->
            obj.contains("\"$alias\"") || obj.contains("'$alias'") || obj.contains("$alias:")
        }
    }

    /** 从文本中摘出"函数式调用"片段：`name(...)`，支持嵌套括号与 kwargs。 */
    private fun functionSyntaxSegments(text: String): List<String> {
        val out = mutableListOf<String>()
        val re = Regex("[A-Za-z_][A-Za-z0-9_.]*\\s*\\([^()]*(?:\\([^()]*\\)[^()]*)*\\)")
        for (m in re.findAll(text)) {
            val whole = m.value.trim()
            // 过滤掉正文里常见的 `xxx(yyy)` 噪声：必须含 `=` 参数、或参数为空、或参数是 JSON。
            val argPart = whole.substringAfter('(', "").substringBeforeLast(')').trim()
            val looksLikeCall = argPart.isEmpty() ||
                argPart == "{}" ||
                argPart.startsWith("{") ||
                argPart.contains("=")
            if (looksLikeCall) out.add(whole)
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // 解析单个候选
    // ---------------------------------------------------------------------------------------

    /**
     * 把一个候选片段解析成工具调用列表。
     *
     * 支持顶层是：单个对象 / 对象数组 / `{"tool_calls":[...]}` 信封 / 函数式调用。
     *
     * @param reasons 失败原因收集器（写进 [Outcome.diagnostic]，必须具体到"哪一步不行"）。
     * @return 解析出的调用；返回 null 表示该候选不是合法调用体。
     */
    private fun callsFromJsonish(
        candidate: String,
        indexBase: Int,
        repairs: MutableList<String>,
        reasons: MutableList<String>,
    ): List<QuroToolCall>? {
        val trimmed = candidate.trim()
        if (trimmed.isEmpty()) return null

        // 函数式调用：name(args) / name(key=value, ...)
        parseFunctionSyntax(trimmed, indexBase, repairs)?.let { return it }

        val parsed = LooseJson(trimmed, repairs).parse()
        if (parsed == null) {
            reasons.add("工具调用体为空或无法识别")
            return null
        }
        if (parsed is String || parsed is Number || parsed is Boolean) {
            reasons.add("工具调用体不是 JSON 对象（实际是裸文本）")
            return null
        }

        val roots = when (parsed) {
            is List<*> -> parsed
            is Map<*, *> -> {
                val envelope = parsed["tool_calls"] ?: parsed["toolCalls"] ?: parsed["calls"]
                if (envelope is List<*>) {
                    repairs.add("识别 tool_calls 信封")
                    envelope
                } else {
                    listOf(parsed)
                }
            }

            else -> return null
        }

        val calls = mutableListOf<QuroToolCall>()
        for ((i, item) in roots.withIndex()) {
            val map = item as? Map<*, *> ?: continue
            callFromMap(map, indexBase + i, repairs, reasons)?.let { calls.add(it) }
        }
        return calls.ifEmpty { null }
    }

    /** 把一个 Map 形式的调用对象转成 [QuroToolCall]；缺 name 时返回 null 并记录原因。 */
    private fun callFromMap(
        map: Map<*, *>,
        index: Int,
        repairs: MutableList<String>,
        reasons: MutableList<String>,
    ): QuroToolCall? {
        val explicitId = map["id"]?.toString()?.trim().orEmpty()

        // 形态 A：{"name": "...", "arguments": {...}}；也兼容再包一层 "function"。
        val inner = (map["function"] as? Map<*, *>) ?: map
        var name = (inner["name"] ?: map["name"] ?: map["tool_name"] ?: map["tool"])
            ?.toString()?.trim().orEmpty()

        // 形态 B：{"get_current_time": {...}}（工具名当键）
        // 必须排除 RESERVED_KEYS，否则 `{"arguments":{...}}` 会产出一个名叫 arguments 的假工具。
        if (name.isEmpty() && inner === map && map.size == 1) {
            val onlyKey = map.keys.firstOrNull()?.toString().orEmpty()
            if (onlyKey.isNotBlank() && onlyKey !in RESERVED_KEYS && map[onlyKey] is Map<*, *>) {
                repairs.add("识别「工具名作键」形态")
                val body = map[onlyKey] as Map<*, *>
                return QuroToolCall(
                    id = explicitId.ifBlank { "call_local_$index" },
                    name = normalizeToolName(onlyKey, repairs),
                    arguments = serializeArguments(body),
                )
            }
        }

        if (name.isEmpty()) {
            reasons.add("工具调用缺少 name 字段")
            return null
        }
        name = normalizeToolName(name, repairs)

        val argsValue = pickArguments(inner) ?: pickArguments(map)
        val arguments = when (argsValue) {
            null -> flattenRemaining(inner, name, repairs)
            else -> serializeArguments(argsValue)
        }
        return QuroToolCall(
            id = explicitId.ifBlank { "call_local_$index" },
            name = name,
            arguments = arguments,
        )
    }

    /** 按别名取 arguments 字段。 */
    private fun pickArguments(map: Map<*, *>): Any? {
        for (alias in ARG_ALIASES) {
            val v = map[alias] ?: continue
            if (v !is String || v.isNotBlank()) return v
        }
        return null
    }

    /**
     * 参数摊平形态：`{"name":"read_file","path":"/a.txt"}`。
     *
     * 这是端侧小模型极高频的写法（把工具名和参数平铺在一个对象里）。旧实现只认
     * `arguments` 字段，这类输出即使 JSON 合法也会被解析成"零参数调用"，
     * 下游报一句"缺少必填参数 path"，用户完全看不懂。
     */
    private fun flattenRemaining(map: Map<*, *>, name: String, repairs: MutableList<String>): String {
        val rest = LinkedHashMap<String, Any?>()
        for ((k, v) in map) {
            val key = k?.toString() ?: continue
            if (key in RESERVED_KEYS) continue
            if (key == name) continue
            rest[key] = v
        }
        if (rest.isEmpty()) return "{}"
        repairs.add("参数摊平在顶层，已聚合为 arguments")
        return serializeMap(rest)
    }

    /**
     * 把 arguments 值序列化成 JSON 对象字符串。
     *
     * 字符串形态会再尝试解析一次（模型双编码：`"{\"path\":\"/a.txt\"}"`）。
     * ⚠️ 已经是"宽松合法"的 JSON 文本时**原样返回**，不做重新序列化——
     * 重新序列化会改变字节（空格/数字格式），破坏上层"透传模型原始 arguments"的契约，
     * 也会让响应缓存/日志比对失去意义。
     */
    private fun serializeArguments(value: Any?): String = when (value) {
        null -> "{}"
        is Map<*, *> -> serializeMap(value)
        is List<*> -> serializeList(value)
        is String -> {
            val t = value.trim()
            when {
                t.isEmpty() -> "{}"
                t.startsWith("{") || t.startsWith("[") -> {
                    val sub = mutableListOf<String>()
                    when (val parsed = LooseJson(t, sub).parse()) {
                        is Map<*, *> -> if (sub.isEmpty()) t else serializeMap(parsed)
                        is List<*> -> if (sub.isEmpty()) t else serializeList(parsed)
                        else -> "{}"
                    }
                }

                else -> "{}"
            }
        }

        else -> "{}"
    }

    /** 工具名归一：去空白、去 `functions.` 前缀、去引号。 */
    private fun normalizeToolName(raw: String, repairs: MutableList<String>): String {
        var n = raw.trim().trim('"', '\'', '`', ' ')
        if (n.startsWith("functions.")) {
            n = n.removePrefix("functions.")
            repairs.add("剥离 functions. 前缀")
        }
        // 有些模型会把名字写成 `name: get_time` 这种半截键值。
        if (n.contains(":")) n = n.substringAfterLast(':').trim()
        return n
    }

    /** 函数式调用解析：`name()` / `name({...})` / `name(path="/a.txt", n=1)`。 */
    private fun parseFunctionSyntax(
        text: String,
        indexBase: Int,
        repairs: MutableList<String>,
    ): List<QuroToolCall>? {
        val m = Regex("^([A-Za-z_][A-Za-z0-9_.]*)\\s*\\((.*)\\)$", RegexOption.DOT_MATCHES_ALL)
            .find(text) ?: return null
        val name = normalizeToolName(m.groupValues[1], repairs)
        if (name.isBlank()) return null
        val argText = m.groupValues[2].trim()

        val args = when {
            argText.isEmpty() -> "{}"
            argText.startsWith("{") || argText.startsWith("[") -> serializeArguments(argText)
            else -> {
                // Python kwargs 形态：path="/a.txt", encoding='utf-8'
                val map = LinkedHashMap<String, Any?>()
                for (part in splitTopLevel(argText, ',')) {
                    val eq = part.indexOf('=')
                    if (eq <= 0) continue
                    val k = part.substring(0, eq).trim().trim('"', '\'', ' ')
                    val rawV = part.substring(eq + 1).trim()
                    map[k] = looseScalar(rawV)
                }
                if (map.isEmpty()) return null
                serializeMap(map)
            }
        }
        repairs.add("识别函数式调用 name(args)")
        return listOf(QuroToolCall(id = "call_local_$indexBase", name = name, arguments = args))
    }

    /** 把一个裸标量文本还原成合适的类型（数字/布尔/null 保持字面量，其余当字符串）。 */
    private fun looseScalar(raw: String): Any? {
        val t = raw.trim().trim('"', '\'', '`')
        if (t.isEmpty()) return ""
        if (t.lowercase() in LITERAL_WORDS) {
            return when (t.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
        t.toLongOrNull()?.let { return it }
        t.toDoubleOrNull()?.let { return it }
        return t
    }

    /** 按顶层分隔符切分（忽略括号/引号内部的分隔符）。 */
    private fun splitTopLevel(text: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var quote: Char? = null
        var escaped = false
        val sb = StringBuilder()
        for (c in text) {
            when {
                escaped -> {
                    sb.append(c); escaped = false
                }

                quote != null -> {
                    sb.append(c)
                    if (c == '\\') escaped = true
                    else if (c == quote) quote = null
                }

                c == '"' || c == '\'' -> {
                    quote = c; sb.append(c)
                }

                c == '{' || c == '[' || c == '(' -> {
                    depth++; sb.append(c)
                }

                c == '}' || c == ']' || c == ')' -> {
                    depth--; sb.append(c)
                }

                c == sep && depth == 0 -> {
                    out.add(sb.toString()); sb.clear()
                }

                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }

    // ---------------------------------------------------------------------------------------
    // 名称纠错
    // ---------------------------------------------------------------------------------------

    /**
     * 按已知工具名纠正模型的书写偏差。
     *
     * 三级匹配：完全一致 → 归一化一致（忽略大小写/下划线/连字符/点）→ 编辑距离最近。
     * 全部落空时**保留模型原样**（由引擎报「工具不存在」），绝不瞎猜一个不相干的工具。
     */
    private fun applyNameCorrection(
        call: QuroToolCall,
        knownNames: Collection<String>,
        repairs: MutableList<String>,
    ): QuroToolCall {
        if (knownNames.isEmpty() || call.name in knownNames) return call

        val fixed = nearestKnownName(call.name, knownNames) ?: return call
        repairs.add("工具名纠错：${call.name} → $fixed")
        return call.copy(name = fixed)
    }

    /** 找到与 [name] 最接近的已知工具名；无合理候选返回 null。 */
    internal fun nearestKnownName(name: String, knownNames: Collection<String>): String? {
        val target = name.trim()
        if (target.isEmpty()) return null

        knownNames.firstOrNull { it.equals(target, ignoreCase = true) }?.let { return it }

        val normTarget = normalizeForCompare(target)
        knownNames.firstOrNull { normalizeForCompare(it) == normTarget }?.let { return it }

        // 编辑距离：阈值随长度放宽，但最多 3，避免短名之间互相误吸。
        val limit = minOf(3, maxOf(1, target.length / 4))
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        var tie = false
        for (candidate in knownNames) {
            val d = levenshtein(normTarget, normalizeForCompare(candidate))
            if (d > limit) continue
            when {
                d < bestDist -> {
                    bestDist = d; best = candidate; tie = false
                }

                d == bestDist -> tie = true
            }
        }
        return if (tie) null else best
    }

    /** 归一化用于比较：小写 + 去掉所有分隔符。 */
    private fun normalizeForCompare(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    /** 标准 Levenshtein（滚动数组，O(min(n,m)) 空间）。 */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    // ---------------------------------------------------------------------------------------
    // 去重与诊断
    // ---------------------------------------------------------------------------------------

    /**
     * 按 (name, arguments) 去重，并为缺省 id 的调用编号。
     *
     * 必要性来自候选收集本身：`<tool_calls>` wrapper 与其中的 `<tool_call>` 会产出
     * 同一段 JSON 两次，不去重就会把一个调用执行两遍（对 `set_clipboard` / `send_message`
     * 这类有副作用的工具是真实伤害）。同参数重复调用在单轮内没有正当用途。
     *
     * ⚠️ 只给**空 id** 补号：信封形态里模型/原生层给的 `call_9` 必须原样保留，
     * 它是后续 `role=tool` 结果回填时的配对键。
     */
    private fun dedupeByIdentity(calls: List<QuroToolCall>): List<QuroToolCall> {
        val seen = HashSet<String>()
        val out = ArrayList<QuroToolCall>(calls.size)
        for (c in calls) {
            val key = c.name + "\u0000" + c.arguments
            if (!seen.add(key)) continue
            out.add(if (c.id.isBlank()) c.copy(id = "call_local_${out.size}") else c)
        }
        return out
    }

    /** 是否存在工具调用特征。 */
    private fun detectMarker(text: String): Boolean {
        for (m in STRONG_MARKERS) {
            if (text.contains(m, ignoreCase = true)) return true
        }
        val trimmed = text.trim()
        return (trimmed.startsWith("{") || trimmed.startsWith("[")) &&
            (trimmed.contains("\"name\"") || trimmed.contains("'name'") || trimmed.contains("name:"))
    }

    /** 给出"为什么没解析出来"的可读原因（带实际片段，否则用户无从下手）。 */
    private fun describeFailure(text: String): String {
        val snippet = text.trim().take(160).replace('\n', ' ')
        return "工具调用体无法解析（片段：$snippet）"
    }

    // ---------------------------------------------------------------------------------------
    // 正文清洗辅助
    // ---------------------------------------------------------------------------------------

    /** 剥掉某个标签族的全部成对片段（含只开不闭的截断尾）。 */
    private fun stripTaggedSpans(text: String, open: String, close: String): String {
        if (!text.contains(open, ignoreCase = true)) return text
        val out = StringBuilder()
        var cursor = 0
        while (cursor < text.length) {
            val openIdx = text.indexOf(open, cursor, ignoreCase = true)
            if (openIdx < 0) {
                out.append(text, cursor, text.length)
                break
            }
            out.append(text, cursor, openIdx)
            val contentStart = openIdx + open.length
            val closeIdx = text.indexOf(close, contentStart, ignoreCase = true)
            if (closeIdx < 0) break // 未闭合：其后全部视为调用体，丢弃
            cursor = closeIdx + close.length
        }
        return out.toString()
    }

    /** 剥掉"内容确实是工具调用"的围栏代码块。 */
    private fun stripFencedCallBlocks(text: String): String {
        val re = Regex("```[a-zA-Z]*\\s*([\\s\\S]*?)```")
        return re.replace(text) { m ->
            val body = m.groupValues[1].trim()
            // ⚠️ 括号必须先写全：`||` 优先级低于 `&&`，漏写括号会让"以 { 开头"的块
            //    绕过可解析性校验被无条件剥掉（连带删掉正文里正常的 JSON 示例）。
            val isCall = (body.startsWith("{") || body.startsWith("[")) &&
                callsFromJsonish(body, 0, mutableListOf(), mutableListOf())?.isNotEmpty() == true
            if (isCall) " " else m.value
        }
    }

    /** 去掉 Markdown 围栏，只留内容（候选收集用）。 */
    private fun stripFences(text: String): String =
        text.replace(Regex("```[a-zA-Z]*"), " ").replace("```", " ")

    // ---------------------------------------------------------------------------------------
    // 序列化
    // ---------------------------------------------------------------------------------------

    /** 把 Map 序列化成规范 JSON 对象字符串。 */
    private fun serializeMap(map: Map<*, *>): String {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in map) {
            val key = k?.toString() ?: continue
            if (!first) sb.append(',')
            first = false
            sb.append(escapeJsonString(key)).append(':').append(serializeValue(v))
        }
        return sb.append('}').toString()
    }

    /** 把 List 序列化成规范 JSON 数组字符串。 */
    private fun serializeList(list: List<*>): String =
        list.joinToString(prefix = "[", postfix = "]", separator = ",") { serializeValue(it) }

    /** 递归序列化任意宽松解析出的值。 */
    private fun serializeValue(v: Any?): String = when (v) {
        null -> "null"
        is Map<*, *> -> serializeMap(v)
        is List<*> -> serializeList(v)
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Number -> v.toString()
        else -> escapeJsonString(v.toString())
    }

    /** JSON 字符串转义。 */
    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    // ---------------------------------------------------------------------------------------
    // 宽松 JSON 解析器
    // ---------------------------------------------------------------------------------------

    /**
     * 宽松 JSON 解析器：把"像 JSON 的东西"解析成 `Map`/`List`/`String`/`Number`/`Boolean`/`null` 树。
     *
     * 与 `org.json` 的区别（也正是本层存在的理由）：
     * | 输入 | `JSONObject` | [LooseJson] |
     * |---|---|---|
     * | `{name: x}` | 抛异常 | `{"name":"x"}`（记「裸键名/裸值补引号」） |
     * | `{'a':1}` | 抛异常 | `{"a":1}`（记「单引号归一」） |
     * | `{"a":1,}` | 抛异常 | `{"a":1}`（记「剔除尾随逗号」） |
     * | `{"a":{"b":1}` | 抛异常 | 自动补 `}`（记「补齐缺失的闭合括号」） |
     * | `{"a":"多\n行"}` | 抛异常 | 接受裸换行（记「字符串内裸换行已转义」） |
     * | `True` / `None` / `undefined` | 抛异常 | `true` / `null` / `null` |
     * | 全角 `：，｛｝""` | 抛异常 | 先做宽度归一 |
     *
     * 解析**只在 EOF 处收束**：任何位置遇到意外字符都尽量"跳过并继续"，而不是抛异常
     * ——因为这里处理的本来就是模型的畸形输出，"多抢救一个调用"远比"报错准确"有价值。
     *
     * @param repairs 修复动作收集器（外部传入，用于诊断）。
     */
    private class LooseJson(private val src: String, private val repairs: MutableList<String>) {

        private val s: String = normalizeWidth(src)
        private var i = 0

        /** 记录一次修复（去重由调用方 `distinct()` 负责）。 */
        private fun note(msg: String) {
            repairs.add(msg)
        }

        /** 解析入口；解析不出任何东西时返回 null。 */
        fun parse(): Any? {
            skipWs()
            if (i >= s.length) return null
            return readValue()
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun readValue(): Any? {
            skipWs()
            return when (val c = peek()) {
                null -> null
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString('"')
                '\'' -> {
                    note("单引号字符串已归一为双引号")
                    readString('\'')
                }

                else -> if (c.isDigit() || c == '-' || c == '+') readNumber() else readBareWord()
            }
        }

        private fun readObject(): Map<String, Any?> {
            i++ // consume '{'
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                skipWs()
                when (val c = peek()) {
                    null -> {
                        note("补齐缺失的闭合括号")
                        return map
                    }

                    '}' -> {
                        i++
                        return map
                    }

                    ',' -> {
                        i++
                        skipWs()
                        if (peek() == '}') {
                            i++
                            note("剔除尾随逗号")
                            return map
                        }
                    }

                    else -> {
                        val key = readKey()
                        skipWs()
                        if (peek() == ':') i++ else note("缺冒号的键值对已容错处理")
                        val value = readValue()
                        if (key != null) map[key] = value
                    }
                }
            }
        }

        private fun readKey(): String? {
            skipWs()
            return when (val c = peek()) {
                null -> null
                '"' -> readString('"')
                '\'' -> {
                    note("单引号键名已归一为双引号")
                    readString('\'')
                }

                else -> {
                    val w = readBareWordRaw() ?: return null
                    if (w.isNotBlank()) note("裸键名已补引号")
                    w
                }
            }
        }

        private fun readArray(): List<Any?> {
            i++ // consume '['
            val list = mutableListOf<Any?>()
            while (true) {
                skipWs()
                when (val c = peek()) {
                    null -> {
                        note("补齐缺失的闭合括号")
                        return list
                    }

                    ']' -> {
                        i++
                        return list
                    }

                    ',' -> {
                        i++
                        skipWs()
                        if (peek() == ']') {
                            i++
                            note("剔除尾随逗号")
                            return list
                        }
                    }

                    else -> list.add(readValue())
                }
            }
        }

        /** 读带引号的字符串；EOF 视为"字符串未闭合"，按已读内容收束。 */
        private fun readString(quote: Char): String {
            i++ // consume opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> {
                        i++
                        if (i >= s.length) break
                        when (val e = s[i]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                // \uXXXX：越界/非法时保留字面量，不让它吃掉后续内容。
                                val hex = s.substring(i + 1, minOf(i + 5, s.length))
                                val code = hex.toIntOrNull(16)
                                if (code != null && hex.length == 4) {
                                    sb.append(code.toChar()); i += 4
                                } else {
                                    sb.append(e)
                                }
                            }

                            else -> sb.append(e) // \" \\ \/ 及未知转义，保留字符本体
                        }
                        i++
                    }

                    c == quote -> {
                        i++
                        return sb.toString()
                    }

                    c == '\n' || c == '\r' -> {
                        // 模型常在 JSON 字符串里直接换行（尤其多行参数），非法 JSON 但必须接受。
                        sb.append('\n')
                        i++
                        note("字符串内裸换行已转义")
                    }

                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            note("字符串未闭合，已按到结尾处理")
            return sb.toString()
        }

        /** 读数字；失败时退化成裸词。 */
        private fun readNumber(): Any? {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val raw = s.substring(start, i)
            raw.toLongOrNull()?.let { return it }
            raw.toDoubleOrNull()?.let { return it }
            i = start
            return readBareWord()
        }

        /** 读裸词并做字面量归一（true/false/null/None/True/undefined/NaN）。 */
        private fun readBareWord(): Any? {
            val w = readBareWordRaw() ?: return null
            return when (w.lowercase()) {
                "true" -> true
                "false" -> false
                in LITERAL_WORDS -> null
                else -> {
                    note("裸值已补引号")
                    w
                }
            }
        }

        /** 读一个裸词（字母/数字/下划线/连字符/点/路径字符），遇到结构字符即停。 */
        private fun readBareWordRaw(): String? {
            skipWs()
            val start = i
            while (i < s.length) {
                val c = s[i]
                val structural = c == ',' || c == ':' || c == '}' || c == ']' ||
                    c == '{' || c == '[' || c == '(' || c == ')'
                if (structural || c == '\n' || c == '\r' || c == '\t') break
                // 允许值里带空格，但只限"看起来是路径"的情形：
                // 否则 `{path: /sdcard/My Documents/a.txt}` 会在 `My` 处截断，参数值变残。
                if (c == ' ' && !(i > start && isInsidePathLike())) break
                i++
            }
            if (i == start) {
                i++ // 完全无法识别的字符：跳过一个，不让解析器卡死
                return null
            }
            return s.substring(start, i).trim().takeIf { it.isNotEmpty() }
        }

        /**
         * 是否处在"看起来是路径/命令的值"中——用于允许裸词里出现空格。
         */
        private fun isInsidePathLike(): Boolean {
            val idx = s.lastIndexOfAny(charArrayOf(':', ',', '{', '['), i)
            val segStart = if (idx < 0) 0 else idx + 1
            val seg = s.substring(segStart, i).trim()
            return seg.startsWith("/") || seg.startsWith("~") || seg.startsWith("./")
        }
    }

    /**
     * 全角标点 → 半角。
     *
     * 中文输入法/中文训练语料会把 JSON 的结构字符打成全角（`｛｝：，""`），
     * 这在云端 API 场景极少见，但在中文能力强的端侧模型上非常高频。
     */
    private fun normalizeWidth(s: String): String {
        if (s.none { it.code in 0xFF00..0xFFEF || it == '\u3000' }) return s
        val sb = StringBuilder(s.length)
        for (c in s) {
            val mapped = when (c) {
                '\uFF1A' -> ':'                              // ：
                '\uFF0C' -> ','                              // ，
                '\uFF1B' -> ';'                              // ；
                '\uFF5B' -> '{'                              // ｛
                '\uFF5D' -> '}'                              // ｝
                '\uFF3B' -> '['                              // ［
                '\uFF3D' -> ']'                              // ］
                '\uFF08' -> '('                              // （
                '\uFF09' -> ')'                              // ）
                '\u201C', '\u201D', '\uFF02' -> '"'          // “ ” ＂
                '\u2018', '\u2019', '\uFF07' -> '\''         // ‘ ’ ＇
                '\u3000' -> ' '
                else -> c
            }
            sb.append(mapped)
        }
        return sb.toString()
    }
}
