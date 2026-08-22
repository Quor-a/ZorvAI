package com.ai.assistance.quro.core.tools

import android.content.Context
import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ai.assistance.quro.service.QuroAccessibilityService
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * L1 无障碍屏幕控制与感知工具集（CapOS 通道）。
 *
 * 通过 QuroAccessibilityService（已声明于 Manifest、用户在系统设置授权后可用）实现：
 *   - 屏幕感知：读取当前界面节点树 / 获取前台应用包名
 *   - 屏幕操控：点击 / 长按 / 文本输入 / 滑动手势 / 滚动列表
 *   - 全局动作：返回键 / 最近任务 / 展开通知栏 / 锁屏
 *
 * 所有工具在无障碍服务未连接时返回友好错误提示，不会崩溃。
 * 工具名称保持与 v108 移除前一致，确保 AI 已有的工具调用知识可直接复用。
 */

// ──────────────────────────── 屏幕感知 ────────────────────────────

/** 读取当前屏幕的 UI 节点树摘要。 */
class ReadScreenTool : QuroTool {
    override val name = "read_screen"
    override val description = "读取当前屏幕的 UI 内容（文本 / 描述 / 资源 ID），返回节点树摘要。无需参数 {}。要求已开启无障碍服务。"
    override val parametersJson = """{"type":"object","properties":{}}"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接：请到 CapOS 权限子系统 → L1 无障碍服务 → 请求授权"
        return try {
            val root = svc.rootInActiveWindow ?: return "⚠️ 无法获取当前窗口根节点（可能被系统限制）"
            val sb = StringBuilder()
            appendNode(root, sb, 0)
            // 另收集可交互元素（clickable/editable/scrollable）做索引，便于 AI 定位「发送/输入框」等
            val interactive = mutableListOf<String>()
            collectInteractive(root, interactive, 0)
            val tree = sb.toString()
            val cappedTree = if (tree.length > 6000) {
                "${tree.take(6000)}\n... (节点树截断，见下方可交互元素索引)"
            } else tree
            val inter = if (interactive.isNotEmpty())
                "\n\n## 可交互元素索引（clickable/editable/scrollable，共 ${interactive.size}）\n" +
                    interactive.joinToString("\n")
            else ""
            cappedTree + inter
        } catch (e: Exception) {
            "❌ 读取屏幕失败: ${e.message}"
        }
    }

    /** 收集可交互节点（点击/编辑/滚动），输出带坐标的紧凑索引，便于 AI 直接定位「发送」按钮或输入框。 */
    private fun collectInteractive(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || out.size >= 80) return
        if (node.isClickable || node.isEditable || node.isScrollable) {
            val text = node.text?.toString()?.take(40)?.ifBlank { null }
            val desc = node.contentDescription?.toString()?.take(40)?.ifBlank { null }
            val rid = node.viewIdResourceName?.toString()?.substringAfterLast(":")?.take(40)?.ifBlank { null }
            val b = Rect().also { node.getBoundsInScreen(it) }
            val label = text ?: desc ?: rid ?: (node.className?.toString()?.substringAfterLast(".") ?: "?")
            val tag = buildString {
                if (node.isEditable) append(" [editable]")
                if (node.isScrollable) append(" [scroll]")
            }
            out.add("· $label [${b.left},${b.top}][${b.right},${b.bottom}]$tag")
        }
        for (i in 0 until node.childCount.coerceAtMost(50)) collectInteractive(node.getChild(i), out, depth + 1)
    }

    private fun appendNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null) return
        val indent = "  ".repeat(depth.coerceAtMost(8))
        val text = node.text?.toString()?.take(80)?.ifBlank { null }
        val desc = node.contentDescription?.toString()?.take(80)?.ifBlank { null }
        val rid = node.viewIdResourceName?.toString()?.ifBlank { null }
        val cls = node.className?.toString()?.substringAfterLast(".")?.take(30)
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val boundStr = "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]"
        val parts = mutableListOf<String>().apply {
            add(cls ?: "?")
            if (text != null) add("text=\"$text\"")
            if (desc != null) add("desc=\"$desc\"")
            if (rid != null) add("id=$rid")
            add(boundStr)
            if (node.isClickable) add("[clickable]")
            if (node.isScrollable) add("[scrollable]")
            if (node.isEditable) add("[editable]")
            if (node.isChecked) add("[checked=${node.isChecked}]")
        }
        sb.appendLine("$indent${parts.joinToString(" ")}")
        for (i in 0 until node.childCount.coerceAtMost(50)) {
            appendNode(node.getChild(i), sb, depth + 1)
        }
    }
}

/** 获取当前前台应用信息（Activity 组件名）。 */
class GetForegroundAppTool : QuroTool {
    override val name = "get_foreground_app"
    override val description = "获取当前前台应用包名与 Activity 名称，无需参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        return try {
            val root = svc.rootInActiveWindow ?: return "⚠️ 无法获取窗口信息"
            // 从根节点的 packageName 和 Activity 的 viewId 推断
            val pkg = root.packageName?.toString() ?: "未知"
            // Android 5+ 可通过 WindowManager 或 UsageStats 辅助确认
            val info = context.packageManager.getPackageInfo(pkg, 0)
            val label = info.applicationInfo?.loadLabel(context.packageManager)?.toString() ?: pkg
            """{"package":"$pkg","label":"$label"}"""
        } catch (e: Exception) {
            "❌ 获取前台应用失败: ${e.message}"
        }
    }
}

/** 获取屏幕状态（是否亮屏 / 方向 / 尺寸）。 */
class GetScreenStateTool : QuroTool {
    override val name = "get_screen_state"
    override val description = "获取屏幕状态（亮灭 / 方向），无需参数 {}。"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String {
        val pm = context.packageManager
        val display = context.display ?: context.getSystemService(Context.WINDOW_SERVICE)?.let {
            @Suppress("DEPRECATION") it.javaClass.getMethod("getDefaultDisplay").invoke(it)
        } ?: return "❌ 无法获取 Display"
        // 使用 Display API 判断方向
        val rotation = try {
            val m = display.javaClass.getMethod("getRotation")
            when (m.invoke(display) as Int) {
                0 -> "竖直(0°)"
                1 -> "横屏左转(90°)"
                2 -> "倒置(180°)"
                3 -> "横屏右转(270°)"
                else -> "未知"
            }
        } catch (_: Exception) { "未知" }
        val dm = context.resources.displayMetrics
        return """{"screen_on":true,"rotation":"$rotation","width_px":${dm.widthPixels},"height_px":${dm.heightPixels},"density":${dm.densityDpi}}"""
    }
}

// ──────────────────────────── 屏幕操控 ────────────────────────────

private const val TAG = "QuroAccTool"

/** 点击屏幕指定坐标或查找包含目标文本的第一个可点击节点并点击。 */
class TapScreenTool : QuroTool {
    override val name = "tap_screen"
    override val description = "点击屏幕上的元素。支持两种模式：(1) 按 x,y 坐标点击；(2) 按文本内容查找并点击第一个匹配的可点击节点。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "x":{"type":"number","description":"X 坐标（像素）"},
            "y":{"type":"number","description":"Y 坐标（像素）"},
            "text":{"type":"string","description":"要点击的按钮/元素的文本内容"},
            "description":{"type":"string","description":"要点击的内容描述（contentDescription）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        return try {
            when {
                args.has("x") && args.has("y") -> {
                    val x = args.getDouble("x").toFloat()
                    val y = args.getDouble("y").toFloat()
                    clickAt(svc, x, y)
                }
                args.has("text") -> {
                    val text = args.getString("text")!!
                    findAndClick(svc, byText = text)
                }
                args.has("description") -> {
                    val desc = args.getString("description")!!
                    findAndClick(svc, byDesc = desc)
                }
                else -> "❌ 缺少参数：需要 x+y / text / description 任一"
            }
        } catch (e: Exception) {
            "❌ 点击失败: ${e.message}"
        }
    }

    private fun clickAt(svc: AccessibilityService, x: Float, y: Float): String {
        // 坐标越界保护：派发到屏幕外的手势在高版本可能返回 true 却什么都不做（语义成功≠执行成功）
        val dm = svc.resources.displayMetrics
        if (x < 0 || y < 0 || x > dm.widthPixels || y > dm.heightPixels)
            return "❌ 坐标越界(屏幕 ${dm.widthPixels}×${dm.heightPixels}): (${x.toInt()},${y.toInt()})"

        val root = svc.rootInActiveWindow ?: return "⚠️ 无法获取当前窗口根节点（可能被系统限制）"
        // 坐标模式：用户给了明确坐标 → 在 (x,y) 精确派发触摸。
        // 关键修复：不再重定向到「可点击祖先的中心」。此前命中一个整行宽度的列表项时，
        // 会去点该容器的中心，导致指定 x=980 实际点中 x=540（系统性坐标偏移）。
        val hit = hitTestNode(root, x, y)
        val label = hit?.let {
            (it.text?.toString() ?: it.contentDescription?.toString()
                ?: it.className?.toString()?.substringAfterLast(".") ?: "节点")
        } ?: "坐标(${x.toInt()},${y.toInt()})"
        val dispatched = tapGestureAt(svc, x, y)
        return if (dispatched) {
            val target = hit?.let { findClickableAncestor(it) } ?: hit
            if (target?.isCheckable == true) verifyToggle(svc, x, y, target.isChecked)
            else "✅ 已点击「$label」(坐标 ${x.toInt()},${y.toInt()}，精确坐标触摸)"
        } else {
            // 手势被拒：降级到 performAction（不认坐标，只能点元素本身）
            val target = hit?.let { findClickableAncestor(it) } ?: hit
            if (target != null) {
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) "✅ 已点击「$label」(performAction 降级，坐标 ${x.toInt()},${y.toInt()})"
                else "❌ 点击「$label」失败（手势与 performAction 均被拒，可能 UI 未就绪）"
            } else "❌ 轻触手势被系统拒绝派发且坐标处无节点"
        }
    }

    /**
     * 对可勾选节点（开关/复选框），点击后重新读取同一中心点的节点状态，
     * 用 isChecked 是否翻转来「真正确认」点击生效——这是少数能闭环验证的场景。
     */
    private fun verifyToggle(svc: AccessibilityService, cx: Float, cy: Float, before: Boolean): String {
        Thread.sleep(300)
        val root = svc.rootInActiveWindow ?: return "✅ 已派发点击(可勾选节点，回读失败)"
        val hit = hitTestNode(root, cx, cy)
        val node = (hit?.let { findClickableAncestor(it) } ?: hit) ?: return "✅ 已派发点击(可勾选节点，回读失败)"
        val after = node.isChecked
        return if (after != before) "✅ 已点击并确认状态翻转($before→$after)（真实节点中心触摸）"
        else "⚠️ 已派发点击但状态未翻转($before)，可能点击未生效（建议重试）"
    }

    /**
     * 命中测试：返回包含 (x,y) 的最深节点（不限是否可点击）。
     * 调用方再用 [findClickableAncestor] 向上回溯到真正可点击的祖先——
     * 很多可点项的文本在不可点子节点、点击监听在父容器，必须点父容器才生效。
     */
    private fun hitTestNode(root: AccessibilityNodeInfo, x: Float, y: Float): AccessibilityNodeInfo? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
        fun collect(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null) return
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                candidates.add(node to depth)
            }
            for (i in 0 until node.childCount.coerceAtMost(80)) collect(node.getChild(i), depth + 1)
        }
        collect(root, 0)
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.second }?.first
    }

    /**
     * 从给定节点向上回溯到最近的「可点击」祖先（含自身）。
     * ColorOS / 多数列表项的可点击容器是父级，文本/图标在不可点子节点——
     * 只对子节点 performAction 往往返回 true 却什么都不做，必须点父容器。
     */
    private fun findClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var guard = 0
        while (cur != null && !cur.isClickable && guard++ < 24) {
            cur = cur.parent
        }
        return cur
    }

    /**
     * 在指定屏幕坐标派发一次真实触摸手势（UIAutomator 同款可靠方案）。
     * 相比 performAction(ACTION_CLICK)，真触摸事件在 ColorOS 等定制 View 上更不容易被吞。
     * 返回系统是否成功「派发」手势（派发≠命中，但点的是真实节点中心，命中概率极高）。
     */
    private fun tapGestureAt(svc: AccessibilityService, cx: Float, cy: Float): Boolean {
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val gd = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return svc.dispatchGesture(gd, null, null)
        }
        val done = CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatched = svc.dispatchGesture(gd, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gd: android.accessibilityservice.GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(gd: android.accessibilityservice.GestureDescription?) { ok.set(false); done.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) return false
        done.await(2, TimeUnit.SECONDS)
        return ok.get()
    }

    private fun findAndClick(svc: AccessibilityService, byText: String? = null, byDesc: String? = null): String {
        // 按文本/描述查找（不要求节点本身可点击，因为可点监听常在父容器）；找不到则延迟重试一次（应对页面跳变）
        var node = svc.rootInActiveWindow?.let { findNode(it, byText, byDesc, 0) }
        if (node == null) {
            Thread.sleep(250)
            node = svc.rootInActiveWindow?.let { findNode(it, byText, byDesc, 0) }
        }
        if (node == null) return "❌ 未找到匹配节点: ${byText ?: byDesc}"
        // 向上回溯到可点击祖先，再点其真实中心（解决「点了文本子节点却没反应」）
        val target = findClickableAncestor(node) ?: node
        val r = Rect().also { target.getBoundsInScreen(it) }
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val label = (target.text?.toString() ?: target.contentDescription?.toString() ?: byText ?: byDesc ?: "节点")
        val dispatched = tapGestureAt(svc, cx, cy)
        return if (dispatched) {
            if (target.isCheckable) verifyToggle(svc, cx, cy, target.isChecked)
            else "✅ 已点击「$label」(真实节点中心触摸)"
        } else {
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) "✅ 已点击「$label」(performAction 降级)" else "❌ 点击「$label」失败（建议重试）"
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, byText: String?, byDesc: String?, depth: Int): AccessibilityNodeInfo? {
        if (depth > 18) return null
        val t = root.text?.toString()
        val d = root.contentDescription?.toString()
        // 精确 + 模糊匹配文本/描述（不要求 isClickable，点击时再向上回溯可点祖先）
        if (byText != null) {
            if (t == byText || t?.contains(byText) == true) return root
        }
        if (byDesc != null) {
            if (d == byDesc || d?.contains(byDesc) == true) return root
        }
        for (i in 0 until root.childCount.coerceAtMost(40)) {
            val found = findNode(root.getChild(i), byText, byDesc, depth + 1)
            if (found != null) return found
        }
        return null
    }
}

/** 长按屏幕元素（x,y 坐标 或 文本/描述查找），用于触发长按菜单（选择/弹出菜单/拖拽预备/应用卸载等）。 */
class LongPressScreenTool : QuroTool {
    override val name = "long_press_screen"
    override val description = "长按屏幕上的元素，触发长按菜单/选择/拖拽预备。支持三种定位：(1) x,y 坐标长按；(2) 按文本内容查找并长按；(3) 按内容描述(description)查找并长按。duration_ms 可选，默认 600ms。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "x":{"type":"number","description":"X 坐标（像素）"},
            "y":{"type":"number","description":"Y 坐标（像素）"},
            "text":{"type":"string","description":"要长按的按钮/元素的文本内容"},
            "description":{"type":"string","description":"要长按的内容描述（contentDescription）"},
            "duration_ms":{"type":"number","description":"长按持续时间毫秒（默认 600，范围 200-3000）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        val duration = args.optLong("duration_ms", 600L).coerceIn(200L, 3000L)
        return try {
            val (cx, cy, label) = when {
                args.has("x") && args.has("y") -> {
                    val x = args.getDouble("x").toFloat()
                    val y = args.getDouble("y").toFloat()
                    Triple(x, y, "坐标(${x.toInt()},${y.toInt()})")
                }
                args.has("text") -> {
                    val node = svc.rootInActiveWindow?.let { findNodeLp(it, args.getString("text"), null, 0) }
                        ?: return "❌ 未找到文本匹配节点: ${args.getString("text")}"
                    val t = findClickableAncestorLp(node) ?: node
                    val r = Rect().also { t.getBoundsInScreen(it) }
                    Triple((r.left + r.right) / 2f, (r.top + r.bottom) / 2f,
                        (t.text?.toString() ?: t.contentDescription?.toString() ?: args.getString("text")))
                }
                args.has("description") -> {
                    val node = svc.rootInActiveWindow?.let { findNodeLp(it, null, args.getString("description"), 0) }
                        ?: return "❌ 未找到描述匹配节点: ${args.getString("description")}"
                    val t = findClickableAncestorLp(node) ?: node
                    val r = Rect().also { t.getBoundsInScreen(it) }
                    Triple((r.left + r.right) / 2f, (r.top + r.bottom) / 2f,
                        (t.contentDescription?.toString() ?: t.text?.toString() ?: args.getString("description")))
                }
                else -> return "❌ 缺少参数：需要 x+y / text / description 任一"
            }
            // 坐标越界保护
            val dm = svc.resources.displayMetrics
            if (cx < 0 || cy < 0 || cx > dm.widthPixels || cy > dm.heightPixels)
                return "❌ 坐标越界(屏幕 ${dm.widthPixels}×${dm.heightPixels}): (${cx.toInt()},${cy.toInt()})"
            val dispatched = dispatchLongPress(svc, cx, cy, duration)
            if (dispatched) "✅ 已长按「$label」(${cx.toInt()},${cy.toInt()}，约 ${duration}ms)"
            else "❌ 长按手势被系统拒绝派发（建议重试）"
        } catch (e: Exception) {
            "❌ 长按失败: ${e.message}"
        }
    }

    private fun dispatchLongPress(svc: AccessibilityService, cx: Float, cy: Float, dur: Long): Boolean {
        // 在同一点保持 dur 毫秒即构成长按手势
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val gd = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, dur))
            .build()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return svc.dispatchGesture(gd, null, null)
        }
        val done = CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatched = svc.dispatchGesture(gd, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gd: android.accessibilityservice.GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(gd: android.accessibilityservice.GestureDescription?) { ok.set(false); done.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) return false
        done.await(4, TimeUnit.SECONDS)
        return ok.get()
    }

    private fun findClickableAncestorLp(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var guard = 0
        while (cur != null && !cur.isClickable && guard++ < 24) cur = cur.parent
        return cur
    }

    private fun findNodeLp(root: AccessibilityNodeInfo, byText: String?, byDesc: String?, depth: Int): AccessibilityNodeInfo? {
        if (depth > 18) return null
        val t = root.text?.toString()
        val d = root.contentDescription?.toString()
        if (byText != null) { if (t == byText || t?.contains(byText) == true) return root }
        if (byDesc != null) { if (d == byDesc || d?.contains(byDesc) == true) return root }
        for (i in 0 until root.childCount.coerceAtMost(40)) {
            val found = findNodeLp(root.getChild(i), byText, byDesc, depth + 1)
            if (found != null) return found
        }
        return null
    }
}

/** 在屏幕上滑动（上滑 / 下滑 / 左滑 / 右滑 / 自定义起止坐标）。 */
class SwipeScreenTool : QuroTool {
    override val name = "swipe_screen"
    override val description = "在屏幕上执行滑动手势。支持预设方向或自定义起止坐标。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "direction":{"type":"string","enum":["up","down","left","right"],"description":"滑动方向（默认 up 即向上划/内容向下滚动）"},
            "start_x":{"type":"number","description":"起点 X 像素"},
            "start_y":{"type":"number","description":"起点 Y 像素"},
            "end_x":{"type":"number","description":"终点 X 像素"},
            "end_y":{"type":"number","description":"终点 Y 像素"},
            "duration_ms":{"type":"number","description":"手势持续时间毫秒（默认 300）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        return try {
            val dm = context.resources.displayMetrics
            val w = dm.widthPixels.toFloat()
            val h = dm.heightPixels.toFloat()
            val duration = args.optLong("duration_ms", 300L)

            val (sx, sy, ex, ey) = if (args.has("start_x") && args.has("start_y")) {
                Quadruple(
                    args.getDouble("start_x").toFloat(),
                    args.getDouble("start_y").toFloat(),
                    args.getDouble("end_x").toFloat(),
                    args.getDouble("end_y").toFloat(),
                )
            } else {
                when (args.optString("direction", "up")) {
                    "down" -> Quadruple(w / 2f, h * 0.3f, w / 2f, h * 0.7f)   // 下滑
                    "left" -> Quadruple(w * 0.7f, h / 2f, w * 0.3f, h / 2f) // 左滑
                    "right" -> Quadruple(w * 0.3f, h / 2f, w * 0.7f, h / 2f)// 右滑
                    else -> Quadruple(w / 2f, h * 0.7f, w / 2f, h * 0.3f)  // 上滑(默认)
                }
            }
            dispatchSwipe(svc, sx, sy, ex, ey, duration)
        } catch (e: Exception) {
            "❌ 滑动失败: ${e.message}"
        }
    }

    private data class Quadruple(val f1: Float, val f2: Float, val f3: Float, val f4: Float)

    private fun dispatchSwipe(svc: AccessibilityService, sx: Float, sy: Float, ex: Float, ey: Float, dur: Long): String {
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val gd = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, dur))
            .build()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val dispatched = svc.dispatchGesture(gd, null, null)
            return if (dispatched) "⚠️ 已派发滑动 ($sx,$sy)→($ex,$ey)（结果无法同步确认）"
            else "❌ 滑动手势被系统拒绝派发"
        }
        val done = CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatched = svc.dispatchGesture(gd, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gd: android.accessibilityservice.GestureDescription?) { ok.set(true); done.countDown() }
            override fun onCancelled(gd: android.accessibilityservice.GestureDescription?) { ok.set(false); done.countDown() }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) return "❌ 滑动手势被系统拒绝派发"
        done.await(2, TimeUnit.SECONDS)
        // onCompleted 仅代表「系统已派发手势」，不代表滑动真的命中了可滚内容，故标 ⚠️ 而非 ✅
        return if (ok.get()) "⚠️ 已派发滑动手势 ($sx,$sy)→($ex,$ey)（结果无法同步确认）" else "❌ 滑动被取消或超时"
    }
}

/** 在可编辑框内输入文本（先查找再输入）。 */
class InputTextTool : QuroTool {
    override val name = "input_text"
    override val description = "在屏幕上找到输入框并填入文本。可通过 hint/text/description 定位输入框。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "text":{"type":"string","description":"要输入的文本内容（必填）"},
            "hint":{"type":"string","description":"输入框的 hint 文本（可选定位用）"},
            "target_text":{"type":"string","description":"输入框当前的文本（可选定位用）"},
            "target_desc":{"type":"string","description":"输入框的 contentDescription（可选定位用）"}
        },
        "required":["text"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        val text = args.optString("text", "") ?: return "❌ 缺少 text 参数"
        return try {
            val root = svc.rootInActiveWindow ?: return "⚠️ 无法获取窗口根节点"
            val target = findEditable(root, args.optString("hint"), args.optString("target_text"), args.optString("target_desc"))
                ?: return "❌ 未找到输入框"
            // 先粘贴到剪贴板，再执行 ACTION_PASTE
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("input", text))
            Thread.sleep(100)
            if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                Thread.sleep(150)
                "✅ 已输入: $text"
            } else {
                // 降级：尝试 ACTION_SET_TEXT（API 18+）
                val arg = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arg))
                    "✅ 已输入(setText): $text"
                else "❌ 输入失败"
            }
        } catch (e: Exception) {
            "❌ 输入文本失败: ${e.message}"
        }
    }

    private fun findEditable(root: AccessibilityNodeInfo, hint: String?, targetText: String?, desc: String?, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 12) return null
        if (root.isEditable) {
            if (hint != null && root.hintText?.toString()?.contains(hint) == true) return root
            if (targetText != null && root.text?.toString() == targetText) return root
            if (desc != null && root.contentDescription?.toString()?.contains(desc) == true) return root
            if (hint == null && targetText == null && desc == null) return root // 取第一个编辑框
        }
        for (i in 0 until root.childCount.coerceAtMost(30)) {
            val found = findEditable(root.getChild(i), hint, targetText, desc, depth + 1)
            if (found != null) return found
        }
        return null
    }
}

/** 滚动列表（向前/向后）。 */
class ScrollScreenTool : QuroTool {
    override val name = "scroll_screen"
    override val description = "滚动当前屏幕上的可滚动容器（列表/页面）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "direction":{"type":"string","enum":["forward","backward","up","down"],"description":"滚动方向（默认 forward 向前/向下翻页）"},
            "count":{"type":"integer","description":"滚动次数（默认 3）"}
        }
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        return try {
            val root = svc.rootInActiveWindow ?: return "⚠️ 无法获取窗口根节点"
            val dir = args.optString("direction", "forward")
            val count = args.optInt("count", 3).coerceIn(1, 20)
            val action = when (dir) {
                "backward", "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            // 找到最近的 scrollable 容器
            var scrolled = 0
            val target = findScrollable(root) ?: root
            repeat(count) {
                if (target.performAction(action)) scrolled++
                Thread.sleep(200)
            }
            "✅ 执行滚动 $dir ×$count 次，实际成功 $scrolled 次"
        } catch (e: Exception) {
            "❌ 滚动失败: ${e.message}"
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 10) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount.coerceAtMost(20)) {
            val found = findScrollable(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }
}

// ──────────────────────────── 全局动作 ────────────────────────────

/** 执行全局无障碍动作（返回键 / 最近任务 / 展开通知栏等）。 */
class GlobalActionTool : QuroTool {
    override val name = "global_action"
    override val description = "执行全局系统级动作：back（返回）、home（主页）、recents（最近任务）、notifications（展开通知栏）、lock_screen（锁屏）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","enum":["back","home","recents","notifications","quick_settings","power_dialog","lock_screen","take_screenshot"],"description":"要执行的全局动作"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arguments: String): String {
        val svc = QuroAccessibilityService.instance ?: return "❌ 无障碍服务未连接"
        val args = JSONObject(arguments)
        val actionStr = args.optString("action", "")
        val ga = when (actionStr) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
                AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
            "take_screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> return "❌ 未知动作: $actionStr（支持: back/home/recents/notifications/quick_settings/power_dialog/lock_screen/take_screenshot）"
        }
        return if (svc.performGlobalAction(ga))
            "✅ 全局动作 $actionStr 执行成功"
        else "❌ 全局动作 $actionStr 执行失败（可能需要更高权限或系统限制）"
    }
}
