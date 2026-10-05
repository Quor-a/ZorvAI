package com.ai.assistance.quro.core.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * 卡片的**增量更新引擎**（A2UI 风格 JSON Pointer 补丁）。
 *
 * ## 为什么需要它：这是本 SDK 最大的架构缺口
 *
 * 此前卡片只有「AI 下发 → 整张渲染」这一个方向。AI 想改一张已经画出去的卡，
 * 只能把同样的 id 再下发一遍整张卡 —— 对 101 种组件意味着每次更新都要重写
 * 完整 JSON。而真实场景里绝大多数更新都是**改一两个值**：
 *
 * ```
 * 进度 10 → 80            仪表读数刷新
 * 倒计时每秒走字          实时看板
 * 待办勾掉一项            用户点了勾选框，AI 侧状态要跟上
 * 表格某个单元格改数      对账 / 监控类输出
 * ```
 *
 * A2UI v0.9 的做法是把**结构与数据分离**，用 JSON Pointer 定位字段做
 * `updateComponents` / `updateDataModel` 增量下发。本文件就是把同一套思想
 * 落到本 SDK 的 wire 格式上。
 *
 * ## 核心设计：走「serialize → 改 JSON → parse」往返，**不动 101 个数据类**
 *
 * 另一条路是给每种卡片写一个 `applyPatch()`，那意味着 101 个 data class 各加一个
 * 方法 —— 新增组件时**漏一处就编译不过或静默不生效**，正是本 SDK 反复栽过的坑
 * （见 [CardSdk.lint] 的「改六处」）。而往返路线复用的是 [serializeCard] /
 * [parseCard] 这条**已经由 lint 和 129 个专项测试守着**的链路：
 * 补丁只用「JSON 层操作」一种语言表达，天然覆盖全部 101 种组件。
 *
 * 代价与边界（都是刻意选择，不是遗漏）：
 *  - 补丁改的是 **wire 键名**，不是 Kotlin 字段名。多数一致，但历史上有少数
 *    别名（如 `chart` 的 wire `type` vs 样例 `chart_type`）。[describe] 返回的
 *    就是真实 wire 路径，模型照着改不会错。
 *  - 往返会把卡片重建为新实例，**不等于原地改字段**。这没关系：卡片是
 *    `data class` + `SnapshotStateList` 持有，整体替换是既有正规路径
 *    （见 [QuroChatCardStore.setToggle]）。
 *
 * ## 路径语法
 *
 * 主体是标准 JSON Pointer（RFC 6901），但**刻意放宽**了两种最常见的手写形态：
 *
 * | 写法 | 含义 |
 * |---|---|
 * | `/items/0/done` | 标准 JSON Pointer（推荐） |
 * | `items.0.done` | 省略首斜杠、点号分隔 —— 模型最常这么写，不该因此失败 |
 * | `/items/-/done` | `-` = 数组末尾（append 时即「加到最后一项」） |
 * | `$.items[0].done` | 带 `$` 与方括号 —— 另一批模型的惯性写法 |
 *
 * 段内 `~1` 解 `\/`、`~0` 解 `~`（RFC 6901 的转义，正经键名里用得到）。
 * 宽松解析在此：**放宽的是分隔符写法，不是类型** —— 路径指到不存在的父节点
 * 仍然报错，不会静默建出一棵野树。
 *
 * ## 原子性
 *
 * 一批补丁**全成功才生效**（[apply] 内部先作用到副本，失败即原样返回旧卡）。
 * 半个补丁生效的卡是比补丁失败更糟的状态：用户看到的内容既不是模型下发的
 * 也不是自己点的，且没有任何提示。
 *
 * ## 身份保护
 *
 * `id` 与 `cardType` **禁止修改**（[PROTECTED_KEYS]）。改了 `id` 卡片会从 store
 * 里凭空消失（按 id 索引），改了 `cardType` 则等于换了一张卡 —— 两者都违背
 * 「更新」而非「替换」的语义。命中时该条补丁报错并说明原因。
 */
object CardPatch {

    /** 禁止被补丁修改的顶层键，理由见类注释「身份保护」。 */
    val PROTECTED_KEYS = setOf("id", "cardType")

    /** 支持的补丁操作。 */
    val OPS = listOf("set", "append", "remove", "merge", "inc", "replace")

