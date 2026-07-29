package com.ai.assistance.quro.core.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 第三方应用功能调用工具（原创，#1 需求）：
 * 让 AI 能「唤醒」其他已安装应用并直接调用其导出的能力，无需用户手动点开前台界面。
 *
 * - list_app_functions：枚举某应用导出的能力（Activity 的 intent-filter、导出 Service、
 *   导出 ContentProvider、导出 BroadcastReceiver），让 AI 知道该应用能做什么。
 * - invoke_app_function：调用其中一项能力。
 *     · kind=service / broadcast / provider → 后台执行，不弹前台界面（满足「不需要台前调用」）；
 *     · kind=activity（deeplink）→ 仍会拉起前台界面，作为兜底。
 *
 * 全程仅使用 Android 标准的包可见性 + 导出组件，不需要任何系统权限。
 */
class ListAppFunctionsTool : QuroTool {
    override val name = "list_app_functions"
    override val description = "枚举某已安装应用对外导出的「功能入口」（供 AI 直接调用，无需用户手动点开）。返回该应用导出的 Activity 意图过滤器、Service、ContentProvider、BroadcastReceiver。参数 {\"package\":\"应用包名\"}。先用 list_installed_apps / get_package_name 拿到包名。"
    override val parametersJson = """{"type":"object","properties":{"package":{"type":"string","description":"目标应用包名，如 com.kuaishou.nebula"}},"required":["package"]}"""

    @Suppress("DEPRECATION")
    override fun run(context: Context, arguments: String): String {
        val pkg = JSONObject(arguments).optString("package", "").trim()
        if (pkg.isEmpty()) return "缺少 package 参数"
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_PROVIDERS or PackageManager.GET_RECEIVERS
        val pi = runCatching { pm.getPackageInfo(pkg, flags) }.getOrNull()
            ?: return "找不到应用：$pkg（未安装或无包可见性）"

        val out = JSONObject()
        val activities = JSONArray()
        pi.activities?.forEach { a ->
            if (a.exported) {
                activities.put(JSONObject().put("component", a.name).put("exported", true))
            }
        }
        out.put("activities", activities)

        val services = JSONArray()
        pi.services?.forEach { s -> if (s.exported) services.put(s.name) }
        out.put("services", services)

        val providers = JSONArray()
        pi.providers?.forEach { p -> if (p.exported) providers.put(p.authority) }
        out.put("providers", providers)

        val receivers = JSONArray()
        pi.receivers?.forEach { r -> if (r.exported) receivers.put(r.name) }
        out.put("receivers", receivers)

        return "应用 $pkg 导出的能力：\n${out.toString(2)}"
    }
}

class InvokeAppFunctionTool : QuroTool {
    override val name = "invoke_app_function"
    override val description = "调用某应用导出的能力（来自 list_app_functions 的枚举结果）。kind=service/broadcast/provider 时后台执行、不弹前台；kind=activity 时拉起前台界面（兜底）。参数 {\"package\":\"包名\",\"kind\":\"activity|service|broadcast|provider\",\"action\":\"可选意图 action\",\"component\":\"可选组件类名\",\"data\":\"可选 Uri(如 content://authority/.. 或 https://..)\",\"type\":\"可选 MIME\",\"extra\":\"可选 JSON 字符串\"}。"
    override val parametersJson = """{"type":"object","properties":{"package":{"type":"string","description":"目标应用包名"},"kind":{"type":"string","description":"activity | service | broadcast | provider"},"action":{"type":"string","description":"可选 Intent action"},"component":{"type":"string","description":"可选组件完整类名（包名.类名）"},"data":{"type":"string","description":"可选 Uri"},"type":{"type":"string","description":"可选 MIME"},"extra":{"type":"string","description":"可选 JSON 对象字符串，键值对作为 Intent extra"}},"required":["package","kind"]}"""

    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val pkg = jo.optString("package", "").trim()
        val kind = jo.optString("kind", "").trim().lowercase()
        if (pkg.isEmpty()) return "缺少 package 参数"
        if (kind !in setOf("activity", "service", "broadcast", "provider")) return "kind 必须是 activity/service/broadcast/provider 之一"

        val action = jo.optString("action", "")
        val comp = jo.optString("component", "")
        val data = jo.optString("data", "")
        val type = jo.optString("type", "")
        val extra = runCatching {
            jo.optString("extra", "").let { if (it.isBlank()) JSONObject() else JSONObject(it) }
        }.getOrElse { return "extra 不是合法 JSON 对象" }

        val intent = Intent().apply {
            if (action.isNotEmpty()) this.action = action
            if (comp.isNotEmpty()) this.component = ComponentName(pkg, comp)
            if (data.isNotEmpty()) this.data = Uri.parse(data)
            if (type.isNotEmpty()) this.type = type
            extra.keys().forEach { k -> putExtra(k, extra.optString(k)) }
        }

        return try {
            when (kind) {
                "service" -> {
                    intent.setPackage(pkg)
                    context.startService(intent)
                    "已在后台启动 Service（无前台界面）：${comp.ifBlank { action }}"
                }
                "broadcast" -> {
                    intent.setPackage(pkg)
                    context.sendBroadcast(intent)
                    "已后台发送广播：${action.ifBlank { comp }}"
                }
                "provider" -> {
                    val uri = if (data.isNotEmpty()) Uri.parse(data) else Uri.parse("content://$pkg")
                    val cur = context.contentResolver.query(uri, null, null, null, null)
                    val rows = JSONArray()
                    cur?.use {
                        val n = it.columnCount
                        val names = (0 until n).map { i -> it.getColumnName(i) }
                        var count = 0
                        while (it.moveToNext() && count < 20) {
                            val row = JSONObject()
                            names.forEachIndexed { i, nm -> row.put(nm, it.getString(i) ?: "") }
                            rows.put(row); count++
                        }
                    }
                    "已后台查询 ContentProvider（无前台界面），返回 ${rows.length()} 行：\n${rows.toString(2)}"
                }
                else -> {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    "已拉起前台界面（activity）：${comp.ifBlank { action }}"
                }
            }
        } catch (e: Exception) {
            "调用失败：${e.message}"
        }
    }
}
