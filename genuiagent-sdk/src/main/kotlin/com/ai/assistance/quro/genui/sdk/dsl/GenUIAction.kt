package com.ai.assistance.quro.genui.sdk.dsl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.ai.assistance.quro.genui.sdk.interaction.FormStateBus
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull

@Serializable
sealed class GenUIAction {

    @Serializable
    @SerialName("navigate")
    data class Navigate(
        val route: String,
        val params: Map<String, String> = emptyMap()
    ) : GenUIAction()

    @Serializable
    @SerialName("update_state")
    data class UpdateState(
        val key: String,
        val value: JsonElement
    ) : GenUIAction()

    @Serializable
    @SerialName("toggle_state")
    data class ToggleState(
        val key: String
    ) : GenUIAction()

    @Serializable
    @SerialName("show_dialog")
    data class ShowDialog(
        val dialog: UIComponent
    ) : GenUIAction()

    @Serializable
    @SerialName("dismiss_dialog")
    data object DismissDialog : GenUIAction()

    @Serializable
    @SerialName("haptic")
    data class Haptic(
        val type: String = "click"
    ) : GenUIAction()

    @Serializable
    @SerialName("emit")
    data class Emit(
        val name: String,
        val payload: JsonElement
    ) : GenUIAction()

    @Serializable
    @SerialName("call_api")
    data class CallApi(
        val url: String,
        val method: String = "GET",
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val onSuccess: List<GenUIAction> = emptyList(),
        val onError: List<GenUIAction> = emptyList()
    ) : GenUIAction()

    @Serializable
    @SerialName("open_url")
    data class OpenUrl(
        val url: String
    ) : GenUIAction()

    @Serializable
    @SerialName("copy_to_clipboard")
    data class CopyToClipboard(
        val text: String
    ) : GenUIAction()

    @Serializable
    @SerialName("custom")
    data class Custom(
        val handlerId: String,
        val payload: JsonElement
    ) : GenUIAction() {
        /** GenUI 表单聚合：payload 里 collectFrom 列表的值并入后回传 */
        val collectedPayload: JsonElement
            get() {
                val obj = payload as? kotlinx.serialization.json.JsonObject ?: return payload
                val idsEl = obj["collectFrom"] as? kotlinx.serialization.json.JsonArray ?: return payload
                val idList = idsEl.mapNotNull { el ->
                    (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                }
                if (idList.isEmpty()) return payload
                val base = obj.toMutableMap()
                idList.forEach { id ->
                    FormStateBus.take(id)?.let { v ->
                        base[id] = kotlinx.serialization.json.JsonPrimitive(v)
                    }
                }
                base.remove("collectFrom")
                return kotlinx.serialization.json.JsonObject(base)
            }
    }

    @Serializable
    @SerialName("log")
    data class Log(
        val message: String
    ) : GenUIAction()

    // ============================================================
    // 反馈类 Action
    // ============================================================

    @Serializable
    @SerialName("toast")
    data class Toast(
        val message: String,
        val duration: String = "short", // short | long
        val level: String = "info"     // info | success | warning | error
    ) : GenUIAction()

    @Serializable
    @SerialName("snackbar")
    data class Snackbar(
        val message: String,
        val actionText: String? = null,
        val duration: String = "short",
        val level: String = "info"
    ) : GenUIAction()

    @Serializable
    @SerialName("vibrate")
    data class Vibrate(
        val duration: Long = 50,
        val pattern: List<Long> = emptyList()
    ) : GenUIAction()

    // ============================================================
    // 组件操作类 Action
    // ============================================================

    @Serializable
    @SerialName("register_component")
    data class RegisterComponent(
        val name: String,
        val description: String = "",
        val category: String = "custom",
        val template: UIComponent,
        val variables: Map<String, String> = emptyMap()
    ) : GenUIAction()

    @Serializable
    @SerialName("set_state")
    data class SetState(
        val key: String,
        val value: JsonElement
    ) : GenUIAction()

    @Serializable
    @SerialName("scroll_to")
    data class ScrollTo(
        val targetId: String,
        val animated: Boolean = true
    ) : GenUIAction()

    // ============================================================
    // 分享类 Action
    // ============================================================

    @Serializable
    @SerialName("share")
    data class Share(
        val text: String,
        val title: String? = null,
        val url: String? = null
    ) : GenUIAction()

    // ============================================================
    // 页面控制类 Action
    // ============================================================

    @Serializable
    @SerialName("go_back")
    data object GoBack : GenUIAction()

    @Serializable
    @SerialName("refresh")
    data class Refresh(
        val targetId: String? = null
    ) : GenUIAction()

    @Serializable
    @SerialName("load_more")
    data class LoadMore(
        val targetId: String? = null
    ) : GenUIAction()

    // ============================================================
    // 真实设备动作 v1.9
    // ============================================================

    /** 真打开 App（包名启动；失败回退网页） */
    @Serializable
    @SerialName("open_app")
    data class OpenApp(
        val packageName: String = "",
        val action: String = "",
        val fallbackUrl: String = ""
    ) : GenUIAction()

    /** 真播放：audio=内置播放器流式播放；video=调起播放器 */
    @Serializable
    @SerialName("play_media")
    data class PlayMedia(
        val url: String,
        val type: String = "audio",
        val title: String = ""
    ) : GenUIAction()

    @Serializable
    @SerialName("stop_media")
    data object StopMedia : GenUIAction()

    /** 真打开二级/多级 GenUI 界面（压入页面栈，返回键可退回） */
    @Serializable
    @SerialName("open_screen")
    data class OpenScreen(
        val spec: UISpec
    ) : GenUIAction()

    /** 内联 HTML 真渲染（内置 WebView 详情页） */
    @Serializable
    @SerialName("open_html")
    data class OpenHtml(
        val html: String,
        val title: String = "详情"
    ) : GenUIAction()

    /** 执行系统级任务：dial/sms/email/web_search/settings/play */
    @Serializable
    @SerialName("execute")
    data class Execute(
        val action: String,
        val params: Map<String, String> = emptyMap()
    ) : GenUIAction()

    /**
     * 对话回传：把文本作为新的用户消息发给 AI 继续编排。
     * 支撑「动态游戏互动 UI 对话」与「编排询问弹窗」——
     * 界面上的选项/落子/操作，点击即成为下一轮对话输入。
     */
    @Serializable
    @SerialName("send_message")
    data class SendMessage(
        val text: String
    ) : GenUIAction()
}
