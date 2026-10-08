package com.ai.assistance.quro.core.cluster

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ai.assistance.quro.core.github.QuroGitHubClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * ★ 开源技能市场（Open Skill Hub）★
 *
 * #191 补的能力：技能**不只有随包那 67 个**，还要能去开源社区现拉现装。
 *
 * ## 为什么必须是这一层
 * #190 做的只是「把本地已有技能摆到集群 UI 上让用户勾选」——
 * 用户明确指出这是错的：**技能库没有的技能，集群得去开源社区拿**。
 * 而「角色卡 = 一个 skills 聚合」，用户不该自己去 67 个条目里一格格挑。
 *
 * ## 数据来源（全部实测存在，2026-10-07 逐个 curl 核实）
 * - `anthropics/skills`：`skills/<name>/SKILL.md`，19 个
 * - `obra/superpowers`：`skills/<name>/SKILL.md`，15 个
 * - `ComposioHQ/awesome-claude-skills`：根目录 + `document-skills/`，34 项
 *
 * 它们的 front-matter（`--- / name: / description: / ---`）与本仓
 * [ClusterSkillStore.parseMd] **完全兼容**，所以拉下来即可直接落集群库，无需转换层。
 *
 * ## 为什么走 GitHub API 而不是 raw.githubusercontent.com
 * 实测 raw 域在本机网络下返回空体，而 `api.github.com/repos/{o}/{r}/contents/{path}`
 * 稳定返回 JSON（含 base64 `content`）→ 统一走 contents API。
 * 另外复用 [QuroGitHubClient] 的**镜像域名**机制（`api.<mirror>`）与登录 token，
 * 国内网络下用户配过镜像就能走镜像；未登录也能匿名拉（contents API 有匿名配额）。
 *
 * ## 诚实性铁律
 * 网络失败/未登录/限流/技能不存在，一律返回**明确中文原因**，
 * 绝不返回空列表让上层误判成「开源社区没有这个技能」。
 */
object ClusterOpenSkillHub {

    private const val TAG = "ClusterOpenSkillHub"

    /** 单个 SKILL.md 正文上限（GitHub contents API 对 >1MB 文件不返回 content）。 */
    private const val MAX_MD_CHARS = 120_000

    /**
     * 开源技能源目录。
     *
     * [path] 是**相对仓库根的目录**，列目录与拼 SKILL.md 路径都用它。
     * 目录是 2026-10-07 实测的：不在仓库里的源会列空 → 用户看到「该源当前列不出目录」，
     * 而不是假装有内容。
     */
    data class Source(
        val id: String,
        val label: String,
        val owner: String,
        val repo: String,
        val ref: String,
        val path: String,
        val note: String,
    ) {
        val slug: String get() = "$owner/$repo"

        /**
         * 列目录的 API 路径。
         *
         * 🔴 [path] 为空（仓库根）时**不能**拼出 `contents//` —— 实测 GitHub 对双斜杠
         * 返回 **302** 跳到不带 `?ref=` 的地址，于是 `ref=master` 被丢掉，
         * 跟着跳转会拿到默认分支的目录（多数情况恰好一样，但这属于碰运气）。
         * 所以这里对空 path 单独处理，路径段之间只用一个斜杠。
         */
        fun contentsApiPath(): String {
            val p = path.trim('/')
            return if (p.isEmpty()) "/repos/$owner/$repo/contents?ref=$ref"
            else "/repos/$owner/$repo/contents/$p?ref=$ref"
        }

        /** 该源下某个技能目录的 SKILL.md API 路径。 */
        fun skillMdApiPath(dir: String): String {
            val p = path.trim('/')
            val base = if (p.isEmpty()) "/repos/$owner/$repo/contents" else "/repos/$owner/$repo/contents/$p"
            return "$base/$dir/SKILL.md?ref=$ref"
        }
    }

