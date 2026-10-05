# -*- coding: utf-8 -*-
"""给 QuroChatCard.kt 打三处补丁（行号定位 + 原子写 + 前后文校验）。

补丁 1  serializeCard 的 cardType when：为 34 种增强卡各补一行 `is X -> "wire"`
       —— 这让 when 保持 exhaustive，以后新增 data class 忘了登记会编译报错。
补丁 2  serializeCard 的 payload when：补 `else -> CardCodec.encode(card, o)`
       —— 34 种增强卡的字段写盘交给 CardCodec（单一实现），本 when 不必摊 34 段细节。
补丁 3  parseComponentSpec / parseCard：未知 type 兜底交给 CardSdk.parseObj
       —— 解析层只此一份，落盘与工具下发不会各自长出一个解析器。

🔴 本脚本只做「按行号插入 + 校验周边文本」，不做任何花括号配平
   （2026-10-05 那次配平算错把尾部 936 行删没了，就是这么来的）。
"""
import io
import os
import sys

ROOT = r"D:\Calw OS-project\QuroAI"
TARGET = os.path.join(
    ROOT, "app/src/main/java/com/ai/assistance/quro/core/cards/QuroChatCard.kt"
)

# 34 种增强卡：Kotlin 类名 -> 线上 wire type（必须与 CardSdk.all 里的 type 一致）
EXT = [
    ("KeyValueCard", "keyvalue"),
    ("RingCard", "ring"),
    ("StackedBarCard", "stackedbar"),
    ("ScatterCard", "scatter"),
    ("FunnelCard", "funnel"),
    ("CandlestickCard", "candlestick"),
    ("BoxPlotCard", "boxplot"),
    ("SpeedometerCard", "speedometer"),
    ("SparklineCard", "sparkline"),
    ("SearchBoxCard", "searchbox"),
    ("PollCard", "poll"),
    ("CheckListCard", "checklist"),
    ("AccordionCard", "accordion"),
    ("GroupedListCard", "groupedlist"),
    ("TreeCard", "tree"),
    ("QuoteCard", "quote"),
    ("DiffCard", "diff"),
    ("FlowCard", "flow"),
    ("HierarchyCard", "hierarchy"),
    ("ContactCard", "contact"),
    ("ProductCard", "product"),
    ("ScheduleCard", "schedule"),
    ("FileCard", "filecard"),
    ("AchievementCard", "achievement"),
    ("WeatherCard", "weather"),
    ("MapCard", "map"),
    ("QrCodeCard", "qrcode"),
    ("GalleryCard", "gallery"),
    ("TerminalCard", "terminal"),
    ("LinkListCard", "linklist"),
    ("PaginationCard", "pagination"),
    ("DividerCard", "divider"),
    ("SpacerCard", "spacer"),
    ("CustomCard", "custom"),
]

raw = io.open(TARGET, encoding="utf-8", newline="").read()
nl = "\r\n" if "\r\n" in raw else "\n"
lines = raw.split(nl)
print("换行风格:", repr(nl), "总行数:", len(lines))


def find(pred, start=0, desc="锚点"):
    for i in range(start, len(lines)):
        if pred(lines[i]):
            return i
    print("ERR: 找不到", desc)
    sys.exit(1)


def insert_at(idx, new_lines, desc, check_above=None, check_below=None):
    """在 idx 行**之前**插入。校验上下各一行文本，避免行号漂移时误插。"""
    if check_above is not None and check_above not in lines[idx - 1]:
        print("ERR: %s 上方上下文不符: %r" % (desc, lines[idx - 1]))
        sys.exit(1)
    if check_below is not None and lines[idx].strip() != check_below:
        print("ERR: %s 下方上下文不符: %r" % (desc, lines[idx]))
        sys.exit(1)
    lines[idx:idx] = new_lines
    print("OK:", desc, "-> 插在第", idx + 1, "行前，共", len(new_lines), "行")


# ── 补丁 3：parseCard 的 else -> null（16 空格缩进的那一处）──
i_parse_card = find(lambda l: l == '            else -> null', desc="parseCard/parseComponentSpec 的 else->null（12空格）")
# parseComponentSpec 与 parseCard 各有一处；先确认两处，插后面的（parseCard 在文件后半）
i_parse_spec = find(lambda l: l == '            else -> null', 0, "第一处 else->null（parseComponentSpec）")
i_parse_card = find(lambda l: l == '            else -> null', i_parse_spec + 1, "第二处 else->null（parseCard）")
print("parseComponentSpec else 行号:", i_parse_spec + 1, " parseCard else 行号:", i_parse_card + 1)

# ── 补丁 1：cardType when 的 CompositetCard 那一行之后 ──
i_composite_type = find(lambda l: l.strip() == 'is QuroChatCard.CompositeCard -> "composite"', desc="cardType when 的 composite 分支")
print("cardType composite 行号:", i_composite_type + 1)

# ── 补丁 2：payload when 收尾（'    return o' 之前）──
i_return_o = find(lambda l: l.strip() == "return o" and lines[lines.index(l) - 1].strip() == "}", desc="serializeCard 的 return o")
print("return o 行号:", i_return_o + 1)

# ═══════════ 自下而上插入，保证行号不漂 ═══════════

# 补丁 3b：parseCard 兜底（文件后半，先插）
insert_at(
    i_parse_card,
    [
        '            // ── v1400 增强组件：解析层只有这一份（与工具下发共用 [CardSdk.parseObj]）──',
        '            else -> runCatching {',
        '                val s = JSONObject(o.toString()).apply { put("type", t) }',
        '                CardSdk.parseObj(s)',
        '            }.getOrNull()',
    ],
    "parseCard 兜底 -> CardSdk.parseObj",
    check_above='            }',
    check_below='else -> null',
)
i_parse_card += 5

# 补丁 1：cardType when（在文件中部）
type_lines = ['        // ── v1400 增强组件（sealed 子类在 QuroChatCardEx.kt，wire type 必须登记在此，否则 when 不穷尽）──']
type_lines += ['        is %s -> "%s"' % (c, t) for c, t in EXT]
insert_at(i_composite_type + 1, type_lines, "cardType when 补 34 行")

# 补丁 3a：parseComponentSpec 兜底
insert_at(
    i_parse_spec,
    [
        '            // ── v1400 增强组件：未知 type 交给 [CardSdk.parseObj]（唯一解析层），未知落 CustomCard 而非丢弃 ──',
        '            else -> CardSdk.parseObj(s)',
    ],
    "parseComponentSpec 兜底 -> CardSdk.parseObj",
)

# 补丁 2：payload when
insert_at(
    i_return_o,
    [
        '        // ── v1400 增强组件：字段写盘统一交给 [CardCodec]（编码单一实现，见其文件头注释）──',
        '        else -> CardCodec.encode(card, o)',
    ],
    "payload when 兜底 -> CardCodec.encode",
)

out = nl.join(lines)
tmp = TARGET + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="").write(out)
os.replace(tmp, TARGET)
print("写入完成，总行数:", len(lines))
