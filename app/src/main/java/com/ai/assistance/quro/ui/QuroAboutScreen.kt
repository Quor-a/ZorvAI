package com.ai.assistance.quro.ui

import com.ai.assistance.quro.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Copyright
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.AccentSoft
import com.ai.assistance.quro.ui.theme.Muted
import com.ai.assistance.quro.ui.theme.Line

/**
 * 关于 Zorv AI（纸感重设计）：品牌 hero + SetGroup/SetRowClickable 分组。
 * 「项目地址」「在 GitHub 点个 Star」跳转到开源仓库；
 * 「开源许可声明」弹出本应用所用第三方依赖的许可证清单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroAboutScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val versionName = remember { BuildConfig.VERSION_NAME }
    val scope = rememberCoroutineScope()
    val repoUrl = "https://github.com/Quor-a/ZorvAI"
    var showLicense by remember { mutableStateOf(false) }
    var showPermissionStatement by remember { mutableStateOf(false) }
    var showUserAgreement by remember { mutableStateOf(false) }
    var updateDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var updateVersion by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val openUrl: (String) -> Unit = { url ->
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(ctx, "无法打开链接：$url", Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "关于 Zorv AI",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // —— 品牌 hero ——
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentSoft)
                    .border(1.dp, Line, RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Zorv AI",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = Accent,
                )
                Text(
                    "开源 AI 助手 · 原创构建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }

            GroupCaption("更新与支持")
            SetGroup {
                SetRowClickable(
                    icon = Icons.Filled.Refresh,
                    name = "检查更新",
                    sub = if (checking) "检查中…" else "当前版本 v$versionName",
                    onClick = {
                        if (checking) return@SetRowClickable
                        checking = true
                        scope.launch(Dispatchers.IO) {
                            var latest: String? = null
                            var htmlUrl: String? = null
                            var errMsg: String? = null
                            // 1) 先试 GitHub（国内网络常不可达）
                            val gh = runCatching { fetchLatestRelease("https://api.github.com/repos/Quor-a/ZorvAI/releases/latest") }
                            if (gh.isSuccess && gh.getOrNull()?.first?.isNotBlank() == true) {
                                latest = gh.getOrNull()!!.first
                                htmlUrl = gh.getOrNull()!!.second
                            } else {
                                // 2) GitHub 不可达 → 回退 Gitee 镜像
                                val ge = runCatching { fetchLatestRelease("https://gitee.com/api/v5/repos/ZorvAI/ZorvAI/releases/latest") }
                                if (ge.isSuccess && ge.getOrNull()?.first?.isNotBlank() == true) {
                                    latest = ge.getOrNull()!!.first
                                    htmlUrl = ge.getOrNull()!!.second.ifBlank { "https://gitee.com/ZorvAI/ZorvAI/releases" }
                                } else {
                                    errMsg = gh.exceptionOrNull()?.message ?: ge.exceptionOrNull()?.message ?: "未知错误"
                                }
                            }
                            withContext(Dispatchers.Main) {
                                checking = false
                                if (latest != null) {
                                    val lv = latest!!.removePrefix("v").trim()
                                    if (isVersionNewer(lv, versionName)) {
                                        updateVersion = lv
                                        updateDialog = Pair(
                                            htmlUrl ?: "$repoUrl/releases/latest",
                                            "https://gitee.com/ZorvAI/ZorvAI/releases"
                                        )
                                        Toast.makeText(ctx, "发现新版本 v$lv，请选择下载镜像", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(ctx, "已是最新版本 v$versionName", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(ctx, "检查更新失败：$errMsg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                )
                SetRowClickable(
                    icon = Icons.Filled.Link,
                    name = "项目地址",
                    sub = repoUrl,
                    onClick = { openUrl(repoUrl) },
                )
                SetRowClickable(
                    icon = Icons.Filled.Star,
                    name = "在 GitHub 点个 Star",
                    sub = "如果喜欢 Zorv AI，欢迎点个 Star ⭐",
                    onClick = { openUrl("$repoUrl/stargazers") },
                )
                SetRowClickable(
                    icon = Icons.Filled.Description,
                    name = "开源许可声明",
                    sub = "查看本应用所用第三方依赖的许可证",
                    onClick = { showLicense = true },
                )
            }

            GroupCaption("项目信息")
            SetGroup {
                SetRowClickable(
                    icon = Icons.Filled.Code,
                    name = "开发者",
                    sub = "Zorv AI",
                    onClick = { },
                )
                SetRowClickable(
                    icon = Icons.Filled.Copyright,
                    name = "版权",
                    sub = "© 2025 - 2026 Zorv AI. 保留所有权利。",
                    onClick = { },
                )
            }

            GroupCaption("法律与合规")
            SetGroup {
                SetRowClickable(
                    icon = Icons.Filled.Security,
                    name = "权限使用声明",
                    sub = "本应用所申请权限的用途说明",
                    onClick = { showPermissionStatement = true },
                )
                SetRowClickable(
                    icon = Icons.AutoMirrored.Filled.Article,
                    name = "用户使用协议",
                    sub = "使用本应用前请阅读并了解",
                    onClick = { showUserAgreement = true },
                )
            }
        }
    }

    if (showLicense) {
        val licenses = listOf(
            Triple("Zorv AI（本应用）", "Apache-2.0", "源码以 Apache-2.0 开源，见仓库 LICENSE 文件"),
            Triple("AndroidX / Jetpack", "Apache-2.0", "Google"),
            Triple("Jetpack Compose", "Apache-2.0", "Google"),
            Triple("Material Components", "Apache-2.0", "Google"),
            Triple("OkHttp", "Apache-2.0", "Square"),
            Triple("Kotlin Coroutines", "Apache-2.0", "JetBrains"),
            Triple("Shizuku", "Apache-2.0", "Rikka"),
            Triple("android-image-cropper", "Apache-2.0", "Vanniktech"),
            Triple("Apache Commons Compress", "Apache-2.0", "Apache 软件基金会"),
            Triple("QuickJS", "MIT", "Fabrice Bellard"),
            Triple("Sherpa-NCNN", "Apache-2.0 / BSD-3", "k2-fsa"),
            Triple("GeckoView", "MPL-2.0", "Mozilla（文件级 Copyleft，源码随包提供）"),
            Triple("org.json", "JSON License", "随 Android 平台附带，条款含 \"not for Evil\""),
            Triple("ACI 控制台 UI（LAN 控制台·本地 SDUI 渲染）", "Apache-2.0", "本应用自研（core/aci + consolekit），无第三方依赖"),
        )
        AlertDialog(
            onDismissRequest = { showLicense = false },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) { Text("关闭") }
            },
            title = { Text("开源许可声明", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    licenses.forEach { (name, lic, note) ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Box(
                                Modifier
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (lic.startsWith("GPL")) Color(0xFFEF4444).copy(alpha = 0.15f)
                                        else if (lic.startsWith("MPL")) Color(0xFFF59E0B).copy(alpha = 0.15f)
                                        else Color(0xFF22C55E).copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    lic,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (lic.startsWith("GPL")) Color(0xFFEF4444)
                                    else if (lic.startsWith("MPL")) Color(0xFFF59E0B)
                                    else Color(0xFF16A34A),
                                )
                            }
                            if (note.isNotBlank()) {
                                Text(note, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            },
        )
    }

    if (updateDialog != null) {
        val (gh, ge) = updateDialog!!
        AlertDialog(
            onDismissRequest = { updateDialog = null },
            confirmButton = { TextButton(onClick = { updateDialog = null }) { Text("取消") } },
            title = { Text("发现新版本 v$updateVersion", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("检测到新版本，请选择下载镜像：")
                    Button(
                        onClick = { openUrl(gh); updateDialog = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("GitHub 镜像") }
                    Button(
                        onClick = { openUrl(ge); updateDialog = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Gitee 镜像") }
                }
            },
        )
    }

    if (showPermissionStatement) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            QuroLegalDocScreen(
                title = "权限使用声明",
                sections = permissionStatementSections(),
                onBack = { showPermissionStatement = false },
            )
        }
    }

    if (showUserAgreement) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            QuroLegalDocScreen(
                title = "用户使用协议",
                sections = userAgreementSections(),
                onBack = { showUserAgreement = false },
            )
        }
    }
}


/**
 * 拉取 latest release 的 tag_name 与 html_url。GitHub 与 Gitee v5 API 字段一致（tag_name / html_url）。
 */
