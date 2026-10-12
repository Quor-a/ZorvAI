package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs

/**
 * 第三十九批（责任在两个主体之间转移）的校验 + 示例 + 规格。
 *
 * 这批查的是**「只记了清单，没有双方确认」**。
 *
 * ── 它和第三十七、三十八批的差别 ──────────────────────────────
 *
 * 第三十七批做的是**事情会正常推进**的那部分：装修按阶段走、
 * 就医排队等、报修派单上门。核心是"现在卡在谁手上、能不能催"。
 *
 * 第三十八批做的是**事情不会正常推进**的那部分：快递丢了、
 * 搬家磕坏了、报销被退了。核心是"证据窗口还剩多久"。
 *
 * 这两批有个共同的隐含前提：**责任归属是清楚的**。
 * 三十七批说"卡在物业手上"，三十八批说"要留开箱照片"——
 * 都默认"谁该负责"这件事没有争议。
 *
 * 这批是第三半：**责任本身正在转移，而转移的那一刻最容易出事**。
 *
 * ── 贯穿的一条 ──────────────────────────────────────────────
 *
 * **交接要留下双方都认的快照——只打勾没人签字，事后就是互相甩锅。**
 *
 * 一份清单，打勾完事，和"双方对同一个状态达成共识"是两件事：
 *
 *   打勾：我记着我交了什么       → 对方不认就没有用
 *   签字：双方在同一时刻认同一个状态 → 后来出问题能定位到哪一次
 *
 * ── 要拦的（与 ZxRender39 注释一一对应） ────────────────────
 *
 *   handoff     **缺状态快照**（只有名字）/ 缺交接双方 / 缺接手方异议入口
 *   moveout     **扣项缺依据** / 缺双方 / 扣款超过押金
 *   vehiclecare 缺当前里程 / 缺下次触发 / **未分必做与建议**
 *   medremote   **缺备案有效期** / **缺报销比例对比** / 缺就医地
 *   housedeal   **无任何资金监管节点** / 节点无金额 / 缺双方
 *   schoolup    **缺报名窗口** / **过期未标后果** / 无必需材料
 *   carerota    排班有空档 / 缺责任人 / 缺交接标记
 *   taxxborder  **缺免征额** / 缺税率 / **小计与单价×数量对不上**
 *   rentreturn  **缺借出时状态** / **状态变了无判定** / 缺双方
 *   dutyrota    排班有空档 / **换班缺双方同意** /
 *   annualaudit **缺年审截止** / **过期未标后果** / 缺材料标记
 *   liability   **责任比例合计 ≠ 100** / **缺依据** / 缺双方
 */
object ZxDsl39 {

