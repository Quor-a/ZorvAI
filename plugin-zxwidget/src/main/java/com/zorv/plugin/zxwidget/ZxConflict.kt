package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.max

/**
 * 冲突解决 —— 把 CRDT 的"合并会覆盖什么"变成用户能看懂、能选的卡片。
 *
 * 为什么需要：⑮ 已经做了 CRDT 多端同步，合并是自动的、收敛的。
 * 但"自动合并"和"用户接受"是两件事：
 *
 *   自动合并解决的是"两边最终一致"，
 *   没解决"合并结果是不是用户想要的"。
 *
 * 典型场景：手机上把预算改成 2400，平板上改成 1800。
 * CRDT 按时间戳选一个（比如后写的 1800），
 * 另一个被静默丢弃——用户永远不知道自己改的那个 2400 没了。
 * 等他下次打开看到 1800，只会觉得"这 App 有 bug"。
 *
 * 所以要做三件事：
 *
 *   一、**合并前先展示代价**。previewConflict() 只报告
 *       "如果我合并会覆盖什么"，不修改状态——
 *       让用户在按下之前就看见后果。
 *
 *   二、**给出三组选项**：保留本地 / 采用远端 / 手工填一个新值。
 *       只给"确定/取消"等于没给选。
 *
 *   三、**解决后要能撤销**。用户选错了要能回去，
 *       否则"解决冲突"反而成了不可逆操作——
 *       这比不解决更糟。
 *
 * 关键设计：本模块**不直接改 CRDT 状态**，
 * 只产出"用户选了什么"，由调用方去执行合并。
 * 否则撤销栈和审计流水都绕不过去。
 */
object ZxConflict {

    enum class Side { LOCAL, REMOTE, MANUAL }

    enum class Severity {
        /** 值不同但都是合法修改 */
        SOFT,
        /** 一边删了另一边改过的数据 */
        DELETE_VS_EDIT,
        /** 结构冲突（类型变了、形状不兼容） */
        SHAPE
    }

    data class Item(
        val cardId: String,
        /** 字段路径，如 rows[0].v[1] */
        val path: String,
        val label: String,
        val local: String,
        val remote: String,
        val severity: Severity,
        /** 谁更新（谁的 ts 大） */
        val newer: Side,
        val site: String = ""
    ) {
        fun line(): String = buildString {
            val mark = when (severity) {
                Severity.SOFT -> "值不同"
                Severity.DELETE_VS_EDIT -> "一边删除、一边修改"
                Severity.SHAPE -> "结构不兼容"
            }
            append("%-8s %-16s %s".format(cardId, path, mark))
        }

        fun sevLabel() = when (severity) {
            Severity.SOFT -> "值不同"; Severity.DELETE_VS_EDIT -> "删除 vs 修改"; Severity.SHAPE -> "结构不兼容"
        }
    }

    data class Resolution(
        val cardId: String,
        val path: String,
        val side: Side,
        val value: String,
        val t: Long = System.currentTimeMillis()
    )

    /** 已解决的记录，供撤销 */
    private val resolved = ArrayDeque<Resolution>()
    private const val MAX_HIST = 40

    /**
     * 预览冲突：只报告，不改状态。
     *
     * 这是整个模块最关键的一条——
     * 如果 preview 顺手把状态改了，用户就再也看不到"原本是什么"了。
     */
    fun preview(local: Map<String, String>, remote: Map<String, String>): List<Item> {
        val out = mutableListOf<Item>()
        val keys = local.keys + remote.keys
        for (k in keys) {
            val l = local[k]
            val r = remote[k]
            if (l == null && r == null) continue
            // 一边有、一边没有 = 一边删了
            if (l == null || r == null) {
                out.add(Item(cardOf(k), pathOf(k), labelOf(k),
                             l ?: "（已删除）", r ?: "（已删除）",
                             Severity.DELETE_VS_EDIT,
                             if (r == null) Side.LOCAL else Side.REMOTE))
                continue
            }
            if (l == r) continue
            out.add(Item(cardOf(k), pathOf(k), labelOf(k), l, r,
                         Severity.SOFT, Side.REMOTE))
        }
        return out
    }

