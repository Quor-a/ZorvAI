package com.ai.assistance.quro.core.cluster

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * ★ 集群技能市场 ★
 *
 * #190 补的能力：主持能自己「查技能 → 建角色 → 配技能」。
 *
 * 🔴 #200 **全链路已切到集群自有技能库** [ClusterSkillStore]：
 * 宿主全局技能库（`QuroSkillStore`，81 个）**与集群完全隔离**，集群不读它、
 * 也不往它写。原因是用户实测出来的三条硬伤：
 *  1. 能力核对会退化成「随便哪个全局技能沾边就算具备」；
 *  2. 一个技能绑 N 个角色 = N 份正文各自注入，3~5 个角色一绑就顶到上下文上限；
 *  3. 用户在技能页动全局技能，会**静默改变集群的判单标准**。
 *
 * 这三个工具把**集群**技能库接进集群：
 *
 * - [ClusterSkillMarketTool]：浏览/搜索集群技能库，按分工标注该技能适合谁
 * - [ClusterSkillGrantTool]：给指定角色绑定/解绑技能
 * - [ClusterSkillImportTool]：把外部技能 md 导入成集群技能（开源技能 → 集群技能市场）
 *
 * 工具名都带 `cluster_` 前缀 —— [QuroToolRouter.categorize] 的
 * `name.startsWith("cluster_")` 规则会自动把它们归到 AI_CAPABILITIES，
 * 不会从路由索引里漏掉。
 *
 * ⚠ 集群技能**没有** function-calling 形态（无 `callable` / `activateTool`），
 * 绑定后是以规程正文注入该角色的 system prompt。所以市场输出里
 * `form = "prompt-only"`，别再对外宣称「可用 skill__xxx 激活」。
 *
 * ⚠ 写工具时踩过的坑（别重犯）：
 *  - `ok()`/`err()` 是 ClusterTools.kt 的 **private** 顶层函数，跨文件用不了 → 本文件自带。
 *  - `RoleRegistry.upsert` 返回 **Unit**（不是 Result），不能 `.onFailure {}`；
 *    要判错得自己 try/catch。
 *  - `return@runCatching` 写在 `runCatching{}.getOrElse{}` 里会**连getOrElse 一起跳过**，
 *    等于异常没被处理就返回了 → 早返回一律用 if/else，不靠 label。
 */

// ————————————————————————————————————————

