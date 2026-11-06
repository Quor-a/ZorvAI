package com.ai.assistance.quro.core.tools

import android.content.Context
import org.json.JSONObject

/**
 * 🔴 工具合体批（2026-11-06 用户裁定生效）：16 个"同一功能被拆散"的工具族合并。
 *
 * 设计：**转发式合并** —— 每个合体工具内部持有原散装工具实例，按 action/root 参数
 * 构造原参数 JSON 转发。原实现零改动（行为/返回/审批事实拆解全部继承），
 * 注册表从 46 个散装入口收到 17 个（详见各 KDoc）。
 *
 * 被退役的名字在本文件底部 [removedToolNotes] 留了映射（模型提示词/经验数据里
 * 引用旧名时，tool_discovery 与错误提示都能指到新入口）。
 */

// ———————————— 通用小件 ————————————

/** 原参转发：把用户 JSON 原样（去掉合体专用键）转给被合体的原工具。 */
private fun forwardArgs(arguments: String, strip: List<String> = listOf("action", "type", "root", "setting")): String =
    runCatching {
        val o = JSONObject(arguments)
        strip.forEach { o.remove(it) }
        o.toString()
    }.getOrElse { "{}" }

// ———————————— 族14：剪贴板 clipboard(get|set) ————————————

class ClipboardTool : QuroTool {
    private val get = GetClipboardTool()
    private val set = SetClipboardTool()
    override val name = "clipboard"
    override val readOnly get() = false
    override val description =
        "读写系统剪贴板。action=get 读取当前内容；action=set 写入文本（text 必填）。"
    override val parametersJson =
        """{"type":"object","properties":{"action":{"type":"string","enum":["get","set"],"description":"get=读取 / set=写入"},"text":{"type":"string","description":"set 时要写入的文本"}},"required":["action"]}"""
    override fun run(context: Context, arguments: String): String {
        val o = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        return when (o.optString("action", "get").lowercase()) {
            "set" -> set.run(context, forwardArgs(arguments))
            else -> get.run(context, "{}")
        }
    }
}

// ———————————— 族8：文件读 file_read(root=auto|app|internal|workspace) ————————————

class FileReadMergedTool : QuroTool {
    private val appRead = ReadTextFileTool()
    private val absRead = FileReadTool()
    private val wsRead = WorkspaceReadTool()
    override val name = "file_read"
    override val readOnly = true
    override val description =
        "读文本文件（分段）。root=app 相对应用外部目录 / internal 绝对路径 / workspace 相对工作区（越界防护）/ auto 自动判断。" +
            "参数：path、offset（字节偏移，分段读取传上一段 nextOffset）、maxBytes（默认 4096 上限 262144）。"
    override val parametersJson =
        """{"type":"object","properties":{"path":{"type":"string","description":"文件路径（按 root 解析；auto 时绝对路径按 internal、相对按 app）"},"root":{"type":"string","enum":["auto","app","internal","workspace"],"description":"路径基准，默认 auto"},"offset":{"type":"integer","description":"起始字节偏移"},"maxBytes":{"type":"integer","description":"本段最多字节数"}},"required":["path"]}"""
    override fun run(context: Context, arguments: String): String {
        val o = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val root = o.optString("root", "auto").lowercase()
        val resolved = when (root) {
            "app" -> appRead.run(context, forwardArgs(arguments, listOf("root")))
            "internal" -> absRead.run(context, forwardArgs(arguments, listOf("root")))
            "workspace" -> wsRead.run(context, forwardArgs(arguments, listOf("root")))
            else -> {
                // auto：绝对路径→internal；相对→app
                val p = o.optString("path", "")
                if (p.startsWith("/")) absRead.run(context, forwardArgs(arguments, listOf("root")))
                else appRead.run(context, forwardArgs(arguments, listOf("root")))
            }
        }
        return resolved
    }
}

// ———————————— 族9：目录列举 list_dir(root, action=list|find) ————————————

