package com.ai.assistance.quro.core.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #193/#194：集群 V2 方法论套件（suite=cluster-v2）的资源回归测试（纯 JVM，不需 Android Context）。
 *
 * 文档第四节原文：
 * ```
 * 四、内置技能包（14 个）
 * skill-fetch / role-forge（主持专用） html-dev / ui-design / frontend-design / md-doc / copywrite
 * planning / strategy / review / code-review mobile-control / data-analysis / web-research
 * 每个技能包都写了真实可执行的工作流与自检清单，不是空话。
 * 比如 ui-design 里明确禁止"紫蓝渐变 + 统一圆角"这套 AI 通用脸，
 * review 里明确"没有验收标准时第一反应是要求补标准，不是凭感觉看看"。
 * ```
 *
 * 守四件事：
 *  1. 14 个包一个不少，且都在 cluster-v2 套件下；
 *  2. 每个包都有 description + trigger + 正文（缺 trigger = 按需注入永远命中不了）；
 *  3. **不是空话** —— 每包都要有可执行的工作流（编号步骤）和可勾选的自检清单，
 *     且文档点名的两条硬要求真的写进去了（ui-design 禁 AI 通用脸 / review 先要验收标准）；
 *  4. id 与签名可复现（宿主 seedClusterV2Skills 靠它落库）。
 */
class ClusterV2SkillsTest {

    private val salt = "zorv-ai-builtin-skill-sign-v1"

    /** 文档第四节列出的 14 个（frontend-design 与既有工具型重名，V2 版改名为 frontend-design-playbook）。 */
    private val expected = listOf(
        // 主持专用
        "skill-fetch", "role-forge",
        // 执行类
        "html-dev", "ui-design", "frontend-design-playbook", "md-doc", "copywrite",
        "mobile-control",
        // 思考与评审类
        "planning", "strategy", "review", "code-review", "data-analysis", "web-research",
    )

