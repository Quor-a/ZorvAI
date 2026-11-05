package com.ai.assistance.quro.core.ui.card.stream

import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.parseCardSpec

/**
 * 装配层：流式与整卡兼容。
 *
 * 状态机：(空) → Skeleton → Streaming → Complete（异常 → Error(retryable)）。
 *
 * ## 流式渲染的关键：**补齐半截 JSON**，而不是等全文
 *
 * 模型是一个 token 一个 token 吐的，卡片 JSON 在大部分时间里都是「不闭合」状态
 * （`{"type":"metric","data":{"series":[{"points":[12`）。旧实现遇到不闭合就
 * 只显示骨架卡 —— 用户看到的是「卡了十几秒的灰条，然后突然弹出完整卡片」。
 *
 * 现在用 [JsonCompletion.close] 把半截 JSON 补成合法结构（补引号、补右括号、砍掉
 * 末尾悬空的 `,` / `:`），只要补完能解析，就立刻渲染出**当前已知的部分**：
 * 数字先出来、点先长一半、标题先显示，随后被后续 patch 覆盖。
 *
 * ## 回滚
 * `streamId` 抖动（重新生成 / 断流）时用 generation 号丢弃过期 patch，避免旧流的半截数据
 * 把新卡片覆盖成残缺形态。
 */
sealed interface AssembleState {
    object Empty : AssembleState
    data class Skeleton(val spec: CardSpec) : AssembleState
    data class Streaming(val spec: CardSpec, val buffer: String) : AssembleState
    data class Complete(val spec: CardSpec) : AssembleState
    data class Error(val retryable: Boolean, val reason: String?) : AssembleState
}

class StreamAssembler(
    private val streamId: String,
    private val onComplete: (CardSpec) -> Unit,
    private val onPatch: (CardSpec) -> Unit = {},
) {
    private var generation = 0L
    private var state: AssembleState = AssembleState.Empty
    private val buf = StringBuilder()
    private var lastPatchKey: String = ""

    /** 重生成/断流时调用，丢弃过期 patch。 */
    fun bumpGeneration() {
        generation++
        buf.setLength(0)
        lastPatchKey = ""
        state = AssembleState.Empty
    }

    /** 喂入增量 token 片段（流式）。 */
    fun feed(chunk: String) {
        val gen = generation
        if (state is AssembleState.Empty) {
            // 第一段到达：先立骨架，防抖动
            state = AssembleState.Skeleton(buildSkeleton())
        }
        buf.append(chunk)
        val snap = buf.toString()

        tryParse(snap)?.let { parsed ->
            if (gen != generation) return
            state = AssembleState.Complete(parsed)
            lastPatchKey = ""
            onComplete(parsed)
            return
        }

        val preview = tryParsePartial(snap)
        if (gen != generation) return
        state = AssembleState.Streaming(preview ?: buildStreamingPreview(snap), snap)
        if (preview != null) {
            // 内容没变化就不重复 patch（每个 token 都重组一次 UI 会卡）
            val key = preview.data.toString() + preview.title
            if (key != lastPatchKey) {
                lastPatchKey = key
                onPatch(preview)
            }
        }
    }

    /** 一次性完整输入（整卡）。 */
    fun feedComplete(json: String) {
        val parsed = tryParse(json)
        if (parsed != null) {
            state = AssembleState.Complete(parsed)
            onComplete(parsed)
        } else {
            state = AssembleState.Error(true, "JSON 解析失败")
        }
    }

    fun snapshot(): AssembleState = state

    private fun buildSkeleton(): CardSpec =
        CardSpec(id = streamId, type = "skeleton", data = CardData.Status(statusType = "skeleton"))

    private fun buildStreamingPreview(snap: String): CardSpec =
        CardSpec(
            id = streamId,
            type = "skeleton",
            data = CardData.Status(statusType = "skeleton", text = snap.take(64)),
        )

    /** 整段解析（含 data）；失败返回 null（说明 JSON 没收全或非法）。 */
    private fun tryParse(s: String): CardSpec? = parseCardSpec(s)

    /**
     * 流式期：把半截 JSON 补齐后再解析，拿到「当前已知的部分」用于增量渲染。
     * 补不齐（比如正卡在 key 中间）返回 null，调用方继续显示骨架。
     */
    private fun tryParsePartial(s: String): CardSpec? {
        val repaired = JsonCompletion.close(s) ?: return null
        return parseCardSpec(repaired)
    }
}

/**
 * 半截 JSON 补全器（纯 JVM，无 Android 依赖，可单测）。
 *
 * 典型输入 → 输出：
 *  - `{"type":"metric","data":{"series":[{"points":[12` →
 *    `{"type":"metric","data":{"series":[{"points":[12]}]}}`
 *  - `{"type":"table","headers":["a","b"],"rows":[["x","y` →
 *    `{"type":"table","headers":["a","b"],"rows":[["x","y"]]}`
 *  - `{"type":"chart","data":` → 砍掉悬空 key 后补壳（`{}`）
 */
object JsonCompletion {

    /** 悬空逗号：`,` 直接贴着 `}` / `]`（流式里极常见：`{"a":1,"}`）。 */
    private val HANGING_COMMA = Regex(",\\s*([}\\]])")

    /** 半截字面量：`tru` / `fals` / `nul`，补括号后必然非法，只能砍。 */
    private val PARTIAL_LITERAL = Regex("(?:t|tr|tru|f|fa|fal|fals|n|nu|nul)$")

