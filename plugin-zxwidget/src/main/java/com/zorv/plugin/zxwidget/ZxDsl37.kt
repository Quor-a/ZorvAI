package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三十七批（跨多次、有外部依赖）的校验 + 示例 + 规格。
 *
 * 这批查的是**「说不清现在轮到谁」**。
 *
 * ── 它和第三十六批的差别 ────────────────────────────────────
 *
 * 第三十六批做的是**一次能做完、且全程在自己手上**的事：
 * 做一顿饭、收拾一次行李、巡检一遍、急救一次。
 * 那批的关键是"我做到第几步了"——进度记在自己这边就够了。
 *
 * 这批做的是**拖很久、而且大部分时间不在自己手上**的事：
 * 装修、就医、报修、签证、寄件、面试。
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **这类事的关键不是"下一步做什么"，是"现在卡在谁手上"。**
 *
 * 第三十六批的急救卡回答"你在做第几步"——答案始终是自己。
 * 这批不一样，**多数时候这事不在你手上**：
 *
 *   装修卡在"等泥木班组进场"，不是"你要做什么"
 *   就医卡在"前面还有 12 个人"，不是"你要做什么"
 *   报修卡在"师傅还没派出来"，不是"你要做什么"
 *
 * 如果卡片只画"下一步：等待"，用户得到的和没画一样——
 * **他本来就什么也做不了，他要知道的是该催谁、等多久算异常。**
 *
 * 所以校验强制每个组件必须有"归属三件套"：
 *   1. **当前在谁手上**（自己 / 对方 / 第三方）
 *   2. **已经多久了**（不是"在等"，是"等了 2 天 3 小时"）
 *   3. **能不能催**（有的能催，有的只能等——混在一起最误导人）
 *
 * ── 要拦的（与 ZxRender37 注释一一对应） ────────────────────
 *
 *   renovation  阶段顺序错乱 / **未验收就进下一阶段** / 负责人为空
 *   clinic      当前环节缺排队人数 / 环节时间倒流 / 状态非法
 *   rental      房源缺必看维度 / 价格非正 / 无推荐项
 *   repair      **缺责任人** / 状态非法 / 日志时间倒流
 *   credential  **缺有效期** / 缺提前办理天数 / 已过期未标
 *   parcel      轨迹节点**时间倒流** / 缺当前节点 / 状态非法
 *   interview   状态非法 / 环节时间倒流 / 无当前环节
 *   visa        材料**缺必需标记** / 缺状态 / 无必需材料
 *   insurance   **缺保额** / 缺到期日 / 保障项为空
 *   reimburse   **单据金额与总额对不上** / 缺状态 / 金额非正
 *   relocation  缺房间 / 箱数非正 / 状态非法
 *   booking     时段冲突 / 缺可预订状态 / 结束早于开始
 */
object ZxDsl37 {

    val ALL = listOf(
        "zx_renovation", "zx_clinic", "zx_rental", "zx_repair",
        "zx_credential", "zx_ship", "zx_interview", "zx_visa",
        "zx_insurance", "zx_reimburse", "zx_relocation", "zx_booking"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_renovation" -> renovation(root)
        "zx_clinic" -> clinic(root)
        "zx_rental" -> rental(root)
        "zx_repair" -> repair(root)
        "zx_credential" -> credential(root)
        "zx_ship" -> parcel(root)
        "zx_interview" -> interview(root)
        "zx_visa" -> visa(root)
        "zx_insurance" -> insurance(root)
        "zx_reimburse" -> reimburse(root)
        "zx_relocation" -> relocation(root)
        "zx_booking" -> booking(root)
        else -> false to "未注册组件：$component"
    }

