package com.zorv.genui.protocol

import org.json.JSONObject

/**
 * 生成式 UI 流式协议解析
 *
 * 传输格式（与 Markdown 正文混排）：
 *
 *   ```zorv/ui id=blk_7f3a rev=3 lang=jsx caps=emit deps=react,recharts
 *   export default function Dashboard() { ... }
 *   ```
 *
 * 设计要点：
 *  1. 元信息放在围栏 info string 而非 JSON body —— JSON 需等闭合才能解析，
 *     且代码内引号/换行需转义，流式体验差且转义 bug 高发。
 *     info string 在围栏首行即完整可得。
 *  2. 代码区保持原始文本，零转义；解析失败时可降级为代码块展示。
 *  3. CODE 状态内绝不执行 —— 任何中间态都大概率语法不完整。
 *
 * 相对上传版的补全（#627）：
 *  - [Artifact.parentRev] 版本树回溯字段
 *  - [ArtifactState] 组件状态快照数据模型（复活重放用）
 */

// ---------------------------------------------------------------- 数据模型

enum class Lang { JSX, HTML }

data class Artifact(
    val id: String,
    val rev: Int,
    val lang: Lang,
    val code: String,
    val caps: Set<String>,
    val deps: Set<String>,
    val createdAt: Long = System.currentTimeMillis(),
    /** 版本树回溯：指向被本版取代的上一 rev；首版为 null */
    val parentRev: Int? = null
)

/**
 * 组件通过 `emit('state', {...})` 上报的关键 UI 状态。
 * 卡片被回收（滚出视口 / onTrimMemory）后，重进视口时用它重放，用户侧应无感。
 */
data class ArtifactState(
    val artifactId: String,
    val rev: Int,
    val uiState: JSONObject?
)

/** 流式输出中的两种片段 */
sealed interface StreamChunk {
    data class Text(val text: String) : StreamChunk
    data class ArtifactCommit(val artifact: Artifact) : StreamChunk
    /** 围栏未闭合而流结束 / 扫描不通过 —— 降级为代码块 */
    data class ArtifactFailed(
        val id: String?,
        val code: String,
        val reason: String,
        val violations: List<String> = emptyList()
    ) : StreamChunk
}

// ---------------------------------------------------------------- 常量

private const val FENCE_MARK = "```"
private const val LANG_TAG = "zorv/ui"

/** 产品策略决定：哪些 capability 可以被授予 */
val GRANTABLE_CAPS = setOf("emit", "storage", "net:api.zorv.ai")

// ---------------------------------------------------------------- 状态机

private enum class State { TEXT, FENCE_HEAD, CODE }

/**
 * 增量解析器。非线程安全，每个流一个实例。
 *
 * 用法：
 *   val p = GenUiProtocolParser()
 *   flow.onEach { p.push(it) }.collect { chunk -> render(chunk) }
 *   p.finish()?.let { handle(it) }
 */
class GenUiProtocolParser {

    private var state: State = State.TEXT
    private val codeBuf = StringBuilder()
    private val lineBuf = StringBuilder()   // 首行累积（等待完整 info string）
    private var meta: Meta? = null
    private var fenceCharCount = 0          // 结尾围栏匹配计数

    private val textOut = StringBuilder()

    fun push(delta: String): List<StreamChunk> {
        val out = mutableListOf<StreamChunk>()
        var i = 0
        while (i < delta.length) {
            val c = delta[i]
            when (state) {

                State.TEXT -> {
                    if (c == '`' && fenceCharCount < 3) {
                        fenceCharCount++
                        if (fenceCharCount == 3) {
                            flushText(out)
                            state = State.FENCE_HEAD
                            lineBuf.clear()
                        }
                    } else {
                        if (fenceCharCount > 0) textOut.append("`".repeat(fenceCharCount))
                        fenceCharCount = 0
                        textOut.append(c)
                    }
                    i++
                }

                State.FENCE_HEAD -> {
                    if (c == '\n') {
                        val head = lineBuf.toString().trim()
                        if (head.startsWith(LANG_TAG)) {
                            meta = parseMeta(head.removePrefix(LANG_TAG).trim())
                            state = State.CODE
                            codeBuf.clear()
                            fenceCharCount = 0
                        } else {
                            // 普通代码块（```kotlin 等）→ 原样吐回文本，不拦截
                            textOut.append(FENCE_MARK).append(head).append('\n')
                            state = State.TEXT
                            fenceCharCount = 0
                        }
                    } else {
                        lineBuf.append(c)
                    }
                    i++
                }

                State.CODE -> {
                    if (c == '`' && fenceCharCount < 3) {
                        fenceCharCount++
                    } else if (fenceCharCount == 3) {
                        // 围栏已闭合
                        if (c == '\n' || c == '\r') {
                            out += commit()
                            state = State.TEXT
                            fenceCharCount = 0
                        } else {
                            // ``` 后跟了别的字符 → 不是闭合，回吐到代码
                            codeBuf.append("```").append(c)
                            fenceCharCount = 0
                        }
                    } else {
                        if (fenceCharCount > 0) codeBuf.append("`".repeat(fenceCharCount))
                        fenceCharCount = 0
                        codeBuf.append(c)
                    }
                    i++
                }
            }
        }
        flushText(out)
        return out
    }

