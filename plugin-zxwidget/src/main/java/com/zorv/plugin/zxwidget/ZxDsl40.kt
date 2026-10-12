package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 第四十批（交接完成 ≠ 责任结束）的校验 + 示例 + 规格。
 *
 * 这批查的是**「签了字就说完了，没说后面那段」**。
 *
 * ── 它和第三十九批的差别 ────────────────────────────────────
 *
 * 第三十九批解决的是**交接那一刻**：
 * 状态快照、双方签字、异议入口。它把"转移的瞬间"钉死了。
 *
 * 但签完字之后还有一段：
 *
 *   猫接回来了，三天后拉稀——是寄养期间吃的还是接回来吃的？
 *   装修尾款付了，一个月后墙裂了——还能不能找他？
 *   员工交接完了，两周后发现他经手的账有问题——还能不能问他？
 *   账号权限回收了，但密钥还在他手里——那算回收了吗？
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **交接签字之后还有一段「责任还没真正结束」的时间，
 *   这段有多长、什么时候真正结束，必须明说。**
 *
 * 签字那一刻看起来是终点，实际往往只是中点。不说清后面那一段：
 *
 *   以为已经结束了 → 出问题找不到人
 *   以为还没结束   → 一直被追着问，或者不敢动
 *
 * 所以每个组件都要给出三样东西：
 *   1. **交接完成的时间点**
 *   2. **残留期还有多久**（质保 / 追溯 / 过渡 / 追责 / 生效滞后…）
 *   3. **残留的是什么**（不只是"还有责任"，要具体到哪一类）
 *
 * ── 要拦的（与 ZxRender40 注释一一对应） ────────────────────
 *
 *   housekeep    缺服务周期 / **缺本次到下次的追责期** / 缺验收记录
 *   finalpay     **缺质保期** / **缺尾款比例** / **有未修项却要付全款**
 *   fosterdaily  **缺每日体重** / **缺追溯期** / 日期有缺漏
 *   wardhand     **缺观察期** / **缺生命体征** / 缺转出或转入
 *   schoolnote   **缺回复期限** / 缺发起方 / 缺待办
 *   sublet       **缺房东同意** / **缺原租客责任终止点** / 缺押金转手
 *   inspecthand  **缺上次巡检** / **缺周期** / **超期不标**
 *   projhandover **缺过渡期** / **缺响应时限** / **未结事项没定归属**
 *   stocktake    **缺上次盘点** / **有差异缺原因** / 缺周期
 *   offboard     **缺追责期** / **未回收权限就判完成**
 *   revoke       **缺权限类型** / **还有开着就判完成** / 缺生效滞后
 *   warrtrack    **缺保修截止** / **有未报问题** / 缺问题登记
 */
object ZxDsl40 {

    val ALL = listOf(
        "zx_housekeep", "zx_finalpay", "zx_fosterdaily", "zx_wardhand",
        "zx_schoolnote", "zx_sublet", "zx_inspecthand", "zx_projhandover",
        "zx_stocktake", "zx_offboard", "zx_revoke", "zx_warrtrack"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_housekeep" -> housekeep(root)
        "zx_finalpay" -> finalpay(root)
        "zx_fosterdaily" -> fosterdaily(root)
        "zx_wardhand" -> wardhand(root)
        "zx_schoolnote" -> schoolnote(root)
        "zx_sublet" -> sublet(root)
        "zx_inspecthand" -> inspecthand(root)
        "zx_projhandover" -> projhandover(root)
        "zx_stocktake" -> stocktake(root)
        "zx_offboard" -> offboard(root)
        "zx_revoke" -> revoke(root)
        "zx_warrtrack" -> warrtrack(root)
        else -> false to "未注册组件：$component"
    }

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun housekeep(o: JSONObject): Pair<Boolean, String> {
        // 缺服务周期
        val cyc = o.optInt("cyc", -1)
        if (cyc <= 0)
            return false to "zx_housekeep **缺 cyc（服务周期天）**——" +
                "**家政是周期性的，不给周期就不知道多久该来一次**"
        if (cyc > 365) return false to "zx_housekeep 的 cyc=$cyc 超过一年"
        val a = o.optJSONArray("visits")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_housekeep 缺少 visits——**没有上门记录**"
        if (n > 31) return false to "zx_housekeep 最多 31 次，实际 $n 次"
        for (k in 0 until n) {
            val v = a.optJSONObject(k)
                ?: return false to "visits[$k] 不是对象，应为 {\"d\":日期,\"who\":阿姨,\"ok\":是否通过}"
            if (v.optString("d").isBlank()) return false to "visits[$k] 缺少 d（日期）"
            if (v.optString("who").isBlank())
                return false to "visits[$k] 缺 who（是谁来的）——" +
                    "**同一家派人不同，人不行要换的是人不是公司**"
            if (!v.has("ok"))
                return false to "visits[$k] 缺 ok（本次验收过没过）——" +
                    "**单次验收说明不了什么，累计才看得出**"
        }
        // 缺本次到下次的追责期
        if (!o.has("leftH"))
            return false to "zx_housekeep **缺 leftH（本次验收到下次上门之间的追责期）**——" +
                "**这次没验好、下次来之前这段时间出了问题算谁的**"
        return true to ""
    }

