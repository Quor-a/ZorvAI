package com.ai.assistance.quro.core.cluster

import android.content.Context
import com.ai.assistance.quro.core.QuroPersona
import com.ai.assistance.quro.core.QuroPersonaRepository
import com.ai.assistance.quro.core.tools.QuroTool
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ★ 开源技能市场 + 角色卡工具组 ★
 *
 * #191 补的三件事（#190 只做了「把本地技能摆出来让用户勾选」，那是错的）：
 *
 * 1. [ClusterOpenSkillSearchTool] `cluster_skill_search` —— 去**开源社区**搜技能。
 *    技能库没有的手艺，就从 anthropics/skills、obra/superpowers、Composio 等真实仓库拿。
 * 2. [ClusterOpenSkillInstallTool] `cluster_skill_install` —— 拉下来装进本地技能库。
 * 3. [ClusterRoleCardTool] `cluster_rolecard` —— 用**角色卡**一键建角色。
 *    角色卡 = 分工 + 灵魂注入正文 + **一整组 skills**（这是用户要的核心：
 *    「每个角色卡就是一个 skills 聚合」）。
 *
 * 另外把 [ClusterSkillMarketTool] 的市场视图扩展成**两个来源**：本地库 + 开源源，
 * 这样主持一眼能看见「本地有什么」和「外面还有什么」。
 *
 * 工具名都带 `cluster_` 前缀 → [com.ai.assistance.quro.core.tools.QuroToolRouter]
 * 的 `name.startsWith("cluster_")` 规则会自动归到 AI_CAPABILITIES 分类。
 *
 * ⚠ 本文件坑位提醒（同 ClusterSkillMarketTools.kt）：
 *  - 这里是**新文件**，`ClusterTools.kt` 的 `ok()`/`err()` 是 private，跨文件用不了 → 自带。
 *  - `RoleRegistry.upsert` / `QuroPersonaRepository.upsert` / `QuroSkillStore.addOrUpdate`
 *    返回 **Unit**（不是 Result），判错必须自己 try/catch。
 *  - 开源拉取是**挂起**的；[QuroTool.run] 不是挂起函数 → 用 `runBlocking` 包起来。
 *    （这是仓库里既有工具的通行做法，见 ClusterHostConfigTool。）
 */

// ————————————————————————————————————————

