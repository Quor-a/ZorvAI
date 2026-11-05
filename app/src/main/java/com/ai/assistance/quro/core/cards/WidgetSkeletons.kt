package com.ai.assistance.quro.core.cards

/**
 * 🔴 可视化小卡片 · 统一参考样板库（2026-11-05 重写；2026-11-06 降级为参考）
 *
 * ## 为什么要有这个文件
 * 「AI 永远写不出可视化小卡片、老是用动态UI组件/富组件顶替」的根因不是渲染能力不够：
 * `visual_card` 工具的卡壳、WebView、落盘、Compose 渲染全都通，但模型侧
 *   1. 提示词里 `visual_card` 字面出现 0 次，「可视化小卡片」被独占绑定到
 *      ```quro-card 的 Canvas DSL（字段多、只有 2 个示例、与 HTML 设计稿正交）；
 *   2. RAG 把「小卡片」映射到 `ui_card` / `ui_widget` 富组件；
 *   3. 工具目录把 `visual_card` 归到「可视化交互/弹窗」类，PROGRESSIVE 下默认不下发。
 * 结果模型既看不到正确通道，也**没有任何可参考的结构** —— 只能退回富组件/动态 UI。
 *
 * ## 🔴 2026-11-06 定位反转（用户拍板）
 * 「要的是 AI 可以自己写出来没有的小卡片。」这批样板曾被表述成「必经两步、结构不发明」，
 * 导致模型只会从 13 份里挑一张填数，库里没有的场景写不出来。现在：`visual_card` 的
 * html 参数**本身就是自由创作空间**，本文件只是「没把握时的观感基准」——可取来改造、
 * 组合、借鉴局部，但不是白名单，场景对不上就自己写。
 *
 * ## 样板规则（改本文件时必须遵守）
 * - 主文字用 `color:CanvasText`（亮暗自适应），线/底一律 `rgba(128,138,160,.x)` 中性灰；
 * - 强调色固定 hex（蓝 #4f6bff / 青 #18b89a / 橙 #ff9a4c / 红 #ff5f6d）；
 * - 零外部依赖、不写 `<script>`、宽高只用 flex/grid + 百分比；
 * - 只写**卡体**：圆角 20 / 头部 / 主题 / viewport 由宿主卡壳（VisualMiniCardView）提供；
 * - `{{...}}` 是样板里的数据位示例，禁止放真实业务数据。
 *
 * 本文件是纯字符串、无 Android 依赖，可 JVM 单测。
 */
object WidgetSkeletons {

    /** 卡体统一根节点：color-scheme 让 WebView 在亮/暗主题下自动取正确文字色。 */
    private const val BASE =
        "<div class=\"wc\" style=\"color-scheme:light dark;color:CanvasText;" +
            "font:13px/1.5 -apple-system,BlinkMacSystemFont,sans-serif;padding:2px 2px 6px\">"

    /** 一行式骨架定义。size 仅供目录展示（1×1 / 2×1）。 */
    data class Skeleton(
        val kind: String,
        val name: String,
        val label: String,
        val size: String,
        val useFor: String,
        val html: String,
    )

