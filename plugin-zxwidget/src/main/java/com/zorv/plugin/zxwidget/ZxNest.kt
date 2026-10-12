package com.zorv.plugin.zxwidget

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 嵌套容器 —— 卡片里套卡片。
 *
 * 支持 4 种容器：zx_tabs / zx_accordion / zx_split / zx_carousel
 * 子节点可以是 12 个组件中的任意一个，也可以再套一层容器（递归）。
 *
 * 递归必须有深度上限，否则模型一个手滑输出自引用就把 UI 栈打爆。
 */
object ZxNest {

    /** 硬上限：再深的嵌套没有可读性，也挡住病态 DSL */
    const val MAX_DEPTH = 4
    /** 单容器子节点上限，防止一次渲染几百个组合把主线程拖死 */
    const val MAX_CHILDREN = 12

    /** 统一的递归渲染入口 */
    @Composable
    fun render(node: JSONObject, ctx: RenderContext, depth: Int = 0) {
        if (depth > MAX_DEPTH) {
            Text("嵌套过深（>$MAX_DEPTH 层），已停止渲染",
                 fontSize = 11.sp, color = ZxT.Err,
                 modifier = Modifier.padding(4.dp)
                     .clip(RoundedCornerShape(7.dp))
                     .background(ZxT.Err.copy(.12f)).padding(7.dp))
            return
        }
        when (node.optString("type")) {
            "zx_tabs"      -> tabs(node, ctx, depth)
            "zx_accordion" -> accordion(node, ctx, depth)
            "zx_split"     -> split(node, ctx, depth)
            "zx_carousel"  -> carousel(node, ctx, depth)
            // 第三批容器走 ZxRender3x，它们要按可用宽度动态决定怎么摆
            "zx_grid", "zx_master", "zx_stagger" -> {
                if (!renderThirdBatchIfAny(node, ctx)) leaf(node, ctx, depth)
            }
            else           -> leaf(node, ctx, depth)
        }
    }

