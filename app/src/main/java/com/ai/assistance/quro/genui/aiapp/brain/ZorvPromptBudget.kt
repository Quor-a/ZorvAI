package com.ai.assistance.quro.genui.aiapp.brain

import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.memory.QuroMemoryEntry

/**
 * GenUI Agent 的**提示词预算编排器**（纯逻辑，不碰 Android / Context，可 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * 线上事故「所有类型界面都画不出来 / 有时候模型不通」的真实体积实测（不是估算）：
 *
 * | 层 | 实测字符 | 占比 |
 * |---|---|---|
 * | 工具 schema（258 个） | 129,351 | 52% |
 * | GenUiRules 组件清单 | 38,604 | 19% |
 * | design-studio（5 份） | 12,917 | 6% |
 * | 平台基座 | 4,812 | 3% |
 * | 灵魂层（无记忆基线） | ~900 | <1% |
 * | 历史（约 10 轮） | ~6,000 | 2% |
 * | **单轮总输入** | **≈186,857** | **≈110K token** |
 *
 * 后果与用户症状一一对应：
 *  · 小上下文模型（32K / 64K）上游直接 400 →「有时候模型都不通」；
 *  · 大窗口模型输入塞得下但输出预算被挤 → ```genui 围栏吐不完整 →「界面画不出来」。
 *
 * ## 旧预算为什么形同虚设
 *
 * 旧 `appendDesignSkills` 是 `allowedChars = (windowTokens * 0.25) * 1.7`：
 *  · 默认 `contextWindow = 1048576` → 允许 445,644 字符，而 design-studio 全量才 12,917
 *    → **永远裁不到**，预算等于没写；
 *  · 而且它**从零开始算**，把已经用掉的 `GenUiRules.RULES` 38,604 字符
 *    完全排除在预算之外 —— 最大的那一块反而不计账。
 *
 * 现在改成「先扣已用、再按份额分配」，且三个层（工具 / 设计技能 / 记忆）共用同一条预算。
 *
 * ## 收敛原则（铁律，违反过会被真机打回）
 *
 * **只丢整份，绝不裁半份。**
 *  · 工具：整份不下发，绝不压短 description / parametersJson（压短会让模型看到残缺schema 而误用）；
 *  · 设计技能：整份不注入，绝不截半份规范；
 *  · 记忆：整条不注入，绝不截半句话。
 *
 * 「把已有信息压缩掉」是 N13–N15 被真机反馈打回的做法（压缩后 AI 拿到的信息更少、
 * 答非所问与复读），已随 af5fe17 整体回滚，勿再引入。
 */
internal object ZorvPromptBudget {

    /** 中文约 1.7 字符/token（实测校准值；混排英文时偏高估，偏保守是安全的）。 */
    const val CHARS_PER_TOKEN: Double = 1.7

    /**
     * 工具层最多占「**剩余**输入预算」的比例。
     *
     * 🔴 取的是剩余额度而不是总窗口 —— 这是实测踩出来的：
     * system 固定部分（GenUiRules 38,604 + 基座 4,812 + 灵魂层 + 渲染规则）
     * 已经是 **44,589 字符 ≈ 26K token**，在 32K 小窗口模型上**光 system 就吃掉 80%**。
     * 若按总窗口算份额（`tokens × 0.35 × 1.7` = 19,497 字符），
     * 64,068 字符仍然超出 55,705 字符的整窗 —— 上游照样 400，预算等于没兜住。
     */
    const val TOOL_SHARE_OF_REMAINING: Double = 0.45

    /**
     * 设计技能层最多占输入预算的比例。
     *
     * 取 0.15 而非旧版的 0.25，且**改为从「剩余预算」里扣**（减去已拼进 system 的字符）。
     */
    const val DESIGN_SHARE_OF_INPUT: Double = 0.15

/**
     * 长期记忆最多占「system 内剩余」的比例。
 *
 * 记忆是拼在 system **内部**的（灵魂层），所以它和设计技能层一样，
 * 要跟「组件清单 38,604 + 基座 4,812」抢同一个 32K 窗口，
 * 也必须按剩余额度算，不能按总窗口算。
 */
const val MEMORY_SHARE_OF_SYSTEM: Double = 0.08

