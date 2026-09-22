package com.ai.assistance.quro.genui.aiapp.renderx

import com.ai.assistance.quro.genui.sdk.components.BuiltinComponents
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import org.junit.Test

/**
 * 诊断用：把「组件目录 / 实际注册表 / 提示词清单」三者的缺口打出来（跑真代码，不靠正则猜）。
 * 输出在 build/test-results/.../TEST-*.xml 的 system-out 里。
 */
class GenUiRegistryAuditTest {

    @Test
    fun audit() {
        val reg = BuiltinComponents.createRegistry()
        val registered = reg.registeredTypes()

        // ComponentTypes 里全部 const 值（用反射拿，避免正则漏项）
        val allConsts = ComponentTypes::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { f ->
                f.isAccessible = true
                (f.get(null) as? String)?.let { v -> f.name to v }
            }

        val sb = StringBuilder()
        sb.appendLine("AUDIT_BEGIN")
        sb.appendLine("registry.registeredTypes = ${registered.size}")
        sb.appendLine("ComponentTypes constants = ${allConsts.size}")
        sb.appendLine("ALL_TYPES = ${ComponentTypes.ALL_TYPES.size}")

        val constVals = allConsts.map { it.second }.toSet()
        val constNameToVal = allConsts.toMap()

        val notInRegistry = constVals - registered
        val inRegistryNotConstant = registered - constVals
        val inAllTypesNotRegistry = ComponentTypes.ALL_TYPES - registered
        val constNotInAllTypes = constVals - ComponentTypes.ALL_TYPES

        sb.appendLine("constVals - registered (定义了没注册) = ${notInRegistry.size}")
        notInRegistry.sorted().forEach { sb.appendLine("  UNREGISTERED $it") }
        sb.appendLine("registered - constVals (注册了但没常量) = ${inRegistryNotConstant.size}")
        inRegistryNotConstant.sorted().forEach { sb.appendLine("  EXTRA $it") }
        sb.appendLine("ALL_TYPES - registered = ${inAllTypesNotRegistry.size}")
        inAllTypesNotRegistry.sorted().forEach { sb.appendLine("  DECLARED_UNRENDERED $it") }
        sb.appendLine("constVals - ALL_TYPES (常量未进 ALL_TYPES) = ${constNotInAllTypes.size}")
        sb.appendLine("AUDIT_END")
        println(sb.toString())

        // 用断言把结果带进报告（故意失败以输出全部内容到 stdout）
        org.junit.Assert.assertTrue(sb.toString(), true)
    }
}
