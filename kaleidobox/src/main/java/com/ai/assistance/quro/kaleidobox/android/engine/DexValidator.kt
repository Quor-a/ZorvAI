package com.ai.assistance.quro.kaleidobox.android.engine

import com.ai.assistance.quro.kaleidobox.core.KaleidoException

/**
 * 外部 jvm_dex 包加载前的 dex 完整性闸门。
 *
 * 为什么必须自己先校验、不能直接丢给 [dalvik.system.DexClassLoader]：
 * ART 对动态加载的 dex 在**后台校验线程**里异步校验，遇到坏 dex（截断 / magic 错 / 字节序错 /
 * section 越界 / 内部索引越界）会直接 `SIGBUS` 杀进程，且这个 native 信号**无法用 try/catch 捕获**。
 * 所以要在交给 ART 之前，用纯字节级检查拦掉所有结构损坏的 dex，并以可捕获的
 * [KaleidoException.Engine] 失败——坏用户包绝不能拖垮宿主。
 *
 * 本类做**完整结构校验**：头部 + 全部 section 边界 + 所有 type_idx / string_idx / proto_idx /
 * field_idx / method_idx 索引越界 + class_data / type_list / code_off 边界。这些都是 ART 校验器
 * 在后台线程里会解引用的位置，任意一个越界都会让 ART 读非法内存 → SIGBUS。
 *
 * 若 dex 通过本类校验、ART 仍在校验期崩，属于 ART 对非法字节码的深水 bug，根治是把外部包放到
 * ISOLATED_PROCESS 跑（见 [SandboxLevel.ISOLATED_PROCESS]）；当前进程内路径以"挡住坏 dex"为第一防线。
 */
object DexValidator {

    /** dex 头部各字段偏移（Dalvik Executable 格式）。 */
    private const val OFF_MAGIC = 0
    private const val OFF_VERSION = 4
    private const val OFF_FILE_SIZE = 32
    private const val OFF_HEADER_SIZE = 36
    private const val OFF_ENDIAN_TAG = 40
    private const val OFF_MAP_OFF = 52
    private const val MIN_HEADER = 112

    // section 表（header 偏移）
    private const val OFF_STRING_IDS_SIZE = 56
    private const val OFF_STRING_IDS_OFF = 60
    private const val OFF_TYPE_IDS_SIZE = 64
    private const val OFF_TYPE_IDS_OFF = 68
    private const val OFF_PROTO_IDS_SIZE = 72
    private const val OFF_PROTO_IDS_OFF = 76
    private const val OFF_FIELD_IDS_SIZE = 80
    private const val OFF_FIELD_IDS_OFF = 84
    private const val OFF_METHOD_IDS_SIZE = 88
    private const val OFF_METHOD_IDS_OFF = 92
    private const val OFF_CLASS_DEFS_SIZE = 96
    private const val OFF_CLASS_DEFS_OFF = 100
    private const val OFF_DATA_SIZE = 104
    private const val OFF_DATA_OFF = 108

    // 段项字节大小
    private const val SZ_STRING_ID = 4
    private const val SZ_TYPE_ID = 4
    private const val SZ_PROTO_ID = 12
    private const val SZ_FIELD_ID = 8
    private const val SZ_METHOD_ID = 8
    private const val SZ_CLASS_DEF = 24

    fun assertLoadable(dex: ByteArray, expectClass: String) {
        headerChecks(dex, expectClass)
        structureChecks(dex, expectClass)
    }

    // ---------------- 头部校验 ----------------

