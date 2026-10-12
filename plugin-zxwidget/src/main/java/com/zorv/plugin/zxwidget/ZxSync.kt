package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 卡片状态的 CRDT 同步 —— 多端离线编辑，联网后自动合并且不丢改动。
 *
 * 场景：手机上在填一张参数表，平板上同一会话也在改。两边都离线改了同一个格子，
 * 联网后怎么办？"后写的赢"会静默丢掉一边，"报错让用户选"在气泡里太重。
 *
 * 这里用「每个字段一个 LWW-Register（Last-Write-Wins）」：
 *   值 + 逻辑时钟 + 站点 ID
 * 比较时先比时钟，时钟相同再比站点 ID——这样比较是全序的，
 * 不存在"两边都一样大"的模糊情况，任意两台设备各自合并都会收敛到同一结果。
 *
 * 为什么不用完整 CRDT（比如 RGA / OR-Set）：
 *   卡片状态是「路径 → 值」的扁平字典，不是有序文本。
 *   字典场景下 LWW-Register 就够了，上复杂结构只会增加实现错误的机会。
 *
 * 三条硬规则：
 *   1. 站点 ID 必须全局唯一且稳定（用设备指纹 + 安装实例，不能每次启动都变）
 *   2. 时钟回拨要处理（设备时间被改过），用「见到的最大时钟 + 1」而不是本地时间
 *   3. 合并必须幂等且可交换——任意顺序、任意次数合并，结果一致
 */
object ZxSync {

    /** 一个字段的 LWW-Register */
    data class Reg(
        val value: String,
        val ts: Long,
        val site: String
    ) {
        /** 全序比较：先比时钟，时钟相同比站点 ID */
        fun beats(other: Reg?): Boolean {
            if (other == null) return true
            if (ts != other.ts) return ts > other.ts
            return site > other.site
        }
    }

    data class Site(
        val id: String,
        val name: String
    )

    /** 本地站点标识 */
    private var localSite = Site("unknown", "本机")

    /** 逻辑时钟：取「见过的最大时钟 + 1」，不依赖本地时间，抗时钟回拨 */
    private var logicalClock = 0L

    fun attachSite(id: String, name: String) { localSite = Site(id, name) }
    fun site(): Site = localSite
    fun clock(): Long = logicalClock

    private fun tick(incoming: Long = 0): Long {
        logicalClock = maxOf(logicalClock, incoming) + 1
        return logicalClock
    }

    /**
     * 全局状态：cardId -> (path -> Reg)
     * 只存用户交互产生的字段值，DSL 本身由会话历史负责。
     */
    private val registers = linkedMapOf<String, LinkedHashMap<String, Reg>>()

    /** 本地改一个字段 */
    fun setLocal(cardId: String, path: String, value: String): Reg {
        val r = Reg(value, tick(), localSite.id)
        registers.getOrPut(cardId) { linkedMapOf() }[path] = r
        return r
    }

    fun getLocal(cardId: String, path: String): Reg? = registers[cardId]?.get(path)

    fun allLocal(): Map<String, Map<String, Reg>> = registers

    /**
     * 合并远端状态。返回本次实际被覆盖的字段数（用于提示用户"同步了 N 处"）。
     * 幂等：重复合并同一份远端状态不会改变结果。
     */
    fun merge(remote: Map<String, Map<String, Reg>>): Int {
        var changed = 0
        for ((cardId, paths) in remote) {
            val local = registers.getOrPut(cardId) { linkedMapOf() }
            for ((path, incoming) in paths) {
                val cur = local[path]
                if (incoming.beats(cur)) {
                    local[path] = incoming
                    if (cur == null || cur.value != incoming.value) changed++
                    // 时钟推进，保证后续本地写入一定赢过这份远端数据
                    tick(incoming.ts)
                }
            }
        }
        return changed
    }

