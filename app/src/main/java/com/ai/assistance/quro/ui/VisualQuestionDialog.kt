package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.assistance.quro.core.tools.VisualQuestionQueue
import com.ai.assistance.quro.core.tools.VisualActionQueue
import com.ai.assistance.quro.core.tools.VisualPendingQuestion
import com.ai.assistance.quro.core.tools.VisualPendingAction

/**
 * 可视化问答弹窗 - 显示AI的问题，用户可以选择预设选项或输入自定义答案
 */
@Composable
fun VisualQuestionDialog() {
    val cs = MaterialTheme.colorScheme
    var currentQuestion by remember { mutableStateOf<Pair<Int, VisualPendingQuestion>?>(null) }
    var customAnswer by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }

    // 修复：原 while(true) delay(500) 浪费 CPU，与 VisualPopupQueue 事件驱动模式不一致。
    // 改为 eventFlow 触发：QuestionAdded 时拉取新问题，QuestionRemoved 时关闭弹窗。
    LaunchedEffect(Unit) {
        VisualQuestionQueue.eventFlow.collect { event ->
            when (event) {
                is VisualQuestionQueue.QuestionEvent.QuestionAdded -> {
                    if (currentQuestion == null) {
                        currentQuestion = VisualQuestionQueue.getCurrentQuestion()
                        showCustomInput = false
                        customAnswer = ""
                    }
                }
                is VisualQuestionQueue.QuestionEvent.QuestionRemoved -> {
                    if (currentQuestion != null) {
                        currentQuestion = null
                    }
                }
            }
        }
    }

    currentQuestion?.let { (index, pending) ->
        Dialog(
            onDismissRequest = { /* 不允许关闭，必须回答 */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = cs.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // 标题
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            Icons.Filled.HelpOutline,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pending.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface
                        )
                    }

                    // 问题内容
                    Text(
                        text = pending.question,
                        fontSize = 16.sp,
                        color = cs.onSurface,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // 选项列表
                    if (pending.options.isNotEmpty()) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                        ) {
                            // 修复：原代码 itemsIndexed 用 position 作 key，options 提交后
                            // 索引变化会导致不必要的重组。改用 option 自身作 key（更稳定）。
                            items(pending.options, key = { it }) { option ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable {
                                            VisualQuestionQueue.submitAnswer(index, option)
                                            currentQuestion = null
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = cs.surfaceVariant
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = option,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        fontSize = 15.sp,
                                        color = cs.onSurface
                                    )
                                }
                            }
                        }
                    }

                    // 自定义输入
                    if (pending.allowCustom) {
                        Spacer(modifier = Modifier.height(12.dp))

                        if (!showCustomInput) {
                            OutlinedButton(
                                onClick = { showCustomInput = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("输入自定义答案")
                            }
                        } else {
                            OutlinedTextField(
                                value = customAnswer,
                                onValueChange = { customAnswer = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("输入你的答案") },
                                placeholder = { Text("请输入...") },
                                minLines = 2,
                                maxLines = 4
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        showCustomInput = false
                                        customAnswer = ""
                                    }
                                ) {
                                    Text("取消")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (customAnswer.isNotBlank()) {
                                            VisualQuestionQueue.submitAnswer(index, customAnswer)
                                            currentQuestion = null
                                        }
                                    },
                                    enabled = customAnswer.isNotBlank()
                                ) {
                                    Text("提交")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 可视化操作弹窗 - 显示AI创建的操作按钮，用户点击执行对应操作
 */
@Composable
fun VisualActionDialog() {
    val cs = MaterialTheme.colorScheme
    var currentAction by remember { mutableStateOf<Pair<Int, VisualPendingAction>?>(null) }

    // 修复：与 VisualQuestionDialog 对齐，改为 eventFlow 驱动
    LaunchedEffect(Unit) {
        VisualActionQueue.eventFlow.collect { event ->
            when (event) {
                is VisualActionQueue.ActionEvent.ActionAdded -> {
                    if (currentAction == null) {
                        currentAction = VisualActionQueue.getCurrentAction()
                    }
                }
                is VisualActionQueue.ActionEvent.ActionRemoved -> {
                    if (currentAction != null) {
                        currentAction = null
                    }
                }
            }
        }
    }

    currentAction?.let { (index, pending) ->
        Dialog(
            onDismissRequest = { /* 不允许关闭，必须选择 */ },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = cs.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // 标题
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            Icons.Filled.TouchApp,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pending.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = cs.onSurface
                        )
                    }

                    // 说明文字
                    Text(
                        text = pending.message,
                        fontSize = 16.sp,
                        color = cs.onSurface,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    // 按钮列表
                    pending.buttons.forEach { button ->
                        val buttonColor = when (button.style) {
                            "danger" -> cs.error
                            "secondary" -> cs.secondary
                            else -> cs.primary
                        }
                        val textColor = when (button.style) {
                            "danger" -> cs.onError
                            "secondary" -> cs.onSecondary
                            else -> cs.onPrimary
                        }

                        Button(
                            onClick = {
                                VisualActionQueue.submitAction(index, button.value)
                                currentAction = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = button.text,
                                fontSize = 15.sp,
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 可视化弹窗容器 - 同时管理问答弹窗和操作弹窗
 */
@Composable
fun VisualDialogs() {
    VisualQuestionDialog()
    VisualActionDialog()
}
