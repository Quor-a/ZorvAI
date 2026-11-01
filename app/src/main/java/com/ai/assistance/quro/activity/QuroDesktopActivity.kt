package com.ai.assistance.quro.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.display.QuroVirtualScreen
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
 * ## 这个桌面做什么
 *
 * - 应用网格：点一下**开进后台虚拟屏**（画面可在悬浮窗/画中画里直接操控）；
 * - 顶部入口：libtaskbar 的桌面模式设置、开发者选项；
 * - 不抢占 LAUNCHER：主 Activity 仍是 ZorvAI 自己的入口，这里只声明 HOME/DEFAULT。
 */
class QuroDesktopActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🔴 全屏沉浸（用户要求「桌面还不是全屏」）：隐藏系统栏，画面铺满
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let { c ->
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // 双保险：部分 ROM 上 insets controller 不彻底，再补传统全屏旗标
        @Suppress("DEPRECATION")
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
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
                val pm = ctx.packageManager
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

    // 🔴 桌面墙纸（ZorvAI 风格，用户要求「不是一片黑」）：
    // 用 ZorvAI 官方主题色系（QuroTheme.kt）—— 暖墨底 #211E1A + 陶土橙 #C25A38 光晕 +
    // 点缀金 #B8902F 顶边，纸色 #F4F1EA 文字。替换上一版不合品牌的深空蓝黑。
    Column(
        Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xFF26221D), Color(0xFF1B1814), Color(0xFF211E1A)),
                ),
            ),
    ) {
        // 顶部：陶土橙光晕（品牌主色）压出暖调纵深
        Box(
            Modifier
                .fillMaxWidth()
                .height(180.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(360.dp, 220.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            listOf(Color(0xFFC25A38).copy(alpha = 0.50f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            // 点缀金细线（品牌第二强调色），呼应消息流里的金色点缀
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.5f)
                    .height(2.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color.Transparent, Color(0xFFB8902F), Color.Transparent),
                        ),
                    ),
            )
            Text(
                text = "桌面",
                color = Color(0xFFF4F1EA),
                fontSize = 22.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ZorvAI 桌面", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Pills("桌面模式设置", Color(0xFF3B82F6)) {
                runCatching { com.farmerbb.taskbar.lib.Taskbar.openSettings(ctx) }
                    .onFailure { Toast.makeText(ctx, "Taskbar 设置打不开：$it", Toast.LENGTH_SHORT).show() }
            }
            Spacer(Modifier.width(8.dp))
            Pills("开发者选项", Color(0xFF6B7280)) {
                com.ai.assistance.quro.core.adb.QuroAdbDebug.openDeveloperOptions(ctx)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "libtaskbar 需要：① 本页设为默认桌面 ② 开发者选项里打开「自由窗口」与「强制桌面模式」" +
                " ③ 接外接屏时才出现任务栏",
            color = Color(0xFF9CA3AF), fontSize = 11.sp,
        )
        if (note.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(note, color = Color(0xFF9CA3AF), fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(apps) { app ->
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                        // 🔴 桌面里的应用**全部开进后台虚拟屏**（用户要求「后台虚拟屏内操控软件」）：
                        // openApp 走 setLaunchDisplayId，双开 App 自动查分身（QuroVirtualScreen 内处理）。
                        // 悬浮窗/画中画显示画面，画面上的触摸就是直接操控这个 App。
                        Thread {
                            val r = com.ai.assistance.quro.core.display.QuroVirtualScreen.openApp(
                                ctx, app.packageName,
                            )
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                Toast.makeText(ctx, r, Toast.LENGTH_SHORT).show()
                            }
                        }.start()
                    }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
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
                    Spacer(Modifier.height(4.dp))
                    Text(
                        app.label, color = Color(0xFFE5E7EB), fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Pills(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = 0.22f))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}
