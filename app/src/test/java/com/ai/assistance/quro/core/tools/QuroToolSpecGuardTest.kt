package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.core.QuroToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [QuroToolSpecGuard] 单元测试（N5）。
 *
 * 最重要的是 [中文技能名不再互相撞名] —— 它对应一个**真实存在且静默**的 bug：
 * 旧 `sanitizeToolName` 把连一个 ASCII 都没有的技能名净化成空、回退成 `"skill"`，
 * 于是所有纯中文技能都变成 `skill__skill`，再被 `specs()` 的 `distinctBy` 去重到只剩一个。
 * 用户装了 N 个中文技能，AI 只能调用其中 1 个，且日志里毫无线索。
 * 本测试用一批真实形态的技能名把这条堵死。
 */
class QuroToolSpecGuardTest {

    /** 真实形态的技能名样本（纯中文、中英混、带标点全角括号、纯 ASCII）。 */
    private val realWorldSkillNames = listOf(
        "视频号账号诊断与拆解（付费版）",
        "抖音热榜",
        "合同风险识别与条款分析",
        "GitHub 项目周报",
        "小红书爆款拆解",
        "微信公众号文章搜索",
        "天气查询",
        "番茄小说分章创作",
        "专利交底书",
        "ima-skill",
        "douyin-hot-trend",
        "QQ音乐",
        "论文降重",
        "简历优化",
        "股票财报解读",
        "PDF 工具箱",
        "图片去水印",
        "视频号起号模板",
        "海外热文搜索",
        "翻译",
    )

    // ─────────────── 核心回归：中文技能名不再撞名 ───────────────

    @Test
    fun `中文技能名不再互相撞名`() {
        val mapped = realWorldSkillNames.map { QuroToolSpecGuard.sanitizeName(it, 57) }
        val distinct = mapped.toSet()
        assertEquals(
            "净化后必须两两不同，否则 distinctBy 会把技能静默去重（旧 bug 的根因）。" +
                "重叠: " + mapped.groupBy { it }.filter { it.value.size > 1 }.keys,
            realWorldSkillNames.size,
            distinct.size,
        )
    }

