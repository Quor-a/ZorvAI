package com.ai.assistance.quro.ui
import androidx.compose.ui.res.stringResource
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LSPosed / Xposed 模块入口页（路线图标 ①：设置-权限添加 LSPosed）。
 *
 * Zorv AI 自身的能力（ACI / 无障碍 / Shizuku / 设备管理员 / ROOT）走自有管线，
 * 不在此定义任何 `ai.aci.permission.*`（定义权属控制端）。本页只负责：
 *  - 探测本机是否安装了 LSPosed / EdXposed / 原版 Xposed 管理器；
 *  - 展示 Zorv AI 是否已被纳入框架作用域（引导用户在管理器里勾选）；
 *  - 一键跳转对应管理器（若已安装）做作用域 / 模块管理。
 *
 * 仅做探测与引导；不请求任何敏感权限、不定义任何 ai.aci.permission.*（定义权属控制端）。
 * 现已提供 opt-in LSPosed 模块（QuroXposedModule）：被纳入作用域即写入作用域标记，
 * 可选开启跨应用注入 / 系统重定向桥（由外部 lsposed_bridge.json 配置驱动，默认关闭）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroLsposeScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current

    /** 已安装的框架管理器：包名 + 展示名。 */
    var managers by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var probing by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) { detectManagers(ctx) }
        managers = found
        probing = false
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部条
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.qk_00143), tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.qk_02024), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            val cs = MaterialTheme.colorScheme
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (managers.isNotEmpty()) Accent else Card).padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    if (probing) stringResource(R.string.qk_01688) else if (managers.isNotEmpty()) stringResource(R.string.qk_00443) else stringResource(R.string.qk_02025),
                    fontSize = 12.sp, color = if (managers.isNotEmpty()) Color.White else cs.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = Line)

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            GroupCaption(stringResource(R.string.qk_02026))
            SetGroup {
                if (probing) {
                    SetRowClickable(Icons.Filled.Sync, stringResource(R.string.qk_02027), stringResource(R.string.qk_02028), "", {}, scaled = { it.sp })
                } else if (managers.isEmpty()) {
                    SetRowClickable(
                        Icons.Filled.Extension, stringResource(R.string.qk_02029),
                        stringResource(R.string.qk_02030), "", {}, scaled = { it.sp },
                    )
                } else {
                    managers.forEachIndexed { idx, (pkg, name) ->
                        if (idx > 0) HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                        SetRowClickable(
                            Icons.Filled.Extension, name, qstr(R.string.qk_02031), "",
                            onClick = { openManager(ctx, pkg) }, scaled = { it.sp },
                        )
                    }
                }
            }

            GroupCaption(stringResource(R.string.qk_02032))
            SetGroup {
                SetRowClickable(
                    Icons.Filled.CheckCircle, stringResource(R.string.qk_02033),
                    stringResource(R.string.qk_02034), "",
                    onClick = {
                        if (managers.isNotEmpty()) openManager(ctx, managers.first().first)
                        else Toast.makeText(ctx, qstr(R.string.qk_02035), Toast.LENGTH_SHORT).show()
                    }, scaled = { it.sp },
                )
                HorizontalDivider(color = Line, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))
                SetRowClickable(
                    Icons.Filled.Tune, stringResource(R.string.qk_02036),
                    stringResource(R.string.qk_02037), "",
                    onClick = {
                        if (managers.isNotEmpty()) openManager(ctx, managers.first().first)
                        else Toast.makeText(ctx, qstr(R.string.qk_02035), Toast.LENGTH_SHORT).show()
                    }, scaled = { it.sp },
                )
            }

            GroupCaption(stringResource(R.string.qk_01691))
            SetGroup {
                InfoLine(stringResource(R.string.qk_02038))
                InfoLine(stringResource(R.string.qk_02039))
                InfoLine(stringResource(R.string.qk_02040))
            }
        }
    }
}

/** 探测常见框架管理器是否已安装。 */
private fun detectManagers(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val known = listOf(
        "org.lsposed.manager" to "LSPosed Manager",
        "com.tsng.edxposed" to "EdXposed Manager",
        "org.meowcat.edxposed" to "EdXposed Manager",
        "de.robv.android.xposed.installer" to "Xposed Installer",
    )
    return known.mapNotNull { (pkg, name) ->
        runCatching { pm.getPackageInfo(pkg, 0) }.fold(
            onSuccess = { pkg to name },
            onFailure = { null },
        )
    }
}

/** 打开已安装的管理器（尽力启动其 LAUNCHER Activity）。 */
private fun openManager(ctx: Context, pkg: String) {
    runCatching {
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } else {
            // 无 LAUNCHER：尝试打开应用详情页
            val details = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            details.data = android.net.Uri.parse("package:$pkg")
            details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(details)
        }
    }.onFailure {
        Toast.makeText(ctx, qstr(R.string.qk_02041, (pkg).toString(), (it.message).toString()), Toast.LENGTH_SHORT).show()
    }
}

/** 纯文本说明行（编辑排版风）。 */
@Composable
private fun InfoLine(text: String) {
    Text(
        text, fontSize = 12.sp, color = Muted, lineHeight = 18.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    )
}