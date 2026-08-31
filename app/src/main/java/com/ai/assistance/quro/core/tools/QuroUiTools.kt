package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.tools.ui.UiNavigationEvent
import org.json.JSONObject

/**
 * UI 控制事件总线：AI 调用 ui_control 工具时，通过此通道通知 ChatScreen 执行界面操作。
 * ChatScreen 在 LaunchedEffect 中 collect [navEvent] 并执行对应的 UI 操作。
 */
object UiNavigationBus {
    @Volatile var navEvent: UiNavigationEvent? = null
        set(value) {
            field = value
        }
}

/**
 * 统一 UI 控制工具：让 AI 能操控自己的每一个角落。
 *
 * 支持的操作类型：
 * - 界面控制：open/toggle/sheet/chat/navigate
 * - 组件渲染：card/widget
 * - 组件操作：status/update/scroll/focus/hide/show
 * - 手势操作：click/long_press/double_tap/swipe/pinch/rotate
 * - 文本操作：input/copy/paste/cut/select_all/undo/redo
 * - 系统操作：back/home/recent/split_screen/screenshot/share
 * - 媒体控制：play/pause/stop_media/next_track/prev_track
 * - 设备控制：volume/brightness/wifi/bluetooth/airplane等
 * - 应用管理：open_app/close_app/install_app/uninstall_app/freeze_app
 * - 设置管理：各种系统设置入口
 */
