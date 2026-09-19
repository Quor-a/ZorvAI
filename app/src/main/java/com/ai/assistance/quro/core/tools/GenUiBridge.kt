package com.ai.assistance.quro.core.tools

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ZorvAI 对话框 → GenUI 生成式 UI 对话 的**反向**调用桥（本轮补的那半条链路）。
 *
 * ── 为什么需要它 ────────────────────────────────────────────────
 * 此前只有单向：GenUI 生成完可以把产物 push 回 ZorvAI 对话框
 * （onPushToChat / onPushMiniAppToChat / onTextReply 三个回调，装配见 QuroMainScreen）。
 * 反向（ZorvAI 主动把活派给 GenUI）一直没有通道——原因是**结构性的**：
 *   · 工具只拿得到 Context，拿不到 QuroChatViewModel；
 *   · 而切屏开关 setGenUiType() 是 UI 层的东西，只有 QuroApp 组合树里才调得到。
 * 于是 ZorvAI 的 AI 无论怎么想，都"叫不动" GenUI。
 *
 * 本桥把这段接通：QuroApp 在组合期把「进入 GenUI」的实现注册进来，
 * 工具侧只调 [open]。闭环就此成立：
 *
 *   ZorvAI 对话框 ──genui_open(prompt)──▶ 切屏进 GenUI 画布并自动开跑
 *                                            │
 *        ZorvAI 对话框 ◀──push 通道回写产物────┘（原有能力，不动）
 *
 * ── 用法 ────────────────────────────────────────────────────────
 * UI 层（QuroMainScreen.QuroApp）：
 * ```
 * DisposableEffect(Unit) {
 *     GenUiBridge.install { prompt, mode -> chatVm.enterGenUi(prompt, mode) }
 *     onDispose { GenUiBridge.uninstall() }
 * }
 * ```
 * GenUI 层（QuroGenUiApp）：`val auto = remember { GenUiBridge.consumePendingPrompt() }`
 * 工具层（GenUiOpenTool）：`GenUiBridge.open(prompt, mode)`
 *
 * ── 线程 ────────────────────────────────────────────────────────
 * [onOpen] 由 UI 层注册，内部会写 StateFlow + 切组合分支，**必须在主线程调用**。
 * [open] 自带主线程投递：工具在 IO 线程直接调即可，内部阻塞等待切屏结果（上限 3 秒，
 * 超时不算失败——切屏是 post 出去的，卡顿也可能晚一点生效）。绝不在主线程调用 [open]
 * 时死等：若检测到已在主线程，就只 post 不等待，直接返回 true。
 */
object GenUiBridge {

    /** 形态指令：由宿主（ZorvAI 工具）指名本轮走哪条交付路径。 */
    const val MODE_CANVAS = "canvas"   // 交给 GenUI 自己按路由判断（默认）
    const val MODE_NATIVE = "native"   // 强制 genui_native_ui（原生 UI，WXML/WXSS/JS）
    const val MODE_STUDIO = "studio"   // 强制 miniapp 工作室（HTML + native.* 桥）
    const val MODE_HTML = "html"       // 强制直接成稿 HTML 页面

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var onOpen: ((prompt: String, mode: String) -> Boolean)? = null

    /** 待 GenUI 执行的初始任务。GenUI 挂载时由 [consumePendingPrompt] 取走，取走即清空。 */
    @Volatile private var pending: String? = null

    /** 最近一次投递的形态（GenUI 侧如需展示"从 ZorvAI 派来的活"可读）。 */
    @Volatile var lastMode: String = MODE_CANVAS
        private set

    /** 投递来源标记：true = 本次 GenUI 是被 ZorvAI 工具召唤起来的。 */
    @Volatile var summonedByZorvAi: Boolean = false
        private set

