package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第十九批组件（序列 / 结构 / 证据）的校验 + 示例 + 规格。
 *
 * 这批查的是**「能不能看出为什么」**：
 *
 *   · 回溯路径 p 指向自己 → 永远排不进任何一层
 *   · 回溯路径有环 → 分层循环停不下来
 *   · 时序图 actors 少于 2 → 没有"谁发给谁"可言
 *   · 时序图 f、t 相同 → 自己发给自己，看不出交互
 *   · 爆炸视图 w 全 0 → 所有块大小归零，散开也看不见
 *   · 关键路径 after 指向自己 → 依赖环，算不出最早开始
 *   · 证据链没有 fact → 全是推断或猜测，结论站不住
 *   · 对质两侧 t 相同 → 那不叫冲突
 *   · 依赖链为空 → 没有链节，谈不上最弱一环
 *
 * 与前面几批的差别：这批的组件是**用来推理的**。
 * 所以校验不只查"画不出来"，还查
 * **"画得出来但推不出结论"**：
 *
 *   时序图只有一个参与者 → 画得出来，但回答不了"谁在等谁"
 *   证据链没有一条事实 → 画得出来，但结论没有依据
 *   对质两边说的一样 → 画得出来，但那不叫冲突
 *
 * **只有结论没有依据的界面，用户只能选择信或不信。**
 */
object ZxDsl19 {

    val ALL = listOf(
        "zx_traceback", "zx_seq", "zx_explode", "zx_critical",
        "zx_evidence", "zx_confront", "zx_matrix3", "zx_receipt",
        "zx_ledger", "zx_gauntlet", "zx_chain", "zx_bracket"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_traceback" -> traceback(root)
        "zx_seq"       -> seq(root)
        "zx_explode"   -> explode(root)
        "zx_critical"  -> critical(root)
        "zx_evidence"  -> evidence(root)
        "zx_confront"  -> confront(root)
        "zx_matrix3"   -> matrix3(root)
        "zx_receipt"   -> receipt(root)
        "zx_ledger"    -> ledger(root)
        "zx_gauntlet"  -> gauntlet(root)
        "zx_chain"     -> chain(root)
        "zx_bracket"   -> bracket(root)
        else -> false to "未注册组件：$component"
    }

    private fun traceback(o: JSONObject): Pair<Boolean, String> {
        val nodes = o.optJSONArray("nodes")
            ?: return false to "zx_traceback 缺少 nodes"
        if (nodes.length() < 2) return false to "回溯至少 2 个节点，实际 ${nodes.length()} 个"
        if (nodes.length() > 12) return false to "回溯最多 12 个节点，实际 ${nodes.length()} 个"
        val n = nodes.length()
        for (i in 0 until n) {
            val nd = nodes.optJSONObject(i)
                ?: return false to "nodes[$i] 不是对象，应为 {\"n\":名,\"p\":父下标,\"k\":类型}"
            if (nd.optString("n").isBlank()) return false to "nodes[$i] 缺少 n"
            if (!nd.has("p")) return false to "nodes[$i] 缺少 p（父下标，根节点给 -1）"
            val p = nd.optInt("p", -1)
            // 指向自己：永远排不进任何一层
            if (p == i) return false to "nodes[$i] 的 p=$i 指向自己——永远排不进任何一层"
            if (p >= n) return false to "nodes[$i].p=$p 越界（共 $n 个节点）"
        }
        // 环检测：分层会停不下来
        for (i in 0 until n) {
            var cur = i
            val seen = mutableSetOf<Int>()
            var steps = 0
            while (cur in 0 until n && steps <= n) {
                if (!seen.add(cur)) return false to "nodes 存在环：$i → … → $cur——分层排布停不下来"
                cur = nodes.optJSONObject(cur)?.optInt("p", -1) ?: -1
                steps++
            }
        }
        return true to ""
    }

