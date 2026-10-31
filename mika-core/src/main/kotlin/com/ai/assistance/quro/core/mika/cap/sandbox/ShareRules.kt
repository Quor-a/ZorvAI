package com.ai.assistance.quro.core.mika.cap.sandbox

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import com.ai.assistance.quro.core.mika.workspace.SandboxError
import com.ai.assistance.quro.core.mika.workspace.SandboxPaths

/** 存到「下载/ZorvAI」的规则;share_file 工具和沙箱里的 `quro share` 共用,两条路一样严。 */
object ShareRules {
    const val MAX_BYTES = 100L shl 20

    /**
     * [path] 是沙箱路径(相对路径以 /workspace 为基),必须在工作区里、经 [SandboxPort.hostPath] 解析(链接逃逸为 null),
     * 是普通文件(不跟随链接)、不超过 100 MB;[name] 默认用文件自己的名字,必须是不含控制字符的单个文件名。
     * 返回宿主文件与保存名;不合规则抛 [SandboxError]。
     */
    fun check(sandbox: SandboxPort, workspace: String, path: String, name: String?): Pair<File, String> {
        val p = SandboxPaths.normalize(path)?.takeIf(SandboxPaths::isWritable)
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared.", recoverable = true)
        val f = sandbox.hostPath(workspace, p, forWrite = true)
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared; links that lead outside are refused.", recoverable = true)
        val given = name?.trim() ?: f.name
        if (!plainName(given)) throw SandboxError("invalid_arguments", "name: must be a plain file name without control characters", recoverable = true)
        val saveAs = fitName(given)
        if (refusedType(saveAs) || refusedType(f.name)) {
            throw SandboxError("share_refused_type", "App installation packages (.apk, .apks, .xapk, .aab) cannot be shared.", recoverable = false)
        }
        val hp = f.toPath()
        if (!Files.exists(hp, LinkOption.NOFOLLOW_LINKS)) throw SandboxError("file_not_found", "$p does not exist.", recoverable = true)
        if (!Files.isRegularFile(hp, LinkOption.NOFOLLOW_LINKS)) throw SandboxError("not_a_file", "$p is not a regular file.", recoverable = true)
        val size = Files.size(hp)
        if (size > MAX_BYTES) throw SandboxError("file_too_large", "$p is ${size shr 20} MB; at most 100 MB can be shared.", recoverable = true)
        return f to saveAs
    }

    /** 单个文件名:不含路径分隔符、控制字符、格式字符(改写方向、零宽等,能让显示的名字和实际的对不上)与行/段分隔符。 */
    fun plainName(n: String): Boolean =
        n.isNotEmpty() && '/' !in n && '\\' !in n && n != "." && n != ".." &&
            n.codePoints().noneMatch(::hiddenChar)

    /** 不让导出:安装包放进「下载」里,用户一点就可能装上沙箱里做出来的 App。 */
    private val REFUSED_TYPES = setOf("apk", "apks", "xapk", "aab")

    fun refusedType(name: String): Boolean = name.substringAfterLast('.', "").trim().lowercase() in REFUSED_TYPES

    /** 文件名上限 255 字节(UTF-8),留出系统加「 (n)」序号的余地。 */
    const val NAME_MAX_BYTES = 240

    /** 按 UTF-8 字节(和可选的字数)截短,保留扩展名(扩展名本身太长时不保留),不劈开字符。 */
    fun fitName(name: String, maxBytes: Int = NAME_MAX_BYTES, maxChars: Int = Int.MAX_VALUE): String {
        fun bytes(t: String) = t.toByteArray(Charsets.UTF_8).size
        fun chars(t: String) = t.codePointCount(0, t.length)
        if (bytes(name) <= maxBytes && chars(name) <= maxChars) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot) else ""
        val keepExt = ext.isNotEmpty() && bytes(ext) <= 32 && chars(ext) < maxChars
        val base = if (keepExt) name.substring(0, dot) else name
        val roomBytes = maxBytes - if (keepExt) bytes(ext) else 0
        val roomChars = if (maxChars == Int.MAX_VALUE) Int.MAX_VALUE else maxChars - if (keepExt) chars(ext) else 0
        val sb = StringBuilder()
        var used = 0
        var count = 0
        var i = 0
        while (i < base.length && count < roomChars) {
            val cp = base.codePointAt(i)
            val n = bytes(String(Character.toChars(cp)))
            if (used + n > roomBytes) break
            sb.appendCodePoint(cp)
            used += n
            count++
            i += Character.charCount(cp)
        }
        return sb.toString() + if (keepExt) ext else ""
    }

    /** 控制字符、格式字符(Cf)与 U+2028/U+2029。 */
    fun hiddenChar(cp: Int): Boolean =
        Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() || cp == 0x2028 || cp == 0x2029
}
