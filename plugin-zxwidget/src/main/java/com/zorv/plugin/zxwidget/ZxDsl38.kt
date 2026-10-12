package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs

/**
 * 第三十八批（出事了之后怎么办）的校验 + 示例 + 规格。
 *
 * 这批查的是**「证据窗口过期了却没提示」**。
 *
 * ── 它和第三十七批的差别 ────────────────────────────────────
 *
 * 第三十七批做的是**事情会正常推进**的那部分：装修按阶段走、
 * 就医排队等、报修派单上门、签证递交审核。它的核心是
 * "现在卡在谁手上、能不能催"——**它假设事情最终会往前走**。
 *
 * 这批是另一半：**事情不会正常推进**。
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **出事之后最关键的是"证据留没留下"，而证据有时间窗口。**
 *
 * 第三十七批的"等待态"是被动的——等就完了。
 * 这批不一样：**等着等着，能补救的手段就消失了。**
 *
 *   快递签收 7 天内要拍照，过了就认不了损
 *   搬家当场登记才认，事后说不清是谁磕的
 *   报销退回 30 天内要重交，超期作废
 *   证件被盗和丢失是两套流程，走错了补办会被拒
 *
 * **窗口过期不是"晚了"，是"没了"。**
 * 而大多数卡片只写"请尽快处理"——用户不知道尽快是多久，
 * 也不知道过了会怎样。
 *
 * 所以校验强制每个组件必须有"证据三件套"：
 *   1. **证据清单**（要留什么）
 *   2. **时限**（还剩多久，不是"尽快"）
 *   3. **过期后果**（没了就明说没了，别让人白跑）
 *
 * ── 要拦的（与 ZxRender38 注释一一对应） ────────────────────
 *
 *   escort     缺就诊记录 / 医嘱为空 / 缺复诊时间
 *   lease      缺关键条款 / 金额对不上 / 缺到期与解约条件
 *   overrun    分项之和 ≠ 总额 / 超支项未标原因 / 无止损建议
 *   vet        缺体重 / 疫苗已过期未标 / 缺下次时间
 *   photospec  尺寸与像素对不上 / 缺底色 / 缺用途
 *   claim      **缺证据清单** / **证据已过期未标后果** / 缺时限
 *   ivreview   缺问题 / **答得差却没写怎么改** / 无评分
 *   claimprog **缺材料截止** / 材料状态非法 / 无材料
 *   refile     **退回缺少原因** / **缺重报截止** / **已超期未标后果**
 *   damage     **缺照片标记** / 损坏未定级 / 缺责任方
 *   slotconflict 冲突时段未标 / **缺一方依据** / 无可选方案
 *   lostdoc    **缺挂失或补办步骤** / 缺受理点 / 顺序错
 */
object ZxDsl38 {

    val ALL = listOf(
        "zx_escort", "zx_lease", "zx_overrun", "zx_vet",
        "zx_photospec", "zx_claim", "zx_ivreview", "zx_claimprog",
        "zx_refile", "zx_damage", "zx_slotconflict", "zx_lostdoc"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_escort" -> escort(root)
        "zx_lease" -> lease(root)
        "zx_overrun" -> overrun(root)
        "zx_vet" -> vet(root)
        "zx_photospec" -> photospec(root)
        "zx_claim" -> claim(root)
        "zx_ivreview" -> ivreview(root)
        "zx_claimprog" -> claimprog(root)
        "zx_refile" -> refile(root)
        "zx_damage" -> damage(root)
        "zx_slotconflict" -> slotconflict(root)
        "zx_lostdoc" -> lostdoc(root)
        else -> false to "未注册组件：$component"
    }

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun escort(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("recs")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_escort 缺少 recs（就诊记录）——" +
                "**没有记录，复诊时说不清做过什么检查**"
        if (n > 20) return false to "zx_escort 最多 20 条记录，实际 $n 条"
        for (k in 0 until n) {
            val r = a.optJSONObject(k)
                ?: return false to "recs[$k] 不是对象，应为 {\"n\":环节,\"t\":时间,\"dr\":医生,\"m\":记录}"
            if (r.optString("n").isBlank()) return false to "recs[$k] 缺少 n（环节）"
            if (r.optString("m").isBlank())
                return false to "recs[$k]「${r.optString("n")}」**缺 m（记录内容）**——" +
                    "**只有环节名没有内容，等于没记**"
        }
        val adv = o.optJSONArray("advice")
        if (adv == null || adv.length() == 0)
            return false to "zx_escort 缺少 advice（医嘱）——" +
                "**陪诊的全部价值在医嘱**，没有医嘱这张卡只是流水账"
        if (adv.length() > 15) return false to "zx_escort 最多 15 条医嘱，实际 ${adv.length()} 条"
        for (k in 0 until adv.length()) {
            val m = adv.optJSONObject(k)?.optString("m", "")
            if (m.isNullOrBlank()) return false to "advice[$k] 缺少 m（医嘱内容）"
        }
        if (!o.has("nextDays"))
            return false to "zx_escort 缺少 nextDays（复诊倒计时）——" +
                "**不给复诊时间，用户会忘了去**"
        return true to ""
    }

