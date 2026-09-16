package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json

/**
 * 真能力插件：联网请求（完整 REST 客户端）。
 *
 * 向 https 地址发起请求（[net.http]，受 netEgress 约束），并补齐一个 HTTP 客户端该有的能力：
 *   - 方法选择（GET/POST/PUT/PATCH/DELETE）、自定义请求头与请求体；
 *   - 回显状态码、【响应头】、【耗时(ms)】、【响应大小】、响应体；
 *   - 响应一键复制、请求历史（[data.kv] 持久化，点击即回填）。
 */
class NetToolToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    private val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
    private val kvKey = "http_history"
    private val maxHistory = 20

    override fun attach(host: ToolkitHost) { this.host = host }

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_nettool" -> KValue.Str("联网请求：向 https 地址发起请求，回显状态码、响应头、耗时、大小与响应体。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- 历史

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

    private fun push(list: MutableList<String>, entry: String): MutableList<String> {
        list.removeAll { it == entry }
        list.add(0, entry)
        while (list.size > maxHistory) list.removeAt(list.size - 1)
        return list
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val state = SamplesUi.readState(args)
        val method = (state["method"] as? String) ?: "GET"
        val url = (state["url"] as? String) ?: ""
        val headers = (state["headers"] as? String) ?: ""
        val body = (state["body"] as? String) ?: ""
        val response = (state["response"] as? String) ?: ""
        val status = (state["status"] as? String) ?: ""
        val meta = (state["meta"] as? String) ?: ""
        var history = SamplesUi.strList(state, "history")
        if (history.isEmpty()) history = loadHistory()

        val content = buildList<UiNode> {
            add(SamplesUi.section("sec", "联网请求 · 仅允许 https"))
            add(SamplesUi.methodPicker("methods", methods, method))
            add(SamplesUi.field("url", "URL (https://…)", Bound.Ref("url"), Action.of("url"), singleLine = true, minH = 52))
            add(SamplesUi.field("headers", "请求头 JSON（可选）", Bound.Ref("headers"), Action.of("headers"), singleLine = false, minH = 70))
            add(SamplesUi.field("body", "请求体（可选）", Bound.Ref("body"), Action.of("body"), singleLine = false, minH = 88))
            add(SamplesUi.section("respSec", "响应"))
            if (response.isNotEmpty() || status.isNotEmpty()) {
                add(SamplesUi.infoCard("meta", listOf(
                    "状态码" to status.ifBlank { "-" },
                    "耗时 / 大小" to meta.ifBlank { "-" },
                )))
                add(SamplesUi.codeBlock("resp", response.split("\n"), "（空响应体）", label = "响应头 + 响应体", minH = 150))
            } else {
                add(SamplesUi.hint("noResp", "（还没有发送请求）"))
            }
            if (history.isNotEmpty()) {
                add(SamplesUi.section("histSec", "请求历史（点“回填”）"))
                history.take(maxHistory).forEachIndexed { i, h ->
                    add(historyRow(i, h))
                }
            }
        }
        return SamplesUi.scrollPage(
            "root",
            content = content,
            actions = listOf(
                SamplesUi.primaryAction("send", "发送请求"),
                if (response.isNotEmpty()) SamplesUi.secondaryAction("copyResp", "复制响应") else UiNode.Spacer("sp", 0),
            ),
        )
    }

    private fun historyRow(i: Int, entry: String): UiNode {
        val preview = if (entry.length > 46) entry.take(46) + "…" else entry
        return UiNode.Row(
            "hh_$i",
            modifier = Mod(padding = Edges(0, 0, 0, 6)),
            children = listOf(
                UiNode.Text(
                    "hht_$i", Bound.Lit(preview), TypeStyle.BODY,
                    color = SamplesUi.C.ink, maxLines = 1, modifier = Mod(weight = 1f),
                ),
                UiNode.Button(
                    "hhu_$i", Bound.Lit("回填"), Action.of("useHist", "idx" to i.toString()),
                    variant = UiNode.Button.Variant.TONAL,
                    modifier = Mod(padding = Edges(0, 0, 0, 6)),
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
        var method = (state["method"] as? String) ?: "GET"
        var url = (state["url"] as? String) ?: ""
        var headers = (state["headers"] as? String) ?: ""
        var body = (state["body"] as? String) ?: ""
        var response = (state["response"] as? String) ?: ""
        var status = (state["status"] as? String) ?: ""
        var meta = (state["meta"] as? String) ?: ""
        var history = SamplesUi.strList(state, "history").toMutableList()
        if (history.isEmpty()) history = loadHistory().toMutableList()

        when (actionId) {
            "method" -> method = payload["value"]?.asString() ?: "GET"
            "url" -> url = payload["value"]?.asString() ?: ""
            "headers" -> headers = payload["value"]?.asString() ?: ""
            "body" -> body = payload["value"]?.asString() ?: ""
            "send" -> {
                val u = url.trim()
                if (!u.startsWith("https://")) {
                    status = "ERR"
                    meta = "-"
                    response = "仅允许 https:// 地址"
                } else {
                    val r = host?.call(
                        "net.http",
                        KValue.obj(
                            "url" to u,
                            "method" to method,
                            "headers" to parseHeaders(headers),
                            "body" to KValue.Str(body),
                        ),
                    ) ?: KValue.Null
                    when (r) {
                        is KValue.Obj -> {
                            status = r.value["status"]?.asString() ?: "?"
                            response = formatWithHeaders(r)
                            val ms = (r.value["timeMs"] as? KValue.I64)?.value ?: 0L
                            val size = (r.value["size"] as? KValue.I64)?.value ?: 0L
                            val ct = r.value["contentType"]?.asString().orEmpty()
                            meta = "${ms} ms · ${fmtSize(size)}" + if (ct.isNotBlank()) " · $ct" else ""
                        }
                        is KValue.Err -> { status = "ERR"; meta = "-"; response = "[${r.code}] ${r.message}" }
                        else -> { status = "?"; meta = "-"; response = "（无响应）" }
                    }
                    push(history, "$method $u")
                    saveHistory(history)
                }
            }
            "useHist" -> {
                val idx = payload["idx"]?.asString()?.toIntOrNull() ?: -1
                history.getOrNull(idx)?.let { entry ->
                    val sp = entry.indexOf(' ')
                    if (sp > 0) {
                        method = entry.substring(0, sp)
                        url = entry.substring(sp + 1)
                        push(history, entry)
                        saveHistory(history)
                    }
                }
            }
            "copyResp" -> {
                if (response.isNotEmpty()) {
                    host?.call("ui.clipboard", KValue.obj("text" to response))
                    host?.call("ui.toast", KValue.obj("text" to "响应已复制"))
                }
            }
        }
        return KValue.obj(
            "method" to method, "url" to url, "headers" to headers,
            "body" to body, "response" to response, "status" to status,
            "meta" to meta, "history" to history,
        )
    }

    /** 把响应头拼在响应体前面展示（便于在代码块里一眼看全）。 */
    private fun formatWithHeaders(r: KValue.Obj): String {
        val body = r.value["body"]?.asString() ?: ""
        val hs = (r.value["headers"] as? KValue.Arr)?.value
            ?.mapNotNull { (it as? KValue.Obj)?.value }
            ?.mapNotNull { m -> m["k"]?.asString()?.let { k -> "$k: ${m["v"]?.asString() ?: ""}" } }
            ?: emptyList()
        if (hs.isEmpty()) return body
        return "── 响应头 ──\n" + hs.joinToString("\n") + "\n── 响应体 ──\n" + body
    }

    private fun fmtSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "%.2f MB".format(b / 1024.0 / 1024.0)
    }

    /** 请求头 JSON 字符串 → KValue.Obj（字符串值）。非法/空白 → 空对象。 */
    private fun parseHeaders(s: String): KValue {
        if (s.isBlank()) return KValue.Obj(emptyMap())
        return runCatching {
            val m = Json.parse(s) as? Map<*, *> ?: return@runCatching KValue.Obj(emptyMap())
            KValue.of(m.mapKeys { it.key.toString() })
        }.getOrDefault(KValue.Obj(emptyMap()))
    }
}
