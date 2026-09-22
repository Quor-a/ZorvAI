package com.ai.assistance.quro.genui.aiapp.brain

import android.content.Context
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.QuroPlatformManifest
import com.ai.assistance.quro.core.QuroTagRepository
import com.ai.assistance.quro.core.QuroToolCall
import com.ai.assistance.quro.core.QuroToolSpec
import com.ai.assistance.quro.core.memory.QuroMemoryRepository
import com.ai.assistance.quro.core.model.QuroModelConfig
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
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
        val m = runCatching { modelConfig().model }.getOrDefault("未配置")
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

        return sb.toString().trimEnd()
    }

    /**
     * 设计/美术技能层（宿主的技能库，默认启用、可在「技能」页单独关掉）。
     *
     * GenUI 每一轮都是在写界面，所以这一层**总是注入**，不做触发词匹配——
     * 触发词那套是给主对话省 token 用的，这里省了就等于让模型裸奔。
     * 内容来自宿主 QuroSkillStore 的 design-studio 套件：界面手艺 / 设计系统 / 自检评分 / 美术指导 / 模式库。
     */
    private fun appendDesignSkills(sb: StringBuilder) {
        val skills = runCatching { QuroSkillStore.designSkills(appCtx) }.getOrNull()
        if (skills.isNullOrEmpty()) return
        sb.append("\n\n## 设计技能层（宿主技能库 · 默认启用）\n")
        sb.append("下面是若干份设计规范，生成界面时按其执行；与上文冲突时，以本层为准。\n\n")
        skills.forEach { s ->
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
        @Volatile private var cachedRegistry: QuroToolRegistry? = null
        @Volatile private var cachedEngine: QuroToolEngine? = null
    }
}
