package com.ai.assistance.quro.core.xposed

import com.ai.assistance.quro.core.tools.QuroLsposeTool
import com.ai.assistance.quro.core.xposed.QuroXposedFrameworks.Family
import com.ai.assistance.quro.core.xposed.QuroXposedFrameworks.ZygiskProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * LSPosed 现代模块（API 102）的契约测试。
 *
 * 这里防的是「声明、常量表、UI、工具」四处手写互相漂移：
 *  · scope.list 少一行 → 某能力永远拿不到目标进程，UI 却显示已开启；
 *  · java_init.list 写错类名 → 模块根本不加载，框架服务却照样显示已连接；
 *  · 能力键重复或缺省为真 → 用户什么都没开就已经被 Hook；
 *  · 旧版 API 82 文件复活 → 新旧两套模块入口同时被 LSPosed 加载。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuroModuleContractTest {

    private fun repoFile(relative: String): File = listOf(
        File("../$relative"),
        File(relative),
        File("D:/Calw OS-project/QuroAI/$relative"),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("找不到 $relative（工作目录 = ${File(".").absolutePath}）")

    private fun repoPath(relative: String): File = listOf(
        File("../$relative"),
        File(relative),
        File("D:/Calw OS-project/QuroAI/$relative"),
    ).firstOrNull { it.exists() }
        ?: File("D:/Calw OS-project/QuroAI/$relative")

    // ---- 模块声明 ----

    @Test
    fun `三件套齐全且入口类指向真实存在的类`() {
        val prop = repoFile("app/src/main/resources/META-INF/xposed/module.prop").readText()
        assertTrue("module.prop 必须要求 API 102", prop.contains("minApiVersion=102"))
        assertTrue("module.prop 必须声明静态作用域", prop.contains("staticScope=true"))
        assertTrue("Hook 异常必须保护宿主进程", prop.contains("exceptionMode=protective"))

        val entry = repoFile("app/src/main/resources/META-INF/xposed/java_init.list")
            .readText().lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("java_init.list 只能有一个入口", 1, entry.size)
        assertEquals("com.ai.assistance.quro.core.xposed.QuroModuleMain", entry.single())
        val source = repoFile(
            "app/src/main/java/com/ai/assistance/quro/core/xposed/QuroModuleMain.kt"
        ).readText()
        assertTrue("入口类必须继承 XposedModule", source.contains("class QuroModuleMain : XposedModule()"))
    }

    @Test
    fun `scope_list 与常量表逐行一致且无重复`() {
        val lines = repoFile("app/src/main/resources/META-INF/xposed/scope.list")
            .readText().lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals("scope.list 出现重复行", lines.size, lines.toSet().size)
        assertEquals("scope.list 与 DECLARED_SCOPE 不一致", QuroModuleConfig.DECLARED_SCOPE, lines)
        assertTrue("必须包含 system（system_server）作用域", QuroModuleConfig.SYSTEM in lines)
    }

    @Test
    fun `旧版 API 82 模块不得复活`() {
        assertFalse(
            "assets/xposed_init 已退役，新旧入口并存会被框架重复加载",
            repoPath("app/src/main/assets/xposed_init").exists(),
        )
        assertFalse(
            "QuroXposedModule.java 应已删除",
            repoPath("app/src/main/java/com/ai/assistance/quro/core/privilege/QuroXposedModule.java").exists(),
        )
        assertFalse(
            "旧跨应用注入桥接收器应已删除",
            repoPath("app/src/main/java/com/ai/assistance/quro/core/privilege/QuroLsposeBridgeReceiver.kt").exists(),
        )
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        assertTrue("xposedminversion 必须同步抬到 102", manifest.contains("android:value=\"102\""))
        assertFalse("清单里不得残留旧接收器", manifest.contains("QuroLsposeBridgeReceiver"))
    }

    // ---- 能力开关 ----

    @Test
    fun `能力键不重复且作用域全部在声明清单内`() {
        val keys = QuroModuleConfig.ALL_SWITCHES.map { it.key }
        assertEquals("能力键出现重复", keys.size, keys.toSet().size)
        QuroModuleConfig.ALL_SWITCHES.forEach { def ->
            assertTrue("${def.key} 缺标题", def.title.isNotBlank())
            assertTrue("${def.key} 缺说明", def.summary.isNotBlank())
            def.scope.forEach { pkg ->
                assertTrue(
                    "${def.key} 引用了未声明的作用域 $pkg",
                    pkg in QuroModuleConfig.DECLARED_SCOPE,
                )
            }
        }
        assertEquals(
            "「系统助手与增强」必须是三条主能力",
            3,
            QuroModuleConfig.MAIN_SWITCHES.size,
        )
    }

    @Test
    fun `主能力缺省开启而写系统配置的开关缺省关闭`() {
        // 与 Eta 一致：装上就该能用的三项默认开启，避免「装完什么都没反应」。
        listOf(
            QuroModuleConfig.Keys.VENDOR_ASSISTANT_TAKEOVER,
            QuroModuleConfig.Keys.GEMINI_CIRCLE_TO_SEARCH,
            QuroModuleConfig.Keys.ACCESSIBILITY_PROTECTION,
        ).forEach { key ->
            assertTrue("$key 缺省必须开启", QuroModuleConfig.defaultOf(key))
        }
        // 会主动改写系统默认助理的开关必须由用户显式打开。
        assertFalse(
            "自动写系统配置的开关缺省不得开启",
            QuroModuleConfig.defaultOf(QuroModuleConfig.Keys.AUTO_DEFAULT_ASSISTANT),
        )
        QuroModuleConfig.ALL_SWITCHES.forEach { def ->
            assertEquals(def.key, def.defaultEnabled, QuroModuleConfig.defaultOf(def.key))
        }
        // 电源键缺省目标必须是厂商，升级不得改写用户既有选择。
        assertEquals(
            QuroModuleConfig.PowerTarget.VENDOR,
            QuroModuleConfig.PowerTarget.ALL.first(),
        )
        assertTrue("quro" in QuroModuleConfig.PowerTarget.ALL)
    }

    // ---- 框架状态 ----

    @Test
    fun `作用域缺失与已加载进程判定正确`() {
        val empty = QuroModuleStatus()
        assertFalse("未连接时不算在作用域", empty.connected)
        assertEquals(QuroModuleConfig.DECLARED_SCOPE, empty.missingScope)

        val full = QuroModuleStatus(
            connected = true,
            scope = QuroModuleConfig.DECLARED_SCOPE,
            runningTargets = listOf(
                QuroRunningTarget("com.android.systemui", "UP_TO_DATE", 1, 1000),
                QuroRunningTarget("system_server", "UP_TO_DATE", 2, 1000),
            ),
        )
        assertTrue("作用域齐了就不该报缺失", full.missingScope.isEmpty())
        assertTrue("system_server 应算作 system 作用域已加载", full.isScopeActive(QuroModuleConfig.SYSTEM))
        assertTrue("SystemUI 进程应算已加载", full.isScopeActive(QuroModuleConfig.SYSTEM_UI))
        assertFalse("Google App 没在已加载列表里", full.isScopeActive(QuroModuleConfig.GOOGLE))
    }

    // ---- 作用域分组语义（旧版「已开启但缺少作用域」刷屏的根因回归） ----

    @Test
    fun `ColorOS 上没装小米助手时小布接管不算缺作用域`() {
        val vendor = QuroModuleConfig.MAIN_SWITCHES
            .first { it.key == QuroModuleConfig.Keys.VENDOR_ASSISTANT_TAKEOVER }
        // 典型 ColorOS：只有小布与识屏服务，没有 com.miui.voiceassist
        val colorOsInstalled = setOf(QuroModuleConfig.BREENO, QuroModuleConfig.COLOR_DIRECT)
        val colorOsStatus = QuroModuleStatus(
            connected = true,
            scope = listOf(QuroModuleConfig.BREENO, QuroModuleConfig.COLOR_DIRECT),
        )
        assertTrue(
            "本机没安装的小米助手不该算缺失：${colorOsStatus.unmetScope(vendor, colorOsInstalled)}",
            colorOsStatus.unmetScope(vendor, colorOsInstalled).isEmpty(),
        )

        // 典型 HyperOS：只有超级小爱，没装小布
        val hyperInstalled = setOf(QuroModuleConfig.XIAOAI)
        val hyperStatus = QuroModuleStatus(connected = true, scope = listOf(QuroModuleConfig.XIAOAI))
        assertTrue(
            "本机没安装的小布不该算缺失",
            hyperStatus.unmetScope(vendor, hyperInstalled).isEmpty(),
        )
    }

    @Test
    fun `助手组内二选一已勾即满足且未勾时给出可操作清单`() {
        val vendor = QuroModuleConfig.MAIN_SWITCHES
            .first { it.key == QuroModuleConfig.Keys.VENDOR_ASSISTANT_TAKEOVER }
        val bothInstalled = setOf(QuroModuleConfig.BREENO, QuroModuleConfig.XIAOAI)
        val nothing = QuroModuleStatus(connected = true, scope = emptyList())
        assertEquals(
            "组内任一已装的助手未勾选时，应把它们列出来让用户选一个",
            listOf(QuroModuleConfig.BREENO, QuroModuleConfig.XIAOAI),
            nothing.unmetScope(vendor, bothInstalled),
        )
        val onlyBreeno = QuroModuleStatus(connected = true, scope = listOf(QuroModuleConfig.BREENO))
        assertTrue(onlyBreeno.unmetScope(vendor, bothInstalled).isEmpty())
    }

    @Test
    fun `补齐清单只包含本机已安装的目标`() {
        val status = QuroModuleStatus(
            connected = true,
            scope = listOf(QuroModuleConfig.SYSTEM, QuroModuleConfig.SYSTEM_UI),
        )
        val installed = setOf(QuroModuleConfig.BREENO, QuroModuleConfig.COLOR_DIRECT)
        val actionable = status.actionableMissingScope(installed)
        assertFalse(
            "未安装的小米助手不得进入补齐请求，否则框架整单拒绝",
            QuroModuleConfig.XIAOAI in actionable,
        )
        assertFalse("未安装的小爱 / 桌面同样不得进入", QuroModuleConfig.MIUI_HOME in actionable)
        assertTrue("已安装但没勾的小布必须在内", QuroModuleConfig.BREENO in actionable)
        assertFalse("已勾选的 system 不必重复补", QuroModuleConfig.SYSTEM in actionable)
        assertTrue(
            "system 不是包名但恒存在，没勾时必须可补",
            QuroModuleConfig.SYSTEM in QuroModuleStatus(
                connected = true,
                scope = listOf(QuroModuleConfig.SYSTEM_UI),
            ).actionableMissingScope(installed),
        )
    }

    @Test
    fun `未连接框架时偏好读取退回缺省而不抛异常`() {
        QuroModulePrefs.detach()
        assertFalse(QuroModulePrefs.hasConnection())
        QuroModuleConfig.ALL_SWITCHES.forEach { def ->
            assertEquals(def.key, def.defaultEnabled, QuroModulePrefs.isEnabled(def.key))
        }
        assertEquals(QuroModuleConfig.PowerTarget.VENDOR, QuroModulePrefs.powerTarget())
        assertFalse("未连接时不得允许写入", QuroModulePrefs.setPowerTarget("quro"))
        assertFalse(
            "未连接时不得允许写入",
            QuroModulePrefs.setEnabled(QuroModuleConfig.Keys.ACCESSIBILITY_PROTECTION, true),
        )
    }

    // ---- 对模型下发的工具 ----

    @Test
    fun `lsposed 工具 schema 无重复键且 action 与实现一致`() {
        val tool = QuroLsposeTool()
        assertEquals("lsposed", tool.name)
        val keys = propertiesKeys(tool.parametersJson)
        assertEquals("schema 出现重复键：$keys", keys.size, keys.toSet().size)
        listOf("action", "capability", "target").forEach {
            assertTrue("schema 缺 $it", it in keys)
        }
        listOf(
            "status", "scope", "enable", "disable", "set_power_target",
            "request_scope", "capabilities", "inbox",
        ).forEach {
            assertTrue("实现/描述必须提到 action=$it", tool.description.contains(it))
        }
    }

    @Test
    fun `lsposed status 在框架未连接时仍能安全返回结构化结果`() {
        val tool = QuroLsposeTool()
        val out = tool.run(RuntimeEnvironment.getApplication(), """{"action":"status"}""")
        assertTrue(out.contains("\"module_api\": 102"))
        assertTrue(out.contains("\"framework_connected\": false") ||
            out.contains("\"framework_connected\": true"))
        assertTrue(out.contains("capabilities"))
        val unknown = tool.run(RuntimeEnvironment.getApplication(), """{"action":"scope","x":1}""")
        assertTrue(unknown.contains("作用域") || unknown.contains("框架"))
    }

    /**
     * 字符级扫描 `properties` 对象第一层的**全部键**（含重复）。
     * JSONObject 解析时重复键静默取后者，只有看原始文本才能发现。
     */
    private fun propertiesKeys(schema: String): List<String> {
        val start = schema.indexOf("\"properties\"")
        require(start >= 0) { "schema 缺 properties" }
        val open = schema.indexOf('{', start)
        require(open >= 0) { "properties 不是对象" }

        val result = mutableListOf<String>()
        val text = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        var i = open
        loop@ while (i < schema.length) {
            val c = schema[i]
            if (inString) {
                when {
                    escaped -> { text.append(c); escaped = false }
                    c == '\\' -> { text.append(c); escaped = true }
                    c == '"' -> {
                        inString = false
                        var j = i + 1
                        while (j < schema.length && schema[j].isWhitespace()) j++
                        if (depth == 1 && j < schema.length && schema[j] == ':') {
                            result.add(text.toString())
                        }
                        text.setLength(0)
                    }
                    else -> text.append(c)
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) break@loop
                    }
                }
            }
            i++
        }
        return result
    }

    // ---- 新框架识别：Vector / ZygiskNext / API 102 ----

    @Test
    fun `Vector 管理器在识别表内且被标为 API 102`() {
        val vector = QuroXposedFrameworks.MANAGERS.firstOrNull {
            it.pkg == "org.matrix.vector.manager"
        }
        assertTrue("必须识别 Vector 管理器包名", vector != null)
        assertEquals(QuroXposedFrameworks.Family.VECTOR, vector!!.family)
        assertTrue("Vector 2.2 支持 API 102", vector.api102)
        assertEquals("*#*#832867#*#*", vector.dialCode)
        // 新框架必须排在旧框架前面，打开管理器时优先走 Vector。
        val vectorIndex = QuroXposedFrameworks.MANAGERS.indexOf(vector)
        val legacyIndex = QuroXposedFrameworks.MANAGERS.indexOfFirst {
            it.pkg == "de.robv.android.xposed.installer"
        }
        assertTrue("现代框架应排在旧框架之前", vectorIndex < legacyIndex)
        val pkgs = QuroXposedFrameworks.MANAGERS.map { it.pkg }
        assertEquals("管理器包名不得重复", pkgs.size, pkgs.toSet().size)
    }

    @Test
    fun `框架存在判定以服务连接为权威信号而不依赖管理器包名`() {
        val F = QuroXposedFrameworks
        assertTrue(
            "Vector 管理器可隐藏，但只要框架服务已连接就必须判定为已安装",
            F.isFrameworkPresent(frameworkConnected = true, installedManagerPkgs = emptyList()),
        )
        assertFalse(
            "既无连接也无管理器时应如实报未安装",
            F.isFrameworkPresent(frameworkConnected = false, installedManagerPkgs = emptyList()),
        )
        assertTrue(
            "服务未连接但装了 Vector 管理器，仍应判定框架存在",
            F.isFrameworkPresent(
                frameworkConnected = false,
                installedManagerPkgs = listOf("org.matrix.vector.manager"),
            ),
        )
    }

    @Test
    fun `框架家族判定覆盖 Vector 与新旧 LSPosed`() {
        val F = QuroXposedFrameworks
        assertEquals(Family.VECTOR, F.familyOf("Vector 2.2", 102, emptyList()))
        assertEquals(Family.VECTOR, F.familyOf("JingMatrix LSPosed", 102, emptyList()))
        assertEquals(Family.LSPOSED_MODERN, F.familyOf("LSPosed", 102, emptyList()))
        assertEquals(Family.LSPOSED_LEGACY, F.familyOf("LSPosed", 93, emptyList()))
        assertEquals(
            Family.VECTOR,
            F.familyOf(null, 0, listOf("org.matrix.vector.manager")),
        )
        assertEquals(Family.UNKNOWN, F.familyOf(null, 0, emptyList()))
    }

    @Test
    fun `API 102 兼容性判定不给旧框架开绿灯`() {
        val F = QuroXposedFrameworks
        assertTrue(F.supportsApi102(Family.VECTOR, 102))
        assertTrue(F.supportsApi102(Family.LSPOSED_MODERN, 102))
        assertFalse(F.supportsApi102(Family.LSPOSED_LEGACY, 93))
        assertFalse("API 未知时不得凭框架名假装可用", F.supportsApi102(Family.UNKNOWN, 0))
        assertTrue(F.compatibilityNote(Family.VECTOR, true, 102).contains("Vector"))
        assertTrue(F.compatibilityNote(Family.LSPOSED_LEGACY, true, 93).contains("102"))
    }

    @Test
    fun `Zygisk 提供者从模块目录清单正确识别`() {
        assertEquals(ZygiskProvider.ZYGISK_NEXT, QuroXposedFrameworks.zygiskFromModuleListing("zygisknext\nlspd"))
        assertEquals(ZygiskProvider.NEO_ZYGISK, QuroXposedFrameworks.zygiskFromModuleListing("nezygisk"))
        assertEquals(ZygiskProvider.RE_ZYGISK, QuroXposedFrameworks.zygiskFromModuleListing("rezygisk"))
        assertEquals(ZygiskProvider.UNKNOWN, QuroXposedFrameworks.zygiskFromModuleListing(null))
        assertEquals(ZygiskProvider.UNKNOWN, QuroXposedFrameworks.zygiskFromModuleListing(""))
    }

    @Test
    fun `未检测到框架时状态文本明确提到 Vector 与新框架`() {
        val ctx = RuntimeEnvironment.getApplication()
        val text = com.ai.assistance.quro.core.privilege.QuroLSPosed.statusText(ctx)
        assertTrue(text.contains("Vector"))
        assertTrue(text.contains("LSPosed"))
    }

    @Test
    fun `lsposed 工具 status 暴露新框架识别字段`() {
        val ctx = RuntimeEnvironment.getApplication()
        val json = org.json.JSONObject(QuroLsposeTool().run(ctx, """{"action":"status"}"""))
        assertEquals(102, json.getInt("module_api"))
        assertTrue(json.has("framework_family"))
        assertTrue(json.has("framework_label"))
        assertTrue(json.has("api102_supported"))
        assertTrue(json.has("compatibility"))
        assertTrue(json.has("zygisk_provider"))
        assertTrue(json.has("managers"))
        assertTrue(json.getString("compatibility").isNotBlank())
    }
}
