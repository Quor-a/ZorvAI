package com.ai.assistance.mnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RepetitionGuard] 的真实 JUnit 回归测试（不是离线模拟，直接调用被测类）。
 *
 * 覆盖三类场景：
 * 1. **命中**：线上真实复现过的复读形态必须被截断；
 * 2. **不误伤**：正常回复 / 人设文本 / Markdown 排版不允许被截断（假阳性比漏检更伤体验）；
 * 3. **边界**：把 minSpanChars / minRepeats 阈值锁死，防止后续改参数悄悄改崩。
 */
class RepetitionGuardTest {

    // ---------------------------------------------------------------- helpers

    /** 一次性喂入整段文本（非流式），返回 guard。 */
    private fun feedWhole(
        text: String,
        config: RepetitionGuard.Config = RepetitionGuard.Config(),
    ): RepetitionGuard = RepetitionGuard(config).apply { accept(text) }

    /**
     * 逐字符喂入（模拟流式 token 回调），返回 guard 与「首次返回 false 时已喂入的字符数」。
     * 未命中时 index 为 -1。
     */
    private fun feedStreaming(
        text: String,
        config: RepetitionGuard.Config = RepetitionGuard.Config(),
    ): Pair<RepetitionGuard, Int> {
        val guard = RepetitionGuard(config)
        var firstFalseAt = -1
        text.forEachIndexed { i, c ->
            val ok = guard.accept(c.toString())
            if (!ok && firstFalseAt < 0) firstFalseAt = i + 1
        }
        return guard to firstFalseAt
    }

    // ------------------------------------------------------------- 命中用例

    /** 原始 Bug 复现：「主人～」×30。应在第 8 次重复（第 24 字符）处截断。 */
    @Test
    fun trips_onOriginalBug_zhuRenRepeat30() {
        val (guard, at) = feedStreaming("主人～".repeat(30))
        assertTrue("「主人～」×30 必须被判定为复读退化", guard.tripped)
        val d = guard.detection!!
        assertEquals("主人～", d.phrase)
        assertEquals(8, d.repeats)
        assertEquals(24, d.totalChars)
        assertEquals("必须在第 24 个字符处截断，而不是拖到 maxTokens", 24, at)
    }

    /** 带空格变体：「主人～ 」×30，单元长度 4（>shortPhraseChars），门槛降为 4 次但仍需跨度 24。 */
    @Test
    fun trips_onZhuRenWithTrailingSpace() {
        val (guard, at) = feedStreaming("主人～ ".repeat(30))
        assertTrue("「主人～ 」×30 必须被判定为复读退化", guard.tripped)
        val d = guard.detection!!
        assertEquals("主人～ ", d.phrase)
        assertEquals(6, d.repeats)
        assertEquals(24, d.totalChars)
        assertEquals(24, at)
    }

    /** 整句复读：「我不太明白你的意思。」×6（单元 10 字符，需 4 次 → 第 40 字符处截断）。 */
    @Test
    fun trips_onWholeSentenceRepeat() {
        val (guard, at) = feedStreaming("我不太明白你的意思。".repeat(6))
        assertTrue(guard.tripped)
        val d = guard.detection!!
        assertEquals("我不太明白你的意思。", d.phrase)
        assertEquals(4, d.repeats)
        assertEquals(40, d.totalChars)
        assertEquals(40, at)
    }

    /** 单字复读：「啊」×40，单元 1 字符需 8 次 + 跨度 24 → 第 24 字符处截断。 */
    @Test
    fun trips_onSingleCharRepeat() {
        val (guard, at) = feedStreaming("啊".repeat(40))
        assertTrue(guard.tripped)
        val d = guard.detection!!
        assertEquals("啊", d.phrase)
        assertEquals(24, d.repeats)
        assertEquals(24, at)
    }

    // ----------------------------------------------------------- 不误伤用例

    /** 口语重复「哈哈哈哈」：跨度不足，且后接正常内容，不得截断。 */
    @Test
    fun doesNotTrip_onColloquialLaughter() {
        val text = "哈哈哈哈，这个问题很有意思，我来帮你分析一下具体的原因和几种可行的解决办法吧。"
        assertFalse("正常口语重复被误判为复读：$text", feedStreaming(text).first.tripped)
        assertFalse(feedWhole(text).tripped)
    }

    /** 省略号：「嗯……我想想……」不得截断。 */
    @Test
    fun doesNotTrip_onEllipsis() {
        val text = "嗯……我想想……这个问题其实有好几种不同的解法，我先讲最常见也最稳妥的那一种吧。"
        assertFalse(feedStreaming(text).first.tripped)
        assertFalse(feedWhole(text).tripped)
    }