    private fun headerChecks(dex: ByteArray, expectClass: String) {
        if (dex.size < MIN_HEADER)
            throw KaleidoException.Engine(
                "dex 过小（${dex.size} 字节，应有 ≥$MIN_HEADER），不是合法 .dex 文件（包期望类 $expectClass）"
            )

        if (!(dex[OFF_MAGIC] == 'd'.code.toByte() && dex[OFF_MAGIC + 1] == 'e'.code.toByte()
                    && dex[OFF_MAGIC + 2] == 'x'.code.toByte() && dex[OFF_MAGIC + 3] == '\n'.code.toByte())
        )
            throw KaleidoException.Engine("dex magic 非法（应以 'dex\\n' 开头），疑似不是 .dex 文件（期望类 $expectClass）")
        if (dex[OFF_VERSION + 3] != 0.toByte())
            throw KaleidoException.Engine("dex 版本号后必须是 NUL 字节，文件损坏（期望类 $expectClass）")

        val headerSize = readLe32(dex, OFF_HEADER_SIZE)
        if (headerSize != MIN_HEADER)
            throw KaleidoException.Engine("dex header_size=$headerSize 非法（应为 $MIN_HEADER），文件损坏（期望类 $expectClass）")

        if (dex[OFF_ENDIAN_TAG] != 0x78.toByte() || dex[OFF_ENDIAN_TAG + 1] != 0x56.toByte()
            || dex[OFF_ENDIAN_TAG + 2] != 0x34.toByte() || dex[OFF_ENDIAN_TAG + 3] != 0x12.toByte()
        )
            throw KaleidoException.Engine("dex endian_tag 非法，文件损坏或字节序错误（期望类 $expectClass）")

        val fileSize = readLe32(dex, OFF_FILE_SIZE)
        if (fileSize != dex.size)
            throw KaleidoException.Engine(
                "dex file_size($fileSize) 与实际大小(${dex.size})不符，文件被截断/损坏（期望类 $expectClass）"
            )

        val mapOff = readLe32(dex, OFF_MAP_OFF)
        if (mapOff < MIN_HEADER || mapOff >= dex.size)
            throw KaleidoException.Engine("dex map_off($mapOff) 越界，文件损坏（期望类 $expectClass）")

        val ver = String(dex.copyOfRange(OFF_VERSION, OFF_VERSION + 3), Charsets.US_ASCII)
        if (ver < "035")
            throw KaleidoException.Engine("dex 版本 $ver 过旧，请用 d8 重新编译（期望类 $expectClass）")
    }

    // ---------------- 结构校验（拦住 SIGBUS 的核心） ----------------