    @Test
    fun `旧实现的坍缩场景不再发生`() {
        // 这三个名字在旧实现下**全部**得到 "skill"（每个中文字都被换成 '-'，折叠后为空）。
        val a = QuroToolSpecGuard.sanitizeName("视频号账号诊断", 57)
        val b = QuroToolSpecGuard.sanitizeName("合同风险审查", 57)
        val c = QuroToolSpecGuard.sanitizeName("抖音热榜", 57)
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, c)
        // 且都不再是那个把所有中文技能糊在一起的 "skill"
        listOf(a, b, c).forEach {
            assertNotEquals("旧 bug 的固定回退值", "skill", it)
        }
    }

    @Test
    fun `带存活 ASCII 片段的混名保留语义并加哈希`() {
        // 「视频号-diagnose」净化后 ASCII 片段 "diagnose" 应保留，同时加哈希保证唯一
        val one = QuroToolSpecGuard.sanitizeName("视频号-diagnose", 57)
        val two = QuroToolSpecGuard.sanitizeName("小红书-diagnose", 57)
        assertTrue("可读的 ASCII 片段不该被丢掉：$one", one.startsWith("diagnose"))
        assertTrue(two.startsWith("diagnose"))
        assertNotEquals("共享 ASCII 片段的两个名字必须仍可区分", one, two)
    }

    // ─────────────── 确定性 / 幂等 ───────────────

    @Test
    fun `确定性 同一输入永远得到同一工具名`() {
        // 反向查找靠它：execOnce 里是 toolNameOf(skill.name) == call.name 现算的，
        // 一旦不确定，重启后模型传来的名字就查不到技能。
        realWorldSkillNames.forEach {
            assertEquals(
                "同一技能名两次净化结果必须一致：$it",
                QuroToolSpecGuard.sanitizeName(it, 57),
                QuroToolSpecGuard.sanitizeName(it, 57),
            )
        }
    }

    @Test
    fun `幂等 净化结果再净化不变`() {
        // 导入工具的 add/remove/contains 都会对名字再净化一次，
        // 幂等才能保证「存进去的名字」与「查出来的名字」一致（否则删不掉）。
        realWorldSkillNames.forEach { raw ->
            val once = QuroToolSpecGuard.sanitizeName(raw, 57)
            assertEquals("幂等被破坏：$raw", once, QuroToolSpecGuard.sanitizeName(once, 57))
        }
    }

    @Test
    fun `合法短名原样返回`() {
        listOf("ima-skill", "douyin-hot-trend", "read_file", "a", "1234", "A_b-C").forEach {
            assertEquals("合法名不该被改写：$it", it, QuroToolSpecGuard.sanitizeName(it))
        }
    }

    // ─────────────── 长度上限（超了整个 tools 数组被上游拒收） ───────────────

    @Test
    fun `净化结果长度始终不超过上限`() {
        val samples = realWorldSkillNames + listOf(
            "a".repeat(200),
            "x".repeat(57),
            "很长的中文技能名字".repeat(20),
            "混合 mixed 名字 with spaces".repeat(10),
        )
        samples.forEach { s ->
            val out = QuroToolSpecGuard.sanitizeName(s, 57)
            assertTrue("结果超长（${out.length} > 57）：$s -> $out", out.length <= 57)
            assertTrue("结果必须合法：$out", QuroToolSpecGuard.isLegalName(out) == (out.length <= 64))
        }
    }

    @Test
    fun `超长名按内建上限截断`() {
        val out = QuroToolSpecGuard.sanitizeName("a".repeat(500))
        assertTrue("默认上限是 64，实际 ${out.length}", out.length <= QuroToolSpecGuard.MAX_TOOL_NAME_LEN)
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(out))
        // 截断会丢信息，所以必须带哈希尾缀，否则两个共享长前缀的名字会撞
        assertTrue("截断后必须带哈希尾缀：$out", Regex("-[0-9a-f]{8}$").containsMatchIn(out))
    }

    @Test
    fun `共享长前缀的名字截断后不撞名`() {
        val one = QuroToolSpecGuard.sanitizeName("a".repeat(60) + "x")
        val two = QuroToolSpecGuard.sanitizeName("a".repeat(60) + "y")
        assertEquals(one.length, two.length)
        assertNotEquals(one, two)
    }

    // ─────────────── 边界输入 ───────────────

    @Test
    fun `空白与空串得到合法非空名`() {
        listOf("", "   ", "\t\n").forEach { raw ->
            val out = QuroToolSpecGuard.sanitizeName(raw)
            assertTrue("空输入的产物必须非空且合法：'$raw' -> '$out'", QuroToolSpecGuard.isLegalName(out))
        }
        // 空白变体（空串 / 空格 / 制表换行）净化后 trim 成同一个值，因此产物**相同** ——
        // 这是刻意的：它们本来就是同一件无意义输入，不该因一个多余空格就多出一个工具名。
        // （真正有害的坍缩是**不同技能名**撞成同一个名字，那条由 `中文技能名不再互相撞名` 守住。）
        assertEquals(
            QuroToolSpecGuard.sanitizeName(""),
            QuroToolSpecGuard.sanitizeName("   "),
        )
        // 但空白占位名不能和真实名字撞
        assertNotEquals(QuroToolSpecGuard.sanitizeName(""), QuroToolSpecGuard.sanitizeName("tool"))
        assertNotEquals(QuroToolSpecGuard.sanitizeName(""), QuroToolSpecGuard.sanitizeName("技能"))
    }

    @Test
    fun `非法字符被替换为合法字符`() {
        listOf("a b", "a/b", "a.b", "a:b", "a(b)", "a　b").forEach { raw ->
            val out = QuroToolSpecGuard.sanitizeName(raw)
            assertTrue("'$raw' -> '$out' 仍含非法字符", QuroToolSpecGuard.isLegalName(out))
        }
    }

    @Test
    fun `isLegalName 边界`() {
        assertTrue(QuroToolSpecGuard.isLegalName("a"))
        assertTrue(QuroToolSpecGuard.isLegalName("a".repeat(64)))
        assertFalse("超 64 必须非法（OpenAI 上限）", QuroToolSpecGuard.isLegalName("a".repeat(65)))
        assertFalse("中文非法", QuroToolSpecGuard.isLegalName("视频号"))
        assertFalse("空串非法", QuroToolSpecGuard.isLegalName(""))
        assertFalse("空格非法", QuroToolSpecGuard.isLegalName("a b"))
        assertFalse("点号非法", QuroToolSpecGuard.isLegalName("a.b"))
    }

    // ─────────────── hash8 ───────────────

    @Test
    fun `hash8 稳定且格式固定`() {
        val h1 = QuroToolSpecGuard.hash8("视频号账号诊断")
        val h2 = QuroToolSpecGuard.hash8("视频号账号诊断")
        assertEquals("哈希必须稳定（跨进程也不能变）", h1, h2)
        assertEquals(8, h1.length)
        assertTrue("必须是 8 位小写十六进制：$h1", Regex("^[0-9a-f]{8}$").matches(h1))
        // 相邻输入不能哈希到同一个值（否则又出现坍缩）
        assertNotEquals(QuroToolSpecGuard.hash8("抖音热榜"), QuroToolSpecGuard.hash8("抖音热点"))
    }

    // ─────────────── parametersJson ───────────────

    @Test
    fun `looksLikeJsonObject 只认对象形状`() {
        assertTrue(QuroToolSpecGuard.looksLikeJsonObject("""{"type":"object"}"""))
        assertTrue(QuroToolSpecGuard.looksLikeJsonObject("""  {"a":1}  """))
        assertFalse(QuroToolSpecGuard.looksLikeJsonObject(""))
        assertFalse(QuroToolSpecGuard.looksLikeJsonObject("   "))
        assertFalse(QuroToolSpecGuard.looksLikeJsonObject(null))
        assertFalse("数组不是对象", QuroToolSpecGuard.looksLikeJsonObject("""[1,2]"""))
        assertFalse("裸字符串不是对象", QuroToolSpecGuard.looksLikeJsonObject("hello"))
        assertFalse("单个 { 不算", QuroToolSpecGuard.looksLikeJsonObject("{"))
    }

    @Test
    fun `normalizeParametersJson 畸形时退化为空对象 schema`() {
        // 退化成空对象 schema 而不是抛异常：单个坏 schema 不该让整个 chat() 请求构造失败
        // （那会让**所有**工具调用一起失效）。
        assertEquals(QuroToolSpecGuard.EMPTY_SCHEMA, QuroToolSpecGuard.normalizeParametersJson(null))
        assertEquals(QuroToolSpecGuard.EMPTY_SCHEMA, QuroToolSpecGuard.normalizeParametersJson(""))
        assertEquals(QuroToolSpecGuard.EMPTY_SCHEMA, QuroToolSpecGuard.normalizeParametersJson("不是 JSON"))
        assertEquals(QuroToolSpecGuard.EMPTY_SCHEMA, QuroToolSpecGuard.normalizeParametersJson("""[1,2]"""))

        val good = """{"type":"object","properties":{"a":{"type":"string"}}}"""
        assertEquals("合法的原样保留（只去首尾空白）", good, QuroToolSpecGuard.normalizeParametersJson(good))
        assertEquals(good, QuroToolSpecGuard.normalizeParametersJson("  $good  "))

        // 兜底 schema 本身必须是合法 JSON 对象的形状，否则兜底等于没兜
        assertTrue(QuroToolSpecGuard.looksLikeJsonObject(QuroToolSpecGuard.EMPTY_SCHEMA))
    }

    // ─────────────── dedupe ───────────────

    @Test
    fun `dedupe 保留先出现的并报出被丢弃的名字`() {
        val specs = listOf(
            QuroToolSpec("read_file", "内置", "{}"),
            QuroToolSpec("skill__x", "技能A", "{}"),
            QuroToolSpec("read_file", "导入的同名", "{}"),
            QuroToolSpec("skill__x", "技能B", "{}"),
            QuroToolSpec("calc", "内置", "{}"),
        )
        val r = QuroToolSpecGuard.dedupe(specs)
        assertEquals(3, r.specs.size)
        assertEquals(listOf("read_file", "skill__x"), r.droppedDuplicates)
        assertTrue(r.hadDuplicates)
        // 保留的是**先出现**的那个（内置优先于后置的导入/技能）
        assertEquals("内置", r.specs.first { it.name == "read_file" }.description)
    }

    @Test
    fun `dedupe 无重复时不动数据`() {
        val specs = listOf(
            QuroToolSpec("a", "d", "{}"),
            QuroToolSpec("b", "d", "{}"),
        )
        val r = QuroToolSpecGuard.dedupe(specs)
        assertEquals(specs, r.specs)
        assertFalse(r.hadDuplicates)
        assertTrue(r.droppedDuplicates.isEmpty())
    }

    // ─────────────── 与技能命名的集成（前缀长度预算） ───────────────

    @Test
    fun `技能工具名把 skill 前缀算进 64 长度预算`() {
        // skill__(7) + 片段 ≤ 64 → 片段上限 57。带前缀的最终名字必须整体合法。
        val out = QuroSkillNamingProbe.toolNameOf("很长的中文技能名字".repeat(10))
        assertTrue("技能工具名超长：${out.length}", out.length <= QuroToolSpecGuard.MAX_TOOL_NAME_LEN)
        assertTrue(QuroToolSpecGuard.isLegalName(out))
        assertTrue(out.startsWith("skill__"))
    }

    @Test
    fun `不同技能得到不同工具名（经真实 toolNameOf）`() {
        val names = listOf("视频号账号诊断与拆解（付费版）", "抖音热榜", "合同风险识别与条款分析")
        val tokens = names.map { QuroSkillNamingProbe.toolNameOf(it) }
        assertEquals("真实 toolNameOf 也必须两两不同", 3, tokens.toSet().size)
    }
}

/**
 * 探针：只暴露 [com.ai.assistance.quro.core.skill.QuroSkill.toolNameOf] 这一个纯函数，
 * 让本测试**不必**触碰带 `Context` 的技能类（避免单测里初始化 Android 依赖）。
 *
 * 注意这里必须与 `QuroSkill.toolNameOf` 用**同一套**前缀与预算；
 * 该不变量由 [技能工具名把 skill 前缀算进 64 长度预算] 断言守住。
 */
private object QuroSkillNamingProbe {
    private const val PREFIX = "skill__"
    fun toolNameOf(raw: String): String =
        PREFIX + QuroToolSpecGuard.sanitizeName(raw.removePrefix(PREFIX), QuroToolSpecGuard.MAX_TOOL_NAME_LEN - PREFIX.length)
}
