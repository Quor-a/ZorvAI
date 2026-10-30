package com.ai.assistance.quro.activity

import com.ai.assistance.quro.core.assistant.AssistantSelfCheck.FixAction
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.ai.assistance.quro.core.assistant.AssistantBuiltinSkills
import com.ai.assistance.quro.core.assistant.AssistantChannel
import com.ai.assistance.quro.core.assistant.AssistantLockSkills
import com.ai.assistance.quro.core.assistant.AssistantServiceState
import com.ai.assistance.quro.core.assistant.AssistantShortcuts
import com.ai.assistance.quro.core.assistant.AssistantSkillRegistry
import com.ai.assistance.quro.core.assistant.SpeakScene
import com.ai.assistance.quro.core.assistant.AssistantSpeakPolicy

/**
 * 助手设置。
 *
 * 小布设置页的**逐项一致**（用户把小布那一整页贴了出来，这里一项不少）：
 *
 * | 小布 | 本页 |
 * |---|---|
 * | 小布助手服务（关闭后任何情况下不允许启动） | 「助手服务」总开关 —— 关了 [AssistantServiceState.gate] 在**所有**入口拒绝 |
 * | 语音唤醒 / 连续对话 / 长按电源键 / 线控耳机 / 蓝牙耳机 | 唤醒设置五项，各自独立开关 |
 * | 播报设置：音量与音色 / 播报场景 | 播报总开关 + 音量 + 音色 + 5 个场景 + 静默时段 |
 * | 桌面快捷方式已添加 | 状态展示 + 一键重装 |
 * | 锁屏技能管理 | 勾选锁屏展示的能力 |
 * | 小布通话已开启 | 诚实标注：需系统级通话特权，未支持（不放假开关） |
 * | 首页卡片已开启 | 展示当前小组件/磁贴状态 |
 */
class AssistantSettingsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.ai.assistance.quro.util.QuroLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistantBuiltinSkills.registerAll()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(applicationContext)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(ctx: Context) {
    var serviceOn by remember { mutableStateOf(AssistantServiceState.isServiceEnabled(ctx)) }
    var speakOn by remember { mutableStateOf(AssistantSpeakPolicy.isSpeakEnabled(ctx)) }
    var volume by remember { mutableStateOf(AssistantSpeakPolicy.volumeScale(ctx)) }
    var voice by remember { mutableStateOf(AssistantSpeakPolicy.voiceId(ctx)) }
    var quiet by remember { mutableStateOf(AssistantSpeakPolicy.quietRange(ctx)) }
    var quietAllowError by remember { mutableStateOf(AssistantSpeakPolicy.quietAllowError(ctx)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { Spacer(Modifier.height(14.dp)); Title("助手设置") }

        // ── 〇、真实可用性自检（放最前，它决定下面所有开关能不能真生效）──
        item { SectionHeader("现在能用吗（逐项自检）") }
        item { SelfCheckBlock() }

        // ── 一、助手服务 ─────────────────────────────────────
        item { SectionHeader("助手服务") }
        item {
            SwitchRow(
                title = "ZorvAI 助手服务",
                desc = if (serviceOn) {
                    "关闭后，任何情况下助手都不会启动（外部指令 / 磁贴 / 耳机键 / 分享 全部拒绝）"
                } else {
                    "已关闭：所有入口一律拒绝启动。打开后才会响应任何唤起方式。"
                },
                checked = serviceOn,
                accent = !serviceOn,
                onChange = {
                    serviceOn = it
                    AssistantServiceState.setServiceEnabled(ctx, it)
                },
            )
        }

        // ── 二、唤醒设置 ─────────────────────────────────────
        item { SectionHeader("唤醒设置") }

        // 🔴 这一行放在最前面，因为它是下面所有"被唤起"的总前提：
        // 长按电源键 / 长按耳机键 是**系统**把 intent 投给"默认数字助理应用"。
        // 不设成默认助手，下面那些开关全部打开了也不会被唤起 —— 这正是"有个开关但不工作"的根因。
        item { AssistantRoleRow() }

        item {
            ChannelRow(
                ctx = ctx,
                channel = AssistantChannel.VOICE_WAKEWORD,
                title = "语音唤醒",
                desc = "说出唤醒词直接唤起",
                unsupportedNote = "引擎与唤醒模型已从小布包内搬入，点下方「探测」验证是否可用",
            )
        }
        item { WakeupProbeRow(ctx) }
        item {
            InfoRow(
                title = "连续对话",
                desc = "唤醒后可连续对话，并支持随时打断发起新对话",
                value = "已支持（语音球）",
            )
        }
        item { ChannelRow(ctx, AssistantChannel.ASSIST_KEY, "长按电源键唤醒", "长按电源键 0.5 秒唤起，输入语音或文字指令") }
        item { ChannelRow(ctx, AssistantChannel.HEADSET_WIRED, "线控耳机唤醒", "长按线控耳机播放键唤起（短按仍归音乐）") }
        item { ChannelRow(ctx, AssistantChannel.HEADSET_BT, "蓝牙耳机唤醒", "触碰或长按蓝牙耳机唤起") }
        item { ChannelRow(ctx, AssistantChannel.TILE, "快捷磁贴", "从下拉快捷面板一键唤起") }
        item { ChannelRow(ctx, AssistantChannel.SHORTCUT, "桌面快捷方式", "长按图标直达能力广场 / 新对话") }
        item { ChannelRow(ctx, AssistantChannel.SHARE, "分享与选中文本", "其他应用把文本分享或选中后发进来") }
        item { ChannelRow(ctx, AssistantChannel.DIRECTIVE, "外部指令", "第三方通过广播或深链交一句话进来") }
        item { ChannelRow(ctx, AssistantChannel.RELAY, "后台任务中转", "第三方提交长任务，宿主跑完回执") }
        item { ChannelRow(ctx, AssistantChannel.AI_TOOL, "AI 工具调用", "对话里由模型自己调用内置能力") }

        // ── 三、播报设置 ─────────────────────────────────────
        item { SectionHeader("播报设置") }
        item {
            SwitchRow(
                title = "语音播报",
                desc = "关掉后所有场景都不出声（仍会有界面提示）",
                checked = speakOn,
                onChange = {
                    speakOn = it
                    AssistantSpeakPolicy.setSpeakEnabled(ctx, it)
                },
            )
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音量", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.weight(1f))
                    Text("${(volume * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = it },
                    onValueChangeFinished = { AssistantSpeakPolicy.setVolumeScale(ctx, volume) },
                )
            }
        }
        item {
            InputRow(
                title = "音色",
                desc = "填 TTS 引擎的音色名，留空 = 跟随系统默认",
                value = voice,
                placeholder = "如 zh-cn-x-ccc-local",
                onValueChange = { voice = it },
                onSave = { AssistantSpeakPolicy.setVoiceId(ctx, voice) },
            )
        }
        item {
            InputRow(
                title = "静默时段",
                desc = "到点自动不出声，格式 22:00-07:00；留空 = 不启用",
                value = quiet,
                placeholder = "22:00-07:00",
                onValueChange = { quiet = it },
                onSave = { AssistantSpeakPolicy.setQuietRange(ctx, quiet); quiet = AssistantSpeakPolicy.quietRange(ctx) },
            )
        }
        item {
            SwitchRow(
                title = "静默时段内仍播报错误",
                desc = "出事了要让人知道（默认开）",
                checked = quietAllowError,
                onChange = {
                    quietAllowError = it
                    AssistantSpeakPolicy.setQuietAllowError(ctx, it)
                },
            )
        }
        item { Spacer(Modifier.height(4.dp)); SubHeader("播报场景") }
        items(SpeakScene.entries.size) { idx ->
            val scene = SpeakScene.entries[idx]
            SceneRow(ctx, scene)
        }

        // ── 四、个性化设置 ───────────────────────────────────
        item { SectionHeader("个性化设置") }
        item {
            /*
             * 桌面快捷方式：只做状态展示 + 一键重装，不假装"已添加"。
             * 真实状态以 ShortcutManager 的 pinned/dynamic 列表为准，这里退一步：
             * 我们能保证的是"动态快捷方式已装"，用户是否拖到桌面由桌面决定。
             */
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text("桌面快捷方式", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "长按图标可见「能力广场 / 对话」；若被清掉可重新安装",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                )
                TextButton(onClick = { AssistantShortcuts.install(ctx) }) { Text("重新安装快捷方式") }
            }
        }
        item { LockSkillsBlock(ctx) }
        item {
            InfoRow(
                title = "首页卡片 / 磁贴",
                desc = "桌面小组件与下拉快捷面板磁贴（能力广场、我手机呢、对话、语音球）",
                value = "已提供",
            )
        }
        item {
            InfoRow(
                title = "通话场景",
                desc = "小布「小布通话」（AI 接电话、通话中语音转写）",
                value = "不支持：需系统级通话特权（BIND_CALL_SERVICE / CALL_PRIVILEGED），普通应用拿不到",
                warn = true,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                TextButton(onClick = {
                    ctx.startActivity(
                        android.content.Intent(ctx, AssistantSkillPlazaActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("ZorvAI 技能清单") }
            }
        }

        // ── 五、小布协作（双向）──────────────────────────────
        item { SectionHeader("小布协作") }
        item { BreenoBridgeBlock(ctx) }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

// ── 通用行组件 ──────────────────────────────────────────────

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider(
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
    )
}

@Composable
private fun SubHeader(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    accent: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                color = if (accent) Color(0xFFD9534F) else MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun InfoRow(title: String, desc: String, value: String, warn: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            Text(
                value,
                fontSize = 11.5.sp,
                color = if (warn) Color(0xFFE08700) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(desc, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
    }
}

@Composable
private fun ChannelRow(
    ctx: Context,
    channel: AssistantChannel,
    title: String,
    desc: String,
    unsupportedNote: String = "",
) {
    var on by remember { mutableStateOf(AssistantServiceState.isChannelEnabled(ctx, channel)) }
    Column(Modifier.fillMaxWidth()) {
        SwitchRow(
            title = title,
            desc = if (unsupportedNote.isNotBlank()) "$desc · $unsupportedNote" else desc,
            checked = on,
            onChange = {
                on = it
                AssistantServiceState.setChannelEnabled(ctx, channel, it)
                // 语音唤醒不只是一个开关：开就要真的去听、关就要真的停下。
                // 旧行为只存了个偏好值，根本没有人喂音频——所以这个开关之前是空的。
                if (channel == AssistantChannel.VOICE_WAKEWORD) {
                    toggleWakeupService(ctx, it)
                }
            },
        )
    }
}

/**
 * 启停常驻唤醒监听。
 *
 * 两种失败都必须留痕：权限不够（麦克风）会直接抛异常；
 * 引擎初始化失败会写进 `:wakeup` 进程日志并把结果显示在通知里。
 */
private fun toggleWakeupService(ctx: Context, enable: Boolean) {
    val tag = "assistant-wakeup-toggle"
    if (enable) {
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            com.ai.assistance.quro.util.QuroDiag.log(tag, "未授予麦克风权限，不启动监听")
            return
        }
        val ok = runCatching {
            ctx.startForegroundService(
                android.content.Intent(ctx, com.ai.assistance.quro.service.AssistantWakeupService::class.java)
                    .setAction(com.ai.assistance.quro.service.AssistantWakeupService.ACTION_START)
            )
        }.isSuccess
        com.ai.assistance.quro.util.QuroDiag.log(tag, "start wakeup service ok=" + ok)
    } else {
        runCatching {
            ctx.startService(
                android.content.Intent(ctx, com.ai.assistance.quro.service.AssistantWakeupService::class.java)
                    .setAction(com.ai.assistance.quro.service.AssistantWakeupService.ACTION_STOP)
            )
        }
        com.ai.assistance.quro.util.QuroDiag.log(tag, "stop wakeup service requested")
    }
}

@Composable
private fun SceneRow(ctx: Context, scene: SpeakScene) {
    var on by remember { mutableStateOf(AssistantSpeakPolicy.isSceneOn(ctx, scene)) }
    SwitchRow(
        title = scene.label,
        desc = "播报场景：${scene.wire}",
        checked = on,
        onChange = {
            on = it
            AssistantSpeakPolicy.setSceneOn(ctx, scene, it)
        },
    )
}

@Composable
private fun InputRow(
    title: String,
    desc: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(desc, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f))
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(placeholder, fontSize = 12.sp) },
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSave) { Text("保存") }
        }
    }
}

