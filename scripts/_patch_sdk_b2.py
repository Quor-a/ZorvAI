# -*- coding: utf-8 -*-
"""第二批增强组件：往 CardSdk 名册里插 12 条 CardSpec，并加强 lint 自检。

用途是「一次性把 12 种新组件登记进唯一真册」，避免手改几百行时漏掉锚点。
跑完必须复核：:app:testFullDebugUnitTest 里 CardSdkLintTest 全绿（lint() 返回空）。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "app", "src", "main", "java", "com", "ai", "assistance", "quro", "core", "cards", "CardSdk.kt")


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    tmp = p + ".tmp_%d" % os.getpid()
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(s)
    os.replace(tmp, p)


SPECS = '''        // ════════════════ 第二批增强（v1400-b2） ════════════════
        add(CardSpec("gantt", "data", "甘特图：任务排期与跨度重叠（table 看值、gantt 看时间长度）", """{"type":"gantt","title":"项目排期","unit":"day","total":7,"axisStart":"10/01","tasks":[{"name":"设计","start":1,"duration":2,"progress":100,"owner":"设计"},{"name":"开发","start":3,"duration":4,"progress":40}]}""") { s ->
            GanttCard(
                s.id(), s.title(),
                (0 until s.arrLen("tasks")).map { i ->
                    val t = s.objAt("tasks", i)
                    GanttCard.Task(
                        t.optString("name", ""), t.optInt("start", 1), t.optInt("duration", 1),
                        t.optInt("progress", -1), t.optString("color", ""), t.optString("owner", ""),
                    )
                },
                s.optString("unit", "day"), s.optInt("total", 0), s.optString("axisStart", ""),
            )
        })
        add(CardSpec("invoice", "data", "账单/小票：明细 + 小计/折扣/合计（table 是中性表格，invoice 有金额语义）", """{"type":"invoice","title":"10月账单","merchant":"云主机","currency":"¥","items":[{"name":"CPU 实例","qty":2,"price":"99.00","amount":"198.00"}],"subtotal":"198.00","discount":"-20.00","total":"178.00","paid":false}""") { s ->
            InvoiceCard(
                s.id(), s.title(), s.optString("merchant", ""),
                (0 until s.arrLen("items")).map { i ->
                    val it = s.objAt("items", i)
                    InvoiceCard.Item(
                        it.optString("name", ""), it.optInt("qty", 1),
                        it.optString("price", ""), it.optString("amount", ""),
                    )
                },
                s.optString("currency", "¥"), s.optString("subtotal", ""),
                s.optString("discount", ""), s.optString("total", ""),
                s.optString("note", ""), s.optBoolean("paid", false), s.optString("command", ""),
            )
        })
        add(CardSpec("currency", "data", "多币种换算：基准额 + 各币种汇率与涨跌（keyvalue 是属性表，currency 是换算表）", """{"type":"currency","title":"1 元能换多少","base":"CNY","value":"1","rates":[{"code":"USD","symbol":"$","rate":"0.1392","change":"up"},{"code":"EUR","symbol":"€","rate":"0.1281"}]}""") { s ->
            CurrencyCard(
                s.id(), s.title(), s.optString("base", "CNY"), s.optString("value", "1"),
                (0 until s.arrLen("rates")).map { i ->
                    val r = s.objAt("rates", i)
                    CurrencyCard.Rate(r.optString("code", ""), r.optString("symbol", ""), r.optString("rate", ""), r.optString("change", ""))
                },
                s.optString("updated", ""),
            )
        })
        add(CardSpec("clock", "data", "跨时区时刻对照：本地区刻 + 各城市偏移（回答「纽约现在几点」）", """{"type":"clock","title":"此刻","current":"2026-10-05 14:30","format":"24h","zones":[{"city":"北京","offset":"+08:00","diff":"本机"},{"city":"纽约","offset":"-04:00","diff":"-12h"}]}""") { s ->
            ClockCard(
                s.id(), s.title(), s.optString("current", ""),
                (0 until s.arrLen("zones")).map { i ->
                    val z = s.objAt("zones", i)
                    ClockCard.Zone(z.optString("city", ""), z.optString("offset", ""), z.optString("diff", ""))
                },
                s.optString("format", "24h"),
            )
        })
        add(CardSpec("tracker", "data", "习惯打卡：逐天格子 + 连续天数（checklist 是待办清单，tracker 是已成 history 的格子）", """{"type":"tracker","title":"10月阅读打卡","name":"阅读","days":["1","1","0","1","1","1","1"],"target":21,"streak":5}""") { s ->
            TrackerCard(
                s.id(), s.title(), s.optString("name", ""), s.strArr("days"),
                s.optInt("target", 0), s.optInt("streak", 0), s.optString("unit", "天"),
            )
        })
        add(CardSpec("scoreboard", "data", "比分板：主客队 + 比分 + 节次/时间/比赛状态", """{"type":"scoreboard","title":"小组赛","home":"主队","homeScore":"2","away":"客队","awayScore":"1","period":"上半场","time":"45:00","status":"live"}""") { s ->
            ScoreboardCard(
                s.id(), s.title(), s.optString("home", ""), s.optString("homeScore", ""),
                s.optString("away", ""), s.optString("awayScore", ""),
                s.optString("period", ""), s.optString("time", ""), s.optString("status", "live"),
            )
        })
        add(CardSpec("vocab", "aiwrite", "词汇卡：单词 + 音标 + 词性 + 释义 + 中英例句（背单词场景）", """{"type":"vocab","title":"resilient","word":"resilient","phonetic":"/rɪˈzɪliənt/","pos":"adj","meaning":"有韧性的；受冲击后能快速恢复的","examples":[{"en":"She is resilient under pressure.","zh":"她在压力下很有韧性。"}],"tags":["性格","高频"]}""") { s ->
            VocabCard(
                s.id(), s.title(), s.optString("word", ""), s.optString("phonetic", ""),
                s.optString("pos", ""), s.optString("meaning", ""),
                (0 until s.arrLen("examples")).map { i ->
                    val e = s.objAt("examples", i)
                    VocabCard.Example(e.optString("en", ""), e.optString("zh", ""))
                },
                s.strArr("tags"),
            )
        })
        add(CardSpec("formula", "aiwrite", "公式卡：公式 + 变量释义（项目无 KaTeX，[^] 上标与 _下标_ 做极简渲染，其余原样）", """{"type":"formula","title":"质能方程","expr":"E = m c^2","vars":[{"name":"E","desc":"能量"},{"name":"m","desc":"质量"}],"note":"光速平方约为 9e16"}""") { s ->
            FormulaCard(
                s.id(), s.title(), s.optString("expr", ""),
                (0 until s.arrLen("vars")).map { i ->
                    val v = s.objAt("vars", i)
                    FormulaCard.Var(v.optString("name", ""), v.optString("desc", ""))
                },
                s.optString("note", ""),
            )
        })
        add(CardSpec("translate", "aiwrite", "翻译对照：源文/译文上下对照 + 备选译法（compare 是方案取舍，不是上下对照）", """{"type":"translate","title":"翻译","srcLang":"英文","dstLang":"中文","src":"Good morning, everyone.","dst":"大家早上好。","alt":["各位早安。"]}""") { s ->
            TranslateCard(
                s.id(), s.title(), s.optString("srcLang", ""), s.optString("dstLang", ""),
                s.optString("src", ""), s.optString("dst", ""), s.strArr("alt"), s.optString("audio", ""),
            )
        })
        add(CardSpec("palette", "decoration", "色卡：一组色 + 每色用途名（color 是单色块，palette 是一整套配色）", """{"type":"palette","title":"秋季配色","name":"陶土","colors":[{"name":"主色","hex":"#C96F4A"},{"name":"辅色","hex":"#E8C39E"},{"name":"底色","hex":"#F5EFE6"}],"copyable":true}""") { s ->
            PaletteCard(
                s.id(), s.title(), s.optString("name", ""),
                (0 until s.arrLen("colors")).map { i ->
                    val c = s.objAt("colors", i)
                    PaletteCard.Swatch(c.optString("name", ""), c.optString("hex", ""))
                },
                s.optBoolean("copyable", true),
            )
        })
        add(CardSpec("stopwatch", "input", "秒表（正计时累加，与 timer 的倒计时互补）", """{"type":"stopwatch","title":"耗时","label":"本次翻页耗时","seconds":0,"command":"ui_stopwatch"}""") { s ->
            StopwatchCard(s.id(), s.title(), s.optString("label", ""), s.optLong("seconds", 0L), s.optString("command", ""))
        })
        add(CardSpec("barcode", "media", "条码卡：一维码观感 + 码字可复制（项目无条码库，只给原文不画假码）", """{"type":"barcode","title":"商品条码","code":"6901234567892","format":"EAN13","caption":"扫码支付可识别"}""") { s ->
            BarcodeCard(s.id(), s.title(), s.optString("code", ""), s.optString("format", "CODE128"), s.optString("caption", ""))
        })
        add(CardSpec("spacer", "decoration", "垂直间隔（调整节奏）", """{"type":"spacer","height":16}""") { s ->
            SpacerCard(s.id(), s.title(), s.optInt("height", 16))
        })
    }
'''

LINT_OLD = '''    fun lint(): List<String> {
        val issues = ArrayList<String>()
        all.groupBy { it.type }.filterValues { it.size > 1 }.forEach { (t, v) ->
            issues += "type 重复：$t 出现 ${v.size} 次"
        }
        all.forEach { spec ->
            val built = spec.builder?.let { b -> runCatching { b(JSONObject(spec.sample)) }.getOrNull() }
            if (built == null) {
                issues += "${spec.type}：样例无法构造"
                return@forEach
            }
            val round = runCatching { parseCard(serializeCard(built)) }.getOrNull()
            if (round == null) issues += "${spec.type}：serialize → parse 往返失败"
            else if (round::class != built::class) issues += "${spec.type}：往返后类型变了"
        }
        if (catalog().size != all.size) issues += "catalog 数量 ≠ all 数量"
        return issues
    }
'''

LINT_NEW = '''    fun lint(): List<String> {
        val issues = ArrayList<String>()
        all.groupBy { it.type }.filterValues { it.size > 1 }.forEach { (t, v) ->
            issues += "type 重复：$t 出现 ${v.size} 次"
        }
        // type 命名规范：normalizeType 会把 A2UI 的 PascalCase 名 snake_case 化后查名册，
        // 名册里混进 camelCase 就会让「同名组件从围栏进来时认不出来」这类玄学问题。
        val typeRe = Regex("^[a-z][a-z0-9_]*$")
        all.forEach { spec ->
            if (!typeRe.matches(spec.type)) issues += "type 不规范（须 snake_case）：${spec.type}"
        }
        // 样例自检：AI 照抄的是样例，样例错了 = 整类卡片全废，而且**编译不报、运行不报**
        all.forEach { spec ->
            val root = runCatching { JSONObject(spec.sample) }.getOrNull()
            if (root == null) {
                issues += "${spec.type}：样例不是合法 JSON"
                return@forEach
            }
            if (root.optString("type", "") != spec.type) {
                issues += "${spec.type}：样例里的 type 写成了 ${root.optString("type", "(空)")}"
            }
            if (spec.description.isBlank()) {
                issues += "${spec.type}：description 为空，AI 不知道什么时候该用"
            }
            if (spec.category !in KNOWN_CATEGORIES) {
                issues += "${spec.type}：未知类目 ${spec.category}（目录页会整类消失）"
            }
        }
        // 构造 + 往返：漏掉 encode 分支的卡片能解析、能渲染，但存档读回来变另一张卡
        all.forEach { spec ->
            val built = spec.builder?.let { b -> runCatching { b(JSONObject(spec.sample)) }.getOrNull() }
            if (built == null) {
                issues += "${spec.type}：样例无法构造"
                return@forEach
            }
            val round = runCatching { parseCard(serializeCard(built)) }.getOrNull()
            if (round == null) issues += "${spec.type}：serialize → parse 往返失败"
            else if (round::class != built::class) issues += "${spec.type}：往返后类型变了"
        }
        if (catalog().size != all.size) issues += "catalog 数量 ≠ all 数量"
        return issues
    }
'''

BYTYPE_ANCHOR = '''    /** type → 定义。 */
    val byType: Map<String, CardSpec> = all.associateBy { it.type }
'''

BYTYPE_NEW = '''    /**
     * 合法类目白名单。
     *
     * 类目同时喂给目录页分组和提示词组名，拼错一个字母的结果是「这类组件在目录页
     * 整个消失、AI 也拿不到这个名字」—— 不报错、不崩溃，只是悄悄少一批能力。
     */
    private val KNOWN_CATEGORIES = setOf(
        "input", "data", "layout", "action", "media", "nav", "flow", "decoration", "aiwrite",
    )

''' + BYTYPE_ANCHOR


def main():
    src = read(TARGET)

    # 1) 名册尾部插入 12 条
    anchor = '''        add(CardSpec("spacer", "decoration", "垂直间隔（调整节奏）", """{"type":"spacer","height":16}""") { s ->
            SpacerCard(s.id(), s.title(), s.optInt("height", 16))
        })
    }
'''
    assert src.count(anchor) == 1, "spacer 锚点不唯一/不存在"
    src = src.replace(anchor, SPECS)

    # 2) lint 增强
    assert src.count(LINT_OLD) == 1, "lint 锚点不唯一/不存在"
    src = src.replace(LINT_OLD, LINT_NEW)

    # 3) 类目白名单
    assert src.count(BYTYPE_ANCHOR) == 1, "byType 锚点不唯一/不存在"
    src = src.replace(BYTYPE_ANCHOR, BYTYPE_NEW)

    write(TARGET, src)

    # 4) 复核
    out = read(TARGET)
    want = ["gantt", "invoice", "currency", "clock", "tracker", "scoreboard",
            "vocab", "formula", "translate", "palette", "stopwatch", "barcode"]
    for t in want:
        needle = 'add(CardSpec("%s"' % t
        if needle not in out:
            raise AssertionError("缺少 %s" % t)
    total = out.count("add(CardSpec(")
    if total != 92:
        raise AssertionError("名册条数应为 80+12=92，实际 %d" % total)
    if "KNOWN_CATEGORIES" not in out:
        raise AssertionError("类目白名单没插进去")
    if "样例不是合法 JSON" not in out:
        raise AssertionError("lint 增强没生效")
    print("[OK] CardSdk.kt 已登记 12 条新组件，名册 %d 条" % total)


if __name__ == "__main__":
    sys.exit(main())
