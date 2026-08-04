package com.ai.assistance.quro.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroGgufNaming] 的纯逻辑单测（main 源码集，两个风味共用）。
 *
 * 这是本轮新引入的**共享命名解析层**，导入侧与加载侧都依赖它。
 * 原 Bug 的根因正是「两侧各写一套扫描逻辑而不对称」，因此这层的正确性是防复发的关键：
 * 一旦它误判，两侧会**一起**错，且错得一致、更难发现。
 */
class QuroGgufNamingTest {

    // ---------------------------------------------------------------------------------------
    // stem()
    // ---------------------------------------------------------------------------------------

    @Test
    fun stem_stripsExtensionCaseInsensitively() {
        assertEquals("model", QuroGgufNaming.stem("model.gguf"))
        assertEquals("model", QuroGgufNaming.stem("model.GGUF"))
        assertEquals("model", QuroGgufNaming.stem("model.GgUf"))
    }

    @Test
    fun stem_withoutExtension_returnsAsIs() {
        assertEquals("model", QuroGgufNaming.stem("model"))
        assertEquals("", QuroGgufNaming.stem(""))
    }

    /** 只在**结尾**剥扩展名，名字中间出现 .gguf 不能被误删。 */
    @Test
    fun stem_onlyStripsTrailingExtension() {
        assertEquals("a.gguf.b", QuroGgufNaming.stem("a.gguf.b"))
        assertEquals("model.gguf", QuroGgufNaming.stem("model.gguf.gguf"))
    }

    /** 文件名恰好就是 ".gguf" → stem 为空串，不得抛异常。 */
    @Test
    fun stem_bareExtension_yieldsEmpty() {
        assertEquals("", QuroGgufNaming.stem(".gguf"))
    }

    // ---------------------------------------------------------------------------------------
    // shardBase()
    // ---------------------------------------------------------------------------------------

    @Test
    fun shardBase_extractsBaseName() {
        assertEquals("model", QuroGgufNaming.shardBase("model-00001-of-00003"))
        assertEquals("model", QuroGgufNaming.shardBase("model-00002-of-00003"))
        assertEquals("Qwen2.5-7B-q4_k_m", QuroGgufNaming.shardBase("Qwen2.5-7B-q4_k_m-00001-of-00009"))
    }

    /** 容错：传入带扩展名的完整文件名也应正确解析。 */
    @Test
    fun shardBase_toleratesFullFileName() {
        assertEquals("model", QuroGgufNaming.shardBase("model-00001-of-00003.gguf"))
        assertEquals("model", QuroGgufNaming.shardBase("model-00001-of-00003.GGUF"))
    }

    @Test
    fun shardBase_nonShardNames_returnNull() {
        assertNull(QuroGgufNaming.shardBase("model"))
        assertNull(QuroGgufNaming.shardBase("qwen2.5-1.5b-instruct-q4_k_m"))
        assertNull(QuroGgufNaming.shardBase(""))
    }

    /** 位数不是 5 位 → 不算标准分片命名，不得误判。 */
    @Test
    fun shardBase_wrongDigitCount_returnsNull() {
        assertNull("4 位不是标准命名", QuroGgufNaming.shardBase("model-0001-of-0003"))
        assertNull("6 位不是标准命名", QuroGgufNaming.shardBase("model-000001-of-000003"))
        assertNull(QuroGgufNaming.shardBase("model-1-of-3"))
    }

    /** 空基名（`-00001-of-00003`）必须返回 null，否则会折叠出空模型名。 */
    @Test
    fun shardBase_emptyBase_returnsNull() {
        assertNull(QuroGgufNaming.shardBase("-00001-of-00003"))
    }

    // ---------------------------------------------------------------------------------------
    // toFirstShard()
    // ---------------------------------------------------------------------------------------

    @Test
    fun toFirstShard_normalizesAnyShardToFirst() {
        assertEquals("model-00001-of-00003", QuroGgufNaming.toFirstShard("model-00001-of-00003"))
        assertEquals("model-00001-of-00003", QuroGgufNaming.toFirstShard("model-00002-of-00003"))
        assertEquals("model-00001-of-00003", QuroGgufNaming.toFirstShard("model-00003-of-00003"))
    }

    /** 总片数必须原样保留（llama.cpp 按 split.count 找齐后续分片）。 */
    @Test
    fun toFirstShard_preservesTotalCount() {
        assertEquals("m-00001-of-00042", QuroGgufNaming.toFirstShard("m-00017-of-00042"))
    }

    @Test
    fun toFirstShard_nonShardName_returnsUnchanged() {
        assertEquals("model", QuroGgufNaming.toFirstShard("model"))
        assertEquals("qwen2.5-1.5b-q4", QuroGgufNaming.toFirstShard("qwen2.5-1.5b-q4"))
        assertEquals("", QuroGgufNaming.toFirstShard(""))
    }

    /** 空基名不得被改写成 `-00001-of-...`（会解析到不存在的文件）。 */
    @Test
    fun toFirstShard_emptyBase_returnsUnchanged() {
        assertEquals("-00001-of-00003", QuroGgufNaming.toFirstShard("-00001-of-00003"))
        assertEquals("-00002-of-00003", QuroGgufNaming.toFirstShard("-00002-of-00003"))
    }

    /** 幂等性：已经是首分片名的，再归一化一次结果不变。 */
    @Test
    fun toFirstShard_isIdempotent() {
        listOf("model-00002-of-00003", "model", "", "-00001-of-00003").forEach {
            val once = QuroGgufNaming.toFirstShard(it)
            assertEquals("toFirstShard 必须幂等: $it", once, QuroGgufNaming.toFirstShard(once))
        }
    }

