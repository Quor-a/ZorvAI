package com.ai.assistance.quro.ui.chat

import com.ai.assistance.quro.core.QuroMessage
import com.ai.assistance.quro.core.tools.QuroVoiceStyle
import com.ai.assistance.quro.ui.MsgBlock
import com.ai.assistance.quro.ui.extractInlineComponents
import com.ai.assistance.quro.ui.parseBlocks
import org.json.JSONArray
import org.json.JSONObject

/**
 * Web UI 投影层 —— 把 ZorvAI 对话框**该被看见的东西**序列化出去。
 *
 * ── 为什么要有这一层（用户 2026-10-13 纠偏）───────────────────────────
 * 用户原话：「web ui 是 zorvAI 的投影」，不是「另外设计 UI 界面」。
 * 前几轮我做错的地方是：把网页写成了**一个独立设计的聊天应用**——自造顶栏、
 * 自造空状态、自造页脚、自造一套 Markdown 渲染器。那不是投影，那是第二个产品。
 *
 * 投影的正确定义（沿用本仓既有词汇，见 `docs/features/cluster/README.md`：
 * 「集群事件会投影成当前对话框的气泡」）：
 * **宿主把「该被看见的块」投出去，接收端只负责呈现，不负责设计。**
 *
 * ── 实现要点：块真相源只有一份 ───────────────────────────────────────
 * 本文件**不自己解析** Markdown／围栏／表格。对话正文一律走 [parseBlocks]
 * ——正是原生对话框 `MessageRow` 渲染用的同一个入口
 * （它已从 `private` 提为 `internal`，就是为了这里能复用）。
 * 于是「网页看到的块」与「原生看到的块」在**结构上不可能漂移**：
 * 它们本来就是同一个函数的输出，差别只在谁来画。
 *
 * 🔴 因此本文件里若出现任何 ``` 围栏嗅探、Markdown 分词、HTML 探测，
 *    都是**投影退化成自造实现**的信号，必须打回。
 */
object WebUiProjection {

    /** 与原生一致：互动UI界面挂在 `zx_web_card` 这个工具调用上。 */
    private const val ZX_TOOL = "zx_web_card"

    /**
     * 顶栏 = 原生 `ChatTopBar` 的内容（模型名 / 人格 / 标题）。
     * 只投字段，不投「怎么画」。
     */
    fun bar(title: String, modelName: String, personaName: String): JSONObject =
        JSONObject()
            .put("title", title)
            .put("model", modelName)
            .put("persona", personaName)

    /**
     * 输入区 = 原生 `ChatPermissionModeBar` + 输入行。
     * 这些开关在原生侧是胶囊按钮，投影只投**状态**，画法由接收端与原生保持一致。
     */
    fun composer(
        busy: Boolean,
        deepThink: Boolean,
        autoSaveMemory: Boolean,
        subAgent: Boolean,
        autoRead: Boolean,
        vision: Boolean,
        workspace: String,
    ): JSONObject = JSONObject()
        .put("busy", busy)
        .put("deepThink", deepThink)
        .put("autoSaveMemory", autoSaveMemory)
        .put("subAgent", subAgent)
        .put("autoRead", autoRead)
        .put("vision", vision)
        .put("workspace", workspace)

    /**
     * 一条消息 → 该被看见的块。
     *
     * 块顺序与原生 `MessageRow` 一致：**思考 → 正文块序列 → 工具 → 互动UI界面**。
     * 顺序在这里定死、不接受接收端重排：顺序就是原生对话的语义。
     */
    fun message(m: QuroMessage, mine: Boolean): JSONObject {
        val o = JSONObject()
            .put("id", m.id)
            .put("role", m.role)
            .put("mine", mine)
            .put("senderName", m.senderName ?: "")
            .put("time", m.createdAt)

        // ── 思考 ──
        m.reasoning?.takeIf { it.isNotBlank() }?.let { o.put("reasoning", it) }

        // ── 正文块：与原生同一个解析入口，不重写 ──
        blockList(m.content).let { if (it.length() > 0) o.put("blocks", it) }

        // ── 工具调用（原生是「N 工具」胶囊，投影投同样的条目）──
        m.toolCalls?.takeIf { it.isNotEmpty() }?.let { calls ->
            val arr = JSONArray()
            calls.forEach { tc ->
                // 🔴 互动UI界面不是「一次工具调用」：原生侧把它抽成界面块内联渲染，
                //   投影必须同样处理，否则网页会多出一行原始 JSON 参数
                //   —— 那正是「卡片方式」的残留观感，用户已明确否定。
                if (tc.name == ZX_TOOL) return@forEach
                arr.put(
                    JSONObject()
                        .put("name", tc.name)
                        .put("arguments", tc.arguments)
                        .put("result", tc.result ?: JSONObject.NULL)
                        .put("durationMs", tc.durationMs)
                )
            }
            if (arr.length() > 0) o.put("tools", arr)
        }

        // ── 互动UI界面（zxwidget）──
        // 🔴 只投「这里有一块互动UI界面 + 它的组件名」，**不投实现、不做编译**。
        //   zxwidget 是独立模块（`assets/zx/`），Web UI 不拥有它、也不负责渲染它；
        //   用户原话：「你怎么老是把 zxwidget 和 webui 混合在一起」。
        uiBlocks(m)?.let { o.put("ui", it) }

        // ── 附件 ──
        m.attachments?.takeIf { it.isNotEmpty() }?.let { atts ->
            val arr = JSONArray()
            atts.forEach { a ->
                arr.put(
                    JSONObject()
                        .put("type", a.type)
                        .put("uri", "file://" + a.uri)
                        .put("name", a.name)
                )
            }
            o.put("attachments", arr)
        }
        return o
    }

