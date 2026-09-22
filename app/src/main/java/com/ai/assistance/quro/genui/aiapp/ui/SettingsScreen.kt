package com.ai.assistance.quro.genui.aiapp.ui

import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置页（GenUI · 生成式 UI 对话）
 * 入口：右上角 ⚙。包含：身份与模型（跟随 ZorvAI 主设置，只读）/ 历史对话。
 *
 * 说明：本页不再提供独立的 API Key / Base URL / 模型配置，也不再提供人格孵化。
 * 模型配置与人格卡（灵魂）统一由 ZorvAI 主设置管理，GenUI 助手直接沿用，
 * 保证两个入口用的是同一份模型与同一个「人」。
 */
@Composable
fun SettingsScreen(
    worksCount: Int,
    personaName: String,
    modelLabel: String,
    askChannel: Boolean,
    onToggleAskChannel: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("← 返回", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { onBack() }.padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.weight(1f))
                Text("设置", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }

            Text(
                "GenUI · 生成式 UI 对话",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(12.dp))

            SettingsRow(
                iconRes = com.ai.assistance.quro.R.drawable.ic_set_model,
                iconDesc = "身份与模型", title = "身份与模型",
                subtitle = "人格卡：$personaName · 模型：$modelLabel（由 ZorvAI 主设置统一管理）",
                onClick = null
            )
            SettingsRow(
                iconRes = com.ai.assistance.quro.R.drawable.ic_set_soul,
                iconDesc = "灵魂注入", title = "灵魂注入",
                subtitle = "已接入 ZorvAI 人格卡/记忆/标签体系，与主对话共用同一个身份，无需在此单独设置",
                onClick = null
            )
            SettingsRow(
                iconRes = com.ai.assistance.quro.R.drawable.ic_set_history,
                iconDesc = "历史对话", title = "历史对话",
                subtitle = "共 $worksCount 个界面记录 · 点击回放与查看往来",
                onClick = onOpenHistory
            )

            // ── 渲染通道：每轮生成前先问一句走哪条管线 ──
            Spacer(Modifier.height(8.dp))
            Text(
                "渲染通道",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每轮询问渲染通道", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            if (askChannel)
                                "已开启 · 每次生成前弹出四选一（GenUI / A2UI / Markdown / HTML），选完才开始生成"
                            else
                                "已关闭 · 不再询问，统一走 GenUI SDK；你若在话里点名通道仍会照办",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Switch(checked = askChannel, onCheckedChange = onToggleAskChannel)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsRow(iconRes: Int, iconDesc: String, title: String, subtitle: String, onClick: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(iconRes),
                contentDescription = iconDesc,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            if (onClick != null) {
                Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
