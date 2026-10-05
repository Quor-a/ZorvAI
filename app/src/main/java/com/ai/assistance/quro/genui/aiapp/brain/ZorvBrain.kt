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
        val sb = StringBuilder()
        sb.append(QuroPlatformManifest.SYSTEM).append("\n\n")

        // 「AI 回复语言」：GenUI Agent 走独立提示词，此前没有语言指令 → 界面切英文后
        // 它生成的界面文案与对话文字仍是中文。
        sb.append(QuroReplyLanguage.directive(appCtx))

        val persona = runCatching { activePersona() }.getOrNull()
        val soul = QuroSoulPromptEngine.build(
            SoulContext(
                persona = persona,
                tags = persona?.let { runCatching { tagRepo.resolve(it.tags) }.getOrDefault(emptyList()) }
                    ?: emptyList(),
                memories = persona?.let { runCatching { memoryRepo.loadForPersona(it.id) }.getOrDefault(emptyList()) }
                    ?: emptyList(),
                autoSaveMemory = autoSaveMemory(),
                voiceStyleHint = null, // GenUI 是视觉通道，不注入语音风格提示
            )
        )
        if (soul.isNotBlank()) sb.append(soul).append("\n\n")

        sb.append("## GenUI 表达层（本会话的输出形态）\n")
        sb.append("以下约束的是「你怎么表达」，不改变你的身份：你的名字与人格始终以当前激活的人格卡为准。\n\n")
        sb.append(GenUiRules.RULES)
        appendDesignSkills(sb)

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
    private fun appendDesignSkills(sb: StringBuilder) {
        // 用 designSkillsForGenUI 而非 designSkills：后者遵守用户在「技能」页的开关，
        // 一旦被关掉 GenUI 就裸奔（这正是「GenUI 没用 skills」的根因）；GenUI 强制带这套规范。
        val skills = runCatching { QuroSkillStore.designSkillsForGenUI(appCtx) }.getOrNull()
        if (skills.isNullOrEmpty()) return

        // 按模型真实窗口决定注几份（修「所有类型界面都画不出来 / 有时模型不通」）。
        //
        // 背景：GenUiRules.RULES 本身就有 3.8 万中文字符，design-studio 再全量注入 5 份
        // （实测 24 KB），单轮输入轻易冲到 6 万字符以上。后果是同一个原因两种表现：
        //   · 小上下文模型（32K/64K）→ 上游 400 context_length_exceeded →「模型不通」；
        //   · 大窗口模型（1M）→ 输入塞得下但输出预算被挤 → 吐不完整 genui 围栏 →「画不出界面」。
        // 这不是渲染层的 bug（解析/桥接/注册表全链路都有兜底），而是提示词体积问题。
        //
        // 注意：这里只减少注入的技能份数，不裁剪任何单份技能的内容 ——
        // 「把已有信息压缩掉」正是 N13–N15 被真机反馈打回的做法（压缩后 AI 拿到的信息更少、
        // 答非所问与复读），已随 af5fe17 整体回滚，勿再引入。超限时优先丢整份、绝不截半份。
        val cfg = runCatching { modelConfig() }.getOrNull()
        val budget = runCatching {
            QuroModelContextBudget.resolve(
                modelName = cfg?.model.orEmpty(),
                provider = cfg?.provider.orEmpty(),
                apiContextLength = cfg?.modelContextLength ?: 0,
                userContextWindow = cfg?.contextWindow ?: 0,
            )
        }.getOrNull()

        // 换算成「能给设计层的字符数」：设计层最多占窗口的 1/4，
        // 剩下的留给组件清单 / 历史 / 输出，避免把输出预算吃光。
        val windowTokens = budget?.inputTokens ?: 0
        val allowedChars = if (windowTokens <= 0) {
            Int.MAX_VALUE
        } else {
            // 1 token 约 1.7 个中文字符
            ((windowTokens * 0.25) * 1.7).toInt().coerceAtLeast(MIN_DESIGN_SKILL_CHARS)
        }

        val kept = ArrayList<QuroSkill>(skills.size)
        var used = 0
        for (sk in skills) {
            val cost = sk.prompt.trim().length
            if (used + cost > allowedChars) {
                android.util.Log.w(
                    "ZorvBrain",
                    "设计技能层按窗口预算注到 ${kept.size}/${skills.size} 份：已注 ${used}字符、"
                        + "允许 ${allowedChars}字符（窗口 ${windowTokens}tok），"
                        + "跳过「${sk.name}」(${cost}字符)。界面规范会变简，但至少能生成。"
                )
                break
            }
            kept.add(sk)
            used += cost
        }
        if (kept.isEmpty()) return

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
     * 默认用宿主 [QuroToolRegistry.fullSpecs]（完整工具集，与模型配置 useFullTools 默认开启一致）；
     * 若用户在宿主设置里关掉了完整工具集，则回退核心集，避免 API 中转静默丢弃 tools 字段。
     */
    fun toolSpecs(cfg: QuroModelConfig): List<QuroToolSpec> {
        val reg = registry()
        return runCatching {
            if (cfg.useFullTools) reg.fullSpecs() else reg.coreSpecs()
        }.getOrElse { reg.specs() }
    }

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
        /** 设计技能层的最小预算（字符）：低于此值连一份技能都放不下，宁可不注入。 */
        private const val MIN_DESIGN_SKILL_CHARS = 2_000
        @Volatile private var cachedRegistry: QuroToolRegistry? = null
        @Volatile private var cachedEngine: QuroToolEngine? = null
    }
}