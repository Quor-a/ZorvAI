package com.ai.assistance.quro.core.tools

import android.content.Context
import android.accessibilityservice.AccessibilityServiceInfo
import com.ai.assistance.quro.core.shizuku.QuroShizuku
import org.json.JSONObject

/**
 * ZorvAI 能力清单（harness 侧的能力门）。
 *
 * ## 它解决什么
 *
 * harness 的 agent 通过 MCP 桥使用宿主（ZorvAI）的能力时，工具名带 `mcp__zorvai__` 前缀。
 * 但 agent 并不知道那个前缀下究竟有什么、哪些能力此刻真的可用（Shizuku 没授权、无障碍没开、
 * 某个 App 没装……）。
 *
 * 这个工具把「此刻能用什么」摊开给 agent 看：**先问，再动手**。这与参考实现里的
 * `android_capabilities` 是同一个思路（能力按组渐进披露，先解锁再出现）。
 *
 * 返回的是实时快照 —— 不是写死的清单，而是当前注册表里真实存在的工具与它们的权限状态。
 */
class ZorvaiCapabilitiesTool : QuroTool {
    override val name: String = "zorvai_capabilities"

    override val description: String =
        "列出宿主（ZorvAI）当前能提供的手机控制能力，以及它们此刻是否可用。" +
        "harness 的 agent 在用 mcp__zorvai__* 这些工具之前，先调用本工具确认能力与前提条件" +
        "（Shizuku 是否已授权、无障碍是否已开启）。" +
        "返回分组清单：读屏与无障碍 / 触控与输入 / 屏幕与录制 / 应用与通知 / 系统与特权 / 文件与浏览器。" +
        "标了「不可用」的项会说明缺什么条件。"

    override val parametersJson: String = """{
        "type":"object",
        "properties":{
            "group":{"type":"string","description":"只查某一组：screen / touch / app / system / file；留空返回全部分组"}
        }
    }"""

    /** 分组 → 该组的 ZorvAI 工具名。工具名与宿主注册表里的完全一致，调用时加 mcp__zorvai__ 前缀。 */
    private val groups: Map<String, List<String>> = linkedMapOf(
        "screen" to listOf(
            "read_screen", "get_screen_state", "screenshot", "screenshot_base64",
            "enable_screen_capture", "screen_record", "screen_rotation",
        ),
        "touch" to listOf(
            "tap_screen", "long_press_screen", "swipe_screen", "scroll_screen",
            "touch", "input_text",
        ),
        "app" to listOf(
            "launch_app", "search_and_launch_app", "lock_screen",
            "get_active_notifications", "notification_control",
        ),
        "system" to listOf(
            "shizuku_exec", "shizuku_root_exec", "shizuku_status",
            "root_exec", "root_status", "get_device_info", "device_admin_status",
        ),
        "file" to listOf(
            "file_read", "file_write", "file_list", "file_delete", "file_search",
            "browser_open", "browser_snapshot", "browser_click",
        ),
    )

    override fun run(context: Context, arguments: String): String {
        val args = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val only = args.optString("group", "").trim().lowercase()
        val reg = buildQuroRegistry(context)
        val present = reg.all().map { it.name }.toSet()

        val shizukuReady = runCatching { QuroShizuku.isReady }.getOrDefault(false)
        val a11yReady = accessibilityReady(context)

        val sb = StringBuilder()
        sb.appendLine("=== ZorvAI 手机能力（实时快照）===")
        sb.appendLine("调用方式：工具名加前缀 mcp__zorvai__ ，例如 mcp__zorvai__read_screen")
        sb.appendLine("前提：Shizuku ${if (shizukuReady) "已就绪（uid 2000 / ADB 级）" else "未就绪（未安装或未授权）"}；" +
            "无障碍 ${if (a11yReady) "已开启" else "未开启"}")
        sb.appendLine()

        groups.forEach { (group, names) ->
            if (only.isNotEmpty() && only != group) return@forEach
            sb.appendLine("── $group ──")
            names.forEach { n ->
                when {
                    !present.contains(n) -> sb.appendLine("  · $n —— 当前不可用（宿主未注册该工具）")
                    n.startsWith("shizuku") && !shizukuReady ->
                        sb.appendLine("  · $n —— 需要 Shizuku 已授权")
                    n in ACCESSIBILITY_TOOLS && !a11yReady ->
                        sb.appendLine("  · $n —— 需要无障碍权限已开启")
                    else -> sb.appendLine("  · $n —— 可用")
                }
            }
            sb.appendLine()
        }
        sb.append("提示：调用前先看上面「可用/需要什么」的判定；缺条件时先让用户授权，而不是反复重试。")
        return sb.toString()
    }

    /** 依赖无障碍权限的工具。 */
    private val ACCESSIBILITY_TOOLS = setOf(
        "read_screen", "get_screen_state", "tap_screen", "long_press_screen",
        "swipe_screen", "scroll_screen", "touch", "input_text",
        "get_active_notifications", "notification_control",
    )

    /**
     * 无障碍是否已开启。
     *
     * Android 没有公开的「无障碍服务是否启用」查询接口，可靠做法是看
     * `AccessibilityManager.getEnabledAccessibilityServiceList` 里有没有我们自己的服务，
     * 或者直接问设置里的 `ACCESSIBILITY_ENABLED`。这里用后者 + 服务列表双保险。
     */
    private fun accessibilityReady(context: Context): Boolean {
        val enabled = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ACCESSIBILITY_ENABLED
            )
        }.getOrNull()
        if (enabled == "1") return true
        return runCatching {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as? android.view.accessibility.AccessibilityManager
            am?.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )?.isNotEmpty() == true
        }.getOrDefault(false)
    }
}
