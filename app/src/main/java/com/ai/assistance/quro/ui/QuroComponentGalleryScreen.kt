package com.ai.assistance.quro.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.tools.UiCardTool
import com.ai.assistance.quro.core.tools.UiWidgetTool

/**
 * Quro 可视化组件库（v-rewrite 重构版）
 *
 * 旧版的「内置组件」Demo（卡片/按钮/输入框/展示/交互/覆盖层 6 类写死的预览）已移除。
 * 现统一融合真实能力：
 *  - 富组件（ui_widget）：stat/progress/list/pie/rating/table/alert/tabs/steps/timeline …
 *  - 富卡片（ui_card）：todo/note/actions …
 *  - AI 自写（mermaid / miniapp）：AI 下发的流程图与小程序
 * 点击任意样例，即通过真实工具把组件发送到对话卡片栏（回归全局卡片栏兜底），所见即所得。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroComponentGalleryScreen(
    onBack: () -> Unit,
    onComponentSelected: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    // 富组件（ui_widget）样例：标签 -> spec
    val widgetSamples = listOf(
        "统计卡" to """{"type":"stat","title":"今日访问","value":"12847","unit":"次","delta":"+12.3%"}""",
        "进度卡" to """{"type":"progress","title":"项目进度","value":68,"suffix":"%","label":"开发阶段"}""",
        "列表卡" to """{"type":"list","title":"今日待办","items":[{"text":"完成报告","selected":true},{"text":"回复邮件"}]}""",
        "饼图" to """{"type":"pie","title":"时间分配","segments":[{"name":"工作","value":8,"color":"#FF6384"},{"name":"睡眠","value":7,"color":"#36A2EB"}]}""",
        "评分" to """{"type":"rating","label":"满意度","max":5,"value":4}""",
        "表格" to """{"type":"table","headers":["名称","数量"],"rows":[["苹果","3"],["香蕉","5"]]}""",
        "提醒" to """{"type":"alert","severity":"warning","text":"磁盘空间不足"}""",
        "标签页" to """{"type":"tabs","tabs":[{"title":"概览","body":"内容A"},{"title":"详情","body":"内容B"}]}""",
        "步骤" to """{"type":"steps","steps":[{"title":"下单","status":"done"},{"title":"发货","status":"active"},{"title":"签收"}],"current":1}""",
        "时间线" to """{"type":"timeline","events":[{"time":"09:00","title":"晨会"},{"time":"14:00","title":"评审"}]}""",
    )
    // 富卡片（ui_card）样例：标签 -> spec
    val cardSamples = listOf(
        "待办卡" to """{"kind":"todo","title":"任务","items":[{"text":"写文档","done":true},{"text":"发邮件"}]}""",
        "笔记卡" to """{"kind":"note","title":"读书笔记","body":"这是一段 Markdown 笔记内容。"}""",
        "动作卡" to """{"kind":"actions","title":"可用操作","actions":[{"label":"打开终端","command":"ui_open_terminal"}]}""",
    )
    // AI 自写（mermaid / miniapp）样例：标签 -> spec
    val aiSamples = listOf(
        "Mermaid 流程图" to """{"type":"mermaid","title":"登录流程","source":"flowchart TD\nA[开始] --> B{已登录?}\nB -- 否 --> C[跳登录页]\nB -- 是 --> D[进首页]"}""",
        "小程序 MiniApp" to """{"type":"miniapp","title":"示例卡片","html":"<div style='padding:16px'><h3>AI 自写小程序</h3><p>这段 HTML/CSS/JS 由 AI 生成并在对话框内实时渲染。</p></div>"}""",
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("可视化组件库") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { SectionTitle("富组件（ui_widget）", cs) }
            item {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    widgetSamples.forEach { (label, spec) ->
                        FilterChip(
                            selected = false,
                            onClick = { launchSpec(context, UiWidgetTool(), spec, label) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            item { SectionTitle("富卡片（ui_card）", cs) }
            item {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    cardSamples.forEach { (label, spec) ->
                        FilterChip(
                            selected = false,
                            onClick = { launchSpec(context, UiCardTool(), spec, label) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            item { SectionTitle("AI 自写（mermaid / miniapp）", cs) }
            item {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    aiSamples.forEach { (label, spec) ->
                        FilterChip(
                            selected = false,
                            onClick = { launchSpec(context, UiWidgetTool(), spec, label) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    color = cs.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Info, null, tint = cs.onPrimaryContainer)
                        Text(
                            "点击任意组件，真实样例会发送到对话卡片栏。ui_widget / ui_card / mermaid / miniapp 均为真实渲染能力，已并入本组件库；旧版写死的「内置组件」Demo 已移除。",
                            color = cs.onPrimaryContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

/** 用真实工具把组件 spec 发送到对话卡片栏（桥未连接时回落全局卡片栏） */
private fun launchSpec(context: Context, tool: QuroTool, spec: String, label: String) {
    val result = runCatching { tool.run(context, spec) }.getOrElse { """{"error":"$it"}""" }
    val ok = result.contains("\"ok\":true") || !result.contains("\"error\"")
    Toast.makeText(context, if (ok) "已发送：$label" else "发送失败：$label", Toast.LENGTH_SHORT).show()
}

@Composable
private fun SectionTitle(text: String, cs: ColorScheme) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onSurface)
}