class ListDirMergedTool : QuroTool {
    private val appList = ListFilesTool()
    private val internalList = BrowseFilesTool()
    private val wsList = WorkspaceListTool()
    private val find = FindFilesTool()
    override val name = "list_dir"
    override val readOnly = true
    override val description =
        "列举目录 / 按名查找文件。root=app 应用外部目录 / internal 应用私有目录 / workspace 工作区。" +
            "action=list 列目录；action=find（仅 app 域）按 query 子串查找。"
    override val parametersJson =
        """{"type":"object","properties":{"path":{"type":"string","description":"相对子路径（可选）"},"root":{"type":"string","enum":["app","internal","workspace"],"description":"路径基准，默认 app"},"action":{"type":"string","enum":["list","find"],"description":"list=列举 / find=按名查找（app 域）"},"query":{"type":"string","description":"find 时的名称子串"},"limit":{"type":"integer","description":"返回条数"}}}"""
    override fun run(context: Context, arguments: String): String {
        val o = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val root = o.optString("root", "app").lowercase()
        return if (o.optString("action", "list").lowercase() == "find") {
            find.run(context, forwardArgs(arguments, listOf("action", "root", "path")))
        } else when (root) {
            "internal" -> internalList.run(context, forwardArgs(arguments, listOf("action", "root")))
            "workspace" -> wsList.run(context, forwardArgs(arguments, listOf("action", "root")))
            else -> appList.run(context, forwardArgs(arguments, listOf("action", "root")))
        }
    }
}

// ———————————— 族10：文件写 file_write(root=app|workspace) ————————————

class FileWriteMergedTool : QuroTool {
    private val appWrite = WriteFileTool()
    private val wsWrite = WorkspaceWriteTool()
    override val name = "file_write"
    override val description =
        "写文本文件（可 append）。root=app 应用外部目录 / workspace 工作区（越界防护）。" +
            "参数：path、content、append。旧名 write_file=app 域、workspace_write=workspace 域。"
    override val parametersJson =
        """{"type":"object","properties":{"path":{"type":"string","description":"文件路径（按 root 解析）"},"content":{"type":"string","description":"写入内容"},"append":{"type":"boolean","description":"true=追加，默认覆盖"},"root":{"type":"string","enum":["app","workspace"],"description":"路径基准，默认 app"}},"required":["path","content"]}"""
    override fun run(context: Context, arguments: String): String {
        val o = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        return if (o.optString("root", "app").lowercase() == "workspace") {
            wsWrite.run(context, forwardArgs(arguments, listOf("root")))
        } else {
            appWrite.run(context, forwardArgs(arguments, listOf("root")))
        }
    }
}

// ———————————— 族5：闹钟/倒计时 alarm_clock(action, type) ————————————

class AlarmClockMergedTool : QuroTool {
    private val setAlarm = SetAlarmTool()
    private val cancelAlarm = CancelAlarmTool()
    private val listAlarms = ListAlarmsTool()
    private val setTimer = SetTimerTool()
    override val name = "alarm_clock"
    override val description =
        "闹钟与倒计时。action=set：type=alarm（hour/minute/label/days，重复日 1-7）或 type=timer（minutes/label）。" +
            "action=cancel：id（cancel 需要）。action=list：列出。"
    override val parametersJson =
        """{"type":"object","properties":{"action":{"type":"string","enum":["set","cancel","list"],"description":"set=设置 / cancel=取消 / list=列出"},"type":{"type":"string","enum":["alarm","timer"],"description":"set 时：alarm=闹钟 / timer=倒计时"},"hour":{"type":"integer","description":"alarm 小时0-23"},"minute":{"type":"integer","description":"alarm 分钟0-59"},"minutes":{"type":"integer","description":"timer 分钟数"},"label":{"type":"string"},"days":{"type":"array","description":"重复日1-7，省略=只响一次"},"id":{"type":"string","description":"cancel 的闹钟 id"}},"required":["action"]}"""
    override fun run(context: Context, arguments: String): String {
        val o = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        return when (o.optString("action", "set").lowercase()) {
            "cancel" -> cancelAlarm.run(context, forwardArgs(arguments))
            "list" -> listAlarms.run(context, "{}")
            else -> if (o.optString("type", "alarm").lowercase() == "timer") {
                setTimer.run(context, forwardArgs(arguments))
            } else {
                setAlarm.run(context, forwardArgs(arguments))
            }
        }
    }
}

// ———————————— 族6：定时任务 task_schedule(action=create|list|delete) ————————————

