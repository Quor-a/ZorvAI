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
