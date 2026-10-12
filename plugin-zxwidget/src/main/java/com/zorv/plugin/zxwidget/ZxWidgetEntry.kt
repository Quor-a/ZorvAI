package com.zorv.plugin.zxwidget

import com.ai.assistance.quro.plugin.contract.*
import com.ai.assistance.quro.plugin.dsl.plugin
import com.ai.assistance.quro.plugin.extension.SettingKind
import org.json.JSONObject

/**
 * zx_widget · 气泡内可交互 UI 组件族（终极版）
 *
 * 宿主：Zorv AI (com.ai.assistance.quro) >= 1.0.89 ｜ Apache-2.0
 *
 * 占 10 个扩展点，14 个 AI 工具，12 个实体组件，4 种嵌套容器，6 个内联控件。
 *
 * 相对上一版新增的 5 项能力：
 *   1. 跨卡联动   —— {{ref.cardId.path}} 引用 + DAG 传播 + 环检测
 *   2. 嵌套容器   —— tabs / accordion / split / carousel，可递归到 4 层
 *   3. 撤销栈     —— 卡片上的每次交互都能撤销，跨会话持久化
 *   4. 卡片转工具 —— 用户填的表自动注册成 AI 可调用的新工具
 *   5. 跨设备渲染 —— 远端 Agent 经 ACI 往本机对话气泡画卡
 */
class ZxWidgetEntry : PluginEntry {