    private fun seq(o: JSONObject): Pair<Boolean, String> {
        val actors = o.optJSONArray("actors")
            ?: return false to "zx_seq 缺少 actors"
        // 少于 2：没有"谁发给谁"可言
        if (actors.length() < 2)
            return false to "时序图至少 2 个参与者，实际 ${actors.length()} 个——1 个就没有「谁发给谁」可言"
        if (actors.length() > 5) return false to "时序图最多 5 个参与者，实际 ${actors.length()} 个"
        for (i in 0 until actors.length())
            if (actors.optString(i).isBlank()) return false to "actors[$i] 是空字符串"
        val msgs = o.optJSONArray("msgs")
            ?: return false to "zx_seq 缺少 msgs"
        if (msgs.length() == 0) return false to "zx_seq 的 msgs 为空"
        if (msgs.length() > 10) return false to "消息最多 10 条，实际 ${msgs.length()} 条"
        for (k in 0 until msgs.length()) {
            val m = msgs.optJSONObject(k)
                ?: return false to "msgs[$k] 不是对象，应为 {\"f\":,\"t\":,\"n\":,\"wait\":}"
            val f = m.optInt("f", -1); val t = m.optInt("t", -1)
            if (f < 0 || t < 0) return false to "msgs[$k] 缺少 f 或 t"
            if (f >= actors.length() || t >= actors.length())
                return false to "msgs[$k] 的 f=$f t=$t 越界（共 ${actors.length()} 个参与者）"
            // 自己发给自己：看不出交互
            if (f == t) return false to "msgs[$k] 的 f=t=$f——自己发给自己，看不出交互"
            if (m.optString("n").isBlank()) return false to "msgs[$k] 缺少 n（消息名）"
        }
        return true to ""
    }

