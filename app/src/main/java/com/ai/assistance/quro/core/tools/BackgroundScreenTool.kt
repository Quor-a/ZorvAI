package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 后台虚拟屏工具（移植自 Mika 的后台屏能力，去品牌化）。
 *
 * 与 `read_screen` / `tap_screen` 这组**无障碍**工具的关键区别：
 *  - 无障碍工具操作的是**用户正在看的这块屏**，会抢焦点、打断用户，而且安全窗口截图是黑的；
 *  - 本工具在一块**用户看不见的独立虚拟屏**上启动 App、点按、滑动、截图，
 *    主屏焦点与输入法都不受影响，用户照常用手机。
 *
 * 通道：Shizuku 特权进程 `createVirtualDisplay` → Surface → 本进程 ImageReader 出图，
 * 截图不经过无障碍接口。
 */
class BackgroundScreenTool : QuroTool {

    override val name: String = "bg_screen"

    override val description: String = """
后台虚拟屏工具：在一块用户看不见的独立屏上打开 App、点按、滑动、截图，全程不打扰用户正在用的主屏。
需要 Shizuku 已安装、已授权、服务运行中。

适合：在不打扰用户的情况下操作 App（发消息、查信息、走流程）、读取 App 界面截图给自己看。

action：
- open：把某个 App 启动到后台屏（需 packageName）
- read：**读界面结构**（控件树：ref / 类型 / 文字 / 中心坐标 / 是否可点可输）—— 优先用它，别靠截图猜坐标
- tap_element：按 read 返回的 ref 精确点击（如 ref="@e3"）
- screenshot：截取后台屏当前画面，返回图片文件路径（read 读不到结构时再用）
- tap：盲点一个坐标（需 x / y）
- swipe：滑动（需 x1/y1/x2/y2）
- back：按返回键
- top：查当前最上层 Activity（判断走到哪一步）
- status：查屏是否就绪 / displayId
- release：释放后台屏（任务结束时调用，避免长期占用）

**推荐流程**：status → open → read（拿到元素表）→ tap_element 或 tap → read 确认变化。
坐标说明：read 返回的 centerX/centerY 与截图上的像素坐标一致，可直接用于 tap。
""".trimIndent()

    override val parametersJson: String = """
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "description": "open / read / tap_element / screenshot / tap / swipe / back / top / status / release"},
    "ref": {"type": "string", "description": "tap_element 用的元素引用，如 @e3（来自 read 的返回）"},
    "packageName": {"type": "string", "description": "open 用的包名，如 com.tencent.mm"},
    "x": {"type": "integer", "description": "tap 的横坐标"},
    "y": {"type": "integer", "description": "tap 的纵坐标"},
    "x1": {"type": "integer", "description": "swipe 起点横坐标"},
    "y1": {"type": "integer", "description": "swipe 起点纵坐标"},
    "x2": {"type": "integer", "description": "swipe 终点横坐标"},
    "y2": {"type": "integer", "description": "swipe 终点纵坐标"},
    "duration_ms": {"type": "integer", "description": "swipe 时长毫秒，默认 300"}
  },
  "required": ["action"]
}
""".trimIndent()

