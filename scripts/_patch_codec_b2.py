# -*- coding: utf-8 -*-
"""第二批 12 种卡片的「落盘」接线：serializeCard 的 cardType 表 + CardCodec.encode。

为什么要改两处：
  · serializeCard 里那个 `when (card) { ... }` **没有 else**（写盘 type 必须穷尽），
    漏一个分支就是编译不过 —— 这正是「漏一处不会编译报错」的反面，必须主动补；
  · CardCodec.encode 走 `else -> Unit`（给老卡兜底），这里**故意不补 else**，
    而是把新卡全部显式列出，这样往返校验（lint）才有机会抓到漏写的分支。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CARD = os.path.join(ROOT, "app", "src", "main", "java", "com", "ai", "assistance", "quro", "core", "cards", "QuroChatCard.kt")
CODEC = os.path.join(ROOT, "app", "src", "main", "java", "com", "ai", "assistance", "quro", "core", "cards", "CardCodec.kt")


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    tmp = p + ".tmp_%d" % os.getpid()
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(s)
    os.replace(tmp, p)


# type 值 / data class 名（顺序必须与名册一致）
PAIRS = [
    ("gantt", "GanttCard"),
    ("invoice", "InvoiceCard"),
    ("currency", "CurrencyCard"),
    ("clock", "ClockCard"),
    ("tracker", "TrackerCard"),
    ("scoreboard", "ScoreboardCard"),
    ("vocab", "VocabCard"),
    ("formula", "FormulaCard"),
    ("translate", "TranslateCard"),
    ("palette", "PaletteCard"),
    ("stopwatch", "StopwatchCard"),
    ("barcode", "BarcodeCard"),
]

TYPE_ROWS = "".join('        is %s -> "%s"\n' % (cls, t) for t, cls in PAIRS)

ENCODE = '''            is GanttCard -> {
                o.put("unit", card.unit); o.put("total", card.total); o.put("axisStart", card.axisStart)
                o.put("tasks", JSONArray().also { a -> card.tasks.forEach { t ->
                    a.put(JSONObject().apply {
                        put("name", t.name); put("start", t.start); put("duration", t.duration)
                        put("progress", t.progress); put("color", t.color); put("owner", t.owner)
                    })
                } })
            }
            is InvoiceCard -> {
                o.put("merchant", card.merchant); o.put("currency", card.currency)
                o.put("items", JSONArray().also { a -> card.items.forEach { it ->
                    a.put(JSONObject().apply {
                        put("name", it.name); put("qty", it.qty); put("price", it.price); put("amount", it.amount)
                    })
                } })
                o.put("subtotal", card.subtotal); o.put("discount", card.discount)
                o.put("total", card.total); o.put("note", card.note); o.put("paid", card.paid)
                o.put("command", card.command)
            }
            is CurrencyCard -> {
                o.put("base", card.base); o.put("value", card.value); o.put("updated", card.updated)
                o.put("rates", JSONArray().also { a -> card.rates.forEach { r ->
                    a.put(JSONObject().apply { put("code", r.code); put("symbol", r.symbol); put("rate", r.rate); put("change", r.change) })
                } })
            }
            is ClockCard -> {
                o.put("current", card.current); o.put("format", card.format)
                o.put("zones", JSONArray().also { a -> card.zones.forEach { z ->
                    a.put(JSONObject().apply { put("city", z.city); put("offset", z.offset); put("diff", z.diff) })
                } })
            }
            is TrackerCard -> {
                o.put("name", card.name); o.put("days", sArr(card.days))
                o.put("target", card.target); o.put("streak", card.streak); o.put("unit", card.unit)
            }
            is ScoreboardCard -> {
                o.put("home", card.home); o.put("homeScore", card.homeScore)
                o.put("away", card.away); o.put("awayScore", card.awayScore)
                o.put("period", card.period); o.put("time", card.time); o.put("status", card.status)
            }
            is VocabCard -> {
                o.put("word", card.word); o.put("phonetic", card.phonetic)
                o.put("pos", card.pos); o.put("meaning", card.meaning)
                o.put("examples", JSONArray().also { a -> card.examples.forEach { e ->
                    a.put(JSONObject().apply { put("en", e.en); put("zh", e.zh) })
                } })
                o.put("tags", sArr(card.tags))
            }
            is FormulaCard -> {
                o.put("expr", card.expr); o.put("note", card.note)
                o.put("vars", JSONArray().also { a -> card.vars.forEach { v ->
                    a.put(JSONObject().apply { put("name", v.name); put("desc", v.desc) })
                } })
            }
            is TranslateCard -> {
                o.put("srcLang", card.srcLang); o.put("dstLang", card.dstLang)
                o.put("src", card.src); o.put("dst", card.dst)
                o.put("alt", sArr(card.alt)); o.put("audio", card.audio)
            }
            is PaletteCard -> {
                o.put("name", card.name); o.put("copyable", card.copyable)
                o.put("colors", JSONArray().also { a -> card.colors.forEach { c ->
                    a.put(JSONObject().apply { put("name", c.name); put("hex", c.hex) })
                } })
            }
            is StopwatchCard -> {
                o.put("label", card.label); o.put("seconds", card.seconds); o.put("command", card.command)
            }
            is BarcodeCard -> {
                o.put("code", card.code); o.put("format", card.format); o.put("caption", card.caption)
            }
'''


def main():
    # 1) serializeCard 的 cardType 穷尽表
    card_src = read(CARD)
    tail_anchor = '        is CustomCard -> "custom"\n'
    idx = card_src.rindex(tail_anchor)
    end = idx + len(tail_anchor)
    card_src = card_src[:end] + TYPE_ROWS + card_src[end:]

    # 2) CardCodec.encode 的显式分支
    codec_src = read(CODEC)
    else_anchor = "            else -> Unit\n"
    assert codec_src.count(else_anchor) == 1, "encode 的 else 锚点不唯一"
    codec_src = codec_src.replace(else_anchor, ENCODE + else_anchor)

    write(CARD, card_src)
    write(CODEC, codec_src)

    # 3) 复核
    for src, label in ((card_src, "QuroChatCard.kt"), (codec_src, "CardCodec.kt")):
        for t, cls in PAIRS:
            if ('is %s -> "%s"' % (cls, t)) not in src and ('is %s -> {' % cls) not in src:
                raise AssertionError("%s 里缺 %s" % (label, cls))
    print("[OK] 第二批 12 种卡片已接线 cardType 表 + CardCodec.encode")


if __name__ == "__main__":
    sys.exit(main())