    override fun onCreate(ctx: PluginContext) {
        plugin(ctx) {

            /* ══════════════════════════════════════════════════
               一、AI_TOOL —— 14 个工具
               ══════════════════════════════════════════════════ */

            aiTool(
                name = "zx_render",
                description = """
                    在对话气泡内渲染一个可交互 UI 组件。回答含结构化数据（对比、参数收集、
                    多步执行、代码改动、表格编辑、图表、待授权操作）时使用，不要用纯文本罗列。
                    component 取：zx_compare / zx_form / zx_timeline / zx_choice / zx_table /
                    zx_chart / zx_kanban / zx_diff / zx_terminal / zx_approval / zx_media / zx_stepper /
                    zx_metrics / zx_gantt / zx_tree / zx_flow / zx_calendar / zx_rating /
                    zx_editor / zx_audio / zx_heatmap / zx_toggle / zx_qr / zx_stack /
                    zx_radar / zx_rank / zx_org / zx_sankey / zx_gauge / zx_range /
                    zx_color / zx_breadcrumb / zx_pager / zx_countdown / zx_sign / zx_board /
                    zx_poll / zx_transfer / zx_chips / zx_pin / zx_dial / zx_joystick /
                    zx_timepicker / zx_slidergroup / zx_sortable / zx_cardflip / zx_comment / zx_seek /
                    zx_wordcloud / zx_funnel / zx_treemap / zx_chord / zx_sparkline / zx_kanban /
                    zx_tour / zx_swipe / zx_matrix / zx_autocomplete / zx_spin / zx_barcode /
                    zx_dualist / zx_scrubber / zx_bento / zx_minmap / zx_statelist / zx_legend /
                    zx_radiogroup / zx_checklist / zx_avatargroup / zx_meter / zx_ring / zx_stat /
                    zx_swatch / zx_notice / zx_price / zx_keyvalue / zx_inputmask / zx_datalist /
                    zx_waterfall / zx_pyramid / zx_bubble / zx_venn / zx_candle / zx_dag /
                    zx_swimlane / zx_mindmap / zx_gallery / zx_wizard / zx_spreadsheet / zx_conversation /
                    zx_calheat / zx_matrixheat / zx_polar / zx_voice / zx_stars / zx_react / zx_scale /
                    zx_tags / zx_flipnum / zx_skeleton / zx_badges /
                    zx_rich / zx_quote / zx_filecard / zx_callout / zx_hint / zx_seat /
                    zx_floor / zx_banner / zx_divider / zx_settings / zx_command / zx_empty /
                    zx_orgchart / zx_sankey / zx_gauge / zx_sparkgrid / zx_breadcrumb / zx_pagination /
                    zx_tabbar / zx_searchbar / zx_chips / zx_colorpick / zx_stepper / zx_treeview /
                    zx_diffview / zx_baseline / zx_logview / zx_console / zx_depgraph / zx_datamap /
                    zx_conflict / zx_activity / zx_timerange / zx_gridform / zx_scorecard / zx_waveedit /
                    zx_histogram / zx_boxplot / zx_pareto / zx_control / zx_sunburst / zx_video /
                    zx_docview / zx_model3d / zx_schedule / zx_nps / zx_export / zx_bargauge /
                    zx_route / zx_network / zx_annotate / zx_revision / zx_bullet / zx_icicle /
                    zx_cohort / zx_slope / zx_dumbbell / zx_waffle / zx_ridgeline / zx_steparea /
                    zx_weekgrid / zx_pulse / zx_scoreboard / zx_abtest / zx_statemachine /
                    zx_checkflow / zx_branch / zx_sla / zx_repost / zx_thread /
                    zx_splitflap / zx_runbook /
                    zx_geo / zx_formula / zx_tokens / zx_ast / zx_hexgrid / zx_spiral /
                    zx_marquee / zx_odometer / zx_radial / zx_isometric / zx_spectrum / zx_bezier /
                    zx_flowedit / zx_crop / zx_mapping / zx_ratio / zx_deviation / zx_timeline2 /
                    zx_scale2 / zx_mirror / zx_dial2 / zx_matrix2 / zx_ladder / zx_slot /
                    zx_traceback / zx_seq / zx_explode / zx_critical / zx_evidence / zx_confront /
                    zx_matrix3 / zx_receipt / zx_ledger / zx_gauntlet / zx_chain / zx_bracket /
                    zx_contour / zx_stream / zx_horizon / zx_parallel / zx_beeswarm / zx_violin /
                    zx_glyph / zx_arc / zx_mosaic / zx_trail / zx_pictogram / zx_dotstrip /
                    zx_align / zx_jsondiff / zx_threeway / zx_sankey2 / zx_split / zx_switch /
                    zx_lane / zx_bullet2 / zx_ring2 / zx_stepflow / zx_annotate / zx_dial3 /
                    zx_cmdhist / zx_callstack / zx_uptime / zx_playback / zx_pipe / zx_corr /
                    zx_radar2 / zx_choro / zx_toc / zx_glossary / zx_survey / zx_punch /
                    zx_decision / zx_tradeoff / zx_fallback / zx_retry / zx_brush / zx_blast /
                    zx_batch / zx_priority / zx_probe / zx_inspect / zx_remap / zx_rollup /
                    zx_qq / zx_forest / zx_bland / zx_survival / zx_ecdf / zx_lorenz /
                    zx_regress / zx_scatter / zx_seqdiff / zx_cmdpal / zx_span / zx_tolerance /
                    zx_decompose / zx_boot / zx_perm / zx_test / zx_roc / zx_lift /
                    zx_pscan / zx_vardec / zx_mds / zx_geoflow / zx_violincmp / zx_survcmp /
                    zx_causal / zx_did / zx_rdd / zx_iv / zx_sense / zx_psm / zx_balance /
                    zx_missing / zx_seqtest / zx_bayes / zx_anomaly / zx_power /
                    zx_forecast / zx_calib / zx_optline / zx_decision / zx_riskreturn /
                    zx_utility / zx_bandit / zx_explain / zx_cfactual / zx_cforest /
                    zx_alloc / zx_abmon /
                    zx_rollout / zx_canary / zx_slo / zx_healthcheck / zx_incident /
                    zx_rca / zx_regret / zx_backtest / zx_drift / zx_alert /
                    zx_costtrack / zx_feedback /
                    zx_capacity / zx_queue / zx_loadtest / zx_topology / zx_changerisk /
                    zx_playbook / zx_escalation / zx_postmortem / zx_knowledge /
                    zx_decisionlog / zx_debt / zx_checkpoint /
                    zx_unitecon / zx_costattrib / zx_lineage / zx_impact / zx_dataclass /
                    zx_retention / zx_workload / zx_bottleneck / zx_expresult /
                    zx_compliance / zx_audittrail / zx_accessreview /
                    zx_scenario / zx_montecarlo / zx_stress / zx_tornado / zx_whatif /
                    zx_breakeven / zx_systemdyn / zx_markov / zx_optpath / zx_payoff /
                    zx_reversesolve / zx_realoption /
                    zx_bargain / zx_auction / zx_multiissue / zx_pareto / zx_portfolio /
                    zx_bidcurve / zx_sequential / zx_stoprule / zx_hedge /
                    zx_contingent / zx_escalate / zx_commit /
                    zx_weather / zx_wxradar / zx_aqi / zx_news / zx_headline / zx_feed /
                    zx_code / zx_codetheme / zx_shell / zx_tty /
                    zx_toolcall / zx_toolchain / zx_toolargs / zx_2048 / zx_tictactoe /
                    zx_mapnav / zx_transit / zx_music / zx_album / zx_chatlog / zx_contact /
                    zx_agenda / zx_filemgr / zx_setup / zx_shop / zx_order / zx_wallet /
                    zx_remote / zx_dialpad / zx_tasklist /
                    zx_fitness / zx_medicine / zx_parcel / zx_ticket / zx_study / zx_plant /
                    zx_parking / zx_habit / zx_subscription / zx_pet / zx_utilitymeter /
                    zx_sleep / zx_coupon / zx_appointment /
                    zx_recipe / zx_ledger / zx_commute / zx_warranty / zx_inspection /
                    zx_examprep / zx_tripkit / zx_inventory / zx_recycle / zx_carservice /
                    zx_expiry / zx_splitbill / zx_firstaid / zx_sizing / zx_gift /
                    zx_renovation / zx_clinic / zx_rental / zx_repair / zx_credential /
                    zx_parcel / zx_interview / zx_visa / zx_insurance / zx_reimburse /
                    zx_relocation / zx_booking /
                    zx_escort / zx_lease / zx_overrun / zx_vet / zx_photospec / zx_claim /
                    zx_handoff / zx_moveout / zx_vehiclecare / zx_medremote / zx_housedeal /
                    zx_schoolup / zx_carerota / zx_taxxborder / zx_rentreturn / zx_dutyrota /
                    zx_annualaudit / zx_liability /
                    zx_housekeep / zx_finalpay / zx_fosterdaily / zx_wardhand /
                    zx_schoolnote / zx_sublet / zx_inspecthand / zx_projhandover /
                    zx_stocktake / zx_offboard / zx_revoke / zx_warrtrack /
                    zx_ivreview / zx_claimprog / zx_refile / zx_damage / zx_slotconflict /
                    zx_lostdoc /
                    zx_health / zx_costtrack / zx_alertitem / zx_deploy /
                    zx_hotspot / zx_stepper / zx_segmented / zx_mention / zx_dropzone / zx_resizable /
                    容器：zx_tabs / zx_accordion / zx_split / zx_carousel / zx_grid / zx_master / zx_stagger
                """.trimIndent()
            ) {
                param("component", ParamType.STRING, "组件类型", required = true)
                param("dsl", ParamType.STRING, "quro-ui JSON 节点", required = true)
                param("summary", ParamType.STRING, "组件上方一句话说明")
                param("id", ParamType.STRING, "卡片 id，供其它卡片用 {{ref.id.path}} 引用")
                execute { args ->
                    val component = args.string("component")
                    val dsl = args.string("dsl")
                    val id = args.string("id").ifBlank { "zx_${System.currentTimeMillis()}" }

                    val check = ZxDsl.validate(component, dsl)
                    if (!check.ok) return@execute ToolResult.text(
                        "DSL 校验失败：${check.reason}\n修正后重新调用 zx_render。"
                    )

                    // 登记进依赖图，别的卡片就能用 {{ref.id.xxx}} 引用它
                    ZxRef.put(id, component, dsl)
                    // 引用先解析一遍，再推给渲染层
                    ZxCardStore.emit(args.string("summary"), component,
                                     ZxRef.resolve(dsl, id))
                    ToolResult.text("已渲染 $component（id=$id）。其它卡片可用 {{ref.$id.字段名}} 引用它的值。")
                }
            }

            aiTool(
                name = "zx_link",
                description = "让一张卡片引用另一张卡片的值，建立联动。源卡一改，引用它的卡自动重算。"
            ) {
                param("id", ParamType.STRING, "本卡 id", required = true)
                param("dsl", ParamType.STRING, "含 {{ref.其它卡id.路径}} 的 DSL", required = true)
                execute { args ->
                    val r = ZxRef.checkLink(args.string("id"), args.string("dsl"))
                    if (!r.ok) ToolResult.text(r.reason)
                    else {
                        val node = ZxRef.get(args.string("id"))!!
                        ZxCardStore.emit(null, node.component, ZxRef.resolve(node.dsl, node.id))
                        ToolResult.text("${r.reason}。示例写法：${ZxRef.sampleLink()}")
                    }
                }
            }

            aiTool(
                name = "zx_patch",
                description = "增量更新已在气泡里的组件（表格加行、时间轴推进、终端追加输出），不重画整张卡。"
            ) {
                param("component", ParamType.STRING, "目标组件", required = true)
                param("patch", ParamType.STRING, "增量 JSON", required = true)
                execute { args ->
                    ZxCardStore.patch(args.string("component"), args.string("patch"))
                    ToolResult.text("已增量更新 ${args.string("component")}。")
                }
            }

            aiTool(name = "zx_select",
                description = "用户在卡片上做出选择后由 UI 回调触发。把 choice 纳入推理，不要重复提问。") {
                param("choice", ParamType.STRING, "选中的 key", required = true)
                param("source", ParamType.STRING, "来源组件 id")
                execute { args -> ToolResult.text("用户已选择：${args.string("choice")}（来自 ${args.string("source")}）") }
            }

            aiTool(name = "zx_form_submit",
                description = "用户填完参数卡并提交后触发。payload 是可直接使用的参数集。") {
                param("payload", ParamType.STRING, "JSON 参数集", required = true)
                execute { args -> ToolResult.text("已收到参数：${args.string("payload")}。据此继续，不要再追问这些字段。") }
            }

            aiTool(name = "zx_table_commit",
                description = "用户编辑完可编辑表格并提交后触发。rows 为完整行数据。") {
                param("rows", ParamType.STRING, "完整行数据 JSON", required = true)
                execute { args -> ToolResult.text("表格已提交：${args.string("rows")}") }
            }

            aiTool(name = "zx_diff_apply",
                description = "用户在代码差异卡上接受或拒绝改动后触发。") {
                param("accepted", ParamType.STRING, "接受的 hunk 序号，逗号分隔", required = true)
                param("path", ParamType.STRING, "目标文件路径")
                execute { args ->
                    val n = args.string("accepted").split(",").filter { it.isNotBlank() }.size
                    ToolResult.text("已接受 $n 个改动块，目标 ${args.string("path")}。")
                }
            }

            aiTool(name = "zx_approve",
                description = "用户在审批卡上授权后触发。仅在用户显式同意后才执行对应特权操作。") {
                param("op", ParamType.STRING, "操作标识", required = true)
                param("level", ParamType.STRING, "特权等级 L1-L5", required = true)
                execute { args -> ToolResult.text("用户已授权 ${args.string("op")}（${args.string("level")}），可以执行。") }
            }

            aiTool(name = "zx_undo",
                description = "撤销气泡卡片上的上一次交互（改单元格、移动看板卡、切换改动块）。redo=true 则重做。") {
                param("redo", ParamType.BOOLEAN, "true 表示重做而非撤销")
                execute { args ->
                    ToolResult.text(if (args.optBoolean("redo", false)) ZxStore.redo() else ZxStore.undo())
                }
            }

            aiTool(name = "zx_nest",
                description = "在一个容器里放多个组件。container 取 zx_tabs（分页）/ zx_accordion（折叠）/ zx_split（并排两栏）/ zx_carousel（横滑）。子节点必须是完整组件 DSL 且带 tab 字段。"
            ) {
                param("container", ParamType.STRING, "容器类型", required = true)
                param("children", ParamType.STRING, "子节点 JSON 数组", required = true)
                execute { args ->
                    val root = org.json.JSONObject()
                        .put("type", args.string("container"))
                        .put("children", org.json.JSONArray(args.string("children")))
                    val (ok, why) = ZxNest.validate(root)
                    if (!ok) ToolResult.text("容器校验失败：$why")
                    else { ZxCardStore.emit(null, args.string("container"), root.toString())
                           ToolResult.text("已渲染容器 ${args.string("container")}。") }
                }
            }

            aiTool(name = "zx_gen_tool",
                description = "把用户填好的表格卡片注册成一个新的 AI 工具。填完表调用这个，下一轮起你就能用这个工具了。"
            ) {
                param("card_id", ParamType.STRING, "表格卡片 id", required = true)
                param("name", ParamType.STRING, "工具名，zx_ 或 u_ 开头", required = true)
                param("description", ParamType.STRING, "什么时候该调它，不少于 8 字", required = true)
                param("action", ParamType.STRING, "执行的动作，见白名单", required = true)
                param("template", ParamType.STRING, "执行模板，用 {{参数名}} 占位", required = true)
                execute { args ->
                    val meta = org.json.JSONObject()
                        .put("name", args.string("name"))
                        .put("description", args.string("description"))
                        .put("action", args.string("action"))
                        .put("template", args.string("template"))
                    val r = ZxToolGen.fromTable(args.string("card_id"), meta)
                    if (!r.ok) ToolResult.text("生成失败：${r.reason}")
                    else ToolResult.text("已注册工具 ${r.toolName}，下一轮起可用。\n${r.reason}")
                }
            }

            aiTool(name = "zx_remote_render",
                description = "渲染远端设备/远端 Agent 发来的卡片。会校验配对与 DSL，未配对直接拒绝。"
            ) {
                param("peer", ParamType.STRING, "来源设备 id", required = true)
                param("component", ParamType.STRING, "组件类型", required = true)
                param("dsl", ParamType.STRING, "quro-ui JSON", required = true)
                execute { args ->
                    val r = ZxRemote.request(args.string("peer"), args.string("component"), args.string("dsl"))
                    ToolResult.text(r.reason)
                }
            }

            aiTool(name = "zx_pair",
                description = "配对一台设备，允许它往本对话框画卡。block=true 则拉黑。") {
                param("peer", ParamType.STRING, "设备 id", required = true)
                param("name", ParamType.STRING, "设备名")
                param("block", ParamType.BOOLEAN, "true 表示拉黑")
                execute { args ->
                    val id = args.string("peer")
                    when {
                        args.optBoolean("block", false) -> { ZxRemote.block(id); ToolResult.text("已拉黑 $id") }
                        else -> { ZxRemote.pair(id, args.string("name").ifBlank { id })
                                  ToolResult.text("已配对 ${args.string("name").ifBlank { id }}") }
                    }
                }
            }

            aiTool(name = "zx_state",
                description = "保存或还原卡片交互状态。save 落盘当前所有卡的用户输入，restore 在会话重建时还原。"                ) {
                param("op", ParamType.STRING, "save / restore / clear", required = true)
                param("session", ParamType.STRING, "会话 id", required = true)
                execute { args ->
                    when (args.string("op")) {
                        "save" -> { ZxStore.saveAll(args.string("session"))
                                    ToolResult.text("已保存本轮卡片状态。") }
                        "restore" -> { val n = ZxStore.restore(args.string("session"))
                                       ToolResult.text("已还原 $n 张卡片的交互状态。") }
                        else -> { ZxStore.clearSession(args.string("session"))
                                  ToolResult.text("已清空该会话的卡片状态与撤销栈。") }
                    }
                }
            }

            /* ── 沙箱 ────────────────────────────────────────── */
            aiTool(name = "zx_caps",
                description = "查看或修改卡片权限矩阵。不传 op 时打印矩阵；op=ceiling 时设置用户授权上限（R0/R1/R2/R3）。"
            ) {
                param("op", ParamType.STRING, "留空打印矩阵 / ceiling 设置上限")
                param("level", ParamType.STRING, "R0 / R1 / R2 / R3")
                execute { args ->
                    if (args.string("op") == "ceiling") {
                        val r = ZxSandbox.Risk.entries.firstOrNull { it.name == args.string("level") }
                            ?: return@execute ToolResult.text("等级只能是 R0/R1/R2/R3，实际 ${args.string("level")}")
                        ZxSandbox.setCeiling(r)
                        ToolResult.text("用户授权上限已设为 ${r.label}。超过此等级的卡片操作将被拦截。")
                    } else ToolResult.text(ZxSandbox.matrix())
                }
            }

            /* ── 版本树 ──────────────────────────────────────── */
            aiTool(name = "zx_version",
                description = "卡片版本管理。op=commit 提交新版本 / tree 打印版本树 / diff 比对两版 / rollback 回溯到某版。"
            ) {
                param("op", ParamType.STRING, "commit / tree / diff / rollback", required = true)
                param("card", ParamType.STRING, "卡片 id", required = true)
                param("from", ParamType.STRING, "diff 的起始版本")
                param("to", ParamType.STRING, "diff 的目标版本 / rollback 的目标版本")
                param("dsl", ParamType.STRING, "commit 时的新 DSL")
                param("summary", ParamType.STRING, "commit 时的一句话说明")
                execute { args ->
                    val card = args.string("card")
                    when (args.string("op")) {
                        "commit" -> {
                            val dsl = args.string("dsl")
                            if (dsl.isBlank()) return@execute ToolResult.text("commit 必须给 dsl")
                            val v = ZxVersion.commit(card, dsl, args.string("summary").ifBlank { "未命名修订" })
                            ToolResult.text("已提交 ${v.id}（${v.summary}）。当前共 ${ZxVersion.list(card).size} 个版本。")
                        }
                        "tree" -> {
                            ZxCardStore.emit(null, "zx_terminal", ZxVersion.treeAsTerminal(card))
                            ToolResult.text("已输出版本树。")
                        }
                        "diff" -> {
                            val a = args.string("from"); val b = args.string("to")
                            if (a.isBlank() || b.isBlank())
                                return@execute ToolResult.text("diff 需要 from 和 to")
                            ZxCardStore.emit(null, "zx_diff", ZxVersion.diffAsCard(card, a, b))
                            ToolResult.text("已输出 $a → $b 的差异卡。")
                        }
                        "rollback" -> {
                            val r = ZxVersion.rollback(card, args.string("to"))
                            ToolResult.text(r.reason)
                        }
                        else -> ToolResult.text("op 只能是 commit / tree / diff / rollback")
                    }
                }
            }

            /* ── 渲染路由 ────────────────────────────────────── */
            aiTool(name = "zx_route",
                description = "由环境决定这一轮该画什么组件，而不是模型硬选。离线/窄屏/token 紧张时自动降级。返回组件与降级链。"
            ) {
                param("intent", ParamType.STRING, "compare/trend/collect/progress/code/decide/output/authorize/preview", required = true)
                param("data_points", ParamType.NUMBER, "本轮数据点数量")
                param("explain", ParamType.BOOLEAN, "是否把决策过程输出到终端卡")
                execute { args ->
                    val env = ZxRouter.snapshot()   // 环境条件由宿主注入，不猜
                    val ctx = ZxRouter.Ctx(
                        online = env.online,
                        localEngine = env.localEngine,
                        tokenBudget = env.tokenBudget,
                        widthDp = env.widthDp,
                        dataPoints = args.string("data_points").toIntOrNull() ?: 0
                    )
                    val d = ZxRouter.decide(args.string("intent"), ctx)
                    if (args.optBoolean("explain", false))
                        ZxCardStore.emit(null, "zx_terminal", ZxRouter.explainAsTerminal(args.string("intent"), ctx))
                    ZxAudit.log(ZxAudit.Kind.ROUTE, d.component, "intent=${args.string("intent")} score=${d.score}")
                    ToolResult.text(
                        "建议画 ${d.component}（${"%.2f".format(d.score)}）。\n${d.reason}\n" +
                        if (d.fallback.size > 1) "降级链：${d.fallback.joinToString(" → ")}" else ""
                    )
                }
            }

            /* ── 审计 ────────────────────────────────────────── */
            aiTool(name = "zx_audit",
                description = "导出卡片操作的审计流水。sensitive=true 只看敏感项（授权/生成工具/远端渲染/沙箱拦截）。"
            ) {
                param("sensitive", ParamType.BOOLEAN, "只看敏感项")
                execute { args ->
                    ZxCardStore.emit(null, "zx_terminal",
                        ZxAudit.exportAsTerminal(args.optBoolean("sensitive", false)))
                    ToolResult.text("已输出审计流水。${ZxAudit.summary()}")
                }
            }

            /* ── 模板 ────────────────────────────────────────── */
            aiTool(name = "zx_template",
                description = "模板库。op=save 存模板 / use 实例化 / list 列出。模板用 {{\$参数名}} 占位，实例化时填充并校验。"
            ) {
                param("op", ParamType.STRING, "save / use / list", required = true)
                param("name", ParamType.STRING, "模板名")
                param("component", ParamType.STRING, "save 时的组件类型")
                param("dsl", ParamType.STRING, "save 时的 DSL")
                param("desc", ParamType.STRING, "save 时的说明")
                param("args", ParamType.STRING, "use 时的参数 JSON")
                execute { args ->
                    when (args.string("op")) {
                        "save" -> {
                            val r = ZxTemplate.save(args.string("name"), args.string("component"),
                                                    args.string("dsl"), args.string("desc"))
                            ToolResult.text(r.reason)
                        }
                        "use" -> {
                            val raw = args.string("args")
                            val map = linkedMapOf<String, String>()
                            runCatching {
                                val o = org.json.JSONObject(raw)
                                o.keys().forEach { map[it] = o.optString(it) }
                            }
                            val r = ZxTemplate.instantiate(args.string("name"), map)
                            if (!r.ok) ToolResult.text(r.reason)
                            else {
                                ZxCardStore.emit(null,
                                    ZxTemplate.get(args.string("name"))!!.component, r.reason)
                                ToolResult.text("已按模板 ${args.string("name")} 实例化并渲染。")
                            }
                        }
                        else -> {
                            ZxCardStore.emit(null, "zx_terminal", ZxTemplate.listAsTerminal())
                            ToolResult.text("已列出模板。")
                        }
                    }
                }
            }

            /* ── 事务 ────────────────────────────────────────── */
            aiTool(name = "zx_tx",
                description = "跨卡事务。改一组卡片时用它：要么全成，要么全回滚。op=begin/set/commit/rollback/status。"
            ) {
                param("op", ParamType.STRING, "begin / set / commit / rollback / status", required = true)
                param("card", ParamType.STRING, "set 时的卡片 id")
                param("path", ParamType.STRING, "set 时的字段路径，如 rows[0][1]")
                param("value", ParamType.STRING, "set 时的新值")
                execute { args ->
                    when (args.string("op")) {
                        "begin" -> { ZxTx.begin(); ToolResult.text("事务已开启。改完记得 zx_tx(op=commit)。") }
                        "set" -> {
                            val r = ZxTx.set(args.string("card"), args.string("path"), args.string("value"))
                            ToolResult.text(r.reason)
                        }
                        "commit" -> ToolResult.text(ZxTx.commit().reason)
                        "rollback" -> ToolResult.text(ZxTx.rollback("模型主动回滚").reason)
                        else -> {
                            ZxCardStore.emit(null, "zx_terminal", ZxTx.statusAsTerminal())
                            ToolResult.text("已输出事务状态。")
                        }
                    }
                }
            }

            /* ── 意图协议 ────────────────────────────────────── */
            aiTool(name = "zx_intent",
                description = "只说要什么，本地编译成卡片。比手写 DSL 省 10 倍 token。intent 取 compare/trend/guide，其余字段按类型给。"
            ) {
                param("json", ParamType.STRING, "意图 JSON", required = true)
                param("explain", ParamType.BOOLEAN, "是否把编译过程输出到终端卡")
                execute { args ->
                    val raw = args.string("json")
                    val c = ZxIntent.compile(raw)
                    if (args.optBoolean("explain", false))
                        ZxCardStore.emit(null, "zx_terminal", ZxIntent.explainAsTerminal(raw))
                    if (!c.ok) ToolResult.text(c.reason)
                    else {
                        val key = ZxCache.put("intent", c.component, c.dsl, emptySet())
                        ZxCardStore.emit("意图编译", c.component, c.dsl)
                        ZxAudit.log(ZxAudit.Kind.INTENT, c.template, "编译 ${c.component} · 省 ${c.savedTokens} token")
                        ToolResult.text("已编译为 ${c.component}（模板 ${c.template}，省约 ${c.savedTokens} token）。\n${c.reason}")
                    }
                }
            }

            /* ── DSL 自愈 ────────────────────────────────────── */
            aiTool(name = "zx_heal",
                description = "DSL 校验失败时先尝试本地修复（全角/字段名/数字类型/单位/中文数字），修好就不用重发。reason 传上次失败原因。"
            ) {
                param("component", ParamType.STRING, "组件类型或模板名", required = true)
                param("reason", ParamType.STRING, "上一次校验失败的原因")
                param("args", ParamType.STRING, "参数 JSON（模板场景）或 {\"dsl\":\"...\"}（直接修 DSL）")
                execute { args ->
                    val map = linkedMapOf<String, String?>()
                    runCatching {
                        val o = org.json.JSONObject(args.string("args"))
                        o.keys().forEach { map[it] = o.optString(it) }
                    }
                    val r = ZxHeal.attempt(args.string("component"), args.string("reason"), map)
                    ZxCardStore.emit(null, "zx_terminal",
                        ZxHeal.explainAsTerminal(args.string("component"), args.string("reason"), map))
                    if (r.ok) {
                        ZxAudit.log(ZxAudit.Kind.HEAL, args.string("component"), "自愈成功：${r.fixes.joinToString("；")}")
                        ToolResult.text("已自愈，无需重发。修改：${r.fixes.joinToString("；")}")
                    } else {
                        ZxAudit.log(ZxAudit.Kind.HEAL, args.string("component"), "自愈失败：${r.residual}")
                        ToolResult.text("本地修不好，需要重发。残留问题：${r.residual}")
                    }
                }
            }

            /* ── 渲染缓存 ────────────────────────────────────── */
            aiTool(name = "zx_cache",
                description = "渲染缓存管理。op=stats 看命中率 / clear 清空 / invalidate 作废某张卡的下游缓存。"
            ) {
                param("op", ParamType.STRING, "stats / clear / invalidate", required = true)
                param("card", ParamType.STRING, "invalidate 时的卡片 id")
                execute { args ->
                    when (args.string("op")) {
                        "clear" -> { ZxCache.clear(); ToolResult.text("缓存已清空。") }
                        "invalidate" -> {
                            val n = ZxCache.invalidate(args.string("card"))
                            ToolResult.text("已作废 ${args.string("card")} 相关的 $n 条缓存。")
                        }
                        else -> {
                            ZxCardStore.emit(null, "zx_terminal", ZxCache.statsAsTerminal())
                            ToolResult.text("当前 ${ZxCache.size()} 条，命中率 ${"%.1f".format(ZxCache.hitRate() * 100)}%。")
                        }
                    }
                }
            }

            /* ── 多端同步 ────────────────────────────────────── */
            aiTool(name = "zx_sync",
                description = "多端卡片状态同步（CRDT，离线编辑后合并不丢改动）。op=preview 先看冲突 / import 合并 / export 导出增量。"
            ) {
                param("op", ParamType.STRING, "preview / import / export", required = true)
                param("payload", ParamType.STRING, "import 时的同步包 JSON")
                execute { args ->
                    when (args.string("op")) {
                        "export" -> ToolResult.text(ZxSync.exportDelta())
                        "preview" -> ToolResult.text(ZxSync.previewConflict(args.string("payload")).reason)
                        "import" -> ToolResult.text(ZxSync.importDelta(args.string("payload")).reason)
                        else -> ToolResult.text("op 只能是 preview / import / export")
                    }
                }
            }

            /* ── 卡片自评 ────────────────────────────────────── */
            aiTool(name = "zx_review",
                description = "渲染后自评可读性（高度溢出/列宽/密度/横滚/可点区），不达标自动降级为更简单的组件。不传 metrics 则打印阈值指引。"
            ) {
                param("component", ParamType.STRING, "被评的组件")
                param("metrics", ParamType.STRING, "实测尺寸 JSON：{w,h,rows,cols,chars}")
                execute { args ->
                    val comp = args.string("component")
                    if (comp.isBlank()) return@execute ToolResult.text(ZxReview.guide())
                    val raw = args.string("metrics")
                    val o = runCatching { org.json.JSONObject(raw) }.getOrNull()
                        ?: return@execute ToolResult.text("metrics 需要是 JSON：{w,h,rows,cols,chars}\n${ZxReview.guide()}")
                    val m = ZxReview.Metrics(
                        widthDp = o.optInt("w", 360),
                        heightDp = o.optInt("h", 200),
                        rows = o.optInt("rows", 0),
                        cols = o.optInt("cols", 0),
                        textChars = o.optInt("chars", 0)
                    )
                    val r = ZxReview.review(comp, m)
                    ZxCardStore.emit(null, "zx_terminal", ZxReview.explainAsTerminal(comp, m, r))
                    ToolResult.text(if (r.ok) "自评通过（${"%.2f".format(r.score)}）。"
                                    else "自评不通过（${"%.2f".format(r.score)}）：${r.suggestion}")
                }
            }

            /* ── 无障碍 ──────────────────────────────────────── */
            aiTool(name = "zx_a11y",
                description = "无障碍检查：无标签控件 / 仅颜色表意 / 对比度不足 / 表格缺表头 / 选项重名。不影响渲染，只影响谁能用。"
            ) {
                param("component", ParamType.STRING, "组件类型")
                param("dsl", ParamType.STRING, "卡片 DSL")
                execute { args ->
                    val comp = args.string("component")
                    if (comp.isBlank()) return@execute ToolResult.text(ZxA11y.guide())
                    val dsl = args.string("dsl")
                    val r = ZxA11y.check(comp, dsl.ifBlank { "{}" })
                    ZxCardStore.emit(null, "zx_terminal", ZxA11y.reportAsTerminal(comp, dsl))
                    ZxAudit.log(ZxAudit.Kind.A11Y, comp, "可用性 ${"%.2f".format(r.score)} · ${r.issues.size} 项")
                    ToolResult.text(if (r.ok) "无障碍通过（${"%.2f".format(r.score)}）。"
                                    else "有 ${r.issues.count { it.level == ZxA11y.Level.FAIL }} 项不合规：${r.issues.first().detail}")
                }
            }

            /* ── 卡片市场 ────────────────────────────────────── */
            aiTool(name = "zx_market",
                description = "模板市场。op=list 看目录 / preview 安装前检查（依赖环+缺失+特权） / install 安装 / search 搜索。"
            ) {
                param("op", ParamType.STRING, "list / search / preview / install / uninstall", required = true)
                param("q", ParamType.STRING, "搜索关键词，或包名")
                param("approved", ParamType.BOOLEAN, "install 时表示用户已批准特权包")
                execute { args ->
                    when (args.string("op")) {
                        "list" -> { ZxCardStore.emit(null, "zx_terminal", ZxMarket.catalogAsTerminal()); ToolResult.text("已列出市场目录。") }
                        "search" -> { ZxCardStore.emit(null, "zx_terminal", ZxMarket.catalogAsTerminal(args.string("q"))); ToolResult.text("已搜索 ${args.string("q")}。") }
                        "preview" -> {
                            ZxCardStore.emit(null, "zx_terminal", ZxMarket.planAsTerminal(args.string("q")))
                            ToolResult.text(ZxMarket.preview(args.string("q")).reason)
                        }
                        "install" -> ToolResult.text(
                            ZxMarket.install(args.string("q"), null, args.optBoolean("approved", false)).reason)
                        "uninstall" -> ToolResult.text(ZxMarket.uninstall(args.string("q")).reason)
                        else -> ToolResult.text("op 只能是 list / search / preview / install / uninstall")
                    }
                }
            }

            /* ── 开关回传 ────────────────────────────────────── */
            aiTool(name = "zx_toggle",
                description = "用户在开关列表上切换某项后由 UI 回调触发。key 为开关标识，on 为切换后的状态。") {
                param("key", ParamType.STRING, "开关标识", required = true)
                param("on", ParamType.STRING, "切换后的状态 true/false", required = true)
                execute { args -> ToolResult.text("开关 ${args.string("key")} 已置为 ${args.string("on")}。据此继续。") }
            }

            /* ── 展开为独立界面 ────────────────────────────────── */
            aiTool(name = "zx_expand",
                description = "气泡装不下时把卡片展开为独立界面。mode 取 fullscreen / sheet / dialog。编辑器、日历、超过 6 条任务的甘特图建议直接展开。"
            ) {
                param("component", ParamType.STRING, "组件类型", required = true)
                param("dsl", ParamType.STRING, "卡片 DSL", required = true)
                param("title", ParamType.STRING, "界面标题")
                param("mode", ParamType.STRING, "fullscreen / sheet / dialog，默认 fullscreen")
                param("from_card", ParamType.STRING, "来源卡片 id")
                execute { args ->
                    val mode = when (args.string("mode")) {
                        "sheet" -> ZxSurface.Mode.BOTTOM_SHEET
                        "dialog" -> ZxSurface.Mode.DIALOG
                        else -> ZxSurface.Mode.FULLSCREEN
                    }
                    val r = ZxSurface.request(ZxSurface.Request(
                        component = args.string("component"),
                        dsl = args.string("dsl"),
                        mode = mode,
                        title = args.string("title").ifBlank { args.string("component") },
                        fromCard = args.string("from_card").ifBlank { null }
                    ))
                    ToolResult.text(r.reason)
                }
            }

            /* ── 该不该展开 ────────────────────────────────────── */
            aiTool(name = "zx_fits",
                description = "判断某个 DSL 在气泡里是否放得下。放不下就改用 zx_expand 展开，别硬塞。返回建议的呈现方式。"
            ) {
                param("component", ParamType.STRING, "组件类型", required = true)
                param("dsl", ParamType.STRING, "卡片 DSL", required = true)
                execute { args ->
                    val o = runCatching { org.json.JSONObject(args.string("dsl")) }.getOrNull()
                        ?: return@execute ToolResult.text("DSL 无法解析")
                    val need = ZxSurface.needsSurface(args.string("component"), o)
                    if (!need) ToolResult.text("${args.string("component")} 在气泡里放得下，直接 zx_render 即可。")
                    else ToolResult.text("${args.string("component")} 在气泡里放不下（内容超出窄气泡可读范围），请用 zx_expand 展开为独立界面。")
                }
            }

            /* ── 主题变体 ────────────────────────────────────── */
            aiTool(name = "zx_theme",
                description = "切换渲染密度：compact 紧凑（多装 40%）/ comfortable 标准 / accessible 无障碍（1.3× 字号 + 高对比）。op=auto 按内容规模自动挑。"
            ) {
                param("op", ParamType.STRING, "set / auto / current", required = true)
                param("variant", ParamType.STRING, "compact / comfortable / accessible")
                param("rows", ParamType.NUMBER, "auto 时的行数")
                param("cols", ParamType.NUMBER, "auto 时的列数")
                execute { args ->
                    when (args.string("op")) {
                        "set" -> {
                            val v = ZxTheme.Variant.entries.firstOrNull {
                                it.name.equals(args.string("variant"), true) }
                                ?: return@execute ToolResult.text(
                                    "variant 只能是 compact / comfortable / accessible，实际 ${args.string("variant")}")
                            ZxTheme.set(v)
                            ToolResult.text("已切到${v.label}：${v.desc}。仅影响间距/字号/圆角，不影响结构。")
                        }
                        "auto" -> {
                            val v = ZxTheme.autoFor(
                                args.string("rows").toIntOrNull() ?: 5,
                                args.string("cols").toIntOrNull() ?: 2)
                            ZxTheme.set(v)
                            ToolResult.text("按内容规模自动选为${v.label}（${v.desc}）。")
                        }
                        else -> ToolResult.text(ZxTheme.spec())
                    }
                }
            }

            /* ── 动效 ────────────────────────────────────────── */
            aiTool(name = "zx_motion",
                description = "查看或设置卡片动效。动效写在 DSL 的 _motion 字段，如 \"enter:300:emphasis\"。缺省按语义自动选。"
            ) {
                param("op", ParamType.STRING, "spec / guide / reduce", required = true)
                param("on", ParamType.BOOLEAN, "reduce 时的开关")
                execute { args ->
                    when (args.string("op")) {
                        "reduce" -> {
                            ZxMotion.setReduceMotion(args.optBoolean("on", true))
                            ToolResult.text("系统「减少动画」已设为 ${ZxMotion.reduce()}，开启时所有动效降到 0ms。")
                        }
                        "guide" -> ToolResult.text(ZxMotion.guide())
                        else -> ToolResult.text(ZxMotion.spec())
                    }
                }
            }

            /* ── 多窗口 ──────────────────────────────────────── */
            aiTool(name = "zx_window",
                description = "并排看多张卡。op=open 打开一个窗口 / close 关闭 / list 列出。同一屏放不下时用它做并排对比。"
            ) {
                param("op", ParamType.STRING, "open / close / list", required = true)
                param("component", ParamType.STRING, "open 时的组件类型")
                param("dsl", ParamType.STRING, "open 时的 DSL")
                param("title", ParamType.STRING, "窗口标题")
                param("slot", ParamType.NUMBER, "槽位 0/1，最多 2 个窗口")
                execute { args ->
                    when (args.string("op")) {
                        "open" -> {
                            val r = ZxSurface.openWindow(
                                slot = args.string("slot").toIntOrNull() ?: 0,
                                component = args.string("component"),
                                dsl = args.string("dsl"),
                                title = args.string("title").ifBlank { args.string("component") })
                            ToolResult.text(r.reason)
                        }
                        "close" -> ToolResult.text(
                            ZxSurface.closeWindow(args.string("slot").toIntOrNull() ?: 0).reason)
                        else -> ToolResult.text(ZxSurface.windowList())
                    }
                }
            }

            /* ── 交互基建：手势 / 焦点 / 长按 ─────────────────── */
            aiTool(name = "zx_gesture",
                description = "交互基建。op=axis 查看手势仲裁阈值 / focus 焦点链规则 / menu 长按菜单项。"
            ) {
                param("op", ParamType.STRING, "axis / focus / menu", required = true)
                execute { args ->
                    ToolResult.text(when (args.string("op")) {
                        "axis" -> ZxGesture.axisGuide()
                        "focus" -> ZxGesture.focusGuide()
                        else -> ZxGesture.menuGuide()
                    })
                }
            }

            /* ── 组件自注册发现 ───────────────────────────────── */
            aiTool(name = "zx_discover",
                description = "查当前能画什么。按屏宽和权限过滤，只返回真正可用的组件。op=list 清单 / detail 单个 / audit 自检。"
            ) {
                param("op", ParamType.STRING, "list / detail / audit", required = true)
                param("name", ParamType.STRING, "detail 时的组件名")
                param("category", ParamType.STRING, "data / input / gesture / layout / text / status")
                param("keyword", ParamType.STRING, "按名字或标签搜索")
                param("widthDp", ParamType.NUMBER, "当前可用宽度，默认 360")
                execute { args ->
                    val env = ZxRegistry.Env(
                        widthDp = args.string("widthDp").toIntOrNull() ?: 360,
                        variant = ZxTheme.current().name,
                        caps = ZxSandbox.capsOf("_"),
                        online = true)
                    when (args.string("op")) {
                        "detail" -> ToolResult.text(ZxRegistry.detail(args.string("name")))
                        "audit" -> {
                            val bad = ZxRegistry.audit()
                            ToolResult.text(if (bad.isEmpty()) "注册表自检通过，${ZxRegistry.names().size} 个组件登记完整。"
                                            else "登记不完整：\n" + bad.joinToString("\n"))
                        }
                        else -> ToolResult.text(ZxRegistry.catalog(
                            env,
                            runCatching { ZxRegistry.Category.valueOf(args.string("category").uppercase()) }.getOrNull(),
                            args.string("keyword")))
                    }
                }
            }

            /* ── 跨卡约束 ─────────────────────────────────────── */
            aiTool(name = "zx_constraint",
                description = "跨卡表单联动校验。违反时卡片显示错误条但不阻止输入，提交时才拦截。op=add/list/check/remove/clear。"
            ) {
                param("op", ParamType.STRING, "add / list / check / remove / clear", required = true)
                param("id", ParamType.STRING, "规则 ID，remove 时必填")
                param("card", ParamType.STRING, "被约束的卡片 ID")
                param("field", ParamType.STRING, "被约束的字段路径")
                param("op2", ParamType.STRING, "gt / gte / lt / lte / eq / neq")
                param("other", ParamType.STRING, "参照值：常量，或 {{ref.cardId.field}}")
                param("msg", ParamType.STRING, "违反时的提示文案")
                execute { args ->
                    when (args.string("op")) {
                        "add" -> {
                            val op = ZxConstraint.Op.entries.firstOrNull {
                                it.name.equals(args.string("op2"), true) }
                                ?: return@execute ToolResult.text(
                                    "op2 只能是 gt/gte/lt/lte/eq/neq，实际 ${args.string("op2")}")
                            val r = ZxConstraint.add(ZxConstraint.Rule(
                                id = args.string("id").ifBlank { "r${System.currentTimeMillis()}" },
                                cardId = args.string("card"),
                                field = args.string("field"),
                                op = op,
                                other = args.string("other"),
                                msg = args.string("msg").ifBlank { "该项不满足约束" }))
                            ToolResult.text(if (r.ok) "${r.reason}。违反时不阻止输入，提交时拦截。"
                                            else r.reason)
                        }
                        "check" -> {
                            val v = ZxConstraint.checkAll()
                            ToolResult.text(if (v.isEmpty()) "全部约束满足。"
                                            else v.joinToString("\n") { "· ${it.rule.msg}（${it.actual} vs ${it.expected}）" })
                        }
                        "remove" -> ToolResult.text(
                            if (ZxConstraint.remove(args.string("id"))) "已移除 ${args.string("id")}"
                            else "没找到规则 ${args.string("id")}")
                        "clear" -> { ZxConstraint.clear(); ToolResult.text("已清空全部约束") }
                        else -> ToolResult.text(ZxConstraint.spec())
                    }
                }
            }

            /* ── 输入级撤销 ───────────────────────────────────── */
            aiTool(name = "zx_input_undo",
                description = "字段级撤销重做。表单卡里改了五个字段，Ctrl+Z 撤销最后一个字段而不是整张卡。op=undo/redo/history/clear/guide。"
            ) {
                param("op", ParamType.STRING, "undo / redo / history / clear / guide", required = true)
                param("card", ParamType.STRING, "卡片 ID")
                execute { args ->
                    val card = args.string("card")
                    when (args.string("op")) {
                        "undo" -> {
                            val p = ZxInputUndo.undo(card)
                                ?: return@execute ToolResult.text("没有可撤销的编辑。")
                            ZxRef.set(card, p.first, p.second)
                            ?: ZxRef.propagate(card, p.first, p.second)
                            ToolResult.text("已撤销：${p.first} 恢复到 \"${p.second}\"")
                        }
                        "redo" -> {
                            val p = ZxInputUndo.redo(card)
                                ?: return@execute ToolResult.text("没有可重做的编辑。")
                            ZxRef.set(card, p.first, p.second)
                            ?: ZxRef.propagate(card, p.first, p.second)
                            ToolResult.text("已重做：${p.first} 设为 \"${p.second}\"")
                        }
                        "clear" -> { ZxInputUndo.clear(card); ToolResult.text("已清空该卡的编辑历史") }
                        "guide" -> ToolResult.text(ZxInputUndo.guide())
                        else -> ToolResult.text(ZxInputUndo.history(card))
                    }
                }
            }

            /* ── 残留责任 ───────────────────────────────────────────── */
            aiTool(name = "zx_residual",
                description = "残留责任：交接签字之后还剩多久才真正结束。结束时间取最晚的那个，不是最早的——OA 关了就说结束了，但密钥还在他手里。"
            ) {
                param("op", ParamType.STRING, "add / close / status / report / demo / clear", required = true)
                param("kind", ParamType.STRING, "质保 / 追溯 / 过渡 / 残留（**主动类填 -1 表示未关闭**）")
                param("what", ParamType.STRING, "残留的是什么")
                param("hours", ParamType.STRING, "剩余小时")
                execute { args ->
                    ToolResult.text(ZxResidual.handle(
                        args.string("op"), args.string("kind"),
                        args.string("what"), args.string("hours")))
                }
            }

            /* ── 持有期窗口 ──────────────────────────────────────────── */
            aiTool(name = "zx_holdwindow",
                description = "持有期窗口：没有断裂点时按潜伏期反推症状落在谁的持有期内。给全部嫌疑而不只给一个——只给最近的会冤枉最后接手的人。"
            ) {
                param("op", ParamType.STRING, "hold / trace / list / report / demo / clear", required = true)
                param("who", ParamType.STRING, "谁持有的")
                param("fromH", ParamType.STRING, "开始持有距今多少小时")
                param("toH", ParamType.STRING, "结束持有距今多少小时（**0 = 持有到现在**）")
                param("nowH", ParamType.STRING, "症状出现距今多少小时")
                param("minH", ParamType.STRING, "最短潜伏期（**必须给范围，只给一个值会漏掉边缘窗口**）")
                param("maxH", ParamType.STRING, "最长潜伏期")
                execute { args ->
                    ToolResult.text(ZxHoldWindow.handle(
                        args.string("op"), args.string("who"),
                        args.string("fromH"), args.string("toH"),
                        args.string("nowH")))
                }
            }

            /* ── 确认登记 ───────────────────────────────────────────── */
            aiTool(name = "zx_attest",
                description = "确认登记：谁对什么状态做了确认。确认后只能追加异议，不能修改确认本身——允许改就等于允许事后抵赖。"
            ) {
                param("op", ParamType.STRING, "confirm / object / invalidate / agreed / list / report / demo / clear", required = true)
                param("who", ParamType.STRING, "谁确认的")
                param("what", ParamType.STRING, "确认的对象")
                param("snapshot", ParamType.STRING, "确认时的状态快照")
                execute { args ->
                    ToolResult.text(ZxAttest.handle(
                        args.string("op"), args.string("who"),
                        args.string("what"), args.string("snapshot")))
                }
            }

            /* ── 监管链 ──────────────────────────────────────────────── */
            aiTool(name = "zx_custody",
                description = "监管链：一件东西经过了几次手，定位「第几次交接开始对不上」。每次交接都完好，串起来才看得出是哪一段断的。"
            ) {
                param("op", ParamType.STRING, "link / find / list / report / demo / clear", required = true)
                param("from", ParamType.STRING, "交出方")
                param("to", ParamType.STRING, "接手方（**必须与上一环的接手方一致，否则会被拦**）")
                param("state", ParamType.STRING, "接手时的状态（**必须能和上一环对比**）")
                param("at", ParamType.STRING, "时间")
                execute { args ->
                    ToolResult.text(ZxCustody.handle(
                        args.string("op"), args.string("from"),
                        args.string("to"), args.string("state"), args.string("at")))
                }
            }

            /* ── 补救时限 ───────────────────────────────────────────── */
            aiTool(name = "zx_remedy",
                description = "补救时限：出事的第一分钟最值钱。按剩余时间排出「还来得及做什么」，可行动窗口 = 剩余 − 耗时，已过期的不静默删除。"
            ) {
                param("op", ParamType.STRING, "add / done / list / report / demo / clear", required = true)
                param("what", ParamType.STRING, "事项")
                param("leftH", ParamType.STRING, "剩余小时")
                param("costH", ParamType.STRING, "做这件事要多久（小时）")
                param("miss", ParamType.STRING, "过期后果")
                param("serves", ParamType.STRING, "逗号分隔：这份证据还服务于哪些事项")
                execute { args ->
                    ToolResult.text(ZxRemedy.handle(
                        args.string("op"), args.string("what"),
                        args.string("leftH"), args.string("costH"),
                        args.string("miss"), args.string("serves")))
                }
            }

            /* ── 事后归档 ───────────────────────────────────────────── */
            aiTool(name = "zx_aftermath",
                description = "事后归档：把一次事故的处置固化成预案。要写成「下次第一步做什么 + 触发时机」，而不是「这次做错了什么」。"
            ) {
                param("op", ParamType.STRING, "add / advise / list / report / demo / clear", required = true)
                param("scene", ParamType.STRING, "场景")
                param("cause", ParamType.STRING, "这次做错了什么（依据）")
                param("action", ParamType.STRING, "下次第一步做什么")
                param("when", ParamType.STRING, "触发时机（要具体，含「时/前/后/第一/当场」等）")
                param("cost", ParamType.STRING, "不做会怎样")
                execute { args ->
                    ToolResult.text(ZxAftermath.handle(
                        args.string("op"), args.string("scene"),
                        args.string("cause"), args.string("action"),
                        args.string("when"), args.string("cost")))
                }
            }

            /* ── 等待基线 ───────────────────────────────────────────── */
            aiTool(name = "zx_wait",
                description = "等待基线：学出这类事该等多久，让超时判定不再靠拍脑袋。用 P50/P85/P95 分位数而非均值，样本不足不下结论。"
            ) {
                param("op", ParamType.STRING, "record / judge / list / report / demo / clear", required = true)
                param("kind", ParamType.STRING, "事件类别")
                param("hours", ParamType.STRING, "小时数")
                execute { args ->
                    ToolResult.text(ZxWait.handle(
                        args.string("op"), args.string("kind"), args.string("hours")))
                }
            }

            /* ── 场景记忆 ───────────────────────────────────────────── */
            aiTool(name = "zx_recall",
                description = "场景记忆：记住这类场景上次是怎么走完的。与偏好学习不同，它记的是场景级——上次画了几张、用户实际点了几张。"
            ) {
                param("op", ParamType.STRING, "advise / remember / used / dropped / list / report / demo / clear", required = true)
                param("scene", ParamType.STRING, "场景名")
                param("ctx", ParamType.STRING, "处境摘要")
                param("comps", ParamType.STRING, "逗号分隔的组件名")
                execute { args ->
                    ToolResult.text(ZxRecall.handle(
                        args.string("op"), args.string("scene"),
                        args.string("ctx"), args.string("comps")))
                }
            }

            /* ── DSL 错题本 ─────────────────────────────────────────── */
            aiTool(name = "zx_mistake",
                description = "DSL 错题本：记住模型在这台设备上反复写错的地方，下次下发同一组件之前给预警。把「事后修」变「事前防」。"
            ) {
                param("op", ParamType.STRING, "warn / list / report / demo / decay / clear", required = true)
                param("component", ParamType.STRING, "op=warn 时的组件名")
                execute { args ->
                    ToolResult.text(ZxMistake.handle(
                        args.string("op"), args.string("component")))
                }
            }

            /* ── 场景剧本 ─────────────────────────────────────────────── */
            aiTool(name = "zx_playbook",
                description = "场景剧本：把一个生活任务串成有序步骤，每步绑一张卡片。与流水线不同，它支持等待态、回退与阻塞原因。"
            ) {
                param("op", ParamType.STRING, "create / advance / block / rollback / skip / render / report / demo", required = true)
                param("id", ParamType.STRING, "剧本 id")
                param("step", ParamType.STRING, "步骤 id")
                param("reason", ParamType.STRING, "阻塞原因")
                execute { args ->
                    ToolResult.text(ZxPlaybook.handle(
                        args.string("op"), args.string("id"),
                        args.string("step"), args.string("reason")))
                }
            }

            /* ── 全量回归 ─────────────────────────────────────────────── */
            aiTool(name = "zx_regress",
                description = "全量回归：把全部注册组件的示例 DSL 跑一遍校验，报出失败/无示例/指纹变化。改渲染器或共用校验分支之后应跑一次。"
            ) {
                param("op", ParamType.STRING, "run / demo / report / update / clear / guide", required = true)
                execute { args ->
                    when (args.string("op")) {
                        "run" -> ToolResult.text(ZxRegress.render())
                        "demo" -> ToolResult.text(ZxRegress.demo())
                        "report" -> ToolResult.text(ZxRegress.report())
                        "update" -> ToolResult.text("已把 ${ZxRegress.update()} 个组件的当前状态写进基线")
                        "clear" -> ToolResult.text("基线已清空（当前剩 ${ZxRegress.clear()} 条）")
                        else -> ToolResult.text(ZxRegress.guide())
                    }
                }
            }

            /* ── 真机适配 ─────────────────────────────────────────────── */
            aiTool(name = "zx_adapt",
                description = "真机适配：同一份 DSL 在不同屏宽/字缩/性能下该按什么参数渲染。列数/密度/触控尺寸/采样上限/是否展开。只改度量与布局，不改语义。"
            ) {
                param("op", ParamType.STRING, "decide / touch / split / fits / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "kind / items / cols / n / widthDp / heightDp / fontScale / perf")
                execute { args ->
                    val o = JSONObject(args.string("dsl"))
                    val chk = ZxAdapt.validate(o)
                    if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                    else {
                        val env = ZxAdapt.Env(
                            widthDp = o.optInt("widthDp", 360),
                            heightDp = o.optInt("heightDp", 720),
                            fontScale = o.optDouble("fontScale", 1.0).toFloat(),
                            perf = ZxAdapt.Perf.values()
                                .firstOrNull { it.id == o.optString("perf", "mid") }
                                ?: ZxAdapt.Perf.MID,
                            reduceMotion = o.optBoolean("reduceMotion", false),
                            folded = o.optBoolean("folded", true)
                        )
                        when (o.optString("op")) {
                            "decide" -> {
                                val d = ZxAdapt.decide(o.optString("kind", "table"),
                                    o.optInt("items", 10), o.optInt("cols", 3), env)
                                ToolResult.text(
                                    "列 ${d.cols}　密度 ${d.density}　行高 ${d.rowHeightDp}dp\n" +
                                    "字号 ${d.fontSizeSp}sp　触控 ${d.touchDp}dp（一排最多 ${d.maxTouchCount} 个）\n" +
                                    "画布 ${d.imageDp}dp　采样上限 ${d.sampleCap}　动画 ${if (d.animate) "开" else "**关**"}\n" +
                                    "预估高度 ${d.estHeightDp}dp${if (d.expand) "　**建议展开**" else ""}\n\n" +
                                    d.note)
                            }
                            "touch" -> {
                                val (fit, msg) = ZxAdapt.touchFit(o.optInt("n", 3), env)
                                ToolResult.text(if (msg.isBlank()) "放得下 $fit 个" else "$fit 个。$msg")
                            }
                            "split" -> {
                                val (a, b) = ZxAdapt.splitForUnfolded(env)
                                ToolResult.text(if (b <= 0f) "不够宽，不分栏"
                                    else "主列表 ${(a * 100).toInt()}% / 详情 ${(b * 100).toInt()}%")
                            }
                            "fits" -> ToolResult.text(
                                if (ZxAdapt.textFits(o.optInt("chars", 10),
                                        o.optInt("widthDp", 100), env.fontScale))
                                    "放得下" else "**放不下，会截断**")
                            "demo" -> ToolResult.text(ZxAdapt.demo())
                            "report" -> ToolResult.text(ZxAdapt.report())
                            else -> ToolResult.text(ZxAdapt.guide())
                        }
                    }
                }
            }

            /* ── 内容源与时效 ─────────────────────────────────────────── */
            aiTool(name = "zx_feed",
                description = "内容源与时效：天气/新闻/内容的统一缓存、保鲜与去重。保鲜期由类型决定，过期只标记不删除，跨源去重按相似度，合并后更新时间取最早。"
            ) {
                param("op", ParamType.STRING, "put / get / list / stale / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "kind / title / source / ts / body / id")
                execute { args ->
                    val o = JSONObject(args.string("dsl"))
                    val chk = ZxFeed.validate(o)
                    if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                    else when (o.optString("op")) {
                        "put" -> ToolResult.text(ZxFeed.put(o.optString("kind"),
                            o.optString("title"), o.optString("source"),
                            o.optLong("ts", System.currentTimeMillis()), o.optString("body")))
                        "get" -> ToolResult.text(ZxFeed.get(o.optString("id"))?.let {
                            "${it.title}（${it.source}）${if (it.stale()) "**已过期**" else "有效"}"
                        } ?: "没有这条内容")
                        "stale" -> ToolResult.text(
                            if (ZxFeed.staleList().isEmpty()) "没有过期内容"
                            else ZxFeed.staleList().joinToString("\n") {
                                "[${it.kind.label}] ${it.title} —— ${it.source}" })
                        "list" -> ToolResult.text(ZxFeed.render())
                        "demo" -> ToolResult.text(ZxFeed.demo())
                        "report" -> ToolResult.text(ZxFeed.report())
                        else -> ToolResult.text(ZxFeed.guide())
                    }
                }
            }

            /* ── 推演校准 ─────────────────────────────────────────────── */
            aiTool(name = "zx_calib",
                description = "推演校准：拿真实结果回填，看当初的推演偏了多少、往哪偏。覆盖率与名义水平比、偏差同向检验、PIT 均匀性。"
            ) {
                param("op", ParamType.STRING, "add / resolve / del / list / coverage / bias / pit / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "name / comp / point / lo / hi / nominal / id / value")
                execute { args ->
                    val o = JSONObject(args.string("dsl"))
                    val chk = ZxCalib.validate(o)
                    if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                    else when (o.optString("op")) {
                        "add" -> ToolResult.text("已记录预测 " + ZxCalib.add(
                            o.optString("name", "未命名"), o.optString("comp", "—"),
                            o.optDouble("point"), o.optDouble("lo"), o.optDouble("hi"),
                            o.optDouble("nominal", 0.8)))
                        "resolve" -> ToolResult.text(
                            ZxCalib.resolve(o.optString("id"), o.optDouble("value")))
                        "del" -> ToolResult.text(
                            if (ZxCalib.del(o.optString("id"))) "已删除" else "找不到该预测")
                        "coverage" -> ToolResult.text(ZxCalib.coverage())
                        "bias" -> ToolResult.text(ZxCalib.bias())
                        "pit" -> ToolResult.text(ZxCalib.pit())
                        "list" -> ToolResult.text(ZxCalib.render())
                        "demo" -> ToolResult.text(ZxCalib.demo())
                        "report" -> ToolResult.text(ZxCalib.report())
                        else -> ToolResult.text(ZxCalib.guide())
                    }
                }
            }

            /* ── 情景快照与复现 ─────────────────────────────────────────── */
            aiTool(name = "zx_scen",
                description = "情景快照与复现：让推演可复核。存 seed 与分位数，支持重跑比对与两条情景对比。"
            ) {
                param("op", ParamType.STRING, "save / force / del / list / replay / compare / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "name / comp / dsl(JSON) / q(分位数) / a / b / id")
                execute { args ->
                    val o = JSONObject(args.string("dsl"))
                    val chk = ZxScen.validate(o)
                    if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                    else when (o.optString("op")) {
                        "save" -> {
                            val d = JSONObject(o.optString("dsl", "{}"))
                            val q = ZxScen.get(o.optString("name"))?.q ?: emptyMap()
                            ToolResult.text(ZxScen.save(o.optString("name"),
                                o.optString("comp"), d, q).second)
                        }
                        "force" -> {
                            val d = JSONObject(o.optString("dsl", "{}"))
                            ToolResult.text("已覆盖：" +
                                ZxScen.force(o.optString("name"), o.optString("comp"), d,
                                    emptyMap()).name)
                        }
                        "del" -> ToolResult.text(
                            if (ZxScen.del(o.optString("id"))) "已删除" else "找不到该情景")
                        "replay" -> ToolResult.text(ZxScen.replay(o.optString("id"),
                            JSONObject(o.optString("dsl", "{}")), emptyMap()))
                        "compare" -> ToolResult.text(
                            ZxScen.compare(o.optString("a"), o.optString("b")))
                        "list" -> ToolResult.text(ZxScen.render())
                        "demo" -> ToolResult.text(ZxScen.demo())
                        "report" -> ToolResult.text(ZxScen.report())
                        else -> ToolResult.text(ZxScen.guide())
                    }
                }
            }

            /* ── 组件族摘要 ─────────────────────────────────────────────── */
            aiTool(name = "zx_families",
                description = "组件族级索引：先按「要回答什么问题」检索族，再在族内选；每族有 default。"
            ) {
                param("op", ParamType.STRING, "list / brief / spec / of / q / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "f（族名）/ name（组件名）/ q（问题）")
                execute { args ->
                    when (args.string("op")) {
                        "brief" -> {
                            val f = JSONObject(args.string("dsl")).optString("f")
                            ToolResult.text(ZxFamilies.brief(
                                ZxFamilies.F.values().firstOrNull { it.id == f }))
                        }
                        "spec" -> {
                            val f = JSONObject(args.string("dsl")).optString("f")
                            ToolResult.text(ZxFamilies.specFor(
                                ZxFamilies.F.values().first { it.id == f }))
                        }
                        "of" -> {
                            val n = JSONObject(args.string("dsl")).optString("name")
                            val fs = ZxFamilies.familiesOf(n)
                            ToolResult.text(if (fs.isEmpty()) "「$n」不属于任何族（未登记语义映射）"
                                else "「$n」属于 ${fs.size} 个族：" +
                                    fs.joinToString(" / ") { it.id + "(" + it.label + ")" })
                        }
                        "q" -> {
                            val q = JSONObject(args.string("dsl")).optString("q")
                            val fs = ZxFamilies.byQuestion(q)
                            ToolResult.text(if (fs.isEmpty()) "「$q」没匹配到族，用 list 看全部"
                                else fs.joinToString("\n") { x ->
                                    "  ${x.id}（${x.label}）默认 ${x.default}\n      ${x.diff}" })
                        }
                        "list" -> ToolResult.text(ZxFamilies.render())
                        "rank" -> ToolResult.text(ZxFamilies.rankReport())
                        "demo" -> ToolResult.text(ZxFamilies.demo())
                        "report" -> ToolResult.text(ZxFamilies.report())
                        else -> ToolResult.text(ZxFamilies.guide())
                    }
                }
            }

            /* ── 闭环自动触发器 ─────────────────────────────────────────── */
            aiTool(name = "zx_trigger",
                description = "让监控类卡片自己接上下一步：条件满足就声明预期 / 建卡 / 回传给模型。触发前可预演。"
            ) {
                param("op", ParamType.STRING, "reg / list / dry / fire / reset / on / off / rm / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "id / name / comp / sem / i / op / th / act / arg / card")
                execute { args ->
                    when (args.string("op")) {
                        "reg" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxTrigger.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else ToolResult.text(ZxTrigger.reg(ZxTrigger.from(o)).second)
                        }
                        "dry" -> {
                            val o = JSONObject(args.string("dsl"))
                            val hit = ZxTrigger.dryRun(o.optString("card"), o.optString("comp"))
                            ToolResult.text(if (hit.isEmpty()) "预演：没有规则命中——**不会做任何事**"
                                else "预演：${hit.size} 条会命中（尚未执行）\n" +
                                    hit.joinToString("\n") { (r, v) ->
                                        "  · ${r.name}：${r.component}.${r.sem}=$v ${r.op.label} ${r.threshold} → ${r.act.label}" })
                        }
                        "fire" -> {
                            val o = JSONObject(args.string("dsl"))
                            ToolResult.text(ZxTrigger.fire(o.optString("card"), o.optString("comp")) { c, t, d ->
                                ZxCardStore.emit(t, c, d.ifBlank { "{}" })
                            })
                        }
                        "reset" -> {
                            val o = JSONObject(args.string("dsl"))
                            ZxTrigger.reset(o.optString("card"), o.optString("comp"))
                            ToolResult.text("已重置：下次再超过阈值会重新触发")
                        }
                        "regx" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxTrigger.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else {
                                val cs = o.optJSONArray("conds")
                                val conds = (0 until (cs?.length() ?: 0)).map {
                                    val c = cs!!.optJSONObject(it) ?: JSONObject()
                                    ZxTrigger.Cond(c.optString("comp"), c.optString("sem"),
                                        when (c.optString("op", "gte")) {
                                            "gt" -> ZxTrigger.Op.GT; "lt" -> ZxTrigger.Op.LT
                                            "lte" -> ZxTrigger.Op.LTE; else -> ZxTrigger.Op.GTE },
                                        c.optDouble("th", 0.0))
                                }
                                ToolResult.text(ZxTrigger.regX(ZxTrigger.RuleX(
                                    o.optString("id", "x${System.currentTimeMillis() % 10000}"),
                                    o.optString("name", "组合规则"),
                                    ZxTrigger.CondGroup(
                                        if (o.optString("join", "and") == "or")
                                            ZxTrigger.CondGroup.Join.OR
                                        else ZxTrigger.CondGroup.Join.AND, conds),
                                    o.optLong("win", 0),
                                    when (o.optString("act", "audit")) {
                                        "promise" -> ZxTrigger.Act.PROMISE
                                        "card" -> ZxTrigger.Act.CARD
                                        "select" -> ZxTrigger.Act.SELECT
                                        else -> ZxTrigger.Act.AUDIT },
                                    o.optString("arg", ""))).second)
                            }
                        }
                        "checkx" -> ToolResult.text(
                            ZxTrigger.checkX().joinToString("\n") { (r, ok, why) ->
                                "  ${if (ok) "●" else "○"} [${r.name}] $why" })
                        "firex" -> ToolResult.text(ZxTrigger.fireX { c, t, d ->
                            ZxCardStore.emit(t, c, d.ifBlank { "{}" })
                        })
                        "fb" -> {
                            val o = JSONObject(args.string("dsl"))
                            ToolResult.text(ZxTrigger.feedback(
                                o.optString("rule"),
                                try { ZxTrigger.Feedback.Kind.valueOf(
                                    o.optString("kind", "NOISE").uppercase()) }
                                catch (e: Exception) { ZxTrigger.Feedback.Kind.NOISE },
                                o.optDouble("value", 0.0)))
                        }
                        "suggest" -> ToolResult.text(
                            if (JSONObject(args.string("dsl")).optString("rule").isBlank())
                                ZxTrigger.suggestAll() else ZxTrigger.suggest(
                                JSONObject(args.string("dsl")).optString("rule")))
                        "list" -> ToolResult.text(ZxTrigger.render())
                        "demo" -> ToolResult.text(ZxTrigger.demo())
                        "report" -> ToolResult.text(ZxTrigger.report())
                        "report" -> ToolResult.text(ZxTrigger.report())
                        else -> ToolResult.text(ZxTrigger.guide())
                    }
                }
            }

            /* ── 组件收敛（元编程层） ───────────────────────────────────── */
            aiTool(name = "zx_core",
                description = "用自组合反推哪些手工组件其实可以合并掉。只报告不合并，并给出合不了的代价。"
            ) {
                param("op", ParamType.STRING, "list / cand / merge / block / shape / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "name")
                execute { args ->
                    when (args.string("op")) {
                        "shape" -> {
                            val s = ZxCore.shapeOf(JSONObject(args.string("dsl")).optString("name"))
                            ToolResult.text("${s.name}（${s.label}）\n" +
                                "  语义　${s.sem.joinToString("/").ifBlank { "（无）" }}\n" +
                                "  交互　${s.mode.label}\n维度　${s.dim.label}\n判据　${s.verdict.label}")
                        }
                        "cand" -> ToolResult.text(ZxCore.candidates().joinToString("\n") { g ->
                            "  [${g.first().sem.joinToString("/")}] " +
                                g.joinToString(" ↔ ") { it.name } })
                        "merge" -> {
                            val m = ZxCore.mergeable()
                            ToolResult.text(if (m.isEmpty()) "没有三件事全同的对——现有组件区分度够"
                                else m.joinToString("\n") { (x, y) -> "  ${x.name} ↔ ${y.name}" })
                        }
                        "block" -> {
                            val b = ZxCore.blocked()
                            ToolResult.text(if (b.isEmpty()) "没有语义相同的组"
                                else b.take(10).joinToString("\n") { (x, y, why) ->
                                    "  ${x.name} ↔ ${y.name}　**不能合并**：差在 ${why}" })
                        }
                        "list" -> ToolResult.text(ZxCore.render())
                        "demo" -> ToolResult.text(ZxCore.demo())
                        "report" -> ToolResult.text(ZxCore.report())
                        else -> ToolResult.text(ZxCore.guide())
                    }
                }
            }

            /* ── 结果回收协议 ─────────────────────────────────────────── */
            aiTool(name = "zx_outcome",
                description = "让全部组件参与闭环：声明预期 → 回填实际 → 算偏差 → 报有偏。"
            ) {
                param("op", ParamType.STRING, "promise / actual / settle / list / bias / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "card / comp / sem / exp / p / v / note")
                execute { args ->
                    when (args.string("op")) {
                        "promise" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxOutcome.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else ToolResult.text(ZxOutcome.put(o).second)
                        }
                        "actual" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxOutcome.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else ToolResult.text(ZxOutcome.actual(o).second)
                        }
                        "settle" -> {
                            val o = JSONObject(args.string("dsl"))
                            ToolResult.text(ZxOutcome.settle(o.optString("p")).second)
                        }
                        "bias" -> ToolResult.text(ZxOutcome.biasReport())
                        "list" -> ToolResult.text(ZxOutcome.render())
                        "demo" -> ToolResult.text(ZxOutcome.demo())
                        "report" -> ToolResult.text(ZxOutcome.report())
                        else -> ToolResult.text(ZxOutcome.guide())
                    }
                }
            }

            /* ── 组件自组合协议 ───────────────────────────────────────── */
            aiTool(name = "zx_compose",
                description = "按语义把已有组件拼成新组件，不再手工加。每层各自校验，单层非法整个拒绝。"
            ) {
                param("op", ParamType.STRING, "reg / get / list / check / expand / intent / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "name / label / desc / layers[{c,at,dsl}]")
                execute { args ->
                    when (args.string("op")) {
                        "reg" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxCompose.validate2(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else ToolResult.text(ZxCompose.reg(ZxCompose.from(o)).second)
                        }
                        "check" -> {
                            val o = JSONObject(args.string("dsl"))
                            val r = ZxCompose.validate(o.optString("name"), o)
                            ToolResult.text((if (r.first) "✓ " else "✗ ") + r.second)
                        }
                        "expand" -> {
                            val o = JSONObject(args.string("dsl"))
                            val ex = ZxCompose.expand(o.optString("name"), o)
                            ToolResult.text(if (ex.isEmpty()) "展开失败：不是已注册组合"
                                else "展开为 ${ex.size} 层：\n" +
                                    ex.mapIndexed { i, (c, _) -> "  ${i + 1}. $c" }.joinToString("\n"))
                        }
                        "intent" -> ToolResult.text(
                            ZxCompose.intent(JSONObject(args.string("dsl")).optString("name"))
                                ?: "无法反编译：不是已注册组合")
                        "list" -> ToolResult.text(ZxCompose.render())
                        "demo" -> ToolResult.text(ZxCompose.demo())
                        "report" -> ToolResult.text(ZxCompose.report())
                        else -> ToolResult.text(ZxCompose.guide())
                    }
                }
            }

            /* ── 假设登记册 ─────────────────────────────────────────── */
            aiTool(name = "zx_ledger",
                description = "跨会话记住「这条假设查过了没、查出什么」。带出处、会过期、不替用户下结论。"
            ) {
                param("op", ParamType.STRING, "put / get / list / todo / stale / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "claim + aid + v + via + card + note + dv")
                execute { args ->
                    when (args.string("op")) {
                        "put" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxLedger.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else {
                                val e = ZxLedger.put(ZxLedger.from(o))
                                ToolResult.text("已登记「${e.text}」→ ${e.verdict.label}（出处 ${e.via}）")
                            }
                        }
                        "get" -> {
                            val o = JSONObject(args.string("dsl"))
                            val e = ZxLedger.get(o.optString("claim"), o.optString("aid"))
                            ToolResult.text(if (e == null) "这条假设没查过。"
                                else "「${e.text}」→ ${e.verdict.label}；出处 ${e.via}；${e.note}")
                        }
                        "todo" -> {
                            val o = JSONObject(args.string("dsl"))
                            val claim = ZxCausal.from(o)
                            val list = ZxCausal.assumptions(claim)
                            ToolResult.text(ZxLedger.todo(o.optString("claim", "c1"), list))
                        }
                        "stale" -> ToolResult.text(
                            ZxLedger.stale().let { st ->
                                if (st.isEmpty()) "没有过期的登记。"
                                else "已过期 ${st.size} 条（结论可能已不成立，建议重查）：\n" +
                                    st.joinToString("\n") { "  · ${it.text}（${it.via}）" }
                            })
                        "list" -> ToolResult.text(ZxLedger.render())
                        "demo" -> ToolResult.text(ZxLedger.demo())
                        "report" -> ToolResult.text(ZxLedger.report())
                        else -> ToolResult.text(ZxLedger.guide())
                    }
                }
            }

            /* ── 语义字典 ───────────────────────────────────────────── */
            aiTool(name = "zx_sem",
                description = "用统一语义名跨组件取数：不管这个组件把「区间」叫 lo/hi 还是 ci[0]/ci[1]。"
            ) {
                param("op", ParamType.STRING, "get / set / list / audit / demo / report / guide", required = true)
                param("comp", ParamType.STRING, "组件名")
                param("sem", ParamType.STRING, "labels/values/ci/weight/group/prob/time/status/score")
                param("i", ParamType.NUMBER, "取第几个（可选）")
                param("v", ParamType.STRING, "set 时写入的值")
                param("dsl", ParamType.STRING, "get/set 时作为数据源的卡片 JSON")
                execute { args ->
                    when (args.string("op")) {
                        "get" -> {
                            val comp = args.string("comp")
                            val sem = ZxSem.Sem.values()
                                .firstOrNull { it.id() == args.string("sem") }
                                ?: return@execute ToolResult.text("未知语义：${args.string("sem")}")
                            val card = JSONObject(args.string("dsl"))
                            val r = ZxSem.get(card, comp, sem, args.number("i").toInt())
                            ToolResult.text(if (r.ok)
                                "$comp.${sem.id()} = ${r.value}${if (r.unit.isNotBlank()) "（${r.unit}）" else ""}"
                            else "取不到：${r.reason}")
                        }
                        "set" -> {
                            val comp = args.string("comp")
                            val sem = ZxSem.Sem.values()
                                .firstOrNull { it.id() == args.string("sem") }
                                ?: return@execute ToolResult.text("未知语义：${args.string("sem")}")
                            val card = JSONObject(args.string("dsl"))
                            val r = ZxSem.set(card, comp, sem, args.string("v"))
                            ToolResult.text((if (r.first) "✓ " else "✗ ") + r.second)
                        }
                        "list" -> ToolResult.text(ZxSem.render())
                        "audit" -> ToolResult.text(ZxSem.audit())
                        "demo" -> ToolResult.text(ZxSem.demo())
                        "report" -> ToolResult.text(ZxSem.report())
                        else -> ToolResult.text(ZxSem.guide())
                    }
                }
            }

            /* ── 因果推断界面 ─────────────────────────────────────── */
            aiTool(name = "zx_claim",
                description = "把「因为所以」背后没说出口的假设摆出来：几条假设、能不能验证、违反了会怎样。"
            ) {
                param("op", ParamType.STRING, "list / next / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "cause + effect + method + states{}")
                execute { args ->
                    when (args.string("op")) {
                        "list", "next" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxCausal.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else {
                                val claim = ZxCausal.from(o)
                                val list = ZxCausal.assumptions(claim)
                                ZxCausal.applyStates(list, o)
                                ToolResult.text(if (args.string("op") == "next")
                                    ZxCausal.next(list) else ZxCausal.render(claim, list))
                            }
                        }
                        "demo" -> ToolResult.text(ZxCausal.demo())
                        "report" -> ToolResult.text(ZxCausal.report())
                        else -> ToolResult.text(ZxCausal.guide())
                    }
                }
            }

            /* ── 流水线版本与复现 ─────────────────────────────────── */
            aiTool(name = "zx_pipever",
                description = "整条分析链快照、可回放、可对比两版差异。快照冻住输入，回放不重跑副作用。"
            ) {
                param("op", ParamType.STRING, "snap / diff / replay / tree / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "steps[{id,op,n,inputs[],params{},summary}]")
                param("a", ParamType.STRING, "diff 的左快照 id")
                param("b", ParamType.STRING, "diff 的右快照 id")
                param("id", ParamType.STRING, "replay 的快照 id")
                execute { args ->
                    when (args.string("op")) {
                        "snap" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxPipeVer.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else {
                                val s = ZxPipeVer.put(ZxPipeVer.from(o))
                                ToolResult.text("已存快照 ${s.id}「${s.label}」" +
                                    "[${s.branch}] ${s.steps.size} 步\n\n" + ZxPipeVer.tree())
                            }
                        }
                        "diff" -> ToolResult.text(
                            ZxPipeVer.renderDiff(args.string("a"), args.string("b")))
                        "replay" -> ToolResult.text(ZxPipeVer.replay(args.string("id")))
                        "tree" -> ToolResult.text(ZxPipeVer.tree())
                        "demo" -> ToolResult.text(ZxPipeVer.demo())
                        "report" -> ToolResult.text(ZxPipeVer.report())
                        else -> ToolResult.text(ZxPipeVer.guide())
                    }
                }
            }

            /* ── 假设检验界面 + 多重比较校正 ─────────────────────── */
            aiTool(name = "zx_hypo",
                description = "把「p=0.03」变成能追问的界面：稳不稳、换方法还是不是、效应多大。"
            ) {
                param("op", ParamType.STRING, "run / compare / correct / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "items[{n,a[],b[]}]")
                param("method", ParamType.STRING, "t / rank / perm")
                param("corr", ParamType.STRING, "none / bonf / bh（默认 bh）")
                execute { args ->
                    val meth = when (args.string("method")) {
                        "rank" -> ZxHypo.Method.RANK
                        "perm" -> ZxHypo.Method.PERM
                        else -> ZxHypo.Method.T
                    }
                    val corr = when (args.string("corr")) {
                        "none" -> ZxHypo.Corr.NONE
                        "bonf" -> ZxHypo.Corr.BONF
                        else -> ZxHypo.Corr.BH
                    }
                    when (args.string("op")) {
                        "run" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxHypo.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else ToolResult.text(ZxHypo.render(
                                ZxHypo.from(o), meth, corr, o.optDouble("q", 0.05)))
                        }
                        "compare" -> {
                            val o = JSONObject(args.string("dsl"))
                            val items = ZxHypo.from(o)
                            if (items.isEmpty()) ToolResult.text("DSL 解析失败")
                            else ToolResult.text(items.joinToString("\n\n") { it2 ->
                                "「${it2.name}」三种方法：\n" + ZxHypo.compare(it2)
                                    .joinToString("\n") { r ->
                                        "  ${r.method.label} p=${"%.4f".format(r.pRaw)} d=${"%.3f".format(r.d)}"
                                    }
                            })
                        }
                        "correct" -> {
                            val ps = args.string("dsl").split(",")
                                .mapNotNull { it.trim().toDoubleOrNull() }
                            if (ps.isEmpty()) ToolResult.text("需要一组 p 值，逗号分隔")
                            else ToolResult.text(ZxHypo.correct(ps, corr).second.let { sig ->
                                ZxHypo.correctionImpact(ps) })
                        }
                        "demo" -> ToolResult.text(ZxHypo.demo())
                        "report" -> ToolResult.text(ZxHypo.report())
                        else -> ToolResult.text(ZxHypo.guide())
                    }
                }
            }

            /* ── 分析流水线 ─────────────────────────────────────── */
            aiTool(name = "zx_pipe",
                description = "把一串分析步骤串成可整体重跑的链，上游失败下游待定。"
            ) {
                param("op", ParamType.STRING, "run / rerun / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "steps[{id,op,n,inputs[],params{}}]")
                param("from", ParamType.STRING, "rerun 时指定从哪一步开始")
                execute { args ->
                    when (args.string("op")) {
                        "run", "rerun" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxPipe.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else {
                                val run = ZxPipe.from(o)!!
                                run.runAll()
                                val from = args.string("from")
                                if (args.string("op") == "rerun" && from.isNotBlank()) run.rerunFrom(from)
                                ToolResult.text(ZxPipe.render(run))
                            }
                        }
                        "demo" -> ToolResult.text(ZxPipe.demo())
                        "report" -> ToolResult.text(ZxPipe.report())
                        else -> ToolResult.text(ZxPipe.guide())
                    }
                }
            }

            /* ── 约束可视化求解界面 ───────────────────────────── */
            aiTool(name = "zx_solve",
                description = "把「怎么改」变成看得见的界面。每项可拖，各维度同步变，超了变红。"
            ) {
                param("op", ParamType.STRING, "make / solve / apply / render / demo / report / guide", required = true)
                param("dsl", ParamType.STRING, "items[{n,v,lo,hi,prio}] + axes[{n,cap,cost[]}]")
                execute { args ->
                    when (args.string("op")) {
                        "make" -> {
                            val o = JSONObject(args.string("dsl"))
                            val chk = ZxSolveUI.validate(o)
                            if (!chk.first) ToolResult.text("校验失败：${chk.second}")
                            else ToolResult.text(ZxSolveUI.render(ZxSolveUI.from(o)!!))
                        }
                        "solve" -> {
                            val o = JSONObject(args.string("dsl"))
                            val st = ZxSolveUI.from(o)
                            if (st == null) ToolResult.text("DSL 解析失败")
                            else { st.solve(); ToolResult.text(ZxSolveUI.render(st)) }
                        }
                        "apply" -> {
                            val o = JSONObject(args.string("dsl"))
                            val st = ZxSolveUI.from(o)
                            if (st == null) ToolResult.text("DSL 解析失败")
                            else { st.solve(); ToolResult.text(st.applySuggest()) }
                        }
                        "demo" -> ToolResult.text(ZxSolveUI.demo())
                        "report" -> ToolResult.text(ZxSolveUI.report())
                        else -> ToolResult.text(ZxSolveUI.guide())
                    }
                }
            }

            /* ── 交互轨迹差异对比 ─────────────────────────────── */
            aiTool(name = "zx_tracediff",
                description = "两条轨迹并排，看在哪一步分叉。单看一条看不出「少了什么」。"
            ) {
                param("op", ParamType.STRING, "diff / missing / demo / report / guide", required = true)
                param("base", ParamType.STRING, "基准卡片 id（通常是上次能跑通的）")
                param("target", ParamType.STRING, "对比卡片 id（这次跑不通的）")
                execute { args ->
                    when (args.string("op")) {
                        "diff", "missing" -> {
                            val b = ZxTraceDiff.causal(ZxTrace.of(args.string("base")))
                            val t = ZxTraceDiff.causal(ZxTrace.of(args.string("target")))
                            val rows = ZxTraceDiff.diff(b, t)
                            if (rows.isEmpty()) ToolResult.text("两条轨迹都没有会写值的步骤")
                            else if (args.string("op") == "missing") {
                                val m = ZxTraceDiff.missing(rows)
                                ToolResult.text(if (m.isEmpty()) "没有缺少的步骤"
                                else "缺少的步骤（这才是原因）：\n" +
                                     m.joinToString("\n") { "  · ${it.line()}" })
                            } else ToolResult.text(ZxTraceDiff.render(rows,
                                args.string("base"), args.string("target")))
                        }
                        "demo" -> ToolResult.text(ZxTraceDiff.demo())
                        "report" -> ToolResult.text(ZxTraceDiff.report())
                        else -> ToolResult.text(ZxTraceDiff.guide())
                    }
                }
            }

            /* ── 卡片交互导出与复现 ───────────────────────────── */
            aiTool(name = "zx_trace",
                description = "把「到底点了什么」说清楚。录用户操作本身，不是渲染事件。"
            ) {
                param("op", ParamType.STRING, "record / export / replay / tail / demo / report / guide", required = true)
                param("card", ParamType.STRING, "卡片 id")
                param("path", ParamType.STRING, "字段路径")
                param("act", ParamType.STRING, "动作：click / set / toggle / submit / expand")
                param("to", ParamType.STRING, "新值")
                param("redact", ParamType.STRING, "导出是否脱敏（true/false）")
                param("confirm", ParamType.STRING, "回放是否执行副作用（true/false，默认 false）")
                execute { args ->
                    when (args.string("op")) {
                        "record" -> {
                            ZxTrace.record(args.string("card"), args.string("act")
                                .ifBlank { "click" }, args.string("path"),
                                "", args.string("to"))
                            ToolResult.text("✓ 已记录")
                        }
                        "export" -> ToolResult.text(ZxTrace.export(
                            args.string("card").takeIf { it.isNotBlank() },
                            args.string("redact") == "true"))
                        "replay" -> ToolResult.text(ZxTrace.replay(
                            args.string("card"), args.string("confirm") == "true"))
                        "tail" -> ToolResult.text(ZxTrace.tail(args.string("card")))
                        "demo" -> ToolResult.text(ZxTrace.demo())
                        "report" -> ToolResult.text(ZxTrace.report())
                        else -> ToolResult.text(ZxTrace.guide())
                    }
                }
            }

            /* ── 离线意图广播 ─────────────────────────────────── */
            aiTool(name = "zx_bcast",
                description = "断网时先把「打算改哪」排队，联网后补发。冲突提示不该来得太晚。"
            ) {
                param("op", ParamType.STRING, "send / flush / recv / queue / demo / report / guide", required = true)
                param("card", ParamType.STRING, "卡片 id")
                param("path", ParamType.STRING, "字段路径")
                param("kind", ParamType.STRING, "edit / focus / delete / move")
                param("site", ParamType.STRING, "对端站点名")
                param("seq", ParamType.STRING, "对端 seq（去重用）")
                param("online", ParamType.STRING, "设置在线状态（true/false）")
                execute { args ->
                    args.string("online").takeIf { it.isNotBlank() }
                        ?.let { ZxBcast.setOnline(it == "true") }
                    when (args.string("op")) {
                        "send" -> ToolResult.text(ZxBcast.send(
                            args.string("card"), args.string("path"),
                            args.string("kind").ifBlank { "edit" }))
                        "flush" -> ToolResult.text(ZxBcast.flush())
                        "recv" -> ToolResult.text(ZxBcast.onReceive(
                            args.string("site"), args.string("seq").toLongOrNull() ?: 0,
                            args.string("card"), args.string("path"),
                            args.string("kind").ifBlank { "edit" }))
                        "queue" -> ToolResult.text(ZxBcast.format())
                        "demo" -> ToolResult.text(ZxBcast.demo())
                        "report" -> ToolResult.text(ZxBcast.report())
                        else -> ToolResult.text(ZxBcast.guide())
                    }
                }
            }

            /* ── 多目标约束求解 ───────────────────────────────── */
            aiTool(name = "zx_multisolve",
                description = "多个限额同时满足时怎么权衡。找最紧的约束先缩，不追求一定有解。"
            ) {
                param("op", ParamType.STRING, "solve / gap / worst / demo / report / guide", required = true)
                param("items", ParamType.STRING, "cardId|path=预算:人力:工期，分号分隔")
                param("limits", ParamType.STRING, "维度名=限额，分号分隔")
                execute { args ->
                    fun parseItems(s: String): List<ZxMultiSolve.Item> = s.split(";", "；")
                        .map { it.trim() }.filter { it.isNotBlank() }
                        .mapNotNull { seg ->
                            val i = seg.indexOf('='); if (i < 0) return@mapNotNull null
                            val lp = seg.substring(0, i)
                            val nums = seg.substring(i + 1).split(":")
                                .mapNotNull { it.trim().toDoubleOrNull() }
                            if (nums.size < 3) return@mapNotNull null
                            val j = lp.indexOf('|'); if (j < 0) return@mapNotNull null
                            ZxMultiSolve.Item(lp.substring(0, j), lp.substring(j + 1),
                                mapOf("预算" to nums[0], "人力" to nums[1], "工期" to nums[2]))
                        }
                    fun parseLimits(s: String): List<ZxMultiSolve.Limit> = s.split(";", "；")
                        .map { it.trim() }.filter { it.isNotBlank() }
                        .mapNotNull { seg ->
                            val i = seg.indexOf('='); if (i < 0) return@mapNotNull null
                            val cap = seg.substring(i + 1).toDoubleOrNull() ?: return@mapNotNull null
                            val d = seg.substring(0, i).trim()
                            ZxMultiSolve.Limit(d, cap, d)
                        }
                    when (args.string("op")) {
                        "solve", "gap", "worst" -> {
                            val items = parseItems(args.string("items"))
                            val limits = parseLimits(args.string("limits"))
                            if (items.isEmpty() || limits.isEmpty())
                                return@execute ToolResult.text("要给 items 和 limits。")
                            when (args.string("op")) {
                                "gap" -> return@execute ToolResult.text(ZxMultiSolve.gap(items, limits))
                                "worst" -> return@execute ToolResult.text(buildString {
                                    appendLine("最该砍的项（按各超限维度的相对占用）：")
                                    ZxMultiSolve.worstOffenders(items, limits).forEach { (it, s) ->
                                        appendLine("  ${it.path}（相对占用 %.2f）".format(s)) } })
                            }
                            val (p, e) = ZxMultiSolve.solve(items, limits)
                            ToolResult.text(buildString {
                                if (p != null) {
                                    appendLine("最紧的约束：" + p.binding)
                                    p.changes.forEach { (_, path, cost) ->
                                        appendLine("  $path → " + cost.entries.joinToString(" ") {
                                            "${it.key}=${"%.1f".format(it.value)}" }) }
                                    appendLine("达成：" + p.totals.entries.joinToString("  ") {
                                        "${it.key}=${"%.1f".format(it.value)}" })
                                    appendLine()
                                    appendLine(p.note)
                                } else {
                                    appendLine("无解：" + (e?.reason ?: ""))
                                    e?.blockers?.forEach { appendLine("  · $it") }
                                    appendLine()
                                    append(ZxMultiSolve.gap(items, limits))
                                }
                            })
                        }
                        "demo" -> ToolResult.text(ZxMultiSolve.demo())
                        "report" -> ToolResult.text(ZxMultiSolve.report())
                        else -> ToolResult.text(ZxMultiSolve.guide())
                    }
                }
            }

            /* ── 协同编辑 ─────────────────────────────────────── */
            aiTool(name = "zx_collab",
                description = "让「谁在改」看得见。改之前就看见，比改完才提示冲突有用。"
            ) {
                param("op", ParamType.STRING, "get / focus / blur / clash / sweep / demo / report / guide", required = true)
                param("card", ParamType.STRING, "卡片 id")
                param("path", ParamType.STRING, "字段路径")
                param("site", ParamType.STRING, "远端站点名（模拟对端）")
                execute { args ->
                    when (args.string("op")) {
                        "focus" -> {
                            val c = ZxCollab.focus(args.string("card"), args.string("path"))
                            ToolResult.text(if (c != null)
                                "✓ 已标记我在改 ${c.cardId}${c.path.takeIf { it.isNotBlank() }?.let { " / $it" } ?: ""}"
                            else "要先 attachLocal。")
                        }
                        "blur" -> { ZxCollab.blur(); ToolResult.text("✓ 已取消标记") }
                        "clash" -> ToolResult.text("撞车：" +
                            ZxCollab.clash(args.string("card"), args.string("path")))
                        "sweep" -> ToolResult.text("清掉 ${ZxCollab.sweep()} 条过期状态")
                        "get" -> ToolResult.text(ZxCollab.format())
                        "demo" -> ToolResult.text(ZxCollab.demo())
                        "report" -> ToolResult.text(ZxCollab.report())
                        else -> ToolResult.text(ZxCollab.guide())
                    }
                }
            }

            /* ── 意图级重放 ───────────────────────────────────── */
            aiTool(name = "zx_replay",
                description = "回退到某轮之后还能重做回来。不敢退的功能等于没有，所以回退不能单向。"
            ) {
                param("op", ParamType.STRING, "redo / undo / get / demo / report / guide", required = true)
                execute { args ->
                    when (args.string("op")) {
                        "redo" -> {
                            val h = ZxReplay.redo()
                            ToolResult.text(if (h != null)
                                "✓ 重做：回到 #${h.markId} ${h.label}（前进 ${h.steps} 步）"
                            else "没有可重做的退回。")
                        }
                        "undo" -> {
                            val h = ZxReplay.undoRedo()
                            ToolResult.text(if (h != null)
                                "✓ 撤销这次重做：退回 #${h.markId}（后退 ${h.steps} 步）"
                            else "没有已重做的记录。")
                        }
                        "get" -> ToolResult.text(ZxReplay.format())
                        "demo" -> ToolResult.text(ZxReplay.demo())
                        "report" -> ToolResult.text(ZxReplay.report())
                        else -> ToolResult.text(ZxReplay.guide())
                    }
                }
            }

            /* ── 增量求解 ─────────────────────────────────────── */
            aiTool(name = "zx_incsolve",
                description = "约束求解只重算受影响的部分。增量与全量必须一致，不确定就退回全量。"
            ) {
                param("op", ParamType.STRING, "scale / consistent / demo / report / guide", required = true)
                param("items", ParamType.STRING, "cardId|path=value[:min]，分号分隔")
                param("limit", ParamType.STRING, "限额")
                param("changed", ParamType.STRING, "改动的项下标（0 起）")
                execute { args ->
                    fun parse(s: String): List<ZxIncSolve.Item> = s.split(";", "；")
                        .map { it.trim() }.filter { it.isNotBlank() }
                        .mapNotNull { seg ->
                            val i = seg.indexOf('='); if (i < 0) return@mapNotNull null
                            val lp = seg.substring(0, i)
                            val rest = seg.substring(i + 1).split(":")
                            val j = lp.indexOf('|'); if (j < 0) return@mapNotNull null
                            ZxIncSolve.Item(lp.substring(0, j), lp.substring(j + 1),
                                rest.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null,
                                rest.getOrNull(1)?.toDoubleOrNull() ?: 0.0)
                        }
                    when (args.string("op")) {
                        "scale", "consistent" -> {
                            val items = parse(args.string("items"))
                            val limit = args.string("limit").toDoubleOrNull()
                            val ci = args.string("changed").toIntOrNull() ?: 0
                            if (items.isEmpty() || limit == null)
                                return@execute ToolResult.text("要给 items 和 limit。")
                            if (args.string("op") == "consistent")
                                return@execute ToolResult.text("与全量一致：" +
                                    ZxIncSolve.consistent(items, items.map { it.value }, ci, limit))
                            val r = ZxIncSolve.scaleIncremental(
                                items, items.map { it.value }, ci, limit)
                            ToolResult.text(buildString {
                                appendLine(if (r.incremental) "增量路径" else "退回全量")
                                appendLine("重算 ${r.touched} / ${items.size} 项，达成 %.2f".format(r.total))
                                r.changed.forEach { (c, p, v) ->
                                    appendLine("  $p → %.2f".format(v)) }
                                appendLine()
                                appendLine(r.note)
                            })
                        }
                        "demo" -> ToolResult.text(ZxIncSolve.demo())
                        "report" -> ToolResult.text(ZxIncSolve.report())
                        else -> ToolResult.text(ZxIncSolve.guide())
                    }
                }
            }

            /* ── 轮次边界（意图级撤销） ───────────────────────── */
            aiTool(name = "zx_turn",
                description = "撤销到「AI 那一轮之前」，而不是逐步退。用户想要的是回到某个时刻，不是倒着走 N 步。"
            ) {
                param("op", ParamType.STRING, "begin / to / prev / get / report / guide", required = true)
                param("label", ParamType.STRING, "本轮摘要，用于给边界命名")
                param("id", ParamType.STRING, "to 时指定边界 id")
                execute { args ->
                    when (args.string("op")) {
                        "begin" -> {
                            val m = ZxTurn.begin(args.string("label"))
                            ToolResult.text("✓ 已记边界 #${m.id}「${m.label}」（栈深 ${m.depth}）")
                        }
                        "to" -> {
                            val id = args.string("id").toIntOrNull()
                                ?: return@execute ToolResult.text("id 不是数字")
                            val (ok, n) = ZxTurn.stepsTo(id)
                            ToolResult.text(if (ok) "退到 #$id 需要撤销 $n 步。"
                                            else "已经在 #$id 或更靠前的位置了。")
                        }
                        "prev" -> {
                            val (ok, n) = ZxTurn.stepsToPrev()
                            ToolResult.text(if (ok) "退到上一轮需要撤销 $n 步。"
                                            else "只有一个边界，或已经在第一个边界之前。")
                        }
                        "get" -> ToolResult.text(ZxTurn.format())
                        "report" -> ToolResult.text(ZxTurn.report())
                        else -> ToolResult.text(ZxTurn.guide())
                    }
                }
            }

            /* ── 跨卡约束求解 ─────────────────────────────────── */
            aiTool(name = "zx_solve",
                description = "多张卡的约束打架时给出可行解：等比缩放（保比例）或按优先级扣减（保重点）。只给建议不改卡。"
            ) {
                param("op", ParamType.STRING, "scale / prio / demo / report / guide", required = true)
                param("items", ParamType.STRING, "cardId|path=value[:min][:prio]，分号分隔")
                param("limit", ParamType.STRING, "限额")
                execute { args ->
                    fun parse(s: String): List<ZxSolve.Item> = s.split(";", "；")
                        .map { it.trim() }.filter { it.isNotBlank() }
                        .mapNotNull { seg ->
                            val i = seg.indexOf('='); if (i < 0) return@mapNotNull null
                            val lp = seg.substring(0, i)
                            val rest = seg.substring(i + 1).split(":")
                            val j = lp.indexOf('|'); if (j < 0) return@mapNotNull null
                            ZxSolve.Item(lp.substring(0, j), lp.substring(j + 1),
                                rest.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null,
                                rest.getOrNull(1)?.toDoubleOrNull(),
                                rest.getOrNull(2)?.toIntOrNull() ?: 0)
                        }
                    when (args.string("op")) {
                        "scale", "prio" -> {
                            val items = parse(args.string("items"))
                            val limit = args.string("limit").toDoubleOrNull()
                            if (items.isEmpty() || limit == null)
                                return@execute ToolResult.text("要给 items 和 limit。" +
                                    "items 格式：cardId|path=value[:min][:prio]，分号分隔")
                            val (p, e) = if (args.string("op") == "scale")
                                ZxSolve.scale(items, limit) else ZxSolve.byPriority(items, limit)
                            ToolResult.text(if (p != null) ZxSolve.format(p)
                            else buildString {
                                appendLine(e?.reason)
                                e?.blockers?.forEach { appendLine("  · $it") }
                            })
                        }
                        "demo" -> ToolResult.text(ZxSolve.demo())
                        "report" -> ToolResult.text(ZxSolve.report())
                        else -> ToolResult.text(ZxSolve.guide())
                    }
                }
            }

            /* ── 渐进式披露 ───────────────────────────────────── */
            aiTool(name = "zx_disclose",
                description = "长卡该不该折叠。给摘要、给重点（异常值/被标记行，不机械取前 N 行）、记住展开状态。只给建议不改 DSL。"
            ) {
                param("op", ParamType.STRING, "advise / expand / collapse / report / guide", required = true)
                param("card", ParamType.STRING, "cardId")
                param("component", ParamType.STRING, "组件名")
                execute { args ->
                    val component = args.string("component")
                    val dsl = runCatching { JSONObject(ZxCardStore.find(args.string("card")) ?: "") }
                        .getOrElse { JSONObject() }
                    when (args.string("op")) {
                        "advise" -> {
                            if (!dsl.has("type")) return@execute ToolResult.text("找不到卡片 ${args.string("card")}")
                            val a = ZxDisclose.advise(component, dsl)
                            ToolResult.text(if (a.collapse)
                                "建议折叠：${a.hint}；保留第 ${a.keep.joinToString(",")} 项（按异常值/标记挑的，不是前几项）"
                            else "不需要折叠（共 ${a.total} 项，未达阈值，或用户已展开过同类卡）")
                        }
                        "expand" -> {
                            ZxDisclose.markExpanded(component, dsl)
                            ToolResult.text("已记住：同类卡片（$component，相近规模）下次默认展开")
                        }
                        "collapse" -> {
                            ZxDisclose.markCollapsed(component, dsl)
                            ToolResult.text("已取消该类默认展开")
                        }
                        "report" -> ToolResult.text(ZxDisclose.report())
                        else -> ToolResult.text(ZxDisclose.guide())
                    }
                }
            }

            /* ── 事务冲突合并 ─────────────────────────────────── */
            aiTool(name = "zx_txmerge",
                description = "两个事务改了同一张卡时怎么并。不同字段自动合并、同值幂等、异值真冲突必须让人判。"
            ) {
                param("op", ParamType.STRING, "merge / cases / guide", required = true)
                param("a", ParamType.STRING, "事务 A 的步骤，格式 cardId|path=value，分号分隔")
                param("b", ParamType.STRING, "事务 B 的步骤，同格式")
                execute { args ->
                    fun parse(s: String): List<ZxTxMerge.Step> = s.split(";", "；")
                        .map { it.trim() }.filter { it.isNotBlank() }
                        .mapNotNull { seg ->
                            val i = seg.indexOf('='); if (i < 0) return@mapNotNull null
                            val lp = seg.substring(0, i)
                            val j = lp.indexOf('|'); if (j < 0) return@mapNotNull null
                            ZxTxMerge.Step(lp.substring(0, j), lp.substring(j + 1), seg.substring(i + 1))
                        }
                    when (args.string("op")) {
                        "merge" -> {
                            val a = parse(args.string("a")); val b = parse(args.string("b"))
                            if (a.isEmpty() || b.isEmpty())
                                return@execute ToolResult.text("两边都要给步骤，格式 cardId|path=value，分号分隔")
                            ToolResult.text(ZxTxMerge.report(ZxTxMerge.merge(a, b)))
                        }
                        "cases" -> ToolResult.text(ZxTxMerge.cases())
                        else -> ToolResult.text(ZxTxMerge.guide())
                    }
                }
            }

            /* ── 卡片生命周期 ─────────────────────────────────── */
            aiTool(name = "zx_life",
                description = "查询或推进卡片走到哪一步了。表单填了没、审批过了没，用这个问，别靠猜 DSL 里有没有值。"
            ) {
                param("op", ParamType.STRING, "init / advance / back / reset / get / report / guide", required = true)
                param("card", ParamType.STRING, "cardId")
                param("to", ParamType.STRING, "目标阶段 key")
                execute { args ->
                    val card = args.string("card")
                    when (args.string("op")) {
                        "advance" -> ToolResult.text(ZxLifecycle.advance(card, args.string("to")).let {
                            if (it.first) "✓ ${it.second}" else it.second })
                        "back" -> ToolResult.text(ZxLifecycle.back(card).let {
                            if (it.first) "✓ ${it.second}" else it.second })
                        "reset" -> ToolResult.text(ZxLifecycle.reset(card).let {
                            if (it.first) "✓ ${it.second}" else it.second })
                        "get" -> ToolResult.text(ZxLifecycle.format(card))
                        "report" -> ToolResult.text(ZxLifecycle.report())
                        else -> ToolResult.text(ZxLifecycle.guide())
                    }
                }
            }

            /* ── 事务可视化 ───────────────────────────────────── */
            aiTool(name = "zx_txview",
                description = "把一组跨卡改动在界面上变成一个可撤销单元。提交多张卡后用它命名并标记同源。"
            ) {
                param("op", ParamType.STRING, "begin / undo / get / report / guide", required = true)
                param("name", ParamType.STRING, "事务名，如「提交 Q4 预算」")
                param("cards", ParamType.STRING, "参与的 cardId，逗号分隔")
                param("card", ParamType.STRING, "undo 时给任一参与的 cardId")
                execute { args ->
                    when (args.string("op")) {
                        "begin" -> {
                            val cards = args.string("cards").split(",", "，")
                                .map { it.trim() }.filter { it.isNotBlank() }
                            if (cards.isEmpty()) ToolResult.text("没有给 cards——不给参与的卡就标不出边界")
                            else {
                                val id = ZxTxView.begin(args.string("name").ifBlank { "未命名事务" }, cards)
                                ToolResult.text("✓ 已命名事务 $id「${args.string("name")}」，" +
                                    "标记 ${cards.size} 张卡同源，撤销时按一组撤。")
                            }
                        }
                        "undo" -> {
                            val (ok, list) = ZxTxView.undo(args.string("card"))
                            ToolResult.text(if (ok) "✓ 已整组撤销 ${list.size} 张卡：${list.joinToString(", ")}"
                                            else "这张卡不属于任何生效中的事务")
                        }
                        "get" -> ToolResult.text(ZxTxView.format())
                        "report" -> ToolResult.text(ZxTxView.report())
                        else -> ToolResult.text(ZxTxView.guide())
                    }
                }
            }

            /* ── DSL 补丁协议 ─────────────────────────────────── */
            aiTool(name = "zx_patch",
                description = "改一张卡只发补丁不重发整份 DSL。改 3 个单元格时约 60 token，重发整份约 2000。应用后自动校验，失败整体回滚。"
            ) {
                param("op", ParamType.STRING, "apply / suggest / guide", required = true)
                param("card", ParamType.STRING, "cardId")
                param("component", ParamType.STRING, "组件名")
                param("patch", ParamType.STRING, "补丁文本，一行一步")
                execute { args ->
                    val component = args.string("component")
                    val dsl = runCatching { JSONObject(ZxCardStore.find(args.string("card")) ?: "") }
                        .getOrElse { JSONObject() }
                    when (args.string("op")) {
                        "apply" -> {
                            if (!dsl.has("type"))
                                return@execute ToolResult.text("找不到卡片 ${args.string("card")}")
                            val steps = ZxPatch.parse(args.string("patch"))
                            if (steps.isEmpty())
                                return@execute ToolResult.text("补丁为空或格式不对。\n" + ZxPatch.suggest(component, dsl))
                            val r = ZxPatch.apply(component, dsl, steps)
                            if (r.ok) {
                                ZxCardStore.emit("修订卡片", component, r.dsl.toString())
                                ToolResult.text(r.reason)
                            } else ToolResult.text(r.reason)
                        }
                        "suggest" -> ToolResult.text(ZxPatch.suggest(component, dsl))
                        else -> ToolResult.text(ZxPatch.guide())
                    }
                }
            }

            /* ── 渲染偏好学习 ─────────────────────────────────── */
            aiTool(name = "zx_pref",
                description = "查询用户对某个意图习惯用哪个组件。样本不足会明说不给建议——三次观测就断言偏好是在编故事。"
            ) {
                param("op", ParamType.STRING, "advise / profile / record / summary / guide", required = true)
                param("cands", ParamType.STRING, "候选组件，逗号分隔")
                param("intent", ParamType.STRING, "本轮意图描述")
                param("component", ParamType.STRING, "组件名（profile/record 用）")
                param("signal", ParamType.STRING, "asked / used / accepted / rejected")
                execute { args ->
                    when (args.string("op")) {
                        "advise" -> {
                            val cands = args.string("cands").split(",", "，")
                                .map { it.trim() }.filter { it.isNotBlank() }
                            ToolResult.text(ZxPref.advise(cands, args.string("intent")))
                        }
                        "profile" -> ToolResult.text(ZxPref.profile(args.string("component")))
                        "record" -> {
                            val sig = when (args.string("signal").lowercase()) {
                                "asked" -> ZxPref.Signal.ASKED
                                "used" -> ZxPref.Signal.USED
                                "rejected" -> ZxPref.Signal.REJECTED
                                else -> ZxPref.Signal.ACCEPTED
                            }
                            ZxPref.record(args.string("component"), sig)
                            ToolResult.text("已记录 ${args.string("component")} / ${sig.label}（共 ${ZxPref.size()} 次）")
                        }
                        "summary" -> ToolResult.text(ZxPref.summary())
                        else -> ToolResult.text(ZxPref.guide())
                    }
                }
            }

            /* ── 跨会话卡片索引 ───────────────────────────────── */
            aiTool(name = "zx_index",
                description = "按内容搜历史卡片，不读全文就能定位。用户说「上周那张预算表」时用这个，别把全部卡片读进上下文。"
            ) {
                param("op", ParamType.STRING, "search / put / drop / audit / report / guide", required = true)
                param("q", ParamType.STRING, "检索词，空格分隔多个词")
                param("card", ParamType.STRING, "cardId")
                param("session", ParamType.STRING, "会话 id（drop 用）")
                param("topK", ParamType.STRING, "返回条数，默认 6")
                execute { args ->
                    when (args.string("op")) {
                        "search" -> ToolResult.text(ZxIndex.format(
                            args.string("q"),
                            args.string("topK").toIntOrNull() ?: 6))
                        "put" -> {
                            val dsl = runCatching { JSONObject(args.string("dsl")) }
                                .getOrElse { JSONObject() }
                            ZxIndex.put(args.string("card"), args.string("component"),
                                args.string("title"), dsl, args.string("session"))
                            ToolResult.text("已索引 ${args.string("card")}（索引共 ${ZxIndex.size()} 条）")
                        }
                        "drop" -> ToolResult.text(
                            "已清理 ${ZxIndex.dropSession(args.string("session"))} 条")
                        "audit" -> {
                            val a = ZxIndex.audit { ZxCardStore.find(it) != null }
                            ToolResult.text(if (a.isEmpty()) "✓ 索引与卡片一致。"
                                            else a.joinToString("\n"))
                        }
                        "report" -> ToolResult.text(ZxIndex.report())
                        else -> ToolResult.text(ZxIndex.guide())
                    }
                }
            }

            /* ── 卡片热更新 ───────────────────────────────────── */
            aiTool(name = "zx_hot",
                description = "不重装插件就加一个组件：用别名+预设（ALIAS）或容器组合（COMBO）描述新组件。不做代码注入。"
            ) {
                param("op", ParamType.STRING, "define / undefine / list / audit / guide", required = true)
                param("name", ParamType.STRING, "新组件名，须匹配 zx_[a-z][a-z0-9_]{1,20}")
                param("kind", ParamType.STRING, "ALIAS / COMBO")
                param("base", ParamType.STRING, "底层组件名或容器名")
                param("defaults", ParamType.STRING, "ALIAS 的字段默认值，JSON")
                param("map", ParamType.STRING, "ALIAS 的字段重命名，如 title→n")
                param("parts", ParamType.STRING, "COMBO 的 slot→组件名，如 a:zx_chart,b:zx_table")
                execute { args ->
                    when (args.string("op")) {
                        "undefine" -> {
                            val r = ZxHot.undefine(args.string("name"))
                            ToolResult.text(if (r.ok) r.reason else "失败：${r.reason}")
                        }
                        "list" -> ToolResult.text(ZxHot.list())
                        "audit" -> {
                            val a = ZxHot.audit()
                            ToolResult.text(if (a.isEmpty()) "✓ 热更新组件全部健康。"
                                            else a.joinToString("\n"))
                        }
                        "define" -> {
                            val name = args.string("name")
                            val kind = runCatching {
                                ZxHot.Kind.valueOf(args.string("kind").uppercase()) }
                                .getOrNull()
                                ?: return@execute ToolResult.text("kind 必须是 ALIAS 或 COMBO")
                            val defaults = args.string("defaults").let {
                                if (it.isBlank()) JSONObject()
                                else runCatching { JSONObject(it) }.getOrElse { JSONObject() } }
                            val map = args.string("map").split(";")
                                .mapNotNull { p ->
                                    val kv = p.split("→", "->").map { it.trim() }
                                    if (kv.size == 2 && kv[0].isNotBlank()) kv[0] to kv[1] else null }
                                .toMap()
                            val parts = args.string("parts").split(",")
                                .mapNotNull { p ->
                                    val kv = p.split(":").map { it.trim() }
                                    if (kv.size == 2 && kv[0].isNotBlank()) kv[0] to kv[1] else null }
                                .toMap()
                            val r = ZxHot.define(ZxHot.Def(name, kind, args.string("base"),
                                args.string("label").ifBlank { name }, defaults, map, parts))
                            ToolResult.text(if (r.ok) r.reason else "失败：${r.reason}")
                        }
                        else -> ToolResult.text(ZxHot.guide())
                    }
                }
            }

            /* ── 意图反向编译 ─────────────────────────────────── */
            aiTool(name = "zx_reverse",
                description = "从已有卡片反推意图，让模型能复用上一轮的卡（换个数据再来一张）。认不出来会明说，不猜。"
            ) {
                param("op", ParamType.STRING, "infer / saving / report / guide", required = true)
                param("component", ParamType.STRING, "组件名")
                param("dsl", ParamType.STRING, "卡片 DSL")
                execute { args ->
                    when (args.string("op")) {
                        "saving" -> {
                            val o = runCatching { JSONObject(args.string("dsl")) }.getOrNull()
                                ?: return@execute ToolResult.text("dsl 不是合法 JSON")
                            ToolResult.text(ZxReverse.saving(args.string("component"), o))
                        }
                        "infer" -> {
                            val o = runCatching { JSONObject(args.string("dsl")) }.getOrNull()
                                ?: return@execute ToolResult.text("dsl 不是合法 JSON")
                            val it = ZxReverse.infer(args.string("component"), o)
                            ToolResult.text(it?.toJson()
                                ?: "无法反推 ${args.string("component")}——宁可自己重写，也不要猜一个看起来像的")
                        }
                        "report" -> ToolResult.text(ZxReverse.report())
                        else -> ToolResult.text(ZxReverse.guide())
                    }
                }
            }

            /* ── 自解释协议 ───────────────────────────────────── */
            aiTool(name = "zx_introspect",
                description = "让组件自己说明该怎么用它：骨架 + 硬约束 + 用户动作 + 常见错误写法。写 DSL 前先问一次，可以省掉一整轮试错。"
            ) {
                param("op", ParamType.STRING, "describe / rules / report / guide", required = true)
                param("component", ParamType.STRING, "describe 时的组件名")
                param("names", ParamType.STRING, "rules 时的组件名，逗号分隔")
                param("parts", ParamType.STRING, "逗号分隔：skeleton/rules/actions/anti")
                execute { args ->
                    when (args.string("op")) {
                        "rules" -> ToolResult.text(ZxIntrospect.rulesFor(
                            args.string("names").split(",").map { it.trim() }.filter { it.isNotBlank() }))
                        "report" -> ToolResult.text(ZxIntrospect.report())
                        "describe" -> {
                            val ps = args.string("parts").split(",")
                                .map { it.trim().lowercase() }.filter { it.isNotBlank() }
                            val set = ps.mapNotNull {
                                runCatching { ZxIntrospect.Part.valueOf(it.uppercase()) }.getOrNull()
                            }.toSet().ifEmpty {
                                setOf(ZxIntrospect.Part.SKELETON, ZxIntrospect.Part.RULES)
                            }
                            ToolResult.text(ZxIntrospect.describe(args.string("component"), set))
                        }
                        else -> ToolResult.text(ZxIntrospect.guide())
                    }
                }
            }

            /* ── 端到端回归测试 ───────────────────────────────── */
            aiTool(name = "zx_regress",
                description = "跑全部组件的示例 DSL 并比对渲染指纹，抓出「改了渲染器/校验器把别处改坏了」。"
            ) {
                param("op", ParamType.STRING, "run / diff / baseline / update / report / guide", required = true)
                param("names", ParamType.STRING, "update 时的组件名，逗号分隔")
                execute { args ->
                    when (args.string("op")) {
                        "baseline" -> {
                            val items = ZxRegress.run()
                            val r = ZxRegress.diff(items)
                            ToolResult.text("已存基线 ${r.baselineUpdated.size} 个。\n" + ZxRegress.render())
                        }
                        "diff" -> {
                            val items = ZxRegress.run()
                            val d = ZxRegress.diff(items)
                            ToolResult.text(if (d.healthy) "✓ 与基线完全一致。"
                                            else "与基线有差异：changed=${d.changed.size}, failed=${d.failed.size}, noSample=${d.noSample.size} 个：\n" +
                                                d.changed.take(10).joinToString("\n") { it.name } +
                                                d.failed.take(10).joinToString("\n") { it.name })
                        }
                        "update" -> {
                            ZxRegress.run()
                            val ns = args.string("names").split(",").map { it.trim() }.filter { it.isNotBlank() }
                            ToolResult.text("已更新 ${ZxRegress.update()} 个组件的基线。")
                        }
                        "report" -> ToolResult.text(ZxRegress.report())
                        else -> ToolResult.text(ZxRegress.guide())
                    }
                }
            }

            /* ── 冲突解决 ─────────────────────────────────────── */
            aiTool(name = "zx_conflict_resolve",
                description = "把 CRDT 合并会覆盖什么变成可交互卡片：预览代价 + 三组选项 + 可撤销。"
            ) {
                param("op", ParamType.STRING, "preview / resolve / undo / report / guide", required = true)
                param("card", ParamType.STRING, "resolve 时的卡片 ID")
                param("path", ParamType.STRING, "resolve 时的字段路径")
                param("side", ParamType.STRING, "LOCAL / REMOTE / MANUAL")
                param("value", ParamType.STRING, "MANUAL 时填的新值")
                execute { args ->
                    when (args.string("op")) {
                        "resolve" -> {
                            val r = ZxConflict.resolve(
                                args.string("card"), args.string("path"),
                                runCatching { ZxConflict.Side.valueOf(args.string("side")) }
                                    .getOrDefault(ZxConflict.Side.LOCAL),
                                args.string("value"))
                            ToolResult.text("已解决 ${r.cardId}.${r.path} → ${r.side} = ${r.value}")
                        }
                        "undo" -> ToolResult.text(
                            ZxConflict.undo()?.let { "已撤销 ${it.cardId}.${it.path}" } ?: "没有可撤销的解决。")
                        "report" -> ToolResult.text(ZxConflict.report(ZxConflict.preview(
                            ZxConflict.curSnapshotLocal(), ZxConflict.curSnapshotRemote())))
                        "preview" -> ToolResult.text(ZxConflict.toDsl(ZxConflict.preview(
                            ZxConflict.curSnapshotLocal(), ZxConflict.curSnapshotRemote())))
                        else -> ToolResult.text(ZxConflict.guide())
                    }
                }
            }

            /* ── 流式增量渲染 ─────────────────────────────────── */
            aiTool(name = "zx_stream",
                description = "卡片的 token 级增量渲染：只把新增的一段拼上去，不整卡重绘（会丢滚动位置和输入焦点）。"
            ) {
                param("op", ParamType.STRING, "begin/append/patch/end/simulate/report/guide", required = true)
                param("card", ParamType.STRING, "卡片 ID")
                param("path", ParamType.STRING, "append/patch 的目标路径")
                param("value", ParamType.STRING, "append/patch 的内容")
                param("component", ParamType.STRING, "begin 时的组件类型")
                execute { args ->
                    val id = args.string("card")
                    when (args.string("op")) {
                        "begin" -> {
                            if (id.isBlank()) return@execute ToolResult.text("begin 需要 card。")
                            ToolResult.text("已开流：" + ZxStream.begin(id, args.string("component")).line())
                        }
                        "append" -> {
                            val c = ZxStream.append(id, args.string("path"), args.string("value"))
                            ToolResult.text(c?.line() ?: "卡片 $id 没有活跃流，先调 begin。")
                        }
                        "patch" -> {
                            val c = ZxStream.patch(id, args.string("path"), args.string("value"))
                            ToolResult.text(c?.line() ?: "卡片 $id 没有活跃流，先调 begin。")
                        }
                        "end" -> ToolResult.text(ZxStream.end(id)?.line() ?: "没有活跃流。")
                        "simulate" -> {
                            val out = ZxStream.simulate(id, args.string("component"), args.string("value"))
                            ToolResult.text(out.take(6).joinToString("\n") { it.line() } +
                                "\n…共 ${out.size} 个增量片段")
                        }
                        "report" -> ToolResult.text(ZxStream.report())
                        "guide" -> ToolResult.text(ZxStream.guide())
                        else -> ToolResult.text(ZxStream.guide())
                    }
                }
            }

            /* ── 跨卡查询语言 ─────────────────────────────────── */
            aiTool(name = "zx_query",
                description = "跨卡查询语言：用一句查询从多张卡里取数，而不是把整张卡读进上下文。只读。"
            ) {
                param("q", ParamType.STRING, "查询表达式，如 get(budget.rows[k=dev].v[1]) / sum(budget.rows.*.v[1])")
                param("op", ParamType.STRING, "report / guide")
                execute { args ->
                    if (args.string("op") == "guide") return@execute ToolResult.text(ZxQuery.guide())
                    if (args.string("op") == "report") return@execute ToolResult.text(ZxQuery.report())
                    val q = args.string("q")
                    if (q.isBlank()) return@execute ToolResult.text(ZxQuery.guide())
                    ToolResult.text(ZxQuery.exec(q))
                }
            }

            /* ── 多卡过渡编排 ─────────────────────────────────── */
            aiTool(name = "zx_transition",
                description = "多卡同时更新时的补间与调度，让一批卡的变化看起来是同一个动作。op=plan/planRefs/report/stats/guide。"
            ) {
                param("op", ParamType.STRING, "plan / planRefs / report / stats / guide", required = true)
                param("card", ParamType.STRING, "planRefs 时的源卡片 ID")
                execute { args ->
                    when (args.string("op")) {
                        "planRefs" -> {
                            val id = args.string("card")
                            if (id.isBlank()) return@execute ToolResult.text("planRefs 需要 card（源卡片）。")
                            val b = ZxTransition.planFromRefs(id, emptyMap())
                            ToolResult.text(ZxTransition.report())
                        }
                        "report" -> ToolResult.text(ZxTransition.report())
                        "stats" -> ToolResult.text(ZxTransition.stats())
                        "guide" -> ToolResult.text(ZxTransition.guide())
                        else -> ToolResult.text(ZxTransition.guide())
                    }
                }
            }

            /* ── 跨会话持久化 ─────────────────────────────────── */
            aiTool(name = "zx_persist",
                description = "跨会话卡片持久化：关掉对话再回来交互状态还在。op=save/saveAll/restore/export/import/forget/clear/report/guide。"
            ) {
                param("op", ParamType.STRING, "save / saveAll / restore / export / import / forget / clear / report / guide", required = true)
                param("card", ParamType.STRING, "卡片 ID")
                param("json", ParamType.STRING, "import 时的 JSON")
                execute { args ->
                    when (args.string("op")) {
                        "save" -> {
                            val id = args.string("card")
                            ToolResult.text(if (ZxPersist.save(id)) "已保存 $id 的状态。"
                                            else "保存失败：卡片 $id 不存在。")
                        }
                        "saveAll" -> { ZxPersist.saveAll(); ToolResult.text("已保存 ${ZxPersist.size()} 张卡。") }
                        "restore" -> {
                            val r = ZxPersist.restore(args.string("card"))
                            ToolResult.text(if (r == null) "不能恢复（组件改过版或卡片已不存在）——应重新渲染，不要硬塞空状态。"
                                           else "可恢复：${r.component}，${r.edits.size} 处改动。")
                        }
                        "export" -> ToolResult.text(ZxPersist.export())
                        "import" -> {
                            val (ok, skip) = ZxPersist.import(args.string("json"))
                            ToolResult.text("导入 $ok 张，跳过 $skip 张。")
                        }
                        "forget" -> { ZxPersist.forget(args.string("card")); ToolResult.text("已遗忘该卡状态") }
                        "clear" -> { ZxPersist.clear(); ToolResult.text("已清空") }
                        "guide" -> ToolResult.text(ZxPersist.guide())
                        else -> ToolResult.text(ZxPersist.report())
                    }
                }
            }

            /* ── 渲染回执 ─────────────────────────────────────── */
            aiTool(name = "zx_feedback",
                description = "渲染回执：把「这张卡实际渲染成什么样」回写给模型，含未使用字段报告。op=digest/problems/card/stats/clear/guide。"
            ) {
                param("op", ParamType.STRING, "digest / problems / card / stats / clear / guide", required = true)
                param("card", ParamType.STRING, "card 时的卡片 ID")
                param("limit", ParamType.NUMBER, "digest 条数，默认 8")
                execute { args ->
                    when (args.string("op")) {
                        "problems" -> ToolResult.text(ZxFeedback.problems())
                        "card" -> ToolResult.text(ZxFeedback.of(args.string("card")))
                        "stats" -> ToolResult.text(ZxFeedback.stats())
                        "clear" -> { ZxFeedback.clear(); ToolResult.text("已清空回执") }
                        "guide" -> ToolResult.text(ZxFeedback.guide())
                        else -> ToolResult.text(ZxFeedback.digest(
                            args.string("limit").toIntOrNull() ?: 8))
                    }
                }
            }

            /* ── 快照回归测试 ─────────────────────────────────── */
            aiTool(name = "zx_snapshot",
                description = "卡片快照回归测试：比结构指纹防 DSL 改动引起渲染回归。op=save/diff/check/report/clear/guide。"
            ) {
                param("op", ParamType.STRING, "save / diff / check / report / clear / guide", required = true)
                param("card", ParamType.STRING, "check 时的目标卡片 ID")
                execute { args ->
                    when (args.string("op")) {
                        "save" -> ToolResult.text("已存基线 ${ZxSnapshot.save()} 张卡。")
                        "diff" -> ToolResult.text(ZxSnapshot.report())
                        "check" -> {
                            val id = args.string("card")
                            if (id.isBlank()) return@execute ToolResult.text("check 需要 card 参数。")
                            // 演示：把该卡当前值原样写回，看是否引起预期外变化
                            ToolResult.text(ZxSnapshot.checkAfter(id) {
                                ZxRef.set(id, "_touched", System.currentTimeMillis().toString())
                            })
                        }
                        "clear" -> { ZxSnapshot.clear(); ToolResult.text("已清空基线") }
                        "guide" -> ToolResult.text(ZxSnapshot.guide())
                        else -> ToolResult.text(ZxSnapshot.report())
                    }
                }
            }

            /* ── 无障碍焦点顺序 ───────────────────────────────── */
            aiTool(name = "zx_focus",
                description = "无障碍焦点顺序声明：读屏遍历顺序可指定，不靠布局默认顺序。op=declare/check/report/next/guide。"
            ) {
                param("op", ParamType.STRING, "declare / check / report / next / guide", required = true)
                param("card", ParamType.STRING, "卡片 ID")
                param("dsl", ParamType.STRING, "declare 时的 DSL（需含 _focus 数组）")
                param("from", ParamType.STRING, "next 时的当前节点 id")
                execute { args ->
                    when (args.string("op")) {
                        "declare" -> {
                            val r = ZxFocus.declareFromDsl(args.string("card"), args.string("dsl"))
                            ToolResult.text(if (r.ok) r.reason else "声明失败：${r.reason}")
                        }
                        "report" -> ToolResult.text(ZxFocus.report(args.string("card")))
                        "next" -> {
                            val n = ZxFocus.next(args.string("card"), args.string("from"))
                            ToolResult.text(if (n == null) "没有声明焦点顺序。"
                                           else "下一个：${n.id} — 「${n.label}」")
                        }
                        "guide" -> ToolResult.text(ZxFocus.guide())
                        else -> ToolResult.text(ZxFocus.guide())
                    }
                }
            }

            /* ── 多卡布局编排 ─────────────────────────────────── */
            aiTool(name = "zx_layout",
                description = "多卡布局编排：给一组卡 ID 和意图，本地算容器/分栏/顺序。op=plan/preview/guide。"
            ) {
                param("op", ParamType.STRING, "plan / preview / guide", required = true)
                param("cards", ParamType.STRING, "卡片 ID，逗号分隔")
                param("intent", ParamType.STRING, "compare / flow / rank / detail")
                param("widthDp", ParamType.NUMBER, "可用宽度，默认 360")
                param("title", ParamType.STRING, "容器标题")
                execute { args ->
                    if (args.string("op") == "guide") return@execute ToolResult.text(ZxLayout.guide())
                    val ids = args.string("cards").split(",", "，")
                        .map { it.trim() }.filter { it.isNotBlank() }
                    if (ids.isEmpty())
                        return@execute ToolResult.text("先用 cards 给出要一起看的卡片 ID。")
                    val missing = ids.filter { ZxRef.get(it) == null }
                    if (missing.isNotEmpty())
                        return@execute ToolResult.text("这些卡片不在当前会话：${missing.joinToString()}")
                    val p = ZxLayout.plan(ZxLayout.Request(
                        cardIds = ids,
                        intent = args.string("intent").ifBlank { "compare" },
                        widthDp = args.string("widthDp").toIntOrNull() ?: 360,
                        title = args.string("title")))
                    ToolResult.text(p.summary() + "\n\nDSL:\n" + p.dsl)
                }
            }

            /* ── 时间旅行调试 ─────────────────────────────────── */
            aiTool(name = "zx_replay",
                description = "意图级重放调试：查看退回/重做历史。op=status/redo/undoRedo/demo/report/guide。"
            ) {
                param("op", ParamType.STRING, "status / redo / undoRedo / demo / report / guide", required = true)
                param("card", ParamType.STRING, "card/since 时的卡片 ID")
                param("limit", ParamType.NUMBER, "timeline 显示条数，默认 40")
                execute { args ->
                    when (args.string("op")) {
                        "status" -> ToolResult.text(ZxReplay.format())
                        "redo" -> {
                            val h = ZxReplay.redo()
                            ToolResult.text(if (h != null) "重做：→ #${h.markId} ${h.label}（前进 ${h.steps} 步）" else "没有可重做的退回。")
                        }
                        "undoRedo" -> {
                            val h = ZxReplay.undoRedo()
                            ToolResult.text(if (h != null) "撤销重做：← #${h.markId} ${h.label}（后退 ${h.steps} 步）" else "没有可撤销的重做。")
                        }
                        "demo" -> ToolResult.text(ZxReplay.demo())
                        "report" -> ToolResult.text(ZxReplay.report())
                        "guide" -> ToolResult.text(ZxReplay.guide())
                        else -> ToolResult.text(ZxReplay.format())
                    }
                }
            }

            /* ══════════════════════════════════════════════════
               十一、UI_SURFACE —— 气泡装不下时的独立界面
               ══════════════════════════════════════════════════ */

            uiComposeSurface("zx_canvas", "全屏画布", build = { ctx ->
                val req = ZxSurface.active()
                if (req == null) ZxSurface.empty()
                else ZxSurface.render(req, ctx)
            })

            uiComposeSurface("zx_sheet", "底部抽屉", build = { ctx ->
                val req = ZxSurface.active()
                if (req == null) ZxSurface.empty()
                else ZxSurface.render(req, ctx)
            })

            /* ══════════════════════════════════════════════════
               二、CHAT_CARD —— 12 实体组件 + 4 容器
               ══════════════════════════════════════════════════ */

            chatCard("zx_compare",  "多对象对比卡")   { d, c -> ZxRender.compare(d, c) }
            chatCard("zx_form",     "参数收集卡")     { d, c -> ZxRender.form(d, c) }
            chatCard("zx_timeline", "执行时间轴")     { d, c -> ZxRender.timeline(d, c) }
            chatCard("zx_choice",   "决策卡")         { d, c -> ZxRender.choice(d, c) }
            chatCard("zx_table",    "可编辑表格")     { d, c -> ZxRender2.table(d, c) }
            chatCard("zx_chart",    "Canvas 自绘图")  { d, c -> ZxRender2.chart(d, c) }
            chatCard("zx_kanban",   "看板")           { d, c -> ZxRender2.kanban(d, c) }
            chatCard("zx_diff",     "代码差异卡")     { d, c -> ZxRender2.diff(d, c) }
            chatCard("zx_terminal", "终端输出卡")     { d, c -> ZxRender2.terminal(d, c) }
            chatCard("zx_approval", "授权审批卡")     { d, c -> ZxRender2.approval(d, c) }
            chatCard("zx_media",    "内联媒体卡")     { d, c -> ZxRender2.media(d, c) }
            chatCard("zx_stepper",  "分步向导")       { d, c -> ZxRender2.stepper(d, c) }

            // 第三批 12 个
            chatCard("zx_metrics",  "指标卡组")       { d, c -> ZxRender3.metrics(d, c) }
            chatCard("zx_gantt",    "甘特图")         { d, c -> ZxRender3.gantt(d, c) }
            chatCard("zx_tree",     "文件树")         { d, c -> ZxRender3.tree(d, c) }
            chatCard("zx_flow",     "流程图")         { d, c -> ZxRender3.flow(d, c) }
            chatCard("zx_calendar", "日历")           { d, c -> ZxRender3.calendar(d, c) }
            chatCard("zx_rating",   "评分卡")         { d, c -> ZxRender3.rating(d, c) }
            chatCard("zx_editor",   "代码编辑器")     { d, c -> ZxRender3.editor(d, c) }
            chatCard("zx_audio",    "音频波形")       { d, c -> ZxRender3.audio(d, c) }
            chatCard("zx_heatmap",  "热力图")         { d, c -> ZxRender3.heatmap(d, c) }
            chatCard("zx_toggle",   "开关列表")       { d, c -> ZxRender3.toggle(d, c) }
            chatCard("zx_qr",       "二维码")         { d, c -> ZxRender3.qr(d, c) }
            chatCard("zx_stack",    "卡片堆栈")       { d, c -> ZxRender3.stack(d, c) }

            // 第三批容器 3 个
            chatCard("zx_grid",     "等宽网格")       { d, c -> ZxRender3x.Grid(d, c) }
            chatCard("zx_master",   "主从布局")       { d, c -> ZxRender3x.Master(d, c) }
            chatCard("zx_stagger",  "瀑布流")         { d, c -> ZxRender3x.Stagger(d, c) }

            // 第四批 12 个（都带连续手势）
            chatCard("zx_radar",      "雷达图")       { d, c -> ZxRender4.radar(d, c) }
            chatCard("zx_rank",       "排行榜")       { d, c -> ZxRender4.rank(d, c) }
            chatCard("zx_org",        "组织架构")     { d, c -> ZxRender4.org(d, c) }
            chatCard("zx_sankey",     "桑基图")       { d, c -> ZxRender4.sankey(d, c) }
            chatCard("zx_gauge",      "仪表盘")       { d, c -> ZxRender4.gauge(d, c) }
            chatCard("zx_range",      "范围滑块")     { d, c -> ZxRender4.range(d, c) }
            chatCard("zx_color",      "取色器")       { d, c -> ZxRender4.color(d, c) }
            chatCard("zx_breadcrumb", "面包屑")       { d, c -> ZxRender4.breadcrumb(d, c) }
            chatCard("zx_pager",      "分页器")       { d, c -> ZxRender4.pager(d, c) }
            chatCard("zx_countdown",  "倒计时")       { d, c -> ZxRender4.countdown(d, c) }
            chatCard("zx_sign",       "签名板")       { d, c -> ZxRender4.sign(d, c) }
            chatCard("zx_board",      "自由画布")     { d, c -> ZxRender4.board(d, c) }

            // 第五批 12 个（输入型：用户往里填东西）
            chatCard("zx_poll",        "投票问卷")     { d, c -> ZxRender5.poll(d, c) }
            chatCard("zx_transfer",    "穿梭框")       { d, c -> ZxRender5.transfer(d, c) }
            chatCard("zx_chips",       "标签多选")     { d, c -> ZxRender5.chips(d, c) }
            chatCard("zx_pin",         "口令输入")     { d, c -> ZxRender5.pin(d, c) }
            chatCard("zx_dial",        "旋钮")         { d, c -> ZxRender5.dial(d, c) }
            chatCard("zx_joystick",    "二维摇杆")     { d, c -> ZxRender5.joystick(d, c) }
            chatCard("zx_timepicker",  "时间选择")     { d, c -> ZxRender5.timepicker(d, c) }
            chatCard("zx_slidergroup", "多滑块组")     { d, c -> ZxRender5.slidergroup(d, c) }
            chatCard("zx_sortable",    "拖拽排序")     { d, c -> ZxRender5.sortable(d, c) }
            chatCard("zx_cardflip",    "翻转卡")       { d, c -> ZxRender5.cardflip(d, c) }
            chatCard("zx_comment",     "内联批注")     { d, c -> ZxRender5.comment(d, c) }
            chatCard("zx_seek",        "进度标记条")   { d, c -> ZxRender5.seek(d, c) }

            // 第六批 12 个（数据密集 + 直接操纵）
            chatCard("zx_wordcloud", "词云")         { d, c -> ZxRender6.wordcloud(d, c) }
            chatCard("zx_funnel",    "漏斗图")       { d, c -> ZxRender6.funnel(d, c) }
            chatCard("zx_treemap",   "树图")         { d, c -> ZxRender6.treemap(d, c) }
            chatCard("zx_chord",     "和弦图")       { d, c -> ZxRender6.chord(d, c) }
            chatCard("zx_sparkline", "迷你趋势")     { d, c -> ZxRender6.sparkline(d, c) }
            chatCard("zx_kanban",    "看板")         { d, c -> ZxRender6.kanban(d, c) }
            chatCard("zx_hotspot",   "图片热点")     { d, c -> ZxRender6.hotspot(d, c) }
            chatCard("zx_stepper",   "步进器")       { d, c -> ZxRender6.stepper(d, c) }
            chatCard("zx_segmented", "分段控件")     { d, c -> ZxRender6.segmented(d, c) }
            chatCard("zx_mention",   "@提及输入")    { d, c -> ZxRender6.mention(d, c) }
            chatCard("zx_dropzone",  "拖放区")       { d, c -> ZxRender6.dropzone(d, c) }
            chatCard("zx_resizable", "可拖拽分栏")   { d, c -> ZxRender6.resizable(d, c) }

            // 第七批 12 个（引导 / 识别 / 编排）
            chatCard("zx_tour",         "分步引导")   { d, c -> ZxRender7.tour(d, c) }
            chatCard("zx_swipe",        "滑动操作")   { d, c -> ZxRender7.swipe(d, c) }
            chatCard("zx_matrix",       "交叉表")     { d, c -> ZxRender7.matrix(d, c) }
            chatCard("zx_autocomplete", "自动完成")   { d, c -> ZxRender7.autocomplete(d, c) }
            chatCard("zx_spin",         "转盘")       { d, c -> ZxRender7.spin(d, c) }
            chatCard("zx_barcode",      "条形码")     { d, c -> ZxRender7.barcode(d, c) }
            chatCard("zx_dualist",      "双列差异")   { d, c -> ZxRender7.dualist(d, c) }
            chatCard("zx_scrubber",     "数值微调")   { d, c -> ZxRender7.scrubber(d, c) }
            chatCard("zx_bento",        "Bento 网格") { d, c -> ZxRender7.bento(d, c) }
            chatCard("zx_minmap",       "缩略导航")   { d, c -> ZxRender7.minmap(d, c) }
            chatCard("zx_statelist",    "状态流")     { d, c -> ZxRender7.statelist(d, c) }
            chatCard("zx_legend",       "图例选择")   { d, c -> ZxRender7.legend(d, c) }

            // 第八批 12 个（表单原语 / 状态呈现 / 集合展示）
            chatCard("zx_radiogroup",  "单选组")     { d, c -> ZxRender8.radiogroup(d, c) }
            chatCard("zx_checklist",   "清单勾选")   { d, c -> ZxRender8.checklist(d, c) }
            chatCard("zx_avatargroup", "头像组")     { d, c -> ZxRender8.avatargroup(d, c) }
            chatCard("zx_meter",       "分段计量条") { d, c -> ZxRender8.meter(d, c) }
            chatCard("zx_ring",        "环形进度")   { d, c -> ZxRender8.ring(d, c) }
            chatCard("zx_stat",        "统计数字")   { d, c -> ZxRender8.stat(d, c) }
            chatCard("zx_swatch",      "色板选择")   { d, c -> ZxRender8.swatch(d, c) }
            chatCard("zx_notice",      "通知条")     { d, c -> ZxRender8.notice(d, c) }
            chatCard("zx_price",       "定价卡")     { d, c -> ZxRender8.price(d, c) }
            chatCard("zx_keyvalue",    "键值列表")   { d, c -> ZxRender8.keyvalue(d, c) }
            chatCard("zx_inputmask",   "格式化输入") { d, c -> ZxRender8.inputmask(d, c) }
            chatCard("zx_datalist",    "可编辑列表") { d, c -> ZxRender8.datalist(d, c) }

            // 第九批 12 个（复合图表 / 结构图 / 复合容器）
            chatCard("zx_waterfall",   "瀑布图")     { d, c -> ZxRender9.waterfall(d, c) }
            chatCard("zx_pyramid",     "金字塔图")   { d, c -> ZxRender9.pyramid(d, c) }
            chatCard("zx_bubble",      "气泡图")     { d, c -> ZxRender9.bubble(d, c) }
            chatCard("zx_venn",        "韦恩图")     { d, c -> ZxRender9.venn(d, c) }
            chatCard("zx_candle",      "K线图")      { d, c -> ZxRender9.candle(d, c) }
            chatCard("zx_dag",         "依赖有向图") { d, c -> ZxRender9.dag(d, c) }
            chatCard("zx_swimlane",    "泳道图")     { d, c -> ZxRender9.swimlane(d, c) }
            chatCard("zx_mindmap",     "思维导图")   { d, c -> ZxRender9.mindmap(d, c) }
            chatCard("zx_gallery",     "图片画廊")   { d, c -> ZxRender9.gallery(d, c) }
            chatCard("zx_wizard",      "分步表单")   { d, c -> ZxRender9.wizard(d, c) }
            chatCard("zx_spreadsheet", "可编辑网格") { d, c -> ZxRender9.spreadsheet(d, c) }
            chatCard("zx_conversation","模拟对话")   { d, c -> ZxRender9.conversation(d, c) }

            // 第十批 11 个（高信息密度 / 专用输入 / 装饰性呈现）
            chatCard("zx_calheat",    "日历热力")   { d, c -> ZxRender10.calheat(d, c) }
            chatCard("zx_matrixheat", "矩阵热力")   { d, c -> ZxRender10.matrixheat(d, c) }
            chatCard("zx_polar",      "极坐标图")   { d, c -> ZxRender10.polar(d, c) }
            chatCard("zx_voice",      "语音输入条") { d, c -> ZxRender10.voice(d, c) }
            chatCard("zx_stars",      "评分星级")   { d, c -> ZxRender10.stars(d, c) }
            chatCard("zx_react",      "表情反馈")   { d, c -> ZxRender10.react(d, c) }
            chatCard("zx_scale",      "颜色刻度")   { d, c -> ZxRender10.scale(d, c) }
            chatCard("zx_tags",       "标签输入")   { d, c -> ZxRender10.tags(d, c) }
            chatCard("zx_flipnum",    "翻页数字")   { d, c -> ZxRender10.flipnum(d, c) }
            chatCard("zx_skeleton",   "加载骨架")   { d, c -> ZxRender10.skeleton(d, c) }
            chatCard("zx_badges",     "成就徽章")   { d, c -> ZxRender10.badges(d, c) }

            // 第十一批 12 个（富内容 / 空间布局 / 系统级界面）
            chatCard("zx_rich",     "富文本")     { d, c -> ZxRender11.rich(d, c) }
            chatCard("zx_quote",    "引用块")     { d, c -> ZxRender11.quote(d, c) }
            chatCard("zx_filecard", "文件卡片")   { d, c -> ZxRender11.filecard(d, c) }
            chatCard("zx_callout",  "提示块")     { d, c -> ZxRender11.callout(d, c) }
            chatCard("zx_hint",     "提示气泡")   { d, c -> ZxRender11.hint(d, c) }
            chatCard("zx_seat",     "座位图")     { d, c -> ZxRender11.seat(d, c) }
            chatCard("zx_floor",    "平面图")     { d, c -> ZxRender11.floor(d, c) }
            chatCard("zx_banner",   "横幅")       { d, c -> ZxRender11.banner(d, c) }
            chatCard("zx_divider",  "分隔线")     { d, c -> ZxRender11.divider(d, c) }
            chatCard("zx_settings", "设置面板")   { d, c -> ZxRender11.settings(d, c) }
            chatCard("zx_command",  "命令面板")   { d, c -> ZxRender11.command(d, c) }
            chatCard("zx_empty",    "空状态")     { d, c -> ZxRender11.empty(d, c) }

            // 第十二批 12 个（组织 / 流向 / 导航 / 采集）
            chatCard("zx_orgchart",  "组织架构")   { d, c -> ZxRender12.orgchart(d, c) }
            chatCard("zx_sankey",    "桑基图")     { d, c -> ZxRender12.sankey(d, c) }
            chatCard("zx_gauge",     "仪表盘")     { d, c -> ZxRender12.gauge(d, c) }
            chatCard("zx_sparkgrid", "迷你图网格") { d, c -> ZxRender12.sparkgrid(d, c) }
            chatCard("zx_breadcrumb","面包屑")     { d, c -> ZxRender12.breadcrumb(d, c) }
            chatCard("zx_pagination","分页器")     { d, c -> ZxRender12.pagination(d, c) }
            chatCard("zx_tabbar",    "底部标签栏") { d, c -> ZxRender12.tabbar(d, c) }
            chatCard("zx_searchbar", "搜索栏")     { d, c -> ZxRender12.searchbar(d, c) }
            chatCard("zx_filterchips",     "筛选芯片组") { d, c -> ZxRender12.chips(d, c) }
            chatCard("zx_colorpick", "取色板")     { d, c -> ZxRender12.colorpick(d, c) }
            chatCard("zx_stepper",   "数字步进器") { d, c -> ZxRender12.stepper(d, c) }
            chatCard("zx_treeview",  "层级树")     { d, c -> ZxRender12.treeview(d, c) }

            // 第十三批 12 个（对照 / 诊断 / 协同）
            chatCard("zx_diffview", "并排差异")     { d, c -> ZxRender13.diffview(d, c) }
            chatCard("zx_baseline", "基线对比")     { d, c -> ZxRender13.baseline(d, c) }
            chatCard("zx_logview",  "日志流")       { d, c -> ZxRender13.logview(d, c) }
            chatCard("zx_console",  "交互式控制台") { d, c -> ZxRender13.console(d, c) }
            chatCard("zx_depgraph", "依赖关系图")   { d, c -> ZxRender13.depgraph(d, c) }
            chatCard("zx_datamap",  "数据地图")     { d, c -> ZxRender13.datamap(d, c) }
            chatCard("zx_conflict", "冲突解决卡")   { d, c -> ZxRender13.conflict(d, c) }
            chatCard("zx_activity", "活动流")       { d, c -> ZxRender13.activity(d, c) }
            chatCard("zx_timerange","时间区间")     { d, c -> ZxRender13.timerange(d, c) }
            chatCard("zx_gridform", "栅格表单")     { d, c -> ZxRender13.gridform(d, c) }
            chatCard("zx_scorecard","评分卡")       { d, c -> ZxRender13.scorecard(d, c) }
            chatCard("zx_waveedit", "波形编辑")     { d, c -> ZxRender13.waveedit(d, c) }

            // 第十四批 12 个（分布 / 富媒体 / 产出）
            chatCard("zx_histogram", "直方图")     { d, c -> ZxRender14.histogram(d, c) }
            chatCard("zx_boxplot",   "箱线图")     { d, c -> ZxRender14.boxplot(d, c) }
            chatCard("zx_pareto",    "帕累托图")   { d, c -> ZxRender14.pareto(d, c) }
            chatCard("zx_control",   "控制图")     { d, c -> ZxRender14.control(d, c) }
            chatCard("zx_sunburst",  "旭日图")     { d, c -> ZxRender14.sunburst(d, c) }
            chatCard("zx_video",     "视频播放器") { d, c -> ZxRender14.video(d, c) }
            chatCard("zx_docview",   "文档预览")   { d, c -> ZxRender14.docview(d, c) }
            chatCard("zx_model3d",   "模型查看器") { d, c -> ZxRender14.model3d(d, c) }
            chatCard("zx_schedule",  "排班表")     { d, c -> ZxRender14.schedule(d, c) }
            chatCard("zx_nps",       "NPS 条")     { d, c -> ZxRender14.nps(d, c) }
            chatCard("zx_export",    "导出卡")     { d, c -> ZxRender14.export(d, c) }
            chatCard("zx_bargauge",  "条形仪表")   { d, c -> ZxRender14.bargauge(d, c) }

            // 第十五批 12 个（关系 / 文本 / 小众图表）
            chatCard("zx_route",     "路径图")     { d, c -> ZxRender15.route(d, c) }
            chatCard("zx_network",   "关系网络")   { d, c -> ZxRender15.network(d, c) }
            chatCard("zx_annotate",  "文本批注")   { d, c -> ZxRender15.annotate(d, c) }
            chatCard("zx_revision",  "修订标记")   { d, c -> ZxRender15.revision(d, c) }
            chatCard("zx_bullet",    "子弹图")     { d, c -> ZxRender15.bullet(d, c) }
            chatCard("zx_icicle",    "冰柱图")     { d, c -> ZxRender15.icicle(d, c) }
            chatCard("zx_cohort",    "队列留存")   { d, c -> ZxRender15.cohort(d, c) }
            chatCard("zx_slope",     "斜率图")     { d, c -> ZxRender15.slope(d, c) }
            chatCard("zx_dumbbell",  "哑铃图")     { d, c -> ZxRender15.dumbbell(d, c) }
            chatCard("zx_waffle",    "华夫饼")     { d, c -> ZxRender15.waffle(d, c) }
            chatCard("zx_ridgeline", "山脊线")     { d, c -> ZxRender15.ridgeline(d, c) }
            chatCard("zx_steparea",  "阶梯面积")   { d, c -> ZxRender15.steparea(d, c) }

            // 第十六批 12 个（时间 / 对比 / 状态机）
            chatCard("zx_weekgrid",    "周网格")     { d, c -> ZxRender16.weekgrid(d, c) }
            chatCard("zx_pulse",       "脉冲图")     { d, c -> ZxRender16.pulse(d, c) }
            chatCard("zx_scoreboard",  "打分组")     { d, c -> ZxRender16.scoreboard(d, c) }
            chatCard("zx_abtest",      "A/B 对比")   { d, c -> ZxRender16.abtest(d, c) }
            chatCard("zx_statemachine","状态机")     { d, c -> ZxRender16.statemachine(d, c) }
            chatCard("zx_checkflow",   "检查清单")   { d, c -> ZxRender16.checkflow(d, c) }
            chatCard("zx_branch",      "分支对比图") { d, c -> ZxRender16.branch(d, c) }
            chatCard("zx_sla",         "SLA 追踪")   { d, c -> ZxRender16.sla(d, c) }
            chatCard("zx_repost",      "转发卡")     { d, c -> ZxRender16.repost(d, c) }
            chatCard("zx_thread",      "话题串")     { d, c -> ZxRender16.thread(d, c) }
            chatCard("zx_splitflap",   "翻页牌")     { d, c -> ZxRender16.splitflap(d, c) }
            chatCard("zx_runbook",     "操作手册")   { d, c -> ZxRender16.runbook(d, c) }

            // 第十七批 12 个（空间 / 符号 / 动效）
            chatCard("zx_geo",       "地理点阵")   { d, c -> ZxRender17.geo(d, c) }
            chatCard("zx_formula",   "公式")       { d, c -> ZxRender17.formula(d, c) }
            chatCard("zx_tokens",    "token 流")   { d, c -> ZxRender17.tokens(d, c) }
            chatCard("zx_ast",       "语法树")     { d, c -> ZxRender17.ast(d, c) }
            chatCard("zx_hexgrid",   "六边形网格") { d, c -> ZxRender17.hexgrid(d, c) }
            chatCard("zx_spiral",    "螺旋时间轴") { d, c -> ZxRender17.spiral(d, c) }
            chatCard("zx_marquee",   "跑马灯")     { d, c -> ZxRender17.marquee(d, c) }
            chatCard("zx_odometer",  "数字滚动")   { d, c -> ZxRender17.odometer(d, c) }
            chatCard("zx_radial",    "径向菜单")   { d, c -> ZxRender17.radial(d, c) }
            chatCard("zx_isometric", "等距视图")   { d, c -> ZxRender17.isometric(d, c) }
            chatCard("zx_spectrum",  "频谱")       { d, c -> ZxRender17.spectrum(d, c) }
            chatCard("zx_bezier",    "曲线编辑器") { d, c -> ZxRender17.bezier(d, c) }

            // 第十八批 12 个（编辑 / 映射 / 度量）
            chatCard("zx_flowedit",  "流程图编辑器") { d, c -> ZxRender18.flowedit(d, c) }
            chatCard("zx_crop",      "裁剪框")       { d, c -> ZxRender18.crop(d, c) }
            chatCard("zx_mapping",   "字段映射")     { d, c -> ZxRender18.mapping(d, c) }
            chatCard("zx_ratio",     "比率条")       { d, c -> ZxRender18.ratio(d, c) }
            chatCard("zx_deviation", "偏差分布")     { d, c -> ZxRender18.deviation(d, c) }
            chatCard("zx_timeline2", "双轨时间轴")   { d, c -> ZxRender18.timeline2(d, c) }
            chatCard("zx_scale2",    "天平")         { d, c -> ZxRender18.scale2(d, c) }
            chatCard("zx_mirror",    "镜像对比")     { d, c -> ZxRender18.mirror(d, c) }
            chatCard("zx_dial2",     "刻度盘")       { d, c -> ZxRender18.dial2(d, c) }
            chatCard("zx_matrix2",   "矩阵选择")     { d, c -> ZxRender18.matrix2(d, c) }
            chatCard("zx_ladder",    "阶梯排序")     { d, c -> ZxRender18.ladder(d, c) }
            chatCard("zx_slot",      "时间段排布")   { d, c -> ZxRender18.slot(d, c) }

            // 第十九批 12 个（序列 / 结构 / 证据）
            chatCard("zx_traceback", "回溯路径")     { d, c -> ZxRender19.traceback(d, c) }
            chatCard("zx_seq",       "时序图")       { d, c -> ZxRender19.seq(d, c) }
            chatCard("zx_explode",   "爆炸视图")     { d, c -> ZxRender19.explode(d, c) }
            chatCard("zx_critical",  "关键路径")     { d, c -> ZxRender19.critical(d, c) }
            chatCard("zx_evidence",  "证据链")       { d, c -> ZxRender19.evidence(d, c) }
            chatCard("zx_confront",  "证据对质")     { d, c -> ZxRender19.confront(d, c) }
            chatCard("zx_matrix3",   "影响矩阵")     { d, c -> ZxRender19.matrix3(d, c) }
            chatCard("zx_receipt",   "回执凭据")     { d, c -> ZxRender19.receipt(d, c) }
            chatCard("zx_ledger",    "流水账")       { d, c -> ZxRender19.ledger(d, c) }
            chatCard("zx_gauntlet",  "多轮筛选")     { d, c -> ZxRender19.gauntlet(d, c) }
            chatCard("zx_chain",     "依赖链")       { d, c -> ZxRender19.chain(d, c) }
            chatCard("zx_bracket",   "淘汰对阵")     { d, c -> ZxRender19.bracket(d, c) }

            // 第二十批 12 个（密度 / 分布 / 编码）
            chatCard("zx_contour",   "等高线")       { d, c -> ZxRender20.contour(d, c) }
            chatCard("zx_stream",    "流图")         { d, c -> ZxRender20.stream(d, c) }
            chatCard("zx_horizon",   "地平线图")     { d, c -> ZxRender20.horizon(d, c) }
            chatCard("zx_parallel",  "平行坐标")     { d, c -> ZxRender20.parallel(d, c) }
            chatCard("zx_beeswarm",  "蜂群图")       { d, c -> ZxRender20.beeswarm(d, c) }
            chatCard("zx_violin",    "小提琴图")     { d, c -> ZxRender20.violin(d, c) }
            chatCard("zx_glyph",     "字形网格")     { d, c -> ZxRender20.glyph(d, c) }
            chatCard("zx_arc",       "弧形图")       { d, c -> ZxRender20.arc(d, c) }
            chatCard("zx_mosaic",    "马赛克图")     { d, c -> ZxRender20.mosaic(d, c) }
            chatCard("zx_trail",     "轨迹图")       { d, c -> ZxRender20.trail(d, c) }
            chatCard("zx_pictogram", "象形图")       { d, c -> ZxRender20.pictogram(d, c) }
            chatCard("zx_dotstrip",  "单位点图")     { d, c -> ZxRender20.dotstrip(d, c) }

            // 第二十一批 12 个（对齐 / 差异 / 编排）
            chatCard("zx_align",     "序列对齐")     { d, c -> ZxRender21.align(d, c) }
            chatCard("zx_jsondiff",  "结构化差异")   { d, c -> ZxRender21.jsondiff(d, c) }
            chatCard("zx_threeway",  "三方比对")     { d, c -> ZxRender21.threeway(d, c) }
            chatCard("zx_sankey2",   "能量桑基")     { d, c -> ZxRender21.sankey2(d, c) }
            chatCard("zx_split",     "可分割面板")   { d, c -> ZxRender21.split(d, c) }
            chatCard("zx_switch",    "条件容器")     { d, c -> ZxRender21.switch(d, c) }
            chatCard("zx_lane",      "泳道时间线")   { d, c -> ZxRender21.lane(d, c) }
            chatCard("zx_bullet2",   "子弹图组")     { d, c -> ZxRender21.bullet2(d, c) }
            chatCard("zx_ring2",     "同心环占比")   { d, c -> ZxRender21.ring2(d, c) }
            chatCard("zx_stepflow",  "分步流程")     { d, c -> ZxRender21.stepflow(d, c) }
            chatCard("zx_annotate",  "自由标注")     { d, c -> ZxRender21.annotate(d, c) }
            chatCard("zx_dial3",     "多表盘组")     { d, c -> ZxRender21.dial3(d, c) }

            // 第二十二批 12 个（追溯 / 关系强度 / 定位）
            chatCard("zx_cmdhist",   "命令历史")     { d, c -> ZxRender22.cmdhist(d, c) }
            chatCard("zx_callstack", "调用栈")       { d, c -> ZxRender22.callstack(d, c) }
            chatCard("zx_uptime",    "可用性条")     { d, c -> ZxRender22.uptime(d, c) }
            chatCard("zx_playback",  "回放控制器")   { d, c -> ZxRender22.playback(d, c) }
            chatCard("zx_pipe",      "流水线吞吐")   { d, c -> ZxRender22.pipe(d, c) }
            chatCard("zx_corr",      "相关性矩阵")   { d, c -> ZxRender22.corr(d, c) }
            chatCard("zx_radar2",    "多对象雷达")   { d, c -> ZxRender22.radar2(d, c) }
            chatCard("zx_choro",     "区域着色")     { d, c -> ZxRender22.choro(d, c) }
            chatCard("zx_toc",       "目录导航")     { d, c -> ZxRender22.toc(d, c) }
            chatCard("zx_glossary",  "术语表")       { d, c -> ZxRender22.glossary(d, c) }
            chatCard("zx_survey",    "问卷")         { d, c -> ZxRender22.survey(d, c) }
            chatCard("zx_punch",     "打点卡")       { d, c -> ZxRender22.punch(d, c) }

            // 第二十三批 12 个（推演 / 规则操纵）
            chatCard("zx_decision",  "决策树")       { d, c -> ZxRender23.decision(d, c) }
            chatCard("zx_tradeoff",  "权衡卡")       { d, c -> ZxRender23.tradeoff(d, c) }
            chatCard("zx_fallback",  "降级链")       { d, c -> ZxRender23.fallback(d, c) }
            chatCard("zx_retry",     "重试策略")     { d, c -> ZxRender23.retry(d, c) }
            chatCard("zx_brush",     "区间刷选")     { d, c -> ZxRender23.brush(d, c) }
            chatCard("zx_blast",     "影响波及")     { d, c -> ZxRender23.blast(d, c) }
            chatCard("zx_batch",     "批量操作")     { d, c -> ZxRender23.batch(d, c) }
            chatCard("zx_priority",  "优先级象限")   { d, c -> ZxRender23.priority(d, c) }
            chatCard("zx_probe",     "探针卡")       { d, c -> ZxRender23.probe(d, c) }
            chatCard("zx_inspect",   "字段检查器")   { d, c -> ZxRender23.inspect(d, c) }
            chatCard("zx_remap",     "值重映射")     { d, c -> ZxRender23.remap(d, c) }
            chatCard("zx_rollup",    "汇总树")       { d, c -> ZxRender23.rollup(d, c) }

            // 第二十四批 12 个（统计推断 / 一致性）
            chatCard("zx_qq",        "QQ 图")        { d, c -> ZxRender24.qq(d, c) }
            chatCard("zx_forest",    "森林图")       { d, c -> ZxRender24.forest(d, c) }
            chatCard("zx_bland",     "一致性")       { d, c -> ZxRender24.bland(d, c) }
            chatCard("zx_survival",  "生存曲线")     { d, c -> ZxRender24.survival(d, c) }
            chatCard("zx_ecdf",      "累积分布")     { d, c -> ZxRender24.ecdf(d, c) }
            chatCard("zx_lorenz",    "集中度")       { d, c -> ZxRender24.lorenz(d, c) }
            chatCard("zx_regress",   "回归")         { d, c -> ZxRender24.regress(d, c) }
            chatCard("zx_scatter",   "散点")         { d, c -> ZxRender24.scatter(d, c) }
            chatCard("zx_seqdiff",   "序列对比")     { d, c -> ZxRender24.seqdiff(d, c) }
            chatCard("zx_cmdpal",    "命令面板")     { d, c -> ZxRender24.cmdpal(d, c) }
            chatCard("zx_span",      "区间标注")     { d, c -> ZxRender24.span(d, c) }
            chatCard("zx_tolerance", "公差带")       { d, c -> ZxRender24.tolerance(d, c) }

            // 第二十五批 12 个（稳健性 / 敏感性 / 效应量）
            chatCard("zx_decompose","序列分解")      { d, c -> ZxRender25.decompose(d, c) }
            chatCard("zx_boot",     "Bootstrap")     { d, c -> ZxRender25.boot(d, c) }
            chatCard("zx_perm",     "置换检验")      { d, c -> ZxRender25.perm(d, c) }
            chatCard("zx_test",     "假设检验")      { d, c -> ZxRender25.test(d, c) }
            chatCard("zx_roc",      "ROC")           { d, c -> ZxRender25.roc(d, c) }
            chatCard("zx_lift",     "提升图")        { d, c -> ZxRender25.lift(d, c) }
            chatCard("zx_pscan",    "参数扫描")      { d, c -> ZxRender25.pscan(d, c) }
            chatCard("zx_vardec",   "方差分解")      { d, c -> ZxRender25.vardec(d, c) }
            chatCard("zx_mds",      "多维尺度")      { d, c -> ZxRender25.mds(d, c) }
            chatCard("zx_geoflow",  "地理流")        { d, c -> ZxRender25.geoflow(d, c) }
            chatCard("zx_violincmp","分布对比")      { d, c -> ZxRender25.violincmp(d, c) }
            chatCard("zx_survcmp",  "生存对比")      { d, c -> ZxRender25.survcmp(d, c) }

            // 第二十六批 12 个（因果与可信度）
            chatCard("zx_causal",   "因果图")        { d, c -> ZxRender26.causal(d, c) }
            chatCard("zx_did",      "双重差分")      { d, c -> ZxRender26.did(d, c) }
            chatCard("zx_rdd",      "断点回归")      { d, c -> ZxRender26.rdd(d, c) }
            chatCard("zx_iv",       "工具变量")      { d, c -> ZxRender26.iv(d, c) }
            chatCard("zx_sense",    "敏感性分析")    { d, c -> ZxRender26.sense(d, c) }
            chatCard("zx_psm",      "倾向匹配")      { d, c -> ZxRender26.psm(d, c) }
            chatCard("zx_balance",  "协变量平衡")    { d, c -> ZxRender26.balance(d, c) }
            chatCard("zx_missing",  "缺失模式")      { d, c -> ZxRender26.missing(d, c) }
            chatCard("zx_seqtest",  "序贯检验")      { d, c -> ZxRender26.seqtest(d, c) }
            chatCard("zx_bayes",    "贝叶斯更新")    { d, c -> ZxRender26.bayes(d, c) }
            chatCard("zx_anomaly",  "异常检测")      { d, c -> ZxRender26.anomaly(d, c) }
            chatCard("zx_power",    "功效分析")      { d, c -> ZxRender26.power(d, c) }

            // 第二十七批 12 个（预测与决策）
            chatCard("zx_forecast",   "预测")        { d, c -> ZxRender27.forecast(d, c) }
            chatCard("zx_calib",      "校准曲线")    { d, c -> ZxRender27.calib(d, c) }
            chatCard("zx_optline",    "最优停止")    { d, c -> ZxRender27.optline(d, c) }
            chatCard("zx_dmatrix",   "决策矩阵")    { d, c -> ZxRender27.decision(d, c) }
            chatCard("zx_riskreturn", "风险收益")    { d, c -> ZxRender27.riskreturn(d, c) }
            chatCard("zx_utility",    "效用曲线")    { d, c -> ZxRender27.utility(d, c) }
            chatCard("zx_bandit",     "多臂老虎机")  { d, c -> ZxRender27.bandit(d, c) }
            chatCard("zx_explain",    "特征归因")    { d, c -> ZxRender27.explain(d, c) }
            chatCard("zx_cfactual",   "反事实")      { d, c -> ZxRender27.cfactual(d, c) }
            chatCard("zx_cforest",    "异质效应")    { d, c -> ZxRender27.cforest(d, c) }
            chatCard("zx_alloc",      "序贯分配")    { d, c -> ZxRender27.alloc(d, c) }
            chatCard("zx_abmon",      "分流监控")    { d, c -> ZxRender27.abmon(d, c) }

            // 第二十八批 12 个（行动之后）
            chatCard("zx_rollout",     "灰度发布")    { d, c -> ZxRender28.rollout(d, c) }
            chatCard("zx_canary",      "金丝雀对比")  { d, c -> ZxRender28.canary(d, c) }
            chatCard("zx_slo",         "错误预算")    { d, c -> ZxRender28.slo(d, c) }
            chatCard("zx_healthcheck", "健康检查")    { d, c -> ZxRender28.healthcheck(d, c) }
            chatCard("zx_incident",    "事故时间线")  { d, c -> ZxRender28.incident(d, c) }
            chatCard("zx_rca",         "根因溯源")    { d, c -> ZxRender28.rca(d, c) }
            chatCard("zx_regret",      "遗憾分析")    { d, c -> ZxRender28.regret(d, c) }
            chatCard("zx_backtest",    "回测")        { d, c -> ZxRender28.backtest(d, c) }
            chatCard("zx_drift",       "数据漂移")    { d, c -> ZxRender28.drift(d, c) }
            chatCard("zx_alert",       "告警")        { d, c -> ZxRender28.alert(d, c) }
            chatCard("zx_costtrack",   "成本追踪")    { d, c -> ZxRender28.costtrack(d, c) }
            chatCard("zx_feedback",    "结果回收")    { d, c -> ZxRender28.feedback(d, c) }

            // 第二十九批 12 个（知识与判断的留存）
            chatCard("zx_capacity",    "容量规划")    { d, c -> ZxRender29.capacity(d, c) }
            chatCard("zx_queue",       "排队")        { d, c -> ZxRender29.queue(d, c) }
            chatCard("zx_loadtest",    "负载测试")    { d, c -> ZxRender29.loadtest(d, c) }
            chatCard("zx_topology",    "服务拓扑")    { d, c -> ZxRender29.topology(d, c) }
            chatCard("zx_changerisk",  "变更风险")    { d, c -> ZxRender29.changerisk(d, c) }
            chatCard("zx_playbook",    "处置剧本")    { d, c -> ZxRender29.playbook(d, c) }
            chatCard("zx_escalation",  "升级路径")    { d, c -> ZxRender29.escalation(d, c) }
            chatCard("zx_postmortem",  "复盘")        { d, c -> ZxRender29.postmortem(d, c) }
            chatCard("zx_knowledge",   "知识沉淀")    { d, c -> ZxRender29.knowledge(d, c) }
            chatCard("zx_decisionlog", "决策日志")    { d, c -> ZxRender29.decisionlog(d, c) }
            chatCard("zx_debt",        "技术债")      { d, c -> ZxRender29.debt(d, c) }
            chatCard("zx_checkpoint",  "检查点")      { d, c -> ZxRender29.checkpoint(d, c) }

            // 第三十批 12 个（组织与治理）
            chatCard("zx_unitecon",    "单客经济")    { d, c -> ZxRender30.unitecon(d, c) }
            chatCard("zx_costattrib",  "成本归因")    { d, c -> ZxRender30.costattrib(d, c) }
            chatCard("zx_lineage",     "数据血缘")    { d, c -> ZxRender30.lineage(d, c) }
            chatCard("zx_impact",      "影响分析")    { d, c -> ZxRender30.impact(d, c) }
            chatCard("zx_dataclass",   "数据分级")    { d, c -> ZxRender30.dataclass(d, c) }
            chatCard("zx_retention",   "保留策略")    { d, c -> ZxRender30.retention(d, c) }
            chatCard("zx_workload",    "团队负载")    { d, c -> ZxRender30.workload(d, c) }
            chatCard("zx_bottleneck",  "瓶颈识别")    { d, c -> ZxRender30.bottleneck(d, c) }
            chatCard("zx_expresult",   "实验结果")    { d, c -> ZxRender30.expresult(d, c) }
            chatCard("zx_compliance",  "合规检查")    { d, c -> ZxRender30.compliance(d, c) }
            chatCard("zx_audittrail",  "审计追踪")    { d, c -> ZxRender30.audittrail(d, c) }
            chatCard("zx_accessreview","权限评审")    { d, c -> ZxRender30.accessreview(d, c) }

            // 第三十一批 12 个（模拟与推演）
            chatCard("zx_scenario",    "情景推演")    { d, c -> ZxRender31.scenario(d, c) }
            chatCard("zx_montecarlo",  "蒙特卡洛")    { d, c -> ZxRender31.montecarlo(d, c) }
            chatCard("zx_stress",      "压力情景")    { d, c -> ZxRender31.stress(d, c) }
            chatCard("zx_tornado",     "龙卷风图")    { d, c -> ZxRender31.tornado(d, c) }
            chatCard("zx_whatif",      "假设推演")    { d, c -> ZxRender31.whatif(d, c) }
            chatCard("zx_breakeven",   "盈亏平衡")    { d, c -> ZxRender31.breakeven(d, c) }
            chatCard("zx_systemdyn",   "系统动力学")  { d, c -> ZxRender31.systemdyn(d, c) }
            chatCard("zx_markov",      "马尔可夫链")  { d, c -> ZxRender31.markov(d, c) }
            chatCard("zx_optpath",     "最优路径")    { d, c -> ZxRender31.optpath(d, c) }
            chatCard("zx_payoff",      "博弈矩阵")    { d, c -> ZxRender31.payoff(d, c) }
            chatCard("zx_reversesolve","反向求解")    { d, c -> ZxRender31.reversesolve(d, c) }
            chatCard("zx_realoption",  "实物期权")    { d, c -> ZxRender31.realoption(d, c) }

            // 第三十二批 12 个（议价、竞标与序贯决策）
            chatCard("zx_bargain",    "议价区间")    { d, c -> ZxRender32.bargain(d, c) }
            chatCard("zx_auction",    "竞标")        { d, c -> ZxRender32.auction(d, c) }
            chatCard("zx_multiissue", "多议题交换")  { d, c -> ZxRender32.multiissue(d, c) }
            chatCard("zx_pfront",     "Pareto 前沿") { d, c -> ZxRender32.pareto(d, c) }
            chatCard("zx_portfolio",  "组合优化")    { d, c -> ZxRender32.portfolio(d, c) }
            chatCard("zx_bidcurve",   "出价曲线")    { d, c -> ZxRender32.bidcurve(d, c) }
            chatCard("zx_sequential", "序贯决策")    { d, c -> ZxRender32.sequential(d, c) }
            chatCard("zx_stoprule",   "最优停止")    { d, c -> ZxRender32.stoprule(d, c) }
            chatCard("zx_hedge",      "对冲")        { d, c -> ZxRender32.hedge(d, c) }
            chatCard("zx_contingent", "应急计划")    { d, c -> ZxRender32.contingent(d, c) }
            chatCard("zx_escalate",   "升级决策")    { d, c -> ZxRender32.escalate(d, c) }
            chatCard("zx_commit",     "承诺可信度")  { d, c -> ZxRender32.commit(d, c) }

            // 第三十三批 15 个（消费、开发与娱乐）
            chatCard("zx_weather",   "天气")        { d, c -> ZxRender33.weather(d, c) }
            chatCard("zx_wxradar",   "降水雷达")    { d, c -> ZxRender33.wxradar(d, c) }
            chatCard("zx_aqi",       "空气质量")    { d, c -> ZxRender33.aqi(d, c) }
            chatCard("zx_news",      "新闻流")      { d, c -> ZxRender33.news(d, c) }
            chatCard("zx_headline",  "头条卡")      { d, c -> ZxRender33.headline(d, c) }
            chatCard("zx_feed",      "订阅流")      { d, c -> ZxRender33.feed(d, c) }
            chatCard("zx_code",      "代码块")      { d, c -> ZxRender33.code(d, c) }
            chatCard("zx_codetheme", "代码主题对比") { d, c -> ZxRender33.codetheme(d, c) }
            chatCard("zx_shell",     "Shell")       { d, c -> ZxRender33.shell(d, c) }
            chatCard("zx_tty",       "终端会话")    { d, c -> ZxRender33.tty(d, c) }
            chatCard("zx_toolcall",  "工具调用")    { d, c -> ZxRender33.toolcall(d, c) }
            chatCard("zx_toolchain", "工具调用链")  { d, c -> ZxRender33.toolchain(d, c) }
            chatCard("zx_toolargs",  "工具参数表单") { d, c -> ZxRender33.toolargs(d, c) }
            chatCard("zx_2048",      "2048")        { d, c -> ZxRender33.game2048(d, c) }
            chatCard("zx_tictactoe", "井字棋")      { d, c -> ZxRender33.tictactoe(d, c) }

            // 第三十四批 15 个（日常界面）
            chatCard("zx_mapnav",   "地图导航")    { d, c -> ZxRender34.mapnav(d, c) }
            chatCard("zx_transit",  "公交换乘")    { d, c -> ZxRender34.transit(d, c) }
            chatCard("zx_music",    "音乐播放器")  { d, c -> ZxRender34.music(d, c) }
            chatCard("zx_album",    "相册")        { d, c -> ZxRender34.album(d, c) }
            chatCard("zx_chatlog",  "聊天记录")    { d, c -> ZxRender34.chatlog(d, c) }
            chatCard("zx_contact",  "联系人")      { d, c -> ZxRender34.contact(d, c) }
            chatCard("zx_agenda",   "日程时间轴")  { d, c -> ZxRender34.agenda(d, c) }
            chatCard("zx_filemgr",  "文件管理器")  { d, c -> ZxRender34.filemgr(d, c) }
            chatCard("zx_setup",    "设置向导")    { d, c -> ZxRender34.setup(d, c) }
            chatCard("zx_shop",     "商品卡")      { d, c -> ZxRender34.shop(d, c) }
            chatCard("zx_order",    "订单物流")    { d, c -> ZxRender34.order(d, c) }
            chatCard("zx_wallet",   "钱包账单")    { d, c -> ZxRender34.wallet(d, c) }
            chatCard("zx_remote",   "遥控器")      { d, c -> ZxRender34.remote(d, c) }
            chatCard("zx_dialpad",  "拨号盘")      { d, c -> ZxRender34.dialpad(d, c) }
            chatCard("zx_tasklist", "任务清单")    { d, c -> ZxRender34.tasklist(d, c) }

            // 第三十五批 14 个（生活场景）
            chatCard("zx_fitness",      "健身记录")  { d, c -> ZxRender35.fitness(d, c) }
            chatCard("zx_medicine",     "用药记录")  { d, c -> ZxRender35.medicine(d, c) }
            chatCard("zx_parcel",       "快递轨迹")  { d, c -> ZxRender35.parcel(d, c) }
            chatCard("zx_ticket",       "购票选座")  { d, c -> ZxRender35.ticket(d, c) }
            chatCard("zx_study",        "学习卡片")  { d, c -> ZxRender35.study(d, c) }
            chatCard("zx_plant",        "植物养护")  { d, c -> ZxRender35.plant(d, c) }
            chatCard("zx_parking",      "停车找车")  { d, c -> ZxRender35.parking(d, c) }
            chatCard("zx_habit",        "习惯打卡")  { d, c -> ZxRender35.habit(d, c) }
            chatCard("zx_subscription", "订阅管理")  { d, c -> ZxRender35.subscription(d, c) }
            chatCard("zx_pet",          "宠物照护")  { d, c -> ZxRender35.pet(d, c) }
            chatCard("zx_utilitymeter", "水电表")    { d, c -> ZxRender35.utilitymeter(d, c) }
            chatCard("zx_sleep",        "睡眠记录")  { d, c -> ZxRender35.sleep(d, c) }
            chatCard("zx_coupon",       "优惠券")    { d, c -> ZxRender35.coupon(d, c) }
            chatCard("zx_appointment",  "预约挂号")  { d, c -> ZxRender35.appointment(d, c) }

            // 第三十六批 15 个（生活操作）
            chatCard("zx_recipe",     "菜谱步骤")  { d, c -> ZxRender36.recipe(d, c) }
            chatCard("zx_bookkeep",     "记账流水")  { d, c -> ZxRender36.ledger(d, c) }
            chatCard("zx_commute",    "通勤路况")  { d, c -> ZxRender36.commute(d, c) }
            chatCard("zx_warranty",   "保修卡")    { d, c -> ZxRender36.warranty(d, c) }
            chatCard("zx_inspection", "巡检表")    { d, c -> ZxRender36.inspection(d, c) }
            chatCard("zx_examprep",   "备考计划")  { d, c -> ZxRender36.examprep(d, c) }
            chatCard("zx_tripkit",    "出行清单")  { d, c -> ZxRender36.tripkit(d, c) }
            chatCard("zx_inventory",  "家庭库存")  { d, c -> ZxRender36.inventory(d, c) }
            chatCard("zx_recycle",    "垃圾分类")  { d, c -> ZxRender36.recycle(d, c) }
            chatCard("zx_carservice", "车辆保养")  { d, c -> ZxRender36.carservice(d, c) }
            chatCard("zx_expiry",     "食品保质期") { d, c -> ZxRender36.expiry(d, c) }
            chatCard("zx_splitbill",  "AA 分账")   { d, c -> ZxRender36.splitbill(d, c) }
            chatCard("zx_firstaid",   "急救步骤")  { d, c -> ZxRender36.firstaid(d, c) }
            chatCard("zx_sizing",     "尺码对照")  { d, c -> ZxRender36.sizing(d, c) }
            chatCard("zx_gift",       "人情往来")  { d, c -> ZxRender36.gift(d, c) }

            // 第三十七批 12 个（跨多次、有外部依赖）
            chatCard("zx_renovation", "装修进度")  { d, c -> ZxRender37.renovation(d, c) }
            chatCard("zx_clinic",     "就医陪诊")  { d, c -> ZxRender37.clinic(d, c) }
            chatCard("zx_rental",     "租房比选")  { d, c -> ZxRender37.rental(d, c) }
            chatCard("zx_repair",     "报修工单")  { d, c -> ZxRender37.repair(d, c) }
            chatCard("zx_credential", "证件到期")  { d, c -> ZxRender37.credential(d, c) }
            chatCard("zx_ship",     "快递寄件")  { d, c -> ZxRender37.parcel(d, c) }
            chatCard("zx_interview",  "面试流程")  { d, c -> ZxRender37.interview(d, c) }
            chatCard("zx_visa",       "签证材料")  { d, c -> ZxRender37.visa(d, c) }
            chatCard("zx_insurance",  "保单")      { d, c -> ZxRender37.insurance(d, c) }
            chatCard("zx_reimburse",  "报销单据")  { d, c -> ZxRender37.reimburse(d, c) }
            chatCard("zx_relocation", "搬家迁入")  { d, c -> ZxRender37.relocation(d, c) }
            chatCard("zx_booking",    "场地预订")  { d, c -> ZxRender37.booking(d, c) }

            // 第三十八批 12 个（出事了之后怎么办）
            chatCard("zx_escort",       "陪诊记录")  { d, c -> ZxRender38.escort(d, c) }
            chatCard("zx_lease",        "租房合同")  { d, c -> ZxRender38.lease(d, c) }
            chatCard("zx_overrun",      "装修超支")  { d, c -> ZxRender38.overrun(d, c) }
            chatCard("zx_vet",          "宠物医疗")  { d, c -> ZxRender38.vet(d, c) }
            chatCard("zx_photospec",    "证件照规格"){ d, c -> ZxRender38.photospec(d, c) }
            chatCard("zx_claim",        "快递索赔")  { d, c -> ZxRender38.claim(d, c) }
            chatCard("zx_ivreview",     "面试复盘")  { d, c -> ZxRender38.ivreview(d, c) }
            chatCard("zx_claimprog",    "理赔进度")  { d, c -> ZxRender38.claimprog(d, c) }
            chatCard("zx_refile",       "报销重报")  { d, c -> ZxRender38.refile(d, c) }
            chatCard("zx_damage",       "搬家损坏")  { d, c -> ZxRender38.damage(d, c) }
            chatCard("zx_slotconflict", "场地冲突")  { d, c -> ZxRender38.slotconflict(d, c) }
            chatCard("zx_lostdoc",      "证件挂失")  { d, c -> ZxRender38.lostdoc(d, c) }

            // 第三十九批 12 个（责任在两个主体之间转移）
            chatCard("zx_handoff",      "交接清单")  { d, c -> ZxRender39.handoff(d, c) }
            chatCard("zx_moveout",      "退租结算")  { d, c -> ZxRender39.moveout(d, c) }
            chatCard("zx_vehiclecare",  "车辆维保")  { d, c -> ZxRender39.vehiclecare(d, c) }
            chatCard("zx_medremote",    "异地备案")  { d, c -> ZxRender39.medremote(d, c) }
            chatCard("zx_housedeal",    "二手房交易"){ d, c -> ZxRender39.housedeal(d, c) }
            chatCard("zx_schoolup",     "升学流程")  { d, c -> ZxRender39.schoolup(d, c) }
            chatCard("zx_carerota",     "照护排班")  { d, c -> ZxRender39.carerota(d, c) }
            chatCard("zx_taxxborder",   "跨境税")    { d, c -> ZxRender39.taxxborder(d, c) }
            chatCard("zx_rentreturn",   "设备归还")  { d, c -> ZxRender39.rentreturn(d, c) }
            chatCard("zx_dutyrota",     "团队值班")  { d, c -> ZxRender39.dutyrota(d, c) }
            chatCard("zx_annualaudit",  "证照年审")  { d, c -> ZxRender39.annualaudit(d, c) }
            chatCard("zx_liability",    "责任认定")  { d, c -> ZxRender39.liability(d, c) }

            // 第四十批 12 个（交接完成 ≠ 责任结束）
            chatCard("zx_housekeep",    "家政验收")  { d, c -> ZxRender40.housekeep(d, c) }
            chatCard("zx_finalpay",     "装修尾款")  { d, c -> ZxRender40.finalpay(d, c) }
            chatCard("zx_fosterdaily",  "寄养日报")  { d, c -> ZxRender40.fosterdaily(d, c) }
            chatCard("zx_wardhand",     "转科交接")  { d, c -> ZxRender40.wardhand(d, c) }
            chatCard("zx_schoolnote",   "家校沟通")  { d, c -> ZxRender40.schoolnote(d, c) }
            chatCard("zx_sublet",       "转租")      { d, c -> ZxRender40.sublet(d, c) }
            chatCard("zx_inspecthand",  "周期巡检")  { d, c -> ZxRender40.inspecthand(d, c) }
            chatCard("zx_projhandover", "项目移交")  { d, c -> ZxRender40.projhandover(d, c) }
            chatCard("zx_stocktake",    "盘点")      { d, c -> ZxRender40.stocktake(d, c) }
            chatCard("zx_offboard",     "离职交接")  { d, c -> ZxRender40.offboard(d, c) }
            chatCard("zx_revoke",       "权限回收")  { d, c -> ZxRender40.revoke(d, c) }
            chatCard("zx_warrtrack",    "质保追踪")  { d, c -> ZxRender40.warrtrack(d, c) }

            // 4 个容器全部走 ZxNest 的递归渲染
            chatCard("zx_tabs",      "分页容器")      { d, c -> ZxNestCard.Tabs(d, c) }
            chatCard("zx_accordion", "折叠容器")      { d, c -> ZxNestCard.Accordion(d, c) }
            chatCard("zx_split",     "并排容器")      { d, c -> ZxNestCard.Split(d, c) }
            chatCard("zx_carousel",  "横滑容器")      { d, c -> ZxNestCard.Carousel(d, c) }

            /* ══════════════════════════════════════════════════
               三、UI_WIDGET —— 6 个内联控件
               ══════════════════════════════════════════════════ */
            uiWidget("zx_pick_button",   "行内选中按钮") { d, c -> ZxRender.pickButton(d, c) }
            uiWidget("zx_slider",        "滑块")         { d, c -> ZxRender.slider(d, c) }
            uiWidget("zx_editable_cell", "可编辑单元格") { d, c -> ZxRender2.cell(d, c) }
            uiWidget("zx_hunk_toggle",   "改动块开关")   { d, c -> ZxRender2.hunkToggle(d, c) }
            uiWidget("zx_progress",      "行内进度条")   { d, c -> ZxRender2.progress(d, c) }
            uiWidget("zx_badge",         "状态徽章")     { d, c -> ZxRender2.badge(d, c) }

            /* ══════════════════════════════════════════════════
               四、COMMAND
               ══════════════════════════════════════════════════ */
            command("zx", "/zx <组件名> — 手动渲染一个组件") { raw ->
                val c = raw.trim()
                if (c in ZxDsl.ALL_COMPONENTS) {
                    ZxCardStore.emit(null, c,
                        if (c in ZxDsl3.ALL || c in ZxDsl3.CONTAINERS) ZxDsl3.sample(c)
                        else ZxDsl.sample(c))
                    true
                } else false
            }
            command("zxlist", "/zxlist — 列出全部组件与容器") { _ ->
                val body = buildString {
                    appendLine("实体组件 ${ZxDsl.ALL_COMPONENTS.size} 个：")
                    ZxDsl.ALL_COMPONENTS.forEach { c ->
                        val sp = if (c in ZxDsl4.ALL) ZxDsl4.spec(c)
                                 else if (c in ZxDsl3.ALL || c in ZxDsl3.CONTAINERS) ZxDsl3.spec(c)
                                 else ""
                        appendLine("  $c  $sp")
                    }
                    appendLine("容器 ${ZxDsl.CONTAINERS + ZxDsl3.CONTAINERS} ")
                }
                ZxCardStore.emit(null, "zx_terminal", term(body)); true
            }
            command("zxtheme", "/zxtheme [compact|comfortable|accessible] — 主题变体") { raw ->
                val v = raw.trim()
                if (v.isBlank()) ZxCardStore.emit(null, "zx_terminal", term(ZxTheme.spec()))
                else {
                    val e = ZxTheme.Variant.entries.firstOrNull { it.name.equals(v, true) }
                    if (e == null) ZxCardStore.emit(null, "zx_terminal", term("只能是 compact / comfortable / accessible"))
                    else { ZxTheme.set(e); ZxCardStore.emit(null, "zx_terminal", term("已切到${e.label}\n\n${ZxTheme.spec()}")) }
                }
                true
            }
            command("zxmotion", "/zxmotion — 动效协议") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxMotion.spec())); true
            }
            command("zxwait", "/zxwait — 等待基线") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxWait.demo())); true
            }
            command("zxrecall", "/zxrecall — 场景记忆") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxRecall.demo())); true
            }
            command("zxremedy", "/zxremedy — 补救时限") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxRemedy.demo())); true
            }
            command("zxafter", "/zxafter — 事后归档") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxAftermath.demo())); true
            }
            command("zxattest", "/zxattest — 确认登记") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxAttest.demo())); true
            }
            command("zxcustody", "/zxcustody — 监管链") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxCustody.demo())); true
            }
            command("zxresid", "/zxresid — 残留责任") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxResidual.demo())); true
            }
            command("zxhold", "/zxhold — 持有期窗口") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxHoldWindow.demo())); true
            }
            command("zxbook", "/zxbook — 场景剧本") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxPlaybook.demo())); true
            }
            command("zxmiss", "/zxmiss — DSL 错题本") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxMistake.demo())); true
            }
            command("zxregress", "/zxregress — 全量回归") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxRegress.demo())); true
            }
            command("zxadapt", "/zxadapt — 真机适配") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxAdapt.demo())); true
            }
            command("zxfeed", "/zxfeed — 内容源与时效") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxFeed.demo())); true
            }
            command("zxcalib", "/zxcalib — 推演校准") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxCalib.demo())); true
            }
            command("zxscen", "/zxscen — 情景快照与复现") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxScen.demo())); true
            }
            command("zxfamilies", "/zxfamilies — 组件族摘要") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxFamilies.demo())); true
            }
            command("zxtrigger", "/zxtrigger — 闭环自动触发器") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxTrigger.demo())); true
            }
            command("zxcore", "/zxcore — 组件收敛分析") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxCore.demo())); true
            }
            command("zxoutcome", "/zxoutcome — 结果回收协议") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxOutcome.demo())); true
            }
            command("zxcompose", "/zxcompose — 组件自组合") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxCompose.demo())); true
            }
            command("zxledger", "/zxledger — 假设登记册") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxLedger.demo())); true
            }
            command("zxsem", "/zxsem — 语义字典") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxSem.demo())); true
            }
            command("zxclaim", "/zxclaim — 因果主张的假设清单") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxCausal.demo())); true
            }
            command("zxpipever", "/zxpipever — 流水线版本与复现") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxPipeVer.demo())); true
            }
            command("zxhypo", "/zxhypo — 假设检验与多重比较") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxHypo.demo())); true
            }
            command("zxpipe", "/zxpipe — 分析流水线") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxPipe.demo())); true
            }
            command("zxsolve", "/zxsolve — 约束可视化求解") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxSolveUI.demo())); true
            }
            command("zxtdiff", "/zxtdiff — 轨迹差异对比") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxTraceDiff.demo())); true
            }
            command("zxtrace", "/zxtrace — 交互导出") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxTrace.demo())); true
            }
            command("zxcast", "/zxcast — 离线意图广播") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxBcast.demo())); true
            }
            command("zxmulti", "/zxmulti — 多目标求解") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxMultiSolve.demo())); true
            }
            command("zxcolab", "/zxcolab — 协同编辑") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxCollab.demo())); true
            }
            command("zxreplay", "/zxreplay — 意图级重放") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxReplay.demo())); true
            }
            command("zxinc", "/zxinc — 增量求解") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxIncSolve.demo())); true
            }
            command("zxturn", "/zxturn — 轮次边界（意图级撤销）") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxTurn.report())); true
            }
            command("zxsolve", "/zxsolve — 跨卡约束求解") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxSolve.demo())); true
            }
            command("zxdisc", "/zxdisc — 渐进式披露") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxDisclose.report())); true
            }
            command("zxmrg", "/zxmrg — 事务冲突合并") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxTxMerge.cases())); true
            }
            command("zxlife", "/zxlife — 卡片生命周期") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxLifecycle.report())); true
            }
            command("zxtx", "/zxtx — 事务可视化") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxTxView.report())); true
            }
            command("zxpatch", "/zxpatch — DSL 补丁协议") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxPatch.guide())); true
            }
            command("zxpref", "/zxpref — 渲染偏好") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxPref.summary())); true
            }
            command("zxindex", "/zxindex <词> — 搜历史卡片") { raw ->
                val q = raw.removePrefix("/zxindex").trim()
                ZxCardStore.emit(null, "zx_terminal", term(
                    if (q.isBlank()) ZxIndex.report() else ZxIndex.format(q)))
                true
            }
            command("zxhot", "/zxhot — 卡片热更新") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxHot.list() + "\n\n" + ZxHot.guide())); true
            }
            command("zxreverse", "/zxreverse — 意图反向编译") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxReverse.report())); true
            }
            command("zxregress", "/zxregress — 端到端回归测试") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxRegress.report())); true
            }
            command("zxintro", "/zxintro <组件> — 查组件契约") { raw ->
                val nm = raw.removePrefix("/zxintro").trim()
                ZxCardStore.emit(null, "zx_terminal", term(
                    if (nm.isBlank()) ZxIntrospect.guide() else ZxIntrospect.describe(nm)))
                true
            }
            command("zxconflict", "/zxconflict — 冲突解决") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxConflict.guide())); true
            }
            command("zxstream", "/zxstream — 流式增量渲染") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxStream.guide())); true
            }
            command("zxq", "/zxq <查询> — 跨卡查询") { raw ->
                val q = raw.removePrefix("/zxq").trim()
                ZxCardStore.emit(null, "zx_terminal",
                    term(if (q.isBlank()) ZxQuery.report() else "$ " + q + "\n" + ZxQuery.exec(q))); true
            }
            command("zxtrans", "/zxtrans — 多卡过渡编排") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxTransition.guide())); true
            }
            command("zxpersist", "/zxpersist — 跨会话持久化") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxPersist.guide() + "\n\n" + ZxPersist.report())); true
            }
            command("zxfeedback", "/zxfeedback — 渲染回执") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxFeedback.guide() + "\n\n" + ZxFeedback.digest(10))); true
            }
            command("zxsnap", "/zxsnap — 快照回归测试") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxSnapshot.guide())); true
            }
            command("zxfocus", "/zxfocus — 无障碍焦点顺序") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxFocus.guide())); true
            }
            command("zxlayout", "/zxlayout — 多卡布局编排") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxLayout.guide())); true
            }
            command("zxreplay", "/zxreplay — 重放栈调试") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxReplay.guide() + "\n\n" + ZxReplay.format())); true
            }
            command("zxdiscover", "/zxdiscover [关键词] — 查当前能画什么") { raw ->
                val env = ZxRegistry.Env(widthDp = 360, variant = ZxTheme.current().name,
                                         caps = ZxSandbox.capsOf("_"), online = true)
                ZxCardStore.emit(null, "zx_terminal", term(ZxRegistry.catalog(env, null, raw.trim()))); true
            }
            command("zxconstraint", "/zxconstraint — 跨卡约束") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxConstraint.spec())); true
            }
            command("zxundo", "/zxundo [卡片ID] — 输入级撤销历史") { raw ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxInputUndo.guide() + "\n\n" +
                    ZxInputUndo.history(raw.trim()))); true
            }
            command("zxgesture", "/zxgesture — 交互基建（手势/焦点/长按）") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(
                    ZxGesture.axisGuide() + "\n\n" + ZxGesture.focusGuide() + "\n\n" + ZxGesture.menuGuide()
                )); true
            }
            command("zxspec", "/zxspec [组件名] — 打印字段规格") { raw ->
                ZxCardStore.emit(null, "zx_terminal", ZxDsl.specAsTerminal(raw.trim().ifBlank { null })); true
            }
            command("zxundo", "/zxundo [redo] — 撤销/重做上一次卡片交互") { raw ->
                ZxCardStore.emit(null, "zx_terminal",
                    term(if (raw.trim() == "redo") ZxStore.redo() else ZxStore.undo())); true
            }
            command("zxclear", "/zxclear — 清空卡片与撤销栈") {
                ZxRef.clear(); ZxCardStore.clear(); true
            }
            command("zxpeers", "/zxpeers — 列出已配对设备与离线积压") { _ ->
                val ps = ZxRemote.peers().joinToString("\n") { "  ${it.id} · ${it.name} · ${it.trust}" }
                ZxCardStore.emit(null, "zx_terminal",
                    term("已配对 ${ZxRemote.peers().size} 台，离线积压 ${ZxRemote.pendingCount()} 条\n$ps")); true
            }
            command("zxcaps", "/zxcaps [R0|R1|R2|R3] — 打印权限矩阵 / 设置授权上限") { raw ->
                val lv = raw.trim()
                if (lv.isBlank()) { ZxCardStore.emit(null, "zx_terminal", term(ZxSandbox.matrix())) }
                else {
                    val r = ZxSandbox.Risk.entries.firstOrNull { it.name == lv }
                    if (r == null) ZxCardStore.emit(null, "zx_terminal", term("等级只能是 R0/R1/R2/R3"))
                    else { ZxSandbox.setCeiling(r); ZxCardStore.emit(null, "zx_terminal",
                        term("用户授权上限已设为 ${r.label}\n\n${ZxSandbox.matrix()}")) }
                }
                true
            }
            command("zxaudit", "/zxaudit [sensitive] — 导出审计流水") { raw ->
                ZxCardStore.emit(null, "zx_terminal",
                    ZxAudit.exportAsTerminal(raw.trim() == "sensitive")); true
            }
            command("zxtpl", "/zxtpl — 列出模板库") { _ ->
                ZxCardStore.emit(null, "zx_terminal", ZxTemplate.listAsTerminal()); true
            }
            command("zxtx", "/zxtx — 查看事务状态") { _ ->
                ZxCardStore.emit(null, "zx_terminal", ZxTx.statusAsTerminal()); true
            }
            command("zxcache", "/zxcache — 渲染缓存命中率") { _ ->
                ZxCardStore.emit(null, "zx_terminal", ZxCache.statsAsTerminal()); true
            }
            command("zxheal", "/zxheal — DSL 自愈统计") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term("自愈错误统计：\n${ZxHeal.stats()}")); true
            }
            command("zxintent", "/zxintent — 列出可用意图类型") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term("可用意图：\n${ZxIntent.types()}")); true
            }
            command("zxsync", "/zxsync — 导出本机卡片增量") { _ ->
                ZxCardStore.emit(null, "zx_terminal",
                    term("本机站点 ${ZxSync.site().id} · 时钟 ${ZxSync.clock()}\n\n${ZxSync.exportDelta()}")); true
            }
            command("zxmarket", "/zxmarket [关键词] — 卡片市场目录") { raw ->
                ZxCardStore.emit(null, "zx_terminal",
                    ZxMarket.catalogAsTerminal(raw.trim().ifBlank { null })); true
            }
            command("zxa11y", "/zxa11y — 无障碍检查指引") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxA11y.guide())); true
            }
            command("zxreview", "/zxreview — 自评阈值与降级表") { _ ->
                ZxCardStore.emit(null, "zx_terminal", term(ZxReview.guide())); true
            }
            command("zxroute", "/zxroute <意图> — 看当前条件下该画什么") { raw ->
                val intent = raw.trim().ifBlank { "trend" }
                val env = ZxRouter.snapshot()
                val ctx = ZxRouter.Ctx(online = env.online, localEngine = env.localEngine,
                    tokenBudget = env.tokenBudget, widthDp = env.widthDp, dataPoints = 7)
                ZxCardStore.emit(null, "zx_terminal", ZxRouter.explainAsTerminal(intent, ctx)); true
            }

            /* ══════════════════════════════════════════════════
               五、SCHEDULE_TASK
               ══════════════════════════════════════════════════ */
            scheduleTask("zx_daily_digest", "每日卡片摘要") { payload ->
                ZxCardStore.emit("每日摘要", "zx_chart", ZxDsl2.buildChartFrom(payload)); "已生成每日摘要卡"
            }
            scheduleTask("zx_watch_diff", "文件变更监听") { payload ->
                ZxCardStore.emit(null, "zx_diff", ZxDsl2.buildDiffFrom(payload)); "已生成差异卡"
            }
            scheduleTask("zx_flush_remote", "重放离线积压卡片") { _ ->
                val n = ZxRemote.flush(); "已补发 $n 张离线卡片"
            }

            /* ══════════════════════════════════════════════════
               六、FILE_HANDLER
               ══════════════════════════════════════════════════ */
            fileHandler("zx_card", "zx 卡片预览", "zxcard", "zx") { path ->
                val dsl = readText(path)
                val type = org.json.JSONObject(dsl).optString("type")
                if (type in ZxDsl.COMPONENTS || type in setOf("zx_tabs","zx_accordion","zx_split","zx_carousel")) {
                    ZxCardStore.emit(null, type, dsl); ToolResult.text("已预览 $path（$type）")
                } else ToolResult.text("不是合法 zx 组件文件：$path")
            }

            /* ══════════════════════════════════════════════════
               七、CODE_RUNTIME
               ══════════════════════════════════════════════════ */
            codeRuntime("zxscript", "zx 卡片脚本", "zxscript") { code ->
                var n = 0
                code.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { ln ->
                    val i = ln.indexOf(' ')
                    val verb = if (i < 0) ln else ln.substring(0, i)
                    val rest = if (i < 0) "" else ln.substring(i + 1)
                    val comp = verb.removePrefix("render_")
                    when {
                        comp in ZxDsl.COMPONENTS -> {
                            ZxCardStore.emit(null, comp, rest.ifBlank { ZxDsl.sample(comp) }); n++
                        }
                        comp == "nest" -> {
                            val root = org.json.JSONObject(rest.ifBlank { ZxRemote.sampleNested() })
                            ZxCardStore.emit(null, root.optString("type"), root.toString()); n++
                        }
                    }
                }
                "执行完毕，渲染 $n 个组件"
            }

            /* ══════════════════════════════════════════════════
               八、RAG_SOURCE
               ══════════════════════════════════════════════════ */
            ragSource("zx_cards", "历史卡片") { query, topK -> ZxCardStore.search(query, topK) }

            /* ══════════════════════════════════════════════════
               九、ACI_CAPABILITY
               ══════════════════════════════════════════════════ */
            aciCapability("zx_render_remote", "让外部 Agent 在本机对话框渲染一个卡片") {
                param("component", ParamType.STRING, "组件类型")
                param("dsl", ParamType.STRING, "quro-ui JSON")
                execute { args ->
                    // ACI 侧拿不到 peer 时按未知设备处理，必须显式配对
                    val peer = args.string("peer").ifBlank { "unknown" }
                    val r = ZxRemote.request(peer, args.string("component"), args.string("dsl"))
                    ToolResult.text(r.reason)
                }
            }
            aciCapability("zx_list_components", "列出本机可用组件与容器") {
                execute { ToolResult.text(ZxRemote.manifest()) }
            }
            aciCapability("zx_pair_device", "配对一台设备") {
                param("peer", ParamType.STRING, "设备 id")
                param("name", ParamType.STRING, "设备名")
                execute { args ->
                    ZxRemote.pair(args.string("peer"), args.string("name").ifBlank { args.string("peer") })
                    ToolResult.text("已配对 ${args.string("peer")}")
                }
            }

            /* ══════════════════════════════════════════════════
               十、SETTING
               ══════════════════════════════════════════════════ */
            setting("zx_enabled",      "气泡内可交互组件", SettingKind.BOOLEAN,   default = "true")
            setting("zx_stream_grow",  "逐层流式生长",     SettingKind.BOOLEAN,   default = "true")
            setting("zx_auto_approve", "L1 操作免确认",    SettingKind.BOOLEAN,   default = "false")
            setting("zx_chart_style",  "图表风格",         SettingKind.SELECT, default = "line",
                    options = "line,bar,donut,area".split(","))
            setting("zx_max_nest",     "嵌套层数上限",     SettingKind.SELECT, default = "4",
                    options = "2,3,4".split(","))
            setting("zx_risk_ceiling", "授权上限",         SettingKind.SELECT, default = "R1",
                    options = "R0,R1,R2,R3".split(","))
            setting("zx_audit_sensitive", "只审计敏感项",  SettingKind.BOOLEAN,   default = "false")
            setting("zx_theme",         "渲染密度",       SettingKind.SELECT, default = "comfortable",
                    options = "compact,comfortable,accessible".split(","))
            setting("zx_reduce_motion", "减少动画",       SettingKind.BOOLEAN,   default = "false")
        }

        // 装插件时装好内置模板与市场示例包，AI 第一次调用就有东西可用
        ZxTemplate.installBuiltins()
        ZxMarket.seed()
        ZxSync.attachSite(ctx.deviceId.ifBlank { "site-${System.currentTimeMillis()}" }, "本机")
        ZxAudit.log(ZxAudit.Kind.TEMPLATE, "_plugin", "zx_widget 安装完成，内置模板与市场已就绪")
    }

    private fun readText(path: String): String = java.io.File(path).readText()
    private fun term(s: String): String =
        """{"type":"zx_terminal","cmd":"zx","exit":0,"lines":[${
            s.lineSequence().joinToString(",") { """{"lv":"out","c":${org.json.JSONObject.quote(it)}}""" }
        }]}"""
}