    private fun finalpay(o: JSONObject): Pair<Boolean, String> {
        val total = o.optDouble("total", Double.NaN)
        if (total.isNaN() || total <= 0)
            return false to "zx_finalpay 缺 total（总价）或总价非正"
        // 缺尾款比例
        val hp = o.optDouble("holdPct", Double.NaN)
        if (hp.isNaN() || hp <= 0 || hp > 1.0)
            return false to "zx_finalpay **缺 holdPct（尾款比例）或不在 0-1**——" +
                "**尾款的意义就是压住质保期**，不给比例就不知道压了多少"
        // 缺质保期
        if (!o.has("warrH"))
            return false to "zx_finalpay **缺 warrH（质保期小时）**——" +
                "**不知道质保到哪天，就不知道尾款该压到什么时候**"
        val wh = o.optInt("warrH", 0)
        val a = o.optJSONArray("issues")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_finalpay 缺少 issues——**没有验收问题登记**"
        if (n > 20) return false to "zx_finalpay 最多 20 项，实际 $n 项"
        var unfixed = 0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "issues[$k] 不是对象，应为 {\"n\":问题,\"h\":完工后多少小时发现,\"fix\":是否已修}"
            if (it.optString("n").isBlank()) return false to "issues[$k] 缺少 n（问题）"
            if (!it.has("h"))
                return false to "issues[$k] 缺 h（完工后多少小时发现）——" +
                    "**不知道什么时候发现的，就判不了在不在质保内**"
            if (it.optInt("h", 0) < 0) return false to "issues[$k] 的 h 不能为负"
            if (!it.has("fix"))
                return false to "issues[$k] 缺 fix（是否已修）——**没修就付尾款，筹码就没了**"
            if (!it.optBoolean("fix", false)) unfixed++
        }
        // 有未修项却要付全款
        if (unfixed > 0 && hp <= 0.001)
            return false to "zx_finalpay **有 $unfixed 项未修但尾款比例为 0**——" +
                "**这时候付全款，他可以不认剩下的**"
        return true to ""
    }

    private fun fosterdaily(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("days")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_fosterdaily 缺少 days——**没有日报**"
        if (n > 60) return false to "zx_fosterdaily 最多 60 天，实际 $n 天"
        var prev = -1
        for (k in 0 until n) {
            val d = a.optJSONObject(k)
                ?: return false to "days[$k] 不是对象，应为 {\"d\":第几天,\"w\":体重,\"eat\":进食,\"mood\":精神}"
            val day = d.optInt("d", -1)
            if (day < 0) return false to "days[$k] 缺少 d（第几天）"
            // 日期有缺漏
            if (prev >= 0 && day != prev + 1)
                return false to "days[$k] 第 $day 天，上一项是第 $prev 天——" +
                    "**中间缺了天，体重掉在哪一段就说不清**"
            prev = day
            // 缺每日体重
            val w = d.optDouble("w", Double.NaN)
            if (w.isNaN() || w <= 0)
                return false to "days[$k]（第 $day 天）**缺 w（体重）**——" +
                    "**体重是寄养期间唯一能看出问题的客观量**"
            if (w > 200) return false to "days[$k] 的 w=$w 超过 200kg"
            if (d.optString("eat").isBlank())
                return false to "days[$k] 缺 eat（进食情况）——**不吃比体重掉更早出现**"
        }
        // 缺追溯期
        if (!o.has("traceH"))
            return false to "zx_fosterdaily **缺 traceH（追溯期小时）**——" +
                "**寄养的问题往往在接回来之后才显现**，" +
                "不给追溯期，接回来三天出问题双方都说不是自己的"
        return true to ""
    }

    private fun wardhand(o: JSONObject): Pair<Boolean, String> {
        // 缺转出或转入
        if (o.optString("from").isBlank() || o.optString("to").isBlank())
            return false to "zx_wardhand **缺 from 或 to（转出科/转入科）**——" +
                "**不知道从哪转到哪，出问题时两边都说不是自己的**"
        // 缺观察期
        if (!o.has("obsH"))
            return false to "zx_wardhand **缺 obsH（观察期小时）**——" +
                "**转科后那几个小时是最容易出事的，也是最容易被两边推的**"
        if (o.optInt("obsH", 0) <= 0)
            return false to "zx_wardhand 的 obsH 必须为正——**观察期为 0 等于没定**"
        val v = o.optJSONArray("vitals")
        // 缺生命体征
        if (v == null || v.length() == 0)
            return false to "zx_wardhand **缺 vitals（交接时生命体征）**——" +
                "**转科交接的全部意义就是这一组数**，没有它交的是什么"
        if (v.length() > 12) return false to "zx_wardhand 最多 12 项体征，实际 ${v.length()} 项"
        for (k in 0 until v.length()) {
            val it = v.optJSONObject(k)
                ?: return false to "vitals[$k] 不是对象，应为 {\"n\":项目,\"v\":值}"
            if (it.optString("n").isBlank()) return false to "vitals[$k] 缺少 n"
            if (it.optString("v").isBlank())
                return false to "vitals[$k]「${it.optString("n")}」缺 v（值）——" +
                    "**只写项目不写值，等于没交接**"
        }
        val t = o.optJSONArray("todo")
        if (t == null || t.length() == 0)
            return false to "zx_wardhand 缺少 todo（待办）"
        if (t.length() > 15) return false to "zx_wardhand 最多 15 项待办，实际 ${t.length()} 项"
        for (k in 0 until t.length()) {
            val it = t.optJSONObject(k)
                ?: return false to "todo[$k] 不是对象，应为 {\"n\":事项,\"done\":是否完成}"
            if (it.optString("n").isBlank()) return false to "todo[$k] 缺少 n"
            if (!it.has("done")) return false to "todo[$k] 缺 done（是否完成）"
        }
        return true to ""
    }

    private fun schoolnote(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("notes")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_schoolnote 缺少 notes——**没有沟通记录**"
        if (n > 20) return false to "zx_schoolnote 最多 20 条，实际 $n 条"
        for (k in 0 until n) {
            val nt = a.optJSONObject(k)
                ?: return false to "notes[$k] 不是对象，应为 {\"n\":事项,\"who\":发起方,\"h\":距回复期限小时,\"done\":已回复}"
            if (nt.optString("n").isBlank()) return false to "notes[$k] 缺少 n（事项）"
            // 缺发起方
            if (nt.optString("who").isBlank())
                return false to "notes[$k] 缺 who（发起方）——" +
                    "**不知道是谁发的，双方都以为是对方的事**"
            // 缺回复期限
            if (!nt.has("h"))
                return false to "notes[$k]「${nt.optString("n")}」**缺 h（距回复期限小时）**——" +
                    "**只发通知不设期限，这条就会一直挂着没人回**"
            if (!nt.has("done"))
                return false to "notes[$k] 缺 done（是否已回复）——**不标状态看不出超期**"
        }
        return true to ""
    }

    private fun sublet(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("old").isBlank() || o.optString("new").isBlank())
            return false to "zx_sublet **缺 old 或 new（原租客/新租客）**——" +
                "**转租三方缺一，房东可以不认**"
        // 缺押金转手
        val dep = o.optDouble("dep", Double.NaN)
        if (dep.isNaN())
            return false to "zx_sublet **缺 dep（押金转手金额）**——" +
                "**押金是转租里最容易扯皮的一笔**，不给数额就说不清"
        if (dep < 0) return false to "zx_sublet 的 dep 不能为负"
        // 缺房东同意
        if (!o.has("ok") || !o.optBoolean("ok", false))
            return false to "zx_sublet **缺 ok 或房东未同意（ok=true）**——" +
                "**没经房东同意的转租，原租客的责任一直挂着**"
        // 缺原租客责任终止点
        if (!o.has("endH"))
            return false to "zx_sublet **缺 endH（原租客责任还剩多少小时）**——" +
                "**不给终止点，原租客不知道自己什么时候才算脱身**"
        val a = o.optJSONArray("items")
        if (a == null || a.length() == 0)
            return false to "zx_sublet 缺少 items（待办）"
        if (a.length() > 15) return false to "zx_sublet 最多 15 项，实际 ${a.length()} 项"
        for (k in 0 until a.length()) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":事项,\"done\":是否完成}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n"
            if (!it.has("done")) return false to "items[$k] 缺 done"
        }
        return true to ""
    }

    private fun inspecthand(o: JSONObject): Pair<Boolean, String> {
        // 缺上次巡检
        if (o.optString("last").isBlank())
            return false to "zx_inspecthand **缺 last（上次巡检日期）**——" +
                "**巡检的价值在两次之间**，不知道上次是哪天，就不知道空了多久"
        // 缺周期
        val cyc = o.optInt("cycD", -1)
        if (cyc <= 0)
            return false to "zx_inspecthand **缺 cycD（巡检周期天）**——" +
                "**没有周期就判不了超期**"
        if (cyc > 3650) return false to "zx_inspecthand 的 cycD=$cyc 超过十年"
        val since = o.optInt("sinceD", -1)
        if (since < 0)
            return false to "zx_inspecthand 缺 sinceD（距上次多少天）"
        // 超期不标
        if (since > cyc && o.optString("miss").isBlank())
            return false to "zx_inspecthand **已超期 ${since - cyc} 天但没写 miss（后果）**——" +
                "**这段空档没人负责，不标出来用户以为一直有人在查**"
        val a = o.optJSONArray("find")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_inspecthand 缺少 find（本周期发现项）"
        if (n > 20) return false to "zx_inspecthand 最多 20 项，实际 $n 项"
        for (k in 0 until n) {
            val f = a.optJSONObject(k)
                ?: return false to "find[$k] 不是对象，应为 {\"n\":发现项,\"lv\":严重/注意/一般,\"fix\":是否已处理}"
            if (f.optString("n").isBlank()) return false to "find[$k] 缺少 n"
            if (f.optString("lv") !in listOf("严重", "注意", "一般"))
                return false to "find[$k].lv 只能是「严重」「注意」「一般」——" +
                    "**不分级就不知道哪个必须先处理**"
            if (!f.has("fix"))
                return false to "find[$k] 缺 fix（是否已处理）——**发现了不处理等于没查**"
        }
        return true to ""
    }

    private fun projhandover(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("old").isBlank() || o.optString("new").isBlank())
            return false to "zx_projhandover **缺 old 或 new（原/新负责人）**——" +
                "**不知道交给谁，过渡期内出问题找不到人**"
        // 缺过渡期
        val trans = o.optInt("transH", -1)
        if (trans <= 0)
            return false to "zx_projhandover **缺 transH（过渡期小时）或不为正**——" +
                "**文档交了不等于新负责人能上手，中间必须有过渡期**"
        if (trans > 24 * 365) return false to "zx_projhandover 的 transH 超过一年"
        // 缺响应时限
        val oc = o.optInt("oncallH", -1)
        if (oc <= 0)
            return false to "zx_projhandover **缺 oncallH（随叫随到的响应时限小时）**——" +
                "**过渡期不写响应速度，原负责人可以不接电话**"
        val a = o.optJSONArray("open")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_projhandover 缺少 open（未结事项）"
        if (n > 20) return false to "zx_projhandover 最多 20 项，实际 $n 项"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "open[$k] 不是对象，应为 {\"n\":未结事项,\"who\":归谁}"
            if (it.optString("n").isBlank()) return false to "open[$k] 缺少 n"
            // 未结事项没定归属
            if (it.optString("who").isBlank())
                return false to "open[$k]「${it.optString("n")}」**缺 who（归谁）**——" +
                    "**没定归属的事项，两边都以为对方在处理**"
        }
        return true to ""
    }

    private fun stocktake(o: JSONObject): Pair<Boolean, String> {
        // 缺上次盘点
        if (o.optString("last").isBlank())
            return false to "zx_stocktake **缺 last（上次盘点日期）**——" +
                "**不知道上次是哪天，就追不到多远以前**"
        val cyc = o.optInt("cycD", -1)
        if (cyc <= 0)
            return false to "zx_stocktake **缺 cycD（盘点周期天）**——" +
                "**没有周期就不知道这次覆盖的是哪一段**"
        if (o.optInt("sinceD", -1) < 0)
            return false to "zx_stocktake 缺 sinceD（距上次多少天）"
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_stocktake 缺少 items——**没有盘点项**"
        if (n > 30) return false to "zx_stocktake 最多 30 项，实际 $n 项"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":品名,\"book\":账面,\"real\":实盘,\"why\":差异原因}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（品名）"
            val b = it.optInt("book", Int.MIN_VALUE)
            val r = it.optInt("real", Int.MIN_VALUE)
            if (b == Int.MIN_VALUE || r == Int.MIN_VALUE)
                return false to "items[$k] 缺 book 或 real——**账面和实盘缺一就没法比**"
            if (b < 0 || r < 0) return false to "items[$k] 的数量不能为负"
            // 有差异缺原因
            if (b != r && it.optString("why").isBlank())
                return false to "items[$k]「${it.optString("n")}」**账 $b 实 $r 有差异但没写 why（原因）**——" +
                    "**盘亏可能是丢了、记错了、被拿了，三种处置完全不同**"
        }
        return true to ""
    }

    private fun offboard(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("who").isBlank())
            return false to "zx_offboard 缺 who（离职人）——**不知道是谁的交接**"
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_offboard 缺少 items——**没有交接项**"
        if (n > 25) return false to "zx_offboard 最多 25 项，实际 $n 项"
        var left = 0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":交接项,\"done\":是否完成}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n"
            if (!it.has("done")) return false to "items[$k] 缺 done"
            if (!it.optBoolean("done", false)) left++
        }
        // 未回收权限就判完成
        if (!o.optBoolean("revoke", false))
            return false to "zx_offboard **权限还没回收（revoke 不为 true）**——" +
                "**交接完了但账号还开着，这不叫离职**"
        // 缺追责期
        if (!o.has("clawH"))
            return false to "zx_offboard **缺 clawH（追责期小时）**——" +
                "**离职后这段时间内发现他在职时的问题还能不能追，必须写明**"
        return true to ""
    }

    private fun revoke(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("who").isBlank())
            return false to "zx_revoke 缺 who（被回收人）"
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_revoke 缺少 items——**没有权限项**"
        if (n > 25) return false to "zx_revoke 最多 25 项，实际 $n 项"
        var open_ = 0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":权限,\"t\":系统/令牌/密钥/物理,\"off\":是否已关,\"lagH\":生效滞后小时}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n"
            // 缺权限类型
            val t = it.optString("t", "")
            if (t !in listOf("系统", "令牌", "密钥", "物理"))
                return false to "items[$k]「${it.optString("n")}」**缺 t 或类型不在（系统/令牌/密钥/物理）**——" +
                    "**四类的失效时点完全不同，混在一起标「已回收」是假的**"
            if (!it.has("off")) return false to "items[$k] 缺 off（是否已关）"
            if (!it.optBoolean("off", false)) open_++
            if (it.optInt("lagH", 0) < 0) return false to "items[$k] 的 lagH 不能为负"
        }
        // 还有开着就判完成
        if (open_ > 0 && o.optBoolean("done", false))
            return false to "zx_revoke **还有 $open_ 项开着却标了 done=true**——" +
                "**系统权限关了但密钥还在他手里，那不算回收**"
        return true to ""
    }

    private fun warrtrack(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("item").isBlank())
            return false to "zx_warrtrack 缺 item（物品）"
        // 缺保修截止
        if (!o.has("endH"))
            return false to "zx_warrtrack **缺 endH（保修截止还剩多少小时）**——" +
                "**不知道什么时候过保，就不知道什么时候必须去报**"
        val endH = o.optInt("endH", 0)
        val a = o.optJSONArray("issues")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_warrtrack 缺少 issues——**没有登记的问题**"
        if (n > 20) return false to "zx_warrtrack 最多 20 项，实际 $n 项"
        var untold = 0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "issues[$k] 不是对象，应为 {\"n\":问题,\"h\":已过去多少小时,\"told\":是否已报}"
            if (it.optString("n").isBlank()) return false to "issues[$k] 缺少 n"
            if (!it.has("h"))
                return false to "issues[$k] 缺 h（距现在多少小时发现）——" +
                    "**不知道什么时候发现的，判不了在不在保内**"
            if (!it.has("told"))
                return false to "issues[$k] 缺 told（是否已报）——" +
                    "**发现了不报，过保那天它就变成自费了**"
            if (!it.optBoolean("told", false)) untold++
        }
        // 有未报问题（且还在保内）
        if (untold > 0 && endH > 0 && o.optBoolean("done", false))
            return false to "zx_warrtrack **还有 $untold 个问题没报却标了 done=true**——" +
                "**在保 ≠ 修了，不报就等于放弃**"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_housekeep" -> """
            {"type":"zx_housekeep","n":"家政","cyc":7,"leftH":120,"visits":[
               {"d":"05-06","who":"王阿姨","ok":true},
               {"d":"05-13","who":"王阿姨","ok":true},
               {"d":"05-20","who":"李阿姨","ok":false}]}""".trimIndent()
        "zx_finalpay" -> """
            {"type":"zx_finalpay","n":"尾款","total":128000,"holdPct":0.10,
             "warrH":2160,"issues":[
               {"n":"主卧墙面开裂","h":720,"fix":true},
               {"n":"卫生间渗水","h":1440,"fix":false}]}""".trimIndent()
        "zx_fosterdaily" -> """
            {"type":"zx_fosterdaily","n":"寄养日报","traceH":168,"days":[
               {"d":1,"w":4.8,"eat":"正常","mood":"活泼"}",
               {"d":2,"w":4.8,"eat":"正常","mood":"活泼"}",
               {"d":3,"w":4.7,"eat":"略少","mood":"安静"}",
               {"d":4,"w":4.5,"eat":"少","mood":"蔫"}"]}""".trimIndent()
        "zx_wardhand" -> """
            {"type":"zx_wardhand","n":"转科","from":"急诊","to":"心内科",
             "obsH":24,"vitals":[
               {"n":"血压","v":"138/86"},{"n":"心率","v":"92"},
               {"n":"血氧","v":"96%"}],
             "todo":[{"n":"复查心电图","done":true},
               {"n":"停急诊用药","done":false}]}""".trimIndent()
        "zx_schoolnote" -> """
            {"type":"zx_schoolnote","n":"家校沟通","termH":1440,"notes":[
               {"n":"明天带运动服","who":"老师","h":12,"done":true},
               {"n":"视力复查回执","who":"校医","h":-6,"done":false},
               {"n":"家长会确认","who":"班主任","h":48,"done":false}]}""".trimIndent()
        "zx_sublet" -> """
            {"type":"zx_sublet","n":"转租","old":"原租客","new":"新租客",
             "dep":4200,"ok":true,"endH":720,"items":[
               {"n":"钥匙移交","done":true},
               {"n":"物业登记变更","done":true},
               {"n":"水电过户","done":false}]}""".trimIndent()
        "zx_inspecthand" -> """
            {"type":"zx_inspecthand","n":"巡检","last":"04-15","cycD":30,
             "sinceD":38,"miss":"超期这段没人负责","find":[
               {"n":"消防通道堆放杂物","lv":"严重","fix":false},
               {"n":"应急灯一盏不亮","lv":"注意","fix":true},
               {"n":"灭火器压力正常","lv":"一般","fix":true}]}""".trimIndent()
        "zx_projhandover" -> """
            {"type":"zx_projhandover","n":"项目移交","old":"张三","new":"李四",
             "transH":720,"oncallH":4,"open":[
               {"n":"供应商合同待签","who":"李四"}",
               {"n":"线上遗留 bug","who":"张三"}",
               {"n":"文档补全","who":"李四"}"]}""".trimIndent()
        "zx_stocktake" -> """
            {"type":"zx_stocktake","n":"盘点","last":"04-30","cycD":30,
             "sinceD":32,"items":[
               {"n":"A4 纸","book":120,"real":117,"why":"领用未登记"}",
               {"n":"硒鼓","book":8,"real":8},
               {"n":"订书机","book":15,"real":13,"why":"两台北楼借用未归还"}"]}""".trimIndent()
        "zx_offboard" -> """
            {"type":"zx_offboard","n":"离职交接","who":"王五","revoke":true,
             "clawH":2160,"items":[
               {"n":"客户资料移交","done":true},
               {"n":"未报销单据","done":true},
               {"n":"门禁卡归还","done":false}]}""".trimIndent()
        "zx_revoke" -> """
            {"type":"zx_revoke","n":"权限回收","who":"王五","items":[
               {"n":"OA 账号","t":"系统","off":true,"lagH":0},
               {"n":"VPN 令牌","t":"令牌","off":true,"lagH":12},
               {"n":"生产库密钥","t":"密钥","off":true,"lagH":24},
               {"n":"办公室钥匙","t":"物理","off":false,"lagH":0}]}""".trimIndent()
        "zx_warrtrack" -> """
            {"type":"zx_warrtrack","n":"质保追踪","item":"洗碗机","endH":360,
             "issues":[
               {"n":"排水慢","h":0,"told":true},
               {"n":"门封条老化","h":120,"told":false}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_housekeep" -> "**cyc（缺会被拦）** + visits[{d,**who 非空**,ok}] ≤31 + **leftH（缺会被拦）**"
        "zx_finalpay" -> "total>0 + **holdPct∈0-1（缺会被拦）** + **warrH（缺会被拦）** + issues[{n,**h**,**fix**}] ≤20；**有未修项但 holdPct=0 会被拦**"
        "zx_fosterdaily" -> "days[{d 连续（缺天会被拦）,**w>0（缺会被拦）**,eat 非空,mood?}] ≤60 + **traceH（缺会被拦）**"
        "zx_wardhand" -> "**from/to 非空（缺会被拦）** + **obsH>0（缺会被拦）** + **vitals[{n,v 非空}]（缺会被拦）** ≤12 + todo[{n,done}] ≤15"
        "zx_schoolnote" -> "notes[{n,**who 非空（缺会被拦）**,**h（缺会被拦）**,done}] ≤20 + termH?"
        "zx_sublet" -> "**old/new 非空** + **dep（缺会被拦）** + **ok=true（房东未同意会被拦）** + **endH（缺会被拦）** + items[{n,done}] ≤15"
        "zx_inspecthand" -> "**last 非空（缺会被拦）** + **cycD>0（缺会被拦）** + sinceD + find[{n,**lv∈严重/注意/一般**,fix}] ≤20；**超期缺 miss 会被拦**"
        "zx_projhandover" -> "**old/new 非空** + **transH>0（缺会被拦）** + **oncallH>0（缺会被拦）** + open[{n,**who 非空（缺会被拦）**}] ≤20"
        "zx_stocktake" -> "**last 非空（缺会被拦）** + **cycD>0（缺会被拦）** + sinceD + items[{n,book,real,**有差异时 why 非空（缺会被拦）**}] ≤30"
        "zx_offboard" -> "who 非空 + items[{n,done}] ≤25 + **revoke=true（未回收会被拦）** + **clawH（缺会被拦）**"
        "zx_revoke" -> "who 非空 + items[{n,**t∈系统/令牌/密钥/物理（缺会被拦）**,off,lagH?}] ≤25；**还有开着却 done=true 会被拦**"
        "zx_warrtrack" -> "item 非空 + **endH（缺会被拦）** + issues[{n,h,**told**}] ≤20；**有未报却 done=true 会被拦**"
        else -> "未注册组件：$component"
    }
}