    private val LIST: List<Skeleton> = listOf(
        Skeleton(
            "weather", "天气", "WEATHER", "2×1",
            "天气/气温/未来几日预报",
            BASE +
                "<div style=\"display:flex;justify-content:space-between;align-items:flex-start\">" +
                "<b style=\"font-size:13px\">📍 {{城市}}</b>" +
                "<span style=\"opacity:.55;font:600 10px ui-monospace,monospace\">{{更新时刻}}</span></div>" +
                "<div style=\"display:flex;align-items:center;gap:16px;margin-top:12px\">" +
                "<div style=\"font-size:54px;font-weight:300;line-height:1;letter-spacing:-.05em\">{{温度}}°</div>" +
                "<div style=\"font-size:12px;line-height:1.8;opacity:.75\"><b style=\"font-size:15px;opacity:1\">{{天气}}</b><br>" +
                "{{体感}} · {{湿度}}<br>{{风力}} · {{紫外线}}</div></div>" +
                "<div style=\"display:grid;grid-template-columns:repeat(5,1fr);gap:6px;margin-top:14px\">" +
                (1..5).joinToString("") { i ->
                    "<div style=\"text-align:center;padding:9px 2px;border-radius:12px;background:rgba(128,138,160,.12);font-size:11px\">" +
                        "<span style=\"opacity:.6\">{{日$i}}</span><br><b>{{高$i}}°</b><br><span style=\"opacity:.55\">{{低$i}}°</span></div>"
                } +
                "</div></div>",
        ),
        Skeleton(
            "news", "新闻", "NEWS", "1×1",
            "新闻/资讯/多条摘要列表",
            BASE +
                "<div style=\"font-size:12px;font-weight:700\">{{栏目标题}}</div>" +
                "<div style=\"opacity:.5;font:600 9px ui-monospace,monospace;letter-spacing:.1em;margin-top:3px\">{{EN标签}}</div>" +
                "<div style=\"display:flex;flex-direction:column;gap:9px;margin-top:11px\">" +
                listOf(
                    "linear-gradient(135deg,#ff9a6c,#ff5f6d)",
                    "linear-gradient(135deg,#6b8cff,#8b5cf6)",
                    "linear-gradient(135deg,#22c1a5,#0e7f9e)",
                ).mapIndexed { i, bg ->
                    "<div style=\"display:flex;gap:10px\">" +
                        "<div style=\"width:36px;height:36px;border-radius:10px;flex:none;background:$bg\"></div>" +
                        "<div style=\"min-width:0\"><span style=\"color:#4f6bff;font-size:9px;font-weight:700\">{{标签${i + 1}}}</span>" +
                        "<div style=\"font-size:11px;font-weight:600;line-height:1.35\">{{标题${i + 1}}}</div>" +
                        "<span style=\"opacity:.5;font:9px ui-monospace,monospace\">{{时间${i + 1}}}</span></div></div>"
                }.joinToString("") +
                "</div></div>",
        ),
        Skeleton(
            "calendar", "日程", "CALENDAR", "1×1",
            "今日日程/会议/待办时间点",
            BASE +
                "<div style=\"display:flex;justify-content:space-between;align-items:flex-start\">" +
                "<div><div style=\"font-size:52px;font-weight:750;line-height:.9;letter-spacing:-.06em\">{{日}}</div>" +
                "<div style=\"opacity:.6;font-size:11px;margin-top:8px;font-weight:600\">{{月}} · {{星期}} · {{第几周}}</div></div>" +
                "<span style=\"background:#4f6bff;color:#fff;font:700 10px ui-monospace,monospace;padding:6px 10px;border-radius:9px\">{{角标}}</span></div>" +
                "<div style=\"display:flex;flex-direction:column;gap:8px;margin-top:16px\">" +
                listOf("#4f6bff", "#18b89a").mapIndexed { i, c ->
                    "<div style=\"display:flex;gap:10px;align-items:center;padding:9px 10px;border-radius:11px;background:rgba(128,138,160,.10)\">" +
                        "<div style=\"width:3px;height:26px;border-radius:3px;background:$c;flex:none\"></div>" +
                        "<span style=\"font:600 10px ui-monospace,monospace;opacity:.6;width:38px;flex:none\">{{时${i + 1}}}</span>" +
                        "<div style=\"font-size:11px;font-weight:600\">{{事件${i + 1}}}<br>" +
                        "<span style=\"opacity:.55;font-weight:400;font-size:9.5px\">{{备注${i + 1}}}</span></div></div>"
                }.joinToString("") +
                "</div><div style=\"margin-top:12px;font-size:10.5px;opacity:.6\">{{下一项提示}}</div></div>",
        ),
        Skeleton(
            "terminal", "终端", "TERMINAL", "2×1",
            "终端输出/构建日志/命令结果",
            "<div class=\"wc\" style=\"color-scheme:dark;background:#0d1119;color:#c9d4e5;" +
                "font:11.5px/1.62 ui-monospace,Menlo,Consolas,monospace;padding:12px 16px;border-radius:12px\">" +
                "<div style=\"color:#5d6b82;font-size:10px;margin-bottom:8px\">{{工作目录}} — {{shell}}</div>" +
                "<div><span style=\"color:#3ddc97\">➜</span> <span style=\"color:#6fa8ff\">{{工程名}}</span> " +
                "<span style=\"color:#5d6b82\">{{命令1}}</span></div>" +
                "<div style=\"color:#5d6b82\">{{输出1}}</div><div>{{输出2}}</div>" +
                "<div style=\"margin-top:4px\"><span style=\"color:#3ddc97\">➜</span> " +
                "<span style=\"color:#6fa8ff\">{{工程名}}</span> {{命令2}}</div>" +
                "<div><span style=\"color:#3ddc97\">✓</span> {{结果}} <span style=\"color:#5d6b82\">{{耗时}}</span></div>" +
                "<div><span style=\"color:#f0c674\">{{产物}}</span> <span style=\"color:#b48cff\">{{大小}}</span></div></div>",
        ),
        Skeleton(
            "music", "播放器", "MUSIC", "1×1",
            "正在播放/媒体状态",
            BASE +
                "<div style=\"display:flex;gap:13px;align-items:center\">" +
                "<div style=\"width:58px;height:58px;border-radius:14px;flex:none;background:linear-gradient(140deg,#8b5cf6,#4f6bff 55%,#18b89a)\"></div>" +
                "<div style=\"min-width:0\"><div style=\"font-size:14px;font-weight:700\">{{曲名}}</div>" +
                "<div style=\"font-size:11px;opacity:.6;margin-top:4px\">{{艺人}} / {{专辑}}</div>" +
                "<div style=\"font:600 9px ui-monospace,monospace;opacity:.5;margin-top:7px\">{{TRACK}}</div></div></div>" +
                "<div style=\"height:5px;border-radius:5px;background:rgba(128,138,160,.20);margin:16px 0 7px;overflow:hidden\">" +
                "<div style=\"height:100%;width:{{进度}}%;border-radius:5px;background:linear-gradient(90deg,#8b5cf6,#4f6bff)\"></div></div>" +
                "<div style=\"display:flex;justify-content:space-between;font:9.5px ui-monospace,monospace;opacity:.55\">" +
                "<span>{{已播}}</span><span>-{{剩余}}</span></div>" +
                "<div style=\"display:flex;justify-content:center;align-items:center;gap:22px;margin-top:13px;font-size:15px;opacity:.75\">" +
                "<span>◀◀</span><span style=\"width:36px;height:36px;border-radius:50%;background:Canvas;color:CanvasText;display:grid;place-items:center\">▶</span><span>▶▶</span></div></div>",
        ),
        Skeleton(
            "market", "行情", "MARKET", "1×1",
            "指数/价格/涨跌走势（A 股口径：涨红跌绿）",
            BASE +
                "<div style=\"font-size:27px;font-weight:750;letter-spacing:-.03em;line-height:1;margin-top:6px\">{{点位}}</div>" +
                "<div style=\"font:700 11px ui-monospace,monospace;color:#e5484d;margin-top:7px\">▲ {{涨跌额}}　{{涨跌幅}}</div>" +
                "<svg viewBox=\"0 0 240 66\" preserveAspectRatio=\"none\" style=\"width:100%;height:66px;margin-top:8px\">" +
                "<path d=\"{{折线路径}} L240 66 L0 66Z\" fill=\"#e5484d\" opacity=\".16\"/>" +
                "<path d=\"{{折线路径}}\" fill=\"none\" stroke=\"#e5484d\" stroke-width=\"2\" stroke-linejoin=\"round\"/></svg>" +
                "<div style=\"display:flex;gap:18px;margin-top:8px;font:9.5px/1.6 ui-monospace,monospace;opacity:.6\">" +
                "<div>成交额<br><b style=\"opacity:1;font-size:11px\">{{成交额}}</b></div>" +
                "<div>振幅<br><b style=\"opacity:1;font-size:11px\">{{振幅}}</b></div>" +
                "<div>更新<br><b style=\"opacity:1;font-size:11px\">{{时刻}}</b></div></div></div>",
        ),
        Skeleton(
            "todo", "待办", "TODO", "1×1",
            "待办清单/完成进度",
            BASE +
                "<div style=\"display:flex;justify-content:space-between;font-size:12px;font-weight:700\">" +
                "<span>{{标题}}</span><span style=\"opacity:.5;font:600 10px ui-monospace,monospace\">{{完成数}} / {{总数}}</span></div>" +
                "<div style=\"display:flex;flex-direction:column;gap:9px;margin-top:13px;font-size:11.5px;font-weight:600\">" +
                "<div style=\"display:flex;align-items:center;gap:9px\"><span style=\"width:16px;height:16px;border-radius:5px;flex:none;background:#4f6bff\"></span>" +
                "<span style=\"opacity:.5;text-decoration:line-through\">{{已完成1}}</span>" +
                "<span style=\"margin-left:auto;opacity:.45;font:9px ui-monospace,monospace\">{{时间1}}</span></div>" +
                (1..2).joinToString("") { i ->
                    "<div style=\"display:flex;align-items:center;gap:9px\">" +
                        "<span style=\"width:16px;height:16px;border-radius:5px;flex:none;border:1.6px solid rgba(128,138,160,.45)\"></span>" +
                        "<span>{{待办$i}}</span><span style=\"margin-left:auto;opacity:.45;font:9px ui-monospace,monospace\">{{时间${i + 1}}}</span></div>"
                } +
                "</div><div style=\"display:flex;align-items:center;gap:10px;margin-top:16px\">" +
                "<div style=\"flex:1;height:5px;border-radius:5px;background:rgba(128,138,160,.20);overflow:hidden\">" +
                "<div style=\"height:100%;width:{{进度}}%;background:#4f6bff\"></div></div>" +
                "<span style=\"font:600 10px ui-monospace,monospace;opacity:.6\">{{进度}}%</span></div></div>",
        ),
        Skeleton(
            "monitor", "监控", "MONITOR", "1×1",
            "CPU/内存/磁盘/网络等指标条",
            BASE +
                "<div style=\"display:flex;flex-direction:column;gap:13px;margin-top:10px;font:600 10px ui-monospace,monospace\">" +
                listOf(
                    "linear-gradient(90deg,#4f6bff,#8b5cf6)",
                    "linear-gradient(90deg,#ffb24c,#ff7a59)",
                    "linear-gradient(90deg,#18b89a,#5ad6b0)",
                    "linear-gradient(90deg,#4f6bff,#8b5cf6)",
                ).mapIndexed { i, g ->
                    "<div style=\"display:flex;align-items:center;gap:9px;opacity:.65\">" +
                        "<span style=\"width:34px;flex:none\">{{名${i + 1}}}</span>" +
                        "<div style=\"flex:1;height:6px;border-radius:6px;background:rgba(128,138,160,.20);overflow:hidden\">" +
                        "<div style=\"height:100%;width:{{值${i + 1}}}%;background:$g\"></div></div>" +
                        "<b style=\"width:34px;text-align:right;opacity:1\">{{显示${i + 1}}}</b></div>"
                }.joinToString("") +
                "</div><div style=\"display:flex;align-items:center;gap:7px;margin-top:16px;font-size:10.5px;opacity:.65\">" +
                "<span style=\"width:7px;height:7px;border-radius:50%;background:#18b89a\"></span>{{状态}}" +
                "<b style=\"margin-left:auto;font:600 10px ui-monospace,monospace;opacity:1\">{{UPTIME}}</b></div></div>",
        ),
        Skeleton(
            "location", "位置", "LOCATION", "1×1",
            "当前位置/地点摘要（内联 SVG 路网，不引地图 SDK）",
            BASE +
                "<svg viewBox=\"0 0 260 150\" preserveAspectRatio=\"xMidYMid slice\" " +
                "style=\"width:100%;height:140px;border-radius:10px;background:rgba(128,138,160,.10)\">" +
                "<g stroke=\"#4f6bff\" stroke-opacity=\".16\" stroke-width=\"10\" fill=\"none\" stroke-linecap=\"round\">" +
                "<path d=\"M-10 40 C60 52 100 18 270 34\"/><path d=\"M-10 118 C70 100 150 138 270 112\"/>" +
                "<path d=\"M84 -10 C96 50 60 90 78 160\"/></g>" +
                "<g fill=\"#4f6bff\" fill-opacity=\".12\"><rect x=\"112\" y=\"52\" width=\"46\" height=\"30\" rx=\"4\"/>" +
                "<rect x=\"170\" y=\"60\" width=\"38\" height=\"26\" rx=\"4\"/><rect x=\"105\" y=\"92\" width=\"34\" height=\"24\" rx=\"4\"/></g>" +
                "<circle cx=\"130\" cy=\"66\" r=\"7\" fill=\"#ff5f6d\" stroke=\"#fff\" stroke-width=\"3\"/>" +
                "<circle cx=\"130\" cy=\"66\" r=\"18\" fill=\"none\" stroke=\"#ff5f6d\" stroke-opacity=\".45\" stroke-width=\"2\"/></svg>" +
                "<div style=\"display:flex;justify-content:space-between;align-items:center;margin-top:11px;font-size:11px;font-weight:700\">" +
                "<span>{{地点}}</span><span style=\"opacity:.55;font:9.5px ui-monospace,monospace\">{{精度}} · {{时刻}}</span></div></div>",
        ),
        Skeleton(
            "battery", "电量", "BATTERY", "1×1",
            "设备电量/环形进度/多设备状态",
            BASE +
                "<div style=\"display:flex;align-items:center;gap:16px;margin-top:8px\">" +
                "<div style=\"width:104px;height:104px;position:relative;flex:none\">" +
                "<svg viewBox=\"0 0 120 120\" style=\"width:100%;height:100%;transform:rotate(-90deg)\">" +
                "<circle cx=\"60\" cy=\"60\" r=\"50\" fill=\"none\" stroke=\"rgba(128,138,160,.20)\" stroke-width=\"10\"/>" +
                "<circle cx=\"60\" cy=\"60\" r=\"50\" fill=\"none\" stroke=\"#18b89a\" stroke-width=\"10\" stroke-linecap=\"round\" " +
                "stroke-dasharray=\"314\" stroke-dashoffset=\"{{环偏移}}\"/></svg>" +
                "<div style=\"position:absolute;inset:0;display:grid;place-content:center;text-align:center\">" +
                "<b style=\"font-size:22px\">{{平均}}%</b><span style=\"opacity:.55;font-size:9px\">AVG</span></div></div>" +
                "<div style=\"flex:1;display:flex;flex-direction:column;gap:9px;font-size:10.5px;font-weight:600;min-width:0\">" +
                (1..3).joinToString("") { i ->
                    "<div style=\"display:flex;gap:8px;align-items:center\">{{图标$i}} {{设备$i}}" +
                        "<span style=\"margin-left:auto;color:#18b89a;font:600 9px ui-monospace,monospace\">{{电量$i}}%</span></div>"
                } +
                "</div></div><div style=\"margin-top:14px;font-size:10.5px;opacity:.6\">{{续航说明}}</div></div>",
        ),
        Skeleton(
            "shortcuts", "快捷", "SHORTCUTS", "2×1",
            "快捷入口四宫格/功能导航",
            BASE +
                "<div style=\"display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:12px;text-align:center\">" +
                listOf(
                    "linear-gradient(140deg,#4f6bff,#8b5cf6)",
                    "linear-gradient(140deg,#18b89a,#0e9f8f)",
                    "linear-gradient(140deg,#ff9a4c,#ff5f6d)",
                    "linear-gradient(140deg,#5b6b8c,#2d3a55)",
                ).mapIndexed { i, g ->
                    "<div style=\"border:1px solid rgba(128,138,160,.18);border-radius:15px;background:rgba(128,138,160,.08);padding:14px 6px 11px\">" +
                        "<div style=\"width:34px;height:34px;margin:0 auto 9px;border-radius:11px;background:$g\"></div>" +
                        "<span style=\"font-size:10px;font-weight:600\">{{名${i + 1}}}</span>" +
                        "<span style=\"display:block;opacity:.5;font:8.5px ui-monospace,monospace;margin-top:4px\">{{EN${i + 1}}}</span></div>"
                }.joinToString("") +
                "</div><div style=\"text-align:center;margin-top:14px;opacity:.45;font:9px ui-monospace,monospace\">{{脚注}}</div></div>",
        ),
        Skeleton(
            "clock", "时钟", "CLOCK", "1×1",
            "世界时钟/时间/日期",
            BASE +
                "<div style=\"font:300 40px/1 ui-monospace,Menlo,monospace;letter-spacing:-.045em;margin-top:10px\">" +
                "{{时}}:{{分}}<span style=\"font-size:15px;opacity:.5;margin-left:5px\">{{秒}}</span></div>" +
                "<div style=\"font-size:11px;opacity:.6;margin-top:8px;font-weight:600\">{{年}}年{{月}}月{{日}}日 {{星期}} · {{时区}}</div>" +
                "<div style=\"border-top:1px solid rgba(128,138,160,.22);margin-top:16px;padding-top:10px;" +
                "display:flex;flex-direction:column;gap:8px;font-size:10.5px;font-weight:600\">" +
                listOf("#4f6bff", "#18b89a", "#ff9a4c").mapIndexed { i, c ->
                    "<div style=\"display:flex;align-items:center\">" +
                        "<span style=\"width:5px;height:5px;border-radius:50%;background:$c;margin-right:8px\"></span>" +
                        "<span style=\"opacity:.6\">{{城市${i + 1}}}</span>" +
                        "<b style=\"margin-left:auto;font:600 10.5px ui-monospace,monospace\">{{时间${i + 1}}}</b></div>"
                }.joinToString("") +
                "</div></div>",
        ),
        Skeleton(
            "quote", "摘录", "QUOTE", "1×1",
            "一句话结论/引用/摘要/告警（最简骨架）",
            BASE +
                "<div style=\"font:750 46px/1 Georgia,serif;color:#4f6bff;opacity:.4\">“</div>" +
                "<div style=\"font-size:14px;line-height:1.75;font-weight:600;margin-top:2px\">{{正文第一行}}<br>{{正文第二行}}</div>" +
                "<div style=\"display:flex;align-items:center;gap:9px;margin-top:18px;font-size:10px;opacity:.6;font-weight:600\">" +
                "<span style=\"width:22px;height:22px;border-radius:50%;background:linear-gradient(135deg,#ff9a6c,#8b5cf6)\"></span>" +
                "{{出处}}<span style=\"margin-left:auto;font:500 9px ui-monospace,monospace;letter-spacing:.08em\">{{标签}}</span></div></div>",
        ),
    )

