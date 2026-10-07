# -*- coding: utf-8 -*-
"""第三批 9 种组件写入 CardSdk 名册（名册 92 -> 101）。

类目归属：
- decision / confirm  -> action（AI 征询决策，是交互动作）
- sankey / funnel / waterfall / quadrant -> data（数据可视化进阶）
- matrix / feed / graph / section     -> data（结构化明细与关系）
"""
import io, os, sys

P = "app/src/main/java/com/ai/assistance/quro/core/cards/CardSdk.kt"
src = io.open(P, encoding="utf-8").read()

ANCHOR = chr(10) + '    }'

BLOCK = '''
        // ════════════════ AI 征询决策（v1400 第三批） ════════════════
        add(CardSpec("decision", "action", "AI 卡住时征询用户拍一个决定（收集约束，非推进对话）。与 quickreply 的区别：这个是解除 AI 的阻塞，allowCustom 必须为 true 以免用户被逼在给定选项里；典型如「退款退原卡还是余额」「要不要覆盖文件」", """{"type":"decision","title":"需要你确认","question":"这笔 $86.40 退到哪里？","context":"订单 #4821 已通过审核，退款方式会影响到账时间","required":true,"allowCustom":true,"customHint":"或者直接输入你的选择","options":[{"label":"退原卡","value":"refund_card","detail":"Visa ····4242，3-5 个工作日","recommended":true},{"label":"存账户余额","value":"store_credit","detail":"即时到账，不可提现"}]}""") { s ->
            QuroChatCard.DecisionCard(
                s.id(), s.title(),
                s.optString("question", ""),
                (0 until s.arrLen("options")).map { i ->
                    val o = s.objAt("options", i)
                    DecisionOpt(o.optString("label", ""), o.optString("value", o.optString("label", "")),
                        o.optString("detail", ""), o.optBoolean("recommended", false))
                },
                s.optBoolean("allowCustom", true), s.optBoolean("required", true),
                s.optString("customHint", "或者直接输入你的选择"), s.optString("context", ""),
            )
        })
        add(CardSpec("confirm", "action", "危险或不可逆操作前的最后一道闸（做/不做二元确认，默认焦点在取消）。与 decision 的区别：decision 是多选一的选择题，confirm 是安全闸门；删除文件、清空数据、付款这类操作都该用它", """{"type":"confirm","title":"确认删除","message":"将永久删除 3 个文件，此操作不可撤销。","detail":"docs/a.md、docs/b.md、docs/c.md","confirmLabel":"删除","cancelLabel":"再想想","danger":true}""") { s ->
            QuroChatCard.ConfirmCard(
                s.id(), s.title(), s.optString("message", ""),
                s.optString("confirmLabel", "确认"), s.optString("cancelLabel", "取消"),
                s.optBoolean("danger", false), s.optString("detail", ""),
            )
        })

        // ════════════════ 数据可视化进阶（v1400 第三批） ════════════════
        add(CardSpec("sankey", "data", "桑基图：表达流向（A 到 B 的量，可跨多层）。与 pie 的区别：pie 是占比无流向，sankey 有流向与链路；典型如用户来源→页面→转化行为、预算从部门到科目", """{"type":"sankey","title":"用户流向","unit":"人","nodes":[{"id":"src","label":"搜索"},{"id":"home","label":"首页"},{"id":"pay","label":"支付"},{"id":"exit","label":"跳出"}],"links":[{"from":"src","to":"home","value":1200},{"from":"src","to":"exit","value":300},{"from":"home","to":"pay","value":420},{"from":"home","to":"exit","value":780}]}""") { s ->
            QuroChatCard.SankeyCard(
                s.id(), s.title(),
                (0 until s.arrLen("nodes")).map { i ->
                    val o = s.objAt("nodes", i)
                    SankeyNode(o.optString("id", ""), o.optString("label", o.optString("id", "")))
                },
                (0 until s.arrLen("links")).map { i ->
                    val o = s.objAt("links", i)
                    SankeyLink(o.optString("from", ""), o.optString("to", ""), o.optDouble("value", 0.0))
                },
                s.optString("unit", ""),
            )
        })
        add(CardSpec("funnel", "data", "漏斗：有序多级的转化流失。与 gauge/progress 的区别：那俩是单值，funnel 是多级且能看每级掉多少；典型如注册漏斗、下单漏斗、招聘各环节通过率", """{"type":"funnel","title":"注册转化","unit":"人","showRate":true,"steps":[{"label":"访问","value":10000},{"label":"注册","value":3200,"hint":"表单太长"},{"label":"验证","value":2800},{"label":"完成资料","value":1900},{"label":"激活","value":1450}]}""") { s ->
            QuroChatCard.FunnelCard(
                s.id(), s.title(),
                (0 until s.arrLen("steps")).map { i ->
                    val o = s.objAt("steps", i)
                    FunnelStep(o.optString("label", ""), o.optDouble("value", 0.0), o.optString("hint", ""))
                },
                s.optBoolean("showRate", true), s.optString("unit", ""),
            )
        })
        add(CardSpec("waterfall", "data", "瀑布图：变化归因（从起点累加，每根柱是一个可正可负的增量）。与 bar/stackedbar 的区别：那俩是并列比较，waterfall 是累加推导；典型如预算vs实际差异拆解、利润变动归因、增长来源", """{"type":"waterfall","title":"Q2 利润归因","unit":"万元","start":100,"steps":[{"label":"Q1","value":100,"isTotal":true},{"label":"新增客户","delta":35},{"label":"流失","delta":-18},{"label":"涨价","delta":12},{"label":"成本上涨","delta":-9},{"label":"Q2","value":120,"isTotal":true}]}""") { s ->
            QuroChatCard.WaterfallCard(
                s.id(), s.title(),
                (0 until s.arrLen("steps")).map { i ->
                    val o = s.objAt("steps", i)
                    WaterfallStep(o.optString("label", ""), o.optDouble("delta", 0.0),
                        o.optDouble("value", 0.0), o.optBoolean("isTotal", false))
                },
                if (s.has("start")) s.optDouble("start", 0.0) else null,
                s.optString("unit", ""),
            )
        })
        add(CardSpec("quadrant", "data", "四象限决策矩阵：两轴分档定位。与 radar 的区别：radar 是同维度多指标轮廓，quadrant 是两个维度分档；典型如优先级矩阵（影响×成本）、用户分群、风险评级", """{"type":"quadrant","title":"功能优先级","xLabel":"开发成本","yLabel":"用户影响","axisMax":100,"quadrants":["低优先","快赢","战略","谨慎投入"],"items":[{"label":"离线模式","x":20,"y":90,"tag":"快赢"},{"label":"社交登录","x":35,"y":70,"tag":"快赢"},{"label":"AI 助手","x":85,"y":95,"tag":"战略"},{"label":"主题皮肤","x":25,"y":15,"tag":"低优先"}]}""") { s ->
            QuroChatCard.QuadrantCard(
                s.id(), s.title(),
                (0 until s.arrLen("items")).map { i ->
                    val o = s.objAt("items", i)
                    QuadrantItem(o.optString("label", ""), o.optDouble("x", 0.0), o.optDouble("y", 0.0), o.optString("tag", ""))
                },
                s.optString("xLabel", ""), s.optString("yLabel", ""), s.optDouble("axisMax", 100.0),
                (0 until s.arrLen("quadrants")).mapNotNull { s.objOrNull("quadrants", it)?.optString("value", "") },
            )
        })
        add(CardSpec("matrix", "data", "逐维对照打分表（两个方案/实体在同一批指标上比高低，差值自动着色）。与 compare 的区别：compare 是左右两张卡并列（结构可不同），matrix 是同维度逐行打分；better 字段说明哪方向为优（high/low/null）", """{"type":"matrix","title":"方案对比","leftLabel":"方案 A","rightLabel":"方案 B","showDiff":true,"unit":"ms","rows":[{"label":"平均延迟","left":"120","right":"80","better":"low"},{"label":"月费","left":"免费","right":"¥99","better":"low"},{"label":"可用区","left":"1","right":"3","better":"high"},{"label":"上线时间","left":"2026-01","right":"2026-04","better":null}]}""") { s ->
            QuroChatCard.MatrixCard(
                s.id(), s.title(),
                s.optString("leftLabel", "方案 A"), s.optString("rightLabel", "方案 B"),
                (0 until s.arrLen("rows")).map { i ->
                    val o = s.objAt("rows", i)
                    MatrixRow(o.optString("label", ""), o.optString("left", ""), o.optString("right", ""),
                        if (o.has("better")) o.optString("better", "").ifBlank { null } else "high")
                },
                s.optBoolean("showDiff", true), s.optString("unit", ""),
            )
        })
        add(CardSpec("feed", "data", "事件流水：带时间戳的已发生事实（level: info/success/warning/error 决定圆点颜色，出错项一眼可见）。与 timeline 的区别：timeline 是计划里程碑（未来），feed 是已发生的事实流（日志/提交/账单变动）", """{"type":"feed","title":"部署日志","source":"服务器","items":[{"time":"12:03:41","text":"构建成功","level":"success","actor":"ci"},{"time":"12:04:02","text":"拉取镜像耗时 8.2s","level":"info"},{"time":"12:04:15","text":"数据库迁移超时","level":"error","actor":"migrate"},{"time":"12:05:00","text":"已回滚到上一版本","level":"warning"}]}""") { s ->
            QuroChatCard.FeedCard(
                s.id(), s.title(),
                (0 until s.arrLen("items")).map { i ->
                    val o = s.objAt("items", i)
                    FeedItem(o.optString("time", ""), o.optString("text", ""),
                        o.optString("level", "info"), o.optString("actor", ""))
                },
                s.optString("source", ""),
            )
        })
        add(CardSpec("graph", "data", "关系图：可多父可成环的任意图（与 tree/cardui 的树不同）。节点用显式网格坐标 col/row 摆放（缺省按出现顺序顺排），不做力导向布局以保证结果可预期；shape: node/service/db/queue；edge kind: flow/dep/back", """{"type":"graph","title":"服务依赖","nodes":[{"label":"网关","col":0,"row":1,"shape":"service"},{"label":"订单","col":1,"row":0,"shape":"service"},{"label":"库存","col":1,"row":2,"shape":"db"},{"label":"消息队列","col":2,"row":1,"shape":"queue"}],"edges":[{"from":"网关","to":"订单","label":"HTTP"},{"from":"订单","to":"库存","label":"扣减","kind":"dep"},{"from":"订单","to":"消息队列","label":"投递","kind":"dep"}]}""") { s ->
            QuroChatCard.GraphCard(
                s.id(), s.title(),
                (0 until s.arrLen("nodes")).map { i ->
                    val o = s.objAt("nodes", i)
                    GraphNode(o.optString("label", ""), o.optInt("col", -1), o.optInt("row", -1), o.optString("shape", "node"))
                },
                (0 until s.arrLen("edges")).map { i ->
                    val o = s.objAt("edges", i)
                    GraphEdge(o.optString("from", ""), o.optString("to", ""), o.optString("label", ""), o.optString("kind", "flow"))
                },
            )
        })
        add(CardSpec("section", "data", "分区明细：分组展示结构化内容（每组有小标题，组内才是键值对）。与 keyvalue 的区别：keyvalue 是单层平铺，本卡是多组分区（订单的商品/收货/支付三段明细）", """{"type":"section","title":"订单 #4821","sections":[{"title":"商品","rows":[["机械键盘","¥499"],["键帽套装","¥159"]]},{"title":"收货","rows":[["收件人","张三"],["地址","杭州市西湖区…"]]},{"title":"支付","rows":[["方式","余额"],["实付","¥658"]]}]}""") { s ->
            QuroChatCard.SectionCard(
                s.id(), s.title(),
                (0 until s.arrLen("sections")).map { i ->
                    val o = s.objAt("sections", i)
                    val ra = o.optJSONArray("rows")
                    SectionSection(o.optString("title", ""), if (ra == null) emptyList() else (0 until ra.length()).map { j ->
                        val kv = ra.optJSONArray(j) ?: JSONObject()
                        kv.optString(0, "") to kv.optString(1, "")
                    })
                },
            )
        })
'''

if ANCHOR not in src:
    print("ABORT: 未找到名册末尾锚点")
    sys.exit(1)
if 'CardSpec("decision"' in src:
    print("SKIP: 第三批已在名册里")
    sys.exit(0)

src = src.replace(ANCHOR, BLOCK + ANCHOR, 1)
tmp = P + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
os.replace(tmp, P)
print("OK: 名册已加 9 条（92 -> 101）")