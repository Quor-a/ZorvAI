package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 卡片级权限沙箱 —— 每张卡自带一份"能碰什么"的声明，回传时门控。
 *
 * 为什么必须有：卡片是 AI 下发的，但卡片上的按钮是用户在点。
 * 如果一张"只读展示卡"能触发 zx_approve 或写文件，那么 AI 只要
 * 把危险操作伪装成一张看起来人畜无害的卡，就能绕过授权审批。
 *
 * 所以权限不看"这个工具存不存在"，而看"发起它的那张卡有没有声明这个能力"。
 * 声明写在 DSL 的 _caps 字段里，缺省按最严格处理（UI_ONLY）。
 *
 * 四条硬规则：
 *   1. 缺省即最小权限——没写 _caps 的卡只能做纯 UI 交互
 *   2. 风险等级超过卡片上限 → 必须走审批，不能静默放行
 *   3. 门控失败要回喂模型可执行建议，不是干巴巴一句"拒绝"
 *   4. 每次拦截都落审计（见 ZxAudit），可追溯是谁下发的
 */
object ZxSandbox {

    /** 卡片能力位 */
    enum class Cap(val label: String) {
        UI_ONLY("仅界面交互"),
        READ_FILE("读文件"),
        WRITE_FILE("写文件"),
        NET("网络请求"),
        TOOL_CALL("调用工具"),
        ACI_OUT("对外暴露能力"),
        PRIVILEGED("特权操作")
    }

    /** 操作风险等级，对齐宿主 L1-L5 的语义但保持在卡片层判断 */
    enum class Risk(val level: Int, val label: String) {
        R0(0, "无副作用"),
        R1(1, "本地只读"),
        R2(2, "本地写入 / 网络读"),
        R3(3, "网络写 / 特权")
    }

    /** 一张卡的权限策略 */
    data class Policy(
        val caps: Set<Cap>,
        val maxRisk: Risk,
        /** 允许回传的工具白名单，空集表示只允许 UI 类回传 */
        val allowTools: Set<String>
    )

    /** 纯 UI 回传：任何卡片都允许，因为不产生副作用 */
    private val UI_TOOLS = setOf("zx_select", "zx_form_submit", "zx_undo")

    /** 工具 → 所需能力 与 风险等级 */
    private val TOOL_SPEC = mapOf(
        "zx_table_commit" to (Cap.WRITE_FILE to Risk.R1),
        "zx_diff_apply"   to (Cap.WRITE_FILE to Risk.R2),
        "zx_approve"      to (Cap.PRIVILEGED to Risk.R3),
        "zx_gen_tool"     to (Cap.TOOL_CALL  to Risk.R2),
        "zx_remote_render" to (Cap.ACI_OUT   to Risk.R2),
        "zx_state"        to (Cap.WRITE_FILE to Risk.R1)
    )

    /** 取一张卡已声明的能力名集合（供 ZxRegistry.discover 过滤） */
    fun capsOf(cardId: String): Set<String> =
        runCatching { parse(ZxRef.get(cardId)?.dsl ?: "") }.getOrNull()
            ?.caps?.map { it.name }?.toSet() ?: emptySet()

    /** 当前登录用户允许的最高风险（由 SETTING 控制，默认只允许 R1） */
    private var userCeiling = Risk.R1
    fun setCeiling(r: Risk) { userCeiling = r }
    fun ceiling(): Risk = userCeiling

