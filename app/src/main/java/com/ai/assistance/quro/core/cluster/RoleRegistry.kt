package com.ai.assistance.quro.core.cluster

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 角色注册表 —— 复用人格卡 QuroPersona，在其上补一层 RoleProfile。
 *
 * 持久化沿用 QuroPersonaRepository 的风格：filesDir 下单个 JSON 文件，
 * 不引入 Room / DataStore，保持与仓库现有做法一致。
 *
 * ★ 主持不可替换的三道锁：
 *   1. HOST_PERSONA_ID 是常量，ensureHost() 每次都保证它存在；
 *   2. delete() 对主持直接抛异常，且不提供任何 rename / replace 接口；
 *   3. upsert() 对主持只允许改 modelProfileId / context / skills，
 *      身份字段（personaId / role）被强制回写。
 */
object RoleRegistry {

    const val HOST_PERSONA_ID = "persona_cluster_host"
    const val HOST_NAME = "主持"

    /**
     * #215 五专家默认播种：老用户升级后也能直接看到，不用再去角色卡里手动建。
     * 只种一次（幂等），已存在则跳过；失败静默，不阻断启动。
     */
    private const val KEY_EXPERTS_SEEDED = "quro_cluster_experts_seeded_v1"

    /** 文档第四节标注「主持专用」的两个方法论包（会由 [bindHostSkills] 自动绑给主持）。 */
    private val HOST_ONLY_SKILLS = listOf("skill-fetch", "role-forge")
    private const val FILE = "quro_cluster_roles.json"

    /** 主持的誓言：写死在代码里，UI 不提供编辑入口 */
    val HOST_OATH = """
你是本集群的主持，不是参与者。你的唯一职责是把当前任务推到闭环。
你不得放弃任务：只有「目标达成 / 熔断触发 / 用户中止 / 不可恢复错误」四种情况可以结束。
你不得代替角色执行具体工作，你只负责：定义验收标准、拆解、选人、裁决、验收、收敛。
你点名谁，谁才说话。无人可用时你必须自己推进流程，而不是空转等待。
每次点名必须说明理由；每次验收必须对照验收标准逐条给结论。
""".trimIndent()

    // ——————————————— 主持 ———————————————

    /** 保证主持人格与角色配置一定存在（应用启动 / 集群初始化时调用） */
    fun ensureHost(context: Context) {
        // 🔴 灵魂解耦（2026-11-06）：集群身份不再写进 ZorvAI 人格库 —— 主持档案
        // 完全自持在 quro_cluster_roles.json（见 hostProfile），人格库零写入。
        val roles = loadRaw(context).toMutableList()
        if (roles.none { it.personaId == HOST_PERSONA_ID }) {
            roles += hostProfile()
            saveRaw(context, roles)
        }
        bindHostSkills(context)
        seedBuiltinExperts(context)
    }

    /**
     * 测试专用入口：绕过 prefs 一次性守卫，强制重新播种。
     * 生产代码不调用；仅 [ClusterExpertSeedTest] 使用。
     */
    internal fun seedBuiltinExpertsForTest(context: Context) {
        val prefs = context.getSharedPreferences("quro_cluster", Context.MODE_PRIVATE)
        // 先清掉守卫，让重复调用也能走到播种逻辑（内部仍有 personaId 查重，保证幂等）
        val existing = loadRaw(context).map { it.personaId }.toSet()
        seedBuiltinExpertsLocked(context, existing)
    }