class QuroUiControlTool : QuroTool {
    override val name = "ui_control"
    override val description = """UI 统一控制工具。参数:
{
  "action": "操作类型",
  "target": "操作目标",
  ...其他参数...
}

action 说明:
- open: 打开界面 (target: editor/terminal/toolbox/knowledge/cms/aci/about/appearance/soul/memory/permission/model_config/voice/settings/tool_center)
- toggle: 切换开关 (target: deepthink/memory/permission_*)
- sheet: 打开弹层 (target: model/persona/settings)
- chat: 对话管理 (action_type: new/clear)
- card: 渲染卡片 (title, content, style: info/success/warning/error)
- widget: 渲染组件 (type: button/toggle/slider/input/select, id, label, value)
- status: 查询组件状态 (component: header/sidebar/input/toolbox)
- update: 更新组件属性 (component, props: {key:value})
- scroll: 滚动到指定位置 (target: top/bottom/id)
- focus: 聚焦到组件 (target: input/toolbox)
- hide: 隐藏组件 (target: sidebar/toolbox)
- show: 显示组件 (target: sidebar/toolbox)
- navigate: 页面内导航 (target: section_id)
- click: 点击元素 (target: 元素ID)
- long_press: 长按元素 (target: 元素ID)
- double_tap: 双击元素 (target: 元素ID)
- swipe: 滑动 (direction: left/right/up/down)
- pinch: 缩放 (scale: 缩放比例)
- rotate: 旋转 (angle: 角度)
- input: 输入文本 (target: 输入框ID, value: 文本内容)
- copy: 复制内容 (target: 要复制的文本)
- paste: 粘贴剪贴板内容
- cut: 剪切选中内容
- select_all: 全选
- undo: 撤销
- redo: 重做
- back: 返回上一页
- home: 回到首页
- recent: 打开最近任务
- split_screen: 分屏
- screenshot: 截屏
- share: 分享当前内容
- search: 搜索 (target: 搜索关键词)
- refresh: 刷新页面
- stop: 停止加载
- bookmark: 收藏 (target: 收藏标题)
- fullscreen: 全屏切换
- minimize: 最小化
- maximize: 最大化
- close: 关闭当前页面
- minimize_app: 最小化应用
- lock_screen: 锁屏
- wake_screen: 唤醒屏幕
- open_notification: 打开通知栏
- open_quick_settings: 打开快速设置
- take_photo: 拍照
- start_recording: 开始录屏
- stop_recording: 停止录屏
- play: 播放媒体
- pause: 暂停媒体
- stop_media: 停止媒体
- next_track: 下一曲
- prev_track: 上一曲
- volume_up: 增大音量
- volume_down: 减小音量
- mute: 静音
- unmute: 取消静音
- brightness_up: 增大亮度
- brightness_down: 减小亮度
- auto_brightness: 自动亮度
- wifi_on: 开启WiFi
- wifi_off: 关闭WiFi
- bluetooth_on: 开启蓝牙
- bluetooth_off: 关闭蓝牙
- airplane_on: 开启飞行模式
- airplane_off: 关闭飞行模式
- do_not_disturb_on: 开启勿扰模式
- do_not_disturb_off: 关闭勿扰模式
- flashlight_on: 打开手电筒
- flashlight_off: 关闭手电筒
- location_on: 开启定位
- location_off: 关闭定位
- nfc_on: 开启NFC
- nfc_off: 关闭NFC
- auto_rotate_on: 开启自动旋转
- auto_rotate_off: 关闭自动旋转
- portrait: 锁定竖屏
- landscape: 锁定横屏
- open_app: 打开应用 (target: 应用包名)
- close_app: 关闭应用 (target: 应用包名)
- install_app: 安装应用 (target: APK路径)
- uninstall_app: 卸载应用 (target: 应用包名)
- freeze_app: 冻结应用 (target: 应用包名)
- unfreeze_app: 解冻应用 (target: 应用包名)
- open_settings: 打开设置 (target: 设置项)"""
    override val parametersJson = """{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["open", "toggle", "sheet", "chat", "card", "widget", "status", "update", "scroll", "focus", "hide", "show", "navigate", "click", "long_press", "double_tap", "swipe", "pinch", "rotate", "input", "copy", "paste", "cut", "select_all", "undo", "redo", "back", "home", "recent", "split_screen", "screenshot", "share", "search", "refresh", "stop", "bookmark", "fullscreen", "minimize", "maximize", "close", "minimize_app", "lock_screen", "wake_screen", "open_notification", "open_quick_settings", "take_photo", "start_recording", "stop_recording", "play", "pause", "stop_media", "next_track", "prev_track", "volume_up", "volume_down", "mute", "unmute", "brightness_up", "brightness_down", "auto_brightness", "wifi_on", "wifi_off", "bluetooth_on", "bluetooth_off", "airplane_on", "airplane_off", "do_not_disturb_on", "do_not_disturb_off", "flashlight_on", "flashlight_off", "location_on", "location_off", "nfc_on", "nfc_off", "auto_rotate_on", "auto_rotate_off", "portrait", "landscape", "open_app", "close_app", "install_app", "uninstall_app", "freeze_app", "unfreeze_app", "open_settings"],
      "description": "操作类型"
    },
    "target": {
      "type": "string",
      "description": "操作目标"
    },
    "action_type": {
      "type": "string",
      "description": "chat操作的子类型: new/clear"
    },
    "title": {
      "type": "string",
      "description": "卡片标题"
    },
    "content": {
      "type": "string",
      "description": "卡片内容（Markdown）"
    },
    "style": {
      "type": "string",
      "enum": ["info", "success", "warning", "error"],
      "description": "卡片样式"
    },
    "type": {
      "type": "string",
      "enum": ["button", "toggle", "slider", "input", "select"],
      "description": "组件类型"
    },
    "id": {
      "type": "string",
      "description": "组件唯一ID"
    },
    "label": {
      "type": "string",
      "description": "组件标签"
    },
    "value": {
      "type": "string",
      "description": "组件值/文本内容"
    },
    "component": {
      "type": "string",
      "description": "组件标识"
    },
    "props": {
      "type": "object",
      "description": "要更新的属性键值对"
    },
    "permission": {
      "type": "string",
      "description": "权限类型"
    },
    "enabled": {
      "type": "boolean",
      "description": "权限启用状态"
    },
    "direction": {
      "type": "string",
      "enum": ["left", "right", "up", "down"],
      "description": "滑动方向"
    },
    "scale": {
      "type": "string",
      "description": "缩放比例"
    },
    "angle": {
      "type": "string",
      "description": "旋转角度"
    }
  },
  "required": ["action"]
}"""

