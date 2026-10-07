# -*- coding: utf-8 -*-
"""给 CardSdk 增加「紧凑清单」API：让 92 种组件真正能被模型看到。
幂等：若紧凑清单已存在则先摘除旧块再插入。原子写 + 写后复核。
"""
import io, os, sys

P = "app/src/main/java/com/ai/assistance/quro/core/cards/CardSdk.kt"

ANCHOR = "    /** 目录条目（字段与旧 `CARD_CATALOG` 一致，调用方零改动）。 */"

BLOCK = '''    /**
     * **紧凑清单**：一行一个类目，形如 `data(27): stat, table, ...`。
     *
     * ## 为什么要紧凑而不是把 [catalogJson] 整份塞进工具描述
     *
     * 92 种组件的完整样例加起来十几 KB，每轮对话都带进系统提示词会实打实吃掉上下文预算，
     * 而模型大多数时候只需要知道「有没有这种卡」；真要写复杂卡时再按需拉样例即可。
     * 所以拆成两级：
     *  - 常驻：compactCatalog()，约 2KB，进 ui_widget / ui_card 的工具描述；
     *  - 按需：[CardCatalogTool] 走 samples()/catalogJson() 拉完整样例。
     *
     * ## 为什么这个函数以前不存在是个真缺口
     *
     * 名册扩到 92 种，但工具描述里那份手写清单还停在 v1068 的四十来种 ——
     * 模型看不见新增的组件，只能靠围栏里撞见样例去猜。加组件而不接这一环，
     * 等于新组件只对「已经知道它存在」的模型有效，这正是要避免的静默失效。
     */
    fun compactCatalog(): String = byCategory.entries
        .sortedBy { CATEGORY_ORDER.indexOf(it.key) }
        .joinToString("; ") { (cat, list) ->
            "$cat(${list.size}): " + list.joinToString(",")
        }

    /**
     * 按类目/类型筛样例，供模型按需拉取完整用法。
     *
     * @param category 类目名，null/空 = 不限
     * @param types    指定 type 列表，null/空 = 该类目全部
     * @param maxChars 字符上限，防止一次把上下文吃光
     * @return JSON 文本；类目非法或无匹配时返回带提示的 JSON 数组（不抛异常，让模型能自我纠正）
     */
    fun samples(category: String?, types: List<String>?, maxChars: Int = 8000): String {
        val cat = category?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (cat != null && cat !in KNOWN_CATEGORIES) {
            return JSONArray().put(JSONObject().apply {
                put("error", "未知类目 $cat")
                put("known", JSONArray(KNOWN_CATEGORIES.toList()))
            }).toString()
        }
        val want = types?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        val picked = all.filter { spec ->
            (cat == null || spec.category == cat) && (want.isEmpty() || spec.type in want)
        }
        val arr = JSONArray()
        var used = 2
        for (spec in picked) {
            val o = JSONObject().apply {
                put("type", spec.type)
                put("category", spec.category)
                put("description", spec.description)
                put("sample", spec.sample)
            }
            val line = o.toString()
            if (used + line.length + 1 > maxChars) {
                arr.put(JSONObject().apply {
                    put("type", spec.type)
                    put("note", "已截断，剩余 ${picked.size - arr.length()} 种请缩小 category/types 范围再取")
                })
                break
            }
            arr.put(o)
            used += line.length + 1
        }
        return arr.toString()
    }

    /** 紧凑清单的类目顺序（与目录页一致，缺后不影响正确性）。 */
    private val CATEGORY_ORDER = listOf(
        "input", "data", "layout", "action", "nav", "media", "flow", "decoration", "aiwrite",
    )

'''

src = io.open(P, encoding="utf-8").read()
if "fun compactCatalog()" in src:
    print("SKIP: 紧凑清单 API 已存在")
    sys.exit(0)

if src.count(ANCHOR) != 1:
    print("ABORT: 锚点命中 %d 次" % src.count(ANCHOR))
    sys.exit(1)

src = src.replace(ANCHOR, BLOCK + ANCHOR)

tmp = P + ".tmp"
with io.open(tmp, "w", encoding="utf-8", newline="\n") as f:
    f.write(src)
os.replace(tmp, P)
print("OK: 已插入 compactCatalog / samples")