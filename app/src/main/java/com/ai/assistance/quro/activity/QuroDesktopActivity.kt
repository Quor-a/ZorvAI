package com.ai.assistance.quro.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import com.ai.assistance.quro.ui.ImmersiveWorkSurface
import com.ai.assistance.quro.ui.enterImmersiveFullscreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面模式宿主（可选 Launcher）。
 *
 * ## 为什么需要这个 Activity
 *
 * [libtaskbar](https://github.com/farmerbb/libtaskbar) 是**唯一官方支持「当库引入」**的
 * Android 10 桌面模式实现（farmerbb 的 Taskbar 拆出的 AAR，JitPack 上 2.2.0）。
 * 但它的生效前提很硬：
 *  1. 宿主 App **必须是系统默认 launcher**；
 *  2. 用户要打开开发者选项里的「Enable freeform windows」与「Force desktop mode」；
 *  3. 接外接屏（HDMI / 投屏）时出现任务栏。
 *
 * ZorvAI 原本不是 launcher，所以光加依赖不会生效。这里提供可选桌面入口 ——
 * **只有用户自己把这个 Activity 设为默认桌面时才会接管**，不影响未设默认的用户。
 *
 * ## 全屏约定（2026-11-05 重写）
 * 旧版在顶部堆了 180dp 光晕头 + 「ZorvAI 桌面」标题行 + 两个按钮 + 两行说明 +
 * 若干 Spacer，应用网格被挤到屏幕下半截，隐藏系统栏也铺不满。现在：
 * - 系统栏由 [enterImmersiveFullscreen] 边到边隐藏（含挖孔区、透明系统栏底色）；
 * - 墙纸是**整屏背景**，品牌光晕只是背景画刷，不是一行布局；
 * - 应用网格 [LazyVerticalGrid] 直接 fillMaxSize，只用 contentPadding 给控制条留白；
 * - 设置 / 开发者选项 / 说明 / 返回主应用全部收进 [ImmersiveWorkSurface] 的
 *   唯一一条悬浮控制条，3.2 秒后自动收起，画面即为纯全屏。
 */
class QuroDesktopActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🔴 真全屏：与工具中心的 ImmersiveSystemBars 同一套规则，独立 Activity 版。
        enterImmersiveFullscreen()
        setContent {
            MaterialTheme {
                DesktopHome()
            }
        }
    }
}

/** 桌面上一个应用条目。 */
private data class DesktopApp(
    val label: String,
    val packageName: String,
    val icon: android.graphics.Bitmap?,
)

@Composable
private fun DesktopHome() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<DesktopApp>>(emptyList()) }
    var note by remember { mutableStateOf("正在载入应用…") }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val pm: PackageManager = ctx.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val list = pm.queryIntentActivities(intent, 0)
                list.asSequence()
                    .filter { it.activityInfo.packageName != ctx.packageName }
                    .distinctBy { it.activityInfo.packageName }
                    .map { ri ->
                        val bmp = runCatching {
                            val d = ri.loadIcon(pm)
                            val w = (d.intrinsicWidth.takeIf { it > 0 } ?: 48).coerceAtMost(144)
                            val h = (d.intrinsicHeight.takeIf { it > 0 } ?: 48).coerceAtMost(144)
                            val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                            val c = android.graphics.Canvas(b)
                            d.setBounds(0, 0, w, h)
                            d.draw(c)
                            b
                        }.getOrNull()
                        DesktopApp(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, bmp)
                    }
                    .sortedBy { it.label }
                    .toList()
            }.getOrDefault(emptyList())
        }
        apps = loaded
        note = if (loaded.isEmpty()) "没有可显示的应用（可能是权限受限）" else ""
    }

    val helpText =
        "libtaskbar 需要：① 本页设为默认桌面 ② 开发者选项里打开「自由窗口」与「强制桌面模式」③ 接外接屏时才出现任务栏"

    val desktopActions: List<Pair<String, () -> Unit>> = listOf(
        "桌面模式设置" to {
            runCatching { com.farmerbb.taskbar.lib.Taskbar.openSettings(ctx) }
                .onFailure { Toast.makeText(ctx, "Taskbar 设置打不开：$it", Toast.LENGTH_SHORT).show() }
        },
        "开发者选项" to {
            com.ai.assistance.quro.core.adb.QuroAdbDebug.openDeveloperOptions(ctx)
        },
        "返回主应用" to {
            runCatching {
                ctx.startActivity(
                    Intent(ctx, QuroMainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { Toast.makeText(ctx, "返回失败：$it", Toast.LENGTH_SHORT).show() }
        },
        "使用说明" to { Toast.makeText(ctx, helpText, Toast.LENGTH_LONG).show() },
    )

    ImmersiveWorkSurface(
        title = "ZorvAI 桌面",
        chromeContainerColor = Color(0xCC211E1A),
        chromeContentColor = Color(0xFFF4F1EA),
        menuItems = desktopActions,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // 暖墨底 + 陶土橙光晕：全部是背景画刷，不占任何一行的高度
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF26221D), Color(0xFF1B1814), Color(0xFF211E1A)),
                    ),
                )
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFFC25A38).copy(alpha = 0.28f), Color.Transparent),
                    ),
                ),
        ) {
            // 点缀金细线（品牌第二强调色），纯装饰，2dp 且不参与布局
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.5f)
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Color(0xFFB8902F), Color.Transparent),
                        ),
                    ),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 64.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(apps) { app ->
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                // 桌面里的应用全部开进后台虚拟屏：openApp 走 setLaunchDisplayId，
                                // 双开 App 自动查分身；悬浮窗/画中画显示画面并直接操控。
                                Thread {
                                    val r = QuroVirtualScreen.openApp(ctx, app.packageName)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
                                    }
                                }.start()
                            }
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1F2430)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val bmp = app.icon
                            if (bmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = app.label,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        }
                        Text(
                            app.label,
                            color = Color(0xFFE5E7EB),
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            if (note.isNotBlank()) {
                Text(
                    note,
                    color = Color(0xFF9CA3AF),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                )
            }
        }
    }
}