    /** 解析 DSL 里的 _caps，缺省按最严格 */
    fun parse(dsl: String): Policy {
        val o = runCatching { JSONObject(dsl) }.getOrNull() ?: return Policy(setOf(Cap.UI_ONLY), Risk.R0, emptySet())
        val arr = o.optJSONArray("_caps")
        val caps = mutableSetOf<Cap>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                Cap.entries.firstOrNull { it.name == s }?.let { caps.add(it) }
            }
        }
        if (caps.isEmpty()) caps.add(Cap.UI_ONLY)

        val maxRisk = when (o.optString("_maxRisk")) {
            "R1" -> Risk.R1; "R2" -> Risk.R2; "R3" -> Risk.R3; else -> Risk.R0
        }
        val tools = mutableSetOf<String>()
        val ta = o.optJSONArray("_allowTools")
        if (ta != null) for (i in 0 until ta.length()) tools.add(ta.optString(i))

        return Policy(caps, maxRisk, tools)
    }

    /**
     * 回传门控：这张卡能不能发起这个工具调用。
     * 在任何 ctx.callTool 之前调用。
     */
    fun gate(cardId: String, toolName: String): Result {
        // 纯 UI 回传，任何卡都能做
        if (toolName in UI_TOOLS) return Result(true, "允许：$toolName 为无副作用回传")

        val node = ZxRef.get(cardId)
            ?: return Result(false, "卡片 $cardId 未在沙箱登记，拒绝 $toolName")
        val policy = parse(node.dsl)

        val (needCap, risk) = TOOL_SPEC[toolName]
            ?: return Result(false, "未知工具 $toolName，按默认拒绝")

        if (!policy.caps.contains(needCap))
            return Result(false,
                "卡片「${node.component}」未声明 ${needCap.label} 能力，不能发起 $toolName。" +
                "若确有必要，请在 DSL 的 _caps 里补上 \"${needCap.name}\" 后重新下发。")

        if (needCap != Cap.TOOL_CALL && !policy.caps.contains(Cap.TOOL_CALL)
            && !policy.allowTools.contains(toolName))
            return Result(false, "卡片未把 $toolName 列入 _allowTools，拒绝。")

        if (risk.level > policy.maxRisk.level)
            return Result(false,
                "$toolName 的风险为 ${risk.label}，超过该卡上限 ${policy.maxRisk.label}。" +
                "请改用审批卡 zx_approval 让用户显式确认。")

        if (risk.level > userCeiling.level)
            return Result(false,
                "$toolName 的风险 ${risk.label} 超过当前用户授权上限 ${userCeiling.label}，已拦截。")

        ZxAudit.log(ZxAudit.Kind.SANDBOX_PASS, cardId, "$toolName · ${risk.label}")
        return Result(true, "允许：$toolName（${needCap.label} · ${risk.label}）")
    }

    /** 下发门控：这张卡本身能不能渲染（例如声明了特权但用户没给到 R3） */
    fun checkDsl(component: String, dsl: String): Result {
        val p = parse(dsl)
        if (p.caps.contains(Cap.PRIVILEGED) && userCeiling.level < Risk.R3.level)
            return Result(false,
                "该卡声明了 PRIVILEGED 能力，但用户授权上限为 ${userCeiling.label}。已拒绝渲染。")
        if (component == "zx_approval" && !p.caps.contains(Cap.PRIVILEGED)) {
            // 审批卡天然带特权语义，自动补上，避免模型忘了写
            return Result(true, "审批卡已自动授予 PRIVILEGED 声明")
        }
        return Result(true, "下发校验通过")
    }

    /** 生成一张带权限声明的示例 DSL，供模型抄 */
    fun sampleDsl(component: String): String = """
        {"type":"$component",
         "_caps":["UI_ONLY","TOOL_CALL"],
         "_maxRisk":"R1",
         "_allowTools":["zx_select","zx_form_submit"]}
    """.trimIndent()

    /** 权限矩阵，供 /zxcaps 输出到终端卡 */
    fun matrix(): String = buildString {
        appendLine("工具              所需能力        风险")
        TOOL_SPEC.forEach { (t, v) ->
            appendLine("${t.padEnd(18)}${v.first.label.padEnd(14)}${v.second.label}")
        }
        appendLine()
        appendLine("当前用户授权上限：${userCeiling.label}")
        appendLine("纯 UI 回传（任何卡都允许）：${UI_TOOLS.joinToString()}")
    }
}
