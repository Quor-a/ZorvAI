package com.zorv.plugin.zxwidget

import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三十六批（生活操作）的校验 + 示例 + 规格。
 *
 * 这批查的是**「照着做会出事」**。
 *
 * 前十三档一直在查"读不读得出信息"，到第三十五批换成"有没有对比基准"。
 * 这批再换一次——因为第三十五批的卡是**看**的，这批的卡是**照着做**的：
 *
 *   菜谱    不是"有什么菜"，是"第 3 步该放什么、放多少"
 *   急救    不是"急救知识"，是"现在这一步做什么、不能跳"
 *   出行清单 不是"要带什么"，是"还有哪件没装进包"
 *   巡检    不是"检查了什么"，是"哪一项不合格、为什么"
 *   备考    不是"要考什么"，是"今天该复习哪一科"
 *
 * "看"错了顶多误判；**"照着做"错了会真的出事**——
 * 急救顺序错了加重伤害、必带没带上了路才发现、
 * 不合格项没写原因后面没法整改。
 *
 * ── 贯穿这批的一条 ──────────────────────────────────────────
 *
 * **操作类卡片必须能停在"正在做"的状态。**
 *
 * 做一件事是分次的：菜做到第 3 步要去接电话、清单勾到一半要出门。
 * 所以这批每个组件都得记住进度——步骤勾选、当前步、已打包、已检项。
 *
 * 不能只给一份静态说明让用户自己记"我做到哪了"——
 * **把记忆负担留给用户，这张卡就退化成一段文字**。
 *
 * ── 要拦的（与 ZxRender36 注释一一对应） ────────────────────
 *
 *   recipe      步骤为空 / 配料缺量 / **份量缩放后用量非正**
 *   ledger      流水为空 / 金额为负 / 分类为空
 *   commute     无方案 / **耗时不含换乘步行** / 延误为负
 *   warranty    无条目 / 购买日晚于今天 / 保修月非正
 *   inspection  无检查项 / 状态值非法 / 已标不合格但没说明
 *   examprep    无科目 / 权重为负 / 剩余天数非正
 *   tripkit     无物品 / 分类为空 / 无必带项
 *   inventory   无条目 / 数量非正 / 安全线为负
 *   recycle     无条目 / **类别不在四类内** / 无投放要求
 *   carservice  无保养项 / 间隔非正 / 上次里程 > 当前里程
 *   expiry      无条目 / 剩余天数为负 / 数量非正
 *   splitbill   无人 / 金额非正 / **付款人不在名单里**
 *   firstaid    无步骤 / **缺禁忌提示** / 无是否叫 120
 *   sizing      无尺码表 / 测量值非正 / 尺码重复
 *   gift        无记录 / 金额非正 / dir 不在收/送
 */
object ZxDsl36 {

    val ALL = listOf(
        "zx_recipe", "zx_bookkeep", "zx_commute", "zx_warranty", "zx_inspection",
        "zx_examprep", "zx_tripkit", "zx_inventory", "zx_recycle", "zx_carservice",
        "zx_expiry", "zx_splitbill", "zx_firstaid", "zx_sizing", "zx_gift"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_recipe" -> recipe(root)
        "zx_bookkeep" -> ledger(root)
        "zx_commute" -> commute(root)
        "zx_warranty" -> warranty(root)
        "zx_inspection" -> inspection(root)
        "zx_examprep" -> examprep(root)
        "zx_tripkit" -> tripkit(root)
        "zx_inventory" -> inventory(root)
        "zx_recycle" -> recycle(root)
        "zx_carservice" -> carservice(root)
        "zx_expiry" -> expiry(root)
        "zx_splitbill" -> splitbill(root)
        "zx_firstaid" -> firstaid(root)
        "zx_sizing" -> sizing(root)
        "zx_gift" -> gift(root)
        else -> false to "未注册组件：$component"
    }

