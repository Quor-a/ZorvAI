package com.zorv.plugin.zxwidget

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第三十四批（日常界面）的校验 + 示例 + 规格。
 *
 * 这批查的是**「操作了但得不到结果」**。
 *
 * 前十一档一直在查"读不读得出信息"：
 *   画不出来 → 统计前提不成立 → 区间前提不成立 →
 *   因果前提无从判断 → 看起来确定了其实没那么确定 →
 *   推不出该做什么 → 记了下次用不上 → 落不到谁该做什么 →
 *   推演本身不可信 → 决策前提在过程中会变 →
 *   信息新不新、能不能信
 *
 * 第三十三批开始补"非分析"的领域，但那批多是**看**：
 * 天气是看、新闻是看、代码是看、工具调用记录也是看。
 *
 * 而这批是**操作一个日常工具**——导航、播放器、相册、文件管理、
 * 购物车、拨号盘。评价标准因此从"读不读得出"换成"**能不能用**"：
 *
 *   播放器进度条画出来了但不能拖     → 那是个装饰
 *   相册 9 张图摆出来了但不能翻      → 那是九宫格贴图
 *   文件管理器列了目录但点不进去      → 那是张树形图
 *   购物车能选规格但缺货的也能选      → 那是把错误留到付款时才告诉用户
 *   拨号盘按了不显示号码             → 用户不知道按没按上
 *
 * 这类问题不崩、不报错，只是让用户面对一个**看起来能交互、
 * 实际点了没反应**的界面——比崩溃更糟，因为模型会以为下发成功了。
 *
 * 具体要拦的（与 ZxRender34 注释一一对应）：
 *   mapnav    步骤距离和 ≠ 总距离 / 无转向点 / turn 越界
 *   transit   无步行段 / 某段到达早于出发 / min ≤ 0
 *   music     时长 ≤ 0（进度条除零）/ 播放列表为空
 *   album     无图 / 初始索引越界
 *   chatlog   无消息
 *   contact   无联系方式 / 方式类型非法
 *   agenda    时间块重叠 / e ≤ s / 超出 0-1440
 *   filemgr   无节点 / 层级 > 6 / 父路径不存在
 *   setup     无步骤 / 前置指向不存在的步骤 / 前置是自己
 *   shop      价格非正 / **选中的规格缺货** / 无规格
 *   order     无节点 / 当前步越界
 *   wallet    流水合计 ≠ 余额
 *   remote    按键无 action
 *   dialpad   号码含非数字
 *   tasklist  任务依赖自己 / 依赖指向不存在
 */
object ZxDsl34 {

    val ALL = listOf(
        "zx_mapnav", "zx_transit",
        "zx_music", "zx_album",
        "zx_chatlog", "zx_contact",
        "zx_agenda", "zx_tasklist", "zx_filemgr",
        "zx_shop", "zx_order", "zx_wallet",
        "zx_setup", "zx_remote", "zx_dialpad"
    )

    private val CONTACT_TYPES = setOf("tel", "mail", "addr")

    fun validate(component: String, root: JSONObject): Pair<Boolean, String> = when (component) {
        "zx_mapnav"   -> mapnav(root)
        "zx_transit"  -> transit(root)
        "zx_music"    -> music(root)
        "zx_album"    -> album(root)
        "zx_chatlog"  -> chatlog(root)
        "zx_contact"  -> contact(root)
        "zx_agenda"   -> agenda(root)
        "zx_filemgr"  -> filemgr(root)
        "zx_setup"    -> setup(root)
        "zx_shop"     -> shop(root)
        "zx_order"    -> order(root)
        "zx_wallet"   -> wallet(root)
        "zx_remote"   -> remote(root)
        "zx_dialpad"  -> dialpad(root)
        "zx_tasklist" -> tasklist(root)
        else -> false to "未注册组件：$component"
    }

