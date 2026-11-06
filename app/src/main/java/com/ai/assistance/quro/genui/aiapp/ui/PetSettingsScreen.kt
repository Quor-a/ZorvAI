package com.ai.assistance.quro.genui.aiapp.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pets
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
import kotlinx.coroutines.launch
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
            placementMode = sp.getString("placement_mode", "physics") ?: "physics",
            screenContext = sp.getBoolean("screen_context", false),
            packDir = sp.getString("pet_pack_dir", null),
            tapInteract = sp.getBoolean("tap_interact", false),
            bubbleEnabled = sp.getBoolean("bubble_enabled", true),
            petVisible = sp.getBoolean("pet_visible", true),
            petScale = sp.getFloat("pet_scale", 1.0f),
            actionIntervalSec = sp.getInt("action_interval_sec", 33),
            freePosition = sp.getBoolean("free_position", true),
            thinkMatch = sp.getBoolean("think_match", true),
            interactions = run {
                val out = mutableListOf<PetInteraction>()
                runCatching {
                    val arr = JSONArray(sp.getString("interactions_json", null) ?: DEFAULT_INTERACTIONS_JSON)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        out.add(PetInteraction(o.optString("label", ""), o.optString("action", "actionWave")))
                    }
                }
                if (out.isEmpty()) DEFAULT_INTERACTIONS else out
            },
        )
    }

    fun save(context: Context, s: PetSettings) {
        val sp = context.getSharedPreferences("pet_prefs", Context.MODE_PRIVATE)
        val arr = JSONArray()
        s.interactions.forEach { arr.put(JSONObject().put("label", it.label).put("action", it.action)) }
        sp.edit()
            .putString("pet_json", s.current.toJson())
            .putString("pet_list_json", JSONArray().apply { s.pets.forEach { put(JSONObject(it.toJson())) } }.toString())
            .putString("placement_mode", s.placementMode)
            .putBoolean("screen_context", s.screenContext)
            .putString("pet_pack_dir", s.packDir)
            .putBoolean("tap_opens_panel", s.tapOpensPanel)
            .putBoolean("tap_interact", s.tapInteract)
            .putBoolean("bubble_enabled", s.bubbleEnabled)
            .putBoolean("pet_visible", s.petVisible)
            .putFloat("pet_scale", s.petScale)
            .putInt("action_interval_sec", s.actionIntervalSec)
            .putBoolean("free_position", s.freePosition)
            .putBoolean("think_match", s.thinkMatch)
            .putString("interactions_json", arr.toString())
            .apply()
        // 🔴 设置热更新（2026-11-05）：旧实现
        //    只在服务首次 addPet 时读一次 PetPrefs，改形象/大小/开关必须重启服务才生效。
        //    这里统一广播，所有设置入口（主 app 与 GenUI 应用内）自动覆盖。
        runCatching {
            context.sendBroadcast(
                android.content.Intent("com.ai.assistance.quro.action.DESKTOP_PET_APPLY_SETTINGS")
                    .setPackage(context.packageName),
            )
        }
    }
}

/**
 * 互动动作：一个触发词 + 一个动画类型。
 * 参考 ChatGPT 桌宠的口径——轻点宠物时按当前消息内容匹配触发词，
 * 播放对应动作（actionWave 挥手 / actionBounce 弹跳 / actionChat 聊天 / think 思考）。
 */
data class PetInteraction(val label: String, val action: String) {
    companion object {
        val ACTIONS = listOf("actionWave", "actionBounce", "actionChat", "think")
    }
}

/**
 * 🔴 AI 产出的动作收敛到 PetSprite 实际支持的 4 种（2026-11-05）：
 * AI 写的宠物动作若不在 actionWave/actionBounce/actionChat/think 里，
 * 旧实现会静默不播（宠物不支持）——现在强制收敛并保留 label。
 */

fun List<PetInteraction>.clampedToSupported(): List<PetInteraction> =
    map { it.copy(action = if (it.action in PetInteraction.ACTIONS) it.action else "actionWave") }
        .filter { it.label.isNotBlank() }
        .take(4)