@Composable
private fun LockSkillsBlock(ctx: Context) {
    var ids by remember { mutableStateOf(AssistantLockSkills.lockScreenIds(ctx)) }
    val candidates = remember { AssistantLockSkills.candidates() }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("锁屏技能管理", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "勾选后展示在锁屏快捷区（小布锁屏技能）；不可执行的能力不会出现在这里",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(6.dp))
        candidates.forEach { s ->
            val checked = ids.contains(s.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.onBackground.copy(alpha = if (checked) 0.06f else 0.02f),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable {
                        AssistantLockSkills.toggle(ctx, s.id, !checked)
                        ids = AssistantLockSkills.lockScreenIds(ctx)
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        s.summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    )
                }
                Text(
                    if (checked) "已选" else "添加",
                    fontSize = 11.5.sp,
                    color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * 语音引擎探测行。
 *
 * 引擎 `.so` 与唤醒模型是从 `Breeno.apk` 字节级搬进来的（8.7MB + 1.4MB），
 * 但**能不能真跑只能靠真机验证**——JNI 绑定错一个 descriptor 就会 UnsatisfiedLinkError，
 * native 内部错就会 SIGSEGV。所以这里给一个显式探测入口，把结果摊开给用户看，
 * 而不是让"语音唤醒"变成一个默默不工作的开关。
 */
@Composable
private fun WakeupProbeRow(ctx: android.content.Context) {
    var probing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<com.ai.assistance.quro.core.assistant.BreenoWakeup.Probe?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = !probing,
                onClick = {
                    probing = true
                    scope.launch(Dispatchers.IO) {
                        val r = com.ai.assistance.quro.core.assistant.BreenoWakeup.probe(ctx)
                        withContext(Dispatchers.Main) {
                            result = r
                            probing = false
                        }
                    }
                },
            ) { Text(if (probing) "探测中…" else "探测语音引擎") }

            result?.let { p ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (p.usable) "可用" else "不可用",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (p.usable) Color(0xFF2E9E6B) else Color(0xFFD9534F),
                )
            }
        }
        result?.let { p ->
            val detail = buildString {
                append(p.level.desc)
                p.version?.let { append("\n" + "版本: ").append(it) }
                p.amplitude?.let { append("\n" + "前端处理返回: ").append(String.format("%.3f", it)) }
                if (p.handle != 0L) append("\n" + "引擎句柄: ").append(p.handle)
                if (p.missingModels.isNotEmpty()) append("\n" + "缺模型: ").append(p.missingModels.joinToString(","))
                p.error?.let { append("\n" + "失败原因: ").append(it) }
            }
            Text(
                text = detail,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                lineHeight = 16.sp,
            )
        }
    }
}

