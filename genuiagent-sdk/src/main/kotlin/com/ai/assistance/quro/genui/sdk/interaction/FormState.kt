package com.ai.assistance.quro.genui.sdk.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember

/**
 * GenUI 表单状态总线（纯静态快照，零 CompositionLocal 依赖）：
 * 带 id 的输入组件实时写值；按钮 CallbackAction 声明 collectFrom 列表，
 * 提交时聚合值并入 payload 回传 AI。executor 任意上下文可读。
 */
object FormStateBus {
    @Volatile
    private var values: Map<String, String> = emptyMap()

    fun set(id: String?, value: String) {
        if (!id.isNullOrBlank()) {
            values = values + (id to value)
        }
    }

    fun take(id: String): String? = values[id]

    fun collect(ids: List<String>): Map<String, String> =
        ids.mapNotNull { id -> values[id]?.let { id to it } }.toMap()

    fun clear() { values = emptyMap() }
}

/**
 * 渲染根挂载：渲染前清空上页残留，防止跨页串值
 */
@Composable
fun FormStateHost(content: @Composable () -> Unit) {
    remember { FormStateBus.clear(); true }
    content()
}