class TaskScheduleMergedTool : QuroTool {
    private val create = ScheduleTaskTool()
    private val list = ListScheduledTasksTool()
    private val delete = DeleteScheduledTaskTool()
    override val name = "task_schedule"
    override val description =
        "会话定时任务（同一存储/调度器）。action=create（title/prompt/scheduleType/scheduledAt/rrule/…，见参数）/ list / delete（id）。"
    override val parametersJson =
        """{"type":"object","properties":{"action":{"type":"string","enum":["create","list","delete"],"description":"create=新建 / list=列出 / delete=删除"},"title":{"type":"string","description":"create 任务标题"},"prompt":{"type":"string","description":"触发时执行的内容"},"scheduleType":{"type":"string","description":"recurring / once"},"scheduledAt":{"type":"string","description":"once 触发时刻 yyyy-MM-dd HH:mm"},"rrule":{"type":"string","description":"重复规则 FREQ=DAILY 等"},"autoNew":{"type":"boolean","description":"触发时自动新建会话"},"endAt":{"type":"string","description":"重复结束日期"},"id":{"type":"string","description":"delete 的任务 id"}},"required":["action"]}"""
    override fun run(context: Context, arguments: String): String {
        val o = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        return when (o.optString("action", "list").lowercase()) {
            "create" -> create.run(context, forwardArgs(arguments))
            "delete" -> delete.run(context, forwardArgs(arguments))
            else -> list.run(context, "{}")
        }
    }
}

// ———————————— 族7：设备设置开关 device_control(setting, action) ————————————

class DeviceControlMergedTool : QuroTool {
    private val volume = VolumeControlTool()
    private val brightness = BrightnessControlTool()
    private val wifi = WiFiControlTool()
    private val bluetooth = BluetoothControlTool()
    private val notification = NotificationControlTool()
    private val airplane = AirplaneModeTool()
    private val rotation = ScreenRotationTool()
    private val flashlight = ToggleFlashlightTool()
    override val name = "device_control"
    override val description =
        "系统设置项开关/调节，8 个设置项一个入口。setting=volume（up/down/mute/unmute/set+level0-15）/ " +
            "brightness（up/down/auto/set+level0-255）/ wifi（on/off/toggle）/ bluetooth（on/off/toggle）/ " +
            "notification（expand/collapse/clear）/ airplane（on/off/toggle）/ rotation（auto/portrait/landscape）/ " +
            "flashlight（on/off）。"
    override val parametersJson =
        """{"type":"object","properties":{"setting":{"type":"string","enum":["volume","brightness","wifi","bluetooth","notification","airplane","rotation","flashlight"],"description":"设置项"},"action":{"type":"string","description":"各 setting 支持的动作见描述"},"level":{"type":"integer","description":"volume 0-15 / brightness 0-255（仅 set）"}},"required":["setting","action"]}"""
    override fun run(context: Context, arguments: String): String {
        val o = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val args = forwardArgs(arguments, listOf("setting"))
        return when (o.optString("setting", "").lowercase()) {
            "volume" -> volume.run(context, args)
            "brightness" -> brightness.run(context, args)
            "wifi" -> wifi.run(context, args)
            "bluetooth" -> bluetooth.run(context, args)
            "notification" -> notification.run(context, args)
            "airplane" -> airplane.run(context, args)
            "rotation" -> rotation.run(context, args)
            "flashlight" -> flashlight.run(context, args)
            else -> "未知 setting：" + o.optString("setting") + "。可选 volume/brightness/wifi/bluetooth/notification/airplane/rotation/flashlight"
        }
    }
}

// ———————————— 族15：TTS 播报 speak(action=say|stop) ————————————

class SpeakMergedTool : QuroTool {
    private val say = SpeakTool()
    private val stop = StopSpeakTool()
    override val name = "speak"
    override val description =
        "AI 主动语音播报（与自动朗读开关解耦）。action=say（默认）：text 必填，voice 选音色，多角色多音色分多次调用；" +
            "action=stop：停止当前播报并清队列。"
    override val parametersJson =
        """{"type":"object","properties":{"action":{"type":"string","enum":["say","stop"],"description":"say=播报 / stop=停止"},"text":{"type":"string","description":"say 时要朗读的文本（可含情绪标签）"},"voice":{"type":"string","description":"say 时本句音色名/id"},"lang":{"type":"string"}}}"""
    override fun run(context: Context, arguments: String): String {
        val o = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        return if (o.optString("action", "say").lowercase() == "stop") {
            stop.run(context, "{}")
        } else {
            say.run(context, forwardArgs(arguments, listOf("action")))
        }
    }
}