    /**
     * 正文 → 块 JSON。
     *
     * 对齐 `MessageRow` 的真实取法：先 [QuroVoiceStyle.strip] 去语音标记、
     * 再 [extractInlineComponents] 抽走内联组件 JSON，最后交给 [parseBlocks]。
     * 这三步**一步都不能省**，否则网页会比原生多出/少掉块
     * （内联组件 JSON 会裸露成正文文本，页面上就是一坨 JSON）。
     */
    private fun blockList(text: String?): JSONArray {
        val arr = JSONArray()
        val t = text?.takeIf { it.isNotBlank() } ?: return arr
        val clean = runCatching { QuroVoiceStyle.strip(t) }.getOrDefault(t)
        val stripped = runCatching { extractInlineComponents(clean).first }.getOrDefault(clean)
        runCatching { parseBlocks(stripped) }.getOrDefault(emptyList()).forEach { b ->
            blockJson(b).let { arr.put(it) }
        }
        return arr
    }

    /** 一种块 → 一个 JSON 对象。`t` 是块类型，接收端按它分派。 */
    private fun blockJson(b: MsgBlock): JSONObject = when (b) {
        is MsgBlock.Text -> JSONObject().put("t", "text").put("text", b.text)

        is MsgBlock.Heading ->
            JSONObject().put("t", "heading").put("level", b.level).put("text", b.text)

        is MsgBlock.Quote -> JSONObject().put("t", "quote").put("text", b.text)

        is MsgBlock.Rule -> JSONObject().put("t", "rule")

        is MsgBlock.Code ->
            JSONObject().put("t", "code").put("lang", b.lang).put("code", b.code)

        is MsgBlock.Table -> {
            val header = JSONArray()
            b.header.forEach { header.put(it) }
            val rows = JSONArray()
            b.rows.forEach { r ->
                val rr = JSONArray()
                r.forEach { rr.put(it) }
                rows.put(rr)
            }
            JSONObject().put("t", "table").put("header", header).put("rows", rows)
        }

        // 原生专属渲染的块（Mermaid 图 / AIP 排版 / 动态 UI / 自研卡片 / 组件围栏 / MiniApp）：
        // 它们在原生侧是 Compose 渲染器，浏览器里**没有对应物**。
        // 这里如实投出「这是什么块 + 它的源」，而不是假装能画 ——
        // 假装只会得到「像原生的假象」，而那正是上一轮被否掉的东西。
        is MsgBlock.Mermaid ->
            JSONObject().put("t", "native").put("kind", "mermaid").put("source", b.source)

        is MsgBlock.Aip ->
            JSONObject().put("t", "native").put("kind", "aip").put("source", b.source)

        is MsgBlock.DynamicUi ->
            JSONObject().put("t", "native").put("kind", "dynamic_ui").put("source", b.source)

        is MsgBlock.SelfCard ->
            JSONObject().put("t", "native").put("kind", "self_card").put("source", b.source)

        is MsgBlock.MiniApp ->
            JSONObject().put("t", "native").put("kind", "miniapp").put("source", b.html)

        is MsgBlock.Card -> JSONObject()
            .put("t", "native")
            .put("kind", "card")
            .put("fence", b.fence)
            .put("attrs", b.attrs)
            .put("closed", b.closed)
            .put("source", b.source)
    }

    /**
     * 消息上的互动UI界面（`zx_web_card` 工具参数里的 component + dsl 存在性）。
     * 复用原生侧同一个抽取函数 [zxSpecOf]，两边对「什么算一块界面」的判断不会分叉。
     */
    private fun uiBlocks(m: QuroMessage): JSONArray? {
        val calls = m.toolCalls?.takeIf { it.isNotEmpty() } ?: return null
        val arr = JSONArray()
        calls.forEach { tc ->
            if (tc.name != ZX_TOOL) return@forEach
            val spec = runCatching { zxSpecOf(tc.arguments) }.getOrNull() ?: return@forEach
            arr.put(
                JSONObject()
                    .put("component", spec.component)
                    .put("dsl", spec.dsl)
            )
        }
        return if (arr.length() > 0) arr else null
    }
}
