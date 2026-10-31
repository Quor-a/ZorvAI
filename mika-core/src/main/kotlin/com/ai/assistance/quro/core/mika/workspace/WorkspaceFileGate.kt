package com.ai.assistance.quro.core.mika.workspace

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.UUID

/**
 * 宿主侧进出沙箱目录(工作区、技能目录、根文件系统)的文件都过这一道。
 *
 * 沙箱里的命令和 ZorvAI 是同一个用户,随时能把文件或目录换成符号链接、硬链接或 FIFO,所以「先按路径检查、再按路径使用」
 * 挡不住。这里每一段都相对上一段已经打开的目录句柄打开、不跟随链接;最后的文件只打开一次,在句柄上核实
 * (实际位置仍在根目录里、是普通文件、只有一个硬链接),之后的读写都在这同一个句柄上。删除也先这样核实。
 *
 * [rel] 是根目录下的相对路径(`/` 分隔);`..`、空字节一律算越界。错误都抛 [SandboxError],消息里不带宿主路径。
 */
class WorkspaceFileGate(private val sys: FdSys) {

    /** 读:普通文件、只有一个硬链接;[under] 给出时文件必须直接在 `<root>/<under>/` 里。 */
    fun openRead(root: File, rel: String, under: String? = null): GatedFile = open(root, rel, under, OpenHow.READ, createDirs = false)

    /** 新建(已有同名的报 file_exists,从不覆盖);缺的目录逐段建。 */
    fun createNew(root: File, rel: String, under: String? = null): GatedFile = open(root, rel, under, OpenHow.CREATE_NEW, createDirs = true)

    /** 写:没有就建;已有的核实后才截断([append] 时接着写),所以截断不到链进来的别的文件。 */
    fun openWrite(root: File, rel: String, append: Boolean): GatedFile {
        val g = open(root, rel, null, if (append) OpenHow.APPEND else OpenHow.WRITE, createDirs = true)
        if (!append) try { g.truncate() } catch (e: IOException) { g.close(); throw e }
        return g
    }

    /** 在 `<root>/<dir>/` 里新建 [name];重名时依次试「name (2).ext」……,至多 [max] 个。返回实际名字与句柄。 */
    fun createNumbered(root: File, dir: String, name: String, max: Int = 999): Pair<String, GatedFile> {
        for (n in 1..max) {
            val candidate = numbered(name, n)
            try {
                return candidate to createNew(root, "$dir/$candidate", under = dir)
            } catch (e: SandboxError) {
                if (e.code != FILE_EXISTS) throw e
            }
        }
        throw SandboxError(FILE_EXISTS, "Too many files with this name.", recoverable = true)
    }

    /** 核实是普通文件后才删。 */
    fun delete(root: File, rel: String, under: String? = null) {
        open(root, rel, under, OpenHow.READ, createDirs = false).use { it.delete() }
    }

    /** 建好目录(逐段,不跟随链接)。 */
    fun mkdirs(root: File, rel: String) {
        anchored(root, segments(rel), create = true) { _, _ -> }
    }

    /** 目录里的名字;目录不存在为空。 */
    fun list(root: File, rel: String): List<String> = try {
        anchored(root, segments(rel), create = false) { _, d -> sys.list(d) }
    } catch (e: SandboxError) {
        if (e.code == FILE_NOT_FOUND) emptyList() else throw e
    }

