package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 审计流水 —— 卡片世界里的每一件事都留痕。
 *
 * 为什么需要：气泡里的卡片是 AI 生成的，但后果是用户在承担。
 * 一张卡触发了写文件、一次授权放行了 L3 特权、一个外部设备推了张卡进来——
 * 事后用户问"谁干的"，必须能答得上来。
 *
 * 设计取舍：
 *   1. 环形缓冲（上限 500 条），不无限增长 —— 审计是给人看的，不是大数据
 *   2. 敏感条目单独标记，支持只筛敏感项
 *   3. 导出成 zx_terminal 卡，直接在气泡里看，不跳转、不导出文件
 *   4. 记录但不阻断 —— 审计不该影响主链路性能，所以写内存 + 异步落盘
 */
object ZxAudit {

    enum class Kind(val label: String, val sensitive: Boolean = false) {
        RENDER("渲染卡片"),
        INTERACT("用户交互"),
        TOOL_CALL("工具回传"),
        APPROVE("授权", sensitive = true),
        GEN_TOOL("生成工具", sensitive = true),
        REMOTE("远端渲染", sensitive = true),
        SANDBOX_DENY("沙箱拦截", sensitive = true),
        SANDBOX_PASS("沙箱放行"),
        ROLLBACK("版本回溯"),
        ROUTE("路由决策"),
        TEMPLATE("模板"),
        TX("事务提交"),
        TX_ROLLBACK("事务回滚", sensitive = true),
        INTENT("意图编译"),
        HEAL("DSL 自愈"),
        CACHE("缓存"),
        SYNC("多端同步", sensitive = true),
        REVIEW("自评降级"),
        A11Y("无障碍"),
        MARKET("卡片市场", sensitive = true)
    }

    data class Entry(
        val kind: Kind,
        val cardId: String,
        val detail: String,
        val at: Long = System.currentTimeMillis()
    ) {
        val sensitive: Boolean get() = kind.sensitive
    }

    private const val MAX = 500
    private val buf = ArrayDeque<Entry>(MAX)

    fun log(kind: Kind, cardId: String, detail: String) {
        buf.addLast(Entry(kind, cardId, detail))
        if (buf.size > MAX) buf.removeFirst()
    }

    fun all(): List<Entry> = buf.toList()

    fun query(kinds: Set<Kind>? = null, sensitiveOnly: Boolean = false): List<Entry> =
        buf.filter { (kinds == null || it.kind in kinds) && (!sensitiveOnly || it.sensitive) }

    fun countSensitive(): Int = buf.count { it.sensitive }

    /** 时间格式化：只给时分秒，审计不需要日期精度 */
    private fun hms(t: Long): String {
        val d = java.util.Date(t)
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(d)
    }

    /** 导出为终端卡 DSL：分级着色，敏感项用 err 红 */
    fun exportAsTerminal(sensitiveOnly: Boolean = false): String {
        val list = query(sensitiveOnly = sensitiveOnly)
        val lines = JSONArray()
        if (list.isEmpty()) {
            lines.put(JSONObject().put("lv", "out")
                .put("c", sensitiveOnly.toString().let { if (sensitiveOnly) "没有敏感操作记录" else "暂无记录" }))
        } else {
            lines.put(JSONObject().put("lv", "cmd").put("c", "zx_audit${if (sensitiveOnly) " --sensitive" else ""}"))
            list.takeLast(40).forEach { e ->
                lines.put(JSONObject()
                    .put("lv", if (e.sensitive) "err" else "out")
                    .put("c", "${hms(e.at)}  [${e.kind.label}]  ${e.cardId}  ${e.detail}"))
            }
            lines.put(JSONObject().put("lv", "ok")
                .put("c", "共 ${buf.size} 条，敏感 ${countSensitive()} 条（环形缓冲上限 $MAX）"))
        }
        return JSONObject().put("type", "zx_terminal")
            .put("cmd", "zx_audit").put("exit", 0).put("lines", lines).toString()
    }

    /** 统计摘要，供状态行显示 */
    fun summary(): String {
        val s = countSensitive()
        return "${buf.size} 条 · 敏感 $s"
    }

    fun clear() { buf.clear() }
}
