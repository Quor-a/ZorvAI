package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：剪贴板（完整 app）。
 *
 * 读写系统剪贴板（[ui.clipboard]），并补齐一个剪贴板管理器该有的能力：
 *   - 实时显示当前内容（含字符/行数统计）；
 *   - 写入新内容；
 *   - 剪贴板历史（[data.kv] 持久化）：一键复用、单条删除、清空历史；
 *   - 读取系统剪贴板并自动记入历史。
 */
class ClipboardToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    private val kvKey = "clip_history"
    private val maxItems = 50

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_clipboard" -> KValue.Str("剪贴板：读写系统剪贴板，含历史记录、复用与清空。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 剪贴板 / 历史

    private fun readClip(): String {
        val r = host?.call("ui.clipboard", KValue.obj()) ?: KValue.Null
        return when (r) {
            is KValue.Str -> r.value
            is KValue.Obj -> r.value["text"]?.asString() ?: ""
            else -> ""
        }
    }

    private fun writeClip(text: String) {
        host?.call("ui.clipboard", KValue.obj("text" to text))
    }

    private fun loadHistory(): List<String> {
        val r = host?.call("data.kv", KValue.obj("op" to "get", "key" to kvKey)) ?: KValue.Null
        val str = (r as? KValue.Str)?.value ?: return emptyList()
        val parsed = runCatching { Json.parse(str) }.getOrNull()
        return (parsed as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    }

    private fun saveHistory(list: List<String>) {
        host?.call(
            "data.kv",
            KValue.obj("op" to "set", "key" to kvKey, "value" to Json.write(KValue.of(list))),
        )
    }

    /** 去重后置顶。 */
    private fun pushHistory(list: MutableList<String>, text: String): MutableList<String> {
        if (text.isBlank()) return list
        list.removeAll { it == text }
        list.add(0, text)
        while (list.size > maxItems) list.removeAt(list.size - 1)
        return list
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val state = SamplesUi.readState(args)
        val current = (state["current"] as? String) ?: readClip()
        val draft = (state["draft"] as? String) ?: ""
        var history = SamplesUi.strList(state, "history")
        if (history.isEmpty()) history = loadHistory()

        val curLines = if (current.isBlank()) emptyList() else current.split("\n")
        val content = buildList<UiNode> {
            add(SamplesUi.section("sec", "剪贴板 · 系统剪贴板读写"))
            add(
                SamplesUi.codeBlock(
                    "clip", curLines,
                    "（当前剪贴板为空）",
                    label = "当前剪贴板 · ${current.length} 字 / ${if (current.isBlank()) 0 else curLines.size} 行",
                    minH = 110,
                )
            )
            add(SamplesUi.field("draft", "写入内容", Bound.Ref("draft"), Action.of("draft"), singleLine = false, minH = 76))
            add(SamplesUi.section("hist", "历史记录（点“复用”写回剪贴板）"))
            if (history.isEmpty()) {
                add(SamplesUi.hint("hempty", "（暂无历史，写入或读取后会自动记录）"))
            } else {
                history.take(maxItems).forEachIndexed { i, item ->
                    add(historyRow(i, item))
                }
            }
        }
        return SamplesUi.scrollPage(
            "root",
            content = content,
            actions = listOf(
                SamplesUi.primaryAction("copy", "写入剪贴板"),
                SamplesUi.secondaryAction("read", "读取系统剪贴板"),
                SamplesUi.secondaryAction("clearHist", "清空历史"),
            ),
        )
    }

    private fun historyRow(i: Int, item: String): UiNode {
        val preview = item.replace("\n", " ").trim().let { if (it.length > 44) it.take(44) + "…" else it }
        return UiNode.Row(
            "h_$i",
            modifier = Mod(padding = Edges(0, 0, 0, 6)),
            children = listOf(
                UiNode.Text(
                    "ht_$i", Bound.Lit(if (preview.isBlank()) "(空白)" else preview),
                    TypeStyle.BODY, color = SamplesUi.C.ink, maxLines = 1,
                    modifier = Mod(weight = 1f),
                ),
                UiNode.Button(
                    "hu_$i", Bound.Lit("复用"), Action.of("use", "idx" to i.toString()),
                    variant = UiNode.Button.Variant.TONAL,
                    modifier = Mod(padding = Edges(0, 0, 0, 6)),
                ),
                UiNode.Button(
                    "hd_$i", Bound.Lit("删除"), Action.of("delHist", "idx" to i.toString()),
                    variant = UiNode.Button.Variant.TONAL,
                    modifier = Mod(padding = Edges(0, 0, 0, 8)),
                ),
            ),
        )
    }

    // ---------------------------------------------------------------- 行为

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val state = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()
        var draft = (state["draft"] as? String) ?: ""
        var current = (state["current"] as? String) ?: readClip()
        var history = SamplesUi.strList(state, "history").toMutableList()
        if (history.isEmpty()) history = loadHistory().toMutableList()

        when (actionId) {
            "draft" -> draft = payload["value"]?.asString() ?: ""
            "copy" -> {
                if (draft.isNotEmpty()) {
                    writeClip(draft)
                    pushHistory(history, draft)
                    saveHistory(history)
                    current = draft
                    host?.call("ui.toast", KValue.obj("text" to "已写入剪贴板"))
                }
            }
            "read" -> {
                current = readClip()
                pushHistory(history, current)
                saveHistory(history)
            }
            "use" -> {
                val idx = payload["idx"]?.asString()?.toIntOrNull() ?: -1
                history.getOrNull(idx)?.let {
                    writeClip(it)
                    pushHistory(history, it)
                    saveHistory(history)
                    current = it
                    host?.call("ui.toast", KValue.obj("text" to "已写回剪贴板"))
                }
            }
            "delHist" -> {
                val idx = payload["idx"]?.asString()?.toIntOrNull() ?: -1
                if (idx in history.indices) {
                    history.removeAt(idx)
                    saveHistory(history)
                }
            }
            "clearHist" -> {
                history.clear()
                saveHistory(history)
                host?.call("ui.toast", KValue.obj("text" to "历史已清空"))
            }
        }
        return KValue.obj("draft" to draft, "current" to current, "history" to history)
    }
}
