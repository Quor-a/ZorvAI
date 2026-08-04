package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.model.QuroGgufNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `QuroLocalEngineNative.resolveLlamaModelFileStatic()` 的真实文件系统单测（full 风味）。
 *
 * 背景：llama.cpp 本地模型「导入成功、点加载却静默失败、聊天被门禁拦」的根因是
 * **导入侧递归（walkTopDown）、解析侧只扫顶层（listFiles）** 的不对称。本测试用
 * [TemporaryFolder] 在磁盘上构造真实目录布局，直接驱动解析函数，不 mock 任何 IO。
 *
 * 覆盖：顶层快路径（零回归）、一层/多层子目录（原始 Bug 场景）、唯一 .gguf 兜底、
 * 优先级顺序、各类必须返回 null 的失败场景，以及 **GGUF 分片模型** 这一未在修复
 * 说明中覆盖的真实布局。
 */
class LlamaModelResolveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    /** 建一个内容合法（GGUF 魔数开头）的文件，父目录自动创建。 */
    private fun gguf(relative: String): File {
        val f = File(root, relative)
        f.parentFile?.mkdirs()
        f.writeBytes(byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(64))
        return f
    }

    private fun plain(relative: String, text: String = "x"): File {
        val f = File(root, relative)
        f.parentFile?.mkdirs()
        f.writeText(text)
        return f
    }

    private fun resolve(modelName: String, folder: String = root.absolutePath): File? =
        QuroLocalEngineNative.resolveLlamaModelFileStatic(folder, modelName)

    @Before
    fun setUp() {
        root = tmp.newFolder("model-root")
        // 前置断言：确认被测的是产品代码里那个真方法，而不是测试里同名的辅助函数。
        // 注：internal 成员在字节码里会被 mangle 成 `名字$app_fullDebug`，故用前缀匹配；
        // 同名候选里可能混有编译器生成的桥接/合成方法，因此按签名（2×String → File）精确挑选。
        val candidates = QuroLocalEngineNative.Companion::class.java.declaredMethods
            .filter { it.name.startsWith("resolveLlamaModelFileStatic") }
        assertTrue(
            "未在 QuroLocalEngineNative.Companion 上找到 resolveLlamaModelFileStatic，被测目标可能已改名。" +
                "实际候选=${candidates.map { c -> "${c.name}(${c.parameterTypes.joinToString { it.simpleName }}):${c.returnType.simpleName}" }}",
            candidates.isNotEmpty()
        )
        val real = candidates.firstOrNull {
            it.returnType == File::class.java &&
                it.parameterTypes.toList() == listOf(String::class.java, String::class.java)
        }
        assertNotNull(
            "找不到签名为 (String, String): File 的真方法。实际候选=" +
                candidates.map { c -> "${c.name}(${c.parameterTypes.joinToString { it.simpleName }}):${c.returnType.simpleName}" },
            real
        )
    }

    // ---------------------------------------------------------------------------------------
    // 必须解析成功
    // ---------------------------------------------------------------------------------------

    /** 顶层同名 .gguf —— 走快路径，证明老行为零回归。 */
    @Test
    fun topLevelExactStem_hitsFastPath() {
        val f = gguf("qwen2.5-1.5b-q4.gguf")
        assertEquals(f.absolutePath, resolve("qwen2.5-1.5b-q4")?.absolutePath)
    }

    /** modelName 已带扩展名，顶层直接命中（第二条快路径）。 */
    @Test
    fun topLevelNameAlreadyHasExtension_hitsFastPath() {
        val f = gguf("qwen.gguf")
        assertEquals(f.absolutePath, resolve("qwen.gguf")?.absolutePath)
    }

    /** 一层子目录 —— 本 Bug 的原始复现场景（HuggingFace 快照式布局）。 */
    @Test
    fun oneLevelSubdir_resolvesAfterFix() {
        val f = gguf("snapshots/abc123/qwen.gguf")
        assertEquals(
            "子目录里的 .gguf 必须能解析到（导入侧 walkTopDown 已能发现它）",
            f.absolutePath, resolve("qwen")?.absolutePath
        )
    }

    /** 三层深的子目录同样必须命中（递归无深度限制）。 */
    @Test
    fun deepNestedSubdir_resolves() {
        val f = gguf("a/b/c/deep-model.gguf")
        assertEquals(f.absolutePath, resolve("deep-model")?.absolutePath)
    }

    /** 大小写不同的扩展名 / 名称都应命中（ignoreCase）。 */
    @Test
    fun caseInsensitiveNameAndExtension_resolves() {
        val f = gguf("sub/Qwen-Chat.GGUF")
        assertEquals(f.absolutePath, resolve("qwen-chat")?.absolutePath)
    }

    /** 目录内只有一个 .gguf，但文件名与 cfg.model 完全对不上 → 兜底直接用它（历史脏配置）。 */
    @Test
    fun singleGgufWithMismatchedName_fallsBackToIt() {
        val f = gguf("nested/totally-other-name.gguf")
        assertEquals(
            "唯一 .gguf 无歧义，应兜底选中",
            f.absolutePath, resolve("name-recorded-long-ago")?.absolutePath
        )
    }

    /**
     * 优先级：精确 stem 同名 > 带扩展名同名。
     * 构造 modelName = "m.gguf"：
     *  - `p1/m.gguf.gguf` → stem = "m.gguf" == modelName（第一优先级）
     *  - `p2/m.gguf`      → name = "m.gguf" == modelName（第二优先级）
     * 两者都在子目录，避免走顶层快路径。
     */
    @Test
    fun priority_stemMatchBeatsFullNameMatch() {
        val stemHit = gguf("p1/m.gguf.gguf")
        gguf("p2/m.gguf")
        assertEquals(
            "工程师声称的优先级是「精确同名 stem → 带扩展名同名」，实际应命中 stem 分支",
            stemHit.absolutePath, resolve("m.gguf")?.absolutePath
        )
    }

    /** 多个 .gguf 但其中一个精确匹配 → 必须选中匹配那个，不能被兜底或顺序影响。 */
    @Test
    fun multipleGguf_exactMatchWins() {
        gguf("sub/other-a.gguf")
        val want = gguf("sub/wanted.gguf")
        gguf("sub/other-b.gguf")
        assertEquals(want.absolutePath, resolve("wanted")?.absolutePath)
    }

    // ---------------------------------------------------------------------------------------
    // 必须解析失败（返回 null）
    // ---------------------------------------------------------------------------------------

    @Test
    fun emptyDirectory_returnsNull() {
        assertNull(resolve("anything"))
    }

    @Test
    fun nonExistentDirectory_returnsNull() {
        assertNull(resolve("anything", File(root, "no-such-dir").absolutePath))
    }

    @Test
    fun pathIsFileNotDirectory_returnsNull() {
        val f = gguf("solo.gguf")
        assertNull(resolve("solo", f.absolutePath))
    }

    @Test
    fun blankFolder_returnsNull() {
        assertNull(resolve("anything", ""))
    }

    @Test
    fun onlyNonGgufFiles_returnsNull() {
        plain("readme.md")
        plain("sub/config.json")
        plain("sub/tokenizer.model")
        assertNull(resolve("qwen"))
    }

    /** 多个 .gguf 且没有一个匹配 cfg.model → singleOrNull 不成立 → null。 */
    @Test
    fun multipleGgufNoneMatching_returnsNull() {
        gguf("sub/alpha.gguf")
        gguf("sub/beta.gguf")
        assertNull(
            "多个候选且无一匹配时必须放弃，绝不能随便挑一个",
            resolve("gamma")
        )
    }

    // ---------------------------------------------------------------------------------------
    // GGUF 分片模型（F1 修复：归一化到首分片）
    //
    // llama.cpp 只接受**首分片**路径（内部按 split.count 自动找齐其余分片），传非首分片必失败。
    // 因此解析层必须把任何分片请求归一化到 -00001-of-。
    // ---------------------------------------------------------------------------------------

    /** 分片布局 + cfg.model 记的是基名 → 走 2b 兜底定位首分片。 */
    @Test
    fun shardedGguf_baseNameRecorded_resolvesToFirstShard() {
        val first = gguf("model-00001-of-00003.gguf")
        gguf("model-00002-of-00003.gguf")
        gguf("model-00003-of-00003.gguf")
        assertEquals(
            "纯分片目录 + 基名配置应兜底到首分片",
            first.absolutePath, resolve("model")?.absolutePath
        )
    }

    /** 请求名就是非首分片 → 必须归一化到首分片，而不是原样返回。 */
    @Test
    fun shardedGguf_nonFirstShardRequested_normalizedToFirstShard() {
        val first = gguf("model-00001-of-00003.gguf")
        gguf("model-00002-of-00003.gguf")
        gguf("model-00003-of-00003.gguf")
        assertEquals(
            "请求第 2 片也必须归一化到首分片，否则 llama.cpp 加载必失败",
            first.absolutePath, resolve("model-00002-of-00003")?.absolutePath
        )
        assertEquals(first.absolutePath, resolve("model-00003-of-00003")?.absolutePath)
    }

    /**
     * 🔑 **老配置迁移安全性**（本次修复最关键的兼容性属性，工程师清单里没提）。
     *
     * 在 F1 之前导入的分片模型，`quro_local_models.json` 里已经持久化了 N 个分片名，
     * 且 `modelNames.first()` 可能是任意一片（ext4 哈希序）。这些记录**不会**被重新导入，
     * 导入侧的 `collapseShards` 对它们无效。必须靠解析侧的归一化兜住，
     * 否则老用户升级后依旧加载失败。
     */
    @Test
    fun shardedGguf_legacyPersistedNonFirstShardName_stillResolves() {
        val first = gguf("model-00001-of-00004.gguf")
        gguf("model-00002-of-00004.gguf")
        gguf("model-00003-of-00004.gguf")
        gguf("model-00004-of-00004.gguf")
        // 模拟升级前写进 json 的 modelNames（顺序随机，first() 落在第 3 片）
        val legacyModelNames = listOf(
            "model-00003-of-00004", "model-00001-of-00004",
            "model-00004-of-00004", "model-00002-of-00004",
        )
        val resolved = resolve(legacyModelNames.first())
        assertEquals(
            "老记录里存的非首分片名必须仍能解析到首分片（升级不需要重新导入）",
            first.absolutePath, resolved?.absolutePath
        )
    }

    /** 分片位于子目录（HuggingFace 快照布局 + 分片，两个缺口叠加）。 */
    @Test
    fun shardedGguf_inSubdirectory_resolvesToFirstShard() {
        val first = gguf("snapshots/abc/model-00001-of-00003.gguf")
        gguf("snapshots/abc/model-00002-of-00003.gguf")
        gguf("snapshots/abc/model-00003-of-00003.gguf")
        assertEquals(first.absolutePath, resolve("model")?.absolutePath)
        assertEquals(first.absolutePath, resolve("model-00002-of-00003")?.absolutePath)
    }

    /**
     * 端到端复刻「导入 → 加载」链路（F1② 导入侧 collapseShards + F1① 解析侧归一化）。
     * 关键属性：结果**确定**，不再依赖 readdir 顺序。
     */
    @Test
    fun shardedGguf_importThenLoadPipeline_deterministicallyPicksFirstShard() {
        val first = gguf("sharded/model-00001-of-00004.gguf")
        gguf("sharded/model-00002-of-00004.gguf")
        gguf("sharded/model-00003-of-00004.gguf")
        gguf("sharded/model-00004-of-00004.gguf")
        // —— 复刻导入侧 QuroModelConfigScreen.kt:581-586 ——
        val modelNames = QuroGgufNaming.collapseShards(
            root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
                .map { QuroGgufNaming.stem(it.name) }
                .toList()
        )
        assertEquals("一组分片对外只应暴露 1 个模型名（基名）", listOf("model"), modelNames)
        // —— 复刻加载侧 LocalModelSessionHolder.kt:185-186 ——
        val resolved = resolve(modelNames.first())
        assertEquals(
            "导入+加载全链路必须确定性地落到首分片",
            first.absolutePath, resolved?.absolutePath
        )
    }

    /** 打乱 readdir 顺序后结果不变 —— 证明不再依赖文件系统遍历顺序。 */
    @Test
    fun shardedGguf_collapseIsOrderIndependent() {
        val stems = listOf("model-00003-of-00003", "model-00001-of-00003", "model-00002-of-00003")
        assertEquals(listOf("model"), QuroGgufNaming.collapseShards(stems))
        assertEquals(listOf("model"), QuroGgufNaming.collapseShards(stems.reversed()))
        assertEquals(listOf("model"), QuroGgufNaming.collapseShards(stems.shuffled()))
    }

    /** 两组不同的分片模型混在同一目录 → 有歧义，必须放弃（不能瞎猜）。 */
    @Test
    fun twoDifferentShardGroupsInOneDir_returnsNull() {
        gguf("a-00001-of-00002.gguf")
        gguf("a-00002-of-00002.gguf")
        gguf("b-00001-of-00002.gguf")
        gguf("b-00002-of-00002.gguf")
        assertNull(
            "两组分片基名不同（bases.size != 1），无法判定要哪个，必须返回 null",
            resolve("unknown-name")
        )
        // 但显式指名某一组时仍应归一化到该组首片
        assertEquals(
            File(root, "b-00001-of-00002.gguf").absolutePath,
            resolve("b-00002-of-00002")?.absolutePath
        )
    }

    /**
     * 分片 + 一个非分片 `.gguf`（多模态常见：主模型分片 + `mmproj-*.gguf` 投影器）。
     * `shardFiles.size == all.size` 不成立 → 2b 兜底不生效 → 落到 singleOrNull → null。
     * 这是**刻意保守**（有歧义就不猜），记录实际行为。
     */
    @Test
    fun shardsPlusNonShardGguf_baseNameRecorded_returnsNull() {
        gguf("model-00001-of-00002.gguf")
        gguf("model-00002-of-00002.gguf")
        gguf("mmproj-model-f16.gguf")
        assertNull(
            "混合目录有歧义，2b 兜底刻意不生效",
            resolve("model")
        )
        // 显式指名分片仍可归一化命中（快路径）
        assertEquals(
            File(root, "model-00001-of-00002.gguf").absolutePath,
            resolve("model-00002-of-00002")?.absolutePath
        )
    }

    /** 只下载了一半的分片（首片缺失）→ 无法归一化，退回唯一候选，交给原生层报错。 */
    @Test
    fun incompleteShardSet_missingFirstShard_fallsBackToSingleCandidate() {
        val only = gguf("model-00002-of-00003.gguf")
        assertEquals(
            "首片不存在时归一化落空，退回 singleOrNull（原生层会给出真实错误）",
            only.absolutePath, resolve("model-00002-of-00003")?.absolutePath
        )
    }

    /** 非分片模型名不得被分片正则误伤（老行为零回归）。 */
    @Test
    fun nonShardNamesAreUnaffectedByNormalization() {
        val f = gguf("qwen2.5-1.5b-instruct-q4_k_m.gguf")
        assertEquals(f.absolutePath, resolve("qwen2.5-1.5b-instruct-q4_k_m")?.absolutePath)
    }

    // ---------------------------------------------------------------------------------------
    // 快路径的边界（direct2 不校验扩展名）
    // ---------------------------------------------------------------------------------------

    /**
     * 顶层存在一个与 modelName 同名的**非 .gguf 普通文件**时，快路径 `File(dir, modelName)`
     * 会直接返回它，从而遮蔽子目录里真正的 .gguf。记录实际行为。
     */
    @Test
    fun fastPath_topLevelNonGgufSameName_shadowsRealGguf() {
        val decoy = plain("qwen") // 同名但不是 gguf
        val real = gguf("sub/qwen.gguf")
        val got = resolve("qwen")
        assertEquals(
            "快路径 direct2 不校验扩展名，会先命中同名非 gguf 文件",
            decoy.absolutePath, got?.absolutePath
        )
        assertTrue("真正的 gguf 存在却被遮蔽", real.isFile)
    }

    /** 同名的是**目录**而不是文件时，不应被快路径误命中（isFile 守卫生效）。 */
    @Test
    fun fastPath_topLevelDirectoryWithSameName_isSkipped() {
        File(root, "qwen").mkdirs()
        val real = gguf("qwen/qwen.gguf")
        assertEquals(real.absolutePath, resolve("qwen")?.absolutePath)
    }
}
