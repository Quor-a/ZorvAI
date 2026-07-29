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
 * 关于 Quro AI（纸感重设计）：品牌 hero + SetGroup/SetRowClickable 分组。
 * 「项目地址」「在 GitHub 点个 Star」跳转到开源仓库；
 * 「开源许可声明」弹出本应用所用第三方依赖的许可证清单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroAboutScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val versionName = remember { BuildConfig.VERSION_NAME }
    val repoUrl = "https://github.com/Quor-a/QuorAI"
    var showLicense by remember { mutableStateOf(false) }
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
                        "关于 Quro AI",
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
                    "Quro AI",
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
                    sub = "当前版本 v$versionName",
                    onClick = {
                        Toast.makeText(ctx, "已是最新版本 v$versionName", Toast.LENGTH_SHORT).show()
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
                    sub = "如果喜欢 Quro AI，欢迎点个 Star ⭐",
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
                    sub = "Quro AI",
                    onClick = { },
                )
                SetRowClickable(
                    icon = Icons.Filled.Copyright,
                    name = "版权",
                    sub = "© 2025 - 2026 Quro AI. 保留所有权利。",
                    onClick = { },
                )
            }
        }
    }

    if (showLicense) {
        val licenses = listOf(
            Triple("Quro AI（本应用）", "Apache-2.0", "源码以 Apache-2.0 开源，见仓库 LICENSE 文件"),
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
}