private fun fetchLatestRelease(apiUrl: String): Pair<String, String> {
    val url = URL(apiUrl)
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.setRequestProperty("Accept", "application/json")
    conn.connectTimeout = 10000
    conn.readTimeout = 10000
    try {
        val code = conn.responseCode
        if (code != 200) throw RuntimeException("HTTP $code")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val json = org.json.JSONObject(text)
        val tag = json.optString("tag_name", "")
        val html = json.optString("html_url", "")
        return tag to html
    } finally {
        conn.disconnect()
    }
}

/**
 * 比较「最新发布版本号」是否高于「当前版本号」（按点分数字逐段比较）。
 */
private fun isVersionNewer(latest: String, current: String): Boolean {
    val a = latest.split('.').map { it.toIntOrNull() ?: 0 }
    val b = current.split('.').map { it.toIntOrNull() ?: 0 }
    val n = if (a.size > b.size) a.size else b.size
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x > y) return true
        if (x < y) return false
    }
    return false
}

/**
 * 通用合规文档阅读页（全屏）：纸张式标题 + 可滚动章节列表。
 * 用于「权限使用声明」「用户使用协议」等较长的说明文本。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuroLegalDocScreen(
    title: String,
    sections: List<Pair<String, String>>,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            sections.forEach { (heading, body) ->
                if (heading.isNotBlank()) {
                    Text(
                        heading,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Accent,
                    )
                }
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )
            }
            Text(
                "本声明随应用版本更新可能调整，最新版本以本页为准。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

/**
 * 权限使用声明内容：逐项说明本应用所申请的系统权限及其用途、调用时机、是否必需、涉及数据与撤销后果。
 */
