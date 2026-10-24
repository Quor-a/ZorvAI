package com.ai.assistance.quro.terminal.moyu

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * 最小 ISO9660（ECMA-119 Level 1）镜像写出器。
 *
 * ## 为什么必须自己写
 *
 * 墨语言自举的系统要"构建成 ISO"，而 proot 承载的是一个**我们自己造的最小 rootfs** ——
 * 里面既没有 `/bin/sh`，也没有 `mkisofs` / `xorriso`。之前的实现是
 * `prootRun("/bin/sh", "-c", "command -v mkisofs ...")`，在这套 rootfs 里 `/bin/sh` 根本不存在，
 * 于是这一步永远静默跳过、ISO 永远不生成 —— 也就是"proot 里没有 iso"。
 *
 * 所以 ISO 必须由 App 侧直接产出：不依赖任何外部工具、不依赖 rootfs 里有什么。
 *
 * ## 产出的镜像结构
 *
 * ```
 * sector 0..15    系统区（全 0）
 * sector 16       Primary Volume Descriptor
 * sector 17       Volume Descriptor Set Terminator
 * sector 18..     Type-L Path Table（紧邻）
 *                 Type-M Path Table
 * 之后             各目录的 Directory Record 区
 * 最后             各文件数据（2048 对齐）
 * ```
 *
 * 算法已用 pycdlib（严格 ISO9660 解析器）逐字节校验：目录树可遍历、文件内容可原样读回。
 *
 * ## 两个踩过的坑（改这里前先看）
 *
 * 1. 目录记录里的 Recording Date 是 **7 字节**（6 字节日期 + 1 字节 GMT 偏移），不是 6 字节。
 * 2. 目录自己的记录区里，首条标识符**恒为 0x00**（"."）、次条恒为 0x01（".."）。
 *    目录的真名只出现在**父目录**的记录里；写成自己的名字会被解析器当成
 *    "extent 与自身相同的子目录" → 判成 Directory Records form a loop。
 */
object MolangIso {

    private const val SECTOR = 2048
    private val ALLOWED = ('A'..'Z').toSet() + ('0'..'9').toSet() + '_'

    /** 镜像中的一个节点。 */
    private class Node(
        val isoName: String,
        val path: File,
        val isDir: Boolean,
    ) {
        val children = mutableListOf<Node>()
        var parent: Node = this
        var dirNum: Int = 0
        var lba: Int = 0
        /** 目录 = 记录区补齐后的字节数；文件 = 实际字节数。 */
        var size: Long = 0
        var recs: ByteArray = ByteArray(0)
    }

