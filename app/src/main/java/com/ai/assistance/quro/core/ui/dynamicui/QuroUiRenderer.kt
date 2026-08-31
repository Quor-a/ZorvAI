package com.ai.assistance.quro.core.ui.dynamicui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * ZorvAI 动态 UI 的 Compose 原生渲染器（参照 Kai `KaiUiRenderer` 设计，去品牌化重写）。
 *
 * 之所以坚持原生渲染而非 WebView：
 *  - 控件可直接读写状态，用户交互能原样回传给模型（WebView 需桥接且易丢事件）；
 *  - 自动继承应用主题（深色/浅色、字体缩放、动态取色），无需 AI 关心配色；
 *  - 无 JS 注入面，安全边界更清晰。
 *
 * @param root 解析后的节点树
 * @param modifier 根容器修饰符
 * @param onAction 动作回调：宿主收到动作后决定如何执行（发消息给模型 / 调工具 / 开网页等）
 * @param onSubmit 便捷回调：当动作需要把收集到的表单值作为用户消息发回模型时触发
 */
@Composable
fun QuroUiRenderer(
    root: QuroUiNode,
    modifier: Modifier = Modifier,
    onAction: (QuroUiAction, Map<String, String>) -> Unit = { _, _ -> },
) {
    // 表单状态集中托管：id -> 值（String / Boolean / Float）
    // 用 mutableStateMapOf，任一控件写入都会自动触发依赖它的 Composable 重组。
    val state = remember { mutableStateMapOf<String, Any>() }
    // 被 toggle 隐藏的节点 id 集合
    val hidden = remember { mutableStateMapOf<String, Boolean>() }

    RenderNode(
        node = root,
        state = state,
        hidden = hidden,
        onAction = onAction,
        modifier = modifier,
    )
}

// =============================================================================================
// 递归渲染
// =============================================================================================

@Composable
private fun RenderNode(
    node: QuroUiNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // toggle 隐藏：节点有 id 且被标记为隐藏则整体不渲染
    val nodeId = node.id
    if (nodeId != null && hidden[nodeId] == true) return

    when (node) {
        is QuroColumnNode -> RenderColumn(node, state, hidden, onAction, modifier)
        is QuroRowNode -> RenderRow(node, state, hidden, onAction, modifier)
        is QuroBoxNode -> RenderBox(node, state, hidden, onAction, modifier)
        is QuroCardNode -> RenderCard(node, state, hidden, onAction, modifier)
        is QuroTextNode -> RenderText(node, modifier)
        is QuroImageNode -> RenderImage(node, modifier)
        is QuroIconNode -> RenderIcon(node, modifier)
        is QuroBadgeNode -> RenderBadge(node, modifier)
        is QuroProgressNode -> RenderProgress(node, modifier)
        is QuroDividerNode -> RenderDivider(node, modifier)
        is QuroSpacerNode -> RenderSpacer(node, modifier)
        is QuroButtonNode -> RenderButton(node, state, hidden, onAction, modifier)
        is QuroTextInputNode -> RenderTextInput(node, state, modifier)
        is QuroCheckboxNode -> RenderCheckbox(node, state, modifier)
        is QuroSwitchNode -> RenderSwitch(node, state, modifier)
        is QuroSelectNode -> RenderSelect(node, state, modifier)
        is QuroSliderNode -> RenderSlider(node, state, modifier)
        is QuroListNode -> RenderList(node, state, hidden, onAction, modifier)
        is QuroTabsNode -> RenderTabs(node, state, hidden, onAction, modifier)
    }
}

// =============================================================================================
// 布局
// =============================================================================================