    /**
     * 整个换掉 [rel]:在同一目录里写好一份新的再改名盖上。原来那一项是链接、FIFO 或硬链接都只是被换掉,不会写进它指向的东西。
     */
    fun replace(root: File, rel: String, bytes: ByteArray, mode: Int = 420 /* 0644 */) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        anchored(root, segs.dropLast(1), create = true) { rootReal, dir ->
            val tmp = ".quro-new-" + UUID.randomUUID()
            val f = file(dir, tmp, OpenHow.CREATE_NEW)
            try {
                f.use {
                    verify(it, rootReal, dir, tmp)
                    val buf = ByteBuffer.wrap(bytes)
                    while (buf.hasRemaining()) it.channel().write(buf)
                    sys.chmod(it, mode)
                }
                sys.rename(dir, tmp, segs.last())
            } catch (e: Exception) {
                runCatching { sys.remove(dir, tmp) }
                throw e
            }
        }
    }

    /** 让 [rel] 成为指向 [target] 的符号链接(已是就不动);原来那一项整个换掉。 */
    fun symlink(root: File, rel: String, target: String) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        anchored(root, segs.dropLast(1), create = true) { _, dir ->
            if (sys.readlink(dir, segs.last()) == target) return@anchored
            val tmp = ".quro-link-" + UUID.randomUUID()
            sys.symlink(dir, tmp, target)
            try { sys.rename(dir, tmp, segs.last()) } catch (e: Exception) { runCatching { sys.remove(dir, tmp) }; throw e }
        }
    }

    /**
     * 删掉 `<root>/<rel>` 整棵树:每一层都相对上一层的句柄打开、不跟随链接,链接只删链接本身。
     * 删的过程中被换成链接的目录不会被跟进去。只读目录先改成可写。
     */
    fun deleteTree(root: File, rel: String) {
        val segs = segments(rel)
        if (segs.isEmpty()) throw outside()
        try {
            anchored(root, segs.dropLast(1), create = false) { rootReal, dir -> removeEntry(dir, segs.last(), rootReal, 0) }
        } catch (e: SandboxError) {
            if (e.code != FILE_NOT_FOUND) throw e
        }
    }

    private fun removeEntry(dir: OpenFd, name: String, rootReal: String, depth: Int) {
        val st = sys.lstat(dir, name) ?: return
        if (st.kind == FdStat.Kind.DIR) {
            if (depth > MAX_DEPTH) throw IOException("directory tree too deep to delete")
            val sub = try { sys.openDir(dir, name, create = false) } catch (e: FdSysException) { null }
            if (sub != null) sub.use {
                // 删的过程中被挪到根目录外面的,不跟着删
                if (!inside(it.realPath(), rootReal)) return
                runCatching { sys.chmod(it, 448 /* 0700 */) }
                for (child in sys.list(it)) removeEntry(it, child, rootReal, depth + 1)
            }
        }
        try { sys.remove(dir, name) } catch (e: FdSysException) { if (e.kind != FdSysException.Kind.NOT_FOUND) throw e }
    }

    private fun open(root: File, rel: String, under: String?, how: OpenHow, createDirs: Boolean): GatedFile {
        val segs = segments(rel)
        if (segs.isEmpty()) throw notAFile()
        if (under != null && segs.dropLast(1) != segments(under)) throw outside()
        val rootFd = openRoot(root)
        val rootReal: String
        val dir: OpenFd
        try {
            rootReal = rootFd.realPath()
            dir = walk(rootFd, segs.dropLast(1), createDirs)
        } catch (e: Exception) {
            rootFd.close()
            throw e
        }
        // 之后 dir 归 GatedFile 关;dir 不是根目录本身时根目录句柄这里就关
        if (dir !== rootFd) rootFd.close()
        try {
            if (under != null && dir.realPath() != rootReal.trimEnd('/') + "/" + segments(under).joinToString("/")) throw outside()
            val f = file(dir, segs.last(), how)
            try {
                return GatedFile(sys, dir, f, segs.last(), verify(f, rootReal, dir, segs.last()))
            } catch (e: Exception) {
                f.close()
                throw e
            }
        } catch (e: Exception) {
            dir.close()
            throw e
        }
    }

    /** 根目录句柄 → 逐段打开到 [segs] 指的目录;中间的句柄用完即关。 */
    private fun walk(rootFd: OpenFd, segs: List<String>, create: Boolean): OpenFd {
        var cur = rootFd
        for (s in segs) {
            val next = try { sys.openDir(cur, s, create) } catch (e: FdSysException) {
                if (cur !== rootFd) cur.close()
                throw map(e, dirStep = true)
            }
            if (cur !== rootFd) cur.close()
            cur = next
        }
        return cur
    }

    private fun <T> anchored(root: File, segs: List<String>, create: Boolean, block: (String, OpenFd) -> T): T {
        val rootFd = openRoot(root)
        try {
            val rootReal = rootFd.realPath()
            val dir = walk(rootFd, segs, create)
            try {
                if (!inside(dir.realPath(), rootReal) && dir !== rootFd) throw outside()
                return block(rootReal, dir)
            } finally {
                if (dir !== rootFd) dir.close()
            }
        } finally {
            rootFd.close()
        }
    }

    private fun openRoot(root: File): OpenFd = try { sys.openRoot(root) } catch (e: FdSysException) { throw map(e, dirStep = true) }

    private fun file(dir: OpenFd, name: String, how: OpenHow): OpenFd =
        try { sys.openFile(dir, name, how) } catch (e: FdSysException) { throw map(e, dirStep = false) }

    /** 在句柄上核实:实际位置就是打开它的那个目录里的那个名字、在根目录里;普通文件;只有一个硬链接。 */
    private fun verify(f: OpenFd, rootReal: String, dir: OpenFd, name: String): FdStat {
        val real = f.realPath()
        if (!inside(real, rootReal) || real != dir.realPath().trimEnd('/') + "/" + name) throw outside()
        val st = f.stat()
        if (st.kind != FdStat.Kind.FILE) throw notAFile()
        if (st.nlink != 1L) throw SandboxError(LINKED_FILE, "The file has more than one hard link, so ZorvAI does not use it; copy it (cp) and use the copy.", recoverable = true)
        return st
    }

    private fun map(e: FdSysException, dirStep: Boolean): SandboxError = when (e.kind) {
        FdSysException.Kind.NOT_FOUND -> SandboxError(FILE_NOT_FOUND, "The file does not exist.", recoverable = true)
        FdSysException.Kind.EXISTS -> SandboxError(FILE_EXISTS, "A file with this name already exists.", recoverable = true)
        FdSysException.Kind.NOT_DIR -> SandboxError(FILE_NOT_FOUND, "A folder on this path is not a folder.", recoverable = true)
        FdSysException.Kind.LINK ->
            if (dirStep) SandboxError(NOT_A_FILE, "A folder on this path is a link; ZorvAI's file tools do not follow links. Use the real path.", recoverable = true)
            else notAFile()
        FdSysException.Kind.NOT_FILE -> notAFile()
    }

    private fun outside() = SandboxError(OUTSIDE, "The file is outside the allowed folder.", recoverable = true)
    private fun notAFile() = SandboxError(NOT_A_FILE, "Not a regular file (a directory, link or special file).", recoverable = true)

    companion object {
        const val FILE_NOT_FOUND = "file_not_found"
        const val FILE_EXISTS = "file_exists"
        const val NOT_A_FILE = "not_a_file"
        const val LINKED_FILE = "linked_file"
        const val OUTSIDE = "path_outside_workspace"
        private const val MAX_DEPTH = 256

        /** 根目录下的各段;`..`、空字节越界。 */
        fun segments(rel: String): List<String> {
            if ('\u0000' in rel) throw SandboxError(OUTSIDE, "The path is not allowed.", recoverable = true)
            return rel.split('/').filter { it.isNotEmpty() && it != "." }.onEach {
                if (it == "..") throw SandboxError(OUTSIDE, "The path is not allowed.", recoverable = true)
            }
        }

        /** 「a.csv」→「a (2).csv」;没有扩展名的直接加在后面。 */
        fun numbered(name: String, n: Int): String {
            if (n <= 1) return name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
        }

        internal fun inside(path: String, root: String): Boolean = path == root || path.startsWith(root.trimEnd('/') + "/")
    }
}

