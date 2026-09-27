package com.ai.assistance.quro.core.ui.dynamicui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.core.net.toUri
import com.ai.assistance.quro.genui.aiapp.host.HtmlViewerActivity
import com.ai.assistance.quro.genui.sdk.dsl.UIComponent
import com.ai.assistance.quro.genui.sdk.dsl.UISpec
import com.ai.assistance.quro.genui.sdk.interaction.ActionHost
import com.ai.assistance.quro.genui.sdk.interaction.DefaultActionHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把嵌入在动态 UI（quro-ui）里的 GenUI 组件交互桥接回 quro-ui 的回调循环。
 *
 * 设计：GenUI SDK 是一套独立的「生成式界面」渲染体系（530+ 原生组件，由 GenUiAgentActivity 作为
 * 独立应用承载）。本类让这套组件可以直接渲染在 quro-ui 面板内，并把组件里的交互（按钮、表单、事件、
 * 导航、弹窗、媒体等）回传给主聊天的 AI——从而把「完整 GenUI Agent 的原生组件能力」接进动态 UI 组件。
 *
 * 关键认知（修正）：GenUI Agent 的组件是「拼」出来的，是否全屏只是宿主差异，**渲染与交互能力本身与
 * 宿主无关**。因此本宿主必须和全屏 Agent 的 AiActionHost 一样，实现 ActionHost 的**全部**方法，
 * 而不是只桥接其中几个——否则 navigate / showDialog / openScreen / playMedia 等在对话框里会变成空操作，
 * 给人「只能在全屏用」的错觉。
 *
 * 桥接策略：
 *  - sendMessage / emit / navigate / handleCustom / executeAction / refresh / loadMore /
 *    scrollTo / goBack / requestApi / showDialog / dismissDialog / openScreen / log
 *    → 转成 QuroCallbackAction，交给 onAction（与 quro-ui 普通 callback 同一条管道，回发成用户消息
 *    给主聊天 AI），实现「动态 UI 里渲染的 GenUI 组件可交互、可对话、可导航」。
 *  - openUrl / copyToClipboard / haptic / vibrate / showToast / showSnackbar / share /
 *    openApp / playMedia / stopMedia / openHtml → 直接落地本机真实能力（与全屏 Agent 一致）。
 */