    /**
     * 把目录树写成 ISO。
     *
     * @param rootDir 要打包的根目录（子目录递归）
     * @param outFile 输出的 .iso
     * @return 成功返回镜像信息，失败抛出
     */
    @Throws(Exception::class)
    fun write(
        rootDir: File,
        outFile: File,
        volumeId: String = "MOLANG",
        systemId: String = "ZORVAI",
        applicationId: String = "ZORVAI-MOLANG",
    ): String {
        require(rootDir.isDirectory) { "不是目录：${rootDir.absolutePath}" }
        outFile.parentFile?.mkdirs()

        val root = buildTree(rootDir)
        root.parent = root
        setParent(root)
        // ISO9660 单区段上限约 2GiB，超了这里会静默截断 —— 提前说清楚
        for (f in collect(root)) {
            for (c in f.children) {
                if (!c.isDir && c.path.length() > Int.MAX_VALUE - 1) {
                    throw IllegalArgumentException("文件超过 ISO9660 单区段上限：${c.path.absolutePath}")
                }
            }
        }

        val dirs = collect(root)
        val files = mutableListOf<Node>()
        for (d in dirs) {
            for (c in d.children) if (!c.isDir) {
                c.parent = d
                files.add(c)
            }
        }

        val dt = recordDate()

        // 记录区大小 ↔ LBA 互相依赖，跑两趟即可稳定
        var total = 0L
        repeat(2) {
            var cursor = 20L
            for (d in dirs) {
                d.lba = cursor.toInt()
                d.recs = recordsFor(d, dt)
                d.size = roundUp(d.recs.size.toLong())
                cursor += d.size / SECTOR
            }
            for (f in files) {
                f.lba = cursor.toInt()
                f.size = f.path.length()
                cursor += (roundUp(f.size) / SECTOR).coerceAtLeast(1)
            }
            total = cursor
        }

        val ptL = pathTable(dirs, big = false)
        val ptM = pathTable(dirs, big = true)
        val ptSize = maxOf(ptL.size, ptM.size)
        val ptSectors = (ptSize + SECTOR - 1) / SECTOR

        FileOutputStream(outFile).use { os ->
            // 系统区
            os.write(ByteArray(16 * SECTOR))

            // PVD
            val p = ByteArray(SECTOR)
            p[0] = 1
            p.put(1, "CD001".toByteArray(Charsets.US_ASCII))
            p[6] = 1                      // version
            p[7] = 0                      // flags
            p.put(8, ascii(systemId, 32))
            p.put(40, ascii(volumeId, 32))
            // 72..79 unused（8 字节 0）
            p.put32(80, total.toInt()); p.put32be(84, total.toInt())   // volume space size
            // 88..119 escape sequences（32 字节 0）
            p.putBoth16(120, 1)           // volume set size
            p.putBoth16(124, 1)           // volume sequence number
            p.putBoth16(128, SECTOR)      // logical block size
            p.put32(132, ptSize); p.put32be(136, ptSize)               // path table size
            p.put32(140, 18)              // L path table 位置
            p.put32(144, 0)               // optional L
            p.put32be(148, 18 + ptSectors) // M path table 位置（紧邻 L，差值 = 占用扇区数）
            p.put32be(152, 0)             // optional M
            p.put(156, dirRecord(byteArrayOf(0x00), root.lba, root.size.toInt(), 0x02, dt))
            p.put(190, ascii("MOLANG_SYSTEM", 128))  // volume set identifier
            p.put(318, ascii(systemId, 128))         // publisher
            p.put(446, ascii(systemId, 128))         // data preparer
            p.put(574, ascii(applicationId, 128))    // application
            // 702..812 copyright / abstract / bibliographic（留空）
            val vd = volumeDate()
            p.put(813, vd); p.put(830, vd); p.put(847, vd); p.put(864, vd)
            p[881] = 1                    // file structure version
            p[882] = 0                    // unused2
            os.write(p)

            // Terminator
            val t = ByteArray(SECTOR)
            t[0] = 255.toByte()
            t.put(1, "CD001".toByteArray(Charsets.US_ASCII))
            t[6] = 1
            os.write(t)

            for (i in 0 until ptSectors) os.write(chunk(ptL, i).padTo(SECTOR))
            for (i in 0 until ptSectors) os.write(chunk(ptM, i).padTo(SECTOR))

            for (d in dirs) {
                os.write(d.recs.padTo(d.size.toInt()))
            }
            for (f in files) {
                val buf = ByteArray(64 * 1024)
                FileInputStream(f.path).use { ins ->
                    var left = f.size
                    while (left > 0) {
                        val n = ins.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                        if (n < 0) break
                        os.write(buf, 0, n)
                        left -= n
                    }
                }
                // 🔴 必须与 LBA 分配用**同一个**扇区数：空文件也占 1 扇区。
                // 若这里按 (-size) mod 2048 补零，size=0 时补 0 字节 —— 后面的文件
                // 整体前移一个扇区，读出来的全是错位内容。
                val sectors = (roundUp(f.size) / SECTOR).coerceAtLeast(1)
                val pad = sectors * SECTOR - f.size
                if (pad > 0) os.write(ByteArray(pad.toInt()))
            }
        }

        return buildString {
            appendLine("ISO：${outFile.absolutePath}")
            appendLine("卷标：$volumeId · ${outFile.length()} 字节 · $total 个 2048 字节扇区")
            appendLine("目录 ${dirs.size} 个 · 文件 ${files.size} 个")
        }
    }

    // ───────────────────────── 树构造 ─────────────────────────

    private fun buildTree(rootDir: File): Node {
        val root = Node("", rootDir, true)
        fill(root, rootDir)
        return root
    }