    // ---------------------------------------------------------------------------------------
    // isFirstShard()
    // ---------------------------------------------------------------------------------------

    @Test
    fun isFirstShard_onlyTrueForShardOne() {
        assertTrue(QuroGgufNaming.isFirstShard("model-00001-of-00003"))
        assertFalse(QuroGgufNaming.isFirstShard("model-00002-of-00003"))
        assertFalse(QuroGgufNaming.isFirstShard("model-00003-of-00003"))
    }

    @Test
    fun isFirstShard_nonShardName_isFalse() {
        assertFalse(QuroGgufNaming.isFirstShard("model"))
        assertFalse(QuroGgufNaming.isFirstShard(""))
    }

    /**
     * ⚠️ 0 基分片编号（`-00000-of-`）：llama.cpp 官方 `gguf-split` 从 00001 起编号，
     * 但个别第三方工具会从 00000 起。此时 `isFirstShard` 判 false，
     * 而 `toFirstShard` 会把它改写成不存在的 `-00001-of-`。记录实际行为。
     */
    @Test
    fun zeroBasedShardNumbering_isNotRecognizedAsFirst() {
        assertFalse(
            "0 基编号不被识别为首片（llama.cpp 标准是 1 基）",
            QuroGgufNaming.isFirstShard("model-00000-of-00003")
        )
        assertEquals("model-00001-of-00003", QuroGgufNaming.toFirstShard("model-00000-of-00003"))
    }

    // ---------------------------------------------------------------------------------------
    // collapseShards()
    // ---------------------------------------------------------------------------------------

    @Test
    fun collapseShards_oneGroupYieldsOneName() {
        assertEquals(
            listOf("model"),
            QuroGgufNaming.collapseShards(
                listOf("model-00001-of-00003", "model-00002-of-00003", "model-00003-of-00003")
            )
        )
    }

    /** 顺序无关：ext4 哈希序与 NTFS 字典序必须得到相同结果。 */
    @Test
    fun collapseShards_isOrderIndependentAndSorted() {
        val a = QuroGgufNaming.collapseShards(listOf("z-model", "a-model", "m-00002-of-00002", "m-00001-of-00002"))
        val b = QuroGgufNaming.collapseShards(listOf("m-00001-of-00002", "z-model", "m-00002-of-00002", "a-model"))
        assertEquals(a, b)
        assertEquals(listOf("a-model", "m", "z-model"), a)
    }

    @Test
    fun collapseShards_mixedShardAndPlainNames() {
        assertEquals(
            listOf("mmproj-model-f16", "model"),
            QuroGgufNaming.collapseShards(
                listOf("model-00001-of-00002", "model-00002-of-00002", "mmproj-model-f16")
            )
        )
    }

    @Test
    fun collapseShards_twoGroupsYieldTwoNames() {
        assertEquals(
            listOf("alpha", "beta"),
            QuroGgufNaming.collapseShards(
                listOf("beta-00002-of-00002", "alpha-00001-of-00003", "beta-00001-of-00002", "alpha-00002-of-00003")
            )
        )
    }

    @Test
    fun collapseShards_dedupesPlainNames() {
        assertEquals(listOf("model"), QuroGgufNaming.collapseShards(listOf("model", "model", "model")))
    }

    @Test
    fun collapseShards_emptyInput_returnsEmpty() {
        assertEquals(emptyList<String>(), QuroGgufNaming.collapseShards(emptyList()))
    }

    /** 空/空白名必须被滤掉，绝不能产出空模型名（会让 UI 出现空条目、load 解析空串）。 */
    @Test
    fun collapseShards_filtersBlankNames() {
        assertEquals(
            listOf("model"),
            QuroGgufNaming.collapseShards(listOf("", "   ", "model.gguf", ".gguf"))
        )
    }

    /** 接受完整文件名输入（导入侧已先调 stem，这里做容错双保险）。 */
    @Test
    fun collapseShards_toleratesFullFileNames() {
        assertEquals(
            listOf("model"),
            QuroGgufNaming.collapseShards(listOf("model-00001-of-00002.gguf", "model-00002-of-00002.gguf"))
        )
    }

    /**
     * 🔑 折叠出的基名必须能被 `toFirstShard` + 目录兜底重新解析回首分片 ——
     * 即「导入侧折叠」与「加载侧解析」是一对可逆闭环。这条属性一旦破裂，
     * 就会重演本次 Bug 的根因（两侧不对称）。
     */
    @Test
    fun collapseThenResolve_isConsistentRoundTrip() {
        val stems = listOf("model-00003-of-00003", "model-00001-of-00003", "model-00002-of-00003")
        val collapsed = QuroGgufNaming.collapseShards(stems)
        assertEquals(listOf("model"), collapsed)
        val wanted = collapsed.first()
        // 加载侧：基名不是分片名，toFirstShard 原样返回，靠目录内分片兜底（见 LlamaModelResolveTest）
        assertEquals("model", QuroGgufNaming.toFirstShard(wanted))
        assertNull("基名本身不应被误判为分片", QuroGgufNaming.shardBase(wanted))
        // 而每个原始分片名都能独立归一化到首片
        stems.forEach {
            assertEquals("model-00001-of-00003", QuroGgufNaming.toFirstShard(it))
            assertEquals(wanted, QuroGgufNaming.shardBase(it))
        }
    }
}
