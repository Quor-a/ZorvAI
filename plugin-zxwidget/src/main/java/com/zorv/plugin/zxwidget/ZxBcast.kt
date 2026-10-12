package com.zorv.plugin.zxwidget

/**
 * 离线意图广播 —— 断网时先把"打算改哪"排队。
 *
 * ⑮ CRDT 解决的是"两个改动最终怎么合并"，
 * ⑫ 协同编辑解决的是"改之前就看见对方在改"。
 * 两者都**假设网络最终可达**，但没回答中间那段时间：
 *
 *   地铁上改了三格，出隧道前对方完全不知道——
 *   而对方可能正在改**同一格**。
 *
 * 等联网了再合并，两个人已经各自改完了，
 * 冲突提示这才出现——**冲突提示来得太晚，等于没有提示**。
 * ㉚ 的冲突合并能判断怎么并，但并完之后
 * "我白填了"这个感受已经产生了。
 *
 * 所以要做的是**在改动发生之前就把意图发出去**：
 *
 *   我打算改 rows[2][1]
 *   （没网 → 入队，联网后自动补发）
 *
 * 意图比改动小得多，所以**离线时也能攒下来**——
 * 一条意图几十字节，一次改动可能几 KB。
 *
 * 四条约束：
 *
 *   一、**意图不是改动，不参与合并**。
 *       它只是"预告"。真改动还是走 CRDT。
 *       让意图参与合并等于开了第二条数据通路，
 *       两条通路必然不一致。
 *
 *   二、**过期意图要作废**。
 *       攒了两小时的"我打算改第 3 行"，
 *       发出去时对方早就改完了——
 *       这条意图不只没用，还会让人以为
 *       "有人正在改"从而不敢下手（幽灵意图）。
 *       所以有 TTL。
 *
 *   三、**入队不能静默失败**。
 *       满了就丢最旧的并告知，不能假装存下了——
 *       用户以为预告已发，实际上没人收到，
 *       比不发更糟。
 *
 *   四、**联网补发要有幂等**。
 *       补发失败会重试，同一条意图发两次
 *       对方会看到两条"正在改"，
 *       所以每条带 seq，对端按 seq 去重。
 */
object ZxBcast {

    /** 一条意图 */
    data class Intent(
        val seq: Long,
        val cardId: String,
        val path: String,
        /** 意图类型：edit / focus / delete / move */
        val kind: String = "edit",
        val ts: Long = System.currentTimeMillis()
    ) {
        fun expired(now: Long = System.currentTimeMillis()) = now - ts > TTL_MS
    }

    /** 意图的有效期：超过这个时间没发出去就作废 */
    const val TTL_MS = 120_000L

    private const val MAX_QUEUE = 200

    private var seq = 0L
    private val queue = ArrayDeque<Intent>()
    /** 对端已收到的最大 seq，用于去重 */
    private val ack = LinkedHashMap<String, Long>()
    private var online = true
    private var dropped = 0

    fun setOnline(v: Boolean) { online = v }
    fun isOnline() = online

    /**
     * 广播一条意图。
     * 离线时入队；满了丢最旧的**并如实返回**。
     */
    fun send(cardId: String, path: String, kind: String = "edit"): String {
        if (!online) return enqueue(cardId, path, kind)
        val i = Intent(++seq, cardId, path, kind)
        return "已广播 ${i.kind} ${i.cardId}/${i.path}（seq=${i.seq}）"
    }

    private fun enqueue(cardId: String, path: String, kind: String): String {
        if (queue.size >= MAX_QUEUE) {
            val old = queue.removeFirst()
            dropped++
            return "队列已满（$MAX_QUEUE），丢弃最旧的一条（${old.cardId}/${old.path}）——" +
                   "没有广播成功，别当成已发送。"
        }
        val i = Intent(++seq, cardId, path, kind)
        queue.addLast(i)
        return "离线，已入队（第 ${queue.size} 条，seq=${i.seq}），联网后自动补发。"
    }