    private fun recipe(o: JSONObject): Pair<Boolean, String> {
        val st = o.optJSONArray("steps")
        val n = st?.length() ?: 0
        // 步骤为空
        if (st == null || n == 0)
            return false to "zx_recipe 缺少 steps——**没有步骤**，" +
                "这只是一张配料表，不是菜谱"
        if (n > 30) return false to "zx_recipe 最多 30 步，实际 $n 步"
        for (k in 0 until n)
            if (st.optString(k).isBlank())
                return false to "zx_recipe 的 steps[$k] 是空串——**空步骤会让序号错位**"
        val a = o.optJSONArray("ing")
        val m = a?.length() ?: 0
        if (a == null || m == 0)
            return false to "zx_recipe 缺少 ing（配料）——**不知道放什么**"
        if (m > 30) return false to "zx_recipe 最多 30 种配料，实际 $m 种"
        val base = o.optDouble("base", Double.NaN)
        if (base.isNaN() || base <= 0)
            return false to "zx_recipe 的 base=$base 必须为正——" +
                "**份量缩放要除以基准份数，除零会让用量变成无穷大**"
        for (k in 0 until m) {
            val ig = a.optJSONObject(k)
                ?: return false to "ing[$k] 不是对象，应为 {\"n\":名,\"q\":用量,\"u\":单位}"
            if (ig.optString("n").isBlank()) return false to "ing[$k] 缺少 n（配料名）"
            // 配料缺量
            val q = ig.optDouble("q", Double.NaN)
            if (q.isNaN())
                return false to "ing[$k]「${ig.optString("n")}」**缺少 q（用量）**——" +
                    "**没有用量就没法随份数缩放**，改份数时这一项不会变"
            if (q <= 0) return false to "ing[$k] 的 q=$q 必须为正——" +
                "**缩放后用量非正**，用户会看到「−0.5 勺」"
        }
        return true to ""
    }

