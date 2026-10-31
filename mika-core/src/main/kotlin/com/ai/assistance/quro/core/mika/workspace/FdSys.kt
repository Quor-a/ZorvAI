package com.ai.assistance.quro.core.mika.workspace

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** 句柄所指文件的类型与身份(fstat/lstat)。 */
data class FdStat(val kind: Kind, val nlink: Long, val size: Long, val dev: Long, val ino: Long) {
    enum class Kind { FILE, DIR, LINK, OTHER }
}

/** 打开一次得到的句柄;实际是哪个文件只看句柄本身(安卓上读 /proc/self/fd/N),不再按路径重走。 */
interface OpenFd : Closeable {
    fun realPath(): String
    fun stat(): FdStat
    /** 文件句柄上的通道(读或写,看打开方式);目录没有。 */
    fun channel(): FileChannel
    fun truncate(size: Long)
}

enum class OpenHow { READ, CREATE_NEW, WRITE, APPEND }

/** [FdSys] 报的系统调用错误,由 [WorkspaceFileGate] 换成工具层的错误码。 */
class FdSysException(val kind: Kind, cause: Throwable? = null) : IOException(kind.name, cause) {
    enum class Kind { NOT_FOUND, EXISTS, LINK, NOT_DIR, NOT_FILE }
}

/**
 * 系统调用那一层。带 dir 的调用都以那个已打开的目录为准,[name] 是单段名字,最后一段不跟随链接。
 * 安卓上的实现在 app(open O_NOFOLLOW + /proc/self/fd/N);[NioFdSys] 是 JVM 上按路径的近似,只给 JVM 测试用。
 */
interface FdSys {
    /** 根目录本身(调用方给的、沙箱碰不到的路径);最后一段是链接时报 LINK。 */
    fun openRoot(path: File): OpenFd
    fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd
    fun openFile(dir: OpenFd, name: String, how: OpenHow): OpenFd
    /** 不跟随链接;不存在为 null。 */
    fun lstat(dir: OpenFd, name: String): FdStat?
    fun readlink(dir: OpenFd, name: String): String?
    fun list(dir: OpenFd): List<String>
    /** 删掉这一项本身(文件、链接或空目录),不跟随链接。 */
    fun remove(dir: OpenFd, name: String)
    /** 同一目录里改名,[to] 已有(文件或链接)时整个换掉。 */
    fun rename(dir: OpenFd, from: String, to: String)
    fun symlink(dir: OpenFd, name: String, target: String)
    fun chmod(fd: OpenFd, mode: Int)
}

/**
 * JVM 上的近似实现:按路径解析,检查与使用之间有空隙。只用于 JVM 单元测试(macOS 上没有 /proc/self/fd);
 * 手机上一律用 app 里的安卓实现。
 */
object NioFdSys : FdSys {
    private class Fd(val path: Path, private val ch: FileChannel?) : OpenFd {
        private val real = path.toRealPath().toString()
        override fun realPath() = real
        override fun stat(): FdStat = statOf(path) ?: throw FdSysException(FdSysException.Kind.NOT_FOUND)
        override fun channel(): FileChannel = ch ?: throw FdSysException(FdSysException.Kind.NOT_FILE)
        override fun truncate(size: Long) { channel().truncate(size) }
        override fun close() { ch?.close() }
    }

    private fun fd(dir: OpenFd) = dir as Fd

    private fun attrs(p: Path): BasicFileAttributes? =
        try { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (e: IOException) { null }

    private fun statOf(p: Path): FdStat? {
        val a = try { Files.readAttributes(p, "unix:mode,nlink,size,dev,ino", LinkOption.NOFOLLOW_LINKS) } catch (e: IOException) { return null }
        val mode = (a["mode"] as Int) and 0xF000
        val kind = when (mode) { 0x8000 -> FdStat.Kind.FILE; 0x4000 -> FdStat.Kind.DIR; 0xA000 -> FdStat.Kind.LINK; else -> FdStat.Kind.OTHER }
        return FdStat(kind, (a["nlink"] as Number).toLong(), (a["size"] as Number).toLong(), (a["dev"] as Number).toLong(), (a["ino"] as Number).toLong())
    }

    private fun dirAt(p: Path): Fd {
        val a = attrs(p) ?: throw FdSysException(FdSysException.Kind.NOT_FOUND)
        if (a.isSymbolicLink) throw FdSysException(FdSysException.Kind.LINK)
        if (!a.isDirectory) throw FdSysException(FdSysException.Kind.NOT_DIR)
        return Fd(p, null)
    }

    override fun openRoot(path: File): OpenFd = dirAt(path.toPath().toAbsolutePath())

    override fun openDir(dir: OpenFd, name: String, create: Boolean): OpenFd {
        val p = fd(dir).path.resolve(name)
        if (create && attrs(p) == null) runCatching { Files.createDirectory(p) }
        return dirAt(p)
    }

    override fun openFile(dir: OpenFd, name: String, how: OpenHow): OpenFd {
        val p = fd(dir).path.resolve(name)
        val a = attrs(p)
        if (a?.isSymbolicLink == true) throw FdSysException(if (how == OpenHow.CREATE_NEW) FdSysException.Kind.EXISTS else FdSysException.Kind.LINK)
        // NIO 打不开 O_NONBLOCK:FIFO 之类先按类型拒掉,免得读写时卡住
        if (a != null && !a.isRegularFile) throw FdSysException(if (how == OpenHow.CREATE_NEW) FdSysException.Kind.EXISTS else FdSysException.Kind.NOT_FILE)
        val opts = when (how) {
            OpenHow.READ -> setOf(StandardOpenOption.READ)
            OpenHow.CREATE_NEW -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            OpenHow.WRITE -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            OpenHow.APPEND -> setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
        val ch = try {
            FileChannel.open(p, opts + LinkOption.NOFOLLOW_LINKS)
        } catch (e: FileAlreadyExistsException) {
            throw FdSysException(FdSysException.Kind.EXISTS, e)
        } catch (e: NoSuchFileException) {
            throw FdSysException(FdSysException.Kind.NOT_FOUND, e)
        }
        return Fd(p, ch)
    }

    override fun lstat(dir: OpenFd, name: String): FdStat? = statOf(fd(dir).path.resolve(name))

    override fun readlink(dir: OpenFd, name: String): String? =
        runCatching { Files.readSymbolicLink(fd(dir).path.resolve(name)).toString() }.getOrNull()

    override fun list(dir: OpenFd): List<String> = Files.list(fd(dir).path).use { s -> s.map { it.fileName.toString() }.toList() }

    override fun remove(dir: OpenFd, name: String) {
        try { Files.delete(fd(dir).path.resolve(name)) } catch (e: NoSuchFileException) { throw FdSysException(FdSysException.Kind.NOT_FOUND, e) }
    }

    override fun rename(dir: OpenFd, from: String, to: String) {
        val d = fd(dir).path
        Files.move(d.resolve(from), d.resolve(to), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun symlink(dir: OpenFd, name: String, target: String) {
        Files.createSymbolicLink(fd(dir).path.resolve(name), java.nio.file.Paths.get(target))
    }

    override fun chmod(fd: OpenFd, mode: Int) {
        val perms = java.nio.file.attribute.PosixFilePermissions.fromString(
            (0..8).joinToString("") { i -> if (mode and (1 shl (8 - i)) != 0) "rwx"[i % 3].toString() else "-" },
        )
        Files.setPosixFilePermissions(fd(fd).path, perms)
    }
}