/** cluster_skill_search：在开源社区搜技能（不落盘，纯查） */
class ClusterOpenSkillSearchTool : QuroTool {
    override val name = "cluster_skill_search"
    override val description =
        "在开源技能社区搜索技能。当技能库里没有你需要的手艺时用它去外面找。" +
        "已接入的源：Anthropic 官方技能集、Superpowers 工程方法论集、Composio 技能集（含文档四件套）。" +
        "返回每个技能的 源id/目录名、源名称、是否已安装到本地。" +
        "拿到 key 后用 cluster_skill_install 安装，再用 cluster_skill_grant 绑定给角色。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "query":{"type":"string","description":"搜索关键词，匹配技能目录名；留空则列出各源全部"},
        "sourceId":{"type":"string","description":"只搜某个源：anthropics-skills / obra-superpowers / composio-awesome / composio-docs；留空搜全部"},
        "limit":{"type":"integer","description":"每个源最多返回条数，默认 60"}
      }
    }"""
    override val readOnly = true

    override fun run(context: Context, arguments: String): String = runBlocking {
        try {
            val a = JSONObject(arguments)
            val query = a.optString("query").trim()
            val sourceId = a.optString("sourceId").trim()
            val limit = a.optInt("limit", 60).coerceIn(1, 200)

            if (sourceId.isNotBlank() && ClusterOpenSkillHub.sourceById(sourceId) == null) {
                return@runBlocking cardErr(
                    "没有这个开源源：$sourceId。现有源：" +
                        ClusterOpenSkillHub.SOURCES.joinToString(", ") { it.id }
                )
            }

            val targets = if (sourceId.isBlank()) ClusterOpenSkillHub.SOURCES
            else listOfNotNull(ClusterOpenSkillHub.sourceById(sourceId))

            val results = mutableListOf<ClusterOpenSkillHub.RemoteEntry>()
            val failures = mutableListOf<String>()
            targets.forEach { src ->
                // 一个源挂了不能拖垮整次搜索 —— 但必须把失败原因如实带回。
                val r = ClusterOpenSkillHub.listSource(context, src.id)
                if (r.isFailure) {
                    failures += "${src.label}：${r.exceptionOrNull()?.message ?: "未知错误"}"
                    return@forEach
                }
                val list = r.getOrDefault(emptyList())
                val q = query.lowercase()
                val hit = if (q.isEmpty()) list else list.filter { it.dir.lowercase().contains(q) }
                results += hit.take(limit)
            }

            val arr = JSONArray()
            results.forEach { e ->
                val src = ClusterOpenSkillHub.sourceById(e.sourceId)
                arr.put(JSONObject().apply {
                    put("key", e.key)
                    put("sourceId", e.sourceId)
                    put("sourceLabel", src?.label ?: e.sourceId)
                    put("dir", e.dir)
                    put("name", e.dir)
                    put("installed", ClusterOpenSkillHub.isInstalled(context, e))
                })
            }

            JSONObject().apply {
                put("ok", true)
                put("total", results.size)
                put("skills", arr)
                put(
                    "sources",
                    JSONArray(
                        ClusterOpenSkillHub.SOURCES.map {
                            JSONObject().apply {
                                put("id", it.id); put("label", it.label)
                                put("slug", it.slug); put("note", it.note)
                            }
                        }
                    )
                )
                // 网络失败必须显式返回，不能让上层把「空列表」误读成「开源社区没有这个技能」
                put("sourceFailures", JSONArray(failures))
                put(
                    "hint",
                    "用 cluster_skill_install(key) 安装；装完用 cluster_skill_grant(personaId, skillIds) 绑给角色。" +
                        "想省事就 cluster_rolecard(mode=list) 看内置角色卡，一键建角并自动绑好这组技能。"
                )
                if (results.isEmpty() && failures.isNotEmpty()) {
                    put("warning", "所有源都没拉到内容，原因见 sourceFailures；这不代表社区没有该技能。")
                }
            }.toString()
        } catch (e: Throwable) {
            cardErr("开源技能搜索失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** cluster_skill_install：从开源社区安装一个技能进本地技能库 */
class ClusterOpenSkillInstallTool : QuroTool {
    override val name = "cluster_skill_install"
    override val description =
        "把开源社区里的一个技能下载并安装进宿主技能库，之后就能被 cluster_skill_market 查到、" +
        "用 cluster_skill_grant 绑定给任何集群角色，也可直接在本机技能页启用。" +
        "key 格式为「源id/目录名」，从 cluster_skill_search 的结果里拿。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "key":{"type":"string","description":"源id/目录名，例如 anthropics-skills/frontend-design"},
        "autoGrant":{"type":"string","description":"可选：装完顺便绑给这个 personaId（cluster_roles 可查）"}
      },
      "required":["key"]
    }"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String = runBlocking {
        try {
            val a = JSONObject(arguments)
            val key = a.optString("key").trim()
            if (key.isEmpty()) return@runBlocking cardErr("缺少 key")
            val slash = key.indexOf('/')
            if (slash <= 0 || slash == key.length - 1) {
                return@runBlocking cardErr("key 格式应为「源id/目录名」，收到：$key")
            }
            val sourceId = key.substring(0, slash)
            val dir = key.substring(slash + 1)

            val r = ClusterOpenSkillHub.install(context, sourceId, dir)
            if (r.isFailure) {
                return@runBlocking cardErr(
                    "安装失败：${r.exceptionOrNull()?.message ?: "未知错误"}"
                )
            }
            val (skill, overwritten) = r.getOrNull()!!  // 已用 isFailure 过滤，getOrNull 必非空

            // 可选：装完直接绑给某个角色（用户说「下载后拉取给他创建的角色」）
            var granted = ""
            val autoGrant = a.optString("autoGrant").trim()
            if (autoGrant.isNotEmpty()) {
                if (RoleRegistry.isHost(autoGrant)) {
                    granted = "（主持不可绑定技能，已跳过自动绑定）"
                } else {
                    val role = RoleRegistry.get(context, autoGrant)
                    if (role == null) {
                        granted = "（角色 $autoGrant 不存在，未自动绑定）"
                    } else {
                        val next = (role.skillIds + skill.id).distinct()
                        granted = try {
                            RoleRegistry.upsert(context, role.copy(skillIds = next))
                            "（已自动绑定给 $autoGrant）"
                        } catch (e: Throwable) {
                            "（技能已装上，但绑定失败：${e.message ?: e.javaClass.simpleName}）"
                        }
                    }
                }
            }

            JSONObject().apply {
                put("ok", true)
                put("id", skill.id)
                put("name", skill.name)
                put("description", skill.description.take(160))
                put("promptChars", skill.prompt.length)
                put("source", "open/${ClusterOpenSkillHub.sourceById(sourceId)?.id ?: sourceId}")
                put("overwroteExisting", overwritten)
                // 🔴 #200：集群技能是提示词规程，没有可激活的工具名。
                // 原来这里 put("activateTool", skill.toolName()) 会给模型一个
                // 指向不存在工具的名字，让它去调一个空工具。
                put("form", "prompt-only")
                put(
                    "message",
                    "已${if (overwritten) "覆盖安装" else "安装"}技能「${skill.name}」" +
                        "（${skill.prompt.length} 字正文）$granted。可用 cluster_skill_grant 绑定给其他角色。"
                )
            }.toString()
        } catch (e: Throwable) {
            cardErr("安装开源技能失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** cluster_rolecard：角色卡目录 + 用角色卡一键建角并绑定技能 */
class ClusterRoleCardTool : QuroTool {
    override val name = "cluster_rolecard"
    override val description =
        "用「角色卡」创建集群角色。角色卡 = 一个 skills 聚合：一张卡同时给出分工（执行/规划/评审/专家）、" +
        "灵魂注入正文（写进它的角色设定）、以及**一整组技能**，建完角技能已自动绑好，" +
        "它不用再凭空想象。mode=list 看有哪些卡；mode=create 建角色。" +
        "自带卡覆盖：UI 设计师、前端开发、网站搭建部署、手机操作员、规划师、策划师、写作、Markdown 文档、办公文档、评审。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "mode":{"type":"string","description":"list=列出内置角色卡（默认）；create=用卡建角色"},
        "cardId":{"type":"string","description":"create 时必填：角色卡 id，如 ui-designer"},
        "personaName":{"type":"string","description":"create 时可选：给新角色起名，默认用卡片名"},
        "modelId":{"type":"string","description":"create 时可选：指定模型 profile id"},
        "cardIdFilter":{"type":"string","description":"list 时可选：按关键词过滤卡片"}
      }
    }"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String = runBlocking {
        try {
            val a = JSONObject(arguments)
            val mode = a.optString("mode", "list").ifBlank { "list" }
            if (mode == "list") return@runBlocking listCards(context, a)
            if (mode != "create") return@runBlocking cardErr("mode 只能是 list / create，收到：$mode")
            createRole(context, a)
        } catch (e: Throwable) {
            cardErr("角色卡操作失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun listCards(context: Context, a: JSONObject): String {
        val q = a.optString("cardIdFilter").trim()
        val cards = if (q.isEmpty()) ClusterRoleCards.BUILT_IN else ClusterRoleCards.search(q)
        val arr = JSONArray()
        cards.forEach { c ->
            val (localHits, localMissing) = ClusterRoleCards.localOnlyPreview(context, c)
            arr.put(JSONObject().apply {
                put("cardId", c.id)
                put("label", c.label)
                put("emoji", c.emoji)
                put("kind", c.kind.name.lowercase())
                put("tagline", c.tagline)
                put("duties", JSONArray(c.duties))
                put("taboos", JSONArray(c.taboos))
                        // #192：把结构化灵魂的判据暴露出来 —— 用户建角前要能看见
                        // "怎样才算做对"，否则卡片列表只是一堆名字。
                        put("soulBeliefs", JSONArray(c.soul.beliefs))
                        put("soulDespises", JSONArray(c.soul.despises))
                        put("soulStandards", JSONArray(c.soul.standards))
                put(
                    "skillsReady",
                    JSONArray(localHits.map { it.name })
                )
                // 本地没装的技能必须列名：建角后少哪门手艺，用户要看得见
                put("skillsNotInstalled", JSONArray(localMissing))
                put(
                    "openSkills",
                    JSONArray(c.skills.filter { it.isOpenSource }.map { it.openSource })
                )
                put("totalSkills", c.skills.size)
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("total", cards.size)
            put("cards", arr)
            put(
                "note",
                "skillsNotInstalled 是本地还没有的技能名；建角时会自动跳过并在结果里如实回报。" +
                    "openSkills 是需要联网现拉的开源技能。"
            )
            put("hint", "用 cluster_rolecard(mode=create, cardId=xxx) 建角并自动绑技能。")
        }.toString()
    }

    private suspend fun createRole(context: Context, a: JSONObject): String {
        val cardId = a.optString("cardId").trim()
        if (cardId.isEmpty()) return cardErr("create 模式必须给 cardId（先用 mode=list 看有哪些卡）")
        val card = ClusterRoleCards.byId(cardId)
            ?: return cardErr(
                "没有这张角色卡：$cardId。现有卡：" +
                    ClusterRoleCards.BUILT_IN.joinToString(", ") { it.id }
            )

        val displayName = a.optString("personaName").trim().ifEmpty { card.label }
        val personaId = "persona_cluster_${card.id}_${UUID.randomUUID().toString().take(6)}"

        // 解析技能引用：本地直接查；开源的现拉现装（失败不阻断建角，但必须如实回报缺了什么）
        val resolved = card.skills.resolveForRole(context)
        val installedCount = resolved.installedNow.size
        val missingLocal = resolved.missing.map { it.localName }

        val persona = QuroPersona(
            id = personaId,
            name = displayName,
            avatarEmoji = card.emoji,
            description = card.tagline,
            // #192：soul（信念 / 鄙视什么 / 可判据标准）渲染进 roleSetting。
            // 为什么必须合成一段写进去而不只是展示在 UI：
            // persona 只说「你是谁」，soul 说的才是「怎样才算做对」——
            // 后者是唯一能被评审逐条对照的东西，也是本轮消灭空壳角色的关键。
            roleSetting = buildString {
                appendLine(card.persona)
                if (!card.soul.isEmpty) {
                    appendLine()
                    appendLine(card.soul.render())
                }
            }.trim(),
            chatSetting = "以职责为准，动手执行，不空谈。",
            tags = listOf("集群角色", card.kind.label, "角色卡")
        )
        try {
            QuroPersonaRepository(context).upsert(persona)
        } catch (e: Throwable) {
            return cardErr("创建人格卡失败：${e.message ?: e.javaClass.simpleName}")
        }

        val role = RoleProfile(
            personaId = personaId,
            modelProfileId = a.optString("modelId").trim(),
            role = card.kind,
            duties = card.duties,
            taboos = card.taboos,
            skillIds = resolved.skillIds
        )
        try {
            RoleRegistry.upsert(context, role)
        } catch (e: Throwable) {
            return cardErr("登记集群角色失败：${e.message ?: e.javaClass.simpleName}")
        }

        val gaps = mutableListOf<String>()
        if (missingLocal.isNotEmpty()) gaps += "本地缺失技能：${missingLocal.joinToString("、")}"
        if (resolved.failed.isNotEmpty()) {
            gaps += resolved.failed.joinToString("；") { "${it.first.label}（${it.second}）" }
        }

        return JSONObject().apply {
            put("ok", true)
            put("personaId", personaId)
            put("personaName", displayName)
            put("cardId", card.id)
            put("kind", card.kind.name)
            put("kindLabel", card.kind.label)
            put("duties", JSONArray(card.duties))
            put("taboos", JSONArray(card.taboos))
            put("skillsBound", JSONArray(resolved.skillNames))
            put("skillCount", resolved.skillIds.size)
            if (installedCount > 0) put("installedFromOpenSource", JSONArray(resolved.installedNow))
            if (gaps.isNotEmpty()) {
                put("gaps", JSONArray(gaps))
                // 🔴 诚实性：建出来了但有技能没绑上，必须明说，不能让用户以为它是满配的
                put("warning", "角色已创建，但有技能没绑上（见 gaps）。原因多半是本地没装或网络拉取失败。")
            }
            put(
                "message",
                "已用角色卡「${card.label}」创建角色 $displayName（${card.kind.label}）：" +
                    "绑上 ${resolved.skillIds.size} 门技能" +
                    (if (installedCount > 0) "，其中 $installedCount 门刚从开源社区下载" else "") +
                    (if (gaps.isNotEmpty()) "；仍有缺口，见 gaps" else "") + "。"
            )
        }.toString()
    }
}

// ————————————————————————————————————————

/** 本文件的 JSON 错误返回（ClusterTools.kt 里的同名函数是 private，跨文件不可用）。 */
private fun cardErr(msg: String): String =
    JSONObject().put("ok", false).put("error", msg).toString()