    /** Markdown 无序列表不得截断。 */
    @Test
    fun doesNotTrip_onMarkdownBulletList() {
        val text = buildString {
            append("下面是排查步骤：\n")
            append("- 第一步：确认设备网络连接是否正常\n")
            append("- 第二步：检查配置文件里的模型路径\n")
            append("- 第三步：重启应用并重新加载模型\n")
            append("- 第四步：如果仍然失败，导出诊断日志\n")
        }
        assertFalse(feedStreaming(text).first.tripped)
        assertFalse(feedWhole(text).tripped)
    }

    /** 带缩进的代码块不得截断（缩进空白单元必须被跳过）。 */
    @Test
    fun doesNotTrip_onIndentedCodeBlock() {
        val text = buildString {
            append("可以这样写：\n\n")
            append("```kotlin\n")
            append("fun main() {\n")
            append("    val a = 1\n")
            append("    val b = 2\n")
            append("    val c = 3\n")
            append("    println(a + b + c)\n")
            append("}\n")
            append("```\n")
        }
        assertFalse(feedStreaming(text).first.tripped)
        assertFalse(feedWhole(text).tripped)
    }

    /**
     * App 真实 persona 开场白原文（星眠少女）。
     * 出处：app/src/main/java/com/ai/assistance/quro/core/QuroPersona.kt:186 —— 本身就含「主人～」。
     */
    @Test
    fun doesNotTrip_onRealPersonaOpening() {
        val opening = "主人～你来找我啦 ♡ 今天也想被你抱着呢~"
        assertFalse("真实人设开场白被误判为复读", feedStreaming(opening).first.tripped)
        assertFalse(feedWhole(opening).tripped)
    }

    /** persona 的 chatSetting 原文（QuroPersona.kt:187），含大量「～」「♡」。 */
    @Test
    fun doesNotTrip_onRealPersonaChatSetting() {
        val chatSetting =
            "语气超级软萌撒娇；频繁使用「～」「♡」「❤」；称呼用户为'主人'或'宝贝'；" +
                "回复体现女朋友的体贴和依赖感；偶尔主动撒娇求关注。"
        assertFalse(feedStreaming(chatSetting).first.tripped)
    }

    /** 一段正常回复里「主人～」自然出现 3 次（非连续），不得截断。 */
    @Test
    fun doesNotTrip_onThreeNaturalZhuRenOccurrences() {
        val text = "主人～今天天气很好呢。要不要一起出去走走呀？主人～你想去公园还是去商场逛逛？" +
            "不管去哪里我都会陪着你的，主人～我最喜欢和你待在一起了。"
        assertEquals("前置条件：文本里应恰好出现 3 次「主人～」", 3, Regex("主人～").findAll(text).count())
        assertFalse(feedStreaming(text).first.tripped)
        assertFalse(feedWhole(text).tripped)
    }

    /** 长段正常中文回复不得截断。 */
    @Test
    fun doesNotTrip_onLongNormalReply() {
        val text = "关于你问的这个问题，我的理解是这样的：首先需要确认输入数据的来源和格式，" +
            "其次要评估现有方案在性能和可维护性上的取舍，最后再决定是否引入新的依赖。" +
            "如果你愿意的话，我可以按这三步分别给出更具体的建议和示例代码。"
        assertFalse(feedStreaming(text).first.tripped)
    }

    /** 纯空白重复（排版空行 / 缩进）不得截断。 */
    @Test
    fun doesNotTrip_onWhitespaceOnlyRepetition() {
        assertFalse(feedStreaming("好的，我明白了。" + " ".repeat(60)).first.tripped)
        assertFalse(feedStreaming("好的，我明白了。" + "\n".repeat(60)).first.tripped)
        assertFalse(feedStreaming(" ".repeat(200)).first.tripped)
    }

    // --------------------------------------------------------- 阈值边界锁定

    /** 「主人～」×7 = 21 字符 < minSpanChars(24) → 不得命中。 */
    @Test
    fun boundary_sevenRepeats21Chars_doesNotTrip() {
        val text = "主人～".repeat(7)
        assertEquals(21, text.length)
        assertFalse("21 字符跨度不到 minSpanChars=24，不应命中", feedStreaming(text).first.tripped)
        assertFalse(feedWhole(text).tripped)
    }

    /** 「主人～」×8 = 24 字符 = minSpanChars → 必须命中。 */
    @Test
    fun boundary_eightRepeats24Chars_trips() {
        val text = "主人～".repeat(8)
        assertEquals(24, text.length)
        assertTrue("恰好 24 字符跨度应命中", feedStreaming(text).first.tripped)
        assertTrue(feedWhole(text).tripped)
    }

