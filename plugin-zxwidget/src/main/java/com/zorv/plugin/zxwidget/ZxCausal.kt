package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 因果推断界面 —— 把"因为所以"背后没说出口的假设，摆到界面上。
 *
 * **为什么需要**
 *
 * 第二十六批做了 12 个因果组件，每个都把**一个**前提变成了
 * 界面上的东西：DID 画平行趋势、RDD 画断点跳跃、
 * IV 给一阶段 F、PSM 看共同支撑、敏感性给临界 Γ。
 *
 * 但这里还缺一层：**一个因果结论背后往往有好几条假设**，
 * 而组件一次只让你看见一条。
 *
 * 真实对话里模型说的是：
 *
 *   "留下降了，是因为改了首页。"
 *
 * 这句话背后至少压着五条假设：
 *
 *   1. 改版确实发生在留存下降之前（时序）
 *   2. 改版期间没有别的事同时发生（无并发干预）
 *   3. 两组用户本来就差不多（可比性）
 *   4. 没有既影响改版又影响留存的第三因素（无未测混杂）
 *   5. 留存的测量方式前后一致（测量不变性）
 *
 * 这些假设在文字里是**隐形的**——模型不会主动列，
 * 用户不会主动问，于是"因为所以"就滑过去了。
 *
 * 所以要做成一张清单：每条假设显式列出，并且标明
 *
 *   · **能不能验证**（能用数据查 / 只能靠假定）
 *   · **违反了会怎样**（结论偏哪边、还是彻底无效）
 *   · **现在什么状态**（已验证 / 存疑 / 未查）
 *
 * ── 四条约束 ────────────────────────────────────────────────
 *
 * 一、**只列假设，不替用户判断结论成不成立**。
 *     这个模块输出的是"哪些假设还没验证"，
 *     不是"这个因果结论对不对"。
 *     判断权在用户手上——模型给假设清单，用户去查。
 *     如果模块直接下结论，用户就会停止思考，
 *     那和听模型说"是因为改版"没有区别。
 *
 * 二、**能验证的假设必须给出用什么验证**。
 *     只写"需要检查可比性"等于没写——用户不知道怎么查。
 *     所以每条都带 `check`（用哪个组件 / 看哪个指标）。
 *
 * 三、**只能假定的假设要标出来，不能混在能验证的里面**。
 *     "无未测混杂"永远无法用观测数据证明，
 *     如果它和"时序正确"混在一张表里看起来一样，
 *     用户会以为查完这张表就完事了。
 *     不可验证的假设只能靠敏感性分析量化它有多脆弱。
 *
 * 四、**不改动任何卡片，只产出清单**。
 *     验证动作本身要用户去点、去跑对应组件。
 *     模块自己不去跑，否则用户不知道结论是怎么来的。
 */
object ZxCausal {

    /** 假设的可检验性 */
    enum class Kind(val label: String, val desc: String) {
        CHECKABLE("可验证", "有数据就能查"),
        PARTIAL("部分可验证", "只能查到一部分，剩下靠推论"),
        ASSUMED("只能假定", "观测数据永远证不了，只能量化它的脆弱程度")
    }

    /** 一条假设 */
    data class Assumption(
        val id: String,
        val text: String,
        val kind: Kind,
        /** 违反之后会怎样 */
        val ifBroken: String,
        /** 用什么验证（ASSUMED 时给替代做法） */
        val check: String,
        /** 状态 */
        var state: State = State.UNKNOWN
    ) {
        enum class State(val label: String) {
            OK("已验证"), WARN("存疑"), UNKNOWN("未查"), BROKEN("不成立")
        }
    }

    /** 一个因果主张 */
    data class Claim(
        val cause: String,
        val effect: String,
        /** 采用的方法，决定该列哪些假设 */
        val method: Method
    ) {
        enum class Method(val label: String) {
            RCT("实验"), DID("双重差分"), RDD("断点回归"),
            IV("工具变量"), MATCH("匹配"), OBS("纯观测对比")
        }
    }

