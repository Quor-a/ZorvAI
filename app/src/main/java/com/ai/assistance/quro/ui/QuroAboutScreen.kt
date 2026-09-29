package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

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
    var updateDialog by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var updateVersion by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val openUrl: (String) -> Unit = { url ->
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(ctx, qstr(R.string.qk_00496, (url).toString()), Toast.LENGTH_SHORT).show()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.qk_00497),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) }
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
                Text(stringResource(R.string.qk_00498),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }

            GroupCaption(stringResource(R.string.qk_00499))
            SetGroup {
                SetRowClickable(
                    icon = Icons.Filled.Refresh,
                    name = stringResource(R.string.qk_00500),
                    sub = if (checking) stringResource(R.string.qk_00501) else stringResource(R.string.qk_00502, (versionName).toString()),
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
                                val ge = runCatching { fetchLatestRelease("https://gitee.com/api/v5/repos/Quor-a/ZorvAI/releases/latest") }
                                if (ge.isSuccess && ge.getOrNull()?.first?.isNotBlank() == true) {
                                    latest = ge.getOrNull()!!.first
                                    htmlUrl = ge.getOrNull()!!.second.ifBlank { "https://gitee.com/Quor-a/ZorvAI/releases" }
                                    apkUrl = ge.getOrNull()!!.third
                                } else {
                                    errMsg = gh.exceptionOrNull()?.message ?: ge.exceptionOrNull()?.message ?: qstr(R.string.qk_00503)
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
                                            downloadApk(ctx, apkUrl!!, "ZorvAI-v$lv.apk", lv)
                                        } else {
                                            // 如果没有 APK 下载链接，显示对话框让用户选择
                                            updateDialog = Triple(
                                                htmlUrl ?: "$repoUrl/releases/latest",
                                                "https://gitee.com/Quor-a/ZorvAI/releases",
                                                apkUrl ?: ""
                                            )
                                        }
                                        Toast.makeText(ctx, qstr(R.string.qk_00504, (lv).toString()), Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(ctx, qstr(R.string.qk_00505, (versionName).toString()), Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(ctx, qstr(R.string.qk_00506, (errMsg).toString()), Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                )
                SetRowClickable(
                    icon = Icons.Filled.Link,
                    name = stringResource(R.string.qk_00507),
                    sub = repoUrl,
                    onClick = { openUrl(repoUrl) },
                )
                SetRowClickable(
                    icon = Icons.Filled.Star,
                    name = stringResource(R.string.qk_00508),
                    sub = stringResource(R.string.qk_00509),
                    onClick = { openUrl("$repoUrl/stargazers") },
                )
                SetRowClickable(
                    icon = Icons.Filled.Description,
                    name = stringResource(R.string.qk_00510),
                    sub = stringResource(R.string.qk_00511),
                    onClick = { showLicense = true },
                )
            }

            GroupCaption(stringResource(R.string.qk_00512))
            SetGroup {
                SetRowClickable(
                    icon = Icons.Filled.Security,
                    name = stringResource(R.string.qk_00513),
                    sub = stringResource(R.string.qk_00514),
                    onClick = { showPermissionStatement = true },
                )
                SetRowClickable(
                    icon = Icons.AutoMirrored.Filled.Article,
                    name = stringResource(R.string.qk_00515),
                    sub = stringResource(R.string.qk_00516),
                    onClick = { showUserAgreement = true },
                )
                SetRowClickable(
                    icon = Icons.Filled.Code,
                    name = stringResource(R.string.qk_00517),
                    sub = "Zorv AI",
                    onClick = { },
                )
            }

            // 底部版权信息
            Text(stringResource(R.string.qk_00518),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }

    if (showLicense) {
        val licenses = listOf(
            Triple(stringResource(R.string.qk_00519), "Apache-2.0", stringResource(R.string.qk_00520)),
            Triple("AndroidX / Jetpack", "Apache-2.0", stringResource(R.string.qk_00521)),
            Triple("Jetpack Compose", "Apache-2.0", stringResource(R.string.qk_00522)),
            Triple("Material Components", "Apache-2.0", "Google"),
            Triple("OkHttp", "Apache-2.0", stringResource(R.string.qk_00523)),
            Triple("Kotlin Coroutines", "Apache-2.0", stringResource(R.string.qk_00524)),
            Triple("Shizuku", "Apache-2.0", stringResource(R.string.qk_00525)),
            Triple("android-image-cropper", "Apache-2.0", stringResource(R.string.qk_00526)),
            Triple("Apache Commons Compress", "Apache-2.0", stringResource(R.string.qk_00527)),
            Triple("QuickJS", "MIT", stringResource(R.string.qk_00528)),
            Triple("Sherpa-NCNN", "Apache-2.0 / BSD-3", stringResource(R.string.qk_00529)),
            Triple("GeckoView", "MPL-2.0", stringResource(R.string.qk_00530)),
            Triple("org.json", "Public Domain", stringResource(R.string.qk_00531)),
            Triple("Health Connect", "Apache-2.0", stringResource(R.string.qk_00532)),
            Triple("JUnit", "EPL-2.0", stringResource(R.string.qk_00533)),
            // ── 离线模型引擎 ──
            Triple("MNN（Mobile Neural Network）", "Apache-2.0", stringResource(R.string.qk_00534)),
            Triple("llama.cpp", "MIT", stringResource(R.string.qk_00535)),
            Triple("ncnn", "BSD-3-Clause", stringResource(R.string.qk_00536)),
            // ── 原生工具库 ──
            Triple("proot", "GPL-2.0+", stringResource(R.string.qk_00537)),
            Triple("talloc", "LGPL-3.0+", stringResource(R.string.qk_00538)),
            Triple(stringResource(R.string.qk_00539), "GPL-2.0+ / MIT / BSD", stringResource(R.string.qk_00540)),
            // ── 前端 JavaScript 库 ──
            Triple("Three.js", "MIT", stringResource(R.string.qk_00541)),
            Triple("Draco", "Apache-2.0", stringResource(R.string.qk_00542)),
            Triple("CodeMirror", "MIT", stringResource(R.string.qk_00543)),
            Triple("Brython", "BSD-3-Clause", stringResource(R.string.qk_00544)),
            Triple("Mermaid", "MIT", stringResource(R.string.qk_00545)),
            // ── 文档处理库 ──
            Triple("pdf.js", "Apache-2.0", stringResource(R.string.qk_00546)),
            Triple(stringResource(R.string.qk_00547), "BSD-3-Clause", stringResource(R.string.qk_00548)),
            Triple("Mammoth.js", "BSD-2-Clause", stringResource(R.string.qk_00549)),
            Triple("SheetJS", "Apache-2.0", stringResource(R.string.qk_00550)),
            // ── PDF 字体 ──
            Triple("Liberation Sans", "SIL OFL 1.1", stringResource(R.string.qk_00551)),
            Triple("Foxit PDF Fonts", "BSD-3-Clause", stringResource(R.string.qk_00552)),
            Triple(stringResource(R.string.qk_00553), "Apache-2.0", stringResource(R.string.qk_00554)),
        )
        AlertDialog(
            onDismissRequest = { showLicense = false },
            confirmButton = {
                TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.qk_00065)) }
            },
            title = { Text(stringResource(R.string.qk_00510), style = MaterialTheme.typography.titleMedium) },
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
            confirmButton = { TextButton(onClick = { updateDialog = null }) { Text(stringResource(R.string.qk_00011)) } },
            title = { Text(stringResource(R.string.qk_00555, (updateVersion).toString()), style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.qk_00556))
                    Text(stringResource(R.string.qk_00557), fontSize = 12.sp, color = Muted)
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
                    ) { Text(stringResource(R.string.qk_00558)) }
                    TextButton(
                        onClick = { openUrl(ge); updateDialog = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.qk_00559)) }
                }
            },
        )
    }

    if (showPermissionStatement) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            QuroLegalDocScreen(
                title = stringResource(R.string.qk_00513),
                sections = permissionStatementSections(),
                onBack = { showPermissionStatement = false },
            )
        }
    }

    if (showUserAgreement) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            QuroLegalDocScreen(
                title = stringResource(R.string.qk_00515),
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
private fun downloadApk(context: android.content.Context, apkUrl: String, fileName: String = "ZorvAI-update.apk", version: String = "") {
    try {
        val downloadManager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle(qstr(R.string.qk_00560))
            .setDescription(qstr(R.string.qk_00561, (version).toString()))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        
        val downloadId = downloadManager.enqueue(request)
        Toast.makeText(context, qstr(R.string.qk_00562), Toast.LENGTH_SHORT).show()
        
        // 注意：实际安装需要监听下载完成并启动安装意图
        // 这里简化处理，用户可以从通知栏点击安装
    } catch (e: Exception) {
        Toast.makeText(context, qstr(R.string.qk_00563, (e.message).toString()), Toast.LENGTH_SHORT).show()
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
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143)) }
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
            Text(stringResource(R.string.qk_00564),
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
    "" to qstr(R.string.qk_00565),
    
    "一、基础网络与设备权限" to 
    qstr(R.string.qk_00567) +
    qstr(R.string.qk_00568) +
    qstr(R.string.qk_00569) +
    qstr(R.string.qk_00570) + qstr(R.string.qk_00571),
    
    "二、音频与媒体权限" to 
    qstr(R.string.qk_00573) +
    qstr(R.string.qk_00574) + qstr(R.string.qk_00575),
    
    "三、存储与文件权限" to 
    qstr(R.string.qk_00577) +
    qstr(R.string.qk_00578) +
    qstr(R.string.qk_00579) + qstr(R.string.qk_00580),
    
    "四、电话与短信权限" to 
    qstr(R.string.qk_00582) +
    qstr(R.string.qk_00583) +
    qstr(R.string.qk_00584) +
    qstr(R.string.qk_00585) +
    qstr(R.string.qk_00586) + qstr(R.string.qk_00587),
    
    "五、联系人与日历权限" to 
    qstr(R.string.qk_00589) +
    qstr(R.string.qk_00590) +
    qstr(R.string.qk_00591) +
    qstr(R.string.qk_00592) +
    qstr(R.string.qk_00593) + qstr(R.string.qk_00594),
    
    "六、位置权限" to 
    qstr(R.string.qk_00596) +
    qstr(R.string.qk_00597) +
    qstr(R.string.qk_00598) + qstr(R.string.qk_00599),
    
    "七、通知与显示权限" to 
    qstr(R.string.qk_00601) +
    qstr(R.string.qk_00602) +
    qstr(R.string.qk_00603) + qstr(R.string.qk_00604),
    
    "八、系统与特殊权限" to 
    qstr(R.string.qk_00606) +
    qstr(R.string.qk_00607) +
    qstr(R.string.qk_00608) +
    qstr(R.string.qk_00609) +
    qstr(R.string.qk_00610) +
    qstr(R.string.qk_00611) +
    qstr(R.string.qk_00612) + qstr(R.string.qk_00613),
    
    "九、无障碍与自动化权限" to 
    qstr(R.string.qk_00615) +
    qstr(R.string.qk_00616) +
    qstr(R.string.qk_00617) + qstr(R.string.qk_00618),
    
    "十、前台服务权限" to 
    qstr(R.string.qk_00620) +
    qstr(R.string.qk_00621) +
    qstr(R.string.qk_00622) +
    qstr(R.string.qk_00623) +
    qstr(R.string.qk_00624) + qstr(R.string.qk_00625),
    
    "十一、包可见性权限" to 
    qstr(R.string.qk_00627) +
    qstr(R.string.qk_00628) + qstr(R.string.qk_00629),
    
    "十二、健康数据权限" to 
    qstr(R.string.qk_00631) +
    qstr(R.string.qk_00632) + qstr(R.string.qk_00633),
    
    "十三、ACI 协议权限" to 
    qstr(R.string.qk_00635) +
    qstr(R.string.qk_00636) +
    qstr(R.string.qk_00637) + qstr(R.string.qk_00638),
    
    "我们的承诺" to qstr(R.string.qk_00640),
    "权限的查询与撤销" to qstr(R.string.qk_00642),
)

