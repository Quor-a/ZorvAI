package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.agent.QuroAgentTrace
import com.ai.assistance.quro.core.display.DisplayAwake
import com.ai.assistance.quro.core.display.QuroVirtualScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
- open：把某个 App 启动到后台屏（需 packageName；可选 userId=分身编号、cold=true 冷启动）
- app_users：列出应用分身（双开/手机分身/工作资料）。**双开的 App 开不起来时先调它**
- read：**读界面**（控件树 ref/类型/文字/坐标 + 必要时自动补端侧 OCR）—— 优先用它，别靠截图猜坐标
- input_text：在当前输入框里写入文字（走无障碍 ACTION_SET_TEXT，**不弹系统输入法**，主屏不会冒键盘）
- tap_element：按 read 返回的 ref 精确点击（如 ref="@e3"）
- screenshot：截取后台屏当前画面，返回图片文件路径（read 已经带 OCR 了，一般不需要）
- tap：盲点一个坐标（需 x / y）
- swipe：滑动（需 x1/y1/x2/y2）
- back：按返回键
- top：查当前最上层 Activity（判断走到哪一步）
- status：查屏是否就绪 / displayId / 保活 / 无障碍 / OCR 状态
- release：**释放整块后台屏**。⚠️ 日常任务结束时**不要**调它 —— 默认把 App 留在原页面，
  用户切后台能看到、下次任务接着做。只有「用户明确要求关掉/释放」或「屏坏了要重建」时才用。

**read 会自动做三件事**（不用你去判断）：
1. 等画面停稳（避免读到启动图/白屏/骨架屏）；
2. 控件树读到的太少时，用本机 ML Kit 补一层 OCR 文字（自绘 UI / Flutter / 游戏 / WebView）；
3. 给出诊断：**画面全黑 = 该界面禁止截屏**、**画面一直在变 = 还在加载**、**无障碍未开 = 只能按坐标点**。
   看到这些提示就按它说的做，不要当没看见、更不要原地重试同一个动作。

**推荐流程**：status → open → read → tap_element 或 tap → **read 确认界面真的变了** → 继续下一步。
每一步动作之后都要再 read 一次；没变就是没点中，同一动作**不要原样重试**。
read 提示「还在加载」时，等 1 秒再 read 一次即可，不要重复 open。

**打开软件**：知道包名就直接 open，不要先 list_installed_apps。
刚建好的屏是空的，这是正常的（那是你自己的屏），空屏不等于失败。
多开/双开的应用不用特殊处理：open 会自动遍历所有用户空间（含 OPPO/小米那些不在 userProfiles 里的
分身用户），从装它的那一份启动；要指定就在 open 里传 userId（不确定就先 app_users）。
返回「已接管 / 已另开一个实例 / 已挪到后台屏 / 已从用户 N（分身）启动」**全都算成功**，直接 read 继续，
不要为了确认再 open 一遍。只有返回 code=1（主屏前台在跑 + 系统拒绝另起实例 + 挪不动）
或「主用户与所有分身都没有启动页」才是真的打不开。

**任务结束时**：不 release、不关 App，让它留在当前页。唯一例外是它此刻正在放声音/放视频 → 先停掉再汇报。
一次性登录页/验证码页这类不该留给用户看的，按 back 退出去或明确告知用户留在后台了。