    /** 长单元门槛：4 字符单元 ×3 = 12 字符不命中，×6 = 24 字符命中。 */
    @Test
    fun boundary_longUnitRepeatThreshold() {
        assertFalse(feedStreaming("好的好的".repeat(3)).first.tripped)
        assertTrue(feedStreaming("好的好的".repeat(6)).first.tripped)
    }

    // ------------------------------------------------------- 自补边界用例

    /** 空 / 极短输入不得崩溃、不得误报。 */
    @Test
    fun edge_emptyAndTinyInputs_areSafe() {
        val g = RepetitionGuard()
        assertTrue(g.accept(""))
        assertTrue(g.accept(""))
        assertFalse(g.tripped)
        assertEquals("", g.text)

        assertFalse(feedWhole("").tripped)
        assertFalse(feedWhole("啊").tripped)
        assertFalse(feedWhole("？").tripped)
        // 长度恰好比 minSpanChars 少 1
        assertFalse(feedWhole("啊".repeat(23)).tripped)
        assertTrue(feedWhole("啊".repeat(24)).tripped)
    }

    /** enabled=false 时这道防线完全关闭。 */
    @Test
    fun edge_disabledConfig_neverTrips() {
        val cfg = RepetitionGuard.Config(enabled = false)
        val (guard, at) = feedStreaming("主人～".repeat(50), cfg)
        assertFalse(guard.tripped)
        assertEquals(-1, at)
        assertNull(guard.detection)
    }

    /**
     * 长句整句复读（周期 38 字符）。
     *
     * QA 原先记录的是「maxPhraseChars=32 时漏检」这一缺口；缺口已通过把默认
     * [RepetitionGuard.Config.maxPhraseChars] 提到 64 关闭。本用例现在同时锁死两侧：
     * 默认配置**必须**检出；把上限调回 32 则**必然**漏检——证明这自始至终只是参数上限
     * 问题而非算法缺陷。
     */
    @Test
    fun edge_periodLongerThanOldMaxPhraseChars_isDetectedWithNewDefault() {
        val sentence = "我需要提醒你一件非常重要的事情，请务必认真阅读下面的全部内容并且牢牢记住它。"
        assertTrue("前置条件：句子长度必须 > 旧上限 32", sentence.length > 32)
        assertTrue("前置条件：句子长度必须 <= 新上限 64", sentence.length <= 64)

        val guard = feedStreaming(sentence.repeat(6)).first
        assertTrue(
            "周期 ${sentence.length} 的整句复读必须被新默认上限(64)检出",
            guard.tripped,
        )
        assertEquals(sentence, guard.detection!!.phrase)
        assertEquals("长单元门槛 repeatThreshold=4", 4, guard.detection!!.repeats)

        // 把上限调回旧值 32 后必然漏检，说明差异完全来自参数上限。
        val narrow = RepetitionGuard.Config(maxPhraseChars = 32)
        assertFalse(feedStreaming(sentence.repeat(6), narrow).first.tripped)
    }

    /** 流式判定时机稳定性：命中前一路 true，命中后永远 false，不抖动。 */
    @Test
    fun streaming_decisionIsMonotonicAndStable() {
        val guard = RepetitionGuard()
        val text = "主人～".repeat(40)
        val results = text.map { guard.accept(it.toString()) }

        val firstFalse = results.indexOfFirst { !it }
        assertTrue("必须命中", firstFalse >= 0)
        assertEquals("命中位置应为第 24 个字符（下标 23）", 23, firstFalse)
        assertTrue("命中前不允许出现 false", results.take(firstFalse).all { it })
        assertTrue("命中后必须恒为 false（不允许抖动）", results.drop(firstFalse).none { it })

        // 命中后 detection 不再变化
        val snapshot = guard.detection
        repeat(20) { guard.accept("主人～") }
        assertEquals(snapshot, guard.detection)
    }

    /** 流式与整段喂入的判定结果一致（同一段文本，结论不能因为分片方式不同而变）。 */
    @Test
    fun streaming_matchesWholeFeedForSameText() {
        val text = "主人～".repeat(8)
        val streamed = feedStreaming(text).first.detection
        val whole = feedWhole(text).detection
        assertNotNull(streamed)
        assertEquals(whole, streamed)
    }

    /** 按 token 分片（每次多字符）与逐字分片结论一致。 */
    @Test
    fun streaming_chunkSizeDoesNotChangeVerdict() {
        val text = "主人～".repeat(12)
        val byToken = RepetitionGuard()
        var i = 0
        while (i < text.length) {
            val end = minOf(i + 3, text.length)
            if (!byToken.accept(text.substring(i, end))) break
            i = end
        }
        assertTrue(byToken.tripped)
        assertEquals("主人～", byToken.detection!!.phrase)
    }

