package com.ai.assistance.quro.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模糊匹配原语的**行为钉死**测试（[RagFuzzy] / [RagConcept] / [RagText]）。
 *
 * ## 为什么这些必须测
 * 四路融合的权重是拍出来的，但「拍得合不合理」只能靠单测证明；
 * 更关键的是几个**反直觉的坑**：
 *  - 字形通道权重不低，但它对短词噪声极大（「日」和「月」编辑距离 1）。
 *    若不限制短词容错，几乎所有单字查询都会命中一堆不相干的工具。
 *  - 拼音通道只收首字母不收全拼，这是**取舍**不是漏做，必须用测试说明取舍成立。
 *  - 意图扩展必须是**追加**不是替换，否则用户精确限定（「剪成30秒」）会被扩展词冲掉。
 */
class RagFuzzyTest {

    @Test
    fun 编辑距离符合直觉() {
        assertEquals(0, RagFuzzy.editDistance("微信", "微信"))
        assertEquals(1, RagFuzzy.editDistance("微信", "微心"))
        // 三个字符全不同 → 3 次替换
        assertEquals(3, RagFuzzy.editDistance("abc", "xyz"))
        // 长度不同的两个串，距离至少等于长度差
        assertTrue(RagFuzzy.editDistance("短", "稍微长一点") >= 3)
    }

    @Test
    fun 短词只容许零距离() {
        // 1 字、2 字：错一个字就判为不同，否则单字查询会全被判成近似
        assertTrue(RagFuzzy.isTypoMatch("日", "月") == false)
        assertTrue(RagFuzzy.isTypoMatch("图", "团") == false)
        // 3~4 字：允许 1 个错字
        assertTrue(RagFuzzy.isTypoMatch("微信登录", "微心登录"))
        // 更长：允许 2 个
        assertTrue(RagFuzzy.isTypoMatch("screen_record", "screen_reocrd"))
    }

    @Test
    fun 三元组相似度对称且自反() {
        assertTrue(RagFuzzy.trigramSimilarity("abc", "abc") > 0.99f)
        // 对称
        val a = RagFuzzy.trigramSimilarity("剪短视频", "视频剪辑")
        val b = RagFuzzy.trigramSimilarity("视频剪辑", "剪短视频")
        assertTrue(Math.abs(a - b) < 1e-6f)
        // 完全无关的两个串相似度应很低
        assertTrue(RagFuzzy.trigramSimilarity("天气查询", "剪短视频") < 0.2f)
    }

    @Test
    fun 拼音首字母走包含匹配而非全等() {
        // 参数序是 (query, target)：query 是用户输入的缩写，target 是待匹配的工具字段。
        // 真实用法就是缩写，全等匹配形同虚设。
        // 注意「微信登录」的首字母串是 wxdl，所以缩写必须是它的**连续子串**。
        assertTrue(RagFuzzy.pinyinScore("wx", "微信登录") > 0.3)
        assertTrue(RagFuzzy.pinyinScore("wxd", "微信登录") > 0.3)
        // 缩写比目标短 → 走包含分支，分数应低于全等的 1.0
        assertTrue(RagFuzzy.pinyinScore("wx", "微信登录") < 1.0)
        // 目标越短、缩写占比越高，分数越接近上限
        assertTrue(
            RagFuzzy.pinyinScore("wx", "微信登录") <
                RagFuzzy.pinyinScore("wx", "微信")
        )
        // 完全无关的缩写不得命中
        assertEquals(0.0, RagFuzzy.pinyinScore("zzz", "微信登录"), 1e-9)
        // 非连续缩写不是子串 → 不命中（避免「包含匹配」变成乱猜）
        assertEquals(0.0, RagFuzzy.pinyinScore("wxl", "微信登录"), 1e-9)
    }

    @Test
    fun 拼音只收首字母是取舍不是漏做() {
        // 设计取舍：全拼表要覆盖 2 万+ 汉字，成本与出错率高，
        // 而用户输入法打「wx」远比「weixin」快。
        // 这条断言是防止以后有人误以为是漏做而去「补全拼表」——
        // 补了没问题，但会挤占拼音通道权重预算，必须先更新这里的设计说明。
        assertTrue(RagFuzzy.pinyinScore("wx", "微信") > 0.3)
        // 全拼不命中是**预期行为**，写清楚以免被当成 bug 反复排查。
        assertEquals(0.0, RagFuzzy.pinyinScore("weixin", "微信"), 1e-9)
    }

    @Test
    fun 拼音逐token取最大而非整串() {
        // 「ziliao 搜索」整串转首字母会得到 "ziliaoss" 这种无意义串，
        // 逐 token 算才可能命中「资料」类目标。
        val whole = RagFuzzy.pinyinScore("zl ss", "查资料")
        val byToken = RagFuzzy.pinyinScoreTokenized("zl 搜索", "查资料") { RagText.tokenize(it) }
        assertTrue("逐 token 应能命中，实际=$byToken", byToken > 0.3)
        assertTrue(whole >= 0.0)
    }

    @Test
    fun 英文别名按词边界不污染标识符() {
        // 事故记录：ALIASES 里有 "net" to "联网"，
        // 用裸 contains 替换时 get_network_info 被归一化成 get_联网work_info，
        // 自己的名字再也匹配不上自己。
        assertEquals("get_network_info", RagConcept.normalize("get_network_info"))
        assertEquals("http_request", RagConcept.normalize("http_request"))
        // 独立成词时仍应替换
        assertEquals("联网", RagConcept.normalize("net"))
    }

    @Test
    fun 同义表双向等价() {
        val a = RagConcept.normalize("上网")
        val b = RagConcept.normalize("联网")
        assertEquals("联网", a)
        assertEquals("联网", b)
    }

    @Test
    fun 同义替换长词优先() {
        // 「上网查」不能被「上网」先吃掉成「联网查」——顺序错了语义就变了
        val r = RagConcept.normalize("上网查一下")
        assertTrue("不应把限定词吃掉，实际=$r", r.contains("查") || r.contains("搜索"))
    }

    @Test
    fun 意图扩展是追加不是替换() {
        // expandIntent 只返回「补进来的词」，原文由调用方另行保留——
        // 引擎里扩展词权重 0.45 低于原文 1.0，正是为了不冲掉用户的精确限定。
        val extra = RagConcept.expandIntent("把这段视频剪短到30秒")
        assertTrue("应补进视频类扩展词，实际=$extra", extra.isNotEmpty())
        assertTrue(
            "扩展词里应含视频/剪辑类词，实际=$extra",
            extra.any { it.contains("视频") || it.contains("剪辑") || it.contains("转码") }
        )
        // 未登记的句子不应凭空造词
        assertTrue(RagConcept.expandIntent("你好呀").isEmpty())
    }

    @Test
    fun 停用词被压制() {
        val stop = RagText.informativeness("一张")
        val real = RagText.informativeness("视频")
        assertTrue("停用词信息量应低于实词：stop=$stop real=$real", stop < real)
    }

    @Test
    fun 中文按二元切分() {
        val t = RagText.tokenize("视频剪辑")
        assertTrue(t.contains("视频"))
        assertTrue(t.contains("频剪"))
    }
}