    private fun ledger(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_ledger 缺少 items——**没有流水**，空的账本"
        if (n > 60) return false to "zx_ledger 最多 60 笔，实际 $n 笔"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"d\":日期,\"n\":名,\"c\":分类,\"v\":金额}"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$k] 缺少 v（金额）"
            // 金额为负
            if (v <= 0)
                return false to "items[$k].v=$v 必须为正——" +
                    "**支出为负会让总额偏小**，用户以为自己花得少。" +
                    "退款请单独记一笔，不要写成负支出"
            if (it.optString("c").isBlank())
                return false to "items[$k] 缺少 c（分类）——**没有分类就出不来占比**"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（名称）"
        }
        val b = o.optDouble("budget", Double.NaN)
        if (!b.isNaN() && b <= 0)
            return false to "zx_ledger 的 budget=$b 必须为正——" +
                "**预算为 0 或负，完成度除零**"
        return true to ""
    }

    private fun commute(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("plans")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_commute 缺少 plans——**没有方案**，用户没法选"
        if (n > 5) return false to "zx_commute 最多 5 个方案，实际 $n 个——" +
            "**气泡里超过 5 个就要滚动，反而比不完**"
        for (k in 0 until n) {
            val p = a.optJSONObject(k)
                ?: return false to "plans[$k] 不是对象，应为 {\"n\":方式,\"t\":总分钟,\"walk\":步行,\"chg\":换乘,\"delay\":延误}"
            if (p.optString("n").isBlank()) return false to "plans[$k] 缺少 n（方式名）"
            val t = p.optInt("t", 0)
            if (t <= 0) return false to "plans[$k].t=$t 必须为正"
            val walk = p.optInt("walk", -1)
            // 耗时不含步行
            if (walk < 0)
                return false to "plans[$k]「${p.optString("n")}」**缺少 walk（步行分钟）**——" +
                    "**步行段最容易漏**。漏了它总时长看着更短，" +
                    "用户按这个时间出门就会迟到"
            if (walk > t)
                return false to "plans[$k] 的 walk=$walk **大于**总时长 $t——" +
                    "**步行比全程还久，说明 t 没算步行进去**"
            if (p.optInt("chg", 0) < 0) return false to "plans[$k] 的 chg 不能为负"
            // 延误为负
            if (p.optInt("delay", 0) < 0)
                return false to "plans[$k] 的 delay 不能为负——" +
                    "**负延误会显示成「提前 −5 分钟」，用户不知道这是什么意思**"
            val jam = p.optInt("jam", 0)
            if (jam < 0 || jam > 100) return false to "plans[$k].jam=$jam 超出 0-100"
        }
        return true to ""
    }

    private fun warranty(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_warranty 缺少 items——**没有条目**，空的保修卡包"
        if (n > 20) return false to "zx_warranty 最多 20 项，实际 $n 项"
        val today = 20261011
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":品名,\"buy\":购买日,\"m\":保修月,\"left\":剩余天,\"sn\":序列号}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（品名）"
            // 购买日晚于今天
            val buy = it.optString("buy", "")
            if (buy.isNotBlank()) {
                val bd = buy.filter { it.isDigit() }.toIntOrNull()
                if (bd != null && bd > today)
                    return false to "items[$k]「${it.optString("n")}」的购买日 $buy " +
                        "**晚于今天**——**还没买的东西已经有保修了，" +
                        "多半是年份写错（比如 2027 写成 2026 的下一笔）**"
            }
            val m = it.optInt("m", 0)
            if (m <= 0) return false to "items[$k] 的 m=$m 必须为正——" +
                "**保修月为 0，进度条除零**"
            if (m > 120) return false to "items[$k] 的 m=$m 月超过 10 年"
            // 剩余天数：为负表示已过保，这是合法的，但必须给出
            if (!it.has("left"))
                return false to "items[$k] 缺少 left（剩余天）——" +
                    "**只给购买日期不给剩余天，用户得自己算保修到哪天**"
            if (it.optInt("left", 0) > m * 31)
                return false to "items[$k] 的 left=${it.optInt("left", 0)} " +
                    "**超过保修总长**（${m} 月）——剩余天数算错了"
        }
        return true to ""
    }

    private fun inspection(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_inspection 缺少 items——**没有检查项**，空的巡检表"
        if (n > 30) return false to "zx_inspection 最多 30 项，实际 $n 项"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":项,\"s\":0未检/1合格/2不合格,\"why\":说明}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（检查项）"
            // 状态值非法
            val s = it.optInt("s", 0)
            if (s !in 0..2)
                return false to "items[$k].s=$s 超出 0-2（0未检/1合格/2不合格）——" +
                    "**未知状态会被当未检，用户以为自己没检过**"
            // 不合格但没说明
            if (s == 2 && it.optString("why").isBlank())
                return false to "items[$k]「${it.optString("n")}」**已标不合格但没填 why**——" +
                    "**没有原因的不合格等于没记录**，后面没法整改也没法追责"
        }
        return true to ""
    }

    private fun examprep(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("subs")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_examprep 缺少 subs——**没有科目**，不知道要考什么"
        if (n > 15) return false to "zx_examprep 最多 15 科，实际 $n 科"
        var wSum = 0.0
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "subs[$k] 不是对象，应为 {\"n\":科目,\"w\":权重,\"done\":已学h,\"need\":需要h}"
            if (s.optString("n").isBlank()) return false to "subs[$k] 缺少 n（科目名）"
            val w = s.optDouble("w", Double.NaN)
            if (w.isNaN()) return false to "subs[$k] 缺少 w（权重）"
            if (w <= 0) return false to "subs[$k].w=$w 必须为正——" +
                "**权重为 0 的科目永远排不到「最该补」**"
            wSum += w
            val nd = s.optDouble("need", Double.NaN)
            if (nd.isNaN() || nd <= 0)
                return false to "subs[$k] 的 need=$nd 必须为正——" +
                    "**需要小时为 0，完成率除零**"
            if (s.optDouble("done", 0.0) < 0)
                return false to "subs[$k] 的 done 不能为负"
        }
        if (wSum <= 0) return false to "zx_examprep 的权重总和为 0"
        val days = o.optInt("days", -1)
        // 剩余天数非正
        if (days < 0)
            return false to "zx_examprep 缺少 days（剩余天）——" +
                "**没有剩余天数就算不出每天要学多久**，" +
                "而「每天几小时」才是用户真正要的"
        if (days > 3650) return false to "zx_examprep 的 days=$days 超过 10 年"
        return true to ""
    }

    private fun tripkit(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_tripkit 缺少 items——**没有物品**，空的清单"
        if (n > 40) return false to "zx_tripkit 最多 40 件，实际 $n 件"
        var must = 0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":品名,\"c\":分类,\"must\":是否必带}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（品名）"
            // 分类为空
            if (it.optString("c").isBlank())
                return false to "items[$k]「${it.optString("n")}」**缺少 c（分类）**——" +
                    "**不分组的清单是一长条**，用户勾到一半看不出哪类还没装"
            if (it.optBoolean("must", false)) must++
        }
        // 无必带项
        if (must == 0)
            return false to "zx_tripkit **没有标记任何必带项**——" +
                "**所有项平权列出，用户分不清哪件忘了会出事**"
        return true to ""
    }

    private fun inventory(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_inventory 缺少 items——**没有条目**，空的库存表"
        if (n > 30) return false to "zx_inventory 最多 30 项，实际 $n 项"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":品名,\"q\":存量,\"min\":安全线,\"u\":单位}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（品名）"
            val q = it.optDouble("q", Double.NaN)
            if (q.isNaN())
                return false to "items[$k] 缺少 q（存量）——**不知道还有多少**"
            if (q < 0) return false to "items[$k].q=$q 不能为负——" +
                "**负存量会让进度条宽度变负**"
            val mn = it.optDouble("min", Double.NaN)
            if (mn.isNaN())
                return false to "items[$k]「${it.optString("n")}」**缺少 min（安全线）**——" +
                    "**只给存量，用户不知道 3 卷纸算多还是算少**"
            if (mn < 0) return false to "items[$k] 的 min=$mn 不能为负"
        }
        return true to ""
    }

    private fun recycle(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_recycle 缺少 items——**没有条目**"
        if (n > 30) return false to "zx_recycle 最多 30 项，实际 $n 项"
        val OK4 = setOf("可回收", "有害", "厨余", "其他")
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":物品,\"t\":类别,\"tip\":投放要求}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（物品名）"
            val t = it.optString("t", "")
            // 类别不在四类内
            if (t !in OK4)
                return false to "items[$k]「${it.optString("n")}」的 t=\"$t\" " +
                    "**不在四类之内**（可回收/有害/厨余/其他）——" +
                    "**认不出的类别会掉到「其他」桶，而有害垃圾混进其他桶是实际问题**"
            // 无投放要求
            if (it.optString("tip").isBlank())
                return false to "items[$k]「${it.optString("n")}」**缺少 tip（投放要求）**——" +
                    "**只说「电池是有害」，用户不知道要不要拆、要不要包好**"
        }
        return true to ""
    }

    private fun carservice(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_carservice 缺少 items——**没有保养项**"
        if (n > 20) return false to "zx_carservice 最多 20 项，实际 $n 项"
        val km = o.optInt("km", -1)
        if (km < 0) return false to "zx_carservice 缺少 km（当前里程）——" +
            "**没有当前里程判不出超没超里程**"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":项目,\"ivKm\":里程间隔,\"lastKm\":上次里程,\"ivM\":月间隔,\"lastM\":上次距今月}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（项目名）"
            val ivKm = it.optInt("ivKm", 0)
            val ivM = it.optInt("ivM", 0)
            // 间隔非正——两个都非正就永远不触发
            if (ivKm <= 0 && ivM <= 0)
                return false to "items[$k]「${it.optString("n")}」**ivKm 和 ivM 都没给或都是 0**——" +
                    "**里程和时间两个触发条件至少要有一个是正的**，" +
                    "否则这一项永远判不出到期"
            if (ivKm < 0 || ivM < 0)
                return false to "items[$k] 的间隔不能为负——**负间隔会立刻判定为超期**"
            if (ivKm > 100000) return false to "items[$k] 的 ivKm=$ivKm 超过 10 万公里"
            // 上次里程 > 当前里程
            if (ivKm > 0) {
                val lk = it.optInt("lastKm", -1)
                if (lk < 0)
                    return false to "items[$k] 缺少 lastKm——**不知道上次保养时的里程**"
                if (lk > km)
                    return false to "items[$k] 的 lastKm=$lk **大于**当前里程 $km——" +
                        "**里程表倒转**，算出来的「还差多少 km」是负的"
            }
        }
        return true to ""
    }

    private fun expiry(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_expiry 缺少 items——**没有条目**"
        if (n > 40) return false to "zx_expiry 最多 40 项，实际 $n 项"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":品名,\"left\":剩余天,\"q\":数量}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（品名）"
            // 剩余天数为负
            if (!it.has("left") || it.optInt("left", 0) < 0)
                return false to "items[$k]「${it.optString("n")}」的 left 缺失或为负——" +
                    "**不知道还剩几天就排不了序**，而这张卡的全部价值就是排序"
            if (it.optInt("left", 0) > 36500)
                return false to "items[$k] 的 left 超过 100 年——多半是日期算错了"
            if ((it.optInt("q", 1)) <= 0)
                return false to "items[$k] 的 q=${it.optInt("q", 1)} 必须为正"
        }
        return true to ""
    }

    private fun splitbill(o: JSONObject): Pair<Boolean, String> {
        val ps = o.optJSONArray("people")
        val pn = ps?.length() ?: 0
        if (ps == null || pn == 0)
            return false to "zx_splitbill 缺少 people——**没有人**，不知道跟谁分"
        if (pn > 20) return false to "zx_splitbill 最多 20 人，实际 $pn 人"
        val names = HashSet<String>()
        for (k in 0 until pn) {
            val nm = ps.optString(k)
            if (nm.isBlank()) return false to "people[$k] 是空串"
            if (!names.add(nm)) return false to "people[$k]「$nm」重复——" +
                "**两个人同名，转账方案会算错**"
        }
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_splitbill 缺少 items——**没有支出**，不用分"
        if (n > 60) return false to "zx_splitbill 最多 60 笔，实际 $n 笔"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"p\":付款人,\"v\":金额,\"n\":名}"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$k] 缺少 v（金额）"
            if (v <= 0) return false to "items[$k].v=$v 必须为正——" +
                "**金额为负会让总额对不上**，人均算出来也是错的"
            // 付款人不在名单里
            val p = it.optString("p", "")
            if (p.isBlank())
                return false to "items[$k] 缺少 p（付款人）——**不知道谁付的**"
            if (p !in names)
                return false to "items[$k] 的付款人「$p」**不在 people 名单里**——" +
                    "**他的付款会被算进「别人欠他」之外，总额对不上**"
        }
        return true to ""
    }

    private fun firstaid(o: JSONObject): Pair<Boolean, String> {
        val st = o.optJSONArray("steps")
        val n = st?.length() ?: 0
        // 无步骤
        if (st == null || n == 0)
            return false to "zx_firstaid 缺少 steps——**没有步骤**，" +
                "**急救卡没有步骤就是没有用**"
        if (n > 12) return false to "zx_firstaid 最多 12 步，实际 $n 步——" +
            "**急救步骤超过 12 步，人在慌的时候记不住**"
        for (k in 0 until n)
            if (st.optString(k).isBlank())
                return false to "zx_firstaid 的 steps[$k] 是空串——" +
                    "**空步骤会让用户卡在这一步不知道做什么**"
        // 缺禁忌提示
        val w = o.optJSONArray("warn")
        if (w == null || w.length() == 0)
            return false to "zx_firstaid **缺少 warn（禁忌）**——" +
                "**急救里「不要做什么」和「要做什么」同样重要**。" +
                "只给步骤不给禁忌，用户可能做对步骤的同时犯了禁忌"
        for (k in 0 until w.length())
            if (w.optString(k).isBlank())
                return false to "zx_firstaid 的 warn[$k] 是空串"
        // 是否叫 120
        if (!o.has("call"))
            return false to "zx_firstaid **缺少 call（是否需要先叫 120）**——" +
                "**这是急救里最优先的判断**，不给它用户可能自己处理而耽误叫车"
        return true to ""
    }

    private fun sizing(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("rows")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_sizing 缺少 rows——**没有尺码表**，比不了"
        if (n > 15) return false to "zx_sizing 最多 15 个码，实际 $n 个"
        val seen = HashSet<String>()
        for (k in 0 until n) {
            val r = a.optJSONObject(k)
                ?: return false to "rows[$k] 不是对象，应为 {\"size\":尺码,\"chest\":胸围,\"waist\":腰围,\"len\":衣长}"
            val sz = r.optString("size", "")
            if (sz.isBlank()) return false to "rows[$k] 缺少 size"
            if (!seen.add(sz)) return false to "rows[$k] 的尺码「$sz」重复"
            val c = r.optDouble("chest", Double.NaN)
            val ww = r.optDouble("waist", Double.NaN)
            if (c.isNaN() || ww.isNaN())
                return false to "rows[$k]「$sz」**缺少 chest 或 waist**——" +
                    "**两个维度都要有**，只按胸围选，腰围装不下的退换率极高"
            if (c <= 0 || ww <= 0) return false to "rows[$k] 的尺寸必须为正"
            if (c > 300 || ww > 300) return false to "rows[$k] 的尺寸超过 300cm——单位多半错了"
        }
        val mc = o.optDouble("chest", Double.NaN)
        val mw = o.optDouble("waist", Double.NaN)
        if (!mc.isNaN() && mc <= 0)
            return false to "zx_sizing 的 chest=$mc 必须为正"
        if (!mw.isNaN() && mw <= 0)
            return false to "zx_sizing 的 waist=$mw 必须为正"
        if (mc.isNaN() && mw.isNaN())
            return false to "zx_sizing **没有给实测值**（chest / waist 都没有）——" +
                "**没有实测就推不出推荐码**，这张卡退化成一张普通尺码表"
        return true to ""
    }

    private fun gift(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_gift 缺少 items——**没有记录**"
        if (n > 40) return false to "zx_gift 最多 40 笔，实际 $n 笔"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":人,\"what\":什么,\"v\":金额,\"d\":日期,\"dir\":\"收\"或\"送\",\"back\":是否已回}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（人）"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$k] 缺少 v（金额）"
            if (v <= 0) return false to "items[$k].v=$v 必须为正——" +
                "**金额为负会让收送差额算错**"
            val dir = it.optString("dir", "")
            if (dir != "收" && dir != "送")
                return false to "items[$k] 的 dir=\"$dir\" 只认「收」或「送」——" +
                    "**方向认不出来，这笔要么不进统计，要么被算反**"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_recipe" -> """
            {"type":"zx_recipe","n":"番茄炒蛋","base":2,"ing":[
               {"n":"番茄","q":2,"u":"个"}",{"n":"鸡蛋","q":3,"u":"个"}",
               {"n":"盐","q":2,"u":"g"}],
             "steps":["番茄切块","鸡蛋打散加盐",
               "热油炒蛋盛出","下番茄炒出汁","倒回鸡蛋翻匀"]}""".trimIndent()
        "zx_bookkeep" -> """
            {"type":"zx_bookkeep","n":"10 月","budget":4000,"items":[
               {"d":"10-02","n":"超市","c":"餐饮","v":186},
               {"d":"10-05","n":"打车","c":"交通","v":43},
               {"d":"10-08","n":"新鞋","c":"购物","v":899},
               {"d":"10-11","n":"聚餐","c":"餐饮","v":320}]}""".trimIndent()
        "zx_commute" -> """
            {"type":"zx_commute","from":"天河","to":"珠江新城","plans":[
               {"n":"地铁","t":32,"walk":8,"chg":1,"delay":0,"jam":65},
               {"n":"打车","t":24,"walk":3,"chg":0,"delay":12,"jam":0},
               {"n":"公交","t":45,"walk":12,"chg":0,"delay":0,"jam":40}]}""".trimIndent()
        "zx_warranty" -> """
            {"type":"zx_warranty","n":"家电","items":[
               {"n":"冰箱","buy":"2023-06","m":36,"left":245,"sn":"BX7788"},
               {"n":"洗衣机","buy":"2021-03","m":36,"left":-180,"sn":"XY1024"},
               {"n":"电视","buy":"2024-11","m":24,"left":32,"sn":"TV3391"}]}""".trimIndent()
        "zx_inspection" -> """
            {"type":"zx_inspection","n":"消防巡检","items":[
               {"n":"灭火器压力","s":1},
               {"n":"疏散通道","s":2,"why":"三层堆放杂物"}",
               {"n":"应急照明","s":1},
               {"n":"报警按钮","s":0}]}""".trimIndent()
        "zx_examprep" -> """
            {"type":"zx_examprep","n":"期末","days":14,"subs":[
               {"n":"高数","w":3,"done":12,"need":40},
               {"n":"英语","w":2,"done":18,"need":25},
               {"n":"专业课","w":4,"done":6,"need":35}]}""".trimIndent()
        "zx_tripkit" -> """
            {"type":"zx_tripkit","n":"出差","items":[
               {"n":"身份证","c":"证件","must":true},
               {"n":"充电器","c":"电子","must":true},
               {"n":"换洗衣物","c":"衣物","must":true},
               {"n":"雨伞","c":"杂物","must":false}]}""".trimIndent()
        "zx_inventory" -> """
            {"type":"zx_inventory","n":"家用","items":[
               {"n":"纸巾","q":2,"min":6,"u":"卷"}",
               {"n":"米","q":8,"min":2,"u":"kg"},
               {"n":"洗衣液","q":1,"min":1,"u":"瓶"}"]}""".trimIndent()
        "zx_recycle" -> """
            {"type":"zx_recycle","n":"今天","items":[
               {"n":"电池","t":"有害","tip":"单独投放，不要压扁"}",
               {"n":"菜叶","t":"厨余","tip":"沥干水分后投放"}",
               {"n":"快递箱","t":"可回收","tip":"拆开压平"}",
               {"n":"用过的纸巾","t":"其他","tip":"不可回收"}"]}""".trimIndent()
        "zx_carservice" -> """
            {"type":"zx_carservice","n":"保养","km":48200,"items":[
               {"n":"机油","ivKm":10000,"lastKm":41000,"ivM":12,"lastM":9},
               {"n":"刹车片","ivKm":40000,"lastKm":12000,"ivM":0,"lastM":0},
               {"n":"雨刷","ivKm":0,"lastKm":0,"ivM":24,"lastM":26}]}""".trimIndent()
        "zx_expiry" -> """
            {"type":"zx_expiry","n":"冰箱","items":[
               {"n":"牛奶","left":-2,"q":1},
               {"n":"酸奶","left":4,"q":3},
               {"n":"鸡蛋","left":21,"q":10},
               {"n":"黄油","left":90,"q":1}]}""".trimIndent()
        "zx_splitbill" -> """
            {"type":"zx_splitbill","n":"聚餐","people":["我","小李","小王"],
             "items":[
               {"p":"我","v":420,"n":"晚餐"}",
               {"p":"小李","v":80,"n":"打车"}",
               {"p":"我","v":60,"n":"零食"}"]}""".trimIndent()
        "zx_firstaid" -> """
            {"type":"zx_firstaid","n":"烫伤","call":false,
             "steps":["立刻用流动冷水冲 15 分钟","小心脱去衣物，粘住的部分别硬扯",
               "继续泡冷水降温","用干净纱布覆盖","不要挑破水泡"],
             "warn":["不要涂牙膏酱油","不要冰敷","不要挑破水泡"]}""".trimIndent()
        "zx_sizing" -> """
            {"type":"zx_sizing","n":"衬衫","chest":96,"waist":84,"rows":[
               {"size":"S","chest":92,"waist":78,"len":68},
               {"size":"M","chest":98,"waist":86,"len":70},
               {"size":"L","chest":104,"waist":90,"len":72}]}""".trimIndent()
        "zx_gift" -> """
            {"type":"zx_gift","n":"人情","items":[
               {"n":"同事A","what":"结婚","v":800,"d":"05-02","dir":"收","back":false},
               {"n":"同学B","what":"满月","v":600,"d":"06-18","dir":"送"}",
               {"n":"邻居C","what":"乔迁","v":300,"d":"09-01","dir":"收","back":true}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_recipe" -> "ing[{n,**q>0（缺会被拦）**,u?}] + **steps 非空（空会被拦）** + base>0；渲染时**配料随份数缩放**"
        "zx_bookkeep" -> "items[{d,n,**c 非空**,**v>0（负会被拦）**}] ≤60 + budget?；**大额 3 倍均值标红**"
        "zx_commute" -> "plans[{n,**t>0**,**walk≥0 且 ≤t（缺或超过会被拦）**,chg?,delay≥0,jam∈0-100}] ≤5"
        "zx_warranty" -> "items[{n,buy?,**m>0**,**left（缺会被拦）**,sn?}] ≤20；**购买日晚于今天会被拦**；left 可负（已过保）"
        "zx_inspection" -> "items[{n,**s∈0-1-2**,why?}] ≤30；**已标不合格但 why 为空会被拦**"
        "zx_examprep" -> "subs[{n,**w>0**,done≥0,**need>0**}] ≤15 + **days≥0（缺会被拦）**"
        "zx_tripkit" -> "items[{n,**c 非空（缺会被拦）**,must?}] ≤40；**必须至少标记一个 must（没有会被拦）**"
        "zx_inventory" -> "items[{n,**q≥0**,**min（缺会被拦）**,u?}] ≤30；**存量 ≤ 安全线标红**"
        "zx_recycle" -> "items[{n,**t∈可回收/有害/厨余/其他（不在四类内会被拦）**,**tip 非空（缺会被拦）**}] ≤30"
        "zx_carservice" -> "km≥0 + items[{n,ivKm?,lastKm?,**ivM?**}] ≤20；**两个间隔都非正会被拦**；**lastKm > km 会被拦**"
        "zx_expiry" -> "items[{n,**left≥0（缺或负会被拦）**,q?}] ≤40；**渲染时按 left 升序**，过期标红"
        "zx_splitbill" -> "people[名] ≤20 + items[{**p 必须在 people 里（不在会被拦）**,**v>0**,n?}] ≤60"
        "zx_firstaid" -> "**steps 非空** ≤12 + **warn 非空（缺会被拦）** + **call（缺会被拦）**；**不能跳步**"
        "zx_sizing" -> "rows[{size,**chest>0**,**waist>0**,len?}] ≤15 + **chest 或 waist 实测至少一个（都没有会被拦）**"
        "zx_gift" -> "items[{n,what?,**v>0**,d?,**dir∈收/送（非法会被拦）**,back?}] ≤40"
        else -> "未注册组件：$component"
    }
}
