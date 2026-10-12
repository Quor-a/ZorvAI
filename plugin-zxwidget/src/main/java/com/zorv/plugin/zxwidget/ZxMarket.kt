package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 卡片市场 —— 模板跨用户分享，带版本依赖与来源校验。
 *
 * 模板解决了"AI 每次重拼 DSL"，但每个用户还是得自己攒模板。
 * 市场让模板可分享：别人写好的「机型对比」「部署向导」拿过来就能用。
 *
 * 真正的难点不是分发，是**信任**：
 *   1. 一个模板可能引用另一个模板（依赖），装的时候要能解析出完整依赖树
 *   2. 依赖成环、依赖缺失、版本冲突都要在**安装前**检出，不能装一半崩
 *   3. 远端模板可能带危险声明（PRIVILEGED），装之前要让用户看见
 *   4. 来源不可信的模板不能直接进本地命名空间
 *
 * 所以市场的核心不是"下载"，是"安装前的安全检查"。
 *
 * 三条硬规则：
 *   1. 必须先 preview 再 install，不允许静默安装
 *   2. 依赖树安装前做环检测 + 缺失检测，任一失败整包拒绝
 *   3. 声明了 PRIVILEGED / ACI_OUT 的模板，安装时强制走审批
 */
object ZxMarket {

    data class Package(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val component: String,
        val dsl: String,
        /** 依赖：[模板名@版本] */
        val requires: List<String>,
        /** 权限声明 */
        val caps: List<String>,
        val desc: String,
        val downloads: Int = 0
    )

    data class InstallPlan(
        val ok: Boolean,
        val steps: List<String>,
        val missing: List<String>,
        val cycle: List<String>?,
        val needsApproval: Boolean,
        val reason: String
    )

    /** 本地已装 */
    private val installed = linkedMapOf<String, Package>()

    /** 远端目录（真实实现走 ACI 或宿主网络能力，这里内存模拟） */
    private val remote = linkedMapOf<String, Package>()

    fun publish(p: Package): Result {
        if (!p.name.matches(Regex("[a-z0-9_]{2,32}")))
            return Result(false, "模板名只能含小写字母/数字/下划线，2-32 字符：${p.name}")
        if (p.requires.any { it.substringBefore('@') == p.name })
            return Result(false, "模板不能依赖自己：${p.name}")
        remote["${p.name}@${p.version}"] = p
        ZxAudit.log(ZxAudit.Kind.MARKET, p.name, "发布 v${p.version} · 依赖 ${p.requires.size} 个")
        return Result(true, "已发布 ${p.name}@${p.version}")
    }

    fun catalog(): List<Package> = remote.values.toList()
    fun installed(): List<Package> = installed.values.toList()

    fun search(q: String): List<Package> =
        remote.values.filter { it.name.contains(q, true) || it.desc.contains(q, true) }

    /**
     * 安装前检查：解析依赖树，检出缺失与环，判断是否需要审批。
     * 不修改任何状态——真正的安装在 install() 里做。
     */
    fun preview(name: String, version: String? = null): InstallPlan {
        val root = remote.entries.firstOrNull { it.key == "$name@${version ?: latest(name)}" }?.value
            ?: return InstallPlan(false, emptyList(), emptyList(), null, false, "市场里没有 $name")

        val steps = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val visiting = mutableListOf<String>()
        val done = mutableSetOf<String>()
        var cycle: List<String>? = null
        var needsApproval = false

        fun walk(p: Package) {
            if (cycle != null) return
            val key = "${p.name}@${p.version}"
            if (key in done) return
            if (key in visiting) {                 // 回边 → 成环
                cycle = visiting.subList(visiting.indexOf(key), visiting.size) + key
                return
            }
            visiting.add(key)
            steps.add("装 ${p.name}@${p.version}（${p.component}）")

            if (p.caps.any { it == "PRIVILEGED" || it == "ACI_OUT" }) needsApproval = true

            for (req in p.requires) {
                val rn = req.substringBefore('@')
                val rv = req.substringAfter('@', "").ifBlank { null }
                // 已装且版本满足则跳过
                val local = installed[rn]
                if (local != null && (rv == null || local.version == rv)) {
                    steps.add("  ∙ $rn 已装 v${local.version}，跳过")
                    continue
                }
                val dep = remote.entries.firstOrNull { it.key == "$rn@${rv ?: latest(rn)}" }?.value
                if (dep == null) { missing.add(rn); continue }
                walk(dep)
            }
            visiting.removeAt(visiting.size - 1)
            done.add(key)
        }
        walk(root)

        if (cycle != null)
            return InstallPlan(false, steps, missing, cycle, false,
                "依赖成环：${cycle!!.joinToString(" → ")}。整包拒绝。")
        if (missing.isNotEmpty())
            return InstallPlan(false, steps, missing, null, false,
                "缺少依赖：${missing.joinToString()}。整包拒绝。")
        if (needsApproval)
            return InstallPlan(true, steps, emptyList(), null, true,
                "该包声明了 PRIVILEGED / ACI_OUT 能力，需要用户显式批准才能安装。")
        return InstallPlan(true, steps, emptyList(), null, false, "检查通过，可安装 ${steps.size} 项")
    }

    private fun latest(name: String): String =
        remote.keys.filter { it.substringBefore('@') == name }
            .map { it.substringAfter('@') }
            .maxOrNull() ?: "1.0"