    private fun explode(o: JSONObject): Pair<Boolean, String> {
        val parts = o.optJSONArray("parts")
            ?: return false to "zx_explode 缺少 parts"
        if (parts.length() < 2) return false to "爆炸视图至少 2 个部件，实际 ${parts.length()} 个"
        if (parts.length() > 8) return false to "爆炸视图最多 8 个部件，实际 ${parts.length()} 个"
        var allZero = true
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i)
                ?: return false to "parts[$i] 不是对象，应为 {\"n\":名,\"w\":占比权重,\"d\":说明}"
            if (p.optString("n").isBlank()) return false to "parts[$i] 缺少 n"
            val w = p.optDouble("w", Double.NaN)
            if (w.isNaN()) return false to "parts[$i] 缺少 w（权重）"
            if (w < 0) return false to "parts[$i].w=$w 不能为负"
            if (w > 0) allZero = false
        }
        // 全 0：所有块大小归零，散开也看不见
        if (allZero) return false to "所有部件的 w 都是 0——块大小全归零，散开也看不见"
        val spread = o.optDouble("spread", .5)
        if (spread < 0 || spread > 1)
            return false to "zx_explode 的 spread=$spread 超出 0-1"
        return true to ""
    }

    private fun critical(o: JSONObject): Pair<Boolean, String> {
        val tasks = o.optJSONArray("tasks")
            ?: return false to "zx_critical 缺少 tasks"
        if (tasks.length() < 2) return false to "关键路径至少 2 个任务，实际 ${tasks.length()} 个"
        if (tasks.length() > 10) return false to "关键路径最多 10 个任务，实际 ${tasks.length()} 个"
        val n = tasks.length()
        for (i in 0 until n) {
            val t = tasks.optJSONObject(i)
                ?: return false to "tasks[$i] 不是对象，应为 {\"n\":名,\"d\":时长,\"after\":[前置]}"
            if (t.optString("n").isBlank()) return false to "tasks[$i] 缺少 n"
            val d = t.optDouble("d", Double.NaN)
            if (d.isNaN()) return false to "tasks[$i] 缺少 d（时长）"
            if (d <= 0) return false to "tasks[$i].d=$d 必须为正——时长为 0 的任务没有意义"
            val arr = t.optJSONArray("after")
            for (k in 0 until (arr?.length() ?: 0)) {
                val a = arr?.optInt(k) ?: continue
                // 自依赖：算不出最早开始
                if (a == i) return false to "tasks[$i] 依赖自己（after 含 $i）——算不出最早开始"
                if (a >= n) return false to "tasks[$i].after[$k]=$a 越界（共 $n 个任务）"
            }
        }
        // 依赖环：CPM 会死循环
        for (i in 0 until n) {
            val seen = mutableSetOf<Int>()
            val stack = mutableListOf(i)
            var steps = 0
            while (stack.isNotEmpty() && steps <= n * n) {
                val cur = stack.removeAt(stack.size - 1)
                if (!seen.add(cur)) return false to "tasks 存在依赖环：$i → … → $cur"
                val arr = tasks.optJSONObject(cur)?.optJSONArray("after")
                for (k in 0 until (arr?.length() ?: 0))
                    arr?.optInt(k)?.let { if (it in 0 until n) stack.add(it) }
                steps++
            }
        }
        return true to ""
    }

    private fun evidence(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_evidence 缺少 items"
        if (items.length() == 0) return false to "zx_evidence 的 items 为空"
        if (items.length() > 8) return false to "依据最多 8 条，实际 ${items.length()} 条"
        val kinds = setOf("fact", "infer", "quote", "guess")
        var hasFact = false
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"t\":内容,\"k\":种类,\"c\":置信度,\"src\":来源}"
            if (it.optString("t").isBlank()) return false to "items[$i] 缺少 t（依据内容）"
            val k = it.optString("k", "fact")
            if (k !in kinds)
                return false to "items[$i].k=\"$k\" 不支持（可选：${kinds.joinToString("/")}）"
            if (k == "fact") hasFact = true
            val c = it.optDouble("c", Double.NaN)
            if (c.isNaN()) return false to "items[$i] 缺少 c（置信度）"
            if (c < 0 || c > 1) return false to "items[$i].c=$c 超出 0-1"
        }
        // 没有事实：全是推断或猜测，结论站不住
        if (!hasFact)
            return false to "没有一条 k=\"fact\" 的依据——全是推断或猜测，结论站不住"
        if (o.optString("concl").isBlank())
            return false to "zx_evidence 缺少 concl（结论）——只有依据没有结论，用户不知道在证明什么"
        return true to ""
    }

    private fun confront(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONObject("a")
            ?: return false to "zx_confront 缺少 a（一侧）"
        val b = o.optJSONObject("b")
            ?: return false to "zx_confront 缺少 b（另一侧）"
        for ((k, side) in listOf("a" to a, "b" to b)) {
            if (side.optString("n").isBlank()) return false to "$k 缺少 n（这一侧是谁）"
            if (side.optString("t").isBlank()) return false to "$k 缺少 t（这一侧的说法）"
            val c = side.optDouble("c", Double.NaN)
            if (c.isNaN()) return false to "$k 缺少 c（置信度）"
            if (c < 0 || c > 1) return false to "$k.c=$c 超出 0-1"
        }
        // 两边说的一样：那不叫冲突
        if (a.optString("t") == b.optString("t"))
            return false to "两侧的说法完全相同——那不叫冲突，不需要对质"
        val v = o.optString("verdict", "")
        if (v.isNotBlank() && v !in setOf("A", "B", "both", "none"))
            return false to "verdict=\"$v\" 只能是 A / B / both / none"
        return true to ""
    }

    private fun matrix3(o: JSONObject): Pair<Boolean, String> {
        val items = o.optJSONArray("items")
            ?: return false to "zx_matrix3 缺少 items"
        if (items.length() < 2) return false to "影响矩阵至少 2 项，实际 ${items.length()} 项"
        if (items.length() > 12) return false to "影响矩阵最多 12 项，实际 ${items.length()} 项"
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i)
                ?: return false to "items[$i] 不是对象，应为 {\"n\":名,\"i\":重要性,\"e\":影响力}"
            if (it.optString("n").isBlank()) return false to "items[$i] 缺少 n"
            val im = it.optDouble("i", Double.NaN)
            val ef = it.optDouble("e", Double.NaN)
            if (im.isNaN()) return false to "items[$i] 缺少 i（重要性）"
            if (ef.isNaN()) return false to "items[$i] 缺少 e（影响力）"
            if (im < 0 || im > 1) return false to "items[$i].i=$im 超出 0-1"
            if (ef < 0 || ef > 1) return false to "items[$i].e=$ef 超出 0-1"
        }
        return true to ""
    }

    private fun receipt(o: JSONObject): Pair<Boolean, String> {
        val id = o.optString("id")
        // 单号是回执的全部意义
        if (id.isBlank()) return false to "zx_receipt 缺少 id（单号）——没有单号的回执没法查证"
        if (o.optString("at").isBlank()) return false to "zx_receipt 缺少 at（时间）"
        val st = o.optString("st", "ok")
        if (st !in setOf("ok", "warn", "err"))
            return false to "zx_receipt 的 st=\"$st\" 只能是 ok / warn / err"
        val rows = o.optJSONArray("rows")
        if (rows != null) {
            if (rows.length() > 8) return false to "明细最多 8 行，实际 ${rows.length()} 行"
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i)
                    ?: return false to "rows[$i] 不是对象，应为 {\"k\":,\"v\":}"
                if (r.optString("k").isBlank()) return false to "rows[$i] 缺少 k"
            }
        }
        return true to ""
    }

    private fun ledger(o: JSONObject): Pair<Boolean, String> {
        val rows = o.optJSONArray("rows")
            ?: return false to "zx_ledger 缺少 rows"
        if (rows.length() == 0) return false to "zx_ledger 的 rows 为空"
        if (rows.length() > 10) return false to "流水最多 10 行，实际 ${rows.length()} 行"
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i)
                ?: return false to "rows[$i] 不是对象，应为 {\"n\":名,\"d\":变动}"
            if (r.optString("n").isBlank()) return false to "rows[$i] 缺少 n"
            val d = r.optDouble("d", Double.NaN)
            // 缺 d 会让余额算错，而且不报错
            if (d.isNaN()) return false to "rows[$i] 缺少 d（变动额）——缺了余额会算错且看不出来"
        }
        return true to ""
    }

    private fun gauntlet(o: JSONObject): Pair<Boolean, String> {
        val rounds = o.optJSONArray("rounds")
            ?: return false to "zx_gauntlet 缺少 rounds"
        if (rounds.length() < 2)
            return false to "多轮筛选至少 2 轮，实际 ${rounds.length()} 轮——1 轮就是一次过滤，看不出淘汰过程"
        if (rounds.length() > 6) return false to "多轮筛选最多 6 轮，实际 ${rounds.length()} 轮"
        var prev = Int.MAX_VALUE
        for (i in 0 until rounds.length()) {
            val r = rounds.optJSONObject(i)
                ?: return false to "rounds[$i] 不是对象，应为 {\"n\":名,\"n\":剩余数,\"pass\":}"
            if (r.optString("n").isBlank()) return false to "rounds[$i] 缺少 n"
            val c = r.optInt("cnt", -1)
            if (c < 0) return false to "rounds[$i] 缺少 cnt（本轮剩余数）"
            // 后一轮不可能比前一轮多：那是数据给错了，用户会推出错的淘汰率
            if (c > prev)
                return false to "rounds[$i].cnt=$c 大于上一轮的 $prev——筛选只能越筛越少，数据给反了"
            prev = c
        }
        return true to ""
    }

    private fun chain(o: JSONObject): Pair<Boolean, String> {
        val links = o.optJSONArray("links")
            ?: return false to "zx_chain 缺少 links"
        if (links.length() < 2)
            return false to "依赖链至少 2 节，实际 ${links.length()} 节——1 节谈不上「最弱一环」"
        if (links.length() > 8) return false to "依赖链最多 8 节，实际 ${links.length()} 节"
        for (i in 0 until links.length()) {
            val l = links.optJSONObject(i)
                ?: return false to "links[$i] 不是对象，应为 {\"n\":名,\"s\":强度}"
            if (l.optString("n").isBlank()) return false to "links[$i] 缺少 n"
            val s = l.optDouble("s", Double.NaN)
            if (s.isNaN()) return false to "links[$i] 缺少 s（强度）"
            if (s < 0 || s > 1) return false to "links[$i].s=$s 超出 0-1"
        }
        return true to ""
    }

    private fun bracket(o: JSONObject): Pair<Boolean, String> {
        val rounds = o.optJSONArray("rounds")
            ?: return false to "zx_bracket 缺少 rounds"
        if (rounds.length() == 0) return false to "zx_bracket 的 rounds 为空"
        if (rounds.length() > 4) return false to "对阵最多 4 轮，实际 ${rounds.length()} 轮"
        var prev = Int.MAX_VALUE
        for (r in 0 until rounds.length()) {
            val arr = rounds.optJSONArray(r)
                ?: return false to "rounds[$r] 不是数组"
            if (arr.length() == 0) return false to "rounds[$r] 为空"
            if (arr.length() > 8) return false to "每轮最多 8 场，实际 ${arr.length()} 场"
            // 对阵每轮场次减半
            if (arr.length() > prev)
                return false to "rounds[$r] 有 ${arr.length()} 场，多于上一轮的 $prev——对阵每轮场次应递减"
            prev = arr.length()
            for (i in 0 until arr.length()) {
                val g = arr.optJSONObject(i)
                    ?: return false to "rounds[$r][$i] 不是对象，应为 {\"a\":,\"b\":,\"sa\":,\"sb\":,\"w\":}"
                val a = g.optString("a"); val b = g.optString("b")
                if (a.isBlank() || b.isBlank())
                    return false to "rounds[$r][$i] 缺少 a 或 b——对阵要有两方"
                if (a == b) return false to "rounds[$r][$i] 的 a=b=\"$a\"——自己打自己"
                val w = g.optString("w")
                if (w.isNotBlank() && w != a && w != b)
                    return false to "rounds[$r][$i].w=\"$w\" 既不是 a 也不是 b——胜者必须是对阵两方之一"
            }
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_traceback" -> """
            {"type":"zx_traceback","nodes":[
              {"n":"报错","p":-1,"k":"现象"}",
              {"n":"空指针","p":0,"k":"直接原因"}",
              {"n":"未初始化","p":1,"k":"根因"}"]}""".trimIndent()
        "zx_seq" -> """
            {"type":"zx_seq","actors":["客户端","网关","数据库"],
             "msgs":[{"f":0,"t":1,"n":"请求"}",
                     {"f":1,"t":2,"n":"查询","wait":true},
                     {"f":2,"t":1,"n":"返回"}"]}""".trimIndent()
        "zx_explode" -> """
            {"type":"zx_explode","spread":0.5,"parts":[
              {"n":"外壳","w":30,"d":"铝合金"}",
              {"n":"主板","w":45,"d":"8 层板"}",
              {"n":"电池","w":25,"d":"5000mAh"}]}""".trimIndent()
        "zx_critical" -> """
            {"type":"zx_critical","n":"上线计划","tasks":[
              {"n":"设计","d":5,"after":[]},
              {"n":"开发","d":12,"after":[0]},
              {"n":"测试","d":6,"after":[1]}]}""".trimIndent()
        "zx_evidence" -> """
            {"type":"zx_evidence","concl":"应该拆分成两个服务",
             "items":[{"t":"该模块 QPS 是其余 8 倍","k":"fact","c":0.95},
                      {"t":"拆分后运维成本可控","k":"infer","c":0.6}]}""".trimIndent()
        "zx_confront" -> """
            {"type":"zx_confront","n":"版本冲突",
             "a":{"n":"文档","t":"需要 v2.1","c":0.8},
             "b":{"n":"实测","t":"v2.1 会崩","c":0.9}}""".trimIndent()
        "zx_matrix3" -> """
            {"type":"zx_matrix3","n":"优先级","items":[
              {"n":"登录","i":0.9,"e":0.8},
              {"n":"换肤","i":0.2,"e":0.6}]}""".trimIndent()
        "zx_receipt" -> """
            {"type":"zx_receipt","id":"TX20240115-8842","at":"2024-01-15 14:32",
             "st":"ok","rows":[{"k":"金额","v":"¥1,280.00"},{"k":"收款方","v":"某某"}"]}""".trimIndent()
        "zx_ledger" -> """
            {"type":"zx_ledger","n":"账户流水","rows":[
              {"n":"充值","d":1000},{"n":"消费","d":-240},
              {"n":"退款","d":80}]}""".trimIndent()
        "zx_gauntlet" -> """
            {"type":"zx_gauntlet","n":"简历筛选","rounds":[
              {"n":"投递","cnt":1200},{"n":"初筛","cnt":240},
              {"n":"笔试","cnt":60}]}""".trimIndent()
        "zx_chain" -> """
            {"type":"zx_chain","n":"发布链路","links":[
              {"n":"构建","s":0.9},{"n":"测试","s":0.55},
              {"n":"部署","s":0.85}]}""".trimIndent()
        "zx_bracket" -> """
            {"type":"zx_bracket","names":["半决赛","决赛"],
             "rounds":[[{"a":"A","b":"B","sa":"3","sb":"1","w":"A"},
                        {"a":"C","b":"D","sa":"2","sb":"2","w":"C"}],
                       [{"a":"A","b":"C","sa":"3","sb":"0","w":"A"}]]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_traceback" -> "nodes[{n,p,k?}]  2-12 · p 不指向自己、无环"
        "zx_seq"       -> "actors 2-5 + msgs[{f,t,n,wait?}]  ≤10 · f≠t"
        "zx_explode"   -> "parts[{n,w>0,d?}]  2-8 · 不可全 0 · spread 0-1"
        "zx_critical"  -> "tasks[{n,d>0,after?[]}]  2-10 · 无自依赖无环"
        "zx_evidence"  -> "concl + items[{t,k,c}]  ≤8 · 至少一条 fact"
        "zx_confront"  -> "a{n,t,c} + b{n,t,c} · 两侧 t 不可相同"
        "zx_matrix3"   -> "items[{n,i,e}]  2-12 · 四象限给结论"
        "zx_receipt"   -> "id + at + st ∈ ok/warn/err + rows? ≤8"
        "zx_ledger"    -> "rows[{n,d}]  ≤10 · 缺 d 余额会错且看不出"
        "zx_gauntlet"  -> "rounds[{n,cnt}]  2-6 · cnt 单调不增"
        "zx_chain"     -> "links[{n,s}]  2-8 · 整链强度 = 最弱一节"
        "zx_bracket"   -> "rounds[[{a,b,sa?,sb?,w?}]]  ≤4 轮 · 场次递减"
        else -> "未注册组件：$component"
    }
}
