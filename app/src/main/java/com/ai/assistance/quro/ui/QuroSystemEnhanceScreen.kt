package com.ai.assistance.quro.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.privilege.QuroLSPosed
import com.ai.assistance.quro.core.privilege.QuroModuleSelfCheck
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeInbox
import com.ai.assistance.quro.core.assistant.QuroAgentBridgeProtocol
import com.ai.assistance.quro.core.xposed.QuroModuleConfig
import com.ai.assistance.quro.core.xposed.QuroModulePrefs
import com.ai.assistance.quro.core.xposed.QuroModuleServiceState
import com.ai.assistance.quro.core.xposed.QuroModuleStatus
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted

/**
 * 「Root 与系统增强」页（不含任何需要 Root 的能力）。
 *
 * 数据全部来自框架本身：框架通信看 XposedService 是否连接，作用域看 getScope()，
 * 「模块是否真的启用」看 getRunningTargets()——服务连接不等于 Hook 已加载，两者分开显示。
 * 所有开关经 RemotePreferences 下发到被 Hook 的进程，未连接框架时不可修改。
 */
@Composable
fun QuroSystemEnhanceScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(QuroModuleServiceState.status()) }
    var switches by remember {
        mutableStateOf(
            QuroModuleConfig.ALL_SWITCHES.associate { it.key to QuroModulePrefs.isEnabled(it.key) }
        )
    }
    var powerTarget by remember { mutableStateOf(QuroModulePrefs.powerTarget()) }
    var zygisk by remember { mutableStateOf(QuroLSPosed.zygiskProvider()) }
    var selfCheck by remember { mutableStateOf<String?>(null) }
    var assistantDialog by remember { mutableStateOf(false) }

    // Zygisk 提供者是 Magisk 模块，只有经 Root / Shizuku 读 /data/adb/modules 才知道，
    // 属阻塞探测，必须放 IO 线程；读不到就如实显示「未确认」，不伪装成没装。
    LaunchedEffect(Unit) {
        zygisk = withContext(Dispatchers.IO) { QuroLSPosed.refreshZygiskProviderBlocking() }
    }

    DisposableEffect(Unit) {
        val refresh = {
            status = QuroModuleServiceState.status()
            switches = QuroModuleConfig.ALL_SWITCHES.associate {
                it.key to QuroModulePrefs.isEnabled(it.key)
            }
            powerTarget = QuroModulePrefs.powerTarget()
            Unit
        }
        QuroModuleServiceState.addListener(refresh)
        refresh()
        onDispose { QuroModuleServiceState.removeListener(refresh) }
    }

    val connected = status.connected
    val showToast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }

    // 作用域是否「有意义」取决于本机有没有装那套组件：ColorOS 上没有小米助手，
    // 把它列成「未配置」只会让整页看起来是坏的。这里取一次已安装包名，回到本页时刷新。
    var installed by remember { mutableStateOf(QuroModuleConfig.installedPackages(ctx.packageManager)) }
    val localScopes = remember(installed, status) {
        QuroModuleConfig.DECLARED_SCOPE.filter { QuroModuleConfig.isTargetPresent(it, installed) }
    }

    // 从管理器勾完作用域回到本页要能立刻看到变化，否则用户会以为「勾了也没用」。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                installed = QuroModuleConfig.installedPackages(ctx.packageManager)
                status = QuroModuleServiceState.status()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 统一的「缺作用域」动作：先让框架弹授权；框架不弹（静态作用域模块常见）就直接把用户送进管理器。
    val requestScope: (List<String>) -> Unit = {
        QuroModuleServiceState.requestMissingScope(installed) { ok, message ->
            showToast(message)
            if (ok) {
                status = QuroModuleServiceState.status()
            } else {
                openFrameworkManager(ctx, showToast)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Root 与系统增强",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (connected) "框架已连接" else "未连接",
                fontSize = 12.sp,
                color = if (connected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (connected) Accent else Card)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        HorizontalDivider(color = Line)

        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            // ── 框架通信 ──
            GroupCaption("框架通信")
            SetGroup {
                SetRowClickable(
                    Icons.Filled.Link,
                    "框架服务连接",
                    if (connected) {
                        "${status.frameworkName ?: QuroLSPosed.frameworkLabel(ctx)} ${status.frameworkVersion ?: ""} · API ${status.apiVersion} · 已收到服务连接"
                    } else {
                        "未收到框架服务连接；不影响聊天、GUI、浏览器与普通 Linux 环境"
                    },
                    "",
                    onClick = { showToast(QuroLSPosed.statusText(ctx)) },
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.Extension,
                    "框架与注入环境",
                    "${QuroLSPosed.frameworkLabel(ctx)} · ${QuroLSPosed.compatibilityNote(ctx)}；" +
                        "Zygisk：${zygisk.label}。Vector 2.2（org.matrix.vector.manager）+ ZygiskNext/NeoZygisk 与 LSPosed 2.x 均支持 API 102。",
                    if (QuroLSPosed.supportsApi102(ctx) && connected) "API 102" else "需升级",
                    onClick = { showToast(QuroLSPosed.statusText(ctx)) },
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.Refresh,
                    "刷新状态",
                    "重新读取框架连接、作用域与已加载模块进程",
                    "",
                    onClick = {
                        showToast(QuroLSPosed.statusText(ctx))
                    },
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.Warning,
                    "接管自检（为什么没生效）",
                    "按「框架 → 作用域 → 目标进程是否加载模块 → 开关 → 最近一次接管」逐环检查，只读不改任何配置",
                    "查看",
                    onClick = { selfCheck = QuroModuleSelfCheck.report(ctx) },
                )
            }

            // ── 模块启用与作用域 ──
            GroupCaption("模块启用与作用域")
            SetGroup {
                val configuredLocal = localScopes.count { it in status.scope }
                SetRowClickable(
                    Icons.Filled.Extension,
                    "作用域配置",
                    "只统计本机真实存在的组件（${localScopes.size} 项）。服务连接不代表模块已启用，实际生效取决于目标进程是否加载 Hook。",
                    "$configuredLocal/${localScopes.size}",
                    onClick = {
                        if (!connected) showToast("框架服务未连接，无法读取作用域")
                        else showToast("本机适用 ${localScopes.size} 项，已勾选 $configuredLocal 项，还差 ${localScopes.size - configuredLocal} 项")
                    },
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                localScopes.forEach { pkg ->
                    ScopeLine(
                        label = QuroModuleConfig.scopeLabel(pkg),
                        configured = pkg in status.scope,
                        loaded = status.isScopeActive(pkg),
                    )
                }
                // 本机不存在的组件单独折叠成一行，避免整页红字；也让用户知道「不是没配，是这台机器没有」。
                val absent = QuroModuleConfig.DECLARED_SCOPE.filterNot { it in localScopes }
                if (absent.isNotEmpty()) {
                    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    ScopeLine(
                        label = "本机未安装：" + absent.joinToString("、") { QuroModuleConfig.scopeLabel(it) },
                        configured = true,
                        loaded = false,
                        neutral = true,
                    )
                }
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                val actionable = status.actionableMissingScope(installed)
                SetRowClickable(
                    Icons.Filled.CheckCircle,
                    "一键补齐作用域",
                    when {
                        !connected -> "需要框架服务连接后才能补齐"
                        actionable.isEmpty() -> "本机适用的作用域已勾全（未安装的组件不参与判定）"
                        else -> "还差 ${actionable.joinToString()}；点这里让框架弹授权，不弹就直接进管理器"
                    },
                    if (actionable.isEmpty()) "已完整" else "补 ${actionable.size} 项",
                    onClick = {
                        if (!connected) showToast("框架服务未连接，无法请求作用域")
                        else requestScope(actionable)
                    },
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.Security,
                    "打开框架管理器",
                    "Vector / LSPosed 均可；Vector 管理器可能没有桌面图标，会自动改走拨号入口 *#*#832867#*#*",
                    "",
                    onClick = { openFrameworkManager(ctx, showToast) },
                )
            }

            // ── 系统助手与增强 ──
            GroupCaption("系统助手与增强")
            SetGroup {
                // 主路径放最前面：这条不依赖 LSPosed，设为默认助理后长按电源键 / 耳机键
                // 直接进本应用的完整运行时（模型、工具、Skills、记忆全都可用）。
                val roleHeld = com.ai.assistance.quro.core.assistant.AssistantRole.isAssistant(ctx)
                SetRowClickable(
                    Icons.Filled.SupportAgent,
                    "设为默认数字助理（推荐，不需要框架）",
                    if (roleHeld) {
                        "已持有该角色：长按电源键 / 耳机键会直接唤起 Zorv AI 完整运行时"
                    } else {
                        "系统会把长按电源键、线控与蓝牙耳机键交给本应用；这条路与 LSPosed 无关，装上就能用"
                    },
                    if (roleHeld) "已启用" else "去设置",
                    onClick = { assistantDialog = true },
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                QuroModuleConfig.MAIN_SWITCHES.forEachIndexed { index, def ->
                    if (index > 0) {
                        HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                    }
                    val checked = switches[def.key] ?: def.defaultEnabled
                    val unmet = if (checked) status.unmetScope(def, installed) else emptyList()
                    SetRow(
                        icon = when (def.key) {
                            QuroModuleConfig.Keys.VENDOR_ASSISTANT_TAKEOVER -> Icons.Filled.SupportAgent
                            QuroModuleConfig.Keys.GEMINI_CIRCLE_TO_SEARCH -> Icons.Filled.Link
                            else -> Icons.Filled.Security
                        },
                        name = def.title,
                        sub = def.summary,
                        checked = checked,
                        onToggle = {
                            if (!connected) {
                                showToast("框架服务未连接，开关不可修改")
                                return@SetRow
                            }
                            val next = !checked
                            if (QuroModulePrefs.setEnabled(def.key, next)) {
                                switches = switches.toMutableMap().apply { put(def.key, next) }
                                // 开启后如果作用域没齐，直接把用户送到能补齐的地方；
                                // 不再用「已开启但缺少作用域」这种说了等于没说的提示。
                                if (next) {
                                    val gap = QuroModuleServiceState.status().unmetScope(def, installed)
                                    if (gap.isNotEmpty()) {
                                        showToast("已开启；还需勾选 ${gap.joinToString()}")
                                        requestScope(gap)
                                    }
                                }
                            } else {
                                showToast("写入框架配置失败，请检查框架服务")
                            }
                        },
                        enabled = connected,
                    )
                    ScopeGapRow(unmet) { requestScope(unmet) }
                }
            }

            // ── 最近一次接管：证明「接管后确实跑在完整运行时上」，而不是静默回落厂商原生 ──
            val lastTakeover = remember(status) { QuroAgentBridgeInbox.recent(ctx, 1).firstOrNull() }
            SetGroup {
                SetRowClickable(
                    Icons.Filled.History,
                    "最近接管记录",
                    lastTakeover?.let {
                        val state = if (it.ok) "成功" else "失败"
                        "${QuroAgentBridgeProtocol.sourceLabel(it.source)} · $state · ${it.elapsedMs}ms · ${it.reply.take(40)}"
                    } ?: "还没有接管记录；开启开关后从厂商助手入口发起一次请求即可",
                    "",
                    onClick = {
                        val record = lastTakeover
                        showToast(
                            if (record == null) {
                                "暂无接管记录"
                            } else {
                                "${QuroAgentBridgeProtocol.sourceLabel(record.source)} ${if (record.ok) "成功" else "失败"} " +
                                    "${record.elapsedMs}ms\n请求：${record.request.take(60)}\n" +
                                    if (record.ok) "回复：${record.reply.take(60)}" else "错误：${record.error.take(60)}"
                            },
                        )
                    },
                )
            }

            // ── 长按电源键目标 ──
            GroupCaption("系统助手接管")
            SetGroup {
                SetRowClickable(
                    Icons.Filled.PowerSettingsNew,
                    "长按电源键唤起",
                    "选择在锁死默认助理的 ROM 上由哪个助手接管；未连接框架时保持厂商默认",
                    powerTargetLabel(powerTarget),
                    onClick = {
                        if (!connected) {
                            showToast("框架服务未连接，无法修改")
                        } else {
                            val all = QuroModuleConfig.PowerTarget.ALL
                            val next = all[(all.indexOf(powerTarget) + 1) % all.size]
                            if (QuroModulePrefs.setPowerTarget(next)) {
                                powerTarget = next
                                showToast("已设为 ${powerTargetLabel(next)}")
                            } else {
                                showToast("写入框架配置失败")
                            }
                        }
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "本版本未包含需要 Root 授权的能力（应用与系统管理、受保护数据与文件、Linux 与终端增强、Google App 系统化）；" +
                    "以上能力均通过 LSPosed 框架 Hook 实现，普通模式下可手动开启无障碍，不依赖 Root。",
                fontSize = 12.sp,
                color = Muted,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }

    if (assistantDialog) {
        AssistantSetupDialog(ctx, showToast) { assistantDialog = false }
    }

    val report = selfCheck
    if (report != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { selfCheck = null },
            title = { Text("接管自检", fontSize = 16.sp) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(report, fontSize = 11.sp, lineHeight = 16.sp)
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val ok = QuroModuleSelfCheck.copy(ctx)
                        showToast(if (ok) "自检报告已复制" else "复制失败，可直接截图")
                        selfCheck = null
                    },
                ) { Text("复制并关闭") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { selfCheck = null }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun ScopeLine(
    label: String,
    configured: Boolean,
    loaded: Boolean,
    neutral: Boolean = false,
) {
    val state = when {
        neutral -> "不适用"
        loaded -> "Hook 已加载"
        configured -> "已勾选 · 进程未加载"
        else -> "未勾选"
    }
    val color = when {
        neutral -> Muted
        loaded -> Accent
        configured -> Muted
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = if (neutral) Muted else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        Spacer(Modifier.width(8.dp))
        Text(state, fontSize = 11.sp, color = color)
    }
}

/**
 * 开关下方常驻的「作用域还差这几项」行。
 *
 * 旧版只用一条 Toast 告知，划走了就再也不知道卡在哪，于是整页看起来「开了但没用」。
 * 这里做成可点行：点一下先让框架弹授权，不弹就直接把用户送进管理器。
 */
@Composable
private fun ScopeGapRow(missing: List<String>, onClick: () -> Unit) {
    if (missing.isEmpty()) return
    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
    SetRowClickable(
        Icons.Filled.Warning,
        "还差作用域：${missing.joinToString()}",
        "框架不会把模块注入未勾选的应用；勾选后需重启该应用（或重启手机）才生效",
        "去勾选",
        onClick = onClick,
        danger = true,
    )
}

private fun powerTargetLabel(target: String): String = when (target) {
    QuroModuleConfig.PowerTarget.QURO -> "Zorv AI"
    QuroModuleConfig.PowerTarget.GEMINI -> "Gemini"
    else -> "厂商助手"
}

/**
 * 「设为默认数字助理」的一步一反馈弹窗。
 *
 * 旧实现点一下就去调 RoleManager，ColorOS 上常常既不弹框也不跳页，用户只看到「没反应」。
 * 现在把三步拆开、每步都有明确结果，并且先列出诊断（角色是否可申请、设置页是否存在、
 * 有没有 Root/Shizuku），用户一眼能看到是哪一环被 ROM 挡住了。
 */
@Composable
private fun AssistantSetupDialog(
    ctx: Context,
    showToast: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val activity = ctx as? android.app.Activity
    val lines = remember { com.ai.assistance.quro.core.assistant.AssistantRole.diagnostics(ctx) }
    var result by remember { mutableStateOf<String?>(null) }
    val roleHeld = remember { com.ai.assistance.quro.core.assistant.AssistantRole.isAssistant(ctx) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (roleHeld) "默认数字助理：已持有" else "设为默认数字助理", fontSize = 16.sp) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "ColorOS / HyperOS 的长按电源键是系统写死交给厂商助手的，只有第 ④ 步能真正改掉它" +
                        "（需要 LSPosed 的 system 作用域）；①②③ 是系统「默认数字助理」通道，主要影响耳机键与原生助手唤起。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Accent,
                )
                Spacer(Modifier.height(8.dp))
                lines.forEach {
                    Text("· $it", fontSize = 11.sp, lineHeight = 16.sp, color = Muted)
                }
                Spacer(Modifier.height(10.dp))
                StepAction("① 弹出系统申请框（先试这个）") {
                    val ok = activity?.let {
                        com.ai.assistance.quro.core.assistant.AssistantRole.request(it)
                    } ?: false
                    result = if (ok) {
                        "已弹出系统对话框，点「允许 / 设为默认」即可。若框一闪而过，说明该 ROM 关掉了 ROLE_ASSISTANT，改用 ② 或 ③。"
                    } else {
                        "系统没有弹出对话框：该 ROM 不支持角色申请，或当前页面不是 Activity。请改用 ② / ③。"
                    }
                }
                StepAction("② 打开系统「数字助理 / 默认应用」设置") {
                    val ok = com.ai.assistance.quro.core.assistant.AssistantRole.openSystemSettings(ctx)
                    result = if (ok) {
                        "已打开设置页，请在列表里选择 Zorv AI。找不到入口就用 ③ 直接写入。"
                    } else {
                        "本机没有任何可打开的数字助理设置页。"
                    }
                }
                StepAction("③ 用 Root / Shizuku 直接写入系统配置") {
                    val (ok, message) = com.ai.assistance.quro.core.assistant.AssistantRole.applyViaPrivilege(ctx)
                    result = message
                    if (ok) showToast("已写入系统助手配置")
                }
                StepAction("④ 长按电源键目标设为 Zorv AI（需 system 作用域）") {
                    if (!QuroModuleServiceState.isConnected()) {
                        result = "框架服务未连接，无法写入电源键目标。这一项需要 LSPosed/Vector 作用域，但不影响 ①②③。"
                    } else if (QuroModulePrefs.setPowerTarget(QuroModuleConfig.PowerTarget.QURO)) {
                        val st = QuroModuleServiceState.status()
                        val systemLoaded = st.isScopeActive(QuroModuleConfig.SYSTEM)
                        val systemScoped = QuroModuleConfig.SYSTEM in st.scope
                        result = when {
                            systemLoaded ->
                                "已写入。system_server 已加载本模块 —— 重启手机后长按电源键会直接进 Zorv AI。"
                            systemScoped ->
                                "已写入。system 作用域已勾选，但 system_server 还没加载模块：重启手机后生效。"
                            else ->
                                "已写入。但 system 作用域没勾选，电源键仍然由厂商助手接管：先去「模块启用与作用域」勾上 system，再重启手机。"
                        }
                    } else {
                        result = "写入电源键目标失败，请检查框架服务。"
                    }
                }
                result?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = Accent)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun StepAction(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 13.sp, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * 设置首页上的「系统助手接管 / Gemini / 厂商助手兼容入口」三组开关。
 *
 * 只有框架服务确实连接后才渲染开关；未连接时保留「数字助理应用」这个系统设置入口
 * （它是普通 Android 设置跳转，不需要 LSPosed），并给一行指路到增强页。
 */
@Composable
fun QuroModuleSettingsGroups(
    onOpenEnhance: () -> Unit,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
) {
    val ctx = LocalContext.current
    var connected by remember { mutableStateOf(QuroModuleServiceState.isConnected()) }
    DisposableEffect(Unit) {
        val refresh = { connected = QuroModuleServiceState.isConnected() }
        QuroModuleServiceState.addListener(refresh)
        refresh()
        onDispose { QuroModuleServiceState.removeListener(refresh) }
    }

    GroupCaption("系统助手接管")
    var assistantDialog by remember { mutableStateOf(false) }
    var roleHeld by remember { mutableStateOf(com.ai.assistance.quro.core.assistant.AssistantRole.isAssistant(ctx)) }
    val showToast: (String) -> Unit = { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
    SetGroup {
        SetRowClickable(
            Icons.Filled.SupportAgent,
            "数字助理应用",
            if (roleHeld) {
                "已设为默认数字助理：长按电源键 / 耳机键会唤起 Zorv AI"
            } else {
                "点开后逐步操作：系统申请框 → 设置页 → 特权直接写入，每步都有结果提示"
            },
            if (roleHeld) "已启用" else "去设置",
            onClick = { assistantDialog = true },
            scaled = scaled,
        )
        if (!connected) {
            HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            SetRowClickable(
                Icons.Filled.Link,
                "框架服务未连接",
                "下面的开关需要 LSPosed / Vector 把本模块的配置通道推给应用后才能写入；" +
                    "请先在框架里启用本模块并把作用域打开",
                "去查看",
                onClick = onOpenEnhance,
                scaled = scaled,
            )
        }
    }

    if (assistantDialog) {
        AssistantSetupDialog(ctx, showToast) {
            assistantDialog = false
            roleHeld = com.ai.assistance.quro.core.assistant.AssistantRole.isAssistant(ctx)
        }
    }

    GroupCaption("Gemini 与一圈即搜")
    SetGroup {
        QuroModuleConfig.GEMINI_SWITCHES.forEachIndexed { index, def ->
            if (index > 0) {
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
            }
            QuroModuleSwitchRow(def, scaled)
        }
    }

    GroupCaption("小布 / 超级小爱兼容入口")
    SetGroup {
        QuroModuleConfig.ASSISTANT_SWITCHES
            .filter { it.group == "小布 / 超级小爱兼容入口" }
            .forEachIndexed { index, def ->
                if (index > 0) {
                    HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                }
                QuroModuleSwitchRow(def, scaled)
            }
        HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
        QuroModuleSwitchRow(
            QuroModuleConfig.ASSISTANT_SWITCHES.first { it.key == QuroModuleConfig.Keys.AUTO_DEFAULT_ASSISTANT },
            scaled,
        )
    }
}

/** 单个 RemotePreferences 开关行；连接状态与选中态都跟随框架回调刷新。 */
@Composable
private fun QuroModuleSwitchRow(
    def: QuroModuleConfig.SwitchDef,
    scaled: (Int) -> androidx.compose.ui.unit.TextUnit,
) {
    val ctx = LocalContext.current
    val installed = remember { QuroModuleConfig.installedPackages(ctx.packageManager) }
    var connected by remember { mutableStateOf(QuroModuleServiceState.isConnected()) }
    var checked by remember { mutableStateOf(QuroModulePrefs.isEnabled(def.key)) }
    // 只在「已开启」时算缺失：关着的开关不该在页面上占一行红字。
    var gap by remember { mutableStateOf<List<String>>(emptyList()) }

    DisposableEffect(Unit) {
        val refresh = {
            connected = QuroModuleServiceState.isConnected()
            checked = QuroModulePrefs.isEnabled(def.key)
            gap = if (checked) QuroModuleServiceState.status().unmetScope(def, installed) else emptyList()
            Unit
        }
        QuroModuleServiceState.addListener(refresh)
        refresh()
        onDispose { QuroModuleServiceState.removeListener(refresh) }
    }

    SetRow(
        icon = Icons.Filled.Extension,
        name = def.title,
        sub = def.summary,
        checked = checked,
        onToggle = {
            if (!connected) {
                Toast.makeText(ctx, "框架服务未连接，开关不可修改", Toast.LENGTH_SHORT).show()
                return@SetRow
            }
            val next = !checked
            if (QuroModulePrefs.setEnabled(def.key, next)) {
                checked = next
                gap = if (next) QuroModuleServiceState.status().unmetScope(def, installed) else emptyList()
                if (gap.isNotEmpty()) {
                    Toast.makeText(ctx, "已开启；还需勾选 ${gap.joinToString()}", Toast.LENGTH_SHORT).show()
                    QuroModuleServiceState.requestMissingScope(installed) { ok, message ->
                        Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                        if (ok) {
                            gap = QuroModuleServiceState.status().unmetScope(def, installed)
                        } else {
                            openFrameworkManager(ctx) { msg ->
                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } else {
                Toast.makeText(ctx, "写入框架配置失败", Toast.LENGTH_SHORT).show()
            }
        },
        enabled = connected,
        scaled = scaled,
    )
    ScopeGapRow(gap) {
        QuroModuleServiceState.requestMissingScope(installed) { ok, message ->
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            if (ok) {
                gap = QuroModuleServiceState.status().unmetScope(def, installed)
            } else {
                openFrameworkManager(ctx) { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
            }
        }
    }
}

private fun openFrameworkManager(ctx: Context, showToast: (String) -> Unit) {
    // 判定与跳转统一收敛到 QuroLSPosed：先 Launcher Intent，再拨号快捷入口，最后应用详情页。
    // Vector 管理器（org.matrix.vector.manager）常隐藏图标，拨号入口往往是唯一可用路径。
    val opened = QuroLSPosed.openManager(ctx)
    if (!opened) {
        showToast(
            "未检测到 Vector / LSPosed 管理器；若已刷入 Vector 2.2，可从开机通知安装管理器，" +
                "或拨号 *#*#832867#*#* 打开"
        )
    }
}