/**
 * 用户使用协议内容：说明服务性质、账户凭证、用户义务与禁止行为、数据与隐私、第三方服务、
 * 知识产权、AI 内容免责、法律风险、法律责任、违法使用后果、未成年人保护、违规处理、协议变更、法律适用与争议解决等。
 */
private fun userAgreementSections(): List<Pair<String, String>> = listOf(
    "" to qstr(R.string.qk_00643),

    "一、服务说明" to qstr(R.string.qk_00645),

    "二、账户、凭证与 API Key" to qstr(R.string.qk_00647),

    "三、用户义务与禁止行为" to qstr(R.string.qk_00649),

    "四、数据与隐私" to qstr(R.string.qk_00651),

    "五、第三方服务与开源组件" to qstr(R.string.qk_00653),

    "六、知识产权" to qstr(R.string.qk_00655),

    "七、关于 AI 生成内容" to qstr(R.string.qk_00657),

    "八、法律风险提示" to qstr(R.string.qk_00659),

    "九、法律责任" to qstr(R.string.qk_00661),

    "十、违法使用及后果" to qstr(R.string.qk_00663),

    "十一、未成年人保护" to qstr(R.string.qk_00665),

    "十二、违规处理与终止" to qstr(R.string.qk_00667),

    "十三、协议的变更" to qstr(R.string.qk_00669),

    "十四、法律适用与争议解决" to qstr(R.string.qk_00671),

    "十五、其他条款" to qstr(R.string.qk_00673),

    "十六、联系我们" to qstr(R.string.qk_00675),
)