    /** 工具层最小预算（字符）：低于此值宁可不发工具，也不要发半截。 */
    const val MIN_TOOL_CHARS: Int = 4_000

    /** 设计技能层最小预算（字符）：连一份技能都放不下时，宁可整层不注入。 */
    const val MIN_DESIGN_CHARS: Int = 2_000

    /**
     * 画界面真正用得到的工具（按注册顺序之外的**优先保**名单）。
     *
     * 命不中的工具不是不能用，只是预算不够时先被丢掉 —— 日志会打出来，可归因。
     */
    val PRIORITY_TOOLS: Set<String> = setOf(
        "get_device_info", "get_current_time", "calculate", "run_code",
        "web_search", "read_url", "http_request",
        "list_files", "read_text_file", "file_read", "file_info", "find_files",
        "terminal_run", "terminal_exec", "terminal_write", "terminal_status",
        "speak", "stop_speak", "get_clipboard", "set_clipboard",
        "get_battery", "get_network_info", "get_location",
        "get_active_notifications", "get_wifi_info",
        "list_installed_apps", "launch_app", "search_and_launch_app",
        "read_calendar", "get_sensors", "vibrate",
    )

    /** 工具层收敛结果（带诊断信息，供调用方打日志；不自己打，避免单测依赖 android.util.Log）。 */
    data class ToolFit(
        val specs: List<QuroToolSpec>,
        val beforeCount: Int,
        val beforeChars: Int,
        val allowedChars: Int,
        val truncated: Boolean,
    )

    /** 长期记忆收敛结果。 */
    data class MemoryFit(
        val entries: List<QuroMemoryEntry>,
        val beforeCount: Int,
        val beforeChars: Int,
        val allowedChars: Int,
        val truncated: Boolean,
    )

    /** 设计技能层可用字符数（**已扣掉 system 里已用的字符**）。负数= 一份都放不下。 */
    data class DesignRoom(
        val allowedChars: Int,
        val alreadyUsedChars: Int,
        val budgetTokens: Int,
        /** `allowedChars <= 0` 表示剩余预算连一份技能都放不下 → 整层不注入。 */
        val fitsNothing: Boolean,
    )

    /** token 预算 → 字符预算。`tokens <= 0`（未知）时返回 [Int.MAX_VALUE]，即不收敛。 */
    fun chars(tokens: Int, share: Double, minChars: Int): Int =
        if (tokens <= 0) Int.MAX_VALUE
        else (tokens * share * CHARS_PER_TOKEN).toInt().coerceAtLeast(minChars)

    /**
     * token 预算 → 「扣除 system 已用后**还能给这一层**」的字符额度。
     *
     * @param alreadyUsedChars 已经拼进 system 的字符数（GenUI 侧由 `systemPrompt().length` 传入）
     */
    fun remainingChars(tokens: Int, alreadyUsedChars: Int, shareOfRemaining: Double, minChars: Int): Int {
        if (tokens <= 0) return Int.MAX_VALUE
        val total = (tokens * CHARS_PER_TOKEN).toInt()
        val remaining = (total - alreadyUsedChars).coerceAtLeast(0)
        return (remaining * shareOfRemaining).toInt().coerceAtLeast(minChars)
    }

