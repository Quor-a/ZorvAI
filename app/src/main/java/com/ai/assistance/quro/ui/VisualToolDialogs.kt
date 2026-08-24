package com.ai.assistance.quro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 可视化弹窗选择器：选择弹窗类型并配置参数
 *
 * 弹窗类型：
 * 1. 信息展示 - 纯内容展示，带确认按钮
 * 2. 按钮选择 - 多个按钮，用户点击一个
 * 3. 表单输入 - 包含输入框，用户填写
 * 4. 确认操作 - 确认/取消二选一
 * 5. 自由HTML - AI完全自写HTML
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualPopupSelectorDialog(
    onDismiss: () -> Unit,
    onSendStructuredPrompt: (String) -> Unit,
) {
    var selectedType by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var buttonsText by remember { mutableStateOf("") }
    var showConfig by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("可视化弹窗") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Text(
                    "选择弹窗类型",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 弹窗类型选项
                val types = listOf(
                    Triple("info", "信息展示", Icons.Filled.Info),
                    Triple("buttons", "按钮选择", Icons.Filled.TouchApp),
                    Triple("form", "表单输入", Icons.Filled.EditNote),
                    Triple("confirm", "确认操作", Icons.Filled.CheckCircle),
                    Triple("custom", "自由HTML", Icons.Filled.Code),
                )

                types.forEach { (type, label, icon) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { selectedType = type; showConfig = true },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedType == type)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surface
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            if (selectedType == type) {
                                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // 配置区域
                if (showConfig && selectedType.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("弹窗标题") },
                        placeholder = { Text("例如：选择操作") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(Modifier.height(8.dp))

                    when (selectedType) {
                        "info" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text("展示内容") },
                                placeholder = { Text("支持 Markdown/HTML") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                                maxLines = 5,
                            )
                        }
                        "buttons" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text("提示文本") },
                                placeholder = { Text("请选择一个操作") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = buttonsText,
                                onValueChange = { buttonsText = it },
                                label = { Text("按钮（逗号分隔）") },
                                placeholder = { Text("查看,编辑,删除") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "form" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text("表单描述") },
                                placeholder = { Text("请填写以下信息") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = buttonsText,
                                onValueChange = { buttonsText = it },
                                label = { Text("字段（逗号分隔）") },
                                placeholder = { Text("姓名,邮箱,电话") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "confirm" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text("确认内容") },
                                placeholder = { Text("确定要执行此操作吗？") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                                maxLines = 3,
                            )
                        }
                        "custom" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text("HTML内容描述") },
                                placeholder = { Text("描述你想要的界面，AI会生成完整HTML") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                                maxLines = 5,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedType.isEmpty()) return@Button
                    val prompt = buildVisualPopupPrompt(selectedType, title, content, buttonsText)
                    onSendStructuredPrompt(prompt)
                    onDismiss()
                },
                enabled = selectedType.isNotEmpty() && title.isNotBlank(),
            ) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 可视化询问选择器：配置 AI 向用户提问的方式
 *
 * 询问类型：
 * 1. 选择题 - AI提供选项，用户点选
 * 2. 输入框 - AI提供输入框，用户填写
 * 3. 评分 - AI展示评分条，用户打分
 * 4. 开关 - AI展示开关，用户切换
 * 5. 自由HTML - AI完全自写询问界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualQuestionSelectorDialog(
    onDismiss: () -> Unit,
    onSendStructuredPrompt: (String) -> Unit,
) {
    var selectedType by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var optionsText by remember { mutableStateOf("") }
    var showConfig by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("可视化询问") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Text(
                    "选择询问方式",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val types = listOf(
                    Triple("choice", "选择题", Icons.Filled.List),
                    Triple("input", "输入框", Icons.Filled.TextFields),
                    Triple("rating", "评分", Icons.Filled.Star),
                    Triple("toggle", "开关", Icons.Filled.ToggleOn),
                    Triple("custom", "自由HTML", Icons.Filled.Code),
                )

                types.forEach { (type, label, icon) ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clickable { selectedType = type; showConfig = true },
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedType == type)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                            else MaterialTheme.colorScheme.surface
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            if (selectedType == type) {
                                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                if (showConfig && selectedType.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        label = { Text("问题内容") },
                        placeholder = { Text("你想问用户什么？") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(Modifier.height(8.dp))

                    when (selectedType) {
                        "choice" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text("选项（逗号分隔）") },
                                placeholder = { Text("选项A,选项B,选项C") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "input" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text("输入框提示") },
                                placeholder = { Text("请输入你的答案") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "rating" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text("评分描述（可选）") },
                                placeholder = { Text("请为体验打分 1-5") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "toggle" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text("开关标签") },
                                placeholder = { Text("启用通知") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "custom" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text("HTML描述") },
                                placeholder = { Text("描述你想要的询问界面") },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                                maxLines = 5,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (selectedType.isEmpty()) return@Button
                    val prompt = buildVisualQuestionPrompt(selectedType, question, optionsText)
                    onSendStructuredPrompt(prompt)
                    onDismiss()
                },
                enabled = selectedType.isNotEmpty() && question.isNotBlank(),
            ) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 构建可视化弹窗的结构化提示词
 */
private fun buildVisualPopupPrompt(type: String, title: String, content: String, options: String): String {
    return when (type) {
        "info" -> "调用 visual_popup 工具，参数：title=\"$title\"，content=\"$content\"，buttons=[{\"text\":\"确认\",\"value\":\"ok\",\"style\":\"primary\"}]"
        "buttons" -> {
            val btnList = options.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val btns = btnList.joinToString(",") { "{\"text\":\"$it\",\"value\":\"$it\",\"style\":\"primary\"}" }
            "调用 visual_popup 工具，参数：title=\"$title\"，content=\"$content\"，buttons=[$btns]"
        }
        "form" -> {
            val fields = options.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val inputs = fields.joinToString(",") { "{\"id\":\"${it}\",\"label\":\"$it\",\"type\":\"text\"}" }
            "调用 visual_popup 工具，参数：title=\"$title\"，content=\"$content\"，inputs=[$inputs]，buttons=[{\"text\":\"提交\",\"value\":\"submit\",\"style\":\"primary\"},{\"text\":\"取消\",\"value\":\"cancel\",\"style\":\"secondary\"}]"
        }
        "confirm" -> "调用 visual_popup 工具，参数：title=\"$title\"，content=\"$content\"，buttons=[{\"text\":\"确定\",\"value\":\"confirm\",\"style\":\"primary\"},{\"text\":\"取消\",\"value\":\"cancel\",\"style\":\"secondary\"}]"
        "custom" -> "调用 visual_custom_popup 工具，参数：title=\"$title\"，html=\"（请根据以下描述生成HTML内容：$content）\"，card_title=\"$title\""
        else -> "调用 visual_popup 工具，参数：title=\"$title\"，content=\"$content\""
    }
}

/**
 * 构建可视化询问的结构化提示词
 */
private fun buildVisualQuestionPrompt(type: String, question: String, options: String): String {
    return when (type) {
        "choice" -> {
            val opts = options.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val btns = opts.joinToString(",") { "{\"text\":\"$it\",\"value\":\"$it\",\"style\":\"primary\"}" }
            "调用 visual_popup 工具，参数：title=\"请选择\"，content=\"$question\"，buttons=[$btns]"
        }
        "input" -> "调用 visual_popup 工具，参数：title=\"请输入\"，content=\"$question\"，inputs=[{\"id\":\"answer\",\"label\":\"$options\",\"type\":\"text\"}]，buttons=[{\"text\":\"提交\",\"value\":\"submit\",\"style\":\"primary\"}]"
        "rating" -> "调用 visual_custom_popup 工具，参数：title=\"评分\"，html=\"（请生成一个评分界面：$question）\"，card_title=\"评分\""
        "toggle" -> "调用 visual_custom_popup 工具，参数：title=\"开关\"，html=\"（请生成一个开关界面：$options）\"，card_title=\"$options\""
        "custom" -> "调用 visual_custom_popup 工具，参数：title=\"询问\"，html=\"（请根据以下描述生成HTML：$question）\"，card_title=\"询问\""
        else -> "调用 visual_popup 工具，参数：title=\"询问\"，content=\"$question\""
    }
}