/**
 * 小布协作区：两个方向都要能看见、能操作。
 *
 * 方向一（我们用小布）：打开它的设置 / 技能广场 / 让它找手机——
 * 这些都是它 manifest 里确定的导出入口，成功率高。
 *
 * 方向二（小布用我们）：我们要把自己的能力发布成 App Shortcuts——
 * 因为小布在 dex 里就是用 `ShortcutManager.execShortcutAsync(shortcutId)` 调第三方 App 的。
 */
@Composable
private fun BreenoBridgeBlock(ctx: android.content.Context) {
    var status by remember { mutableStateOf<com.ai.assistance.quro.core.assistant.BreenoBridge.Status?>(null) }
    var lastMsg by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    fun run(label: String, block: () -> com.ai.assistance.quro.core.assistant.AssistantResult) {
        scope.launch(Dispatchers.IO) {
            val r = runCatching(block).getOrElse {
                com.ai.assistance.quro.core.assistant.AssistantResult.internalError("breeno", it)
            }
            withContext(Dispatchers.Main) {
                lastMsg = label + " → " + (if (r.ok) "OK " else "失败 ") + r.code + " " + r.message
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val st = com.ai.assistance.quro.core.assistant.BreenoBridge.status(ctx)
            withContext(Dispatchers.Main) { status = st }
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val st = status
        Text(
            text = when {
                st == null -> "正在探测小布…"
                st.installed -> "已找到小布：" + st.pkg + " " + st.versionName
                else -> "这台机器上没有小布（或未在清单 queries 里声明包名）"
            },
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (st != null && st.installed) {
            Text(
                text = st.resolvable.entries.joinToString("   ") { it.key + "=" + if (it.value) "可用" else "不可用" },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "小布用 ZorvAI：把能力发布成应用快捷方式，小布按 ShortcutManager 就能发现并执行",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
        Row {
            TextButton(onClick = {
                run("发布快捷方式") { com.ai.assistance.quro.core.assistant.BreenoBridge.publishShortcuts(ctx) }
            }) { Text("让小布能用 ZorvAI") }
        }
        Text(
            text = "ZorvAI 用小布：调它的设置 / 技能广场 / 找手机（都是它导出的入口）",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
        Row {
            TextButton(onClick = {
                run("打开小布设置") { com.ai.assistance.quro.core.assistant.BreenoBridge.openSettings(ctx) }
            }) { Text("小布设置") }
            TextButton(onClick = {
                run("技能广场") { com.ai.assistance.quro.core.assistant.BreenoBridge.openSkillPlaza(ctx) }
            }) { Text("技能广场") }
            TextButton(onClick = {
                run("让小布找手机") { com.ai.assistance.quro.core.assistant.BreenoBridge.findPhone(ctx) }
            }) { Text("让它找手机") }
        }
        if (lastMsg.isNotBlank()) {
            Text(
                text = lastMsg,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 「默认数字助理」状态 + 一键申请。
 *
 * ## 为什么这一行决定上面所有开关有没有用
 * 长按电源键（ASSIST）、长按耳机播放键（VOICE_COMMAND）、蓝牙耳机语音键
 * （VOICE_SEARCH_HANDS_FREE）——这几条 intent 是**系统**发给"默认数字助理应用"的。
 * 我们的 [com.ai.assistance.quro.service.AssistantAssistService] 已经把这四个 action
 * 注册齐了，但**系统只投给默认助手**。不设成默认助手，开关全开也不会响 ——
 * 这就是"设置页有开关但唤不醒"的真正原因。
 *
 * 状态每 1.5 秒轮询一次：用户从这里跳到系统弹窗/设置页再返回，状态必须立刻变，
 * 否则用户会以为"点了没反应"。
 */
@Composable
private fun AssistantRoleRow() {
    val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var held by remember { mutableStateOf(false) }
    var canRequest by remember { mutableStateOf(true) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            act?.let {
                held = com.ai.assistance.quro.core.assistant.AssistantRole.isAssistant(it)
                canRequest = com.ai.assistance.quro.core.assistant.AssistantRole.available(it)
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "默认数字助理",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = "长按电源键 / 长按耳机键由系统投递给默认助手；不设为默认助手，下面这几项开关不会生效",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    lineHeight = 16.sp,
                )
            }
            Text(
                text = if (held) "已启用" else "未启用",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (held) Color(0xFF2E9E6B) else Color(0xFFD9534F),
            )
        }
        if (!held) {
            Row {
                TextButton(onClick = {
                    act?.let { a ->
                        if (!com.ai.assistance.quro.core.assistant.AssistantRole.request(a)) {
                            // 申请弹不出来时已经自动跳到系统设置页，这里只落日志
                            com.ai.assistance.quro.util.QuroDiag.log(
                                "assistant-role",
                                "ROLE_ASSISTANT 申请未弹出（canRequest=" + canRequest + "），已跳系统设置",
                            )
                        }
                    }
                }) { Text(if (canRequest) "设为默认数字助理" else "去系统设置选助手") }
            }
        } else {
            Row {
                TextButton(onClick = {
                    act?.let { com.ai.assistance.quro.core.assistant.AssistantRole.openSystemSettings(it) }
                }) { Text("查看系统助手设置") }
            }
        }
    }
}

/**
 * 逐项自检面板 —— 把“为什么用不了”直接摆出来。
 *
 * 每一项三行：状态（可用/需处理/被挡住）→ 实测判据 → 修复入口。
 * 很大一部分“这些功能都是乱写”的感受，实际是**权限 / 默认助手身份没给**，
 * 而不是代码没写；这种情况必须能在界面上自己看出来，不能让用户去猜。
 */
@Composable
private fun SelfCheckBlock() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val act = ctx as? android.app.Activity
    var items by remember {
        mutableStateOf<List<com.ai.assistance.quro.core.assistant.AssistantSelfCheck.Item>>(emptyList())
    }
    var tick by remember { mutableStateOf(0) }

    // 权限弹窗必须在 Activity 上下文里发起
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { tick++ }
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { tick++ }

    androidx.compose.runtime.LaunchedEffect(tick) {
        items = com.ai.assistance.quro.core.assistant.AssistantSelfCheck.run(ctx)
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        items.forEach { item ->
            val lv = item.level
            val color = when (lv) {
                com.ai.assistance.quro.core.assistant.AssistantSelfCheck.Level.OK -> Color(0xFF2E9E6B)
                com.ai.assistance.quro.core.assistant.AssistantSelfCheck.Level.WARN -> Color(0xFFD9A100)
                else -> Color(0xFFD9534F)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = when (lv) {
                        com.ai.assistance.quro.core.assistant.AssistantSelfCheck.Level.OK -> "\u25CF"
                        com.ai.assistance.quro.core.assistant.AssistantSelfCheck.Level.WARN -> "\u25B2"
                        else -> "\u25A0"
                    },
                    fontSize = 11.sp,
                    color = color,
                )
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (item.detail.isNotBlank()) {
                        Text(
                            text = item.detail,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                            lineHeight = 15.sp,
                        )
                    }
                    if (item.fixHint.isNotBlank()) {
                        Text(
                            text = item.fixHint,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            lineHeight = 15.sp,
                        )
                    }
                }
                if (item.fixAction != com.ai.assistance.quro.core.assistant.AssistantSelfCheck.FixAction.NONE) {
                    TextButton(onClick = {
                        handleFix(
                            ctx = ctx,
                            act = act,
                            action = item.fixAction,
                            reqMic = { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) },
                            reqNotif = {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            refresh = { tick++ },
                        )
                    }) { Text("去修", fontSize = 12.sp) }
                }
            }
        }
        Row {
            TextButton(onClick = { tick++ }) { Text("重新自检", fontSize = 12.sp) }
        }
    }
}

/** 自检行上“去修”的分派。 */
private fun handleFix(
    ctx: android.content.Context,
    act: android.app.Activity?,
    action: com.ai.assistance.quro.core.assistant.AssistantSelfCheck.FixAction,
    reqMic: () -> Unit,
    reqNotif: () -> Unit,
    refresh: () -> Unit,
) {
    when (action) {
        FixAction.REQUEST_ASSISTANT_ROLE -> act?.let { com.ai.assistance.quro.core.assistant.AssistantRole.request(it) }
        FixAction.OPEN_ASSISTANT_SETTINGS -> com.ai.assistance.quro.core.assistant.AssistantRole.openSystemSettings(ctx)
        FixAction.REQUEST_MIC -> reqMic()
        FixAction.REQUEST_NOTIFICATION -> reqNotif()
        FixAction.REQUEST_OVERLAY -> runCatching {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + ctx.packageName),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        FixAction.OPEN_QS_PANEL -> runCatching {
            // Android 没有"快捷设置面板"的公共 action（各 ROM 私有），只能把用户带到系统设置首页，
            // 由用户自己进「快捷设置 / 下拉面板编辑」。这里如实做，不假装能一步到位。
            val i = android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
        FixAction.PUBLISH_SHORTCUTS -> kotlinx.coroutines.MainScope().launch {
            com.ai.assistance.quro.core.assistant.BreenoBridge.publishShortcuts(ctx)
            refresh()
        }
        FixAction.NONE -> Unit
    }
}
