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
            roles += RoleProfile(
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
            saveRaw(context, roles)
        }
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
            // 自愈：主持被外部改没了就补回来
            if (list.none { it.personaId == HOST_PERSONA_ID }) ensureHost(context)
        }
    }

    private fun saveRaw(context: Context, list: List<RoleProfile>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { file(context).writeText(arr.toString()) }
    }
}
