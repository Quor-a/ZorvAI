package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentPress
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Line2
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Sage

/** 章节小标题（编辑排版风）：陶土色衬线序号 + 衬线标题 + 分隔线 */
@Composable
fun ChapterLabel(num: String, title: String, sub: String = "") {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                num, fontSize = 18.sp, color = Accent,
                fontWeight = FontWeight.Normal, fontFamily = FontFamily.Serif,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title, fontSize = 15.sp, color = cs.onSurface,
                fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif,
            )
            if (sub.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("($sub)", fontSize = 12.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

/** 组说明小字（编辑排版风） */
@Composable
fun GroupCaption(text: String) {
    Text(
        text, fontSize = 12.sp, color = Muted,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
        letterSpacing = 0.6.sp,
    )
}

/** 一组设置卡片（纸感：白底 + Line 描边 + 16dp 圆角，无阴影） */
@Composable
fun SetGroup(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(1.dp, Line, RoundedCornerShape(16.dp)),
    ) { content() }
}

/** 开关行：图标 + 名称/副标题 + Accent 开关（scaled 用于聊天界面动态字号，默认 it.sp） */
@Composable
fun SetRow(
    icon: ImageVector,
    name: String,
    sub: String = "",
    checked: Boolean,
    onToggle: () -> Unit,
    scaled: (Int) -> TextUnit = { it.sp },
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).clickable(onClick = onToggle).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = scaled(14), color = cs.onSurface)
            if (sub.isNotBlank()) Text(sub, fontSize = scaled(11), color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = Accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = cs.surfaceVariant,
                uncheckedThumbColor = cs.onSurfaceVariant,
            ),
        )
    }
}

/** 可点击行：图标 + 名称/副标题 + 值 + 箭头；danger 行用陶土深红（scaled 用于动态字号，默认 it.sp） */
@Composable
fun SetRowClickable(
    icon: ImageVector,
    name: String,
    sub: String = "",
    value: String = "",
    onClick: () -> Unit,
    scaled: (Int) -> TextUnit = { it.sp },
    danger: Boolean = false,
) {
    val dangerColor = Color(android.graphics.Color.parseColor("#C0432F"))
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = if (danger) dangerColor else cs.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = scaled(14), color = if (danger) dangerColor else cs.onSurface)
            if (sub.isNotBlank()) Text(sub, fontSize = scaled(11), color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (value.isNotBlank()) Text(value, fontSize = scaled(13), color = Muted)
        Icon(Icons.Filled.ChevronRight, contentDescription = null, Modifier.size(16.dp), tint = Muted)
    }
}

/** 选择入口行：AccentSoft 图标盒 + 标题/副标题 + 值 + 箭头（模型配置同款） */
@Composable
fun SettingsSelectorRow(
    title: String,
    subtitle: String,
    value: String,
    color: Color = Accent,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
            .border(1.dp, if (value.isNotBlank()) Accent else Line, RoundedCornerShape(14.dp))
            .clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Public, null, Modifier.size(20.dp), tint = Accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
            Text(subtitle, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
        }
        if (value.isNotBlank()) {
            Text(value, fontSize = 14.sp, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
        }
        Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = Muted)
    }
}

/** 工具磁贴（对话框 + 号菜单同款）：AccentSoft/Line 方块 + 图标 + 标题 */
@Composable
fun ToolCard(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.widthIn(min = 72.dp, max = 96.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant)
            .border(1.dp, Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = Accent)
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = 11.sp, color = cs.onSurface, maxLines = 1)
        if (subtitle.isNotBlank()) {
            Text(subtitle, fontSize = 10.sp, color = Muted, maxLines = 1)
        }
    }
}

/** 下划线输入框（编辑排版风） */
@Composable
fun UnderlineField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    scaled: (Int) -> TextUnit = { it.sp },
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    isSecret: Boolean = false,
    showSecret: Boolean = false,
    onToggleSecret: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = scaled(12), color = Muted, modifier = Modifier.padding(bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                textStyle = TextStyle(fontSize = scaled(15), color = cs.onSurface),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Next,
                ),
                visualTransformation = if (isSecret && !showSecret) PasswordVisualTransformation() else VisualTransformation.None,
                decorationBox = { it2 -> if (value.isEmpty()) Text(placeholder, fontSize = scaled(15), color = Muted); it2() },
                cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
            )
            if (isSecret) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.clickable(onClick = onToggleSecret)) {
                    Icon(
                        if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        "切换可见", Modifier.size(18.dp), tint = Muted,
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    }
}

/** 普通字段（包装 UnderlineField，签名保持稳定） */
@Composable
fun QuroField(
    label: String,
    value: String,
    keyboardOptions: KeyboardOptions,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    UnderlineField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        placeholder = "",
        scaled = { it.sp },
        modifier = modifier,
        numeric = keyboardOptions.keyboardType == KeyboardType.Number,
    )
}

/** 密钥字段（可切换可见） */
@Composable
fun ApiKeyField(value: String, onValueChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    UnderlineField(
        label = "API Key",
        value = value,
        onValueChange = onValueChange,
        placeholder = "sk-••••••••",
        scaled = { it.sp },
        isSecret = true,
        showSecret = visible,
        onToggleSecret = { visible = !visible },
    )
}

/** 步进器（编辑排版风） */
@Composable
fun StepperField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(bottom = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).border(1.dp, Line, CircleShape)
                    .clickable { if (value > 0) onValueChange(value - 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text("−", fontSize = 18.sp, color = cs.onSurface)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                value.toString(), fontSize = 15.sp, color = cs.onSurface,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 20.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(Accent)
                    .clickable { onValueChange(value + 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text("+", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** 信息/状态展示盒（纸感：AccentSoft 底 + Sage/Accent 文字） */
@Composable
fun InfoBox(
    text: String,
    tone: Color = Sage,
) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(AccentSoft.copy(alpha = 0.5f)).padding(12.dp),
    ) {
        SelectionContainer {
            Text(text, fontSize = 13.sp, color = tone, lineHeight = 18.sp)
        }
    }
}

/** 主操作按钮（纸感：陶土实心） */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Accent else cs.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, color = if (enabled) Color.White else Muted, fontWeight = FontWeight.SemiBold)
    }
}

/** 危险操作按钮（纸感：深红描边/实心） */
@Composable
fun DangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
) {
    val danger = Color(android.graphics.Color.parseColor("#C0432F"))
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (filled) danger else Color.Transparent)
            .border(1.dp, danger, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, color = if (filled) Color.White else danger, fontWeight = FontWeight.SemiBold)
    }
}

/** 水平排列的一对按钮（取消 / 确认） */
@Composable
fun DialogActions(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    cancelText: String = "取消",
    confirmText: String = "确认",
    confirmFilled: Boolean = true,
) {
    val danger = Color(android.graphics.Color.parseColor("#C0432F"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onCancel).padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(cancelText, fontSize = 14.sp, color = Muted, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(10.dp))
                .background(if (confirmFilled) Accent else Color.Transparent)
                .border(if (confirmFilled) 0.dp else 1.dp, danger, RoundedCornerShape(10.dp))
                .clickable(onClick = onConfirm).padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(confirmText, fontSize = 14.sp, color = if (confirmFilled) Color.White else danger, fontWeight = FontWeight.SemiBold)
        }
    }
}