    /**
     * 工具层按预算收敛：**整份取舍，不裁内容**。
     *
     * @param budgetTokens 输入预算 token；`<= 0` 视为未知 → 全量下发（不收敛）
     * @param alreadyUsedChars 已经拼进 system 的字符数（务必传入，否则小窗口模型必然超限 ——
     *        system 固定部分就已占44,589 字符 ≈ 26K token）
     */
    fun fitTools(
        all: List<QuroToolSpec>,
        budgetTokens: Int,
        alreadyUsedChars: Int = 0,
    ): ToolFit {
        val totalChars = all.sumOf { it.description.length + it.parametersJson.length }
        val allowed = remainingChars(budgetTokens, alreadyUsedChars, TOOL_SHARE_OF_REMAINING, MIN_TOOL_CHARS)
        if (all.isEmpty() || budgetTokens <= 0 || totalChars <= allowed) {
            return ToolFit(all, all.size, totalChars, allowed, truncated = false)
        }

        val kept = ArrayList<QuroToolSpec>(all.size)
        var used = 0
        // 第一轮：白名单工具优先全保（它们是 GenUI 的实际依赖）
        for (t in all) {
            if (t.name !in PRIORITY_TOOLS) continue
            val cost = t.description.length + t.parametersJson.length
            if (used + cost > allowed) continue
            kept.add(t); used += cost
        }
        // 第二轮：其余按注册顺序补足剩余预算
        for (t in all) {
            if (t.name in PRIORITY_TOOLS) continue
            val cost = t.description.length + t.parametersJson.length
            if (used + cost > allowed) continue
            kept.add(t); used += cost
        }
        // 极端情况下（预算连一份工具都放不下）至少发一份，别让 Agent 彻底没有工具
        if (kept.isEmpty()) return ToolFit(all.take(1), all.size, totalChars, allowed, truncated = true)
        return ToolFit(kept, all.size, totalChars, allowed, truncated = true)
    }

/**
     * 设计技能层的**剩余**预算：先按份额算出总额，再扣掉 system 里已经用掉的字符。
     *
     * 这是旧实现最大的漏洞 —— 它从零开始算，把 38,604 字符的组件清单排除在预算外，
     * 结果 `allowedChars` 高达 445,644，design-studio 全量 12,917 永远裁不到。
     */
    fun designRoom(budgetTokens: Int, alreadyUsedChars: Int): DesignRoom {
        if (budgetTokens <= 0) {
            return DesignRoom(Int.MAX_VALUE, alreadyUsedChars, budgetTokens, fitsNothing = false)
        }
        val total = (budgetTokens * DESIGN_SHARE_OF_INPUT * CHARS_PER_TOKEN).toInt()
        val remaining = total - alreadyUsedChars
        return DesignRoom(
            allowedChars = remaining,
            alreadyUsedChars = alreadyUsedChars,
            budgetTokens = budgetTokens,
            fitsNothing = remaining < MIN_DESIGN_CHARS,
        )
    }

    /**
     * 长期记忆按预算收敛：**整条取舍，绝不裁半条**。
     *
     * 排序策略：按 `updatedAt` 倒序取（最近的记忆最 relevant），但**输出时恢复原顺序**，
     * 免得提示词里的记忆条目顺序被时间倒排打乱、读起来像乱序清单。
     *
     * @param budgetTokens 输入预算 token；`<= 0` 视为未知 → 全量下发（不收敛）
     * @param alreadyUsedChars 记忆**之前**已拼进 system 的字符数（基座 + 人格卡 + 标签…）
     */
    fun fitMemories(
        all: List<QuroMemoryEntry>,
        budgetTokens: Int,
        alreadyUsedChars: Int = 0,
    ): MemoryFit {
        val totalChars = all.sumOf { it.content.length + it.tags.joinToString(",").length + 3 }
        val allowed = remainingChars(budgetTokens, alreadyUsedChars, MEMORY_SHARE_OF_SYSTEM, 0)
        if (all.isEmpty() || budgetTokens <= 0 || totalChars <= allowed) {
            return MemoryFit(all, all.size, totalChars, allowed, truncated = false)
        }
        val order = all.indices.sortedByDescending { all[it].updatedAt }
        val keepIdx = LinkedHashSet<Int>()
        var used = 0
        for (i in order) {
            val m = all[i]
            val cost = m.content.length + m.tags.joinToString(",").length + 3
            if (used + cost > allowed) continue
            keepIdx.add(i)
            used += cost
        }
        // 全被跳过（单条就超预算）时至少保最近一条，避免记忆层彻底消失
        if (keepIdx.isEmpty()) keepIdx.add(order.first())
        val kept = all.filterIndexed { i, _ -> i in keepIdx }
        return MemoryFit(kept, all.size, totalChars, allowed, truncated = true)
    }
}