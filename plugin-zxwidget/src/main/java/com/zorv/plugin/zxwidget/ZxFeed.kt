package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * 内容源与时效 —— 天气 / 新闻 / 内容的统一缓存、保鲜与去重。
 *
 * **为什么需要**
 *
 * 第三十三批加了天气、新闻、订阅流三个消费类组件，然后立刻撞上一个问题：
 *
 *   **同一条内容在不同卡片里重复出现。**
 *
 *   模型先画了 zx_news 列出五条，用户点开一条，它又画一张 zx_headline。
 *   两张卡各自持有自己的数据，第二张不知道第一张已经拿过这一条了——
 *   于是同一条新闻被拉取两次、占用两份上下文、显示两个不同的"更新时间"。
 *
 *   更糟的是**更新时间不一致**：第一张卡是 2 小时前拉的（显示"2 小时前"），
 *   第二张是刚拉的（显示"刚刚"）。用户来回看会觉得这个 App 在骗他。
 *
 * 另一个问题是**保鲜期没人管**：
 *
 *   天气 30 分钟就该失效，两小时前的"当前温度"就是错的
 *   新闻 2 小时还行，两天前的"最新消息"就是错的
 *   文档 7 天问题不大，代码 30 天也还行
 *
 *   而 ㉝ 知识沉淀那条"过期的知识比没有更糟"在消费类上同样成立，
 *   而且**后果更直接**——用户真的会照着两小时前的天气决定穿什么。
 *
 * 三件事：
 *
 *   一、**保鲜期按类型定**，不由调用方随便传
 *   二、**过期不删除只标记**（用户可能就是想看旧的）
 *   三、**跨源去重**（不同来源报同一件事要能认出来）
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**保鲜期由类型决定，不接受调用方覆盖**。
 *     允许覆盖就一定会有人传个很大的值来"让它别过期"——
 *     然后这条约束就不存在了。见 TTL。
 *
 * 二、**过期要标记，不删除**。
 *     删了用户想看旧的也看不到了。标记是告诉他"这条可能不准了"，
 *     判断权在他手上。这也和 ㊴ 因果推断界面那条一致：只列假设不下结论。
 *
 * 三、**去重按内容相似度，不按 ID**。
 *     不同来源的 ID 必然不同，按 ID 去重一条都合并不了。
 *     但相似度去重有个陷阱：两条不同的新闻标题很接近会被误合并——
 *     所以合并后要保留**全部来源**，而不是只留第一个。见 dedup()。
 *
 * 四、**合并后更新时间取最早的，不取最新的**。
 *     这是最容易做错的一条。取最新会让一条 3 天前的内容显示成"刚刚"，
 *     因为其中某个来源刚刚又推了一次。取最早才是"这条内容最早什么时候出现"。
 *     见 mergedTs()。
 */
object ZxFeed {

    /** 内容类型 —— 保鲜期由类型决定，不接受覆盖 */
    enum class Kind(val id: String, val label: String, val ttlMs: Long) {
        WEATHER("wx", "天气", 30 * 60_000L),          // 30 分钟
        NEWS("news", "新闻", 2 * 3600_000L),          // 2 小时
        FEED("feed", "订阅", 12 * 3600_000L),         // 12 小时
        DOC("doc", "文档", 7 * 24 * 3600_000L),       // 7 天
        CODE("code", "代码", 30 * 24 * 3600_000L);    // 30 天

        companion object {
            fun of(s: String): Kind? = values().firstOrNull { it.id == s }
        }
    }

    data class Item(
        val id: String,
        val kind: Kind,
        val title: String,
        val source: String,
        val ts: Long = System.currentTimeMillis(),
        val body: String = "",
        val merged: MutableList<String> = mutableListOf()   // 合并进来的其他来源
    ) {
        /** 是否已过期 */
        fun stale(now: Long = System.currentTimeMillis()): Boolean =
            now - ts > kind.ttlMs

        /** 剩余保鲜比例，1.0 = 刚拉取，0 = 已过期 */
        fun fresh(now: Long = System.currentTimeMillis()): Double =
            (1.0 - (now - ts).toDouble() / kind.ttlMs).coerceIn(0.0, 1.0)
    }

    private val store = LinkedHashMap<String, Item>()
    private var seq = 0

