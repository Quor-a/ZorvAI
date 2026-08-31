package com.ai.assistance.quro.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.ai.assistance.quro.core.model.QuroConversationBranch
import com.ai.assistance.quro.core.model.QuroConversationBranchRepository
import com.ai.assistance.quro.core.model.QuroConversationTree
import com.ai.assistance.quro.core.model.BranchVisualizationNode
import com.ai.assistance.quro.core.model.BranchVisualizationTree
import com.ai.assistance.quro.core.model.BranchStatistics
import kotlinx.coroutines.launch

/**
 * 对话分支可视化界面
 * 
 * 提供分支树的可视化展示、分支切换、创建子分支、合并分支等功能。
 * 参考 Agora 的树状结构对话设计。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroBranchVisualizationScreen(
    treeId: String,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { QuroConversationBranchRepository(context) }
    val scope = rememberCoroutineScope()
    
    var tree by remember { mutableStateOf(repository.getTree(treeId)) }
    var showCreateBranchDialog by remember { mutableStateOf<String?>(null) } // 父分支ID
    var showMergeDialog by remember { mutableStateOf<Pair<String, String>?>(null) } // 源分支ID, 目标分支ID
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var showStatistics by remember { mutableStateOf(false) }
    
    // 刷新树数据
    fun refreshTree() {
        tree = repository.getTree(treeId)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "对话分支",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showStatistics = true }) {
                        Icon(Icons.Default.Info, contentDescription = "统计信息")
                    }
                    IconButton(onClick = { 
                        showCreateBranchDialog = tree?.activeBranchId
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "创建分支")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 统计信息卡片
            tree?.let { currentTree ->
                val vizTree = remember(currentTree) { BranchVisualizationTree(currentTree) }
                val stats = remember(vizTree) { vizTree.getStatistics() }
                
                BranchStatsCard(stats)
            }
            
            // 分支树可视化
            tree?.let { currentTree ->
                val vizTree = remember(currentTree) { BranchVisualizationTree(currentTree) }
                val nodes = remember(vizTree) { vizTree.generateVisualizationNodes() }
                
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(nodes, key = { it.branch.id }) { node ->
                        BranchNodeCard(
                            node = node,
                            isActive = node.branch.id == currentTree.activeBranchId,
                            onSwitch = {
                                scope.launch {
                                    repository.switchActiveBranch(treeId, node.branch.id)
                                    refreshTree()
                                }
                            },
                            onCreateChild = {
                                showCreateBranchDialog = node.branch.id
                            },
                            onRename = {
                                showRenameDialog = node.branch.id
                            },
                            onDelete = {
                                scope.launch {
                                    repository.deleteBranch(treeId, node.branch.id)
                                    refreshTree()
                                }
                            }
                        )
                    }
                }
            } ?: run {
                // 树不存在
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("对话树不存在")
                }
            }
        }
    }
    
    // 创建分支对话框
    showCreateBranchDialog?.let { parentBranchId ->
        CreateBranchDialog(
            onDismiss = { showCreateBranchDialog = null },
            onCreate = { name ->
                scope.launch {
                    repository.createBranch(treeId, parentBranchId, name)
                    refreshTree()
                    showCreateBranchDialog = null
                }
            }
        )
    }
    
    // 重命名对话框
    showRenameDialog?.let { branchId ->
        val branch = tree?.branches?.get(branchId)
        branch?.let { currentBranch ->
            RenameBranchDialog(
                currentName = currentBranch.name,
                onDismiss = { showRenameDialog = null },
                onRename = { newName ->
                    scope.launch {
                        repository.renameBranch(treeId, branchId, newName)
                        refreshTree()
                        showRenameDialog = null
                    }
                }
            )
        }
    }
    
    // 统计信息对话框
    if (showStatistics) {
        tree?.let { currentTree ->
            val vizTree = remember(currentTree) { BranchVisualizationTree(currentTree) }
            val stats = remember(vizTree) { vizTree.getStatistics() }
            
            StatisticsDialog(
                stats = stats,
                onDismiss = { showStatistics = false }
            )
        }
    }
}

@Composable
fun BranchStatsCard(stats: BranchStatistics) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "分支概览",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("分支数", stats.totalBranches.toString())
                StatItem("最大深度", stats.maxDepth.toString())
                StatItem("消息数", stats.totalMessages.toString())
            }
        }
    }
}

@Composable
fun BranchNodeCard(
    node: BranchVisualizationNode,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onCreateChild: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val backgroundColor = when {
        isActive -> MaterialTheme.colorScheme.primaryContainer
        node.depth == 0 -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (node.depth * 16).dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // 头部：名称和状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // 分支指示器
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isActive) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.outline
                            )
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Column {
                        Text(
                            text = node.branch.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isActive) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.onSurface
                        )
                        
                        Text(
                            text = "${node.branch.messages.size} 条消息",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                
                // 操作按钮
                Row {
                    if (isActive) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "当前分支",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        IconButton(
                            onClick = onSwitch,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = "切换到此分支",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    IconButton(
                        onClick = onCreateChild,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "创建子分支",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    
                    IconButton(
                        onClick = onRename,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "重命名",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    
                    if (node.depth > 0) { // 不能删除根分支
                        IconButton(
                            onClick = onDelete,
                            modifier = Modifier.size(32.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            
            // 最近消息预览
            if (node.branch.messages.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                
                val lastMessage = node.branch.messages.last()
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
                ) {
                    Text(
                        text = lastMessage.content.take(100) + if (lastMessage.content.length > 100) "..." else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(8.dp),
                        maxLines = 2
                    )
                }
            }
            
            // 子分支指示器
            if (node.branch.childBranchIds.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "↓ ${node.branch.childBranchIds.size} 个子分支",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
fun CreateBranchDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建新分支") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("分支名称") },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入分支名称") }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.ifBlank { "新分支" }) },
                enabled = true
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun RenameBranchDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名分支") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("分支名称") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(name) },
                enabled = name.isNotBlank()
            ) {
                Text("重命名")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun StatisticsDialog(
    stats: BranchStatistics,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分支统计") },
        text = {
            Column {
                StatRow("总分支数", stats.totalBranches.toString())
                StatRow("最大深度", stats.maxDepth.toString())
                StatRow("总消息数", stats.totalMessages.toString())
                StatRow("活跃分支ID", stats.activeBranchId.take(8) + "...")
                StatRow("根分支ID", stats.rootBranchId.take(8) + "...")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
