package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 对话框全屏文本编辑器
 * 用于在对话框中编辑长文本内容，点击完成后将文本回填到输入框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    initialText: String = "",
    onClose: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑文本") },
                navigationIcon = {
                    IconButton(onClick = { onClose(text) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 清空按钮
                    IconButton(onClick = { text = "" }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空")
                    }
                    // 完成按钮
                    IconButton(onClick = { onClose(text) }) {
                        Icon(Icons.Filled.Check, contentDescription = "完成")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
                .verticalScroll(scrollState),
            textStyle = TextStyle(
                fontSize = 16.sp,
                fontFamily = FontFamily.Default,
                lineHeight = 24.sp
            ),
            placeholder = {
                Text(
                    "在此输入文本内容...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            )
        )
    }
}