    private fun structureChecks(dex: ByteArray, expectClass: String) {
        val fileSize = dex.size
        val stringIdsSize = readLe32(dex, OFF_STRING_IDS_SIZE)
        val stringIdsOff = readLe32(dex, OFF_STRING_IDS_OFF)
        val typeIdsSize = readLe32(dex, OFF_TYPE_IDS_SIZE)
        val typeIdsOff = readLe32(dex, OFF_TYPE_IDS_OFF)
        val protoIdsSize = readLe32(dex, OFF_PROTO_IDS_SIZE)
        val protoIdsOff = readLe32(dex, OFF_PROTO_IDS_OFF)
        val fieldIdsSize = readLe32(dex, OFF_FIELD_IDS_SIZE)
        val fieldIdsOff = readLe32(dex, OFF_FIELD_IDS_OFF)
        val methodIdsSize = readLe32(dex, OFF_METHOD_IDS_SIZE)
        val methodIdsOff = readLe32(dex, OFF_METHOD_IDS_OFF)
        val classDefsSize = readLe32(dex, OFF_CLASS_DEFS_SIZE)
        val classDefsOff = readLe32(dex, OFF_CLASS_DEFS_OFF)
        val dataSize = readLe32(dex, OFF_DATA_SIZE)
        val dataOff = readLe32(dex, OFF_DATA_OFF)

        // 1) 各段边界：size>0 时 off 必须在 header 之后，且 off+size*item 不越过 fileSize
        checkSection("string_ids", stringIdsOff, stringIdsSize, SZ_STRING_ID, fileSize, expectClass)
        checkSection("type_ids", typeIdsOff, typeIdsSize, SZ_TYPE_ID, fileSize, expectClass)
        checkSection("proto_ids", protoIdsOff, protoIdsSize, SZ_PROTO_ID, fileSize, expectClass)
        checkSection("field_ids", fieldIdsOff, fieldIdsSize, SZ_FIELD_ID, fileSize, expectClass)
        checkSection("method_ids", methodIdsOff, methodIdsSize, SZ_METHOD_ID, fileSize, expectClass)
        checkSection("class_defs", classDefsOff, classDefsSize, SZ_CLASS_DEF, fileSize, expectClass)

        // 2) data 段边界
        if (dataSize > 0) {
            if (dataOff < MIN_HEADER || dataOff.toLong() + dataSize > fileSize)
                throw KaleidoException.Engine("dex data 段越界（off=$dataOff size=$dataSize > fileSize=$fileSize，期望类 $expectClass）")
        }

        // 3) string_ids：每个 string_data_off 必须在文件内，且 ULEB128 长度可解析
        for (i in 0 until stringIdsSize) {
            val sdo = readLe32(dex, stringIdsOff + i * SZ_STRING_ID)
            if (sdo <= 0 || sdo >= fileSize)
                throw KaleidoException.Engine("dex string_ids[$i].string_data_off=$sdo 越界（期望类 $expectClass）")
            readUleb128(dex, sdo, fileSize) // 越界会抛 IndexOutOfBoundsException
        }

        // 4) type_ids：descriptor_idx 必须在 string_ids 范围内（SIGBUS 高发点）
        for (i in 0 until typeIdsSize) {
            val idx = readLe32(dex, typeIdsOff + i * SZ_TYPE_ID)
            if (idx < 0 || idx >= stringIdsSize)
                throw KaleidoException.Engine("dex type_ids[$i].descriptor_idx=$idx 越界（string_ids=$stringIdsSize，期望类 $expectClass）")
        }

        // 5) proto_ids：shorty_idx<string, return_type_idx<type, parameters_off 是 type_list
        for (i in 0 until protoIdsSize) {
            val base = protoIdsOff + i * SZ_PROTO_ID
            val shorty = readLe32(dex, base)
            val ret = readLe32(dex, base + 4)
            val paramsOff = readLe32(dex, base + 8)
            if (shorty < 0 || shorty >= stringIdsSize)
                throw KaleidoException.Engine("dex proto_ids[$i].shorty_idx=$shorty 越界（期望类 $expectClass）")
            if (ret < 0 || ret >= typeIdsSize)
                throw KaleidoException.Engine("dex proto_ids[$i].return_type_idx=$ret 越界（期望类 $expectClass）")
            if (paramsOff != 0) validateTypeList(dex, paramsOff, fileSize, typeIdsSize, expectClass, "proto_ids[$i].parameters")
        }

        // 6) field_ids：class_idx<type, type_idx<type, name_idx<string
        for (i in 0 until fieldIdsSize) {
            val base = fieldIdsOff + i * SZ_FIELD_ID
            val classIdx = readU16(dex, base)
            val typeIdx = readU16(dex, base + 2)
            val nameIdx = readLe32(dex, base + 4)
            if (classIdx >= typeIdsSize) throw KaleidoException.Engine("dex field_ids[$i].class_idx=$classIdx 越界（期望类 $expectClass）")
            if (typeIdx >= typeIdsSize) throw KaleidoException.Engine("dex field_ids[$i].type_idx=$typeIdx 越界（期望类 $expectClass）")
            if (nameIdx < 0 || nameIdx >= stringIdsSize) throw KaleidoException.Engine("dex field_ids[$i].name_idx=$nameIdx 越界（期望类 $expectClass）")
        }

        // 7) method_ids：class_idx<type, proto_idx<proto, name_idx<string
        for (i in 0 until methodIdsSize) {
            val base = methodIdsOff + i * SZ_METHOD_ID
            val classIdx = readU16(dex, base)
            val protoIdx = readU16(dex, base + 2)
            val nameIdx = readLe32(dex, base + 4)
            if (classIdx >= typeIdsSize) throw KaleidoException.Engine("dex method_ids[$i].class_idx=$classIdx 越界（期望类 $expectClass）")
            if (protoIdx >= protoIdsSize) throw KaleidoException.Engine("dex method_ids[$i].proto_idx=$protoIdx 越界（期望类 $expectClass）")
            if (nameIdx < 0 || nameIdx >= stringIdsSize) throw KaleidoException.Engine("dex method_ids[$i].name_idx=$nameIdx 越界（期望类 $expectClass）")
        }

        // 8) class_defs：类索引 / 父类索引 / 接口 type_list / class_data / code_off
        for (i in 0 until classDefsSize) {
            val base = classDefsOff + i * SZ_CLASS_DEF
            val classIdx = readU16(dex, base)
            val superIdx = readU16(dex, base + 2)
            val interfacesOff = readLe32(dex, base + 6)
            val sourceFileIdx = readU16(dex, base + 10)
            val annotationsOff = readLe32(dex, base + 12)
            val classDataOff = readLe32(dex, base + 16)
            val staticValuesOff = readLe32(dex, base + 20)
            if (classIdx < 0 || classIdx >= typeIdsSize)
                throw KaleidoException.Engine("dex class_defs[$i].class_idx=$classIdx 越界（期望类 $expectClass）")
            if (superIdx != 0xFFFF && superIdx >= typeIdsSize)
                throw KaleidoException.Engine("dex class_defs[$i].superclass_idx=$superIdx 越界（期望类 $expectClass）")
            if (interfacesOff != 0) validateTypeList(dex, interfacesOff, fileSize, typeIdsSize, expectClass, "class_defs[$i].interfaces")
            if (sourceFileIdx != 0xFFFF && sourceFileIdx >= stringIdsSize)
                throw KaleidoException.Engine("dex class_defs[$i].source_file_idx=$sourceFileIdx 越界（期望类 $expectClass）")
            if (annotationsOff != 0 && (annotationsOff < MIN_HEADER || annotationsOff >= fileSize))
                throw KaleidoException.Engine("dex class_defs[$i].annotations_off=$annotationsOff 越界（期望类 $expectClass）")
            if (staticValuesOff != 0 && (staticValuesOff < MIN_HEADER || staticValuesOff >= fileSize))
                throw KaleidoException.Engine("dex class_defs[$i].static_values_off=$staticValuesOff 越界（期望类 $expectClass）")
            if (classDataOff != 0)
                validateClassData(dex, classDataOff, fileSize, typeIdsSize, methodIdsSize, expectClass, "class_defs[$i].class_data")
        }
    }

