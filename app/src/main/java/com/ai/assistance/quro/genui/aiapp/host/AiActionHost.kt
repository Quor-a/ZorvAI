package com.ai.assistance.quro.genui.aiapp.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import android.media.MediaPlayer
import com.ai.assistance.quro.genui.sdk.dsl.UISpec
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.interaction.ActionHost
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * AI 动作宿主
 * 实现 GenUI SDK 的 ActionHost 接口，处理 UI 组件触发的各种动作
 *
 * @property context Android 上下文
 */
class AiActionHost(
    private val context: Context
) : ActionHost {

    private val listeners = mutableListOf<(AiEvent) -> Unit>()

    // ===== 真实设备动作 v1.9 =====
    /** 二级界面压栈回调（由 ChatScreen 注入 ViewModel） */
    var onOpenScreen: ((UISpec) -> Unit)? = null
    /** 返回上一级回调 */
    var onGoBack: (() -> Unit)? = null
    /** 对话回传回调（选择/落子 → 新一轮生成），由 ChatScreen 注入 viewModel.send */
    var onSendMessage: ((String) -> Unit)? = null

    private var mediaPlayer: MediaPlayer? = null

    /**
     * 添加事件监听器
     */
    fun addListener(listener: (AiEvent) -> Unit) {
        listeners.add(listener)
    }

    /**
     * 移除事件监听器
     */
    fun removeListener(listener: (AiEvent) -> Unit) {
        listeners.remove(listener)
    }

    private fun dispatch(event: AiEvent) {
        listeners.forEach { it(event) }
    }

    override fun navigate(route: String, params: Map<String, String>) {
        dispatch(AiEvent.Navigate(route, params))
    }

    override fun handleCustom(handlerId: String, payload: JsonElement): Any? {
        dispatch(AiEvent.Custom(handlerId, payload))
        return null
    }

    override fun emit(name: String, payload: JsonElement) {
        dispatch(AiEvent.Emit(name, payload))
    }

    override fun openUrl(url: String) {
        if (url.isBlank()) return

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开链接：${e.message}", Toast.LENGTH_SHORT).show()
        }

        dispatch(AiEvent.OpenUrl(url))
    }

    override fun copyToClipboard(text: String) {
        if (text.isBlank()) return

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("GenUI", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "复制失败：${e.message}", Toast.LENGTH_SHORT).show()
        }

        dispatch(AiEvent.CopyToClipboard(text))
    }

    override fun haptic(type: String) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (type.lowercase()) {
                    "heavy" -> VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    "light" -> VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                    "double" -> VibrationEffect.createWaveform(longArrayOf(0, 30, 50, 30), -1)
                    else -> VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20)
            }
        } catch (_: Exception) {
            // 忽略振动失败
        }

        dispatch(AiEvent.Haptic(type))
    }

    override fun showDialog(dialog: UIComponent) {
        dispatch(AiEvent.ShowDialog(dialog))
    }

    override fun dismissDialog() {
        dispatch(AiEvent.DismissDialog)
    }

    override fun log(message: String) {
        dispatch(AiEvent.Log(message))
    }

    override suspend fun requestApi(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>
    ): JsonElement {
        dispatch(AiEvent.RequestApi(method, url, body, headers))
        return JsonNull
    }

    // ============================================================
    // 反馈类
    // ============================================================

    override fun showToast(message: String, duration: String, level: String) {
        val toastDuration = if (duration == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(context, message, toastDuration).show()
        dispatch(AiEvent.Toast(message, level))
    }

    override fun showSnackbar(message: String, actionText: String?, duration: String, level: String) {
        // Snackbar 需要 View，这里先降级为 Toast
        val toastDuration = if (duration == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(context, message, toastDuration).show()
        dispatch(AiEvent.Snackbar(message, level))
    }

    override fun vibrate(duration: Long, pattern: List<Long>) {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (pattern.isNotEmpty()) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern.toLongArray(), -1))
                } else {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } catch (_: Exception) { }
    }

    // ============================================================
    // 页面控制类
    // ============================================================

    override fun scrollTo(targetId: String, animated: Boolean) {
        dispatch(AiEvent.ScrollTo(targetId, animated))
    }

    override fun goBack() {
        val cb = onGoBack
        if (cb != null) cb() else dispatch(AiEvent.GoBack)
    }

    override fun sendMessage(text: String) {
        onSendMessage?.invoke(text)
            ?: Toast.makeText(context, "当前无法回传对话", Toast.LENGTH_SHORT).show()
    }

    override fun refresh(targetId: String?) {
        dispatch(AiEvent.Refresh(targetId))
    }

    override fun loadMore(targetId: String?) {
        dispatch(AiEvent.LoadMore(targetId))
    }

    // ============================================================
    // 分享类
    // ============================================================

    override fun share(text: String, title: String?, url: String?) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, buildString {
                    append(text)
                    if (url != null) append("\n").append(url)
                })
                if (title != null) putExtra(Intent.EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, title ?: "分享"))
        } catch (e: Exception) {
            Toast.makeText(context, "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
        dispatch(AiEvent.Share(text, url))
    }

    // ============================================================
    // 真实设备动作 v1.9 — 真打开、真播放、真分享、真跳转
    // ============================================================

    override fun openApp(packageName: String, action: String, fallbackUrl: String) {
        try {
            val intent = when {
                packageName.isNotBlank() -> context.packageManager.getLaunchIntentForPackage(packageName)
                action.isNotBlank() -> Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                else -> null
            }
            if (intent != null) {
                context.startActivity(intent)
            } else throw IllegalStateException("未找到目标应用")
        } catch (e: Exception) {
            if (fallbackUrl.isNotBlank()) openUrl(fallbackUrl)
            else Toast.makeText(context, "无法打开应用：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun playMedia(url: String, type: String, title: String) {
        if (url.isBlank()) return
        if (type == "video") {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "未找到视频播放器", Toast.LENGTH_SHORT).show()
            }
            return
        }
        try {
            stopMedia()
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                it.start()
                Toast.makeText(context, if (title.isBlank()) "▶ 正在播放" else "▶ 正在播放：$title", Toast.LENGTH_SHORT).show()
            }
            mp.setOnErrorListener { _, what, extra ->
                Toast.makeText(context, "播放失败($what/$extra)", Toast.LENGTH_SHORT).show()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(context, "播放失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun stopMedia() {
        mediaPlayer?.let { runCatching { if (it.isPlaying) it.stop(); it.release() } }
        mediaPlayer = null
    }

    override fun openScreen(spec: UISpec) {
        val cb = onOpenScreen
        if (cb != null) cb(spec)
        else Toast.makeText(context, "当前页面不支持二级跳转", Toast.LENGTH_SHORT).show()
    }

    override fun openHtml(html: String, title: String) {
        if (html.isBlank()) return
        try {
            val intent = Intent(context, HtmlViewerActivity::class.java).apply {
                putExtra("html", html)
                putExtra("title", title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开页面：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun executeAction(action: String, params: Map<String, String>) {
        try {
            val intent = when (action) {
                "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:${params["tel"] ?: ""}"))
                "sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${params["to"] ?: ""}")).putExtra("sms_body", params["text"] ?: "")
                "email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${params["to"] ?: ""}"))
                "web_search" -> Intent(Intent.ACTION_WEB_SEARCH).putExtra("query", params["q"] ?: "")
                "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
                "play" -> Intent(Intent.ACTION_VIEW, Uri.parse(params["url"] ?: "")).setDataAndType(Uri.parse(params["url"] ?: ""), "audio/*")
                else -> Intent(action)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "无法执行：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

}