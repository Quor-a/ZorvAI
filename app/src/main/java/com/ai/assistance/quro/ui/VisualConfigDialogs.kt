package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R

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
        title = { Text(stringResource(R.string.qk_03102)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.qk_03103)) },
                    placeholder = { Text(stringResource(R.string.qk_03104)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.qk_03105)) },
                    placeholder = { Text(stringResource(R.string.qk_03106)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                OutlinedTextField(
                    value = cardTitle,
                    onValueChange = { cardTitle = it },
                    label = { Text(stringResource(R.string.qk_03107)) },
                    placeholder = { Text(stringResource(R.string.qk_03108)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = cardDescription,
                    onValueChange = { cardDescription = it },
                    label = { Text(stringResource(R.string.qk_03109)) },
                    placeholder = { Text(stringResource(R.string.qk_00097)) },
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
                Text(stringResource(R.string.qk_02412))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.qk_00011))
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
        title = { Text(stringResource(R.string.qk_03110)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it },
                    label = { Text(stringResource(R.string.qk_01073)) },
                    placeholder = { Text(stringResource(R.string.qk_03111)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedTextField(
                    value = options,
                    onValueChange = { options = it },
                    label = { Text(stringResource(R.string.qk_03112)) },
                    placeholder = { Text(stringResource(R.string.qk_03113)) },
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
                    Text(stringResource(R.string.qk_03114), modifier = Modifier.weight(1f))
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.qk_03115)) },
                    placeholder = { Text(stringResource(R.string.qk_03116)) },
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
                Text(stringResource(R.string.qk_02412))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.qk_00011))
            }
        },
    )
}