    /**
     * 补丁应用结果。
     *
     * 刻意**不抛异常**：调用方（工具层 / 渲染层）都要能直接把 [errors] 呈现给
     * 模型，让它自己改对，而不是抛栈中断整轮对话。
     */
    data class Result(
        /**
         * 生效后的卡片。
         *
         * 刻意**可空**：唯一为 null 的情形是「按 id 没找到这张卡」。
         * 早先用一张占位卡顶上，但那样调用方一个不留神就会把占位卡渲染出去 ——
         * 可空让这个 misuse 在编译期就不成立。找不到时看 [errors]。
         */
        val card: QuroChatCard?,
        /** 是否真的改动了。false = 空补丁或全部失败。 */
        val changed: Boolean,
        /** 已成功应用的补丁描述（给人看 / 给模型复盘）。 */
        val applied: List<String>,
        /** 失败原因，逐条。 */
        val errors: List<String>,
    ) {
        /**
         * 成功与否。
         *
         * 派生属性而非构造参数：[Result] 的构造点有十几处（每个失败分支都要造一个），
         * 让调用方额外传一个 `ok = errors.isEmpty()` 只会带来「两处不一致」的机会。
         */
        val ok: Boolean get() = errors.isEmpty()

        /**
         * 改动后的卡片，失败时抛。
         *
         * 给「已经确认卡存在、只想拿新卡」的调用方用，省一次判空；
         * 不确定时读 [card] 与 [changed]。
         */
        val newCard: QuroChatCard get() = card ?: error("补丁未生效（card 为 null）：" + errors.joinToString("; "))

        /**
         * 给模型看的**自愈提示**。
         *
         * 只回「哪条错了、为什么」模型往往改不对（它不知道有哪些合法路径）。
         * 所以附上 [describe] 的合法路径清单 —— 这也是工具失败回喂该有的样子：
         * 说清现状 + 给出可用词汇，而不是把异常字符串甩回去。
         */
        fun feedback(cardId: String): String {
            if (ok && !changed) return "card_patch：卡片 $cardId 无改动（补丁为空）"
            if (ok) return "card_patch：已应用 ${applied.size} 条 → $cardId" +
                (if (applied.isEmpty()) "" else "\n" + applied.joinToString("\n") { "· $it" })
            val legal = card?.let { CardPatch.describe(it) }.orEmpty().take(40)
            return buildString {
                append("card_patch 失败：$cardId\n")
                errors.forEach { append("✗ $it\n") }
                append("该卡当前可改的合法路径（JSON Pointer）：\n")
                append(legal.joinToString("\n") { "  $it" })
                if (legal.size >= 40) append("\n  …（只列前 40 条，可用 describe=cardId 看全量）")
            }
        }
    }

    // ═══════════════════════ 对外入口 ═══════════════════════

    /**
     * 对卡片应用一批补丁。
     *
     * @param spec 单条补丁 [JSONObject]、补丁数组 [JSONArray]，
     *             或 `{patches:[...]}` / `{ops:[...]}` 包装。
     *             宽容接受是为了模型总能写对：包一层、写成数组、直接给单条都认。
     */
    fun apply(card: QuroChatCard, spec: JSONObject): Result {
        val ops = normalizeOps(spec)
        if (ops.isEmpty()) return Result(card, false, emptyList(), emptyList())

        // 作用到副本：任何一条失败都丢弃整个副本，保证原子性。
        val work = runCatching { JSONObject(serializeCard(card).toString()) }
            .getOrElse { return Result(card, false, emptyList(), listOf("卡片无法序列化：${it.message}")) }
        work.put("id", card.id)

        val applied = ArrayList<String>()
        val errors = ArrayList<String>()

        ops.forEachIndexed { i, op ->
            val label = "#${i + 1}"
            val kind = op.optString("op", "").trim().lowercase()
            if (kind !in OPS) {
                errors += "$label 未知操作 ${op.optString("op", "(空)")}，可用：${OPS.joinToString("/")}"
                return@forEachIndexed
            }
            val path = op.optString("path", op.optString("pointer", "")).trim()
            val segs = parsePath(path)
            if (segs == null) {
                errors += "$label 路径无法解析：${path.ifEmpty { "(空)" }}"
                return@forEachIndexed
            }
            // 身份保护只看第一段：/id/x 这种嵌套 id 一律拦。
            if (segs.isNotEmpty() && segs[0] in PROTECTED_KEYS) {
                errors += "$label 禁止修改受保护键 ${segs[0]}（id/cardType 决定卡片身份，" +
                    "改了就等于删除旧卡+新建一张）。要换内容请下发新卡。"
                return@forEachIndexed
            }
            val e = applyOne(work, kind, segs, op)
            if (e == null) applied += "$label $kind ${path.ifEmpty { "/" }}" else errors += "$label $e"
        }

        if (errors.isNotEmpty()) return Result(card, false, emptyList(), errors) // 🔴 整体丢弃副本
        val rebuilt = runCatching { parseCard(work) }.getOrNull()
        // 往返失败 = 补丁把卡改成了不合法形状。宁可不动也不能交一张坏卡。
            ?: return Result(card, false, emptyList(), listOf("补丁后卡片无法解析（形状非法），已放弃"))
        if (rebuilt::class != card::class) {
            return Result(
                card, false, emptyList(),
                listOf("补丁把卡片类型改成了 ${rebuilt::class.simpleName}，已放弃（换类型请下发新卡）")
            )
        }
        return Result(rebuilt, true, applied, emptyList())
    }

