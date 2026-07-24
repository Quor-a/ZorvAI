package com.ai.assistance.quro.core.media

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 文档查看器事件总线：把「在应用内打开文档」的请求从工具 / 文档中心桥接到 ChatScreen。
 * 百分百开源（自研 OOXML 解析，无第三方库）。
 */
object QuroDocLauncher {
    private val _file = MutableStateFlow<File?>(null)
    val file: StateFlow<File?> = _file

    fun open(file: File) { _file.value = file }
    fun consume() { _file.value = null }
}
