package com.ai.assistance.quro.core.cluster

import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.network.QuroToolCallRepair
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ 端侧（离线模型）ReAct 文本协议 ★
 *
 * ## 为什么必须有这一层
 *
 * 端侧小模型（MNN / llama.cpp 跑的量化模型）**大多不支持原生 function-calling**：
 * 一旦在请求里带 `tools` 字段，要么被静默忽略，要么模板错位后生成出一堆垃圾，
 * 表现就是「模型不听话了 / 掉线」。
 * 现状代码因此把端侧直接降级成纯文本（`LlmGateway.withTools` 里
 * `model.kind == ModelKind.LOCAL` → `complete()`），
 * 代价是**端侧角色完全不能真正动手** —— 只能把「我做了」写在文本里。
 *
 * 文档 V2 给的解法是不用原生 tools，改用**普通文本协议**：
 * ```
 * 思考：我要先看看目录里有什么
 * 行动：list_files
 * 参数：{"path":"D:/"}
 * ```
 * 这样任何能读文本的模型都能用，不依赖任何厂商的原生能力。
 *
 * ## 三级兜底解析（缺一不可）
 *
 * 小模型不一定会乖乖按协议输出。所以按**可靠度从高到低**依次尝试：
 * 1. **协议级**：`行动：` + `参数：` 成对出现 → 最可靠，优先；
 * 2. **JSON 级**：整段就是 `{"tool":"...","arguments":{...}}` →
 *    交给已有的 [QuroToolCallRepair] 做宽松解析（它能处理裸键名/单引号/
 *    尾随逗号/缺闭合/全角引号/多标签族/工具名纠错）；
 * 3. **结论级**：出现 `结论：` 且**没有**任何可执行调用 →
 *    说明模型已经给出最终答案了，把它的话当答案收下。
 *
 * 🔴 铁律：**模型不按协议输出也不能崩**。
 * 解析不出来就当它说了句人话直接返回，让上层拿到一段文本继续走 ——
 * 崩溃等于整个任务因模型不听话而失败，那比重叠低效严重得多。
 *
 * ## 为什么解析用带标记原文、清洗只作用于展示副本
 *
 * 与 [QuroToolCallRepair] 同一条纪律：解析必须基于**原始输出**，
 * 因为 `行动：`/`参数：` 这些标记对正则和对人眼都一样重要；
 * 只有给用户看的副本才允许洗掉协议行，否则用户会看到一堆 `行动：xxx` 的噪音。
 */
object ClusterTextReAct {

    /** 一轮解析的结果。 */
    data class Parsed(
        /** 解析出的工具调用；为空表示本轮没有可执行的调用。 */
        val calls: List<QuroToolCall>,
        /** 给用户看的文本（已洗掉协议行）。 */
        val visible: String,
        /** 模型是否给出了最终答案（`结论：` 或无调用可执行）。 */
        val finalAnswer: Boolean,
        /** 诊断信息：命中了哪一级、做了什么修复。 */
        val diagnostic: String?,
    )

    // ——————————————— 协议行 ———————————————

    /**
     * 协议标记。
     *
     * 全部接受若干常见写法（半角/全角冒号、中英文逗号、加粗星号），
     * 因为 0.5B~3B 量级的模型写 `Action:` 或 `**行动**：` 都很常见。
     * 写得越宽容，能用上工具的端侧模型就越多。
     */
    private val ACTION_RE = Regex(
        """(?:^|\n)\s*\**\s*(?:行动|动作|Action|ACTION|工具|Tool|TOOL)\s*\**\s*[:：]?\s*\**\s*([^\n*]{1,80})"""
    )
    private val ARGS_RE = Regex(
        """(?:^|\n)\s*\**\s*(?:参数|Arguments|args|Args|ARGS|入参)\s*\**\s*[:：]?\s*"""
    )
    private val THINK_RE = Regex(
        """(?:^|\n)\s*\**\s*(?:思考|理由|Reasoning|Thought|THINK)\s*\**\s*[:：]?"""
    )
    private val OBSERVE_RE = Regex(
        """(?:^|\n)\s*\**\s*(?:观察|结果|输出|Observation|OBS|Output)\s*\**\s*[:：]?"""
    )
    private val CONCLUSION_RE = Regex(
        """(?:^|\n)\s*\**\s*(?:结论|最终答案|答案|Conclusion|FINAL|Final)\s*\**\s*[:：]?"""
    )

