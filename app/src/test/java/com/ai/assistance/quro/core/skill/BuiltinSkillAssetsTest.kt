package com.ai.assistance.quro.core.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 内置技能资源完整性测试（纯 JVM，不需要 Android Context）。
 *
 * 守三件事，任何一件破了都会在真机上表现为「技能读不到 / 界面渲染不出来」：
 *  1. manifest.json 里的每条签名都能复现 —— 有人改了 SKILL.md 却没重签，技能页会显示「签名校验失败」。
 *  2. 设计/美术套件（design-studio）存在且带 trigger —— 没 trigger 就永远命中不了按需注入。
 *  3. GenUiRules 里不存在「幽灵类型」—— 规则写了但 SDK 没注册的类型会渲染成未知组件卡（大片空白）。
 */
class BuiltinSkillAssetsTest {

    private val salt = "zorv-ai-builtin-skill-sign-v1"

    /** 从 JVM 工作目录向上找仓库根（Gradle test 的工作目录可能是 app/ 或工程根）。 */
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

    private fun hmac(id: String, name: String, content: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$id|$name|$content".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** 极简 JSON 取值：只处理 manifest 这种扁平数组结构，避免引入额外依赖。 */
    private fun entries(manifest: String): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        val arr = manifest.substringAfter("\"skills\"", "").substringAfter("[", "")
        val objRegex = Regex("\\{[^}]*\\}")
        objRegex.findAll(arr).forEach { m ->
            val body = m.value
            fun field(k: String) = Regex("\"$k\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .find(body)?.groupValues?.get(1)?.replace("\\\"", "\"") ?: ""
            out += mapOf(
                "id" to field("id"),
                "name" to field("name"),
                "file" to field("file"),
                "suite" to field("suite"),
                "signature" to field("signature"),
            )
        }
        return out
    }

    @Test
    fun `manifest 里每条签名都能复现`() {
        val dir = skillDir()
        assertTrue("找不到内置技能目录：${dir.absolutePath}", dir.isDirectory)
        val manifest = File(dir, "manifest.json").readText()
        val list = entries(manifest)
        assertTrue("manifest 解析出 0 条技能", list.size >= 60)

        val failures = mutableListOf<String>()
        list.forEach { e ->
            val f = File(dir, e["file"]!!)
            if (!f.exists()) {
                failures += "${e["name"]}: 文件缺失 ${e["file"]}"
                return@forEach
            }
            // 与宿主 SkillSigner 一致：CRLF → LF 归一化后再签
            val content = f.readText().replace("\r\n", "\n")
            val expect = hmac(e["id"]!!, e["name"]!!, content)
            if (expect != e["signature"]) {
                failures += "${e["name"]}: 签名不符（改了 md 没重签？）"
            }
        }
        assertTrue(
            "内置技能签名校验失败：\n" + failures.joinToString("\n"),
            failures.isEmpty()
        )
    }

    @Test
    fun `内置技能 id 带 zorv_ 前缀且新技能符合 sha1 命名`() {
        val all = entries(File(skillDir(), "manifest.json").readText())
        val noPrefix = all.filter { !it["id"]!!.startsWith("zorv_") }.map { it["name"] }
        assertTrue("这些技能 id 缺 zorv_ 前缀：$noPrefix", noPrefix.isEmpty())

        // 只强制校验 design-studio 套件：历史条目的 id 是按上游原始名生成的，沿用即可，
        // 不能反过来改（改了用户已开启的技能会被当成新技能重新播种）。
        val bad = all.filter { it["suite"] == QuroSkillStore.SUITE_DESIGN }
            .filter { it["id"] != "zorv_" + sha1(it["name"]!!).take(12) }
            .map { "${it["name"]} → ${it["id"]}" }
        assertTrue("设计套件 id 不符合 zorv_<sha1(name)[:12]>：$bad", bad.isEmpty())
    }

    @Test
    fun `设计套件存在且每个技能都带触发词`() {
        val dir = skillDir()
        val design = entries(File(dir, "manifest.json").readText())
            .filter { it["suite"] == QuroSkillStore.SUITE_DESIGN }
        assertEquals("设计套件技能数应为 5", 5, design.size)

        val missingTrigger = design.filter {
            val md = File(dir, it["file"]!!).readText()
            !Regex("^trigger:\\s*.+$", RegexOption.MULTILINE).containsMatchIn(md)
        }.map { it["name"] }
        assertTrue("这些设计技能缺 trigger（按需注入永远命中不了）：$missingTrigger", missingTrigger.isEmpty())
    }

    @Test
    fun `规则里没有幽灵组件类型`() {
        val rules = File(
            repoRoot(),
            "app/src/main/java/com/ai/assistance/quro/genui/aiapp/brain/GenUiRules.kt"
        )
        assertTrue("找不到 GenUiRules.kt", rules.exists())
        val text = rules.readText()

        // 已知的非组件标识符（属性名 / 章节词 / 分词残留），不算类型
        val noise = setOf(
            "genui", "json", "action", "properties", "children", "style", "type", "root",
            "calling", "function", "self_correct", "register_component",
            "ules", // `RULES` 常量被正则从尾部截断的残留，不是类型
        )
        val catalog = Regex("([a-z][a-z0-9_]{2,})\\s*\\{").findAll(text).map { it.groupValues[1] }.toSet()
        val registered = registeredTypes()
        val ghosts = (catalog - registered - noise).sorted()
        assertTrue(
            "规则里写了但 SDK 没注册的类型（会渲染成未知组件卡）：$ghosts",
            ghosts.isEmpty()
        )
    }

    /** 从 ComponentTypes.kt + 注册调用里还原 SDK 真实注册的类型集合。 */
    private fun registeredTypes(): Set<String> {
        val base = File(repoRoot(), "genuiagent-sdk/src/main/kotlin/com/ai/assistance/quro/genui/sdk")
        val consts = mutableMapOf<String, String>()
        base.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val s = f.readText()
            Regex("const val ([A-Z0-9_]+)\\s*=\\s*\"([a-z0-9_]+)\"").findAll(s)
                .forEach { consts[it.groupValues[1]] = it.groupValues[2] }
            Regex("\"([a-z0-9_]{2,})\"\\s+to\\s+").findAll(s).forEach { consts[it.groupValues[1]] = it.groupValues[1] }
        }
        val used = mutableSetOf<String>()
        base.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            Regex("\\.register\\(\\s*(?:ComponentTypes\\.)?([A-Z0-9_]+)\\s*\\)").findAll(f.readText())
                .forEach { used += it.groupValues[1] }
        }
        return used.mapNotNull { consts[it] }.toSet().also {
            assertTrue("SDK 注册类型解析异常（过少）：${it.size}", it.size > 400)
        }
    }
}