    /** 内置开源源（实测存在的四个）。 */
    val SOURCES: List<Source> = listOf(
        Source(
            id = "anthropics-skills",
            label = "Anthropic 官方技能集",
            owner = "anthropics", repo = "skills", ref = "main", path = "skills",
            note = "官方出品：前端设计、文档（docx/pptx/xlsx/pdf）、品牌规范、算法艺术、网页工件构建等 19 个",
        ),
        Source(
            id = "obra-superpowers",
            label = "Superpowers 技能集",
            owner = "obra", repo = "superpowers", ref = "main", path = "skills",
            note = "工程方法论 15 个：头脑风暴、写计划、TDD、系统化调试、代码评审、子智能体编排等",
        ),
        Source(
            id = "composio-awesome",
            label = "Composio 技能集",
            // 🔴 实测该仓默认分支是 master 不是 main（写 main 会 404）
            owner = "ComposioHQ", repo = "awesome-claude-skills", ref = "master", path = "",
            note = "32 项：内容写作、竞品广告拆解、图片增强、发票/档案整理、简历生成等",
        ),
        Source(
            id = "composio-docs",
            label = "Composio 文档技能集",
            owner = "ComposioHQ", repo = "awesome-claude-skills", ref = "master", path = "document-skills",
            note = "文档四件套的独立镜像（docx / pdf / pptx / xlsx），适合文档与办公类角色",
        ),
    )

    fun sourceById(id: String): Source? = SOURCES.firstOrNull { it.id == id }

    /** 远端技能目录里的一项（尚未安装到本地）。 */
    data class RemoteEntry(
        val sourceId: String,
        val dir: String,
        val name: String,
        val description: String,
    ) {
        /** 唯一键：`源/目录`，用于本地「已安装」判定。 */
        val key: String get() = "$sourceId/$dir"
    }

    // ——————————————————————— 列目录 ———————————————————————