    // ----------------------------------------------- trimDegenerateTail 正确性

    /** 未命中时原样返回，正常回复零影响。 */
    @Test
    fun trim_returnsOriginalWhenNoDetection() {
        val text = "这是一段完全正常的回复，不应该被裁剪掉任何内容。"
        assertEquals(text, RepetitionGuard.trimDegenerateTail(text, null))
    }

    /** 「正常句子 + 复读尾巴」：正常内容一个字都不能少，只砍复读尾巴。 */
    @Test
    fun trim_keepsNormalPrefixIntact() {
        val prefix = "好的，我这就去做。"
        val text = prefix + "主人～".repeat(8)
        val guard = feedStreaming(text).first
        assertTrue(guard.tripped)

        val trimmed = RepetitionGuard.trimDegenerateTail(text, guard.detection)
        assertTrue("正常前缀被裁掉了：$trimmed", trimmed.startsWith(prefix))
        assertEquals(prefix + "主人～主人～", trimmed)
        assertTrue("裁剪后长度必须小于原文", trimmed.length < text.length)
    }

    /** 长正常段落 + 复读尾巴：前缀逐字对比不得有任何丢失。 */
    @Test
    fun trim_keepsLongNormalPrefixCharForChar() {
        val prefix = "关于你问的这个问题，我的理解是这样的：首先需要确认输入数据的来源和格式，" +
            "其次要评估现有方案在性能和可维护性上的取舍。"
        val guard = feedStreaming(prefix + "我不太明白你的意思。".repeat(6)).first
        assertTrue(guard.tripped)
        // 生产语义：命中即中断生成，上层拿到的是命中时刻的累计文本（== guard.text）。
        val text = guard.text

        val trimmed = RepetitionGuard.trimDegenerateTail(text, guard.detection)
        assertEquals(prefix, trimmed.substring(0, prefix.length))
        assertTrue(trimmed.length < text.length)
        assertTrue("裁剪后应保留少量重复以免语义断裂", trimmed.contains("我不太明白你的意思。"))
    }

    /** 重复次数 <= 保留次数时不裁剪。 */
    @Test
    fun trim_noOpWhenRepeatsWithinKeepBudget() {
        val text = "主人～主人～"
        val d = RepetitionGuard.Detection(phrase = "主人～", repeats = 2, totalChars = text.length)
        assertEquals(text, RepetitionGuard.trimDegenerateTail(text, d))
    }

    /** 空 phrase / 越界 dropChars 不得抛异常，也不得产出空回复。 */
    @Test
    fun trim_isSafeOnDegenerateDetections() {
        val text = "一段正常内容"
        assertEquals(text, RepetitionGuard.trimDegenerateTail(text, RepetitionGuard.Detection("", 99, 6)))
        assertEquals(text, RepetitionGuard.trimDegenerateTail(text, RepetitionGuard.Detection("正常", 99, 6)))
        assertTrue(RepetitionGuard.trimDegenerateTail("主人～".repeat(8), RepetitionGuard.Detection("主人～", 8, 24)).isNotBlank())
    }

    /** 纯复读（无正常前缀）裁剪后仍保留 2 次重复，不产出空串。 */
    @Test
    fun trim_pureRepetitionKeepsTwoUnits() {
        val text = "主人～".repeat(8)
        val guard = feedWhole(text)
        val trimmed = RepetitionGuard.trimDegenerateTail(text, guard.detection)
        assertEquals("主人～主人～", trimmed)
    }

    // --------------------------------------- 假阳性回归（QA 自补，非工程师用例）
    //
    // 缺陷背景：detect() 曾经只用 isBlank() 排除空白单元，对「纯符号 / 纯标点 / 纯数字」
    // 乃至「符号占主体」的重复单元没有任何额外门槛，导致 Markdown 表格分隔行、ASCII
    // 分隔线、用户显式要求的重复载荷都会被判成模型退化并**提前中断生成**。
    //
    // 修复方式：detect() 改为单元三分类（纯空白 / 语义型 / 排版型），排版型走
    // symbolMinSpanChars=200 + symbolMinRepeats=32 的严格门槛。
    // 这 4 个用例是该修复的回归锁，请勿删除或 @Ignore。

