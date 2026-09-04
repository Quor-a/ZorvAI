package com.ai.assistance.quro.core.tools.ui

/**
 * UI 导航事件：AI 调用 ui_control 工具时，通过此密封类通知 ChatScreen 执行界面操作。
 */
sealed class UiNavigationEvent {
    // ─── 界面控制 ───
    data class OpenScreen(val target: String) : UiNavigationEvent()
    data class ToggleSwitch(val target: String) : UiNavigationEvent()
    data class OpenSheet(val target: String) : UiNavigationEvent()
    data class ChatAction(val action: String) : UiNavigationEvent()
    data class NavigateTo(val target: String) : UiNavigationEvent()

    // ─── 组件渲染 ───
    data class RenderCard(val title: String, val content: String, val style: String) : UiNavigationEvent()
    data class RenderWidget(val type: String, val id: String, val label: String, val value: String) : UiNavigationEvent()

    // ─── 可视化交互（动态 UI 深链）───
    /** 可视化弹窗：标题 + 内容，纯展示。 */
    data class VisualPopup(val title: String, val content: String) : UiNavigationEvent()
    /** 可视化询问：prompt + 选项，选中项回发对话。 */
    data class VisualAsk(val prompt: String, val options: List<String>) : UiNavigationEvent()

    // ─── 组件操作 ───
    data class QueryStatus(val component: String) : UiNavigationEvent()
    data class UpdateComponent(val component: String, val props: Map<String, String>) : UiNavigationEvent()
    data class ScrollTo(val target: String) : UiNavigationEvent()
    data class FocusComponent(val target: String) : UiNavigationEvent()
    data class HideComponent(val target: String) : UiNavigationEvent()
    data class ShowComponent(val target: String) : UiNavigationEvent()

    // ─── 手势操作 ───
    data class ClickElement(val target: String) : UiNavigationEvent()
    data class LongPress(val target: String) : UiNavigationEvent()
    data class DoubleTap(val target: String) : UiNavigationEvent()
    data class Swipe(val direction: String) : UiNavigationEvent()
    data class Pinch(val scale: String) : UiNavigationEvent()
    data class Rotate(val angle: String) : UiNavigationEvent()

    // ─── 文本操作 ───
    data class InputText(val target: String, val value: String) : UiNavigationEvent()
    data class Copy(val target: String) : UiNavigationEvent()
    object Paste : UiNavigationEvent()
    object Cut : UiNavigationEvent()
    object SelectAll : UiNavigationEvent()
    object Undo : UiNavigationEvent()
    object Redo : UiNavigationEvent()

    // ─── 系统操作 ───
    object Back : UiNavigationEvent()
    object Home : UiNavigationEvent()
    object Recent : UiNavigationEvent()
    object SplitScreen : UiNavigationEvent()
    object Screenshot : UiNavigationEvent()
    data class Share(val target: String) : UiNavigationEvent()
    data class Search(val target: String) : UiNavigationEvent()
    object Refresh : UiNavigationEvent()
    object StopLoading : UiNavigationEvent()
    data class Bookmark(val target: String) : UiNavigationEvent()
    object Fullscreen : UiNavigationEvent()
    object Minimize : UiNavigationEvent()
    object Maximize : UiNavigationEvent()
    object Close : UiNavigationEvent()
    object MinimizeApp : UiNavigationEvent()
    object LockScreen : UiNavigationEvent()
    object WakeScreen : UiNavigationEvent()
    object OpenNotification : UiNavigationEvent()
    object OpenQuickSettings : UiNavigationEvent()

    // ─── 媒体控制 ───
    object TakePhoto : UiNavigationEvent()
    object StartRecording : UiNavigationEvent()
    object StopRecording : UiNavigationEvent()
    object PlayMedia : UiNavigationEvent()
    object PauseMedia : UiNavigationEvent()
    object StopMedia : UiNavigationEvent()
    object NextTrack : UiNavigationEvent()
    object PrevTrack : UiNavigationEvent()

    // ─── 设备控制 ───
    object VolumeUp : UiNavigationEvent()
    object VolumeDown : UiNavigationEvent()
    object Mute : UiNavigationEvent()
    object Unmute : UiNavigationEvent()
    object BrightnessUp : UiNavigationEvent()
    object BrightnessDown : UiNavigationEvent()
    object AutoBrightness : UiNavigationEvent()
    object WifiOn : UiNavigationEvent()
    object WifiOff : UiNavigationEvent()
    object BluetoothOn : UiNavigationEvent()
    object BluetoothOff : UiNavigationEvent()
    object AirplaneModeOn : UiNavigationEvent()
    object AirplaneModeOff : UiNavigationEvent()
    object DoNotDisturbOn : UiNavigationEvent()
    object DoNotDisturbOff : UiNavigationEvent()
    object FlashlightOn : UiNavigationEvent()
    object FlashlightOff : UiNavigationEvent()
    object LocationOn : UiNavigationEvent()
    object LocationOff : UiNavigationEvent()
    object NfcOn : UiNavigationEvent()
    object NfcOff : UiNavigationEvent()
    object AutoRotateOn : UiNavigationEvent()
    object AutoRotateOff : UiNavigationEvent()
    object Portrait : UiNavigationEvent()
    object Landscape : UiNavigationEvent()

    // ─── 应用管理 ───
    data class OpenApp(val target: String) : UiNavigationEvent()
    data class CloseApp(val target: String) : UiNavigationEvent()
    data class InstallApp(val target: String) : UiNavigationEvent()
    data class UninstallApp(val target: String) : UiNavigationEvent()
    data class FreezeApp(val target: String) : UiNavigationEvent()
    data class UnfreezeApp(val target: String) : UiNavigationEvent()

    // ─── 设置管理 ───
    data class OpenSettings(val target: String) : UiNavigationEvent()
    object OpenAccessibilitySettings : UiNavigationEvent()
    object OpenDeveloperOptionsSettings : UiNavigationEvent()
    object OpenAboutPhoneSettings : UiNavigationEvent()
    object OpenBatterySettings : UiNavigationEvent()
    object OpenStorageSettings : UiNavigationEvent()
    object OpenNetworkSettings : UiNavigationEvent()
    object OpenDisplaySettings : UiNavigationEvent()
    object OpenSoundSettings : UiNavigationEvent()
    object OpenSecuritySettings : UiNavigationEvent()
    object OpenPrivacySettings : UiNavigationEvent()
    object OpenAccountsSettings : UiNavigationEvent()
    object OpenDateTimeSettings : UiNavigationEvent()
    object OpenLanguageSettings : UiNavigationEvent()

    // ─── 权限控制 ───
    data class PermissionControl(val permission: String, val enabled: Boolean) : UiNavigationEvent()
}