    /**
     * 存入一条内容。若与已有条目相似则合并（保留全部来源）。
     */
    fun put(kindId: String, title: String, source: String,
            ts: Long = System.currentTimeMillis(), body: String = ""): String {
        val kind = Kind.of(kindId)
            ?: return "类型 \"$kindId\" 未知（只认 ${Kind.values().joinToString(" / ") { it.id }}）"
        // 相似则合并
        val hit = store.values.firstOrNull { it.kind == kind && similar(it.title, title) }
        if (hit != null) {
            if (!hit.merged.contains(source)) hit.merged.add(source)
            // 更新时间取最早的（约束四）
            val oldTs = hit.ts
            if (ts < oldTs) {
                store[hit.id] = hit.copy(ts = ts)
                return "与「${hit.title}」相似，已合并（来源：${hit.merged.joinToString(" / ")}）。" +
                    "**更新时间取最早的**：这条最早出现在 ${ago(ts)}，" +
                    "不是刚刚——虽然 $source 刚刚又推了一次"
            }
            return "与「${hit.title}」相似，已合并（来源：${hit.merged.joinToString(" / ")}）"
        }
        val id = "f${++seq}"
        store[id] = Item(id, kind, title, source, ts, body, mutableListOf(source))
        return "已存入 $id（${kind.label}），保鲜 ${fmtTtl(kind.ttlMs)}"
    }

