package com.ai.assistance.quro.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.ai.assistance.quro.core.aci.QuroAciManager
import com.ai.assistance.quro.lanui.LanScreen
import com.ai.assistance.quro.lanui.LanUiModel
import com.ai.assistance.quro.lanui.LanUiScreen
import org.json.JSONObject
import androidx.compose.ui.window.Dialog
import com.ai.assistance.quro.core.tools.QuroDownloadUtil
import com.ai.assistance.quro.ui.theme.Card as PaperCard
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * ACI 被控方（第三方 App）开发手册 —— 接 Zorv AI 作为控制方时，被调方应如何开发。
 * 放在 ACI 管理中心内，供第三方 App 开发者直接照做。
 */
/**
 * 被控方「自写部分」依赖模板：ACI 核心接口 + BaseACIService 骨架。
 * 开发者可把它直接保存到本地作为接入起点（替代手写样板）。
 */
private val ACI_STUB_SOURCE = """
package com.example.aci

import ai.aci.core.*
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * 最小可运行被控端 Service（依赖 aci-core AAR）。
 * 把本文件放进你的模块、改包名与能力即可编译。
 * 注意：BaseACIService / Capability / ACIResponse / ACIError 都由 AAR 提供，不要自己重写。
 */
class MyAciService : BaseACIService() {

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        caps.add(
            Capability.create("echo", "回显文本")   // 第 1 参=id，第 2 参=描述（不是版本号！）
                .addParam("text", "string", true, "要回显的内容")
                .addResult("reply", "string", "回显结果")
        )
    }

    override fun onCall(req: ACIRequest?): ACIResponse {
        val capability = req?.capability ?: return ACIResponse.error(ACIError.REQUEST_NULL, "null")
        return when (capability) {
            "echo" -> {
                val text = req.params?.getString("text") ?: ""
                ACIResponse.success(Bundle()).putResult("reply", "你发了：${'$'}text")
            }
            else -> ACIResponse.error(ACIError.CAPABILITY_NOT_FOUND, "未知能力：${'$'}capability")
        }
    }
}
"""