@Composable
private fun RenderColumn(
    node: QuroColumnNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val pad = (node.padding ?: 0).dp
    val m = modifier
        .then(if (pad > 0.dp) Modifier.padding(pad) else Modifier)
        .then(if (node.scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)

    Column(
        modifier = m,
        verticalArrangement = Arrangement.spacedBy((node.spacing ?: 8).dp),
        horizontalAlignment = when (node.horizontalAlign?.lowercase()) {
            "center", "centerhorizontally" -> Alignment.CenterHorizontally
            "end", "right" -> Alignment.End
            else -> Alignment.Start
        },
    ) {
        node.children.forEach { child ->
            // weight 是 ColumnScope 的扩展，必须在本作用域内应用，
            // 不能提到 RenderNode 内部（那里没有 ColumnScope，会编译失败）。
            val childMod = weightOf(child)?.let { Modifier.weight(it) } ?: Modifier
            RenderNode(child, state, hidden, onAction, childMod)
        }
    }
}

/** 提取节点的 weight（用于 Row/Column 内按比例分配空间）。 */
private fun weightOf(node: QuroUiNode): Float? = when (node) {
    is QuroColumnNode -> node.weight
    is QuroRowNode -> node.weight
    is QuroBoxNode -> node.weight
    is QuroCardNode -> node.weight
    else -> null
}

@Composable
private fun RenderRow(
    node: QuroRowNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val pad = (node.padding ?: 0).dp
    val m = modifier
        .then(if (pad > 0.dp) Modifier.padding(pad) else Modifier)
        .then(if (node.scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)

    Row(
        modifier = m,
        horizontalArrangement = Arrangement.spacedBy((node.spacing ?: 8).dp),
        verticalAlignment = when (node.verticalAlign?.lowercase()) {
            "top" -> Alignment.Top
            "bottom" -> Alignment.Bottom
            else -> Alignment.CenterVertically
        },
    ) {
        node.children.forEach { child ->
            // weight 是 RowScope 的扩展，必须在本作用域内应用
            val childMod = weightOf(child)?.let { Modifier.weight(it) } ?: Modifier
            RenderNode(child, state, hidden, onAction, childMod)
        }
    }
}

@Composable
private fun RenderBox(
    node: QuroBoxNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val pad = (node.padding ?: 0).dp
    Box(
        modifier = modifier
            .then(if (pad > 0.dp) Modifier.padding(pad) else Modifier),
    ) {
        node.children.forEach { child ->
            RenderNode(child, state, hidden, onAction)
        }
    }
}

@Composable
private fun RenderCard(
    node: QuroCardNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val pad = (node.padding ?: 12).dp
    val radius = (node.cornerRadius ?: 12).dp
    val shape = RoundedCornerShape(radius)
    val action = node.onClick

    // M3 的 Card content 带 ColumnScope receiver，必须声明为 ColumnScope 扩展，
    // 否则类型不匹配（ComposableFunction0 vs ComposableFunction1<ColumnScope, Unit>）。
    val content: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.padding(pad),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!node.title.isNullOrBlank()) {
                Text(
                    text = node.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            node.children.forEach { child ->
                RenderNode(child, state, hidden, onAction)
            }
        }
    }

    val m = modifier.fillMaxWidth()

    if (action != null) {
        OutlinedCard(
            modifier = m,
            shape = shape,
            onClick = { dispatch(action, state, hidden, onAction) },
            content = content,
        )
    } else {
        Card(
            modifier = m,
            shape = shape,
            colors = CardDefaults.cardColors(),
            content = content,
        )
    }
}

// =============================================================================================
// 内容
// =============================================================================================

@Composable
private fun RenderText(node: QuroTextNode, modifier: Modifier) {
    if (node.value.isBlank() && node.id == null) return
    val color = node.color?.let { QuroUiColor.parse(it) }
    val baseStyle = when (node.style?.lowercase()) {
        "title", "h1" -> MaterialTheme.typography.titleLarge
        "headline", "h2", "subtitle" -> MaterialTheme.typography.titleMedium
        "caption", "small" -> MaterialTheme.typography.bodySmall
        "label" -> MaterialTheme.typography.labelMedium
        else -> MaterialTheme.typography.bodyMedium
    }
    Text(
        text = node.value,
        modifier = modifier.fillMaxWidth(),
        style = baseStyle.copy(
            fontSize = (node.size ?: baseStyle.fontSize.value.toInt()).sp,
            fontWeight = if (node.bold) FontWeight.Bold else baseStyle.fontWeight,
            fontStyle = if (node.italic) FontStyle.Italic else baseStyle.fontStyle,
        ),
        color = color ?: androidx.compose.ui.graphics.Color.Unspecified,
        maxLines = node.maxLines ?: Int.MAX_VALUE,
        overflow = if (node.maxLines != null) TextOverflow.Ellipsis else TextOverflow.Clip,
        textAlign = when (node.align?.lowercase()) {
            "center" -> TextAlign.Center
            "end", "right" -> TextAlign.End
            else -> TextAlign.Start
        },
    )
}

/** 图片：支持 http(s) URL 与 data:image base64，在 IO 线程解码，不阻塞 UI。 */
@Composable
private fun RenderImage(node: QuroImageNode, modifier: Modifier) {
    val url = node.url
    if (url.isBlank()) return

    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, key1 = url) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    url.startsWith("data:") -> {
                        val b64 = url.substringAfter("base64,", "")
                        if (b64.isBlank()) return@runCatching null
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    url.startsWith("http") -> {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        try {
                            conn.connectTimeout = 8000
                            conn.readTimeout = 12000
                            conn.instanceFollowRedirects = true
                            conn.setRequestProperty("User-Agent", "ZorvAI/1.0")
                            conn.inputStream.use { BitmapFactory.decodeStream(it) }
                        } finally {
                            conn.disconnect()
                        }
                    }
                    else -> BitmapFactory.decodeFile(url)
                }
            }.getOrNull()
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = node.alt ?: "",
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (node.aspectRatio != null && node.aspectRatio > 0f) {
                        Modifier
                    } else Modifier.height((node.height ?: 180).dp)
                ),
        )
    } else {
        // 加载中/失败占位，避免布局跳动
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height((node.height ?: 120).dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape((node.cornerRadius ?: 8).dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = node.alt ?: "图片",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 图标：内置常用图标名映射，未命中回落 Info，绝不因未知图标名崩溃。 */
@Composable
private fun RenderIcon(node: QuroIconNode, modifier: Modifier) {
    val imageVector = remember(node.name) { QuroUiIcons.resolve(node.name) }
    Icon(
        imageVector = imageVector,
        contentDescription = node.description ?: node.name,
        modifier = modifier.size((node.size ?: 24).dp),
        tint = node.tint?.let { QuroUiColor.parse(it) } ?: MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun RenderBadge(node: QuroBadgeNode, modifier: Modifier) {
    if (node.text.isBlank()) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = node.background?.let { QuroUiColor.parse(it) }
            ?: MaterialTheme.colorScheme.secondaryContainer,
        contentColor = node.color?.let { QuroUiColor.parse(it) }
            ?: MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            text = node.text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun RenderProgress(node: QuroProgressNode, modifier: Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!node.label.isNullOrBlank()) {
            Text(text = node.label, style = MaterialTheme.typography.bodySmall)
        }
        val p = node.progress
        if (p == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { p.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RenderDivider(node: QuroDividerNode, modifier: Modifier) {
    val pad = (node.padding ?: 0).dp
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .then(if (pad > 0.dp) Modifier.padding(vertical = pad) else Modifier),
        thickness = (node.thickness ?: 1).dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
private fun RenderSpacer(node: QuroSpacerNode, modifier: Modifier) {
    Spacer(
        modifier = modifier
            .then(if (node.height != null) Modifier.height(node.height.dp) else Modifier)
            .then(if (node.width != null) Modifier.width(node.width.dp) else Modifier),
    )
}

// =============================================================================================
// 交互控件
// =============================================================================================

@Composable
private fun RenderButton(
    node: QuroButtonNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val enabled = node.enabled
    val onClick = {
        val action = node.action
        if (action != null) dispatch(action, state, hidden, onAction)
    }
    // 无动作时按钮退化为展示型胶囊，避免「点了没反应」的困惑
    if (node.action == null) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text(node.label) },
            modifier = modifier,
        )
        return
    }

    when (node.variant?.lowercase()) {
        "outlined" -> OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            ButtonLabel(node)
        }
        "text" -> TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
            ButtonLabel(node)
        }
        else -> Button(onClick = onClick, enabled = enabled, modifier = modifier) {
            ButtonLabel(node)
        }
    }
}

@Composable
private fun ButtonLabel(node: QuroButtonNode) {
    if (!node.icon.isNullOrBlank()) {
        Icon(
            imageVector = QuroUiIcons.resolve(node.icon),
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    }
    Text(node.label)
}

@Composable
private fun RenderTextInput(
    node: QuroTextInputNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    // 首次出现时用 DSL 里的 value 播种，之后由用户输入接管
    var text by remember(node.id) {
        mutableStateOf(node.value ?: state[node.id] as? String ?: "")
    }
    LaunchedEffect(text) { state[node.id] = text }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = modifier.fillMaxWidth(),
        label = node.label?.let { { Text(it) } },
        placeholder = node.placeholder?.let { { Text(it) } },
        minLines = if (node.multiline) (node.lines ?: 3) else 1,
        maxLines = if (node.multiline) (node.lines ?: 6) else 1,
        singleLine = !node.multiline,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = when (node.inputType?.lowercase()) {
                "number", "int", "float" -> KeyboardType.Number
                "phone" -> KeyboardType.Phone
                "email" -> KeyboardType.Email
                "password" -> KeyboardType.Password
                else -> KeyboardType.Text
            },
        ),
        visualTransformation = if (node.inputType?.lowercase() == "password") {
            PasswordVisualTransformation()
        } else VisualTransformation.None,
    )
}

@Composable
private fun RenderCheckbox(
    node: QuroCheckboxNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    var checked by remember(node.id) {
        mutableStateOf(state[node.id] as? Boolean ?: node.checked)
    }
    LaunchedEffect(checked) { state[node.id] = checked }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { checked = !checked },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { checked = it })
        Spacer(Modifier.width(8.dp))
        Text(text = node.label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RenderSwitch(
    node: QuroSwitchNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    var checked by remember(node.id) {
        mutableStateOf(state[node.id] as? Boolean ?: node.checked)
    }
    LaunchedEffect(checked) { state[node.id] = checked }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { checked = !checked },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (!node.label.isNullOrBlank()) {
            Text(text = node.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
        }
        Switch(checked = checked, onCheckedChange = { checked = it })
    }
}

// 下拉菜单相关 API（ExposedDropdownMenuBox / menuAnchor / ExposedDropdownMenuDefaults）
// 在 Material3 中仍标记 @ExperimentalMaterial3Api，需显式 OptIn 才能调用。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenderSelect(
    node: QuroSelectNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    if (node.options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    var selected by remember(node.id) {
        mutableStateOf(state[node.id] as? String ?: node.selected ?: node.options.first())
    }
    LaunchedEffect(selected) { state[node.id] = selected }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(),
            label = node.label?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            node.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        selected = option
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun RenderSlider(
    node: QuroSliderNode,
    state: MutableMap<String, Any>,
    modifier: Modifier,
) {
    val safeMax = if (node.max > node.min) node.max else node.min + 1f
    var value by remember(node.id) {
        mutableStateOf((state[node.id] as? Float ?: node.value).coerceIn(node.min, safeMax))
    }
    LaunchedEffect(value) { state[node.id] = value }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!node.label.isNullOrBlank()) {
                Text(text = node.label, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = value.toInt().toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = node.min..safeMax,
            steps = (node.step - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RenderList(
    node: QuroListNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    val m = modifier
        .fillMaxWidth()
        .then(if (node.maxHeight != null) Modifier.heightIn(max = node.maxHeight.dp) else Modifier)
        .verticalScroll(rememberScrollState())

    Column(modifier = m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        node.items.forEachIndexed { index, item ->
            val template = node.itemTemplate
            if (template != null) {
                // 模板渲染：把 {{item}} / {{index}} 占位替换为实际值
                RenderNode(
                    node = substitutePlaceholders(template, item, index),
                    state = state,
                    hidden = hidden,
                    onAction = onAction,
                )
            } else {
                Text(text = "• $item", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RenderTabs(
    node: QuroTabsNode,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    if (node.tabs.isEmpty()) return
    var selectedIndex by remember { mutableStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        TabRow(selectedTabIndex = selectedIndex) {
            node.tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedIndex == index,
                    onClick = { selectedIndex = index },
                    text = { Text(tab.title) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        node.tabs.getOrNull(selectedIndex)?.node?.let { content ->
            RenderNode(content, state, hidden, onAction)
        }
    }
}

// =============================================================================================
// 动作分发
// =============================================================================================

/** 执行动作：先把 collectFrom 指定的控件值收集成 Map，再连同动作交给宿主。 */
private fun dispatch(
    action: QuroUiAction,
    state: MutableMap<String, Any>,
    hidden: MutableMap<String, Boolean>,
    onAction: (QuroUiAction, Map<String, String>) -> Unit,
) {
    val collected = when (action) {
        is QuroCallbackAction -> collect(action.collectFrom, state)
        is QuroToolCallAction -> collect(action.collectFrom, state)
        is QuroSkillAction -> collect(action.collectFrom, state)
        else -> emptyMap()
    }
    // toggle 是纯本地行为，直接改可见性即可，无需惊动模型
    if (action is QuroToggleAction && action.targetId.isNotBlank()) {
        val id = action.targetId
        hidden[id] = hidden[id] != true
    }
    onAction(action, collected)
}

/** 收集指定 id 的当前值；未指定 id 时收集全部（表单整体提交场景）。 */
private fun collect(ids: List<String>, state: Map<String, Any>): Map<String, String> {
    if (ids.isEmpty()) return state.mapValues { it.value.toString() }
    return ids.associateWith { id -> state[id]?.toString() ?: "" }
        .filterValues { it.isNotEmpty() }
}

// =============================================================================================
// 模板占位替换（列表项）
// =============================================================================================

/** 把模板节点里 {{item}} / {{index}} 占位替换为列表当前项的值。 */
private fun substitutePlaceholders(
    node: QuroUiNode,
    item: String,
    index: Int,
): QuroUiNode {
    fun String.sub(): String = this
        .replace("{{item}}", item)
        .replace("{{index}}", index.toString())

    return when (node) {
        is QuroTextNode -> node.copy(value = node.value.sub())
        is QuroButtonNode -> node.copy(
            label = node.label.sub(),
            action = node.action?.let { substituteInAction(it, item, index) },
        )
        is QuroBadgeNode -> node.copy(text = node.text.sub())
        is QuroColumnNode -> node.copy(children = node.children.map { substitutePlaceholders(it, item, index) })
        is QuroRowNode -> node.copy(children = node.children.map { substitutePlaceholders(it, item, index) })
        is QuroBoxNode -> node.copy(children = node.children.map { substitutePlaceholders(it, item, index) })
        is QuroCardNode -> node.copy(
            title = node.title?.sub(),
            children = node.children.map { substitutePlaceholders(it, item, index) },
        )
        else -> node
    }
}

private fun substituteInAction(
    action: QuroUiAction,
    item: String,
    index: Int,
): QuroUiAction {
    fun String.sub(): String = this
        .replace("{{item}}", item)
        .replace("{{index}}", index.toString())

    return when (action) {
        is QuroCallbackAction -> action.copy(
            data = action.data.mapValues { it.value.sub() },
        )
        is QuroToolCallAction -> action.copy(
            arguments = action.arguments.mapValues { it.value.sub() },
        )
        is QuroSkillAction -> action.copy(input = action.input?.sub())
        is QuroCopyAction -> action.copy(text = action.text.sub())
        is QuroOpenUrlAction -> action.copy(url = action.url.sub())
        else -> action
    }
}
