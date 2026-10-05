package com.ai.assistance.quro.genui.aiapp.brain
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.QuroPlatformManifest
import com.ai.assistance.quro.core.QuroReplyLanguage
import com.ai.assistance.quro.core.QuroTagRepository
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroModelContextBudget
import com.ai.assistance.quro.core.skill.QuroSkill
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.soul.QuroSoulPromptEngine
import com.ai.assistance.quro.core.soul.SoulContext
import com.ai.assistance.quro.core.tools.QuroToolEngine
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GenUI Agent 的宿主对接层（唯一的 ZorvAI 侧接入点）。
 *
 * 设计原则：aiapp 不再自带模型配置、不再自带人格/灵魂注入，全部改用 ZorvAI 宿主既有设施：
 *  · 模型配置 → [QuroModelConfigRepository]（与主对话共用同一份 "quro_model_config"）
 *  · 身份/人格/记忆 → [QuroSoulPromptEngine] + [QuroPlatformManifest.SYSTEM]（与主对话同一套灵魂层）
 *  · 工具调用 → 宿主 [QuroToolRegistry]（fullSpecs ~ 全部内置工具）+ [QuroToolEngine] 执行
 *
 * aiapp 自己只保留一层「GenUI 表达规则」（[GenUiRules]），因为它是输出形态约束，不是人格。
 */
class ZorvBrain(private val context: Context) {

    private val appCtx: Context = context.applicationContext
    private val modelRepo = QuroModelConfigRepository(appCtx)
    private val personaRepo = QuroPersonaRepository(appCtx)
    private val memoryRepo = QuroMemoryRepository(appCtx)
    private val tagRepo = QuroTagRepository(appCtx)
    private val uiPrefs = appCtx.getSharedPreferences("quro_ui", Context.MODE_PRIVATE)

    // ─────────────────────────── 模型配置（宿主的） ───────────────────────────

    /** 当前生效的模型配置（ZorvAI 主对话同一份）。 */
    fun modelConfig(): QuroModelConfig = modelRepo.load()

    /** 云端 / 本地离线模型判定：本地模型（MNN / llama.cpp）由宿主 QuroLocalEngine 承载，GenUI 不走该通道。 */
    fun isLocalProvider(cfg: QuroModelConfig): Boolean =
        cfg.provider == "MNN" || cfg.provider == "LLAMA_CPP"

    /** 「AI 自动保存记忆」开关（宿主的，同样与主对话共用）。 */
    fun autoSaveMemory(): Boolean = uiPrefs.getBoolean("auto_save_memory", true)

    // ─────────────────────────── 灵魂层（宿主的） ───────────────────────────

    /** 当前激活的人格卡。 */
    fun activePersona(): QuroPersona = personaRepo.getActive()

    /** 供 UI 显示的一行摘要：人格卡名 + 模型名。 */
    fun summary(): String {
        val p = runCatching { activePersona().name }.getOrDefault("默认人格")
        val m = runCatching { modelConfig().model }.getOrDefault(qstr(R.string.qk_00015))
        return "$p · $m"
    }

