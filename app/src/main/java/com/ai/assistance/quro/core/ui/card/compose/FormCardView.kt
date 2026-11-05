package com.ai.assistance.quro.core.ui.card.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.ui.card.spec.Action
import com.ai.assistance.quro.core.ui.card.spec.CardData
import com.ai.assistance.quro.core.ui.card.spec.CardSpec
import com.ai.assistance.quro.core.ui.card.spec.ColorToken

/**
 * 表单类卡片：`form_card`（输入字段 + 提交）、滑块、选择器。
 *
 * 三种形态共用同一个数据壳 [CardData.Form]，按字段是否存在自动降级：
 *  - 有 `fields` → 画输入框 + 提交按钮（提交时把所有字段塞进 action.payload）；
 *  - 有 `options` → 画可点选择器（单选/多选由 selected 判断）；
 *  - 有 `min/max` → 画滑块（拖动结束才回传，避免每一帧都产生一次工具调用）。
 *
 * 输入值全部存在 `remember` 里，**不进 ViewModel**：小卡片是消息附件，
 * 上报靠 [Action] 回传给宿主；卡片被复用/销毁时状态自然释放，不会串消息。
 */
@Composable
fun FormCardView(
    spec: CardSpec,
    modifier: Modifier = Modifier,
    onAction: (Action) -> Unit,
    resolveToken: (ColorToken) -> Color,
) {
    val form = spec.data as? CardData.Form ?: return
    CardShell(spec, resolveToken, modifier) {
        val onlySlider = form.fields.isEmpty() && form.options.isEmpty() && form.buttons.isEmpty() &&
            form.max > form.min
        when {
            form.formType.equals("slider", true) || onlySlider -> SliderBody(form, onAction)
            form.options.isNotEmpty() -> SelectorBody(form, onAction, resolveToken)
            else -> FieldsBody(spec, form, onAction, resolveToken)
        }
    }
}

@Composable
private fun SliderBody(form: CardData.Form, onAction: (Action) -> Unit) {
    val lo = if (form.max > form.min) form.min else 0f
    val hi = if (form.max > form.min) form.max else 100f
    var value by remember(form.value) { mutableStateOf(form.value.coerceIn(lo, hi)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = lo..hi,
            onValueChangeFinished = {
                onAction(
                    Action(
                        type = "callback",
                        name = "slider",
                        payload = mapOf("value" to value, "min" to lo, "max" to hi),
                    ),
                )
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            trimNum(value),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SelectorBody(
    form: CardData.Form,
    onAction: (Action) -> Unit,
    resolveToken: (ColorToken) -> Color,
) {
    // 多选/单选判定：selected 里有多于 1 项就按多选放开（模型很少显式写 multi）
    val multi = form.selected.size > 1
    var selected by remember(form.selected) { mutableStateOf(form.selected.toSet()) }

    fun toggle(option: String) {
        selected = if (!multi) {
            setOf(option)
        } else if (option in selected) {
            selected - option
        } else {
            selected + option
        }
        onAction(
            Action(
                type = "callback",
                name = "select",
                payload = mapOf("value" to option, "selected" to selected.toList()),
            ),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        form.options.chunked(4).forEach { rowOptions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowOptions.forEach { option ->
                    OptionChip(
                        label = option,
                        on = option in selected,
                        resolveToken = resolveToken,
                        modifier = Modifier.weight(1f),
                        onClick = { toggle(option) },
                    )
                }
                // 补齐占位，保持等宽（否则末行 2 个选项会被拉成两半）
                repeat(4 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun OptionChip(
    label: String,
    on: Boolean,
    resolveToken: (ColorToken) -> Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (on) resolveToken(ColorToken.Primary) else resolveToken(ColorToken.SurfaceVariant))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) resolveToken(ColorToken.OnPrimary) else resolveToken(ColorToken.OnSurface),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FieldsBody(
    spec: CardSpec,
    form: CardData.Form,
    onAction: (Action) -> Unit,
    resolveToken: (ColorToken) -> Color,
) {
    val values = remember(spec.id) {
        mutableStateMapOf<String, String>().apply {
            form.fields.forEach { put(it.key, it.value) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        form.fields.forEach { field ->
            OutlinedTextField(
                value = values[field.key].orEmpty(),
                onValueChange = { values[field.key] = it },
                label = { Text(field.label.ifBlank { field.key }, fontSize = 12.sp) },
                placeholder = if (field.placeholder.isNotBlank()) {
                    { Text(field.placeholder, fontSize = 12.sp) }
                } else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (form.buttons.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                form.buttons.take(2).forEach { btn ->
                    Button(
                        onClick = {
                            // 提交：按钮自带的 action 优先，字段值一律塞进 payload
                            val merged = btn.action.copy(
                                payload = btn.action.payload + values.toMap(),
                            )
                            onAction(merged)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (btn.variant == "outline") {
                                resolveToken(ColorToken.SurfaceVariant)
                            } else {
                                resolveToken(ColorToken.Primary)
                            },
                            contentColor = if (btn.variant == "outline") {
                                resolveToken(ColorToken.OnSurface)
                            } else {
                                resolveToken(ColorToken.OnPrimary)
                            },
                        ),
                    ) {
                        Text(btn.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.height(0.dp))
    }
}
