package com.ai.assistance.quro.cluster.engine.capability

import com.ai.assistance.quro.cluster.model.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SKILL.md 解析。格式：
 * ```
 * ---
 * name: 财报速读
 * description: 从财报中提取关键指标并生成点评（约100字，常驻系统提示）
 * version: 1.2.0
 * permissions: [fs_scoped, tool_call]
 * triggers: [财报, 季报, 年报]
 * risk: low
 * ---
 * ## 工作流
 * 1. …
 * ```
 * 不引 YAML 库是为了控制包体积。
 */
object SkillParser {

    fun parse(skillId: SkillId, raw: String, path: String): Pair<SkillManifest, String> {
        val (front, body) = splitFrontMatter(raw)
        val map = parseFrontMatter(front)
        val manifest = SkillManifest(
            id = skillId,
            name = map["name"] ?: skillId.value,
            description = map["description"] ?: "",
            version = map["version"] ?: "1.0.0",
            permissions = parseList(map["permissions"]).mapNotNull {
                runCatching { SkillPermission.valueOf(it.uppercase()) }.getOrNull()
            }.toSet(),
            triggers = parseList(map["triggers"]),
            path = path,
            risk = runCatching { RiskLevel.valueOf((map["risk"] ?: "low").uppercase()) }
                .getOrDefault(RiskLevel.LOW)
        )
        return manifest to body
    }

    private fun splitFrontMatter(raw: String): Pair<String, String> {
        val t = raw.trimStart()
        if (!t.startsWith("---")) return "" to raw
        val end = t.indexOf("\n---", 3)
        if (end < 0) return "" to raw
        return t.substring(3, end) to t.substring(end + 4).trimStart()
    }

    private fun parseFrontMatter(front: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        front.lineSequence().forEach { line ->
            val i = line.indexOf(':')
            if (i <= 0) return@forEach
            val v = line.substring(i + 1).trim().trim('"', '\'')
            if (v.startsWith("[") && !v.endsWith("]")) return@forEach
            out[line.substring(0, i).trim()] = v
        }
        return out
    }

    private fun parseList(v: String?): List<String> =
        (v ?: "").trim('[', ']').split(",").map { it.trim() }.filter { it.isNotBlank() }

    /** AI 生成 Skill 时的静态扫描。返回空表示通过。 */
    fun scan(body: String, permissions: Set<SkillPermission>): List<String> {
        val issues = mutableListOf<String>()
        listOf("rm -rf", "su ", "adb ", "Runtime.getRuntime", "反射", "动态加载", "chmod 777", "API_KEY")
            .filter { body.contains(it, true) }
            .forEach { issues += "包含危险指令：$it" }
        if (permissions.contains(SkillPermission.NET_ALLOWLIST)) issues += "声明网络权限，需核对域名白名单"
        if (permissions.contains(SkillPermission.SYSTEM)) issues += "申请 SYSTEM 权限，必须逐次用户确认"
        if (body.length > 20_000) issues += "正文过长（>20k 字符），请精简"
        return issues
    }
}

/**
 * Skill 加载器 —— 三级渐进式披露，省 token 的核心。
 *  L1（~100 token）：name + description，常驻
 *  L2（~1-5k token）：正文，命中 trigger 或被点名才注入
 *  L3（按需）：resources，真要读才载入
 */
class SkillLoader(
    private val rootDir: File,
    private val audit: (String, SkillManifest) -> Unit = { _, _ -> }
) {
    private val mutex = Mutex()
    private val manifests = LinkedHashMap<SkillId, SkillManifest>()
    private val bodies = HashMap<SkillId, String>()
    private val resources = HashMap<String, Map<String, String>>()

    suspend fun install(skillId: SkillId, raw: String): Result<SkillManifest> = mutex.withLock {
        val dir = File(rootDir, skillId.value).apply { mkdirs() }
        val (m, body) = SkillParser.parse(skillId, raw, dir.absolutePath)
        if (m.description.isBlank())
            return Result.failure(IllegalArgumentException("Skill 缺少 description（L1 摘要必填）"))
        val issues = SkillParser.scan(body, m.permissions)
        if (issues.any { it.contains("危险指令") })
            return Result.failure(SecurityException(issues.joinToString("; ")))

        File(dir, "SKILL.md").writeText(raw)
        val stored = m.copy(path = dir.absolutePath, checksum = sha256(raw))
        manifests[skillId] = stored
        bodies[skillId] = body
        audit("install", stored)
        Result.success(stored)
    }

    suspend fun l1(ids: Set<SkillId>): String = mutex.withLock {
        ids.mapNotNull { manifests[it] }.filter { it.enabled }
            .joinToString("\n") { "- ${it.name}（${it.id.value}）：${it.description}" }
    }

    /** 命中 trigger 才返回正文 */
    suspend fun l2(ids: Set<SkillId>, taskText: String): List<SkillSnapshot> = mutex.withLock {
        ids.mapNotNull { sid ->
            val m = manifests[sid] ?: return@mapNotNull null
            if (!m.enabled) return@mapNotNull null
            val hit = m.triggers.isEmpty() || m.triggers.any { taskText.contains(it, true) }
            if (!hit) return@mapNotNull null
            SkillSnapshot(m, SkillLevel.L2, bodies[sid])
        }
    }

    suspend fun l3(skillId: SkillId): SkillSnapshot? = mutex.withLock {
        val m = manifests[skillId] ?: return null
        val cached = resources[m.path] ?: run {
            val dir = File(m.path, "resources")
            val map = if (dir.exists()) dir.listFiles()
                ?.filter { it.isFile && it.length() < 512_000 }
                ?.associate { it.name to it.readText() } ?: emptyMap()
            else emptyMap()
            resources[m.path] = map
            map
        }
        SkillSnapshot(m, SkillLevel.L3, bodies[skillId], cached)
    }

    suspend fun list(): List<SkillManifest> = mutex.withLock { manifests.values.toList() }

    suspend fun loadFromDisk() = mutex.withLock {
        rootDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val f = File(dir, "SKILL.md")
            if (!f.exists()) return@forEach
            val sid = SkillId(dir.name)
            val (m, body) = SkillParser.parse(sid, f.readText(), dir.absolutePath)
            manifests[sid] = m.copy(path = dir.absolutePath)
            bodies[sid] = body
        }
    }

    suspend fun uninstall(id: SkillId) = mutex.withLock {
        manifests[id]?.let { File(it.path).deleteRecursively() }
        manifests.remove(id); bodies.remove(id)
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