/** 默认互动动作：聊天/安装/讲笑话/冷知识/等等我/催我/许愿。 */
val DEFAULT_INTERACTIONS = listOf(
    PetInteraction("聊天", "actionWave"),
    PetInteraction("安装", "actionWave"),
    PetInteraction("讲笑话", "actionBounce"),
    PetInteraction("冷知识", "think"),
    PetInteraction("等等我", "actionBounce"),
    PetInteraction("催我", "actionChat"),
    PetInteraction("许愿", "think"),
)
private val DEFAULT_INTERACTIONS_JSON = JSONArray().apply {
    DEFAULT_INTERACTIONS.forEach { put(JSONObject().put("label", it.label).put("action", it.action)) }
}.toString()

data class PetSettings(
    val current: PetSpec,
    val pets: List<PetSpec> = emptyList(),
    val tapOpensPanel: Boolean = true,
    /** 🔴 位置模式（对齐 BotHub）：physics 物理活动 / freeroam 自由活动 / pinned 固定当前位置 */
    val placementMode: String = "physics",
    /** 点击桌宠时读取当前屏幕作为回合上下文 */
    val screenContext: Boolean = false,
    /** 选中的宠物包目录名（desktop_pets/<dir>）；null = 无包（Canvas 矢量形象） */
    val packDir: String? = null,
    val tapInteract: Boolean = false,
    val bubbleEnabled: Boolean = true,
    val petVisible: Boolean = true,
    /** 宠物大小倍率（0.5..2.0，1.0 = 100%）。 */
    val petScale: Float = 1.0f,
    /** 空闲动作间隔（秒）：每隔这么久随机播一个互动动作，让宠物「活着」。 */
    val actionIntervalSec: Int = 33,
    /** 位置模式：true=自由（可拖到任意处）；false=吸附屏幕右缘。 */
    val freePosition: Boolean = true,
    /** 显示思考匹配：AI 思考时宠物同步播放 think 动画。 */
    val thinkMatch: Boolean = true,
    /** 互动动作列表（触发词 → 动画）。 */
    val interactions: List<PetInteraction> = DEFAULT_INTERACTIONS,
)

/** 内置预置宠物 */
val BUILTIN_PETS = listOf(
    PetSpec(name = qstr(R.string.qk_03524), body = listOf(Color(0xFFFFB5C2), Color(0xFFFFD9E0)), eye = Color(0xFF3A2E39), accent = Color(0xFFFF8FA3), form = "blob"),
    PetSpec(name = qstr(R.string.qk_03477), body = listOf(Color(0xFF9B8CFF), Color(0xFFD9D2FF)), eye = Color(0xFF2A2440), accent = Color(0xFFB5A8FF), form = "cat"),
    PetSpec(name = qstr(R.string.qk_03494), body = listOf(Color(0xFF7FD8E8), Color(0xFFD6F4FA)), eye = Color(0xFF1E3D47), accent = Color(0xFF9FE3F0), form = "ghost"),
    PetSpec(name = qstr(R.string.qk_03568), body = listOf(Color(0xFFFFC66B), Color(0xFFFFE8C2)), eye = Color(0xFF4A3418), accent = Color(0xFFFFB25A), form = "cat")
)

