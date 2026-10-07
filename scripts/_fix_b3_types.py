# -*- coding: utf-8 -*-
"""修第三批类型错误：
1. `QuroChatCard.XxxCard(` -> `XxxCard(`：第三批 data class 是顶层类（同第二批 GanttCard），
   加 sealed interface 前缀反而找不到符号。
2. 存量 FunnelCard.Step.value 放宽为 Double 后，名册里的 `.toFloat()` 要去掉。
3. Section.rows 同时吃两种写法：`{"k":..,"v":..}` 对象与 `["k","v"]` 数组。
   数组分支不用 JSONArray.optString（org.json 的数字下标取字符串在部分实现上不稳），
   改成先 optJSONObject 再兜底 optJSONArray。
"""
import io, os, sys

P = "app/src/main/java/com/ai/assistance/quro/core/cards/CardSdk.kt"
s = io.open(P, encoding="utf-8").read()


def sub(old, new, tag):
    global s
    if s.count(old) != 1:
        print("ABORT %s: 命中 %d 次" % (tag, s.count(old)))
        sys.exit(1)
    s = s.replace(old, new, 1)
    print("OK %s" % tag)


# ── 1) 去掉 QuroChatCard. 前缀 ──
for name in ["DecisionCard", "ConfirmCard", "SankeyCard", "FunnelCard", "WaterfallCard",
             "QuadrantCard", "MatrixCard", "FeedCard", "GraphCard", "SectionCard"]:
    old = "            QuroChatCard.%s(\n" % name
    if old in s:
        s = s.replace(old, "            %s(\n" % name)
        print("OK 前缀: %s" % name)

# ── 2) 存量 funnel 去 toFloat ──
sub(
    'FunnelCard.Step(it.optString("name", ""), it.optDouble("value", 0.0).toFloat(), it.optString("color", ""))',
    'FunnelCard.Step(it.optString("name", ""), it.optDouble("value", 0.0), it.optString("color", ""), it.optString("hint", ""))',
    "存量 funnel Step 构造",
)

# ── 3) 第三批 funnel 名册：字段名对齐存量（name 而非 label） ──
sub(
    '''            FunnelCard(
                s.id(), s.title(),
                (0 until s.arrLen("steps")).map { i ->
                    val o = s.objAt("steps", i)
                    FunnelStep(o.optString("label", ""), o.optDouble("value", 0.0), o.optString("hint", ""))
                },
                s.optBoolean("showRate", true), s.optString("unit", ""),
            )''',
    '''            FunnelCard(
                s.id(), s.title(),
                (0 until s.arrLen("steps")).map { i ->
                    val o = s.objAt("steps", i)
                    // 存量字段是 name；label 是别名，两种都吃，模型写哪个都不丢
                    val nm = o.optString("name", "").ifBlank { o.optString("label", "") }
                    FunnelCard.Step(nm, o.optDouble("value", 0.0), o.optString("color", ""), o.optString("hint", ""))
                },
                s.optBoolean("showRate", true), s.optString("unit", ""),
            )''',
    "第三批 funnel 名册",
)

# ── 4) 第三批 funnel 样例：字段名改回 name ──
sub(
    '"steps":[{"label":"访问","value":10000},{"label":"注册","value":3200,"hint":"表单太长"},{"label":"验证","value":2800},{"label":"完成资料","value":1900},{"label":"激活","value":1450}]',
    '"steps":[{"name":"访问","value":10000},{"name":"注册","value":3200,"hint":"表单太长"},{"name":"验证","value":2800},{"name":"完成资料","value":1900},{"name":"激活","value":1450}]',
    "第三批 funnel 样例",
)

# ── 5) Section.rows 双形态解析 ──
sub(
    '''                    SectionSection(o.optString("title", ""), if (ra == null) emptyList() else (0 until ra.length()).map { j ->
                        val kv = ra.optJSONArray(j) ?: JSONObject()
                        kv.optString(0, "") to kv.optString(1, "")
                    })''',
    '''                    SectionSection(o.optString("title", ""), if (ra == null) emptyList() else (0 until ra.length()).map { j ->
                        // 双形态：{"k":..,"v":..}（与存量 keyvalue 同形）或 ["k","v"]（数组更省 token）
                        val rowAny = ra.opt(j)
                        when (rowAny) {
                            is JSONObject -> rowAny.optString("k", "") to rowAny.optString("v", "")
                            is JSONArray -> {
                                val kArr = rowAny.optJSONArray(0) ?: JSONArray()
                                val vArr = rowAny.optJSONArray(1) ?: JSONArray()
                                val kk = if (kArr.length() > 0) kArr.opt(0)?.toString().orEmpty() else ""
                                val vv = if (vArr.length() > 0) vArr.opt(0)?.toString().orEmpty() else ""
                                kk to vv
                            }
                            else -> "" to ""
                        }
                    })''',
    "Section.rows 双形态",
)

tmp = P + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(s)
os.replace(tmp, P)
print("DONE")