    /**
     * 联网后补发。返回实际发出几条、作废几条。
     * 过期的直接丢——发出去只会造成幽灵意图。
     */
    fun flush(): String {
        val now = System.currentTimeMillis()
        var sent = 0; var stale = 0
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            if (i.expired(now)) { stale++; continue }
            sent++
        }
        return buildString {
            appendLine("补发 $sent 条" + if (stale > 0) "，作废 $stale 条（超时未发出）" else "")
            if (stale > 0)
                appendLine("作废的不会补发——发出去只会让人以为「有人正在改」，" +
                           "实际上那条意图早就没意义了。")
            if (dropped > 0) appendLine("（此前因队列满丢弃 $dropped 条）")
        }.trimEnd()
    }

    /** 对端收到意图：按 seq 去重 */
    fun onReceive(site: String, remoteSeq: Long, cardId: String,
                  path: String, kind: String = "edit"): String {
        val last = ack[site] ?: 0
        // 幂等：补发失败会重试，同一条发两次会显示两条"正在改"
        if (remoteSeq <= last) return "重复意图 seq=$remoteSeq（已有 $last），已忽略。"
        ack[site] = remoteSeq
        return "来自 $site：正在 $kind ${cardId}/${path}"
    }

    fun pending(): List<Intent> = queue.toList()
    fun droppedCount() = dropped

    fun reset() { queue.clear(); ack.clear(); seq = 0; dropped = 0; online = true }

    fun format(): String = buildString {
        appendLine(if (online) "在线" else "离线（改动会入队）")
        appendLine("待发 ${queue.size} 条 / 上限 $MAX_QUEUE" +
            if (dropped > 0) "　已丢弃 $dropped 条" else "")
        if (queue.isNotEmpty()) {
            appendLine("最早一条：${queue.first().cardId}/${queue.first().path}" +
                "（${(System.currentTimeMillis() - queue.first().ts) / 1000}s 前）")
        }
        if (ack.isNotEmpty())
            appendLine("对端已收：" + ack.entries.joinToString("、") { "${it.key}=${it.value}" })
    }

    fun demo(): String {
        reset()
        return buildString {
            appendLine("示例：地铁上改三格")
            appendLine()
            setOnline(false)
            appendLine(send("budget", "rows[2][1]"))
            appendLine(send("budget", "rows[3][1]", "edit"))
            appendLine(send("budget", "rows[4][1]", "delete"))
            appendLine()
            appendLine(format().trimEnd())
            appendLine()
            appendLine("出隧道：")
            appendLine(flush())
            appendLine()
            appendLine("对端收到（补发失败重试，同一条发两次）：")
            appendLine("  " + onReceive("tablet", 1, "budget", "rows[2][1]"))
            appendLine("  " + onReceive("tablet", 1, "budget", "rows[2][1]"))
            appendLine("  " + onReceive("tablet", 2, "budget", "rows[3][1]"))
            appendLine()
            appendLine("第二条被去重——否则对方会看到两条「正在改」。")
        }
    }

    fun report(): String = buildString {
        appendLine("离线意图广播（断网时先把「打算改哪」排队）")
        appendLine()
        appendLine("⑮ CRDT 解决「两个改动最终怎么合并」")
        appendLine("⑫ 协同编辑解决「改之前就看见对方在改」")
        appendLine()
        appendLine("两者都**假设网络最终可达**，但没回答中间那段时间：")
        appendLine()
        appendLine("  地铁上改了三格，出隧道前对方完全不知道——")
        appendLine("  而对方可能正在改**同一格**。")
        appendLine()
        appendLine("等联网了再合并，两个人已经各自改完了，")
        appendLine("冲突提示这才出现——**冲突提示来得太晚，等于没有提示**。")
        appendLine("㉚ 能判断怎么并，但并完之后")
        appendLine("「我白填了」这个感受已经产生了。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、意图不是改动，不参与合并（只是预告）")
        appendLine("  二、过期意图要作废（TTL 120s，否则是幽灵意图）")
        appendLine("  三、入队不能静默失败（满了要告知，不能假装存下了）")
        appendLine("  四、联网补发要有幂等（按 seq 去重）")
        appendLine()
        appendLine("第三条最关键：")
        appendLine("用户以为预告已发，实际上没人收到，比不发更糟。")
    }

    fun guide(): String = """
        zx_bcast(op=send|flush|recv|queue|demo|report|guide, card?, path?,
                 kind?, site?, seq?, online?)

        断网时先把「打算改哪」排队。

        ⑮ CRDT 解决改动怎么合并，⑫ 协同解决改之前就看见。
        两者都假设网络最终可达，但没回答中间那段时间：

          地铁上改了三格，出隧道前对方完全不知道
          而对方可能正在改同一格

        等联网再合并，冲突提示这才出现——来得太晚，等于没有提示。

        意图比改动小得多（几十字节 vs 几 KB），所以离线也攒得下。

        四条约束：
          一、意图不是改动，不参与合并
          二、过期意图要作废（TTL 120s，否则是幽灵意图）
          三、入队不能静默失败（满了要告知）
          四、联网补发要有幂等（按 seq 去重）
    """.trimIndent()
}
