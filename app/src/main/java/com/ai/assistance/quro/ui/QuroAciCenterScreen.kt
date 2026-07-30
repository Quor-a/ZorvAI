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
package ai.aci.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException

/** ACI 请求：能力 id + 参数(JSON) + 用户确认标记 */
data class ACIRequest(val capability: String, val params: String, val userConfirmed: Boolean)

/** ACI 响应：code(0=成功) + 结果(JSON) + 错误信息 */
data class ACIResponse(val code: Int, val result: String, val error: String?)

/** 能力声明 */
data class Capability(val id: String, val description: String, val requireUserConfirm: Boolean)

/** 控制方调用被控方能力的 AIDL 接口（由 aci-core AAR 提供，此处仅为自写依赖示意） */
interface IACIService {
    fun call(req: ACIRequest): ACIResponse
    fun listCapabilities(): List<Capability>
}

/** 被控方 BaseACIService 骨架：继承后声明能力、实现 onCall */
abstract class BaseACIService : Service() {
    abstract fun onCreateCapabilities(): List<Capability>
    abstract fun onCall(req: ACIRequest): ACIResponse

    private val binder = object : IACIService.Stub() {
        override fun call(req: ACIRequest): ACIResponse {
            // 危险能力务必校验 userConfirmed（服务端兜底，防被绕过）
            val caps = onCreateCapabilities()
            val cap = caps.firstOrNull { it.id == req.capability }
                ?: return ACIResponse(-1, "{}", "未知能力：${'$'}{req.capability}")
            if (cap.requireUserConfirm && !req.userConfirmed)
                return ACIResponse(-2, "{}", "需要用户确认")
            return onCall(req)
        }
        override fun listCapabilities(): List<Capability> = onCreateCapabilities()
    }

    override fun onBind(intent: Intent?): IBinder = binder
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

二、被控方接入 5 步
1) 依赖 aci-core AAR（提供 ai.aci.core.*：IACIService / ACIRequest / ACIResponse / Capability / BaseACIService）。
2) AndroidManifest 声明权限与 Service（见第三节）。
3) 写一个 Service 继承 ai.aci.core.BaseACIService，重写 onCreateCapabilities() 声明能力、onCall() 处理逻辑。
4) 在 Application/Activity 里把 Service 跑起来（或被 ACI 唤醒广播拉起，见第四节）。
5) 打包安装 → 在 Zorv AI「ACT 关联启动」点刷新即可发现；或本页「按名称搜索」找到后「注册并启动」。

三、AndroidManifest 配置
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

四、Kotlin 代码示例
class MyAciService : BaseACIService() {
    override fun onCreateCapabilities(): List<Capability> = listOf(
        Capability.create("echo")
            .setDescription("回显文本")
            .addParam("text", "string", true, "要回显的内容"),
        Capability.create("danger_action")
            .setDescription("危险操作示例")
            .setUserConfirm(true)   // 需用户在控制方确认
    )
    override fun onCall(req: ACIRequest, res: ACIResponse) {
        when (req.capability) {
            "echo" -> {
                val text = req.params.getString("text") ?: ""
                res.success("你发了：${'$'}text")
            }
            "danger_action" -> {
                // 服务端兜底：基类不会自动拦截 isRequireUserConfirm，
                // 必须自己查 user_confirmed 再执行真实动作。
                if (!req.params.getBoolean("user_confirmed", false)) {
                    res.denied("需要用户确认")
                    return
                }
                res.success("已执行危险动作")
            }
            else -> res.notFound("未知能力：${'$'}{req.capability}")
        }
    }
}
// 被控 App 处于 stopped 态时，注册会发 ACI 唤醒广播（FLAG_INCLUDE_STOPPED_PACKAGES）拉起进程；
// 你也可在 MainActivity 里直接 startService(MyAciService::class.java) 预热。

五、能力声明规范
• id 用小写蛇形（如 open_door）；description 一句话说清用途。
• 每个参数 addParam(name, type, required, desc)；type ∈ string/int/boolean/float。
• 危险能力务必 setUserConfirm(true)，并在 onCall 内校验 user_confirmed（服务端兜底，防被绕过）。

六、控制方如何调用（供你联调）
• Zorv AI 用 aci_call 调用：aci_call(packageName, capability, params)。
• 危险能力调用前控制方会弹确认框；被控方 onCall 仍要查 user_confirmed。

七、打包与测试清单
☐ AAR 依赖正确，BaseACIService 可继承
☐ Manifest 权限 + Service + intent-filter + queries 齐全
☐ 有可启动 Activity 与有效图标
☐ 安装后在 Zorv AI ACT 关联启动「刷新」可见
☐ echo 类能力能正常返回值
☐ 危险能力在两侧都做了确认

八、排障铁律
• aci_list 为空：别用 Shizuku / dumpsys / ROOT 排查，确认你已装且 Service 带 ACTION_BIND。
• 返回 503：绑定生命周期抖动，框架自动重绑，直接重试即可。
• 能力不出现：确认 onCreateCapabilities 正确返回，且 Service 已运行（stopped 态会被唤醒广播拉起）。
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
}

@Composable
private fun AciAppCard(
    s: QuroAciManager.AciAppStatus,
    onRebind: () -> Unit,
    onLaunch: () -> Unit,
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
