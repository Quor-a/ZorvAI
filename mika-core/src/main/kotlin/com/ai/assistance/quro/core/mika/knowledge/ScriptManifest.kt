package com.ai.assistance.quro.core.mika.knowledge

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import com.ai.assistance.quro.core.mika.workspace.SandboxPaths

/** 技能脚本清单(`scripts/manifest.json`)里的一项;[file] 是技能目录下的相对路径。 */
@Serializable
data class ScriptEntry(val file: String, val runnable: Boolean, val needs: List<String> = emptyList(), val network: Boolean = false, val reason: String? = null)

/**
 * 技能目录下 `scripts/manifest.json`:`[{file, runnable, needs, network, reason?}]`,[ScriptEntry.file] 是技能目录下的相对路径。
 * run_bundled_script 与 knowledge_read、skill_read 都以它为准。
 */
object ScriptManifest {
    const val NEEDS_NETWORK = "needs network access: the user can turn on \"Allow scripts to go online\" (允许脚本联网) in ZorvAI's settings"
    private val json = Json { ignoreUnknownKeys = true }
    private val INTERPRETERS = mapOf("py" to "/usr/bin/python3", "mjs" to "/usr/bin/node", "js" to "/usr/bin/node", "sh" to "/bin/sh")

    /** 读不懂时为空;路径不是技能目录里的相对路径的项丢掉。 */
    fun parse(text: String): List<ScriptEntry> = try {
        json.decodeFromString<List<ScriptEntry>>(text).filter { safe(it.file) }
    } catch (e: SerializationException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    /** 用绝对路径:不受工作区里同名文件或 PATH 的影响。 */
    fun interpreterFor(file: String): String? = INTERPRETERS[file.substringAfterLast('.', "").lowercase()]

    /** 此刻能不能跑,不能时给出原因:标为不可运行的用它自己的原因;需要联网而联网关着的,提示去设置里打开。 */
    fun effectiveRunnable(e: ScriptEntry, networkAllowed: Boolean): Pair<Boolean, String?> = when {
        !e.runnable -> false to (e.reason ?: "cannot be run for now")
        interpreterFor(e.file) == null -> false to "unsupported script type"
        e.network && !networkAllowed -> false to NEEDS_NETWORK
        else -> true to null
    }

    private fun safe(rel: String): Boolean {
        if (rel.isBlank() || rel.startsWith("/")) return false
        val base = "${SandboxPaths.SKILLS}/x"
        return SandboxPaths.normalize(rel, base)?.startsWith("$base/") == true
    }
}