    /** 合并后再写回依赖图，让联动在同步后也能生效 */
    fun applyToRef(): Int {
        var n = 0
        registers.forEach { (cardId, paths) ->
            val node = ZxRef.get(cardId) ?: return@forEach
            paths.forEach { (path, reg) ->
                if (node.values[path] != reg.value) {
                    node.values[path] = reg.value
                    ZxRef.propagate(cardId, path, reg.value)
                    n++
                }
            }
        }
        return n
    }

    /* ── 序列化：导出/导入增量 ────────────────────────────────── */

    /** 导出为紧凑 JSON 数组：[cardId, path, value, ts, site] */
    fun exportDelta(sinceTs: Long = 0): String {
        val arr = org.json.JSONArray()
        registers.forEach { (cardId, paths) ->
            paths.forEach { (path, reg) ->
                if (reg.ts > sinceTs) {
                    arr.put(org.json.JSONArray().apply {
                        put(cardId); put(path); put(reg.value); put(reg.ts); put(reg.site)
                    })
                }
            }
        }
        return JSONObject().put("site", localSite.id)
            .put("clock", logicalClock)
            .put("delta", arr).toString()
    }

    fun importDelta(raw: String): Result {
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Result(false, "同步包不是合法 JSON")
        val arr = o.optJSONArray("delta") ?: return Result(false, "同步包缺少 delta")
        val remote = linkedMapOf<String, MutableMap<String, Reg>>()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            if (a.length() < 5) continue
            val cardId = a.optString(0)
            val path = a.optString(1)
            val value = a.optString(2)
            val ts = a.optLong(3)
            val site = a.optString(4)
            remote.getOrPut(cardId) { linkedMapOf() }[path] = Reg(value, ts, site)
        }
        val n = merge(remote)
        val applied = applyToRef()
        tick(o.optLong("clock", 0))
        ZxAudit.log(ZxAudit.Kind.SYNC, localSite.id, "合并 ${remote.size} 张卡 · 覆盖 $n 处 · 联动 $applied 个")
        return Result(true, "已合并 ${remote.size} 张卡，覆盖 $n 处冲突字段，联动刷新 $applied 个下游。")
    }

    /**
     * 冲突预览：不合并，只报告"如果我合并会覆盖什么"。
     * 让用户在授权前看见代价，而不是事后发现自己的改动没了。
     */
    fun previewConflict(raw: String): Result {
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Result(false, "同步包不是合法 JSON")
        val arr = o.optJSONArray("delta") ?: return Result(false, "缺少 delta")
        val lines = org.json.JSONArray()
        var conflicts = 0
        for (i in 0 until arr.length()) {
            val a = arr.optJSONArray(i) ?: continue
            if (a.length() < 5) continue
            val cardId = a.optString(0); val path = a.optString(1)
            val value = a.optString(2); val ts = a.optLong(3); val site = a.optString(4)
            val cur = getLocal(cardId, path)
            val incoming = Reg(value, ts, site)
            if (cur != null && cur.value != value) {
                conflicts++
                lines.put(JSONObject().put("lv", "warn")
                    .put("c", "$cardId.$path  本机「${cur.value}」 ← 远端「$value」（$site）"))
            } else if (cur == null) {
                lines.put(JSONObject().put("lv", "out").put("c", "$cardId.$path  ← 新增「$value」"))
            }
        }
        if (conflicts == 0) lines.put(JSONObject().put("lv", "ok").put("c", "无冲突，可安全合并"))
        else lines.put(JSONObject().put("lv", "err").put("c", "共 $conflicts 处将被远端覆盖"))
        ZxCardStore.emit(null, "zx_terminal", JSONObject()
            .put("type", "zx_terminal").put("cmd", "zx_sync --preview")
            .put("exit", 0).put("lines", lines).toString())
        return Result(true, "冲突预览：$conflicts 处将被覆盖（详见终端卡）")
    }

    /** 时钟回拨测试：模拟收到一个时钟远超本地的包 */
    fun simulateClockSkew(hugeTs: Long) { tick(hugeTs) }

    fun reset() { registers.clear(); logicalClock = 0 }
}
