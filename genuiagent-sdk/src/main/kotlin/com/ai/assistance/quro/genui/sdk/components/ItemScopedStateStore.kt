package com.ai.assistance.quro.genui.sdk.components

import com.ai.assistance.quro.genui.sdk.state.GenUIStateStore
import kotlinx.serialization.json.JsonElement

/**
 * 列表项作用域状态存储
 * 继承自GenUIStateStore，为列表中的每一项提供独立的作用域状态
 * 将当前项数据以"item"键合并到父状态中
 */
class ItemScopedStateStore(
    private val parent: GenUIStateStore,
    private val item: JsonElement
) : GenUIStateStore(parent.snapshot() + ("item" to item))