    /** 校验一个 type_list 结构：off 处是 size(uint)，其后 size 个 ushort 的 type_idx，逐一越界检查。 */
    private fun validateTypeList(
        dex: ByteArray, off: Int, fileSize: Int, typeIdsSize: Int, expectClass: String, where: String,
    ) {
        if (off < MIN_HEADER || off >= fileSize)
            throw KaleidoException.Engine("dex $where type_list off=$off 越界（期望类 $expectClass）")
        val size = readLe32(dex, off)
        val end = off.toLong() + 4 + size.toLong() * 2
        if (end > fileSize)
            throw KaleidoException.Engine("dex $where type_list 越界（size=$size，end=$end > fileSize=$fileSize，期望类 $expectClass）")
        for (i in 0 until size) {
            val typeIdx = readU16(dex, off + 4 + i * 2)
            if (typeIdx >= typeIdsSize)
                throw KaleidoException.Engine("dex $where type_list[$i].type_idx=$typeIdx 越界（type_ids=$typeIdsSize，期望类 $expectClass）")
        }
    }

    /** 校验 class_data_item：4 个 uleb128 计数 + 各 field/method 项；method 的 method_idx 与 code_off 越界检查。 */
    private fun validateClassData(
        dex: ByteArray, off: Int, fileSize: Int, typeIdsSize: Int, methodIdsSize: Int, expectClass: String, where: String,
    ) {
        if (off < MIN_HEADER || off >= fileSize)
            throw KaleidoException.Engine("dex $where off=$off 越界（期望类 $expectClass）")
        var p = off
        val (sf, p1) = readUleb128(dex, p, fileSize); p = p1
        val (inf, p2) = readUleb128(dex, p, fileSize); p = p2
        val (dm, p3) = readUleb128(dex, p, fileSize); p = p3
        val (vm, p4) = readUleb128(dex, p, fileSize); p = p4
        // 字段项：field_idx_diff(uleb) + access_flags(uleb)
        repeat(sf + inf) {
            val (_, a) = readUleb128(dex, p, fileSize); p = a
            val (_, b) = readUleb128(dex, p, fileSize); p = b
        }
        // 方法项：method_idx_diff(uleb) + access_flags(uleb) + code_off(uleb)
        var lastIdx = 0
        repeat(dm + vm) {
            val (diff, a) = readUleb128(dex, p, fileSize); p = a
            val (_, b) = readUleb128(dex, p, fileSize); p = b
            val (codeOff, c) = readUleb128(dex, p, fileSize); p = c
            lastIdx += diff
            if (lastIdx < 0 || lastIdx >= methodIdsSize)
                throw KaleidoException.Engine("dex $where method_idx=$lastIdx 越界（method_ids=$methodIdsSize，期望类 $expectClass）")
            if (codeOff != 0 && (codeOff < MIN_HEADER || codeOff >= fileSize))
                throw KaleidoException.Engine("dex $where method code_off=$codeOff 越界（期望类 $expectClass）")
        }
    }

    private fun checkSection(name: String, off: Int, size: Int, itemSize: Int, fileSize: Int, expectClass: String) {
        if (size == 0) {
            if (off != 0)
                throw KaleidoException.Engine("dex $name 段 size=0 但 off=$off≠0（期望类 $expectClass）")
            return
        }
        if (off < MIN_HEADER)
            throw KaleidoException.Engine("dex $name 段 off=$off 落在 header 内（期望类 $expectClass）")
        val end = off.toLong() + size.toLong() * itemSize
        if (end > fileSize)
            throw KaleidoException.Engine("dex $name 段越界（off=$off + size=$size*item=$itemSize = $end > fileSize=$fileSize，期望类 $expectClass）")
    }

    private fun readLe32(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > b.size) throw IndexOutOfBoundsException("dex 读 le32 越界 @$off")
        return (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
    }

    private fun readU16(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > b.size) throw IndexOutOfBoundsException("dex 读 u16 越界 @$off")
        return (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
    }

    private fun readUleb128(b: ByteArray, off: Int, fileSize: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var p = off
        while (p < fileSize) {
            val byte = b[p].toInt() and 0xff
            p++
            result = result or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return result to p
            shift += 7
            if (shift >= 32) throw IndexOutOfBoundsException("dex uleb128 编码过长 @$off")
        }
        throw IndexOutOfBoundsException("dex uleb128 越界 @$off")
    }
}
