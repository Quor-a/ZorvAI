package com.ai.assistance.quro.activity

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.assistant.AssistantCategory
import com.ai.assistance.quro.core.assistant.AssistantRequest
import com.ai.assistance.quro.core.assistant.AssistantResult
import com.ai.assistance.quro.core.assistant.AssistantSkill
import com.ai.assistance.quro.core.assistant.AssistantSkillRegistry
import com.ai.assistance.quro.core.assistant.AssistantSource
import kotlinx.coroutines.launch

/**
 * 技能广场。
 *
 * 对标小布的四个组件：`SkillPlazaActivity`（首页）+ `SearchActivity`（搜索）+
 * `MySkillsActivity`（我的/启停）+ `SkillDetailActivity`（详情）。
 *
 * 为什么要它：ZorvAI 已有 171 个工具和十几项助手能力，但**用户看不见**——只能靠 AI 自己想起来调。
 * 小布把"我会什么"做成了一等公民界面（技能广场是它的一级入口），这是可发现性的差距，
 * 不是功能差距。本页把 [AssistantSkillRegistry] 渲染成可搜、可筛、可跑、可停的列表。
 */
class AssistantSkillPlazaActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.ai.assistance.quro.util.QuroLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 幂等：直接从外部拉起本页时，内置能力必须已在表里
        com.ai.assistance.quro.core.assistant.AssistantBuiltinSkills.registerAll()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlazaScreen()
                }
            }
        }
    }
}

@Composable
private fun PlazaScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<AssistantCategory?>(null) }
    var last by remember { mutableStateOf<AssistantResult?>(null) }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // revision 参与 key：启停开关变化后列表要重排/重读状态
    val skills: List<AssistantSkill> = remember(query, category, revision) {
        AssistantSkillRegistry.search(query, category)
    }
    val total = remember(revision) { AssistantSkillRegistry.all().size }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "能力广场",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "共 $total 项能力 · 搜索、点一下就执行",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            // 助手设置就在这里：能力表和它的开关应该挨着，用户不用回工具箱找
            TextButton(onClick = {
                ctx.startActivity(
                    android.content.Intent(ctx, com.ai.assistance.quro.activity.AssistantSettingsActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("设置", fontSize = 13.sp) }
        }
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("搜能力：找手机 / 终端 / 诊断…", fontSize = 13.sp) },
        )

        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                CategoryPill("全部", category == null) { category = null }
            }
            items(AssistantCategory.entries.toList()) { c ->
                CategoryPill(c.label, category == c) { category = if (category == c) null else c }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(skills, key = { it.id }) { s ->
                SkillRow(
                    skill = s,
                    onRun = {
                        scope.launch {
                            // 广场是应用内自己点，不属于任何唤醒通道，但服务总开关照样管住
                            val denied = com.ai.assistance.quro.core.assistant.AssistantServiceState
                                .gateServiceOnly(ctx)
                            last = denied ?: AssistantSkillRegistry.run(
                                s.id,
                                AssistantRequest(
                                    text = if (query.isNotBlank()) query else s.title,
                                    source = AssistantSource.PLAZA,
                                ),
                            )
                        }
                    },
                    onToggle = { on ->
                        AssistantSkillRegistry.setEnabled(s.id, on)
                        revision++
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f))
            }
            if (skills.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("没有匹配的能力", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
                    }
                }
            }
        }

        last?.let { r ->
            ResultBar(r)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CategoryPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.06f)
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, fontSize = 12.sp, color = fg)
    }
}

@Composable
private fun SkillRow(skill: AssistantSkill, onRun: () -> Unit, onToggle: (Boolean) -> Unit) {
    val enabled = remember(skill.id) { AssistantSkillRegistry.isEnabled(skill.id) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).clickable(onClick = onRun)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = skill.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = skill.category.label,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                )
                if (skill.requirement.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text("需配置", fontSize = 10.sp, color = Color(0xFFE08700))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = skill.summary,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            if (skill.keywords.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "触发：" + skill.keywords.take(4).joinToString(" "),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = enabled, onCheckedChange = onToggle, modifier = Modifier.size(width = 42.dp, height = 26.dp))
    }
}

@Composable
private fun ResultBar(r: AssistantResult) {
    val okColor = Color(0xFF2E9E6B)
    val badColor = Color(0xFFD9534F)
    Surface(
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.05f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = (if (r.ok) "✓ " else "✗ ") + r.code,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (r.ok) okColor else badColor,
            )
            if (r.message.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(r.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f))
            }
            if (r.data.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = r.data.entries.joinToString("\n") { "${it.key} = ${it.value}" }.take(600),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
            }
        }
    }
}
