package com.ai.assistance.quro.core.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台虚拟屏「打开应用」的接管契约。
 *
 * 🔴 对应两条用户反馈：
 *  ① 「软件多开 AI 根本不会换起打开，老是说在主屏」
 *  ② 「在主屏情况下 AI 应该直接接管执行任务，不是提示」
 *
 * ## 当时的真实缺陷（三条，全在特权进程侧）
 * 1. **判定过宽**：`inUseOnMainScreen` 用的是「主屏任务栈里出现过这个包」。
 *    用户切走过的 App 任务仍留在栈里 → 这个包**永远**被判「用户正在用」→ 再也打不开。
 * 2. **需求反了**：命中就直接 `return START_IN_USE_ON_MAIN`，上层只能放弃并解释
 *    「用户正在主屏使用」，而不是接管。
 * 3. **多开应用必挂**：`startAppInUser` 用的是**主用户**的
 *    `getLaunchIntentForPackage`；只在分身里装着的包在主用户 PM 里查不到 →
 *    返回 START_NOT_FOUND → 上层判定「没装」。而 `appCopyUsers` 又只读
 *    `UserManager.userProfiles`，国产 ROM 的应用分身（常见 user 999）不在里面。
 *
 * 本测试把「接管阶梯」和「分身解析」这两条钉死，避免以后又退回到「放弃 + 反问用户」。
 */
class BackgroundLaunchTakeoverTest {

    private fun source(relative: String): String {
        val cands = listOf(
            java.io.File("../$relative"),
            java.io.File(relative),
            java.io.File("D:/Calw OS-project/QuroAI/$relative"),
            java.io.File("D:\\Calw OS-project\\QuroAI\\$relative"),
        )
        for (f in cands) {
            if (f.exists()) return f.readText()
        }
        throw AssertionError("找不到源码文件：$relative（工作目录 ${java.io.File(".").absolutePath}）")
    }

    private val shell by lazy { source("app/src/main/java/com/ai/assistance/quro/core/shizuku/QuroShellService.kt") }
    private val vs by lazy { source("app/src/main/java/com/ai/assistance/quro/core/display/QuroVirtualScreen.kt") }
    private val best by lazy { ToolCapabilityDirectory.buildBestPractices() }
    private val bgTool by lazy {
        source("app/src/main/java/com/ai/assistance/quro/core/tools/BackgroundScreenTool.kt")
    }

    // ─────────── ① 不许再「见到主屏就放弃」 ───────────

    @Test
    fun `startApp 不得在主屏命中时直接放弃`() {
        assertFalse(
            "startApp 里又出现了 `if (inUseOnMainScreen(...)) return START_IN_USE_ON_MAIN`。\n" +
                "这正是「AI 老是说在主屏、根本打不开」的根源：用户切走过的 App 任务还在栈里，" +
                "这个包就永远被判「在用」。必须走接管阶梯（带前台 → 挪屏 → 另起实例 → 接管）。",
            shell.contains("if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN"),
        )
        assertTrue(
            "startApp 必须保留接管阶梯的四个结果码",
            listOf("START_OK_RESUMED", "START_OK_MOVED", "START_OK_SECOND", "START_OK_TAKEOVER")
                .all { shell.contains("START_OK_RESUMED") && shell.contains(it) },
        )
    }

    @Test
    fun `主屏在用判定必须真的可见才算`() {
        assertTrue(
            "inUseOnMainScreen 必须基于「任务真的可见」判定（mainScreenTask(...)?.visible），" +
                "不能只用「主屏任务栈里包含该包」——后者会把用户早就切走的历史任务也算成在用。",
            shell.contains("mainScreenTask(packageName)?.visible == true"),
        )
        assertTrue(
            "缺少 isTaskVisible：需要按 TaskInfo.isVisible → 焦点 task → 主屏最近任务 三级判定",
            shell.contains("private fun isTaskVisible("),
        )
        assertTrue(
            "拿不到可见性字段时应保守判为「不可见」（宁可接管也不要一律放弃），注释里要写明这一点",
            shell.contains("宁可接管"),
        )
    }