private val ACI_DEV_DOC = """
══════════════════════════════════════════
ACI 被控方（第三方 App）开发手册
══════════════════════════════════════════
一、ACI 是什么
• 本地、无 Root 的 App 间 AIDL 调用框架；不依赖 Shizuku / dumpsys / ROOT / 无障碍 / 设备管理员。
• 控制方（AI 中枢，如 Zorv AI）发现并调用第三方 App 暴露的能力；你作为「被控方」按本协议暴露能力。
• 一次调用 = 一个 capability（能力）：带 id、描述、参数清单、是否需用户确认。

二、依赖获取（aci-core AAR）
• AAR 下载（GitHub Release，免登录）：
  https://github.com/Quor-a/ZorvAI/releases/download/v1.0.6/aci-core-release.aar
• Gradle 依赖：implementation(files("libs/aci-core-release.aar"))（把 AAR 放进模块 libs/）
• ACI 核心库独立开源分支：https://github.com/Quor-a/ZorvAI/tree/aci-core
• 完整开发者手册（网页版）：https://github.com/Quor-a/ZorvAI/blob/main/docs/ACI_DEVELOPER_GUIDE.md

三、被控方接入 5 步
1) 依赖 aci-core AAR（提供 ai.aci.core.*：IACIService / ACIRequest / ACIResponse / Capability / BaseACIService / ACIError）。
2) AndroidManifest 声明 3 个 <permission> 定义 + uses-permission + Service（见第四节）。⚠️ 3 个权限定义必须写，否则 Service 的 android:permission 指向不存在的权限 → 绑定必失败。
3) 写一个 Service 继承 ai.aci.core.BaseACIService，重写 onCreateCapabilities(caps) 声明能力、onCall(req) 返回 ACIResponse。
4) 在 Application/Activity 里把 Service 跑起来（或被 ACI 唤醒广播拉起，见第五节）。
5) 打包安装 → 在 Zorv AI「ACT 关联启动」点刷新即可发现；或本页「按名称搜索」找到后「注册并启动」。

四、AndroidManifest 配置
<!-- ① 必须声明 3 个权限定义（CALL 普通 / DISCOVER 普通 / CALL_DANGEROUS 危险）。缺任一，绑定会被系统拒绝 -->
<permission android:name="ai.aci.permission.CALL" android:protectionLevel="normal" />
<permission android:name="ai.aci.permission.DISCOVER" android:protectionLevel="normal" />
<permission android:name="ai.aci.permission.CALL_DANGEROUS" android:protectionLevel="dangerous" />

<uses-permission android:name="ai.aci.permission.CALL" />
<uses-permission android:name="ai.aci.permission.DISCOVER" />
<!-- 包可见性（Android 11+）：让控制方能发现你 -->
<queries>
  <intent>
    <action android:name="ai.aci.core.ACTION_BIND" />
  </intent>
</queries>
<service
    android:name=".MyAciService"
    android:exported="true"
    android:permission="ai.aci.permission.CALL">
  <intent-filter>
    <action android:name="ai.aci.core.ACTION_BIND" />
  </intent-filter>
</service>
<!-- 必须有一个可启动的 Activity + 有效图标，否则装了也无桌面入口、启不动。
     图标不要引用已删除的 @android:drawable/* 资源。 -->

五、Kotlin 代码示例
import ai.aci.core.*
class MyAciService : BaseACIService() {
    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        caps.add(
            Capability.create("echo", "回显文本")   // 第 1 参=id，第 2 参=描述（不是版本号！）
                .addParam("text", "string", true, "要回显的内容")
                .addResult("reply", "string", "回显结果")
        )
        caps.add(
            Capability.create("danger_action", "危险操作示例")
                .setUserConfirm(true)   // 需用户在控制方确认
                .addResult("ok", "boolean", "是否执行成功")
        )
    }
    override fun onCall(req: ACIRequest?): ACIResponse {
        val capability = req?.capability ?: return ACIResponse.error(ACIError.REQUEST_NULL, "null")
        return when (capability) {
            "echo" -> {
                val text = req.params?.getString("text") ?: ""
                ACIResponse.success(android.os.Bundle())
                    .putResult("reply", "你发了：${'$'}text")
            }
            "danger_action" -> {
                // 服务端兜底：危险能力务必在 onCall 内校验 user_confirmed，防被绕过
                val confirmed = req.params?.getBoolean("user_confirmed", false) ?: false
                if (!confirmed) return ACIResponse.error(ACIError.BAD_REQUEST, "需要用户确认")
                ACIResponse.success(android.os.Bundle()).putResult("ok", true)
            }
            else -> ACIResponse.error(ACIError.CAPABILITY_NOT_FOUND, "未知能力：${'$'}capability")
        }
    }
}
// 被控 App 处于 stopped 态时，控制方注册会发 ACI 唤醒广播（FLAG_INCLUDE_STOPPED_PACKAGES）拉起进程；
// 你也可在 MainActivity 里直接 startService(MyAciService::class.java) 预热。

六、能力声明规范
• id 用小写蛇形（如 open_door）；Capability.create(id, description) 第 2 参是「描述」，不是版本号。
• 每个参数 addParam(name, type, required, desc)；type ∈ string/int/boolean/float。
• 每个返回 addResult(name, type, desc)。
• 危险能力务必 setUserConfirm(true)，并在 onCall 内校验 user_confirmed（服务端兜底，防被绕过）。

七、控制方如何调用（供你联调）
• Zorv AI 用 aci_call 调用：aci_call(packageName, capability, params)。
• 危险能力调用前控制方会弹确认框；被控方 onCall 仍要查 user_confirmed。

八、打包与测试清单
☐ AAR 依赖正确，BaseACIService 可继承
☐ Manifest 声明 3 个 <permission> + 2 个 uses-permission + Service + intent-filter + queries
☐ 有可启动 Activity 与有效图标
☐ 安装后在 Zorv AI ACT 关联启动「刷新」可见
☐ echo 类能力能正常返回值
☐ 危险能力在两侧都做了确认

九、排障铁律
• aci_list 为空：别用 Shizuku / dumpsys / ROOT 排查，确认你已装且 Service 带 ACTION_BIND。
• 返回 503：绑定生命周期抖动，框架自动重绑，直接重试即可。
• 能力不出现：确认 onCreateCapabilities 用「参数式 caps.add(...)」正确填充，且 Service 已运行（stopped 态会被唤醒广播拉起）。
• 绑定直接失败/秒拒：99% 是 Manifest 漏写 <permission> 定义（CALL / DISCOVER / CALL_DANGEROUS），补上即可。
"""