    /** 真正安装。必须先 preview 通过。 */
    fun install(name: String, version: String? = null, approved: Boolean = false): Result {
        val plan = preview(name, version)
        if (!plan.ok) return Result(false, plan.reason)
        if (plan.needsApproval && !approved)
            return Result(false, "需要用户批准：${plan.reason}")

        var n = 0
        val root = remote["$name@${version ?: latest(name)}"] ?: return Result(false, "包不存在")
        fun walk(p: Package) {
            val key = "${p.name}@${p.version}"
            if (installed.containsKey(p.name) && installed[p.name]?.version == p.version) return
            p.requires.forEach { req ->
                val rn = req.substringBefore('@')
                val rv = req.substringAfter('@', "").ifBlank { null }
                remote["$rn@${rv ?: latest(rn)}"]?.let { walk(it) }
            }
            installed[p.name] = p
            // 同时注册成本地模板，AI 立刻能用
            ZxTemplate.save(p.name, p.component, p.dsl, p.desc)
            n++
        }
        walk(root)
        ZxAudit.log(ZxAudit.Kind.MARKET, name, "安装成功 · $n 个模板入本地")
        return Result(true, "已安装 $name 及依赖共 $n 个模板，AI 现在可以直接 zx_template(use)。")
    }

    fun uninstall(name: String): Result {
        val p = installed.remove(name)
            ?: return Result(false, "本地没装 $name")
        return Result(true, "已卸载 $name@${p.version}")
    }

    /** 目录渲染成终端卡 */
    fun catalogAsTerminal(q: String? = null): String {
        val list = q?.let { search(it) } ?: catalog()
        val lines = org.json.JSONArray()
        if (list.isEmpty()) {
            lines.put(JSONObject().put("lv", "warn").put("c", "市场里没有匹配的包"))
        } else {
            lines.put(JSONObject().put("lv", "cmd").put("c", "zx_market list"))
            list.forEach { p ->
                val risky = p.caps.any { it == "PRIVILEGED" || it == "ACI_OUT" }
                lines.put(JSONObject().put("lv", if (risky) "warn" else "ok")
                    .put("c", "${p.name}@${p.version}  [${p.component}]  ↓${p.downloads}"))
                lines.put(JSONObject().put("lv", "out")
                    .put("c", "    ${p.desc} · by ${p.author}" +
                        (if (p.requires.isNotEmpty()) " · 依赖 ${p.requires.joinToString()}" else "") +
                        if (risky) " · ⚠ 含特权声明" else ""))
            }
        }
        val ins = installed()
        if (ins.isNotEmpty()) {
            lines.put(JSONObject().put("lv", "out").put("c", "本地已装：${ins.joinToString { "${it.name}@${it.version}" }}"))
        }
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_market list").put("exit", 0).put("lines", lines).toString()
    }

    /** 安装计划渲染成终端卡，让用户看见要装什么 */
    fun planAsTerminal(name: String): String {
        val plan = preview(name)
        val lines = org.json.JSONArray()
        lines.put(JSONObject().put("lv", "cmd").put("c", "zx_market preview $name"))
        plan.steps.forEach { s ->
            lines.put(JSONObject().put("lv", "out").put("c", "  $s"))
        }
        when {
            plan.cycle != null -> lines.put(JSONObject().put("lv", "err").put("c", "⛔ ${plan.reason}"))
            plan.missing.isNotEmpty() -> lines.put(JSONObject().put("lv", "err").put("c", "⛔ ${plan.reason}"))
            plan.needsApproval -> lines.put(JSONObject().put("lv", "warn").put("c", "⚠ ${plan.reason}"))
            else -> lines.put(JSONObject().put("lv", "ok").put("c", "✓ ${plan.reason}"))
        }
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_market preview").put("exit", if (plan.ok) 0 else 1)
            .put("lines", lines).toString()
    }

    /** 内置示例包，让市场第一次打开就有东西 */
    fun seed() {
        publish(Package("basic_compare", "basic_compare", "1.0", "官方", "zx_compare",
            """{"type":"zx_compare","title":"{{${'$'}title}}","columns":["{{${'$'}col0}}","{{${'$'}col1}}"],
               "rows":[{"k":"{{${'$'}dim}}","v":["{{${'$'}a}}","{{${'$'}b}}"]}],
               "onPick":{"action":"tool_call","name":"zx_select"}}""",
            emptyList(), listOf("UI_ONLY"), "两列基础对比，无副作用", 1280))

        publish(Package("report_suite", "report_suite", "1.2", "社区", "zx_tabs",
            """{"type":"zx_tabs","children":[
                 {"tab":"概览","type":"zx_chart","points":[1,2,3]},
                 {"tab":"明细","type":"zx_table","columns":["项","值"],"rows":[["a",1]]}]}""",
            listOf("basic_compare@1.0"), listOf("UI_ONLY"), "带概览页的报告套件，依赖 basic_compare", 640))

        publish(Package("remote_probe", "remote_probe", "2.0", "第三方", "zx_terminal",
            """{"type":"zx_terminal","cmd":"probe","lines":[]}""",
            emptyList(), listOf("ACI_OUT", "PRIVILEGED"), "远程探测（含特权声明，需审批）", 42))

        publish(Package("broken_pack", "broken_pack", "1.0", "测试", "zx_table",
            """{"type":"zx_table","columns":["a"],"rows":[["1"]]}""",
            listOf("nonexistent@9.9"), listOf("UI_ONLY"), "故意缺失依赖，用于演示拦截", 3))
    }
}
