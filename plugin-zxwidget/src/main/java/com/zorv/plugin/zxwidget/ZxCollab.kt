package com.zorv.plugin.zxwidget

/**
 * 协同编辑 —— 让"谁在改"看得见。
 *
 * ⑮ CRDT 解决了多端数据**最终一致**，
 * 但它解决的是"合并结果对不对"，
 * 没解决"**过程里用户知不知道**"。
 *
 *   手机上改第 3 行，平板上也在改第 3 行。
 *   CRDT 按时间戳选一个，另一个被静默丢弃（㉒ 补了冲突提示），
 *   但在此之前——**两边都不知道对方正在改这一行**。
 *
 * 等改完了才提示冲突，已经晚了：
 * 两个人都花了力气填，才知道要重来一次。
 * 真正有用的是**改之前就看见**。
 *
 * 所以这层做三件事：
 *
 *   一、**在线成员**。谁在、在哪台设备、在改哪张卡。
 *
 *   二、**远端光标 / 选中字段**。对方正在填哪一格，
 *       这一格要**在界面上标出来**。
 *
 *   三、**正在编辑的字段**要高亮且**不可被同时抢**（提示，不是锁）。
 *
 * 三条硬约束：
 *
 *   一、**这层不碰数据**。合并在 ZxSync，
 *       冲突解决在 ㉒。这层只展示"谁在哪"。
 *       越权去写数据，撤销栈、审计流水、事务全都绕不过去。
 *
 *   二、**光标只记位置，不记内容**。
 *       内容是 CRDT 的事；光标里带内容等于开了第二条数据通路，
 *       两条通路必然不一致。
 *
 *   三、**状态要会过期**。
 *       设备断网、App 被杀，光标不会自己消失——
 *       界面上会留下一堆**永远亮着的幽灵光标**，
 *       用户会以为有人在看，实际上那个人早就走了。
 *       所以每条状态带时间戳，超过 TTL 就清掉。
 *       这是这类功能最容易漏的一条，漏了整个机制就是假的。
 */
object ZxCollab {

    /** 谁在编辑哪个字段 */
    data class Cursor(
        val siteId: String,
        val siteName: String,
        val cardId: String,
        /** 字段路径，空串表示整张卡 */
        val path: String = "",
        val ts: Long = nowMs()
    )

    /** 在线成员 */
    data class Peer(
        val siteId: String,
        val siteName: String,
        val cardId: String = "",
        val ts: Long = nowMs()
    )

    /** 超过这个时间没心跳就当作离线 */
    private const val TTL_MS = 30_000L

    /**
     * TTL 必须用真实时钟，不能用逻辑时钟。
     * 逻辑时钟只在有事件时才推进，设备一离线就停住，
     * 于是「多久没心跳」永远是 0，状态永不过期——
     * 幽灵光标就清不掉了。
     */
    private fun nowMs(): Long = System.currentTimeMillis()

    private val peers = LinkedHashMap<String, Peer>()
    private val cursors = LinkedHashMap<String, Cursor>()
    /** 本地方向：我自己在改哪，要广播出去 */
    private var local: Cursor? = null

    fun attachLocal(siteId: String, name: String) { ZxSync.attachSite(siteId, name) }

    /** 我在改这个字段了（广播给别的端） */
    fun focus(cardId: String, path: String = ""): Cursor? {
        val s = ZxSync.site()
        local = Cursor(s.id, s.name, cardId, path)
        return local
    }

    /** 我不改了 */
    fun blur() { local = null }

    fun localCursor(): Cursor? = local

    /** 收到远端心跳/光标 */
    fun onPeer(siteId: String, name: String, cardId: String = "") {
        if (siteId == ZxSync.site().id) return     // 不记自己
        peers[siteId] = Peer(siteId, name, cardId)
    }

    fun onCursor(siteId: String, name: String, cardId: String, path: String = "") {
        if (siteId == ZxSync.site().id) return
        cursors[siteId] = Cursor(siteId, name, cardId, path)
        peers[siteId] = Peer(siteId, name, cardId)
    }

    fun onLeave(siteId: String) { peers.remove(siteId); cursors.remove(siteId) }

    /**
     * 清掉过期状态。返回清了几条。
     * 每次读取前都该调一次——不调就是幽灵光标。
     */
    fun sweep(now: Long = nowMs()): Int {
        var n = 0
        val dp = peers.filter { (_, v) -> now - v.ts > TTL_MS }.keys.toList()
        dp.forEach { peers.remove(it); n++ }
        val dc = cursors.filter { (_, v) -> now - v.ts > TTL_MS }.keys.toList()
        dc.forEach { cursors.remove(it); n++ }
        return n
    }

    fun peers(): List<Peer> { sweep(); return peers.values.toList() }
    fun cursors(): List<Cursor> { sweep(); return cursors.values.toList() }

    /** 这张卡上有谁在看 */
    fun onCard(cardId: String): List<Peer> = peers().filter { it.cardId == cardId }

