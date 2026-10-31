package com.ai.assistance.quro.core.mika.cap.sandbox

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

data class ExtractReport(
    val files: Int,
    val dirs: Int,
    val symlinks: Int,
    val hardlinksCopied: Int,
    /** 最多 [RootfsExtractor.MAX_SKIPPED_NAMES] 个名字;总数看 [skippedTotal]。 */
    val skipped: List<String>,
    val skippedTotal: Int = skipped.size,
)

class UnsafeArchiveException(msg: String) : Exception(msg)

/**
 * 把根文件系统 tar.gz 流式解到 dest。
 *
 * - 符号链接的目标原样保留(哪怕是绝对路径,它在 PRoot 里按根文件系统解释)。
 * - 安卓上 App 不能建硬链接,硬链接改为复制已解出的目标。
 * - 设备文件、FIFO 跳过,记进 skipped。
 * - 任何条目(以及硬链接目标)规范化后不在 dest 里,或要穿过一个已解出的符号链接,抛 UnsafeArchiveException;
 *   已解出的内容由调用方用 [deleteTree] 删除——不能用 File.deleteRecursively,它会跟随(绝对)符号链接删到 dest 外面。
 * - 条目数超过 maxEntries,或普通文件与硬链接副本的总字节数超过 maxBytes,同样抛 UnsafeArchiveException。
 */
object RootfsExtractor {
    const val MAX_SKIPPED_NAMES = 1000

    fun extract(
        tarGz: InputStream,
        dest: File,
        maxBytes: Long = 1L shl 30,
        maxEntries: Int = 200_000,
    ): ExtractReport = extractTar(GzipCompressorInputStream(BufferedInputStream(tarGz), true), dest, maxBytes, maxEntries)