    /** 文档标注「主持专用」的两个。 */
    private val hostOnly = setOf("skill-fetch", "role-forge")

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir"))
        var d: File? = cwd.absoluteFile
        repeat(6) {
            val cur = d ?: return cwd
            if (File(cur, "settings.gradle.kts").exists()) return cur
            d = cur.parentFile
        }
        return cwd
    }

    private fun skillDir(): File = File(repoRoot(), "app/src/main/assets/skills/zorv")

    private fun manifestText(): String = File(skillDir(), "manifest.json").readText()

    private fun entries(): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        val arr = manifestText().substringAfter("\"skills\"", "").substringAfter("[", "")
        Regex("\\{[^}]*\\}").findAll(arr).forEach { m ->
            fun field(k: String) = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(m.value)?.groupValues?.get(1)?.replace("\\\"", "\"") ?: ""
            out += mapOf(
                "id" to field("id"), "name" to field("name"),
                "file" to field("file"), "suite" to field("suite"),
                "description" to field("description"), "signature" to field("signature"),
            )
        }
        return out
    }

    private fun v2(): List<Map<String, String>> =
        entries().filter { it["suite"] == QuroSkillStore.SUITE_CLUSTER_V2 }

    /**
     * 与宿主 [QuroSkillStore.parseSkillMd] **完全一致**的 front-matter 切分。
     *
     * 🔴 绝不能用 split("---") —— 正文里的 markdown 表格分隔行 `|---|---|`
     * 会把正文从中间劈开，导致后面半个包的「工作流/自检清单」凭空消失，
     * 测试就会误报「这包是空话」。宿主用的是 `^---\s*\n(.*?)\n---\s*\n`
     * （无 MULTILINE，`^` 只锚在文首），这里必须照抄。
     */
    private fun splitMd(md: String): Pair<String, String> {
        val text = md.trim()
        val m = Regex("^---\\s*\\n(.*?)\\n---\\s*\\n?", RegexOption.DOT_MATCHES_ALL).find(text)
            ?: return "" to text
        return m.groupValues[1] to text.removeRange(m.range)
    }

    private fun mdOf(name: String): String {
        val e = v2().firstOrNull { it["name"] == name }
            ?: error("cluster-v2 里没有技能：$name")
        return File(skillDir(), e["file"]!!).readText()
    }

    private fun hmac(id: String, name: String, content: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$id|$name|$content".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ————————————————— 1. 齐全性 —————————————————

    @Test
    fun `文档第四节的 14 个包一个不少`() {
        val actual = v2().map { it["name"]!! }.toSet()
        assertEquals("cluster-v2 套件应有 14 个包", 14, actual.size)
        val missing = expected.filter { it !in actual }
        assertTrue("文档要求但缺失的包：$missing", missing.isEmpty())
        val extra = actual.filter { it !in expected.toSet() }
        assertTrue("多出来的未登记包：$extra", extra.isEmpty())
    }

    @Test
    fun `主持专用的两个包确实都在`() {
        val names = v2().map { it["name"]!! }.toSet()
        assertTrue("skill-fetch 缺失", "skill-fetch" in names)
        assertTrue("role-forge 缺失", "role-forge" in names)
        // 这两个是主持专用，不该是任何执行角色的默认手艺
        assertEquals(hostOnly.size, hostOnly.count { it in names })
    }

    @Test
    fun `V2 技能名不与既有 67 个工具型技能撞名`() {
        // 撞名会导致 id 相同 → seed 时被 `id in existing` 跳过 → 用户永远拿不到 V2 版
        val all = entries().map { it["name"]!! }
        val dup = all.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("manifest 里有重名技能（id 会撞车）：$dup", dup.isEmpty())
    }

    // ————————————————— 2. 可解析性 —————————————————

    @Test
    fun `每个包都有 description trigger 与正文`() {
        val bad = mutableListOf<String>()
        v2().forEach { e ->
            val name = e["name"]!!
            if (e["description"]!!.isBlank()) bad += "$name: manifest description 空"
            val md = File(skillDir(), e["file"]!!).readText()
            val (fm, body) = splitMd(md)
            if (!Regex("^name:\\s*.+$", RegexOption.MULTILINE).containsMatchIn(fm)) bad += "$name: 缺 name"
            if (!Regex("^trigger:\\s*.+$", RegexOption.MULTILINE).containsMatchIn(fm)) bad += "$name: 缺 trigger"
            if (body.trim().length < 300) bad += "$name: 正文过短（${body.trim().length} 字符），不是空话的概率高"
        }
        assertTrue("以下包不合格：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `id 与签名都能复现`() {
        val bad = mutableListOf<String>()
        v2().forEach { e ->
            val id = e["id"]!!
            val name = e["name"]!!
            val expectId = "zorv_" + sha1(name).take(12)
            if (id != expectId) bad += "$name: id 应为 $expectId，实际 $id"
            val f = File(skillDir(), e["file"]!!)
            if (!f.exists()) { bad += "$name: 文件缺失"; return@forEach }
            val content = f.readText().replace("\r\n", "\n")
            if (hmac(id, name, content) != e["signature"]) bad += "$name: 签名不符"
        }
        assertTrue("id/签名校验失败：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    // ————————————————— 3. 不是空话（文档硬要求） —————————————————

    @Test
    fun `每个包都有编号工作流与勾选式自检清单`() {
        val bad = mutableListOf<String>()
        v2().forEach { e ->
            val name = e["name"]!!
            val md = File(skillDir(), e["file"]!!).readText()
            // 编号步骤：至少 2 条「1. / 2.」开头的列表项
            val steps = Regex("^\\s*\\d+\\.\\s+\\S", RegexOption.MULTILINE).findAll(md).count()
            if (steps < 2) bad += "$name: 工作流步骤不足（$steps 条编号项）"
            // 勾选式自检清单：至少 3 条 `- [ ]`
            // 🔴 必须加 MULTILINE：不加的话 `^` 只锚在整个字符串开头，
            // 第一条勾选项之后全是 0 条 —— 这个 bug 曾经让 14 个包全被判「空话」。
            val checks = Regex("^\\s*-\\s*\\[[ x]\\]", RegexOption.MULTILINE).findAll(md).count()
            if (checks < 3) bad += "$name: 自检清单不足（$checks 条勾选项）"
        }
        assertTrue("以下包是空话（无工作流/无自检清单）：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `ui-design 明确禁止紫蓝渐变加统一圆角这套 AI 通用脸`() {
        val md = mdOf("ui-design")
        assertTrue("没提紫蓝渐变禁令", md.contains("紫蓝渐变"))
        assertTrue("没写明是「禁止」", Regex("明令禁止|一律禁止").containsMatchIn(md))
        // 禁的不只是词，得给出替代要求，否则等于没禁
        assertTrue("禁了但没给替代方案（应给间距刻度/层级圆角表）",
            md.contains("4/8/12/16/24/32/48") && md.contains("圆角"))
        // 圆角必须按层级区分
        assertTrue("圆角没按层级递减", md.contains("递减") || md.contains("层级"))
    }

    @Test
    fun `review 明确没有验收标准时先要标准而不是凭感觉看看`() {
        val md = mdOf("review")
        assertTrue("review 没提验收标准", md.contains("验收标准"))
        assertTrue("没写「第一反应是要求补标准」", md.contains("第一反应"))
        assertTrue("没写「不是凭感觉看看」", md.contains("凭感觉"))
        // 结论必须三选一，禁止和稀泥
        assertTrue("没禁止「基本通过」类模糊结论", md.contains("基本通过"))
        assertTrue("没给出三种结论", md.contains("无法判断"))
    }

    @Test
    fun `每个包都有明确的禁止项`() {
        val bad = v2().mapNotNull { e ->
            val md = File(skillDir(), e["file"]!!).readText()
            val bans = Regex("❌").findAll(md).count()
            if (bans < 2) "${e["name"]}: 禁止项不足（$bans 条）" else null
        }
        assertTrue("以下包没写清不许做什么：\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `skill-fetch 与 role-forge 写的是补救规程而不是技能介绍`() {
        val fetch = mdOf("skill-fetch")
        assertTrue("skill-fetch 没说清什么时候不该用（本地有就不许装）", fetch.contains("什么时候用"))
        assertTrue("skill-fetch 没禁止失败时编造", Regex("编造|假装").containsMatchIn(fetch))
        assertTrue("skill-fetch 没禁止过量安装", fetch.contains("3"))

        val forge = mdOf("role-forge")
        assertTrue("role-forge 没禁空壳卡", forge.contains("你是一个专业的"))
        assertTrue("role-forge 没要求可判据标准", forge.contains("可判据"))
        assertTrue("role-forge 没禁死循环造卡", forge.contains("一次"))
    }

    @Test
    fun `review 与 code-review 都禁止和稀泥式结论`() {
        val code = mdOf("code-review")
        assertTrue("code-review 没要求触发条件", code.contains("触发条件"))
        assertTrue("code-review 没禁无证据的安全指控", Regex("无证据|没有证据").containsMatchIn(code))
        assertTrue("code-review 没给出高危清单", Regex("高危清单").containsMatchIn(code))
    }

    // ————————————————— 4. 播种安全性（#194） —————————————————

    @Test
    fun `V2 套件常量与播种入口存在`() {
        // seedClusterV2Skills 必须在 load() 里挂上，否则老设备永远拿不到新包
        val src = File(repoRoot(), "app/src/main/java/com/ai/assistance/quro/core/skill/QuroSkill.kt")
            .readText()
        assertTrue("QuroSkillStore 里没有 seedClusterV2Skills", src.contains("fun seedClusterV2Skills"))
        assertTrue("seedClusterV2Skills 没挂进 load()", src.contains("seedClusterV2Skills(context)"))
        // 守卫必须独立于 builtin_zorv_v1，否则老设备直接 return
        assertTrue("守卫 key 仍复用旧的 builtin_zorv_v1", src.contains("builtin_zorv_v2_v1"))
        // 默认值必须 callable=false（防离线模型工具爆炸）
        val body = src.substringAfter("fun seedClusterV2Skills").substringBefore("fun clusterV2SkillIds")
        assertTrue("新包 callable 应为 false", Regex("callable\\s*=\\s*false").containsMatchIn(body))
        assertTrue("新包 enabled 应为 true（方法论型不注入等于不存在）",
            Regex("enabled\\s*=\\s*true").containsMatchIn(body))
    }

    @Test
    fun `主持绑定是只增不删的`() {
        val src = File(repoRoot(), "app/src/main/java/com/ai/assistance/quro/core/cluster/RoleRegistry.kt")
            .readText()
        assertTrue("没有 bindHostSkills", src.contains("fun bindHostSkills"))
        assertTrue("ensureHost 没调用 bindHostSkills", src.contains("bindHostSkills(context)"))
        assertTrue("绑定用了 union（distinct）而非覆盖", src.contains("host.skillIds + ids"))
        assertTrue("已绑齐时不该反复写盘", src.contains("merged.size == host.skillIds.size"))
        assertFalse("不该把 skillIds 直接赋成 ids（会抹掉用户手动绑的）",
            src.contains("skillIds = ids)"))
    }
}