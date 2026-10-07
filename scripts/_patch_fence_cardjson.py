# -*- coding: utf-8 -*-
"""给 CardFence 增加第 4 种围栏 cardjson（流式友好的逐行 JSON）。

动机：现有 card/cards 围栏体是**一整段** JSON，流式时必须等整段闭合才能解析；
而真实使用里模型经常一行一个卡片地陆续吐。cardjson 把围栏体约定成「一行一个独立
JSON 对象」，于是：
  - 已收全的行立刻能渲染，流式体验与围栏闭合解耦；
  - 末尾没收全的那行只是暂时解析不出来，闭合后自然补上；
  - 单行脏数据不会连累其它行（对比整段 JSON 坏一处就整卡全废）。
"""
import io, os, sys

P = "app/src/main/java/com/ai/assistance/quro/core/cards/CardFence.kt"
src = io.open(P, encoding="utf-8").read()


def sub(old, new, tag):
    global src
    if old not in src:
        print("ABORT: 锚点未命中 -> %s" % tag)
        sys.exit(1)
    if src.count(old) != 1:
        print("ABORT: 锚点不唯一 -> %s (%d 次)" % (tag, src.count(old)))
        sys.exit(1)
    src = src.replace(old, new, 1)


# ---- 1) 新增常量 ----
sub(
    '    /** A2UI 风格扁平邻接表围栏头。 */\n    const val FENCE_CARDUI = "cardui"\n',
    '    /** A2UI 风格扁平邻接表围栏头。 */\n'
    '    const val FENCE_CARDUI = "cardui"\n'
    '\n'
    '    /**\n'
    '     * 逐行 JSON 围栏头：**一行一个独立卡片 JSON**。\n'
    '     *\n'
    '     * ## 为什么再加一种围栏\n'
    '     *\n'
    '     * [FENCE_CARD] / [FENCE_CARDS] 的围栏体是一整段 JSON，流式时必须等整段收全才能解析，\n'
    '     * 而模型实际经常是一行一个卡片地陆续吐 —— 这段时间用户只能看到代码块。\n'
    '     * 逐行约定让每行独立解析：已完整的行立刻出卡片，末尾没收全的那行等闭合后自然补上，\n'
    '     * 且某一行脏数据不会连累其余各行（整段 JSON 坏一处就整卡全废）。\n'
    '     */\n'
    '    const val FENCE_CARDJSON = "cardjson"\n',
    "常量",
)

# ---- 2) 纳入 ALL_FENCES（card 已在 cards 之前，靠 \\b 消歧，无需调整顺序） ----
sub(
    '    val ALL_FENCES: List<String> = listOf(FENCE_CARD, FENCE_CARDS, FENCE_CARDUI)',
    '    val ALL_FENCES: List<String> = listOf(FENCE_CARD, FENCE_CARDS, FENCE_CARDUI, FENCE_CARDJSON)',
    "ALL_FENCES",
)

# ---- 3) Slice.isMulti ----
sub(
    '        val isMulti: Boolean get() = fence == FENCE_CARDS || fence == FENCE_CARDUI',
    '        val isMulti: Boolean get() = fence == FENCE_CARDS || fence == FENCE_CARDUI\n'
    '\n'
    '        /**\n'
    '         * 逐行卡片围栏：调用方应**边收边渲染**，不要等 [closed] 为 true。\n'
    '         *\n'
    '         * 未闭合不等于不可用 —— 这正是它存在的理由。\n'
    '         */\n'
    '        val isStreaming: Boolean get() = fence == FENCE_CARDJSON',
    "isMulti",
)

# ---- 4) toCards 分派 ----
sub(
    '            FENCE_CARDUI -> parseA2uiBlock(t)\n            else -> emptyList()',
    '            FENCE_CARDUI -> parseA2uiBlock(t)\n'
    '            FENCE_CARDJSON -> parseLineJsonBlock(t)\n'
    '            else -> emptyList()',
    "toCards 分派",
)

# ---- 5) 逐行解析实现，插到 toCards 之后 ----
sub(
    '    /** 解析围栏 + 转卡片，一步到位。 */',
    '''    /**
     * 逐行 JSON 围栏体解析：**一行一个独立卡片**。
     *
     * 与整段解析的关键差别在容错粒度 —— 逐行的代价是失去跨行的组合卡语法
     * （`cards` 的数组 / `composite` 的 children 都得跨行才写得下），
     * 换来的是单行脏数据不连累其余各行、以及流式可增量渲染。
     * 所以组合卡请继续用 [FENCE_CARDS]。
     *
     * 未闭合围栏（`closed=false`）的最后一行通常还没收全，解析不出来属正常，
     * 直接跳过即可；等闭合后重新解析自然会补上。
     */
    fun parseLineJsonBlock(body: String): List<QuroChatCard> {
        if (body.isBlank()) return emptyList()
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.startsWith("{") }
            .mapNotNull { safeParse(it) }
            .toList()
    }

    /** 解析围栏 + 转卡片，一步到位。 */''',
    "逐行解析实现",
)

# ---- 6) 文档注释补一行 ----
sub(
    ' * - [FENCE_CARDUI] `cardui` —— A2UI 风格邻接表（扁平 `id` + `children` 引用）\n',
    ' * - [FENCE_CARDUI] `cardui` —— A2UI 风格邻接表（扁平 `id` + `children` 引用）\n'
    ' * - [FENCE_CARDJSON] `cardjson` —— 逐行 JSON（一行一个卡片，流式友好）\n',
    "文档注释",
)

tmp = P + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
os.replace(tmp, P)
print("OK: 已新增 cardjson 围栏")