    private fun fill(node: Node, diskDir: File) {
        val entries = diskDir.listFiles()?.sortedBy { it.name } ?: return
        val used = HashSet<String>()
        for (e in entries) {
            val isDir = e.isDirectory
            if (!isDir && !e.isFile) continue
            // 8.3 截断后可能撞名，加序号区分
            var n = isoName(e.name, isDir)
            var i = 1
            while (!used.add(n)) {
                n = isoName(e.name, isDir, suffix = (++i).toString())
            }
            val child = Node(n, e, isDir)
            node.children.add(child)
            if (isDir) fill(child, e)
        }
    }

    private fun setParent(node: Node) {
        for (c in node.children) {
            c.parent = node
            if (c.isDir) setParent(c)
        }
    }

    /** 广度优先编号：root 恒为 1。 */
    private fun collect(root: Node): List<Node> {
        val out = mutableListOf<Node>()
        val queue = ArrayDeque<Node>()
        queue.add(root)
        var n = 0
        while (queue.isNotEmpty()) {
            val d = queue.removeFirst()
            d.dirNum = ++n
            out.add(d)
            for (c in d.children) if (c.isDir) queue.add(c)
        }
        return out
    }

    /**
     * 转成 ISO9660 Level 1 名字：大写、`A-Z0-9_`、主干最多 8、扩展名最多 3，文件再加 `;1`。
     *
     * @param suffix 撞名时插在主干尾部的序号（保证主干总长仍 ≤ 8）
     */
    private fun isoName(raw: String, isDir: Boolean, suffix: String = ""): String {
        val base = raw.uppercase()
        val stemRaw: String
        val extRaw: String
        if (!isDir && '.' in base) {
            val i = base.lastIndexOf('.')
            stemRaw = base.substring(0, i)
            extRaw = base.substring(i + 1)
        } else {
            stemRaw = base
            extRaw = ""
        }
        val core = stemRaw.map { if (it in ALLOWED) it else '_' }.joinToString("")
        val stem = if (suffix.isEmpty()) core.take(8) else core.take(8 - suffix.length) + suffix
        val ext = extRaw.map { if (it in ALLOWED) it else '_' }.joinToString("").take(3)
        return if (isDir) stem else "$stem.$ext;1"
    }

    // ───────────────────────── 记录构造 ─────────────────────────

    private fun dirRecord(
        nameBytes: ByteArray,
        lba: Int,
        size: Int,
        flags: Int,
        dt: ByteArray,
    ): ByteArray {
        val pad = if (nameBytes.size % 2 == 0) 1 else 0
        val total = 33 + nameBytes.size + pad
        val b = ByteArray(total)
        b[0] = total.toByte()
        b[1] = 0                       // extended attribute record length
        b.putBoth32(2, lba)            // extent location
        b.putBoth32(10, size)          // data length
        b.put(18, dt)                  // 7 字节日期
        b[25] = flags.toByte()
        b[26] = 0                      // file unit size
        b[27] = 0                      // interleave gap
        b.putBoth16(28, 1)             // volume sequence number
        b[32] = nameBytes.size.toByte()
        b.put(33, nameBytes)
        return b
    }

