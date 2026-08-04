package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门禁提示语 `QuroLocalEngineNative.gateMessage()` 的分支正确性测试（full 风味）。
 *
 * 用户侧无 adb，聊天气泡是唯一的诊断面板。四个 holder 状态必须给出**互不相同、可执行**的提示；
 * 同时 `State.None` 分支必须与修复前的原文案**逐字一致**（行为不回退）。
 */
class GateMessageTest {

    /** 修复前（git HEAD）QuroLocalEngineNative.kt:51 的原文案，逐字复制作为黄金值。 */
    private val legacyText =
        "请先在「模型配置」中加载该本地模型后再对话（设置 → 模型配置 → 选择模型 → 点「加载」）。"

    private val engine = QuroLocalEngineNative()

    private val method = QuroLocalEngineNative::class.java
        .getDeclaredMethod(
            "gateMessage",
            LocalModelSessionHolder.Snapshot::class.java,
            QuroLocalModel::class.java,
        ).apply { isAccessible = true }

    private fun model(id: String, name: String, type: QuroLocalModelType = QuroLocalModelType.LLAMA_CPP) =
        QuroLocalModel(id = id, type = type, name = name, path = "/data/models/$id")

    private fun snap(state: LocalModelLoader.State, held: QuroLocalModel? = null) =
        LocalModelSessionHolder.Snapshot(
            state = state, heldModel = held, nCtx = 4096,
            activeGen = 0, closing = false, pid = 12345,
        )

    private fun gate(state: LocalModelLoader.State, held: QuroLocalModel?, incoming: QuroLocalModel): String =
        method.invoke(engine, snap(state, held), incoming) as String

    @Test
    fun reflectionTargetsRealPrivateMethod() {
        assertNotNull(method)
        assertEquals(String::class.java, method.returnType)
        assertTrue(
            "gateMessage 应是私有实现细节",
            java.lang.reflect.Modifier.isPrivate(method.modifiers)
        )
    }

    /** None：必须与修复前原文案逐字一致。 */
    @Test
    fun stateNone_keepsLegacyTextVerbatim() {
        val msg = gate(LocalModelLoader.State.None, null, model("b", "Qwen"))
        assertEquals("State.None 分支的文案不允许改动（行为不回退）", legacyText, msg)
    }

    /** Failed：必须把真实失败原因抛给用户。 */
    @Test
    fun stateFailed_surfacesRealReason() {
        val reason = "llama.cpp 模型文件未找到：目录 /data/models/x 下找不到 \"qwen\" 对应的 .gguf"
        val msg = gate(LocalModelLoader.State.Failed(reason), null, model("b", "Qwen"))
        assertTrue("必须原样带出失败原因，这是用户唯一能看到的诊断信息", msg.contains(reason))
        assertTrue(msg.contains("加载失败"))
        assertFalse("Failed 不应再退回泛泛的「请先加载」原文案", msg == legacyText)
    }

    /** Loading：提示等待，且不能诱导用户重复点加载。 */
    @Test
    fun stateLoading_tellsUserToWait() {
        val msg = gate(LocalModelLoader.State.Loading, null, model("b", "Qwen"))
        assertTrue(msg.contains("正在加载"))
        assertFalse(msg == legacyText)
    }

    /** Loaded 但持有的是别的模型：必须点名当前常驻的是谁。 */
    @Test
    fun stateLoadedDifferentModel_namesBothSides() {
        val held = model("a", "Qwen2.5-1.5B")
        val incoming = model("b", "Llama3-8B")
        val msg = gate(LocalModelLoader.State.Loaded(held), held, incoming)
        assertTrue("必须点名当前常驻模型", msg.contains("Qwen2.5-1.5B"))
        assertTrue("必须点名本次对话选用的模型", msg.contains("Llama3-8B"))
        assertTrue(msg.contains("不是同一个模型"))
    }

    /** Loaded 但 heldModel 为 null（理论上不该出现）→ 安全回退到原文案。 */
    @Test
    fun stateLoadedWithNullHeld_fallsBackToLegacyText() {
        val msg = gate(LocalModelLoader.State.Loaded(model("a", "Qwen")), null, model("b", "Llama"))
        assertEquals(legacyText, msg)
    }

    /** 四个分支两两不同，保证用户能据此区分处置方式。 */
    @Test
    fun allFourBranchesProduceDistinctMessages() {
        val held = model("a", "Qwen")
        val incoming = model("b", "Llama")
        val msgs = listOf(
            gate(LocalModelLoader.State.None, null, incoming),
            gate(LocalModelLoader.State.Loading, null, incoming),
            gate(LocalModelLoader.State.Failed("boom"), null, incoming),
            gate(LocalModelLoader.State.Loaded(held), held, incoming),
        )
        assertEquals("四个状态必须给出四条不同的提示", 4, msgs.toSet().size)
    }

    // ---------------------------------------------------------------------------------------
    // Snapshot 取证文本
    // ---------------------------------------------------------------------------------------

    @Test
    fun snapshotDescribe_containsAllForensicFields() {
        val held = model("held-id", "Qwen")
        val line = snap(LocalModelLoader.State.Loaded(held), held).describe()
        listOf("state=", "held.id=held-id", "held.type=LLAMA_CPP", "held.path=/data/models/held-id",
            "nCtx=4096", "activeGen=0", "closing=false", "pid=12345").forEach {
            assertTrue("取证行缺字段 $it —— 实际: $line", line.contains(it))
        }
    }

    @Test
    fun snapshotStateText_failedCarriesReason() {
        assertTrue(
            snap(LocalModelLoader.State.Failed("磁盘满")).stateText().contains("磁盘满")
        )
        assertTrue(snap(LocalModelLoader.State.None).stateText().startsWith("None"))
        assertTrue(snap(LocalModelLoader.State.Loading).stateText().startsWith("Loading"))
    }

    /** holder.snapshot() 在纯 JVM 环境下不得抛异常（android.os.Process 走 mockable 默认值）。 */
    @Test
    fun holderSnapshot_doesNotThrowOnJvm() {
        val s = LocalModelSessionHolder.snapshot()
        assertNotNull(s)
        assertNotNull(s.describe())
    }
}
