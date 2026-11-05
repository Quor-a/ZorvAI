package com.ai.assistance.quro.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr
import com.ai.assistance.quro.service.QuroBgScreenOverlayService
import com.ai.assistance.quro.service.QuroBotBootstrapService
import com.ai.assistance.quro.service.QuroDesktopPetService
import com.ai.assistance.quro.service.QuroMediaProjectionService
import com.ai.assistance.quro.service.QuroNotificationListenerService
import com.ai.assistance.quro.service.QuroVideoCallService
import com.ai.assistance.quro.service.QuroVoiceBallService
import com.ai.assistance.quro.service.QuroWebUiService
import com.ai.assistance.quro.ui.theme.Accent
import com.ai.assistance.quro.ui.theme.Card
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import android.widget.Toast

/**
 * 后台进程管理页（参考 ZorvAI：把常驻服务从「黑盒」变成用户可查看、可控的面板）。
 *
 * 列出本应用的全部常驻前台服务：运行状态实时探测（ActivityManager#getRunningServices
 * 对本应用自身的服务仍然有效），可启动/停止的给出开关，系统托管或需特殊授权的给出说明。
 * 每行说明该进程「做什么、为什么常驻」，让用户清楚知道后台在跑什么、为什么耗电。
 */
private data class ProcessEntry(
    val id: String,
    val name: String,
    val desc: String,
    val icon: ImageVector,
    val cls: Class<*>,
    /** 是否提供启动开关（需要用户授权/系统托管的服务只展示状态）。 */
    val startable: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroBackgroundProcessScreen(onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current

    val entries = listOf(
        ProcessEntry(
            "voice_ball", qstr(R.string.qk_04600), qstr(R.string.qk_04610),
            Icons.Filled.Mic, QuroVoiceBallService::class.java, startable = true,
        ),
        ProcessEntry(
            "desktop_pet", qstr(R.string.qk_04590), qstr(R.string.qk_04611),
            Icons.Filled.Favorite, QuroDesktopPetService::class.java, startable = true,
        ),
        ProcessEntry(
            "webui", qstr(R.string.qk_04601), qstr(R.string.qk_04612),
            Icons.Filled.Language, QuroWebUiService::class.java, startable = true,
        ),
        ProcessEntry(
            "bot", qstr(R.string.qk_04602), qstr(R.string.qk_04613),
            Icons.Filled.SmartToy, QuroBotBootstrapService::class.java, startable = true,
        ),
        ProcessEntry(
            "bg_screen", qstr(R.string.qk_04603), qstr(R.string.qk_04614),
            Icons.Filled.DesktopWindows, QuroBgScreenOverlayService::class.java, startable = false,
        ),
        ProcessEntry(
            "video_call", qstr(R.string.qk_04604), qstr(R.string.qk_04615),
            Icons.Filled.Videocam, QuroVideoCallService::class.java, startable = false,
        ),
        ProcessEntry(
            "media_projection", qstr(R.string.qk_04605), qstr(R.string.qk_04616),
            Icons.Filled.ScreenShare, QuroMediaProjectionService::class.java, startable = false,
        ),
        ProcessEntry(
            "notify_listener", qstr(R.string.qk_04606), qstr(R.string.qk_04617),
            Icons.Filled.Notifications, QuroNotificationListenerService::class.java, startable = false,
        ),
    )

    fun isRunning(cls: Class<*>): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.packageName == ctx.packageName && it.service.className == cls.name }
    }

    // 用本地 state 镜像运行状态：开关后立即刷新（getRunningServices 是 binder 调用，不能每帧读）
    var runningMap by remember {
        mutableStateOf(entries.associate { it.id to isRunning(it.cls) })
    }
    fun refresh() { runningMap = entries.associate { it.id to isRunning(it.cls) } }

    Scaffold(
        containerColor = cs.background,
        topBar = {
            TopAppBar(
                title = { Text(qstr(R.string.qk_04620), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    Icon(
                        Icons.Filled.ArrowBack, qstr(R.string.qk_00011),
                        Modifier.size(24.dp).clickable { onClose() }.padding(4.dp),
                        tint = cs.onSurface,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    qstr(R.string.qk_04621),
                    fontSize = 13.sp, color = Muted,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            items(entries) { e ->
                val running = runningMap[e.id] == true
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Card, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(38.dp).background(
                                if (running) Accent.copy(alpha = 0.14f) else Color.Gray.copy(alpha = 0.12f),
                                RoundedCornerShape(10.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) { Icon(e.icon, e.name, Modifier.size(20.dp), tint = if (running) Accent else Muted) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (running) qstr(R.string.qk_04622) else qstr(R.string.qk_04623),
                                    fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                    color = if (running) Accent else Muted,
                                    modifier = Modifier
                                        .background(
                                            (if (running) Accent else Muted).copy(alpha = 0.12f),
                                            RoundedCornerShape(6.dp),
                                        )
                                        .padding(6.dp, 2.dp),
                                )
                            }
                            Text(e.desc, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 2.dp))
                        }
                        if (e.startable) {
                            Switch(
                                checked = running,
                                onCheckedChange = { on ->
                                    runCatching {
                                        if (on) {
                                            if (e.cls == QuroDesktopPetService::class.java) {
                                                // 统一入口：内部做悬浮窗权限检查 + 开关持久化，
                                                // 避免这里裸 startForegroundService 绕过两者。
                                                QuroDesktopPetService.start(ctx)
                                            } else {
                                                ctx.startForegroundService(Intent(ctx, e.cls))
                                            }
                                        } else {
                                            if (e.cls == QuroDesktopPetService::class.java) {
                                                // 桌面宠物走统一停启（同步开关持久化）
                                                QuroDesktopPetService.stop(ctx)
                                            } else {
                                                ctx.stopService(Intent(ctx, e.cls))
                                            }
                                        }
                                    }.onFailure {
                                        Toast.makeText(ctx, it.message ?: "", Toast.LENGTH_SHORT).show()
                                    }
                                    // 服务启停是异步的，稍等一下再探测
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                                        { refresh() }, 400,
                                    )
                                },
                            )
                        }
                    }
                    if (!e.startable) {
                        Text(
                            qstr(R.string.qk_04624),
                            fontSize = 10.sp, color = Muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
