package com.ai.assistance.quro.genui.sdk.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ai.assistance.quro.genui.sdk.dsl.ComponentTypes
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.render.RenderContext
import com.ai.assistance.quro.genui.sdk.render.StyleResolver

/**
 * 输入框组件类型常量
 */
const val TEXT_FIELD_TYPE = ComponentTypes.TEXT_FIELD

/**
 * TextField输入框组件渲染器
 *
 * 支持的属性：
 * - value: 当前值（支持数据绑定，双向绑定）
 * - placeholder: 占位提示文本
 * - label: 标签文本
 * - enabled: 是否启用
 * - readOnly: 是否只读
 * - variant: 样式变体（filled/outlined，默认filled）
 *
 * 支持的样式：
 * - textColor: 文字颜色
 * - backgroundColor: 背景颜色
 * - cornerRadius: 圆角
 *
 * 支持的事件：
 * - onChange: 文本变化时触发
 * - onFocus: 获得焦点时触发
 * - onBlur: 失去焦点时触发
 */
@Composable
fun TextFieldRenderer(
    component: UIComponent,
    ctx: RenderContext,
    modifier: Modifier = Modifier
) {
    val valueKey = component.propString("value") ?: ""
    val initialValue = component.propStringResolved("value", ctx).orEmpty()
    var text by remember { mutableStateOf(initialValue) }
    val placeholder = component.propStringResolved("placeholder", ctx)
    val label = component.propStringResolved("label", ctx)
    val enabled = component.propBool("enabled", true)
    val readOnly = component.propBool("readOnly", false)
    val variant = component.propString("variant") ?: "filled"

    val containerColor = StyleResolver.resolveColor(
        component.style.backgroundColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.surface
    )
    val textColor = StyleResolver.resolveColor(
        component.style.textColor,
        ctx.theme.colorScheme,
        ctx.theme.colorScheme.onSurface
    )
    val shape = StyleResolver.resolveShape(component.style)

    val onValueChange: (String) -> Unit = { newText ->
        text = newText
        // GenUI 表单总线：id 绑定实时值（collectFrom 提交用）
        com.ai.assistance.quro.genui.sdk.interaction.FormStateBus.set(component.id, newText)
        // 更新状态
        if (valueKey.isNotEmpty()) {
            ctx.state.set(valueKey, newText)
        }
        // 触发onChange事件
        component.events["onChange"]?.let { event ->
            ctx.executor.execute(event, component)
        }
    }

    if (variant == "outlined") {
        OutlinedTextField(
            value = text,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = if (label != null) {
                { Text(text = label) }
            } else null,
            placeholder = if (placeholder != null) {
                { Text(text = placeholder) }
            } else null,
            enabled = enabled,
            readOnly = readOnly,
            shape = shape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
                focusedTextColor = textColor,
                unfocusedTextColor = textColor
            )
        )
    } else {
        TextField(
            value = text,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            label = if (label != null) {
                { Text(text = label) }
            } else null,
            placeholder = if (placeholder != null) {
                { Text(text = placeholder) }
            } else null,
            enabled = enabled,
            readOnly = readOnly,
            shape = shape,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = containerColor,
                unfocusedContainerColor = containerColor,
                focusedTextColor = textColor,
                unfocusedTextColor = textColor
            )
        )
    }
}
