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

/**
 * 技能选择对话框：列出所有已导入的技能，支持启用/禁用切换。
 * 与 AciAppSelectionDialog 风格一致。
 */
@Composable
fun SkillSelectionDialog(
    onDismiss: () -> Unit,
    onSkillsChanged: (Int) -> Unit, // 回传已启用技能数量
) {
    val ctx = LocalContext.current
    var skills by remember { mutableStateOf(QuroSkillStore.load(ctx)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("选择技能")
        },
        text = {
            if (skills.isEmpty()) {
                Text(
                    "暂无已导入的技能。\n可在「技能管理」中新增或导入技能。",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(skills, key = { it.id }) { skill ->
                        SkillItem(
                            skill = skill,
                            onToggle = { newEnabled ->
                                // 更新本地状态
                                skills = skills.map {
                                    if (it.id == skill.id) it.copy(enabled = newEnabled) else it
                                }
                                // 持久化
                                QuroSkillStore.save(ctx, skills)
                                // 回传已启用数量
                                onSkillsChanged(skills.count { it.enabled })
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "已启用 ${skills.count { it.enabled }} / ${skills.size} 个技能",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                Button(onClick = onDismiss) {
                    Text("完成")
                }
            }
        },
    )
}

@Composable
private fun SkillItem(
    skill: QuroSkill,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!skill.enabled) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(
            checked = skill.enabled,
            onCheckedChange = { onToggle(it) },
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = skill.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (skill.enabled) cs.onSurface else cs.onSurfaceVariant,
            )
            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
