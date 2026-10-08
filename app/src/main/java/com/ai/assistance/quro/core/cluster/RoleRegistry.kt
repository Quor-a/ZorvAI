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
        val personas = com.ai.assistance.quro.core.QuroPersonaRepository(context)
        if (personas.get(HOST_PERSONA_ID) == null) {
            personas.upsert(
                com.ai.assistance.quro.core.QuroPersona(
                    id = HOST_PERSONA_ID,
                    name = HOST_NAME,
                    avatarEmoji = "🎯",
                    description = "集群主持，内置不可替换",
                    roleSetting = HOST_OATH,
                    chatSetting = "简洁果断，直接点名并说明理由，不给废话。",
                    tags = listOf("内置", "主持")
                )
            )
        }
        val roles = loadRaw(context).toMutableList()
        if (roles.none { it.personaId == HOST_PERSONA_ID }) {
            roles += hostProfile()
            saveRaw(context, roles)
        }
        bindHostSkills(context)
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
        }.getOrDefault(emptyList()).also { list ->
            // 自愈：主持被外部改没了就补回来。
            //
            // 🔴 这里**绝不能调 ensureHost**：ensureHost 内部会再调 loadRaw，
            // 而此刻主持仍然不在文件里 → 又触发这里 → 无限递归 → StackOverflowError。
            // （本轮实测：主持未初始化时 upsert 任何角色都会炸，且 runCatching
            //  会把 StackOverflow 吞成 message=null，报成「绑定失败：null」，极难定位。）
            // 所以这里直接就地补一条主持档案，不走任何会回读本函数的路径。
            if (list.none { it.personaId == HOST_PERSONA_ID }) {
                val fixed = list + hostProfile()
                saveRaw(context, fixed)
                return fixed
            }
        }
    }

    /** 内置主持档案（唯一真源，ensureHost 与 loadRaw 自愈共用）。 */
    private fun hostProfile() = RoleProfile(
        personaId = HOST_PERSONA_ID,
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
