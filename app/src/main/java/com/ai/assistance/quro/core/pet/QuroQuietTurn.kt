package com.ai.assistance.quro.core.pet

import android.view.accessibility.AccessibilityNodeInfo
import com.ai.assistance.quro.service.QuroAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「安静回合」：桌宠轻点互动、语音球识别后自动发起的 AI 回合。
 *
 * 这类回合不是用户手打的一句话，而是应用自己接线出来的陪伴式对话，
 * 因此有三条硬规则：
 *
 *  1. **内部诊断文本绝不进对话框**：互动提示词模板、无障碍节点树 dump、
 *     「无障碍服务未连接」、HTTP 429 / 5xx、API Key 缺失等，都是应用内部状态，
 *     只能作为隐藏上下文或轻提示，不能变成用户气泡 / AI 错误气泡。
 *  2. **失败静默**：本轮请求失败时整轮撤下（用户消息 + 错误气泡都不留），
 *     只给一句轻提示；连续失败后短时冷却，不再反复发起请求刷屏。
 *  3. **不做沉重读屏**：桌宠原先在主线程同步跑完整节点树 + 截图 + OCR（内含
 *     5 秒等待），既是无屏 ANR 元凶，也会把 ZorvAI 自己的聊天记录当「屏幕线索」
 *     读回来形成自我污染。这里只做纯内存节点文本采集，且排除自身窗口。
 *
 * 正常回复不受影响：桌宠 / 语音说了一句完整的话，照常留在对话里。
 */
object QuroQuietTurn {

    private val _hint = MutableStateFlow<String?>(null)

    /** 一次性轻提示事件（Toast / 桌宠气泡消费后清空）。 */
    val hint: StateFlow<String?> = _hint.asStateFlow()

    fun emit(message: String) {
        _hint.value = message
    }

    fun consume() {
        _hint.value = null
    }

    const val FAILURE_HINT = "网络开小差了，稍后再试试"

    // ── 失败冷却：连续失败 N 次后，短时间内不再自动发起请求 ──
    private const val COOLDOWN_MS = 60_000L
    private const val FAILURES_BEFORE_COOLDOWN = 2

    @Volatile private var consecutiveFailures = 0
    @Volatile private var cooldownUntil = 0L

    /** 自动回合发起前调用：处于冷却期则返回 false，调用方只播动作 / 轻提示。 */
    fun allowAutoTurn(): Boolean =
        System.currentTimeMillis() >= cooldownUntil

    fun noteSuccess() {
        consecutiveFailures = 0
        cooldownUntil = 0L
    }

    fun noteFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= FAILURES_BEFORE_COOLDOWN) {
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
        }
    }

    /** 是否为应用内部的诊断 / 报错文案（而不是 AI 说给用户的正常内容）。 */
    fun isDiagnosticError(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.trimStart()
        if (t.startsWith("⚠️") || t.startsWith("❌") || t.startsWith("⏳")) return true
        if (t.startsWith("请求失败") || t.startsWith("回复生成失败") || t.startsWith("发生错误")) return true
        if (t.startsWith("语音球出错") || t.startsWith("模型服务")) return true
        if (HTTP_ERROR.containsMatchIn(t)) return true
        return t.contains("无障碍服务未连接") || t.contains("语音球出错了") ||
            t.contains("尚未配置模型 API Key") || t.contains("已达到每周") ||
            t.contains("使用上限")
    }

    /** 是否为无障碍节点树 / 坐标 dump（这类文本永远不能当「屏幕线索」给人看）。 */
    fun looksLikeUiDump(text: String): Boolean =
        UI_CLASS.containsMatchIn(text) || COORDS.containsMatchIn(text)

    /**
     * 把回复里的诊断 / 工具痕迹清掉，只留适合在桌宠气泡里展示的一句人话。
     * 桌宠气泡空间极小，任何 ❌ / HTTP 429 / 节点树都不该出现在这里。
     */
    fun stripDiagnostics(text: String): String {
        if (text.isBlank()) return ""
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isDiagnosticError(it) && !looksLikeUiDump(it) }
            .joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .take(120)
    }

    /**
     * 采集「当前屏幕可读文字」作为桌宠互动的隐藏线索。
     *
     * 必须在 IO 线程调用。与 `ReadScreenTool` 的区别：
     *  - 不截图、不 OCR、无 CountDownLatch 等待，纯内存遍历，毫秒级返回；
     *  - 只取 text / contentDescription，绝不输出类名、坐标、资源 id、节点树；
     *  - **排除 ZorvAI 自身窗口**：桌宠悬浮窗叠在主界面上，读自己的聊天气泡
     *    （含历史报错）当线索会导致错误文本反复回流，形成自我污染；
     *  - 服务未连接 / 根节点为空 / 没有可读文字时返回 null，调用方静默跳过，
     *    绝不把「❌ 无障碍服务未连接」这类错误文案注入对话。
     */
    fun readScreenCue(selfPackage: String, maxChars: Int = 600): String? {
        val svc = QuroAccessibilityService.instance ?: return null
        val root = runCatching { svc.rootInActiveWindow }.getOrNull() ?: return null
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
        // 前台就是本应用：桌宠陪伴场景读自己没有意义，且会读回聊天记录里的报错。
        if (pkg.isNullOrBlank() || pkg == selfPackage) return null

        val lines = LinkedHashSet<String>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 12 || lines.size >= 40 || visited >= 300) return
            visited++
            for (raw in listOfNotNull(
                runCatching { node.text?.toString()?.trim() }.getOrNull(),
                runCatching { node.contentDescription?.toString()?.trim() }.getOrNull(),
            )) {
                val s = raw.take(80)
                if (s.length >= 2 && !isDiagnosticError(s) && !looksLikeUiDump(s) &&
                    !s.startsWith("FrameLayout") && !s.contains("text=\"")
                ) lines.add(s)
            }
            for (i in 0 until runCatching { node.childCount }.getOrDefault(0).coerceAtMost(40)) {
                walk(runCatching { node.getChild(i) }.getOrNull(), depth + 1)
            }
        }
        // 不手动 recycle rootInActiveWindow：递归拿到的子节点未逐个回收，
        // 半回收反而会让后续遍历拿到失效节点；系统随窗口刷新统一回收。
        walk(root, 0)
        if (lines.isEmpty()) return null
        return lines.take(24).joinToString("；").take(maxChars).takeIf { it.isNotBlank() }
    }

    private val HTTP_ERROR = Regex("""HTTP\s*[45]\d\d""", RegexOption.IGNORE_CASE)
    private val UI_CLASS = Regex(
        """FrameLayout|LinearLayout|ComposeView|RelativeLayout|ConstraintLayout|ScrollView|RecyclerView""",
    )
    private val COORDS = Regex("""\[\d+,\d+\]\[\d+,\d+\]""")
}
