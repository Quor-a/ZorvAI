package com.ai.assistance.quro.cluster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
//🔴 #181：describe() 里用 Regex 压缩空白做摘要，漏 import 会直接编译不过
import kotlin.text.Regex
import com.ai.assistance.quro.cluster.engine.ClusterEngine
import com.ai.assistance.quro.cluster.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 任务实时视图：多角色对话流 + 主持控制台。
 * 宿主把它挂在自己的任务详情页里即可，不需要自建任何服务。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskScreen(taskId: TaskId) {
    val engine = remember { ClusterEngine.get() }
    val scope = rememberCoroutineScope()

    var task by remember { mutableStateOf<Task?>(null) }
    var messages by remember { mutableStateOf(listOf<Message>()) }
    var events by remember { mutableStateOf(listOf<ClusterEvent>()) }
    var inject by remember { mutableStateOf("") }

    // 轮询刷新：SDK 侧事件流也可直接用 engine.events()
    LaunchedEffect(taskId) {
        while (true) {
            task = engine.task(taskId)
            messages = engine.messages(taskId, 100)
            events = engine.replay(taskId)
            if (task?.isOpen() == false) break
            delay(700)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("主持控制台") }) },
        bottomBar = {
            Row(Modifier.padding(8.dp)) {
                OutlinedTextField(
                    value = inject, onValueChange = { inject = it },
                    placeholder = { Text("插话 / 改方向…") }, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Button(onClick = {
                    engine.command(UserCommand.Inject(taskId, inject)); inject = ""
                }) { Text("插话") }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TaskStatusBar(task)
            Row(Modifier.padding(horizontal = 12.dp)) {
                Button(onClick = { engine.command(UserCommand.Pause(taskId)) }) { Text("暂停") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { engine.command(UserCommand.Resume(taskId)) }) { Text("继续") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    engine.command(UserCommand.Terminate(taskId, "用户中止"))
                }) { Text("中止") }
            }
            LazyColumn(Modifier.weight(1f).padding(12.dp)) {
                items(messages) { m -> MessageBubble(m) }
            }
            EventTimeline(events.takeLast(8).reversed(), Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun TaskStatusBar(task: Task?) {
    val (done, total) = task?.graph?.progress() ?: (0 to 0)
    Column(Modifier.padding(12.dp)) {
        Text(task?.state?.name ?: "LOADING", fontWeight = FontWeight.Bold)
        Text("目标：${task?.goal.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        task?.acceptance?.let {
            Text("验收：${it.description}", style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = if (total == 0) 0f else done.toFloat() / total,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
        Text(
            "子任务 $done/$total · 轮次 ${task?.turnCount ?: 0} · token ${task?.tokenUsed ?: 0} · 重规划 ${task?.replanCount ?: 0}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun MessageBubble(m: Message) {
    val isHost = m.agentId == HOST_AGENT_ID
    val bg = if (isHost) MaterialTheme.colorScheme.primaryContainer
    else if (m.role == MessageRole.USER) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.secondaryContainer
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalAlignment = if (isHost) androidx.compose.ui.Alignment.CenterHorizontally
        else androidx.compose.ui.Alignment.Start
    ) {
        Box(
            Modifier.background(bg, RoundedCornerShape(10.dp)).padding(10.dp)
        ) {
            Column {
                Text(
                    if (isHost) "🎯 主持" else m.agentId.value,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold
                )
                Text(m.content.take(1_200), style = MaterialTheme.typography.bodyMedium)
                if (m.hostModelId != null) {
                    Text("模型：${m.hostModelId}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun EventTimeline(events: List<ClusterEvent>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("事件流", style = MaterialTheme.typography.labelMedium)
        events.forEach { e ->
            Text(
                "· ${e::class.simpleName} ${describe(e)}",
                style = MaterialTheme.typography.labelSmall, color = Color.Gray, maxLines = 2
            )
        }
    }
}

private fun describe(e: ClusterEvent): String = when (e) {
    is ClusterEvent.SpeakerSelected -> "→ ${e.agentId.value}（${e.reason}）"
    is ClusterEvent.ProposalSubmitted -> "${e.agentId.value} 提案：${e.summary.take(40)}"
    // 🔴 角色真实发言（#181）：不补这条就会落到 else -> "" 被静默丢弃
    is ClusterEvent.AgentSpoke -> "${e.agentId.value}：${e.text.replace(Regex("\\s+"), " ").take(40)}"
    is ClusterEvent.VerdictIssued -> "裁决 ${if (e.pass) "通过" else "不通过"}：${e.reason.take(40)}"
    is ClusterEvent.ArtifactProduced -> "产出：${e.title}"
    is ClusterEvent.ErrorRaised -> "⚠ ${e.message.take(60)}"
    is ClusterEvent.ModelSwitched -> "模型切换 ${e.from}→${e.to}（${e.reason}）"
    is ClusterEvent.TaskClosed -> "关闭：${e.reason}"
    else -> ""
}