    /**
     * 相似度：按字符 2-gram 的 Jaccard。
     *
     * 为什么不用标题完全相等：不同来源会改写标题（加来源名、改标点），
     * 完全相等一条都合并不了。
     * 为什么用 2-gram 而不是词：中文没有空格，分词要额外依赖。
     */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val ga = grams(a); val gb = grams(b)
        if (ga.isEmpty() || gb.isEmpty()) return false
        val inter = ga.intersect(gb).size.toDouble()
        val j = inter / (ga.size + gb.size - inter)
        return j >= 0.5
    }
    private fun grams(s: String): Set<String> {
        val t = s.filter { !it.isWhitespace() && it !in "，。、！？：；「」《》,.!?:;" }
        if (t.length < 2) return setOf(t)
        return (0 until t.length - 1).map { t.substring(it, it + 2) }.toSet()
    }

    /** 合并后的更新时间：取最早（不是最新） */
    fun mergedTs(id: String): Long? = store[id]?.ts

    fun get(id: String): Item? = store[id]

    fun list(kindId: String? = null): List<Item> {
        val k = kindId?.let { Kind.of(it) }
        return store.values.filter { k == null || it.kind == k }
            .sortedByDescending { it.ts }
    }

    /** 过期清单 */
    fun staleList(): List<Item> =
        store.values.filter { it.stale() }.sortedBy { it.ts }

    private fun ago(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        if (d < 0) return "时间在未来"
        val m = d / 60000
        return when {
            m < 1 -> "刚刚"; m < 60 -> "${m} 分钟前"
            m < 1440 -> "${m / 60} 小时前"; else -> "${m / 1440} 天前"
        }
    }
    private fun fmtTtl(ms: Long): String = when {
        ms < 3600_000 -> "${ms / 60_000} 分钟"
        ms < 24 * 3600_000 -> "${ms / 3600_000} 小时"
        else -> "${ms / (24 * 3600_000)} 天"
    }

    fun render(): String = buildString {
        appendLine("内容源与时效（${store.size} 条）")
        appendLine()
        if (store.isEmpty()) { appendLine("（还没有存过任何内容）"); return@buildString }
        val now = System.currentTimeMillis()
        store.values.sortedByDescending { it.ts }.forEach { it ->
            val s = it.stale(now)
            appendLine("  ${it.id}　[${it.kind.label}]　${it.title}")
            appendLine("     来源 ${it.merged.joinToString(" / ")}" +
                if (it.merged.size > 1) "（${it.merged.size} 源合并）" else "")
            appendLine("     ${ago(it.ts)}　保鲜 ${"%.0f%%".format(it.fresh(now) * 100)}" +
                if (s) "　**已过期**" else "")
            if (it.body.isNotBlank()) appendLine("     ${it.body.take(60)}")
            appendLine()
        }
        val st = staleList()
        if (st.isNotEmpty()) {
            appendLine("**${st.size} 条已过期**。过期不删除只标记——")
            appendLine("用户可能就是想看旧的，删了他看不到了。")
        }
    }

    fun report(): String = buildString {
        appendLine("内容源与时效 —— 天气 / 新闻 / 内容的统一缓存、保鲜与去重")
        appendLine()
        appendLine("第三十三批加了天气、新闻、订阅流，然后立刻撞上一个问题：")
        appendLine()
        appendLine("  **同一条内容在不同卡片里重复出现。**")
        appendLine()
        appendLine("模型先画了 zx_news 列出五条，用户点开一条，它又画一张 zx_headline。")
        appendLine("两张卡各自持有自己的数据，第二张不知道第一张已经拿过这一条了——")
        appendLine("于是同一条新闻被拉取两次、占用两份上下文、")
        appendLine("显示两个不同的「更新时间」。")
        appendLine()
        appendLine("更糟的是**更新时间不一致**：第一张卡是 2 小时前拉的，")
        appendLine("第二张是刚拉的。用户来回看会觉得这个 App 在骗他。")
        appendLine()
        appendLine("另一个问题是**保鲜期没人管**：")
        appendLine()
        appendLine("  天气 30 分钟就该失效，两小时前的「当前温度」就是错的")
        appendLine("  新闻 2 小时还行，两天前的「最新消息」就是错的")
        appendLine()
        appendLine("而 ㉝ 知识沉淀那条「过期的知识比没有更糟」在消费类上同样成立，")
        appendLine("而且**后果更直接**——用户真的会照着两小时前的天气决定穿什么。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、**保鲜期由类型决定**，不接受调用方覆盖")
        appendLine("  二、**过期要标记，不删除**")
        appendLine("  三、**去重按内容相似度，不按 ID**")
        appendLine("  四、**合并后更新时间取最早的，不取最新的**")
        appendLine()
        appendLine("第四条最容易做错：取最新会让一条 3 天前的内容显示成「刚刚」，")
        appendLine("因为其中某个来源刚刚又推了一次。")
    }

    fun demo(): String = buildString {
        appendLine("══ 保鲜期由类型决定 ══")
        appendLine()
        Kind.values().forEach {
            appendLine("  ${it.label.padEnd(4)} ${it.id.padEnd(5)} ${fmtTtl(it.ttlMs)}")
        }
        appendLine()
        appendLine("不接受调用方覆盖。允许覆盖就一定会有人传个很大的值")
        appendLine("来「让它别过期」——然后这条约束就不存在了。")
        appendLine()
        appendLine("══ 合并后取最早，不取最新 ══")
        appendLine()
        appendLine("  同一条新闻，3 天前 A 站报过，刚刚 B 站又推了一次")
        appendLine()
        appendLine("    取最新 → 显示「刚刚」        ← 错")
        appendLine("    取最早 → 显示「3 天前」      ← 对")
        appendLine()
        appendLine("  取最新会让一条 3 天前的内容看起来是新的，")
        appendLine("  因为其中某个来源刚刚又推了一次。")
        appendLine()
        appendLine("══ 去重按相似度，不按 ID ══")
        appendLine()
        appendLine("  不同来源的 ID 必然不同，按 ID 去重一条都合并不了。")
        appendLine("  但相似度去重有个陷阱：两条不同的新闻标题很接近会被误合并。")
        appendLine()
        appendLine("  所以合并后要**保留全部来源**，而不是只留第一个——")
        appendLine("  万一误合并了，用户还能看出这是两个来源。")
    }

    fun guide(): String = """
        zx_feed(op=put|get|list|stale|demo|report|guide, ...)

        天气 / 新闻 / 内容的统一缓存、保鲜与去重。

        第三十三批加了天气、新闻、订阅流，然后立刻撞上一个问题：

          **同一条内容在不同卡片里重复出现。**

        模型先画了 zx_news 列出五条，用户点开一条，它又画一张 zx_headline。
        两张卡各自持有自己的数据，第二张不知道第一张已经拿过这一条了——
        于是同一条新闻被拉取两次、占用两份上下文、
        显示两个不同的「更新时间」。

        更糟的是**更新时间不一致**：第一张卡是 2 小时前拉的，
        第二张是刚拉的。用户来回看会觉得这个 App 在骗他。

        四条约束：
          一、**保鲜期由类型决定**，不接受调用方覆盖
          二、**过期要标记，不删除**（用户可能就是想看旧的）
          三、**去重按内容相似度，不按 ID**（不同来源 ID 必然不同）
          四、**合并后更新时间取最早的，不取最新的**

          第四条最容易做错：
            同一条新闻，3 天前 A 站报过，刚刚 B 站又推了一次
              取最新 → 显示「刚刚」      ← 错
              取最早 → 显示「3 天前」    ← 对

          类型与保鲜期：
            wx 天气 30min / news 新闻 2h / feed 订阅 12h
            doc 文档 7d   / code 代码 30d

          op=put    存入（相似自动合并，保留全部来源）
          op=list   清单
          op=stale  过期清单
    """.trimIndent()

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val op = o.optString("op", "")
        val ops = setOf("put", "get", "list", "stale", "demo", "report", "guide")
        if (op !in ops)
            return false to "zx_feed 的 op=\"$op\" 只支持 ${ops.joinToString(" / ")}"
        if (op == "put") {
            val kind = o.optString("kind")
            if (Kind.of(kind) == null)
                return false to "put 的 kind=\"$kind\" 未知（只认 " +
                    "${Kind.values().joinToString(" / ") { it.id }}）——" +
                    "**保鲜期由类型决定**，认不出类型就定不了保鲜期"
            if (o.optString("title").isBlank())
                return false to "put 需要 title——**没有标题无法去重**，去重是这个模块的核心能力"
            if (o.optString("source").isBlank())
                return false to "put 需要 source——**没有来源无法做同源交叉验证**"
            val ts = o.optLong("ts", System.currentTimeMillis())
            if (ts > System.currentTimeMillis() + 3600_000L)
                return false to "put 的 ts 在未来（超前 1 小时以上）——内容时间不能是未来的"
        }
        if (op == "get" && store[o.optString("id")] == null && o.optString("id").isNotBlank())
            return false to "找不到内容 ${o.optString("id")}"
        return true to ""
    }
}
