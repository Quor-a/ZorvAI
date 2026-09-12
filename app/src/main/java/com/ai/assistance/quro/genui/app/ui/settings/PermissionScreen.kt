package com.ai.assistance.quro.genui.app.ui.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.genui.app.agent.ToolGate
import com.ai.assistance.quro.genui.app.perms.PermRegistry
import com.ai.assistance.quro.genui.app.store.GenStore
import com.ai.assistance.quro.genui.app.ui.theme.GenTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 权限屏 = 三层结构：
 *  ① 概览条       —— 一眼看清"能开多少、开了多少"
 *  ② 系统权限     —— Android 运行时权限，未授权可一键请求，永久拒绝引导跳设置
 *  ③ 工具策略     —— L1-L5 分级门禁（控制 Agent 有没有资格碰这个族）
 *  ④ 审计日志     —— 谁在什么时候调了什么、结果如何
 *
 * 关掉某工具后，Agent 调用它会收到可操作的拒绝文案（不是干巴巴一句"无权限"）。
 */
@Composable
fun PermissionScreen(store: GenStore, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val gate = remember { ToolGate(store.context()) }
    var modes by remember { mutableStateOf(gate.loadAllModes()) }
    var audit by remember { mutableStateOf(gate.recentAudit()) }
    val counts = remember(modes) { gate.counts() }
    // 权限状态刷新信号：授权返回后重新计算
    var refresh by remember { mutableStateOf(0) }
    val overview = remember(refresh) { PermRegistry.overview(ctx) }

    BackHandler { onBack() }

    fun cycle(tool: String) {
        // 点按循环切换策略：允许 → 每次询问 → 拒绝 → 允许
        val next = when (gate.modeOf(tool)) {
            ToolGate.AuthMode.ALWAYS_ALLOW -> ToolGate.AuthMode.ALWAYS_ASK
            ToolGate.AuthMode.ALWAYS_ASK -> ToolGate.AuthMode.DENY
            else -> ToolGate.AuthMode.ALWAYS_ALLOW
        }
        gate.setMode(tool, next)
        modes = gate.loadAllModes()
    }

    Scaffold(
        containerColor = GenTheme.Screen,
        topBar = {
            Column(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .background(GenTheme.Screen).padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "← 返回", color = GenTheme.Amber, fontSize = 13.sp,
                        modifier = Modifier.clickable { onBack() }.padding(vertical = 6.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    Text("能 力 与 权 限", color = GenTheme.Text, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(60.dp))
                }
                // ① 概览条：信息层级 —— 数字大、说明小
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .background(GenTheme.Panel, RoundedCornerShape(10.dp))
                        .border(0.5.dp, GenTheme.Line, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${overview.first}",
                        color = if (overview.first == overview.second) GenTheme.Green else GenTheme.Amber,
                        fontSize = 20.sp, fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "/${overview.second}",
                        color = GenTheme.Dim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("系统权限已就绪", color = GenTheme.Text, fontSize = 11.sp)
                        Text(
                            "未开启的权限，对应工具会返回明确提示并引导你来这里",
                            color = GenTheme.Dim, fontSize = 9.sp
                        )
                    }
                }
            }
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            // ② 系统权限
            item {
                SectionHeader("系统权限 · Android")
            }
            items(PermRegistry.all.filter { PermRegistry.applies(it) }) { e ->
                SystemPermRow(entry = e, onChanged = { refresh++ })
                Spacer(Modifier.height(8.dp))
            }

            // ③ 工具策略
            item {
                SectionHeader("工具授权 · L1-L5 分级")
                Text(
                    "控制 Agent 是否有资格调用某类工具。点按切换：允许 → 每次询问 → 拒绝。被拒时模型会收到原因并自行调整方案。",
                    color = GenTheme.Dim, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            items(ToolGate.ALL) { tool ->
                val mode = modes[tool] ?: ToolGate.AuthMode.ALWAYS_ALLOW
                ToolRow(
                    tool = tool,
                    level = ToolGate.LEVEL[tool] ?: 1,
                    mode = mode,
                    used = counts[tool] ?: 0,
                    onClick = { cycle(tool) }
                )
                Spacer(Modifier.height(6.dp))
            }

            // ④ 审计
            item {
                SectionHeader("审计日志 · 最近调用")
            }
            if (audit.isEmpty()) {
                item {
                    Text(
                        "暂无调用记录。Agent 每次调用工具都会在这里留痕。",
                        color = GenTheme.Dim, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                items(audit) { entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(entry.optLong("ts"))),
                            color = GenTheme.Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            entry.optString("tool"), color = GenTheme.Amber, fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${entry.optString("action")} · ${entry.optString("detail")}",
                            color = GenTheme.Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }

            // 审计可能有更新，提供手动刷新
            item {
                Text(
                    "↻ 刷新审计日志",
                    color = GenTheme.Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable {
                            audit = gate.recentAudit()
                            modes = gate.loadAllModes()
                            refresh++
                        }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        color = GenTheme.Dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), letterSpacing = 1.5.sp
    )
}

/** 单个工具族行：等级徽标 + 说明 + 策略胶囊 + 累计次数 */
@Composable
private fun ToolRow(
    tool: String,
    level: Int,
    mode: ToolGate.AuthMode,
    used: Int,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 0.dp)
            .background(GenTheme.Panel, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tool, color = GenTheme.Text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                // 等级徽标：L1 最低、L5 最敏感，用颜色区分风险
                Text(
                    "L$level",
                    color = levelColor(level), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(levelColor(level).copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                if (used > 0) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "×$used", color = GenTheme.Dim, fontSize = 9.sp, fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(ToolGate.brief(tool), color = GenTheme.Dim, fontSize = 10.sp)
        }
        Text(
            when (mode) {
                ToolGate.AuthMode.ALWAYS_ALLOW -> "允许"
                ToolGate.AuthMode.ALWAYS_ASK -> "每次询问"
                ToolGate.AuthMode.DENY -> "拒绝"
            },
            color = when (mode) {
                ToolGate.AuthMode.ALWAYS_ALLOW -> GenTheme.Green
                ToolGate.AuthMode.ALWAYS_ASK -> GenTheme.Amber
                ToolGate.AuthMode.DENY -> GenTheme.Red
            },
            fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .background(GenTheme.PanelUp, RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

private fun levelColor(level: Int) = when (level) {
    1 -> GenTheme.Green
    2 -> GenTheme.Green
    3 -> GenTheme.Amber
    4 -> GenTheme.Amber
    else -> GenTheme.Red
}

/**
 * 单行 Android 系统权限。
 * 交互路径刻意设计成三段式，覆盖 Android 授权的全部真实情况：
 *  - 未请求过 → 直接弹系统请求框
 *  - 请求被拒（可再问）→ 仍弹系统框
 *  - 永久拒绝（系统不再弹）→ 跳应用详情页，用户手动开
 *  - 无运行时权限的（精确闹钟）→ 跳专用系统设置页
 */
@Composable
private fun SystemPermRow(entry: PermRegistry.Entry, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    var granted by remember { mutableStateOf(PermRegistry.satisfied(ctx, entry)) }

    val launcher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        granted = PermRegistry.satisfied(ctx, entry)
        onChanged()
    }

    fun openAppDetails() {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", ctx.packageName, null)
                )
            )
        }
    }

    fun openSpecialSettings() {
        val act = entry.settingsAction ?: return
        runCatching {
            ctx.startActivity(Intent(act, Uri.fromParts("package", ctx.packageName, null)))
        }.recoverCatching {
            openAppDetails()
        }
        onChanged()
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .background(GenTheme.Panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.label, color = GenTheme.Text, fontSize = 13.sp)
                if (entry.danger) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "敏感",
                        color = GenTheme.Red, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(GenTheme.Red.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(entry.why, color = GenTheme.Dim, fontSize = 10.sp, lineHeight = 14.sp)
            if (entry.tools.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row {
                    entry.tools.forEach { t ->
                        Text(
                            t,
                            color = GenTheme.Amber.copy(alpha = 0.85f), fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .background(GenTheme.PanelUp, RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        if (granted) {
            Text("已授权", color = GenTheme.Green, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            Text(
                "去授权 →", color = GenTheme.Amber, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(GenTheme.PanelUp, RoundedCornerShape(8.dp))
                    .clickable {
                        // 自启动：记录用户意图（开启本地开关），并跳应用详情引导去 ROM 自启动管理
                        if (entry.key == "autostart") {
                            com.ai.assistance.quro.genui.app.perms.PermRegistry.setAutostart(ctx, true)
                            openAppDetails()
                            onChanged()
                            return@clickable
                        }
                        val perm = entry.perm
                        when {
                            // 无运行时权限（精确闹钟）→ 只能跳系统设置
                            perm == null -> openSpecialSettings()
                            // 永久拒绝（系统不再弹框）→ 跳应用详情
                            activity != null &&
                                !androidx.core.app.ActivityCompat
                                    .shouldShowRequestPermissionRationale(activity, perm) &&
                                !PermRegistry.satisfied(ctx, entry) -> openAppDetails()
                            else -> launcher.launch(perm)
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}
