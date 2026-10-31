package com.ai.assistance.quro.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 内藏桌面 AI —— 打开即虚拟屏。
 *
 * ## 它是什么
 *
 * 一个**跑在后台虚拟屏上的桌面**：进来第一眼就是虚拟屏的全屏实时画面，
 * 应用从 Dock 打开、全部跑在虚拟屏里（主屏不被占用），画面即桌面、点画面即操作。
 * 用户原话：「内藏一个完整的桌面AI，通过这个内藏打开就是虚拟屏」。
 *
 * ## 与 Taskbar 桌面模式的关系
 *
 * Taskbar（libtaskbar）是**系统级**多窗口（把本应用设为默认桌面后的 freeform 窗口），
 * 本 Activity 是**应用内**的虚拟屏桌面 —— 两者入口都在这里：默认展示虚拟屏桌面，
 * Dock 上的「桌面模式」按钮才进 Taskbar 设置。不互相依赖，谁都能单独用。
 *
 * ## 前置
 *
 * 虚拟屏依赖 Shizuku（shell 身份 createVirtualDisplay）。没授权时给出明确引导，
 * 而不是白屏。
 */
class QuroDesktopActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B0E14)) {
                    AiDesktop(
                        onOpenTaskbarSettings = {
                            runCatching { com.farmerbb.taskbar.lib.Taskbar.openSettings(this) }
                                .onFailure {
                                    Toast.makeText(this, "Taskbar 设置打不开：$it", Toast.LENGTH_SHORT).show()
                                }
                        },
                    )
                }
            }
        }
    }
}

private data class DesktopApp(
    val label: String,
    val packageName: String,
)

/**
 * AI 桌面主体：上方是虚拟屏画面（可点直控），下方 Dock 放应用网格与控制。
 */
@Composable
private fun AiDesktop(onOpenTaskbarSettings: () -> Unit) {
    val ctx = LocalContext.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("正在启动后台虚拟屏…（需要 Shizuku 已授权）") }
    var showGrid by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<DesktopApp>>(emptyList()) }
    val scope = rememberCoroutineScope()

    // 建屏（首次进入自动建；失败原因直接摆在桌面上，不白屏）
    LaunchedEffect(Unit) {
        val ok = withContext(Dispatchers.IO) { QuroVirtualScreen.ensureDisplay(ctx) }
        if (ok != null) {
            status = ""
            // 屏就绪后把画面流接上（frames 是 StateFlow，collect 持续更新）
            QuroVirtualScreen.frames.collect { frame = it }
        } else {
            status = QuroVirtualScreen.lastError ?: "后台虚拟屏不可用"
        }
    }

    // 应用网格数据（懒加载：点开 Dock 才查）
    LaunchedEffect(showGrid) {
        if (showGrid && apps.isEmpty()) {
            apps = withContext(Dispatchers.IO) { queryLaunchableApps(ctx) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF0B0E14))) {

        // ── 虚拟屏画面区（占满剩余空间）──
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF11141C)),
        ) {
            val bmp = frame
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "后台虚拟屏实时画面",
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            // 直接操控虚拟屏（对照 Extendroid）：点击按比例换算成屏内坐标，
                            // 走无障碍手势通道（不需要 Shizuku 在线）
                            detectTapGestures { off ->
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                val dw = QuroVirtualScreen.frameWidth().toFloat()
                                val dh = QuroVirtualScreen.frameHeight().toFloat()
                                if (w <= 0f || h <= 0f || dw <= 0f || dh <= 0f) return@detectTapGestures
                                val x = (off.x / w * dw).toInt().coerceIn(0, dw.toInt() - 1)
                                val y = (off.y / h * dh).toInt().coerceIn(0, dh.toInt() - 1)
                                scope.launch(Dispatchers.IO) { QuroVirtualScreen.tapAt(ctx, x, y) }
                            }
                        },
                    contentScale = ContentScale.Fit,
                )
            }
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    color = Color(0xFFFFB4AB),
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }

        // ── Dock ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161A23))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockBtn("应用") { showGrid = !showGrid }
            DockBtn("返回") {
                scope.launch(Dispatchers.IO) { QuroVirtualScreen.back(ctx) }
            }
            DockBtn("多任务") {
                scope.launch(Dispatchers.IO) {
                    val top = QuroVirtualScreen.topActivity(ctx)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            ctx,
                            if (top != null) "当前：$top" else "虚拟屏上没有运行的任务",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
            DockBtn("分身") {
                scope.launch(Dispatchers.IO) {
                    val info = QuroVirtualScreen.listAppCopies(ctx)
                    withContext(Dispatchers.Main) {
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle("应用分身（双开/工作资料）")
                            .setMessage(info)
                            .setPositiveButton("好的", null)
                            .show()
                    }
                }
            }
            DockBtn("桌面模式") { onOpenTaskbarSettings() }
        }
    }

    // 应用网格（覆盖层）
    if (showGrid) {
        AlertDialog(
            onDismissRequest = { showGrid = false },
            confirmButton = { TextButton(onClick = { showGrid = false }) { Text("关闭") } },
            title = { Text("打开应用到虚拟屏") },
            text = {
                LazyVerticalGrid(columns = GridCells.Fixed(4), modifier = Modifier.height(420.dp)) {
                    items(apps) { app ->
                        Text(
                            text = app.label,
                            fontSize = 11.sp,
                            maxLines = 2,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    showGrid = false
                                    scope.launch(Dispatchers.IO) {
                                        QuroVirtualScreen.openApp(ctx, app.packageName)
                                    }
                                }
                                .padding(8.dp),
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun DockBtn(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color(0xFFE6E1E5),
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** 查全部可启动应用（桌面网格数据源）。 */
private fun queryLaunchableApps(ctx: Context): List<DesktopApp> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return runCatching {
        pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo?.packageName != ctx.packageName }
            .distinctBy { it.activityInfo?.packageName }
            .map {
                DesktopApp(
                    it.loadLabel(pm)?.toString() ?: it.activityInfo?.packageName ?: "?",
                    it.activityInfo?.packageName ?: "",
                )
            }
            .filter { it.packageName.isNotBlank() }
            .sortedBy { it.label.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
}