    private fun mapnav(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("steps")
            ?: return false to "zx_mapnav 缺少 steps（导航步骤）"
        val n = a.length()
        if (n == 0) return false to "zx_mapnav 的 steps 为空——**没有步骤的导航不知道往哪走**"
        if (n > 20) return false to "zx_mapnav 最多 20 步，实际 $n 步——" +
            "超过 20 步的路线，用户看第 15 步时已经不在那儿了"
        var sum = 0.0
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "steps[$k] 不是对象，应为 {\"d\":距离米,\"t\":说明,\"turn\":0-3}"
            val d = s.optDouble("d", Double.NaN)
            if (d.isNaN()) return false to "steps[$k] 缺少 d（距离，米）"
            if (d <= 0) return false to "steps[$k].d=$d 必须为正——" +
                "**距离为 0 或负的步骤会画出一个不前进的点**"
            val turn = s.optInt("turn", 0)
            if (turn !in 0..3) return false to "steps[$k].turn=$turn 超出 0-3（0直1左2右3掉头）"
            if (s.optString("t").isBlank()) return false to "steps[$k] 缺少 t（说明）"
            sum += d
        }
        val total = o.optDouble("total", Double.NaN)
        if (!total.isNaN()) {
            // 步骤距离和 ≠ 总距离
            if (abs(sum - total) > max(1.0, total * 0.01))
                return false to "zx_mapnav 各步距离之和 ${"%.0f".format(sum)}m " +
                    "与 total ${"%.0f".format(total)}m **差 ${"%.0f".format(abs(sum - total))}m**——" +
                    "用户把各步加起来发现对不上，会以为算错了"
        }
        return true to ""
    }

    private fun transit(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("legs") ?: return false to "zx_transit 缺少 legs（行程段）"
        val n = a.length()
        if (n == 0) return false to "zx_transit 的 legs 为空"
        if (n > 10) return false to "zx_transit 最多 10 段，实际 $n 段"
        val modes = setOf("walk", "bus", "metro")
        var walk = 0
        for (k in 0 until n) {
            val l = a.optJSONObject(k)
                ?: return false to "legs[$k] 不是对象，应为 {\"m\":walk/bus/metro,\"l\":线路,\"from\",\"to\",\"min\"}"
            val m = l.optString("m")
            if (m !in modes) return false to "legs[$k].m=\"$m\" 非法（只认 ${modes.joinToString(" / ")}）"
            if (m == "walk") walk++
            val min = l.optInt("min", -1)
            if (min <= 0) return false to "legs[$k].min=$min 必须为正——" +
                "**时长为 0 或负的段会让总时长偏短**，用户按这个出门会迟到"
            val dep = l.optString("dep", "")
            val arr = l.optString("arr", "")
            if (dep.isNotBlank() && arr.isNotBlank()) {
                // 到达早于出发
                val dm = hm(dep); val am = hm(arr)
                if (dm >= 0 && am >= 0 && am < dm)
                    return false to "legs[$k] 的到达 $arr **早于**出发 $dep——" +
                        "时间倒挂，用户在看一个不可能发生的行程"
            }
            if (l.optString("from").isBlank() || l.optString("to").isBlank())
                return false to "legs[$k] 缺少 from 或 to——**不知道从哪上车、在哪下车**，这方案没法用"
        }
        // 无步行段
        if (walk == 0)
            return false to "zx_transit **没有步行段**——地铁 A 口直接到地铁 B 口是不可能的，" +
                "换乘必然要走路。缺了它，总时长 ${a.length()} 段合计偏短"
        return true to ""
    }

    private fun hm(s: String): Int {
        val p = s.split(":")
        if (p.size != 2) return -1
        val h = p[0].trim().toIntOrNull() ?: return -1
        val m = p[1].trim().toIntOrNull() ?: return -1
        return h * 60 + m
    }

    private fun music(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("tracks")
        val n = a?.length() ?: 0
        // 播放列表为空
        if (a == null || n == 0)
            return false to "zx_music 缺少 tracks——**没有歌的播放器是空的壳**。" +
                "播放列表为空时进度条、上下曲、循环模式全都没意义"
        if (n > 30) return false to "zx_music 最多 30 首，实际 $n 首"
        for (k in 0 until n) {
            val t = a.optJSONObject(k)
                ?: return false to "tracks[$k] 不是对象，应为 {\"t\":曲名,\"a\":艺人,\"d\":时长秒}"
            if (t.optString("t").isBlank()) return false to "tracks[$k] 缺少 t（曲名）"
            val d = t.optInt("d", 0)
            // 时长 ≤ 0：进度条除零
            if (d <= 0)
                return false to "tracks[$k].d=$d 必须为正——**时长为 0 会让进度条除零**，" +
                    "画出来是一条不动的线，用户会以为卡住了"
            if (d > 7200) return false to "tracks[$k].d=$d 秒超过 2 小时——多半是单位错了（传成了毫秒）"
        }
        val cur = o.optInt("cur", 0)
        if (cur < 0 || cur >= n)
            return false to "zx_music 的 cur=$cur 超出 0-${n - 1}——当前曲目不存在"
        val loop = o.optString("loop", "off")
        if (loop !in setOf("off", "all", "one"))
            return false to "zx_music 的 loop=\"$loop\" 非法（只认 off / all / one）"
        return true to ""
    }

    private fun album(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("imgs")
        val n = a?.length() ?: 0
        // 无图
        if (a == null || n == 0)
            return false to "zx_album 缺少 imgs——**空的相册没有任何意义**"
        if (n > 24) return false to "zx_album 最多 24 张，实际 $n 张"
        for (k in 0 until n)
            if (a.optString(k).isBlank()) return false to "imgs[$k] 为空"
        // 初始索引越界
        val cur = o.optInt("cur", 0)
        if (cur < 0 || cur >= n)
            return false to "zx_album 的 cur=$cur 超出 0-${n - 1}——" +
                "**初始索引越界会打开一个不存在的图**，查看器是空的"
        return true to ""
    }

    private fun chatlog(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("msgs")
        val n = a?.length() ?: 0
        // 无消息
        if (a == null || n == 0)
            return false to "zx_chatlog 缺少 msgs——**空的聊天记录没有任何内容可回顾**"
        if (n > 40) return false to "zx_chatlog 最多 40 条，实际 $n 条"
        for (k in 0 until n) {
            val m = a.optJSONObject(k)
                ?: return false to "msgs[$k] 不是对象，应为 {\"me\":是否我说,\"t\":内容,\"who\":对方名,\"ts\":时间,\"st\":0-2}"
            if (m.optString("t").isBlank()) return false to "msgs[$k] 缺少 t（内容）"
            val st = m.optInt("st", 0)
            if (st !in 0..2) return false to "msgs[$k].st=$st 超出 0-2（0未读1已读2发送中）"
            // 对方的消息必须有 who，否则分不清是谁说的
            if (!m.optBoolean("me", false) && m.optString("who").isBlank())
                return false to "msgs[$k] 是对方的消息但**没有 who**——" +
                    "**聊天记录里分不清哪句是谁说的，这个记录就没用了**"
        }
        return true to ""
    }

    private fun contact(o: JSONObject): Pair<Boolean, String> {
        val name = o.optString("name")
        if (name.isBlank()) return false to "zx_contact 缺少 name（姓名）"
        val a = o.optJSONArray("ways")
        val n = a?.length() ?: 0
        // 无联系方式
        if (a == null || n == 0)
            return false to "zx_contact 缺少 ways——**没有任何联系方式，那不是联系人卡，那是名字**"
        if (n > 6) return false to "zx_contact 最多 6 种联系方式，实际 $n 种"
        for (k in 0 until n) {
            val w = a.optJSONObject(k)
                ?: return false to "ways[$k] 不是对象，应为 {\"t\":tel/mail/addr,\"v\":值}"
            val t = w.optString("t")
            if (t !in CONTACT_TYPES)
                return false to "ways[$k].t=\"$t\" 非法（只认 ${CONTACT_TYPES.joinToString(" / ")}）——" +
                    "**类型认不出来就不知道点了该干什么**"
            if (w.optString("v").isBlank())
                return false to "ways[$k] 缺少 v（值）——**空号码点了没反应**，等于让用户自己去别处查"
        }
        return true to ""
    }

    private fun agenda(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("blocks")
            ?: return false to "zx_agenda 缺少 blocks（日程块）"
        val n = a.length()
        if (n == 0) return false to "zx_agenda 的 blocks 为空——**没有日程块的日程表是空的**"
        if (n > 16) return false to "zx_agenda 最多 16 块，实际 $n 块"
        val se = ArrayList<Pair<Int, Int>>()
        for (k in 0 until n) {
            val b = a.optJSONObject(k)
                ?: return false to "blocks[$k] 不是对象，应为 {\"t\":标题,\"s\":开始分钟,\"e\":结束分钟,\"c\":色号}"
            if (b.optString("t").isBlank()) return false to "blocks[$k] 缺少 t（标题）"
            val s = b.optInt("s", -1); val e = b.optInt("e", -1)
            if (s < 0 || s > 1440) return false to "blocks[$k].s=$s 超出 0-1440（一天只有 1440 分钟）"
            if (e < 0 || e > 1440) return false to "blocks[$k].e=$e 超出 0-1440"
            // e ≤ s：块高度为 0 或负
            if (e <= s) return false to "blocks[$k] 的 e=$e **不大于** s=$s——" +
                "**时间块高度为 0 或负，画出来是一条看不见的线**，用户以为没排上"
            se.add(s to e)
        }
        // 时间块重叠
        for (i in 0 until n) for (j in i + 1 until n) {
            val (s1, e1) = se[i]; val (s2, e2) = se[j]
            if (maxOf(s1, s2) < minOf(e1, e2))
                return false to "zx_agenda 第 ${i + 1} 块（${minute(s1)}-${minute(e1)}）与" +
                    "第 ${j + 1} 块（${minute(s2)}-${minute(e2)}）**时间重叠**——" +
                    "**日程表看不见冲突，就等于没有检测冲突**。用户按这个表出门，" +
                    "到点了才发现两个会撞车，那时已经晚了"
        }
        return true to ""
    }
    private fun minute(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    private fun filemgr(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("nodes")
        val n = a?.length() ?: 0
        // 无节点
        if (a == null || n == 0)
            return false to "zx_filemgr 缺少 nodes——**没有任何节点，空的文件管理器**"
        if (n > 120) return false to "zx_filemgr 最多 120 个节点，实际 $n 个"
        val paths = HashSet<String>()
        for (k in 0 until n) {
            val nd = a.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"n\":名,\"p\":父路径,\"d\":是否目录,\"sz\":大小}"
            val nm = nd.optString("n")
            if (nm.isBlank()) return false to "nodes[$k] 缺少 n（名称）"
            val p = nd.optString("p")
            if (p.isBlank()) return false to "nodes[$k] 缺少 p（父路径）——" +
                "**不知道父路径就挂不到树上**，这个节点永远显示不出来"
            val full = (if (p == "/") "/$nm" else "$p/$nm")
            if (!paths.add(full))
                return false to "nodes[$k] 的路径「$full」重复——同目录下不能有两个同名"
            // 层级过深
            val depth = p.count { it == '/' } + 1
            if (depth > 6)
                return false to "nodes[$k] 的路径「$full」层级 $depth **超过 6 层**——" +
                    "手机上点七次才能看到一个文件，不如换个组织方式"
        }
        // 根必须有子项，否则一进来就是空目录
        if (paths.none { it.count { c -> c == '/' } == 1 })
            return false to "zx_filemgr **根目录下没有任何节点**——" +
                "一进来就是空的，用户会以为加载失败"
        return true to ""
    }

    private fun setup(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("steps")
        val n = a?.length() ?: 0
        // 无步骤
        if (a == null || n == 0)
            return false to "zx_setup 缺少 steps——**没有步骤，那不是向导**"
        if (n > 10) return false to "zx_setup 最多 10 步，实际 $n 步"
        val titles = ArrayList<String>()
        val reqs = ArrayList<String>()
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "steps[$k] 不是对象，应为 {\"t\":标题,\"req\":前置标题,\"hint\":提示}"
            val t = s.optString("t")
            if (t.isBlank()) return false to "steps[$k] 缺少 t（标题）"
            titles.add(t)
            reqs.add(s.optString("req", ""))
        }
        for (k in 0 until n) {
            val r = reqs[k]
            if (r.isBlank()) continue
            // 前置是自己
            if (r == titles[k])
                return false to "steps[$k]「${titles[k]}」的**前置是自己**——" +
                    "**永远满足不了，这一步永远过不去**"
            // 前置指向不存在
            if (r !in titles)
                return false to "steps[$k]「${titles[k]}」的前置「$r」**不存在**——" +
                    "**前置指向空处，这一步永远卡住**，用户不知道该先做什么"
            // 前置在后面
            val ri = titles.indexOf(r)
            if (ri > k)
                return false to "steps[$k]「${titles[k]}」的前置「$r」在第 ${ri + 1} 步（在它后面）——" +
                    "**前置排在后面，用户只能来回跳**，那不叫流程"
        }
        return true to ""
    }

    private fun shop(o: JSONObject): Pair<Boolean, String> {
        val name = o.optString("name")
        if (name.isBlank()) return false to "zx_shop 缺少 name（商品名）"
        val price = o.optDouble("price", Double.NaN)
        if (price.isNaN()) return false to "zx_shop 缺少 price（价格）"
        // 价格非正
        if (price <= 0)
            return false to "zx_shop 的 price=$price 必须为正——**价格非正那不是商品，" +
                "那是赠品或数据错了**"
        val a = o.optJSONArray("specs")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_shop 缺少 specs——**没有规格的商品卡不知道买的是什么**"
        if (n > 8) return false to "zx_shop 最多 8 个规格，实际 $n 个"
        var anyStock = false
        var curSel = o.optInt("sel", -1)
        for (k in 0 until n) {
            val s = a.optJSONObject(k)
                ?: return false to "specs[$k] 不是对象，应为 {\"n\":规格名,\"stock\":库存,\"add\":差价}"
            if (s.optString("n").isBlank()) return false to "specs[$k] 缺少 n（规格名）"
            val st = s.optInt("stock", 0)
            if (st < 0) return false to "specs[$k].stock=$st 不能为负"
            if (st > 0) anyStock = true
        }
        if (!anyStock)
            return false to "zx_shop **所有规格都缺货**——全缺货的商品不该展示成可买的样子"
        // 选中的规格缺货
        if (curSel < 0) curSel = 0
        if (curSel >= 0 && curSel < n) {
            val st = a.optJSONObject(curSel)?.optInt("stock", 0) ?: 0
            if (st <= 0)
                return false to "zx_shop 默认选中的规格「${a.optJSONObject(curSel)?.optString("n")}」**库存为 0**——" +
                    "**缺货的规格不能选**。选了等于把错误留到付款时才告诉用户，" +
                    "用户的感受是「这 App 有 bug」，而不是「我选错了」"
        }
        return true to ""
    }

    private fun order(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("nodes")
        val n = a?.length() ?: 0
        // 无节点
        if (a == null || n == 0)
            return false to "zx_order 缺少 nodes（物流节点）——**没有节点的订单看不到货到哪了**"
        if (n > 12) return false to "zx_order 最多 12 个节点，实际 $n 个"
        for (k in 0 until n) {
            val nd = a.optJSONObject(k)
                ?: return false to "nodes[$k] 不是对象，应为 {\"t\":节点名,\"ts\":时间}"
            if (nd.optString("t").isBlank()) return false to "nodes[$k] 缺少 t（节点名）"
        }
        val cur = o.optInt("cur", n - 1)
        if (cur < 0 || cur >= n)
            return false to "zx_order 的 cur=$cur 超出 0-${n - 1}——" +
                "**当前状态指向不存在的节点，用户看不出货到哪了**"
        return true to ""
    }

    private fun wallet(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("items")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_wallet 缺少 items——**没有流水的钱包不知道钱花哪了**"
        if (n > 40) return false to "zx_wallet 最多 40 笔，实际 $n 笔"
        var sum = 0.0
        for (k in 0 until n) {
            val it = a.optJSONObject(k)
                ?: return false to "items[$k] 不是对象，应为 {\"t\":摘要,\"v\":金额(负为支出),\"c\":分类}"
            if (it.optString("t").isBlank()) return false to "items[$k] 缺少 t（摘要）"
            val v = it.optDouble("v", Double.NaN)
            if (v.isNaN()) return false to "items[$k] 缺少 v（金额）"
            if (v == 0.0) return false to "items[$k].v=0——**金额为 0 的流水没有信息**"
            sum += v
        }
        val bal = o.optDouble("bal", Double.NaN)
        if (bal.isNaN()) return false to "zx_wallet 缺少 bal（余额）"
        // 流水合计 ≠ 余额
        if (abs(sum - bal) > 0.5)
            return false to "zx_wallet 流水合计 ${"%.2f".format(sum)} **≠ 余额** " +
                "${"%.2f".format(bal)}，差 ${"%.2f".format(abs(sum - bal))}——" +
                "**账单对不上账，其余所有数字都不可信**。用户会怀疑每一笔是不是记错了，" +
                "而不只是怀疑总额"
        return true to ""
    }

    private fun remote(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("keys")
        val n = a?.length() ?: 0
        if (a == null || n == 0) return true to ""   // 只有方向键也算一个遥控器
        if (n > 12) return false to "zx_remote 最多 12 个自定义键，实际 $n 个"
        for (k in 0 until n) {
            val key = a.optJSONObject(k)
                ?: return false to "keys[$k] 不是对象，应为 {\"l\":显示,\"a\":action,\"s\":占格}"
            if (key.optString("l").isBlank()) return false to "keys[$k] 缺少 l（显示文字）"
            // 按键无 action
            if (key.optString("a").isBlank())
                return false to "keys[$k]「${key.optString("l")}」**没有 action**——" +
                    "**按了不知道发给谁，那按键是画上去的**。" +
                    "遥控器上没有反应的键，比没有这个键更让人困惑——" +
                    "用户会反复按，以为是自己没按准"
        }
        return true to ""
    }

    private fun dialpad(o: JSONObject): Pair<Boolean, String> {
        val num = o.optString("num", "")
        if (num.isEmpty()) return true to ""   // 空号码是合法初始态
        if (num.length > 20)
            return false to "zx_dialpad 的 num 有 ${num.length} 位，超过 20 位"
        // 号码含非数字
        val bad = num.firstOrNull { !it.isDigit() && it != '*' && it != '#' }
        if (bad != null)
            return false to "zx_dialpad 的 num=\"$num\" 含**非法字符「$bad」**——" +
                "号码只能由数字和 * # 组成，否则拨不出去"
        return true to ""
    }

    private fun tasklist(o: JSONObject): Pair<Boolean, String> {
        val a = o.optJSONArray("tasks")
        val n = a?.length() ?: 0
        if (a == null || n == 0)
            return false to "zx_tasklist 缺少 tasks——**没有任务的清单是空的**"
        if (n > 20) return false to "zx_tasklist 最多 20 个任务，实际 $n 个"
        val titles = ArrayList<String>()
        val deps = ArrayList<String>()
        for (k in 0 until n) {
            val t = a.optJSONObject(k)
                ?: return false to "tasks[$k] 不是对象，应为 {\"t\":标题,\"done\":,\"dep\":依赖,\"sub\":[...],\"pri\":1-3}"
            val ti = t.optString("t")
            if (ti.isBlank()) return false to "tasks[$k] 缺少 t（标题）"
            titles.add(ti)
            deps.add(t.optString("dep", ""))
            val pri = t.optInt("pri", 2)
            if (pri !in 1..3) return false to "tasks[$k].pri=$pri 超出 1-3（1高2中3低）"
        }
        for (k in 0 until n) {
            val d = deps[k]
            if (d.isBlank()) continue
            // 依赖自己
            if (d == titles[k])
                return false to "tasks[$k]「${titles[k]}」**依赖自己**——" +
                    "**永远完成不了，这个任务永远锁着**"
            if (d !in titles)
                return false to "tasks[$k]「${titles[k]}」的依赖「$d」**不存在**——" +
                    "**依赖指向空处，这个任务永远锁着**，用户不知道要先做什么"
        }
        return true to ""
    }

    /* ── 示例 DSL ────────────────────────────────────────── */
    fun sample(component: String): String = when (component) {
        "zx_mapnav" -> """
            {"type":"zx_mapnav","n":"去公司","total":3400,"eta":18,"steps":[
               {"d":300,"t":"沿当前路直行","turn":0},
               {"d":800,"t":"到环岛右转","turn":2},
               {"d":1200,"t":"进入快速路","turn":0},
               {"d":900,"t":"下匝道左转","turn":1},
               {"d":200,"t":"到达目的地","turn":0}]}""".trimIndent()
        "zx_transit" -> """
            {"type":"zx_transit","n":"去机场","legs":[
               {"m":"walk","l":"步行","from":"家","to":"地铁 A 口","min":6},
               {"m":"metro","l":"2 号线","from":"A 口","to":"换乘站","min":22,"dep":"08:10","arr":"08:32"},
               {"m":"walk","l":"换乘","from":"2 号线","to":"机场线","min":5},
               {"m":"metro","l":"机场线","from":"换乘站","to":"机场","min":18,"dep":"08:38","arr":"08:56"}]}""".trimIndent()
        "zx_music" -> """
            {"type":"zx_music","cur":0,"loop":"all","tracks":[
               {"t":"夜航","a":"某某","d":214},
               {"t":"晨光","a":"某某","d":187},
               {"t":"中途","a":"另一人","d":253}]}""".trimIndent()
        "zx_album" -> """
            {"type":"zx_album","n":"旅行","cur":0,
             "imgs":["a.jpg","b.jpg","c.jpg","d.jpg","e.jpg","f.jpg"]}""".trimIndent()
        "zx_chatlog" -> """
            {"type":"zx_chatlog","n":"与客服","msgs":[
               {"me":false,"t":"您好，请问什么问题","who":"客服","ts":1760000000000,"st":1},
               {"me":true,"t":"我的订单还没发货","ts":1760000060000,"st":1},
               {"me":false,"t":"帮您查一下","who":"客服","ts":1760000120000,"st":0}]}""".trimIndent()
        "zx_contact" -> """
            {"type":"zx_contact","name":"张三","role":"技术对接","acts":["打电话","发消息"],
             "ways":[{"t":"tel","v":"13800000000"},{"t":"mail","v":"z@example.com"}]}""".trimIndent()
        "zx_agenda" -> """
            {"type":"zx_agenda","n":"今日","blocks":[
               {"t":"晨会","s":540,"e":570,"c":0},
               {"t":"需求评审","s":600,"e":720,"c":1},
               {"t":"一对一","s":840,"e":900,"c":4}]}""".trimIndent()
        "zx_filemgr" -> """
            {"type":"zx_filemgr","n":"文件","root":"/","nodes":[
               {"n":"src","p":"/","d":true},{"n":"doc","p":"/","d":true},
               {"n":"README.md","p":"/","d":false,"sz":2048},
               {"n":"Main.kt","p":"/src","d":false,"sz":5120}]}""".trimIndent()
        "zx_setup" -> """
            {"type":"zx_setup","n":"首次配置","steps":[
               {"t":"连接设备","hint":"打开蓝牙"}",
               {"t":"授权权限","req":"连接设备"}",
               {"t":"完成","req":"授权权限"}"]}""".trimIndent()
        "zx_shop" -> """
            {"type":"zx_shop","name":"无线耳机","price":299,
             "specs":[{"n":"黑色","stock":12,"add":0},
                      {"n":"白色","stock":0,"add":10},
                      {"n":"蓝色","stock":5,"add":0}]}""".trimIndent()
        "zx_order" -> """
            {"type":"zx_order","id":"A20261011","total":299,"cur":2,"nodes":[
               {"t":"已下单","ts":"10-11 09:20"},
               {"t":"已付款","ts":"10-11 09:21"},
               {"t":"已发货","ts":"10-11 15:40"},
               {"t":"运输中","ts":""},{"t":"待签收","ts":""}]}""".trimIndent()
        "zx_wallet" -> """
            {"type":"zx_wallet","n":"本月","bal":1240,"items":[
               {"t":"工资","v":8000,"c":""},{"t":"房租","v":-3200,"c":"居住"}",
               {"t":"餐饮","v":-1860,"c":"饮食"}",{"t":"交通","v":-400,"c":"出行"}",
               {"t":"购物","v":-1300,"c":"购物"}"]}""".trimIndent()
        "zx_remote" -> """
            {"type":"zx_remote","n":"客厅空调","keys":[
               {"l":"开关","a":"power"},{"l":"模式","a":"mode"},
               {"l":"定时","a":"timer"}]}""".trimIndent()
        "zx_dialpad" -> """
            {"type":"zx_dialpad","n":"拨号","num":"1380000"}""".trimIndent()
        "zx_tasklist" -> """
            {"type":"zx_tasklist","n":"上线前","tasks":[
               {"t":"跑通测试","done":true,"pri":1,"sub":["单测","集成"]},
               {"t":"写发布说明","dep":"跑通测试","pri":2},
               {"t":"提交审核","dep":"写发布说明","pri":3}]}""".trimIndent()
        else -> "{}"
    }

    fun spec(component: String): String = when (component) {
        "zx_mapnav" -> "steps[{d>0,t,turn∈0-3}] ≤20 + total?；**各步距离和 ≠ total 会被拦**"
        "zx_transit" -> "legs[{m∈walk/bus/metro,l,from,to,min>0,dep?,arr?}] ≤10；**无步行段会被拦**；**arr 早于 dep 会被拦**"
        "zx_music" -> "tracks[{t,a,**d>0（≤0 会被拦，进度条除零）**}] ≤30 + cur? + loop?∈off/all/one；**列表为空会被拦**"
        "zx_album" -> "imgs 非空 ≤24 + cur? 在范围内；**无图会被拦**；**cur 越界会被拦**"
        "zx_chatlog" -> "msgs[{me,t,who（对方必填）,ts?,st∈0-2}] ≤40；**无消息会被拦**"
        "zx_contact" -> "name + **ways 非空（缺会被拦）**[{t∈tel/mail/addr,v}] ≤6 + acts?"
        "zx_agenda" -> "blocks[{t,**s<e**,0-1440,c?}] ≤16；**e≤s 会被拦**；**时间块重叠会被拦**"
        "zx_filemgr" -> "nodes[{n,p,d,sz?}] ≤120；**无节点会被拦**；**层级 >6 会被拦**；**根无子项会被拦**"
        "zx_setup" -> "steps[{t,req?,hint?}] ≤10；**无步骤会被拦**；**前置指向不存在/是自己/排在后面会被拦**"
        "zx_shop" -> "name + **price>0（非正会被拦）** + specs[{n,stock,add?}] ≤8；**选中规格缺货会被拦**；**全缺货会被拦**"
        "zx_order" -> "nodes[{t,ts?}] ≤12 + cur?；**无节点会被拦**；**cur 越界会被拦**"
        "zx_wallet" -> "items[{t,**v≠0**,c?}] ≤40 + bal；**流水合计 ≠ 余额会被拦**"
        "zx_remote" -> "keys?[{l,**a 非空（缺会被拦）**,s?}] ≤12 + n?"
        "zx_dialpad" -> "num?（仅数字与 * #，**含其他字符会被拦**）≤20 位 + n?"
        "zx_tasklist" -> "tasks[{t,done?,dep?,sub?,pri∈1-3}] ≤20；**依赖自己/指向不存在会被拦**"
        else -> "未注册组件：$component"
    }
}
