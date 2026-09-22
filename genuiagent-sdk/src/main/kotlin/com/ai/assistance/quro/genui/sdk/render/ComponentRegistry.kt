package com.ai.assistance.quro.genui.sdk.render

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderChildren

/**
 * 组件渲染器类型别名
 * 接收组件、渲染上下文，返回 Composable 内容
 */
typealias ComponentRenderer = @Composable (UIComponent, RenderContext) -> Unit

/**
 * 组件注册表，管理组件类型到渲染函数的映射
 */
class ComponentRegistry {

    private val renderers = mutableMapOf<String, ComponentRenderer>()

    /**
     * 回退渲染器，当找不到对应类型的渲染器时使用
     */
    var fallback: ComponentRenderer = { component, ctx ->
        FallbackRenderer(component, ctx)
    }

    /**
     * 注册一个组件渲染器
     */
    fun register(type: String, renderer: ComponentRenderer) {
        renderers[type] = renderer
    }

    /**
     * 批量注册组件渲染器
     */
    fun registerAll(map: Map<String, ComponentRenderer>) {
        renderers.putAll(map)
    }

    /**
     * 根据组件类型解析渲染器
     * 如果找不到则返回 fallback（自动降级），同时收集未知组件类型
     */
    fun resolve(type: String): ComponentRenderer {
        return renderers[type] ?: run {
            // 收集未知组件类型，用于自动注册引擎分析
            AutoRegisterEngine.collectUnknown(type)
            fallback
        }
    }

    /**
     * 检查指定类型是否已注册
     */
    fun isRegistered(type: String): Boolean {
        return type in renderers
    }

    /**
     * 获取所有已注册的组件类型
     */
    fun registeredTypes(): Set<String> {
        return renderers.keys.toSet()
    }
}

/**
 * 回退渲染器 — 遇到未知组件时的降级策略
 *
 * 不要让界面崩溃，而是：
 * 1. 显示一个警告标签（标明这是未知组件）
 * 2. 尽力渲染 children（内容不丢失）
 * 3. 用卡片样式包裹，保持视觉层次
 *
 * 这样即使 AI 输出了不支持的组件类型，
 * 用户仍然能看到内容，而不是空白或报错。
 */
@Composable
fun FallbackRenderer(component: UIComponent, ctx: RenderContext) {
    val hasChildren = component.children.isNotEmpty()

    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 警告标签
            if (component.type.isNotBlank()) {
                Text(
                    text = "⚠ 未知组件: ${component.type}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            // 尽力渲染 children（最重要：内容不丢失）
            if (hasChildren) {
                RenderChildren(component.children, ctx)
            }
        }
    }
}
