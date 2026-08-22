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
import android.app.DownloadManager
import android.os.Environment
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
                            var apkUrl: String? = null
                            var errMsg: String? = null
                            // 1) 先试 GitHub（国内网络常不可达）
                            val gh = runCatching { fetchLatestRelease("https://api.github.com/repos/Quor-a/ZorvAI/releases/latest") }
                            if (gh.isSuccess && gh.getOrNull()?.first?.isNotBlank() == true) {
                                latest = gh.getOrNull()!!.first
                                htmlUrl = gh.getOrNull()!!.second
                                apkUrl = gh.getOrNull()!!.third
                            } else {
                                // 2) GitHub 不可达 → 回退 Gitee 镜像
                                val ge = runCatching { fetchLatestRelease("https://gitee.com/api/v5/repos/ZorvAI/ZorvAI/releases/latest") }
                                if (ge.isSuccess && ge.getOrNull()?.first?.isNotBlank() == true) {
                                    latest = ge.getOrNull()!!.first
                                    htmlUrl = ge.getOrNull()!!.second.ifBlank { "https://gitee.com/ZorvAI/ZorvAI/releases" }
                                    apkUrl = ge.getOrNull()!!.third
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
                                        // 自动下载 APK
                                        if (apkUrl != null && apkUrl!!.isNotBlank()) {
                                            downloadApk(ctx, apkUrl!!, "ZorvAI-v$lv.apk")
                                        } else {
                                            // 如果没有 APK 下载链接，显示对话框让用户选择
                                            updateDialog = Triple(
                                                htmlUrl ?: "$repoUrl/releases/latest",
                                                "https://gitee.com/ZorvAI/ZorvAI/releases",
                                                apkUrl ?: ""
                                            )
                                        }
                                        Toast.makeText(ctx, "发现新版本 v$lv", Toast.LENGTH_LONG).show()
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
                SetRowClickable(
                    icon = Icons.Filled.Code,
                    name = "开发者",
                    sub = "Zorv AI",
                    onClick = { },
                )
            }

            // 底部版权信息
            Text(
                "© 2025 - 2026 Zorv AI. 保留所有权利。",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    if (showLicense) {
        val licenses = listOf(
            Triple("Zorv AI（本应用）", "Apache-2.0", "源码以 Apache-2.0 开源，见仓库 LICENSE 文件"),
            Triple("AndroidX / Jetpack", "Apache-2.0", "Google - core-ktx、appcompat、lifecycle、activity-compose、webkit、concurrent-futures 等"),
            Triple("Jetpack Compose", "Apache-2.0", "Google - UI、Material3、icons-extended 等组件"),
            Triple("Material Components", "Apache-2.0", "Google"),
            Triple("OkHttp", "Apache-2.0", "Square - HTTP 客户端"),
            Triple("Kotlin Coroutines", "Apache-2.0", "JetBrains - 异步编程框架"),
            Triple("Shizuku", "Apache-2.0", "Rikka - ADB 级 IPC 通道，免 Root 系统命令执行"),
            Triple("android-image-cropper", "Apache-2.0", "Vanniktech - 图片裁剪组件"),
            Triple("Apache Commons Compress", "Apache-2.0", "Apache 软件基金会 - 压缩文件处理"),
            Triple("QuickJS", "MIT", "Fabrice Bellard - 轻量级 JavaScript 引擎，用于插件运行时"),
            Triple("Sherpa-NCNN", "Apache-2.0 / BSD-3", "k2-fsa - 端侧语音识别引擎（SenseVoice）"),
            Triple("GeckoView", "MPL-2.0", "Mozilla - 开源浏览器引擎，文件级 Copyleft，源码随包提供"),
            Triple("org.json", "Public Domain", "公共领域 - 自 20220924 版本起移除 \"Good, not Evil\" 条款"),
            Triple("Health Connect", "Apache-2.0", "Google - 健康数据连接客户端"),
            Triple("JUnit", "EPL-2.0", "JUnit 团队 - 单元测试框架（Eclipse Public License 2.0）"),
            // ── 离线模型引擎 ──
            Triple("MNN（Mobile Neural Network）", "Apache-2.0", "阿里巴巴 - 端侧 AI 推理框架，源码编译，full 风味"),
            Triple("llama.cpp", "MIT", "ggml authors - 本地大语言模型推理引擎，源码编译，full 风味"),
            Triple("ncnn", "BSD-3-Clause", "腾讯 - 高性能神经网络推理框架，预编译 .so"),
            // ── 原生工具库 ──
            Triple("proot", "GPL-2.0+", "PRoot - 无 Root 用户空间 chroot/mount 模拟，预编译 .so。源码见 https://github.com/proot-me/proot"),
            Triple("talloc", "LGPL-3.0+", "Samba 项目 - 层级内存分配库，预编译 .so，动态链接。源码见 https://talloc.samba.org"),
            Triple("Alpine Linux 环境", "GPL-2.0+ / MIT / BSD", "包含 musl libc (MIT)、BusyBox (GPL-2.0)、apk 包管理器等，用于应用内 Linux 环境"),
            // ── 前端 JavaScript 库 ──
            Triple("Three.js", "MIT", "Three.js Authors - 3D 渲染引擎，用于 GLB 头像显示"),
            Triple("Draco", "Apache-2.0", "Google - 3D 数据压缩/解压库（draco_decoder.wasm）"),
            Triple("CodeMirror", "MIT", "CodeMirror - 代码编辑器组件（v5.65.16）"),
            Triple("Brython", "BSD-3-Clause", "Pierre Quentel - 浏览器 Python 运行时"),
            Triple("Mermaid", "MIT", "Mermaid - 图表/流程图渲染库"),
            // ── 文档处理库 ──
            Triple("pdf.js", "Apache-2.0", "Mozilla - PDF 渲染引擎"),
            Triple("Adobe CMap 资源", "BSD-3-Clause", "Adobe Systems - PDF 字符映射表（cmaps/ 目录）"),
            Triple("Mammoth.js", "BSD-2-Clause", "Michael Williamson - DOCX 转 HTML 转换器"),
            Triple("SheetJS", "Apache-2.0", "SheetJS - 电子表格解析/写入库（xlsx.js）"),
            // ── PDF 字体 ──
            Triple("Liberation Sans", "SIL OFL 1.1", "Google/Red Hat - PDF 标准字体"),
            Triple("Foxit PDF Fonts", "BSD-3-Clause", "PDFium Authors (Google) - PDF 标准字体"),
            Triple("ACI 控制台 UI", "Apache-2.0", "本应用自研（core/aci + consolekit），无第三方依赖"),
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
                                        else if (lic.startsWith("LGPL") || lic.startsWith("MPL")) Color(0xFFF59E0B).copy(alpha = 0.15f)
                                        else Color(0xFF22C55E).copy(alpha = 0.15f)
                                    )
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    lic,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (lic.startsWith("GPL")) Color(0xFFEF4444)
                                    else if (lic.startsWith("LGPL") || lic.startsWith("MPL")) Color(0xFFF59E0B)
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
        val (gh, ge, apk) = updateDialog!!
        AlertDialog(
            onDismissRequest = { updateDialog = null },
            confirmButton = { TextButton(onClick = { updateDialog = null }) { Text("取消") } },
            title = { Text("发现新版本 v$updateVersion", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("检测到新版本，正在直接下载 APK…")
                    Text("如果下载未自动开始，请点击下方按钮手动下载：", fontSize = 12.sp, color = Muted)
                    Button(
                        onClick = { 
                            if (apk.isNotBlank()) {
                                openUrl(apk)
                            } else {
                                openUrl(gh)
                            }
                            updateDialog = null 
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("手动下载 APK") }
                    TextButton(
                        onClick = { openUrl(ge); updateDialog = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("使用 Gitee 镜像下载") }
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
 * 拉取 latest release 的 tag_name、html_url 与 APK 下载链接。GitHub 与 Gitee v5 API 字段一致。
 */
private fun fetchLatestRelease(apiUrl: String): Triple<String, String, String> {
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
        
        // 查找 APK 资源
        var apkUrl = ""
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")
                val browserDownloadUrl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk")) {
                    apkUrl = browserDownloadUrl
                    break
                }
            }
        }
        
        // 如果没有找到 APK 资源，尝试构建默认下载链接
        if (apkUrl.isEmpty()) {
            // GitHub 格式：https://github.com/owner/repo/releases/download/tag/app-full-release.apk
            // Gitee 格式：https://gitee.com/owner/repo/releases/download/tag/app-full-release.apk
            val baseUrl = html.replace("/releases/tag/", "/releases/download/")
            apkUrl = "$baseUrl/app-full-release.apk"
        }
        
        return Triple(tag, html, apkUrl)
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
 * 自动下载 APK 文件
 */
private fun downloadApk(context: android.content.Context, apkUrl: String, fileName: String = "ZorvAI-update.apk") {
    try {
        val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("下载 ZorvAI 更新")
            .setDescription("正在下载新版本 v$updateVersion...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        
        val downloadId = downloadManager.enqueue(request)
        Toast.makeText(context, "开始下载更新…", Toast.LENGTH_SHORT).show()
        
        // 注意：实际安装需要监听下载完成并启动安装意图
        // 这里简化处理，用户可以从通知栏点击安装
    } catch (e: Exception) {
        Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
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
    
    "一、基础网络与设备权限" to 
    "1. 网络访问（INTERNET / ACCESS_NETWORK_STATE）\n用途：连接你配置的云端模型、检查版本更新、加载必要资源。\n是否必需：是（基础联网能力）。\n撤销后果：云端模型与更新不可用，本地端侧引擎仍可运行。\n\n" +
    "2. Wi-Fi 状态（ACCESS_WIFI_STATE / CHANGE_WIFI_STATE）\n用途：检查 Wi-Fi 连接状态，用于网络诊断和优化。\n是否必需：否。\n撤销后果：无法获取 Wi-Fi 状态，不影响核心功能。\n\n" +
    "3. 组播锁（CHANGE_WIFI_MULTICAST_STATE）\n用途：网页端访问本机（LAN Web 服务 / NSD 服务发现）。\n是否必需：否（仅 LAN 功能需要）。\n撤销后果：LAN 相关功能受限。\n\n" +
    "4. 震动（VIBRATE）\n用途：语音球点击反馈、通知提醒等交互反馈。\n是否必需：否。\n撤销后果：无触觉反馈，不影响功能。\n\n" +
    "5. 蓝牙（BLUETOOTH / BLUETOOTH_CONNECT）\n用途：蓝牙设备连接（如蓝牙耳机、蓝牙键盘）。\n是否必需：否。\n撤销后果：蓝牙设备连接功能不可用。",
    
    "二、音频与媒体权限" to 
    "1. 麦克风（RECORD_AUDIO）\n用途：语音识别（STT），将你说的话转写为文字后发送给 AI 或本地识别引擎。\n调用时机：语音对话或语音输入按下录音键时启用。\n是否必需：仅语音输入类功能需要。\n数据流向：录音仅在本地处理，或发往你配置的识别引擎（端侧 sherpa-ncnn / 原生识别 / 云端 STT），绝不会在后台静默录音。\n撤销后果：语音输入不可用，文字输入与全部文本功能不受影响。\n\n" +
    "2. 音频文件读取（READ_MEDIA_AUDIO）\n用途：读取音频文件（如录音、音乐），用于 AI 分析或播放。\n是否必需：否。\n撤销后果：无法读取音频文件。\n\n" +
    "3. 相机（CAMERA）\n用途：拍照或扫描二维码等功能。\n是否必需：否。\n撤销后果：拍照和二维码扫描功能不可用。",
    
    "三、存储与文件权限" to 
    "1. 存储读取（READ_EXTERNAL_STORAGE / READ_MEDIA_IMAGES / READ_MEDIA_VIDEO）\n用途：知识库文档的导入与导出、文件读写，以及 AI 生成内容（文档 / 图片 / 代码等）的本地保存。\n调用时机：你执行导入、导出或保存操作时访问。\n是否必需：文档与文件相关功能需要。\n涉及数据：仅访问你明确选择或操作的对象文件，不会扫描或上传全盘文件。\n\n" +
    "2. 所有文件访问（MANAGE_EXTERNAL_STORAGE）\n用途：全文件系统读写（Android 11+ 特殊权限）。\n是否必需：否（仅特定高级功能需要）。\n\n" +
    "3. 存储写入（WRITE_EXTERNAL_STORAGE，Android 9 及以下）\n用途：写入外部存储文件。\n是否必需：仅低版本设备需要。\n\n" +
    "撤销后果：文件类功能受限，纯对话功能不受影响。",
    
    "四、电话与短信权限" to 
    "1. 电话状态（READ_PHONE_STATE / READ_PHONE_NUMBERS）\n用途：获取设备信息（如 SIM 卡状态、网络类型），用于网络诊断。\n是否必需：否。\n\n" +
    "2. 拨打电话（CALL_PHONE）\n用途：AI 拨打电话功能。\n是否必需：否。\n\n" +
    "3. 短信读取与发送（READ_SMS / SEND_SMS / RECEIVE_SMS）\n用途：短信读取、发送、接收功能。\n是否必需：否。\n\n" +
    "4. 彩信收发（RECEIVE_MMS / SEND_MMS）\n用途：彩信收发功能。\n是否必需：否。\n\n" +
    "5. 通话记录（READ_CALL_LOG / WRITE_CALL_LOG）\n用途：读取和写入通话记录。\n是否必需：否。\n\n" +
    "撤销后果：相关电话、短信、通话记录功能不可用。",
    
    "五、联系人与日历权限" to 
    "1. 联系人读取（READ_CONTACTS）\n用途：读取联系人信息，用于通讯相关功能。\n是否必需：否。\n\n" +
    "2. 联系人写入（WRITE_CONTACTS）\n用途：写入或删除联系人。\n是否必需：否。\n\n" +
    "3. 账户访问（GET_ACCOUNTS）\n用途：访问设备账户列表。\n是否必需：否。\n\n" +
    "4. 日历读取（READ_CALENDAR）\n用途：读取日历事件，用于日程管理。\n是否必需：否。\n\n" +
    "5. 日历写入（WRITE_CALENDAR）\n用途：写入或删除日历事件。\n是否必需：否。\n\n" +
    "撤销后果：联系人和日历相关功能不可用。",
    
    "六、位置权限" to 
    "1. 精确位置（ACCESS_FINE_LOCATION）\n用途：获取精确位置信息，用于位置相关功能。\n是否必需：否。\n\n" +
    "2. 粗略位置（ACCESS_COARSE_LOCATION）\n用途：获取粗略位置信息。\n是否必需：否。\n\n" +
    "3. 后台定位（ACCESS_BACKGROUND_LOCATION）\n用途：后台持续获取位置。\n是否必需：否。\n\n" +
    "撤销后果：位置相关功能不可用。",
    
    "七、通知与显示权限" to 
    "1. 通知（POST_NOTIFICATIONS）\n用途：提供常驻通知栏入口与语音服务保活提示，便于你从通知栏快速唤起语音球。\n调用时机：Android 13 及以上首次需要常驻通知时申请。\n是否必需：否。\n撤销后果：无法从通知栏唤起语音球，核心对话功能不受影响。\n\n" +
    "2. 全屏通知（USE_FULL_SCREEN_INTENT）\n用途：发送全屏通知（如来电提醒、重要提醒）。\n是否必需：否。\n\n" +
    "3. 通知策略访问（ACCESS_NOTIFICATION_POLICY）\n用途：读取/设置勿扰模式状态。\n是否必需：否。\n\n" +
    "4. 悬浮窗（SYSTEM_ALERT_WINDOW）\n用途：显示常驻「悬浮语音球」，让你随时呼出语音对话与快捷操作。\n调用时机：首次开启「悬浮语音球」开关时申请。\n是否必需：否。\n涉及数据：仅绘制界面浮层，不读取被覆盖界面的任何内容。\n撤销后果：悬浮语音球不可用；聊天、工具、数字人等其余功能完全不受影响。",
    
    "八、系统与特殊权限" to 
    "1. 精确闹钟（SCHEDULE_EXACT_ALARM）\n用途：「设置闹钟 / 定时提醒」工具精确触发定时任务。\n调用时机：你使用定时提醒或闹钟工具时申请（Android 12 及以上需手动在系统设置授权）。\n是否必需：否。\n撤销后果：精确闹钟不可用，可由系统模糊闹钟兜底。\n\n" +
    "2. 设备管理员（DeviceAdmin，可选）\n用途：提供「防卸载 / 远程锁定」等高级安全能力。\n是否必需：否，默认不申请。\n撤销后果：相关高级安全能力不可用，全部基础功能不受影响。\n\n" +
    "3. 使用情况访问（PACKAGE_USAGE_STATS）\n用途：获取前台应用信息，用于智能体操作。\n是否必需：否。\n\n" +
    "4. 修改系统设置（WRITE_SETTINGS）\n用途：修改系统设置（如亮度、音量）。\n是否必需：否。\n\n" +
    "5. 唤醒屏幕（WAKE_LOCK）\n用途：保持设备唤醒状态。\n是否必需：否。\n\n" +
    "6. NFC（NFC）\n用途：NFC 启动控制。\n是否必需：否。\n\n" +
    "7. 电池优化豁免（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）\n用途：防止系统杀死后台服务。\n是否必需：否。\n\n" +
    "8. 开机启动（RECEIVE_BOOT_COMPLETED）\n用途：开机自启动相关服务。\n是否必需：否。",
    
    "九、无障碍与自动化权限" to 
    "1. 无障碍服务（AccessibilityService）\n用途：支撑「智能体操作」能力，如自动填表、模拟点击、跨 App 操作、读取界面控件以实现自动化。\n调用时机：首次开启「智能体操作 / 自动化」能力时，由你主动授权。\n是否必需：否。\n涉及数据：仅在智能体任务被你主动触发期间，读取当前界面的控件信息以完成自动化；不会持续性后台采集。\n安全说明：该权限能力较强，请仅在可信场景下开启，并可随时在系统「设置 → 无障碍」关闭。\n撤销后果：自动化与跨 App 操作能力不可用，其余功能不受影响。\n\n" +
    "2. 自动填充服务（AutofillService）\n用途：在其他 App 输入框提供 AI 生成的填充建议。\n是否必需：否。\n\n" +
    "3. 通知侦听服务（NotificationListenerService）\n用途：读取/清除通知、触发通知按钮。\n是否必需：否。\n\n" +
    "4. 输入法服务（InputMethod）\n用途：作为系统输入法使用。\n是否必需：否。",
    
    "十、前台服务权限" to 
    "1. 前台服务（FOREGROUND_SERVICE）\n用途：语音球与语音对话在后台持续运行，避免被系统回收导致对话中断。\n是否必需：使用后台语音能力时需要。\n\n" +
    "2. 麦克风前台服务（FOREGROUND_SERVICE_MICROPHONE）\n用途：后台语音识别服务。\n\n" +
    "3. 数据同步前台服务（FOREGROUND_SERVICE_DATA_SYNC）\n用途：后台数据同步服务。\n\n" +
    "4. 媒体播放前台服务（FOREGROUND_SERVICE_MEDIA_PLAYBACK）\n用途：后台媒体播放服务。\n\n" +
    "5. 屏幕录制前台服务（FOREGROUND_SERVICE_MEDIA_PROJECTION）\n用途：屏幕录制服务（Android 14+ 必须显式声明）。\n\n" +
    "撤销后果：相关后台保活能力受限。",
    
    "十一、包可见性权限" to 
    "1. 查询所有应用（QUERY_ALL_PACKAGES）\n用途：查询已安装的应用列表，用于「查看软件包名」「启动应用」等功能。\n是否必需：是。\n\n" +
    "2. 查询特定应用（queries）\n用途：查询特定类型的应用（如 TTS 引擎、ACI 应用）。\n是否必需：是。\n\n" +
    "撤销后果：无法查询已安装应用，相关功能受限。",
    
    "十二、健康数据权限" to 
    "1. 健康数据连接（Health Connect）\n用途：读取和写入健康数据（如步数、心率、睡眠、运动）。\n是否必需：否。\n\n" +
    "2. 活动识别（ACTIVITY_RECOGNITION）\n用途：读取步数和活动识别（Android 10+ 运行时危险权限）。\n是否必需：否。\n\n" +
    "撤销后果：健康数据相关功能不可用。",
    
    "十三、ACI 协议权限" to 
    "1. ACI 调用权限（ai.aci.permission.CALL）\n用途：控制方调用第三方 ACI 应用的能力。\n是否必需：是（ACI 功能需要）。\n\n" +
    "2. ACI 发现权限（ai.aci.permission.DISCOVER）\n用途：发现第三方 ACI 应用。\n是否必需：是（ACI 功能需要）。\n\n" +
    "3. Shizuku 权限（moe.shizuku.manager.permission.API / API_V23）\n用途：通过 Shizuku 进行 ADB 级 IPC 通信。\n是否必需：否（仅 Shizuku 功能需要）。\n\n" +
    "撤销后果：ACI 和 Shizuku 相关功能不可用。",
    
    "我们的承诺" to "· 不会在后台静默录音、拍照或截屏；\n· 不会将你的聊天内容上传至与应用功能无关的第三方；\n· 不会索取与功能无关的权限；\n· 所有权限的申请目的与调用时机均在本声明中公开。",
    "权限的查询与撤销" to "你可随时在系统「设置 → 应用 → Zorv AI → 权限」中查看已授予的权限，并逐项撤销。撤销某项权限后，仅该功能受限，应用其余部分仍可正常使用；如某项必需权限被撤销导致功能异常，重新授予即可恢复。",
)

/**
 * 用户使用协议内容：说明服务性质、账户凭证、用户义务与禁止行为、数据与隐私、第三方服务、
 * 知识产权、AI 内容免责、法律风险、法律责任、违法使用后果、未成年人保护、违规处理、协议变更、法律适用与争议解决等。
 */
private fun userAgreementSections(): List<Pair<String, String>> = listOf(
    "" to "欢迎使用 Zorv AI（以下简称「本应用」）。下载、安装或使用本应用前，请认真阅读以下条款。使用即表示同意受约束；不同意请勿使用。",

    "一、服务说明" to "本应用提供基于大语言模型的对话、语音合成与识别、工具调用等能力。模型运行在您自行配置的云端服务商，或设备本地的端侧引擎。本应用按「现状」提供，不对第三方模型服务商的内容、稳定性负责。\n本应用为开源软件，源代码以 Apache-2.0 许可证公开。可能包含实验性功能，不稳定或随时变更。",

    "二、账户、凭证与 API Key" to "本应用以本地优先为原则，通常无需注册账户。若您填入 API Key 等凭证，由您自行保管，仅存于本机，勿向他人泄露。因保管不善导致的损失由您承担。\n本应用不会主动收集或传输您的 API Key 至第三方服务器。您应确保所用 API Key 有合法使用权限，符合相应服务提供商的条款。",

    "三、用户义务与禁止行为" to "您承诺合法使用本应用，不得从事以下行为：\n· 违反国家法律法规或公序良俗；\n· 侵害他人知识产权、隐私权、名誉权等合法权益；\n· 生成、传播违法或不良信息，用于诈骗、骚扰、操纵等有害目的；\n· 制作或传播计算机病毒、恶意代码，用于网络攻击；\n· 滥用深度合成技术伪造他人声音、肖像从事欺诈或侵权；\n· 绕过、破坏本应用或第三方服务的安全措施；\n· 利用本应用危害国家安全、社会稳定或公共利益。\n因使用不当造成的一切后果由您自行承担。",

    "四、数据与隐私" to "对话记录、模型配置与 API Key 等信息默认存储于本机，不会自动上传。选用云端模型时，对话内容会发往对应的服务商。诊断日志写入本机「下载 / QuroAI_logs」目录，不会主动外传。\n我们不出售个人数据；除您主动配置的云端服务外，不会将聊天内容上传至无关第三方。卸载应用通常清除本机数据，手动导出的文件需您自行删除。\n具体权限与数据访问方式详见「权限使用声明」。",

    "五、第三方服务与开源组件" to "本应用可能集成第三方模型、开源依赖与组件（详见「开源许可声明」）。使用相关第三方服务时，您还应遵守其各自的服务条款；因第三方服务导致的问题，本应用不负责。\n本应用可能包含指向第三方网站的链接，仅为方便提供，我们对第三方内容不负责。",

    "六、知识产权" to "本应用源代码以 Apache-2.0 许可证开源，您可在遵守该许可证的前提下使用、修改与再分发。「Zorv AI」名称与品牌标识的商标权益独立于代码许可证，未经授权不得用于暗示官方背书或误导性商业用途。\n您使用本应用生成的内容，其知识产权依适用法律及您的输入归属；本应用不因提供生成服务而对生成内容主张权属。",

    "七、关于 AI 生成内容" to "AI 生成内容由模型自动产生，仅供参考，不保证准确性、完整性、时效性或适用性，不构成专业建议（包括但不限于医疗、法律、金融等领域）。您应独立判断并自行承担使用风险。\nAI 生成内容可能存在偏见、错误或过时信息，请自行验证重要信息。本应用不保证 AI 生成内容不侵犯第三方权利，您应自行确保使用不违反相关法律法规。",

    "八、法律风险提示" to "使用本应用可能存在以下法律风险，请您充分了解：\n\n1. AI 生成内容的法律风险\nAI 生成的内容可能涉及版权、商标、肖像权、隐私权等知识产权问题。您使用 AI 生成内容进行商业活动、公开发布或传播前，应自行核实其合法性，确认不侵犯第三方权利。因使用 AI 生成内容导致的侵权责任，由您自行承担。\n\n2. 深度合成技术风险\n本应用可能涉及语音合成、图像生成等深度合成技术。根据《互联网信息服务深度合成管理规定》，您不得利用深度合成服务制作、复制、发布、传播虚假新闻信息，不得利用深度合成服务从事侵害他人肖像权、名誉权、隐私权等合法权益的行为。使用者应标注深度合成内容，避免公众混淆。\n\n3. 自动化操作风险\n本应用的智能体操作、无障碍自动化等功能可模拟用户操作其他应用。您应确保自动化操作的对象和内容合法合规，不得利用自动化功能实施未经授权的访问、数据窃取、系统破坏等行为。\n\n4. 数据安全风险\n虽然本应用优先本地存储，但使用云端模型时数据会经网络传输。您应了解网络传输的固有风险，敏感数据（如商业机密、个人隐私信息）建议使用端侧模型处理。\n\n5. 第三方服务风险\n本应用依赖的云端模型服务可能因政策、技术等原因中断或变更。您应自行评估第三方服务的可靠性，重要业务不应完全依赖单一服务。",

    "九、法律责任" to "您使用本应用所产生的一切行为及其后果，由您自行承担法律责任。包括但不限于：\n· 您输入的内容（Prompt）所引发的法律纠纷；\n· 您基于 AI 生成内容所作出的决策和采取的行动；\n· 您通过本应用的自动化功能操作其他应用所产生的后果；\n· 您将 AI 生成内容用于商业用途所涉及的知识产权、消费者权益等问题。",

    "十、违法使用及后果" to "以下行为属于违法或违规使用，一经发现，本应用有权立即采取限制措施，同时保留配合执法机关调查的权利：\n\n1. 利用本应用实施诈骗、敲诈勒索、诽谤、侵犯公民个人信息等违法犯罪行为；\n2. 利用深度合成功能伪造他人身份、制作虚假证据或实施身份冒用；\n3. 利用自动化功能非法获取、篡改或删除他人数据；\n4. 利用本应用绕过网络安全措施、入侵他人系统或窃取商业秘密；\n5. 利用 AI 生成大量虚假信息用于传播、营销欺诈或操纵舆论；\n6. 违反《网络安全法》《数据安全法》《个人信息保护法》《生成式人工智能服务管理暂行办法》等法律法规的行为。\n\n对于上述行为，本应用将根据情况采取以下措施：\n· 限制或终止您对部分或全部功能的使用；\n· 保留相关日志和证据，配合公安机关、网络安全主管部门的调查；\n· 依法追究您的民事赔偿责任；\n· 涉嫌犯罪的，依法向司法机关举报。",

    "十一、未成年人保护" to "本应用主要面向成年用户。若您为未成年人，请在监护人陪同与同意下使用，并注意保护个人隐私，勿向 AI 透露真实姓名、住址、学校等敏感信息。监护人应监督未成年人的使用行为。",

    "十二、违规处理与终止" to "若您违反本协议或相关法律法规，本应用有权限制或终止您对部分或全部功能的使用。您亦可随时停止使用并卸载本应用。本应用保留在必要时修改或终止服务的权利，但会提前通知用户。因您违反本协议导致的任何损失，本应用不承担责任。",

    "十三、协议的变更" to "我们可能不时更新本协议。更新后在「关于 Zorv AI」中可见；继续使用即视为接受更新后的条款。重大变更通过应用内通知告知。您有权在协议变更后停止使用本应用。",

    "十四、法律适用与争议解决" to "本协议的订立、效力、解释及争议解决均适用中华人民共和国大陆地区法律。因本协议引起的争议，双方应首先友好协商；协商不成的，向本应用运营方所在地有管辖权的人民法院提起诉讼。\n本协议的任何条款如被认定无效或不可执行，该条款在允许的最大范围内执行，其余条款仍具效力。",

    "十五、其他条款" to "本协议构成双方就使用本应用的完整协议，取代之前的口头或书面协议。本协议的修改或放弃须以书面形式作出。本应用未行使或延迟行使任何权利，不构成对该权利的放弃。",

    "十六、联系我们" to "对本协议或隐私事宜有疑问，可通过「关于 Zorv AI → 项目地址」中的仓库 Issues 联系我们。",
)