    /* ── 叶子：12 个实体组件，委托给已有的渲染器 ─────────────── */
    @Composable
    private fun leaf(node: JSONObject, ctx: RenderContext, depth: Int) {
        // 引用先解析，再交给渲染器——嵌套层也要能吃 {{ref.xxx}}
        val resolved = ZxRef.resolve(node.toString(), node.optString("id", "_"))
        val o = runCatching { JSONObject(resolved) }.getOrDefault(node)
        when (o.optString("type")) {
            "zx_compare"  -> ZxRender.compare(o, ctx)
            "zx_form"     -> ZxRender.form(o, ctx)
            "zx_timeline" -> ZxRender.timeline(o, ctx)
            "zx_choice"   -> ZxRender.choice(o, ctx)
            "zx_table"    -> ZxRender2.table(o, ctx)
            "zx_chart"    -> ZxRender2.chart(o, ctx)
            "zx_kanban"   -> ZxRender2.kanban(o, ctx)
            "zx_diff"     -> ZxRender2.diff(o, ctx)
            "zx_terminal" -> ZxRender2.terminal(o, ctx)
            "zx_approval" -> ZxRender2.approval(o, ctx)
            "zx_media"    -> ZxRender2.media(o, ctx)
            "zx_stepper"  -> ZxRender2.stepper(o, ctx)
            // 第三批
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
            "zx_tour"         -> ZxRender7.tour(o, ctx)
            "zx_swipe"       -> ZxRender7.swipe(o, ctx)
            "zx_matrix"      -> ZxRender7.matrix(o, ctx)
            "zx_autocomplete" -> ZxRender7.autocomplete(o, ctx)
            "zx_spin"        -> ZxRender7.spin(o, ctx)
            "zx_barcode"     -> ZxRender7.barcode(o, ctx)
            "zx_dualist"     -> ZxRender7.dualist(o, ctx)
            "zx_scrubber"    -> ZxRender7.scrubber(o, ctx)
            "zx_bento"       -> ZxRender7.bento(o, ctx)
            "zx_minmap"      -> ZxRender7.minmap(o, ctx)
            "zx_statelist"   -> ZxRender7.statelist(o, ctx)
            "zx_legend"      -> ZxRender7.legend(o, ctx)
            // 第八批
            "zx_radiogroup"  -> ZxRender8.radiogroup(o, ctx)
            "zx_checklist"   -> ZxRender8.checklist(o, ctx)
            "zx_avatargroup" -> ZxRender8.avatargroup(o, ctx)
            "zx_meter"       -> ZxRender8.meter(o, ctx)
            "zx_ring"        -> ZxRender8.ring(o, ctx)
            "zx_stat"        -> ZxRender8.stat(o, ctx)
            "zx_swatch"      -> ZxRender8.swatch(o, ctx)
            "zx_notice"      -> ZxRender8.notice(o, ctx)
            "zx_price"       -> ZxRender8.price(o, ctx)
            "zx_keyvalue"    -> ZxRender8.keyvalue(o, ctx)
            "zx_inputmask"   -> ZxRender8.inputmask(o, ctx)
            "zx_datalist"    -> ZxRender8.datalist(o, ctx)
            // 第九批
            "zx_waterfall"   -> ZxRender9.waterfall(o, ctx)
            "zx_pyramid"     -> ZxRender9.pyramid(o, ctx)
            "zx_bubble"      -> ZxRender9.bubble(o, ctx)
            "zx_venn"        -> ZxRender9.venn(o, ctx)
            "zx_candle"      -> ZxRender9.candle(o, ctx)
            "zx_dag"         -> ZxRender9.dag(o, ctx)
            "zx_swimlane"    -> ZxRender9.swimlane(o, ctx)
            "zx_mindmap"     -> ZxRender9.mindmap(o, ctx)
            "zx_gallery"     -> ZxRender9.gallery(o, ctx)
            "zx_wizard"      -> ZxRender9.wizard(o, ctx)
            "zx_spreadsheet" -> ZxRender9.spreadsheet(o, ctx)
            "zx_conversation"-> ZxRender9.conversation(o, ctx)
            // 第十批
            "zx_calheat"   -> ZxRender10.calheat(o, ctx)
            "zx_matrixheat"-> ZxRender10.matrixheat(o, ctx)
            "zx_polar"     -> ZxRender10.polar(o, ctx)
            "zx_voice"     -> ZxRender10.voice(o, ctx)
            "zx_stars"     -> ZxRender10.stars(o, ctx)
            "zx_react"     -> ZxRender10.react(o, ctx)
            "zx_scale"     -> ZxRender10.scale(o, ctx)
            "zx_tags"      -> ZxRender10.tags(o, ctx)
            "zx_flipnum"   -> ZxRender10.flipnum(o, ctx)
            "zx_skeleton"  -> ZxRender10.skeleton(o, ctx)
            "zx_badges"    -> ZxRender10.badges(o, ctx)
            // 第十一批
            "zx_rich"     -> ZxRender11.rich(o, ctx)
            "zx_quote"    -> ZxRender11.quote(o, ctx)
            "zx_filecard" -> ZxRender11.filecard(o, ctx)
            "zx_callout"  -> ZxRender11.callout(o, ctx)
            "zx_hint"     -> ZxRender11.hint(o, ctx)
            "zx_seat"     -> ZxRender11.seat(o, ctx)
            "zx_floor"    -> ZxRender11.floor(o, ctx)
            "zx_banner"   -> ZxRender11.banner(o, ctx)
            "zx_divider"  -> ZxRender11.divider(o, ctx)
            "zx_settings" -> ZxRender11.settings(o, ctx)
            "zx_command"  -> ZxRender11.command(o, ctx)
            "zx_empty"    -> ZxRender11.empty(o, ctx)
            // 第十二批
            "zx_orgchart"  -> ZxRender12.orgchart(o, ctx)
            "zx_sankey"    -> ZxRender12.sankey(o, ctx)
            "zx_gauge"     -> ZxRender12.gauge(o, ctx)
            "zx_sparkgrid" -> ZxRender12.sparkgrid(o, ctx)
            "zx_breadcrumb"-> ZxRender12.breadcrumb(o, ctx)
            "zx_pagination"-> ZxRender12.pagination(o, ctx)
            "zx_tabbar"    -> ZxRender12.tabbar(o, ctx)
            "zx_searchbar" -> ZxRender12.searchbar(o, ctx)
            "zx_filterchips"     -> ZxRender12.chips(o, ctx)
            "zx_colorpick" -> ZxRender12.colorpick(o, ctx)
            "zx_stepper"   -> ZxRender12.stepper(o, ctx)
            "zx_treeview"  -> ZxRender12.treeview(o, ctx)
            // 第十三批
            "zx_diffview" -> ZxRender13.diffview(o, ctx)
            "zx_baseline" -> ZxRender13.baseline(o, ctx)
            "zx_logview"  -> ZxRender13.logview(o, ctx)
            "zx_console"  -> ZxRender13.console(o, ctx)
            "zx_depgraph" -> ZxRender13.depgraph(o, ctx)
            "zx_datamap"  -> ZxRender13.datamap(o, ctx)
            "zx_conflict" -> ZxRender13.conflict(o, ctx)
            "zx_activity" -> ZxRender13.activity(o, ctx)
            "zx_timerange"-> ZxRender13.timerange(o, ctx)
            "zx_gridform" -> ZxRender13.gridform(o, ctx)
            "zx_scorecard"-> ZxRender13.scorecard(o, ctx)
            "zx_waveedit" -> ZxRender13.waveedit(o, ctx)
            // 第十四批
            "zx_histogram" -> ZxRender14.histogram(o, ctx)
            "zx_boxplot"   -> ZxRender14.boxplot(o, ctx)
            "zx_pareto"    -> ZxRender14.pareto(o, ctx)
            "zx_control"   -> ZxRender14.control(o, ctx)
            "zx_sunburst"  -> ZxRender14.sunburst(o, ctx)
            "zx_video"     -> ZxRender14.video(o, ctx)
            "zx_docview"   -> ZxRender14.docview(o, ctx)
            "zx_model3d"   -> ZxRender14.model3d(o, ctx)
            "zx_schedule"  -> ZxRender14.schedule(o, ctx)
            "zx_nps"       -> ZxRender14.nps(o, ctx)
            "zx_export"    -> ZxRender14.export(o, ctx)
            "zx_bargauge"  -> ZxRender14.bargauge(o, ctx)
            // 第十五批
            "zx_route"     -> ZxRender15.route(o, ctx)
            "zx_network"   -> ZxRender15.network(o, ctx)
            "zx_annotate"  -> ZxRender15.annotate(o, ctx)
            "zx_revision"  -> ZxRender15.revision(o, ctx)
            "zx_bullet"    -> ZxRender15.bullet(o, ctx)
            "zx_icicle"    -> ZxRender15.icicle(o, ctx)
            "zx_cohort"    -> ZxRender15.cohort(o, ctx)
            "zx_slope"     -> ZxRender15.slope(o, ctx)
            "zx_dumbbell"  -> ZxRender15.dumbbell(o, ctx)
            "zx_waffle"    -> ZxRender15.waffle(o, ctx)
            "zx_ridgeline" -> ZxRender15.ridgeline(o, ctx)
            "zx_steparea"  -> ZxRender15.steparea(o, ctx)
            // 第十六批
            "zx_weekgrid"    -> ZxRender16.weekgrid(o, ctx)
            "zx_pulse"       -> ZxRender16.pulse(o, ctx)
            "zx_scoreboard"  -> ZxRender16.scoreboard(o, ctx)
            "zx_abtest"      -> ZxRender16.abtest(o, ctx)
            "zx_statemachine"-> ZxRender16.statemachine(o, ctx)
            "zx_checkflow"   -> ZxRender16.checkflow(o, ctx)
            "zx_branch"      -> ZxRender16.branch(o, ctx)
            "zx_sla"         -> ZxRender16.sla(o, ctx)
            "zx_repost"      -> ZxRender16.repost(o, ctx)
            "zx_thread"      -> ZxRender16.thread(o, ctx)
            "zx_splitflap"   -> ZxRender16.splitflap(o, ctx)
            "zx_runbook"     -> ZxRender16.runbook(o, ctx)
            // 第十七批
            "zx_geo"       -> ZxRender17.geo(o, ctx)
            "zx_formula"   -> ZxRender17.formula(o, ctx)
            "zx_tokens"    -> ZxRender17.tokens(o, ctx)
            "zx_ast"       -> ZxRender17.ast(o, ctx)
            "zx_hexgrid"   -> ZxRender17.hexgrid(o, ctx)
            "zx_spiral"    -> ZxRender17.spiral(o, ctx)
            "zx_marquee"   -> ZxRender17.marquee(o, ctx)
            "zx_odometer"  -> ZxRender17.odometer(o, ctx)
            "zx_radial"    -> ZxRender17.radial(o, ctx)
            "zx_isometric" -> ZxRender17.isometric(o, ctx)
            "zx_spectrum"  -> ZxRender17.spectrum(o, ctx)
            "zx_bezier"    -> ZxRender17.bezier(o, ctx)
            // 第十八批
            "zx_flowedit"  -> ZxRender18.flowedit(o, ctx)
            "zx_crop"      -> ZxRender18.crop(o, ctx)
            "zx_mapping"   -> ZxRender18.mapping(o, ctx)
            "zx_ratio"     -> ZxRender18.ratio(o, ctx)
            "zx_deviation" -> ZxRender18.deviation(o, ctx)
            "zx_timeline2" -> ZxRender18.timeline2(o, ctx)
            "zx_scale2"    -> ZxRender18.scale2(o, ctx)
            "zx_mirror"    -> ZxRender18.mirror(o, ctx)
            "zx_dial2"     -> ZxRender18.dial2(o, ctx)
            "zx_matrix2"   -> ZxRender18.matrix2(o, ctx)
            "zx_ladder"    -> ZxRender18.ladder(o, ctx)
            "zx_slot"      -> ZxRender18.slot(o, ctx)
            // 第十九批
            "zx_traceback" -> ZxRender19.traceback(o, ctx)
            "zx_seq"       -> ZxRender19.seq(o, ctx)
            "zx_explode"   -> ZxRender19.explode(o, ctx)
            "zx_critical"  -> ZxRender19.critical(o, ctx)
            "zx_evidence"  -> ZxRender19.evidence(o, ctx)
            "zx_confront"  -> ZxRender19.confront(o, ctx)
            "zx_matrix3"   -> ZxRender19.matrix3(o, ctx)
            "zx_receipt"   -> ZxRender19.receipt(o, ctx)
            "zx_ledger"    -> ZxRender19.ledger(o, ctx)
            "zx_gauntlet"  -> ZxRender19.gauntlet(o, ctx)
            "zx_chain"     -> ZxRender19.chain(o, ctx)
            "zx_bracket"   -> ZxRender19.bracket(o, ctx)
            // 第二十批
            "zx_contour"   -> ZxRender20.contour(o, ctx)
            "zx_stream"    -> ZxRender20.stream(o, ctx)
            "zx_horizon"   -> ZxRender20.horizon(o, ctx)
            "zx_parallel"  -> ZxRender20.parallel(o, ctx)
            "zx_beeswarm"  -> ZxRender20.beeswarm(o, ctx)
            "zx_violin"    -> ZxRender20.violin(o, ctx)
            "zx_glyph"     -> ZxRender20.glyph(o, ctx)
            "zx_arc"       -> ZxRender20.arc(o, ctx)
            "zx_mosaic"    -> ZxRender20.mosaic(o, ctx)
            "zx_trail"     -> ZxRender20.trail(o, ctx)
            "zx_pictogram" -> ZxRender20.pictogram(o, ctx)
            "zx_dotstrip"  -> ZxRender20.dotstrip(o, ctx)
            // 第二十一批
            "zx_align"     -> ZxRender21.align(o, ctx)
            "zx_jsondiff"  -> ZxRender21.jsondiff(o, ctx)
            "zx_threeway"  -> ZxRender21.threeway(o, ctx)
            "zx_sankey2"   -> ZxRender21.sankey2(o, ctx)
            "zx_split"     -> ZxRender21.split(o, ctx)
            "zx_switch"    -> ZxRender21.switch(o, ctx)
            "zx_lane"      -> ZxRender21.lane(o, ctx)
            "zx_bullet2"   -> ZxRender21.bullet2(o, ctx)
            "zx_ring2"     -> ZxRender21.ring2(o, ctx)
            "zx_stepflow"  -> ZxRender21.stepflow(o, ctx)
            "zx_annotate"  -> ZxRender21.annotate(o, ctx)
            "zx_dial3"     -> ZxRender21.dial3(o, ctx)
            // 第二十二批
            "zx_cmdhist"   -> ZxRender22.cmdhist(o, ctx)
            "zx_callstack" -> ZxRender22.callstack(o, ctx)
            "zx_uptime"    -> ZxRender22.uptime(o, ctx)
            "zx_playback"  -> ZxRender22.playback(o, ctx)
            "zx_pipe"      -> ZxRender22.pipe(o, ctx)
            "zx_corr"      -> ZxRender22.corr(o, ctx)
            "zx_radar2"    -> ZxRender22.radar2(o, ctx)
            "zx_choro"     -> ZxRender22.choro(o, ctx)
            "zx_toc"       -> ZxRender22.toc(o, ctx)
            "zx_glossary"  -> ZxRender22.glossary(o, ctx)
            "zx_survey"    -> ZxRender22.survey(o, ctx)
            "zx_punch"     -> ZxRender22.punch(o, ctx)
            // 第二十三批
            "zx_decision"  -> ZxRender23.decision(o, ctx)
            "zx_tradeoff"  -> ZxRender23.tradeoff(o, ctx)
            "zx_fallback"  -> ZxRender23.fallback(o, ctx)
            "zx_retry"     -> ZxRender23.retry(o, ctx)
            "zx_brush"     -> ZxRender23.brush(o, ctx)
            "zx_blast"     -> ZxRender23.blast(o, ctx)
            "zx_batch"     -> ZxRender23.batch(o, ctx)
            "zx_priority"  -> ZxRender23.priority(o, ctx)
            "zx_probe"     -> ZxRender23.probe(o, ctx)
            "zx_inspect"   -> ZxRender23.inspect(o, ctx)
            "zx_remap"     -> ZxRender23.remap(o, ctx)
            "zx_rollup"    -> ZxRender23.rollup(o, ctx)
            // 第二十四批
            "zx_qq"        -> ZxRender24.qq(o, ctx)
            "zx_forest"    -> ZxRender24.forest(o, ctx)
            "zx_bland"     -> ZxRender24.bland(o, ctx)
            "zx_survival"  -> ZxRender24.survival(o, ctx)
            "zx_ecdf"      -> ZxRender24.ecdf(o, ctx)
            "zx_lorenz"    -> ZxRender24.lorenz(o, ctx)
            "zx_regress"   -> ZxRender24.regress(o, ctx)
            "zx_scatter"   -> ZxRender24.scatter(o, ctx)
            "zx_seqdiff"   -> ZxRender24.seqdiff(o, ctx)
            "zx_cmdpal"    -> ZxRender24.cmdpal(o, ctx)
            "zx_span"      -> ZxRender24.span(o, ctx)
            "zx_tolerance" -> ZxRender24.tolerance(o, ctx)
            // 第二十五批
            "zx_decompose" -> ZxRender25.decompose(o, ctx)
            "zx_boot"      -> ZxRender25.boot(o, ctx)
            "zx_perm"      -> ZxRender25.perm(o, ctx)
            "zx_test"      -> ZxRender25.test(o, ctx)
            "zx_roc"       -> ZxRender25.roc(o, ctx)
            "zx_lift"      -> ZxRender25.lift(o, ctx)
            "zx_pscan"     -> ZxRender25.pscan(o, ctx)
            "zx_vardec"    -> ZxRender25.vardec(o, ctx)
            "zx_mds"       -> ZxRender25.mds(o, ctx)
            "zx_geoflow"   -> ZxRender25.geoflow(o, ctx)
            "zx_violincmp" -> ZxRender25.violincmp(o, ctx)
            "zx_survcmp"   -> ZxRender25.survcmp(o, ctx)
            // 第二十六批
            "zx_causal"    -> ZxRender26.causal(o, ctx)
            "zx_did"       -> ZxRender26.did(o, ctx)
            "zx_rdd"       -> ZxRender26.rdd(o, ctx)
            "zx_iv"        -> ZxRender26.iv(o, ctx)
            "zx_sense"     -> ZxRender26.sense(o, ctx)
            "zx_psm"       -> ZxRender26.psm(o, ctx)
            "zx_balance"   -> ZxRender26.balance(o, ctx)
            "zx_missing"   -> ZxRender26.missing(o, ctx)
            "zx_seqtest"   -> ZxRender26.seqtest(o, ctx)
            "zx_bayes"     -> ZxRender26.bayes(o, ctx)
            "zx_anomaly"   -> ZxRender26.anomaly(o, ctx)
            "zx_power"     -> ZxRender26.power(o, ctx)
            // 第二十七批
            "zx_forecast"   -> ZxRender27.forecast(o, ctx)
            "zx_calib"      -> ZxRender27.calib(o, ctx)
            "zx_optline"    -> ZxRender27.optline(o, ctx)
            "zx_dmatrix"   -> ZxRender27.decision(o, ctx)
            "zx_riskreturn" -> ZxRender27.riskreturn(o, ctx)
            "zx_utility"    -> ZxRender27.utility(o, ctx)
            "zx_bandit"     -> ZxRender27.bandit(o, ctx)
            "zx_explain"    -> ZxRender27.explain(o, ctx)
            "zx_cfactual"   -> ZxRender27.cfactual(o, ctx)
            "zx_cforest"    -> ZxRender27.cforest(o, ctx)
            "zx_alloc"      -> ZxRender27.alloc(o, ctx)
            "zx_abmon"      -> ZxRender27.abmon(o, ctx)
            // 第二十八批
            "zx_rollout"     -> ZxRender28.rollout(o, ctx)
            "zx_canary"      -> ZxRender28.canary(o, ctx)
            "zx_slo"         -> ZxRender28.slo(o, ctx)
            "zx_healthcheck" -> ZxRender28.healthcheck(o, ctx)
            "zx_incident"    -> ZxRender28.incident(o, ctx)
            "zx_rca"         -> ZxRender28.rca(o, ctx)
            "zx_regret"      -> ZxRender28.regret(o, ctx)
            "zx_backtest"    -> ZxRender28.backtest(o, ctx)
            "zx_drift"       -> ZxRender28.drift(o, ctx)
            "zx_alert"       -> ZxRender28.alert(o, ctx)
            "zx_costtrack"   -> ZxRender28.costtrack(o, ctx)
            "zx_feedback"    -> ZxRender28.feedback(o, ctx)
            // 第二十九批
            "zx_capacity"    -> ZxRender29.capacity(o, ctx)
            "zx_queue"       -> ZxRender29.queue(o, ctx)
            "zx_loadtest"    -> ZxRender29.loadtest(o, ctx)
            "zx_topology"    -> ZxRender29.topology(o, ctx)
            "zx_changerisk"  -> ZxRender29.changerisk(o, ctx)
            "zx_playbook"    -> ZxRender29.playbook(o, ctx)
            "zx_escalation"  -> ZxRender29.escalation(o, ctx)
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
            else -> Text("未注册组件：${o.optString("type")}",
                         fontSize = 11.sp, color = ZxT.Text3)
        }
    }