    /**
     * 系统提示词：三层拼接，与主对话同构。
     *   ① 平台/品牌基座（QuroPlatformManifest.SYSTEM，ALWAYS-ON）
     *   ② 灵魂层（QuroSoulPromptEngine：人格卡身份 + 风格 + 标签 + 长期记忆）
     *   ③ GenUI 表达层（GenUiRules：把回复表达成界面，能力约束非人格）
     */
    fun systemPrompt(): String {
        // 本次请求的输入预算（token）。三层（记忆 / 设计技能 / 工具）共用它，
        // 否则每层各算一遍「窗口的百分之几」，加起来必然超窗。
        val budgetTokens = inputBudgetTokens()
        val sb = StringBuilder()
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")

        // 「AI 回复语言」：GenUI Agent 走独立提示词，此前没有语言指令 → 界面切英文后
        // 它生成的界面文案与对话文字仍是中文。
        sb.append(QuroReplyLanguage.directive(appCtx))

        val persona = runCatching { activePersona() }.getOrNull()

        // 🔴 长期记忆按预算收敛（整条取舍，绝不裁半条）。
        //
        // 旧实现把 loadForPersona() 的**全量**结果直接灌进 system，而 loadForPersona
        // 内部是 loadAll() 无条数上限、无字符上限 —— 「AI 自动保存记忆」默认开着，
        // 每轮都在累加 → 用得越久 system 越长 → 「前几天开始越用越坏」的机制。
        // 这不是渲染层 bug，是提示词体积问题。
        val memFit = persona?.let { p ->
            val all = runCatching { memoryRepo.loadForPersona(p.id) }.getOrDefault(emptyList())
            // 记忆是拼在 system **内部**的，所以额度要跟「基座 + 人格卡 + 标签」抢同一个窗口，
            // 不能按总窗口算 —— 这与设计技能层 / 工具层是同一个坑（实测见 ZorvPromptBudget）。
            ZorvPromptBudget.fitMemories(all, budgetTokens, sb.length).also { f ->
                if (f.truncated) {
                    android.util.Log.w(
                        "ZorvBrain",
                        "长期记忆按 system 剩余预算收敛：${f.beforeCount} 条/${f.beforeChars}字符 → " +
                        "${f.entries.size} 条（system 已用 ${sb.length}字符，该层额度 ${f.allowedChars}字符）。" +
                        "丢整条、不裁半条。"
                    )
                }
            }
        }

        val soul = QuroSoulPromptEngine.build(
            SoulContext(
                persona = persona,
                tags = persona?.let { runCatching { tagRepo.resolve(it.tags) }.getOrDefault(emptyList()) }
                    ?: emptyList(),
                memories = memFit?.entries ?: emptyList(),
                autoSaveMemory = autoSaveMemory(),
                voiceStyleHint = null, // GenUI 是视觉通道，不注入语音风格提示
            )
        )
        if (soul.isNotBlank()) sb.append(soul).append("\n\n")

        sb.append("## GenUI 表达层（本会话的输出形态）\n")
        sb.append("以下约束的是「你怎么表达」，不改变你的身份：你的名字与人格始终以当前激活的人格卡为准。\n\n")
        sb.append(GenUiRules.RULES)
        appendDesignSkills(sb, budgetTokens)

        // 语言指令结尾复述（近因强化）：设计技能层篇幅很长，必须压在最末尾。
        sb.append(QuroReplyLanguage.tailReminder(appCtx))

        return sb.toString().trimEnd()
    }

    /**
     * 设计/美术技能层（宿主的技能库，GenUI 强制注入，无视「技能」页的开关）。
     *
     * GenUI 每一轮都是在写界面，所以这一层**总是注入**，不做触发词匹配——
     * 触发词那套是给主对话省 token 用的，这里省了就等于让模型裸奔。
     * 内容来自宿主 QuroSkillStore 的 design-studio 套件：界面手艺 / 设计系统 / 自检评分 / 美术指导 / 模式库。
     */
    private fun appendDesignSkills(sb: StringBuilder, budgetTokens: Int) {
        // 用 designSkillsForGenUI 而非 designSkills：后者遵守用户在「技能」页的开关，
        // 一旦被关掉 GenUI 就裸奔（这正是「GenUI 没用 skills」的根因）；GenUI 强制带这套规范。
        val skills = runCatching { QuroSkillStore.designSkillsForGenUI(appCtx) }.getOrNull()
        if (skills.isNullOrEmpty()) return

        // 🔴 按「**剩余**预算」决定注几份，而不是按「窗口的 1/4」。
        //
        // 旧算法（v1.1.3 引入）有两个致命问题，导致这个预算**从来没生效过**：
        //  ① allowedChars = (windowTokens * 0.25) * 1.7，而默认 contextWindow = 1048576
        //     → 允许 445,644 字符；design-studio 全量只有 12,917 → 永远裁不到，
        //     预算写了等于没写（实测已确认）。
        //  ② 它**从零开始算**，把已经拼进 sb 的 GenUiRules.RULES 38,604 字符
        //     完全排除在预算之外 —— 最大的那一块反而不计账。
        //
        // 现在改为：先按份额算出总额，再扣掉 sb 里已用的字符（ZorvPromptBudget.designRoom）。
        // 小上下文模型上这会真的把设计层砍掉或砍到只剩一两份 —— 这正是目的：
        // 与其整轮被上游 400（用户说的「模型不通」），不如界面规范变简但至少能生成。
        //
        // 仍然只减少注入的**份数**，不裁剪任何单份技能的内容 ——
        // 「把已有信息压缩掉」正是 N13–N15 被真机反馈打回的做法（压缩后 AI 拿到的信息更少、
        // 答非所问与复读），已随 af5fe17 整体回滚，勿再引入。超限时优先丢整份、绝不截半份。
        val room = ZorvPromptBudget.designRoom(budgetTokens, sb.length)
        if (room.fitsNothing) {
            android.util.Log.w(
                "ZorvBrain",
                "设计技能层整层跳过：窗口 ${room.budgetTokens}tok，system 已用 ${room.alreadyUsedChars}字符，"
                    + "该层只剩 ${room.allowedChars}字符 < ${ZorvPromptBudget.MIN_DESIGN_CHARS}。"
                    + "组件清单+基座已占满预算，再注入设计规范只会让整轮被上游拒绝。"
            )
            return
        }

        val kept = ArrayList<QuroSkill>(skills.size)
        var used = 0
        var skipped = 0
        for (sk in skills) {
            val cost = sk.prompt.trim().length
            // 🔴 旧代码这里是 break —— 一份放不下就整层停，后面更小的技能也全丢。
            // 现在改成 continue：只要这份放得下就继续装，别因为排序靠前的一份大就全层报废。
            if (used + cost > room.allowedChars) {
                skipped++
                continue
            }
            kept.add(sk)
            used += cost
        }
        if (kept.isEmpty()) return
        if (skipped > 0) {
            android.util.Log.w(
                "ZorvBrain",
                "设计技能层按剩余预算注到 ${kept.size}/${skills.size} 份：已注 ${used}字符、"
                    + "剩余预算 ${room.allowedChars}字符（system 已用 ${room.alreadyUsedChars}字符，"
                    + "窗口 ${room.budgetTokens}tok），丢弃 ${skipped} 份（整份丢，不截半份）。"
            )
        }

        sb.append("\n\n## 设计技能层（宿主技能库 · 默认启用）\n")
        sb.append("下面是若干份设计规范，生成界面时按其执行；与上文冲突时，以本层为准。\n\n")
        kept.forEach { s ->
            sb.append("### 技能：").append(s.name).append("\n")
            sb.append(s.prompt.trim()).append("\n\n")
        }
    }

