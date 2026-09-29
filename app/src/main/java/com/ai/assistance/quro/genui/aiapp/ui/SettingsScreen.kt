package com.ai.assistance.quro.genui.aiapp.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R

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
                Text(stringResource(R.string.qk_03416), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable { onBack() }.padding(horizontal = 8.dp, vertical = 4.dp))
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.qk_01760), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }

            Text(stringResource(R.string.qk_03621),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Spacer(Modifier.height(12.dp))

            SettingsRow(
                iconRes = com.ai.assistance.quro.R.drawable.ic_set_model,
                iconDesc = stringResource(R.string.qk_03735), title = stringResource(R.string.qk_03735),
                subtitle = stringResource(R.string.qk_03805, (personaName).toString(), (modelLabel).toString()),
                onClick = null
            )
            SettingsRow(
                iconRes = com.ai.assistance.quro.R.drawable.ic_set_soul,
                iconDesc = stringResource(R.string.qk_02449), title = stringResource(R.string.qk_02449),
                subtitle = stringResource(R.string.qk_03733),
                onClick = null
            )
            SettingsRow(
                iconRes = com.ai.assistance.quro.R.drawable.ic_set_history,
                iconDesc = stringResource(R.string.qk_03423), title = stringResource(R.string.qk_03423),
                subtitle = stringResource(R.string.qk_03806, (worksCount).toString()),
                onClick = onOpenHistory
            )

            // ── 渲染通道：每轮生成前先问一句走哪条管线 ──
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.qk_03508),
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
                        Text(stringResource(R.string.qk_03692), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            if (askChannel)
                                stringResource(R.string.qk_03513)
                            else
                                stringResource(R.string.qk_03435),
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