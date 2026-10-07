# -*- coding: utf-8 -*-
"""修 normalizeType 这一轮暴露出来的三个真问题：

 1. 容器别名指向了**名册里不存在**的 `stack`（roster 里叫 composite）——
    结果 A2UI 的 Column/Row/Stack/Card/Container 一类容器全部静默落 CustomCard。
    [CardFence.lintAliases] 已经把它报出来了，这里按报出来的修；
 2. pascalToSnake 里的 `"$1_$2"` 在 Kotlin 模板里把下划线吞了（实测 QRCode → qrcode），
    改成 `${g1}_${g2}` 显式插值；
 3. 带了 children 但组件名不认识的节点，应该当容器走组合卡，而不是退化成一张 CustomCard。
"""
import io
import os
import sys

FENCE = "app/src/main/java/com/ai/assistance/quro/core/cards/CardFence.kt"


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def write(p, s):
    tmp = p + ".tmp_%d" % os.getpid()
    with io.open(tmp, "w", encoding="utf-8", newline="") as f:
        f.write(s)
    os.replace(tmp, p)


FIX1 = [
    ('"Column" to "stack", "Row" to "stack", "Stack" to "stack", "Card" to "stack",',
     '"Column" to "composite", "Row" to "composite", "Stack" to "composite", "Card" to "composite",'),
    ('"Container" to "stack", "List" to "list", "Grid" to "list", "Tabs" to "tabs",',
     '"Container" to "composite", "List" to "list", "Grid" to "list", "Tabs" to "tabs",'),
]

FIX2_OLD = '''    /** PascalCase / camelCase → snake_case（`TextInput` → `text_input`，`QRCode` → `qr_code`）。 */
    private fun pascalToSnake(n: String): String =
        n.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
            .lowercase()
'''
FIX2_NEW = '''    /**
     * PascalCase / camelCase → snake_case（`TextInput` → `text_input`，`QRCode` → `qr_code`）。
     *
     * 🔴 替换串必须写成 `${g1}_${g2}` 而不是 `"$1_$2"`：Kotlin 的模板会把 `$1_$2`
     * 解析成 `$1` + `$2` 两个引用，中间那个下划线被吞掉（实测 QRCode 直接退化成 qrcode，
     * 与 roster 的 `qrcode` 撞巧能对上，但 HTMLPreview 这类就悄悄错成 htmlpreview）。
     *
     * 做成 internal 是为了能直接单测这条纯函数 —— 它错了不会报错，只会让组件名映射悄悄失效。
     */
    internal fun pascalToSnake(n: String): String {
        val a = Regex("([a-z0-9])([A-Z])")
        val b = Regex("([A-Z]+)([A-Z][a-z])")
        var s = a.replace(n) { m -> "${m.groupValues[0][0]}_${m.groupValues[1]}" }
        s = b.replace(s) { m ->
            val head = m.groupValues[0].dropLast(2)   // 前一段全大写
            val tail = m.groupValues[0].takeLast(2)   // 最后一个大写 + 后面那个小写
            "${head}_$tail"
        }
        return s.lowercase()
    }
'''

FIX3_OLD = '    private val CONTAINER_TYPES = setOf("stack", "column", "row", "card", "container", "list", "grid", "tabs")'
FIX3_NEW = '''    /**
     * 容器型 type：子节点要挂进 children 而不是被 props 吞掉。
     *
     * 末尾那个 `custom` 是**识别不出来的容器**：model 写了 `{"component":"Whatever","children":[...]}`
     * 这种，它带的 children 显然还是子卡，当成容器组一层组合卡，比丢一堆孤儿子节点有用。
     */
    private val CONTAINER_TYPES = setOf(
        "stack", "column", "row", "card", "container", "list", "grid", "tabs", "composite", "custom",
    )'''

FIX4_OLD = '''        COMPONENT_ALIASES.forEach { (k, v) ->
            if (v !in CardSdk.types) bad += "别名 $k → $v 不在名册里"
        }'''
FIX4_NEW = '''        COMPONENT_ALIASES.forEach { (k, v) ->
            // `custom` 是 normalizeType 的哨兵值（表示"这组件我们没做"），不是名册里的 type，放行
            if (v != "custom" && v !in CardSdk.types) bad += "别名 $k → $v 不在名册里"
        }'''


def main():
    src = read(FENCE)
    for old, new in FIX1:
        if src.count(old) != 1:
            raise SystemExit("锚点命中 %d 次：%s" % (src.count(old), old))
        src = src.replace(old, new)
    for old, new in ((FIX2_OLD, FIX2_NEW), (FIX3_OLD, FIX3_NEW), (FIX4_OLD, FIX4_NEW)):
        if src.count(old) != 1:
            raise SystemExit("锚点命中 %d 次：%s" % (src.count(old), old[:48]))
        src = src.replace(old, new)
    write(FENCE, src)

    out = read(FENCE)
    for needle in ('"Column" to "composite"', 'internal fun pascalToSnake',
                   '"stack", "column", "row", "card", "container", "list", "grid", "tabs", "composite", "custom"',
                   'v != "custom" && v !in CardSdk.types'):
        if needle not in out:
            raise SystemExit("复核失败：" + needle)
    print("[OK] 容器别名归位 / pascalToSnake 修下划线 / 未知容器当组合卡 / 哨兵放行")


if __name__ == "__main__":
    sys.exit(main())
