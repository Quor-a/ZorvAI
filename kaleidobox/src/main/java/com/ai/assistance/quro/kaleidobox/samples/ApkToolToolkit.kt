package com.ai.assistance.quro.kaleidobox.samples

import com.ai.assistance.quro.kaleidobox.core.engine.InvokeContext
import com.ai.assistance.quro.kaleidobox.core.engine.KaleidoToolkit
import com.ai.assistance.quro.kaleidobox.core.engine.ToolkitHost
import com.ai.assistance.quro.kaleidobox.core.model.KValue
import com.ai.assistance.quro.kaleidobox.core.ui.*
import com.ai.assistance.quro.kaleidobox.core.util.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 真能力插件：APK 逆向（完整 app）。
 *
 * 走宿主的 [app.apk.list] / [app.apk.info] / [app.apk.pull] / [app.apk.dex]
 * （均基于 Android PackageManager + ZipFile），实现一个完整的 APK 分析器：
 *   应用列表（关键字过滤 / 系统应用开关 / 打开即加载）→
 *   应用详情（版本 / 体积 / 安装更新时间 / targetSdk / minSdk / 源码路径）→
 *   四大组件 / 权限 / 签名 → 拉取 APK 到私有目录 → 列出内部 DEX → 一键导出。
 */
class ApkToolToolkit : KaleidoToolkit {

    private var host: ToolkitHost? = null

    override fun attach(host: ToolkitHost) { this.host = host }

