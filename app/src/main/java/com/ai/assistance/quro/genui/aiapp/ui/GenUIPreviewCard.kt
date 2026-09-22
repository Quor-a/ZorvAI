package com.ai.assistance.quro.genui.aiapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.genui.sdk.GenUI
import com.ai.assistance.quro.genui.sdk.interaction.ActionHost
import com.ai.assistance.quro.genui.sdk.interaction.DefaultActionHost
import com.ai.assistance.quro.genui.sdk.style.GenUITheme

/**
 * GenUI 渲染区域
 * 全屏宽度渲染 AI 生成的 GenUI DSL — 无气泡包裹
 *
 * @property json GenUI DSL JSON 字符串
 * @property modifier 修饰符
 * @property host ActionHost 用于处理UI交互
 * @property onError 解析错误回调
 */
@Composable
fun GenUIPreviewCard(
    json: String,
    modifier: Modifier = Modifier,
    host: ActionHost = DefaultActionHost(),
    onError: (String) -> Unit = {}
) {
    val specResult = remember(json) {
        try {
            Result.success(GenUI.safeParse(json))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // 无背景卡片包裹 — 直接全屏渲染
    when {
        specResult.isSuccess -> {
            val spec = specResult.getOrNull()
            if (spec != null && spec.root.type.isNotBlank()) {
                GenUI.Screen(
                    spec = spec,
                    host = host,
                    modifier = modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = "无法渲染 GenUI 内容",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        specResult.isFailure -> {
            val error = specResult.exceptionOrNull()?.message ?: "未知错误"
            Text(
                text = "解析失败：$error",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
            onError(error)
        }
    }
}