    /** 从补丁 JSON 里抽出补丁列表，兼容单条 / 数组 / `{patches|ops:[...]}`。 */
    private fun normalizeOps(spec: JSONObject): List<JSONObject> {
        spec.optJSONArray("patches")?.let { return it.toObjList() }
        spec.optJSONArray("ops")?.let { return it.toObjList() }
        // 也接受 {op,path,value} 直接摊平在顶层
        if (spec.optString("op", "").isNotBlank()) return listOf(spec)
        return emptyList()
    }

    private fun JSONArray.toObjList(): List<JSONObject> {
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) optJSONObject(i)?.let { out += it }
        return out
    }

    // ═══════════════════════ 路径解析 ═══════════════════════

    /**
     * 解析成段列表；解析不出来返回 null。
     *
     * 接受 `/a/b` `a.b` `$.a[0]` `/a/-` 混用。刻意**不接受**空段（`a..b` / `/a//b`）——
     * 那通常是模型拼错了而不是真要空键，与其猜不如报错让它改。
     */
    fun parsePath(raw: String): List<String>? {
        if (raw.isBlank()) return emptyList() // 空 = 整卡（merge / append 有意义）
        var s = raw.trim()
        // 🔴 顺序要紧：三种写法的归一化必须「先去 $ 和括号，再统一切 /」。
        //   `$.items[0].done` 早期版本在这里被拆成 `.items/0/done`，
        //   切分后首段为空 → 判非法（测试「三种路径写法等价」抓到的）。
        if (s.startsWith("$")) s = s.substring(1)
        s = s.replace("[", "/").replace("]", "")
        s = s.replace(".", "/")
        s = s.trim('/')          // 去掉归一化后可能出现的前导/尾随斜杠
        if (s.isEmpty()) return emptyList()
        val segs = ArrayList<String>()
        s.split("/").forEach { seg ->
            if (seg.isEmpty()) return null
            segs += seg.replace("~1", "/").replace("~0", "~")
        }
        return segs
    }

    // ═══════════════════════ 单条操作 ═══════════════════════

    /** 返回 null = 成功；返回字符串 = 失败原因（直接进 [Result.errors]）。 */
    private fun applyOne(root: JSONObject, kind: String, segs: List<String>, op: JSONObject): String? {
        if (segs.isEmpty()) {
            // 空路径 = 作用在根上。merge 深合并、append 补键、replace 整体替换，
            // 三种都是模型会写的自然形态；set/inc/remove 作用在根上没有意义（会清字段）。
            if (kind == "set" || kind == "inc" || kind == "remove") {
                return "空路径不支持 $kind（要改整个卡片请用 merge 或 replace）"
            }
            if (op.has("id") || op.has("cardType")) return "空路径补丁不能携带 id/cardType"
            if (kind == "append") {
                val leaf = op.optString("key", op.optString("path", "")).trim()
                if (leaf.isEmpty()) return "给根补键时需要 key（或用 path 指明键名）"
                if (leaf in PROTECTED_KEYS) return "禁止修改受保护键 $leaf"
                root.put(leaf, op.opt("value"))
                return null
            }
            val patchObj = op.optJSONObject("value") ?: op.optJSONObject("patch")
                ?: return "空路径 $kind 需要 value 为 JSON 对象"
            val pk = patchObj.keys()
            while (pk.hasNext()) {
                val k = pk.next()
                if (k in PROTECTED_KEYS) return "禁止修改受保护键 $k"
            }
            if (kind == "merge") {
                // 🔴 merge 一律**深合并**（只动 patch 里出现的键）。
                // 这里原本实现的是「整体替换」，危险在于：模型写
                // `{"op":"merge","value":{...}}` 而忘了写 path 时，它理解的是
                // 「合并到根」= 深合并，实际却把整张卡其它字段全清空 ——
                // 静默数据丢失，且卡片**照样渲染成功**（只是内容没了），最难查。
                // 所以默认必须是安全的那一种；要整体替换请显式写 op=replace。
                deepMerge(root, patchObj)
                return null
            }
            // replace 到根 = 清空根上除受保护键外的全部字段后写入
            val stale = ArrayList<String>()
            val rk = root.keys()
            while (rk.hasNext()) {
                val k = rk.next()
                if (k != "id" && k != "cardType") stale += k
            }
            stale.forEach { root.remove(it) }
            val nk = patchObj.keys()
            while (nk.hasNext()) {
                val k = nk.next()
                root.put(k, patchObj.get(k))
            }
            return null
        }

        val parentPath = segs.dropLast(1)
        val leaf = segs.last()
        val parent = resolveForWrite(root, parentPath)
            ?: return "路径不存在：/${parentPath.joinToString("/")}（补丁不会凭空创建中间层，请先下发带该结构的卡）"

        return when (kind) {
            "set" -> {
                if (!op.has("value")) return "set 缺少 value"
                // when 各分支返回 put() 的结果（Any?），这里显式丢弃 —— 分支里出错都直接 return 了
                @Suppress("UNUSED_VARIABLE") val ignored = when (parent) {
                    is JSONObject -> parent.put(leaf, op.get("value"))
                    is JSONArray -> {
                        val i = leaf.toIntOrNull() ?: return "数组下标不是数字：$leaf"
                        if (i < 0 || i >= parent.length()) return "下标 $i 越界（长度 ${parent.length()}）"
                        parent.put(i, op.get("value"))
                    }
                    else -> return "父节点不是对象或数组"
                }
                null
            }
            "merge" -> {
                if (parent !is JSONObject) return "merge 的父节点必须是对象"
                val patch = op.optJSONObject("value") ?: op.optJSONObject("patch")
                    ?: return "merge 需要 value 为 JSON 对象"
                deepMerge(parent, patch)
                null
            }
            // replace：整体替换该节点（与 merge 的区别是**清掉**原节点里 patch 没提到的键）。
            // 非空路径的 replace 只清该节点自己的键，不动兄弟节点。
            "replace" -> {
                val patch = op.optJSONObject("value") ?: op.optJSONObject("patch")
                    ?: return "replace 需要 value 为 JSON 对象"
                when (parent) {
                    is JSONObject -> {
                        // 保护：路径首段是 id/cardType 时上面已拦；这里防的是 replace
                        // 往卡里塞一个含受保护键的子树（不会真的改身份，但会让下轮
                        // 补丁的 describe 出现假路径）
                        val pk = patch.keys()
                        while (pk.hasNext()) {
                            val k = pk.next()
                            if (parentPath.isEmpty() && k in PROTECTED_KEYS) return "禁止修改受保护键 $k"
                        }
                        val stale = ArrayList<String>()
                        val rk = parent.keys()
                        while (rk.hasNext()) stale += rk.next()
                        stale.forEach { parent.remove(it) }
                        val nk = patch.keys()
                        while (nk.hasNext()) {
                            val k = nk.next()
                            parent.put(k, patch.get(k))
                        }
                    }
                    is JSONArray -> return "replace 只支持对象节点（数组请用 set/append/remove）"
                    else -> return "父节点不是对象或数组"
                }
                null
            }
            "append" -> {
                @Suppress("UNUSED_VARIABLE") val ignored = when (parent) {
                    is JSONArray -> {
                        if (leaf != "-" && leaf.isNotEmpty()) {
                            val i = leaf.toIntOrNull() ?: return "append 到数组时下标不是数字：$leaf"
                            if (i < 0 || i > parent.length()) return "下标 $i 越界（长度 ${parent.length()}）"
                            parent.put(i, op.get("value"))
                        } else {
                            parent.put(op.get("value"))
                        }
                    }
                    is JSONObject -> parent.put(leaf, op.opt("value"))
                    else -> return "父节点不是对象或数组"
                }
                null
            }
            "remove" -> {
                when (parent) {
                    is JSONObject -> {
                        if (!parent.has(leaf)) return "要删的键不存在：$leaf"
                        parent.remove(leaf)
                    }
                    is JSONArray -> {
                        val i = leaf.toIntOrNull() ?: return "数组下标不是数字：$leaf"
                        if (i < 0 || i >= parent.length()) return "下标 $i 越界（长度 ${parent.length()}）"
                        parent.remove(i)
                    }
                    else -> return "父节点不是对象或数组"
                }
                null
            }
            "inc" -> {
                val delta = when {
                    op.has("value") -> numOf(op.get("value"))
                    op.has("delta") -> numOf(op.get("delta"))
                    else -> return "inc 需要 value 或 delta（数值）"
                } ?: return "inc 的增量不是数值：${op.opt("value")}"
                val cur = when (parent) {
                    is JSONObject -> {
                        if (!parent.has(leaf)) return "inc 的目标不存在：$leaf"
                        numOf(parent.get(leaf)) ?: return "inc 目标不是数值：${parent.get(leaf)}"
                    }
                    is JSONArray -> {
                        val i = leaf.toIntOrNull() ?: return "数组下标不是数字：$leaf"
                        if (i < 0 || i >= parent.length()) return "下标 $i 越界（长度 ${parent.length()}）"
                        numOf(parent.get(i)) ?: return "inc 目标不是数值：${parent.get(i)}"
                    }
                    else -> return "父节点不是对象或数组"
                }
                val next = cur + delta
                when (parent) {
                    is JSONObject -> parent.put(leaf, next)
                    is JSONArray -> parent.put(leaf.toInt(), next)
                    else -> Unit
                }
                null
            }
            else -> "未知操作 $kind"
        }
    }

    /**
     * 解析可写父容器。
     *
     * **不自动创建中间层**：模型写出 `/a/b/c` 而卡里没有 `a` 时，若默默建一棵空树，
     * 会得到一张「有 b 和 c 却没有 a 语义」的怪卡 —— 比明确报错难查得多。
     * 但若 `a` 存在且是对象、只缺叶子，则正常返回（由 put 创建）。
     */
    private fun resolveForWrite(root: JSONObject, segs: List<String>): Any? {
        var cur: Any = root
        segs.forEach { s ->
            cur = when (cur) {
                is JSONObject -> if (cur.has(s)) cur.get(s) else return null
                is JSONArray -> {
                    val i = s.toIntOrNull() ?: return null
                    if (i < 0 || i >= cur.length()) return null
                    cur.get(i)
                }
                else -> return null
            }
        }
        // 只负责「走到父容器」，**不校验叶子段**。
        // 🔴 早期版本在这里多校验了一句「末段是不是数字」，可 segs 传进来的是
        //   **父路径**（已 dropLast），末段是父容器的键名（如 `items`），
        //   于是 `/items/0/done`、`/items/-` 全被误判成「路径不存在」
        //   （测试「append 数组末尾」「remove 删数组元素」抓到的）。
        //   叶子段的合法性本来就该由各操作自己判断：set/remove/inc 都会查下标与越界。
        return cur
    }

    /** 深合并：只对 JSONObject 递归，其它值整体覆盖。null 视为显式值（可清字段）。 */
    private fun deepMerge(dst: JSONObject, src: JSONObject) {
        val ks = src.keys()
        while (ks.hasNext()) {
            val k = ks.next()
            val v = src.get(k)
            val cur = dst.opt(k)
            if (cur is JSONObject && v is JSONObject) deepMerge(cur, v) else dst.put(k, v)
        }
    }

    /** 宽松取数：能当数字的都当（字符串 "1.5" 也收），实在不像数字才返回 null。 */
    private fun numOf(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    // ═══════════════════════ 路径发现（describe） ═══════════════════════

    /**
     * 列出这张卡**当前**可改的合法路径。
     *
     * 这是补丁能用起来的关键前提：模型看不到卡片的数据类字段，只能看到
     * 自己下发的 JSON。补丁失败时若不告诉它合法路径，它只能瞎猜、反复试错
     * （一次对话几十轮全耗在猜字段上）。返回的是**真实 wire 路径**，
     * 照着改必定命中 —— 包括那几个 wire 别名。
     *
     * 只列**标量叶子**：对象/数组中间节点本身不是补丁目标。
     */
    fun describe(card: QuroChatCard, includeArrays: Boolean = true): List<String> {
        val out = ArrayList<String>()
        val root = runCatching { JSONObject(serializeCard(card).toString()) }.getOrNull() ?: return out
        walk(root, "", out, includeArrays, 0)
        return out
    }

    private fun walk(node: Any, prefix: String, out: MutableList<String>, includeArrays: Boolean, depth: Int) {
        if (depth > 6) return // 防御：畸形深嵌套（自定义卡 payload）别把输出撑爆
        if (node is JSONObject) {
            val ks = node.keys()
            while (ks.hasNext()) {
                val k = ks.next()
                if (k in PROTECTED_KEYS && prefix.isEmpty()) continue
                val p = "$prefix/$k"
                when (val v = node.get(k)) {
                    is JSONObject -> walk(v, p, out, includeArrays, depth + 1)
                    is JSONArray -> {
                        if (includeArrays) {
                            out += "$p/0 … $p/${v.length() - 1}（数组，len=${v.length()}）"
                            for (i in 0 until v.length()) {
                                val e = v.opt(i)
                                if (e is JSONObject || e is JSONArray) walk(e, "$p/$i", out, includeArrays, depth + 1)
                            }
                        }
                    }
                    else -> out += "$p = $v"
                }
            }
        }
    }

    /** 给 `card_catalog` / 工具描述用的补丁语法说明（现读，不写死文案）。 */
    fun syntaxJson(): String = JSONObject().apply {
        put("ops", JSONArray(OPS))
        put(
            "pathForms",
            JSONArray().apply {
                put(JSONObject().apply {
                    put("form", "/items/0/done")
                    put("note", "标准 JSON Pointer（RFC 6901），推荐")
                })
                put(JSONObject().apply {
                    put("form", "items.0.done")
                    put("note", "省略首斜杠 + 点号分隔；也认 $.a[0].b 这种写法")
                })
                put(JSONObject().apply {
                    put("form", "/items/-")
                    put("note", "- 表示数组末尾，append 时即「加到最后一项」")
                })
            }
        )
        put(
            "opMeanings",
            JSONObject().apply {
                put("set", "设值（父节点须已存在；补丁不会凭空造中间层）")
                put("append", "数组末尾追加（path 指到数组，如 /items 与 /items/- 都行）/ 对象补键；" +
                    "path 为空时用 key 指定要补的键名")
                put("remove", "删键或删数组下标")
                put("merge", "对象**深合并**（只动 value 里出现的键；path 为空即合并到根）—— 最常用")
                put("inc", "数值增减（value 或 delta），倒计时/进度/计数专用")
                put("replace", "整体替换该节点（**会清掉** value 没提到的键），慎用")
            }
        )
        put("protected", JSONArray(PROTECTED_KEYS.toList()))
        put(
            "rule",
            "一批补丁全成功才生效（原子）；任一条失败则整批放弃并回退原卡，" +
                "失败信息里带该卡当前合法路径清单。"
        )
        put(
            "example",
            "{\"cardId\":\"order_1\",\"patches\":[" +
                "{\"op\":\"set\",\"path\":\"/progress\",\"value\":80}," +
                "{\"op\":\"inc\",\"path\":\"/remaining\",\"value\":-1}," +
                "{\"op\":\"append\",\"path\":\"/items\",\"value\":{\"text\":\"新的一项\",\"done\":false}}]}"
        )
        put(
            "beforeYouPatch",
            "先 card_patch(describe=true, cardId) 拿该卡当前合法路径：模型看不到卡片内部字段，" +
                "不探路就写路径极易猜错。"
        )
    }.toString()
}
