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
 * 用法: ui_control({"action":"open", "target":"editor"})
 *       ui_control({"action":"toggle", "target":"deepthink"})
 *       ui_control({"action":"sheet", "target":"model"})
 *       ui_control({"action":"chat", "action_type":"new"})
 *       ui_control({"action":"card", "title":"标题", "content":"内容", "style":"info"})
 *       ui_control({"action":"widget", "type":"button", "id":"btn1", "label":"点击"})
 *       ui_control({"action":"status", "component":"header"})
 *       ui_control({"action":"update", "component":"header", "props":{"title":"新标题"}})
 */
class QuroUiControlTool : QuroTool {
    override val name = "ui_control"
    override val description = """UI 统一控制工具。参数:
{
  "action": "open|toggle|sheet|chat|card|widget|status|update|scroll|focus|hide|show|navigate",
  "target": "操作目标",
  ...其他参数...
}

action 说明:
- open: 打开界面 (target: editor/terminal/toolbox/knowledge/cms/aci/about/appearance/soul/memory/permission/model_config/voice/settings)
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
- navigate: 页面内导航 (target: section_id)"""
    override val parametersJson = """{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["open", "toggle", "sheet", "chat", "card", "widget", "status", "update", "scroll", "focus", "hide", "show", "navigate"],
      "description": "操作类型"
    },
    "target": {
      "type": "string",
      "description": "操作目标（界面名称/开关名称/组件ID）"
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
      "description": "组件值"
    },
    "component": {
      "type": "string",
      "description": "组件标识（header/sidebar/input/toolbox等）"
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
    }
  },
  "required": ["action"]
}"""

    override fun run(context: Context, arguments: String): String {
        val json = JSONObject(arguments)
        val action = json.optString("action", "")
        val target = json.optString("target", "")

        return when (action) {
            // ─── 打开界面 ───
            "open" -> {
                UiNavigationBus.navEvent = UiNavigationEvent.OpenScreen(target)
                "已打开界面: $target"
            }

            // ─── 切换开关 ───
            "toggle" -> {
                UiNavigationBus.navEvent = UiNavigationEvent.ToggleSwitch(target)
                "已切换开关: $target"
            }

            // ─── 打开弹层 ───
            "sheet" -> {
                UiNavigationBus.navEvent = UiNavigationEvent.OpenSheet(target)
                "已打开弹层: $target"
            }

            // ─── 对话管理 ───
            "chat" -> {
                val actionType = json.optString("action_type", target)
                UiNavigationBus.navEvent = UiNavigationEvent.ChatAction(actionType)
                "已执行对话操作: $actionType"
            }

            // ─── 渲染卡片 ───
            "card" -> {
                val title = json.optString("title", "")
                val content = json.optString("content", "")
                val style = json.optString("style", "info")
                UiNavigationBus.navEvent = UiNavigationEvent.RenderCard(title, content, style)
                "已渲染卡片: $title"
            }

            // ─── 渲染组件 ───
            "widget" -> {
                val type = json.optString("type", "button")
                val id = json.optString("id", "")
                val label = json.optString("label", "")
                val value = json.optString("value", "")
                UiNavigationBus.navEvent = UiNavigationEvent.RenderWidget(type, id, label, value)
                "已渲染组件: $type ($label)"
            }

            // ─── 查询状态 ───
            "status" -> {
                val component = json.optString("component", "header")
                UiNavigationBus.navEvent = UiNavigationEvent.QueryStatus(component)
                "正在查询状态: $component"
            }

            // ─── 更新属性 ───
            "update" -> {
                val component = json.optString("component", "")
                val props = json.optJSONObject("props")
                val propsMap = mutableMapOf<String, String>()
                props?.keys()?.forEach { key ->
                    propsMap[key] = props.optString(key, "")
                }
                UiNavigationBus.navEvent = UiNavigationEvent.UpdateComponent(component, propsMap)
                "已更新组件: $component"
            }

            // ─── 滚动 ───
            "scroll" -> {
                UiNavigationBus.navEvent = UiNavigationEvent.ScrollTo(target)
                "已滚动到: $target"
            }

            // ─── 聚焦 ───
            "focus" -> {
                UiNavigationBus.navEvent = UiNavigationEvent.FocusComponent(target)
                "已聚焦到: $target"
            }

            // ─── 隐藏 ───
            "hide" -> {
                UiNavigationBus.navEvent = UiNavigationEvent.HideComponent(target)
                "已隐藏: $target"
            }

            // ─── 显示 ───
            "show" -> {
                UiNavigationBus.navEvent = UiNavigationEvent.ShowComponent(target)
                "已显示: $target"
            }

            // ─── 页面内导航 ───
            "navigate" -> {
                UiNavigationBus.navEvent = UiNavigationEvent.NavigateTo(target)
                "已导航到: $target"
            }

            // ─── 权限控制 ───
            "permission" -> {
                val permission = json.optString("permission", "")
                val enabled = json.optBoolean("enabled", true)
                UiNavigationBus.navEvent = UiNavigationEvent.PermissionControl(permission, enabled)
                "已${if (enabled) "启用" else "禁用"}权限: $permission"
            }

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
