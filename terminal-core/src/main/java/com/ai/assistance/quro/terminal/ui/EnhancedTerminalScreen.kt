package com.ai.assistance.quro.terminal.ui

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.KeyEvent
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.quro.terminal.runtime.TerminalEnvironment
import com.ai.assistance.quro.terminal.vt.VtTerminalBridge
import com.ai.assistance.quro.vt.TerminalView
import java.io.File

private const val TAG = "EnhancedTerminalScreen"

/**
 * 增强终端页（vtlib 完整能力）。
 *
 * 使用 Termux terminal-emulator + terminal-view 移植栈渲染，支持：
 * 双缓冲/备用屏幕/滚动/sixel/24 位真彩/鼠标协议/括号粘贴/OSC52/文本选择/额外按键栏。
 */
@Composable
fun EnhancedTerminalScreen(
    environment: TerminalEnvironment,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val bridge = remember { VtTerminalBridge(context, environment) }
    var title by remember { mutableStateOf("增强终端") }
    var sessionFinished by remember { mutableStateOf(false) }
    var sessionExitCode by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        // 写入默认属性文件（若不存在），用户可在 ~/.termux/termux.properties 修改
        writeDefaultProperties(context)

        val ok = bridge.start()
        Log.d(TAG, "bridge.start() = $ok")
        if (ok) {
            bridge.onTitleChanged = { title = it ?: "增强终端" }
            bridge.onSessionFinished = { code ->
                sessionExitCode = code
                sessionFinished = true
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            bridge.stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ComposeColor(0xFF0B0E14))
    ) {
        // 顶部栏
        Surface(
            color = ComposeColor(0xFF161B22),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = ComposeColor(0xFFE6EDF3)
                    )
                }
                Text(
                    text = title,
                    color = ComposeColor(0xFFE6EDF3),
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        if (sessionFinished) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "会话已结束（exit=${sessionExitCode ?: "?"}）\n返回后重新进入可开启新会话",
                    color = ComposeColor(0xFFE6EDF3)
                )
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    createTerminalLayout(ctx, bridge)
                },
                update = { layout ->
                    // 确保尺寸变化时 TerminalView 更新
                    layout.requestLayout()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** 创建 终端视图 + 额外按键栏 的垂直布局。 */
private fun createTerminalLayout(context: Context, bridge: VtTerminalBridge): LinearLayout {
    val layout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(0x0B, 0x0E, 0x14))
    }

    val terminalView = TerminalView(context, null).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        setBackgroundColor(Color.rgb(0x0B, 0x0E, 0x14))
    }

    val extraKeysContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    layout.addView(terminalView)
    layout.addView(extraKeysContainer)

    // 绑定会话
    bridge.attach(terminalView)

    // 创建额外按键栏（仅在会话存在时）
    if (bridge.isRunning) {
        bridge.createExtraKeysView(extraKeysContainer)
    }

    return layout
}

/** 写入默认 termux.properties（若不存在）。 */
private fun writeDefaultProperties(context: Context) {
    runCatching {
        val dir = File(context.filesDir, ".termux")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "termux.properties")
        if (!file.exists()) {
            val defaults = """
                # ZorvAI 终端属性（参考 Termux termux.properties）
                # 修改后重新进入终端页生效

                # 额外按键栏布局（JSON 数组，每行一个数组）
                extra-keys = [['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]

                # 光标样式：block / underline / bar
                # terminal-cursor-style = block

                # 光标闪烁速率（毫秒，0=不闪烁）
                # terminal-cursor-blink-rate = 0

                # 历史回滚行数
                # terminal-transcript-rows = 5000

                # 回退键行为：back / escape
                # back-key = back

                # 音量键行为：virtual / volume
                # volume-keys = virtual

                # 夜间模式：true / false / system
                # night-mode = system

                # 强制字符输入（部分键盘兼容）
                # enforce-char-based-input = false

                # 启动时隐藏软键盘
                # hide-soft-keyboard-on-startup = false
            """.trimIndent() + "\n"
            file.writeText(defaults)
        }
    }.onFailure {
        Log.e(TAG, "写入默认属性文件失败", it)
    }
}