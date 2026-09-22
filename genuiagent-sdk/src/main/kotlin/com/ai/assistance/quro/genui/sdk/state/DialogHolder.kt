package com.ai.assistance.quro.genui.sdk.state

import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 对话框持有器，管理对话框栈
 */
class DialogHolder {
    private val _dialogs = MutableStateFlow<List<UIComponent>>(emptyList())
    val dialogs: StateFlow<List<UIComponent>> = _dialogs.asStateFlow()

    fun show(dialog: UIComponent) {
        _dialogs.value = _dialogs.value + dialog
    }

    fun dismiss() {
        if (_dialogs.value.isEmpty()) return
        _dialogs.value = _dialogs.value.dropLast(1)
    }

    fun dismissAll() {
        _dialogs.value = emptyList()
    }

    fun current(): UIComponent? = _dialogs.value.lastOrNull()
}
