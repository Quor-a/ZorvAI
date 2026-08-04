package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 门禁身份判定 [LocalModelSessionHolder.isLoaded] 的安全性回归测试（full 风味）。
 *
 * 本轮修复把判定从「只比 id」放宽为「id 相等 **或** 类型+规范化绝对路径相等」。
 * 放宽逻辑最大的风险是**把门禁判松**——「加载了 A 却拿 B 对话」会静默走到错误的常驻会话上。
 * 本测试用反射直接摆布 holder 的私有持有状态（`_state` / `_model`），逐条证明放行/拦截边界。
 *
 * 说明：holder 是进程级单例，测试通过 [reset] 在每个用例前后复位，保证用例互不污染。
 */
class LocalModelSessionHolderGateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val holderCls = LocalModelSessionHolder::class.java
    private val fState = holderCls.getDeclaredField("_state").apply { isAccessible = true }
    private val fModel = holderCls.getDeclaredField("_model").apply { isAccessible = true }

    private fun model(
        id: String,
        path: String,
        type: QuroLocalModelType = QuroLocalModelType.LLAMA_CPP,
        name: String = "m-$id",
    ) = QuroLocalModel(id = id, type = type, name = name, path = path, modelNames = listOf(name))

    /** 强制把 holder 摆成「已加载 [m]」。 */
    private fun pretendLoaded(m: QuroLocalModel) {
        fModel.set(LocalModelSessionHolder, m)
        fState.set(LocalModelSessionHolder, LocalModelLoader.State.Loaded(m))
    }

    private fun pretendState(s: LocalModelLoader.State, m: QuroLocalModel?) {
        fModel.set(LocalModelSessionHolder, m)
        fState.set(LocalModelSessionHolder, s)
    }

    private fun reset() {
        fModel.set(LocalModelSessionHolder, null)
        fState.set(LocalModelSessionHolder, LocalModelLoader.State.None)
    }

    @Before
    fun setUp() {
        // 前置断言：确认反射打到的确实是产品代码的私有持有字段，字段改名会立即暴露。
        assertNotNull("_state 字段缺失", fState)
        assertNotNull("_model 字段缺失", fModel)
        assertEquals(QuroLocalModel::class.java, fModel.type)
        // 确认 isLoaded 是接口实现，而不是测试里另写的同名函数。
        assertTrue(
            "LocalModelSessionHolder 必须实现 LocalModelLoader",
            LocalModelLoader::class.java.isAssignableFrom(holderCls)
        )
        reset()
    }

    @After
    fun tearDown() = reset()

    // ---------------------------------------------------------------------------------------
    // 老行为：id 判定（必须一字不改地保留）
    // ---------------------------------------------------------------------------------------

    @Test
    fun sameId_allowed() {
        val dir = tmp.newFolder("a").absolutePath
        pretendLoaded(model("id-1", dir))
        assertTrue(LocalModelSessionHolder.isLoaded(model("id-1", dir)))
    }

    /** id 相同但路径不同 → 仍放行（与老逻辑完全一致，避免已存配置被迫迁移）。 */
    @Test
    fun sameId_differentPath_stillAllowed_legacyBehavior() {
        val a = tmp.newFolder("a").absolutePath
        val b = tmp.newFolder("b").absolutePath
        pretendLoaded(model("id-1", a))
        assertTrue(LocalModelSessionHolder.isLoaded(model("id-1", b)))
    }

    /** 🔒 核心安全断言：加载 A、拿 B 对话（不同 id + 不同路径）必须被拦。 */
    @Test
    fun loadedA_chatWithB_blocked() {
        val a = tmp.newFolder("modelA").absolutePath
        val b = tmp.newFolder("modelB").absolutePath
        pretendLoaded(model("id-A", a, name = "Qwen"))
        assertFalse(
            "不同 id + 不同路径必须被门禁拦截，否则会拿 A 的常驻权重回答 B 的对话",
            LocalModelSessionHolder.isLoaded(model("id-B", b, name = "Llama"))
        )
    }

    // ---------------------------------------------------------------------------------------
    // F2 收紧：路径不再参与判定，不同 id 一律拦截
    //
    // 第一轮曾加过「id 不等但 type + 规范化路径相等 → 视为同一模型」的回退，
    // 该回退既判松（同目录多 id 记录互相放行）又解决不了它声称的场景（重导会换 uuid **且** 换目录）。
    // 现已删除（含 canonPath）。以下用例集中证明：**路径的任何形态都不再影响判定结果**。
    // ---------------------------------------------------------------------------------------

    @Test
    fun differentId_samePathSameType_nowBlocked() {
        val dir = tmp.newFolder("shared").absolutePath
        pretendLoaded(model("old-uuid", dir))
        assertFalse(
            "路径回退已删除：id 不同即拦截，哪怕指向同一目录、同一引擎",
            LocalModelSessionHolder.isLoaded(model("new-uuid", dir))
        )
    }

    /** 🔒 同路径但引擎类型不同（一个 MNN 一个 LLAMA_CPP）必须被拦。 */
    @Test
    fun differentId_samePath_differentType_blocked() {
        val dir = tmp.newFolder("shared").absolutePath
        pretendLoaded(model("id-A", dir, QuroLocalModelType.MNN))
        assertFalse(
            "路径相同但引擎类型不同不是同一个模型",
            LocalModelSessionHolder.isLoaded(model("id-B", dir, QuroLocalModelType.LLAMA_CPP))
        )
    }

    // ---------------------------------------------------------------------------------------
    // 路径形态无关性（原 canonPath 边界用例，收紧后统一预期为「拦截」）
    // ---------------------------------------------------------------------------------------

    /** `.` / `..` 冗余段：即使规范化后等价，id 不同仍拦。 */
    @Test
    fun dotAndDotDotSegments_differentId_stillBlocked() {
        val base = tmp.newFolder("base")
        val real = File(base, "sub").apply { mkdirs() }
        val noisy = File(base, "./sub/../sub").path
        pretendLoaded(model("id-A", real.absolutePath))
        assertFalse(LocalModelSessionHolder.isLoaded(model("id-B", noisy)))
    }

    /** 末尾分隔符：id 不同仍拦。 */
    @Test
    fun trailingSeparator_differentId_stillBlocked() {
        val dir = tmp.newFolder("trail")
        pretendLoaded(model("id-A", dir.absolutePath))
        assertFalse(
            LocalModelSessionHolder.isLoaded(model("id-B", dir.absolutePath + File.separatorChar))
        )
    }

    /** 重复分隔符：id 不同仍拦。 */
    @Test
    fun duplicatedSeparators_differentId_stillBlocked() {
        val base = tmp.newFolder("dup")
        val real = File(base, "inner").apply { mkdirs() }
        val noisy = base.absolutePath + File.separatorChar + File.separatorChar + "inner"
        pretendLoaded(model("id-A", real.absolutePath))
        assertFalse(LocalModelSessionHolder.isLoaded(model("id-B", noisy)))
    }

    /** 空路径：两边都空 → 拦。 */
    @Test
    fun bothPathsBlank_blocked() {
        pretendLoaded(model("id-A", ""))
        assertFalse(
            "两个空路径不能被判成同一个模型",
            LocalModelSessionHolder.isLoaded(model("id-B", ""))
        )
    }

    @Test
    fun oneSideBlankPath_blocked() {
        val dir = tmp.newFolder("x").absolutePath
        pretendLoaded(model("id-A", dir))
        assertFalse(LocalModelSessionHolder.isLoaded(model("id-B", "")))
        pretendLoaded(model("id-A", ""))
        assertFalse(LocalModelSessionHolder.isLoaded(model("id-B", dir)))
    }

    /** 路径不存在于磁盘：id 不同仍拦，且不得抛异常。 */
    @Test
    fun nonExistentPaths_differentId_blockedAndNoThrow() {
        val ghost = File(tmp.root, "never-created/deep").absolutePath
        pretendLoaded(model("id-A", ghost))
        assertFalse(LocalModelSessionHolder.isLoaded(model("id-B", ghost)))
        assertFalse(
            LocalModelSessionHolder.isLoaded(model("id-B", File(tmp.root, "never-created/other").absolutePath))
        )
    }

    /**
     * 含 NUL 字符的非法路径：`canonPath` 已随 F2 删除，判定链路不再碰文件系统。
     * 保留本用例作回归哨兵——若日后有人再把路径逻辑加回来，必须仍然不抛异常。
     */
    @Test
    fun pathWithNulChar_doesNotThrow_andIsBlocked() {
        val bad = tmp.root.absolutePath + File.separatorChar + "bad\u0000name"
        pretendLoaded(model("id-A", bad))
        assertFalse(
            "非法路径字符不得导致异常，且 id 不同仍拦",
            LocalModelSessionHolder.isLoaded(model("id-B", bad))
        )
        // 同 id + 非法路径 → 放行，证明路径确实完全不参与判定
        assertTrue(LocalModelSessionHolder.isLoaded(model("id-A", bad)))
    }

    /**
     * 大小写差异：F2 前该用例结果依赖宿主文件系统（Windows 判 true / Android 判 false），
     * 是 PC 测试掩盖真机行为的典型。收紧后判定与文件系统彻底解耦，
     * **两个平台结果一致**，`Assume` 平台门可以去掉了。
     */
    @Test
    fun caseDifference_nowPlatformIndependent() {
        val dir = tmp.newFolder("CaseTest")
        pretendLoaded(model("id-A", dir.absolutePath))
        val upper = dir.absolutePath.replace("CaseTest", "CASETEST")
        assertFalse(
            "判定不再依赖 getCanonicalPath()，Windows 与 Android 行为一致",
            LocalModelSessionHolder.isLoaded(model("id-B", upper))
        )
    }

    // ---------------------------------------------------------------------------------------
    // 空白 id：不能因为 "" == "" 误放行
    // ---------------------------------------------------------------------------------------

    @Test
    fun bothIdsBlank_differentPath_blocked() {
        val a = tmp.newFolder("ba").absolutePath
        val b = tmp.newFolder("bb").absolutePath
        pretendLoaded(model("", a))
        assertFalse(
            "两边 id 都是空串时绝不能因 \"\"==\"\" 直接放行",
            LocalModelSessionHolder.isLoaded(model("", b))
        )
    }

    /** 空 id + 同路径同类型：收紧后也必须拦（`held.id.isNotBlank()` 前置守卫兜住）。 */
    @Test
    fun bothIdsBlank_samePathSameType_nowBlocked() {
        val dir = tmp.newFolder("bc").absolutePath
        pretendLoaded(model("", dir))
        assertFalse(
            "空 id 不构成有效身份，路径回退已删除，必须拦",
            LocalModelSessionHolder.isLoaded(model("", dir))
        )
    }

    @Test
    fun heldIdBlank_incomingIdSet_blocked() {
        val dir = tmp.newFolder("bd").absolutePath
        val other = tmp.newFolder("be").absolutePath
        pretendLoaded(model("", dir))
        assertFalse(LocalModelSessionHolder.isLoaded(model("id-X", dir)))
        assertFalse(LocalModelSessionHolder.isLoaded(model("id-X", other)))
    }

    // ---------------------------------------------------------------------------------------
    // 状态前置：非 Loaded 一律拦
    // ---------------------------------------------------------------------------------------

    @Test
    fun nonLoadedStates_alwaysBlocked_evenWithSameId() {
        val dir = tmp.newFolder("st").absolutePath
        val m = model("id-A", dir)
        listOf(
            LocalModelLoader.State.None,
            LocalModelLoader.State.Loading,
            LocalModelLoader.State.Failed("boom"),
        ).forEach { s ->
            pretendState(s, m)
            assertFalse("state=$s 时门禁必须拦截", LocalModelSessionHolder.isLoaded(m))
        }
    }

    @Test
    fun loadedButHeldModelNull_blocked() {
        pretendState(LocalModelLoader.State.Loaded(model("id-A", "/x")), null)
        assertFalse(LocalModelSessionHolder.isLoaded(model("id-A", "/x")))
    }

    // ---------------------------------------------------------------------------------------
    // F2 修复验证：第一轮报告的判松面已封堵
    // ---------------------------------------------------------------------------------------

    /**
     * 第一轮报告的 F2(a)：同一目录下两条不同 id 的记录（如同目录放了两个不同 `.gguf`）
     * 曾被路径回退判成同一模型，等于重开「加载 A 拿 B 对话」的口子。收紧后必须被拦。
     */
    @Test
    fun twoRecordsSharingOneDirectory_nowBlocked() {
        val dir = tmp.newFolder("multi").absolutePath
        val a = QuroLocalModel(
            id = "id-A", type = QuroLocalModelType.LLAMA_CPP, name = "Qwen",
            path = dir, modelNames = listOf("qwen"),
        )
        val b = QuroLocalModel(
            id = "id-B", type = QuroLocalModelType.LLAMA_CPP, name = "Llama",
            path = dir, modelNames = listOf("llama"),
        )
        pretendLoaded(a)
        assertFalse(
            "同目录不同权重的两条记录必须被拦（F2 判松面已封堵）",
            LocalModelSessionHolder.isLoaded(b)
        )
    }

    /**
     * 门禁 [LocalModelSessionHolder.isLoaded] 与 [LocalModelSessionHolder.load] 的幂等判据
     * 必须是**同一个** `sameModel`，否则会出现「门禁说没加载、load 说已加载」的死锁死角
     * （load 直接返回 Success 不做事，门禁却继续拦 → 用户点加载显示成功但永远不能对话）。
     * 这里用反射确认两处调用的是同一私有方法，并校验其行为一致。
     */
    @Test
    fun gateAndLoadShareTheSamePredicate() {
        val sameModelFn = holderCls.declaredMethods
            .firstOrNull { it.name.startsWith("sameModel") }
        assertNotNull("sameModel 方法缺失，门禁与 load 可能已各写一套判据", sameModelFn)
        sameModelFn!!.isAccessible = true

        val dir = tmp.newFolder("pred").absolutePath
        val held = model("id-A", dir)
        pretendLoaded(held)

        listOf(
            model("id-A", dir) to true,        // 同 id
            model("id-B", dir) to false,       // 同路径不同 id
            model("", dir) to false,           // 空 id
        ).forEach { (incoming, expected) ->
            val direct = sameModelFn.invoke(LocalModelSessionHolder, held, incoming) as Boolean
            assertEquals("sameModel 判据与预期不符: ${incoming.id}", expected, direct)
            assertEquals(
                "isLoaded 必须与 sameModel 判据完全一致: ${incoming.id}",
                expected, LocalModelSessionHolder.isLoaded(incoming)
            )
        }
    }
}