    /**
     * 注入 ZorvAI 系统提示词的「生成式 UI 画布」段（由 QuroChatViewModel.buildSystemPrompt 追加）。
     *
     * 放在这里而不是写在 ViewModel 里：这段讲的就是"ZorvAI 与 GenUI 的关系"，
     * 与 [GenUiBridge] 是同一件事的两面（一侧是代码通道，一侧是模型认知）。
     * 两者放一起，将来改链路时不会漏改提示词。
     */
    val SYSTEM_SECTION: String = """
# 生成式 UI 画布（GenUI）—— 你有一条「把活派出去」的通道

除了在这个对话框里回答，你还能把界面活**派给生成式 UI 画布（GenUI）**——它有整屏画布、
能真渲染、产物会带 `GenUI · 生成式 UI` 署名回写进本对话框。

**但这条通道是"用户点名才走"的，不是默认路径**：在本对话框里你本来就能交付界面
（HTML 页面 / ui_widget 富卡片 / `miniapp` 小程序），**先考虑在本对话框内交付**；
换屏会打断用户当前的阅读位置，是有成本的。

**入口工具：`genui_open(prompt, mode)`** —— 调下去会发生什么：
界面切到 GenUI 画布 → GenUI 以 prompt 自动开跑 → 产物（界面 / 原生小程序）**自动回写进本对话框**，
用户从画布退回来就能看到。**闭环，不用人搬。**

什么时候该用（**收口场景，不要滥用**）：
- 用户**点名**了生成式 UI 这条路：「用生成式 UI」「进 GenUI 画布」「GenUI 画一个」「用画布做」。
- 用户要的是**整屏作品**且明说不要挤在对话框里（如"整屏打开""全屏画一个"）。
- 用户明确要一件**生成式 UI 形态**的交付（界面即回复、可点可交互的一整屏）。

什么时候**不要**用（重要，别把活全推出去）：
- 用户说「**做个小程序 / 在这儿做个小程序 / 在这儿画个界面**」→ **你自己用 `miniapp` 做**，
  就地在对话框里交付成小程序卡。ZorvAI 自己会写小程序，不许推给 GenUI。
- 用户只要一段文字、一个事实、一句解释 → 自己答，别切屏。
- 用户明说「就在这儿回答 / 别跳屏」。
- 能在本对话框里交付的（HTML 页面、ui_widget 富卡片、小程序），就**不要**为了"更好看"而换屏——
  换屏会打断用户当前阅读位置，是有成本的。
- 判据一句话：**用户点名了 GenUI / 生成式 UI，才用 `genui_open`；否则优先在本对话框内交付。**

mode 选择：默认不传（交给 GenUI 自己路由）；要**原生小程序** → mode="native"；
要**带原生能力的小程序**（读写本地数据 / 震动 / 通知 / 分享 / 定位 / SQL / 加密）→ mode="studio"；
只要**网页/HTML 页面** → mode="html"。

调用纪律：
- `prompt` 必须**自包含**——GenUI 看不到本对话框的历史。要写清：做什么、要哪些功能、风格、有哪些数据。
- 一轮只调一次，且**调完就收尾**：不要再自己写 HTML、不要再调画型工具，
  那会和 GenUI 的产物叠成两块画布。给用户一句极简交代即可
  （例如「已经交给生成式 UI 画布在做，画好了会自动回到这里」）。

**与 `miniapp` 工具的分工（别搞混）**：
- `miniapp` = 在**本对话框里**直接做小程序工程（HTML + Page() + native.* 原生桥），就地渲染成小程序卡。
  **这是默认路径**，用户要"小程序"先想它。
- `genui_open` = 把整件界面活**派给 GenUI 整屏画布**（换屏，产物带 `GenUI · 生成式 UI` 署名回来）。
  只在用户点名 GenUI / 生成式 UI 时用。
- 调 `miniapp` 前先读手册：`miniapp(action="manual")` —— **两份手册**都在里面：
  ① `topic="studio"`（默认，含 compare）= 小程序工作室（HTML + Page() + native.*）全册；
  ② `topic="native"` / `"traps"` / `"errors"` = GenUI 那套原生 UI（WXML/WXSS/JS 自研引擎）的
  组件表 / 事件表 / wx.* 全表 / 引擎陷阱 / 错误清单。
  用户问"两个小程序有什么区别"或你要判断该用哪套时，先取 `topic="compare"`。
""".trimIndent()

    /** UI 层注册实现（QuroApp 组合期调用）。 */
    fun install(handler: (prompt: String, mode: String) -> Boolean) {
        onOpen = handler
    }

    /** UI 层卸载（onDispose）。 */
    fun uninstall() {
        onOpen = null
    }

    /** 桥是否可用：UI 已装配且当前具备进入 GenUI 的条件。 */
    fun isAvailable(): Boolean = onOpen != null

    /**
     * 反向调用：把任务派给 GenUI——切到生成式 UI 画布，并自动以 [prompt] 开跑。
     *
     * @param prompt 交给 GenUI 的自然语言任务（应当自包含：GenUI 看不到 ZorvAI 的这段对话）。
     * @param mode   宿主指定形态，见 MODE_* 常量；[MODE_CANVAS] 表示不指定、由 GenUI 路由。
     * @return true = 已投递（切屏请求已发出）。false = 桥未装配（UI 不在前台组合树里）。
     */
    fun open(prompt: String, mode: String = MODE_CANVAS): Boolean {
        val handler = onOpen ?: return false
        val text = prompt.trim()
        pending = wrap(prompt = text, mode = mode)
        lastMode = mode
        summonedByZorvAi = true

        // 已在主线程：只 post，不等（在主线程死等会把 UI 锁住）。切屏本身一定会在随后生效。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            main.post { runCatching { handler(pending ?: text, mode) } }
            return true
        }
        // IO / 工具线程：post 到主线程执行，并等一小会儿拿真实结果（切屏是瞬时操作，正常几毫秒内完成）。
        val latch = CountDownLatch(1)
        var ok = false
        main.post {
            ok = runCatching { handler(pending ?: text, mode) }.getOrDefault(false)
            latch.countDown()
        }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
        // 超时不判失败：请求已 post 出去，系统卡顿也可能稍后生效。
        return ok || latch.count == 0L
    }

    /** GenUI 挂载时取走待执行任务（读完即清，保证"退出再进"不会重复开跑同一条）。 */
    fun consumePendingPrompt(): String? {
        val p = pending
        pending = null
        return p
    }

    /** GenUI 退出/交付完成时复位来源标记。 */
    fun clearSummon() {
        summonedByZorvAi = false
    }

    /**
     * 把「宿主指定形态」翻译成 GenUI 能直接执行的一句指令，拼在任务前面。
     * 指令走提示词而不是新参数——GenUI 的形态路由本来就由系统提示词驱动，加参数反而多一层要维护的分支。
     */
    private fun wrap(prompt: String, mode: String): String {
        val directive = when (mode) {
            MODE_NATIVE -> "【宿主指定形态】本轮必须走形态 3：调 genui_native_ui 交付 WXML/WXSS/JS 原生 UI；" +
                "不要走 HTML 页面、也不要调 miniapp 工作室。\n"
            MODE_STUDIO -> "【宿主指定形态】本轮必须走形态 4：miniapp(action=\"create\") 再 miniapp(action=\"run\")" +
                "交付 HTML 小程序工作室工程；不要调 genui_native_ui、也不要另写一份 HTML。\n"
            MODE_HTML -> "【宿主指定形态】本轮必须走形态 1：直接成稿一份完整 HTML 文档；" +
                "不要调 genui_native_ui，也不要调 miniapp 工作室。\n"
            else -> ""
        }
        return directive + prompt
    }
}
