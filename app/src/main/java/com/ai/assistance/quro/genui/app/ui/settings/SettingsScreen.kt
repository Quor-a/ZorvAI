package com.ai.assistance.quro.genui.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme

/**
 * 统一设置首页 —— 把原来散落在顶栏的「魂/记/权/模」收进来，给它们完整名称。
 *
 * 魂 → 灵魂注入
 * 记 → 记忆库
 * 权 → 工具权限
 * 模 → 模型服务
 */
enum class SettingsItem {
    Soul, Memory, Perms, ModelConfig
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: GenStore,
    onBack: () -> Unit,
    onItem: (SettingsItem) -> Unit
) {
    Scaffold(
        containerColor = GenTheme.Screen,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().background(GenTheme.Screen)
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("← 返回", color = GenTheme.Amber, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("设置", color = GenTheme.Text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp).padding(top = 8.dp)
        ) {
            val items = listOf(
                SettingsItem.Soul to ("灵魂注入" to "孵化 AI 人格与视觉签名"),
                SettingsItem.Memory to ("记忆库" to "长期知识与偏好管理"),
                SettingsItem.Perms to ("工具权限" to "系统权限与 Agent 门禁"),
                SettingsItem.ModelConfig to ("模型服务" to "供应商、分派与采样参数")
            )
            items.forEach { (item, labelDesc) ->
                val (label, desc) = labelDesc
                Row(
                    Modifier.fillMaxWidth()
                        .background(GenTheme.Panel, RoundedCornerShape(12.dp))
                        .clickable { onItem(item) }
                        .padding(horizontal = 16.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (item) {
                            SettingsItem.Soul -> "◎"
                            SettingsItem.Memory -> "✦"
                            SettingsItem.Perms -> "✓"
                            SettingsItem.ModelConfig -> "⚙"
                        },
                        color = GenTheme.AmberDim, fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(30.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, color = GenTheme.Text, fontSize = 15.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(desc, color = GenTheme.Dim, fontSize = 10.sp)
                    }
                    Text("→", color = GenTheme.Amber, fontSize = 14.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
    BackHandler { onBack() }
}
