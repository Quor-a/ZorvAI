# -*- coding: utf-8 -*-
"""Task #126：把可视化组件库改由 CardSdk 名册（80 种）自动驱动。

旧版是手抄 3 组样例（富组件 / 富卡片 / AI 自写），加了 34 种新卡片却一个都没进目录页 ——
等于新组件「能解析、能渲染，但用户和 AI 都不知道它存在」。
现在目录页只从 CardSdk.catalog() 读，以后加卡片 UI 一行都不用改。
"""
import io
import os
import sys

P = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "java", "com", "ai", "assistance", "quro", "ui",
    "QuroComponentGalleryScreen.kt",
)

CODE = '''package com.ai.assistance.quro.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.core.cards.CardSdk
import com.ai.assistance.quro.core.cards.CardTemplate
import com.ai.assistance.quro.core.tools.UiWidgetTool
import com.ai.assistance.quro.util.qstr

/**
 * Quro 可视化组件库（v1400 · 名册自动驱动版）
 *
 * 目录页**不再手抄样例**：整份清单由 [CardSdk.catalog()] 单一真源生成（当前 80 种，
 * 输入交互 / 数据展示 / 布局容器 / 操作动作 / 媒体 / 导航 / 流程 / 装饰 / AI 自写 9 类）。
 *
 * ## 为什么必须这么改
 *
 * 旧版把「富组件 / 富卡片 / AI 自写」三组样例写死在 UI 里，而这一轮新增的 34 种卡片
 * （keyvalue / ring / stackedbar / treetimeline …）**一个都没进目录页** ——
 * 用户在组件库里根本看不到它们，等于新能力「能解析、能渲染，但没人知道它存在」。
 * 名册驱动之后，以后再加卡片，UI 一行都不用改，目录页自动长出来。
 *
 * ## 下端通道
 *
 * 点任意 chip 走真实工具 [UiWidgetTool] 把样例 JSON 发到对话卡片栏，所见即所得；
 * 页面底部同时列出正文围栏通道（```card / ```cards / ```cardui），
 * 与工具调用通道等价，但允许模型「边说边画」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroComponentGalleryScreen(
    onBack: () -> Unit,
    onComponentSelected: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    // 名册快照：进程内不变，直接读单一真源，不复制一份进 UI。
    val catalog = remember { CardSdk.catalog() }
    val total = catalog.size
    // 过滤 + 按类目分组。过滤命中 type / description / category 任一。
    val grouped: List<Pair<String, List<CardTemplate>>> = remember(catalog, query) {
        val q = query.trim()
        val hit = if (q.isEmpty()) catalog else catalog.filter {
            it.type.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true) ||
                it.category.contains(q, ignoreCase = true)
        }
        CATEGORY_ORDER.mapNotNull { c ->
            val list = hit.filter { it.category == c }
            if (list.isEmpty()) null else c to list
        } + (hit.filter { it.category !in CATEGORY_ORDER }.let { rest ->
            if (rest.isEmpty()) null else CATEGORY_FALLBACK to rest
        })
    }
    val shown = grouped.sumOf { it.second.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.qk_01678)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "组件名册 " + total.toString() + " 种 · 当前显示 " + shown.toString() + " 种",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurface,
                    )
                    Text(
                        "清单由 CardSdk 名册自动生成，新增卡片自动出现；点任意组件即通过 ui_widget 真实下发到对话卡片栏。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("搜索组件类型 / 说明", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(16.dp)) },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            grouped.forEach { (category, specs) ->
                item {
                    SectionTitle(
                        (CATEGORY_LABELS[category] ?: category) + " · " + specs.size.toString() + " 种",
                        cs,
                    )
                }
                item {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        specs.forEach { spec ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    launchSpec(context, UiWidgetTool(), spec.sample, spec.type)
                                    onComponentSelected?.invoke(spec.type)
                                },
                                label = { Text(spec.type, fontSize = 12.sp) },
                            )
                        }
                    }
                }
                // 说明行：把本组所有 description 并成一段，截两行 —— 81 个 chip 也看得懂每组是干嘛的。
                item {
                    Text(
                        specs.joinToString("；") { it.description },
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (grouped.isEmpty()) {
                item {
                    Text(
                        "没有匹配的组件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }

            item {
                SectionTitle("正文围栏通道 · 3 种", cs)
            }
            item {
                Surface(
                    Modifier.fillMaxWidth(),
                    color = cs.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FenceHint("card", "单组件：一段 JSON 对象")
                        FenceHint("cards", "多组件：JSON 数组，或 {\"layout\":..,\"children\":[..]} 合成一张组合卡")
                        FenceHint("cardui", "A2UI 风格邻接表：扁平 id + children 引用，自动还原成一棵树")
                        Text(
                            "与 ui_widget 工具调用通道等价，但允许模型在正文里「边说边画」；"
                                + "围栏未闭合（流式生成中）时也会逐帧解析，JSON 合法即渲染。",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
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
                            stringResource(R.string.qk_01682),
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

/** 围栏通道说明行：```card 这样的反引号在字符串里写成三个单引号包起来的原文。 */
@Composable
private fun FenceHint(name: String, desc: String) {
    Text(
        "```" + name + "  ——  " + desc,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 类目展示顺序（与名册里的 category 字符串一一对应，新增类目落到末尾「其它」组）。 */
private val CATEGORY_ORDER = listOf("input", "data", "layout", "action", "media", "nav", "flow", "decoration", "aiwrite")

/** 未登记在顺序表里的类目归到这一组。 */
private const val CATEGORY_FALLBACK = "other"

private val CATEGORY_LABELS = mapOf(
    "input" to "输入交互",
    "data" to "数据展示",
    "layout" to "布局容器",
    "action" to "操作动作",
    "media" to "媒体展示",
    "nav" to "导航链接",
    "flow" to "流程状态",
    "decoration" to "装饰提示",
    "aiwrite" to "AI 自写",
    "other" to "其它",
)

/** 用真实工具把组件 spec 发送到对话卡片栏（桥未连接时回落全局卡片栏） */
private fun launchSpec(context: Context, tool: com.ai.assistance.quro.core.tools.QuroTool, spec: String, label: String) {
    val result = runCatching { tool.run(context, spec) }.getOrElse { """{"error":"$it"}""" }
    val ok = result.contains("\\"ok\\":true") || !result.contains("\\"error\\"")
    Toast.makeText(
        context,
        if (ok) qstr(R.string.qk_01683, label) else qstr(R.string.qk_01684, label),
        Toast.LENGTH_SHORT,
    ).show()
}

@Composable
private fun SectionTitle(text: String, cs: Color) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs)
}
'''

with io.open(P, "r", encoding="utf-8", newline="") as f:
    raw = f.read()
crlf = "\r\n" in raw
# 文件原用 CRLF，保持一致，避免整份文件换行符被 IDE 判成改动
if crlf:
    CODE = CODE.replace("\n", "\r\n")

with io.open(P + ".tmp", "w", encoding="utf-8", newline="") as f:
    f.write(CODE)
os.replace(P + ".tmp", P)
print("written: %s (crlf=%s)" % (P, crlf))