    private fun lease(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("terms")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_lease 缺少 terms——**没有条款**"
        if (n > 25) return false to "zx_lease 最多 25 条，实际 $n 条"
        var key = 0
        var hasTerm = false      // 到期条件
        var hasBreak = false     // 提前解约
        for (k in 0 until n) {
            val t = a.optJSONObject(k)
                ?: return false to "terms[$k] 不是对象，应为 {\"n\":条款,\"v\":内容,\"k\":是否关键}"
            val nm = t.optString("n", "")
            if (nm.isBlank()) return false to "terms[$k] 缺少 n（条款名）"
            if (t.optString("v").isBlank())
                return false to "terms[$k]「$nm」**缺 v（条款内容）**——" +
                    "**只有条款名没有内容，出事时无法引用**"
            if (t.optBoolean("k", false)) key++
            if (nm.contains("到期") || nm.contains("期限")) hasTerm = true
            if (nm.contains("解约") || nm.contains("退租") || nm.contains("违约")) hasBreak = true
        }
        // 缺关键条款
        if (key == 0)
            return false to "zx_lease **没有任何关键条款（k=true）**——" +
                "**十几条平铺列出，用户不知道违约时哪条会咬人**。"
                "至少把「违约条件」「押金退还」标成关键"
        // 缺到期与解约条件
        if (!hasTerm || !hasBreak)
            return false to "zx_lease **缺少${if (!hasTerm) "「到期/期限」" else ""}" +
                "${if (!hasTerm && !hasBreak) "和" else ""}${if (!hasBreak) "「解约/退租/违约」" else ""}条款**——" +
                "**租房最容易起冲突的就是这两条**，缺一条签了就会吃亏"
        val rent = o.optDouble("rent", Double.NaN)
        val dep = o.optDouble("dep", Double.NaN)
        if (rent.isNaN() || dep.isNaN())
            return false to "zx_lease 缺 rent（月租）或 dep（押金）——" +
                "**金额对不上就没法核对**"
        if (rent <= 0) return false to "zx_lease 的 rent 必须为正"
        if (dep < 0) return false to "zx_lease 的 dep 不能为负"
        if (o.optInt("months", 0) <= 0)
            return false to "zx_lease 缺 months（租期月数）"
        return true to ""
    }