    val ALL = listOf(
        "zx_handoff", "zx_moveout", "zx_vehiclecare", "zx_medremote",
        "zx_housedeal", "zx_schoolup", "zx_carerota", "zx_taxxborder",
        "zx_rentreturn", "zx_dutyrota", "zx_annualaudit", "zx_liability"
    )

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_handoff" -> handoff(root)
        "zx_moveout" -> moveout(root)
        "zx_vehiclecare" -> vehiclecare(root)
        "zx_medremote" -> medremote(root)
        "zx_housedeal" -> housedeal(root)
        "zx_schoolup" -> schoolup(root)
        "zx_carerota" -> carerota(root)
        "zx_taxxborder" -> taxxborder(root)
        "zx_rentreturn" -> rentreturn(root)
        "zx_dutyrota" -> dutyrota(root)
        "zx_annualaudit" -> annualaudit(root)
        "zx_liability" -> liability(root)
        else -> false to "未注册组件：$component"
    }

    private fun jarr(o: JSONObject, k: String): List<JSONObject> {
        val a = o.optJSONArray(k) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun handoff(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_handoff 缺少 items——**没有交接物**"
        if (n > 30) return false to "zx_handoff 最多 30 项，实际 $n 项"
        var noSnap = 0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":物品,\"v\":交接时状态值,\"ok\":接手方认不认}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（物品名）"
            // 缺状态快照
            if (it.optString("v").isBlank()) {
                noSnap++
                if (noSnap == 1)
                    return false to "items[$k]「${it.optString("n")}」**缺 v（交接时的状态值）**——" +
                        "**「猫粮」和「猫粮还剩 1.2kg」是两回事**。" +
                        "只记名字不记状态，接回来对不上就说不清了"
            }
            if (!it.has("ok"))
                return false to "items[$k] 缺 ok（接手方认不认）——" +
                    "**没有异议入口，接手方只能私下抱怨，不会落到记录上**"
        }
        // 缺交接双方
        if (o.optString("from").isBlank() || o.optString("to").isBlank())
            return false to "zx_handoff **缺 from 或 to（交接双方）**——" +
                "**不知道交给谁，这份清单签给谁看**"
        return true to ""
    }

    private fun moveout(o: JSONObject): Pair<Boolean, String> {
        val dep = o.optDouble("dep", Double.NaN)
        if (dep.isNaN() || dep <= 0)
            return false to "zx_moveout 缺 dep（押金）或押金非正——" +
                "**没有押金数额，扣多少都算不清**"
        val a = o.optJSONArray("ded")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_moveout 缺少 ded（扣项）**——退租结算的全部争议都在扣了多少**"
        if (n > 20) return false to "zx_moveout 最多 20 项，实际 $n 项"
        var cut = 0.0
        for (k in 0 until n) {
            val d = a.optJSONObject(k)
                ?: return false to "ded[$k] 不是对象，应为 {\"n\":扣项,\"amt\":金额,\"why\":依据,\"ok\":租客认不认}"
            if (d.optString("n").isBlank()) return false to "ded[$k] 缺少 n（扣项名）"
            val amt = d.optDouble("amt", Double.NaN)
            if (amt.isNaN() || amt <= 0)
                return false to "ded[$k] 缺 amt 或金额非正——**扣项没金额就没法结算**"
            cut += amt
            // 扣项缺依据
            if (d.optString("why").isBlank())
                return false to "ded[$k]「${d.optString("n")}」**缺 why（依据）**——" +
                    "**「墙面有洞」和「墙面有洞，搬进来就有」是两回事**。" +
                    "不写依据，租客一定不服"
            if (!d.has("ok"))
                return false to "ded[$k] 缺 ok（租客认不认）——" +
                    "**结算要两方认同一个数**，只记房东的算法没用"
        }
        // 扣款超过押金
        if (cut > dep)
            return false to "zx_moveout **扣款合计 ${"%.0f".format(cut)} 超过押金 " +
                "${"%.0f".format(dep)}**——**超了的部分要不要补，得单独说清，不能混在结算里**"
        if (o.optString("landlord").isBlank() || o.optString("tenant").isBlank())
            return false to "zx_moveout **缺 landlord 或 tenant（双方）**——" +
                "**押金扣多少，两边得认同一个数**"
        return true to ""
    }

    private fun vehiclecare(o: JSONObject): Pair<Boolean, String> {
        // 缺当前里程
        val cur = o.optInt("cur", -1)
        if (cur < 0)
            return false to "zx_vehiclecare **缺 cur（当前里程）**——" +
                "**不知道现在多少公里，就没法判断哪个项目到没到**"
        if (cur > 1000000) return false to "zx_vehiclecare 的 cur=$cur 超过一百万公里"
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_vehiclecare 缺少 items——**没有维保项目**"
        if (n > 25) return false to "zx_vehiclecare 最多 25 项，实际 $n 项"
        var must = 0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":项目,\"km\":下次里程,\"mo\":下次月数,\"need\":必做}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（项目名）"
            // 缺下次触发
            if (!it.has("km") && !it.has("mo"))
                return false to "items[$k]「${it.optString("n")}」**km 和 mo 都没有**——" +
                    "**里程和时间哪个先到算哪个**，两个都没有就永远不知道什么时候该做"
            val km = it.optInt("km", Int.MAX_VALUE)
            val mo = it.optInt("mo", 999)
            if (km <= 0 && mo >= 999)
                return false to "items[$k] 的 km=$km 非正且没有 mo"
            if (!it.has("need"))
                return false to "items[$k]「${it.optString("n")}」**缺 need（必做/建议）**——" +
                    "**混在一起，用户要么全做花冤枉钱，要么全不做留下隐患**"
            if (it.optBoolean("need", false)) must++
        }
        if (must == 0)
            return false to "zx_vehiclecare **没有任何必做项目**——" +
                "**全是建议的话，用户会以为都可以不做**"
        return true to ""
    }

    private fun medremote(o: JSONObject): Pair<Boolean, String> {
        // 缺就医地
        if (o.optString("place").isBlank())
            return false to "zx_medremote **缺 place（就医地）**——" +
                "**备案是按就医地办的**，不知道在哪就没法判断用不用得上"
        // 缺备案有效期
        if (!o.has("leftH"))
            return false to "zx_medremote **缺 leftH（备案剩余小时）**——" +
                "**备案有有效期**，不给截止用户不知道什么时候要重办"
        val lh = o.optInt("leftH", 0)
        val rate = o.optDouble("rate", Double.NaN)
        val local = o.optDouble("local", Double.NaN)
        // 缺报销比例对比
        if (rate.isNaN() || local.isNaN())
            return false to "zx_medremote **缺 rate（异地报销比例）或 local（本地比例）**——" +
                "**只说「已备案」没用，用户要知道会少报多少**"
        if (rate !in 0.0..1.0 || local !in 0.0..1.0)
            return false to "zx_medremote 的报销比例应在 0-1 之间（0.7 表示 70%）"
        if (lh <= 0 && o.optString("miss").isBlank())
            return false to "zx_medremote **备案已过期但没写 miss（后果）**——" +
                "**过期后要么全自费，要么回去补办再手工报销**"
        val a = o.optJSONArray("need")
        if (a == null || a.length() == 0)
            return false to "zx_medremote 缺 need（要带的材料）"
        if (a.length() > 15) return false to "zx_medremote 最多 15 项，实际 ${a.length()} 项"
        var must = 0
        for (k in 0 until a.length()) {
            val nd = a.optJSONObject(k)
                ?: return false to "need[$k] 不是对象，应为 {\"n\":材料,\"must\":是否必需}"
            if (nd.optString("n").isBlank()) return false to "need[$k] 缺少 n"
            if (nd.optBoolean("must", false)) must++
        }
        if (must == 0)
            return false to "zx_medremote **没有任何必需材料**——" +
                "**全是可选的话，用户不知道缺哪一样会白跑一趟**"
        return true to ""
    }

    private fun housedeal(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("nodes")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_housedeal 缺少 nodes——**没有交易节点**"
        if (n > 15) return false to "zx_housedeal 最多 15 个节点，实际 $n 个"
        var escrow = 0
        for (k in 0 until n) {
            val nd = a.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"n\":节点,\"amt\":金额万,\"escrow\":是否监管,\"s\":0/1/2}"
            if (nd.optString("n").isBlank()) return false to "nodes[$k] 缺少 n（节点名）"
            // 节点无金额
            val amt = nd.optDouble("amt", Double.NaN)
            if (amt.isNaN())
                return false to "nodes[$k]「${nd.optString("n")}」**缺 amt（金额）**——" +
                    "**二手房每个节点都牵着钱**，不给金额用户不知道这一步押了多少"
            if (amt < 0) return false to "nodes[$k] 的 amt 不能为负"
            if (nd.optBoolean("escrow", false)) escrow++
            if (nd.optInt("s", 0) !in 0..2)
                return false to "nodes[$k].s 超出 0-2（0未做/1进行/2完成）"
        }
        // 无任何资金监管节点
        if (escrow == 0)
            return false to "zx_housedeal **没有任何节点走资金监管（escrow=true）**——" +
                "**首付直接打给卖方，出问题追不回来**。" +
                "至少要标出哪一步的钱在监管账户里"
        if (o.optString("buyer").isBlank() || o.optString("seller").isBlank())
            return false to "zx_housedeal **缺 buyer 或 seller（双方）**——" +
                "**交易节点要双方确认，不知道是谁就没法签**"
        return true to ""
    }

    private fun schoolup(o: JSONObject): Pair<Boolean, String> {
        // 缺报名窗口
        if (o.optString("win").isBlank())
            return false to "zx_schoolup **缺 win（报名窗口）**——" +
                "**升学是卡窗口的**，不知道窗口是哪几天，其余信息都没用"
        if (!o.has("leftH"))
            return false to "zx_schoolup **缺 leftH（窗口剩余小时）**——" +
                "**只写窗口区间，用户还要自己算还剩几天**"
        val lh = o.optInt("leftH", 0)
        // 过期未标后果
        if (lh <= 0 && o.optString("miss").isBlank())
            return false to "zx_schoolup **报名窗口已过但没写 miss（后果）**——" +
                "**错过就是晚一年**，不直说用户还在慢慢准备"
        val a = o.optJSONArray("need")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_schoolup 缺少 need（材料）"
        if (n > 20) return false to "zx_schoolup 最多 20 项，实际 $n 项"
        var must = 0
        for (k in 0 until n) {
            val nd = a.optJSONObject(k)
                ?: return false to "need[$k] 不是对象，应为 {\"n\":材料,\"got\":有/无,\"must\":是否必需}"
            if (nd.optString("n").isBlank()) return false to "need[$k] 缺少 n"
            if (!nd.has("got"))
                return false to "need[$k] 缺 got（有没有）——**不标有没有，用户不知道还差什么**"
            if (nd.optBoolean("must", false)) must++
        }
        // 无必需材料
        if (must == 0)
            return false to "zx_schoolup **没有任何必需材料（must=true）**——" +
                "**全部平权列出，用户不知道缺哪一样会直接报不上**"
        return true to ""
    }

    private fun carerota(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("slots")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_carerota 缺少 slots——**没有排班**"
        if (n > 31) return false to "zx_carerota 最多 31 天，实际 $n 天"
        var gap = 0
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "slots[$k] 不是对象，应为 {\"d\":日期,\"who\":责任人,\"hand\":交接是否完成}"
            if (s.optString("d").isBlank())
                return false to "slots[$k] 缺少 d（日期）——**没有日期就排不了班**"
            val w = s.optString("who", "")
            // 缺责任人
            if (w.isBlank()) {
                gap++
                if (gap == 1)
                    return false to "slots[$k]（${s.optString("d")}）**缺 who（责任人）**——" +
                        "**照护断一天，老人就真的没人管**"
            }
            // 缺交接标记
            if (!s.has("hand"))
                return false to "slots[$k] 缺 hand（交接是否完成）——" +
                    "**上一班说没异常，这一班一接班就出问题**。" +
                    "不记交接，出事就是互相甩锅"
        }
        return true to ""
    }

    private fun taxxborder(o: JSONObject): Pair<Boolean, String> {
        // 缺免征额
        val free = o.optDouble("free", Double.NaN)
        if (free.isNaN() || free < 0)
            return false to "zx_taxxborder **缺 free（免征额）**——" +
                "**跨境购的关键就是超没超免征额**，没有它算不出要不要交税"
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_taxxborder 缺少 items——**没有商品**"
        if (n > 20) return false to "zx_taxxborder 最多 20 项，实际 $n 项"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":商品,\"price\":单价,\"qty\":数量,\"rate\":税率}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（商品名）"
            val p = it.optDouble("price", Double.NaN)
            val q = it.optInt("qty", -1)
            if (p.isNaN() || q < 0)
                return false to "items[$k] 缺 price 或 qty——**算不出小计就没法算税**"
            if (p < 0) return false to "items[$k] 的 price 不能为负"
            // 缺税率
            val r = it.optDouble("rate", Double.NaN)
            if (r.isNaN() || r !in 0.0..1.0)
                return false to "items[$k]「${it.optString("n")}」**缺 rate 或税率不在 0-1**——" +
                    "**不同品类税率不同**，不按商品给税率，算出来是错的"
            // 小计与单价×数量对不上
            val sub = it.optDouble("sub", Double.NaN)
            if (!sub.isNaN() && abs(p * q - sub) >= 0.01)
                return false to "items[$k]「${it.optString("n")}」**sub=${"%.2f".format(sub)} " +
                    "与 price×qty=${"%.2f".format(p * q)} 对不上**——" +
                    "**对不上说明数量或单价记错了，税也就算错了**"
        }
        return true to ""
    }

    private fun rentreturn(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_rentreturn 缺少 items——**没有设备**"
        if (n > 25) return false to "zx_rentreturn 最多 25 件，实际 $n 件"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":设备,\"out\":借出时状态,\"back\":归还时状态,\"dmg\":损坏判定,\"ok\":承租方认不认}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（设备名）"
            // 缺借出时状态
            if (it.optString("out").isBlank())
                return false to "items[$k]「${it.optString("n")}」**缺 out（借出时状态）**——" +
                    "**没有借出时的快照，划伤是新的还是旧的根本说不清**"
            if (it.optString("back").isBlank())
                return false to "items[$k] 缺 back（归还时状态）——**两状态缺一就没法比**"
            // 状态变了无判定
            if (it.optString("out") != it.optString("back") && it.optString("dmg").isBlank())
                return false to "items[$k]「${it.optString("n")}」**状态从" +
                    "「${it.optString("out")}」变成「${it.optString("back")}」但没写 dmg（判定）**——" +
                    "**变了不判定，归还时说什么都是承租方吃亏**"
            if (!it.has("ok"))
                return false to "items[$k] 缺 ok（承租方认不认）——**判定要两边都认**"
        }
        if (o.optString("lessor").isBlank() || o.optString("lessee").isBlank())
            return false to "zx_rentreturn **缺 lessor 或 lessee（双方）**——" +
                "**设备归还的争议全在判定，判定得两边签字**"
        return true to ""
    }

    private fun dutyrota(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("slots")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_dutyrota 缺少 slots——**没有排班**"
        if (n > 31) return false to "zx_dutyrota 最多 31 天，实际 $n 天"
        var gap = 0
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "slots[$k] 不是对象，应为 {\"d\":日期,\"who\":值班人,\"alt\":替班人,\"swap\":换班是否双方同意}"
            if (s.optString("d").isBlank()) return false to "slots[$k] 缺少 d（日期）"
            // 排班有空档
            if (s.optString("who").isBlank()) {
                gap++
                if (gap == 1)
                    return false to "slots[$k]（${s.optString("d")}）**缺 who（值班人）**——" +
                        "**值班断一天，线上出问题没人接**"
            }
            val alt = s.optString("alt", "")
            // 换班缺双方同意
            if (alt.isNotBlank() && !s.has("swap"))
                return false to "slots[$k]（${s.optString("d")}）**有替班人但缺 swap（是否双方同意）**——" +
                    "**一方改了班另一方不知道，那天就没人**"
            if (alt.isNotBlank() && !s.optBoolean("swap", false))
                return false to "slots[$k]（${s.optString("d")}）**换班还没双方同意**——" +
                    "只记录谁值哪天，**改了班不同步，出事时两个人都不在**"
        }
        return true to ""
    }

    private fun annualaudit(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_annualaudit 缺少 items——**没有证照**"
        if (n > 20) return false to "zx_annualaudit 最多 20 项，实际 $n 项"
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"n\":证照,\"leftH\":年审剩余小时,\"got\":材料齐没齐,\"miss\":过期后果}"
            if (it.optString("n").isBlank()) return false to "items[$k] 缺少 n（证照名）"
            // 缺年审截止
            if (!it.has("leftH"))
                return false to "items[$k]「${it.optString("n")}」**缺 leftH（年审剩余小时）**——" +
                    "**年审是有截止的**，不给截止用户不知道还剩多久"
            // 过期未标后果
            if (it.optInt("leftH", 9999) <= 0 && it.optString("miss").isBlank())
                return false to "items[$k]「${it.optString("n")}」**已过期但没写 miss（后果）**——" +
                    "**证照过期不是补审就行，中间这段是无证状态**"
            // 缺材料标记
            if (!it.has("got"))
                return false to "items[$k] 缺 got（材料齐没齐）——" +
                    "**只看日期，临到期才发现材料还得先去别处开**"
        }
        return true to ""
    }

    private fun liability(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("parts")
        val n = a?.length() ?: 0
        if (a == null || n < 2)
            return false to "zx_liability 需要至少 2 方，实际 $n 方——**一方的责任不叫责任划分**"
        if (n > 6) return false to "zx_liability 最多 6 方，实际 $n 方"
        var sum = 0.0
        for (k in 0 until n) {
            val p = a.optJSONObject(k)
                ?: return false to "parts[$k] 不是对象，应为 {\"n\":一方,\"pct\":责任比例,\"why\":依据}"
            if (p.optString("n").isBlank()) return false to "parts[$k] 缺少 n（一方名称）"
            val pct = p.optDouble("pct", Double.NaN)
            if (pct.isNaN() || pct < 0 || pct > 100)
                return false to "parts[$k].pct 应为 0-100 之间的数"
            sum += pct
            // 缺依据
            if (p.optString("why").isBlank())
                return false to "parts[$k]「${p.optString("n")}」**缺 why（依据）**——" +
                    "**定比例不写为什么，对方一定不服**"
        }
        // 责任比例合计 ≠ 100
        if (abs(sum - 100.0) >= 0.01)
            return false to "zx_liability **责任比例合计 ${"%.1f".format(sum)}%，不是 100%**——" +
                "**差的那部分没人担，等于这个认定还没定完**"
        if (o.optString("pa").isBlank() || o.optString("pb").isBlank())
            return false to "zx_liability **缺 pa 或 pb（双方）**——" +
                "**认定的比例没人认，等于没认定**"
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_handoff" -> """
            {"type":"zx_handoff","n":"寄养交接","from":"主人","to":"寄养家庭","items":[
               {"n":"猫粮","v":"剩 1.2kg","ok":true},
               {"n":"猫砂","v":"刚换的新砂","ok":true},
               {"n":"体重","v":"4.8kg","ok":true},
               {"n":"疫苗本","v":"已交，年内有效","ok":false}],
             "diff":"疫苗本只有复印件"}"""".trimIndent()
        "zx_moveout" -> """
            {"type":"zx_moveout","n":"退租结算","landlord":"房东","tenant":"租客",
             "dep":6800,"ded":[
               {"n":"清洁费","amt":200,"why":"合同写明，固定","ok":true},
               {"n":"墙面修补","amt":800,"why":"3 处钉眼，入住时已存在","ok":false},
               {"n":"门锁更换","amt":150,"why":"钥匙少还一把","ok":true}]}""".trimIndent()
        "zx_vehiclecare" -> """
            {"type":"zx_vehiclecare","n":"维保","cur":42000,"items":[
               {"n":"机油机滤","km":45000,"mo":6,"need":true},
               {"n":"刹车油","km":60000,"mo":24,"need":true},
               {"n":"空气滤芯","km":50000,"mo":12,"need":false},
               {"n":"刹车片","km":43000,"mo":36,"need":true}]}""".trimIndent()
        "zx_medremote" -> """
            {"type":"zx_medremote","n":"异地备案","place":"上海","leftH":2160,
             "rate":0.70,"local":0.85,"miss":"过期后全自费，需回去手工报销",
             "need":[{"n":"备案表","must":true},{"n":"转诊单","must":true},
               {"n":"身份证","must":false}]}""".trimIndent()
        "zx_housedeal" -> """
            {"type":"zx_housedeal","n":"交易节点","buyer":"买方","seller":"卖方","nodes":[
               {"n":"定金","amt":10,"escrow":true,"s":2},
               {"n":"首付","amt":120,"escrow":true,"s":1},
               {"n":"过户","amt":0,"escrow":false,"s":0},
               {"n":"尾款","amt":170,"escrow":true,"s":0}]}""".trimIndent()
        "zx_schoolup" -> """
            {"type":"zx_schoolup","n":"升学","win":"05-06 至 05-12","leftH":168,
             "miss":"错过就是晚一年","need":[
               {"n":"户口本","got":true,"must":true},
               {"n":"房产证","got":true,"must":true},
               {"n":"预防接种证","got":false,"must":true},
               {"n":"照片","got":false,"must":false}]}""".trimIndent()
        "zx_carerota" -> """
            {"type":"zx_carerota","n":"照护排班","prevWho":"大姐","nextWho":"二姐","slots":[
               {"d":"05-13","who":"大姐","hand":true},
               {"d":"05-14","who":"大姐","hand":true},
               {"d":"05-15","who":"二姐","hand":false},
               {"d":"05-16","who":"二姐","hand":false}]}""".trimIndent()
        "zx_taxxborder" -> """
            {"type":"zx_taxxborder","n":"跨境税","free":5000,"buyer":"买家",
             "platform":"平台","items":[
               {"n":"奶粉","price":280,"qty":6,"rate":0.13,"sub":1680},
               {"n":"面霜","price":420,"qty":2,"rate":0.30,"sub":840},
               {"n":"维生素","price":160,"qty":4,"rate":0.13,"sub":640}]}""".trimIndent()
        "zx_rentreturn" -> """
            {"type":"zx_rentreturn","n":"设备归还","lessor":"出租方","lessee":"承租方","items":[
               {"n":"投影仪","out":"外壳无划痕","back":"底部有划痕","dmg":"新伤，扣 300","ok":false},
               {"n":"三脚架","out":"完好","back":"完好","ok":true},
               {"n":"镜头","out":"镀膜完好","back":"镀膜完好","ok":true}]}""".trimIndent()
        "zx_dutyrota" -> """
            {"type":"zx_dutyrota","n":"值班表","prevWho":"张三","nextWho":"李四","slots":[
               {"d":"05-13","who":"张三"}",
               {"d":"05-14","who":"李四","alt":"王五","swap":false},
               {"d":"05-15","who":"王五","alt":"张三","swap":true},
               {"d":"05-16","who":"李四"}"]}""".trimIndent()
        "zx_annualaudit" -> """
            {"type":"zx_annualaudit","n":"证照年审","org":"发证机关","holder":"我司","items":[
               {"n":"营业执照","leftH":1440,"got":true},
               {"n":"食品经营许可","leftH":360,"got":false},
               {"n":"卫生许可","leftH":-48,"got":false,"miss":"过期期间属无证经营"}"]}""".trimIndent()
        "zx_liability" -> """
            {"type":"zx_liability","n":"责任认定","pa":"甲方","pb":"乙方","parts":[
               {"n":"甲方","pct":30,"why":"变道未打灯"}",
               {"n":"乙方","pct":70,"why":"超速且未保持车距"}"]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_handoff" -> "items[{n,**v 非空（缺会被拦）**,ok}] ≤30 + **from/to 非空（缺会被拦）** + diff?"
        "zx_moveout" -> "dep>0 + ded[{n,amt>0,**why 非空（缺会被拦）**,ok}] ≤20 + **landlord/tenant 非空**；**扣款超押金会被拦**"
        "zx_vehiclecare" -> "**cur（缺会被拦）** + items[{n,**km 与 mo 至少一个（缺会被拦）**,**need（缺会被拦）**}] ≤25；**无必做项目会被拦**"
        "zx_medremote" -> "**place 非空（缺会被拦）** + **leftH（缺会被拦）** + **rate/local 均在 0-1（缺会被拦）** + need[{n,must?}] ≤15；**过期缺 miss 会被拦**；**无必需材料会被拦**"
        "zx_housedeal" -> "nodes[{n,**amt（缺会被拦）**,escrow?,s?}] ≤15 + **buyer/seller 非空**；**无任何 escrow=true 会被拦**"
        "zx_schoolup" -> "**win 非空（缺会被拦）** + **leftH（缺会被拦）** + need[{n,**got**,must?}] ≤20；**过期缺 miss 会被拦**；**无必需材料会被拦**"
        "zx_carerota" -> "slots[{d,**who 非空（缺会被拦）**,**hand（缺会被拦）**}] ≤31"
        "zx_taxxborder" -> "**free（缺会被拦）** + items[{n,price,qty,**rate∈0-1（缺会被拦）**,sub?}] ≤20；**sub 与 price×qty 对不上会被拦**"
        "zx_rentreturn" -> "items[{n,**out 非空（缺会被拦）**,back,**状态变了时 dmg 非空（缺会被拦）**,ok}] ≤25 + **lessor/lessee 非空**"
        "zx_dutyrota" -> "slots[{d,**who 非空（缺会被拦）**,alt?,**有 alt 时 swap 必须为 true（否则会被拦）**}] ≤31"
        "zx_annualaudit" -> "items[{n,**leftH（缺会被拦）**,**got（缺会被拦）**,miss?}] ≤20；**过期缺 miss 会被拦**"
        "zx_liability" -> "parts[{n,**pct∈0-100**,**why 非空（缺会被拦）**}] ≥2 ≤6 + **pa/pb 非空**；**合计 ≠100 会被拦**"
        else -> "未注册组件：$component"
    }
}