    private val BY_KIND: Map<String, Skeleton> = LIST.associateBy { it.kind }

    /** 全部 13 种 kind。 */
    val kinds: List<String> get() = LIST.map { it.kind }

    /** 目录文本：给 `visual_card_skeleton` 工具与系统提示词共用。
     *  🔴 2026-11-06 定位反转：自由写是主路，本目录只是「参考样板」清单，不再是必经步骤。 */
    fun catalog(): String = buildString {
        append("可视化小卡片参考样板（共 ${LIST.size} 种，visual_card 的 html 可自由写任意场景，拿不准结构时才取样板改造）：\n")
        LIST.forEachIndexed { i, s ->
            append("S${(i + 1).toString().padStart(2, '0')} ${s.kind.padEnd(10)} ${s.name.padEnd(4)} ${s.size}  ${s.useFor}\n")
        }
        append("\n取回后：把 {{数据位}} 换成真实内容，作为 visual_card 的 html 参数；卡壳（标题/圆角/主题）宿主已给，不要重复画。场景对不上就大胆自己写，不要硬套。")
    }

    /** 取一份骨架；未知 kind 返回 null（调用方回目录）。 */
    fun get(kind: String): Skeleton? = BY_KIND[kind.trim().lowercase()]

    /** 提示词注入段：目录 + 3 份高频样板全文（保证模型不查工具也有可参考的观感基准）。 */
    fun promptSection(): String = buildString {
        append(catalog()).append("\n\n")
        append("写作规则：主文字 CanvasText 自适应亮暗；线/底用 rgba(128,138,160,.x) 或主题 token；强调色固定 hex；零外部依赖、不写 <script>；只写卡体，不画第二层卡片边框；宽度全用 flex/grid + 百分比。任何场景都能自由写，样板只是观感基准。\n\n")
        listOf("clock", "weather", "quote").forEach { k ->
            val s = BY_KIND[k] ?: return@forEach
            append("【${s.name} · $k 样板可参考】\n").append(s.html).append("\n\n")
        }
    }
}
