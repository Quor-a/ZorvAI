package com.ai.assistance.quro.genui.aiapp.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.sdk.components.PetPhase
import com.ai.assistance.quro.genui.sdk.components.PetSpec
import com.ai.assistance.quro.genui.sdk.components.PetSprite
import org.json.JSONArray
import org.json.JSONObject

/**
 * 宠物设置界面：宠物切换（内置+导入）/ JSON 导入导出 / 功能开关
 * 设置持久化到 SharedPreferences("pet_prefs")。
 */
object PetPrefs {
    private val prefs = mutableMapOf<String, Context?>()
    fun load(context: Context): PetSettings {
        val sp = context.getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
        val cur = sp.getString("pet_json", null)
        val list = mutableListOf<PetSpec>()
        runCatching {
            val arr = JSONArray(sp.getString("pet_list_json", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                PetSpec.parseOrNull(arr.getJSONObject(i).toString())?.let { list.add(it) }
            }
        }
        return PetSettings(
            current = cur?.let { PetSpec.parseOrNull(it) } ?: PetSpec(),
            pets = list,
            tapOpensPanel = sp.getBoolean("tap_opens_panel", true),
            tapInteract = sp.getBoolean("tap_interact", false),
            bubbleEnabled = sp.getBoolean("bubble_enabled", true),
            petVisible = sp.getBoolean("pet_visible", true)
        )
    }

    fun save(context: Context, s: PetSettings) {
        val sp = context.getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
        sp.edit()
            .putString("pet_json", s.current.toJson())
            .putString("pet_list_json", JSONArray().apply { s.pets.forEach { put(JSONObject(it.toJson())) } }.toString())
            .putBoolean("tap_opens_panel", s.tapOpensPanel)
            .putBoolean("tap_interact", s.tapInteract)
            .putBoolean("bubble_enabled", s.bubbleEnabled)
            .putBoolean("pet_visible", s.petVisible)
            .apply()
    }
}

data class PetSettings(
    val current: PetSpec,
    val pets: List<PetSpec> = emptyList(),
    val tapOpensPanel: Boolean = true,
    val tapInteract: Boolean = false,
    val bubbleEnabled: Boolean = true,
    val petVisible: Boolean = true
)

/** 内置预置宠物 */
val BUILTIN_PETS = listOf(
    PetSpec(name = "团子", body = listOf(Color(0xFFFFB5C2), Color(0xFFFFD9E0)), eye = Color(0xFF3A2E39), accent = Color(0xFFFF8FA3), form = "blob"),
    PetSpec(name = "墨墨", body = listOf(Color(0xFF9B8CFF), Color(0xFFD9D2FF)), eye = Color(0xFF2A2440), accent = Color(0xFFB5A8FF), form = "cat"),
    PetSpec(name = "波波", body = listOf(Color(0xFF7FD8E8), Color(0xFFD6F4FA)), eye = Color(0xFF1E3D47), accent = Color(0xFF9FE3F0), form = "ghost"),
    PetSpec(name = "柚柚", body = listOf(Color(0xFFFFC66B), Color(0xFFFFE8C2)), eye = Color(0xFF4A3418), accent = Color(0xFFFFB25A), form = "cat")
)

@Composable
fun PetSettingsScreen(
    settings: PetSettings,
    onSettingsChange: (PetSettings) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    // JSON 文件导入
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { u ->
            runCatching {
                ctx.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { text ->
                PetSpec.parseOrNull(text)?.let { spec ->
                    onSettingsChange(settings.copy(current = spec, pets = (settings.pets + spec).distinctBy { it.name }))
                    importError = null
                } ?: run { importError = "JSON 解析失败：字段不完整" }
            } ?: run { importError = "无法读取文件" }
        }
    }

    // JSON 导出（系统分享）
    fun exportPet() {
        val json = settings.current.toJson()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_TITLE, "${settings.current.name}.json")
        }
        ctx.startActivity(Intent.createChooser(intent, "导出宠物 JSON"))
    }

    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 顶栏
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Spacer(Modifier.weight(1f))
                Text("宠物管理", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }

            // 预览
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                PetSprite(settings.current, PetPhase.IDLE, showBubble = false)
            }

            // ── 宠物切换 ──
            Text("我的宠物", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val all = (BUILTIN_PETS + settings.pets)
                items(all.size) { idx ->
                    val p = all[idx]
                    val selected = p.name == settings.current.name
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) cs.primaryContainer else cs.surface)
                            .border(
                                2.dp,
                                if (selected) cs.primary else cs.outline.copy(alpha = 0.3f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { onSettingsChange(settings.copy(current = p)) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .width(96.dp)
                    ) {
                        PetSprite(p, PetPhase.IDLE, showBubble = false, sizeOverride = 56f)
                        Spacer(Modifier.height(4.dp))
                        Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (idx < BUILTIN_PETS.size) "内置" else "导入",
                            fontSize = 10.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── 导入 / 导出 ──
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { importLauncher.launch("*/*") }, modifier = Modifier.weight(1f)) {
                    Text("导入 JSON")
                }
                OutlinedButton(onClick = { exportPet() }, modifier = Modifier.weight(1f)) {
                    Text("导出 JSON")
                }
            }
            importError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = cs.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))

            // ── 功能开关 ──
            Text("功能设置", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            PetSwitch("显示宠物", "关闭后画布上隐藏宠物", settings.petVisible) {
                onSettingsChange(settings.copy(petVisible = it))
            }
            PetSwitch("点击宠物打开侧边栏", "轻点宠物弹出输入侧边栏", settings.tapOpensPanel) {
                onSettingsChange(settings.copy(tapOpensPanel = it))
            }
            PetSwitch("长按宠物与 AI 互动", "长按宠物，AI 会回应你的抚摸", settings.tapInteract) {
                onSettingsChange(settings.copy(tapInteract = it))
            }
            PetSwitch("状态气泡播报", "展示思考/工具/生成状态气泡", settings.bubbleEnabled) {
                onSettingsChange(settings.copy(bubbleEnabled = it))
            }
        }
    }
}

@Composable
private fun PetSwitch(title: String, sub: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