/** 过了 [WorkspaceFileGate] 核实的一个文件;读写都在这个句柄上。[stat] 是核实时的。 */
class GatedFile internal constructor(
    private val sys: FdSys,
    private val dir: OpenFd,
    private val fd: OpenFd,
    val name: String,
    val stat: FdStat,
) : Closeable {
    val size: Long get() = stat.size

    fun channel() = fd.channel()

    /** 从头读(或从当前位置);关掉流不关句柄。 */
    fun input(): InputStream = object : java.io.FilterInputStream(Channels.newInputStream(fd.channel())) { override fun close() = Unit }

    fun output(): OutputStream = object : java.io.FilterOutputStream(Channels.newOutputStream(fd.channel())) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    /** 从 [offset] 起读至多 [len] 字节,读到文件末尾为止。 */
    fun read(offset: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = fd.channel().read(buf, pos)
            if (n < 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }

    fun write(bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) fd.channel().write(buf)
    }

    fun currentSize(): Long = fd.channel().size()

    internal fun truncate() = fd.truncate(0)

    /** 删掉这个文件:目录里这个名字仍是同一个文件(设备号+inode)才删,换成了别的就不动。 */
    fun delete() {
        val now = sys.lstat(dir, name) ?: return
        if (now.dev != stat.dev || now.ino != stat.ino || now.kind != FdStat.Kind.FILE) return
        try { sys.remove(dir, name) } catch (e: FdSysException) { if (e.kind != FdSysException.Kind.NOT_FOUND) throw e }
    }

    override fun close() {
        runCatching { fd.close() }
        runCatching { dir.close() }
    }
}
