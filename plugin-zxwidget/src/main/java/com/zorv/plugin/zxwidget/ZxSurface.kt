package com.zorv.plugin.zxwidget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * 界面层 —— 气泡装不下的东西，给它一个真正的界面。
 *
 * 这是第 11 个扩展点（UI_SURFACE），也是之前一直空着的那个。
 * 为什么之前不占：气泡组件不需要独立界面，占了没意义。
 * 为什么现在要占：组件做到第三批，有些东西在气泡里就是放不下——
 *   甘特图 12 条任务、流程图 10 个节点、可编辑代码、文件树、
 *   四列网格。气泡宽度 330dp 左右，硬塞只能缩到看不清。
 *
 * 于是需要"展开"：卡片右上角一个展开按钮 → 宿主用 PluginSurfaceActivity
 * 承载全屏内容。这不是另起一个 App，是同一份 DSL 换个更大的容器渲染。
 *
 * 三条硬规则（沿用插件总则）：
 *   1. 插件不写自己的 Activity，交给宿主的 PluginSurfaceActivity
 *   2. 全屏与气泡共用同一份 DSL 与同一套校验，不做两套渲染逻辑
 *   3. 全屏下的交互回传走与气泡完全相同的 callback，模型侧无感
 */
object ZxSurface {

    /** 表面形态 */
    enum class Mode(val label: String) {
        FULLSCREEN("全屏画布"),
        BOTTOM_SHEET("底部抽屉"),
        DIALOG("居中弹层")
    }

    /** 一次展开请求 */
    data class Request(
        val component: String,
        val dsl: String,
        val mode: Mode,
        val title: String,
        /** 从哪张卡片展开的，回传时带上，模型知道上下文 */
        val fromCard: String? = null
    )

    /** 记录当前处于展开态的内容，供宿主取用 */
    private var active: Request? = null

    /**
     * 多窗口 —— 并排看两张卡。
     *
     * 为什么需要：对比两张图、对着文档改代码，来回切页签很痛苦。
     * 手机上没有真正的多窗口，但在插件自己的界面里可以做左右分栏。
     *
     * 只给 2 个槽位：手机上第三栏宽度不足 100dp，什么都看不清。
     */
    private val windows = arrayOfNulls<Request>(2)

    fun openWindow(slot: Int, component: String, dsl: String, title: String): Result {
        if (slot !in 0..1) return Result(false, "槽位只能是 0 或 1（手机上放不下第三栏）")
        val check = ZxDsl.validate(component, dsl)
        if (!check.ok) return Result(false, "窗口内容校验失败：${check.reason}")
        windows[slot] = Request(component, dsl, Mode.FULLSCREEN, title)
        ZxAudit.log(ZxAudit.Kind.RENDER, "_window$slot", "打开窗口 · $component")
        val other = windows[1 - slot]
        return Result(true, if (other != null) "已在槽位 $slot 打开 $component，与槽位 ${1 - slot} 的「${other.title}」并排。"
                            else "已在槽位 $slot 打开 $component。可在槽位 ${1 - slot} 再开一个做对比。")
    }

    fun closeWindow(slot: Int): Result {
        if (slot !in 0..1) return Result(false, "槽位只能是 0 或 1")
        val w = windows[slot] ?: return Result(false, "槽位 $slot 没有打开的窗口")
        windows[slot] = null
        return Result(true, "已关闭槽位 $slot 的「${w.title}」")
    }

    fun windowAt(slot: Int): Request? = windows[slot]

    fun windowList(): String {
        val a = windows[0]; val b = windows[1]
        return when {
            a == null && b == null -> "当前没有打开的窗口。zx_window(op=open, slot=0) 开一个。"
            else -> buildString {
                appendLine("槽位 0：${a?.let { "${it.title} · ${it.component}" } ?: "空"}")
                appendLine("槽位 1：${b?.let { "${it.title} · ${it.component}" } ?: "空"}")
                if (a != null && b != null) appendLine("两栏并排中。")
            }.trim()
        }
    }

    fun request(req: Request): Result {
        val check = ZxDsl.validate(req.component, req.dsl)
        if (!check.ok) return Result(false, "展开前校验失败：${check.reason}")
        active = req
        ZxAudit.log(ZxAudit.Kind.RENDER, req.fromCard ?: "_surface",
                    "展开为 ${req.mode.label} · ${req.component}")
        return Result(true, "已展开为${req.mode.label}：${req.title}")
    }

    fun active(): Request? = active
    fun close() { active = null }

