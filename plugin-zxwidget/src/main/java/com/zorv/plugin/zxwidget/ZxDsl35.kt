package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三十五批（生活场景）的校验 + 示例 + 规格。
 *
 * 这批查的是**「只有当前值、没有对比基准」**。
 *
 * 前十二档一直在查"读不读得出信息"：
 *   画不出来 → 统计前提不成立 → 区间前提不成立 →
 *   因果前提无从判断 → 看起来确定其实不确定 →
 *   推不出该做什么 → 记了下次用不上 → 落不到谁该做什么 →
 *   推演本身不可信 → 决策前提在过程中会变 →
 *   信息新不新能不能信 → 操作了但得不到结果
 *
 * 而这批面对的是一类**长期跟踪**的场景：
 *
 *   健身   不是今天练了多少，是这个月有没有进步
 *   用药   不是吃了什么药，是**有没有漏服**
 *   快递   不是有个单号，是到哪了、还要多久
 *   订阅   不是有几个订阅，是**下个月要扣多少、哪个忘了取消**
 *   水电表 不是读数是多少，是**比上个月多了还是少了**
 *   睡眠   不是昨晚睡了几小时，是这一周趋势往哪走
 *
 * 贯穿这批一条：**生活场景要看"变化"，不看"当前"**。
 *
 * 一次读数、一天记录、一个状态**都没有信息**。
 * 有意义的是：它跟上次比怎么样、跟目标比怎么样、趋势往哪走。
 *
 *   健身记录只有一次     → 看不出有没有进步
 *   水电表只有本次读数   → 看不出多了还是少了
 *   睡眠只有昨晚         → 看不出是偶尔还是常态
 *   快递只有当前状态     → 看不出还要多久
 *
 * 所以这批每个组件都强制带**对比基准**——上次数、目标值、历史序列。
 * 缺了它校验就拦，因为画出来的卡"好看但回答不了用户真正想问的"。
 * 这类问题不崩、不报错，只是让用户拿着一个回答不了问题的界面，
 * 而模型会以为下发成功了。
 *
 * 具体要拦的（与 ZxRender35 注释一一对应）：
 *   fitness      只有 1 条记录 / 无目标 / 值非正
 *   medicine     **漏服未标** / 下次服药早于上次 / 无用药项
 *   parcel       节点时间倒序 / 无节点 / 无预计时长
 *   ticket       **默认选中已售座位** / 无座位 / max ≤ 0
 *   study        无卡片 / 掌握度超出 0-5
 *   plant        浇水间隔 ≤ 0 / 无植物 / 上次浇水位负
 *   parking      **车位号或楼层缺失** / 已停时长为负
 *   habit        无记录 / 目标 ≤ 0 / 值非 0/1
 *   subscription **下次扣款已过** / 金额为负 / 无订阅
 *   pet          无照护项 / 间隔 ≤ 0 / 上次为负
 *   utilitymeter **本次 < 上次（表倒转）** / 少于 2 期
 *   sleep        时长超出 0-24h / 只有 1 晚 / 质量超出 0-100
 *   coupon       门槛为负 / 折扣率 > 1 / 无券
 *   appointment  **余号为负** / 无时段 / 无科室
 */
object ZxDsl35 {

    val ALL = listOf(
        "zx_fitness", "zx_medicine", "zx_parcel", "zx_ticket", "zx_study",
        "zx_plant", "zx_parking", "zx_habit", "zx_subscription", "zx_pet",
        "zx_utilitymeter", "zx_sleep", "zx_coupon", "zx_appointment"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_fitness" -> fitness(root)
        "zx_medicine" -> medicine(root)
        "zx_parcel" -> parcel(root)
        "zx_ticket" -> ticket(root)
        "zx_study" -> study(root)
        "zx_plant" -> plant(root)
        "zx_parking" -> parking(root)
        "zx_habit" -> habit(root)
        "zx_subscription" -> subscription(root)
        "zx_pet" -> pet(root)
        "zx_utilitymeter" -> utilitymeter(root)
        "zx_sleep" -> sleep(root)
        "zx_coupon" -> coupon(root)
        "zx_appointment" -> appointment(root)
        else -> false to "未注册组件：$component"
    }

