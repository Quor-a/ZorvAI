package com.zorv.plugin.zxwidget

import androidx.compose.runtime.Composable
import org.json.JSONObject

/**
 * 注册表初始化 —— 把五批组件一次性登记进 ZxRegistry。
 *
 * 单独一个文件是因为：登记代码是纯数据，跟任何一批的渲染逻辑都无关。
 * 混进 ZxRender 会让渲染文件多出几百行跟渲染无关的东西。
 *
 * 新增组件只改这里一处（+ 对应批的渲染/校验），
 * 总表、校验分发、嵌套渲染、全屏渲染、宿主注册、规格示例全部自动派生。
 */
internal object ZxRegistryInit {

    private fun batch(
        name: String, label: String, cat: ZxRegistry.Category,
        summary: String, minWidth: Int = 0,
        caps: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        surface: (JSONObject) -> Boolean = { false },
        validate: (String, JSONObject) -> Pair<Boolean, String>,
        sample: (String) -> String,
        spec: (String) -> String,
        render: @Composable (JSONObject, RenderContext) -> Unit
    ) = ZxRegistry.Meta(
        name = name, label = label, category = cat, summary = summary,
        spec = spec(name), sample = sample(name),
        validate = { o -> validate(name, o) },
        render = render,
        minWidthDp = minWidth, prefersSurface = surface,
        needsCaps = caps, tags = tags
    )