坐标说明：read 返回的 centerX/centerY 与截图上的像素坐标一致，可直接用于 tap。
""".trimIndent()

    override val parametersJson: String = """
{
  "type": "object",
  "properties": {
    "action": {"type": "string", "description": "open / read / tap_element / input_text / screenshot / tap / swipe / back / top / status / app_users / release"},
    "ref": {"type": "string", "description": "tap_element 用的元素引用，如 @e3（来自 read 的返回）"},
    "userId": {"type": "integer", "description": "open 时指定分身用户编号（来自 app_users 的列表）。多开/双开的应用不传也行：open 会自动去所有用户空间里找装着它的那一份"},
    "cold": {"type": "boolean", "description": "open 时是否先 force-stop 再启动（冷启动），默认 false"},
    "text": {"type": "string", "description": "input_text 要输入的文字"},
    "hint": {"type": "string", "description": "input_text 选目标输入框时的提示语/占位文字（可选，多个输入框时用它区分）"},
    "append": {"type": "boolean", "description": "input_text 是否在原内容后追加，默认 false（覆盖）"},
    "packageName": {"type": "string", "description": "open 用的包名，如 com.tencent.mm。app_users 时带上它，可查「这个包装在哪几份用户里、现在开在哪块屏」"},
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
                val uid = if (obj.has("userId")) obj.optInt("userId", -1) else -1
                val cold = obj.optBoolean("cold", false)
                QuroVirtualScreen.openApp(context, pkg, uid, cold)
            }

            // 带 packageName 时回答「这个包装在哪几份用户里、现在开在哪」，AI 不用瞎猜 userId
            "app_users" -> QuroVirtualScreen.listAppCopies(context, obj.optString("packageName", "").trim())

            "read" -> {
                // 🔴 旧实现在这里「无障碍读不到就直接返回错误」，于是自绘 UI / 无障碍没开 /
                //    防截屏界面全都表现为「读不到界面结构」，OCR 根本没机会跑——
                //    这正是「AI 看屏有问题」最主要的成因。现在统一走 readForAi：
                //    结构为主，结构稀薄时自动等画面停稳 + 端侧 OCR，并把诊断结论一起给模型。
                val reading = runBlocking(Dispatchers.IO) { QuroVirtualScreen.readForAi(context) }
                QuroAgentTrace.result(
                    "bg_screen",
                    "读界面",
                    "${reading.snapshot?.elements?.size ?: 0} 个元素" +
                        (reading.ocr?.lines?.size?.let { " + OCR $it 行" } ?: ""),
                )
                reading.toPromptText()
            }

            "tap_element" -> {
                val ref = obj.optString("ref", "").trim()
                if (ref.isBlank()) return "缺少 ref（先用 read 拿到元素表）"
                QuroAgentTrace.action("bg_screen", "按 ref 点击", ref)
                val before = QuroVirtualScreen.frameSignature()
                if (QuroVirtualScreen.tapElement(context, ref)) {
                    "已点击 $ref" + changeHint(context, before)
                } else {
                    "点击 $ref 失败：该 ref 不在最近一次 read 的结果里（界面可能已变，请重新 read）"
                }
            }

            "input_text" -> {
                val text = obj.optString("text", "")
                if (text.isEmpty()) return "缺少 text（要输入的文字）"
                val r = QuroVirtualScreen.inputText(
                    context,
                    text,
                    hint = obj.optString("hint", "").takeIf { it.isNotBlank() },
                    append = obj.optBoolean("append", false),
                )
                if (r == null) {
                    "输入失败：这块屏上没有可编辑的输入框。先用 read 找到输入框并 tap_element 点一下，再 input_text。"
                } else {
                    "已输入（${r} 个字符，未经过系统输入法，主屏不会弹键盘）"
                }
            }

            "screenshot" -> screenshot(context)

            "tap" -> {
                val x = obj.optInt("x", -1)
                val y = obj.optInt("y", -1)
                if (x < 0 || y < 0) return "缺少有效的 x / y"
                QuroAgentTrace.action("bg_screen", "后台屏点击", "($x, $y)")
                val before = QuroVirtualScreen.frameSignature()
                if (QuroVirtualScreen.tapAt(context, x, y)) {
                    "已点击 ($x, $y)" + changeHint(context, before)
                } else {
                    tapFailed()
                }
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
        val png = QuroVirtualScreen.captureDisplay(context)
            ?: return "截图失败：${QuroVirtualScreen.lastError ?: "后台屏还没有画面（先 open 一个 App，等界面出来再截）"}" +
                "。通道：优先 Shizuku ImageReader，失败退回 Shizuku/无线调试 ADB 的 screencap；" +
                "若都不可用，请确认 Shizuku 已授权或无线调试已连接。"

        return runCatching {
            val dir = File(context.cacheDir, "bg_screen").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val f = File(dir, "bg_$ts.png")
            f.writeBytes(png)
            QuroAgentTrace.result("bg_screen", "截图", f.absolutePath)
            "✅ 后台屏截图：${f.absolutePath}\ndisplayId=${QuroVirtualScreen.displayId}（Shizuku 或无线调试 ADB 任一通道出图）"
        }.getOrElse { "保存截图失败：${it.message}" }
    }

    private fun statusMessage(context: Context): String {
        val sb = StringBuilder()
        sb.appendLine(if (QuroVirtualScreen.isReady) "后台屏：✅ 已就绪（displayId=${QuroVirtualScreen.displayId}）" else "后台屏：❌ 未创建")
        // 🔴 「看屏到底看的是哪块屏」必须显式写出来。
        // 这条此前没有任何地方说明，用户/AI 都只能猜：究竟是看虚拟屏，还是看用户手机当前屏幕？
        // 三路数据源全部锁定在**本 App 建的虚拟屏**上，主屏内容不会出现在这里：
        //  · 结构：a11y 的 windowsOn(displayId) —— 只取该 displayId 的窗口，取不到就返回空，
        //    绝不用 rootInActiveWindow 退回主屏（那会把用户屏幕当成后台界面）；
        //  · 图像：该虚拟屏自己的 ImageReader 帧（VirtualDisplay 的 Surface）；
        //  · 兜底：shell `screencap -d <displayId>`，同样是指定屏。
        sb.appendLine(
            "读屏目标：虚拟屏 displayId=${QuroVirtualScreen.displayId}" +
                "（结构=无障碍 windowsOn 该屏 · 图像=VirtualDisplay 帧 · 兜底=screencap -d 该屏）" +
                "；**不是用户手机主屏**",
        )
        QuroVirtualScreen.lastError?.let { sb.appendLine("最近错误：$it") }
        sb.appendLine("Shizuku AIDL 通道：${if (com.ai.assistance.quro.core.shizuku.QuroShizuku.isAidlAvailable) "✅ 可用" else "❌ 不可用"}")
        // 保活状态：这条很关键——屏自己息屏时，截图不变、点按无效，看起来像三个不同的 bug
        sb.appendLine("屏保活（防息屏）：${if (QuroVirtualScreen.isAwake) "✅ 每 ${DisplayAwake.PERIOD_MS / 1000}s 报一次用户活动" else "⚠️ 未启用（该屏可能自己息屏）"}")
        sb.appendLine("无障碍读树：${if (QuroVirtualScreen.isA11yAvailable) "✅ 可用（能给出 ref）" else "❌ 未开启（只能 OCR + 坐标点击）"}")
        sb.appendLine("端侧 OCR：${if (QuroVirtualScreen.isOcrReady) "✅ 可用（ML Kit 中文+拉丁）" else "⚠️ 未预热（首次读屏会慢约 1s）"}")
        QuroVirtualScreen.lastReading()?.let { r ->
            sb.appendLine("最近一次读屏：元素 ${r.snapshot?.elements?.size ?: 0} 个" +
                (r.ocr?.lines?.size?.let { "，OCR $it 行" } ?: "") +
                (if (r.probe?.black == true) "，⚠️ 画面全黑" else ""))
        }
        QuroVirtualScreen.topActivity(context)?.let { sb.appendLine("当前 Activity：$it") }
        return sb.toString().trim()
    }

    private fun tapFailed(): String =
        "操作失败：${QuroVirtualScreen.lastError ?: "后台屏未就绪，或注入被系统拒绝（检查 Shizuku 权限）"}"

    /**
     * 点完之后的一句话补充：界面到底变没变。
     *
     * 为什么要多这一步：以前点完只回「已点击」，模型得再 read 一次、自己比对才能判断是否生效；
     * 而它常常懒得比对，于是对同一个控件连点三四次。这里直接给出结论，
     * 变了就让它继续下一步，没变就明确提示「可能没点中或该控件不改界面」。
     *
     * 措辞必须留余地：有些控件点了只改焦点/弹键盘，画面签名可能不变，不能一口咬定「失败」。
     */
    private fun changeHint(context: Context, before: Long): String {
        if (before == 0L) return ""
        // 目标 App 本来就没在画（屏睡着了/还没画），比对没有意义
        val changed = runCatching {
            runBlocking(Dispatchers.Default) { QuroVirtualScreen.awaitFrameChange(before, 1_200L) }
        }.getOrDefault(false)
        return if (changed) {
            "；界面已变化"
        } else {
            "；界面看起来没变（可能没点中，也可能该控件只改焦点/弹输入法）。下一步请 read 确认，别原地重试。"
        }
    }
}