    /** 一个目录的记录区：`.` + `..` + 全部子项。 */
    private fun recordsFor(node: Node, dt: ByteArray): ByteArray {
        val parts = mutableListOf<ByteArray>()
        parts.add(dirRecord(byteArrayOf(0x00), node.lba, node.size.toInt(), 0x02, dt))
        val parent = if (node.dirNum == 1) node else node.parent
        parts.add(dirRecord(byteArrayOf(0x01), parent.lba, parent.size.toInt(), 0x02, dt))
        for (c in node.children) {
            parts.add(
                dirRecord(
                    c.isoName.toByteArray(Charsets.US_ASCII),
                    c.lba,
                    c.size.toInt(),
                    if (c.isDir) 0x02 else 0x00,
                    dt,
                )
            )
        }
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    private fun pathTable(dirs: List<Node>, big: Boolean): ByteArray {
        val parts = mutableListOf<ByteArray>()
        for (d in dirs) {
            val ident = if (d.dirNum == 1) byteArrayOf(0x00)
            else d.isoName.toByteArray(Charsets.US_ASCII)
            // 🔴 与目录记录**相反**：路径表记录基数是 8（8 + 名称），
            // 8 + 奇数 = 奇数，所以**名称长度为奇数时要补 1 字节**。
            // 目录记录基数是 33，规则正好反过来 —— 两处不能共用一套判定。
            val pad = if (ident.size % 2 == 0) 0 else 1
            val rec = ByteArray(8 + ident.size + pad)
            rec[0] = ident.size.toByte()
            rec[1] = 0
            if (big) {
                rec.put32be(2, d.lba)
                rec.put16be(6, if (d.dirNum == 1) 1 else d.parent.dirNum)
            } else {
                rec.put32(2, d.lba)
                rec.put16(6, if (d.dirNum == 1) 1 else d.parent.dirNum)
            }
            System.arraycopy(ident, 0, rec, 8, ident.size)
            parts.add(rec)
        }
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun roundUp(v: Long): Long = ((v + SECTOR - 1) / SECTOR) * SECTOR

    private fun chunk(src: ByteArray, i: Int): ByteArray {
        val from = i * SECTOR
        if (from >= src.size) return ByteArray(0)
        val to = minOf(from + SECTOR, src.size)
        return src.copyOfRange(from, to)
    }

    private fun ByteArray.padTo(size: Int): ByteArray {
        if (this.size == size) return this
        require(this.size <= size) { "内容 ${this.size} 超过补齐目标 $size" }
        return this + ByteArray(size - this.size)
    }

    private fun ascii(s: String, width: Int): ByteArray {
        val b = s.toByteArray(Charsets.US_ASCII)
        return if (b.size >= width) b.copyOfRange(0, width) else b + ByteArray(width - b.size) { ' '.code.toByte() }
    }

    private fun ByteArray.put(off: Int, src: ByteArray) {
        System.arraycopy(src, 0, this, off, src.size)
    }

    private fun ByteArray.put16(off: Int, v: Int) {
        this[off] = (v and 0xFF).toByte()
        this[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun ByteArray.put16be(off: Int, v: Int) {
        this[off] = ((v shr 8) and 0xFF).toByte()
        this[off + 1] = (v and 0xFF).toByte()
    }

    private fun ByteArray.putBoth16(off: Int, v: Int) {
        put16(off, v); put16be(off + 2, v)
    }

    private fun ByteArray.put32(off: Int, v: Int) {
        this[off] = (v and 0xFF).toByte()
        this[off + 1] = ((v shr 8) and 0xFF).toByte()
        this[off + 2] = ((v shr 16) and 0xFF).toByte()
        this[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun ByteArray.put32be(off: Int, v: Int) {
        this[off] = ((v shr 24) and 0xFF).toByte()
        this[off + 1] = ((v shr 16) and 0xFF).toByte()
        this[off + 2] = ((v shr 8) and 0xFF).toByte()
        this[off + 3] = (v and 0xFF).toByte()
    }

    private fun ByteArray.putBoth32(off: Int, v: Int) {
        put32(off, v); put32be(off + 4, v)
    }

    /** 目录记录日期：7 字节（年-1900/月/日/时/分/秒/GMT 偏移）。 */
    private fun recordDate(): ByteArray {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        return byteArrayOf(
            (c[Calendar.YEAR] - 1900).toByte(),
            (c[Calendar.MONTH] + 1).toByte(),
            c[Calendar.DAY_OF_MONTH].toByte(),
            c[Calendar.HOUR_OF_DAY].toByte(),
            c[Calendar.MINUTE].toByte(),
            c[Calendar.SECOND].toByte(),
            0,
        )
    }

    /** PVD 日期：17 字节（16 位数字 + 1 字节 GMT 偏移）。 */
    private fun volumeDate(): ByteArray {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val s = "%04d%02d%02d%02d%02d%02d00".format(
            c[Calendar.YEAR], c[Calendar.MONTH] + 1, c[Calendar.DAY_OF_MONTH],
            c[Calendar.HOUR_OF_DAY], c[Calendar.MINUTE], c[Calendar.SECOND],
        )
        return s.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    }
}
