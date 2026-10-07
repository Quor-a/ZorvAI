import io, os, sys

p = "app/src/main/java/com/ai/assistance/quro/core/cards/CardPatch.kt"
s = io.open(p, encoding="utf-8").read()

# 1) ok 不能放在主构造参数里（构造参数不允许带 getter）—— 移进类体
old = """        /** 失败原因，逐条。 */
        val errors: List<String>,
        /** 成功与否。 */
        val ok: Boolean get() = errors.isEmpty()
    ) {
        /**"""
new = """        /** 失败原因，逐条。 */
        val errors: List<String>,
    ) {
        /**
         * 成功与否。
         *
         * 派生属性而非构造参数：[Result] 的构造点有十几处（每个失败分支都要造一个），
         * 让调用方额外传一个 `ok = errors.isEmpty()` 只会带来「两处不一致」的机会。
         */
        val ok: Boolean get() = errors.isEmpty()

        /**"""
assert s.count(old) == 1, "ok 段锚点不唯一: %d" % s.count(old)
s = s.replace(old, new, 1)

# 2) when 各分支末表达式是 put()/remove() 的返回值（Any?），显式收成 Unit
old2 = """                when (parent) {
                    is JSONObject -> parent.put(leaf, op.get("value"))
                    is JSONArray -> {
                        val i = leaf.toIntOrNull() ?: return "数组下标不是数字：$leaf"
                        if (i < 0 || i >= parent.length()) return "下标 $i 越界（长度 ${parent.length()}）"
                        parent.put(i, op.get("value"))
                    }
                    else -> return "父节点不是对象或数组"
                }
                null"""
new2 = """                when (parent) {
                    is JSONObject -> parent.put(leaf, op.get("value"))
                    is JSONArray -> {
                        val i = leaf.toIntOrNull() ?: return "数组下标不是数字：$leaf"
                        if (i < 0 || i >= parent.length()) return "下标 $i 越界（长度 ${parent.length()}）"
                        parent.put(i, op.get("value"))
                    }
                    else -> return "父节点不是对象或数组"
                }
                @Suppress("UNUSED_EXPRESSION") Unit
                null"""
assert s.count(old2) == 1, "set 分支锚点不唯一: %d" % s.count(old2)
s = s.replace(old2, new2, 1)

old3 = """                    is JSONObject -> parent.put(leaf, op.opt("value"))
                    else -> return "父节点不是对象或数组"
                }
                null"""
new3 = """                    is JSONObject -> parent.put(leaf, op.opt("value"))
                    else -> return "父节点不是对象或数组"
                }
                @Suppress("UNUSED_EXPRESSION") Unit
                null"""
assert s.count(old3) == 1, "append 分支锚点不唯一: %d" % s.count(old3)
s = s.replace(old3, new3, 1)

# 3) remove / inc 的表达式体 when：值是 JSONObject.remove 的返回值
old4 = """            "remove" -> when (parent) {
                is JSONObject -> {
                    if (!parent.has(leaf)) return "要删的键不存在：$leaf"
                    parent.remove(leaf)
                }
                is JSONArray -> {
                    val i = leaf.toIntOrNull() ?: return "数组下标不是数字：$leaf"
                    if (i < 0 || i >= parent.length()) return "下标 $i 越界（长度 ${parent.length()}）"
                    parent.remove(i)
                }
                else -> return "父节点不是对象或数组"
            }"""
new4 = """            "remove" -> {
                when (parent) {
                    is JSONObject -> {
                        if (!parent.has(leaf)) return "要删的键不存在：$leaf"
                        parent.remove(leaf)
                    }
                    is JSONArray -> {
                        val i = leaf.toIntOrNull() ?: return "数组下标不是数字：$leaf"
                        if (i < 0 || i >= parent.length()) return "下标 $i 越界（长度 ${parent.length()}）"
                        parent.remove(i)
                    }
                    else -> return "父节点不是对象或数组"
                }
                null
            }"""
assert s.count(old4) == 1, "remove 锚点不唯一: %d" % s.count(old4)
s = s.replace(old4, new4, 1)

old5 = """                when (parent) {
                    is JSONObject -> parent.put(leaf, next)
                    is JSONArray -> parent.put(leaf.toInt(), next)
                    else -> Unit
                }
                null"""
if s.count(old5) != 1:
    # 实际代码里 else 分支可能不同，兜底打印上下文
    i = s.find('val next = cur + delta')
    sys.stderr.write("inc 尾段上下文：\n" + s[i:i+320] + "\n")
    raise SystemExit("inc 锚点不匹配")
s = s.replace(old5, old5, 1)  # 本身已是 Unit 语义，仅确认

# 6) describe 是 object 成员，Result 内部需限定调用
old6 = "            val legal = describe(card).take(40)"
new6 = "            val legal = CardPatch.describe(card).take(40)"
assert s.count(old6) == 1, "describe 锚点不唯一: %d" % s.count(old6)
s = s.replace(old6, new6, 1)

tmp = p + ".tmp"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(s)
os.replace(tmp, p)
print("OK  patched", p, len(s.splitlines()), "lines")