    private fun overrun(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_overrun 缺少 items——**没有分项，不知道钱花在哪**"
        if (n > 25) return false to "zx_overrun 最多 25 项，实际 $n 项"
        var plan = 0.0; var act = 0.0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":项目,\"plan\":预算,\"act\":实际,\"why\":超支原因}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（项目名）"
            val p = it.optDouble("plan", Double.NaN)
            val c = it.optDouble("act", Double.NaN)
            if (p.isNaN() || c.isNaN())
                return false to "items[$k] 缺 plan 或 act——**两个数缺一个就没法比**"
            if (p < 0 || c < 0) return false to "items[$k] 的金额不能为负"
            plan += p; act += c
            // 超支项未标原因
            if (c > p && it.optString("why").isBlank())
                return false to "items[$k]「${it.optString("n")}」**超支 ${"%.0f".format(c - p)} 但没写 why**——" +
                    "**不知道钱花在哪，就没法止损**"
        }
        val tp = o.optDouble("planTotal", Double.NaN)
        val ta = o.optDouble("actTotal", Double.NaN)
        // 分项之和 ≠ 总额
        if (!tp.isNaN() && abs(plan - tp) >= 0.01)
            return false to "zx_overrun 的**分项预算合计 ${"%.0f".format(plan)} " +
                "与 planTotal ${"%.0f".format(tp)} 对不上**"
        if (!ta.isNaN() && abs(act - ta) >= 0.01)
            return false to "zx_overrun 的**分项实际合计 ${"%.0f".format(act)} " +
                "与 actTotal ${"%.0f".format(ta)} 对不上**"
        if (act <= plan)
            return false to "zx_overrun **实际 ${"%.0f".format(act)} 没超预算 ${"%.0f".format(plan)}**——" +
                "**没超支就别用这张卡**，用 zx_budget 更合适"
        if (o.optString("cut", "").isBlank())
            return false to "zx_overrun 缺 cut（止损建议）——" +
                "**只报超了多少不报该砍哪一项，用户还是不知道怎么办**"
        return true to ""
    }

    private fun vet(o: JSONObject): Pair<Boolean, String> {
        // 缺体重
        val w = o.optDouble("weight", Double.NaN)
        if (w.isNaN() || w <= 0)
            return false to "zx_vet **缺 weight（体重）或体重非正**——" +
                "**用药剂量按体重算**，缺了它其他记录都用不了"
        if (w > 200) return false to "zx_vet 的 weight=$w kg 超过 200——多半单位错了"
        val a = o.optJSONArray("recs")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_vet 缺少 recs——**没有记录**"
        if (n > 25) return false to "zx_vet 最多 25 条，实际 $n 条"
        for (k in 0 until n) {
            val r = a.optJSONObject(k)
                ?: return false to "recs[$k] 不是对象，应为 {\"n\":项目,\"t\":时间,\"dose\":剂量,\"next\":下次天数}"
            if (r.optString("n").isBlank()) return false to "recs[$k] 缺少 n（项目名）"
            // 缺下次时间
            if (!r.has("next"))
                return false to "recs[$k]「${r.optString("n")}」**缺 next（下次天数）**——" +
                    "**疫苗/驱虫是有周期的**，不给下次时间用户会漏掉"
            val nx = r.optInt("next", -9999)
            if (nx > 3650) return false to "recs[$k] 的 next=$nx 天超过十年"
            if (r.optString("dose").isBlank() && r.optString("n").contains(Regex("药|驱虫|疫苗")))
                return false to "recs[$k]「${r.optString("n")}」**缺 dose（剂量）**——" +
                    "**用药记录没剂量，换医院时要重算**"
        }
        return true to ""
    }

    private fun photospec(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("specs")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_photospec 缺少 specs——**没有规格**"
        if (n > 10) return false to "zx_photospec 最多 10 种，实际 $n 种"
        val seen = HashSet<String>()
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "specs[$k] 不是对象，应为 {\"n\":用途,\"w\":宽mm,\"h\":高mm,\"px\":像素,\"bg\":底色,\"req\":要求}"
            val nm = s.optString("n", "")
            if (nm.isBlank()) return false to "specs[$k] 缺少 n（用途）"
            if (!seen.add(nm)) return false to "specs[$k] 的用途「$nm」重复"
            val w = s.optDouble("w", Double.NaN)
            val h = s.optDouble("h", Double.NaN)
            if (w.isNaN() || h.isNaN())
                return false to "specs[$k]「$nm」缺 w 或 h——**尺寸没有就画不出比例**"
            if (w <= 0 || h <= 0) return false to "specs[$k] 的尺寸必须为正"
            if (w > 200 || h > 200) return false to "specs[$k] 的尺寸超过 200mm——多半单位是像素不是毫米"
            // 尺寸与像素对不上
            val px = s.optString("px", "")
            if (px.isNotBlank() && px.contains("x")) {
                val p2 = px.split("x")
                if (p2.size == 2) {
                    val pw = p2[0].trim().toDoubleOrNull()
                    val ph = p2[1].trim().toDoubleOrNull()
                    if (pw != null && ph != null && pw > 0 && ph > 0) {
                        val rw = h / w
                        val rp = ph / pw
                        if (abs(rw - rp) / rw > 0.15)
                            return false to "specs[$k]「$nm」**尺寸 ${"%.0f".format(w)}×${"%.0f".format(h)}mm " +
                                "与像素 $px 的比例对不上**（${"%.2f".format(rw)} vs ${"%.2f".format(rp)}）——" +
                                "**按这个尺寸洗出来的照片会被拉伸**"
                    }
                }
            }
            // 缺底色
            if (s.optString("bg").isBlank())
                return false to "specs[$k]「$nm」**缺 bg（底色）**——" +
                    "**底色错了直接不合格**（白/蓝/红不能混用）"
            if (s.optString("bg") !in listOf("白", "蓝", "红", "渐变"))
                return false to "specs[$k] 的 bg=${s.optString("bg")} 应为 白/蓝/红/渐变"
        }
        return true to ""
    }

    private fun claim(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("ev")
        val n = a?.length() ?: 0
        // 缺证据清单
        if (a == null || n == 0)
            return false to "zx_claim **缺 ev（证据清单）**——" +
                "**索赔的全部价值在证据**，没有清单用户不知道要留什么"
        if (n > 15) return false to "zx_claim 最多 15 项，实际 $n 项"
        var must = 0
        for (k in 0 until n) {
            val e = a.optJSONObject(k)
                ?: return false to "ev[$k] 不是对象，应为 {\"n\":证据,\"got\":有无,\"must\":必需,\"leftH\":剩余小时,\"miss\":过期后果}"
            val nm = e.optString("n", "")
            if (nm.isBlank()) return false to "ev[$k] 缺少 n（证据名）"
            // 缺时限
            if (!e.has("leftH"))
                return false to "ev[$k]「$nm」**缺 leftH（剩余小时）**——" +
                    "**只写「请尽快」，用户不知道尽快是多久**"
            val lh = e.optInt("leftH", 9999)
            // 证据已过期未标后果
            if (lh <= 0 && e.optString("miss").isBlank())
                return false to "ev[$k]「$nm」**已过期但没写 miss（过期后果）**——" +
                    "**窗口过期不是「晚了」，是「没了」**。" +
                    "不写清楚后果，用户还会去跑一趟"
            if (e.optBoolean("must", false)) must++
        }
        if (must == 0)
            return false to "zx_claim **没有任何必需证据（must=true）**——" +
                "**全是可选项的话，这张清单拦不住任何一次拒赔**"
        return true to ""
    }

    private fun ivreview(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("qs")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_ivreview 缺少 qs（问题）——**没有问题**"
        if (n > 20) return false to "zx_ivreview 最多 20 题，实际 $n 题"
        for (k in 0 until n) {
            val q = a.optJSONObject(k)
                ?: return false to "qs[$k] 不是对象，应为 {\"n\":问题,\"s\":0差/1一般/2好,\"why\":为什么,\"fix\":怎么改}"
            if (q.optString("n").isBlank()) return false to "qs[$k] 缺少 n（问题）"
            val s = q.optInt("s", -1)
            if (s !in 0..2)
                return false to "qs[$k].s=$s 超出 0-2（0差/1一般/2好）——" +
                    "**没有评分就不知道哪题要改**"
            // 答得差却没写怎么改
            if (s <= 1 && q.optString("fix").isBlank())
                return false to "qs[$k]「${q.optString("n")}」**评分 ${if (s == 0) "差" else "一般"} 但没写 fix**——" +
                    "**只知道答得差，不知道下次怎么答，等于没复盘**"
            if (q.optString("why").isBlank())
                return false to "qs[$k] 缺 why（为什么这么评分）——" +
                    "**不给原因，过两周自己也想不起来当时为什么卡住**"
        }
        return true to ""
    }

    private fun claimprog(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("docs")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_claimprog 缺少 docs——**没有材料**"
        if (n > 20) return false to "zx_claimprog 最多 20 项，实际 $n 项"
        for (k in 0 until n) {
            val d = a.optJSONObject(k)
                ?: return false to "docs[$k] 不是对象，应为 {\"n\":材料,\"s\":0缺/1已交/2已受理,\"leftH\":剩余小时}"
            if (d.optString("n").isBlank()) return false to "docs[$k] 缺少 n（材料名）"
            val s = d.optInt("s", -1)
            if (s !in 0..2)
                return false to "docs[$k].s=$s 超出 0-2（0缺/1已交/2已受理）——" +
                    "**状态认不出来，就不知道还差什么**"
            // 缺材料截止
            if (!d.has("leftH"))
                return false to "docs[$k]「${d.optString("n")}」**缺 leftH（材料截止剩余小时）**——" +
                    "**理赔材料各有各的截止**，只写「请尽快提交」，" +
                    "用户不知道哪样先过期"
            val lh = d.optInt("leftH", 9999)
            if (lh <= 0 && s < 2 && d.optString("miss").isBlank())
                return false to "docs[$k]「${d.optString("n")}」**已过截止且未受理，但没写 miss**——" +
                    "**过了截止理赔会被直接拒**，不说明白用户还在补"
        }
        return true to ""
    }

    private fun refile(o: JSONObject): Pair<Boolean, String> {
        // 退回缺少原因
        if (o.optString("why").isBlank())
            return false to "zx_refile **缺 why（退回原因）**——" +
                "**不知道为什么被退，重报还是会被退**"
        // 缺重报截止
        if (!o.has("leftH"))
            return false to "zx_refile **缺 leftH（重报剩余小时）**——" +
                "**重报有窗口**，不给截止用户不知道还剩多久"
        val lh = o.optInt("leftH", 0)
        // 已超期未标后果
        if (lh <= 0 && o.optString("miss").isBlank())
            return false to "zx_refile **已超期但没写 miss（后果）**——" +
                "**超期就报不了了，这笔钱要自己承担**。" +
                "不直说，用户会以为还能报，白准备材料"
        val a = o.optJSONArray("need")
        if (a == null || a.length() == 0)
            return false to "zx_refile **缺 need（要补的东西）**——" +
                "**被退回了但不知道要补什么，等于不知道为什么被退**"
        if (a.length() > 15) return false to "zx_refile 最多 15 项，实际 ${a.length()} 项"
        for (k in 0 until a.length()) {
            val nd = a.optJSONObject(k)
                ?: return false to "need[$k] 不是对象，应为 {\"n\":要补的东西,\"got\":是否已补}"
            if (nd.optString("n").isBlank()) return false to "need[$k] 缺少 n"
        }
        return true to ""
    }

    private fun damage(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_damage 缺少 items——**没有损坏物品**"
        if (n > 30) return false to "zx_damage 最多 30 件，实际 $n 件"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":物品,\"lv\":1轻/2中/3重,\"who\":责任方,\"photo\":是否有照片}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（物品名）"
            // 损坏未定级
            val lv = it.optInt("lv", -1)
            if (lv !in 1..3)
                return false to "items[$k]「${it.optString("n")}」**缺 lv 或 lv 超出 1-3**——" +
                    "**不分级就没法定赔多少**"
            // 缺责任方
            if (it.optString("who").isBlank())
                return false to "items[$k]「${it.optString("n")}」**缺 who（责任方）**——" +
                    "**不知道是谁磕的，就没法找谁赔**"
            // 缺照片标记
            if (!it.has("photo"))
                return false to "items[$k]「${it.optString("n")}」**缺 photo（是否有照片）**——" +
                    "**损坏登记的全部价值在当场照片**。" +
                    "不标出来，理赔时才发现没证据"
            if (!it.optBoolean("photo", false) && it.optString("miss").isBlank())
                return false to "items[$k]「${it.optString("n")}」**没照片但没写 miss**——" +
                    "**没照片基本赔不到**，要说清楚"
        }
        return true to ""
    }

    private fun slotconflict(o: JSONObject): Pair<Boolean, String> {
        // 冲突时段未标
        if (o.optString("slot").isBlank())
            return false to "zx_slotconflict **缺 slot（冲突时段）**——" +
                "**不说清是哪个时段冲突了，双方无从谈**"
        val a = o.optJSONArray("sides")
        val n = a?.length() ?: 0
        if (a == null || n < 2)
            return false to "zx_slotconflict 需要至少 2 方，实际 $n 方——" +
                "**一方的冲突不叫冲突**"
        if (n > 5) return false to "zx_slotconflict 最多 5 方，实际 $n 方"
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "sides[$k] 不是对象，应为 {\"n\":一方,\"why\":依据,\"w\":权重1-5}"
            if (s.optString("n").isBlank()) return false to "sides[$k] 缺少 n（一方名称）"
            // 缺一方依据
            if (s.optString("why").isBlank())
                return false to "sides[$k]「${s.optString("n")}」**缺 why（依据）**——" +
                    "**没有依据就变成谁嗓门大谁赢**"
            val w = s.optInt("w", -1)
            if (w !in 1..5)
                return false to "sides[$k].w=$w 超出 1-5（权重）——" +
                    "**不给权重，两边依据看起来一样重，还是没法判**"
        }
        val op = o.optJSONArray("opts")
        // 无可选方案
        if (op == null || op.length() == 0)
            return false to "zx_slotconflict **缺 opts（可选方案）**——" +
                "**两边都有依据时，要给方案而不是让它们自己吵**"
        if (op.length() > 6) return false to "zx_slotconflict 最多 6 个方案，实际 ${op.length()} 个"
        for (k in 0 until op.length()) {
            val p = op.optJSONObject(k)
                ?: return false to "opts[$k] 不是对象，应为 {\"n\":方案,\"cost\":代价}"
            if (p.optString("n").isBlank()) return false to "opts[$k] 缺少 n（方案）"
            if (p.optString("cost").isBlank())
                return false to "opts[$k]「${p.optString("n")}」**缺 cost（代价）**——" +
                    "**方案不带代价，用户没法比较**"
        }
        return true to ""
    }

    private fun lostdoc(o: JSONObject): Pair<Boolean, String> {
        val lost = o.optInt("lost", 0)
        if (lost !in 0..1)
            return false to "zx_lostdoc 的 lost=$lost 应为 0遗失 / 1被盗——" +
                "**这两套流程不一样**，认不出就没法给正确步骤"
        val a = o.optJSONArray("steps")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_lostdoc 缺少 steps——**没有步骤**"
        if (n > 12) return false to "zx_lostdoc 最多 12 步，实际 $n 步"
        var hasReport = false; var hasReissue = false
        var rp = -1; var ri = -1
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "steps[$k] 不是对象，应为 {\"n\":步骤,\"s\":0未做/1已做,\"must\":必需,\"place\":受理点,\"fee\":费用}"
            val nm = s.optString("n", "")
            if (nm.isBlank()) return false to "steps[$k] 缺少 n（步骤名）"
            if (s.optInt("s", 0) !in 0..1)
                return false to "steps[$k].s 超出 0-1（0未做/1已做）"
            // 缺受理点
            if (s.optString("place").isBlank())
                return false to "steps[$k]「$nm」**缺 place（受理点）**——" +
                    "**不知道去哪办，用户还要再问一次**"
            if (nm.contains("挂失")) { hasReport = true; rp = k }
            if (nm.contains("补办")) { hasReissue = true; ri = k }
        }
        // 缺挂失或补办步骤
        if (!hasReport || !hasReissue)
            return false to "zx_lostdoc **缺少${if (!hasReport) "「挂失」" else ""}" +
                "${if (!hasReport && !hasReissue) "和" else ""}${if (!hasReissue) "「补办」" else ""}步骤**——" +
                "**挂失和补办是两件事**，缺一件证件就补不回来"
        // 顺序错
        if (rp >= 0 && ri >= 0) {
            val a2 = o.optJSONArray("steps")
            val doneR = a2.optJSONObject(rp).optInt("s", 0) == 1
            val doneI = a2.optJSONObject(ri).optInt("s", 0) == 1
            if (doneI && !doneR)
                return false to "zx_lostdoc **先补办后挂失了**——" +
                    "**顺序错了，补办会被拒，得重来**"
        }
        // 被盗要有报案
        if (lost == 1 && !o.optJSONArray("steps").let { arr ->
                (0 until arr.length()).any { arr.optJSONObject(it).optString("n").contains("报案") } })
            return false to "zx_lostdoc 标了**被盗但步骤里没有报案**——" +
                "**被盗要先报案，凭回执才能挂失**，流程不一样"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_escort" -> """
            {"type":"zx_escort","n":"陪诊","nextDays":14,"recs":[
               {"n":"血常规","t":"0910","dr":"李医生","m":"白细胞偏高，建议复查"}",
               {"n":"B 超","t":"0940","dr":"王医生","m":"未见异常"}"],
             "advice":[{"m":"三天后复查血常规"}",{"m":"忌辛辣，多喝水"}"]}""".trimIndent()
        "zx_lease" -> """
            {"type":"zx_lease","n":"租房合同","rent":3400,"dep":6800,"months":12,"terms":[
               {"n":"租期与到期","v":"2026-03-01 到期，续租需提前 30 天","k":true},
               {"n":"押金退还","v":"退租后 15 个工作日内，扣清洁费 200","k":true},
               {"n":"提前解约","v":"需赔 1 个月租金","k":true},
               {"n":"宠物","v":"允许养猫，不允许养狗","k":false}]}""".trimIndent()
        "zx_overrun" -> """
            {"type":"zx_overrun","n":"装修预算","planTotal":120000,"actTotal":148600,
             "cut":"砍掉背景墙和吊顶，可省 21000","items":[
               {"n":"水电","plan":18000,"act":19500,"why":"旧管全换，增项"}",
               {"n":"泥木","plan":32000,"act":33800,"why":"客厅加做吊顶"}",
               {"n":"油漆","plan":15000,"act":14200},
               {"n":"主材","plan":55000,"act":81100,"why":"瓷砖升级，超 26100"}]}""".trimIndent()
        "zx_vet" -> """
            {"type":"zx_vet","n":"布偶","weight":4.8,"recs":[
               {"n":"猫三联","t":"0310","dose":"1 针","next":180},
               {"n":"体内驱虫","t":"0405","dose":"0.5 片","next":30},
               {"n":"体外驱虫","t":"0501","dose":"1 支","next":-8}]}""".trimIndent()
        "zx_photospec" -> """
            {"type":"zx_photospec","n":"证件照","specs":[
               {"n":"护照","w":33,"h":48,"px":"295x413","bg":"白","req":"6 个月内近照，不戴首饰"}",
               {"n":"签证","w":35,"h":45,"px":"413x531","bg":"白","req":"露耳，不能笑"}",
               {"n":"社保","w":26,"h":32,"px":"358x441","bg":"白","req":"深色上衣"}"]}""".trimIndent()
        "zx_claim" -> """
            {"type":"zx_claim","n":"快递索赔","ev":[
               {"n":"开箱视频","got":true,"must":true,"leftH":72,"miss":"过了 7 天认不了损"}",
               {"n":"外包装照片","got":true,"must":true,"leftH":72},
               {"n":"物品破损照","got":false,"must":true,"leftH":72},
               {"n":"价值凭证","got":true,"must":false,"leftH":168},
               {"n":"签收单","got":false,"must":false,"leftH":-2,"miss":"已过 7 天，补不到了"}"]}""".trimIndent()
        "zx_ivreview" -> """
            {"type":"zx_ivreview","n":"复盘","qs":[
               {"n":"介绍一个复杂项目","s":0,"why":"讲成了流水账，没说清我的角色",
                "fix":"用 STAR：背景-任务-动作-结果，先说结果"}",
               {"n":"为什么离职","s":2,"why":"说了成长诉求，没抱怨前公司"}",
               {"n":"反问环节","s":1,"why":"只问了薪资，没问业务",
                "fix":"准备 3 个业务问题，别先问钱"}"]}""".trimIndent()
        "zx_claimprog" -> """
            {"type":"zx_claimprog","n":"理赔","docs":[
               {"n":"出院小结","s":2,"leftH":120},
               {"n":"发票原件","s":1,"leftH":120},
               {"n":"费用明细","s":0,"leftH":48},
               {"n":"身份证复印件","s":0,"leftH":-1,"miss":"已过 30 天截止，需特殊申请"}"]}""".trimIndent()
        "zx_refile" -> """
            {"type":"zx_refile","n":"重报","why":"餐费超标准（限 80，实报 120）",
             "leftH":504,"miss":"超期作废，需自担","need":[
               {"n":"拆分餐费，只报 80","got":true},
               {"n":"补部门负责人签字","got":false},
               {"n":"重填报销单","got":false}]}""".trimIndent()
        "zx_damage" -> """
            {"type":"zx_damage","n":"损坏登记","items":[
               {"n":"实木餐桌","lv":3,"who":"搬家公司","photo":true},
               {"n":"电视柜","lv":2,"who":"搬家公司","photo":true},
               {"n":"花瓶","lv":1,"who":"自己","photo":false,"miss":"没拍照，可能赔不到"}"]}""".trimIndent()
        "zx_slotconflict" -> """
            {"type":"zx_slotconflict","n":"冲突协商","slot":"05-13 10-11","sides":[
               {"n":"产品部","why":"已发会议通知给 12 人","w":4},
               {"n":"设计部","why":"客户评审，外部人员已确认","w":5}],
             "opts":[{"n":"产品部改到 14-15","cost":"产品部 2 人冲突"}",
               {"n":"设计部改到 15-16","cost":"客户要重新约"}",
               {"n":"换大会议室","cost":"需申请，当天可能没有"}"]}""".trimIndent()
        "zx_lostdoc" -> """
            {"type":"zx_lostdoc","n":"身份证挂失","lost":0,"steps":[
               {"n":"挂失","s":1,"must":true,"place":"派出所 / 线上","fee":"免费"}",
               {"n":"补办","s":0,"must":true,"place":"户籍所在地派出所","fee":"40 元"}",
               {"n":"领证","s":0,"must":false,"place":"原受理点","fee":"—"}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_escort" -> "recs[{n,**m 非空（缺会被拦）**,t?,dr?}] ≤20 + advice[{**m 非空**}] ≤15 + **nextDays（缺会被拦）**"
        "zx_lease" -> "terms[{n,**v 非空（缺会被拦）**,k?}] ≤25 + rent>0 + dep≥0 + months>0；**无关键条款会被拦**；**缺到期或解约条款会被拦**"
        "zx_overrun" -> "items[{n,plan,act,**超支时 why 非空（缺会被拦）**}] ≤25 + **cut 非空（缺会被拦）**；**实际必须大于预算**；**分项合计与总额对不上会被拦**"
        "zx_vet" -> "**weight>0（缺会被拦）** + recs[{n,**next（缺会被拦）**,dose?,t?}] ≤25"
        "zx_photospec" -> "specs[{n,w,h,px?,**bg∈白/蓝/红/渐变（缺会被拦）**,req?}] ≤10；**尺寸与像素比例差 >15% 会被拦**"
        "zx_claim" -> "ev[{n,**leftH（缺会被拦）**,got?,must?,**过期时 miss 非空（缺会被拦）**}] ≤15；**无必需证据会被拦**"
        "zx_ivreview" -> "qs[{n,**s∈0-2**,**why 非空**,**s≤1 时 fix 非空（缺会被拦）**}] ≤20"
        "zx_claimprog" -> "docs[{n,**s∈0-2**,**leftH（缺会被拦）**,miss?}] ≤20；**过截止未受理缺 miss 会被拦**"
        "zx_refile" -> "**why 非空（缺会被拦）** + **leftH（缺会被拦）** + **超期时 miss 非空（缺会被拦）** + need[{n,got?}] ≤15"
        "zx_damage" -> "items[{n,**lv∈1-3（缺会被拦）**,**who 非空（缺会被拦）**,**photo（缺会被拦）**,miss?}] ≤30"
        "zx_slotconflict" -> "**slot 非空（缺会被拦）** + sides[{n,**why 非空（缺会被拦）**,**w∈1-5**}] ≥2 ≤5 + opts[{n,**cost 非空**}] ≤6"
        "zx_lostdoc" -> "lost∈0-1 + steps[{n,**place 非空（缺会被拦）**,s?,must?,fee?}] ≤12；**缺挂失或补办会被拦**；**顺序错会被拦**；**被盗缺报案会被拦**"
        else -> "未注册组件：$component"
    }
}
