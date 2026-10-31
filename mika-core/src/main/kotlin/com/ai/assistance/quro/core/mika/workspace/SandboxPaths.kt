package com.ai.assistance.quro.core.mika.workspace

import java.io.File
import java.nio.file.Files

/** 模型只看到沙箱里的路径;这里规范化并判断越界。 */
object SandboxPaths {
    const val WORKSPACE = "/workspace"
    const val SKILLS = "/opt/quro/skills"

    /** 相对路径以 [cwd] 为基;规范化后不在 [WORKSPACE] 或 [SKILLS] 下返回 null。 */
    fun normalize(path: String, cwd: String = WORKSPACE): String? {
        if (path.isEmpty() || '\u0000' in path) return null
        val parts = ArrayDeque<String>()
        for (seg in (if (path.startsWith("/")) path else "$cwd/$path").split('/')) when (seg) {
            "", "." -> Unit
            ".." -> parts.removeLastOrNull() ?: return null
            else -> parts.addLast(seg)
        }
        val n = "/" + parts.joinToString("/")
        return n.takeIf { under(it, WORKSPACE) || under(it, SKILLS) }
    }

    /** 只有工作区可写;技能目录只读。 */
    fun isWritable(normalized: String): Boolean = under(normalized, WORKSPACE)

    /**
     * [root] 下的 [sandboxRel];逐段检查符号链接:指到 [root] 外面的、目标不存在的(写下去会落到哪里看不出来)一律 null,
     * 最终的 canonicalFile 也必须仍在 [root] 下。
     */
    fun resolveHost(root: File, sandboxRel: String): File? {
        val base = root.canonicalFile
        var cur = base
        for (seg in sandboxRel.split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") return null
            cur = File(cur, seg)
            val p = cur.toPath()
            if (Files.isSymbolicLink(p)) {
                if (!Files.exists(p)) return null
                cur = cur.canonicalFile
                if (!inside(cur, base)) return null
            }
        }
        return cur.canonicalFile.takeIf { inside(it, base) }
    }

    private fun under(p: String, root: String) = p == root || p.startsWith("$root/")
    private fun inside(f: File, base: File) = f == base || f.path.startsWith(base.path + File.separator)
}