    /**
     * 判断一个组件在气泡里是否"先天放不下"。
     * 供模型决定要不要主动展开，而不是等用户点。
     */
    fun needsSurface(component: String, o: JSONObject): Boolean = when (component) {
        "zx_editor"   -> true                                        // 要键盘，气泡里太挤
        "zx_gantt"    -> (o.optJSONArray("tasks")?.length() ?: 0) > 6
        "zx_flow"     -> (o.optJSONArray("nodes")?.length() ?: 0) > 5
        "zx_heatmap"  -> (o.optJSONArray("rows")?.length() ?: 0) > 8
        "zx_tree"     -> countTree(o.optJSONArray("nodes")) > 10
        "zx_kanban"   -> (o.optJSONArray("columns")?.length() ?: 0) > 3
        "zx_calendar" -> true                                        // 日历在窄气泡里日期格太小
        "zx_grid"     -> (o.optJSONArray("children")?.length() ?: 0) > 4
        // 第五批：输入型组件要键盘/要大操作区，多数更适合展开
        "zx_pin"         -> true                   // 数字键盘占半屏，气泡里挤
        "zx_timepicker"  -> true                   // 双列滚轮需要高度
        "zx_transfer"    -> (o.optJSONArray("items")?.length() ?: 0) > 8
        "zx_sortable"    -> (o.optJSONArray("items")?.length() ?: 0) > 6
        "zx_comment"     -> (o.optJSONArray("comments")?.length() ?: 0) > 4
        // 第六批
        "zx_kanban"    -> true                   // 多列横滚，气泡里太窄
        "zx_hotspot"   -> true                   // 要在图上精确定位
        "zx_wordcloud" -> (o.optJSONArray("words")?.length() ?: 0) > 12
        "zx_treemap"   -> (o.optJSONArray("items")?.length() ?: 0) > 6
        // 第七批
        "zx_matrix"  -> (o.optJSONArray("rows")?.length() ?: 0) > 5
        "zx_dualist" -> (o.optJSONArray("pairs")?.length() ?: 0) > 6
        "zx_tour"    -> (o.optJSONArray("steps")?.length() ?: 0) > 5
        // 第八批
        "zx_datalist" -> (o.optJSONArray("rows")?.length() ?: 0) > 8
        "zx_keyvalue" -> (o.optJSONArray("rows")?.length() ?: 0) > 8
        "zx_avatargroup" -> (o.optJSONArray("people")?.length() ?: 0) > 12
        // 第九批
        "zx_candle"  -> (o.optJSONArray("bars")?.length() ?: 0) > 14
        "zx_dag"     -> (o.optJSONArray("nodes")?.length() ?: 0) > 6
        "zx_mindmap" -> (o.optJSONArray("arms")?.length() ?: 0) > 4
        "zx_conversation" -> (o.optJSONArray("turns")?.length() ?: 0) > 5
        // 第十批
        "zx_calheat" -> (o.optJSONArray("cells")?.length() ?: 0) > 60
        "zx_badges"  -> (o.optJSONArray("items")?.length() ?: 0) > 6
        // 第十一批
        "zx_rich"  -> (o.optJSONArray("blocks")?.length() ?: 0) > 8
        "zx_seat"  -> (o.optJSONArray("rows")?.length() ?: 0) > 6
        "zx_floor" -> true     // 平面图在气泡里永远太挤
        // 第十二批
        "zx_orgchart" -> countNodes(o.optJSONObject("root")) > 8
        "zx_sankey"   -> (o.optJSONArray("flows")?.length() ?: 0) > 5
        "zx_treeview" -> countNodes(o.optJSONObject("root")) > 12
        // 第十三批
        "zx_diffview" -> (o.optJSONArray("lines")?.length() ?: 0) > 8
        "zx_logview"  -> true     // 日志需要纵向空间，气泡里只能看一小段
        "zx_depgraph" -> (o.optJSONArray("nodes")?.length() ?: 0) > 6
        "zx_datamap"  -> (o.optJSONArray("regions")?.length() ?: 0) > 8
        // 第十四批
        "zx_boxplot"  -> (o.optJSONArray("items")?.length() ?: 0) > 4
        "zx_video"    -> true     // 视频要横向空间，气泡里太窄
        "zx_docview"  -> true     // 文档预览永远该全屏
        "zx_model3d"  -> true     // 3D 要手势空间
        "zx_schedule" -> (o.optJSONArray("days")?.length() ?: 0) > 5
        "zx_control"  -> (o.optJSONArray("pts")?.length() ?: 0) > 20
        // 第十五批
        "zx_network"  -> (o.optJSONArray("nodes")?.length() ?: 0) > 6
        "zx_annotate" -> o.optString("text").length > 300
        "zx_cohort"   -> (o.optJSONArray("rows")?.length() ?: 0) > 5
        "zx_ridgeline"-> (o.optJSONArray("rows")?.length() ?: 0) > 4
        // 第十六批
        "zx_weekgrid" -> (o.optJSONArray("slots")?.length() ?: 0) > 4
        "zx_scoreboard"-> (o.optJSONArray("crit")?.length() ?: 0) > 3
        "zx_branch"   -> o.optInt("cols", 6) > 8
        "zx_thread"   -> (o.optJSONArray("posts")?.length() ?: 0) > 5
        // 第十七批
        "zx_geo"      -> (o.optJSONArray("pts")?.length() ?: 0) > 10
        "zx_isometric"-> (o.optJSONArray("blocks")?.length() ?: 0) > 6
        "zx_ast"      -> true                    // 语法树在气泡里总是太挤
        "zx_bezier"   -> true                    // 要拖控制点，气泡里施展不开
        // 第十八批
        "zx_flowedit" -> true                    // 要拉节点
        "zx_crop"     -> true                    // 要框选区
        "zx_slot"     -> true                    // 要拖时间块
        "zx_timeline2"-> (o.optJSONArray("rows")?.length() ?: 0) > 3
        "zx_mapping"  -> (o.optJSONArray("from")?.length() ?: 0) > 5
        // 第十九批
        "zx_seq"      -> (o.optJSONArray("msgs")?.length() ?: 0) > 6
        "zx_bracket"  -> true                    // 对阵要横向空间
        "zx_critical" -> (o.optJSONArray("tasks")?.length() ?: 0) > 5
        "zx_evidence" -> (o.optJSONArray("items")?.length() ?: 0) > 5
        "zx_traceback"-> (o.optJSONArray("nodes")?.length() ?: 0) > 6
        // 第二十批
        "zx_parallel" -> (o.optJSONArray("axes")?.length() ?: 0) > 4
        "zx_glyph"    -> (o.optJSONArray("items")?.length() ?: 0) > 12
        "zx_mosaic"   -> (o.optJSONArray("rows")?.length() ?: 0) > 3
        "zx_contour"  -> (o.optJSONArray("grid")?.length() ?: 0) > 8
        "zx_trail"    -> true                    // 轨迹要地方看清
        // 第二十一批
        "zx_split"    -> true                    // 两半都要看清
        "zx_lane"     -> (o.optJSONArray("lanes")?.length() ?: 0) > 3
        "zx_threeway" -> (o.optJSONArray("rows")?.length() ?: 0) > 8
        "zx_jsondiff" -> (o.optJSONArray("rows")?.length() ?: 0) > 8
        "zx_annotate" -> true                    // 标注要地方指
        "zx_align"    -> (o.optJSONArray("series")?.length() ?: 0) > 2
        // 第二十二批
        "zx_cmdhist"   -> (o.optJSONArray("items")?.length() ?: 0) > 6
        "zx_callstack" -> (o.optJSONArray("frames")?.length() ?: 0) > 6
        "zx_glossary"  -> (o.optJSONArray("terms")?.length() ?: 0) > 6
        "zx_toc"       -> (o.optJSONArray("items")?.length() ?: 0) > 8
        "zx_corr"      -> true                    // 矩阵要地方看清
        "zx_punch"     -> true                    // 双轴格太密
        // 第二十三批
        "zx_decision"  -> (o.optJSONArray("nodes")?.length() ?: 0) > 5
        "zx_tradeoff"  -> true                    // 前沿曲线要地方看
        "zx_blast"     -> (o.optJSONArray("nodes")?.length() ?: 0) > 6
        "zx_brush"     -> (o.optJSONArray("data")?.length() ?: 0) > 24
        "zx_priority"  -> true                    // 四格要展开看
        "zx_rollup"    -> (o.optJSONArray("rows")?.length() ?: 0) > 8
        // 第二十四批
        "zx_qq"        -> (o.optJSONArray("data")?.length() ?: 0) > 20
        "zx_forest"    -> true                    // 要横着看置信区间
        "zx_survival"  -> (o.optJSONArray("events")?.length() ?: 0) > 6
        "zx_scatter"   -> (o.optJSONArray("pts")?.length() ?: 0) > 20
        "zx_regress"   -> true                    // 散点+残差两张图
        // 第二十五批
        "zx_decompose" -> true                    // 四行序列叠着看
        "zx_pscan"     -> (o.optJSONArray("grid")?.length() ?: 0) > 12
        "zx_mds"       -> true                    // 要连线和标签，气泡里挤
        "zx_geoflow"   -> true                    // 弧线需要横向空间
        "zx_roc"       -> (o.optJSONArray("pts")?.length() ?: 0) > 20
        "zx_violincmp" -> (o.optJSONArray("groups")?.length() ?: 0) > 4
        "zx_survcmp"   -> true                    // 多条曲线叠加
        // 第二十六批
        "zx_causal"    -> true                    // 箭头和标签要地方
        "zx_rdd"       -> true                    // 两条拟合线 + 带宽带
        "zx_iv"        -> true                    // 三阶段切换要横着看
        "zx_psm"       -> true                    // 得分分布 + 多行 SMD
        "zx_missing"   -> (o.optJSONArray("rows")?.length() ?: 0) > 8
        "zx_seqtest"   -> true                    // 边界曲线要看清
        // 第二十七批
        "zx_forecast"   -> true                   // 区间带 + 历史/预测两段
        "zx_decision"   -> true                   // 权重条 + 排名要并排看
        "zx_riskreturn" -> true                   // 前沿要看得清
        "zx_explain"    -> true                   // 瀑布要横着读
        "zx_cforest"    -> (o.optJSONArray("subs")?.length() ?: 0) > 4
        "zx_alloc"      -> true                   // 三条渠道 + 预算切换
        // 第二十八批
        "zx_rollout"     -> true                  // 批次条 + 逐批状态
        "zx_canary"      -> (o.optJSONArray("metrics")?.length() ?: 0) > 4
        "zx_slo"         -> true                  // 燃尽条 + 每日柱
        "zx_healthcheck" -> (o.optJSONArray("items")?.length() ?: 0) > 8
        "zx_incident"    -> true                  // 段间时长要看得清
        "zx_rca"         -> true                  // 缩进阶梯要横着看
        "zx_backtest"    -> true                  // 双曲线 + 回撤
        "zx_drift"       -> true                  // 阈值刻度要看得清
        "zx_costtrack"   -> true                  // 外推虚线要看清
        "zx_feedback"    -> true                  // 预测/实际双线
        // 第二十九批
        "zx_capacity"    -> true                  // ρ 曲线要看清
        "zx_loadtest"    -> true                  // 拐点要看清
        "zx_topology"    -> true                  // 传播路径要看清
        "zx_changerisk"  -> true                  // 依据文字要展开读
        "zx_playbook"    -> true                  // 步骤说明要读
        "zx_postmortem"  -> true                  // 行动项要读
        "zx_knowledge"   -> true                  // 时效判断要看清
        "zx_decisionlog" -> true                  // 依据要读
        "zx_debt"        -> true                  // 利息/成本比要看清
        "zx_checkpoint"  -> true                  // 间隔要看清
        // 第三十批
        "zx_unitecon"    -> true                  // 累计曲线要看得清
        "zx_costattrib"  -> true                  // 分项 vs 总额要对齐看
        "zx_lineage"     -> true                  // 分层血缘要展开
        "zx_impact"      -> true                  // 修复方式要读
        "zx_dataclass"   -> true                  // 分级要逐列确认
        "zx_retention"   -> true                  // 到期判断要看得清
        "zx_workload"    -> true                  // 超载/空闲要对比看
        "zx_bottleneck"  -> true                  // 约束 vs 最堵要分得清
        "zx_expresult"   -> true                  // 提升幅度要读
        "zx_compliance"  -> true                  // 整改人要读
        "zx_audittrail"  -> true                  // 操作流水要读
        "zx_accessreview" -> true                 // 审期要看得清
        // 第三十一批
        "zx_scenario"    -> true                  // 色带要看得清
        "zx_montecarlo"  -> true                  // 分布与分位要读
        "zx_stress"      -> true                  // 边界要看得出
        "zx_tornado"     -> true                  // 排序要看得全
        "zx_whatif"      -> true                  // 链路传导要看得清
        "zx_breakeven"   -> true                  // 临界点要拖
        "zx_systemdyn"   -> true                  // 长期行为要展开
        "zx_markov"      -> true                  // 矩阵要读
        "zx_optpath"     -> true                  // 路径要高亮
        "zx_payoff"      -> true                  // 均衡要看得出
        "zx_reversesolve" -> true                 // 值域要看得清
        "zx_realoption"  -> true                  // 期权价值要读
        // 第三十二批
        "zx_bargain"     -> true                  // ZOPA 要拖
        "zx_auction"     -> true                  // 多家对比要看全
        "zx_multiissue"  -> true                  // 交换空间要看清
        "zx_pareto"      -> true                  // 前沿要看得清
        "zx_portfolio"   -> true                  // 权重要拖
        "zx_bidcurve"    -> true                  // 交点要读
        "zx_sequential"  -> true                  // 决策树要看
        "zx_stoprule"    -> true                  // 分期要看
        "zx_hedge"       -> true                  // 比例要拖
        "zx_contingent"  -> true                  // 预案要读
        "zx_escalate"    -> true                  // 阈值要看
        "zx_commit"      -> true                  // 可信度要读
        // 第三十三批
        "zx_weather"    -> true                  // 多日曲线要读
        "zx_wxradar"    -> true                  // 雷达圈要看得清
        "zx_code"       -> true                  // 代码要横滚，气泡里挤
        "zx_codetheme"  -> true                  // 对比要看全
        "zx_shell"      -> true                  // 要键盘，气泡里太挤
        "zx_tty"        -> true                  // 多会话要宽
        "zx_toolchain"  -> true                  // 链要看得全
        "zx_2048"       -> true                  // 游戏要全屏
        "zx_tictactoe"  -> true                  // 棋盘要大
        // 第三十四批
        "zx_mapnav"     -> true                  // 路线要看全
        "zx_transit"    -> true                  // 换乘段多
        "zx_album"      -> true                  // 图要大
        "zx_filemgr"    -> true                  // 目录树要宽
        "zx_shop"       -> true                  // 商品图要看清
        "zx_remote"     -> true                  // 遥控器要大按键
        "zx_parcel"       -> true                  // 轨迹节点多
        "zx_ticket"       -> true                  // 选座区要大
        "zx_appointment"  -> true                  // 号源表要宽        "zx_ledger"       -> true                  // 流水分行多
        "zx_recipe"       -> true                  // 步骤多
        "zx_tripkit"      -> true                  // 清单要宽        "zx_rental"       -> true                  // 房源对比要宽
        "zx_booking"      -> true                  // 时段网格要宽        "zx_overrun"      -> true                  // 双条对比要宽
        "zx_slotconflict" -> true                  // 双方依据 + 方案
        "zx_housedeal"    -> true                  // 节点 + 金额 + 双方确认
        "zx_carerota"     -> true                  // 排班按天要宽
        "zx_fosterdaily"  -> true                  // 按天日报要宽
        "zx_stocktake"    -> true                  // 盘点项要宽

        "zx_renovation"   -> true                  // 阶段多


        "zx_utilitymeter" -> true                  // 多期读数
        "zx_dialpad"    -> true                  // 键盘要大
        else -> false
    }

