package com.ai.assistance.quro.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * 可视化弹窗配置对话框
 * 点击工具菜单"可视化弹窗"时打开，用户填写弹窗参数后发送给AI
 */
@Composable
fun VisualPopupConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var cardTitle by remember { mutableStateOf("") }
    var cardDescription by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置可视化弹窗") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("弹窗标题") },
                    placeholder = { Text("例如：用户反馈") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("弹窗内容") },
                    placeholder = { Text("描述弹窗要展示的内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = cardTitle,
                    onValueChange = { cardTitle = it },
                    label = { Text("小卡片标题（可选）") },
                    placeholder = { Text("对话框中显示的标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = cardDescription,
                    onValueChange = { cardDescription = it },
                    label = { Text("小卡片描述（可选）") },
                    placeholder = { Text("点击查看详情") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // 构建工具调用JSON，直接发送给AI
                    val toolCall = buildString {
                        append("visual_popup ")
                        val args = JSONObject()
                        if (title.isNotBlank()) args.put("title", title)
                        if (content.isNotBlank()) args.put("content", content)
                        if (cardTitle.isNotBlank()) args.put("card_title", cardTitle)
                        if (cardDescription.isNotBlank()) args.put("card_description", cardDescription)
                        append(args.toString())
                    }
                    onConfirm(toolCall)
                },
                enabled = title.isNotBlank() || content.isNotBlank(),
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 可视化询问配置对话框
 * 点击工具菜单"可视化询问"时打开，用户填写询问参数后发送给AI
 */
@Composable
fun VisualQuestionConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var question by remember { mutableStateOf("") }
    var options by remember { mutableStateOf("") }
    var allowCustom by remember { mutableStateOf(true) }
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置可视化询问") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text("问题内容") },
                    placeholder = { Text("例如：你想要哪种风格？") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedTextField(
                    value = options,
                    onValueChange = { options = it },
                    label = { Text("预设选项（每行一个，可选）") },
                    placeholder = { Text("选项1\n选项2\n选项3") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = allowCustom,
                        onCheckedChange = { allowCustom = it },
                    )
                    Text("允许自定义输入", modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("弹窗标题（可选）") },
                    placeholder = { Text("AI 问题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // 构建工具调用JSON，直接发送给AI
                    val toolCall = buildString {
                        append("visual_question ")
                        val args = JSONObject()
                        args.put("question", question)
                        if (options.isNotBlank()) {
                            val optionList = options.lines().filter { it.isNotBlank() }
                            if (optionList.isNotEmpty()) {
                                val optionsArray = JSONArray()
                                optionList.forEach { optionsArray.put(it) }
                                args.put("options", optionsArray)
                            }
                        }
                        args.put("allow_custom", allowCustom)
                        if (title.isNotBlank()) args.put("title", title)
                        append(args.toString())
                    }
                    onConfirm(toolCall)
                },
                enabled = question.isNotBlank(),
            ) {
                Text("确认")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}