    /**
     * 尾部悬空的空键：`{"a":1,"` 补引号后变成 `{"a":1,""}` —— 有键名没冒号，仍是非法 JSON。
     * 这是流式里每个逗号边界都会撞到的形态，必须连同前面的 `,` 一起砍。
     */
    private val DANGLING_EMPTY_KEY = Regex("(^|[{,])\\s*\"\"\\s*$")

    /**
     * 砍掉尾部「只有键名没有值」的成员。
     *
     * 调用前提：已经把悬空的 `:` 去掉了，所以 `text` 若以 `"` 结尾，那个引号一定是**键名**的收尾引号
     * （值的位置在 `:` 之后，`:` 已被砍）。往回找配对的起始引号（跳过被 `\\` 转义的），
     * 连同前面的 `,` 一起砍掉：`{"a":1,"data"` → `{"a":1`。
     */
    private fun dropTrailingKey(text: String): String {
        if (text.isEmpty() || text.last() != '"') return text
        var i = text.length - 2
        while (i >= 0) {
            if (text[i] == '"') {
                // 统计前面连续反斜杠数量：偶数才是真正的起始引号
                var backslashes = 0
                var j = i - 1
                while (j >= 0 && text[j] == '\\') { backslashes++; j-- }
                if (backslashes % 2 == 0) {
                    var head = text.substring(0, i).trimEnd()
                    if (head.endsWith(",")) head = head.dropLast(1)
                    return head.trimEnd()
                }
            }
            i--
        }
        return text
    }

    /** 尾部是 `tru` / `fals` / `nul` 这类半截字面量时砍掉；没有匹配返回 null。 */
    private fun dropPartialLiteral(text: String): String? {
        val m = PARTIAL_LITERAL.find(text) ?: return null
        // 必须紧跟在 `:` `,` `[` 或 `{` 之后，避免误伤 `"data"` 里的字母
        val before = text.getOrNull(m.range.first - 1) ?: return null
        if (before != ':' && before != ',' && before != '[' && before != '{') return null
        return text.substring(0, m.range.first)
    }

    /** 砍掉尾部的空键 `""`；没有匹配返回 null。保留 `{` 或砍掉 `,`，两者结果都是合法前缀。 */
    private fun dropDanglingEmptyKey(text: String): String? {
        val m = DANGLING_EMPTY_KEY.find(text) ?: return null
        val lead = m.groupValues[1]
        // 前面是 `{` 就把它留下，是 `,` 就一起砍掉
        val cut = if (lead == "{") m.range.first + 1 else m.range.first
        return text.substring(0, cut)
    }

    /** 补全失败返回 null（输入不是以 `{` 开头，或补完仍不合法）。 */
    fun close(input: String): String? {
        val s = input.trimStart()
        if (!s.startsWith("{")) return null

        val out = StringBuilder(s.length + 16)
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false

        for (ch in s) {
            when {
                escaped -> { out.append(ch); escaped = false }
                ch == '\\' && inString -> { out.append(ch); escaped = true }
                ch == '"' -> { out.append(ch); inString = !inString }
                inString -> out.append(ch)
                ch == '{' || ch == '[' -> { out.append(ch); stack.addLast(ch) }
                ch == '}' || ch == ']' -> {
                    // 收尾多余/错配的括号直接丢弃，避免「] } 」顺序错乱导致整段解析失败
                    if (stack.isEmpty()) return null
                    val open = stack.removeLast()
                    if ((open == '{' && ch != '}') || (open == '[' && ch != ']')) return null
                    out.append(ch)
                }
                else -> out.append(ch)
            }
        }

        // 1) 字符串没收完 → 补引号
        if (inString) out.append('"')

        // 2) 反复砍掉尾部「不完整的成员」，直到尾部是一个完整的值或开括号：
        //    - `,` 结尾：成员还没开始，直接砍
        //    - `:` 结尾：只砍冒号会留下「有键无值」的非法 JSON（`{"data"}`），
        //      必须连同前面的键名一起砍；补 null 虽然合法，但 null 会污染渲染
        //    - `tru` / `nul` 这类半截字面量：砍掉，否则补括号后必然解析失败
        var text = out.toString().trimEnd()
        while (true) {
            text = text.trimEnd()
            when {
                text.isEmpty() -> break
                text.endsWith(",") -> { text = text.dropLast(1); continue }
                text.endsWith(":") -> { text = dropTrailingKey(text.dropLast(1).trimEnd()); continue }
                else -> {
                    val trimmed = dropDanglingEmptyKey(text)
                        ?: dropPartialLiteral(text)
                        ?: break
                    text = trimmed
                }
            }
        }
        // 2.1) `,` 紧跟 `}` / `]` 也是非法 JSON（流式里极常见：`{"a":1,"}`），删掉悬空逗号
        var prev: String
        do {
            prev = text
            text = HANGING_COMMA.replace(text, "$1")
        } while (text != prev)

        // 3) 补齐未闭合的括号
        val closed = StringBuilder(text)
        while (stack.isNotEmpty()) {
            closed.append(if (stack.removeLast() == '{') '}' else ']')
        }
        val result = closed.toString().trim()
        // 4) 校验：能被 JSON 解析才算补全成功
        return runCatching {
            org.json.JSONObject(result)
            result
        }.getOrNull()
    }
}