    @Composable
    fun init() {

        /* ── 第一批 6 个 ───────────────────────────── */
        reg("zx_compare", "对比卡", ZxRegistry.Category.DATA,
            "多方案横向对比，可切维度、点选回传") { o, c -> ZxRender.compare(o, c) }
        reg("zx_form", "表单卡", ZxRegistry.Category.INPUT,
            "分段/滑块/文本/多选，提交打包 payload", minWidth = 260) { o, c -> ZxRender.form(o, c) }
        reg("zx_timeline", "时间轴", ZxRegistry.Category.STATUS,
            "绑 agent.run_state，步骤随执行推进") { o, c -> ZxRender.timeline(o, c) }
        reg("zx_choice", "决策卡", ZxRegistry.Category.INPUT,
            "带唯一推荐项的选择卡", minWidth = 240) { o, c -> ZxRender.choice(o, c) }
        reg("zx_table", "可编辑表格", ZxRegistry.Category.DATA,
            "单元格可改并标记变更态", minWidth = 300) { o, c -> ZxRender2.table(o, c) }
        reg("zx_diff", "代码差异", ZxRegistry.Category.TEXT,
            "逐块接受改动，只回传被接受的序号", minWidth = 320) { o, c -> ZxRender2.diff(o, c) }
        reg("zx_approval", "授权确认", ZxRegistry.Category.STATUS,
            "L3 以上强制二次确认", caps = setOf("approve")) { o, c -> ZxRender2.approval(o, c) }

        /* ── 第二批 8 个 ───────────────────────────── */
        reg("zx_chart", "图表", ZxRegistry.Category.DATA,
            "Canvas 自绘折线/柱/环，不依赖 WebView") { o, c -> ZxRender2.chart(o, c) }
        reg("zx_terminal", "终端卡", ZxRegistry.Category.TEXT,
            "等宽文本输出，适合日志与结构化结果") { o, c -> ZxRender2.terminal(o, c) }
        reg("zx_progress", "进度条", ZxRegistry.Category.STATUS,
            "行内进度，可绑任务状态") { o, c -> ZxRender2.progress(o, c) }

        /* ── 第三批 12 个 ──────────────────────────── */
        reg("zx_metrics", "指标卡组", ZxRegistry.Category.DATA,
            "多指标并列，带环比") { o, c -> ZxRender3.metrics(o, c) }
        reg("zx_gantt", "甘特图", ZxRegistry.Category.DATA,
            "任务排期，超过 6 条建议展开", minWidth = 320) { o, c -> ZxRender3.gantt(o, c) }
        reg("zx_tree", "文件树", ZxRegistry.Category.LAYOUT,
            "可展开目录，点选回传路径") { o, c -> ZxRender3.tree(o, c) }
        reg("zx_flow", "流程图", ZxRegistry.Category.DATA,
            "节点 + 连线，Canvas 画线文字用 Row 排") { o, c -> ZxRender3.flow(o, c) }
        reg("zx_calendar", "日历", ZxRegistry.Category.INPUT,
            "月视图，窄气泡里日期格太小建议展开", minWidth = 300) { o, c -> ZxRender3.calendar(o, c) }
        reg("zx_rating", "评分卡", ZxRegistry.Category.INPUT,
            "星级评分，提交结构化 payload") { o, c -> ZxRender3.rating(o, c) }
        reg("zx_editor", "代码编辑器", ZxRegistry.Category.INPUT,
            "要键盘，气泡里太挤，建议展开", minWidth = 300) { o, c -> ZxRender3.editor(o, c) }
        reg("zx_audio", "音频波形", ZxRegistry.Category.DATA,
            "波形 + 播放位置") { o, c -> ZxRender3.audio(o, c) }
        reg("zx_heatmap", "热力图", ZxRegistry.Category.DATA,
            "靛蓝→青插值，低值不发灰") { o, c -> ZxRender3.heatmap(o, c) }
        reg("zx_toggle", "开关列表", ZxRegistry.Category.INPUT,
            "多项开关，锁定项点不动") { o, c -> ZxRender3.toggle(o, c) }
        reg("zx_qr", "二维码", ZxRegistry.Category.TEXT,
            "占位实现，真实工程应接 zxing") { o, c -> ZxRender3.qr(o, c) }
        reg("zx_stack", "卡片堆栈", ZxRegistry.Category.LAYOUT,
            "多张叠放，滑动切换") { o, c -> ZxRender3.stack(o, c) }

        /* ── 第四批 12 个 ──────────────────────────── */
        reg("zx_radar", "雷达图", ZxRegistry.Category.DATA,
            "多序列能力对比，至少 3 维") { o, c -> ZxRender4.radar(o, c) }
        reg("zx_rank", "排行榜", ZxRegistry.Category.DATA,
            "带条长的排名，前三名特殊配色") { o, c -> ZxRender4.rank(o, c) }
        reg("zx_org", "组织架构", ZxRegistry.Category.LAYOUT,
            "按层级分组，d 必须连续") { o, c -> ZxRender4.org(o, c) }
        reg("zx_sankey", "桑基图", ZxRegistry.Category.DATA,
            "流量流向，v 必须为正") { o, c -> ZxRender4.sankey(o, c) }
        reg("zx_gauge", "仪表盘", ZxRegistry.Category.DATA,
            "单值进度，带目标刻线") { o, c -> ZxRender4.gauge(o, c) }
        reg("zx_range", "范围滑块", ZxRegistry.Category.GESTURE,
            "双端拖拽，谁离得近拖谁") { o, c -> ZxRender4.range(o, c) }
        reg("zx_color", "取色器", ZxRegistry.Category.INPUT,
            "HSV 面板固定 S=0.85") { o, c -> ZxRender4.color(o, c) }
        reg("zx_breadcrumb", "面包屑", ZxRegistry.Category.LAYOUT,
            "层级导航，最多 8 级") { o, c -> ZxRender4.breadcrumb(o, c) }
        reg("zx_pager", "分页器", ZxRegistry.Category.LAYOUT,
            "窄屏只显示当前与两端") { o, c -> ZxRender4.pager(o, c) }
        reg("zx_countdown", "倒计时", ZxRegistry.Category.STATUS,
            "归零自动回传 done") { o, c -> ZxRender4.countdown(o, c) }
        reg("zx_sign", "签名板", ZxRegistry.Category.GESTURE,
            "笔迹存点集不存位图") { o, c -> ZxRender4.sign(o, c) }
        reg("zx_board", "自由画布", ZxRegistry.Category.GESTURE,
            "拖拽元素，坐标 0-1 相对值") { o, c -> ZxRender4.board(o, c) }

        /* ── 第五批 12 个（输入型） ─────────────────── */
        reg("zx_poll", "投票问卷", ZxRegistry.Category.INPUT,
            "选完原地显示分布，v 要么都给要么都不给") { o, c -> ZxRender5.poll(o, c) }
        reg("zx_transfer", "穿梭框", ZxRegistry.Category.INPUT,
            "双栏互移，中间态不回传", minWidth = 300) { o, c -> ZxRender5.transfer(o, c) }
        reg("zx_chips", "标签多选", ZxRegistry.Category.INPUT,
            "可搜索标签，不可重复") { o, c -> ZxRender5.chips(o, c) }
        reg("zx_pin", "口令输入", ZxRegistry.Category.INPUT,
            "自动跳焦，满位回传", minWidth = 240) { o, c -> ZxRender5.pin(o, c) }
        reg("zx_dial", "旋钮", ZxRegistry.Category.GESTURE,
            "旋转手势，135°–405° 量程") { o, c -> ZxRender5.dial(o, c) }
        reg("zx_joystick", "二维摇杆", ZxRegistry.Category.GESTURE,
            "夹到单位圆 + 死区") { o, c -> ZxRender5.joystick(o, c) }
        reg("zx_timepicker", "时间选择", ZxRegistry.Category.INPUT,
            "双列滚轮，step 必须整除 60", minWidth = 260) { o, c -> ZxRender5.timepicker(o, c) }
        reg("zx_slidergroup", "多滑块组", ZxRegistry.Category.GESTURE,
            "总量约束，超出自动摊回") { o, c -> ZxRender5.slidergroup(o, c) }
        reg("zx_sortable", "拖拽排序", ZxRegistry.Category.GESTURE,
            "按住 ≡ 上下拖，松手回传新顺序") { o, c -> ZxRender5.sortable(o, c) }
        reg("zx_cardflip", "翻转卡", ZxRegistry.Category.TEXT,
            "正反面，q/a 都必填") { o, c -> ZxRender5.cardflip(o, c) }
        reg("zx_comment", "内联批注", ZxRegistry.Category.INPUT,
            "可回复可表情，输入框只在发送时回传") { o, c -> ZxRender5.comment(o, c) }
        reg("zx_seek", "进度标记条", ZxRegistry.Category.GESTURE,
            "拖完松手才回传，点章节名可跳") { o, c -> ZxRender5.seek(o, c) }

        /* ── 第六批 12 个 ──────────────────────────── */
        reg("zx_wordcloud", "词云", ZxRegistry.Category.DATA,
            "按权重决定字号，自动避让排布") { o, c -> ZxRender6.wordcloud(o, c) }
        reg("zx_funnel", "漏斗图", ZxRegistry.Category.DATA,
            "逐级转化，层间算流失率") { o, c -> ZxRender6.funnel(o, c) }
        reg("zx_treemap", "树图", ZxRegistry.Category.DATA,
            "面积编码占比，递归切分") { o, c -> ZxRender6.treemap(o, c) }
        reg("zx_chord", "和弦图", ZxRegistry.Category.DATA,
            "两两关系，弧长编码总量") { o, c -> ZxRender6.chord(o, c) }
        reg("zx_sparkline", "迷你趋势", ZxRegistry.Category.DATA,
            "极小体积内嵌趋势线") { o, c -> ZxRender6.sparkline(o, c) }
        reg("zx_kanban", "看板", ZxRegistry.Category.GESTURE,
            "跨列拖拽卡片", minWidth = 360) { o, c -> ZxRender6.kanban(o, c) }
        reg("zx_hotspot", "图片热点", ZxRegistry.Category.INPUT,
            "在图上的相对坐标打点标注", minWidth = 300) { o, c -> ZxRender6.hotspot(o, c) }
        reg("zx_stepper", "步进器", ZxRegistry.Category.INPUT,
            "加减步进，可设上下限与步长") { o, c -> ZxRender6.stepper(o, c) }
        reg("zx_segmented", "分段控件", ZxRegistry.Category.INPUT,
            "2-5 段单选，切换即回传") { o, c -> ZxRender6.segmented(o, c) }
        reg("zx_mention", "@提及输入", ZxRegistry.Category.INPUT,
            "输入 @ 弹出候选，回传结构化提及") { o, c -> ZxRender6.mention(o, c) }
        reg("zx_dropzone", "拖放区", ZxRegistry.Category.GESTURE,
            "接收拖入的文件/文本，校验后才回传") { o, c -> ZxRender6.dropzone(o, c) }
        reg("zx_resizable", "可拖拽分栏", ZxRegistry.Category.LAYOUT,
            "拖动分隔条调比例，有最小宽度保护") { o, c -> ZxRender6.resizable(o, c) }

        /* ── 第七批 12 个（引导 / 识别 / 编排） ────────────── */
        reg("zx_tour", "分步引导", ZxRegistry.Category.TEXT,
            "分步引导，进度点 + 上下步") { o, c -> ZxRender7.tour(o, c) }
        reg("zx_swipe", "滑动操作", ZxRegistry.Category.GESTURE,
            "行内左滑出动作，同时只展开一个") { o, c -> ZxRender7.swipe(o, c) }
        reg("zx_matrix", "交叉表", ZxRegistry.Category.DATA,
            "行列双表头 + 汇总，v 长度须等于列数", minWidth = 320) { o, c -> ZxRender7.matrix(o, c) }
        reg("zx_autocomplete", "自动完成", ZxRegistry.Category.INPUT,
            "输入即过滤，选中才回传", minWidth = 240) { o, c -> ZxRender7.autocomplete(o, c) }
        reg("zx_spin", "转盘", ZxRegistry.Category.GESTURE,
            "旋转手势 + 松手吸附扇区，3-8 扇区") { o, c -> ZxRender7.spin(o, c) }
        reg("zx_barcode", "条形码", ZxRegistry.Category.TEXT,
            "占位实现，条宽由字符码点决定") { o, c -> ZxRender7.barcode(o, c) }
        reg("zx_dualist", "双列差异", ZxRegistry.Category.DATA,
            "左右逐行比对并标差异", minWidth = 300) { o, c -> ZxRender7.dualist(o, c) }
        reg("zx_scrubber", "数值微调", ZxRegistry.Category.GESTURE,
            "增量式横向拖动，落下不跳值") { o, c -> ZxRender7.scrubber(o, c) }
        reg("zx_bento", "Bento 网格", ZxRegistry.Category.LAYOUT,
            "不等宽拼块，大块须成对") { o, c -> ZxRender7.bento(o, c) }
        reg("zx_minmap", "缩略导航", ZxRegistry.Category.LAYOUT,
            "小图定位视口，k ∈ h1/code/img/body") { o, c -> ZxRender7.minmap(o, c) }
        reg("zx_statelist", "状态流", ZxRegistry.Category.STATUS,
            "带连接线的纵向状态链") { o, c -> ZxRender7.statelist(o, c) }
        reg("zx_legend", "图例选择", ZxRegistry.Category.INPUT,
            "点图例开关序列，至少留一个") { o, c -> ZxRender7.legend(o, c) }

        /* ── 第八批 12 个（表单原语 / 状态呈现 / 集合展示） ── */
        reg("zx_radiogroup", "单选组", ZxRegistry.Category.INPUT,
            "带描述的单选，2-6 项") { o, c -> ZxRender8.radiogroup(o, c) }
        reg("zx_checklist", "清单勾选", ZxRegistry.Category.INPUT,
            "带进度与全选，k 不可重复") { o, c -> ZxRender8.checklist(o, c) }
        reg("zx_avatargroup", "头像组", ZxRegistry.Category.LAYOUT,
            "重叠显示，溢出 +N，max 1-6") { o, c -> ZxRender8.avatargroup(o, c) }
        reg("zx_meter", "分段计量条", ZxRegistry.Category.STATUS,
            "等级刻度，v 与刻度须在量程内且递增") { o, c -> ZxRender8.meter(o, c) }
        reg("zx_ring", "环形进度", ZxRegistry.Category.STATUS,
            "v 0-100，读屏念「进度 N%」") { o, c -> ZxRender8.ring(o, c) }
        reg("zx_stat", "统计数字", ZxRegistry.Category.DATA,
            "同比箭头，lowerBetter 时降为绿") { o, c -> ZxRender8.stat(o, c) }
        reg("zx_swatch", "色板选择", ZxRegistry.Category.INPUT,
            "2-12 个，hex 不可重复") { o, c -> ZxRender8.swatch(o, c) }
        reg("zx_notice", "通知条", ZxRegistry.Category.STATUS,
            "lv ∈ info/ok/warn/err，最多 4 条") { o, c -> ZxRender8.notice(o, c) }
        reg("zx_price", "定价卡", ZxRegistry.Category.DATA,
            "2-3 档，rec 最多 1 个", minWidth = 320) { o, c -> ZxRender8.price(o, c) }
        reg("zx_keyvalue", "键值列表", ZxRegistry.Category.TEXT,
            "点行复制，k ≤ 12 字") { o, c -> ZxRender8.keyvalue(o, c) }
        reg("zx_inputmask", "格式化输入", ZxRegistry.Category.INPUT,
            "掩码输入，内置键盘避免错位") { o, c -> ZxRender8.inputmask(o, c) }
        reg("zx_datalist", "可编辑列表", ZxRegistry.Category.INPUT,
            "增删行，不允许重复项") { o, c -> ZxRender8.datalist(o, c) }

        /* ── 第九批 12 个（复合图表 / 结构图 / 复合容器） ── */
        reg("zx_waterfall", "瀑布图", ZxRegistry.Category.DATA,
            "累计增减，必须有增有减") { o, c -> ZxRender9.waterfall(o, c) }
        reg("zx_pyramid", "金字塔图", ZxRegistry.Category.DATA,
            "层级必须递减，2-7 层") { o, c -> ZxRender9.pyramid(o, c) }
        reg("zx_bubble", "气泡图", ZxRegistry.Category.DATA,
            "三变量 x/y/r，2-14 点") { o, c -> ZxRender9.bubble(o, c) }
        reg("zx_venn", "韦恩图", ZxRegistry.Category.DATA,
            "2-3 组，回答交集问题") { o, c -> ZxRender9.venn(o, c) }
        reg("zx_candle", "K线图", ZxRegistry.Category.DATA,
            "h/l/o/c，实体须在影线内", minWidth = 300) { o, c -> ZxRender9.candle(o, c) }
        reg("zx_dag", "依赖有向图", ZxRegistry.Category.STATUS,
            "分层排布，边不可自环") { o, c -> ZxRender9.dag(o, c) }
        reg("zx_swimlane", "泳道图", ZxRegistry.Category.STATUS,
            "跨角色分带，各道格数须一致") { o, c -> ZxRender9.swimlane(o, c) }
        reg("zx_mindmap", "思维导图", ZxRegistry.Category.LAYOUT,
            "主枝可折叠，≤6 枝") { o, c -> ZxRender9.mindmap(o, c) }
        reg("zx_gallery", "图片画廊", ZxRegistry.Category.LAYOUT,
            "3 列固定，最多 9 张") { o, c -> ZxRender9.gallery(o, c) }
        reg("zx_wizard", "分步表单", ZxRegistry.Category.INPUT,
            "收集而非引导，每步出值") { o, c -> ZxRender9.wizard(o, c) }
        reg("zx_spreadsheet", "可编辑网格", ZxRegistry.Category.INPUT,
            "单元格可改并标黄", minWidth = 320) { o, c -> ZxRender9.spreadsheet(o, c) }
        reg("zx_conversation", "模拟对话", ZxRegistry.Category.TEXT,
            "展示示例对话，who ∈ me/them") { o, c -> ZxRender9.conversation(o, c) }

        /* ── 第十批 11 个（高信息密度 / 专用输入 / 装饰性呈现） ── */
        reg("zx_calheat", "日历热力", ZxRegistry.Category.DATA,
            "≤91 格（一季度），格宽有下限") { o, c -> ZxRender10.calheat(o, c) }
        reg("zx_matrixheat", "矩阵热力", ZxRegistry.Category.DATA,
            "≤8×7，v 长度须等于列数") { o, c -> ZxRender10.matrixheat(o, c) }
        reg("zx_polar", "极坐标图", ZxRegistry.Category.DATA,
            "角度+半径，r 须有变化量") { o, c -> ZxRender10.polar(o, c) }
        reg("zx_voice", "语音输入条", ZxRegistry.Category.INPUT,
            "波形条数固定 28", minWidth = 300) { o, c -> ZxRender10.voice(o, c) }
        reg("zx_stars", "评分星级", ZxRegistry.Category.INPUT,
            "max 3-10，labels 须等于 max") { o, c -> ZxRender10.stars(o, c) }
        reg("zx_react", "表情反馈", ZxRegistry.Category.INPUT,
            "2-6 个，k/e 必填") { o, c -> ZxRender10.react(o, c) }
        reg("zx_scale", "颜色刻度", ZxRegistry.Category.INPUT,
            "色阶滑条，指针随值取色") { o, c -> ZxRender10.scale(o, c) }
        reg("zx_tags", "标签输入", ZxRegistry.Category.INPUT,
            "max 1-12，不可重复") { o, c -> ZxRender10.tags(o, c) }
        reg("zx_flipnum", "翻页数字", ZxRegistry.Category.TEXT,
            "每位一格，v ≤ 4 位") { o, c -> ZxRender10.flipnum(o, c) }
        reg("zx_skeleton", "加载骨架", ZxRegistry.Category.STATUS,
            "rows 1-6，呼吸由动效层驱动") { o, c -> ZxRender10.skeleton(o, c) }
        reg("zx_badges", "成就徽章", ZxRegistry.Category.STATUS,
            "≤8 个，至少一个 got=true") { o, c -> ZxRender10.badges(o, c) }

        /* ── 第十一批 12 个（富内容 / 空间布局 / 系统级界面） ── */
        reg("zx_rich", "富文本", ZxRegistry.Category.TEXT,
            "t ∈ h1/h2/li/num/code/hr/p，行高 1.68") { o, c -> ZxRender11.rich(o, c) }
        reg("zx_quote", "引用块", ZxRegistry.Category.TEXT,
            "v + src?，竖条为唯一标识") { o, c -> ZxRender11.quote(o, c) }
        reg("zx_filecard", "文件卡片", ZxRegistry.Category.TEXT,
            "≤6 个，k 带后缀决定图标色") { o, c -> ZxRender11.filecard(o, c) }
        reg("zx_callout", "提示块", ZxRegistry.Category.TEXT,
            "kind ∈ info/warn/err/ok") { o, c -> ZxRender11.callout(o, c) }
        reg("zx_hint", "提示气泡", ZxRegistry.Category.TEXT,
            "可折叠，三角指示指向") { o, c -> ZxRender11.hint(o, c) }
        reg("zx_seat", "座位图", ZxRegistry.Category.LAYOUT,
            "≤8×10，不可全 sold") { o, c -> ZxRender11.seat(o, c) }
        reg("zx_floor", "平面图", ZxRegistry.Category.LAYOUT,
            "0-1 相对坐标，不可越界或重叠", minWidth = 320) { o, c -> ZxRender11.floor(o, c) }
        reg("zx_banner", "横幅", ZxRegistry.Category.LAYOUT,
            "n + e? + d? + btn? + hex?") { o, c -> ZxRender11.banner(o, c) }
        reg("zx_divider", "分隔线", ZxRegistry.Category.LAYOUT,
            "标签两侧的线") { o, c -> ZxRender11.divider(o, c) }
        reg("zx_settings", "设置面板", ZxRegistry.Category.INPUT,
            "switch/pick/text，pick 的 options 2-3 项") { o, c -> ZxRender11.settings(o, c) }
        reg("zx_command", "命令面板", ZxRegistry.Category.INPUT,
            "≤8 条，输入即过滤") { o, c -> ZxRender11.command(o, c) }
        reg("zx_empty", "空状态", ZxRegistry.Category.STATUS,
            "d 或 btn 至少其一") { o, c -> ZxRender11.empty(o, c) }

        /* ── 第十二批 12 个（组织 / 流向 / 导航 / 采集） ── */
        reg("zx_orgchart", "组织架构", ZxRegistry.Category.LAYOUT,
            "root{k,n,r?,kids[]}，≤24 节点") { o, c -> ZxRender12.orgchart(o, c) }
        reg("zx_sankey", "桑基图", ZxRegistry.Category.DATA,
            "flows[{s,d,v>0}]，2-7 条") { o, c -> ZxRender12.sankey(o, c) }
        reg("zx_gauge", "仪表盘", ZxRegistry.Category.DATA,
            "min/max/v + zones?") { o, c -> ZxRender12.gauge(o, c) }
        reg("zx_sparkgrid", "迷你图网格", ZxRegistry.Category.DATA,
            "items[{n,pts[]}]，≤6 个") { o, c -> ZxRender12.sparkgrid(o, c) }
        reg("zx_breadcrumb", "面包屑", ZxRegistry.Category.LAYOUT,
            "items[] 2-6 + cur 须在范围") { o, c -> ZxRender12.breadcrumb(o, c) }
        reg("zx_pagination", "分页器", ZxRegistry.Category.LAYOUT,
            "total + cur(1..total)") { o, c -> ZxRender12.pagination(o, c) }
        reg("zx_tabbar", "底部标签栏", ZxRegistry.Category.LAYOUT,
            "items[{k,n,e?}]，2-5 个") { o, c -> ZxRender12.tabbar(o, c) }
        reg("zx_searchbar", "搜索栏", ZxRegistry.Category.INPUT,
            "回车才回传，输入过程不回传") { o, c -> ZxRender12.searchbar(o, c) }
        reg("zx_filterchips", "筛选芯片组", ZxRegistry.Category.INPUT,
            "items[] 2-10 + multi?") { o, c -> ZxRender12.chips(o, c) }
        reg("zx_colorpick", "取色板", ZxRegistry.Category.INPUT,
            "swatches?[]，≤12 个") { o, c -> ZxRender12.colorpick(o, c) }
        reg("zx_stepper", "数字步进器", ZxRegistry.Category.INPUT,
            "v 须落在步长刻度上") { o, c -> ZxRender12.stepper(o, c) }
        reg("zx_treeview", "层级树", ZxRegistry.Category.LAYOUT,
            "带复选，父级半选显示 ─") { o, c -> ZxRender12.treeview(o, c) }

        /* ── 第十三批 12 个（对照 / 诊断 / 协同） ── */
        reg("zx_diffview", "并排差异", ZxRegistry.Category.TEXT,
            "≤14 行，至少 1 处改动，行号必填") { o, c -> ZxRender13.diffview(o, c) }
        reg("zx_baseline", "基线对比", ZxRegistry.Category.DATA,
            "基线/当前/目标三条，lowerBetter 反色") { o, c -> ZxRender13.baseline(o, c) }
        reg("zx_logview", "日志流", ZxRegistry.Category.TEXT,
            "lv 必填否则过滤失效，≤30 条") { o, c -> ZxRender13.logview(o, c) }
        reg("zx_console", "交互式控制台", ZxRegistry.Category.INPUT,
            "带历史，↑ 取上一条") { o, c -> ZxRender13.console(o, c) }
        reg("zx_depgraph", "依赖关系图", ZxRegistry.Category.LAYOUT,
            "≤10 节点，高亮传递上游") { o, c -> ZxRender13.depgraph(o, c) }
        reg("zx_datamap", "数据地图", ZxRegistry.Category.DATA,
            "≤12 区域，色阶不可全 0") { o, c -> ZxRender13.datamap(o, c) }
        reg("zx_conflict", "冲突解决卡", ZxRegistry.Category.INPUT,
            "三组选项：本地/远端/手工") { o, c -> ZxRender13.conflict(o, c) }
        reg("zx_activity", "活动流", ZxRegistry.Category.TEXT,
            "who 必填，≤10 条") { o, c -> ZxRender13.activity(o, c) }
        reg("zx_timerange", "时间区间", ZxRegistry.Category.GESTURE,
            "a<b，支持整段平移") { o, c -> ZxRender13.timerange(o, c) }
        reg("zx_gridform", "栅格表单", ZxRegistry.Category.INPUT,
            "cols 1-3，k 不可重复") { o, c -> ZxRender13.gridform(o, c) }
        reg("zx_scorecard", "评分卡", ZxRegistry.Category.INPUT,
            "多维度打分，权重和不可为 0") { o, c -> ZxRender13.scorecard(o, c) }
        reg("zx_waveedit", "波形编辑", ZxRegistry.Category.GESTURE,
            "点两下定选区，可静音") { o, c -> ZxRender13.waveedit(o, c) }

        /* ── 第十四批 12 个（分布 / 富媒体 / 产出） ── */
        reg("zx_histogram", "直方图", ZxRegistry.Category.DATA,
            "分布，bins 的 to 须递增不重叠") { o, c -> ZxRender14.histogram(o, c) }
        reg("zx_boxplot", "箱线图", ZxRegistry.Category.DATA,
            "分布对比，q1≤q2≤q3，须线不可画反") { o, c -> ZxRender14.boxplot(o, c) }
        reg("zx_pareto", "帕累托图", ZxRegistry.Category.DATA,
            "二八分析，items 必须降序") { o, c -> ZxRender14.pareto(o, c) }
        reg("zx_control", "控制图", ZxRegistry.Category.DATA,
            "SPC，lcl<ucl，超界点标红") { o, c -> ZxRender14.control(o, c) }
        reg("zx_sunburst", "旭日图", ZxRegistry.Category.DATA,
            "层级占比，≤3 层，外层合计不可超内层") { o, c -> ZxRender14.sunburst(o, c) }
        reg("zx_video", "视频播放器", ZxRegistry.Category.TEXT,
            "进度 + 倍速 + 章节标记") { o, c -> ZxRender14.video(o, c) }
        reg("zx_docview", "文档预览", ZxRegistry.Category.TEXT,
            "翻页 + 缩放，at 须在 1..total") { o, c -> ZxRender14.docview(o, c) }
        reg("zx_model3d", "模型查看器", ZxRegistry.Category.GESTURE,
            "旋转查看，pitch 限 ±90°") { o, c -> ZxRender14.model3d(o, c) }
        reg("zx_schedule", "排班表", ZxRegistry.Category.LAYOUT,
            "天 2-7 × 时段 2-5") { o, c -> ZxRender14.schedule(o, c) }
        reg("zx_nps", "NPS 条", ZxRegistry.Category.DATA,
            "三段之和须等于 total") { o, c -> ZxRender14.nps(o, c) }
        reg("zx_export", "导出卡", ZxRegistry.Category.INPUT,
            "fmts ≤5，未选格式时按钮禁用") { o, c -> ZxRender14.export(o, c) }
        reg("zx_bargauge", "条形仪表", ZxRegistry.Category.DATA,
            "带目标刻线，v 不可超量程") { o, c -> ZxRender14.bargauge(o, c) }

        /* ── 第十五批 12 个（关系 / 文本 / 小众图表） ── */
        reg("zx_route", "路径图", ZxRegistry.Category.LAYOUT,
            "竖向站点轨道，2-8 站，换乘标黄") { o, c -> ZxRender15.route(o, c) }
        reg("zx_network", "关系网络", ZxRegistry.Category.LAYOUT,
            "力导向布局，3-12 节点，无孤立点") { o, c -> ZxRender15.network(o, c) }
        reg("zx_annotate", "文本批注", ZxRegistry.Category.TEXT,
            "在原文上挂意见，s<e 且区间不重叠") { o, c -> ZxRender15.annotate(o, c) }
        reg("zx_revision", "修订标记", ZxRegistry.Category.TEXT,
            "增删改逐条接受，mod 的 old/new 不可相同") { o, c -> ZxRender15.revision(o, c) }
        reg("zx_bullet", "子弹图", ZxRegistry.Category.DATA,
            "实际/目标/量程三线，bad<ok≤max") { o, c -> ZxRender15.bullet(o, c) }
        reg("zx_icicle", "冰柱图", ZxRegistry.Category.LAYOUT,
            "宽度即占比，≤4 层，子节点和不可超父") { o, c -> ZxRender15.icicle(o, c) }
        reg("zx_cohort", "队列留存", ZxRegistry.Category.DATA,
            "留存矩阵，带平均行") { o, c -> ZxRender15.cohort(o, c) }
        reg("zx_slope", "斜率图", ZxRegistry.Category.DATA,
            "只两个时间点时比折线图清楚") { o, c -> ZxRender15.slope(o, c) }
        reg("zx_dumbbell", "哑铃图", ZxRegistry.Category.DATA,
            "前后对比，差值一目了然") { o, c -> ZxRender15.dumbbell(o, c) }
        reg("zx_waffle", "华夫饼", ZxRegistry.Category.DATA,
            "10×N 格子，per 标明每格代表多少") { o, c -> ZxRender15.waffle(o, c) }
        reg("zx_ridgeline", "山脊线", ZxRegistry.Category.DATA,
            "多分布叠看迁移，各行点数须一致") { o, c -> ZxRender15.ridgeline(o, c) }
        reg("zx_steparea", "阶梯面积", ZxRegistry.Category.DATA,
            "阶梯而非斜线，值非负") { o, c -> ZxRender15.steparea(o, c) }

        /* ── 第十六批 12 个（时间 / 对比 / 状态机） ── */
        reg("zx_weekgrid", "周网格", ZxRegistry.Category.LAYOUT,
            "营业时段/值班表，天 2-7 × 时段 2-6") { o, c -> ZxRender16.weekgrid(o, c) }
        reg("zx_pulse", "脉冲图", ZxRegistry.Category.DATA,
            "时间强度，高于均值染深") { o, c -> ZxRender16.pulse(o, c) }
        reg("zx_scoreboard", "打分组", ZxRegistry.Category.DATA,
            "多方案加权打分，给总分和胜出") { o, c -> ZxRender16.scoreboard(o, c) }
        reg("zx_abtest", "A/B 对比", ZxRegistry.Category.DATA,
            "2-4 变体，提升相对首个算") { o, c -> ZxRender16.abtest(o, c) }
        reg("zx_statemachine", "状态机", ZxRegistry.Category.STATUS,
            "当前状态 + 可迁移 + 走过路径") { o, c -> ZxRender16.statemachine(o, c) }
        reg("zx_checkflow", "检查清单", ZxRegistry.Category.INPUT,
            "带前置依赖，前置未完成拦住") { o, c -> ZxRender16.checkflow(o, c) }
        reg("zx_branch", "分支对比图", ZxRegistry.Category.LAYOUT,
            "commit/branch/merge 点阵") { o, c -> ZxRender16.branch(o, c) }
        reg("zx_sla", "SLA 追踪", ZxRegistry.Category.STATUS,
            "预算刻线在 100%，超出染红") { o, c -> ZxRender16.sla(o, c) }
        reg("zx_repost", "转发卡", ZxRegistry.Category.TEXT,
            "带引用块，可点赞转发") { o, c -> ZxRender16.repost(o, c) }
        reg("zx_thread", "话题串", ZxRegistry.Category.TEXT,
            "缩进回复，可折叠，不跳级") { o, c -> ZxRender16.thread(o, c) }
        reg("zx_splitflap", "翻页牌", ZxRegistry.Category.DATA,
            "一位一牌，带中缝") { o, c -> ZxRender16.splitflap(o, c) }
        reg("zx_runbook", "操作手册", ZxRegistry.Category.INPUT,
            "分步执行，危险步强制二次确认") { o, c -> ZxRender16.runbook(o, c) }

        /* ── 第十七批 12 个（空间 / 符号 / 动效） ── */
        reg("zx_geo", "地理点阵", ZxRegistry.Category.LAYOUT,
            "经纬度投影，2-24 点，坐标不可全同") { o, c -> ZxRender17.geo(o, c) }
        reg("zx_formula", "公式", ZxRegistry.Category.TEXT,
            "结构化表达式 frac/sqrt/pow，≤4 层") { o, c -> ZxRender17.formula(o, c) }
        reg("zx_tokens", "代码 token 流", ZxRegistry.Category.TEXT,
            "8 类着色，≤40 个，可点选") { o, c -> ZxRender17.tokens(o, c) }
        reg("zx_ast", "语法树", ZxRegistry.Category.LAYOUT,
            "按节点种类着色，≤4 层，可折叠") { o, c -> ZxRender17.ast(o, c) }
        reg("zx_hexgrid", "六边形网格", ZxRegistry.Category.LAYOUT,
            "奇数行右移半格，≤18 格") { o, c -> ZxRender17.hexgrid(o, c) }
        reg("zx_spiral", "螺旋时间轴", ZxRegistry.Category.DATA,
            "由内向外推进，4-24 点") { o, c -> ZxRender17.spiral(o, c) }
        reg("zx_marquee", "跑马灯", ZxRegistry.Category.TEXT,
            "2-8 条，可暂停") { o, c -> ZxRender17.marquee(o, c) }
        reg("zx_odometer", "数字滚动", ZxRegistry.Category.DATA,
            "可跳到终值，dur=0 即静态") { o, c -> ZxRender17.odometer(o, c) }
        reg("zx_radial", "径向菜单", ZxRegistry.Category.INPUT,
            "2-8 扇区，扇区间留 3° 间隙") { o, c -> ZxRender17.radial(o, c) }
        reg("zx_isometric", "等距视图", ZxRegistry.Category.LAYOUT,
            "2.5D 方块，按 x+y 排序，位置不可重复") { o, c -> ZxRender17.isometric(o, c) }
        reg("zx_spectrum", "频谱", ZxRegistry.Category.DATA,
            "4-32 频段，可冻结，回传静态值") { o, c -> ZxRender17.spectrum(o, c) }
        reg("zx_bezier", "曲线编辑器", ZxRegistry.Category.INPUT,
            "拖控制点，x 夹在 0-1，y 可回弹") { o, c -> ZxRender17.bezier(o, c) }

        /* ── 第十八批 12 个（编辑 / 映射 / 度量） ── */
        reg("zx_flowedit", "流程图编辑器", ZxRegistry.Category.INPUT,
            "拖节点排版，2-10 节点，松手才回传") { o, c -> ZxRender18.flowedit(o, c) }
        reg("zx_crop", "裁剪框", ZxRegistry.Category.INPUT,
            "框选区 + 三分线 + 比例锁定，实时显示尺寸") { o, c -> ZxRender18.crop(o, c) }
        reg("zx_mapping", "字段映射", ZxRegistry.Category.INPUT,
            "左选右配对，未配置用小圆点标出") { o, c -> ZxRender18.mapping(o, c) }
        reg("zx_ratio", "比率条", ZxRegistry.Category.DATA,
            "两个量的比，可带目标线") { o, c -> ZxRender18.ratio(o, c) }
        reg("zx_deviation", "偏差分布", ZxRegistry.Category.DATA,
            "实际 vs 目标，左红右黄，零线在中") { o, c -> ZxRender18.deviation(o, c) }
        reg("zx_timeline2", "双轨时间轴", ZxRegistry.Category.DATA,
            "计划 vs 实际，≤6 行") { o, c -> ZxRender18.timeline2(o, c) }
        reg("zx_scale2", "天平", ZxRegistry.Category.DATA,
            "两个量的轻重，倾角 ±12° 上限") { o, c -> ZxRender18.scale2(o, c) }
        reg("zx_mirror", "镜像对比", ZxRegistry.Category.DATA,
            "背对背条形，一眼看出哪项差最多") { o, c -> ZxRender18.mirror(o, c) }
        reg("zx_dial2", "刻度盘", ZxRegistry.Category.DATA,
            "半圆刻度 + 区间带 + 指针") { o, c -> ZxRender18.dial2(o, c) }
        reg("zx_matrix2", "矩阵选择", ZxRegistry.Category.INPUT,
            "行×列勾选，one/row 两种模式") { o, c -> ZxRender18.matrix2(o, c) }
        reg("zx_ladder", "阶梯排序", ZxRegistry.Category.INPUT,
            "可在界面内上移重排，不用打字") { o, c -> ZxRender18.ladder(o, c) }
        reg("zx_slot", "时间段排布", ZxRegistry.Category.INPUT,
            "拖色块改时间，松手才回传") { o, c -> ZxRender18.slot(o, c) }

        /* ── 第十九批 12 个（序列 / 结构 / 证据） ── */
        reg("zx_traceback", "回溯路径", ZxRegistry.Category.LAYOUT,
            "分层排布，标出分叉点，2-12 节点无环") { o, c -> ZxRender19.traceback(o, c) }
        reg("zx_seq", "时序图", ZxRegistry.Category.LAYOUT,
            "2-5 参与者，等待用虚线标出") { o, c -> ZxRender19.seq(o, c) }
        reg("zx_explode", "爆炸视图", ZxRegistry.Category.LAYOUT,
            "部件散开，可合拢，2-8 个") { o, c -> ZxRender19.explode(o, c) }
        reg("zx_critical", "关键路径", ZxRegistry.Category.DATA,
            "CPM 算浮动时间，标出哪条不能拖") { o, c -> ZxRender19.critical(o, c) }
        reg("zx_evidence", "证据链", ZxRegistry.Category.TEXT,
            "事实/推断/引用/猜测四类，必须有 fact") { o, c -> ZxRender19.evidence(o, c) }
        reg("zx_confront", "证据对质", ZxRegistry.Category.INPUT,
            "两侧并排，四种采信，两侧说法不可相同") { o, c -> ZxRender19.confront(o, c) }
        reg("zx_matrix3", "影响矩阵", ZxRegistry.Category.DATA,
            "四象限给结论：重点做/排期做/顺手做/别做") { o, c -> ZxRender19.matrix3(o, c) }
        reg("zx_receipt", "回执凭据", ZxRegistry.Category.TEXT,
            "单号可一键复制，st ∈ ok/warn/err") { o, c -> ZxRender19.receipt(o, c) }
        reg("zx_ledger", "流水账", ZxRegistry.Category.DATA,
            "余额连续累加 + 迷你走势线") { o, c -> ZxRender19.ledger(o, c) }
        reg("zx_gauntlet", "多轮筛选", ZxRegistry.Category.DATA,
            "每轮淘汰多少，cnt 单调不增") { o, c -> ZxRender19.gauntlet(o, c) }
        reg("zx_chain", "依赖链", ZxRegistry.Category.DATA,
            "整链强度 = 最弱一节，标出瓶颈") { o, c -> ZxRender19.chain(o, c) }
        reg("zx_bracket", "淘汰对阵", ZxRegistry.Category.LAYOUT,
            "两两淘汰，≤4 轮，场次递减") { o, c -> ZxRender19.bracket(o, c) }

        /* ── 第二十批 12 个（密度 / 分布 / 编码） ── */
        reg("zx_contour", "等高线", ZxRegistry.Category.DATA,
            "marching squares 等值线，值不可全同") { o, c -> ZxRender20.contour(o, c) }
        reg("zx_stream", "流图", ZxRegistry.Category.DATA,
            "居中基线堆叠，能看出此消彼长") { o, c -> ZxRender20.stream(o, c) }
        reg("zx_horizon", "地平线图", ZxRegistry.Category.DATA,
            "长时序折叠成 2-5 层，密度提升 3-5 倍") { o, c -> ZxRender20.horizon(o, c) }
        reg("zx_parallel", "平行坐标", ZxRegistry.Category.DATA,
            "2-6 轴看高维相关性，平行=正相关") { o, c -> ZxRender20.parallel(o, c) }
        reg("zx_beeswarm", "蜂群图", ZxRegistry.Category.DATA,
            "看到每一个个体，outliers 是谁一目了然") { o, c -> ZxRender20.beeswarm(o, c) }
        reg("zx_violin", "小提琴图", ZxRegistry.Category.DATA,
            "看出是不是双峰，boxplot 看不出来") { o, c -> ZxRender20.violin(o, c) }
        reg("zx_glyph", "字形网格", ZxRegistry.Category.DATA,
            "3-5 维星形小倍图，一次比 24 个") { o, c -> ZxRender20.glyph(o, c) }
        reg("zx_arc", "弧形图", ZxRegistry.Category.LAYOUT,
            "节点排一线、连线画弧，看远距离关联") { o, c -> ZxRender20.arc(o, c) }
        reg("zx_mosaic", "马赛克图", ZxRegistry.Category.DATA,
            "面积表边际、颜色表残差") { o, c -> ZxRender20.mosaic(o, c) }
        reg("zx_trail", "轨迹图", ZxRegistry.Category.DATA,
            "越亮越晚，能看出在哪停留最久") { o, c -> ZxRender20.trail(o, c) }
        reg("zx_pictogram", "象形图", ZxRegistry.Category.DATA,
            "1 格 = N 个，统计变成看得见的个体") { o, c -> ZxRender20.pictogram(o, c) }
        reg("zx_dotstrip", "单位点图", ZxRegistry.Category.DATA,
            "用可数的点，小差别一眼数出来") { o, c -> ZxRender20.dotstrip(o, c) }

        /* ── 第二十一批 12 个（对齐 / 差异 / 编排） ── */
        reg("zx_align", "序列对齐", ZxRegistry.Category.DATA,
            "2-4 条，长度可不一，先对齐再比") { o, c -> ZxRender21.align(o, c) }
        reg("zx_jsondiff", "结构化差异", ZxRegistry.Category.TEXT,
            "按字段路径比配置，可筛增/删/改") { o, c -> ZxRender21.jsondiff(o, c) }
        reg("zx_threeway", "三方比对", ZxRegistry.Category.TEXT,
            "基准+两边，三者都不同才是真冲突") { o, c -> ZxRender21.threeway(o, c) }
        reg("zx_sankey2", "能量桑基", ZxRegistry.Category.DATA,
            "带损耗，红色带是丢掉的部分") { o, c -> ZxRender21.sankey2(o, c) }
        reg("zx_split", "可分割面板", ZxRegistry.Category.LAYOUT,
            "用户自己拖比例，min<max") { o, c -> ZxRender21.split(o, c) }
        reg("zx_switch", "条件容器", ZxRegistry.Category.LAYOUT,
            "按条件显示分支，必须有 default") { o, c -> ZxRender21.switch(o, c) }
        reg("zx_lane", "泳道时间线", ZxRegistry.Category.LAYOUT,
            "离散格子排期，冲突格标红") { o, c -> ZxRender21.lane(o, c) }
        reg("zx_bullet2", "子弹图组", ZxRegistry.Category.DATA,
            "一组达成条，目标线实心区分") { o, c -> ZxRender21.bullet2(o, c) }
        reg("zx_ring2", "同心环占比", ZxRegistry.Category.DATA,
            "每层一个维度，看各层构成差异") { o, c -> ZxRender21.ring2(o, c) }
        reg("zx_stepflow", "分步流程", ZxRegistry.Category.LAYOUT,
            "带状态，卡在哪一步一眼看出") { o, c -> ZxRender21.stepflow(o, c) }
        reg("zx_annotate", "自由标注", ZxRegistry.Category.INPUT,
            "框/箭头/线/点四类，坐标 0-1") { o, c -> ZxRender21.annotate(o, c) }
        reg("zx_dial3", "多表盘组", ZxRegistry.Category.DATA,
            "同时看 4 个指标，警戒刻度必画") { o, c -> ZxRender21.dial3(o, c) }

        /* ── 第二十二批 12 个（追溯 / 关系强度 / 定位） ── */
        reg("zx_cmdhist", "命令历史", ZxRegistry.Category.TEXT,
            "每条带时间戳+耗时+退出码，能重跑") { o, c -> ZxRender22.cmdhist(o, c) }
        reg("zx_callstack", "调用栈", ZxRegistry.Category.TEXT,
            "栈顶标红，每层带文件行号") { o, c -> ZxRender22.callstack(o, c) }
        reg("zx_uptime", "可用性条", ZxRegistry.Category.DATA,
            "每格一个时间片且能点开") { o, c -> ZxRender22.uptime(o, c) }
        reg("zx_playback", "回放控制器", ZxRegistry.Category.INPUT,
            "带帧号和倍速，标记点不越界") { o, c -> ZxRender22.playback(o, c) }
        reg("zx_pipe", "流水线吞吐", ZxRegistry.Category.DATA,
            "最窄段即瓶颈，橙色标出") { o, c -> ZxRender22.pipe(o, c) }
        reg("zx_corr", "相关性矩阵", ZxRegistry.Category.DATA,
            "正负两色，须方阵对称") { o, c -> ZxRender22.corr(o, c) }
        reg("zx_radar2", "多对象雷达", ZxRegistry.Category.DATA,
            "≤4 条叠同组轴，图例可关") { o, c -> ZxRender22.radar2(o, c) }
        reg("zx_choro", "区域着色", ZxRegistry.Category.DATA,
            "按数值深浅，低值不发灰") { o, c -> ZxRender22.choro(o, c) }
        reg("zx_toc", "目录导航", ZxRegistry.Category.LAYOUT,
            "当前项必标，否则只是列表") { o, c -> ZxRender22.toc(o, c) }
        reg("zx_glossary", "术语表", ZxRegistry.Category.TEXT,
            "点开看定义，缺定义则失去意义") { o, c -> ZxRender22.glossary(o, c) }
        reg("zx_survey", "问卷", ZxRegistry.Category.INPUT,
            "一屏一题，必答未答拦在提交") { o, c -> ZxRender22.survey(o, c) }
        reg("zx_punch", "打点卡", ZxRegistry.Category.DATA,
            "星期×小时双轴，零次也画底色") { o, c -> ZxRender22.punch(o, c) }

        /* ── 第二十三批 12 个（推演 / 规则操纵） ── */
        reg("zx_decision", "决策树", ZxRegistry.Category.LAYOUT,
            "规则本身 + 沿路径求值，叶子必有结果") { o, c -> ZxRender23.decision(o, c) }
        reg("zx_tradeoff", "权衡卡", ZxRegistry.Category.INPUT,
            "两个目标互相打架，Pareto 前沿可见") { o, c -> ZxRender23.tradeoff(o, c) }
        reg("zx_fallback", "降级链", ZxRegistry.Category.TEXT,
            "主→备策略链，末级必标终点") { o, c -> ZxRender23.fallback(o, c) }
        reg("zx_retry", "重试策略", ZxRegistry.Category.DATA,
            "退避曲线可见，factor≥1、cap≥base") { o, c -> ZxRender23.retry(o, c) }
        reg("zx_brush", "区间刷选", ZxRegistry.Category.INPUT,
            "在有数据的图上拖区间，未选中画暗色") { o, c -> ZxRender23.brush(o, c) }
        reg("zx_blast", "影响波及", ZxRegistry.Category.LAYOUT,
            "从「改谁」找「谁受害」，区分直接与间接") { o, c -> ZxRender23.blast(o, c) }
        reg("zx_batch", "批量操作", ZxRegistry.Category.INPUT,
            "选中 N 项执行同一动作，逐项给结果") { o, c -> ZxRender23.batch(o, c) }
        reg("zx_priority", "优先级象限", ZxRegistry.Category.DATA,
            "两维分四格且可挪，阈值须在 0-100 内") { o, c -> ZxRender23.priority(o, c) }
        reg("zx_probe", "探针卡", ZxRegistry.Category.DATA,
            "此刻对 N 个端点各探一次，有耗时条") { o, c -> ZxRender23.probe(o, c) }
        reg("zx_inspect", "字段检查器", ZxRegistry.Category.TEXT,
            "每个字段标来源，来源不明的值不该当真") { o, c -> ZxRender23.inspect(o, c) }
        reg("zx_remap", "值重映射", ZxRegistry.Category.INPUT,
            "a→b 替换规则，成环必须拦") { o, c -> ZxRender23.remap(o, c) }
        reg("zx_rollup", "汇总树", ZxRegistry.Category.DATA,
            "缩进表 + 父值=子和，不等则标红") { o, c -> ZxRender23.rollup(o, c) }

        /* ── 第二十四批 12 个（统计推断 / 一致性） ── */
        reg("zx_qq",       "QQ 图", ZxRegistry.Category.DATA,
            "正态性：画 95% 偏离带，带外标红") { o, c -> ZxRender24.qq(o, c) }
        reg("zx_forest",   "森林图", ZxRegistry.Category.DATA,
            "多研究效应量逆方差合并 + 合并菱形") { o, c -> ZxRender24.forest(o, c) }
        reg("zx_bland",    "一致性", ZxRegistry.Category.DATA,
            "Bland-Altman：95% 界限，高相关≠一致") { o, c -> ZxRender24.bland(o, c) }
        reg("zx_survival", "生存曲线", ZxRegistry.Category.DATA,
            "Kaplan-Meier 阶梯，截尾处标竖线") { o, c -> ZxRender24.survival(o, c) }
        reg("zx_ecdf",     "累积分布", ZxRegistry.Category.DATA,
            "直接读出「多少比例低于 X」") { o, c -> ZxRender24.ecdf(o, c) }
        reg("zx_lorenz",   "集中度", ZxRegistry.Category.DATA,
            "洛伦兹 + 基尼，画出不平等面积") { o, c -> ZxRender24.lorenz(o, c) }
        reg("zx_regress",  "回归", ZxRegistry.Category.DATA,
            "斜率截距 + R² + 残差图") { o, c -> ZxRender24.regress(o, c) }
        reg("zx_scatter",  "散点", ZxRegistry.Category.DATA,
            "x-y + 分组着色，看趋势与离群") { o, c -> ZxRender24.scatter(o, c) }
        reg("zx_seqdiff",  "序列对比", ZxRegistry.Category.TEXT,
            "两段序列逐位对位，缺的一侧显示「—」") { o, c -> ZxRender24.seqdiff(o, c) }
        reg("zx_cmdpal",   "命令面板", ZxRegistry.Category.INPUT,
            "带过滤与最近使用，按频次排不按字母序") { o, c -> ZxRender24.cmdpal(o, c) }
        reg("zx_span",     "区间标注", ZxRegistry.Category.LAYOUT,
            "同轴标多段并命名，不可重叠") { o, c -> ZxRender24.span(o, c) }
        reg("zx_tolerance","公差带", ZxRegistry.Category.DATA,
            "Cp/Cpk：抓「受控但偏了」") { o, c -> ZxRender24.tolerance(o, c) }

        /* ── 第二十五批 12 个（稳健性 / 敏感性 / 效应量） ── */
        reg("zx_decompose","序列分解", ZxRegistry.Category.DATA,
            "趋势 + 季节 + 残差三项") { o, c -> ZxRender25.decompose(o, c) }
        reg("zx_boot",     "Bootstrap", ZxRegistry.Category.DATA,
            "重采样给区间，不假设分布") { o, c -> ZxRender25.boot(o, c) }
        reg("zx_perm",     "置换检验", ZxRegistry.Category.DATA,
            "打乱重分算精确 p，不假设正态") { o, c -> ZxRender25.perm(o, c) }
        reg("zx_test",     "假设检验", ZxRegistry.Category.DATA,
            "多方法 + Cohen's d + 样本量可拖") { o, c -> ZxRender25.test(o, c) }
        reg("zx_roc",      "ROC", ZxRegistry.Category.DATA,
            "AUC 判别能力，画出随机基线") { o, c -> ZxRender25.roc(o, c) }
        reg("zx_lift",     "提升图", ZxRegistry.Category.DATA,
            "比随机好多少倍，画 1.0 基线") { o, c -> ZxRender25.lift(o, c) }
        reg("zx_pscan",    "参数扫描", ZxRegistry.Category.DATA,
            "结论随参数怎么变，看敏感性") { o, c -> ZxRender25.pscan(o, c) }
        reg("zx_vardec",   "方差分解", ZxRegistry.Category.DATA,
            "ICC：差异有多少来自组间") { o, c -> ZxRender25.vardec(o, c) }
        reg("zx_mds",      "多维尺度", ZxRegistry.Category.DATA,
            "距离矩阵摊平面，给 stress") { o, c -> ZxRender25.mds(o, c) }
        reg("zx_geoflow",  "地理流", ZxRegistry.Category.DATA,
            "有坐标的流向，弧线非直线") { o, c -> ZxRender25.geoflow(o, c) }
        reg("zx_violincmp","分布对比", ZxRegistry.Category.DATA,
            "多组小提琴并排，白线为中位数") { o, c -> ZxRender25.violincmp(o, c) }
        reg("zx_survcmp",  "生存对比", ZxRegistry.Category.DATA,
            "多条 KM + log-rank 检验") { o, c -> ZxRender25.survcmp(o, c) }

        /* ── 第二十六批 12 个（因果与可信度） ── */
        reg("zx_causal",   "因果图", ZxRegistry.Category.DATA,
            "箭头方向 + 混杂/对撞/中介 + 后门路径") { o, c -> ZxRender26.causal(o, c) }
        reg("zx_did",      "双重差分", ZxRegistry.Category.DATA,
            "平行趋势画出来才算前提") { o, c -> ZxRender26.did(o, c) }
        reg("zx_rdd",      "断点回归", ZxRegistry.Category.DATA,
            "断点两侧各拟合一条，落差即效应") { o, c -> ZxRender26.rdd(o, c) }
        reg("zx_iv",       "工具变量", ZxRegistry.Category.DATA,
            "两阶段 + 一阶段 F（弱工具变量会提示）") { o, c -> ZxRender26.iv(o, c) }
        reg("zx_sense",    "敏感性分析", ZxRegistry.Category.DATA,
            "未测混杂要多强才能推翻结论") { o, c -> ZxRender26.sense(o, c) }
        reg("zx_psm",      "倾向得分匹配", ZxRegistry.Category.DATA,
            "最近邻配对 + 共同支撑 + SMD") { o, c -> ZxRender26.psm(o, c) }
        reg("zx_balance",  "协变量平衡", ZxRegistry.Category.DATA,
            "SMD love plot，±0.1 为平衡带") { o, c -> ZxRender26.balance(o, c) }
        reg("zx_missing",  "缺失模式", ZxRegistry.Category.DATA,
            "看缺失有没有结构（MCAR/MAR/MNAR）") { o, c -> ZxRender26.missing(o, c) }
        reg("zx_seqtest",  "序贯检验", ZxRegistry.Category.DATA,
            "看 K 次要分着花 α，边界随次数收紧") { o, c -> ZxRender26.seqtest(o, c) }
        reg("zx_bayes",    "贝叶斯更新", ZxRegistry.Category.DATA,
            "先验 → 后验，信念逐步可见") { o, c -> ZxRender26.bayes(o, c) }
        reg("zx_anomaly",  "异常检测", ZxRegistry.Category.DATA,
            "3σ / IQR / MAD 三判据并列") { o, c -> ZxRender26.anomaly(o, c) }
        reg("zx_power",    "功效分析", ZxRegistry.Category.DATA,
            "不显著是真没差还是没看出来") { o, c -> ZxRender26.power(o, c) }

        /* ── 第二十七批 12 个（预测与决策） ── */
        reg("zx_forecast",   "预测", ZxRegistry.Category.DATA,
            "外推未来 + 区间随 horizon 变宽") { o, c -> ZxRender27.forecast(o, c) }
        reg("zx_calib",      "校准曲线", ZxRegistry.Category.DATA,
            "模型说 0.8 时是不是真有 80%") { o, c -> ZxRender27.calib(o, c) }
        reg("zx_optline",    "最优停止", ZxRegistry.Category.DATA,
            "1/e 法则，让遗憾有上界") { o, c -> ZxRender27.optline(o, c) }
        reg("zx_dmatrix",   "决策矩阵", ZxRegistry.Category.INPUT,
            "多选项 × 多准则，权重可拖") { o, c -> ZxRender27.decision(o, c) }
        reg("zx_riskreturn", "风险收益", ZxRegistry.Category.DATA,
            "有效前沿，前沿外的点是白担风险") { o, c -> ZxRender27.riskreturn(o, c) }
        reg("zx_utility",    "效用曲线", ZxRegistry.Category.INPUT,
            "把偏好变成曲线，期望效用才能算") { o, c -> ZxRender27.utility(o, c) }
        reg("zx_bandit",     "多臂老虎机", ZxRegistry.Category.DATA,
            "边试边选，UCB 不让没试过的臂饿死") { o, c -> ZxRender27.bandit(o, c) }
        reg("zx_explain",    "特征归因", ZxRegistry.Category.DATA,
            "基线推到预测值，且自查自洽") { o, c -> ZxRender27.explain(o, c) }
        reg("zx_cfactual",   "反事实", ZxRegistry.Category.DATA,
            "最小改动让它换一个结果") { o, c -> ZxRender27.cfactual(o, c) }
        reg("zx_cforest",    "异质效应", ZxRegistry.Category.DATA,
            "同一处理对不同人效果是否不同") { o, c -> ZxRender27.cforest(o, c) }
        reg("zx_alloc",      "序贯分配", ZxRegistry.Category.DATA,
            "边际递减下的预算投放") { o, c -> ZxRender27.alloc(o, c) }
        reg("zx_abmon",      "A/B 分流监控", ZxRegistry.Category.STATUS,
            "SRM 检查：分流歪了后面全白看") { o, c -> ZxRender27.abmon(o, c) }

        /* ── 第二十八批 12 个（行动之后） ── */
        reg("zx_rollout",     "灰度发布", ZxRegistry.Category.STATUS,
            "分批放量 + 每批闸门，回答能不能继续") { o, c -> ZxRender28.rollout(o, c) }
        reg("zx_canary",      "金丝雀对比", ZxRegistry.Category.DATA,
            "新旧并跑，逐指标判胜负") { o, c -> ZxRender28.canary(o, c) }
        reg("zx_slo",         "错误预算", ZxRegistry.Category.STATUS,
            "还剩多少失败额度，预算没了就停手") { o, c -> ZxRender28.slo(o, c) }
        reg("zx_healthcheck", "健康检查", ZxRegistry.Category.STATUS,
            "多项检查压成一屏，异常项一眼看出") { o, c -> ZxRender28.healthcheck(o, c) }
        reg("zx_incident",    "事故时间线", ZxRegistry.Category.STATUS,
            "发现/定位/止损/恢复分段，看哪一段最久") { o, c -> ZxRender28.incident(o, c) }
        reg("zx_rca",         "根因溯源", ZxRegistry.Category.DATA,
            "5Why 一层层往下问，不停在表象") { o, c -> ZxRender28.rca(o, c) }
        reg("zx_regret",      "遗憾分析", ZxRegistry.Category.DATA,
            "选了 A，回头看选 B 会怎样") { o, c -> ZxRender28.regret(o, c) }
        reg("zx_backtest",    "回测", ZxRegistry.Category.DATA,
            "策略在历史上的表现，必须有基准") { o, c -> ZxRender28.backtest(o, c) }
        reg("zx_drift",       "数据漂移", ZxRegistry.Category.DATA,
            "线上 vs 训练，给「该不该重训」的判据") { o, c -> ZxRender28.drift(o, c) }
        reg("zx_alert",       "告警", ZxRegistry.Category.STATUS,
            "多条聚合 + 每条都要有处置动作") { o, c -> ZxRender28.alert(o, c) }
        reg("zx_costtrack",   "成本追踪", ZxRegistry.Category.DATA,
            "照这个速度月底会不会超") { o, c -> ZxRender28.costtrack(o, c) }
        reg("zx_feedback",    "结果回收", ZxRegistry.Category.DATA,
            "当初说的数 vs 实际发生的数") { o, c -> ZxRender28.feedback(o, c) }

        /* ── 第二十九批 12 个（知识与判断的留存） ── */
        reg("zx_capacity",    "容量规划", ZxRegistry.Category.DATA,
            "排队论：λ/μ/ρ → 等待时间与队列长度") { o, c -> ZxRender29.capacity(o, c) }
        reg("zx_queue",       "排队", ZxRegistry.Category.DATA,
            "Little's Law：L = λW") { o, c -> ZxRender29.queue(o, c) }
        reg("zx_loadtest",    "负载测试", ZxRegistry.Category.DATA,
            "吞吐-延迟曲线，标出拐点") { o, c -> ZxRender29.loadtest(o, c) }
        reg("zx_topology",    "服务拓扑", ZxRegistry.Category.DATA,
            "健康沿依赖边传播，算影响面") { o, c -> ZxRender29.topology(o, c) }
        reg("zx_changerisk",  "变更风险", ZxRegistry.Category.STATUS,
            "评分 + 每条依据；高风险缺依据会被拦") { o, c -> ZxRender29.changerisk(o, c) }
        reg("zx_playbook",    "处置剧本", ZxRegistry.Category.TEXT,
            "分阶段且必须带回滚") { o, c -> ZxRender29.playbook(o, c) }
        reg("zx_escalation",  "升级路径", ZxRegistry.Category.STATUS,
            "每级有超时，且有终点") { o, c -> ZxRender29.escalation(o, c) }
        reg("zx_postmortem",  "复盘", ZxRegistry.Category.TEXT,
            "必须有行动项，且要有负责人") { o, c -> ZxRender29.postmortem(o, c) }
        reg("zx_knowledge",   "知识沉淀", ZxRegistry.Category.TEXT,
            "必须标出处与时效（过期知识更危险）") { o, c -> ZxRender29.knowledge(o, c) }
        reg("zx_decisionlog", "决策日志", ZxRegistry.Category.TEXT,
            "记当时为什么这么选；缺依据会被拦") { o, c -> ZxRender29.decisionlog(o, c) }
        reg("zx_debt",        "技术债", ZxRegistry.Category.DATA,
            "标利息，按利息/成本排") { o, c -> ZxRender29.debt(o, c) }
        reg("zx_checkpoint",  "检查点", ZxRegistry.Category.STATUS,
            "长任务保存点，看崩了会丢多少") { o, c -> ZxRender29.checkpoint(o, c) }

        /* ── 第三十批 12 个（组织与治理） ── */
        reg("zx_unitecon",    "单客经济", ZxRegistry.Category.DATA,
            "CAC / LTV / 回本周期") { o, c -> ZxRender30.unitecon(o, c) }
        reg("zx_costattrib",  "成本归因", ZxRegistry.Category.DATA,
            "按维度拆，且必须能对上总额") { o, c -> ZxRender30.costattrib(o, c) }
        reg("zx_lineage",     "数据血缘", ZxRegistry.Category.DATA,
            "分层展示，算出深度与影响面") { o, c -> ZxRender30.lineage(o, c) }
        reg("zx_impact",      "影响分析", ZxRegistry.Category.STATUS,
            "改这个波及谁，且每项要给修复方式") { o, c -> ZxRender30.impact(o, c) }
        reg("zx_dataclass",   "数据分级", ZxRegistry.Category.STATUS,
            "每列标敏感级别，不能有未分级的") { o, c -> ZxRender30.dataclass(o, c) }
        reg("zx_retention",   "保留策略", ZxRegistry.Category.STATUS,
            "标出已到期该删的") { o, c -> ZxRender30.retention(o, c) }
        reg("zx_workload",    "团队负载", ZxRegistry.Category.DATA,
            "区分「忙」和「堵」：同时看超载与空闲") { o, c -> ZxRender30.workload(o, c) }
        reg("zx_bottleneck",  "瓶颈识别", ZxRegistry.Category.DATA,
            "区分「约束」和「最堵」") { o, c -> ZxRender30.bottleneck(o, c) }
        reg("zx_expresult",   "实验结果", ZxRegistry.Category.DATA,
            "显著 ≠ 该上：必须给实际提升") { o, c -> ZxRender30.expresult(o, c) }
        reg("zx_compliance",  "合规检查", ZxRegistry.Category.STATUS,
            "不合格项必须落到整改人") { o, c -> ZxRender30.compliance(o, c) }
        reg("zx_audittrail",  "审计追踪", ZxRegistry.Category.TEXT,
            "谁在什么时候改了什么") { o, c -> ZxRender30.audittrail(o, c) }
        reg("zx_accessreview","权限评审", ZxRegistry.Category.STATUS,
            "看「多久没审」而不是有什么权限") { o, c -> ZxRender30.accessreview(o, c) }

        /* ── 第三十一批 12 个（模拟与推演） ── */
        reg("zx_scenario",    "情景推演", ZxRegistry.Category.DATA,
            "乐观/中性/悲观并列，画出色带") { o, c -> ZxRender31.scenario(o, c) }
        reg("zx_montecarlo",  "蒙特卡洛", ZxRegistry.Category.DATA,
            "跑 N 次给分布与 P(达标)，必须存 seed") { o, c -> ZxRender31.montecarlo(o, c) }
        reg("zx_stress",      "压力情景", ZxRegistry.Category.STATUS,
            "极端条件并排，全通过要报出来") { o, c -> ZxRender31.stress(o, c) }
        reg("zx_tornado",     "龙卷风图", ZxRegistry.Category.DATA,
            "单因素敏感性排序") { o, c -> ZxRender31.tornado(o, c) }
        reg("zx_whatif",      "假设推演", ZxRegistry.Category.DATA,
            "改一个输入，全链路跟着变") { o, c -> ZxRender31.whatif(o, c) }
        reg("zx_breakeven",   "盈亏平衡", ZxRegistry.Category.DATA,
            "找临界点，且临界点要能移动") { o, c -> ZxRender31.breakeven(o, c) }
        reg("zx_systemdyn",   "系统动力学", ZxRegistry.Category.DATA,
            "存量-流量，看长期行为与稳态") { o, c -> ZxRender31.systemdyn(o, c) }
        reg("zx_markov",      "马尔可夫链", ZxRegistry.Category.DATA,
            "n 步后分布与稳态分布") { o, c -> ZxRender31.markov(o, c) }
        reg("zx_optpath",     "最优路径", ZxRegistry.Category.DATA,
            "带代价寻优，区分不可达与代价高") { o, c -> ZxRender31.optpath(o, c) }
        reg("zx_payoff",      "博弈矩阵", ZxRegistry.Category.DATA,
            "2×2，标出纳什均衡") { o, c -> ZxRender31.payoff(o, c) }
        reg("zx_reversesolve","反向求解", ZxRegistry.Category.DATA,
            "从目标反推输入，无解必须明说") { o, c -> ZxRender31.reversesolve(o, c) }
        reg("zx_realoption",  "实物期权", ZxRegistry.Category.DATA,
            "「再等等」也是一个选项") { o, c -> ZxRender31.realoption(o, c) }

        /* ── 第三十二批 12 个（议价、竞标与序贯决策） ── */
        reg("zx_bargain",     "议价区间", ZxRegistry.Category.DATA,
            "ZOPA 在哪，剩余怎么分，无成交区要明说") { o, c -> ZxRender32.bargain(o, c) }
        reg("zx_auction",     "竞标", ZxRegistry.Category.DATA,
            "中标者与**赢者诅咒**（出价超自己的估值）") { o, c -> ZxRender32.auction(o, c) }
        reg("zx_multiissue",  "多议题交换", ZxRegistry.Category.DATA,
            "**价值在交换不在让步**") { o, c -> ZxRender32.multiissue(o, c) }
        reg("zx_pfront",      "Pareto 前沿", ZxRegistry.Category.DATA,
            "标出被支配的点，前沿上没有最优") { o, c -> ZxRender32.pareto(o, c) }
        reg("zx_portfolio",   "组合优化", ZxRegistry.Category.DATA,
            "权重可拖，分散化让风险低于加权平均") { o, c -> ZxRender32.portfolio(o, c) }
        reg("zx_bidcurve",    "出价曲线", ZxRegistry.Category.DATA,
            "供需找均衡价，无交点要明说") { o, c -> ZxRender32.bidcurve(o, c) }
        reg("zx_sequential",  "序贯决策", ZxRegistry.Category.DATA,
            "信息价值 vs 等待代价，不该等要明说") { o, c -> ZxRender32.sequential(o, c) }
        reg("zx_stoprule",    "最优停止", ZxRegistry.Category.DATA,
            "37% 规则，观察期与决策期分界") { o, c -> ZxRender32.stoprule(o, c) }
        reg("zx_hedge",       "对冲", ZxRegistry.Category.DATA,
            "把风险换成你能承受的形态，成本要对比") { o, c -> ZxRender32.hedge(o, c) }
        reg("zx_contingent",  "应急计划", ZxRegistry.Category.DATA,
            "情景→动作，标出未覆盖的概率") { o, c -> ZxRender32.contingent(o, c) }
        reg("zx_escalate",    "升级决策", ZxRegistry.Category.DATA,
            "什么时候该叫人，成本要和损失比") { o, c -> ZxRender32.escalate(o, c) }
        reg("zx_commit",      "承诺可信度", ZxRegistry.Category.DATA,
            "**无代价的承诺不可信**") { o, c -> ZxRender32.commit(o, c) }

        /* ── 第三十三批 15 个（消费、开发与娱乐） ── */
        reg("zx_weather",    "天气", ZxRegistry.Category.DATA,
            "当前 + 多日预报，图标自绘，**必带观测时间**") { o, c -> ZxRender33.weather(o, c) }
        reg("zx_wxradar",    "降水雷达", ZxRegistry.Category.DATA,
            "同心圈 + 回波分级 + 扫描线，要能看出在动也能冻结") { o, c -> ZxRender33.wxradar(o, c) }
        reg("zx_aqi",        "空气质量", ZxRegistry.Category.DATA,
            "**首要污染物 = 占标率最高**，不是浓度最高") { o, c -> ZxRender33.aqi(o, c) }
        reg("zx_news",       "新闻流", ZxRegistry.Category.DATA,
            "来源要能看出是否同源，时间要能看出新旧") { o, c -> ZxRender33.news(o, c) }
        reg("zx_headline",   "头条卡", ZxRegistry.Category.DATA,
            "把全文压成要点——**读了要点就不用读全文**") { o, c -> ZxRender33.headline(o, c) }
        reg("zx_feed",       "订阅流", ZxRegistry.Category.DATA,
            "已读标记 + 可折叠") { o, c -> ZxRender33.feed(o, c) }
        reg("zx_code",       "代码块", ZxRegistry.Category.DATA,
            "多语言高亮 + 行号 + **高亮行**（说「看这几行」）") { o, c -> ZxRender33.code(o, c) }
        reg("zx_codetheme",  "代码主题对比", ZxRegistry.Category.DATA,
            "同一段源码三种配色，**看得出区别才算对比**") { o, c -> ZxRender33.codetheme(o, c) }
        reg("zx_shell",      "Shell", ZxRegistry.Category.DATA,
            "可交互：输入→输出 + 历史 + 常用命令快键") { o, c -> ZxRender33.shell(o, c) }
        reg("zx_tty",        "终端会话", ZxRegistry.Category.DATA,
            "多标签，**状态点外显**：不切过去也知道哪个在跑") { o, c -> ZxRender33.tty(o, c) }
        reg("zx_toolcall",   "工具调用", ZxRegistry.Category.DATA,
            "工具名 + 参数 + 结果 + 耗时 + 状态，四件缺一不可") { o, c -> ZxRender33.toolcall(o, c) }
        reg("zx_toolchain",  "工具调用链", ZxRegistry.Category.DATA,
            "顺序 + 依赖 + 断点；**失败和待定要分开**") { o, c -> ZxRender33.toolchain(o, c) }
        reg("zx_toolargs",   "工具参数表单", ZxRegistry.Category.DATA,
            "schema 驱动，**必填项要标出来**") { o, c -> ZxRender33.toolargs(o, c) }
        reg("zx_2048",       "2048", ZxRegistry.Category.DATA,
            "真能玩：滑动合并 + 随机生成 + 判负") { o, c -> ZxRender33.game2048(o, c) }
        reg("zx_tictactoe",  "井字棋", ZxRegistry.Category.DATA,
            "真能下：AI 先赢后挡，**三连高亮**") { o, c -> ZxRender33.tictactoe(o, c) }

        /* ── 第三十四批 15 个（日常界面） ── */
        reg("zx_mapnav",     "地图导航", ZxRegistry.Category.DATA,
            "**下一个路口往哪拐**——导航的价值不在全程路线") { o, c -> ZxRender34.mapnav(o, c) }
        reg("zx_transit",    "公交换乘", ZxRegistry.Category.DATA,
            "多段联程 + 换乘点；**无步行段会被拦**") { o, c -> ZxRender34.transit(o, c) }
        reg("zx_music",      "音乐播放器", ZxRegistry.Category.DATA,
            "进度**能拖** + 上下曲 + 循环模式") { o, c -> ZxRender34.music(o, c) }
        reg("zx_album",      "相册", ZxRegistry.Category.DATA,
            "九宫格 + 查看器 + **第几张 / 共几张**") { o, c -> ZxRender34.album(o, c) }
        reg("zx_chatlog",    "聊天记录", ZxRegistry.Category.DATA,
            "方向 + 时间 + 已读，三层冗余") { o, c -> ZxRender34.chatlog(o, c) }
        reg("zx_contact",    "联系人", ZxRegistry.Category.DATA,
            "多方式，**点了直接拨/发**") { o, c -> ZxRender34.contact(o, c) }
        reg("zx_agenda",     "日程时间轴", ZxRegistry.Category.DATA,
            "24 小时纵向排布 + **冲突高亮**") { o, c -> ZxRender34.agenda(o, c) }
        reg("zx_filemgr",    "文件管理器", ZxRegistry.Category.DATA,
            "能进能退：面包屑 + 上级 + 多选") { o, c -> ZxRender34.filemgr(o, c) }
        reg("zx_setup",      "设置向导", ZxRegistry.Category.DATA,
            "**前置未完成就卡住**——向导的价值在卡住") { o, c -> ZxRender34.setup(o, c) }
        reg("zx_shop",       "商品卡", ZxRegistry.Category.DATA,
            "规格 + 库存；**缺货的规格选不了**") { o, c -> ZxRender34.shop(o, c) }
        reg("zx_order",      "订单物流", ZxRegistry.Category.DATA,
            "节点 + **当前标出**") { o, c -> ZxRender34.order(o, c) }
        reg("zx_wallet",     "钱包账单", ZxRegistry.Category.DATA,
            "收支对比 + 分类占比 + **对账校验**") { o, c -> ZxRender34.wallet(o, c) }
        reg("zx_remote",     "遥控器", ZxRegistry.Category.DATA,
            "方向键 + 音量 + 自定义键，**每键都要有 action**") { o, c -> ZxRender34.remote(o, c) }
        reg("zx_dialpad",    "拨号盘", ZxRegistry.Category.DATA,
            "3×4 + 回显 + 拨出") { o, c -> ZxRender34.dialpad(o, c) }
        reg("zx_tasklist",   "任务清单", ZxRegistry.Category.DATA,
            "进度 + 优先级 + **依赖锁定**") { o, c -> ZxRender34.tasklist(o, c) }

        /* ── 第三十五批 14 个（生活场景） ── */
        reg("zx_fitness",      "健身记录", ZxRegistry.Category.DATA,
            "多次 + 目标；**只有 1 条记录会被拦**（看不出趋势）") { o, c -> ZxRender35.fitness(o, c) }
        reg("zx_medicine",     "用药记录", ZxRegistry.Category.DATA,
            "计划 vs 实际；**漏服标红**") { o, c -> ZxRender35.medicine(o, c) }
        reg("zx_parcel",       "快递轨迹", ZxRegistry.Category.DATA,
            "节点 + 地点 + **预计还需多久**") { o, c -> ZxRender35.parcel(o, c) }
        reg("zx_ticket",       "购票选座", ZxRegistry.Category.DATA,
            "可售状态 + 多选上限；**已售的点不动**") { o, c -> ZxRender35.ticket(o, c) }
        reg("zx_study",        "学习卡片", ZxRegistry.Category.DATA,
            "翻转 + 评分 → **下次间隔**") { o, c -> ZxRender35.study(o, c) }
        reg("zx_plant",        "植物养护", ZxRegistry.Category.DATA,
            "间隔 + 距上次天数；**过期标红**") { o, c -> ZxRender35.plant(o, c) }
        reg("zx_parking",      "停车找车", ZxRegistry.Category.DATA,
            "**车位大号** + 已停时长 + 费用") { o, c -> ZxRender35.parking(o, c) }
        reg("zx_habit",        "习惯打卡", ZxRegistry.Category.DATA,
            "**连续数** + 最长 + 目标") { o, c -> ZxRender35.habit(o, c) }
        reg("zx_subscription", "订阅管理", ZxRegistry.Category.DATA,
            "折算月付 + **闲置提示**") { o, c -> ZxRender35.subscription(o, c) }
        reg("zx_pet",          "宠物照护", ZxRegistry.Category.DATA,
            "**还有多久** + 超时标红（小时级）") { o, c -> ZxRender35.pet(o, c) }
        reg("zx_utilitymeter", "水电表", ZxRegistry.Category.DATA,
            "**每期用量**而非读数 + 与均值比") { o, c -> ZxRender35.utilitymeter(o, c) }
        reg("zx_sleep",        "睡眠记录", ZxRegistry.Category.DATA,
            "多晚 + **目标线** + 质量分") { o, c -> ZxRender35.sleep(o, c) }
        reg("zx_coupon",       "优惠券", ZxRegistry.Category.DATA,
            "满减/折扣 + **临期标红**") { o, c -> ZxRender35.coupon(o, c) }
        reg("zx_appointment",  "预约挂号", ZxRegistry.Category.DATA,
            "时段 + 余号；**满的不可选**") { o, c -> ZxRender35.appointment(o, c) }

        /* ── 第三十六批 15 个（生活操作） ── */
        reg("zx_recipe",     "菜谱步骤", ZxRegistry.Category.DATA,
            "配料随份数缩放 + 步骤勾选；**步骤为空会被拦**") { o, c -> ZxRender36.recipe(o, c) }
        reg("zx_bookkeep",     "记账流水", ZxRegistry.Category.DATA,
            "分类占比 + 预算对比；**大额 3 倍均值标红**") { o, c -> ZxRender36.ledger(o, c) }
        reg("zx_commute",    "通勤路况", ZxRegistry.Category.DATA,
            "多方案并排 + 延误标红；**步行段缺失会被拦**") { o, c -> ZxRender36.commute(o, c) }
        reg("zx_warranty",   "保修卡", ZxRegistry.Category.DATA,
            "剩余天数 + **过保标红** + 序列号") { o, c -> ZxRender36.warranty(o, c) }
        reg("zx_inspection", "巡检表", ZxRegistry.Category.DATA,
            "合格/不合格；**不合格必须填原因**") { o, c -> ZxRender36.inspection(o, c) }
        reg("zx_examprep",   "备考计划", ZxRegistry.Category.DATA,
            "权重 × 完成率 → **最该补的科目**") { o, c -> ZxRender36.examprep(o, c) }
        reg("zx_tripkit",    "出行清单", ZxRegistry.Category.DATA,
            "分类打包；**必带项未勾阻止出发**") { o, c -> ZxRender36.tripkit(o, c) }
        reg("zx_inventory",  "家庭库存", ZxRegistry.Category.DATA,
            "存量 + **安全线标红**") { o, c -> ZxRender36.inventory(o, c) }
        reg("zx_recycle",    "垃圾分类", ZxRegistry.Category.DATA,
            "四类计数 + **投放要求**；类别不在四类内会被拦") { o, c -> ZxRender36.recycle(o, c) }
        reg("zx_carservice", "车辆保养", ZxRegistry.Category.DATA,
            "**里程 + 时间双触发**") { o, c -> ZxRender36.carservice(o, c) }
        reg("zx_expiry",     "食品保质期", ZxRegistry.Category.DATA,
            "按剩余天数排序 + **过期标红**") { o, c -> ZxRender36.expiry(o, c) }
        reg("zx_splitbill",  "AA 分账", ZxRegistry.Category.DATA,
            "净额 + **最少转账方案**") { o, c -> ZxRender36.splitbill(o, c) }
        reg("zx_firstaid",   "急救步骤", ZxRegistry.Category.DATA,
            "**不能跳步** + 禁忌 + 是否先叫 120") { o, c -> ZxRender36.firstaid(o, c) }
        reg("zx_sizing",     "尺码对照", ZxRegistry.Category.DATA,
            "实测 → **推荐码** + 不合身标灰") { o, c -> ZxRender36.sizing(o, c) }
        reg("zx_gift",       "人情往来", ZxRegistry.Category.DATA,
            "收/送 + **待回礼标红** + 差额") { o, c -> ZxRender36.gift(o, c) }

        /* ── 第三十七批 12 个（跨多次、有外部依赖） ── */
        reg("zx_renovation", "装修进度", ZxRegistry.Category.DATA,
            "阶段 + **负责人**；**未验收不能进下一阶段**") { o, c -> ZxRender37.renovation(o, c) }
        reg("zx_clinic",     "就医陪诊", ZxRegistry.Category.DATA,
            "环节 + **排队人数** + 预计等待") { o, c -> ZxRender37.clinic(o, c) }
        reg("zx_rental",     "租房比选", ZxRegistry.Category.DATA,
            "价格/面积/**通勤** 三维度综合分") { o, c -> ZxRender37.rental(o, c) }
        reg("zx_repair",     "报修工单", ZxRegistry.Category.DATA,
            "状态条 + **当前责任人** + **能不能催**") { o, c -> ZxRender37.repair(o, c) }
        reg("zx_credential", "证件到期", ZxRegistry.Category.DATA,
            "剩余天 + **提前办理窗口** + 办理机构") { o, c -> ZxRender37.credential(o, c) }
        reg("zx_ship",     "快递寄件", ZxRegistry.Category.DATA,
            "轨迹节点 + 时间 + 当前在谁手上") { o, c -> ZxRender37.parcel(o, c) }
        reg("zx_interview",  "面试流程", ZxRegistry.Category.DATA,
            "轮次 + 通过/未通过 + 时间") { o, c -> ZxRender37.interview(o, c) }
        reg("zx_visa",       "签证材料", ZxRegistry.Category.DATA,
            "必需/可选 + 三态 + 递交后归属") { o, c -> ZxRender37.visa(o, c) }
        reg("zx_insurance",  "保单", ZxRegistry.Category.DATA,
            "分项保额 + 已理赔 + **到期标红**") { o, c -> ZxRender37.insurance(o, c) }
        reg("zx_reimburse",  "报销单据", ZxRegistry.Category.DATA,
            "合计对账 + 逐张审驳；**驳回要写原因**") { o, c -> ZxRender37.reimburse(o, c) }
        reg("zx_relocation", "搬家迁入", ZxRegistry.Category.DATA,
            "按房间 + 箱数 + **未打包/已打包/已到位**") { o, c -> ZxRender37.relocation(o, c) }
        reg("zx_booking",    "场地预订", ZxRegistry.Category.DATA,
            "时段网格 + **可订/已订/不可订三态**") { o, c -> ZxRender37.booking(o, c) }

        /* ── 第三十八批 12 个（出事了之后怎么办） ── */
        reg("zx_escort",       "陪诊记录", ZxRegistry.Category.DATA,
            "就诊环节 + **医嘱执行勾选**") { o, c -> ZxRender38.escort(o, c) }
        reg("zx_lease",        "租房合同", ZxRegistry.Category.DATA,
            "**关键条款标红** + 金额对账") { o, c -> ZxRender38.lease(o, c) }
        reg("zx_overrun",      "装修超支", ZxRegistry.Category.DATA,
            "预算 vs 实际双条 + **超支原因**") { o, c -> ZxRender38.overrun(o, c) }
        reg("zx_vet",          "宠物医疗", ZxRegistry.Category.DATA,
            "体重 + 用药剂量 + **下次倒计时**") { o, c -> ZxRender38.vet(o, c) }
        reg("zx_photospec",    "证件照规格", ZxRegistry.Category.DATA,
            "按用途切换 + **比例预览** + 底色") { o, c -> ZxRender38.photospec(o, c) }
        reg("zx_claim",        "快递索赔", ZxRegistry.Category.DATA,
            "证据清单 + **每项剩余时间** + 过期后果") { o, c -> ZxRender38.claim(o, c) }
        reg("zx_ivreview",     "面试复盘", ZxRegistry.Category.DATA,
            "逐题打分 + **改进方向**") { o, c -> ZxRender38.ivreview(o, c) }
        reg("zx_claimprog",    "理赔进度", ZxRegistry.Category.DATA,
            "材料三态 + **每项截止** + 过截止标红") { o, c -> ZxRender38.claimprog(o, c) }
        reg("zx_refile",       "报销重报", ZxRegistry.Category.DATA,
            "退回原因 + 待补清单 + **重报截止**") { o, c -> ZxRender38.refile(o, c) }
        reg("zx_damage",       "搬家损坏", ZxRegistry.Category.DATA,
            "物品 + 定级 + **责任方** + 有没有照片") { o, c -> ZxRender38.damage(o, c) }
        reg("zx_slotconflict", "场地冲突", ZxRegistry.Category.DATA,
            "双方依据 + 权重 + **带代价的方案**") { o, c -> ZxRender38.slotconflict(o, c) }
        reg("zx_lostdoc",      "证件挂失", ZxRegistry.Category.DATA,
            "**丢失方式决定流程** + 强制顺序 + 受理点") { o, c -> ZxRender38.lostdoc(o, c) }

        /* ── 第三十九批 12 个（责任在两个主体之间转移） ── */
        reg("zx_handoff",      "交接清单", ZxRegistry.Category.DATA,
            "**状态快照** + **双方签字** + 异议入口") { o, c -> ZxRender39.handoff(o, c) }
        reg("zx_moveout",      "退租结算", ZxRegistry.Category.DATA,
            "扣项 + **依据** + **双方签字**") { o, c -> ZxRender39.moveout(o, c) }
        reg("zx_vehiclecare",  "车辆维保", ZxRegistry.Category.DATA,
            "**里程和时间双触发** + 必做/建议分级") { o, c -> ZxRender39.vehiclecare(o, c) }
        reg("zx_medremote",    "异地备案", ZxRegistry.Category.DATA,
            "**有效期** + **报销比例变化** + 材料") { o, c -> ZxRender39.medremote(o, c) }
        reg("zx_housedeal",    "二手房交易", ZxRegistry.Category.DATA,
            "节点 + **资金监管标记** + 双方确认") { o, c -> ZxRender39.housedeal(o, c) }
        reg("zx_schoolup",     "升学流程", ZxRegistry.Category.DATA,
            "**报名窗口** + 材料 + **必需/可后补**") { o, c -> ZxRender39.schoolup(o, c) }
        reg("zx_carerota",     "照护排班", ZxRegistry.Category.DATA,
            "按天 + **交接完成标记** + 空档检测") { o, c -> ZxRender39.carerota(o, c) }
        reg("zx_taxxborder",   "跨境税", ZxRegistry.Category.DATA,
            "**免征额进度** + 分商品税率 + 应缴") { o, c -> ZxRender39.taxxborder(o, c) }
        reg("zx_rentreturn",   "设备归还", ZxRegistry.Category.DATA,
            "**借出 vs 归还状态对比** + 损坏判定 + 签字") { o, c -> ZxRender39.rentreturn(o, c) }
        reg("zx_dutyrota",     "团队值班", ZxRegistry.Category.DATA,
            "换班 + **双方同意** + 空档检测") { o, c -> ZxRender39.dutyrota(o, c) }
        reg("zx_annualaudit",  "证照年审", ZxRegistry.Category.DATA,
            "**到期倒计时** + **材料齐没齐** + 过期后果") { o, c -> ZxRender39.annualaudit(o, c) }
        reg("zx_liability",    "责任认定", ZxRegistry.Category.DATA,
            "**比例合计必须 100%** + 依据 + 签字") { o, c -> ZxRender39.liability(o, c) }

        /* ── 第四十批 12 个（交接完成 ≠ 责任结束） ── */
        reg("zx_housekeep",    "家政验收", ZxRegistry.Category.DATA,
            "**每次验收** + **累计评价** + 追责期") { o, c -> ZxRender40.housekeep(o, c) }
        reg("zx_finalpay",     "装修尾款", ZxRegistry.Category.DATA,
            "**尾款比例** + **质保期** + 质保内问题") { o, c -> ZxRender40.finalpay(o, c) }
        reg("zx_fosterdaily",  "寄养日报", ZxRegistry.Category.DATA,
            "**每天体重** + **追溯期**") { o, c -> ZxRender40.fosterdaily(o, c) }
        reg("zx_wardhand",     "转科交接", ZxRegistry.Category.DATA,
            "**生命体征快照** + **观察期** + 待办") { o, c -> ZxRender40.wardhand(o, c) }
        reg("zx_schoolnote",   "家校沟通", ZxRegistry.Category.DATA,
            "**每条带回复期限** + 超期检测") { o, c -> ZxRender40.schoolnote(o, c) }
        reg("zx_sublet",       "转租", ZxRegistry.Category.DATA,
            "**三方签字** + **原租客责任终止点**") { o, c -> ZxRender40.sublet(o, c) }
        reg("zx_inspecthand",  "周期巡检", ZxRegistry.Category.DATA,
            "**上次+周期** + 超期检测 + 分级") { o, c -> ZxRender40.inspecthand(o, c) }
        reg("zx_projhandover", "项目移交", ZxRegistry.Category.DATA,
            "**过渡期** + **响应时限** + 归属") { o, c -> ZxRender40.projhandover(o, c) }
        reg("zx_stocktake",    "盘点", ZxRegistry.Category.DATA,
            "**账面 vs 实盘** + **差异必须写原因**") { o, c -> ZxRender40.stocktake(o, c) }
        reg("zx_offboard",     "离职交接", ZxRegistry.Category.DATA,
            "交接项 + **权限回收** + **追责期**") { o, c -> ZxRender40.offboard(o, c) }
        reg("zx_revoke",       "权限回收", ZxRegistry.Category.DATA,
            "**四类分开** + **生效滞后** + 残留检测") { o, c -> ZxRender40.revoke(o, c) }
        reg("zx_warrtrack",    "质保追踪", ZxRegistry.Category.DATA,
            "**距过保倒计时** + **问题是否已报**") { o, c -> ZxRender40.warrtrack(o, c) }

        // 触发器要在组件登记完之后建，否则它监听不到任何组件
        ZxTrigger.bootstrap()

        // 组合要在组件登记完之后建，否则它引用不到任何组件
        ZxCompose.bootstrap()

        // 语义字典要在组件登记完之后建，否则它查不到任何组件
        ZxSem.bootstrap()
    }

