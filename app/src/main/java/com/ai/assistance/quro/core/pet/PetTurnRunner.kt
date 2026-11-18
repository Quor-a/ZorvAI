package com.ai.assistance.quro.core.pet

import android.content.Context
import com.ai.assistance.quro.core.bot.QuroBotPlatform
import com.ai.assistance.quro.core.bot.QuroBotReplyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 桌宠回合的无界面执行器。
 *
 * 此前桌宠的每一个回合都经 `QuroShareBridge` 的 Compose State 交给 `ChatScreen`
 * 消费，只有主界面被组合时才会真正发出请求；为了保证「一定能跑」，服务在发出
 * 回合后必然 `startActivity` 把对话框拉到前台 —— 这就是「对它说 / 看当前屏幕 /
 * 后台虚拟屏任务 / 轻点互动」全都跳对话框的根因。
 *
 * 这里把执行搬到服务侧：复用与 IM 机器人同源的 [QuroBotReplyEngine]
 * （人格 / 灵魂 / 记忆 / 真实工具集，后台任务因此仍能调用设备能力），
 * 结果只经 [QuroPetTurnBus.headlessReply] 回流到悬浮窗气泡：
 *  - 不启动任何 Activity，不抢占前台；
 *  - 不写主聊天记录，也不把「请求失败 / HTTP 429 / 未配置 Key」变成气泡文本；
 *  - 与主对话回合完全隔离：不读写全局 activeTurnId，不会被 ViewModel 收尾串台；
 *  - 忙时与失败冷却由调用方闸门 + [QuroQuietTurn] 共同保护，不排队刷屏。
 *
 * 桌宠因此有一条独立的多轮会话：连续「对它说」能接得上话，但不会污染主对话。
 */
object PetTurnRunner {

    private const val USER_ID = "desktop_pet"
    private const val NORMAL_TIMEOUT_MS = 120_000L
    private const val TASK_TIMEOUT_MS = 240_000L

    @Volatile private var engine: QuroBotReplyEngine? = null

    private fun engine(context: Context): QuroBotReplyEngine =
        engine ?: synchronized(this) {
            engine ?: QuroBotReplyEngine(context.applicationContext).also { engine = it }
        }

    /**
     * 执行一次桌宠回合，返回适合展示在气泡里的文本；失败返回 null（调用方只给轻提示）。
     * 必须在协程里调用，内部自行切到 IO。
     */
    suspend fun run(context: Context, turn: PetTurn): String? = withContext(Dispatchers.IO) {
        if (!QuroQuietTurn.allowAutoTurn()) return@withContext null

        // 可见文本是「用户说了什么」，隐藏上下文是给模型看的执行材料；
        // 明确标注不可复述，避免屏幕 dump / 提示词模板出现在回复里。
        val userText = buildString {
            append(turn.visibleText)
            turn.hiddenContext()?.takeIf { it.isNotBlank() }?.let {
                append("\n\n[以下为内部上下文，仅用于判断，不要原样复述]\n").append(it)
            }
        }

        val timeout = if (turn.completeTask) TASK_TIMEOUT_MS else NORMAL_TIMEOUT_MS
        val raw = runCatching {
            withTimeoutOrNull(timeout) {
                engine(context).reply(QuroBotPlatform.PET, USER_ID, userText, "").text
            }
        }.getOrNull()?.trim().orEmpty()

        val clean = QuroQuietTurn.stripDiagnostics(raw)
        val failed = raw.isBlank() || clean.isBlank() ||
            QuroQuietTurn.isDiagnosticError(raw) ||
            raw.contains("未配置模型 API Key") ||
            raw.contains("机器人无法回复")

        if (failed) {
            QuroQuietTurn.noteFailure()
            // 超时 / 失败时会话里可能只留了一条用户消息，清掉避免下一轮带着半截上下文。
            runCatching { engine(context).reset(QuroBotPlatform.PET, USER_ID) }
            return@withContext null
        }

        QuroQuietTurn.noteSuccess()
        QuroPetTurnBus.emitHeadlessReply(clean, turn.source)
        clean
    }
}
