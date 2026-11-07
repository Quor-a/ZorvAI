package com.ai.assistance.quro.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ai.assistance.quro.core.cluster.QuroRoleType
import com.ai.assistance.quro.core.cluster.QuroRoleTypeRepository
import com.ai.assistance.quro.core.cluster.RoleKind

/**
 * 🔴 集群「角色类型」唯一 UI（2026-11-07 用户裁定）。
 *
 * 旧版编辑页里同时存在「标签（角色类型）」多选区与「角色类型」固定枚举区，
 * 两套各写各的字段。这里把它们合并成**一处**：
 * - 单选切换（一个角色只属于一个类型）；
 * - 「+ 新建」自定义添加类型（名称 / 说明 / 能力档位）；
 * - 「管理」编辑或删除自定义类型，内置 4 档只读、不可删。
 *
 * [RoleKind] 在这里只作为「能力档位」出现（决定运行时能拿到哪些工具），
 * 不再单独占一个叫「角色类型」的入口。
 *
 * 抽成顶层 Composable 也是刻意的：RoleEditScreen 已经很大，再往里塞三层
 * Dialog 会触发 JVM `Method too large`（本项目踩过）。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RoleTypeSection(
    ctx: Context,
    selected: String,
    onSelect: (QuroRoleType) -> Unit,
) {
    var types by remember { mutableStateOf(QuroRoleTypeRepository.all(ctx)) }
    var showEditor by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<QuroRoleType?>(null) }
    var showManage by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("角色类型", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editorTarget = null; showEditor = true }) {
                    Text("+ 新建类型", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(onClick = { showManage = true }) {
                    Text("管理", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                "一个角色只属于一个类型，点一下即可切换；内置四档之外可以自己加。",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                types.forEach { t ->
                    FilterChip(
                        selected = selected == t.name,
                        onClick = { onSelect(t) },
                        label = { Text(t.name) },
                    )
                }
            }
            val cur = types.firstOrNull { it.name == selected }
            if (cur != null) {
                Text(
                    "${cur.name} · 能力档位：${cur.kind.label}\n${cur.hint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (showEditor) {
        RoleTypeEditorDialog(
            ctx = ctx,
            initial = editorTarget,
            onDismiss = { showEditor = false },
            onSaved = { newName ->
                types = QuroRoleTypeRepository.all(ctx)
                showEditor = false
                // 新建或改名后直接切到该类型，kind 随 onSelect 同步给编辑器
                types.firstOrNull { it.name == newName }?.let(onSelect)
            },
        )
    }

    if (showManage) {
        RoleTypeManageDialog(
            ctx = ctx,
            selected = selected,
            onDismiss = { showManage = false; types = QuroRoleTypeRepository.all(ctx) },
            onEdit = { t ->
                showManage = false
                editorTarget = t
                showEditor = true
            },
            onDeleted = { deleted ->
                types = QuroRoleTypeRepository.all(ctx)
                showManage = false
                if (deleted == selected) onSelect(QuroRoleTypeRepository.default(ctx))
            },
        )
    }
}

/** 新建 / 编辑一个角色类型。「能力档位」是这个类型的内部属性，不是另一套类型。 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RoleTypeEditorDialog(
    ctx: Context,
    initial: QuroRoleType?,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var hint by remember { mutableStateOf(initial?.hint.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: RoleKind.EXPERT) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建角色类型" else "编辑角色类型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("类型名称") },
                    placeholder = { Text("如：前端 / 数据分析 / 测试") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it },
                    label = { Text("类型说明") },
                    supportingText = { Text("会展示在选择区，帮助区分不同类型的分工") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("能力档位（内部属性，决定该类型能拿到的工具）",
                    style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoleKind.selectable.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = { kind = k },
                            label = { Text(k.label) },
                        )
                    }
                }
                if (error.isNotBlank()) {
                    Text(error, color = cs.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = if (initial == null) {
                    QuroRoleTypeRepository.add(ctx, name, hint, kind)
                } else {
                    QuroRoleTypeRepository.update(ctx, initial.name, name, hint, kind)
                }
                if (ok) onSaved(name.trim())
                else error = "名称为空，或已存在同名类型（内置类型不可改名）"
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 管理自定义类型：内置 4 档只读，自定义档可编辑 / 删除。 */
@Composable
fun RoleTypeManageDialog(
    ctx: Context,
    selected: String,
    onDismiss: () -> Unit,
    onEdit: (QuroRoleType) -> Unit,
    onDeleted: (String) -> Unit,
) {
    var custom by remember {
        mutableStateOf(QuroRoleTypeRepository.all(ctx).filterNot { it.builtin })
    }
    val cs = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("管理自定义类型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "内置四档（规划 / 执行 / 专家 / 评审）为系统分工，不可删除；下面是你自己添加的类型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
                if (custom.isEmpty()) {
                    Text(
                        "还没有自定义类型，回编辑页点「+ 新建类型」即可添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                custom.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "能力档位：${t.kind.label}" + if (selected == t.name) " · 当前使用" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onEdit(t) }) { Text("编辑") }
                        TextButton(onClick = {
                            QuroRoleTypeRepository.delete(ctx, t.name)
                            custom = QuroRoleTypeRepository.all(ctx).filterNot { it.builtin }
                            onDeleted(t.name)
                        }) { Text("删除", color = cs.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 「添加角色」弹窗里用的紧凑版：同一份类型库、同样的切换与新建能力，
 * 保证创建入口和编辑入口不会出现两套口径。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RoleTypePickerCompact(
    ctx: Context,
    selected: String,
    onSelect: (QuroRoleType) -> Unit,
) {
    var types by remember { mutableStateOf(QuroRoleTypeRepository.all(ctx)) }
    var showAdd by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("角色类型", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { t ->
                FilterChip(
                    selected = selected == t.name,
                    onClick = { onSelect(t) },
                    label = { Text(t.name) },
                )
            }
            AssistChip(onClick = { showAdd = true }, label = { Text("+ 新建类型") })
        }
    }

    if (showAdd) {
        RoleTypeEditorDialog(
            ctx = ctx,
            initial = null,
            onDismiss = { showAdd = false },
            onSaved = { n ->
                types = QuroRoleTypeRepository.all(ctx)
                showAdd = false
                types.firstOrNull { it.name == n }?.let(onSelect)
            },
        )
    }
}