    @Composable
    private fun reg(
        name: String, label: String, cat: ZxRegistry.Category,
        summary: String, minWidth: Int = 0,
        caps: Set<String> = emptySet(),
        surface: (JSONObject) -> Boolean = { false },
        render: @Composable (JSONObject, RenderContext) -> Unit
    ) {
        ZxRegistry.register(
            ZxRegistry.Meta(
                name = name, label = label, category = cat, summary = summary,
                spec = specOf(name), sample = sampleOf(name),
                validate = { o -> validateOf(name, o) },
                render = render,
                minWidthDp = minWidth, prefersSurface = surface,
                needsCaps = caps
            )
        )
    }

    private fun validateOf(name: String, o: JSONObject): Pair<Boolean, String> = when {
        name in ZxDsl40.ALL -> ZxDsl40.validate(name, o)
        name in ZxDsl39.ALL -> ZxDsl39.validate(name, o)
        name in ZxDsl38.ALL -> ZxDsl38.validate(name, o)
        name in ZxDsl37.ALL -> ZxDsl37.validate(name, o)
        name in ZxDsl36.ALL -> ZxDsl36.validate(name, o)
        name in ZxDsl35.ALL -> ZxDsl35.validate(name, o)
        name in ZxDsl34.ALL -> ZxDsl34.validate(name, o)
        name in ZxDsl33.ALL -> ZxDsl33.validate(name, o)
        name in ZxDsl32.ALL -> ZxDsl32.validate(name, o)
        name in ZxDsl31.ALL -> ZxDsl31.validate(name, o)
        name in ZxDsl30.ALL -> ZxDsl30.validate(name, o)
        name in ZxDsl29.ALL -> ZxDsl29.validate(name, o)
        name in ZxDsl28.ALL -> ZxDsl28.validate(name, o)
        name in ZxDsl27.ALL -> ZxDsl27.validate(name, o)
        name in ZxDsl26.ALL -> ZxDsl26.validate(name, o)
        name in ZxDsl25.ALL -> ZxDsl25.validate(name, o)
        name in ZxDsl24.ALL -> ZxDsl24.validate(name, o)
        name in ZxDsl23.ALL -> ZxDsl23.validate(name, o)
        name in ZxDsl22.ALL -> ZxDsl22.validate(name, o)
        name in ZxDsl21.ALL -> ZxDsl21.validate(name, o)
        name in ZxDsl20.ALL -> ZxDsl20.validate(name, o)
        name in ZxDsl19.ALL -> ZxDsl19.validate(name, o)
        name in ZxDsl18.ALL -> ZxDsl18.validate(name, o)
        name in ZxDsl17.ALL -> ZxDsl17.validate(name, o)
        name in ZxDsl16.ALL -> ZxDsl16.validate(name, o)
        name in ZxDsl15.ALL -> ZxDsl15.validate(name, o)
        name in ZxDsl14.ALL -> ZxDsl14.validate(name, o)
        name in ZxDsl13.ALL -> ZxDsl13.validate(name, o)
        name in ZxDsl12.ALL -> ZxDsl12.validate(name, o)
        name in ZxDsl11.ALL -> ZxDsl11.validate(name, o)
        name in ZxDsl10.ALL -> ZxDsl10.validate(name, o)
        name in ZxDsl9.ALL -> ZxDsl9.validate(name, o)
        name in ZxDsl8.ALL -> ZxDsl8.validate(name, o)
        name in ZxDsl7.ALL -> ZxDsl7.validate(name, o)
        name in ZxDsl6.ALL -> ZxDsl6.validate(name, o)
        name in ZxDsl5.ALL -> ZxDsl5.validate(name, o)
        name in ZxDsl4.ALL -> ZxDsl4.validate(name, o)
        name in ZxDsl3.ALL || name in ZxDsl3.CONTAINERS -> ZxDsl3.validate(name, o)
        name in ZxDsl2.ALL -> ZxDsl2.validate(name, o)
        else -> ZxDsl.validateAny(name, o).let { it.ok to it.reason }
    }