    /** 按方法生成该列的假设清单 */
    fun assumptions(c: Claim): List<Assumption> {
        val base = mutableListOf(
            Assumption("time", "「${c.cause}」发生在「${c.effect}」变化之前",
                Kind.CHECKABLE, "因果方向反了，结论直接无效",
                "看时间轴：zx_did / zx_timeline 对齐干预时点"),
            Assumption("measure", "「${c.effect}」的测法前后一致",
                Kind.CHECKABLE, "变化可能只是口径变了，不是真的变了",
                "看口径变更记录：zx_seqdiff 比前后两版定义"),
            Assumption("spill", "处理组的效果没有溢出到对照组",
                Kind.PARTIAL, "两组都被影响 → 效应被低估",
                "看对照组在干预后有没有同向变化：zx_did")
        )
        when (c.method) {
            Claim.Method.RCT -> base += listOf(
                Assumption("random", "分组是随机的", Kind.CHECKABLE,
                    "分组不随机就不是实验，退化成观测", "看分组机制与 SMD：zx_balance")
            )
            Claim.Method.DID -> base += listOf(
                Assumption("parallel", "干预前两组走势平行", Kind.CHECKABLE,
                    "不平行则 DID 估计的是「本来就会有的差距」，不是效应",
                    "画干预前两条线：zx_did"),
                Assumption("noconfound", "没有与干预同时发生的其他变化", Kind.ASSUMED,
                    "并发干预会被算进效应里，且无法分离",
                    "列出同期所有变更；不可验证，只能靠 zx_sense 量化")
            )
            Claim.Method.RDD -> base += listOf(
                Assumption("nomanip", "没人能精确操控运行变量把自己放到断点某一侧",
                    Kind.PARTIAL, "能操控 → 断点两侧不再是同一类人，可比性失效",
                    "看断点附近分布有没有异常堆积：zx_histogram / zx_rdd"),
                Assumption("continuity", "除处理外，其他因素在断点处连续",
                    Kind.CHECKABLE, "有其他跳变 → 那个跳变也算进了效应",
                    "对协变量做同样的断点检验：zx_rdd 换 y")
            )
            Claim.Method.IV -> base += listOf(
                Assumption("relevant", "工具变量与内生变量强相关", Kind.CHECKABLE,
                    "弱工具变量（F<10）会让 IV 估计比朴素 OLS 还差",
                    "看一阶段 F：zx_iv"),
                Assumption("exclusion", "工具变量只通过内生变量影响结果", Kind.ASSUMED,
                    "有直接通路 → IV 估计不一致，且无法用数据证否",
                    "论证排除；不可验证，只有这个假设永远站着")
            )
            Claim.Method.MATCH -> base += listOf(
                Assumption("overlap", "两组有共同支撑（倾向得分重叠）", Kind.CHECKABLE,
                    "不重叠的地方硬配会得到很差的匹配", "看得分分布：zx_psm"),
                Assumption("noconfound", "所有同时影响处理和结果的变量都已测量并纳入",
                    Kind.ASSUMED, "漏一个 → 匹配也平衡不了它，偏倚仍在",
                    "不可验证，只能靠 zx_sense 量化未测混杂要多强才推翻结论")
            )
            Claim.Method.OBS -> base += listOf(
                Assumption("comparable", "两组本来就差不多", Kind.CHECKABLE,
                    "本来就差很远 → 比出来的差异不是处理造成的",
                    "看所有协变量的 SMD：zx_balance"),
                Assumption("noconfound", "没有同时影响两件事的第三因素", Kind.ASSUMED,
                    "有 → 观察到的关联全是它造成的",
                    "不可验证；这是纯观测最强的那条假设")
            )
        }
        return base
    }