    private fun cardOf(k: String) = k.substringBefore('.', k)
    private fun pathOf(k: String) = k.substringAfter('.', "")
    private fun labelOf(k: String) = k.substringAfterLast('.', k)

    /** 应用一个解决。只记录，不直接改 CRDT —— 由调用方执行 */
    fun resolve(cardId: String, path: String, side: Side, manual: String = ""): Resolution {
        val v = when (side) {
            Side.LOCAL -> curLocal[cardId + "." + path] ?: ""
            Side.REMOTE -> curRemote[cardId + "." + path] ?: ""
            Side.MANUAL -> manual
        }
        val r = Resolution(cardId, path, side, v)
        resolved.addLast(r)
        if (resolved.size > MAX_HIST) resolved.removeFirst()
        return r
    }

    /** 演示用的一对本地/远端值（真实实现从 CRDT 读取） */
    private val curLocal = mutableMapOf<String, String>()
    private val curRemote = mutableMapOf<String, String>()

    fun curSnapshotLocal(): Map<String, String> = curLocal.toMap()
    fun curSnapshotRemote(): Map<String, String> = curRemote.toMap()

    fun seed(local: Map<String, String>, remote: Map<String, String>) {
        curLocal.clear(); curLocal.putAll(local)
        curRemote.clear(); curRemote.putAll(remote)
    }

    /** 撤销上一次解决。解决冲突本身必须可逆。 */
    fun undo(): Resolution? = resolved.removeLastOrNull()

    fun history(): List<Resolution> = resolved.toList()

    fun clear() { resolved.clear(); curLocal.clear(); curRemote.clear() }

    fun report(items: List<Item>): String = buildString {
        if (items.isEmpty()) { appendLine("没有冲突。"); return@buildString }
        val bySev = items.groupBy { it.severity }
        appendLine("预览 ${items.size} 处冲突（未修改任何状态）：")
        Severity.values().forEach { sev ->
            val g = bySev[sev] ?: return@forEach
            appendLine()
            appendLine("【${g.first().sevLabel()}】${g.size} 处")
            g.take(4).forEach { it2 ->
                appendLine("  %-8s %-14s".format(it2.cardId, it2.path))
                appendLine("      本地：${it2.local}")
                appendLine("      远端：${it2.remote}")
            }
            if (g.size > 4) appendLine("  …还有 ${g.size - 4} 处")
        }
        appendLine()
        appendLine("每处给三组选项：保留本地 / 采用远端 / 手工填新值。")
        appendLine("只给「确定 / 取消」等于没给选。")
    }

    /** 生成 zx_conflict 组件的 DSL，供渲染 */
    fun toDsl(items: List<Item>): String {
        val arr = org.json.JSONArray()
        items.forEach { i ->
            arr.put(JSONObject().apply {
                put("card", i.cardId)
                put("path", i.path)
                put("label", i.label)
                put("local", i.local)
                put("remote", i.remote)
                put("sev", i.severity.name)
                put("newer", i.newer.name)
            })
        }
        return JSONObject().apply {
            put("type", "zx_conflict")
            put("items", arr)
            put("n", items.size)
        }.toString()
    }

    fun guide(): String = """
        zx_conflict(op=preview|resolve|undo|report|guide, card?, path?, side?, value?)

        把 CRDT 的「合并会覆盖什么」变成用户能看懂、能选的卡片。

        为什么需要：自动合并解决的是「两边最终一致」，
        没解决「合并结果是不是用户想要的」。

        典型场景：手机上把预算改成 2400，平板上改成 1800。
        CRDT 按时间戳选一个，另一个被静默丢弃——
        用户永远不知道自己改的那个 2400 没了。
        等他下次打开看到 1800，只会觉得「这 App 有 bug」。

        三件事：
          一、合并前先展示代价——preview 只报告不修改状态
          二、给三组选项：保留本地 / 采用远端 / 手工填新值
             只给「确定 / 取消」等于没给选
          三、解决后要能撤销——否则「解决冲突」成了不可逆操作，
              这比不解决更糟

        本模块不直接改 CRDT 状态，只产出「用户选了什么」，
        由调用方执行合并——否则撤销栈和审计流水都绕不过去。
    """.trimIndent()
}