    /**
     * 构造端侧 system prompt 的协议段。
     *
     * 只在**端侧且确实有工具**时才拼上去 —— 给云端模型塞这套中文协议纯属噪音，
     * 云端走原生 function-calling（见 `LlmGateway.withTools`）。
     */
    fun protocolPrompt(tools: List<QuroToolSpec>): String = buildString {
        appendLine("## 工具调用方式（重要）")
        appendLine()
        appendLine("你没有原生的函数调用接口。要动手做一件事，必须按下面这个格式输出：")
        appendLine()
        appendLine("```")
        appendLine("思考：我要先确认目标路径存不存在")
        appendLine("行动：<工具名>")
        appendLine("参数：<JSON 对象>")
        appendLine("```")
        appendLine()
        appendLine("规则：")
        appendLine("1. `行动` 必须是下面清单里的工具名，一字不差；")
        appendLine("2. `参数` 必须是合法 JSON 对象，键名照工具的参数说明填；")
        appendLine("3. 一次只调一个工具。调完等结果回来再决定下一步；")
        appendLine("4. 工具返回后，结果会以 `观察：` 开头回给你；")
        appendLine("5. 全部做完后，用 `结论：` 开头写最终答案，不要再调工具。")
        appendLine()
        appendLine("## 可用工具")
        if (tools.isEmpty()) {
            appendLine("（无）")
            return@buildString
        }
        tools.forEach { spec ->
            appendLine("- ${spec.name}：${spec.description}")
            // 参数说明直接从 schema 提 required —— 用真实键名，
            // 模型照抄才有意义；给占位符它就会照抄占位符。
            spec.parametersJson?.let { raw ->
                runCatching {
                    val obj = JSONObject(raw)
                    val props = obj.optJSONObject("properties")
                    val required = obj.optJSONArray("required")?.let { arr ->
                        (0 until arr.length()).map { arr.optString(it) }
                    }.orEmpty()
                    if (props != null) {
                        val desc = props.keys().asSequence().joinToString("；") { k ->
                            val p = props.optJSONObject(k)
                            val t = p?.optString("type") ?: "string"
                            val mark = if (k in required) "（必填）" else ""
                            "$k:$t$mark"
                        }
                        if (desc.isNotBlank()) appendLine("    参数：$desc")
                    }
                }
            }
        }
    }

    /**
     * 解析一轮模型输出。
     *
     * @param raw 模型原始输出（**不要**事先清洗）
     * @param knownNames 可用工具名清单，用于纠正拼写与过滤幻觉工具名
     */
    fun parse(raw: String, knownNames: Collection<String> = emptyList()): Parsed {
        if (raw.isBlank()) return Parsed(emptyList(), "", true, "模型输出为空")

        // —— 第 1 级：文本协议（`行动：` + `参数：`）——
        val actions = ACTION_RE.findAll(raw).map { it.groupValues[1].trim() }.toList()
        if (actions.isNotEmpty()) {
            val calls = mutableListOf<QuroToolCall>()
            actions.forEach { nameLine ->
                val name = cleanToolName(nameLine)
                if (name.isBlank()) return@forEach
                // 只接受清单里的工具名：模型幻觉出的 `web_browser` 之类绝不能真去调，
                // 调了就是「执行了不存在的操作」，比不调更糟。
                if (knownNames.isNotEmpty() && name !in knownNames) return@forEach
                // 参数取该行动行之后、下一行协议标记之前的全部文本
                val args = argsAfter(raw, nameLine)
                calls += QuroToolCall(
                    id = "tc_" + absHash(name + args),
                    name = name,
                    arguments = normalizeArgs(args)
                )
            }
            if (calls.isNotEmpty()) {
                return Parsed(calls, sanitizeVisible(raw), false, "命中文本协议（行动/参数）")
            }
        }

        // —— 第 2 级：JSON 兜底（交给既有的宽松解析层）——
        val repaired = QuroToolCallRepair.extract(raw, knownNames)
        val legit = repaired.calls.filter { knownNames.isEmpty() || it.name in knownNames }
        if (legit.isNotEmpty()) {
            return Parsed(
                legit, sanitizeVisible(raw), false,
                "文本协议未命中，回退 JSON 解析" +
                    (repaired.diagnostic?.let { "（$it）" } ?: "")
            )
        }

        // —— 第 3 级：没有可执行调用 —— 收下它的话当答案
        val text = if (CONCLUSION_RE.containsMatchIn(raw)) {
            // 有 `结论：` 就把结论段留下，前面的思考/工具行剥掉
            CONCLUSION_RE.find(raw)?.let { m ->
                raw.substring(m.range.last + 1).trim().ifBlank { m.value.trim() }
            } ?: raw
        } else {
            sanitizeVisible(raw)
        }
        return Parsed(
            emptyList(), text, true,
            "本轮无可执行调用，按最终答案收下" +
                if (repaired.sawMarker) "（检测到工具特征但解析不出，已放弃执行）" else ""
        )
    }