    /** 渲染成清单文本 */
    fun render(c: Claim, list: List<Assumption>): String = buildString {
        appendLine("因果主张：${c.cause} → ${c.effect}（${c.method.label}）")
        appendLine()
        val byKind = Kind.values()
        byKind.forEach { k ->
            val sub = list.filter { it.kind == k }
            if (sub.isEmpty()) return@forEach
            appendLine("【${k.label}】${k.desc}")
            sub.forEach { a ->
                val mark = when (a.state) {
                    Assumption.State.OK -> "✓"
                    Assumption.State.WARN -> "?"
                    Assumption.State.BROKEN -> "✗"
                    Assumption.State.UNKNOWN -> "·"
                }
                appendLine("  $mark ${a.text}")
                appendLine("      违反会怎样：${a.ifBroken}")
                appendLine("      怎么验证　：${a.check}")
            }
            appendLine()
        }
        val unk = list.count { it.state == Assumption.State.UNKNOWN }
        val assumed = list.count { it.kind == Kind.ASSUMED }
        appendLine("共 ${list.size} 条假设：$unk 条未查，$assumed 条只能假定。")
        appendLine()
        appendLine("这个模块**不下结论**——只列出还没验证的假设。")
        if (assumed > 0)
            appendLine("那 $assumed 条永远证不了，只能用 zx_sense 量化：")
            appendLine("未测混杂要多强，这个结论才会翻？")
    }

    /** 只给出"下一步该查什么"——按可验证优先 */
    fun next(list: List<Assumption>): String = buildString {
        val todo = list.filter { it.state == Assumption.State.UNKNOWN }
        if (todo.isEmpty()) {
            appendLine("所有假设都查过了。")
            val assumed = list.filter { it.kind == Kind.ASSUMED }
            if (assumed.isNotEmpty()) {
                appendLine()
                appendLine("但有 ${assumed.size} 条是**永远证不了**的：")
                assumed.forEach { appendLine("  · ${it.text}") }
                appendLine()
                appendLine("剩下能做的只有量化它：zx_sense 给临界 Γ。")
            }
            return@buildString
        }
        // 可验证的排前面：先做能做的
        val ordered = todo.sortedBy { it.kind.ordinal }
        appendLine("下一步该查（可验证的优先）：")
        ordered.forEachIndexed { i, a ->
            appendLine("  ${i + 1}. ${a.text}")
            appendLine("     → ${a.check}")
        }
    }

    fun validate(o: JSONObject): Pair<Boolean, String> {
        val c = o.optString("cause", "")
        if (c.isBlank()) return false to "zx_claim 缺少 cause（原因）"
        val e = o.optString("effect", "")
        if (e.isBlank()) return false to "zx_claim 缺少 effect（结果）"
        // 因果主张至少要两个不同的东西
        if (c == e) return false to "zx_claim 的 cause 和 effect 都是「$c」——自己导致自己不算因果主张"
        val m = o.optString("method", "obs")
        if (m !in setOf("rct", "did", "rdd", "iv", "match", "obs"))
            return false to "zx_claim 的 method=\"$m\" 只支持 rct / did / rdd / iv / match / obs"
        val st = o.optJSONObject("states")
        if (st != null) {
            val ok = setOf("ok", "warn", "unknown", "broken")
            st.keys().asSequence().forEach { k ->
                val v = st.optString(k, "")
                if (v !in ok) return false to "states.$k=\"$v\" 只支持 ${ok.joinToString(" / ")}"
            }
        }
        return true to ""
    }

    fun from(o: JSONObject): Claim {
        val m = when (o.optString("method", "obs")) {
            "rct" -> Claim.Method.RCT
            "did" -> Claim.Method.DID
            "rdd" -> Claim.Method.RDD
            "iv" -> Claim.Method.IV
            "match" -> Claim.Method.MATCH
            else -> Claim.Method.OBS
        }
        return Claim(o.optString("cause", "原因"), o.optString("effect", "结果"), m)
    }

