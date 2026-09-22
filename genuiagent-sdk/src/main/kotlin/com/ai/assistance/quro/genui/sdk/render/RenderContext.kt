package com.ai.assistance.quro.genui.sdk.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.interaction.ActionExecutor
import com.ai.assistance.quro.genui.sdk.state.DialogHolder
import com.ai.assistance.quro.genui.sdk.state.GenUIStateStore
import com.ai.assistance.quro.genui.sdk.style.FontProvider
import com.ai.assistance.quro.genui.sdk.style.GenUITheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement

/**
 * 渲染上下文，持有渲染过程中需要的所有依赖
 */
class RenderContext(
    val theme: GenUITheme,
    val state: GenUIStateStore,
    val executor: ActionExecutor,
    val dialogHolder: DialogHolder,
    val fontProvider: FontProvider,
    val registry: ComponentRegistry,
    val scope: CoroutineScope
) {
    /**
     * 在 Composable 中观察状态变化，返回当前状态快照
     */
    @Composable
    fun observeState(): Map<String, JsonElement> {
        val stateMap by state.state.collectAsState()
        return stateMap
    }

    /**
     * 解析组件的数据绑定，将 ${state.xxx} 表达式替换为实际值
     */
    fun resolveBindings(component: UIComponent): Map<String, String> {
        if (component.dataBinding.isEmpty()) return emptyMap()
        return component.dataBinding.mapValues { (_, value) ->
            state.resolveBinding(value) ?: value
        }
    }

    /**
     * 获取组件的点击事件处理器
     * @param eventName 事件名称，默认为 "onClick"
     * @return 如果事件不存在则返回 null
     */
    fun clickHandler(component: UIComponent, eventName: String = "onClick"): (() -> Unit)? {
        return executor.handlerFor(component, eventName)
    }

    /**
     * 创建一个新的 RenderContext，替换其中的 state
     */
    fun withState(newState: GenUIStateStore): RenderContext {
        return RenderContext(theme, newState, executor, dialogHolder, fontProvider, registry, scope)
    }
}
