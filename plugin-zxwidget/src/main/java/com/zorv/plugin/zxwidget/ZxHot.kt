package com.zorv.plugin.zxwidget

import org.json.JSONObject

/**
 * 卡片热更新 —— 不重装插件就加一个组件。
 *
 * 为什么需要：现在加一个组件要改 6 处（渲染器、校验、注册表、
 * 示例、规格、入口），改完还要重新编译安装插件。
 * 而"想加一个组件"这件事在实际使用里是**高频**的：
 * 用户有一类固定要展示的东西，希望 AI 每次都按那个形状画。
 *
 * 热更新要解决的不是"能不能动态加载代码"（那样等于开一个 RCE），
 * 而是：**能不能用声明的方式描述一个新组件，由已有渲染器拼出来**。
 *
 * 所以设计上刻意不做代码注入，只做三件事：
 *
 *   一、**模板**：把已有组件 + 一组字段默认值 + 固定样式
 *       打包成一个新名字。本质是别名 + 预设，不执行任何新代码。
 *
 *   二、**组合**：把多个已有组件按容器（tabs/accordion/stack）
 *       拼成一个新组件。组合关系本身就是数据。
 *
 *   三、**字段映射**：新组件收到 DSL 后，按映射规则改字段名，
 *       再交给底层组件渲染。
 *
 * 三处安全约束是硬性的，不是可选项：
 *
 *   · **只能引用已注册的组件**，不能引用未注册的
 *   · **不能覆盖内置组件名**——否则用户会覆盖掉 zx_table，
 *     然后所有历史卡片都变样
 *   · **深度上限 3 层**——组合里再套组合，套几层就会
 *     指数级膨胀，一次渲染要画几十个组件
 */
object ZxHot {

    enum class Kind { ALIAS, COMBO }

    data class Def(
        val name: String,
        val kind: Kind,
        /** ALIAS：底层组件名；COMBO：容器名 */
        val base: String,
        val label: String,
        /** ALIAS：字段默认值/重命名后的固定值 */
        val defaults: JSONObject = JSONObject(),
        /** ALIAS：字段重命名映射 新名 → 旧名 */
        val map: Map<String, String> = emptyMap(),
        /** COMBO：子组件的变量名 → 组件名 */
        val parts: Map<String, String> = emptyMap(),
        val t: Long = System.currentTimeMillis()
    )

    private val defs = LinkedHashMap<String, Def>()
    private const val MAX_DEPTH = 3
    private const val MAX_DEFS = 40

    /** 内置组件名，不允许覆盖 */
    private fun builtin(name: String) =
        ZxRegistry.names().contains(name) && name !in defs

    /**
     * 注册一个新的热更新组件。
     * 返回失败原因而不是抛异常——模型要能读到原因并自己改对。
     */
    fun define(d: Def): Result {
        if (d.name.isBlank()) return Result(false, "name 为空")
        if (!d.name.matches(Regex("zx_[a-z][a-z0-9_]{1,20}")))
            return Result(false, "${d.name} 命名不合法，需匹配 zx_[a-z][a-z0-9_]{1,20}")
        // 覆盖内置组件会让所有历史卡片变样
        if (builtin(d.name))
            return Result(false, "${d.name} 是内置组件，不允许覆盖——否则所有历史卡片都会变样")
        if (defs.size >= MAX_DEFS && d.name !in defs)
            return Result(false, "热更新组件已达上限 $MAX_DEFS 个")

        when (d.kind) {
            Kind.ALIAS -> {
                if (!resolveBase(d.base))
                    return Result(false, "base=${d.base} 未注册——只能引用已注册的组件")
            }
            Kind.COMBO -> {
                val containers = setOf("zx_stack", "tabs", "accordion", "split", "carousel",
                                       "grid", "master", "stagger")
                if (d.base !in containers)
                    return Result(false, "base=${d.base} 不是可用容器（可选：${containers.joinToString("/")}）")
                if (d.parts.isEmpty())
                    return Result(false, "COMBO 至少要有一个 part")
                d.parts.values.forEach { p ->
                    if (!resolveBase(p))
                        return Result(false, "part=$p 未注册——只能引用已注册的组件")
                }
            }
        }

        defs[d.name] = d
        // 深度检查必须在放入之后做，否则自引用检测不到
        val depth = depthOf(d.name, mutableSetOf())
        if (depth > MAX_DEPTH) {
            defs.remove(d.name)
            return Result(false, "${d.name} 展开后深度 $depth 超过上限 $MAX_DEPTH——" +
                                 "组合里套组合会指数级膨胀")
        }
        return Result(true, "已注册 ${d.name}（${d.kind}，展开深度 $depth）")
    }

    /** 底层名解析：可能是内置，可能是另一个热更新组件 */
    private fun resolveBase(name: String): Boolean =
        ZxRegistry.names().contains(name) || defs.containsKey(name)