    override fun invoke(fn: String, args: KValue, ctx: InvokeContext): KValue = when (fn) {
        "about_apk" -> KValue.Str("APK 逆向：列应用、查组件/权限/签名/体积/版本、拉取 APK、列内部 DEX。")
        "render" -> KValue.Str(Json.write(UiCodec.encode(buildUi(args))))
        "onAction" -> handleAction(args)
        else -> KValue.fail("E_NO_FN", "未知函数: $fn")
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(args: KValue): UiNode {
        val state = SamplesUi.readState(args)
        val tab = (state["tab"] as? String) ?: "list"
        val filter = (state["filter"] as? String) ?: ""
        val withSystem = (state["withSystem"] as? String) == "1"
        val selected = (state["selected"] as? String) ?: ""
        // 打开即自动加载应用列表（不依赖用户先点"刷新"），否则首屏是空的、像坏了。
        var apps = strMaps(state, "apps")
        if (apps.isEmpty()) apps = fetchApps(filter, withSystem)
        val info = (state["info"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()
        val dexList = strMaps(state, "dexList")
        val pulled = (state["pulled"] as? String) ?: ""

        val content = buildList<UiNode> {
            add(SamplesUi.section("sec", "APK 逆向 · 已装应用分析"))
            add(
                UiNode.Row(
                    "tabRow",
                    modifier = Mod(padding = Edges(0, 0, 0, 8)),
                    children = listOf(
                        SamplesUi.button("tab_list", "应用列表",
                            variant = if (tab == "list") UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                            modifier = Mod(weight = 1f)),
                        SamplesUi.button("tab_detail", "应用详情",
                            variant = if (tab == "detail") UiNode.Button.Variant.FILLED else UiNode.Button.Variant.TONAL,
                            modifier = Mod(weight = 1f, padding = Edges(0, 0, 0, 8))),
                    ),
                )
            )
            if (tab == "list") {
                add(SamplesUi.field("filter", "关键字过滤（包名/应用名）", Bound.Ref("filter"), Action.of("filter"),
                    singleLine = true, minH = 52))
                add(
                    SamplesUi.button(
                        "withSystem",
                        if (withSystem) "包含系统应用：开" else "包含系统应用：关",
                        variant = UiNode.Button.Variant.TONAL,
                        modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 8)),
                    )
                )
                add(SamplesUi.primaryAction("refresh", "刷新应用列表"))
                add(SamplesUi.hint("c", "共 ${apps.size} 个应用，点应用进入详情"))
                if (apps.isEmpty()) add(SamplesUi.hint("empty", "（没有匹配的应用，换个关键字或打开系统应用开关）"))
                apps.take(80).forEach { app ->
                    val pkg = app["pkg"].orEmpty()
                    if (pkg.isEmpty()) return@forEach
                    val label = app["label"]?.ifBlank { pkg } ?: pkg
                    val ver = app["versionName"].orEmpty()
                    val size = fmtSize(app["size"]?.toLongOrNull() ?: 0L)
                    add(
                        UiNode.Button(
                            "app_$pkg", Bound.Lit("$label  ·  $ver  ·  $size"),
                            Action.of("select", "pkg" to pkg, "label" to label),
                            variant = UiNode.Button.Variant.TONAL,
                            modifier = Mod(width = Size.Fill, padding = Edges(0, 0, 0, 6)),
                        )
                    )
                }
            } else if (selected.isNotEmpty()) {
                val sel = apps.firstOrNull { it["pkg"] == selected } ?: emptyMap()
                val rows = listOf(
                    "应用" to (info["label"]?.toString() ?: sel["label"] ?: selected),
                    "包名" to (info["pkg"]?.toString() ?: selected),
                    "版本名" to (info["versionName"]?.toString() ?: sel["versionName"] ?: "-"),
                    "版本号" to (sel["versionCode"] ?: "-"),
                    "APK 体积" to fmtSize(sel["size"]?.toLongOrNull() ?: 0L),
                    "targetSdk" to (sel["targetSdk"]?.takeIf { it != "0" } ?: "-"),
                    "minSdk" to (sel["minSdk"]?.takeIf { it != "0" } ?: "-"),
                    "UID" to (sel["uid"] ?: "-"),
                    "安装时间" to fmtTime(sel["installTime"]),
                    "更新时间" to fmtTime(sel["updateTime"]),
                    "源码路径" to (info["sourceDir"]?.toString() ?: sel["sourceDir"] ?: "-"),
                )
                add(SamplesUi.infoCard("ic", rows))
                val act = (info["activities"] as? List<*>) ?: emptyList<Any?>()
                val svc = (info["services"] as? List<*>) ?: emptyList<Any?>()
                val rcv = (info["receivers"] as? List<*>) ?: emptyList<Any?>()
                val prv = (info["providers"] as? List<*>) ?: emptyList<Any?>()
                val perms = (info["permissions"] as? List<*>) ?: emptyList<Any?>()
                val sigs = (info["signatures"] as? List<*>) ?: emptyList<Any?>()
                add(SamplesUi.hint("sum", "活动 ${act.size} · 服务 ${svc.size} · 接收器 ${rcv.size} · 提供者 ${prv.size} · 权限 ${perms.size} · 签名 ${sigs.size}"))
                val lines = buildList<String> {
                    act.map { it.toString() }.forEach { add("Activity: $it") }
                    svc.map { it.toString() }.forEach { add("Service: $it") }
                    rcv.map { it.toString() }.forEach { add("Receiver: $it") }
                    prv.map { it.toString() }.forEach { add("Provider: $it") }
                    perms.map { it.toString() }.forEach { add("Perm: $it") }
                    sigs.map { it.toString() }.forEach { add("Sig: $it") }
                }
                add(SamplesUi.codeBlock("comp", lines, "（无组件信息，可能清单解析受限）", label = "组件 / 权限 / 签名", fill = true))
                add(SamplesUi.hint("dl", "DEX 文件 ${dexList.size} 个"))
                dexList.take(20).forEach { d ->
                    add(SamplesUi.hint("dex_${d["name"]}", "· ${d["name"]}  (${fmtSize(d["size"]?.toLongOrNull() ?: 0L)})"))
                }
                add(SamplesUi.primaryAction("pull", "拉取 APK 到私有目录"))
                if (pulled.isNotEmpty())
                    add(SamplesUi.codeBlock("pulled", listOf(pulled), "", label = "已拉取到", minH = 52))
                add(SamplesUi.secondaryAction("dex", "列出内部 DEX"))
                add(SamplesUi.secondaryAction("copyPerms", "复制权限列表"))
                add(SamplesUi.secondaryAction("copyAll", "复制全部信息"))
                add(SamplesUi.secondaryAction("back", "返回列表"))
            } else {
                add(SamplesUi.hint("e", "请先在「应用列表」选择一个应用"))
                add(SamplesUi.secondaryAction("back", "返回列表"))
            }
        }
        return SamplesUi.scrollPage("root", content = content)
    }

    // ---------------------------------------------------------------- 行为

    private fun handleAction(args: KValue): KValue {
        val obj = (args as? KValue.Obj)?.value ?: emptyMap()
        val state = SamplesUi.readState(args)
        val actionId = obj["actionId"]?.asString() ?: ""
        val payload = (obj["payload"] as? KValue.Obj)?.value ?: emptyMap()
        var tab = (state["tab"] as? String) ?: "list"
        var filter = (state["filter"] as? String) ?: ""
        var withSystem = (state["withSystem"] as? String) == "1"
        var selected = (state["selected"] as? String) ?: ""
        var apps = strMaps(state, "apps").toMutableList()
        var info = (state["info"] as? Map<*, *>)?.mapKeys { it.key.toString() } ?: emptyMap<String, Any?>()
        var dexList = strMaps(state, "dexList").toMutableList()
        var pulled = (state["pulled"] as? String) ?: ""

        when (actionId) {
            "tab_list" -> { tab = "list"; selected = "" }
            "tab_detail" -> tab = if (selected.isNotEmpty()) "detail" else "list"
            "filter" -> filter = payload["value"]?.asString() ?: ""
            "withSystem" -> { withSystem = !withSystem; apps = fetchApps(filter, withSystem).toMutableList() }
            "refresh" -> apps = fetchApps(filter, withSystem).toMutableList()
            "select" -> {
                selected = payload["pkg"]?.asString() ?: ""
                val r = host?.call("app.apk.info", KValue.obj("pkg" to selected)) ?: KValue.Null
                info = (r as? KValue.Obj)?.value?.mapValues { v -> kvToAny(v.value) } ?: emptyMap()
                dexList = mutableListOf()
                pulled = ""
                tab = "detail"
            }
            "pull" -> {
                if (selected.isNotEmpty()) {
                    val r = host?.call("app.apk.pull", KValue.obj("pkg" to selected)) ?: KValue.Null
                    val msg = when (r) {
                        is KValue.Obj -> "已拉取：${r.value["name"]?.asString()}（${fmtSize(r.value["size"]?.asLongOr() ?: 0L)}）"
                        is KValue.Err -> "[${r.code}] ${r.message}"
                        else -> "（无响应）"
                    }
                    pulled = when (r) {
                        is KValue.Obj -> r.value["absPath"]?.asString() ?: msg
                        else -> msg
                    }
                    host?.call("ui.toast", KValue.obj("text" to msg))
                }
            }
            "dex" -> {
                if (selected.isNotEmpty()) {
                    val r = host?.call("app.apk.dex", KValue.obj("pkg" to selected)) ?: KValue.Null
                    dexList = (r as? KValue.Arr)?.value?.mapNotNull { objToMap(it) }?.toMutableList() ?: mutableListOf()
                    host?.call("ui.toast", KValue.obj("text" to "DEX 共 ${dexList.size} 个"))
                }
            }
            "copyPerms" -> {
                val perms = (info["permissions"] as? List<*>)?.joinToString("\n") { it.toString() } ?: "（无权限）"
                host?.call("ui.clipboard", KValue.obj("text" to perms))
                host?.call("ui.toast", KValue.obj("text" to "权限已复制"))
            }
            "copyAll" -> {
                val sel = apps.firstOrNull { it["pkg"] == selected } ?: emptyMap()
                val sb = StringBuilder()
                sb.appendLine("包名: $selected")
                sb.appendLine("版本: ${sel["versionName"] ?: "-"} (${sel["versionCode"] ?: "-"})")
                sb.appendLine("体积: ${fmtSize(sel["size"]?.toLongOrNull() ?: 0L)}")
                sb.appendLine("targetSdk: ${sel["targetSdk"] ?: "-"}  minSdk: ${sel["minSdk"] ?: "-"}")
                sb.appendLine("路径: ${info["sourceDir"] ?: sel["sourceDir"] ?: "-"}")
                (info["permissions"] as? List<*>)?.forEach { sb.appendLine("Perm: $it") }
                host?.call("ui.clipboard", KValue.obj("text" to sb.toString()))
                host?.call("ui.toast", KValue.obj("text" to "信息已复制"))
            }
            "back" -> { tab = "list"; selected = "" }
        }
        return KValue.obj(
            "tab" to tab, "filter" to filter, "withSystem" to (if (withSystem) "1" else "0"),
            "selected" to selected, "apps" to apps, "info" to info,
            "dexList" to dexList, "pulled" to pulled,
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun fetchApps(filter: String, withSystem: Boolean): List<Map<String, String>> {
        val r = host?.call(
            "app.apk.list",
            KValue.obj("filter" to filter, "withSystem" to KValue.Bool(withSystem)),
        ) ?: KValue.Null
        return (r as? KValue.Arr)?.value?.mapNotNull { objToMap(it) } ?: emptyList()
    }

    private fun strMaps(state: Map<String, Any?>, key: String): List<Map<String, String>> {
        val v = state[key]
        return (v as? List<*>)?.mapNotNull { it as? Map<*, *> }
            ?.map { m -> m.mapKeys { it.key.toString() }.mapValues { it.value?.toString() ?: "" } }
            ?: emptyList()
    }

    private fun objToMap(kv: KValue): Map<String, String>? {
        val o = (kv as? KValue.Obj) ?: return null
        return o.value.mapValues { it.value.asString() }
    }

    private fun kvToAny(kv: KValue): Any? = when (kv) {
        is KValue.Obj -> kv.value.mapValues { kvToAny(it.value) }
        is KValue.Arr -> kv.value.map { kvToAny(it) }
        is KValue.Str -> kv.value
        is KValue.I64 -> kv.value
        is KValue.F64 -> kv.value
        is KValue.Bool -> kv.value
        is KValue.Null -> null
        else -> kv.asString()
    }

    private fun fmtSize(b: Long): String = when {
        b <= 0 -> "-"
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "%.2f MB".format(b / 1024.0 / 1024.0)
    }

    private fun fmtTime(s: String?): String {
        val ms = s?.toLongOrNull() ?: 0L
        if (ms <= 0) return "-"
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
        }.getOrDefault("-")
    }
}