    private fun fitness(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("recs")
        val n = a?.length() ?: 0
        // 只有 1 条记录
        if (a == null || n == 0)
            return false to "zx_fitness 缺少 recs——**没有记录**"
        if (n == 1)
            return false to "zx_fitness **只有 1 条记录**——**看不出趋势**。" +
                "健身的诉求是「我有没有进步」，不是「今天做了多少」。" +
                "一次读数没有变化可看，用户拿到它什么都判断不了"
        if (n > 60) return false to "zx_fitness 最多 60 条，实际 $n 条"
        for (k in 0 until n) {
            val r = a.optJSONObject(k)
                ?: return false to "recs[$k] 不是对象，应为 {\"d\":日期,\"v\":数值}"
            val v = r.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "recs[$k] 缺少 v（数值）"
            if (v <= 0) return false to "recs[$k].v=$v 必须为正——" +
                "**负的训练量没有意义，多半是单位或符号错了**"
            if (r.optString("d").isBlank()) return false to "recs[$k] 缺少 d（日期）"
        }
        // 无目标
        val tg = o.optDouble("target", Double.NaN)
        if (tg.isNaN())
            return false to "zx_fitness 缺少 target——**没有目标就没有「达成度」**。" +
                "55kg 是好是坏，取决于目标是 50 还是 60"
        if (tg <= 0) return false to "zx_fitness 的 target=$tg 必须为正"
        return true to ""
    }