/**
 * ACI 管理中心：作为 ACI 控制方（AI 中枢），浏览已发现的第三方 ACI App、
 * 查看绑定状态与暴露的能力清单，并支持手动注册 / 搜索 / 启动 / 重绑。
 *
 * 结构设计（对齐 App 既有「纸感」设计系统）：
 * - 01 添加 ACI 应用：包名/名称统一入口，支持「搜索」（模糊匹配本机应用）与「按包名注册并启动」（关联启动）；
 *   搜索结果逐一提供「启动 / 注册并启动」。即把「手动注册」与「按名称搜索」合体为单一流程。
 * - 02 已发现的 ACI 应用：列出已发现 App 的绑定态 + 能力清单，每张卡片可「重绑」与「启动」（手动启动）。
 * - 03 开发者文档：被控方接入手册（可折叠）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroAciCenterScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val cs = MaterialTheme.colorScheme
    val mgr = remember { QuroAciManager.getInstance() }
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf(mgr.getAppStatuses()) }
    var pkgInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<QuroAciManager.InstalledApp>>(emptyList()) }

    // 受控端「控制台」SDUI 渲染：复用既有 LanUiScreen 渲染 console_ui 快照，不新增业务逻辑
    var consolePkg by remember { mutableStateOf<String?>(null) }
    var consoleScreen by remember { mutableStateOf<LanScreen?>(null) }
    var consoleLoading by remember { mutableStateOf(false) }
    var consoleError by remember { mutableStateOf<String?>(null) }

    fun openConsole(pkg: String) {
        consolePkg = pkg
        consoleLoading = true
        consoleError = null
        consoleScreen = null
        scope.launch {
            val resp = withContext(Dispatchers.IO) { mgr.call(pkg, "console_ui", android.os.Bundle()) }
            if (resp.isSuccess) {
                val snap = resp.result?.getString("snapshot") ?: ""
                consoleScreen = runCatching { LanUiModel.parse(JSONObject(snap)) }.getOrElse {
                    consoleError = "控制台 JSON 解析失败：${it.message}"
                    null
                }
            } else {
                consoleError = "打开控制台失败（错误码=${resp.errorCode}）：${resp.errorMessage}"
            }
            consoleLoading = false
        }
    }

    fun consoleAction(action: String, payload: Map<String, String>) {
        val pkg = consolePkg ?: return
        scope.launch {
            val b = android.os.Bundle().apply {
                putString("action", action)
                putString("payload", JSONObject(payload).toString())
            }
            withContext(Dispatchers.IO) { mgr.call(pkg, "console_action", b) }
            val r2 = withContext(Dispatchers.IO) { mgr.call(pkg, "console_ui", android.os.Bundle()) }
            if (r2.isSuccess) {
                val snap = r2.result?.getString("snapshot") ?: ""
                consoleScreen = runCatching { LanUiModel.parse(JSONObject(snap)) }.getOrNull()
            }
        }
    }

    fun reload() { statuses = mgr.getAppStatuses() }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ACT 关联启动") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = {
                        busy = true
                        mgr.refresh()
                        scope.launch {
                            delay(800)
                            reload()
                            busy = false
                            Toast.makeText(ctx, "已刷新", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "ACT 关联启动（底层协议 ACI，Agent Capability Interface）是本地无 Root 的 App 间 AIDL 调用框架，可让 AI 控制支持协议的第三方 App。" +
                    "下面可手动添加应用、查看已发现应用的能力，并手动启动或重绑。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
            HorizontalDivider()

            // ── 01 添加 ACI 应用（手动注册 + 按名称搜索 合体）──────────────────────
            ChapterLabel("01", "添加 ACI 应用")
            SetGroup {
                Column {
                    UnderlineField(
                        label = "包名或应用名",
                        value = pkgInput,
                        onValueChange = { pkgInput = it },
                        placeholder = "如 com.example.chat 或 微信",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryButton(
                            text = "搜索",
                            onClick = {
                                searched = true
                                val kw = pkgInput.trim()
                                // 后台线程执行（getInstalledApplications + loadLabel 在主线程会 ANR）
                                scope.launch {
                                    searchResults = withContext(Dispatchers.IO) {
                                        mgr.searchInstalledApps(kw)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Line, RoundedCornerShape(12.dp))
                                .clickable {
                                    val pkg = pkgInput.trim()
                                    if (pkg.isEmpty()) {
                                        Toast.makeText(ctx, "请输入包名", Toast.LENGTH_SHORT).show()
                                        return@clickable
                                    }
                                    val ok = mgr.registerPackage(pkg)
                                    mgr.launchApp(pkg)
                                    scope.launch { delay(800); reload() }
                                    Toast.makeText(
                                        ctx,
                                        if (ok) "已注册并启动：$pkg" else "未找到 $pkg 的 ACI 服务",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (ok) pkgInput = ""
                                }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("按包名注册并启动", fontSize = 15.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (searchResults.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        searchResults.forEachIndexed { idx, app ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.appName, fontWeight = FontWeight.Medium)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                TextButton(onClick = {
                                    val ok = mgr.launchApp(app.packageName)
                                    Toast.makeText(
                                        ctx,
                                        if (ok) "已启动：${app.appName}" else "启动失败：${app.packageName}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }) { Text("启动") }
                                TextButton(onClick = {
                                    mgr.registerPackage(app.packageName)
                                    val ok = mgr.launchApp(app.packageName)
                                    scope.launch { delay(800); reload() }
                                    Toast.makeText(
                                        ctx,
                                        if (ok) "已注册并启动：${app.appName}" else "启动失败：${app.packageName}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }) { Text("注册并启动") }
                            }
                            if (idx < searchResults.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    } else if (searched) {
                        Spacer(Modifier.height(4.dp))
                        InfoBox("未找到匹配的应用。可确认名称/包名是否正确，或直接用「按包名注册并启动」。", tone = Muted)
                    }
                }
            }

            // ── 02 已发现的 ACI 应用（含手动启动）────────────────────────────────
            ChapterLabel("02", "已发现的 ACI 应用")
            if (statuses.isEmpty()) {
                InfoBox(
                    "未发现任何 ACI App。安装支持 ACI 协议的第三方 App 后点右上「刷新」；" +
                        "或在上方「添加 ACI 应用」输入包名手动注册。\n\n" +
                        "ACI 是本地无 Root 的 App 间 AIDL 框架，列表为空时【禁止】用 dumpsys / Shizuku / ROOT 排查。"
                )
            } else {
                statuses.forEach { s ->
                    AciAppCard(
                        s,
                        onOpenConsole = { openConsole(s.packageName) },
                        onRebind = {
                            mgr.rebind(s.packageName)
                            scope.launch { delay(600); reload() }
                        },
                        onLaunch = {
                            val ok = mgr.launchApp(s.packageName)
                            Toast.makeText(
                                ctx,
                                if (ok) "已启动：${s.appName}" else "启动失败：${s.packageName}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                    )
                }
            }

            // ── 03 开发者文档（正确开发姿势 + 全链路踩坑）────────────────────────
            ChapterLabel("03", "开发者文档")
            var showDevDoc by remember { mutableStateOf(false) }
            SetGroup {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { showDevDoc = !showDevDoc }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.MenuBook, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text("ACI 被控方接入手册", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(
                            if (showDevDoc) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            null, Modifier.size(16.dp), tint = Muted
                        )
                    }
                    if (showDevDoc) {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                        ) {
                            Text(ACI_DEV_DOC, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val r = QuroDownloadUtil.saveTextToDownloads(ctx, "aci_core_stub.kt", "text/plain", ACI_STUB_SOURCE)
                                Toast.makeText(ctx, if (r.startsWith("OK:")) "已保存依赖模板到 Download/Quro/aci_core_stub.kt" else r, Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存依赖模板") }
                        Button(
                            onClick = {
                                val r = QuroDownloadUtil.saveTextToDownloads(ctx, "ACI_被控方接入手册.md", "text/markdown", ACI_DEV_DOC)
                                Toast.makeText(ctx, if (r.startsWith("OK:")) "已保存开发者文档到 Download/Quro/" else r, Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("下载开发者文档") }
                    }
                }
            }
        }
    }
    // 受控端「控制台」SDUI 弹层：复用 LanUiScreen 渲染 console_ui 快照
    if (consolePkg != null) {
        Dialog(onDismissRequest = { consolePkg = null; consoleScreen = null; consoleError = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.92f).wrapContentHeight()
            ) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            consoleScreen?.title ?: "控制台",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { consolePkg = null; consoleScreen = null; consoleError = null }) {
                            Icon(Icons.Filled.Close, "关闭")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        consoleLoading -> CircularProgressIndicator()
                        consoleError != null -> Text(consoleError ?: "", color = MaterialTheme.colorScheme.error)
                        else -> LanUiScreen(consoleScreen, onAction = { a, p -> consoleAction(a, p) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AciAppCard(
    s: QuroAciManager.AciAppStatus,
    onRebind: () -> Unit,
    onLaunch: () -> Unit,
    onOpenConsole: (String) -> Unit,
) {
    val boundColor = Color(0xFF34C759)
    val unboundColor = Color(0xFFFF3B30)
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PaperCard),
        border = if (s.bound) null else BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.appName, fontWeight = FontWeight.Bold)
                    Text(s.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                val (label, color) = if (s.bound) "已绑定" to boundColor else "未绑定" to unboundColor
                Box(
                    Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) { Text(label, color = color, style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.height(10.dp))
            Text("能力（${s.capabilities.size}）", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(2.dp))
            if (s.capabilities.isEmpty()) {
                Text(
                    "（无能力：可能尚未绑定，或该 App 未声明能力）",
                    color = Color.Gray, style = MaterialTheme.typography.bodySmall
                )
            } else {
                s.capabilities.forEach { c ->
                    val danger = if (c.isRequireUserConfirm) "  ⚠️需确认" else ""
                    Text("• ${c.id}：${c.description}$danger", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRebind) { Text("重绑") }
                TextButton(onClick = onLaunch) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("启动")
                }
                if (s.capabilities.any { it.id == "console_ui" }) {
                    TextButton(onClick = { onOpenConsole(s.packageName) }) {
                        Icon(Icons.Filled.Dashboard, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("打开控制台")
                    }
                }
                if (s.lastSeen > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "最近活动 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(s.lastSeen))}",
                        color = Color.Gray, style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