    /** 递归数节点：树形组件判断要不要展开全屏 */
    private fun countNodes(n: org.json.JSONObject?, depth: Int = 0): Int {
        if (n == null || depth > 5) return 0
        var c = 1
        val kids = n.optJSONArray("kids") ?: return c
        for (i in 0 until kids.length()) c += countNodes(kids.optJSONObject(i), depth + 1)
        return c
    }

    private fun countTree(arr: org.json.JSONArray?): Int {
        if (arr == null) return 0
        var n = 0
        for (i in 0 until arr.length()) {
            n++
            val nd = arr.optJSONObject(i) ?: continue
            n += countTree(nd.optJSONArray("children"))
        }
        return n
    }

    /**
     * 全屏渲染。与气泡共用 ZxRender 系列，只是外层容器不同。
     * 不写第二套渲染逻辑——否则两边行为会漂移，改一处忘一处。
     */
    @Composable
    fun render(req: Request, ctx: RenderContext) {
        val o = runCatching { JSONObject(req.dsl) }.getOrNull() ?: return
        Column(Modifier.fillMaxSize().background(ZxT.Surface)) {
            // 全屏顶栏：气泡里不需要，因为气泡本身就带卡片头
            Row(Modifier.fillMaxWidth()
                .background(ZxT.Surface2)
                .padding(14.dp, 12.dp)) {
                Text(req.title, fontSize = 14.5.sp, color = ZxT.Text, modifier = Modifier.weight(1f))
                Text(req.mode.label, fontSize = 10.5.sp, color = ZxT.Text3)
            }
            Box(Modifier.fillMaxSize().padding(14.dp)) {
                // 多窗口：两个槽位都有内容时并排，否则单栏
                val w0 = windows[0]; val w1 = windows[1]
                if (w0 != null || w1 != null) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (w0 != null) renderOne(w0, ctx) else empty()
                        }
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (w1 != null) renderOne(w1, ctx) else empty()
                        }
                    }
                    return@Column
                }
                renderOne(req, ctx)
            }
        }
    }

    /** 单个窗口/展开内容的渲染分发 */
    @Composable
    private fun renderOne(req: Request, ctx: RenderContext) {
        val o = runCatching { JSONObject(req.dsl) }.getOrNull() ?: return
        when (o.optString("type")) {
            "zx_metrics"  -> ZxRender3.metrics(o, ctx)
                    "zx_gantt"    -> ZxRender3.gantt(o, ctx)
                    "zx_tree"     -> ZxRender3.tree(o, ctx)
                    "zx_flow"     -> ZxRender3.flow(o, ctx)
                    "zx_calendar" -> ZxRender3.calendar(o, ctx)
                    "zx_rating"   -> ZxRender3.rating(o, ctx)
                    "zx_editor"   -> ZxRender3.editor(o, ctx)
                    "zx_audio"    -> ZxRender3.audio(o, ctx)
                    "zx_heatmap"  -> ZxRender3.heatmap(o, ctx)
                    "zx_toggle"   -> ZxRender3.toggle(o, ctx)
                    "zx_qr"       -> ZxRender3.qr(o, ctx)
            "zx_stack"    -> ZxRender3.stack(o, ctx)
            // 第四批
            "zx_radar"      -> ZxRender4.radar(o, ctx)
            "zx_rank"       -> ZxRender4.rank(o, ctx)
            "zx_org"        -> ZxRender4.org(o, ctx)
            "zx_sankey"     -> ZxRender4.sankey(o, ctx)
            "zx_gauge"      -> ZxRender4.gauge(o, ctx)
            "zx_range"      -> ZxRender4.range(o, ctx)
            "zx_color"      -> ZxRender4.color(o, ctx)
            "zx_breadcrumb" -> ZxRender4.breadcrumb(o, ctx)
            "zx_pager"      -> ZxRender4.pager(o, ctx)
            "zx_countdown"  -> ZxRender4.countdown(o, ctx)
            "zx_sign"       -> ZxRender4.sign(o, ctx)
            "zx_board"      -> ZxRender4.board(o, ctx)
            // 第五批（输入型）
            "zx_poll"        -> ZxRender5.poll(o, ctx)
            "zx_transfer"    -> ZxRender5.transfer(o, ctx)
            "zx_chips"       -> ZxRender5.chips(o, ctx)
            "zx_pin"         -> ZxRender5.pin(o, ctx)
            "zx_dial"        -> ZxRender5.dial(o, ctx)
            "zx_joystick"    -> ZxRender5.joystick(o, ctx)
            "zx_timepicker"  -> ZxRender5.timepicker(o, ctx)
            "zx_slidergroup" -> ZxRender5.slidergroup(o, ctx)
            "zx_sortable"    -> ZxRender5.sortable(o, ctx)
            "zx_cardflip"    -> ZxRender5.cardflip(o, ctx)
            "zx_comment"     -> ZxRender5.comment(o, ctx)
            "zx_seek"        -> ZxRender5.seek(o, ctx)
            // 第六批
            "zx_wordcloud" -> ZxRender6.wordcloud(o, ctx)
            "zx_funnel"    -> ZxRender6.funnel(o, ctx)
            "zx_treemap"   -> ZxRender6.treemap(o, ctx)
            "zx_chord"     -> ZxRender6.chord(o, ctx)
            "zx_sparkline" -> ZxRender6.sparkline(o, ctx)
            "zx_kanban"    -> ZxRender6.kanban(o, ctx)
            "zx_hotspot"   -> ZxRender6.hotspot(o, ctx)
            "zx_stepper"   -> ZxRender6.stepper(o, ctx)
            "zx_segmented" -> ZxRender6.segmented(o, ctx)
            "zx_mention"   -> ZxRender6.mention(o, ctx)
            "zx_dropzone"  -> ZxRender6.dropzone(o, ctx)
            "zx_resizable" -> ZxRender6.resizable(o, ctx)
            // 第七批
            "zx_tour"   -> ZxRender7.tour(o, ctx)
            "zx_swipe"  -> ZxRender7.swipe(o, ctx)
            "zx_matrix" -> ZxRender7.matrix(o, ctx)
            "zx_spin"   -> ZxRender7.spin(o, ctx)
            "zx_barcode"-> ZxRender7.barcode(o, ctx)
            "zx_dualist"-> ZxRender7.dualist(o, ctx)
            "zx_bento"  -> ZxRender7.bento(o, ctx)
            "zx_minmap" -> ZxRender7.minmap(o, ctx)
            // 第八批
            "zx_price"    -> ZxRender8.price(o, ctx)
            "zx_swatch"   -> ZxRender8.swatch(o, ctx)
            "zx_inputmask"-> ZxRender8.inputmask(o, ctx)
            "zx_datalist" -> ZxRender8.datalist(o, ctx)
            "zx_keyvalue" -> ZxRender8.keyvalue(o, ctx)
            // 第九批
            "zx_candle"   -> ZxRender9.candle(o, ctx)
            "zx_dag"      -> ZxRender9.dag(o, ctx)
            "zx_swimlane" -> ZxRender9.swimlane(o, ctx)
            "zx_mindmap"  -> ZxRender9.mindmap(o, ctx)
            "zx_spreadsheet" -> ZxRender9.spreadsheet(o, ctx)
            "zx_bubble"   -> ZxRender9.bubble(o, ctx)
            // 第十批
            "zx_calheat"  -> ZxRender10.calheat(o, ctx)
            "zx_matrixheat" -> ZxRender10.matrixheat(o, ctx)
            "zx_polar"    -> ZxRender10.polar(o, ctx)
            "zx_scale"    -> ZxRender10.scale(o, ctx)
            "zx_badges"   -> ZxRender10.badges(o, ctx)
            // 第十一批
            "zx_rich"   -> ZxRender11.rich(o, ctx)
            "zx_floor"  -> ZxRender11.floor(o, ctx)
            "zx_seat"   -> ZxRender11.seat(o, ctx)
            "zx_settings" -> ZxRender11.settings(o, ctx)
            // 第十二批
            "zx_orgchart" -> ZxRender12.orgchart(o, ctx)
            "zx_sankey"   -> ZxRender12.sankey(o, ctx)
            "zx_treeview" -> ZxRender12.treeview(o, ctx)
            // 第十三批
            "zx_diffview" -> ZxRender13.diffview(o, ctx)
            "zx_logview"  -> ZxRender13.logview(o, ctx)
            "zx_console"  -> ZxRender13.console(o, ctx)
            "zx_depgraph" -> ZxRender13.depgraph(o, ctx)
            "zx_conflict" -> ZxRender13.conflict(o, ctx)
            // 第十四批
            "zx_histogram" -> ZxRender14.histogram(o, ctx)
            "zx_boxplot"   -> ZxRender14.boxplot(o, ctx)
            "zx_pareto"    -> ZxRender14.pareto(o, ctx)
            "zx_sunburst"  -> ZxRender14.sunburst(o, ctx)
            "zx_video"     -> ZxRender14.video(o, ctx)
            "zx_docview"   -> ZxRender14.docview(o, ctx)
            "zx_model3d"   -> ZxRender14.model3d(o, ctx)
            "zx_schedule"  -> ZxRender14.schedule(o, ctx)
            // 第十五批
            "zx_network"   -> ZxRender15.network(o, ctx)
            "zx_annotate"  -> ZxRender15.annotate(o, ctx)
            "zx_cohort"    -> ZxRender15.cohort(o, ctx)
            "zx_ridgeline" -> ZxRender15.ridgeline(o, ctx)
            "zx_steparea"  -> ZxRender15.steparea(o, ctx)
            // 第十六批
            "zx_weekgrid"    -> ZxRender16.weekgrid(o, ctx)
            "zx_scoreboard"  -> ZxRender16.scoreboard(o, ctx)
            "zx_statemachine"-> ZxRender16.statemachine(o, ctx)
            "zx_branch"      -> ZxRender16.branch(o, ctx)
            "zx_runbook"     -> ZxRender16.runbook(o, ctx)
            // 第十七批
            "zx_geo"       -> ZxRender17.geo(o, ctx)
            "zx_isometric" -> ZxRender17.isometric(o, ctx)
            "zx_ast"       -> ZxRender17.ast(o, ctx)
            "zx_tokens"    -> ZxRender17.tokens(o, ctx)
            "zx_bezier"    -> ZxRender17.bezier(o, ctx)
            // 第十八批：编辑器一律给全屏，气泡里施展不开
            "zx_flowedit"  -> ZxRender18.flowedit(o, ctx)
            "zx_crop"      -> ZxRender18.crop(o, ctx)
            "zx_slot"      -> ZxRender18.slot(o, ctx)
            "zx_timeline2" -> ZxRender18.timeline2(o, ctx)
            "zx_mapping"   -> ZxRender18.mapping(o, ctx)
            // 第十九批
            "zx_seq"       -> ZxRender19.seq(o, ctx)
            "zx_bracket"   -> ZxRender19.bracket(o, ctx)
            "zx_critical"  -> ZxRender19.critical(o, ctx)
            "zx_evidence"  -> ZxRender19.evidence(o, ctx)
            "zx_traceback" -> ZxRender19.traceback(o, ctx)
            // 第二十批
            "zx_parallel"  -> ZxRender20.parallel(o, ctx)
            "zx_glyph"     -> ZxRender20.glyph(o, ctx)
            "zx_mosaic"    -> ZxRender20.mosaic(o, ctx)
            "zx_contour"   -> ZxRender20.contour(o, ctx)
            "zx_trail"     -> ZxRender20.trail(o, ctx)
            // 第二十一批
            "zx_split"     -> ZxRender21.split(o, ctx)
            "zx_lane"      -> ZxRender21.lane(o, ctx)
            "zx_threeway"  -> ZxRender21.threeway(o, ctx)
            "zx_jsondiff"  -> ZxRender21.jsondiff(o, ctx)
            "zx_annotate"  -> ZxRender21.annotate(o, ctx)
            "zx_align"     -> ZxRender21.align(o, ctx)
            // 第二十二批
            "zx_cmdhist"   -> ZxRender22.cmdhist(o, ctx)
            "zx_callstack" -> ZxRender22.callstack(o, ctx)
            "zx_glossary"  -> ZxRender22.glossary(o, ctx)
            "zx_toc"       -> ZxRender22.toc(o, ctx)
            "zx_corr"      -> ZxRender22.corr(o, ctx)
            "zx_punch"     -> ZxRender22.punch(o, ctx)
            // 第二十三批
            "zx_decision"  -> ZxRender23.decision(o, ctx)
            "zx_tradeoff"  -> ZxRender23.tradeoff(o, ctx)
            "zx_blast"     -> ZxRender23.blast(o, ctx)
            "zx_brush"     -> ZxRender23.brush(o, ctx)
            "zx_priority"  -> ZxRender23.priority(o, ctx)
            "zx_rollup"    -> ZxRender23.rollup(o, ctx)
            // 第二十四批
            "zx_qq"        -> ZxRender24.qq(o, ctx)
            "zx_forest"    -> ZxRender24.forest(o, ctx)
            "zx_survival"  -> ZxRender24.survival(o, ctx)
            "zx_scatter"   -> ZxRender24.scatter(o, ctx)
            "zx_lorenz"    -> ZxRender24.lorenz(o, ctx)
            "zx_regress"   -> ZxRender24.regress(o, ctx)
            // 第二十五批
            "zx_decompose" -> ZxRender25.decompose(o, ctx)
            "zx_pscan"     -> ZxRender25.pscan(o, ctx)
            "zx_mds"       -> ZxRender25.mds(o, ctx)
            "zx_geoflow"   -> ZxRender25.geoflow(o, ctx)
            "zx_roc"       -> ZxRender25.roc(o, ctx)
            "zx_survcmp"   -> ZxRender25.survcmp(o, ctx)
            // 第二十六批
            "zx_causal"    -> ZxRender26.causal(o, ctx)
            "zx_rdd"       -> ZxRender26.rdd(o, ctx)
            "zx_iv"        -> ZxRender26.iv(o, ctx)
            "zx_psm"       -> ZxRender26.psm(o, ctx)
            "zx_missing"   -> ZxRender26.missing(o, ctx)
            "zx_seqtest"   -> ZxRender26.seqtest(o, ctx)
            // 第二十七批
            "zx_forecast"   -> ZxRender27.forecast(o, ctx)
            "zx_dmatrix"   -> ZxRender27.decision(o, ctx)
            "zx_riskreturn" -> ZxRender27.riskreturn(o, ctx)
            "zx_explain"    -> ZxRender27.explain(o, ctx)
            "zx_cforest"    -> ZxRender27.cforest(o, ctx)
            "zx_alloc"      -> ZxRender27.alloc(o, ctx)
            // 第二十八批
            "zx_rollout"     -> ZxRender28.rollout(o, ctx)
            "zx_canary"      -> ZxRender28.canary(o, ctx)
            "zx_slo"         -> ZxRender28.slo(o, ctx)
            "zx_healthcheck" -> ZxRender28.healthcheck(o, ctx)
            "zx_incident"    -> ZxRender28.incident(o, ctx)
            "zx_rca"         -> ZxRender28.rca(o, ctx)
            "zx_backtest"    -> ZxRender28.backtest(o, ctx)
            "zx_drift"       -> ZxRender28.drift(o, ctx)
            "zx_costtrack"   -> ZxRender28.costtrack(o, ctx)
            "zx_feedback"    -> ZxRender28.feedback(o, ctx)
            // 第二十九批
            "zx_capacity"    -> ZxRender29.capacity(o, ctx)
            "zx_loadtest"    -> ZxRender29.loadtest(o, ctx)
            "zx_topology"    -> ZxRender29.topology(o, ctx)
            "zx_changerisk"  -> ZxRender29.changerisk(o, ctx)
            "zx_playbook"    -> ZxRender29.playbook(o, ctx)
            "zx_postmortem"  -> ZxRender29.postmortem(o, ctx)
            "zx_knowledge"   -> ZxRender29.knowledge(o, ctx)
            "zx_decisionlog" -> ZxRender29.decisionlog(o, ctx)
            "zx_debt"        -> ZxRender29.debt(o, ctx)
            "zx_checkpoint"  -> ZxRender29.checkpoint(o, ctx)
            // 第三十批
            "zx_unitecon"    -> ZxRender30.unitecon(o, ctx)
            "zx_costattrib"  -> ZxRender30.costattrib(o, ctx)
            "zx_lineage"     -> ZxRender30.lineage(o, ctx)
            "zx_impact"      -> ZxRender30.impact(o, ctx)
            "zx_dataclass"   -> ZxRender30.dataclass(o, ctx)
            "zx_retention"   -> ZxRender30.retention(o, ctx)
            "zx_workload"    -> ZxRender30.workload(o, ctx)
            "zx_bottleneck"  -> ZxRender30.bottleneck(o, ctx)
            "zx_expresult"   -> ZxRender30.expresult(o, ctx)
            "zx_compliance"  -> ZxRender30.compliance(o, ctx)
            "zx_audittrail"  -> ZxRender30.audittrail(o, ctx)
            "zx_accessreview" -> ZxRender30.accessreview(o, ctx)
            // 第三十一批
            "zx_scenario"    -> ZxRender31.scenario(o, ctx)
            "zx_montecarlo"  -> ZxRender31.montecarlo(o, ctx)
            "zx_stress"      -> ZxRender31.stress(o, ctx)
            "zx_tornado"     -> ZxRender31.tornado(o, ctx)
            "zx_whatif"      -> ZxRender31.whatif(o, ctx)
            "zx_breakeven"   -> ZxRender31.breakeven(o, ctx)
            "zx_systemdyn"   -> ZxRender31.systemdyn(o, ctx)
            "zx_markov"      -> ZxRender31.markov(o, ctx)
            "zx_optpath"     -> ZxRender31.optpath(o, ctx)
            "zx_payoff"      -> ZxRender31.payoff(o, ctx)
            "zx_reversesolve" -> ZxRender31.reversesolve(o, ctx)
            "zx_realoption"  -> ZxRender31.realoption(o, ctx)
            // 第三十二批
            "zx_bargain"     -> ZxRender32.bargain(o, ctx)
            "zx_auction"     -> ZxRender32.auction(o, ctx)
            "zx_multiissue"  -> ZxRender32.multiissue(o, ctx)
            "zx_pfront"      -> ZxRender32.pareto(o, ctx)
            "zx_portfolio"   -> ZxRender32.portfolio(o, ctx)
            "zx_bidcurve"    -> ZxRender32.bidcurve(o, ctx)
            "zx_sequential"  -> ZxRender32.sequential(o, ctx)
            "zx_stoprule"    -> ZxRender32.stoprule(o, ctx)
            "zx_hedge"       -> ZxRender32.hedge(o, ctx)
            "zx_contingent"  -> ZxRender32.contingent(o, ctx)
            "zx_escalate"    -> ZxRender32.escalate(o, ctx)
            "zx_commit"      -> ZxRender32.commit(o, ctx)
            // 第三十三批
            "zx_weather"    -> ZxRender33.weather(o, ctx)
            "zx_wxradar"    -> ZxRender33.wxradar(o, ctx)
            "zx_aqi"        -> ZxRender33.aqi(o, ctx)
            "zx_news"       -> ZxRender33.news(o, ctx)
            "zx_headline"   -> ZxRender33.headline(o, ctx)
            "zx_feed"       -> ZxRender33.feed(o, ctx)
            "zx_code"       -> ZxRender33.code(o, ctx)
            "zx_codetheme"  -> ZxRender33.codetheme(o, ctx)
            "zx_shell"      -> ZxRender33.shell(o, ctx)
            "zx_tty"        -> ZxRender33.tty(o, ctx)
            "zx_toolcall"   -> ZxRender33.toolcall(o, ctx)
            "zx_toolchain"  -> ZxRender33.toolchain(o, ctx)
            "zx_toolargs"   -> ZxRender33.toolargs(o, ctx)
            "zx_2048"       -> ZxRender33.game2048(o, ctx)
            "zx_tictactoe"  -> ZxRender33.tictactoe(o, ctx)
            // 第三十四批
            "zx_mapnav"     -> ZxRender34.mapnav(o, ctx)
            "zx_transit"    -> ZxRender34.transit(o, ctx)
            "zx_music"      -> ZxRender34.music(o, ctx)
            "zx_album"      -> ZxRender34.album(o, ctx)
            "zx_chatlog"    -> ZxRender34.chatlog(o, ctx)
            "zx_contact"    -> ZxRender34.contact(o, ctx)
            "zx_agenda"     -> ZxRender34.agenda(o, ctx)
            "zx_filemgr"    -> ZxRender34.filemgr(o, ctx)
            "zx_setup"      -> ZxRender34.setup(o, ctx)
            "zx_shop"       -> ZxRender34.shop(o, ctx)
            "zx_order"      -> ZxRender34.order(o, ctx)
            "zx_wallet"     -> ZxRender34.wallet(o, ctx)
            "zx_remote"     -> ZxRender34.remote(o, ctx)
            "zx_dialpad"    -> ZxRender34.dialpad(o, ctx)
            "zx_tasklist"   -> ZxRender34.tasklist(o, ctx)
            // 第三十五批
            "zx_fitness"      -> ZxRender35.fitness(o, ctx)
            "zx_medicine"     -> ZxRender35.medicine(o, ctx)
            "zx_parcel"       -> ZxRender35.parcel(o, ctx)
            "zx_ticket"       -> ZxRender35.ticket(o, ctx)
            "zx_study"        -> ZxRender35.study(o, ctx)
            "zx_plant"        -> ZxRender35.plant(o, ctx)
            "zx_parking"      -> ZxRender35.parking(o, ctx)
            "zx_habit"        -> ZxRender35.habit(o, ctx)
            "zx_subscription" -> ZxRender35.subscription(o, ctx)
            "zx_pet"          -> ZxRender35.pet(o, ctx)
            "zx_utilitymeter" -> ZxRender35.utilitymeter(o, ctx)
            "zx_sleep"        -> ZxRender35.sleep(o, ctx)
            "zx_coupon"       -> ZxRender35.coupon(o, ctx)
            "zx_appointment"  -> ZxRender35.appointment(o, ctx)
            // 第三十六批
            "zx_recipe"     -> ZxRender36.recipe(o, ctx)
            "zx_bookkeep"     -> ZxRender36.ledger(o, ctx)
            "zx_commute"    -> ZxRender36.commute(o, ctx)
            "zx_warranty"   -> ZxRender36.warranty(o, ctx)
            "zx_inspection" -> ZxRender36.inspection(o, ctx)
            "zx_examprep"   -> ZxRender36.examprep(o, ctx)
            "zx_tripkit"    -> ZxRender36.tripkit(o, ctx)
            "zx_inventory"  -> ZxRender36.inventory(o, ctx)
            "zx_recycle"    -> ZxRender36.recycle(o, ctx)
            "zx_carservice" -> ZxRender36.carservice(o, ctx)
            "zx_expiry"     -> ZxRender36.expiry(o, ctx)
            "zx_splitbill"  -> ZxRender36.splitbill(o, ctx)
            "zx_firstaid"   -> ZxRender36.firstaid(o, ctx)
            "zx_sizing"     -> ZxRender36.sizing(o, ctx)
            "zx_gift"       -> ZxRender36.gift(o, ctx)
            // 第三十七批
            "zx_renovation" -> ZxRender37.renovation(o, ctx)
            "zx_clinic"     -> ZxRender37.clinic(o, ctx)
            "zx_rental"     -> ZxRender37.rental(o, ctx)
            "zx_repair"     -> ZxRender37.repair(o, ctx)
            "zx_credential" -> ZxRender37.credential(o, ctx)
            "zx_ship"     -> ZxRender37.parcel(o, ctx)
            "zx_interview"  -> ZxRender37.interview(o, ctx)
            "zx_visa"       -> ZxRender37.visa(o, ctx)
            "zx_insurance"  -> ZxRender37.insurance(o, ctx)
            "zx_reimburse"  -> ZxRender37.reimburse(o, ctx)
            "zx_relocation" -> ZxRender37.relocation(o, ctx)
            "zx_booking"    -> ZxRender37.booking(o, ctx)
            // 第三十八批
            "zx_escort"       -> ZxRender38.escort(o, ctx)
            "zx_lease"        -> ZxRender38.lease(o, ctx)
            "zx_overrun"      -> ZxRender38.overrun(o, ctx)
            "zx_vet"          -> ZxRender38.vet(o, ctx)
            "zx_photospec"    -> ZxRender38.photospec(o, ctx)
            "zx_claim"        -> ZxRender38.claim(o, ctx)
            "zx_ivreview"     -> ZxRender38.ivreview(o, ctx)
            "zx_claimprog"    -> ZxRender38.claimprog(o, ctx)
            "zx_refile"       -> ZxRender38.refile(o, ctx)
            "zx_damage"       -> ZxRender38.damage(o, ctx)
            "zx_slotconflict" -> ZxRender38.slotconflict(o, ctx)
            "zx_lostdoc"      -> ZxRender38.lostdoc(o, ctx)
            // 第三十九批
            "zx_handoff"      -> ZxRender39.handoff(o, ctx)
            "zx_moveout"      -> ZxRender39.moveout(o, ctx)
            "zx_vehiclecare"  -> ZxRender39.vehiclecare(o, ctx)
            "zx_medremote"    -> ZxRender39.medremote(o, ctx)
            "zx_housedeal"    -> ZxRender39.housedeal(o, ctx)
            "zx_schoolup"     -> ZxRender39.schoolup(o, ctx)
            "zx_carerota"     -> ZxRender39.carerota(o, ctx)
            "zx_taxxborder"   -> ZxRender39.taxxborder(o, ctx)
            "zx_rentreturn"   -> ZxRender39.rentreturn(o, ctx)
            "zx_dutyrota"     -> ZxRender39.dutyrota(o, ctx)
            "zx_annualaudit"  -> ZxRender39.annualaudit(o, ctx)
            "zx_liability"    -> ZxRender39.liability(o, ctx)
            // 第四十批
            "zx_housekeep"    -> ZxRender40.housekeep(o, ctx)
            "zx_finalpay"     -> ZxRender40.finalpay(o, ctx)
            "zx_fosterdaily"  -> ZxRender40.fosterdaily(o, ctx)
            "zx_wardhand"     -> ZxRender40.wardhand(o, ctx)
            "zx_schoolnote"   -> ZxRender40.schoolnote(o, ctx)
            "zx_sublet"       -> ZxRender40.sublet(o, ctx)
            "zx_inspecthand"  -> ZxRender40.inspecthand(o, ctx)
            "zx_projhandover" -> ZxRender40.projhandover(o, ctx)
            "zx_stocktake"    -> ZxRender40.stocktake(o, ctx)
            "zx_offboard"     -> ZxRender40.offboard(o, ctx)
            "zx_revoke"       -> ZxRender40.revoke(o, ctx)
            "zx_warrtrack"    -> ZxRender40.warrtrack(o, ctx)
            else -> ZxNest.render(o, ctx, 0)
        }
    }

    /** 展开态下的兜底空界面 */
    @Composable
    fun empty() {
        Box(Modifier.fillMaxSize().background(ZxT.Surface), contentAlignment =
            androidx.compose.ui.Alignment.Center) {
            Text("没有展开中的内容", fontSize = 13.sp, color = ZxT.Text3,
                 modifier = Modifier.clip(RoundedCornerShape(9.dp))
                     .background(ZxT.Surface2).padding(16.dp, 12.dp))
        }
    }
}
