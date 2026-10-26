package com.ai.assistance.quro.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.ai.assistance.quro.dshmobile.PhoneControl
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentPress
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Sage
import com.ai.assistance.quro.util.qstr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 屏内回执消息的语气（决定着色），不靠猜文本前缀。 */
private enum class PcTone { Info, Ok, Error }

/**
 * 危险语义色（#482 提升为文件级）：重写后状态总览面板、危险操作域卡也要用它，
 * 原来它是 `PhoneControlScreen()` 里的局部 val，跨 composable 复用不到。
 */
private val dangerColor: Color = Color(0xFFC0432F)

/**
 * 手机操控设置屏（#417：功能从 harness WebUI「手机控制」区迁入 ZorvAI 设置）。
 *
 * 全部语义走 dshmobile 壳侧公开门面 [PhoneControl]（屏幕范围 / 虚拟屏 / Shizuku /
 * 无障碍 / Root 两个授权门 + 文件属主维护），与 harness WebUI 的
 * `phone-control.tsx` 同一真源，不另存一份设置。
 *
 * 三条从 WebUI 侧继承的纪律：
 *  1. **读不到就说读不到**：任何字段缺失/解析失败都落「状态不可读」，
 *     绝不拿默认值冒充事实（SetRow 的 checked 只在 readable 时有意义）。
 *  2. **写后回读**：所有写操作完成后重新拉快照，界面显示值只认壳侧回读。
 *  3. **读路径不阻塞 UI**：2s 轮询与所有动作都在 IO 协程里跑；
 *     Shizuku 教程外链按用户要求**不接线**（只保留下载入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneControlScreen(
    onClose: () -> Unit,
    scaled: (Int) -> TextUnit = { it.sp },
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // 快照：null = 尚未读到（加载中/失败）。不预填默认值——展示值只认壳侧真源。
    var snapshot by remember { mutableStateOf<JSONObject?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var tone by remember { mutableStateOf(PcTone.Info) }
    // 三连点确认：4 秒内没有下一步就复位，避免「隔很久点一下」误触。
    var confirmStage by remember { mutableStateOf(0) }
    // 用户尝试开启开关但 root 未授权：壳侧已弹授权框，授权一到就自动续开。
    var pendingEnable by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    /** 重新拉一次全量快照（IO；不阻塞 UI）。 */
    fun reload() {
        scope.launch(Dispatchers.IO) { snapshot = PhoneControl.snapshot(context) }
    }

    /** 失败回执 → 人话（壳侧 guidance 优先，其次按 code 映射，最后兜底）。 */
    fun reasonOf(reply: JSONObject): String {
        val guidance = reply.optString("guidance").ifBlank { reply.optString("message") }
        if (guidance.isNotBlank()) return guidance
        return pcDescribeCallReason(reply.optString("code").ifBlank { reply.optString("reason") })
    }

    /** root 开关写回执结算（含「请求已发起」特例）。 */
    fun settleRootWrite(reply: JSONObject, wantOn: Boolean) {
        val ok = reply.optBoolean("ok", false)
        if (ok) {
            message = qstr(if (wantOn) R.string.qk_04249 else R.string.qk_04250)
            tone = PcTone.Ok
            return
        }
        message = qstr(if (wantOn) R.string.qk_04251 else R.string.qk_04252, reasonOf(reply))
        tone = PcTone.Error
    }

    // 2s 轮询（读路径在 IO；壳侧 status 自带 kickBind 自愈，绝不阻塞等待 binder）。
    LaunchedEffect(Unit) {
        while (true) {
            snapshot = withContext(Dispatchers.IO) { PhoneControl.snapshot(context) }
            delay(2000)
        }
    }

    // 三连点 4s 复位。
    LaunchedEffect(confirmStage) {
        if (confirmStage > 0) {
            delay(4000)
            confirmStage = 0
        }
    }

    val snap = snapshot
    val vdisplay = snap?.optJSONObject("vdisplay")
    val shizuku = snap?.optJSONObject("shizuku")
    val a11y = snap?.optJSONObject("a11y")
    val rootGrant = snap?.optJSONObject("rootGrant")
    val rootAccess = snap?.optJSONObject("rootAccess")

    val scopeWire = snap?.optString("scope") ?: ""
    val vdisplayScale = snap?.optDouble("vdisplayScale", 0.75) ?: 0.75
    val vdisplayFloat = snap?.optBoolean("vdisplayFloat", false)

    val shizukuReadable = shizuku != null && shizuku.has("installed")
    val shizukuInstalled = shizuku?.optBoolean("installed", false) == true
    val shizukuRunning = shizuku?.optBoolean("running", false) == true
    val shizukuGranted = shizuku?.optBoolean("granted", false) == true
    val shizukuDiagnostics = shizuku?.optString("diagnostics") ?: ""

    val a11yReadable = a11y != null && a11y.has("enabled")
    val a11yEnabled = a11y?.optBoolean("enabled", false) == true
    val a11yRestricted = a11y?.optBoolean("restrictedSettingsApplies", false) == true

    val grantReadable = rootGrant != null && rootGrant.has("granted")
    val grantOn = rootGrant?.optBoolean("granted", false) == true
    val consentValid = rootGrant?.optBoolean("consentValid", false) == true
    val channelRoot = rootGrant?.optBoolean("channelRoot", false) == true
    val appRootGranted = rootGrant?.optBoolean("rootGranted", false) == true
    val grantHonesty = rootGrant?.optString("honesty") ?: ""
    val channelUid = rootGrant?.optInt("channelUid", -1) ?: -1

    val accessReadable = rootAccess != null && rootAccess.has("granted")
    val accessGranted = rootAccess?.optBoolean("granted", false) == true
    val accessRequesting = rootAccess?.optBoolean("requesting", false) == true
    val accessSuExists = rootAccess?.optBoolean("suExists", false) == true
    val accessState = rootAccess?.optString("state") ?: ""
    val accessGuidance = rootAccess?.optString("guidance") ?: ""
    val managerInstalled = rootAccess?.optJSONObject("manager")?.optBoolean("installed", false) == true
    val managerLabel = rootAccess?.optJSONObject("manager")?.optString("label") ?: ""

    val ownership = rootGrant?.optJSONObject("ownership")

    // 自动续开（2026-09-30 主人定例的体验闭环）：用户开开关 → 壳侧因「root 未授权」拦下并
    // 弹出授权框 → 用户点「允许」→ 轮询看到 rootAccess.granted → 自动把开关续开。
    LaunchedEffect(pendingEnable, grantOn, consentValid, accessGranted, accessState) {
        if (!pendingEnable) return@LaunchedEffect
        if (!consentValid || accessState == "denied" || accessState == "timeout" || accessState == "no-su") {
            pendingEnable = false
            return@LaunchedEffect
        }
        if (grantOn) {
            pendingEnable = false
            return@LaunchedEffect
        }
        if (accessGranted) {
            pendingEnable = false
            val reply = withContext(Dispatchers.IO) { PhoneControl.setRootGranted(context, true) }
            settleRootWrite(reply, wantOn = true)
            snapshot = withContext(Dispatchers.IO) { PhoneControl.snapshot(context) }
        }
    }

    // ── 动作（全部 IO；Activity 相关的解锁/外链需要前台界面）────────────────────

    fun setScope(wire: String) {
        scope.launch(Dispatchers.IO) {
            PhoneControl.setScreenScope(context, wire)
            snapshot = PhoneControl.snapshot(context)
        }
    }

    fun setScale(value: Double) {
        scope.launch(Dispatchers.IO) {
            PhoneControl.setVdisplayScale(context, value)
            snapshot = PhoneControl.snapshot(context)
        }
    }

    fun setFloat(enable: Boolean) {
        scope.launch(Dispatchers.IO) {
            PhoneControl.setVdisplayFloat(context, enable)
            snapshot = PhoneControl.snapshot(context)
        }
    }

    fun runShizukuAction(call: suspend () -> JSONObject, okText: String, failLeadRes: Int) {
        scope.launch(Dispatchers.IO) {
            val reply = call()
            val ok = reply.optBoolean("ok", false)
            message = if (ok) okText else qstr(failLeadRes, reasonOf(reply))
            tone = if (ok) PcTone.Ok else PcTone.Error
            snapshot = PhoneControl.snapshot(context)
        }
    }

    fun toggleRootConsent(next: Boolean) {
        scope.launch(Dispatchers.IO) {
            val reply = PhoneControl.setRootConsent(context, next)
            val ok = reply.optBoolean("ok", false)
            message = if (ok) {
                qstr(if (next) R.string.qk_04253 else R.string.qk_04254)
            } else {
                qstr(if (next) R.string.qk_04255 else R.string.qk_04256, reasonOf(reply))
            }
            tone = if (ok) PcTone.Ok else PcTone.Error
            snapshot = PhoneControl.snapshot(context)
        }
    }

    fun toggleRootGrant(wantOn: Boolean) {
        scope.launch(Dispatchers.IO) {
            val reply = PhoneControl.setRootGranted(context, wantOn)
            // 「请求已发起」不是失败：壳侧在第三道门拦下时当场弹授权框并回 request-started，
            // 记 pending 等轮询自动续开；渲染成「进行中」而不是「开启失败」。
            if (wantOn && reply.optString("code") == "request-started") {
                pendingEnable = true
                message = qstr(R.string.qk_04248)
                tone = PcTone.Info
                snapshot = PhoneControl.snapshot(context)
                return@launch
            }
            settleRootWrite(reply, wantOn)
            snapshot = PhoneControl.snapshot(context)
        }
    }

    fun requestRootAccess() {
        scope.launch(Dispatchers.IO) {
            val reply = PhoneControl.requestRootAccess(context)
            val ok = reply.optBoolean("ok", false)
            message = if (ok) {
                qstr(R.string.qk_04257)
            } else {
                qstr(R.string.qk_04258, reasonOf(reply))
            }
            tone = if (ok) PcTone.Ok else PcTone.Error
            snapshot = PhoneControl.snapshot(context)
        }
    }

    fun openRootDisclaimer() {
        if (activity == null) return
        scope.launch(Dispatchers.IO) {
            val reply = PhoneControl.openRootDisclaimer(activity)
            val ok = reply.optBoolean("ok", false)
            message = if (ok) qstr(R.string.qk_04273) else qstr(R.string.qk_04272, reply.optString("reason"))
            tone = if (ok) PcTone.Ok else PcTone.Error
        }
    }

    fun unlockRestrictedSettings() {
        if (activity == null) return
        scope.launch(Dispatchers.IO) {
            val reply = PhoneControl.unlockRestrictedSettings(activity)
            val ok = reply.optBoolean("ok", false)
            message = reply.optString("message").ifBlank {
                if (ok) qstr(R.string.qk_04225) else qstr(R.string.qk_04224, qstr(R.string.qk_04271))
            }
            tone = if (ok) PcTone.Ok else PcTone.Error
        }
    }

    fun repairOwnership() {
        scope.launch(Dispatchers.IO) {
            val reply = PhoneControl.repairRootOwnership(context)
            val ok = reply.optBoolean("ok", false)
            if (ok) {
                message = null
            } else {
                message = qstr(R.string.qk_04263, reasonOf(reply))
                tone = PcTone.Error
            }
            snapshot = PhoneControl.snapshot(context)
        }
    }

    fun tapForceDestroy() {
        val next = confirmStage + 1
        if (next < 3) {
            confirmStage = next
            message = null
            return
        }
        confirmStage = 0
        scope.launch(Dispatchers.IO) {
            val reply = PhoneControl.forceDestroyVdisplay(context)
            val ok = reply.optBoolean("ok", false)
            message = reply.optString("guidance").ifBlank {
                if (ok) qstr(R.string.qk_04197) else qstr(R.string.qk_04196)
            }
            tone = if (ok) PcTone.Ok else PcTone.Error
            snapshot = PhoneControl.snapshot(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 标题块（#482 重写）：大字标题 + 一行副题。副题写清「本页管什么、授权在哪」，
                    // 这样页面第一屏就不必再放一段说明文字占位。
                    Column {
                        Text(
                            stringResource(R.string.qk_04398),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        Text(
                            stringResource(R.string.qk_04399),
                            fontSize = scaled(10), color = Muted,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (snap == null) {
                Text(qstr(R.string.qk_04300), fontSize = scaled(12), color = Muted)
                Spacer(Modifier.height(12.dp))
                HarnessActionRow(
                    icon = Icons.Filled.Refresh,
                    title = stringResource(R.string.qk_04408),
                    onClick = { reload() },
                    scaled = scaled,
                )
                Spacer(Modifier.height(24.dp))
                return@Column
            }

            // ══ ① 状态总览 ══════════════════════════════════════════════════════
            // 旧形态是两条 SetRow 夹在一堆设置项里，「现在到底能用几个能力」要靠用户自己数。
            // 新形态把四个域的状态抽出来做成面板：一眼看到 x/4 就绪，哪一环断的也一眼看到。
            val screenReady = vdisplay?.optString("state") == "active" || vdisplay?.optString("state") == "ready"
            val privReady = shizukuRunning && shizukuGranted
            val a11yReady = a11yEnabled
            val rootReady = grantOn
            HarnessStatusPanel(
                entries = listOf(
                    HarnessStatusEntry(
                        icon = Icons.Filled.DesktopWindows,
                        name = stringResource(R.string.qk_04402),
                        state = pcVdisplayStateLabel(vdisplay),
                        ready = screenReady,
                        detail = pcVdisplayGuidance(vdisplay, shizuku),
                    ),
                    HarnessStatusEntry(
                        icon = Icons.Filled.Cable,
                        name = stringResource(R.string.qk_04403),
                        state = pcShizukuStateLabel(shizuku),
                        ready = privReady,
                        detail = shizuku?.optString("guidance")?.ifBlank { null } ?: pcShizukuStepHint(shizuku),
                    ),
                    HarnessStatusEntry(
                        icon = Icons.Filled.Accessibility,
                        name = stringResource(R.string.qk_04405),
                        state = when {
                            !a11yReadable -> stringResource(R.string.qk_04191)
                            a11yEnabled -> stringResource(R.string.qk_04161)
                            else -> stringResource(R.string.qk_04162)
                        },
                        ready = a11yReady,
                        detail = null,
                    ),
                    HarnessStatusEntry(
                        icon = Icons.Filled.Security,
                        name = stringResource(R.string.qk_04404),
                        state = pcRootGrantStateLabel(rootGrant),
                        ready = rootReady,
                        detail = grantHonesty.ifBlank { null },
                    ),
                ),
                readyCount = listOf(screenReady, privReady, a11yReady, rootReady).count { it },
                onRefresh = { reload() },
                scaled = scaled,
            )

            // ══ 02 屏幕范围 ═════════════════════════════════════════════════════
            HarnessDomainCard(
                index = "02",
                title = stringResource(R.string.qk_04169),
                subtitle = stringResource(R.string.qk_04181),
                scaled = scaled,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 6.dp)) {
                    PC_SCOPE_OPTIONS.forEach { option ->
                        HarnessChoiceTile(
                            label = stringResource(option.first),
                            selected = scopeWire == option.third,
                            onClick = { setScope(option.third) },
                            scaled = scaled,
                        )
                    }
                }
                // 只显示**当前选中**那一项的后果说明：三段的原文都在，但一次只呈现一条，
                // 避免旧形态「三个单选项各带一段小字」把这一屏读成说明书。
                PC_SCOPE_OPTIONS.firstOrNull { it.third == scopeWire }?.let { selected ->
                    Text(
                        stringResource(selected.second),
                        fontSize = scaled(11), color = Muted, lineHeight = scaled(16),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }

            // ══ 03 虚拟屏 ═══════════════════════════════════════════════════════
            HarnessDomainCard(
                index = "03",
                title = stringResource(R.string.qk_04170),
                subtitle = stringResource(R.string.qk_04183),
                scaled = scaled,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 6.dp)) {
                    PC_SCALE_OPTIONS.forEach { scale ->
                        HarnessChoiceTile(
                            label = pcScaleLabel(scale),
                            selected = vdisplayScale == scale,
                            onClick = { setScale(scale) },
                            scaled = scaled,
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                HarnessToggleRow(
                    title = stringResource(R.string.qk_04184),
                    subtitle = stringResource(R.string.qk_04185),
                    checked = vdisplayFloat == true,
                    onCheckedChange = { setFloat(it) },
                    scaled = scaled,
                )
            }

            // ══ 04 Shizuku 特权通道 ═════════════════════════════════════════════
            // 🔴 本卡**只有修复动作，没有授权按钮**：Shizuku 授权门唯一在「设置 → 权限」。
            HarnessDomainCard(
                index = "04",
                title = stringResource(R.string.qk_04171),
                subtitle = stringResource(R.string.qk_04198),
                scaled = scaled,
            ) {
                HarnessActionRow(
                    icon = Icons.Filled.Download,
                    title = stringResource(R.string.qk_04199),
                    enabled = activity != null,
                    onClick = {
                        runShizukuAction(
                            call = { PhoneControl.openShizukuDownload(activity!!) },
                            okText = qstr(R.string.qk_04291),
                            failLeadRes = R.string.qk_04211,
                        )
                    },
                    scaled = scaled,
                )
                HarnessActionRow(
                    icon = Icons.Filled.OpenInNew,
                    title = stringResource(R.string.qk_04200),
                    enabled = activity != null && shizukuReadable && shizukuInstalled,
                    onClick = {
                        runShizukuAction(
                            call = { PhoneControl.openShizukuManager(activity!!) },
                            okText = qstr(R.string.qk_04292),
                            failLeadRes = R.string.qk_04301,
                        )
                    },
                    scaled = scaled,
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                HarnessActionRow(
                    icon = Icons.Filled.Refresh,
                    title = stringResource(R.string.qk_04187),
                    onClick = { reload() },
                    scaled = scaled,
                )
                HarnessActionRow(
                    icon = Icons.Filled.Cable,
                    title = stringResource(R.string.qk_04202),
                    onClick = {
                        runShizukuAction(
                            call = { PhoneControl.resetShizukuConnection(context) },
                            okText = qstr(R.string.qk_04213),
                            failLeadRes = R.string.qk_04215,
                        )
                    },
                    scaled = scaled,
                )
                Text(
                    stringResource(R.string.qk_04217),
                    fontSize = scaled(11), color = Muted, lineHeight = scaled(15),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
                // issue #471：连续启动失败时把壳侧截取的子进程堆栈直接展开。
                if (shizukuDiagnostics.isNotBlank()) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    Row(
                        Modifier.fillMaxWidth().clickable { showDiagnostics = !showDiagnostics }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.qk_04210),
                            fontSize = scaled(12), color = Accent,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (showDiagnostics) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null, Modifier.size(18.dp), tint = Accent,
                        )
                    }
                    if (showDiagnostics) {
                        SelectionContainer {
                            Text(
                                shizukuDiagnostics,
                                fontSize = scaled(10), color = Muted,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = scaled(14),
                                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 10.dp),
                            )
                        }
                    }
                }
            }

            // ══ 05 无障碍通道 ═══════════════════════════════════════════════════
            // 🔴 同样**没有「去开启」按钮**：授权门在「设置 → 权限」。这里只留状态说明与
            // 系统级修复动作（解除受限设置），后者权限页没有。
            HarnessDomainCard(
                index = "05",
                title = stringResource(R.string.qk_04172),
                scaled = scaled,
            ) {
                Text(
                    when {
                        !a11yReadable -> stringResource(R.string.qk_04191)
                        a11yEnabled -> stringResource(R.string.qk_04220)
                        else -> stringResource(R.string.qk_04221)
                    },
                    fontSize = scaled(11), color = Muted, lineHeight = scaled(16),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
                if (a11yReadable && a11yRestricted) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    HarnessActionRow(
                        icon = Icons.Filled.Security,
                        title = stringResource(R.string.qk_04219),
                        onClick = { unlockRestrictedSettings() },
                        scaled = scaled,
                    )
                }
            }

            // ══ 06 Root 权限 ════════════════════════════════════════════════════
            HarnessDomainCard(
                index = "06",
                title = stringResource(R.string.qk_04173),
                scaled = scaled,
            ) {
                // 第一步：应用自身 root 授权（Root 管理器）。
                HarnessActionRow(
                    icon = Icons.Filled.Security,
                    title = stringResource(R.string.qk_04226),
                    subtitle = accessGuidance.ifBlank { null },
                    value = pcRootAccessStateLabel(rootAccess),
                    enabled = accessReadable && accessSuExists && !accessRequesting,
                    onClick = { requestRootAccess() },
                    scaled = scaled,
                )
                if (managerInstalled && managerLabel.isNotBlank()) {
                    Text(
                        stringResource(R.string.qk_04229, managerLabel),
                        fontSize = scaled(11), color = Muted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                // 第二步：AI root 权限（策略门 + 知情同意门）。
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.qk_04230),
                        fontSize = scaled(13), color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        pcRootGrantStateLabel(rootGrant),
                        fontSize = scaled(11), color = Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    !grantReadable -> Text(
                        stringResource(R.string.qk_04246),
                        fontSize = scaled(11), color = dangerColor, lineHeight = scaled(15),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                    !channelRoot && !appRootGranted -> {
                        val shellIdentity = channelUid == PC_SHIZUKU_SHELL_UID
                        Text(
                            if (shellIdentity) stringResource(R.string.qk_04243) else stringResource(R.string.qk_04244),
                            fontSize = scaled(11),
                            color = if (shellIdentity) Muted else dangerColor,
                            lineHeight = scaled(15),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                        )
                    }
                    else -> Text(
                        stringResource(
                            R.string.qk_04245,
                            if (channelRoot) qstr(R.string.qk_04277) else qstr(R.string.qk_04278),
                        ),
                        fontSize = scaled(11), color = Muted, lineHeight = scaled(15),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
                HarnessToggleRow(
                    title = stringResource(R.string.qk_04232),
                    checked = consentValid,
                    enabled = grantReadable,
                    onCheckedChange = { toggleRootConsent(it) },
                    scaled = scaled,
                )
                HarnessToggleRow(
                    title = stringResource(R.string.qk_04231),
                    subtitle = grantHonesty.ifBlank { null },
                    checked = grantOn,
                    // 只有通道身份确为 root（或应用已获 su 授权）才可点；读不到/非 root 一律不可点。
                    // 关闭永远允许（撤销不是需要资格的动作）。
                    enabled = grantReadable && (grantOn || channelRoot || appRootGranted) &&
                        (grantOn || consentValid),
                    onCheckedChange = { toggleRootGrant(it) },
                    scaled = scaled,
                )
                if (grantReadable && (channelRoot || appRootGranted) && !consentValid && !grantOn) {
                    Text(
                        stringResource(R.string.qk_04247),
                        fontSize = scaled(11), color = Muted, lineHeight = scaled(15),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
                Text(
                    stringResource(R.string.qk_04233),
                    fontSize = scaled(12), color = Accent,
                    modifier = Modifier
                        .clickable { openRootDisclaimer() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )

                // 文件属主维护（root 写盘后应用读不到文件时用；不改变 AI 授权）。
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                Text(
                    stringResource(R.string.qk_04259),
                    fontSize = scaled(13), color = cs.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                )
                Text(
                    stringResource(R.string.qk_04260),
                    fontSize = scaled(11), color = Muted, lineHeight = scaled(15),
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                val repair = pcOwnershipFeedback(ownership)
                HarnessActionRow(
                    icon = Icons.Filled.Refresh,
                    title = if (repair?.first == PcTone.Info && ownership?.optBoolean("running", false) == true) {
                        stringResource(R.string.qk_04262)
                    } else {
                        stringResource(R.string.qk_04261)
                    },
                    enabled = ownership?.optBoolean("running", false) != true && (accessGranted || channelRoot),
                    onClick = { repairOwnership() },
                    scaled = scaled,
                )
                if (repair != null) {
                    Text(
                        repair.second,
                        fontSize = scaled(11),
                        color = when (repair.first) {
                            PcTone.Ok -> Sage
                            PcTone.Error -> dangerColor
                            PcTone.Info -> Muted
                        },
                        lineHeight = scaled(16),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    )
                }
            }

            // ══ 07 危险操作 ═════════════════════════════════════════════════════
            HarnessDomainCard(
                index = "07",
                title = stringResource(R.string.qk_04174),
                accent = dangerColor,
                scaled = scaled,
            ) {
                HarnessActionRow(
                    icon = Icons.Filled.Delete,
                    title = if (confirmStage == 0) {
                        stringResource(R.string.qk_04192)
                    } else {
                        stringResource(R.string.qk_04194, confirmStage)
                    },
                    subtitle = stringResource(R.string.qk_04193),
                    danger = true,
                    onClick = { tapForceDestroy() },
                    scaled = scaled,
                )
                if (confirmStage > 0) {
                    Text(
                        stringResource(R.string.qk_04195),
                        fontSize = scaled(12), color = Accent,
                        modifier = Modifier
                            .clickable { confirmStage = 0 }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            // ══ 回执 ════════════════════════════════════════════════════════════
            if (message != null) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Box(
                        Modifier
                            .padding(top = 6.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when (tone) {
                                    PcTone.Ok -> Sage
                                    PcTone.Error -> dangerColor
                                    PcTone.Info -> Muted
                                },
                            ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message!!,
                        fontSize = scaled(12),
                        color = when (tone) {
                            PcTone.Ok -> Sage
                            PcTone.Error -> dangerColor
                            PcTone.Info -> Muted
                        },
                        lineHeight = scaled(18),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    DisposableEffect(Unit) { onDispose { } }
}

// ── 选项表（wire 值 / 标签 / 后果说明） ──────────────────────────────────────

/** Shizuku 以 ADB（shell）身份启动时的通道 uid（issue #262 点名的分流判据）。 */
private const val PC_SHIZUKU_SHELL_UID = 2000

private val PC_SCOPE_OPTIONS: List<Triple<Int, Int, String>> = listOf(
    Triple(R.string.qk_04175, R.string.qk_04178, "virtual-only"),
    Triple(R.string.qk_04176, R.string.qk_04179, "real-only"),
    Triple(R.string.qk_04177, R.string.qk_04180, "all"),
)

private val PC_SCALE_OPTIONS: List<Double> = listOf(0.5, 0.75, 1.0)

// ── #482 重写后的页面骨架组件 ────────────────────────────────────────────────
//
// 旧骨架是「GroupCaption + SetGroup(SetRow/SetRowClickable/PcActionChip)」——那是**设置列表**
// 的语法：一屏七段分组标题、每段一个圆角容器、行内右侧塞状态词。用户判「重写 UI」之后，
// 这里换成四个新组件，语法变了、层级也变了：
//
//   HarnessStatusPanel  状态总览面板（x/4 就绪 + 四个域的状态条目）——旧骨架完全没有的层级
//   HarnessDomainCard   编号能力域卡（02/03/04…）：一张卡 = 一个能力域，卡头带编号与副标
//   HarnessChoiceTile   三格选择砖（横向均分、选中填色描边）——取代纵向 RadioButton 列表
//   HarnessActionRow    整行动作条（图标 + 文案 + 状态词 + 进入箭头）——取代小 chip
//   HarnessToggleRow    开关行（标题 + 长说明 + 右侧 Switch）
//
// 动作逻辑与状态判据一个字没动，全部原样复用：这次只换呈现层。

/** 状态总览里的一条（一个能力域）。 */
private data class HarnessStatusEntry(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val name: String,
    val state: String,
    val ready: Boolean,
    val detail: String?,
)

/**
 * 状态总览面板：顶部「x / n 就绪」+ 刷新，下面四条域状态。
 *
 * 这一块是旧页面没有的层级——以前「现在到底能用几个能力」得用户自己把两三条 SetRow 的状态词
 * 挨个读完再心算，现在一眼给出就绪计数，断的那一环带红点。
 */
@Composable
private fun HarnessStatusPanel(
    entries: List<HarnessStatusEntry>,
    readyCount: Int,
    onRefresh: () -> Unit,
    scaled: (Int) -> TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surface)
            .border(1.dp, Line, RoundedCornerShape(16.dp)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.qk_04400, readyCount, entries.size),
                fontSize = scaled(12),
                color = if (readyCount == entries.size) Sage else Accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.qk_04408),
                    Modifier.size(16.dp),
                    tint = Muted,
                )
            }
        }
        entries.forEachIndexed { index, entry ->
            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .size(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (entry.ready) Sage else dangerColor),
                )
                Spacer(Modifier.width(9.dp))
                Icon(
                    entry.icon, contentDescription = null, Modifier.padding(top = 1.dp).size(15.dp),
                    tint = if (entry.ready) cs.onSurfaceVariant else Muted,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entry.name,
                            fontSize = scaled(12), color = cs.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            entry.state,
                            fontSize = scaled(11),
                            color = if (entry.ready) Muted else dangerColor,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!entry.detail.isNullOrBlank()) {
                        Text(
                            entry.detail,
                            fontSize = scaled(10), color = Muted, lineHeight = scaled(14),
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 编号能力域卡：卡头「02 · 屏幕范围」+ 可选副标，内容由调用方给。
 *
 * 编号不是装饰：它把页面从「一堆同权重的分组」变成**有顺序的能力清单**，
 * 也让用户能说「第 04 卡里的下载按钮」而不是「那个下载」。
 */
@Composable
private fun HarnessDomainCard(
    index: String,
    title: String,
    subtitle: String? = null,
    accent: Color = Accent,
    scaled: (Int) -> TextUnit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surface)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(top = 12.dp, bottom = 10.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                index,
                fontSize = scaled(11), color = accent,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                fontSize = scaled(14), color = cs.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                fontSize = scaled(11), color = Muted, lineHeight = scaled(16),
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 2.dp),
            )
        }
        content()
    }
}

/**
 * 三格选择砖（横向均分）：选中填 AccentSoft + 描边 Accent。
 *
 * 取代纵向 RadioButton 列表——「屏幕范围」「虚拟屏分辨率」都是 2~3 个互斥短标签，
 * 横排一屏放得下，纵向列表反而把页面拉长一倍。
 */
@Composable
private fun androidx.compose.foundation.layout.RowScope.HarnessChoiceTile(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    scaled: (Int) -> TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .weight(1f)
            .padding(horizontal = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AccentSoft else Color.Transparent)
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = scaled(12),
            color = when {
                !enabled -> Muted
                selected -> AccentPress
                else -> cs.onSurface
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 整行动作条：图标 + 标题 +（可选）说明 +（可选）状态词 + 进入箭头。 */
@Composable
private fun HarnessActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
    scaled: (Int) -> TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    val tint = when {
        danger -> dangerColor
        enabled -> Accent
        else -> Muted
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(17.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = scaled(13),
                color = if (enabled) cs.onSurface else Muted,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = scaled(11), color = Muted, lineHeight = scaled(15),
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!value.isNullOrBlank()) {
            Text(
                value,
                fontSize = scaled(11), color = tint,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (enabled) {
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, Modifier.size(15.dp), tint = Muted)
        }
    }
}

/** 开关行：标题 +（可选）长说明 + 右侧 Switch。 */
@Composable
private fun HarnessToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    scaled: (Int) -> TextUnit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = scaled(13),
                color = if (enabled) cs.onSurface else Muted,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = scaled(11), color = Muted, lineHeight = scaled(15),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
        )
    }
}

// ── 状态标签（读不到一律「状态不可读」，不冒充） ──────────────────────────────

private fun pcVdisplayStateLabel(v: JSONObject?): String {
    if (v == null || !v.has("state")) return qstr(R.string.qk_04191)
    return when (v.optString("state")) {
        "active" -> qstr(R.string.qk_04188)
        "ready" -> qstr(R.string.qk_04189)
        "blocked" -> qstr(R.string.qk_04190)
        else -> qstr(R.string.qk_04191)
    }
}

/** 虚拟屏说明语；与 Shizuku 说明逐字重复时按值去重（只不重复同一句，不臆造替代文案）。 */
private fun pcVdisplayGuidance(v: JSONObject?, shizuku: JSONObject?): String {
    val guidance = v?.optString("guidance") ?: ""
    val shizukuGuidance = shizuku?.optString("guidance") ?: ""
    return if (guidance.isNotBlank() && guidance != shizukuGuidance) guidance else ""
}

private fun pcShizukuStateLabel(s: JSONObject?): String {
    if (s == null || !s.has("installed")) return qstr(R.string.qk_04191)
    if (!s.optBoolean("installed", false)) return qstr(R.string.qk_04203)
    if (!s.optBoolean("running", false)) return qstr(R.string.qk_04204)
    if (!s.optBoolean("granted", false)) return qstr(R.string.qk_04205)
    if (!s.optBoolean("bound", false)) {
        if (s.optBoolean("binding", false)) return qstr(R.string.qk_04206)
        // issue #471：连续启动失败是一条独立的事实，不得继续被「通道未建立」一笔带过。
        if (s.optBoolean("startFailed", false)) return qstr(R.string.qk_04207)
        return qstr(R.string.qk_04208)
    }
    return qstr(R.string.qk_04209)
}

/** 壳侧 guidance 缺失时的兜底说明（每态都能说清「下一步做什么」）。 */
private fun pcShizukuStepHint(s: JSONObject?): String {
    // 可读性判据：壳侧回包必带 installed 字段；readable:false 回落（解析失败）没有它。
    if (s == null || !s.has("installed")) {
        return qstr(R.string.qk_04293)
    }
    if (!s.optBoolean("installed", false)) return qstr(R.string.qk_04294)
    if (!s.optBoolean("running", false)) return qstr(R.string.qk_04295)
    if (!s.optBoolean("granted", false)) return qstr(R.string.qk_04296)
    if (!s.optBoolean("bound", false) && s.optBoolean("startFailed", false)) return qstr(R.string.qk_04297)
    if (!s.optBoolean("bound", false)) return qstr(R.string.qk_04298)
    return qstr(R.string.qk_04299)
}

private fun pcRootAccessStateLabel(a: JSONObject?): String {
    if (a == null || !a.has("granted")) return qstr(R.string.qk_04191)
    return when (a.optString("state")) {
        "granted" -> qstr(R.string.qk_04234)
        "requesting" -> qstr(R.string.qk_04235)
        "denied" -> qstr(R.string.qk_04236)
        "timeout" -> qstr(R.string.qk_04237)
        "no-su" -> qstr(R.string.qk_04238)
        else -> qstr(R.string.qk_04239)
    }
}

/** 开关状态 → 状态词。顺序即真实判据顺序：读不到 → 无可用 root 通道 → 未授权 → 已授权。 */
private fun pcRootGrantStateLabel(g: JSONObject?): String {
    if (g == null || !g.has("granted")) return qstr(R.string.qk_04191)
    val channelRoot = g.optBoolean("channelRoot", false)
    val rootGranted = g.optBoolean("rootGranted", false)
    if (!channelRoot && !rootGranted) return qstr(R.string.qk_04240)
    if (!g.optBoolean("granted", false)) return qstr(R.string.qk_04241)
    return qstr(R.string.qk_04242)
}

/**
 * 文件属主维护反馈（与 harness WebUI `describeOwnershipRepair` 同口径）：
 * 已提交/进行中/结果不明一律不得渲染成「修复完成」。
 *
 * @return null = 还没有可展示的事实（未发起/读不到）；否则（语气, 文案）。
 */
private fun pcOwnershipFeedback(o: JSONObject?): Pair<PcTone, String>? {
    if (o == null || !o.has("running")) return null
    if (o.optInt("startedAt", 0) == 0) return null
    if (o.optBoolean("running", false)) {
        return if (o.optBoolean("overdue", false)) {
            PcTone.Error to qstr(R.string.qk_04268)
        } else {
            val operation = o.optString("operation")
            if (operation.isNotBlank() && !operation.contains("ownership")) {
                PcTone.Info to qstr(R.string.qk_04269)
            } else {
                PcTone.Info to qstr(R.string.qk_04270)
            }
        }
    }
    val result = o.optJSONObject("result") ?: return PcTone.Error to qstr(R.string.qk_04264)
    if (result.optString("skipped") == "no-root-path") {
        return PcTone.Ok to qstr(R.string.qk_04265)
    }
    fun count(key: String): String {
        val value = result.optInt(key, -1)
        return if (value >= 0) value.toString() else qstr(R.string.qk_04271)
    }
    val counts = qstr(R.string.qk_04266, count("checked"), count("healed"), count("failures"))
    val complete = result.has("checked") && result.has("healed")
    if (result.optBoolean("ok", false) && complete &&
        !result.optBoolean("truncated", true) && !result.optBoolean("deadlineExceeded", true) &&
        result.optInt("remaining", -1) == 0 && result.optInt("unverifiedMutations", -1) == 0 &&
        result.optInt("failures", -1) == 0
    ) {
        return PcTone.Ok to counts
    }
    val reason = result.optString("reason").ifBlank { result.optString("code") }
    return PcTone.Error to qstr(
        R.string.qk_04267,
        count("checked"), count("healed"), count("failures"),
        pcDescribeCallReason(reason),
    )
}

/**
 * 失败原因 → 人话（壳侧/页面侧共用的码表；未登记的码走兜底，绝不把异常措辞当文案）。
 */
private fun pcDescribeCallReason(reason: String?): String {
    val code = reason?.trim().orEmpty()
    if (code.isEmpty()) return qstr(R.string.qk_04279)
    val mapped = when (code) {
        "unknown-key" -> R.string.qk_04289
        "insecure-url" -> R.string.qk_04290
        "no-handler" -> R.string.qk_04280
        "not-installed" -> R.string.qk_04281
        "missing-asset" -> R.string.qk_04282
        "ui-thread-timeout" -> R.string.qk_04283
        "consent-required" -> R.string.qk_04284
        "not-root-channel" -> R.string.qk_04285
        "root-not-granted" -> R.string.qk_04286
        "no-su" -> R.string.qk_04287
        "already-requesting" -> R.string.qk_04288
        else -> null
    }
    return if (mapped != null) qstr(mapped) else qstr(R.string.qk_04279)
}

/** 分辨率档位标签（1.0 显示「原生」，其余显示百分比）。 */
private fun pcScaleLabel(scale: Double): String = when (scale) {
    1.0 -> qstr(R.string.qk_04276)
    0.5 -> qstr(R.string.qk_04274)
    else -> qstr(R.string.qk_04275)
}
