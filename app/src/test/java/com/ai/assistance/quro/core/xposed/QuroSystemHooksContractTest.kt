package com.ai.assistance.quro.core.xposed

import com.ai.assistance.quro.core.assistant.QuroAgentBridgeProtocol
import com.ai.assistance.quro.core.assistant.QuroAssistantCapabilities
import com.ai.assistance.quro.core.tools.QuroToolRegistry
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 批次 2/3/4（无障碍保护 / 一圈即搜 / 厂商助手接管）的**契约测试**。
 *
 * 这一层的价值不在「跑通某个 ROM 的 Hook」——那必须真机验证——而在防止
 * **四处手写的东西互相漂移**：
 *  · 代码里写死的包名 ↔ `scope.list` ↔ `QuroModuleConfig.DECLARED_SCOPE`；
 *  · Hook 里读的开关键 ↔ `ALL_SWITCHES`（写错一个键，开关永远读不到，功能静默失效）；
 *  · 被保护的无障碍组件名 ↔ Manifest 里真实声明的 Service；
 *  · 桥协议 method 常量 ↔ Provider 的 `when` 分支；
 *  · 能力目录 / 厂商能力映射里写的工具名 ↔ 真实注册表（写错就是「模型看得见、点不动」）。
 *
 * 找不到目标的 ROM 兼容属于运行时降级，由 `QuroHookRegistrar` 的 MISSING 记录表示；
 * 这里只保证「代码自身不自相矛盾」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuroSystemHooksContractTest {

    private val hooksDir = "app/src/main/java/com/ai/assistance/quro/core/xposed"

    private fun repoFile(relative: String): File = listOf(
        File(relative),
        File("../$relative"),
        File("../../$relative"),
    ).firstOrNull { it.exists() }
        ?: throw AssertionError("找不到仓库文件：$relative（cwd=${File(".").absolutePath}）")

    private fun source(name: String): String = repoFile("$hooksDir/$name").readText()

    private val hookSources: List<String> by lazy {
        repoFile(hooksDir).listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .map { it.readText() }
    }

    private fun configConst(name: String): String? = runCatching {
        QuroModuleConfig::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(null) as? String
    }.getOrNull()

    private fun keyConst(name: String): String? = runCatching {
        QuroModuleConfig.Keys::class.java.getDeclaredField(name)
            .apply { isAccessible = true }
            .get(null) as? String
    }.getOrNull()

    @Test
    fun `被保护的无障碍组件名与清单声明一致`() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        assertEquals(
            "Manifest 必须声明无障碍服务，否则写回一个不存在的组件等于无效保护",
            true,
            manifest.contains("android:name=\".service.QuroAccessibilityService\""),
        )
        assertEquals(
            "无障碍服务必须 exported=true，系统进程才能跨 UID 绑定",
            true,
            Regex("""android:name="\.service\.QuroAccessibilityService"[\s\S]{0,200}?android:exported="true"""")
                .containsMatchIn(manifest),
        )
        assertEquals(
            true,
            manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"),
        )

        val src = source("QuroAccessibilityProtectionHooks.kt")
        val flat = Regex("""SERVICE_FLAT\s*=\s*"([^"]+)"""").find(src)?.groupValues?.get(1)
        val short = Regex("""SERVICE_SHORT\s*=\s*"([^"]+)"""").find(src)?.groupValues?.get(1)
        assertEquals(
            "全名格式必须是 包名/完整类名",
            "com.ai.assistance.quro/com.ai.assistance.quro.service.QuroAccessibilityService",
            flat,
        )
        assertEquals(
            "短名格式必须是 包名/.类名，且与全名指向同一个组件",
            "com.ai.assistance.quro/.service.QuroAccessibilityService",
            short,
        )
    }

    @Test
    fun `除 system 外每个声明作用域都有对应功能域分发`() {
        val featureSrc = source("QuroPackageFeatureHooks.kt")
        val missing = mutableListOf<String>()
        QuroModuleConfig.DECLARED_SCOPE.filter { it != QuroModuleConfig.SYSTEM }.forEach { pkg ->
            val constName = listOf(
                "SYSTEM_UI", "GOOGLE", "BREENO", "COLOR_DIRECT",
                "AIMEMORY", "XIAOAI", "MIUI_HOME", "MIUI_GLOBAL_HOME",
            ).firstOrNull { configConst(it) == pkg }
            if (constName == null) {
                missing += "$pkg（常量表里没有对应常量名）"
            } else if (!featureSrc.contains("QuroModuleConfig.$constName")) {
                missing += "$pkg（QuroPackageFeatureHooks 没有分发到 QuroModuleConfig.$constName）"
            }
        }
        assertTrue("作用域声明了但没有功能域实现：$missing", missing.isEmpty())
    }

    @Test
    fun `Hook 引用的开关键全部在开关表内`() {
        val declared = QuroModuleConfig.ALL_SWITCHES.map { it.key }.toSet() + QuroModuleConfig.STRING_KEYS
        val bad = mutableListOf<String>()
        hookSources.forEach { text ->
            Regex("""QuroModuleConfig\.Keys\.([A-Z_]+)""").findAll(text).forEach { m ->
                val name = m.groupValues[1]
                val key = keyConst(name)
                if (key == null) {
                    bad += "$name（Keys 里不存在）"
                } else if (key !in declared) {
                    bad += "$name=$key（既不在 ALL_SWITCHES 也不在 STRING_KEYS）"
                }
            }
        }
        assertTrue("Hook 读了未登记的开关：${bad.distinct()}", bad.isEmpty())
    }

    @Test
    fun `电源键三种目标都有分支且默认保留厂商助手`() {
        // 分发实现现在落在 QuroAssistantRole.showSession（system_server 侧统一入口），
        // 电源键只负责「识别到长按 + 取上下文 + 去重 + 震感」，因此两处一起核对。
        val src = source("QuroPowerAssistantHooks.kt") + source("QuroAssistantRole.kt")
        assertEquals(3, QuroModuleConfig.PowerTarget.ALL.size)
        QuroModuleConfig.PowerTarget.ALL.forEach { target ->
            assertTrue(
                "电源键分发缺少 $target 分支",
                src.contains("QuroModuleConfig.PowerTarget.${target.uppercase()}"),
            )
        }
        assertEquals(
            "默认目标必须是 vendor，升级不得改写用户既有选择",
            QuroModuleConfig.PowerTarget.VENDOR,
            QuroModulePrefs.powerTarget(),
        )
    }

    @Test
    fun `电源键上下文必须从 this$0 的 PhoneWindowManager 取`() {
        val src = source("QuroPowerAssistantHooks.kt")
        assertTrue(
            "OplusSpeechHandler 自己没有 mContext；必须经 this$0 → mPhoneWindowManager 取上下文",
            src.contains("\"this\$0\""),
        )
        assertTrue(
            "缺少 mPhoneWindowManager 解析，长按电源键会永远拿不到 Context",
            src.contains("mPhoneWindowManager"),
        )
    }

    @Test
    fun `厂商助手接管挂在协议层而不是 Activity 入口`() {
        val feature = source("QuroPackageFeatureHooks.kt")
        assertTrue(
            "小布必须分发到协议层 Hook",
            feature.contains("QuroBreenoHooks.install"),
        )
        assertTrue(
            "超级小爱必须分发到协议层 Hook",
            feature.contains("QuroXiaoAiHooks.install"),
        )
        assertTrue(
            "不得再挂 Activity.onNewIntent 冒充厂商助手入口（拿不到协议层文本）",
            !feature.contains("vendor.assist-intent"),
        )

        val breeno = source("QuroBreenoTargets.kt")
        listOf(
            "AIChatDataCenter",
            "AIChatViewBean",
            "MessageQueueManager",
            "HeytapSpeechEngine",
            "StreamTextCard",
        ).forEach { anchor ->
            assertTrue("小布协议层缺少稳定锚点：$anchor", breeno.contains(anchor))
        }

        val xiaoai = source("QuroXiaoAiHooks.kt")
        assertTrue(
            "超级小爱必须用 setQueryInfo 捕获提问",
            xiaoai.contains("setQueryInfo"),
        )
        assertTrue(
            "超级小爱卡片管理器必须由 getFloatManager 的返回类型推导（不写死混淆类名）",
            xiaoai.contains("getFloatManager"),
        )
        assertTrue(
            "超级小爱回答必须构造厂商卡片",
            xiaoai.contains("FlowTemplateToastCard"),
        )
    }

    @Test
    fun `小布回答必须写回厂商气泡`() {
        val sink = source("QuroBreenoAnswerSink.kt")
        assertTrue("回答必须以 StreamTextCard 指令下发", sink.contains("StreamTextCard"))
        assertTrue("指令命名空间必须是 MyAI", sink.contains("\"MyAI\""))
        assertTrue(
            "必须能通过 agent 下发指令，否则回答只能停留在本应用",
            sink.contains("dispatchDirectives"),
        )
    }

    @Test
    fun `默认助理写入的组件必须真实存在`() {
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val role = source("QuroAssistantRole.kt")

        assertTrue(
            "默认助理组件必须指向真正的 VoiceInteractionService",
            role.contains("com.ai.assistance.quro.service.QuroVoiceInteractionService"),
        )
        assertTrue(
            "Manifest 必须声明该 VoiceInteractionService",
            manifest.contains("android:name=\".service.QuroVoiceInteractionService\""),
        )
        assertTrue(
            "VoiceInteractionService 必须由系统签名权限保护",
            manifest.contains("android.permission.BIND_VOICE_INTERACTION"),
        )
        assertTrue(
            "缺少 voice_interaction 元数据，系统不会把它当成可用助手",
            manifest.contains("android.voice_interaction"),
        )

        val metaXml = repoFile("app/src/main/res/xml/quro_voice_interaction.xml").readText()
        assertTrue(
            "元数据必须声明会话服务",
            metaXml.contains("QuroVoiceInteractionSessionService"),
        )
        assertTrue(
            "必须声明 supportsAssist，否则长按 Home/电源键不会唤起",
            metaXml.contains("android:supportsAssist=\"true\""),
        )
    }

    @Test
    fun `桥协议方法全部在 Provider 中实现`() {
        val provider = repoFile(
            "app/src/main/java/com/ai/assistance/quro/core/assistant/QuroAgentBridgeProvider.kt",
        ).readText()
        // 用常量名而不是字符串值来核对：Provider 里写的是 METHOD_XXX ->，不是裸字符串。
        val constNames = QuroAgentBridgeProtocol::class.java.declaredFields
            .filter { it.type == String::class.java && it.name.startsWith("METHOD_") }
            .mapNotNull { f ->
                runCatching {
                    f.isAccessible = true
                    (f.get(null) as? String)?.let { v -> v to f.name }
                }.getOrNull()
            }
            .toMap()
        assertEquals(
            "METHOD_* 常量数量与 METHODS 不一致",
            QuroAgentBridgeProtocol.METHODS.size,
            constNames.size,
        )
        val missing = QuroAgentBridgeProtocol.METHODS.filter { value ->
            val constName = constNames[value]
            constName == null || !provider.contains("QuroAgentBridgeProtocol.$constName")
        }
        assertTrue("协议声明了但 Provider 未处理的 method：$missing", missing.isEmpty())
        assertEquals(
            "method 常量不得重复",
            QuroAgentBridgeProtocol.METHODS.size,
            QuroAgentBridgeProtocol.METHODS.toSet().size,
        )
    }

    @Test
    fun `能力目录与厂商映射里的工具名都在真实注册表内`() {
        val reg: QuroToolRegistry = buildQuroRegistry(RuntimeEnvironment.getApplication())
        val names = buildSet {
            QuroAssistantCapabilities.CAPABILITY_DOMAINS.forEach { (_, tools) -> addAll(tools) }
            QuroAssistantCapabilities.VENDOR_CAPABILITIES.values.forEach { caps ->
                caps.forEach { addAll(it.quroCounterparts) }
            }
        }
        val ghost = names.filter { reg.get(it) == null }
        assertTrue(
            "能力清单写了下发不到的工具名（模型看得见点不动）：$ghost",
            ghost.isEmpty(),
        )
        assertTrue("能力清单不应为空", names.isNotEmpty())
    }

    @Test
    fun `厂商接管只覆盖声明过的入口来源`() {
        val sources = QuroAgentBridgeProtocol.SOURCES.toSet()
        val outside = QuroAssistantCapabilities.VENDOR_CAPABILITIES.keys.filter { it !in sources }
        assertTrue("厂商能力映射用了未登记的来源：$outside", outside.isEmpty())
        assertEquals(
            "入口来源常量不得重复",
            QuroAgentBridgeProtocol.SOURCES.size,
            sources.size,
        )
        assertNotNull(
            "小布与超级小爱必须都有能力清单",
            QuroAssistantCapabilities.VENDOR_CAPABILITIES[QuroAgentBridgeProtocol.SOURCE_BREENO],
        )
        assertNotNull(
            QuroAssistantCapabilities.VENDOR_CAPABILITIES[QuroAgentBridgeProtocol.SOURCE_XIAOAI],
        )
    }
}