    /** 这个字段上有谁在改（不含自己） */
    fun onField(cardId: String, path: String): List<Cursor> =
        cursors().filter { it.cardId == cardId && it.path == path }

    /**
     * 我和别人撞到同一个字段上了。
     * 只提示，不阻止——阻止输入用户会以为键盘坏了。
     */
    fun clash(cardId: String, path: String): Boolean {
        val me = local ?: return false
        return me.cardId == cardId && me.path == path &&
            onField(cardId, path).isNotEmpty()
    }

    fun reset() { peers.clear(); cursors.clear(); local = null }

    fun format(): String = buildString {
        sweep()
        appendLine("协同状态（TTL ${TTL_MS / 1000}s）")
        if (local != null)
            appendLine("我在：${local!!.cardId}${local!!.path.takeIf { it.isNotBlank() }?.let { " / $it" } ?: ""}")
        if (peers.isEmpty()) appendLine("没有其他在线成员。")
        else {
            appendLine("在线 ${peers.size} 人：")
            peers.values.forEach { p ->
                val c = cursors[p.siteId]
                appendLine("  ${p.siteName}" +
                    (p.cardId.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                    (c?.path?.takeIf { it.isNotBlank() }?.let { " · 正在改 $it" } ?: ""))
            }
        }
    }

    fun demo(): String {
        reset()
        attachLocal("phone", "手机")
        return buildString {
            appendLine("示例：手机 + 平板 + 电脑")
            appendLine()
            onPeer("tablet", "平板", "budget")
            onCursor("tablet", "平板", "budget", "rows[2][1]")
            onPeer("pc", "电脑", "chart")
            appendLine(format().trimEnd())
            appendLine()
            focus("budget", "rows[2][1]")
            appendLine("我也去改 rows[2][1]：")
            appendLine("  撞车：" + clash("budget", "rows[2][1]"))
            appendLine("  （只提示，不阻止输入——阻止会让人以为键盘坏了）")
            appendLine()
            focus("budget", "rows[0][1]")
            appendLine("我改去 rows[0][1]：")
            appendLine("  撞车：" + clash("budget", "rows[0][1]"))
            appendLine()
            // 模拟平板超时：模拟一个远超 TTL 的时间戳
            cursors["tablet"] = cursors["tablet"]!!.copy(ts = nowMs() - TTL_MS - 1)
            peers["tablet"] = peers["tablet"]!!.copy(ts = nowMs() - TTL_MS - 1)
            val swept = sweep(nowMs())
            appendLine("平板超过 ${TTL_MS / 1000}s 没心跳：")
            appendLine("  清掉 $swept 条（幽灵光标会一直亮着，必须清）")
            appendLine("  剩余在线：" + peers().joinToString("、") { it.siteName })
        }
    }

    fun report(): String = buildString {
        appendLine("协同编辑（让「谁在改」看得见）")
        appendLine()
        appendLine("⑮ CRDT 解决了多端数据**最终一致**，")
        appendLine("但它解决的是「合并结果对不对」，")
        appendLine("没解决「**过程里用户知不知道**」。")
        appendLine()
        appendLine("  手机上改第 3 行，平板上也在改第 3 行。")
        appendLine("  CRDT 按时间戳选一个，另一个被静默丢弃，")
        appendLine("  但在此之前——**两边都不知道对方正在改这一行**。")
        appendLine()
        appendLine("等改完了才提示冲突，已经晚了：")
        appendLine("两个人都花了力气填，才知道要重来一次。")
        appendLine("真正有用的是**改之前就看见**。")
        appendLine()
        appendLine("三条硬约束：")
        appendLine("  一、这层不碰数据（合并在 CRDT，冲突解决在 ㉒）")
        appendLine("  二、光标只记位置不记内容（内容是 CRDT 的事）")
        appendLine("  三、状态要会过期（不清就是幽灵光标）")
        appendLine()
        appendLine("第三条最容易漏，漏了整个机制就是假的：")
        appendLine("设备断网、App 被杀，光标不会自己消失，")
        appendLine("界面上会留下一堆**永远亮着的幽灵光标**，")
        appendLine("用户会以为有人在看，实际上那个人早就走了。")
    }

    fun guide(): String = """
        zx_collab(op=get|focus|blur|clash|sweep|demo|report|guide, card?, path?)

        让「谁在改」看得见。

        ⑮ CRDT 解决了多端数据最终一致，但没解决
        「过程里用户知不知道」。

          手机上改第 3 行，平板上也在改第 3 行
          CRDT 按时间戳选一个，另一个被静默丢弃
          但在此之前——两边都不知道对方正在改这一行

        等改完了才提示冲突，已经晚了。
        真正有用的是改之前就看见。

        三条硬约束：
          一、这层不碰数据（合并在 CRDT，冲突解决在 ㉒）
          二、光标只记位置不记内容
          三、状态要会过期（TTL 30s，不清就是幽灵光标）

        第三条最容易漏，漏了整个机制就是假的：
        界面上会留下一堆永远亮着的幽灵光标。
    """.trimIndent()
}
