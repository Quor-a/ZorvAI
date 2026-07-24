package com.ai.assistance.quro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Quro 可视化组件画廊（v225 精简版）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroComponentGalleryScreen(
    onBack: () -> Unit,
    onComponentSelected: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().background(cs.background)) {
        TopAppBar(
            title = { Text("可视化组件库") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
        )

        LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("卡片组件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { GalleryPlaceholder("人物卡 / 状态卡 / 指标卡 / 媒体卡 / 操作卡", cs) }

            item { Text("按钮组件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { GalleryPlaceholder("主按钮 / 图标按钮 / 分段控件 / FAB / Chip", cs) }

            item { Text("输入框组件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { GalleryPlaceholder("文本框 / 搜索栏 / 下拉选择", cs) }

            item { Text("展示组件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { GalleryPlaceholder("徽章 / 头像 / 进度条 / 空状态 / 骨架屏", cs) }

            item { Text("交互组件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { GalleryPlaceholder("开关 / 滑块 / 手势 / 列表项", cs) }

            item { Text("覆盖层组件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { GalleryPlaceholder("提示条 / Snackbar / 对话框 / 底部弹层 / 通知卡", cs) }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun GalleryPlaceholder(description: String, cs: ColorScheme) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainerLow,
        border = BorderStroke(1.dp, cs.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Construction, "建设中", tint = cs.primary.copy(alpha = 0.4f), modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
                Text(description, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text("(组件 Demo 陆续补全)", color = cs.onSurface.copy(alpha = 0.4f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