    /** 流结束：若仍停在 CODE，说明围栏未闭合（网络中断/模型截断） */
    fun finish(): StreamChunk? {
        return when (state) {
            State.CODE -> {
                val code = codeBuf.toString()
                val m = meta
                state = State.TEXT
                StreamChunk.ArtifactFailed(m?.id, code, "围栏未闭合，输出被截断")
            }
            State.FENCE_HEAD -> null
            State.TEXT -> {
                if (fenceCharCount > 0) textOut.append("`".repeat(fenceCharCount))
                fenceCharCount = 0
                flushText(mutableListOf())
                null
            }
        }
    }

    // ------------------------------------------------------------ 内部

    private fun flushText(out: MutableList<StreamChunk>) {
        if (textOut.isEmpty()) return
        out += StreamChunk.Text(textOut.toString())
        textOut.clear()
    }

    private fun commit(): StreamChunk {
        val m = meta ?: Meta()
        val code = codeBuf.toString().trimEnd()
        meta = null

        // 静态扫描（浅层防御，拦住 95% 的无意违规；真正边界是 CSP）
        val violations = StaticScan.scan(code)
        if (violations.any { it.severity == Severity.FATAL }) {
            return StreamChunk.ArtifactFailed(
                id = m.id,
                code = code,
                reason = "静态扫描未通过",
                violations = violations.filter { it.severity == Severity.FATAL }.map { it.pattern }
            )
        }

        return StreamChunk.ArtifactCommit(
            Artifact(
                id = m.id ?: generateId(),
                rev = m.rev ?: 1,
                lang = m.lang,
                code = code,
                caps = (m.caps intersect GRANTABLE_CAPS),   // 静默裁剪未授权能力
                deps = m.deps,
                parentRev = m.rev?.let { if (it > 1) it - 1 else null }
            )
        )
    }

    private data class Meta(
        val id: String? = null,
        val rev: Int? = null,
        val lang: Lang = Lang.JSX,
        val caps: Set<String> = emptySet(),
        val deps: Set<String> = emptySet()
    )

    private fun parseMeta(s: String): Meta {
        // 形如：id=blk_7f3a rev=3 lang=jsx caps=emit,storage deps=react,recharts
        val kv = s.split(Regex("\\s+")).mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }.toMap()

        return Meta(
            id = kv["id"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{4,32}")) },
            rev = kv["rev"]?.toIntOrNull(),
            lang = if (kv["lang"] == "html") Lang.HTML else Lang.JSX,
            caps = kv["caps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet(),
            deps = kv["deps"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: emptySet()
        )
    }

    private fun generateId(): String =
        "blk_" + (1..6).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
}

// ---------------------------------------------------------------- 静态扫描

enum class Severity { FATAL, WARN }

data class Violation(val pattern: String, val severity: Severity)

/**
 * 浅层防御：字符串匹配，模型换个写法就能绕过。
 * 真实价值是拦住绝大多数"无意违规"，为 CSP 深层防御争取时间。
 */
object StaticScan {

    private val RULES = listOf(
        "eval(" to Severity.FATAL,
        "new Function(" to Severity.FATAL,
        "Function(" to Severity.FATAL,
        "importScripts" to Severity.FATAL,
        "<iframe" to Severity.FATAL,
        "document.cookie" to Severity.FATAL,
        "window.open" to Severity.FATAL,
        "top.location" to Severity.FATAL,
        "parent.location" to Severity.FATAL,
        "XMLHttpRequest" to Severity.FATAL,
        "WebSocket" to Severity.FATAL,

        // 网络与存储：CSP 已封死，这里只是提前给出更友好的降级理由
        "fetch(" to Severity.WARN,
        "localStorage" to Severity.WARN,
        "sessionStorage" to Severity.WARN,
        "indexedDB" to Severity.WARN
    )

    /** 代码量上限，异常输出直接不再渲染 */
    private const val MAX_CODE_CHARS = 48_000

    fun scan(code: String): List<Violation> {
        val hits = RULES.mapNotNull { (pattern, sev) ->
            if (code.contains(pattern, ignoreCase = true)) Violation(pattern, sev) else null
        }.toMutableList()

        if (code.length > MAX_CODE_CHARS) {
            hits += Violation("code too large (${code.length})", Severity.FATAL)
        }
        return hits
    }
}