    /* ── tabs ───────────────────────────────────────────────── */
    @Composable
    private fun tabs(node: JSONObject, ctx: RenderContext, depth: Int) {
        val items = node.optJSONArray("children") ?: return
        val n = minOf(items.length(), MAX_CHILDREN)
        var sel by remember { mutableIntStateOf(0) }

        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(ZxT.Surface2)
                .padding(3.dp)) {
                for (i in 0 until n) {
                    val c = items.optJSONObject(i) ?: continue
                    Box(Modifier.weight(1f).height(28.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(if (i == sel) ZxT.Primary else Color.Transparent)
                        .clickable { sel = i },
                        contentAlignment = Alignment.Center) {
                        Text(c.optString("tab", "页${i + 1}"), fontSize = 11.5.sp,
                             color = if (i == sel) Color.White else ZxT.Text3,
                             fontWeight = if (i == sel) FontWeight.SemiBold else FontWeight.Normal,
                             maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            // 切页时淡入，避免内容突变
            AnimatedContent(targetState = sel,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "tab") { i ->
                val c = items.optJSONObject(i) ?: JSONObject()
                // 子节点带 id 时回传带上来源 tab，模型知道用户在哪一页操作
                render(c, ctx, depth + 1)
            }
        }
    }

    /* ── accordion：手风琴，可同时展开多项 ──────────────────── */
    @Composable
    private fun accordion(node: JSONObject, ctx: RenderContext, depth: Int) {
        val items = node.optJSONArray("children") ?: return
        val n = minOf(items.length(), MAX_CHILDREN)
        val open = remember { mutableStateListOf<Boolean>().apply { repeat(n) { add(false) } } }

        Column(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val c = items.optJSONObject(i) ?: continue
                val isOpen = open[i]
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, if (isOpen) ZxT.Primary.copy(.5f) else ZxT.Line,
                            RoundedCornerShape(10.dp))) {
                    Row(Modifier.fillMaxWidth()
                        .clickable { open[i] = !isOpen }
                        .padding(10.dp, 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c.optString("tab", "项${i + 1}"), fontSize = 12.5.sp,
                             color = ZxT.Text, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(if (isOpen) "−" else "+", fontSize = 14.sp, color = ZxT.Text3)
                    }
                    AnimatedVisibility(visible = isOpen,
                        enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                        exit  = fadeOut(tween(120)) + shrinkVertically(tween(160))) {
                        Box(Modifier.padding(10.dp, 4.dp, 10.dp, 10.dp)) {
                            render(c, ctx, depth + 1)
                        }
                    }
                }
            }
        }
    }

    /* ── split：并排，窄屏自动退化为上下 ────────────────────── */
    @Composable
    private fun split(node: JSONObject, ctx: RenderContext, depth: Int) {
        val items = node.optJSONArray("children") ?: return
        val n = minOf(items.length(), 2).coerceAtLeast(1)
        val ratio = node.optDouble("ratio", 0.5).toFloat().coerceIn(0.25f, 0.75f)

        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until n) {
                val c = items.optJSONObject(i) ?: continue
                Box(Modifier.weight(if (i == 0) ratio else 1f - ratio)
                    .padding(end = if (i == 0) 6.dp else 0.dp)) {
                    render(c, ctx, depth + 1)
                }
            }
        }
    }

    /* ── carousel：横向翻页 ─────────────────────────────────── */
    @Composable
    private fun carousel(node: JSONObject, ctx: RenderContext, depth: Int) {
        val items = node.optJSONArray("children") ?: return
        val n = minOf(items.length(), MAX_CHILDREN)
        var page by remember { mutableIntStateOf(0) }

        Column(Modifier.fillMaxWidth()) {
            AnimatedContent(targetState = page,
                transitionSpec = {
                    (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally(tween(180)) { -it / 4 } + fadeOut(tween(120)))
                }, label = "car") { p ->
                val c = items.optJSONObject(p) ?: JSONObject()
                render(c, ctx, depth + 1)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until n) {
                    Box(Modifier.size(if (i == page) 12.dp else 6.dp, 6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (i == page) ZxT.Primary else ZxT.Surface3))
                }
                Text("${page + 1}/$n", fontSize = 10.5.sp, color = ZxT.Text3,
                     modifier = Modifier.padding(start = 3.dp))
                Spacer(Modifier.weight(1f))
                Row {
                    Text("‹", fontSize = 17.sp, color = ZxT.Text2,
                         modifier = Modifier.clickable { if (page > 0) page-- }.padding(horizontal = 9.dp))
                    Text("›", fontSize = 17.sp, color = ZxT.Text2,
                         modifier = Modifier.clickable { if (page < n - 1) page++ }.padding(horizontal = 9.dp))
                }
            }
        }
    }

    /* ── 校验：递归检查整棵子树 ──────────────────────────────── */
    fun validate(node: JSONObject, depth: Int = 0): Pair<Boolean, String> {
        if (depth > MAX_DEPTH)
            return false to "嵌套超过 $MAX_DEPTH 层，已拒绝渲染"
        val type = node.optString("type")
        val containers = setOf("zx_tabs", "zx_accordion", "zx_split", "zx_carousel",
                               "zx_grid", "zx_master", "zx_stagger")

        if (type in containers) {
            val kids = node.optJSONArray("children")
                ?: return false to "$type 缺少 children"
            if (kids.length() == 0) return false to "$type 的 children 为空"
            if (kids.length() > MAX_CHILDREN)
                return false to "$type 有 ${kids.length()} 个子节点，上限 $MAX_CHILDREN"
            if (type == "zx_split" && kids.length() != 2)
                return false to "zx_split 必须且只能有 2 个子节点（并排两栏）"
            for (i in 0 until kids.length()) {
                val c = kids.optJSONObject(i)
                    ?: return false to "$type.children[$i] 不是对象"
                if (type != "zx_split" && c.optString("tab").isBlank())
                    return false to "$type.children[$i] 缺少 tab（页签名）"
                val (ok, why) = validate(c, depth + 1)
                if (!ok) return false to "$type.children[$i] → $why"
            }
            return true to ""
        }

        // 叶子：交给既有校验
        val raw = node.toString()
        val check = ZxDsl.validate(type, raw)
        return if (check.ok) true to "" else false to check.reason
    }
}