class QuroUiGenUiHost(
    private val context: Context,
    private val onAction: (QuroUiAction, Map<String, String>) -> Unit,
) : DefaultActionHost() {

    private val json = Json { ignoreUnknownKeys = true }

    private var mediaPlayer: MediaPlayer? = null

    // ===== 对话回传类 =====

    override fun sendMessage(text: String) {
        if (text.isBlank()) return
        onAction(
            QuroCallbackAction(event = "genui_message", data = mapOf("text" to text)),
            emptyMap(),
        )
    }

    override fun emit(name: String, payload: JsonElement) {
        val value = when (payload) {
            is JsonPrimitive -> if (payload.isString) payload.content else payload.toString()
            else -> payload.toString()
        }
        onAction(
            QuroCallbackAction(event = name, data = mapOf("event" to name, "value" to value)),
            emptyMap(),
        )
    }

    override fun navigate(route: String, params: Map<String, String>) {
        onAction(
            QuroCallbackAction(
                event = "genui_navigate",
                data = mapOf("route" to route, "params" to params.entries.joinToString(";") { "${it.key}=${it.value}" }),
            ),
            emptyMap(),
        )
    }

    override fun handleCustom(handlerId: String, payload: JsonElement): Any? {
        onAction(
            QuroCallbackAction(
                event = "genui_custom",
                data = mapOf("handler" to handlerId, "payload" to payload.toString()),
            ),
            emptyMap(),
        )
        return null
    }

    override fun executeAction(action: String, params: Map<String, String>) {
        onAction(
            QuroCallbackAction(
                event = "genui_action",
                data = mapOf("action" to action) + params.mapKeys { "p_${it.key}" },
            ),
            emptyMap(),
        )
    }

    override fun refresh(targetId: String?) {
        onAction(
            QuroCallbackAction(event = "genui_refresh", data = mapOf("targetId" to (targetId ?: ""))),
            emptyMap(),
        )
    }

    override fun loadMore(targetId: String?) {
        onAction(
            QuroCallbackAction(event = "genui_load_more", data = mapOf("targetId" to (targetId ?: ""))),
            emptyMap(),
        )
    }

    override fun scrollTo(targetId: String, animated: Boolean) {
        onAction(
            QuroCallbackAction(
                event = "genui_scroll",
                data = mapOf("targetId" to targetId, "animated" to animated.toString()),
            ),
            emptyMap(),
        )
    }

    override fun goBack() {
        onAction(QuroCallbackAction(event = "genui_back"), emptyMap())
    }

    override suspend fun requestApi(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): JsonElement {
        onAction(
            QuroCallbackAction(
                event = "genui_request_api",
                data = mapOf(
                    "method" to method,
                    "url" to url,
                    "body" to (body ?: ""),
                    "headers" to headers.entries.joinToString(";") { "${it.key}=${it.value}" },
                ),
            ),
            emptyMap(),
        )
        return JsonNull
    }

    // ===== 弹窗 / 二级界面（序列化后回传 AI，由 AI 在对话框内重新渲染）=====

    override fun showDialog(dialog: UIComponent) {
        runCatching {
            onAction(
                QuroCallbackAction(event = "genui_dialog", data = mapOf("component" to json.encodeToString(dialog))),
                emptyMap(),
            )
        }.onFailure {
            onAction(QuroCallbackAction(event = "genui_dialog_raw", data = mapOf("error" to (it.message ?: ""))), emptyMap())
        }
    }

    override fun dismissDialog() {
        onAction(QuroCallbackAction(event = "genui_dismiss_dialog"), emptyMap())
    }

    override fun openScreen(spec: UISpec) {
        runCatching {
            onAction(
                QuroCallbackAction(event = "genui_open_screen", data = mapOf("spec" to json.encodeToString(spec))),
                emptyMap(),
            )
        }.onFailure {
            onAction(QuroCallbackAction(event = "genui_open_screen_raw", data = mapOf("error" to (it.message ?: ""))), emptyMap())
        }
    }

    override fun log(message: String) {
        onAction(QuroCallbackAction(event = "genui_log", data = mapOf("message" to message)), emptyMap())
    }

    // ===== 真实设备动作（与全屏 Agent 一致）=====

    override fun openUrl(url: String) {
        if (url.isBlank()) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "无法打开链接：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("genui", text))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "复制失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun haptic(type: String) {
        runCatching {
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
        }.onFailure { /* 忽略振动失败 */ }
    }

    override fun vibrate(duration: Long, pattern: List<Long>) {
        runCatching {
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
        }.onFailure { /* 忽略振动失败 */ }
    }

    override fun showToast(message: String, duration: String, level: String) {
        Toast.makeText(
            context,
            message,
            if (duration == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }

    override fun showSnackbar(message: String, actionText: String?, duration: String, level: String) {
        // Snackbar 需要 View，这里降级为 Toast（与全屏 Agent 一致）。
        Toast.makeText(
            context,
            message,
            if (duration == "long") Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).show()
    }

    override fun share(text: String, title: String?, url: String?) {
        runCatching {
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
        }.onFailure {
            Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun openApp(packageName: String, action: String, fallbackUrl: String) {
        runCatching {
            val intent = when {
                packageName.isNotBlank() -> context.packageManager.getLaunchIntentForPackage(packageName)
                action.isNotBlank() -> Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                else -> null
            }
            if (intent != null) {
                context.startActivity(intent)
            } else {
                throw IllegalStateException("未找到目标应用")
            }
        }.onFailure {
            if (fallbackUrl.isNotBlank()) openUrl(fallbackUrl)
            else Toast.makeText(context, "无法打开应用：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun playMedia(url: String, type: String, title: String) {
        if (url.isBlank()) return
        if (type == "video") {
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(url), "video/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.onFailure {
                Toast.makeText(context, "未找到视频播放器", Toast.LENGTH_SHORT).show()
            }
            return
        }
        runCatching {
            stopMedia()
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                it.start()
                Toast.makeText(
                    context,
                    if (title.isBlank()) "▶ 正在播放" else "▶ 正在播放：$title",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            mp.setOnErrorListener { _, what, extra ->
                Toast.makeText(context, "播放失败($what/$extra)", Toast.LENGTH_SHORT).show()
                true
            }
            mp.prepareAsync()
        }.onFailure {
            Toast.makeText(context, "播放失败：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun stopMedia() {
        mediaPlayer?.let { runCatching { if (it.isPlaying) it.stop(); it.release() } }
        mediaPlayer = null
    }

    override fun openHtml(html: String, title: String) {
        if (html.isBlank()) return
        runCatching {
            val intent = Intent(context, HtmlViewerActivity::class.java).apply {
                putExtra("html", html)
                putExtra("title", title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.onFailure {
            Toast.makeText(context, "无法打开页面：${it.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