    /**
     * #215：把用户点名的五张专家卡默认播种进集群，老用户升级后立即可见。
     *
     * 为什么在这做而不是让用户手动建角：
     * - 角色卡目录虽然已有这 5 张卡，但那是「候选」，不会出现在集群角色列表里；
     * - 用户升级后打开设置页仍只有主持 → 感觉「什么都没改变」。
     *
     * 幂等：用 prefs 标记 + 按 personaId 查重，只种一次；已存在对应角色则跳过。
     * 失败：任何一步异常都静默吞掉，绝不影响启动。
     */
    private fun seedBuiltinExperts(context: Context) {
        val prefs = context.getSharedPreferences("quro_cluster", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EXPERTS_SEEDED, false)) return
        // 🔴 S6（2026-11-06）：播种含 runBlocking 联网（技能解析/现拉现装），而本链路从
        // Application.onCreate → ClusterRuntime.init 进来，是主线程 —— 会 ANR。
        // 标志位先占住防重复播种，重活挪后台线程；失败回滚标志位，下次启动重试。
        prefs.edit().putBoolean(KEY_EXPERTS_SEEDED, true).apply()
        Thread {
            try {
                val existing = loadRaw(context).map { it.personaId }.toSet()
                seedBuiltinExpertsLocked(context, existing)
            } catch (t: Throwable) {
                android.util.Log.w("RoleRegistry", "内置专家人格播种失败（下次启动重试）", t)
                prefs.edit().putBoolean(KEY_EXPERTS_SEEDED, false).apply()
            }
        }.start()
    }

    private fun seedBuiltinExpertsLocked(context: Context, existing: Set<String>) {
        val seededIds = listOf(
            "logic-thinker",
            "prompt-engineer",
            "ai-software-dev",
            "bug-detector",
            "completeness-auditor",
        )
        for (cardId in seededIds) {
            val card = ClusterRoleCards.byId(cardId) ?: continue
            val personaId = "persona_cluster_${card.id}_seed"
            if (personaId in existing) continue
            // 与 ClusterRoleCardTool.createRole 同一套技能解析：本地直接查，开源现拉现装。
            val resolved = runCatching {
                kotlinx.coroutines.runBlocking { card.skills.resolveForRole(context) }
            }.getOrNull() ?: continue
            // 🔴 灵魂解耦（2026-11-06）：不再往 ZorvAI 人格库写 persona_cluster_* ——
            // 角色的灵魂注入自持在 RoleProfile.soulInjection。默认灵魂 = 身份（card.persona）
            // + 灵魂三段（card.soul：信念/鄙视/可判据标准）—— 与人格页灵魂注入同形态，
            // 不含人格孵化/自动进化/自我介绍。
            val role = RoleProfile(
                personaId = personaId,
                name = card.label,
                emoji = card.emoji,
                description = card.tagline,
                soulInjection = buildString {
                    appendLine(card.persona)
                    if (!card.soul.isEmpty) {
                        appendLine()
                        appendLine(card.soul.render())
                    }
                }.trim(),
                role = card.kind,
                // 🔴 2026-11-07：tags 收敛为「单个角色类型名」（与编辑页同一真源）。
                // 卡片原有的能力关键词不丢弃，并入职责继续影响系统提示词。
                duties = (card.duties + card.tags).distinct(),
                taboos = card.taboos,
                skillIds = resolved.skillIds,
                tags = listOf(card.kind.label),
            )
            runCatching { upsert(context, role) }.getOrElse { continue }
        }
    }

    /**
     * 主持启动即绑文档第四节的两个「主持专用」方法论包：skill-fetch / role-forge。
     *
     * 为什么必须常驻：这两个包管的是 CAPABILITY 阶段的补救动作（缺能力时去开源社区取技能 /
     * 造新角色）。主持拿不到这套规程，就只会「没人会 → 跳过」，而文档要求的是
     * 「先补救，补不了才跳过」。它们可调用性为 false，只注入提示词、
     * 不给本地模型注册多余工具；打分时也不该被当成「什么活都能干」的通用能力 ——
     * 真正干活的角色绑的是各自的方法论包（如 ui-design / code-review）。
     *
     * 语义是 **[union + 只增不删]**：用户手动解绑后，下次启动不会被强行绑回去。
     * 查不到 id（老设备播种失败/用户删了包）时静默跳过，主持照常工作。
     */
    private fun bindHostSkills(context: Context) {
        // 🔴 #196：主持的常驻规程也必须来自**集群自己的**技能库。
        // 从全局库取会导致「主持的补救规程」与「角色的执行规程」分属两套，
        // 能力核对时又只认一套 —— 于是主持拿着一套规程去核对另一套标准的角色。
        val ids = runCatching {
            ClusterSkillStore.load(context)
                .filter { it.name in HOST_ONLY_SKILLS && it.enabled }
                .map { it.id }
        }.getOrElse { return }
        if (ids.isEmpty()) return
        val roles = loadRaw(context).toMutableList()
        val idx = roles.indexOfFirst { it.personaId == HOST_PERSONA_ID }
        if (idx < 0) return
        val host = roles[idx]
        val merged = (host.skillIds + ids).distinct()
        if (merged.size == host.skillIds.size) return          // 已绑齐 → 不写盘
        roles[idx] = host.copy(skillIds = merged)
        saveRaw(context, roles)
    }

    fun isHost(personaId: String) = personaId == HOST_PERSONA_ID

    // ——————————————— CRUD ———————————————

    fun roles(context: Context): List<RoleProfile> = loadRaw(context)

    fun experts(context: Context): List<RoleProfile> =
        loadRaw(context).filter { it.enabled && it.role != RoleKind.HOST }

    fun host(context: Context): RoleProfile =
        loadRaw(context).firstOrNull { it.personaId == HOST_PERSONA_ID }
            ?: RoleProfile(personaId = HOST_PERSONA_ID, role = RoleKind.HOST)

    fun get(context: Context, personaId: String): RoleProfile? =
        loadRaw(context).firstOrNull { it.personaId == personaId }

    /** 删除角色。主持不可删 —— 任何调用都会抛异常。 */
    fun delete(context: Context, personaId: String) {
        require(!isHost(personaId)) { "主持不可删除" }
        saveRaw(context, loadRaw(context).filter { it.personaId != personaId })
    }

    fun upsert(context: Context, role: RoleProfile) {
        val list = loadRaw(context).toMutableList()
        val idx = list.indexOfFirst { it.personaId == role.personaId }
        // 主持：身份字段强制回写，只放行能力与模型
        val safe = if (isHost(role.personaId)) role.copy(
            personaId = HOST_PERSONA_ID,
            role = RoleKind.HOST,
            enabled = true
        ) else role
        if (idx >= 0) list[idx] = safe else list += safe
        saveRaw(context, list)
    }

    /** 把已有的人格卡纳为集群角色 */
    fun enroll(
        context: Context, personaId: String,
        modelProfileId: String, role: RoleKind = RoleKind.EXPERT,
        duties: List<String> = emptyList(), taboos: List<String> = emptyList(),
        skills: List<String> = emptyList()
    ) {
        require(!isHost(personaId)) { "不能把主持重新登记为普通角色" }
        upsert(context, RoleProfile(
            personaId = personaId, modelProfileId = modelProfileId,
            role = role, duties = duties, taboos = taboos, skills = skills
        ))
    }

    // ——————————————— 存储 ———————————————

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun loadRaw(context: Context): List<RoleProfile> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { runCatching { RoleProfile.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList()).also { rawList ->
            val list = rawList // 升级/迁移在下方块内完成并提前 return 或落盘
            // 🔴 内置角色灵魂升级（2026-11-06）：播种/建角早期版本只灌了 persona 工作准则，
            // 缺「信念/鄙视/判据」灵魂三段 —— 对内置卡系角色按卡上的默认灵魂补全。
            // 判据：personaId 可解析出卡 id、且卡存在、且当前灵魂没有「## 你的信念」段。
            val upgraded = list.map { r ->
                if (r.soulInjection.contains("## 你的信念")) r
                else runCatching {
                    val cardId = r.personaId
                        .removePrefix("persona_cluster_")
                        .replace(Regex("_(seed|[0-9a-fA-F]{6})$"), "")
                    val card = com.ai.assistance.quro.core.cluster.ClusterRoleCards.byId(cardId)
                        ?: return@runCatching r
                    r.copy(soulInjection = buildString {
                        appendLine(card.persona)
                        if (!card.soul.isEmpty) {
                            appendLine()
                            appendLine(card.soul.render())
                        }
                    }.trim())
                }.getOrDefault(r)
            }
            // 🔴 灵魂解耦迁移（2026-11-06）：老档案的身份/灵魂还在人格库里 ——
            // 只读搬进 RoleProfile（绝不回写人格库），搬完即存。
            if (upgraded.any { it.name.isBlank() || it.soulInjection.isBlank() }) {
                val migrated = upgraded.map { r ->
                    if (r.name.isNotBlank() && r.soulInjection.isNotBlank()) r
                    else runCatching {
                        val p = com.ai.assistance.quro.core.QuroPersonaRepository(context).get(r.personaId)
                        r.copy(
                            name = r.name.ifBlank { p?.name.orEmpty() },
                            emoji = if (r.emoji == "🧩") (p?.avatarEmoji?.takeIf { it.isNotBlank() } ?: "🧩") else r.emoji,
                            soulInjection = r.soulInjection.ifBlank { p?.roleSetting.orEmpty() },
                        )
                    }.getOrDefault(r)
                }
                saveRaw(context, migrated)
                return migrated
            }
            if (upgraded != list) saveRaw(context, upgraded)
            // 自愈：主持被外部改没了就补回来。
            //
            // 🔴 这里**绝不能调 ensureHost**：ensureHost 内部会再调 loadRaw，
            // 而此刻主持仍然不在文件里 → 又触发这里 → 无限递归 → StackOverflowError。
            // （本轮实测：主持未初始化时 upsert 任何角色都会炸，且 runCatching
            //  会把 StackOverflow 吞成 message=null，报成「绑定失败：null」，极难定位。）
            // 所以这里直接就地补一条主持档案，不走任何会回读本函数的路径。
            if (upgraded.none { it.personaId == HOST_PERSONA_ID }) {
                val fixed = upgraded + hostProfile()
                saveRaw(context, fixed)
                return fixed
            }
        }
    }

    /** 内置主持档案（唯一真源，ensureHost 与 loadRaw 自愈共用）。 */
    private fun hostProfile() = RoleProfile(
        personaId = HOST_PERSONA_ID,
        name = HOST_NAME,
        emoji = "🎯",
        soulInjection = HOST_OATH,
        role = RoleKind.HOST,
        duties = listOf("定义验收标准", "拆解子任务", "点名与裁决", "验收与收敛"),
        context = RoleContextPolicy(
            historyRounds = 16,
            seeOtherRoles = true,
            temperature = 0.2f,
            maxTokens = 2048
        )
    )

    private fun saveRaw(context: Context, list: List<RoleProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { file(context).writeText(arr.toString()) }
    }
}
