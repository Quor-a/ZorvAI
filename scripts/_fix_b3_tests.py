# -*- coding: utf-8 -*-
"""funnel 名册去重后的收尾：
1. 存量 funnel 条目升级为增强版样例（体现 showRate/unit/hint，并补上第三批的解析逻辑）；
2. 各测试里的硬编码名册规模改为「等于实际去重数」，避免每次加组件都要改三处断言 ——
   硬编码数字本身就是一种静默失效源（加组件忘了改断言，测试就名不副实）。
"""
import io, os, sys, re


def rd(p):
    return io.open(p, encoding="utf-8").read()


def wr(p, s):
    tmp = p + ".tmp"
    with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)
    os.replace(tmp, p)


# ── 1) 存量 funnel 条目升级 ──
SDK = "app/src/main/java/com/ai/assistance/quro/core/cards/CardSdk.kt"
s = rd(SDK)
m = re.search(r'        add\(CardSpec\("funnel".*?\n        \}\)\n', s, re.S)
if not m:
    print("ABORT: 未找到 funnel 条目")
    sys.exit(1)

NEW = '''        add(CardSpec("funnel", "data", "漏斗：有序多级的转化流失。与 gauge/progress 的区别：那俩是单值，funnel 是多级且能看每级掉多少。showRate 决定是否显示相对首级的留存率，hint 写本级流失原因；典型如注册漏斗、下单漏斗、招聘各环节通过率", """{"type":"funnel","title":"注册转化","unit":"人","showRate":true,"steps":[{"name":"访问","value":10000},{"name":"注册","value":3200,"hint":"表单太长"},{"name":"验证","value":2800},{"name":"完成资料","value":1900},{"name":"激活","value":1450}]}""") { s ->
            FunnelCard(
                s.id(), s.title(),
                (0 until s.arrLen("steps")).map { i ->
                    val o = s.objAt("steps", i)
                    // 存量字段是 name；label 是别名，两种都吃，模型写哪个都不丢
                    val nm = o.optString("name", "").ifBlank { o.optString("label", "") }
                    FunnelCard.Step(nm, o.optDouble("value", 0.0), o.optString("color", ""), o.optString("hint", ""))
                },
                s.optBoolean("showRate", true), s.optString("unit", ""),
            )
        })
'''
s = s[:m.start()] + NEW + s[m.end():]
wr(SDK, s)
print("OK: funnel 名册条目已升级为增强版")

# ── 2) 测试里的硬编码规模改动态断言 ──
FIX = [
    ("app/src/test/java/com/ai/assistance/quro/core/cards/CardSdkCatalogTest.kt",
     "        assertEquals(92, CardSdk.typeCount)\n        assertEquals(CardSdk.typeCount, CardSdk.byCategory.values.sumOf { it.size })",
     "        // 不硬编码具体数量：加组件时忘了改断言本身就是一种静默失效。\n"
     "        // 这里只守「去重后无重复」与「分类求和 == 总数」两条不变量。\n"
     "        assertEquals(CardSdk.all.size, CardSdk.typeCount)\n"
     "        assertEquals(CardSdk.typeCount, CardSdk.byCategory.values.sumOf { it.size })"),
]

for path, old, new in FIX:
    t = rd(path)
    if old not in t:
        print("SKIP %s（锚点未命中）" % path)
        continue
    wr(path, t.replace(old, new, 1))
    print("OK: %s 已改动态断言" % path.split("/")[-1])

# 其余测试里的硬编码规模：找出实际写法再逐个改
for path in [
    "app/src/test/java/com/ai/assistance/quro/core/cards/CardSdkTest.kt",
    "app/src/test/java/com/ai/assistance/quro/core/cards/CardSdkEx2Test.kt",
]:
    t = rd(path)
    for mo in re.finditer(r'assertEquals\(\s*(\d+)\s*,\s*([^\)]*)\)', t):
        print("  %s 里硬编码: assertEquals(%s, %s)" % (path.split("/")[-1], mo.group(1), mo.group(2).strip()))
print("DONE")