package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 跨设备渲染 —— 手机 A 上的 Agent，在手机 B 的对话框里画卡。
 *
 * 走 ACI（AIDL Binder，同设备无 Root）。跨设备场景其实是：
 *   远端 Agent（飞书/QQ/微信那侧的 Bot）→ 本机 ACI → 本对话框气泡
 *
 * 三条必须守住：
 *   1. 未配对的设备一律拒绝，不能让任意来源往用户的对话框里塞 UI
 *   2. 本机离线时入队，恢复后重放，不能静默丢弃
 *   3. 远端下发的 DSL 走和本地完全相同的校验，不因为是"远程"就放宽
 */
object ZxRemote {

    enum class Trust { PAIRED, UNKNOWN, BLOCKED }

    data class Peer(val id: String, val name: String, val trust: Trust)

    /** 已配对设备表（真实工程由宿主持久化，这里保持内存态便于演示） */
    private val peers = linkedMapOf<String, Peer>()

    /** 离线期间积压的渲染请求 */
    private val pending = ArrayDeque<PendingCard>(32)

    data class PendingCard(
        val peerId: String, val component: String, val dsl: String, val at: Long
    )

    fun pair(id: String, name: String) { peers[id] = Peer(id, name, Trust.PAIRED) }
    fun block(id: String) { peers[id]?.let { peers[id] = it.copy(trust = Trust.BLOCKED) } }
    fun unpair(id: String) { peers.remove(id) }
    fun peers(): List<Peer> = peers.values.toList()

    /** 远端想画一张卡 */
    fun request(peerId: String, component: String, dsl: String): Result {
        val peer = peers[peerId]
            ?: return Result(false, "未配对设备：$peerId。请先在本机确认配对。")
        if (peer.trust == Trust.BLOCKED)
            return Result(false, "设备已被拉黑：$peerId")

        // 校验与本地完全同一套，不因为是远端就放宽
        val check = ZxDsl.validate(component, dsl)
        if (!check.ok) return Result(false, "来自 ${peer.name} 的 DSL 不合法：${check.reason}")

        return if (isOnline()) {
            ZxCardStore.emit("来自 ${peer.name}", component, dsl)
            Result(true, "已在对话框渲染 $component（来源：${peer.name}）")
        } else {
            pending.addLast(PendingCard(peerId, component, dsl, System.currentTimeMillis()))
            Result(true, "本机离线，已入队（${pending.size} 条），联网后自动渲染")
        }
    }

    /** 联网恢复后重放 */
    fun flush(): Int {
        if (!isOnline()) return 0
        var n = 0
        while (pending.isNotEmpty()) {
            val p = pending.removeFirst()
            val peer = peers[p.peerId] ?: continue
            if (peer.trust != Trust.PAIRED) continue   // 离线期间被拉黑了就丢弃
            ZxCardStore.emit("来自 ${peer.name}（离线补发）", p.component, p.dsl)
            n++
        }
        return n
    }

    fun pendingCount(): Int = pending.size

    /** 真实工程由宿主注入网络状态；这里给一个可替换的判定点 */
    private var onlineProbe: () -> Boolean = { true }
    fun attachProbe(probe: () -> Boolean) { onlineProbe = probe }
    fun isOnline(): Boolean = runCatching { onlineProbe() }.getOrDefault(false)

    /** 远端只想知道本机有哪些组件可用 */
    fun manifest(): String = JSONObject()
        .put("components", ZxDsl.COMPONENTS.joinToString(","))
        .put("widgets", "zx_pick_button,zx_slider,zx_editable_cell,zx_hunk_toggle,zx_progress,zx_badge")
        .put("containers", "zx_tabs,zx_accordion,zx_split,zx_carousel")
        .put("maxNestDepth", ZxNest.MAX_DEPTH)
        .put("accepts", "quro-ui/v1")
        .toString(2)

    /** 示例：远端下发的嵌套卡 */
    fun sampleNested(): String = """
        {"type":"zx_tabs","children":[
          {"tab":"趋势","type":"zx_chart","style":"bar","title":"周活跃",
           "points":[120,180,150,220,260,240,300],"labels":["一","二","三","四","五","六","日"]},
          {"tab":"明细","type":"zx_table","title":"数据表",
           "columns":["日","活跃"],"rows":[["一","120],["二","180],["三","150]]},
          {"tab":"日志","type":"zx_terminal","cmd":"tail -f access.log","exit":0,
           "lines":[{"lv":"ok","c":"127.0.0.1 GET / 200"}]}
        ]}
    """.trimIndent()
}