    // ─────────────────────────── 工具（宿主的完整工具集） ───────────────────────────

    /**
     * 下发给 LLM 的工具规格。
     *
     * 宿主侧按 [QuroModelConfig.useFullTools] 选 full / core 集；但**本方法额外做一次
     * 按模型真实窗口的预算收敛** —— 因为宿主那两个集的实测规模都远超注释里的假设：
     * ```
     * fullSpecs : 258 个 / 129,351 字符   ← useFullTools 默认 true，GenUI 实际走这条
     * coreSpecs : 201 个 /  86,506 字符   ← 注释写「~23 个 / ~1,200 tokens」，实测是它的十几倍
     * ```
     * 加上 system 侧（GenUiRules 38,604 + 基座 4,812 + design-studio 12,917 + 灵魂层），
     * 单轮输入实测 **≈186,857 字符 ≈ 110K token**，**直接超掉 64K 模型的整窗**。
     * 后果与用户症状一一对应：小上下文模型上游 400 → 「有时候模型都不通」；
     * 大窗口模型输入塞得下但输出预算被挤 → 围栏吐不完整 → 「界面画不出来」。
     *
     * ## 收敛原则（严格遵守「不做成本硬护栏 / 不压缩已有内容」）
     * **只按「工具名白名单」取舍，绝不裁剪任何一份工具的 description / parametersJson。**
     * 被丢掉的工具是整份不下发，而不是把描述压短 —— 压短会让模型看到残缺 schema 而误用，
     * 那是 N13–N15 被真机打回过的做法。
     *
     * 保留优先级：GenUI 画界面真正需要的（设备信息 / 时间 / 计算 / 文件只读 / 联网检索 /
     * 终端 / 技能与插件管理）→ 其余按注册顺序补足预算。
     */
    fun toolSpecs(cfg: QuroModelConfig): List<QuroToolSpec> {
        val reg = registry()
        val all = runCatching {
            if (cfg.useFullTools) reg.fullSpecs() else reg.coreSpecs()
        }.getOrElse { reg.specs() }
        // 🔴 工具层与 system **共享同一个窗口**，所以额度必须扣掉 system 已用的字符。
        // 实测（32K 小窗口模型）：system 固定部分就有 44,589 字符 ≈ 26K token（占整窗 80%），
        // 若按总窗口算工具份额，收敛后单轮输入仍是 64,068 字符 > 55,705 字符的整窗
        // → 上游照样 400，预算等于没兜住。
        return budgetToolSpecs(all, inputBudgetTokens(cfg), systemPrompt().length)
    }

