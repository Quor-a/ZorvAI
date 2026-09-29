package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
import org.json.JSONArray
import org.json.JSONObject

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
        title = { Text(stringResource(R.string.qk_00096)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.qk_03123),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 弹窗类型选项
                val types = listOf(
                    Triple("info", stringResource(R.string.qk_03124), Icons.Filled.Info),
                    Triple("buttons", stringResource(R.string.qk_03125), Icons.Filled.TouchApp),
                    Triple("form", stringResource(R.string.qk_03126), Icons.Filled.EditNote),
                    Triple("confirm", stringResource(R.string.qk_03127), Icons.Filled.CheckCircle),
                    Triple("custom", stringResource(R.string.qk_03128), Icons.Filled.Code),
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
                        label = { Text(stringResource(R.string.qk_03103)) },
                        placeholder = { Text(stringResource(R.string.qk_03129)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(Modifier.height(8.dp))

                    when (selectedType) {
                        "info" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text(stringResource(R.string.qk_03130)) },
                                placeholder = { Text(stringResource(R.string.qk_03131)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                                maxLines = 5,
                            )
                        }
                        "buttons" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text(stringResource(R.string.qk_03132)) },
                                placeholder = { Text(stringResource(R.string.qk_03133)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = buttonsText,
                                onValueChange = { buttonsText = it },
                                label = { Text(stringResource(R.string.qk_03134)) },
                                placeholder = { Text(stringResource(R.string.qk_03135)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "form" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text(stringResource(R.string.qk_03136)) },
                                placeholder = { Text(stringResource(R.string.qk_03137)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = buttonsText,
                                onValueChange = { buttonsText = it },
                                label = { Text(stringResource(R.string.qk_03138)) },
                                placeholder = { Text(stringResource(R.string.qk_03139)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "confirm" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text(stringResource(R.string.qk_03140)) },
                                placeholder = { Text(stringResource(R.string.qk_03141)) },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                                maxLines = 3,
                            )
                        }
                        "custom" -> {
                            OutlinedTextField(
                                value = content,
                                onValueChange = { content = it },
                                label = { Text(stringResource(R.string.qk_03142)) },
                                placeholder = { Text(stringResource(R.string.qk_03143)) },
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
            ) { Text(stringResource(R.string.qk_00165)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.qk_00011)) }
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
        title = { Text(stringResource(R.string.qk_00169)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            ) {
                Text(stringResource(R.string.qk_03144),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                val types = listOf(
                    Triple("choice", stringResource(R.string.qk_03145), Icons.Filled.List),
                    Triple("input", stringResource(R.string.qk_03146), Icons.Filled.TextFields),
                    Triple("rating", stringResource(R.string.qk_01667), Icons.Filled.Star),
                    Triple("toggle", stringResource(R.string.qk_03147), Icons.Filled.ToggleOn),
                    Triple("custom", stringResource(R.string.qk_03128), Icons.Filled.Code),
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
                        label = { Text(stringResource(R.string.qk_01073)) },
                        placeholder = { Text(stringResource(R.string.qk_03148)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(Modifier.height(8.dp))

                    when (selectedType) {
                        "choice" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text(stringResource(R.string.qk_03149)) },
                                placeholder = { Text(stringResource(R.string.qk_03150)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "input" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text(stringResource(R.string.qk_03151)) },
                                placeholder = { Text(stringResource(R.string.qk_03152)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "rating" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text(stringResource(R.string.qk_03153)) },
                                placeholder = { Text(stringResource(R.string.qk_03154)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "toggle" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text(stringResource(R.string.qk_03155)) },
                                placeholder = { Text(stringResource(R.string.qk_03156)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                        }
                        "custom" -> {
                            OutlinedTextField(
                                value = optionsText,
                                onValueChange = { optionsText = it },
                                label = { Text(qstr(R.string.qk_03157)) },
                                placeholder = { Text(stringResource(R.string.qk_03158)) },
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
            ) { Text(stringResource(R.string.qk_00165)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.qk_00011)) }
        },
    )
}

/**
 * 构建可视化弹窗的结构化提示词
 *
 * 修复：原代码用字符串插值 "title=\"$title\"" 拼接，用户输入含双引号/反斜杠/换行
 * 会破坏 JSON 结构（注入风险）。改为 JSONObject/JSONArray 序列化自动转义。
 */
private fun buildVisualPopupPrompt(type: String, title: String, content: String, options: String): String {
    return when (type) {
        "info" -> {
            val args = JSONObject().apply {
                put("title", title)
                put("content", content)
                put("buttons", JSONObject().apply {
                    put("text", qstr(R.string.qk_02412))
                    put("value", "ok")
                    put("style", "primary")
                })
            }
            qstr(R.string.qk_03159, (args).toString())
        }
        "buttons" -> {
            val btnList = options.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val btns = JSONArray()
            btnList.forEach {
                btns.put(JSONObject().apply {
                    put("text", it)
                    put("value", it)
                    put("style", "primary")
                })
            }
            val args = JSONObject().apply {
                put("title", title)
                put("content", content)
                put("buttons", btns)
            }
            qstr(R.string.qk_03159, (args).toString())
        }
        "form" -> {
            val fields = options.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val inputs = JSONArray()
            fields.forEach {
                inputs.put(JSONObject().apply {
                    put("id", it)
                    put("label", it)
                    put("type", "text")
                })
            }
            val buttons = JSONArray()
            buttons.put(JSONObject().apply { put("text", qstr(R.string.qk_00951)); put("value", "submit"); put("style", "primary") })
            buttons.put(JSONObject().apply { put("text", qstr(R.string.qk_00011)); put("value", "cancel"); put("style", "secondary") })
            val args = JSONObject().apply {
                put("title", title)
                put("content", content)
                put("inputs", inputs)
                put("buttons", buttons)
            }
            qstr(R.string.qk_03159, (args).toString())
        }
        "confirm" -> {
            val buttons = JSONArray()
            buttons.put(JSONObject().apply { put("text", qstr(R.string.qk_02020)); put("value", "confirm"); put("style", "primary") })
            buttons.put(JSONObject().apply { put("text", qstr(R.string.qk_00011)); put("value", "cancel"); put("style", "secondary") })
            val args = JSONObject().apply {
                put("title", title)
                put("content", content)
                put("buttons", buttons)
            }
            qstr(R.string.qk_03159, (args).toString())
        }
        "custom" -> {
            val args = JSONObject().apply {
                put("title", title)
                put("html", qstr(R.string.qk_03160, (content).toString()))
                put("card_title", title)
            }
            qstr(R.string.qk_03161, (args).toString())
        }
        else -> {
            val args = JSONObject().apply {
                put("title", title)
                put("content", content)
            }
            qstr(R.string.qk_03159, (args).toString())
        }
    }
}

/**
 * 构建可视化询问的结构化提示词
 *
 * 修复：同上，改用 JSON 序列化避免注入
 */
private fun buildVisualQuestionPrompt(type: String, question: String, options: String): String {
    return when (type) {
        "choice" -> {
            val opts = options.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val btns = JSONArray()
            opts.forEach {
                btns.put(JSONObject().apply {
                    put("text", it)
                    put("value", it)
                    put("style", "primary")
                })
            }
            val args = JSONObject().apply {
                put("title", qstr(R.string.qk_03162))
                put("content", question)
                put("buttons", btns)
            }
            qstr(R.string.qk_03159, (args).toString())
        }
        "input" -> {
            val inputs = JSONArray()
            inputs.put(JSONObject().apply {
                put("id", "answer")
                put("label", options)
                put("type", "text")
            })
            val buttons = JSONArray()
            buttons.put(JSONObject().apply { put("text", qstr(R.string.qk_00951)); put("value", "submit"); put("style", "primary") })
            val args = JSONObject().apply {
                put("title", qstr(R.string.qk_03163))
                put("content", question)
                put("inputs", inputs)
                put("buttons", buttons)
            }
            qstr(R.string.qk_03159, (args).toString())
        }
        "rating" -> {
            val args = JSONObject().apply {
                put("title", qstr(R.string.qk_01667))
                put("html", qstr(R.string.qk_03164, (question).toString()))
                put("card_title", qstr(R.string.qk_01667))
            }
            qstr(R.string.qk_03161, (args).toString())
        }
        "toggle" -> {
            val args = JSONObject().apply {
                put("title", qstr(R.string.qk_03147))
                put("html", qstr(R.string.qk_03165, (options).toString()))
                put("card_title", options)
            }
            qstr(R.string.qk_03161, (args).toString())
        }
        "custom" -> {
            val args = JSONObject().apply {
                put("title", qstr(R.string.qk_03166))
                put("html", qstr(R.string.qk_03167, (question).toString()))
                put("card_title", qstr(R.string.qk_03166))
            }
            qstr(R.string.qk_03161, (args).toString())
        }
        else -> {
            val args = JSONObject().apply {
                put("title", qstr(R.string.qk_03166))
                put("content", question)
            }
            qstr(R.string.qk_03159, (args).toString())
        }
    }
}