package com.ai.assistance.quro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.skill.QuroSkill
import com.ai.assistance.quro.core.skill.QuroSkillStore
import com.ai.assistance.quro.core.skill.QuroSkillSuites

/**
 * 对话框内「选择技能」：挑出要**随本条消息一起发给 AI** 的技能。
 *
 * ## 为什么不再是「启用 / 禁用」开关（2026-11-05 用户拍板）
 * 旧实现是一个 [Switch] 列表：点一下只改本地 `QuroSkillStore` 里的 `enabled` 布尔值，
 * 发出去的消息里最多只有一句「已启用技能(N个): 名字」，AI 既看不到技能正文，也不知道
 * 用户「这一条消息」想用哪个 —— 开关根本不是一种发送方式，AI 无从感知。
 *
 * 现在的契约：
 * - 技能库只是**素材库**，这里只做「选择」，不写任何持久化、不决定 AI 能不能用；
 * - 选中的技能由调用方挂在**本条消息**上，发送时把全文作为隐藏 user 消息注入，
 *   AI 明确收到「用户为这条消息附加了这些技能，请直接遵循」；
 * - 发送完即清空，不污染下一轮；没选就是没有，不存在「悄悄生效的全局状态」。
 */
@Composable
fun SkillSelectionDialog(
    onDismiss: () -> Unit,
    /** 回传用户选中的技能（按技能库顺序）；调用方把它挂到下一条消息上。 */
    onConfirm: (List<QuroSkill>) -> Unit,
    /** 已挂在输入框上的技能，打开时保持勾选，可继续加/减。 */
    initial: List<QuroSkill> = emptyList(),
) {
    val ctx = LocalContext.current
    // 只列有正文的技能：没有 prompt 的条目发给 AI 也没有内容可用。
    val all = remember { QuroSkillStore.load(ctx).filter { it.prompt.isNotBlank() } }
    var selectedIds by remember { mutableStateOf(initial.map { it.id }.toSet()) }
    var query by remember { mutableStateOf("") }

    val visible = remember(all, query) {
        val q = query.trim()
        if (q.isBlank()) all
        else all.filter { s ->
            s.name.contains(q, ignoreCase = true) ||
                s.description.contains(q, ignoreCase = true) ||
                s.descriptionZh.contains(q, ignoreCase = true) ||
                s.suite.contains(q, ignoreCase = true) ||
                s.trigger.contains(q, ignoreCase = true)
        }
    }
    val grouped = remember(visible) { QuroSkillSuites.group(visible) }
    val selectedSkills = remember(all, selectedIds) { all.filter { it.id in selectedIds } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择技能 · 随本条消息发给 AI", fontSize = 17.sp, fontWeight = FontWeight.Medium) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "选中的技能全文会跟着这一条消息一起发出，AI 明确知道并按它来做；发送后自动清除，不影响下一句。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索名称 / 简介 / 套件 / 触发词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "已选 ${selectedSkills.size} 个 / 库内 ${all.size} 个",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                if (visible.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (all.isEmpty()) "技能库还是空的，先到「技能管理」导入或新建" else "没有匹配「$query」的技能",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        grouped.forEach { (suiteId, groupSkills) ->
                            item(key = "suite_$suiteId") {
                                Text(
                                    "${QuroSkillSuites.label(suiteId)} · ${groupSkills.size}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                                )
                            }
                            items(groupSkills, key = { it.id }) { skill ->
                                SkillPickRow(
                                    skill = skill,
                                    checked = skill.id in selectedIds,
                                    onToggle = {
                                        selectedIds = if (skill.id in selectedIds) selectedIds - skill.id
                                        else selectedIds + skill.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { selectedIds = emptySet() }, enabled = selectedIds.isNotEmpty()) {
                    Text("清空")
                }
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(onClick = { onConfirm(selectedSkills); onDismiss() }) {
                    Text("确定 (${selectedSkills.size})")
                }
            }
        },
    )
}

@Composable
private fun SkillPickRow(
    skill: QuroSkill,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                text = skill.name,
                fontSize = 14.sp,
                fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
                color = cs.onSurface,
                maxLines = 1,
            )
            val desc = if (skill.descriptionZh.isNotBlank()) skill.descriptionZh else skill.description
            if (desc.isNotBlank()) {
                Text(
                    text = desc,
                    fontSize = 11.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}