    private fun medicine(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        // 无用药项
        if (a == null || n == 0)
            return false to "zx_medicine 缺少 items——**没有用药项**，空的用药记录"
        if (n > 10) return false to "zx_medicine 最多 10 种药，实际 $n 种"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":药名,\"dose\":剂量,\"plan\":[计划时刻],\"times\":[已服时刻]}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（药名）"
            val plan = it.optJSONArray("plan")
            if (plan == null || plan.length() == 0)
                return false to "items[$k]「${it.optString("n")}」**没有计划时刻**——" +
                    "**不知道该什么时候吃，就判不出漏没漏服**"
            val ps = (0 until plan.length()).map { plan.optString(it) }.filter { it.isNotBlank() }
            if (ps.isEmpty()) return false to "items[$k] 的 plan 全是空串"
            // 计划时刻递增
            for (j in 1 until ps.size) {
                if (hm(ps[j]) >= 0 && hm(ps[j - 1]) >= 0 && hm(ps[j]) <= hm(ps[j - 1]))
                    return false to "items[$k] 的计划时刻 ${ps[j - 1]} → ${ps[j]} **没有递增**——" +
                        "**下次服药早于上次，那不是用药计划是记录错乱**"
            }
            val ts = (it.optJSONArray("times") ?: JSONArray()).let { ta ->
                (0 until ta.length()).map { ta.optString(it) } }.filter { it.isNotBlank() }
            // 已服的时刻必须能在计划里找到，否则算不出漏服
            val stray = ts.firstOrNull { t -> t !in ps }
            if (stray != null)
                return false to "items[$k] 的已服时刻「$stray」**不在计划里**——" +
                    "**有服药记录却对不上计划，漏服数就判不准**"
        }
        return true to ""
    }

    private fun parcel(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("nodes")
        val n = a?.length() ?: 0
        // 无节点
        if (a == null || n == 0)
            return false to "zx_parcel 缺少 nodes——**看不到货在哪**"
        if (n > 15) return false to "zx_parcel 最多 15 个节点，实际 $n 个"
        var prevMin = -1
        for (k in 0 until n) {
            val nd = a.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"t\":状态,\"ts\":时间,\"p\":地点}"
            if (nd.optString("t").isBlank()) return false to "nodes[$k] 缺少 t（状态）"
            val ts = nd.optString("ts")
            if (ts.isNotBlank()) {
                val m = hm(ts)
                if (m >= 0) {
                    // 时间倒序
                    if (prevMin >= 0 && m < prevMin)
                        return false to "nodes[$k] 的时间 $ts **早于**上一个节点——" +
                            "**物流时间倒序，用户看到的是不可能发生的轨迹**"
                    prevMin = m
                }
            }
        }
        // 无预计时长
        val eta = o.optInt("eta", 0)
        if (eta <= 0)
            return false to "zx_parcel 缺少 eta（预计还需小时）——" +
                "**用户真正想问的是「还要等多久」**。只列节点不给剩余时间，" +
                "用户就得自己估，估错了就是白等"
        if (eta > 720) return false to "zx_parcel 的 eta=$eta 小时超过 30 天——多半是单位错了"
        return true to ""
    }

    private fun ticket(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("seats")
        val n = a?.length() ?: 0
        // 无座位
        if (a == null || n == 0)
            return false to "zx_ticket 缺少 seats——**没有座位**，空的选座区"
        if (n > 60) return false to "zx_ticket 最多 60 个座位，实际 $n 个"
        val names = HashSet<String>()
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "seats[$k] 不是对象，应为 {\"n\":座位号,\"s\":0可选/1已售/2锁定}"
            val nm = s.optString("n")
            if (nm.isBlank()) return false to "seats[$k] 缺少 n（座位号）"
            if (!names.add(nm)) return false to "seats[$k] 的座位号「$nm」重复——" +
                "**两个一样号的座位，选了不知道是哪个**"
            val st = s.optInt("s", 0)
            if (st !in 0..2) return false to "seats[$k].s=$st 超出 0-2（0可选1已售2锁定）"
        }
        val max = o.optInt("max", 0)
        if (max <= 0) return false to "zx_ticket 的 max=$max 必须为正——" +
            "**不知道最多选几个，用户选到一半才发现不让选**"
        if (max > 8) return false to "zx_ticket 的 max=$max 超过 8"
        // 默认选中已售座位
        val picked = o.optJSONArray("picked")
        if (picked != null) for (k in 0 until picked.length()) {
            val pn = picked.optString(k)
            val s = (0 until n).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("n") == pn }
            if (s == null)
                return false to "zx_ticket 默认选中「$pn」**不存在**"
            if (s.optInt("s", 0) != 0)
                return false to "zx_ticket 默认选中「$pn」但**它已售或锁定**——" +
                    "**已售座位能选，等于把冲突留到付款时才告诉用户**"
        }
        return true to ""
    }

    private fun study(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("cards")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_study 缺少 cards——**没有卡片**，空的复习组"
        if (n > 40) return false to "zx_study 最多 40 张，实际 $n 张"
        for (k in 0 until n) {
            val c = a.optJSONObject(k)
                ?: return false to "cards[$k] 不是对象，应为 {\"q\":正面,\"a\":背面,\"lv\":0-5}"
            if (c.optString("q").isBlank()) return false to "cards[$k] 缺少 q（正面）"
            if (c.optString("a").isBlank()) return false to "cards[$k] 缺少 a（背面）——" +
                "**翻过来是空的，那不叫卡片**"
            val lv = c.optInt("lv", 0)
            if (lv !in 0..5) return false to "cards[$k].lv=$lv 超出 0-5（掌握度）"
        }
        return true to ""
    }

    private fun plant(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("plants")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_plant 缺少 plants——**没有植物**，空的养护清单"
        if (n > 20) return false to "zx_plant 最多 20 株，实际 $n 株"
        for (k in 0 until n) {
            val p = a.optJSONObject(k)
                ?: return false to "plants[$k] 不是对象，应为 {\"n\":名,\"iv\":间隔天,\"last\":距上次天,\"sun\":光照}"
            if (p.optString("n").isBlank()) return false to "plants[$k] 缺少 n（名称）"
            val iv = p.optInt("iv", 0)
            // 浇水间隔 ≤ 0
            if (iv <= 0)
                return false to "plants[$k]「${p.optString("n")}」的 iv=$iv 必须为正——" +
                    "**间隔为 0 或负，倒计时除零，进度条永远是满的**"
            if (iv > 365) return false to "plants[$k] 的 iv=$iv 天超过一年——多半是单位错了（传成了小时）"
            val last = p.optInt("last", 0)
            if (last < 0) return false to "plants[$k] 的 last=$last 不能为负——" +
                "**「上次浇水」在未来，倒计时算出来是负的**"
        }
        return true to ""
    }

    private fun parking(o: JSONObject): Pair<Boolean, String> {
        val spot = o.optString("spot")
        val floor = o.optString("floor")
        // 车位号或楼层缺失
        if (spot.isBlank())
            return false to "zx_parking 缺少 spot（车位号）——**不知道停哪，这张卡没用**"
        if (floor.isBlank())
            return false to "zx_parking 缺少 floor（楼层）——**只知道车位号不知道在哪层，" +
                "用户还是得一层层找**"
        val mins = o.optInt("mins", 0)
        if (mins < 0) return false to "zx_parking 的 mins=$mins 不能为负——" +
            "**已停时长为负，费用也是负的**"
        if (mins > 60 * 24 * 30) return false to "zx_parking 的 mins=$mins 超过 30 天"
        val fee = o.optDouble("fee", Double.NaN)
        if (!fee.isNaN() && fee < 0)
            return false to "zx_parking 的 fee=$fee 不能为负"
        return true to ""
    }

    private fun habit(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("days")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_habit 缺少 days——**没有记录**，空的打卡表"
        if (n > 90) return false to "zx_habit 最多 90 天，实际 $n 天"
        for (k in 0 until n) {
            val d = a.optJSONObject(k)
                ?: return false to "days[$k] 不是对象，应为 {\"v\":0或1}"
            val v = d.optInt("v", -1)
            // 值非 0/1
            if (v !in 0..1)
                return false to "days[$k].v=$v 只能是 0 或 1——" +
                    "**打卡只有「打了」和「没打」，第三个值会让连续数算错**"
        }
        val goal = o.optInt("goal", 0)
        if (goal <= 0) return false to "zx_habit 的 goal=$goal 必须为正——" +
            "**没有目标就画不出完成度条**"
        if (goal > 365) return false to "zx_habit 的 goal=$goal 超过 365"
        return true to ""
    }

    private fun subscription(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("subs")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_subscription 缺少 subs——**没有订阅**，空的账单"
        if (n > 30) return false to "zx_subscription 最多 30 个，实际 $n 个"
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "subs[$k] 不是对象，应为 {\"n\":名,\"v\":金额,\"cyc\":\"月\"/\"年\",\"next\":天数,\"use\":闲置天数}"
            if (s.optString("n").isBlank()) return false to "subs[$k] 缺少 n（名称）"
            val v = s.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "subs[$k] 缺少 v（金额）"
            // 金额为负
            if (v < 0)
                return false to "subs[$k]「${s.optString("n")}」的 v=$v 不能为负——" +
                    "**负的订阅金额会让月度合计偏小**，用户以为自己花得少"
            val cyc = s.optString("cyc", "月")
            if (cyc != "月" && cyc != "年")
                return false to "subs[$k] 的 cyc=\"$cyc\" 只认「月」或「年」——" +
                    "**周期认不出来就折不成月付，比不动**"
            val next = s.optInt("next", 0)
            // 下次扣款已过
            if (next < 0)
                return false to "subs[$k]「${s.optString("n")}」的 next=$next 为负——" +
                    "**下次扣款日期已经过了却还显示「还有几天」，用户以为没扣**"
            if (next > 3650) return false to "subs[$k] 的 next=$next 天超过 10 年"
            if (s.optInt("use", 0) < 0) return false to "subs[$k] 的 use 不能为负"
        }
        return true to ""
    }

    private fun pet(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("pets")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_pet 缺少 pets——**没有照护项**，空的清单"
        if (n > 20) return false to "zx_pet 最多 20 项，实际 $n 项"
        for (k in 0 until n) {
            val p = a.optJSONObject(k)
                ?: return false to "pets[$k] 不是对象，应为 {\"n\":名,\"kind\":事项,\"iv\":间隔小时,\"last\":距上次小时}"
            if (p.optString("kind").isBlank()) return false to "pets[$k] 缺少 kind（事项）"
            val iv = p.optInt("iv", 0)
            if (iv <= 0) return false to "pets[$k]「${p.optString("kind")}」的 iv=$iv 必须为正——" +
                "**间隔为 0 或负，倒计时除零**"
            if (iv > 24 * 365) return false to "pets[$k] 的 iv=$iv 小时超过一年"
            if (p.optInt("last", 0) < 0) return false to "pets[$k] 的 last 不能为负"
        }
        return true to ""
    }

    private fun utilitymeter(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("reads")
        val n = a?.length() ?: 0
        // 少于 2 期
        if (a == null || n < 2)
            return false to "zx_utilitymeter 至少需要 2 期读数，实际 ${n} 期——" +
                "**算不出用量**。用户只知道表走了多少，不知道自己用了多少"
        if (n > 36) return false to "zx_utilitymeter 最多 36 期，实际 $n 期"
        var prev = Double.NaN
        for (k in 0 until n) {
            val r = a.optJSONObject(k)
                ?: return false to "reads[$k] 不是对象，应为 {\"d\":月份,\"v\":读数}"
            if (r.optString("d").isBlank()) return false to "reads[$k] 缺少 d（月份）"
            val v = r.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "reads[$k] 缺少 v（读数）"
            if (v < 0) return false to "reads[$k].v=$v 不能为负"
            // 表倒转
            if (!prev.isNaN() && v < prev)
                return false to "reads[$k]「${r.optString("d")}」的读数 $v **小于**上期 $prev——" +
                    "**表倒转了**。算出来的本期用量是负的，" +
                    "用户会以为自己用了负数，而不是数据错了"
            prev = v
        }
        return true to ""
    }

    private fun sleep(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("nights")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_sleep 缺少 nights——**没有记录**"
        // 只有 1 晚
        if (n == 1)
            return false to "zx_sleep **只有 1 晚**——**看不出是偶尔还是常态**。" +
                "单晚睡 5 小时不代表睡不好，要看这一周趋势。" +
                "只给一晚，用户会过度解读"
        if (n > 90) return false to "zx_sleep 最多 90 晚，实际 $n 晚"
        for (k in 0 until n) {
            val nt = a.optJSONObject(k)
                ?: return false to "nights[$k] 不是对象，应为 {\"d\":日期,\"v\":小时,\"q\":质量0-100}"
            if (nt.optString("d").isBlank()) return false to "nights[$k] 缺少 d（日期）"
            val v = nt.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "nights[$k] 缺少 v（小时）"
            // 时长超出 0-24h
            if (v <= 0 || v > 24)
                return false to "nights[$k].v=$v 超出 0-24 小时——" +
                    "**一天只有 24 小时**。超出后均值被拉偏，" +
                    "用户看到「平均睡了 30 小时」不会觉得是数据错了，会觉得自己看错了"
            val q = nt.optInt("q", 0)
            if (q < 0 || q > 100) return false to "nights[$k].q=$q 超出 0-100（质量分）"
        }
        val goal = o.optDouble("goal", Double.NaN)
        if (goal.isNaN()) return false to "zx_sleep 缺少 goal（目标小时）——" +
            "**没有目标线，这张趋势图看不出哪一晚是「没睡够」**"
        if (goal <= 0 || goal > 24) return false to "zx_sleep 的 goal=$goal 超出 0-24 小时"
        return true to ""
    }

    private fun coupon(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("coupons")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_coupon 缺少 coupons——**没有优惠券**，空的券包"
        if (n > 24) return false to "zx_coupon 最多 24 张，实际 $n 张"
        for (k in 0 until n) {
            val c = a.optJSONObject(k)
                ?: return false to "coupons[$k] 不是对象，应为 {\"n\":名,\"v\":面额或折扣率,\"t\":0满减/1折扣,\"min\":门槛,\"exp\":剩余天}"
            if (c.optString("n").isBlank()) return false to "coupons[$k] 缺少 n（名称）"
            val v = c.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "coupons[$k] 缺少 v"
            val t = c.optInt("t", 0)
            if (t !in 0..1) return false to "coupons[$k].t=$t 只认 0（满减）或 1（折扣）"
            if (t == 0) {
                if (v <= 0) return false to "coupons[$k].v=$v 面额必须为正"
                // 门槛为负
                if (c.optDouble("min", 0.0) < 0)
                    return false to "coupons[$k] 的 min=${c.optDouble("min", 0.0)} 不能为负——" +
                        "**负门槛意味着「不用买就能减」，多半是数据错了**"
            } else {
                // 折扣率 > 1
                if (v <= 0 || v > 1)
                    return false to "coupons[$k].v=$v 是折扣率，**必须在 0-1 之间**——" +
                        "传 8.5 想表示 8.5 折的话，画出来是 85 折（几乎不减）"
            }
        }
        return true to ""
    }

    private fun appointment(o: JSONObject): Pair<Boolean, String> {
        if (o.optString("dept").isBlank())
            return false to "zx_appointment 缺少 dept（科室）——**不知道挂哪个科**"
        val a = o.optJSONArray("slots")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_appointment 缺少 slots——**没有时段**，空的号源表"
        if (n > 24) return false to "zx_appointment 最多 24 个时段，实际 $n 个"
        val times = HashSet<String>()
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "slots[$k] 不是对象，应为 {\"t\":时段,\"left\":余号}"
            val t = s.optString("t")
            if (t.isBlank()) return false to "slots[$k] 缺少 t（时段）"
            if (!times.add(t)) return false to "slots[$k] 的时段「$t」重复"
            val left = s.optInt("left", 0)
            // 余号为负
            if (left < 0)
                return false to "slots[$k]「$t」的 left=$left 不能为负——" +
                    "**负余号会显示成「余 -3」，用户不知道这是什么意思**"
        }
        return true to ""
    }

    private fun hm(s: String): Int {
        val p = s.split(":")
        if (p.size != 2) return -1
        val h = p[0].trim().toIntOrNull() ?: return -1
        val m = p[1].trim().toIntOrNull() ?: return -1
        return h * 60 + m
    }

    /* ── 示例 DSL ────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_fitness" -> """
            {"type":"zx_fitness","n":"卧推","unit":"kg","target":60,"recs":[
               {"d":"10-01","v":50},{"d":"10-04","v":52.5},
               {"d":"10-07","v":52},{"d":"10-10","v":55},
               {"d":"10-13","v":57.5}]}""".trimIndent()
        "zx_medicine" -> """
            {"type":"zx_medicine","items":[
               {"n":"降压药","dose":"1 片","plan":["08:00","20:00"],"times":["08:00"]},
               {"n":"钙片","dose":"2 片","plan":["12:00"],"times":["12:00"]}]}""".trimIndent()
        "zx_parcel" -> """
            {"type":"zx_parcel","co":"顺丰","id":"SF1234567890","eta":14,"nodes":[
               {"t":"已揽收","ts":"10-10 09:00","p":"深圳中转场"}",
               {"t":"运输中","ts":"10-10 22:00","p":"武汉中转场"}",
               {"t":"派送中","ts":"10-11 08:30","p":"天河网点"}"]}""".trimIndent()
        "zx_ticket" -> """
            {"type":"zx_ticket","n":"音乐会","price":380,"max":2,"seats":[
               {"n":"A1","s":0},{"n":"A2","s":0},{"n":"A3","s":1},
               {"n":"B1","s":0},{"n":"B2","s":2},{"n":"B3","s":0}]}""".trimIndent()
        "zx_study" -> """
            {"type":"zx_study","n":"单词","cards":[
               {"q":"ephemeral","a":"短暂的","lv":1},
               {"q":"ubiquitous","a":"无处不在的","lv":3},
               {"q":"pragmatic","a":"务实的","lv":4}]}""".trimIndent()
        "zx_plant" -> """
            {"type":"zx_plant","n":"阳台","plants":[
               {"n":"绿萝","iv":7,"last":2,"sun":"散射光"}",
               {"n":"龟背竹","iv":10,"last":11,"sun":"明亮"}",
               {"n":"多肉","iv":15,"last":3,"sun":"直射"}"]}""".trimIndent()
        "zx_parking" -> """
            {"type":"zx_parking","floor":"B2","zone":"C 区","spot":"C-137",
             "mins":155,"fee":25}""".trimIndent()
        "zx_habit" -> """
            {"type":"zx_habit","n":"早起","goal":21,
             "labels":["10-05","10-06","10-07","10-08","10-09","10-10"],
             "days":[{"v":1},{"v":1},{"v":0},{"v":1},{"v":1},{"v":1}]}""".trimIndent()
        "zx_subscription" -> """
            {"type":"zx_subscription","n":"我的订阅","subs":[
               {"n":"音乐会员","v":15,"cyc":"月","next":12,"use":2},
               {"n":"云盘","v":998,"cyc":"年","next":180,"use":95},
               {"n":"健身卡","v":199,"cyc":"月","next":5,"use":120}]}""".trimIndent()
        "zx_pet" -> """
            {"type":"zx_pet","n":"豆豆","pets":[
               {"n":"豆豆","kind":"喂食","iv":12,"last":9},
               {"n":"豆豆","kind":"遛弯","iv":24,"last":26},
               {"n":"豆豆","kind":"驱虫","iv":720,"last":100}]}""".trimIndent()
        "zx_utilitymeter" -> """
            {"type":"zx_utilitymeter","kind":"电","unit":"度","reads":[
               {"d":"07月","v":4210},{"d":"08月","v":4386},
               {"d":"09月","v":4622},{"d":"10月","v":4801}]}""".trimIndent()
        "zx_sleep" -> """
            {"type":"zx_sleep","n":"本周","goal":8,"nights":[
               {"d":"10-05","v":7.2,"q":70},{"d":"10-06","v":6.4,"q":55},
               {"d":"10-07","v":8.1,"q":82},{"d":"10-08","v":5.9,"q":48},
               {"d":"10-09","v":7.8,"q":75},{"d":"10-10","v":8.4,"q":88}]}""".trimIndent()
        "zx_coupon" -> """
            {"type":"zx_coupon","n":"券包","coupons":[
               {"n":"满 200 减 30","v":30,"t":0,"min":200,"exp":2},
               {"n":"全场 8.5 折","v":0.85,"t":1,"min":0,"exp":15},
               {"n":"满 100 减 20","v":20,"t":0,"min":100,"exp":0,"used":true}]}""".trimIndent()
        "zx_appointment" -> """
            {"type":"zx_appointment","dept":"心内科","doctor":"李医生","date":"10-12","slots":[
               {"t":"09:00","left":3},{"t":"09:30","left":0},
               {"t":"10:00","left":1},{"t":"14:00","left":6},{"t":"14:30","left":2}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_fitness" -> "recs[{d,**v>0**}] **≥2（只有 1 条会被拦）** + **target（缺会被拦）** + unit?"
        "zx_medicine" -> "items[{n,dose,**plan 非空且递增**,times?}] ≤10；**已服时刻不在计划里会被拦**；渲染时**漏服标红**"
        "zx_parcel" -> "nodes[{t,ts?,p?}] ≤15 + **eta>0（缺会被拦）**；**时间倒序会被拦**"
        "zx_ticket" -> "seats[{n,**s∈0-2**}] ≤60 + **max>0** + price?；**默认选中已售会被拦**"
        "zx_study" -> "cards[{**q**,**a**,lv∈0-5}] ≤40；**无卡片会被拦**；**缺 a 会被拦**"
        "zx_plant" -> "plants[{n,**iv>0**,last≥0,sun?}] ≤20；**间隔 ≤0 会被拦**（倒计时除零）"
        "zx_parking" -> "**spot + floor 缺一即拦** + mins≥0 + fee? ≥0 + zone?"
        "zx_habit" -> "days[{**v∈0或1**}] ≤90 + **goal>0（缺会被拦）** + labels?"
        "zx_subscription" -> "subs[{n,**v≥0**,cyc∈月/年,**next≥0（负会被拦）**,use?}] ≤30"
        "zx_pet" -> "pets[{n,kind,**iv>0**,last≥0}] ≤20；**间隔 ≤0 会被拦**"
        "zx_utilitymeter" -> "reads[{d,**v≥0 且递增（倒转会被拦）**}] **≥2（少于 2 期会被拦）** ≤36 + kind? + unit?"
        "zx_sleep" -> "nights[{d,**v∈(0,24]**,q∈0-100}] **≥2（只有 1 晚会被拦）** ≤90 + **goal（缺会被拦）**"
        "zx_coupon" -> "coupons[{n,v,t∈0/1,**min≥0**,exp}] ≤24；**t=1 时 v 必须在 0-1（传 8.5 会被拦）**"
        "zx_appointment" -> "dept + slots[{t,**left≥0（负会被拦）**}] ≤24 + doctor? + date?"
        else -> "未注册组件：$component"
    }
}
