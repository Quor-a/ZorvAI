package com.ai.assistance.quro.core.tools

import com.yuanbao.miniapp.nativeapi.WxApi
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * miniapp_sdk 的 wx.* 能力面「描述 vs 引擎」一致性卡子。
 *
 * 🔴 为什么必须有这个测试（本轮血的教训）：
 * MiniAppSdkTool.description 里原先**手抄**了一份 wx 接口清单，只有 10 条，
 * 而且**写错了 3 个名字** —— 写的是 wx.setStorage / getStorage / removeStorage，
 * 而引擎当时只有 *Sync 版本。于是 AI 照着手抄清单写 wx.setStorage({key,data})，
 * 会命中 LogicRuntime.WX_UNSUPPORTED_FALLBACKS 里的空壳兜底：
 * 不报错、不抛异常、数据永远不落盘。这类"静默半残"缺陷没有任何编译期或运行期信号，
 * 只能靠字符串断言把它钉住。
 *
 * 现在描述改成从 [WxApi.API_NAMES] 现读，所以这个测试断言的是**契约**：
 * 1. 清单里出现的每个 wx 名字都必须是引擎真有的（不许臆造）；
 * 2. 引擎有的常用接口必须在描述里被点名（不许漏）；
 * 3. 描述必须说明清单是现读的（防止有人又改回手抄）。
 */
class MiniAppSdkToolApiSurfaceTest {

    private val desc: String get() = MiniAppSdkTool().description

    /**
     * 描述里形如 wx.xxx 的名字集合。
     *
     * 🔴 正则必须用**非贪婪 + 词尾**：`wx\.([A-Za-z_][A-Za-z0-9_]*?)(?![A-Za-z0-9_])`。
     * 写成贪婪的 `[A-Za-z0-9_]*` 时，"wx.setStorage / wx.setStorageSync" 这种
     * 一行里出现两处的前缀关系会被错抓成 "setStorage"（把 Sync 版吃掉），
     * 于是把合法名字误判成"引擎不存在" —— 这是本测试第一版的真实 bug。
     */
    private fun mentionedApiNames(): Set<String> =
        Regex("""wx\.([A-Za-z_][A-Za-z0-9_]*?)(?![A-Za-z0-9_])""").findAll(desc)
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `描述点名的每个 wx 接口引擎都真实存在`() {
        val real = WxApi.API_NAMES.toSet()
        // 这几个是 wx 命名空间之外的宿主能力，描述里以 wx. 形式提到时也算合法
        val allowedExtra = setOf("request")
        val bogus = mentionedApiNames() - real - allowedExtra
        assertTrue(
            "描述里出现了引擎不存在的接口（AI 照写必然静默失败）：$bogus",
            bogus.isEmpty()
        )
    }

    @Test
    fun `AI 最常用的接口都必须在描述里被点名`() {
        val desc2 = desc
        val mustMention = listOf(
            // 异步存储版：AI 绝大多数写的是这个，之前恰恰是空壳
            "wx.setStorage", "wx.getStorage", "wx.removeStorage",
            // 同步存储版
            "wx.setStorageSync", "wx.getStorageSync",
            // 交互
            "wx.showModal", "wx.showActionSheet", "wx.showToast",
            // 路由
            "wx.navigateTo", "wx.redirectTo", "wx.navigateBack",
            // 设备
            "wx.vibrateShort", "wx.getNetworkType", "wx.makePhoneCall",
            "wx.setClipboardData",
            // 本轮新增
            "wx.getStorageInfo", "wx.pageScrollTo", "wx.setNavigationBarColor",
            "wx.getLaunchOptionsSync", "wx.getRealtimeLogManager",
        )
        val missing = mustMention.filterNot { desc2.contains(it) }
        assertTrue("描述漏掉了这些接口，AI 不会去用：$missing", missing.isEmpty())
    }

    @Test
    fun `描述声明清单是现读而非手抄`() {
        assertTrue(
            "描述必须引用 WxApi.API_NAMES 现读，否则清单会再次漂移",
            desc.contains("WxApi.API_NAMES")
        )
    }

    @Test
    fun `描述明确禁止凭微信文档臆造接口`() {
        assertTrue(
            "必须提醒模型不要臆造未实现的 wx 接口（未实现只兜底不报错，静默半残）",
            desc.contains("臆造") || desc.contains("不要凭微信文档")
        )
    }

    @Test
    fun `异步与同步存储两套都在描述里`() {
        // 微信官方文档主推异步版，AI 默认写异步；两套都得在
        assertTrue(desc.contains("setStorageSync"))
        assertTrue(desc.contains("异步版"))
    }

    @Test
    fun `引擎清单规模不低于 30 个接口`() {
        // 防止有人误删 API_NAMES 导致大面积退化而无告警
        assertTrue(
            "wx 接口数异常减少：${WxApi.API_NAMES.size}",
            WxApi.API_NAMES.size >= 30
        )
    }

    @Test
    fun `API_NAMES 无重复项`() {
        val dup = WxApi.API_NAMES.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("API_NAMES 有重复：$dup", dup.isEmpty())
    }
}