    private fun specOf(name: String): String = when {
        name in ZxDsl40.ALL -> ZxDsl40.spec(name)
        name in ZxDsl39.ALL -> ZxDsl39.spec(name)
        name in ZxDsl38.ALL -> ZxDsl38.spec(name)
        name in ZxDsl37.ALL -> ZxDsl37.spec(name)
        name in ZxDsl36.ALL -> ZxDsl36.spec(name)
        name in ZxDsl35.ALL -> ZxDsl35.spec(name)
        name in ZxDsl34.ALL -> ZxDsl34.spec(name)
        name in ZxDsl33.ALL -> ZxDsl33.spec(name)
        name in ZxDsl32.ALL -> ZxDsl32.spec(name)
        name in ZxDsl31.ALL -> ZxDsl31.spec(name)
        name in ZxDsl30.ALL -> ZxDsl30.spec(name)
        name in ZxDsl29.ALL -> ZxDsl29.spec(name)
        name in ZxDsl28.ALL -> ZxDsl28.spec(name)
        name in ZxDsl27.ALL -> ZxDsl27.spec(name)
        name in ZxDsl26.ALL -> ZxDsl26.spec(name)
        name in ZxDsl25.ALL -> ZxDsl25.spec(name)
        name in ZxDsl24.ALL -> ZxDsl24.spec(name)
        name in ZxDsl23.ALL -> ZxDsl23.spec(name)
        name in ZxDsl22.ALL -> ZxDsl22.spec(name)
        name in ZxDsl21.ALL -> ZxDsl21.spec(name)
        name in ZxDsl20.ALL -> ZxDsl20.spec(name)
        name in ZxDsl19.ALL -> ZxDsl19.spec(name)
        name in ZxDsl18.ALL -> ZxDsl18.spec(name)
        name in ZxDsl17.ALL -> ZxDsl17.spec(name)
        name in ZxDsl16.ALL -> ZxDsl16.spec(name)
        name in ZxDsl15.ALL -> ZxDsl15.spec(name)
        name in ZxDsl14.ALL -> ZxDsl14.spec(name)
        name in ZxDsl13.ALL -> ZxDsl13.spec(name)
        name in ZxDsl12.ALL -> ZxDsl12.spec(name)
        name in ZxDsl11.ALL -> ZxDsl11.spec(name)
        name in ZxDsl10.ALL -> ZxDsl10.spec(name)
        name in ZxDsl9.ALL -> ZxDsl9.spec(name)
        name in ZxDsl8.ALL -> ZxDsl8.spec(name)
        name in ZxDsl7.ALL -> ZxDsl7.spec(name)
        name in ZxDsl6.ALL -> ZxDsl6.spec(name)
        name in ZxDsl5.ALL -> ZxDsl5.spec(name)
        name in ZxDsl4.ALL -> ZxDsl4.spec(name)
        name in ZxDsl3.ALL || name in ZxDsl3.CONTAINERS -> ZxDsl3.spec(name)
        name in ZxDsl2.ALL -> ZxDsl2.spec(name)
        else -> ZxDsl2.specLine(name)
    }