    fun applyStates(list: List<Assumption>, o: JSONObject) {
        val st = o.optJSONObject("states") ?: return
        list.forEach { a ->
            a.state = when (st.optString(a.id, "unknown")) {
                "ok" -> Assumption.State.OK
                "warn" -> Assumption.State.WARN
                "broken" -> Assumption.State.BROKEN
                else -> Assumption.State.UNKNOWN
            }
        }
    }

    fun demo(): String {
        val c = Claim("首页改版", "次留下降", Claim.Method.DID)
        val list = assumptions(c)
        // 模拟：时序和测法查过了，平行趋势存疑
        list.first { it.id == "time" }.state = Assumption.State.OK
        list.first { it.id == "measure" }.state = Assumption.State.OK
        list.first { it.id == "parallel" }.state = Assumption.State.WARN
        return buildString {
            appendLine("══ 模型说的一句话 ══")
            appendLine()
            appendLine("  「留下降了，是因为改了首页。」")
            appendLine()
            appendLine("这句话背后压着的假设：")
            appendLine()
            appendLine(render(c, list).trimEnd())
            appendLine()
            appendLine("══ 下一步 ══")
            appendLine()
            appendLine(next(list).trimEnd())
            appendLine()
            appendLine("注意「无并发干预」那条是**只能假定**的——")
            appendLine("它永远证不了。如果它和平行趋势混在一张表里")
            appendLine("看起来一样，用户会以为查完表就完事了。")
        }
    }

    fun report(): String = buildString {
        appendLine("因果推断界面（把「因为所以」背后的假设摆到界面上）")
        appendLine()
        appendLine("第二十六批做了 12 个因果组件，")
        appendLine("每个都把**一个**前提变成了界面上的东西。")
        appendLine()
        appendLine("但一个因果结论背后往往压着**好几条**假设：")
        appendLine()
        appendLine("  「留下降了，是因为改了首页。」")
        appendLine()
        appendLine("  1. 改版确实发生在留存下降之前（时序）")
        appendLine("  2. 改版期间没有别的事同时发生（无并发干预）")
        appendLine("  3. 两组用户本来就差不多（可比性）")
        appendLine("  4. 没有既影响改版又影响留存的第三因素")
        appendLine("  5. 留存的测量方式前后一致")
        appendLine()
        appendLine("这些在文字里是**隐形的**——模型不会主动列，")
        appendLine("用户不会主动问，于是「因为所以」就滑过去了。")
        appendLine()
        appendLine("四条约束：")
        appendLine("  一、只列假设，**不下结论**（判断权在用户）")
        appendLine("  二、能验证的必须给出**用什么验证**")
        appendLine("  三、只能假定的要**标出来**，不能混在能验证的里")
        appendLine("  四、不改动任何卡片，只产出清单")
        appendLine()
        appendLine("**不能验证的前提，等于没有前提。**")
    }

    fun guide(): String = """
        zx_claim(op=list|next|demo|report|guide, ...)

        把「因为所以」背后没说出口的假设，摆到界面上。

        模型说「留下降了，是因为改了首页」，
        背后压着五条假设，全是隐形的：
          时序 / 并发干预 / 可比性 / 未测混杂 / 测量不变性

        模型不会主动列，用户不会主动问——于是就滑过去了。

        每条假设标明三件事：
          · 能不能验证（可验证 / 部分 / 只能假定）
          · 违反了会怎样
          · 现在什么状态（已验证 / 存疑 / 未查 / 不成立）

        四条约束：
          一、只列假设，**不下结论**——判断权在用户
          二、能验证的必须给出**用什么验证**
          三、只能假定的要**标出来**，不能混在一起
              （否则用户以为查完表就完事了）
          四、不改动任何卡片，只产出清单

        DSL：cause + effect + method + states{id:ok|warn|unknown|broken}
        method = rct / did / rdd / iv / match / obs
    """.trimIndent()
}