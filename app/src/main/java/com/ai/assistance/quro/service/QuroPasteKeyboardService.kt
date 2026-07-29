package com.ai.assistance.quro.service

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ai.assistance.quro.core.QuroAssistant
import com.ai.assistance.quro.core.util.QuroServiceLifecycleOwner
import com.ai.assistance.quro.core.QuroConversationStore
import com.ai.assistance.quro.core.model.QuroModelConfigRepository
import com.ai.assistance.quro.core.network.QuroLlmClient
import com.ai.assistance.quro.core.tools.buildQuroRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 粘贴键盘服务（通用辅助能力）：
 * 在任意界面挂一个悬浮「伪装键盘」——用户在里面输入指令，AI 生成文本 → 复制到剪贴板 →
 * 经无障碍服务填入/粘贴到当前 App 的输入框。流程：AI → 剪贴板 → 悬浮窗 → 无障碍粘贴。
 *
 * 不针对任何特定 App，不自动发送、不含任何绕过风控/防封逻辑（按用户要求仅做通用层）。
 */
class QuroPasteKeyboardService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Main + SupervisorJob()) {

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private var composeLifecycleOwner: QuroServiceLifecycleOwner? = null

    private val store = QuroConversationStore()
    private val registry by lazy { buildQuroRegistry(this) }
    private val assistant by lazy { QuroAssistant(QuroLlmClient(), registry, store) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // 悬浮窗状态（Compose 直接观察这些 MutableState）
    private var expanded by mutableStateOf(true)
    private var prompt by mutableStateOf("")
    private var result by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var toast by mutableStateOf<String?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (composeView == null) buildWindow()
        return START_NOT_STICKY
    }

    private fun buildWindow() {
        val lo = QuroServiceLifecycleOwner()
        composeLifecycleOwner = lo
        val view = ComposeView(this).apply {
            // 官方公开扩展函数直接绑定 owner（operit 同款，无需反射）。
            // 必须在 addView 之前完成，否则 onAttachedToWindow 时 Compose 找不到
            // ViewTreeLifecycleOwner 直接抛 IllegalStateException 导致闪退。
            setViewTreeLifecycleOwner(lo)
            setViewTreeViewModelStoreOwner(lo)
            setViewTreeSavedStateRegistryOwner(lo)
            setContent {
                val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    PasteKeyboardUi()
                }
            }
        }
        composeView = view
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.BOTTOM
        windowManager.addView(view, params)
        lo.create(); lo.resume()
    }

    private fun removeWindow() {
        try { composeView?.let { windowManager.removeView(it) } } catch (_: Throwable) {}
        composeView = null
        composeLifecycleOwner?.destroy()
        composeLifecycleOwner = null
    }

    override fun onDestroy() {
        removeWindow()
        cancel()
        super.onDestroy()
    }

    // ── 业务逻辑 ──

    private fun generate() {
        val p = prompt.trim()
        if (p.isBlank() || busy) return
        busy = true; result = "生成中…"
        launch {
            val out = runCatching {
                val cfg = QuroModelConfigRepository(applicationContext).load()
                assistant.ask(applicationContext, cfg, buildSystemPrompt())
            }.getOrElse { "⚠️ 出错：${it.message}" }
            result = out
            busy = false
        }
    }

    private fun copyResult() {
        if (result.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("quro", result))
        showToast("已复制到剪贴板")
    }

    private fun pasteResult() {
        if (result.isBlank()) return
        val svc = QuroAccessibilityService.instance
        if (svc == null) { showToast("请先在系统设置开启 Quro 无障碍服务"); return }
        showToast(svc.performPaste(result))
    }

    private fun showToast(msg: String) {
        toast = msg
        mainHandler.postDelayed({ toast = null }, 2400)
    }

    private fun buildSystemPrompt(): String =
        "你是 Quro AI 的「粘贴键盘」助手。用户会给出一段指令，请直接生成可粘贴到任意 App 输入框/评论区的成品文本。" +
        "只输出最终文本本身，不要解释、不要加引号、不要 Markdown 代码块，除非用户明确要求格式。"

    @Composable
    private fun PasteKeyboardUi() {
        val cs = MaterialTheme.colorScheme
        Surface(
            tonalElevation = 6.dp,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = cs.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Keyboard, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("AI 粘贴键盘", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = cs.onSurface, modifier = Modifier.weight(1f))
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
                    TextButton(onClick = { removeWindow(); stopSelf() }) { Text("关闭") }
                }
                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = { Text("给 AI 的指令，例如：写一条夸朋友的评论") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 100.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { generate() }, enabled = !busy && prompt.isNotBlank(), modifier = Modifier.weight(1f)) {
                            Text(if (busy) "生成中…" else "生成")
                        }
                        OutlinedButton(onClick = { copyResult() }, enabled = result.isNotBlank()) { Text("复制") }
                        OutlinedButton(onClick = { pasteResult() }, enabled = result.isNotBlank()) {
                            Icon(Icons.Filled.ContentPaste, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp)); Text("粘贴")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (result.isNotBlank()) {
                        Surface(
                            color = cs.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
                        ) {
                            Text(
                                result, fontSize = 13.sp, color = cs.onSurface,
                                modifier = Modifier.fillMaxWidth().padding(10.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "粘贴：先在目标 App 点一下输入框，再点「粘贴」（走无障碍 SET_TEXT / PASTE）。需开启 Quro 无障碍服务。",
                        fontSize = 11.sp, color = cs.onSurfaceVariant,
                    )
                }
                if (toast != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(toast!!, fontSize = 12.sp, color = cs.primary)
                }
            }
        }
    }
}