    private fun sampleOf(name: String): String = when {
        name in ZxDsl40.ALL -> ZxDsl40.sample(name)
        name in ZxDsl39.ALL -> ZxDsl39.sample(name)
        name in ZxDsl38.ALL -> ZxDsl38.sample(name)
        name in ZxDsl37.ALL -> ZxDsl37.sample(name)
        name in ZxDsl36.ALL -> ZxDsl36.sample(name)
        name in ZxDsl35.ALL -> ZxDsl35.sample(name)
        name in ZxDsl34.ALL -> ZxDsl34.sample(name)
        name in ZxDsl33.ALL -> ZxDsl33.sample(name)
        name in ZxDsl32.ALL -> ZxDsl32.sample(name)
        name in ZxDsl31.ALL -> ZxDsl31.sample(name)
        name in ZxDsl30.ALL -> ZxDsl30.sample(name)
        name in ZxDsl29.ALL -> ZxDsl29.sample(name)
        name in ZxDsl28.ALL -> ZxDsl28.sample(name)
        name in ZxDsl27.ALL -> ZxDsl27.sample(name)
        name in ZxDsl26.ALL -> ZxDsl26.sample(name)
        name in ZxDsl25.ALL -> ZxDsl25.sample(name)
        name in ZxDsl24.ALL -> ZxDsl24.sample(name)
        name in ZxDsl23.ALL -> ZxDsl23.sample(name)
        name in ZxDsl22.ALL -> ZxDsl22.sample(name)
        name in ZxDsl21.ALL -> ZxDsl21.sample(name)
        name in ZxDsl20.ALL -> ZxDsl20.sample(name)
        name in ZxDsl19.ALL -> ZxDsl19.sample(name)
        name in ZxDsl18.ALL -> ZxDsl18.sample(name)
        name in ZxDsl17.ALL -> ZxDsl17.sample(name)
        name in ZxDsl16.ALL -> ZxDsl16.sample(name)
        name in ZxDsl15.ALL -> ZxDsl15.sample(name)
        name in ZxDsl14.ALL -> ZxDsl14.sample(name)
        name in ZxDsl13.ALL -> ZxDsl13.sample(name)
        name in ZxDsl12.ALL -> ZxDsl12.sample(name)
        name in ZxDsl11.ALL -> ZxDsl11.sample(name)
        name in ZxDsl10.ALL -> ZxDsl10.sample(name)
        name in ZxDsl9.ALL -> ZxDsl9.sample(name)
        name in ZxDsl8.ALL -> ZxDsl8.sample(name)
        name in ZxDsl7.ALL -> ZxDsl7.sample(name)
        name in ZxDsl6.ALL -> ZxDsl6.sample(name)
        name in ZxDsl5.ALL -> ZxDsl5.sample(name)
        name in ZxDsl4.ALL -> ZxDsl4.sample(name)
        name in ZxDsl3.ALL || name in ZxDsl3.CONTAINERS -> ZxDsl3.sample(name)
        name in ZxDsl2.ALL -> ZxDsl2.sample(name)
        else -> ZxDsl2.sample(name)
    }
}
