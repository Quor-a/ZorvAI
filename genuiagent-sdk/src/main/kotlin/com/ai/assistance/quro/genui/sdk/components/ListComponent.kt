package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderNode
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * 列表组件类型常量
 */
const val LIST_TYPE = ComponentTypes.LIST

/**
 * List列表组件渲染器
 *
 * 使用LazyColumn实现高性能列表渲染
 *
 * 支持的属性：
 * - items: 列表数据（JsonArray）
 * - dataKey: 状态中数据的键名（默认"items"）
 *
 * 支持的子组件：
 * - 第一个子组件作为列表项的模板
 *   - 列表项中可以通过 ${item.xxx} 访问当前项的数据
 */
@Composable
fun ListRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    // 获取列表数据：优先从属性获取，其次从状态获取
    val itemsJson = run {
        val propItems = component.properties["items"]
        if (propItems is JsonArray) {
            propItems
        } else {
            val dataKey = component.propString("dataKey") ?: "items"
            val stateItems = ctx.state.resolvePath(dataKey)
            if (stateItems is JsonArray) {
                stateItems
            } else {
                JsonArray(emptyList())
            }
        }
    }

    // 获取列表项模板（第一个子组件）
    val itemTemplate = component.children.firstOrNull() ?: return

    // 非 Lazy：LazyColumn 在可滚动父容器（无限高度）中会崩溃。
    // 数据绑定列表项数量有限，普通 Column 即可；滚动交给画布外层。
    Column(modifier = modifier.fillMaxWidth()) {
        itemsJson.forEachIndexed { index, item ->
            val itemCtx = remember(item) {
                ctx.withState(ItemScopedStateStore(ctx.state, item))
            }
            RenderNode(
                component = itemTemplate,
                ctx = itemCtx
            )
        }
    }
}