    override fun run(context: Context, arguments: String): String {
        val obj = runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
        val action = obj.optString("action", "status").ifBlank { "status" }

        return when (action) {
            "open" -> {
                val pkg = obj.optString("packageName", "")
                QuroAgentTrace.action("bg_screen", "打开应用到后台屏", pkg)
                QuroVirtualScreen.openApp(context, pkg)
            }

            "read" -> {
                val snap = QuroVirtualScreen.readScreen()
                    ?: return "读不到界面结构：无障碍服务未开启，或后台屏还没有界面。请开启无障碍（设置 → 无障碍 → ZorvAI），或改用 screenshot 看画面。"
                QuroAgentTrace.result("bg_screen", "读界面", "${snap.elements.size} 个元素")
                snap.toPromptText()
            }

            "tap_element" -> {
                val ref = obj.optString("ref", "").trim()
                if (ref.isBlank()) return "缺少 ref（先用 read 拿到元素表）"
                QuroAgentTrace.action("bg_screen", "按 ref 点击", ref)
                if (QuroVirtualScreen.tapElement(context, ref)) {
                    "已点击 $ref"
                } else {
                    "点击 $ref 失败：该 ref 不在最近一次 read 的结果里（界面可能已变，请重新 read）"
                }
            }

            "screenshot" -> screenshot(context)

            "tap" -> {
                val x = obj.optInt("x", -1)
                val y = obj.optInt("y", -1)
                if (x < 0 || y < 0) return "缺少有效的 x / y"
                QuroAgentTrace.action("bg_screen", "后台屏点击", "($x, $y)")
                if (QuroVirtualScreen.tapAt(context, x, y)) "已点击 ($x, $y)" else tapFailed()
            }

            "swipe" -> {
                val x1 = obj.optInt("x1", -1)
                val y1 = obj.optInt("y1", -1)
                val x2 = obj.optInt("x2", -1)
                val y2 = obj.optInt("y2", -1)
                if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return "缺少有效的 x1/y1/x2/y2"
                val dur = obj.optLong("duration_ms", 300L)
                QuroAgentTrace.action("bg_screen", "后台屏滑动", "($x1,$y1)->($x2,$y2)")
                if (QuroVirtualScreen.swipeAt(context, x1, y1, x2, y2, dur)) {
                    "已滑动 ($x1,$y1) → ($x2,$y2)"
                } else {
                    "滑动失败：${QuroVirtualScreen.lastError ?: "后台屏未就绪或注入被拒"}"
                }
            }

            "back" -> {
                QuroAgentTrace.action("bg_screen", "后台屏返回", "")
                if (QuroVirtualScreen.back(context)) "已按返回键" else tapFailed()
            }

            "top" -> {
                val a = QuroVirtualScreen.topActivity(context)
                if (a == null) "后台屏当前没有任务（或屏未就绪）" else "最上层 Activity：$a"
            }

            "status" -> statusMessage(context)

            "release" -> {
                QuroVirtualScreen.release()
                "已释放后台虚拟屏"
            }

            else -> "未知 action：$action（可用：open / screenshot / tap / swipe / back / top / status / release）"
        }
    }

    private fun screenshot(context: Context): String {
        val png = QuroVirtualScreen.screenshotPng(context)
            ?: return "截图失败：${QuroVirtualScreen.lastError ?: "后台屏还没有画面（先 open 一个 App，等界面出来再截）"}"

        return runCatching {
            val dir = File(context.cacheDir, "bg_screen").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "bg_$ts.png")
            f.writeBytes(png)
            QuroAgentTrace.result("bg_screen", "截图", f.absolutePath)
            "✅ 后台屏截图：${f.absolutePath}\ndisplayId=${QuroVirtualScreen.displayId}"
        }.getOrElse { "保存截图失败：${it.message}" }
    }

    private fun statusMessage(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine(if (QuroVirtualScreen.isReady) "后台屏：✅ 已就绪（displayId=${QuroVirtualScreen.displayId}）" else "后台屏：❌ 未创建")
        QuroVirtualScreen.lastError?.let { sb.appendLine("最近错误：$it") }
        sb.appendLine("Shizuku AIDL 通道：${if (com.ai.assistance.quro.core.shizuku.QuroShizuku.isAidlAvailable) "✅ 可用" else "❌ 不可用"}")
        QuroVirtualScreen.topActivity(context)?.let { sb.appendLine("当前 Activity：$it") }
        return sb.toString().trim()
    }

    private fun tapFailed(): String =
        "操作失败：${QuroVirtualScreen.lastError ?: "后台屏未就绪，或注入被系统拒绝（检查 Shizuku 权限）"}"
}