@Composable
fun PetSettingsScreen(
    settings: PetSettings,
    onSettingsChange: (PetSettings) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    // AI 直接生成宠物：描述 → LLM → PetSpec
    var showAiGen by remember { mutableStateOf(false) }
    var aiPrompt by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }

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
                } ?: run { importError = qstr(R.string.qk_03807) }
            } ?: run { importError = qstr(R.string.qk_03808) }
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
        ctx.startActivity(Intent.createChooser(intent, qstr(R.string.qk_03809)))
    }

    Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 顶栏
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.qk_03416)) }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.qk_03713), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(64.dp))
            }

            // 预览
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                PetSprite(settings.current, PetPhase.IDLE, showBubble = false)
            }

            // ── 宠物切换 ──
            Text(stringResource(R.string.qk_03432), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
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
                            if (idx < BUILTIN_PETS.size) stringResource(R.string.qk_03623) else stringResource(R.string.qk_01635),
                            fontSize = 10.sp,
                            color = cs.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ── AI 生成 / 导入 / 导出 ──
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { showAiGen = true; aiError = null }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_04737), maxLines = 1)
                }
                OutlinedButton(onClick = { importLauncher.launch("*/*") }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_03635), maxLines = 1)
                }
                OutlinedButton(onClick = { exportPet() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.qk_03517), maxLines = 1)
                }
            }
            importError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = cs.error, fontSize = 12.sp)
            }
            aiError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = cs.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))

            // ── 功能开关 ──
            Text(stringResource(R.string.qk_03545), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            PetSwitch(stringResource(R.string.qk_03810), stringResource(R.string.qk_03811), settings.petVisible) {
                onSettingsChange(settings.copy(petVisible = it))
            }
            PetSwitch(stringResource(R.string.qk_03812), stringResource(R.string.qk_03813), settings.tapOpensPanel) {
                onSettingsChange(settings.copy(tapOpensPanel = it))
            }
            PetSwitch(stringResource(R.string.qk_03814), stringResource(R.string.qk_03815), settings.tapInteract) {
                onSettingsChange(settings.copy(tapInteract = it))
            }
            PetSwitch(stringResource(R.string.qk_03816), stringResource(R.string.qk_03817), settings.bubbleEnabled) {
                onSettingsChange(settings.copy(bubbleEnabled = it))
            }
            PetSwitch("点击时参考屏幕", "点击桌宠触发 AI 互动时读取当前屏幕作为上下文", settings.screenContext) {
                onSettingsChange(settings.copy(screenContext = it))
            }
            Spacer(Modifier.height(18.dp))

            // ── 位置模式（对齐 BotHub：物理活动 / 自由活动 / 固定当前位置） ──
            Text("位置模式", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "physics" to "物理活动",
                    "freeroam" to "自由活动",
                    "pinned" to "固定当前位置",
                ).forEach { (id, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = settings.placementMode == id,
                        onClick = { onSettingsChange(settings.copy(placementMode = id)) },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
            Text(
                when (settings.placementMode) {
                    "physics" -> "受重力、拖拽、投掷和边缘活动影响。"
                    "freeroam" -> "在屏幕可用区域内自由移动，不会自动下坠。"
                    else -> "停在你拖到的位置，只在事件反应时播放动作。"
                },
                fontSize = 11.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(18.dp))

            // ── 宠物包：选形象（spritesheet 帧动画包，zip 导入） ──
            Text("宠物包（形象）", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            var packs by remember { mutableStateOf(com.ai.assistance.quro.core.pet.PetPack.scan(ctx).first) }
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    importing = true
                    scope.launch {
                        runCatching {
                            // 从 Download 目录找最新的 petpack zip（用户放进来的）
                            val dl = java.io.File(
                                androidx.core.content.ContextCompat.getExternalFilesDirs(ctx, null)[0],
                                "Download"
                            )
                            val zip = dl.listFiles { f -> f.name.endsWith(".zip") }
                                ?.maxByOrNull { it.lastModified() }
                            if (zip == null) importError = "未找到宠物包 zip（请放到应用目录 Download 下）"
                            else {
                                com.ai.assistance.quro.core.pet.PetPack.importZip(ctx, zip.inputStream())
                                packs = com.ai.assistance.quro.core.pet.PetPack.scan(ctx).first
                                importError = null
                            }
                        }.onFailure { importError = it.message }
                        importing = false
                    }
                },
                enabled = !importing,
            ) { Text(if (importing) "导入中…" else "导入宠物包 zip") }
            importError?.let {
                Text(it, color = cs.error, fontSize = 12.sp)
            }
            if (packs.isEmpty()) {
                Text(
                    "还没有宠物包。导入 zip 后在这里选形象；无包时使用内置矢量形象。",
                    fontSize = 11.sp, color = cs.onSurfaceVariant,
                )
            } else {
                packs.forEach { pk ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        androidx.compose.material3.FilterChip(
                            selected = settings.packDir == pk.dirName,
                            onClick = { onSettingsChange(settings.copy(packDir = pk.dirName)) },
                            label = { Text(pk.manifest.displayName) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            pk.manifest.id + "（" + pk.manifest.animations.size + " 动画）",
                            fontSize = 11.sp, color = cs.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            com.ai.assistance.quro.core.pet.PetPack.delete(ctx, pk.dirName)
                            packs = com.ai.assistance.quro.core.pet.PetPack.scan(ctx).first
                            if (settings.packDir == pk.dirName) onSettingsChange(settings.copy(packDir = null))
                        }) {
                            Icon(Icons.Filled.Delete, "删除", Modifier.size(16.dp), tint = cs.error)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            // ── 互动动作：触发词 → 动画；轻点宠物或空闲到点时随机播放 ──
            Text(stringResource(R.string.qk_04720), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            settings.interactions.forEachIndexed { idx, act ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Icon(Icons.Filled.Pets, null, Modifier.size(16.dp), tint = cs.primary.copy(alpha = 0.7f))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(act.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(act.action, fontSize = 11.sp, color = cs.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        onSettingsChange(settings.copy(interactions = settings.interactions.filterIndexed { i, _ -> i != idx }))
                    }) { Text(stringResource(R.string.qk_04722), fontSize = 12.sp, color = cs.error) }
                }
            }
            var showAddAction by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { showAddAction = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.qk_04721))
            }
            if (showAddAction) {
                var label by remember { mutableStateOf("") }
                var action by remember { mutableStateOf(PetInteraction.ACTIONS.first()) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showAddAction = false },
                    title = { Text(stringResource(R.string.qk_04721)) },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = label, onValueChange = { label = it },
                                label = { Text(stringResource(R.string.qk_04723)) },
                                singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(stringResource(R.string.qk_04724), fontSize = 12.sp, color = cs.onSurfaceVariant)
                            PetInteraction.ACTIONS.forEach { a ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { action = a }
                                        .padding(vertical = 4.dp),
                                ) {
                                    androidx.compose.material3.RadioButton(selected = action == a, onClick = { action = a })
                                    Text(a, fontSize = 13.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (label.isNotBlank()) {
                                onSettingsChange(settings.copy(interactions = settings.interactions + PetInteraction(label.trim(), action)))
                            }
                            showAddAction = false
                        }) { Text(stringResource(R.string.qk_04733)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddAction = false }) { Text(stringResource(R.string.qk_04734)) }
                    },
                )
            }
            Spacer(Modifier.height(18.dp))

            // ── 行为：位置模式 + 思考匹配 ──
            Text(stringResource(R.string.qk_04725), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            PetSwitch(stringResource(R.string.qk_04726), stringResource(R.string.qk_04727), settings.freePosition) {
                onSettingsChange(settings.copy(freePosition = it))
            }
            PetSwitch(stringResource(R.string.qk_04728), stringResource(R.string.qk_04729), settings.thinkMatch) {
                onSettingsChange(settings.copy(thinkMatch = it))
            }
            Spacer(Modifier.height(14.dp))

            // ── 宠物大小 / 动作间隔 ──
            Text(stringResource(R.string.qk_04730), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.qk_04731, Math.round(settings.petScale * 100)),
                fontSize = 12.sp, color = cs.onSurfaceVariant,
            )
            Slider(
                value = settings.petScale,
                valueRange = 0.5f..2.0f,
                onValueChange = { onSettingsChange(settings.copy(petScale = Math.round(it * 20) / 20.0f)) },
            )
            Text(
                stringResource(R.string.qk_04732, settings.actionIntervalSec),
                fontSize = 12.sp, color = cs.onSurfaceVariant,
            )
            Slider(
                value = settings.actionIntervalSec.toFloat(),
                valueRange = 10f..120f,
                onValueChange = { onSettingsChange(settings.copy(actionIntervalSec = (it / 5).toInt() * 5)) },
            )
        }

        // ── AI 生成宠物对话框：描述 → LLM 产出 PetSpec JSON → 直接应用并入库 ──
        if (showAiGen) {
            AlertDialog(
                onDismissRequest = { if (!aiBusy) showAiGen = false },
                title = { Text(stringResource(R.string.qk_04737), fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(stringResource(R.string.qk_04738), fontSize = 12.sp, color = cs.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = aiPrompt,
                            onValueChange = { aiPrompt = it },
                            placeholder = { Text(stringResource(R.string.qk_04738), fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            enabled = !aiBusy,
                        )
                        if (aiBusy) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.qk_04739), fontSize = 12.sp, color = cs.onSurfaceVariant)
                            }
                        }
                        aiError?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, fontSize = 12.sp, color = cs.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !aiBusy && aiPrompt.isNotBlank(),
                        onClick = {
                            aiBusy = true
                            aiError = null
                            scope.launch {
                                val outcome: String? = runCatching {
                                    val cfg = com.ai.assistance.quro.core.model.QuroModelConfigRepository(ctx).load()
                                    if (cfg.apiKey.isBlank()) error(qstr(R.string.qk_04740))
                                    val sys = "你是宠物形象设计师。根据用户描述输出一个 JSON 宠物配置对象。" +
                                        "字段：name(中文短名)、body(两个十六进制颜色组成的数组，如 [\"#FFB5C2\",\"#FFD9E0\"])、" +
                                        "eye(十六进制颜色)、accent(十六进制颜色)、form(只能是 blob/cat/ghost 之一)、size(60-140 的整数)、" +
                                        "interactions(互动动作数组，每项 {\"label\":\"触发词\",\"action\":\"动作\"}，2-4 项；" +
                                        "动作只能是 actionWave/actionBounce/actionChat/think 之一，与描述的性格匹配)。" +
                                        "配色要与描述匹配且和谐。只输出 JSON，不要任何解释、代码块或多余文字。"
                                    val res = com.ai.assistance.quro.core.network.QuroLlmClient().chat(
                                        baseUrl = cfg.baseUrl, apiKey = cfg.apiKey, model = cfg.model,
                                        messages = listOf(
                                            com.ai.assistance.quro.core.QuroChatMessage(role = "system", content = sys),
                                            com.ai.assistance.quro.core.QuroChatMessage(role = "user", content = aiPrompt),
                                        ),
                                        temperature = 0.8f, maxTokens = 600,
                                    )
                                    when (res) {
                                        is com.ai.assistance.quro.core.QuroLlmResult.Text -> res.content
                                        is com.ai.assistance.quro.core.QuroLlmResult.ToolCalls -> res.content ?: ""
                                        is com.ai.assistance.quro.core.QuroLlmResult.Error -> error(res.message)
                                    }
                                }.getOrNull()
                                aiBusy = false
                                val raw: String = outcome ?: ""
                                // 容错：剥掉代码围栏 / 前后杂文，取首个 { 到末个 }
                                val sIdx = raw.indexOf('{')
                                val eIdx = raw.lastIndexOf('}')
                                val spec = if (sIdx >= 0 && eIdx > sIdx) PetSpec.parseOrNull(raw.substring(sIdx, eIdx + 1)) else null
                                // 🔴 AI 写宠物动作（2026-11-05）：AI 产出的互动动作一并解析，收敛到支持集
                                val aiInteractions = if (sIdx >= 0 && eIdx > sIdx) {
                                    runCatching {
                                        val o = JSONObject(raw.substring(sIdx, eIdx + 1))
                                        val arr = o.optJSONArray("interactions")
                                        (0 until (arr?.length() ?: 0)).mapNotNull {
                                            val it2 = arr?.optJSONObject(it) ?: return@mapNotNull null
                                            PetInteraction(it2.optString("label", ""), it2.optString("action", "actionWave"))
                                        }
                                    }.getOrDefault(emptyList())
                                } else emptyList()
                                if (spec != null) {
                                    onSettingsChange(settings.copy(
                                        current = spec,
                                        pets = (settings.pets + spec).distinctBy { it.name },
                                        // 🔴 AI 写宠物动作接线（2026-11-05）：收敛到支持的 4 种动画
                                        interactions = aiInteractions.clampedToSupported().ifEmpty { settings.interactions },
                                    ))
                                    showAiGen = false
                                    aiPrompt = ""
                                } else {
                                    aiError = qstr(R.string.qk_04740)
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.qk_04741)) }
                },
                dismissButton = {
                    TextButton(enabled = !aiBusy, onClick = { showAiGen = false }) { Text(stringResource(R.string.qk_04734)) }
                },
            )
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