    /** 同 [extract],输入是已解压的 tar 流(调用方自己解 xz 等)。 */
    fun extractTar(
        input: InputStream,
        dest: File,
        maxBytes: Long = 1L shl 30,
        maxEntries: Int = 200_000,
    ): ExtractReport {
        val root = dest.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardlinks = 0
        val skipped = mutableListOf<String>()
        var skippedTotal = 0
        var entries = 0
        var bytes = 0L
        fun skip(name: String) {
            skippedTotal++
            if (skipped.size < MAX_SKIPPED_NAMES) skipped += name
        }
        fun addBytes(n: Long, name: String) {
            bytes += n
            if (bytes > maxBytes) throw UnsafeArchiveException("archive exceeds $maxBytes bytes at: $name")
        }

        TarArchiveInputStream(BufferedInputStream(input)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                if (++entries > maxEntries) throw UnsafeArchiveException("archive has more than $maxEntries entries")
                val rel = relativeName(entry.name)
                if (rel.isEmpty()) continue // "./" 本身就是 dest
                val target = resolveInside(root, rel, entry.name)
                when {
                    entry.isDirectory -> {
                        removeIfLinkOrFile(target, dirOk = true)
                        Files.createDirectories(target)
                        setMode(target, entry.mode, isDir = true)
                        dirs++
                    }
                    entry.isSymbolicLink -> {
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        Files.createSymbolicLink(target, linkTarget(target, entry.linkName))
                        symlinks++
                    }
                    entry.isLink -> {
                        val linkRel = relativeName(entry.linkName)
                        val source = resolveInside(root, linkRel, entry.linkName)
                        if (linkRel.isEmpty() || !Files.exists(source, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                            skip(rel)
                            continue
                        }
                        val attrs = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        if (attrs.isRegularFile) addBytes(attrs.size(), rel)
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        // NOFOLLOW_LINKS:目标若是符号链接,复制链接本身而不是它指向的东西
                        Files.copy(source, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                        hardlinks++
                    }
                    // 放在 isFile 之前:Commons Compress 对设备条目的 isFile 也可能为真
                    entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO -> skip(rel)
                    entry.isFile -> {
                        // 库只会读出条目声明的字节数,所以按声明大小卡上限就够了
                        addBytes(entry.size, rel)
                        prepareParent(target)
                        removeIfLinkOrFile(target, dirOk = false)
                        // CREATE_NEW:路径上若冒出符号链接,宁可失败也不跟过去写
                        Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { tar.copyTo(it) }
                        setMode(target, entry.mode, isDir = false)
                        files++
                    }
                    else -> skip(rel) // 其他不认识的类型
                }
            }
        }
        return ExtractReport(files, dirs, symlinks, hardlinks, skipped, skippedTotal)
    }

    /**
     * 删除整棵树,绝不跟随符号链接(链接本身被删,指向的东西不动);删之前把每个目录改成属主可读写可进入,
     * 否则只读目录里的东西删不掉。dest 不存在时什么也不做。
     */
    fun deleteTree(dest: File) {
        val root = dest.toPath()
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        // 不传 FOLLOW_LINKS:符号链接(包括 root 本身是链接时)按普通文件交给 visitFile
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val perms = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).toMutableSet()
                perms += listOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                Files.setPosixFilePermissions(dir, perms)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun linkTarget(at: Path, linkName: String): Path =
        try {
            at.fileSystem.getPath(linkName)
        } catch (e: InvalidPathException) {
            throw UnsafeArchiveException("invalid symlink target: ${e.message}")
        }

    /** 去掉开头的 "./" 与末尾的 "/";绝对路径直接拒绝。 */
    private fun relativeName(name: String): String {
        if (name.startsWith("/")) throw UnsafeArchiveException("absolute path in archive: $name")
        var n = name
        while (n.startsWith("./")) n = n.substring(2)
        if (n == ".") n = ""
        return n.trimEnd('/')
    }

    private fun resolveInside(root: Path, rel: String, original: String): Path {
        val p = try {
            root.resolve(rel).normalize()
        } catch (e: InvalidPathException) {
            throw UnsafeArchiveException("invalid path in archive: ${e.message}")
        }
        if (!p.startsWith(root)) throw UnsafeArchiveException("path escapes destination: $original")
        // 中间某一级若是已解出的符号链接,写进去就可能落到 dest 外面
        var cur = root
        for (part in root.relativize(p).toList().dropLast(1)) {
            cur = cur.resolve(part)
            if (Files.isSymbolicLink(cur)) throw UnsafeArchiveException("path goes through a symlink: $original")
        }
        return p
    }

    private fun prepareParent(target: Path) {
        target.parent?.let { Files.createDirectories(it) }
    }

    private fun removeIfLinkOrFile(target: Path, dirOk: Boolean) {
        if (Files.isSymbolicLink(target)) {
            Files.delete(target)
        } else if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!dirOk) Files.delete(target) // 非空目录会抛 IOException,交给调用方
        } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(target)
        }
    }

    /** 只取 0777;属主始终可读写,目录始终可进入,否则之后写不进、删不掉。 */
    private fun setMode(target: Path, tarMode: Int, isDir: Boolean) {
        var m = (tarMode and 0x1ff) or 0x180
        if (isDir) m = m or 0x40
        Files.setPosixFilePermissions(target, toPerms(m))
    }

    private val permBits = listOf(
        0x100 to PosixFilePermission.OWNER_READ,
        0x80 to PosixFilePermission.OWNER_WRITE,
        0x40 to PosixFilePermission.OWNER_EXECUTE,
        0x20 to PosixFilePermission.GROUP_READ,
        0x10 to PosixFilePermission.GROUP_WRITE,
        0x8 to PosixFilePermission.GROUP_EXECUTE,
        0x4 to PosixFilePermission.OTHERS_READ,
        0x2 to PosixFilePermission.OTHERS_WRITE,
        0x1 to PosixFilePermission.OTHERS_EXECUTE,
    )

    private fun toPerms(mode: Int): Set<PosixFilePermission> =
        permBits.filter { (bit, _) -> mode and bit != 0 }.mapTo(mutableSetOf()) { it.second }
}