    /** 展开深度，带环检测 */
    fun depthOf(name: String, seen: MutableSet<String>): Int {
        val d = defs[name] ?: return 1
        if (!seen.add(name)) return Int.MAX_VALUE      // 环
        val inner = when (d.kind) {
            Kind.ALIAS -> depthOf(d.base, seen)
            Kind.COMBO -> 1 + (d.parts.values.maxOfOrNull { depthOf(it, seen) } ?: 0)
        }
        return if (inner == Int.MAX_VALUE) Int.MAX_VALUE else 1 + inner
    }

    fun has(name: String) = defs.containsKey(name)
    fun get(name: String) = defs[name]
    fun all(): List<Def> = defs.values.toList()

    fun undefine(name: String): Result {
        if (defs.remove(name) == null) return Result(false, "$name 不是热更新组件（内置的删不掉）")
        return Result(true, "已删除 $name")
    }

    /**
     * 把新组件的 DSL 降级到底层组件。
     * 渲染器拿到的是它本来就认识的形状。
     */
    fun lower(name: String, dsl: JSONObject, depth: Int = 0): JSONObject {
        if (depth > MAX_DEPTH) return dsl
        val d = defs[name] ?: return dsl
        return when (d.kind) {
            Kind.ALIAS -> {
                val out = JSONObject()
                out.put("type", d.base)
                // 先铺默认值，再按映射把新字段写到旧名字上
                d.defaults.keys().forEach { k -> out.put(k, d.defaults.opt(k)) }
                dsl.keys().forEach { k ->
                    if (k == "type") return@forEach
                    out.put(d.map[k] ?: k, dsl.opt(k))
                }
                out
            }
            Kind.COMBO -> {
                val out = JSONObject()
                out.put("type", d.base)
                val kids = org.json.JSONArray()
                d.parts.forEach { (slot, comp) ->
                    val sub = dsl.optJSONObject(slot) ?: JSONObject()
                    val lowered = lower(comp, sub, depth + 1)
                    lowered.put("type", comp)
                    kids.put(lowered)
                }
                out.put("items", kids)
                dsl.keys().forEach { k ->
                    if (k == "type" || k in d.parts) return@forEach
                    out.put(k, dsl.opt(k))
                }
                out
            }
        }
    }

    /** 热更新组件也要过校验：降级后交给底层组件的校验 */
    fun validate(name: String, dsl: JSONObject): Pair<Boolean, String> {
        val d = defs[name] ?: return false to "$name 不是热更新组件"
        val lowered = lower(name, dsl)
        return ZxDsl.validateAny(lowered.optString("type"), lowered).let { it.ok to it.reason }
    }

    fun list(): String = buildString {
        if (defs.isEmpty()) { appendLine("还没有热更新组件。"); return@buildString }
        defs.values.forEach { d ->
            appendLine("${d.name} · ${d.label}")
            appendLine("  ${d.kind} → base=${d.base}")
            if (d.kind == Kind.COMBO && d.parts.isNotEmpty())
                appendLine("  parts=${d.parts.entries.joinToString { "${it.key}:${it.value}" }}")
            if (d.map.isNotEmpty())
                appendLine("  map=${d.map.entries.joinToString { "${it.key}→${it.value}" }}")
        }
    }

    /** 自检：有没有环、有没有引用了已消失的组件 */
    fun audit(): List<String> {
        val out = mutableListOf<String>()
        defs.values.forEach { d ->
            if (depthOf(d.name, mutableSetOf()) == Int.MAX_VALUE)
                out.add("${d.name} 存在循环引用")
            val bases = when (d.kind) {
                Kind.ALIAS -> listOf(d.base)
                Kind.COMBO -> d.parts.values.toList()
            }
            bases.forEach { b ->
                if (!resolveBase(b)) out.add("${d.name} 引用了 $b，但它已不存在")
            }
        }
        return out
    }

    fun clear() = defs.clear()

    data class Result(val ok: Boolean, val reason: String = "")

    fun guide(): String = """
        zx_hot(op=define|undefine|list|audit|lower|guide, name?, kind?, base?, ...)

        不重装插件就加一个组件。

        为什么需要：现在加组件要改 6 处（渲染器、校验、注册表、
        示例、规格、入口）再重新编译安装。而「想加一个组件」是高频的：
        用户有一类固定要展示的东西，希望 AI 每次都按那个形状画。

        刻意不做代码注入——那等于开一个 RCE。只做三件事：
          一、模板（ALIAS）：已有组件 + 字段默认值 + 固定样式，
              本质是别名 + 预设，不执行任何新代码
          二、组合（COMBO）：多个已有组件按容器拼起来
          三、字段映射：新 DSL 按映射改字段名，再交给底层组件渲染

        三条硬约束：
          · 只能引用已注册的组件
          · 不能覆盖内置组件名——否则用户会覆盖掉 zx_table，
            然后所有历史卡片都变样
          · 深度上限 3 层——组合里套组合会指数级膨胀
    """.trimIndent()
}