    /**
     * Markdown 表格分隔行。模型输出表格是极常见场景，若被判定为复读会导致
     * 生成被提前中断，表格永远输出不完整。
     */
    @Test
    fun falsePositive_markdownTableSeparatorRow() {
        // 枚举列数，给出「几列开始误伤」的精确证据
        val tripped = (2..8).filter { cols ->
            val sep = "|" + " --- |".repeat(cols)
            val table = "下面是对比结果：\n\n|" + " 列 |".repeat(cols) + "\n" + sep + "\n"
            feedStreaming(table).first.tripped
        }
        assertTrue(
            "Markdown 表格分隔行「| --- | ...」在列数 $tripped 时被误判为复读退化，生成会被中断",
            tripped.isEmpty(),
        )
    }

    /** 紧凑写法的表格分隔行（6 列）。 */
    @Test
    fun falsePositive_compactMarkdownTableSeparator() {
        val text = "结果如下：\n|A|B|C|D|E|F|\n|---|---|---|---|---|---|\n"
        val guard = feedStreaming(text).first
        assertFalse(
            "紧凑表格分隔行被误判：phrase=\"${guard.detection?.phrase}\" x${guard.detection?.repeats}",
            guard.tripped,
        )
    }

    /** 长分隔线 / ASCII 排版横线。 */
    @Test
    fun falsePositive_longHorizontalRule() {
        val text = "小结：\n" + "=".repeat(40) + "\n以上就是全部内容。"
        val guard = feedStreaming(text).first
        assertFalse(
            "ASCII 分隔线被误判：phrase=\"${guard.detection?.phrase}\" x${guard.detection?.repeats}",
            guard.tripped,
        )
    }

    /** 用户显式要求输出的重复内容（如全 0 位串）。 */
    @Test
    fun falsePositive_userRequestedRepeatedPayload() {
        val text = "好的，32 位全 0 的二进制串是：\n" + "0".repeat(32)
        val guard = feedStreaming(text).first
        assertFalse(
            "用户显式要求的重复内容被误判：phrase=\"${guard.detection?.phrase}\" x${guard.detection?.repeats}",
            guard.tripped,
        )
    }

    // ------------------------------------------- 排版型长单元死区回归（p = 33..64）
    //
    // 背景：排版型门槛若写成「固定 32 次重复」，40 字符的整行分隔行需要 32×40 = 1280 字符
    // 才可能命中，已超出 scanWindowChars(1024) —— p 在 33..64 区间成为**永远抓不到的死区**，
    // 而这恰是 maxPhraseChars 提到 64 后新纳入扫描的区间。修复方式是让门槛取
    // min(symbolMinRepeats, ceilDiv(symbolMinSpanChars, p))，令跨度成为唯一主约束。
    // 下面两条用例一正一反，把这个修复锁死。

    /** 整行表格分隔行（41 字符）连续复读 5 次 = 205 字符 >= 200：必须命中。 */
    @Test
    fun symbolLongUnit_wholeSeparatorRowRepeat_isDetected() {
        val row = "|" + "---|".repeat(10)
        assertEquals("前置条件：单元长度应为 41", 41, row.length)
        assertTrue("前置条件：必须落在死区区间 33..64", row.length in 33..64)
        assertTrue("前置条件：必须是排版型单元（无字母）", row.none { Character.isLetter(it) })

        val text = row.repeat(5)
        assertTrue("前置条件：跨度 ${text.length} 必须 >= symbolMinSpanChars(200)", text.length >= 200)

        val (guard, at) = feedStreaming(text)
        assertTrue(
            "p=${row.length} 的整行分隔行复读必须被检出（旧的固定 32 次门槛会漏检）",
            guard.tripped,
        )
        val d = guard.detection!!
        assertEquals("命中单元应是整行分隔行", row, d.phrase)
        assertEquals("应在第 5 次重复处命中", 5, d.repeats)
        assertEquals("应在跨度刚够 200 的第 205 个字符处截断", 205, at)
    }

    /** 同一个 41 字符单元只重复 3 次 = 123 字符 < 200：不许命中（证明跨度主约束仍生效）。 */
    @Test
    fun symbolLongUnit_repeatBelowMinSpan_doesNotTrip() {
        val row = "|" + "---|".repeat(10)
        val text = row.repeat(3)
        assertTrue("前置条件：跨度 ${text.length} 必须 < symbolMinSpanChars(200)", text.length < 200)

        val guard = feedStreaming(text).first
        assertFalse(
            "放宽重复次数门槛后 symbolMinSpanChars=200 必须仍是真实生效的保护线，" +
                "实际命中：phrase=\"${guard.detection?.phrase}\" x${guard.detection?.repeats}",
            guard.tripped,
        )
        assertFalse(feedWhole(text).tripped)
    }
}
