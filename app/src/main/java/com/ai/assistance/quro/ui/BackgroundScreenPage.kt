package com.ai.assistance.quro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后台虚拟屏的**管理界面**。
 *
 * 为什么需要它：后台屏是「用户看不见的屏」，如果没有可视化管理，AI 在里面做什么用户完全无感，
 * 也没法确认它到底卡在哪一步。这里把**实时画面 + 当前 Activity + 手动操作**摆出来。
 *
 * 交互：
 *  - 点画面 = 在后台屏对应坐标点一下（坐标按真实像素换算，与主屏一致）
 *  - 底部操作：返回键 / 刷新 / 截图到文件 / 释放
 *
 * 注意：**关掉这个页面不会销毁后台屏**，AI 仍在用；只有「释放」才销毁。
 */
@Composable
fun BackgroundScreenPage(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val state by QuroVirtualScreen.state.collectAsState()
    val frame by QuroVirtualScreen.frames.collectAsState()
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    // 进页面时若屏还没建，尝试建一次（多数情况是 AI 已经在用，已存在）
    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { QuroVirtualScreen.ensureDisplay(ctx) }
        QuroVirtualScreen.refreshTopActivity()
    }

    // 每 1.5s 刷新一次「当前 Activity」，让用户看到 AI 的进度
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            withContext(Dispatchers.IO) { QuroVirtualScreen.refreshTopActivity() }
        }
    }

    BackHandler { onClose() }

    // 真实屏尺寸：后台屏就是按主屏尺寸建的，坐标可直接换算
    val metrics = ctx.resources.displayMetrics
    val realW = metrics.widthPixels.coerceAtLeast(1)
    val realH = metrics.heightPixels.coerceAtLeast(1)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            // ── 顶栏 ──
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("后台虚拟屏", fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                    Text(
                        if (state.ready) "displayId = ${state.displayId}" else "未创建",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }

            // ── 错误提示 ──
            state.error?.let { err ->
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp)
                ) {
                    Text(
                        err,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            // ── 画面 ──
            Box(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .aspectRatio(realW.toFloat() / realH.toFloat())
                    .pointerInput(realW, realH, frame) {
                        detectTapGestures { offset ->
                            val bmp = frame ?: return@detectTapGestures
                            // 显示尺寸 → 真实像素
                            val sx = realW.toFloat() / size.width
                            val sy = realH.toFloat() / size.height
                            val x = (offset.x * sx).toInt().coerceIn(0, realW - 1)
                            val y = (offset.y * sy).toInt().coerceIn(0, realH - 1)
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { QuroVirtualScreen.tap(ctx, x, y) }
                                hint = if (ok) "已点击 ($x, $y)" else "点击失败（屏未就绪或注入被拒）"
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (frame != null) {
                    Image(
                        frame!!.asImageBitmap(),
                        contentDescription = "后台屏画面",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.ready) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "等待画面…\n先在对话里让 AI 用 bg_screen 打开一个 App",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 17.sp,
                            )
                        } else {
                            Text(
                                "后台屏还没有创建\n需要 Shizuku 已授权并运行中",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }

            // ── 当前 Activity ──
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("当前界面", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text(
                    state.topActivity?.substringAfterLast('.') ?: "—",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── 操作 ──
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(
                    enabled = !busy && state.ready,
                    onClick = {
                        scope.launch {
                            busy = true
                            val ok = withContext(Dispatchers.IO) { QuroVirtualScreen.back(ctx) }
                            hint = if (ok) "已发送返回键" else "返回失败"
                            busy = false
                        }
                    },
                ) { Text("返回键") }

                TextButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            withContext(Dispatchers.IO) { QuroVirtualScreen.refreshTopActivity() }
                            hint = "已刷新"
                            busy = false
                        }
                    },
                ) { Text("刷新") }

                TextButton(
                    enabled = !busy && frame != null,
                    onClick = {
                        scope.launch {
                            busy = true
                            val path = withContext(Dispatchers.IO) {
                                val png = QuroVirtualScreen.screenshotPng(ctx) ?: return@withContext null
                                val dir = java.io.File(ctx.cacheDir, "bg_screen").apply { mkdirs() }
                                val f = java.io.File(
                                    dir,
                                    "bg_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                        .format(java.util.Date()) + ".png",
                                )
                                f.writeBytes(png)
                                f.absolutePath
                            }
                            hint = if (path != null) "已保存：$path" else "截图失败（还没有画面）"
                            busy = false
                        }
                    },
                ) { Text("截图") }

                TextButton(
                    enabled = !busy && state.ready,
                    onClick = {
                        scope.launch {
                            busy = true
                            withContext(Dispatchers.IO) { QuroVirtualScreen.release() }
                            hint = "已释放后台屏"
                            busy = false
                        }
                    },
                ) { Text("释放", color = MaterialTheme.colorScheme.error) }
            }

            hint?.let {
                Text(
                    it,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "说明：这块屏是你的手机里单独的一块，AI 在上面打开 App、点按都不会打断你正在用的屏幕。\n" +
                    "关掉本页不会销毁它；只有点「释放」才会。",
                Modifier.padding(horizontal = 16.dp),
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 圆形小圆点（状态指示）。 */
@Composable
private fun StatusDot(ready: Boolean) {
    Box(
        Modifier.size(8.dp).clip(CircleShape)
            .background(
                if (ready) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
            )
    )
}
