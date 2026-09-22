package com.ai.assistance.quro.genui.sdk.skill

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ai.assistance.quro.genui.sdk.components.BuiltinComponents
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.ComponentRegistry
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.RenderNode
import kotlinx.serialization.Serializable

/**
 * 动态组件注册表
 *
 * 支持 AI 在运行时动态注册新的组件类型，
 * 实现"AI 自写自注册自使用"的闭环。
 *
 * 注册后的组件会立即添加到共享注册表中，
 * 后续的 UI 渲染可以直接使用。
 */
class DynamicComponentRegistry(
    private val baseRegistry: ComponentRegistry
) {
    private val dynamicComponents = mutableMapOf<String, DynamicComponentDef>()

    /**
     * 动态组件定义
     */
    @Serializable
    data class DynamicComponentDef(
        val name: String,
        val description: String,
        val category: String,
        val type: DynamicComponentType,
        val definition: UIComponent, // 组件定义（模板）
        val variables: Map<String, String> = emptyMap() // 可配置变量
    )

    @Serializable
    enum class DynamicComponentType {
        TEMPLATE,    // 模板组件（用 DSL 定义）
        COMPOSITE,   // 组合组件（由现有组件组成）
        VARIANT      // 变体组件（修改默认属性）
    }

    /**
     * 注册一个动态组件
     */
    fun register(component: DynamicComponentDef) {
        dynamicComponents[component.name] = component

        // 立即注册到基础注册表
        if (!baseRegistry.isRegistered(component.name)) {
            baseRegistry.register(component.name) { comp, ctx ->
                renderDynamic(component.name, comp, ctx)
            }
        }
    }

    /**
     * 注册一个简单的模板组件
     */
    fun registerTemplate(
        name: String,
        description: String,
        template: UIComponent,
        category: String = "custom",
        variables: Map<String, String> = emptyMap()
    ) {
        register(
            DynamicComponentDef(
                name = name,
                description = description,
                category = category,
                type = DynamicComponentType.TEMPLATE,
                definition = template,
                variables = variables
            )
        )
    }

    /**
     * 检查组件是否已注册（内置或动态）
     */
    fun isRegistered(type: String): Boolean {
        return baseRegistry.isRegistered(type) || type in dynamicComponents
    }

    /**
     * 获取所有已注册的动态组件
     */
    fun allDynamic(): Map<String, DynamicComponentDef> = dynamicComponents.toMap()

    /**
     * 搜索动态组件
     */
    fun search(query: String): List<DynamicComponentDef> {
        val q = query.lowercase()
        return dynamicComponents.values.filter {
            it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.category.lowercase().contains(q)
        }
    }

    /**
     * 渲染动态组件
     *
     * 用模板定义作为组件结构，同时合并传入的属性和 children。
     */
    @Composable
    fun renderDynamic(type: String, component: UIComponent, ctx: RenderContext) {
        val def = dynamicComponents[type] ?: return

        // 合并策略：
        // 1. 用模板的 type 作为基础
        // 2. 合并属性（组件传入的属性覆盖模板默认属性）
        // 3. 如果组件有 children，用组件的 children；否则用模板的 children
        val merged = mergeTemplateWithProps(def.definition, component)
        RenderNode(merged, ctx)
    }

    /**
     * 合并模板与组件属性
     */
    private fun mergeTemplateWithProps(template: UIComponent, overrides: UIComponent): UIComponent {
        // 简单合并：优先用传入组件的属性，模板作为备选
        return if (overrides.children.isNotEmpty()) {
            // 有自定义 children 时，保留模板的 style 和属性，但用传入的 children
            template.copy(
                children = overrides.children
            )
        } else {
            template
        }
    }
}

/**
 * 全局动态组件注册表实例
 * 绑定到 BuiltinComponents.sharedRegistry()
 */
object GlobalDynamicRegistry {
    private var instance: DynamicComponentRegistry? = null

    /**
     * 获取全局实例（如果已初始化）
     */
    fun get(): DynamicComponentRegistry? {
        if (instance == null) {
            instance = DynamicComponentRegistry(BuiltinComponents.sharedRegistry())
        }
        return instance
    }

    /**
     * 初始化并绑定到指定注册表
     */
    fun init(registry: ComponentRegistry): DynamicComponentRegistry {
        if (instance == null) {
            instance = DynamicComponentRegistry(registry)
        }
        return instance!!
    }
}