    @Test
    fun `另起实例必须带 MULTIPLE_TASK 才不打扰用户主屏那一份`() {
        assertTrue(
            "startFresh 必须能给 intent 加 FLAG_ACTIVITY_MULTIPLE_TASK，" +
                "否则同一包在主屏有任务时系统只会把那个 task 带到前面，后台屏永远空着。",
            shell.contains("FLAG_ACTIVITY_MULTIPLE_TASK"),
        )
        assertTrue(
            "startView / startShare 也不许再因为主屏在跑就返回 IN_USE_ON_MAIN",
            !shell.contains("if (packageName != null && inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN") &&
                !shell.contains("if (inUseOnMainScreen(packageName)) return START_IN_USE_ON_MAIN\n        val intent = Intent(Intent.ACTION_SEND)"),
        )
    }

    // ─────────── ② 多开 / 双开必须能打开 ───────────

    @Test
    fun `分身必须按 userId 解析 launch intent`() {
        assertTrue(
            "startAppInUser 里必须走 launchIntentAsUser：" +
                "只在分身（如 user 999）里装着的包，在主用户 PackageManager 里查不到，\n" +
                "用 user 0 的 getLaunchIntentForPackage 会返回 null → 上层判定「没装」→ 多开应用永远打不开。",
            shell.contains("launchIntentAsUser(packageName, userId)"),
        )
        assertTrue(
            "缺少 launchIntentAsUser 实现（SystemApi 反射 + cmd resolve-activity 兜底）",
            shell.contains("getLaunchIntentForPackageAsUser"),
        )
    }

    @Test
    fun `分身用户清单必须包含国产 ROM 的应用分身`() {
        assertTrue(
            "appCopyUsers 必须读 `pm list users`：UserManager.userProfiles 只返回与当前用户有关联的 profile，" +
                "OPPO / 小米的应用分身用户（常见 999、10）不在里面，只用 userProfiles 会漏掉它们。",
            shell.contains("pm list users"),
        )
        assertTrue(
            "appCopyUsers 必须仍然用 userProfiles 补齐（工作资料 / 克隆 profile）",
            shell.contains("userProfiles"),
        )
    }

    @Test
    fun `openApp 必须把接管结果当成功`() {
        assertTrue(
            "openApp 必须把 10~13（已带前台/已挪屏/已另开实例/已接管）视为成功并继续核对，" +
                "否则 AI 会把成功当失败、反复重开（正是几百轮死循环的成因之一）。",
            vs.contains("r !in setOf(0, 10, 11, 12, 13)"),
        )
        listOf("已接管", "另开一个", "已切到前台").forEach {
            assertTrue("openApp 的返回文案缺少「$it」这类说明，AI 无法判断实际发生了什么", vs.contains(it))
        }
        assertFalse(
            "openApp 里不允许再出现「为不打扰已放弃启动」——那就是被投诉的「老是说在主屏」",
            vs.contains("为不打扰已放弃启动"),
        )
    }

    // ─────────── ③ 提示词不得与实现相反 ───────────

    @Test
    fun `提示词必须教会 AI 接管语义`() {
        listOf("多开", "分身", "已接管", "app_users").forEach {
            assertTrue("后台屏提示词缺少「$it」相关说明，AI 遇到多开/主屏场景仍会卡住", best.contains(it))
        }
        assertTrue(
            "提示词必须说明「这四种都算成功」，否则 AI 会为了确认反复 open",
            best.contains("都算成功") || best.contains("全都算成功"),
        )
    }

    @Test
    fun `提示词不得再让 AI 因为主屏就停手`() {
        assertFalse(
            "提示词里还有「返回「用户正在主屏使用该 App」→ 停手问用户」。" +
                "实现已经改成自动接管，这句会让 AI 在成功接管后反而停下来反问用户。",
            best.contains("返回「用户正在主屏使用该 App」→ **停手，问用户**"),
        )
        assertTrue(
            "提示词要指明只有 code=1 才是真的接管不了",
            best.contains("code=1"),
        )
    }

    @Test
    fun `工具描述与提示词保持一致`() {
        assertTrue(
            "bg_screen 工具描述必须写明「全都算成功」，模型主要读工具描述而非系统提示词",
            bgTool.contains("全都算成功"),
        )
        assertTrue("bg_screen 工具描述必须提到多开/分身由 open 自动处理", bgTool.contains("多开"))
    }
}