/** cluster_skill_market：浏览/搜索技能库 */
class ClusterSkillMarketTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_skill_market"
    override val description =
        "浏览集群可用的技能市场（集群自有技能库，与宿主全局技能库隔离；两个来源：本机集群技能库 + 开源社区目录）。" +
        "可按关键词搜索（中文词如「联网」「设计」「写网页」比英文名更可能命中），" +
        "或按分工（planner/executor/expert/critic）过滤本机技能。" +
        "返回每个技能的 id、名称、说明、能力关键词、适合的分工。" +
        "用法：先查市场拿到 skillId，再调 cluster_skill_grant 绑定给角色。" +
        "若本机没有需要的手艺，用 cluster_skill_search 去开源社区找，再用 cluster_skill_install 装。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "query":{"type":"string","description":"搜索关键词，匹配技能名/说明/套件；留空则列出全部"},
        "kind":{"type":"string","description":"按分工过滤：planner/executor/expert/critic；留空则不按分工过滤"},
        "limit":{"type":"integer","description":"最多返回条数，默认 40"}
      }
    }"""
    override val readOnly = true

    override fun run(context: Context, arguments: String): String {
        return try {
            val a = JSONObject(arguments)
            val query = a.optString("query").trim()
            val kind = a.optString("kind").trim().lowercase()
            val limit = a.optInt("limit", 40).coerceIn(1, 200)

            // 🔴 #200：读**集群**技能库，不是全局库。
            // 集群技能一律是提示词规程，没有 function-calling 形态，
            // 所以下面不再输出 callable / activateTool —— 标了会让主持去调一个不存在的工具。
            val all = ClusterSkillStore.load(context)
            if (all.isEmpty()) {
                return skillErr("技能库为空：内置技能播种失败，请到「集群 → 技能」页确认")
            }

            val bound = boundSkillIds(context)
            var list = all
            if (query.isNotBlank()) {
                val q = query.lowercase()
                list = list.filter {
                    it.name.lowercase().contains(q) ||
                        it.description.lowercase().contains(q) ||
                        it.abilityWords.lowercase().contains(q)
                }
                if (list.isEmpty()) {
                    return skillErr(
                        "没有匹配「$query」的技能。集群技能库共 ${all.size} 个，" +
                            "换个关键词（含中文「联网」「设计」「写网页」这类更可能命中），" +
                            "或用 cluster_skill_search 去开源社区找。"
                    )
                }
            }
            val kindFilter = if (kind.isBlank()) emptyList() else listOf(kind)

            val arr = JSONArray()
            list.asSequence()
                .sortedWith(
                    // 已绑定的排前面：主持一眼看到「这些已经有人用了」
                    compareByDescending<ClusterSkillStore.ClusterSkill> { bound.contains(it.id) }
                        .thenBy { it.name }
                )
                .take(limit)
                .forEach { s ->
                    val kinds = ClusterSkillMarket.kindsFor(s)
                    arr.put(JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("description", s.description.take(160))
                        put("abilityWords", s.abilityWords)
                        put("enabled", s.enabled)
                        put("form", "prompt-only")
                        put("fitsKinds", JSONArray(kinds))
                        put("alreadyBound", bound.contains(s.id))
                        put("matchKind", kindFilter.isEmpty() || kinds.any { it in kindFilter })
                        put("promptChars", s.prompt.length)
                    })
                }

            JSONObject().apply {
                put("ok", true)
                put("total", all.size)
                put("matched", arr.length())
                put("byKind", JSONObject().apply {
                    RoleKind.selectable.forEach { k ->
                        put(k.name.lowercase(), ClusterSkillMarket.kindsForAll(all, k).size)
                    }
                })
                put("skills", arr)
                // #191：本机之外还有开源社区。列源目录（不逐个下载正文，否则太慢耗配额）。
                put(
                    "openSources",
                    JSONArray(
                        ClusterOpenSkillHub.SOURCES.map {
                            JSONObject().apply {
                                put("id", it.id)
                                put("label", it.label)
                                put("slug", it.slug)
                                put("note", it.note)
                            }
                        }
                    )
                )
                put(
                    "openInstalledCount",
                    ClusterOpenSkillHub.installedOpenSkills(context).size
                )
                put(
                    "hint",
                    "用 cluster_skill_grant(personaId, skillIds) 绑定；绑定后技能正文会真正注入该角色的系统提示词。" +
                        "本机没有的能力：用 cluster_skill_search 去开源社区搜，cluster_skill_install 下载安装。"
                )
            }.toString()
        } catch (e: Throwable) {
            skillErr("技能市场查询失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** cluster_skill_grant：给角色绑定/解绑技能 */
class ClusterSkillGrantTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_skill_grant"
    override val description =
        "给集群角色绑定或解绑技能（集群自有技能库，与宿主全局技能库隔离）。" +
        "绑定后，该角色发言时技能规程正文会真正注入它的系统提示词，它就不再是「凭空想象」而是有手艺可用。" +
        "先用 cluster_skill_market 查技能 id。" +
        "也可以不查 id：直接给 skillNames（技能名）或 skillMd（外部技能正文），会自动解析/导入后再绑。" +
        "刚用 cluster_skill_install 装好的技能，填它的 name 就能直接绑。"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "personaId":{"type":"string","description":"角色的人格 id（cluster_roles 可查）"},
        "skillIds":{"type":"array","items":{"type":"string"},"description":"要绑定的技能 id 列表"},
        "skillNames":{"type":"array","items":{"type":"string"},"description":"按技能名绑定（免去先查 id）；本机没有的会尝试从开源社区按名查找"},
        "mode":{"type":"string","description":"add=追加（默认）；replace=覆盖为给定列表；remove=移除给定列表"}
      }
    }"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String = runBlocking {
        try {
            val a = JSONObject(arguments)
            val pid = a.optString("personaId").ifBlank { return@runBlocking skillErr("缺少 personaId") }
            // #191：允许按「技能名」绑定，用户不必先查 id
            val nameArgs = a.optJSONArray("skillNames")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            val idArgs = a.optJSONArray("skillIds")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            } ?: emptyList()
            if (idArgs.isEmpty() && nameArgs.isEmpty()) {
                return@runBlocking skillErr("skillIds 与 skillNames 至少给一个")
            }
            val mode = a.optString("mode", "add").ifBlank { "add" }
            if (mode !in setOf("add", "replace", "remove")) {
                return@runBlocking skillErr("mode 只能是 add / replace / remove，收到：$mode")
            }

            if (RoleRegistry.isHost(pid)) {
                return@runBlocking skillErr("主持不可绑定技能：它按固定职责主持，不做具体工作")
            }
            val role = RoleRegistry.get(context, pid)
                ?: return@runBlocking skillErr("该角色不在集群中：$pid")

            val library = ClusterSkillStore.load(context).associateBy { it.id }
            val byName = library.values.associateBy { it.name }

            // 技能名 → id。本机没有的，查开源社区；有唯一匹配就现装再绑。
            val resolvedFromNames = mutableListOf<String>()   // 由 skillNames 解析出的 id
            val autoInstalled = mutableListOf<String>()
            val nameMisses = mutableListOf<String>()
            nameArgs.forEach { n ->
                val local = byName[n]
                if (local != null) { resolvedFromNames += local.id; return@forEach }
                val hit = ClusterOpenSkillHub.searchAll(context, n, limitPerSource = 5)
                    .firstOrNull { it.dir.equals(n, ignoreCase = true) }
                    ?: run {
                        nameMisses += n
                        return@forEach
                    }
                val r = ClusterOpenSkillHub.install(context, hit.sourceId, hit.dir)
                if (r.isSuccess) {
                    resolvedFromNames += r.getOrNull()!!.first.id
                    autoInstalled += r.getOrNull()!!.first.name
                } else {
                    nameMisses += "$n（${r.exceptionOrNull()?.message ?: "安装失败"}）"
                }
            }

            val ids = (idArgs + resolvedFromNames).distinct()
            if (ids.isEmpty()) {
                return@runBlocking skillErr(
                    "这些技能都解析不出来：${(idArgs + nameMisses).joinToString(", ")}。" +
                        "先用 cluster_skill_search 看开源社区里到底有什么。"
                )
            }
            val known = ids.filter { library.containsKey(it) || resolvedFromNames.contains(it) }
            if (known.isEmpty()) {
                return@runBlocking skillErr(
                    "这些技能 id 都不存在：${ids.joinToString(", ")}。先用 cluster_skill_market 查真实 id。"
                )
            }
            val unknown = ids - known.toSet()

            val next = when (mode) {
                "replace" -> known
                "remove" -> role.skillIds - known.toSet()
                else -> (role.skillIds + known).distinct()
            }
            // upsert 返回 Unit，失败只能自己捕获
            try {
                RoleRegistry.upsert(context, role.copy(skillIds = next))
            } catch (e: Throwable) {
                return@runBlocking skillErr("保存失败：${e.message ?: e.javaClass.simpleName}")
            }

            val label = { id: String -> library[id]?.name ?: id }
            val verb = when (mode) {
                "replace" -> "覆盖绑定"
                "remove" -> "解绑"
                else -> "追加绑定"
            }
            JSONObject().apply {
                put("ok", true)
                put("personaId", pid)
                put("affected", JSONArray(known.map { label(it) }))
                if (unknown.isNotEmpty()) put("unknownIgnored", JSONArray(unknown))
                if (autoInstalled.isNotEmpty()) put("autoInstalledFromOpenSource", JSONArray(autoInstalled))
                if (nameMisses.isNotEmpty()) put("nameMisses", JSONArray(nameMisses))
                put("nowBound", JSONArray(next.map { label(it) }))
                put("message", "$verb ${next.size} 个技能：${next.joinToString("、") { label(it) }}")
            }.toString()
        } catch (e: Throwable) {
            skillErr("绑定技能失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

/** cluster_skill_import：把外部技能 md 导入技能库 */
class ClusterSkillImportTool : com.ai.assistance.quro.core.tools.QuroTool {
    override val name = "cluster_skill_import"
    override val description =
        "把一份外部技能（Markdown）导入宿主技能库，之后就能用 cluster_skill_market 查到、" +
        "用 cluster_skill_grant 绑定给任何集群角色。用于把开源技能、团队规范、用户自己的手艺" +
        "沉淀成集群可用的技能。正文需带 front-matter：--- 换行 name: 技能名 换行 description: 说明 换行 --- 换行 技能正文"
    override val parametersJson = """{
      "type":"object",
      "properties":{
        "markdown":{"type":"string","description":"技能 Markdown 正文，须含 front-matter 的 name/description"},
        "overwrite":{"type":"boolean","description":"同名技能是否覆盖，默认 false"}
      },
      "required":["markdown"]
    }"""
    override val readOnly = false

    override fun run(context: Context, arguments: String): String {
        return try {
            val a = JSONObject(arguments)
            val md = a.optString("markdown").ifBlank { return skillErr("缺少 markdown") }
            val overwrite = a.optBoolean("overwrite", false)

            // 🔴 #200：导入落**集群**技能库。落全局库是无效的 ——
            // 运行时注入读集群库，导入完绑定了，注入时照样找不到。
            val fmText = Regex("^---\\s*\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL)
                .find(md.trim())?.groupValues?.get(1).orEmpty()
            val name = Regex("^name:\\s*(.+)$", RegexOption.MULTILINE)
                .find(fmText)?.groupValues?.get(1)?.trim().orEmpty()
            if (name.isBlank()) {
                return skillErr(
                    "无法解析这份技能：正文必须以 front-matter 开头，" +
                        "格式为 ---\\nname: 技能名\\ndescription: 一句话说明\\n---\\n然后是技能正文。"
                )
            }
            val clash = ClusterSkillStore.byName(context, name)
            if (clash != null && !overwrite) {
                return skillErr(
                    "技能「$name」已存在（id=${clash.id}）。要覆盖请加 overwrite=true，" +
                        "或改个名字。已存在就直接用 cluster_skill_grant 绑定它。"
                )
            }
            val id = if (clash != null && overwrite) clash.id else ClusterSkillStore.stableId(name)
            val skill = ClusterSkillStore.parseMd(id = id, name = name, md = md)

            try {
                ClusterSkillStore.upsert(context, skill)
            } catch (e: Throwable) {
                return skillErr("写入集群技能库失败：${e.message ?: e.javaClass.simpleName}")
            }

            JSONObject().apply {
                put("ok", true)
                put("id", skill.id)
                put("name", skill.name)
                put("promptChars", skill.prompt.length)
                put("message", "技能「${skill.name}」已导入**集群**技能库，可用 cluster_skill_grant 绑定给集群角色。")
            }.toString()
        } catch (e: Throwable) {
            skillErr("导入技能失败：${e.message ?: e.javaClass.simpleName}")
        }
    }
}

// ————————————————————————————————————————

/**
 * 技能与分工的匹配表。
 *
 * 为什么要它：技能库有 67 个条目，主持面对一屏名字无从下手。
 * 按「干这活需要什么手艺」反向索引，主持才能问出「UI 设计师该配哪些技能」。
 *
 * 关键词命中技能名/说明/套件即算适配。宁可多推荐（主持可自己剔）也不要漏 ——
 * 漏了用户就永远不知道有这门手艺。
 */
object ClusterSkillMarket {

    private val KIND_KEYWORDS: Map<String, List<String>> = mapOf(
        "executor" to listOf(
            // 开发
            "code", "dev", "frontend", "backend", "program", "脚本", "开发", "编程", "代码",
            "docker", "deploy", "cloudflare", "netlify", "vercel", "edgeone", "github",
            "终端", "linux", "shell", "http", "web", "api",
            // 设计与产出
            "design", "ui", "ux", "前端", "设计", "界面", "poster", "海报", "layout",
            "frontend-spec", "responsive", "page-editor", "app", "html", "css",
            "image", "video", "媒体", "图片", "视频", "minimax",
            // 文档
            "docx", "pptx", "xlsx", "sheet", "excel", "word", "报告", "文档", "专利",
            "patent", "contract", "合同", "公文", "official-document", "mindmap",
            "processon", "脑图", "pdf",
            // 设备操作
            "phone", "android", "device", "mobile", "手机", "设备", "adb", "screen",
        ),
        "planner" to listOf(
            "plan", "planning", "roadmap", "sprint", "stakeholder", "requirement", "prd",
            "规划", "策划", "方案", "路线", "需求", "产品", "管理", "feature-spec",
            "competitive", "调研", "search", "trending", "news", "hot", "patent", "专利",
            "brainstorm", "mindmap", "脑图", "processon", "roadmap",
        ),
        "expert" to listOf(
            "review", "code-review", "audit", "contract", "patent", "法律", "审查", "评审",
            "分析", "analysis", "search", "research", "trending", "news", "hot", "aihot",
            "decompose", "distill", "humanizer", "seo", "翻译", "专利检索",
            "classification", "医学", "mbti", "诊断", "会计", "税务",
        ),
        "critic" to listOf(
            "review", "audit", "contract", "patent", "code-review", "审查", "评审", "验收",
            "check", "verify", "compliance", "合规", "风险", "risk", "spec", "frontend-spec",
            "critique", "无障碍", "accessibility", "响应式", "responsive", "lint",
        ),
    )

    /** 该技能适合哪些分工。🔴 #200：吃集群技能（干草堆含 abilityWords，跨中英都能命中）。 */
    fun kindsFor(skill: ClusterSkillStore.ClusterSkill): List<String> {
        val hay = ClusterSkillStore.abilityHaystack(skill).joinToString(" ")
        return KIND_KEYWORDS.filter { (_, kws) -> kws.any { hay.contains(it) } }.keys.toList()
    }

    /** 指定分工下推荐的技能。 */
    fun recommendFor(
        context: Context,
        kind: String,
        all: List<ClusterSkillStore.ClusterSkill> = ClusterSkillStore.load(context)
    ): Set<String> = all.filter { kind in kindsFor(it) }.map { it.id }.toSet()

    /** 某分工命中的技能数（用于市场概览）。 */
    fun kindsForAll(
        all: List<ClusterSkillStore.ClusterSkill>,
        kind: RoleKind
    ): List<ClusterSkillStore.ClusterSkill> =
        all.filter { kind.name.lowercase() in kindsFor(it) }
}

/** 当前已被任何集群角色绑定的技能 id。 */
private fun boundSkillIds(context: Context): Set<String> =
    runCatching { RoleRegistry.roles(context).flatMap { it.skillIds }.toSet() }
        .getOrElse { emptySet() }

/** 本文件的错误返回（ClusterTools 里的同名函数是 private，跨文件不可用）。 */
private fun skillErr(msg: String): String =
    JSONObject().put("ok", false).put("error", msg).toString()