private fun permissionStatementSections(): List<Pair<String, String>> = listOf(
    "" to "为保障核心功能正常运行，Zorv AI 会在你主动授权后按需使用下列系统权限。我们恪守「最小必要、合法、透明」原则：\n· 仅在对应功能被你主动触发时，才申请与调用对应的权限；\n· 不会在后台静默收集与当前功能无关的数据；\n· 任何权限均可在系统设置中随时查看与撤销，撤销仅影响该功能，不会造成应用崩溃。\n下文逐项说明各权限的用途、调用时机、是否必需，以及撤销后的影响。",
    "一、悬浮窗（SYSTEM_ALERT_WINDOW）" to "用途：显示常驻「悬浮语音球」，让你随时呼出语音对话与快捷操作。\n调用时机：首次开启「悬浮语音球」开关时申请。\n是否必需：否。\n涉及数据：仅绘制界面浮层，不读取被覆盖界面的任何内容。\n撤销后果：悬浮语音球不可用；聊天、工具、数字人等其余功能完全不受影响。",
    "二、麦克风（RECORD_AUDIO）" to "用途：语音识别（STT），将你说的话转写为文字后发送给 AI 或本地识别引擎。\n调用时机：语音对话或语音输入按下录音键时启用。\n是否必需：仅语音输入类功能需要。\n数据流向：录音仅在本地处理，或发往你配置的识别引擎（端侧 sherpa-ncnn / 原生识别 / 云端 STT），绝不会在后台静默录音。\n撤销后果：语音输入不可用，文字输入与全部文本功能不受影响。",
    "三、通知（POST_NOTIFICATIONS）" to "用途：提供常驻通知栏入口与语音服务保活提示，便于你从通知栏快速唤起语音球。\n调用时机：Android 13 及以上首次需要常驻通知时申请。\n是否必需：否。\n撤销后果：无法从通知栏唤起语音球，核心对话功能不受影响。",
    "四、无障碍服务（AccessibilityService）" to "用途：支撑「智能体操作」能力，如自动填表、模拟点击、跨 App 操作、读取界面控件以实现自动化。\n调用时机：首次开启「智能体操作 / 自动化」能力时，由你主动授权。\n是否必需：否。\n涉及数据：仅在智能体任务被你主动触发期间，读取当前界面的控件信息以完成自动化；不会持续性后台采集。\n安全说明：该权限能力较强，请仅在可信场景下开启，并可随时在系统「设置 → 无障碍」关闭。\n撤销后果：自动化与跨 App 操作能力不可用，其余功能不受影响。",
    "五、精确闹钟（SCHEDULE_EXACT_ALARM）" to "用途：「设置闹钟 / 定时提醒」工具精确触发定时任务。\n调用时机：你使用定时提醒或闹钟工具时申请（Android 12 及以上需手动在系统设置授权）。\n是否必需：否。\n撤销后果：精确闹钟不可用，可由系统模糊闹钟兜底。",
    "六、存储（READ/WRITE_EXTERNAL_STORAGE、MANAGE_EXTERNAL_STORAGE）" to "用途：知识库文档的导入与导出、文件读写，以及 AI 生成内容（文档 / 图片 / 代码等）的本地保存。\n调用时机：你执行导入、导出或保存操作时访问。\n是否必需：文档与文件相关功能需要。\n涉及数据：仅访问你明确选择或操作的对象文件，不会扫描或上传全盘文件。\n撤销后果：文件类功能受限，纯对话功能不受影响。",
    "七、设备管理员（DeviceAdmin，可选）" to "用途：提供「防卸载 / 远程锁定」等高级安全能力。\n是否必需：否，默认不申请。\n撤销后果：相关高级安全能力不可用，全部基础功能不受影响。",
    "八、前台服务（ForegroundService）" to "用途：语音球与语音对话在后台持续运行，避免被系统回收导致对话中断；按用途使用麦克风前台服务、媒体播放前台服务等类型。\n是否必需：使用后台语音能力时需要。\n撤销后果：相关后台保活能力受限。",
    "九、安装未知应用（REQUEST_INSTALL_PACKAGES）" to "用途：应用内「检查更新」下载新版本 APK 后，引导你完成安装。\n调用时机：你主动点击「安装更新」时。\n是否必需：否。\n撤销后果：需手动通过文件管理器安装更新。",
    "十、网络（INTERNET / ACCESS_NETWORK_STATE）" to "用途：连接你配置的云端模型、检查版本更新、加载必要资源。\n说明：为基础联网能力；关闭网络后云端模型与更新不可用，但本地端侧引擎仍可运行。",
    "我们的承诺" to "· 不会在后台静默录音、拍照或截屏；\n· 不会将你的聊天内容上传至与应用功能无关的第三方；\n· 不会索取与功能无关的权限；\n· 所有权限的申请目的与调用时机均在本声明中公开。",
    "权限的查询与撤销" to "你可随时在系统「设置 → 应用 → Zorv AI → 权限」中查看已授予的权限，并逐项撤销。撤销某项权限后，仅该功能受限，应用其余部分仍可正常使用；如某项必需权限被撤销导致功能异常，重新授予即可恢复。",
)