    /**
     * 按输入预算收敛工具集：**整份取舍，不裁内容**。
     *
     * 委托 [ZorvPromptBudget.fitTools]（纯逻辑，可 JVM 单测）—— 生产代码与测试走同一份实现，
     * 保证「测的就是发的」。
     *
     * @param budgetTokens 本次请求可用的输入 token 预算；`<= 0` 表示未知 → 不收敛（全量下发）
     * @param alreadyUsedChars 已经拼进 system 的字符数
     */
    internal fun budgetToolSpecs(
        all: List<QuroToolSpec>,
        budgetTokens: Int,
        alreadyUsedChars: Int = 0,
    ): List<QuroToolSpec> {
        val fit = ZorvPromptBudget.fitTools(all, budgetTokens, alreadyUsedChars)
        if (!fit.truncated) return fit.specs
        android.util.Log.w(
            "ZorvBrain",
            "工具集按 system 剩余预算收敛：${fit.beforeCount} 个/${fit.beforeChars}字符 → " +
                "${fit.specs.size} 个/${fit.specs.sumOf { it.description.length + it.parametersJson.length }}字符" +
                "（窗口 ${budgetTokens}tok，system 已用 ${alreadyUsedChars}字符，" +
                "工具层额度 ${fit.allowedChars}字符）。丢整份、不裁内容。"
        )
        return fit.specs
    }

    /** 本次请求的输入预算（token）；解析不出来返回 0 = 不收敛（全量下发）。 */
    private fun inputBudgetTokens(cfg: QuroModelConfig): Int = runCatching {
        QuroModelContextBudget.resolve(
            modelName = cfg.model,
            provider = cfg.provider,
            apiContextLength = cfg.modelContextLength,
            userContextWindow = cfg.contextWindow,
        ).inputTokens
    }.getOrNull() ?: 0

    /** 无参版本（供 [systemPrompt] 用）：读当前模型配置 → 解析输入预算。 */
    private fun inputBudgetTokens(): Int = runCatching {
        inputBudgetTokens(modelConfig())
    }.getOrDefault(0)

    /**
     * 执行一个工具（走宿主 [QuroToolEngine]：含权限前置申请、60s 超时、失败重试、
     * 技能工具与 APK 插件工具分支）。
     */
    suspend fun executeTool(name: String, arguments: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val results = engine().execute(appCtx, listOf(QuroToolCall(name = name, arguments = arguments)))
            results.firstOrNull()?.result?.takeIf { it.isNotBlank() } ?: "工具「$name」未返回内容"
        }.getOrElse { e ->
            "工具执行异常: ${e.message}"
        }
    }

    /** 宿主工具注册表：优先复用主对话已建好的实例（同一份真相源），无则自建。 */
    private fun registry(): QuroToolRegistry {
        QuroToolRegistry.active?.let { return it }
        return synchronized(LOCK) {
            QuroToolRegistry.active?.let { return it }
            buildQuroRegistry(appCtx).also { QuroToolRegistry.active = it }
        }
    }

    /** 工具执行引擎：按注册表实例缓存（构造时会为每个工具建 MCP 适配，不宜重复创建）。 */
    private fun engine(): QuroToolEngine {
        val reg = registry()
        cachedEngine?.let { if (cachedRegistry === reg) return it }
        return synchronized(LOCK) {
            cachedEngine?.let { if (cachedRegistry === reg) return@let it }
            QuroToolEngine(reg).also {
                it.setContext(appCtx)
                cachedRegistry = reg
                cachedEngine = it
            }
        }
    }

    private companion object {
        private val LOCK = Any()

        // 预算的所有参数（份额 / 最小值 / 优先工具名单）都搬到了 ZorvPromptBudget ——
        // 那个对象是纯逻辑、可 JVM 单测，而这里 companion 里的常量单测够不着，
        // 「预算到底怎么算」就只能靠人肉推演，正是这次踩坑的根源。
        // 🔴 别再把预算常量搬回这里。

        @Volatile private var cachedRegistry: QuroToolRegistry? = null
        @Volatile private var cachedEngine: QuroToolEngine? = null
    }
}