// ———————————— 族2：交互弹窗 visual_popup 吸收 question/action ————————————

class VisualPopupMergedTool : QuroTool {
    private val popup = VisualPopupTool()
    private val question = VisualQuestionTool()
    private val action = VisualActionTool()
    override val name = "visual_popup"
    override val description =
        "向用户弹可交互卡片。三种形态按参数自动选择：\n" +
            "1) 提问（question/options/allow_custom）→ 问题+选项列表，返回用户所选/所输文本；\n" +
            "2) 确认（title+message+buttons，无 inputs/content）→ 按钮确认框，返回所点按钮值；\n" +
            "3) 完整弹窗（title+content/buttons/inputs/image）→ 富弹窗，返回 {button, inputs}。\n" +
            "旧名 visual_question=形态1、visual_action=形态2，均可由本工具替代。"
    override val parametersJson =
        """{"type":"object","properties":{"title":{"type":"string"},"content":{"type":"string","description":"完整弹窗正文，支持 Markdown/HTML"},"question":{"type":"string","description":"形态1：要问的问题"},"options":{"type":"array","items":{"type":"string"},"description":"形态1：预设选项"},"allow_custom":{"type":"boolean","description":"形态1：允许自定义输入，默认 true"},"message":{"type":"string","description":"形态2：确认框说明"},"buttons":{"type":"array","items":{"type":"object","properties":{"text":{"type":"string"},"value":{"type":"string"},"style":{"type":"string"}},"required":["text","value"]}},"inputs":{"type":"array"},"timeout":{"type":"integer","description":"超时秒数"}}}"""
    override fun run(context: Context, arguments: String): String {
        val o = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val hasQuestion = o.optString("question").isNotBlank() || o.optJSONArray("options") != null
        val hasContent = o.optString("content").isNotBlank() || o.optJSONArray("inputs") != null
        return when {
            hasQuestion -> question.run(context, arguments)
            !hasContent && o.optJSONArray("buttons") != null -> action.run(context, arguments)
            else -> popup.run(context, arguments)
        }
    }
}

/** 退役名字 → 新入口映射（工具报错/发现目录引用旧名时指路）。 */
val removedToolNotes: Map<String, String> = mapOf(
    "ui_card" to "ui_widget",
    "visual_question" to "visual_popup",
    "visual_action" to "visual_popup",
    "screenshot_base64" to "screenshot",
    "open_app" to "launch_app",
    "search_and_launch_app" to "launch_app",
    "set_alarm" to "alarm_clock",
    "cancel_alarm" to "alarm_clock",
    "list_alarms" to "alarm_clock",
    "set_timer" to "alarm_clock",
    "schedule_task" to "task_schedule",
    "list_scheduled_tasks" to "task_schedule",
    "delete_scheduled_task" to "task_schedule",
    "volume_control" to "device_control",
    "brightness_control" to "device_control",
    "wifi_control" to "device_control",
    "bluetooth_control" to "device_control",
    "notification_control" to "device_control",
    "airplane_mode" to "device_control",
    "screen_rotation" to "device_control",
    "toggle_flashlight" to "device_control",
    "read_text_file" to "file_read",
    "workspace_read" to "file_read",
    "list_files" to "list_dir",
    "browse_files" to "list_dir",
    "workspace_list" to "list_dir",
    "find_files" to "list_dir",
    "write_file" to "file_write",
    "workspace_write" to "file_write",
    "search_provider" to "web_search",
    "local_music_player" to "music_play",
    "knowledge_search" to "knowledge_manage",
    "knowledge_add" to "knowledge_manage",
    "get_clipboard" to "clipboard",
    "set_clipboard" to "clipboard",
    "stop_speak" to "speak",
    "workbench" to "miniapp",
)