/**
 * 用户使用协议内容：说明服务性质、账户凭证、用户义务与禁止行为、数据与隐私、第三方服务、
 * 知识产权、AI 内容免责、责任限制、未成年人保护、违规处理、协议变更、法律适用与争议解决等。
 */
private fun userAgreementSections(): List<Pair<String, String>> = listOf(
    "" to "欢迎使用 Zorv AI（以下简称「本应用」）。本应用为开源 AI 助手。在您下载、安装或使用本应用前，请仔细阅读以下条款。您使用本应用即表示已阅读、理解并同意受本协议约束；如您不同意，请勿使用。",
    "一、服务说明" to "本应用提供基于大语言模型的对话、语音合成与识别、工具调用（文件处理、浏览器、自动化操作等）能力。模型可运行于您自行配置的云端服务商，或部署在设备本地的端侧引擎。本应用按「现状」提供，不对第三方模型服务商的内容、稳定性与可用性作出担保。",
    "二、账户、凭证与 API Key" to "本应用以本地优先为原则，通常无需注册账户。若您为使用云端模型而自行填入 API Key、端点地址等凭证，该等凭证由您自行保管，仅存储于本机设备并仅用于您配置的请求；请勿向他人泄露。因凭证保管不善导致的损失由您自行承担。",
    "三、用户义务与禁止行为" to "您承诺合法、合理地使用本应用，并特别同意不利用本应用从事以下行为：\n· 违反国家法律法规或公序良俗；\n· 侵害他人知识产权、隐私权、名誉权等合法权益；\n· 生成、传播违法或不良信息，或用于诈骗、骚扰、操纵等有害目的；\n· 生成或传播计算机病毒、恶意代码，或用于网络攻击；\n· 滥用深度合成（如伪造他人声音、肖像）从事欺诈或侵权；\n· 绕过、破坏本应用或第三方服务的安全技术措施。\n因您使用不当造成的一切后果由您自行承担。",
    "四、数据与隐私" to "· 本地存储：您的对话记录、模型配置与 API Key 等敏感信息默认存储于本机设备，不会自动上传。\n· 云端传输：当您选用云端模型时，相关对话内容会按您的配置发往对应的模型服务商。\n· 诊断日志：本应用可能在本机「下载 / QuroAI_logs」目录写入运行诊断日志，便于排查问题，不会主动外传。\n· 我们不会出售您的个人数据；除您主动配置的云端服务与本应用功能所必需外，不会将聊天内容上传至无关第三方。\n· 数据删除：卸载本应用通常会清除本机相关数据；部分您手动导出或缓存的文件需您自行删除。\n具体权限与数据访问方式详见「权限使用声明」。",
    "五、第三方服务与开源组件" to "本应用可能集成第三方模型、开源依赖与组件（详见「开源许可声明」）。您在使用相关第三方服务时，还应遵守其各自的服务条款与隐私政策；因第三方服务导致的问题，本应用不承担责任。",
    "六、知识产权" to "· 本应用源代码以 Apache-2.0 许可证开源，您可在遵守该许可证的前提下使用、修改与再分发。\n· 「Zorv AI」名称与品牌标识的商标权益独立于代码许可证，未经授权不得将本应用用于暗示官方背书或误导性的商业用途。\n· 您使用本应用生成的内容，其知识产权依适用法律及您的输入归属；本应用不因提供生成服务而对生成内容主张权属。",
    "七、关于 AI 生成内容" to "AI 生成的内容由模型自动产生，仅供参考，不保证其准确性、完整性、时效性或适用性，不构成任何专业建议（包括但不限于医疗、法律、金融等领域）。您应独立判断并对基于生成内容所采取的行动负责，切勿据此作出重大决定。",
    "八、免责声明与责任限制" to "本应用按「现状」提供，不提供任何明示或暗示的担保。在法律允许的最大范围内，本应用及其开发者不对因使用或无法使用本应用所导致的任何直接、间接、偶然、特殊或后果性损失承担责任。",
    "九、付费与订阅" to "当前本应用为免费且开源的软件，不收取费用，亦不包含强制付费功能。若未来推出可选的付费或增值服务，将另行以单独条款说明。",
    "十、未成年人保护" to "本应用主要面向成年用户。若您为未成年人，请在监护人陪同与同意下使用，并注意保护个人隐私，勿向 AI 透露真实姓名、住址、学校等敏感信息。",
    "十一、违规处理与终止" to "若您违反本协议或相关法律法规，本应用有权限制或终止您对本应用部分或全部功能的使用。您亦可随时停止使用并卸载本应用。",
    "十二、协议的变更" to "我们可能不时更新本协议。更新后的版本将在应用内展示，并于「关于 Zorv AI」中可见；若您在更新后继续使用本应用，即视为接受更新后的条款。",
    "十三、法律适用与争议解决" to "本协议的订立、效力、解释及争议解决均适用中华人民共和国大陆地区法律。因本协议引起或与本协议有关的任何争议，双方应首先友好协商解决；协商不成的，任何一方均可向本应用运营方所在地有管辖权的人民法院提起诉讼。",
    "十四、联系我们" to "如您对本协议或隐私事宜有疑问，可通过「关于 Zorv AI → 项目地址」中的仓库 Issues 与我们联系。",
)