    /**
     * 剥掉协议标记，只留给人看的话。
     *
     * 🔴 只作用于**展示副本**：[parse] 里的解析用的是原文。
     */
    fun sanitizeVisible(raw: String): String = raw
        .replace(ACTION_RE) { m -> "\n行动：" + m.groupValues[1].trim() }
        .replace(THINK_RE, "\n思考：")
        .replace(OBSERVE_RE, "\n观察：")
        .replace(ARGS_RE, "\n参数：")
        .replace(CONCLUSION_RE, "\n结论：")
        // 去掉被当作标题用的代码围栏
        .replace("```", "")
        .lines()
        .filterNot { it.isBlank() }
        .joinToString("\n")
        .trim()

    // ——————————————— 内部 ———————————————

    /** 把 `**行动**：foo` / `Action: foo` / `行动 foo` 统一取出纯工具名。 */
    private fun cleanToolName(line: String): String =
        line.trim()
            .removePrefix("**").removeSuffix("**")
            .trim()
            .trim('`', '"', '\'')
            .split(Regex("[\\s(（]"))   // `list_files ("x")` → `list_files`
            .firstOrNull()
            ?.trim()
            .orEmpty()

    /** 取某个 `行动：` 行之后的 `参数：` 内容。 */
    private fun argsAfter(raw: String, actionLine: String): String {
        val idx = raw.indexOf(actionLine)
        if (idx < 0) return ""
        val tail = raw.substring(idx + actionLine.length)
        val m = ARGS_RE.find(tail) ?: return ""
        val afterTag = tail.substring(m.range.last + 1)
        // 到下一个协议标记为止
        val stop = listOfNotNull(
            ACTION_RE.find(afterTag)?.range?.first,
            THINK_RE.find(afterTag)?.range?.first,
            OBSERVE_RE.find(afterTag)?.range?.first,
            CONCLUSION_RE.find(afterTag)?.range?.first
        ).minOrNull() ?: afterTag.length
        return afterTag.substring(0, stop).trim()
    }

    /**
     * 参数归一化：能当 JSON 对象解析就用它，否则包一层。
     *
     * 模型经常写成 `参数：path="D:/"`（key=value 风格）或直接 `参数：D:/`。
     * 前者转成 `{"path":"D:/"}`，后者包成 `{"input":"D:/"}` ——
     * **绝不因为解析不了参数就丢弃整个调用**：先让工具自己报错，
     * 它的错误信息往往就是模型能理解的最有效的纠正提示。
     */
    internal fun normalizeArgs(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return "{}"
        if (s.startsWith("{")) {
            runCatching { JSONObject(s) }
                .onSuccess { return it.toString() }
        }
        // key=value / key: value 逐对
        val kv = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*[=:]\s*(?:"([^"]*)"|'([^']*)'|([^\s,，]+))""")
            .findAll(s)
            .mapNotNull { m ->
                val k = m.groupValues[1]
                val v = m.groupValues.drop(2).firstOrNull { it.isNotEmpty() } ?: return@mapNotNull null
                k to v
            }
            .toList()
        if (kv.isNotEmpty()) {
            val o = JSONObject()
            kv.forEach { (k, v) -> o.put(k, v) }
            return o.toString()
        }
        // 裸值 → 包成 input
        val o = JSONObject()
        o.put("input", s)
        return o.toString()
    }

    /** 参数行的白名单回灌：JSON 数组/对象保持原样。 */
    internal fun isJsonLike(s: String): Boolean =
        runCatching { JSONObject(s) }.isSuccess || runCatching { JSONArray(s) }.isSuccess

    /** 稳定 hash（不用 String.hashCode —— 它在不同 JVM 上不保证一致）。 */
    private fun absHash(s: String): String {
        var h = 1125899906842597L
        for (c in s) h = 31 * h + c.code
        return h.toULong().toString(16)
    }
}