package com.ai.assistance.quro.ui

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.dshmobile.NotifyCenter
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Sage
import com.ai.assistance.quro.util.qstr
import org.json.JSONObject

/** 屏内回执消息的语气（决定着色），不靠猜文本前缀。 */
private enum class NotifyMessageTone { Info, Ok, Error }

/**
 * Harness 通知设置屏（#429）：通知从 harness WebUI 迁入 ZorvAI 设置。
 *
 * 全部语义走 dshmobile 壳侧公开单例 [NotifyCenter]（`settingsSnapshot` 读 /
 * `applySetting` 写 / `selfCheck` 自检 / `sendTestAll` 测试 / 系统深链），
 * 与 harness WebUI 的 `notify-settings.tsx` 同一真源，不另存一份设置。
 * 写后一律重新读快照（拒绝乐观置位）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarnessNotifyScreen(
    onClose: () -> Unit,
    scaled: (Int) -> TextUnit = { it.sp },
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dangerColor = Color(android.graphics.Color.parseColor("#C0432F"))
    // 总开关与 ZorvAI 回复通知共用同一个键（quro_ui / ai_reply_notify）——同一个开关，
    // 不是一个新开关。关掉后 ZorvAI 的回复通知与下方 harness 提醒一起静音。
    val uiPrefs = remember {
        context.getSharedPreferences("quro_ui", android.content.Context.MODE_PRIVATE)
    }
    var masterOn by remember { mutableStateOf(uiPrefs.getBoolean("ai_reply_notify", true)) }

    // 快照：null = 尚未读到（加载中/失败）。不预填默认值——展示值只认壳侧真源。
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var selfCheck by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageTone by remember { mutableStateOf(NotifyMessageTone.Info) }
    var permissionOk by remember { mutableStateOf(true) }

    fun reload() {
        snapshot = NotifyCenter.settingsSnapshot(context)
        permissionOk = notifyPermissionOk(context)
    }

    DisposableEffect(Unit) {
        reload()
        onDispose { }
    }

    fun write(key: String, value: Boolean) {
        val reply = NotifyCenter.applySetting(context, key, value)
        val applied = reply.optBoolean("applied", false)
        snapshot = reply
        if (!applied) {
            message = qstr(R.string.qk_04009) // 写入未生效，请重试
            messageTone = NotifyMessageTone.Error
        } else {
            message = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        // #473：本页已合并为**唯一的通知设置页**（总开关 + 提醒方式 + 系统权限），
                        // 标题不再只叫「Harness 通知」——那会让用户以为还有另一套通知系统。
                        stringResource(R.string.qk_01945),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                stringResource(R.string.qk_04133),
                fontSize = scaled(13), color = Muted,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            InfoBox(stringResource(R.string.qk_04134))
            Spacer(Modifier.height(12.dp))

            if (!permissionOk) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(dangerColor.copy(alpha = 0.08f))
                        .border(1.dp, dangerColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        stringResource(R.string.qk_04158),
                        fontSize = scaled(12), color = dangerColor, lineHeight = scaled(18),
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            val snap = snapshot
            if (snap == null) {
                Text(qstr(R.string.qk_04300), fontSize = scaled(12), color = Muted)
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            // ── 总开关（#473 合并）──────────────────────────────────────────────
            // 旧形态：设置页里「AI 回复通知」是一个开关行，「Harness 通知」是另一行、另开一页，
            // 同一件事两个入口（用户报「没有合到一起」）。现在**本页就是唯一的通知设置页**，
            // 第一行是总开关，读写的就是 app 侧同一个键（quro_ui / ai_reply_notify）：
            // 关掉它，ZorvAI 的回复通知与下面这些 harness 提醒一起静音。
            GroupCaption(stringResource(R.string.qk_04395))
            SetGroup {
                SetRow(
                    icon = Icons.Filled.Notifications,
                    name = stringResource(R.string.qk_03842),
                    sub = stringResource(R.string.qk_04396),
                    checked = masterOn,
                    onToggle = {
                        masterOn = !masterOn
                        uiPrefs.edit().putBoolean("ai_reply_notify", masterOn).apply()
                    },
                    scaled = scaled,
                )
            }
            Spacer(Modifier.height(12.dp))

            // ── 前台抑制 ──
            val suppress = snap.optBoolean("suppressForeground", false)
            val suppressDefault = snap.optBoolean("suppressForegroundDefault", false)
            SetGroup {
                SetRow(
                    icon = Icons.Filled.Notifications,
                    name = stringResource(R.string.qk_04135),
                    sub = stringResource(R.string.qk_04136),
                    checked = suppress,
                    onToggle = { write("suppressForeground", !suppress) },
                    scaled = scaled,
                )
            }
            Text(
                stringResource(
                    R.string.qk_04159,
                    (if (suppress) qstr(R.string.qk_04162) else qstr(R.string.qk_04161)) +
                        (if (suppress == suppressDefault) "（等于本版默认值）" else "（已偏离本版默认值）"),
                ),
                fontSize = scaled(11), color = Muted,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 4.dp),
            )

            // ── 五类分类开关 ──
            GroupCaption(stringResource(R.string.qk_04137))
            val categories = snap.optJSONObject("categories") ?: JSONObject()
            SetGroup {
                CATEGORY_ORDER.forEachIndexed { index, (key, labelRes, consequenceRes) ->
                    if (index > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    }
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        SetRow(
                            icon = Icons.Filled.Notifications,
                            name = stringResource(labelRes),
                            sub = stringResource(consequenceRes),
                            checked = categories.optBoolean(key, false),
                            onToggle = { write("cat.$key", !categories.optBoolean(key, false)) },
                            scaled = scaled,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 自检 / 系统设置 / 测试 ──
            SetGroup {
                SetRowClickable(
                    icon = Icons.Filled.Notifications,
                    name = stringResource(R.string.qk_04148),
                    sub = stringResource(R.string.qk_04166),
                    value = "",
                    onClick = {
                        selfCheck = NotifyCenter.selfCheck(context)
                        permissionOk = notifyPermissionOk(context)
                        message = null
                    },
                    scaled = scaled,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                SetRowClickable(
                    icon = Icons.Filled.Notifications,
                    name = stringResource(R.string.qk_04149),
                    sub = "",
                    value = "",
                    onClick = {
                        val ok = try {
                            context.startActivity(NotifyCenter.appSettingsIntent(context))
                            true
                        } catch (t: Throwable) {
                            false
                        }
                        if (!ok) {
                            message = qstr(R.string.qk_04008) // 该系统没有「应用通知设置」页
                            messageTone = NotifyMessageTone.Error
                        }
                    },
                    scaled = scaled,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                SetRowClickable(
                    icon = Icons.Filled.Notifications,
                    name = stringResource(R.string.qk_04150),
                    sub = "",
                    value = "",
                    onClick = {
                        val posted = NotifyCenter.sendTestAll(context)
                        message = if (posted > 0) {
                            messageTone = NotifyMessageTone.Ok
                            qstr(R.string.qk_04155, posted.toString())
                        } else {
                            messageTone = NotifyMessageTone.Error
                            qstr(R.string.qk_04156)
                        }
                    },
                    scaled = scaled,
                )
            }

            // ── 自检结果 ──
            val check = selfCheck
            if (check != null) {
                Spacer(Modifier.height(16.dp))
                GroupCaption(stringResource(R.string.qk_04151))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                ) {
                    val systemOn = check.optBoolean("notificationsEnabled", true)
                    val granted = check.optBoolean("permissionGranted", false)
                    Text(
                        stringResource(
                            R.string.qk_04152,
                            if (systemOn) qstr(R.string.qk_04161) else qstr(R.string.qk_04162),
                            check.optString("permissionLabel").ifEmpty {
                                if (granted) qstr(R.string.qk_04161) else qstr(R.string.qk_04162)
                            },
                        ),
                        fontSize = scaled(12), lineHeight = scaled(18),
                    )
                    Spacer(Modifier.height(6.dp))
                    val channels = check.optJSONArray("channels")
                    val degraded = mutableListOf<String>()
                    if (channels != null) {
                        for (i in 0 until channels.length()) {
                            val row = channels.optJSONObject(i) ?: continue
                            if (row.optBoolean("degraded", false)) {
                                degraded += row.optString("label").ifEmpty { row.optString("category") }
                            }
                        }
                    }
                    Text(
                        if (degraded.isNotEmpty()) {
                            stringResource(R.string.qk_04153, degraded.joinToString("、"))
                        } else {
                            stringResource(R.string.qk_04154)
                        },
                        fontSize = scaled(12),
                        color = if (degraded.isNotEmpty()) dangerColor else Muted,
                        lineHeight = scaled(18),
                    )
                    if (channels != null) {
                        for (i in 0 until channels.length()) {
                            val row = channels.optJSONObject(i) ?: continue
                            val label = row.optString("label").ifEmpty { row.optString("category") }
                            val state = when {
                                row.optBoolean("degraded", false) -> qstr(R.string.qk_04163) // 系统已降级
                                !row.optBoolean("exists", true) -> qstr(R.string.qk_04164) // 渠道不存在
                                else -> qstr(R.string.qk_04165) // 正常
                            }
                            val importance = row.optString("importanceLabel")
                            val channelId = row.optString("selected")
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.qk_04160, label, state) +
                                        if (importance.isEmpty()) "" else "（$importance）",
                                    fontSize = scaled(12),
                                    modifier = Modifier.weight(1f),
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                if (channelId.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.qk_04157),
                                        fontSize = scaled(12), color = Accent,
                                        modifier = Modifier
                                            .clickable {
                                                val ok = try {
                                                    context.startActivity(
                                                        NotifyCenter.channelSettingsIntent(context, channelId),
                                                    )
                                                    true
                                                } catch (t: Throwable) {
                                                    false
                                                }
                                                if (!ok) {
                                                    message = qstr(R.string.qk_04008)
                                                    messageTone = NotifyMessageTone.Error
                                                }
                                            }
                                            .padding(8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message!!,
                    fontSize = scaled(12),
                    color = when (messageTone) {
                        NotifyMessageTone.Ok -> Sage
                        NotifyMessageTone.Error -> dangerColor
                        NotifyMessageTone.Info -> Muted
                    },
                    lineHeight = scaled(18),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 分类顺序与文案（与 harness WebUI `notify-settings.tsx` 的 CATEGORY_LABELS 同序同文）。
 *
 * 标签文本的真源是 dshmobile 壳侧 `UserCopy.notifyCategory`（internal，跨模块不可见），
 * 此处逐字对齐；若壳侧改文案，这里必须同步（沟道已由 spec 测试在壳侧钉住）。
 */
private val CATEGORY_ORDER: List<Triple<String, Int, Int>> = listOf(
    Triple("report", R.string.qk_04138, R.string.qk_04139),
    Triple("question", R.string.qk_04140, R.string.qk_04141),
    Triple("approval", R.string.qk_04142, R.string.qk_04143),
    Triple("todo", R.string.qk_04144, R.string.qk_04145),
    Triple("silent", R.string.qk_04146, R.string.qk_04147),
)

/** 通知权限 + 系统总开关（两者都开才算真的能提醒）。 */
private fun notifyPermissionOk(context: Context): Boolean {
    val runtime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
    return runtime && nm.areNotificationsEnabled()
}
