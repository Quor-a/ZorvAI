package com.ai.assistance.quro.genui.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.agent.Soul
import com.ai.assistance.quro.genui.app.agent.SoulStore
import com.ai.assistance.quro.genui.app.llm.LLMClient
import com.ai.assistance.quro.genui.app.llm.Prompts
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 灵魂屏 —— AI 自动人格孵化（参考 ZorvAI 的 QuroSoulUi 人格/灵魂配置）。
 * 人格卡由模型自己撰写：名字、语气、特质、原则、开场白；可反复重新孵化。
 */
@Composable
fun SoulScreen(store: GenStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val soulStore = remember { SoulStore(store.context()) }
    var soul by remember { mutableStateOf(soulStore.load() ?: soulStore.fallback) }
    var incubating by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    BackHandler { onBack() }

    fun incubate() {
        if (incubating) return
        val provider = currentMainProvider(store) ?: run {
            msg = "先在「模型服务」配置一个供应商"; return
        }
        scope.launch {
            incubating = true; msg = null
            try {
                val llm = LLMClient()
                val resp = llm.chatOnce(
                    provider,
                    org.json.JSONArray().put(
                        org.json.JSONObject().put("role", "user").put("content", Prompts.INCUBATE)
                    ),
                    null
                )
                var raw = resp.optString("content").trim()
                // 剥掉可能的 markdown 包裹
                raw = raw.replace(Regex("(?s)^```(json)?"), "").replace(Regex("```$"), "").trim()
                val start = raw.indexOf('{'); val end = raw.lastIndexOf('}')
                require(start >= 0 && end > start) { "模型未返回 JSON：${raw.take(80)}" }
                val o = JSONObject(raw.substring(start, end + 1))
                val s = Soul(
                    name = o.optString("name", "无名列者"),
                    tone = o.optString("tone", ""),
                    traits = o.optJSONArray("traits")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList(),
                    principles = o.optJSONArray("principles")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList(),
                    taboos = o.optJSONArray("taboos")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } } ?: emptyList(),
                    mission = o.optString("mission", ""),
                    style = o.optString("style", ""),
                    sample = o.optString("sample", ""),
                    greeting = o.optString("greeting", "")
                )
                soulStore.save(s)
                soul = s
                msg = "孵化完成：${s.name}"
            } catch (e: Exception) {
                msg = "孵化失败：${e.message}"
            }
            incubating = false
        }
    }

    Scaffold(
        containerColor = GenTheme.Screen,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().background(GenTheme.Screen).padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回", color = GenTheme.Amber, fontSize = 13.sp) }
                Spacer(Modifier.weight(1f))
                Text("灵魂", color = GenTheme.Text, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(72.dp))
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "人格卡由模型自己孵化并持续保持一致；重新孵化 = 让它重写自己。",
                color = GenTheme.Dim, fontSize = 11.sp, lineHeight = 16.sp
            )
            Spacer(Modifier.height(14.dp))

            CardShape {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(GenTheme.Amber, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Text(soul.name.take(1), color = GenTheme.Screen, fontSize = 20.sp, fontFamily = FontFamily.Serif)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(soul.name, color = GenTheme.Text, fontSize = 17.sp, fontFamily = FontFamily.Serif)
                        Text(soul.tone, color = GenTheme.Dim, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (soul.mission.isNotBlank()) {
                    Text("使命", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(soul.mission, color = GenTheme.Text, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                if (soul.traits.isNotEmpty()) {
                    Text("特质", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text(soul.traits.joinToString(" · "), color = GenTheme.Text, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                if (soul.principles.isNotEmpty()) {
                    Text("原则", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    soul.principles.forEach { Text("· $it", color = GenTheme.Text, fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                }
                if (soul.taboos.isNotEmpty()) {
                    Text("禁忌 · 绝不做", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    soul.taboos.forEach { Text("✗ $it", color = GenTheme.Red, fontSize = 12.sp) }
                    Spacer(Modifier.height(8.dp))
                }
                if (soul.sample.isNotBlank()) {
                    Text("语气样例", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("「${soul.sample}」", color = GenTheme.Text, fontSize = 12.sp, fontFamily = FontFamily.Serif)
                    Spacer(Modifier.height(8.dp))
                }
                if (soul.greeting.isNotBlank()) {
                    Text("开场白", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Text("「${soul.greeting}」", color = GenTheme.Amber, fontSize = 12.sp, fontFamily = FontFamily.Serif)
                }
            }

            // —— 全字段手动编辑（对齐 ZorvAI 人格配置）：改完保存即生效 ——
            Spacer(Modifier.height(14.dp))
            var draftName by remember(soul.name) { mutableStateOf(soul.name) }
            var draftTone by remember(soul.name, soul.tone) { mutableStateOf(soul.tone) }
            var draftMission by remember(soul.name, soul.mission) { mutableStateOf(soul.mission) }
            var draftTraits by remember(soul.name, soul.traits) { mutableStateOf(soul.traits.joinToString("，")) }
            var draftPrinciples by remember(soul.name, soul.principles) { mutableStateOf(soul.principles.joinToString("；")) }
            var draftTaboos by remember(soul.name, soul.taboos) { mutableStateOf(soul.taboos.joinToString("；")) }
            var draftSample by remember(soul.name, soul.sample) { mutableStateOf(soul.sample) }
            var draftGreeting by remember(soul.name, soul.greeting) { mutableStateOf(soul.greeting) }

            CardShape {
                Text("手动微调 · 全字段（保存后下一次生成生效）", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(10.dp))
                SoulField("名字", draftName) { draftName = it }
                SoulField("语气", draftTone) { draftTone = it }
                SoulField("使命", draftMission) { draftMission = it }
                SoulField("特质（逗号分隔）", draftTraits) { draftTraits = it }
                SoulField("原则（分号分隔）", draftPrinciples) { draftPrinciples = it }
                SoulField("禁忌（分号分隔）", draftTaboos) { draftTaboos = it }
                SoulField("语气样例", draftSample) { draftSample = it }
                SoulField("开场白", draftGreeting) { draftGreeting = it }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    val snapshot = soul
                    val s2 = snapshot.copy(
                        name = draftName.ifBlank { snapshot.name },
                        tone = draftTone, mission = draftMission, sample = draftSample, greeting = draftGreeting,
                        traits = draftTraits.split("，", ",").map { it.trim() }.filter { it.isNotBlank() },
                        principles = draftPrinciples.split("；", ";").map { it.trim() }.filter { it.isNotBlank() },
                        taboos = draftTaboos.split("；", ";").map { it.trim() }.filter { it.isNotBlank() }
                    )
                    soulStore.save(s2); soul = s2
                    msg = "人格卡已保存"
                }) { Text("保存人格卡", color = GenTheme.Amber, fontSize = 12.sp) }
            }

            // —— 视觉签名：手动编辑（直接影响 AI 写出的界面美学） ——
            Spacer(Modifier.height(14.dp))
            var styleEdit by remember(soul.name, soul.style) { mutableStateOf(soul.style) }
            CardShape {
                Text("视觉签名 · 注入每一次界面生成", color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Text("色彩倾向、版式气质、装饰母题……改完保存，下一次生成即生效。", color = GenTheme.Dim, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = styleEdit,
                    onValueChange = { styleEdit = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = GenTheme.Text, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 17.sp
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(GenTheme.Amber),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)
                        .background(GenTheme.PanelUp, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 9.dp)
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    val snapshot = styleEdit
                    val s2 = soul.copy(style = snapshot)
                    soulStore.save(s2); soul = s2
                    msg = "视觉签名已保存"
                }) { Text("保存视觉签名", color = GenTheme.Amber, fontSize = 12.sp) }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { incubate() },
                enabled = !incubating,
                colors = ButtonDefaults.buttonColors(containerColor = GenTheme.Amber, contentColor = GenTheme.Screen),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Text(if (soulStore.exists()) "重新孵化（模型重写自己）" else "孵化灵魂", fontSize = 13.sp)
            }
            msg?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = if (it.startsWith("孵化完成")) GenTheme.Green else GenTheme.Red, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun CardShape(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(GenTheme.Panel, RoundedCornerShape(14.dp)).padding(16.dp),
        content = content
    )
}

/** 灵魂文件目录（与 GenStore 同一私有目录） */
fun storeJsonDir(store: GenStore): java.io.File =
    java.io.File(store.context().filesDir, "gen").apply { mkdirs() }

@Composable
fun SoulField(label: String, value: String, onChange: (String) -> Unit) {
    Column {
        Text(label, color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        BasicTextField(
            value = value, onValueChange = onChange,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = GenTheme.Text, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace, lineHeight = 16.sp
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(GenTheme.Amber),
            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp)
                .background(GenTheme.PanelUp, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        )
        Spacer(Modifier.height(7.dp))
    }
}

/** 主脑供应商（与主屏 currentProvider 一致：专项分派优先，否则第一个启用的） */
internal fun currentMainProvider(store: GenStore): com.ai.assistance.quro.genui.app.store.ModelProvider? {
    val routing = store.loadRouting()
    val all = store.loadProviders().filter { it.enabled }
    return all.find { it.id == routing.mainProviderId } ?: all.firstOrNull()
}