    private fun renovation(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("stages")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_renovation 缺少 stages——**没有阶段**，不知道装到哪了"
        if (n > 12) return false to "zx_renovation 最多 12 个阶段，实际 $n 个"
        var seenNotDone = false
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "stages[$k] 不是对象，应为 {\"n\":阶段,\"s\":0未开始/1进行中/2待验收/3已验收,\"who\":负责人}"
            if (s.optString("n").isBlank()) return false to "stages[$k] 缺少 n（阶段名）"
            // 负责人为空
            if (s.optString("who").isBlank())
                return false to "stages[$k]「${s.optString("n")}」**缺少 who（负责人）**——" +
                    "**装修卡的全部价值就是「现在卡在谁手上」**，" +
                    "没有负责人，用户不知道该催班组还是催设计师"
            val st = s.optInt("s", 0)
            if (st !in 0..3)
                return false to "stages[$k].s=$st 超出 0-3（0未开始/1进行中/2待验收/3已验收）"
            // 未验收就进下一阶段
            if (seenNotDone && st == 3)
                return false to "stages[$k]「${s.optString("n")}」**已验收，但它前面还有未验收的阶段**——" +
                    "**装修未验收就进下一阶段，返工成本极高**。" +
                    "要么把前置标为已验收，要么把这一阶段改回待验收"
            if (st < 3) seenNotDone = true
        }
        return true to ""
    }

    private fun clinic(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("steps")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_clinic 缺少 steps——**没有环节**，不知道看到哪了"
        if (n > 10) return false to "zx_clinic 最多 10 个环节，实际 $n 个"
        var cur = -1
        val times = ArrayList<Int>()
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "steps[$k] 不是对象，应为 {\"n\":环节,\"s\":0未到/1当前/2已完成,\"q\":排队人数,\"wait\":预计等待分钟,\"t\":时间}"
            if (s.optString("n").isBlank()) return false to "steps[$k] 缺少 n（环节名）"
            val st = s.optInt("s", 0)
            if (st !in 0..2)
                return false to "steps[$k].s=$st 超出 0-2（0未到/1当前/2已完成）"
            if (st == 1) {
                if (cur >= 0)
                    return false to "zx_clinic 有**多个当前环节**（steps[$cur] 和 steps[$k]）——" +
                        "**同一时刻只能在一个环节**，两个都标当前，用户不知道该去哪"
                cur = k
                // 当前环节缺排队人数
                if (!s.has("q") || s.optInt("q", -1) < 0)
                    return false to "steps[$k]「${s.optString("n")}」是**当前环节但缺 q（前面排队人数）**——" +
                        "**就医卡的全部价值就是「前面还有几个人」**。" +
                        "没有它，用户不知道该坐着等还是先去办别的"
                if (!s.has("wait") || s.optInt("wait", -1) < 0)
                    return false to "steps[$k] 是**当前环节但缺 wait（预计等待分钟）**——" +
                        "**「在等」不带时长，用户不知道是正常还是已经等太久**"
            }
            val t = s.optString("t", "")
            if (t.isNotBlank()) {
                val v = t.filter { it.isDigit() }.toIntOrNull()
                if (v != null) {
                    if (times.any { it > v })
                        return false to "steps[$k] 的时间 $t **早于前面的环节**——" +
                            "**环节时间倒流**，看不出真实顺序"
                    times.add(v)
                }
            }
        }
        if (cur < 0)
            return false to "zx_clinic **没有标记当前环节**（s=1）——" +
                "**不知道现在看到哪一步，这张卡就没有用**"
        return true to ""
    }

    private fun rental(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("houses")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_rental 缺少 houses——**没有房源**，比不了"
        if (n > 10) return false to "zx_rental 最多 10 套，实际 $n 套"
        val seen = HashSet<String>()
        for (k in 0 until n) {
            val h = a.optJSONObject(k)
                ?: return false to "houses[$k] 不是对象，应为 {\"n\":房源,\"price\":月租,\"area\":面积,\"dist\":通勤分钟,\"floor\":楼层,\"dir\":朝向}"
            val nm = h.optString("n", "")
            if (nm.isBlank()) return false to "houses[$k] 缺少 n（房源名）"
            if (!seen.add(nm)) return false to "houses[$k] 的房源「$nm」重复"
            // 缺必看维度
            val p = h.optDouble("price", Double.NaN)
            val ar = h.optDouble("area", Double.NaN)
            if (p.isNaN() || ar.isNaN())
                return false to "houses[$k]「$nm」**缺 price 或 area**——" +
                    "**价格和面积是必看维度**，缺一个就出不来单价也出不来综合分"
            if (p <= 0) return false to "houses[$k].price=$p 必须为正——" +
                "**月租为 0 或负，综合分除零**"
            if (ar <= 0) return false to "houses[$k] 的 area 必须为正"
            if (!h.has("dist"))
                return false to "houses[$k]「$nm」**缺 dist（通勤分钟）**——" +
                    "**只看单价会选到通勤 70 分钟的**，通勤是租房的第二决策维度"
            if (h.optInt("dist", -1) < 0)
                return false to "houses[$k] 的 dist 不能为负"
        }
        return true to ""
    }

    private fun repair(o: JSONObject): Pair<Boolean, String> {
        val s = o.optInt("s", -1)
        if (s !in 0..4)
            return false to "zx_repair 的 s=$s 超出 0-4（0已报/1已派单/2上门中/3已完工/4已评价）——" +
                "**状态认不出来，就不知道现在卡在谁手上**"
        // 缺责任人
        val own = o.optJSONArray("owners")
        if (own == null || own.length() < 5)
            return false to "zx_repair **缺 owners（各状态对应的责任人，需 5 个）**——" +
                "**报修卡的全部价值就是「现在在谁手上」**。" +
                "没有责任人，用户不知道该催物业还是催师傅"
        for (k in 0 until own.length())
            if (own.optString(k).isBlank())
                return false to "owners[$k] 是空串——**空责任人会显示成「卡在」**，等于没说"
        val a = o.optJSONArray("logs")
        if (a != null) {
            if (a.length() > 20) return false to "zx_repair 最多 20 条日志，实际 ${a.length()} 条"
            val ts = ArrayList<Int>()
            for (k in 0 until a.length()) {
                val l = a.optJSONObject(k)
                    ?: return false to "logs[$k] 不是对象，应为 {\"t\":时间,\"w\":谁,\"m\":内容}"
                if (l.optString("m").isBlank()) return false to "logs[$k] 缺少 m（内容）"
                if (l.optString("w").isBlank())
                    return false to "logs[$k] **缺 w（谁做的）**——" +
                        "**工单日志要能看出每一步是谁动的**，否则追责时说不清"
                val t = l.optString("t", "").filter { it.isDigit() }.toIntOrNull()
                if (t != null) {
                    if (ts.any { it > t })
                        return false to "logs[$k] 的时间**早于前面的日志**——" +
                            "**时间倒流**，看不出真实处理顺序"
                    ts.add(t)
                }
            }
        }
        return true to ""
    }

    private fun credential(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_credential 缺少 items——**没有证件**"
        if (n > 20) return false to "zx_credential 最多 20 项，实际 $n 项"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":证件,\"exp\":到期日,\"left\":剩余天,\"need\":提前办理天数,\"org\":办理机构}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（证件名）"
            // 缺有效期
            if (it.optString("exp").isBlank() && !it.has("left"))
                return false to "items[$k]「${it.optString("n")}」**既没 exp（到期日）也没 left（剩余天）**——" +
                    "**证件卡的全部价值是「还剩多久」**，两个都没有，用户得自己翻证件看日期"
            if (!it.has("left"))
                return false to "items[$k] 缺少 left（剩余天）——**给 exp 也请补上 left**，" +
                    "剩余天才是排序和标红的依据"
            // 缺提前办理天数
            val need = it.optInt("need", -1)
            if (need < 0)
                return false to "items[$k]「${it.optString("n")}」**缺 need（提前办理天数）**——" +
                    "**不知道提前多久动手**（有的要办两周），" +
                    "只给到期日用户会在最后一天才发现来不及"
            if (need > 365) return false to "items[$k] 的 need=$need 天超过一年——多半单位错了"
            if (it.optString("org").isBlank())
                return false to "items[$k]「${it.optString("n")}」**缺 org（办理机构）**——" +
                    "**该去哪办不写清楚，用户还要再问一次**"
        }
        return true to ""
    }

    private fun parcel(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("nodes")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_parcel 缺少 nodes——**没有轨迹节点**"
        if (n > 15) return false to "zx_parcel 最多 15 个节点，实际 $n 个"
        var cur = -1
        val ts = ArrayList<Int>()
        for (k in 0 until n) {
            val nd = a.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"n\":节点,\"t\":时间,\"s\":0未到/1当前/2已过}"
            if (nd.optString("n").isBlank()) return false to "nodes[$k] 缺少 n（节点名）"
            val s = nd.optInt("s", 0)
            if (s !in 0..2)
                return false to "nodes[$k].s=$s 超出 0-2（0未到/1当前/2已过）"
            if (s == 1) {
                if (cur >= 0)
                    return false to "nodes[$k] 和 nodes[$cur] **都是当前节点**——" +
                        "**同一时刻只能在一个节点**"
                cur = k
            }
            // 已过的节点必须有时间
            if (s == 2 && nd.optString("t").isBlank())
                return false to "nodes[$k]「${nd.optString("n")}」**已过但没有时间**——" +
                    "**轨迹节点没有时间，用户看不出是正常速度还是已经滞留**"
            val t = nd.optString("t", "").filter { it.isDigit() }.toIntOrNull()
            if (t != null) {
                if (ts.any { it > t })
                    return false to "nodes[$k] 的时间**早于前面的节点**——" +
                        "**轨迹时间倒流**，说明数据串了"
                ts.add(t)
            }
        }
        if (cur < 0 && n > 0)
            return false to "zx_parcel **没有标记当前节点（s=1）**——" +
                "**不知道包裹现在在哪，这张卡就没用**"
        return true to ""
    }

    private fun interview(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("rounds")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_interview 缺少 rounds——**没有轮次**"
        if (n > 8) return false to "zx_interview 最多 8 轮，实际 $n 轮"
        var cur = -1
        val ts = ArrayList<Int>()
        for (k in 0 until n) {
            val r = a.optJSONObject(k)
                ?: return false to "rounds[$k] 不是对象，应为 {\"n\":轮次,\"s\":0未开始/1进行中/2通过/3未通过,\"t\":时间}"
            if (r.optString("n").isBlank()) return false to "rounds[$k] 缺少 n（轮次名）"
            val s = r.optInt("s", 0)
            if (s !in 0..3)
                return false to "rounds[$k].s=$s 超出 0-3（0未开始/1进行中/2通过/3未通过）——" +
                    "**状态认不出来，就不知道卡在哪一轮**"
            if (s == 1) {
                if (cur >= 0)
                    return false to "rounds[$k] 和 rounds[$cur] **都是进行中**——" +
                        "**同一时刻只能在一轮**"
                cur = k
            }
            val t = r.optString("t", "").filter { it.isDigit() }.toIntOrNull()
            if (t != null) {
                if (ts.any { it > t })
                    return false to "rounds[$k] 的时间**早于前面的轮次**——" +
                        "**轮次时间倒流**，看不出真实进度"
                ts.add(t)
            }
        }
        if (cur < 0 && n > 0)
            return false to "zx_interview **没有标记进行中的轮次（s=1）**——" +
                "**不知道现在面到哪一轮、在等谁的结果**"
        return true to ""
    }

    private fun visa(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("docs")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_visa 缺少 docs——**没有材料清单**"
        if (n > 25) return false to "zx_visa 最多 25 项，实际 $n 项"
        var must = 0
        for (k in 0 until n) {
            val d = a.optJSONObject(k)
                ?: return false to "docs[$k] 不是对象，应为 {\"n\":材料,\"must\":是否必需,\"s\":0缺/1准备中/2已齐}"
            if (d.optString("n").isBlank()) return false to "docs[$k] 缺少 n（材料名）"
            // 缺必需标记
            if (!d.has("must"))
                return false to "docs[$k]「${d.optString("n")}」**缺 must（是否必需）**——" +
                    "**签证材料必须区分必需和可选**。" +
                    "混在一起列出，用户不知道缺哪样会直接被拒"
            if (d.optBoolean("must", false)) must++
            val s = d.optInt("s", 0)
            if (s !in 0..2)
                return false to "docs[$k].s=$s 超出 0-2（0缺/1准备中/2已齐）"
        }
        if (must == 0)
            return false to "zx_visa **没有任何必需材料**——" +
                "**全是可选项的话，这张清单拦不住任何一次被拒**"
        return true to ""
    }

    private fun insurance(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("covers")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_insurance 缺少 covers——**没有保障项**，" +
                "**只写「有保险」等于什么都没说**"
        if (n > 15) return false to "zx_insurance 最多 15 项，实际 $n 项"
        for (k in 0 until n) {
            val c = a.optJSONObject(k)
                ?: return false to "covers[$k] 不是对象，应为 {\"n\":保障项,\"amt\":保额,\"used\":已理赔}"
            if (c.optString("n").isBlank()) return false to "covers[$k] 缺少 n（保障项名）"
            // 缺保额
            val amt = c.optDouble("amt", Double.NaN)
            if (amt.isNaN())
                return false to "covers[$k]「${c.optString("n")}」**缺 amt（保额）**——" +
                    "**出事时才知道赔多少**，而那时已经晚了"
            if (amt <= 0) return false to "covers[$k] 的 amt=$amt 必须为正"
            val used = c.optDouble("used", 0.0)
            if (used < 0) return false to "covers[$k] 的 used 不能为负"
            if (used > amt)
                return false to "covers[$k] 的 used=$used **大于保额** $amt——" +
                    "**理赔超过保额**，剩余额度会算成负数"
        }
        // 缺到期日
        if (o.optString("exp").isBlank() && !o.has("left"))
            return false to "zx_insurance **既没 exp（到期日）也没 left（剩余天）**——" +
                "**过期的保单等于没买**，不给到期日用户会以为一直在保"
        if (!o.has("left"))
            return false to "zx_insurance 缺 left（剩余天）——**给 exp 也请补上 left**，" +
                "剩余天才是标红的依据"
        return true to ""
    }

    private fun reimburse(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_reimburse 缺少 items——**没有单据**"
        if (n > 40) return false to "zx_reimburse 最多 40 张，实际 $n 张"
        var sum = 0.0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":单据,\"v\":金额,\"s\":0待审/1已审/2驳回,\"why\":驳回原因}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（单据名）"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$k] 缺少 v（金额）"
            if (v <= 0) return false to "items[$k].v=$v 必须为正——" +
                "**金额为 0 或负，合计对不上账**"
            sum += v
            val s = it.optInt("s", -1)
            if (s !in 0..2)
                return false to "items[$k].s=$s 超出 0-2（0待审/1已审/2驳回）"
            if (s == 2 && it.optString("why").isBlank())
                return false to "items[$k]「${it.optString("n")}」**已驳回但没写 why**——" +
                    "**没有原因的驳回，用户不知道要补什么才能重报**"
        }
        val total = o.optDouble("total", Double.NaN)
        // 单据金额与总额对不上
        if (!total.isNaN() && Math.abs(sum - total) >= 0.01)
            return false to "zx_reimburse 的**单据合计 ¥${"%.2f".format(sum)} " +
                "与申报总额 ¥${"%.2f".format(total)} 对不上**（差 " +
                "¥${"%.2f".format(Math.abs(sum - total))}）——" +
                "**合计对不上就进审核，退回来的成本比先核对高得多**"
        return true to ""
    }

    private fun relocation(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("rooms")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_relocation 缺少 rooms——**没有房间**，" +
                "**只数箱数，到了新家才知道哪个房间的东西散在哪**"
        if (n > 15) return false to "zx_relocation 最多 15 个房间，实际 $n 个"
        var boxes = 0
        for (k in 0 until n) {
            val r = a.optJSONObject(k)
                ?: return false to "rooms[$k] 不是对象，应为 {\"n\":房间,\"box\":箱数,\"s\":0未打包/1已打包/2已到位}"
            if (r.optString("n").isBlank()) return false to "rooms[$k] 缺少 n（房间名）"
            val b = r.optInt("box", -1)
            if (b < 0) return false to "rooms[$k] 缺少 box（箱数）"
            if (b <= 0) return false to "rooms[$k].box=$b 必须为正——" +
                "**箱数为 0 的房间在进度条上占不到权重**"
            if (b > 200) return false to "rooms[$k] 的 box=$b 超过 200 箱"
            boxes += b
            val s = r.optInt("s", 0)
            if (s !in 0..2)
                return false to "rooms[$k].s=$s 超出 0-2（0未打包/1已打包/2已到位）"
        }
        if (boxes <= 0) return false to "zx_relocation 的总箱数为 0"
        return true to ""
    }

    private fun booking(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("slots")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_booking 缺少 slots——**没有时段**"
        if (n > 40) return false to "zx_booking 最多 40 个时段，实际 $n 个"
        var free = 0
        val seen = HashSet<String>()
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "slots[$k] 不是对象，应为 {\"d\":日期,\"t\":时段,\"s\":0可订/1已订/2不可订,\"by\":预订人}"
            if (s.optString("d").isBlank()) return false to "slots[$k] 缺少 d（日期）"
            val t = s.optString("t", "")
            if (t.isBlank()) return false to "slots[$k] 缺少 t（时段）"
            // 结束早于开始
            if (t.contains("-")) {
                val p2 = t.split("-")
                if (p2.size == 2) {
                    val s1 = p2[0].filter { it.isDigit() }.toIntOrNull()
                    val s2 = p2[1].filter { it.isDigit() }.toIntOrNull()
                    if (s1 != null && s2 != null && s2 <= s1)
                        return false to "slots[$k] 的时段「$t」**结束不晚于开始**——" +
                            "**这样画出来的格子宽度是负的**"
                }
            }
            // 时段冲突
            val key = s.optString("d", "") + "|" + t
            if (!seen.add(key))
                return false to "slots[$k] 的「${s.optString("d", "")} $t」**与前面重复**——" +
                    "**同一时段出现两次，用户不知道点哪个才算数**"
            val st = s.optInt("s", -1)
            if (st !in 0..2)
                return false to "slots[$k].s=$st 超出 0-2（0可订/1已订/2不可订）——" +
                    "**状态认不出来，用户会以为已订的还能抢**"
            if (st == 0) free++
        }
        if (free == 0)
            return false to "zx_booking **没有任何可订时段**——" +
                "**全满或全不可用的话，这张卡应该直接说「换日期或换场地」**，" +
                "而不是画一张点不动的网格"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_renovation" -> """
            {"type":"zx_renovation","n":"两居翻新","stages":[
               {"n":"拆改","s":3,"who":"拆除班组"}",
               {"n":"水电","s":3,"who":"水电班组"}",
               {"n":"泥木","s":1,"who":"泥木班组"}",
               {"n":"油漆","s":0,"who":"油漆班组"}",
               {"n":"安装","s":0,"who":"主材商家"}"]}""".trimIndent()
        "zx_clinic" -> """
            {"type":"zx_clinic","n":"陪诊","steps":[
               {"n":"挂号","s":2,"t":"0830"},
               {"n":"候诊","s":1,"q":12,"wait":35,"t":"0840"},
               {"n":"看诊","s":0,"t":""},
               {"n":"缴费","s":0,"t":""},
               {"n":"检查","s":0,"t":""},
               {"n":"取药","s":0,"t":""}]}""".trimIndent()
        "zx_rental" -> """
            {"type":"zx_rental","n":"租房","houses":[
               {"n":"A 小区","price":2800,"area":58,"dist":25,"floor":"6/18","dir":"南"}",
               {"n":"B 公寓","price":3400,"area":72,"dist":12,"floor":"3/28","dir":"东南"}",
               {"n":"C 老楼","price":1900,"area":46,"dist":65,"floor":"2/6","dir":"北"}"]}""".trimIndent()
        "zx_repair" -> """
            {"type":"zx_repair","n":"水管漏水","no":"WO-2048","s":1,
             "owners":["物业前台","维修班组","师傅本人","你（验收）","完成"],
             "logs":[
               {"t":"0902","w":"我","m":"报修：厨房下水管漏水"}",
               {"t":"0915","w":"物业前台","m":"已受理，转维修班组"}",
               {"t":"1030","w":"维修班组","m":"派单给王师傅"}"]}""".trimIndent()
        "zx_credential" -> """
            {"type":"zx_credential","n":"证件","items":[
               {"n":"身份证","exp":"2032-04","left":2050,"need":30,"org":"派出所"}",
               {"n":"驾驶证","exp":"2026-11","left":25,"need":15,"org":"车管所"}",
               {"n":"护照","exp":"2026-09","left":-18,"need":45,"org":"出入境"}"]}""".trimIndent()
        "zx_ship" -> """
            {"type":"zx_ship","n":"寄件","no":"SF1234567","nodes":[
               {"n":"已揽收","t":"1001","s":2},
               {"n":"到达转运中心","t":"1019","s":2},
               {"n":"运输中","t":"1108","s":1},
               {"n":"派送中","t":"","s":0},
               {"n":"已签收","t":"","s":0}]}""".trimIndent()
        "zx_interview" -> """
            {"type":"zx_interview","n":"面试","co":"某公司","rounds":[
               {"n":"笔试","s":2,"t":"0312"},
               {"n":"一面","s":2,"t":"0318"},
               {"n":"二面","s":1,"t":"0322"},
               {"n":"HR 面","s":0,"t":""}]}""".trimIndent()
        "zx_visa" -> """
            {"type":"zx_visa","n":"签证","docs":[
               {"n":"护照","must":true,"s":2},
               {"n":"照片","must":true,"s":2},
               {"n":"银行流水","must":true,"s":1},
               {"n":"在职证明","must":true,"s":0},
               {"n":"行程单","must":false,"s":0}]}""".trimIndent()
        "zx_insurance" -> """
            {"type":"zx_insurance","n":"医疗险","exp":"2026-08","left":295,"covers":[
               {"n":"住院医疗","amt":1000000,"used":0},
               {"n":"门诊","amt":20000,"used":3200},
               {"n":"重疾","amt":500000,"used":0}]}""".trimIndent()
        "zx_reimburse" -> """
            {"type":"zx_reimburse","n":"差旅","total":1444,"items":[
               {"n":"机票","v":890,"s":1},
               {"n":"住宿","v":356,"s":1},
               {"n":"打车","v":78,"s":0},
               {"n":"餐费","v":120,"s":2,"why":"超标准，限 80"}]}""".trimIndent()
        "zx_relocation" -> """
            {"type":"zx_relocation","n":"搬家","rooms":[
               {"n":"主卧","box":6,"s":2},
               {"n":"客厅","box":8,"s":1},
               {"n":"厨房","box":4,"s":1},
               {"n":"书房","box":5,"s":0}]}""".trimIndent()
        "zx_booking" -> """
            {"type":"zx_booking","n":"会议室","slots":[
               {"d":"05-12","t":"09-10","s":1,"by":"产品部"}",
               {"d":"05-12","t":"10-11","s":0},
               {"d":"05-12","t":"11-12","s":2},
               {"d":"05-13","t":"09-10","s":0},
               {"d":"05-13","t":"10-11","s":0},
               {"d":"05-13","t":"11-12","s":1,"by":"设计部"}"]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_renovation" -> "stages[{n,**who 非空（缺会被拦）**,s∈0-3}] ≤12；**未验收就进下一阶段会被拦**"
        "zx_clinic" -> "steps[{n,s∈0-2,t?}] ≤10；**当前环节必须带 q 和 wait（缺会被拦）**；**必须有一个 s=1**"
        "zx_rental" -> "houses[{n,**price>0**,**area>0**,**dist≥0（缺会被拦）**,floor?,dir?}] ≤10"
        "zx_repair" -> "s∈0-4 + **owners 需 5 个且非空（缺会被拦）** + logs[{t,w**非空**,m}]?；**日志时间倒流会被拦**"
        "zx_credential" -> "items[{n,exp?,**left（缺会被拦）**,**need≥0（缺会被拦）**,**org 非空（缺会被拦）**}] ≤20"
        "zx_ship" -> "nodes[{n,t?,s∈0-2}] ≤15；**已过节点必须带 t（缺会被拦）**；**必须有一个 s=1**；**时间倒流会被拦**"
        "zx_interview" -> "rounds[{n,s∈0-3,t?}] ≤8；**必须有一个 s=1**；**轮次时间倒流会被拦**"
        "zx_visa" -> "docs[{n,**must（缺会被拦）**,s∈0-2}] ≤25；**必须至少一项 must=true（没有会被拦）**"
        "zx_insurance" -> "covers[{n,**amt>0（缺会被拦）**,used?}] ≤15 + **exp 或 left（都没有会被拦）**；used ≤ amt"
        "zx_reimburse" -> "items[{n,**v>0**,s∈0-2,why?}] ≤40 + total?；**合计与 total 对不上会被拦**；**驳回缺 why 会被拦**"
        "zx_relocation" -> "rooms[{n,**box>0（缺会被拦）**,s∈0-2}] ≤15；**无房间会被拦**"
        "zx_booking" -> "slots[{d,**t（结束不晚于开始）**,s∈0-2,by?}] ≤40；**同日期同时段重复会被拦**；**无任何可订会被拦**"
        else -> "未注册组件：$component"
    }
}