    /**
     * 列出某个开源源下的全部技能。
     *
     * 失败时返回 [Result.failure]，错误消息直接给人看（网络不通 / 未配镜像 / 限流）。
     */
    suspend fun listSource(context: Context, sourceId: String): Result<List<RemoteEntry>> =
        withContext(Dispatchers.IO) {
            val src = sourceById(sourceId)
                ?: return@withContext Result.failure(
                    IllegalArgumentException(
                        "没有这个开源源：$sourceId。现有源：" +
                            SOURCES.joinToString(", ") { it.id }
                    )
                )
            val (code, body) = githubGet(context, src.contentsApiPath())
            if (code != 200) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "读取 ${src.slug} 失败（HTTP $code）：${apiErrorOf(body)}"
                    )
                )
            }
            val arr = runCatching { org.json.JSONArray(body) }.getOrNull()
                ?: return@withContext Result.failure(
                    IllegalStateException("GitHub 返回的不是目录列表（可能被限流）：${body.take(160)}")
                )
            val out = mutableListOf<RemoteEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("type") != "dir") continue
                val dir = o.optString("name").trim()
                if (dir.isEmpty() || dir.startsWith(".")) continue
                out.add(RemoteEntry(src.id, dir, dir, ""))
            }
            Result.success(out)
        }

    /**
     * 在所有开源源里按关键词搜技能名。
     *
     * 只匹配**目录名**（不逐个下载正文，否则 60+ 次请求既慢又耗配额）。
     * 真正搜正文语义要靠宿主已有的 `web_search` 工具 —— 那条路已经通了。
     */
    suspend fun searchAll(context: Context, query: String, limitPerSource: Int = 60): List<RemoteEntry> {
        val q = query.trim().lowercase()
        val out = mutableListOf<RemoteEntry>()
        SOURCES.forEach { src ->
            val r = listSource(context, src.id).getOrNull() ?: return@forEach
            val hit = if (q.isEmpty()) r else r.filter {
                it.dir.lowercase().contains(q) || it.name.lowercase().contains(q)
            }
            out += hit.take(limitPerSource)
        }
        return out
    }

    // ——————————————————————— 拉取与安装 ———————————————————————

    /**
     * 拉某个技能的 SKILL.md 原文。
     *
     * 走 `contents` API 的 base64 `content` 字段（**不用 raw 域**，本机实测 raw 返回空体）。
     * 超过 1MB 的文件 GitHub 只会给 `download_url` 不给 `content` → 明确报错而不是空字符串。
     */
    suspend fun fetchSkillMd(context: Context, sourceId: String, dir: String): Result<String> =
        withContext(Dispatchers.IO) {
            val src = sourceById(sourceId)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("没有这个开源源：$sourceId")
                )
            val path = src.skillMdApiPath(dir)
            val (code, body) = githubGet(context, path)
            if (code != 200) {
                return@withContext Result.failure(
                    IllegalStateException("拉取 ${src.slug}/$dir 失败（HTTP $code）：${apiErrorOf(body)}")
                )
            }
            val o = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext Result.failure(IllegalStateException("响应不是 JSON：${body.take(160)}"))
            val b64 = o.optString("content", "").replace("\n", "").trim()
            if (b64.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "该 SKILL.md 没返回正文（文件可能超过 1MB，GitHub contents API 不给 content 字段）"
                    )
                )
            }
            val md = runCatching {
                String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
                ?: return@withContext Result.failure(IllegalStateException("base64 解码失败"))
            if (md.isBlank()) return@withContext Result.failure(IllegalStateException("技能正文为空"))
            if (md.length > MAX_MD_CHARS) {
                return@withContext Result.failure(
                    IllegalStateException("技能正文 ${md.length} 字，超过 ${MAX_MD_CHARS} 上限")
                )
            }
            Result.success(md)
        }

    /**
     * 从开源源安装一个技能进**集群**技能库。
     *
     * 🔴 #200：原来落全局库（`gh_` 前缀），而运行时注入读的是集群库 ——
     * 装完绑上，注入时照样找不到技能。这就是用户实测「手动绑 `web-search-exa`
     * 也没用」的原因之一。现在统一落 [ClusterSkillStore]。
     *
     * 落库要点：
     * - id 用 `cluster_gh_<sha1(sourceId/dir)>` **稳定**，重复安装是覆盖而非堆副本；
     * - 正文用 [ClusterSkillStore.parseMd] 解析（front-matter 与内置同一套格式）。
     */
    suspend fun install(
        context: Context,
        sourceId: String,
        dir: String,
        descriptionHint: String = "",
    ): Result<Pair<ClusterSkillStore.ClusterSkill, Boolean>> = withContext(Dispatchers.IO) {
        val src = sourceById(sourceId)
            ?: return@withContext Result.failure(IllegalArgumentException("没有这个开源源：$sourceId"))
        val md = fetchSkillMd(context, sourceId, dir).getOrElse { return@withContext Result.failure(it) }
        val installed = ClusterSkillStore.installOpen(
            context = context,
            key = "${src.id}/$dir",
            md = md,
            descriptionHint = descriptionHint.ifBlank { src.label },
        )
        installed.onSuccess { (sk, existed) ->
            Log.i(TAG, "已安装开源技能 ${sk.id} / ${sk.name}（覆盖=$existed）")
        }.onFailure { e ->
            Log.w(TAG, "开源技能安装失败：${e.message}")
        }
        installed
    }

    /** 本地已安装的开源**集群**技能（按 `cluster_gh_` 前缀判定）。 */
    fun installedOpenSkills(context: Context): List<ClusterSkillStore.ClusterSkill> =
        ClusterSkillStore.installedOpenSkills(context)

    /** 某个远端条目是否已装进集群库。 */
    fun isInstalled(context: Context, e: RemoteEntry): Boolean =
        ClusterSkillStore.isOpenInstalled(context, e.key)

    // ——————————————————————— 底层 ———————————————————————

    /**
     * 复用 [QuroGitHubClient] 的镜像域名 + token。
     *
     * `QuroGitHubClient.request` 是 private，故这里自己发请求；
     * 但**域名与鉴权口径必须跟它一致**，否则用户在设置里配的 GitHub 镜像会对技能市场失效。
     */
    private fun githubGet(context: Context, path: String): Pair<Int, String> = try {
        val url = QuroGitHubClient.getApiBase(context) + path
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ZorvAI")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        // 与 QuroGitHubClient 一致：禁用 keep-alive（代理/镜像会在复用连接时提前截断响应）
        conn.setRequestProperty("Connection", "close")
        QuroGitHubClient.getToken(context)?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        conn.connectTimeout = 15000
        conn.readTimeout = 25000
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        code to text
    } catch (e: Exception) {
        Log.w(TAG, "githubGet 失败: $path -> ${e.message}")
        -1 to (e.message ?: "网络异常")
    }

    /** 把 GitHub 的 JSON 错误体翻成人话。 */
    private fun apiErrorOf(body: String): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrNull()
        return when {
            !msg.isNullOrBlank() -> msg
            body.isBlank() -> "无响应体（网络不通或域名不可达；可在设置里配 GitHub 镜像域名）"
            else -> body.take(160)
        }
    }

    private fun sha1hex(s: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = d.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}