    override fun run(context: Context, arguments: String): String {
        val json = JSONObject(arguments)
        val action = json.optString("action", "")
        val target = json.optString("target", "")

        return when (action) {
            // ─── 界面控制 ───
            "open" -> { UiNavigationBus.navEvent = UiNavigationEvent.OpenScreen(target); "已打开: $target" }
            "toggle" -> { UiNavigationBus.navEvent = UiNavigationEvent.ToggleSwitch(target); "已切换: $target" }
            "sheet" -> { UiNavigationBus.navEvent = UiNavigationEvent.OpenSheet(target); "已打开弹层: $target" }
            "chat" -> { val t = json.optString("action_type", target); UiNavigationBus.navEvent = UiNavigationEvent.ChatAction(t); "已执行: $t" }
            "navigate" -> { UiNavigationBus.navEvent = UiNavigationEvent.NavigateTo(target); "已导航: $target" }

            // ─── 组件渲染 ───
            "card" -> { val t = json.optString("title",""); val c = json.optString("content",""); val s = json.optString("style","info"); UiNavigationBus.navEvent = UiNavigationEvent.RenderCard(t,c,s); "已渲染卡片: $t" }
            "widget" -> { val tp = json.optString("type","button"); val id = json.optString("id",""); val l = json.optString("label",""); val v = json.optString("value",""); UiNavigationBus.navEvent = UiNavigationEvent.RenderWidget(tp,id,l,v); "已渲染组件: $tp" }

            // ─── 组件操作 ───
            "status" -> { val c = json.optString("component","header"); UiNavigationBus.navEvent = UiNavigationEvent.QueryStatus(c); "查询状态: $c" }
            "update" -> { val c = json.optString("component",""); val p = json.optJSONObject("props"); val m = mutableMapOf<String,String>(); p?.keys()?.forEach { k -> m[k] = p.optString(k,"") }; UiNavigationBus.navEvent = UiNavigationEvent.UpdateComponent(c,m); "已更新: $c" }
            "scroll" -> { UiNavigationBus.navEvent = UiNavigationEvent.ScrollTo(target); "已滚动: $target" }
            "focus" -> { UiNavigationBus.navEvent = UiNavigationEvent.FocusComponent(target); "已聚焦: $target" }
            "hide" -> { UiNavigationBus.navEvent = UiNavigationEvent.HideComponent(target); "已隐藏: $target" }
            "show" -> { UiNavigationBus.navEvent = UiNavigationEvent.ShowComponent(target); "已显示: $target" }

            // ─── 手势操作 ───
            "click" -> { UiNavigationBus.navEvent = UiNavigationEvent.ClickElement(target); "已点击: $target" }
            "long_press" -> { UiNavigationBus.navEvent = UiNavigationEvent.LongPress(target); "已长按: $target" }
            "double_tap" -> { UiNavigationBus.navEvent = UiNavigationEvent.DoubleTap(target); "已双击: $target" }
            "swipe" -> { val d = json.optString("direction","up"); UiNavigationBus.navEvent = UiNavigationEvent.Swipe(d); "已滑动: $d" }
            "pinch" -> { val s = json.optString("scale","1.0"); UiNavigationBus.navEvent = UiNavigationEvent.Pinch(s); "已缩放: $s" }
            "rotate" -> { val a = json.optString("angle","0"); UiNavigationBus.navEvent = UiNavigationEvent.Rotate(a); "已旋转: $a" }

            // ─── 文本操作 ───
            "input" -> { val v = json.optString("value",""); UiNavigationBus.navEvent = UiNavigationEvent.InputText(target,v); "已输入: $v" }
            "copy" -> { UiNavigationBus.navEvent = UiNavigationEvent.Copy(target); "已复制: $target" }
            "paste" -> { UiNavigationBus.navEvent = UiNavigationEvent.Paste; "已粘贴" }
            "cut" -> { UiNavigationBus.navEvent = UiNavigationEvent.Cut; "已剪切" }
            "select_all" -> { UiNavigationBus.navEvent = UiNavigationEvent.SelectAll; "已全选" }
            "undo" -> { UiNavigationBus.navEvent = UiNavigationEvent.Undo; "已撤销" }
            "redo" -> { UiNavigationBus.navEvent = UiNavigationEvent.Redo; "已重做" }

            // ─── 系统操作 ───
            "back" -> { UiNavigationBus.navEvent = UiNavigationEvent.Back; "已返回" }
            "home" -> { UiNavigationBus.navEvent = UiNavigationEvent.Home; "已回到首页" }
            "recent" -> { UiNavigationBus.navEvent = UiNavigationEvent.Recent; "已打开最近任务" }
            "split_screen" -> { UiNavigationBus.navEvent = UiNavigationEvent.SplitScreen; "已分屏" }
            "screenshot" -> { UiNavigationBus.navEvent = UiNavigationEvent.Screenshot; "已截屏" }
            "share" -> { UiNavigationBus.navEvent = UiNavigationEvent.Share(target); "已分享" }
            "search" -> { UiNavigationBus.navEvent = UiNavigationEvent.Search(target); "已搜索: $target" }
            "refresh" -> { UiNavigationBus.navEvent = UiNavigationEvent.Refresh; "已刷新" }
            "stop" -> { UiNavigationBus.navEvent = UiNavigationEvent.StopLoading; "已停止" }
            "bookmark" -> { UiNavigationBus.navEvent = UiNavigationEvent.Bookmark(target); "已收藏" }
            "fullscreen" -> { UiNavigationBus.navEvent = UiNavigationEvent.Fullscreen; "已全屏" }
            "minimize" -> { UiNavigationBus.navEvent = UiNavigationEvent.Minimize; "已最小化" }
            "maximize" -> { UiNavigationBus.navEvent = UiNavigationEvent.Maximize; "已最大化" }
            "close" -> { UiNavigationBus.navEvent = UiNavigationEvent.Close; "已关闭" }
            "minimize_app" -> { UiNavigationBus.navEvent = UiNavigationEvent.MinimizeApp; "已最小化应用" }
            "lock_screen" -> { UiNavigationBus.navEvent = UiNavigationEvent.LockScreen; "已锁屏" }
            "wake_screen" -> { UiNavigationBus.navEvent = UiNavigationEvent.WakeScreen; "已唤醒" }
            "open_notification" -> { UiNavigationBus.navEvent = UiNavigationEvent.OpenNotification; "已打开通知栏" }
            "open_quick_settings" -> { UiNavigationBus.navEvent = UiNavigationEvent.OpenQuickSettings; "已打开快速设置" }

            // ─── 媒体控制 ───
            "take_photo" -> { UiNavigationBus.navEvent = UiNavigationEvent.TakePhoto; "已拍照" }
            "start_recording" -> { UiNavigationBus.navEvent = UiNavigationEvent.StartRecording; "已开始录屏" }
            "stop_recording" -> { UiNavigationBus.navEvent = UiNavigationEvent.StopRecording; "已停止录屏" }
            "play" -> { UiNavigationBus.navEvent = UiNavigationEvent.PlayMedia; "已播放" }
            "pause" -> { UiNavigationBus.navEvent = UiNavigationEvent.PauseMedia; "已暂停" }
            "stop_media" -> { UiNavigationBus.navEvent = UiNavigationEvent.StopMedia; "已停止媒体" }
            "next_track" -> { UiNavigationBus.navEvent = UiNavigationEvent.NextTrack; "下一曲" }
            "prev_track" -> { UiNavigationBus.navEvent = UiNavigationEvent.PrevTrack; "上一曲" }

            // ─── 设备控制 ───
            "volume_up" -> { UiNavigationBus.navEvent = UiNavigationEvent.VolumeUp; "音量增大" }
            "volume_down" -> { UiNavigationBus.navEvent = UiNavigationEvent.VolumeDown; "音量减小" }
            "mute" -> { UiNavigationBus.navEvent = UiNavigationEvent.Mute; "已静音" }
            "unmute" -> { UiNavigationBus.navEvent = UiNavigationEvent.Unmute; "已取消静音" }
            "brightness_up" -> { UiNavigationBus.navEvent = UiNavigationEvent.BrightnessUp; "亮度增大" }
            "brightness_down" -> { UiNavigationBus.navEvent = UiNavigationEvent.BrightnessDown; "亮度减小" }
            "auto_brightness" -> { UiNavigationBus.navEvent = UiNavigationEvent.AutoBrightness; "自动亮度" }
            "wifi_on" -> { UiNavigationBus.navEvent = UiNavigationEvent.WifiOn; "WiFi已开启" }
            "wifi_off" -> { UiNavigationBus.navEvent = UiNavigationEvent.WifiOff; "WiFi已关闭" }
            "bluetooth_on" -> { UiNavigationBus.navEvent = UiNavigationEvent.BluetoothOn; "蓝牙已开启" }
            "bluetooth_off" -> { UiNavigationBus.navEvent = UiNavigationEvent.BluetoothOff; "蓝牙已关闭" }
            "airplane_on" -> { UiNavigationBus.navEvent = UiNavigationEvent.AirplaneModeOn; "飞行模式已开启" }
            "airplane_off" -> { UiNavigationBus.navEvent = UiNavigationEvent.AirplaneModeOff; "飞行模式已关闭" }
            "do_not_disturb_on" -> { UiNavigationBus.navEvent = UiNavigationEvent.DoNotDisturbOn; "勿扰模式已开启" }
            "do_not_disturb_off" -> { UiNavigationBus.navEvent = UiNavigationEvent.DoNotDisturbOff; "勿扰模式已关闭" }
            "flashlight_on" -> { UiNavigationBus.navEvent = UiNavigationEvent.FlashlightOn; "手电筒已打开" }
            "flashlight_off" -> { UiNavigationBus.navEvent = UiNavigationEvent.FlashlightOff; "手电筒已关闭" }
            "location_on" -> { UiNavigationBus.navEvent = UiNavigationEvent.LocationOn; "定位已开启" }
            "location_off" -> { UiNavigationBus.navEvent = UiNavigationEvent.LocationOff; "定位已关闭" }
            "nfc_on" -> { UiNavigationBus.navEvent = UiNavigationEvent.NfcOn; "NFC已开启" }
            "nfc_off" -> { UiNavigationBus.navEvent = UiNavigationEvent.NfcOff; "NFC已关闭" }
            "auto_rotate_on" -> { UiNavigationBus.navEvent = UiNavigationEvent.AutoRotateOn; "自动旋转已开启" }
            "auto_rotate_off" -> { UiNavigationBus.navEvent = UiNavigationEvent.AutoRotateOff; "自动旋转已关闭" }
            "portrait" -> { UiNavigationBus.navEvent = UiNavigationEvent.Portrait; "已锁定竖屏" }
            "landscape" -> { UiNavigationBus.navEvent = UiNavigationEvent.Landscape; "已锁定横屏" }

            // ─── 应用管理 ───
            "open_app" -> { UiNavigationBus.navEvent = UiNavigationEvent.OpenApp(target); "已打开: $target" }
            "close_app" -> { UiNavigationBus.navEvent = UiNavigationEvent.CloseApp(target); "已关闭: $target" }
            "install_app" -> { UiNavigationBus.navEvent = UiNavigationEvent.InstallApp(target); "已安装: $target" }
            "uninstall_app" -> { UiNavigationBus.navEvent = UiNavigationEvent.UninstallApp(target); "已卸载: $target" }
            "freeze_app" -> { UiNavigationBus.navEvent = UiNavigationEvent.FreezeApp(target); "已冻结: $target" }
            "unfreeze_app" -> { UiNavigationBus.navEvent = UiNavigationEvent.UnfreezeApp(target); "已解冻: $target" }

            // ─── 设置管理 ───
            "open_settings" -> { UiNavigationBus.navEvent = UiNavigationEvent.OpenSettings(target); "已打开设置: $target" }
            "permission" -> { val p = json.optString("permission",""); val e = json.optBoolean("enabled",true); UiNavigationBus.navEvent = UiNavigationEvent.PermissionControl(p,e); "已${if(e) "启用" else "禁用"}: $p" }

            else -> "未知操作: $action"
        }
    }
}

/**
 * 注册 UI 控制工具到工具注册表。
 */
fun registerUiTools(r: QuroToolRegistry) {
    r.register(QuroUiControlTool())
}
