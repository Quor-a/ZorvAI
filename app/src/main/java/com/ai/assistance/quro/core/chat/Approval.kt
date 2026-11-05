package com.ai.assistance.quro.core.chat

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 工具审批：危险/有副作用的工具在执行前，先挂起等用户在界面上拍板。
 *
 * 此前危险工具（执行 shell、写文件、控制手机）是**直接跑**的，模型一旦给错参数就真的执行了。
 * 并给三档允许粒度（仅这一次 / 本会话 / 总是），用户拍板后才真正执行。
 *
 * ## 关键设计：防注入
 * 审批卡的「摘要 / 理由」是**模型自述**，可以撒谎（说"我只是读文件"其实是删库）。
 * 因此卡片上必须有一行显眼的提示：以上是 AI 的自述，请以下方的实际调用为准。
 * 真正可核对的是下方的「实际调用」——工具名、参数、目标路径，这些是引擎要执行的东西。
 */

/** 审批决策：用户对某一次审批请求的最终答复。 */
sealed interface ApprovalDecision {
    /** 拒绝（可带审查说明）。 */
    data class Deny(val reason: String? = null) : ApprovalDecision

    /** 允许，粒度为「仅这一次 / 本会话 / 总是」。 */
    data class Allow(val scope: AllowScope) : ApprovalDecision
}

/** 允许的持久粒度。 */
enum class AllowScope {
    /** 仅允许这一次调用。 */
    ONCE,

    /** 本会话内，同类工具（同名）不再询问。 */
    SESSION,

    /** 永久允许（写入偏好）。 */
    ALWAYS,
}

/** 审批请求的当前状态（驱动卡片四态标题）。 */
enum class ApprovalState {
    /** 等待用户决定。 */
    PENDING,

    /** 已允许。 */
    ALLOWED,

    /** 已拒绝。 */
    DENIED,

    /** 已失效（例如超时、或会话被切换导致无人来答）。 */
    EXPIRED,
}

/**
 * 一条可核对的事实项：标签 + 值 + 是否强调。
 * 强调项（emphasis=true）是「风险点」，渲染成醒目的警示色，让用户一眼看到要害。
 */
data class ApprovalFact(
    val label: String,
    val value: String,
    val emphasis: Boolean = false,
)

/**
 * 一次审批请求：工具要执行什么、为什么、风险在哪。
 *
 * @param toolName  实际要执行的原生工具名（如 termux_exec、file_write）。
 * @param toolLabel 人类可读的工具名（如「执行终端命令」「写入文件」）。
 * @param summary   模型自述的摘要——**不可信**，UI 必须标注。
 * @param reason    模型自述的理由——**不可信**。
 * @param facts     可核对的事实项（实际参数拆解）。
 * @param risk      风险等级提示（高/中/低），可选。
 */
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    val toolLabel: String,
    val summary: String,
    val reason: String? = null,
    val facts: List<ApprovalFact> = emptyList(),
    val risk: String? = null,
    /** 请求创建时间（用于「已失效」判定）。 */
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** 已省略未展示的参数数量（参数太多时只展示关键的几个）。 */
    var omittedArguments: Int = 0
        internal set
}

/**
 * 审批网关：工具执行与 UI 决策之间的挂起桥。
 *
 * 工具协程调 [awaitDecision]，用 [CompletableDeferred] 挂起；UI 收到请求后渲染审批卡，
 * 用户拍板时调 [resolve]，工具协程被唤醒继续执行或被拒绝短路。
 *
 * 单例、进程级：工具执行发生在 [QuroAssistant] 的协程里，UI 决策发生在 Compose 侧，
 * 两者通过本对象以 requestId 为纽带串起来。
 */
object ApprovalGate {
    /** 等待中的请求：requestId -> 决策信号。 */
    private val pending = mutableMapOf<String, CompletableDeferred<ApprovalDecision>>()

    /** 最新一条待处理的请求（UI 只显示最新的，旧的自动作废）。 */
    @Volatile
    private var latestId: String? = null

    /** UI 订阅：当有新的待审批请求时回调（返回请求 + 取消回调）。 */
    @Volatile
    var listener: ((ApprovalRequest) -> Unit)? = null

    /** 审批默认超时：超过则按「已失效」处理，避免工具协程永久挂起。 */
    const val TIMEOUT_MS = 120_000L

    /**
     * 工具侧：发起一次审批并挂起等待决策。
     *
     * @return 允许则返回 null（继续执行）；拒绝则返回审查说明（可空，作为错误回喂模型）。
     *         超时/失效返回非空串表示「未获批准」。
     */
    suspend fun awaitDecision(request: ApprovalRequest): String? {
        val deferred = CompletableDeferred<ApprovalDecision>()
        synchronized(pending) {
            // 新请求到达：作废旧的（resolve 成 expired），只保留最新。
            pending.values.forEach { it.complete(ApprovalDecision.Deny(EXPIRED_MARK)) }
            pending.clear()
            pending[request.id] = deferred
            latestId = request.id
        }
        listener?.invoke(request)

        val decision = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() }
        synchronized(pending) {
            pending.remove(request.id)
            if (latestId == request.id) latestId = null
        }
        return when (decision) {
            is ApprovalDecision.Allow -> null
            is ApprovalDecision.Deny -> decision.reason?.takeIf { it.isNotBlank() }
                ?.let { "用户拒绝了此次操作：$it" }
                ?: "用户拒绝了此次操作"
            null -> "此次操作等待批准超时，未执行"
        }
    }

    /**
     * UI 侧：用户对当前请求拍板。
     *
     * 允许时按用户选的粒度把「工具名」记进 [ApprovalMemory]（本会话 / 总是），
     * 后续同类调用免问；拒绝则不记忆。
     */
    fun resolve(decision: ApprovalDecision, toolName: String? = null) {
        if (decision is ApprovalDecision.Allow && toolName != null) {
            ApprovalMemory.remember(toolName, decision.scope)
        }
        val id = latestId ?: return
        val d = synchronized(pending) { pending[id] }
        d?.complete(decision)
    }

    /** UI 侧：取当前待处理的请求 id。 */
    fun currentId(): String? = latestId

    /** 标记：已被拒绝，用于内部过期。 */
    private const val EXPIRED_MARK = "__expired__"
}

/**
 * 允许记忆：把「本会话 / 总是允许」的粒度落到一个可查询的集合。
 *
 * - SESSION 粒度存在内存（进程内），进程结束即失效。
 * - ALWAYS 粒度交给外部持久化（由调用方在 resolve 后写入偏好）。
 */
object ApprovalMemory {
    /** 本会话已允许的工具名集合。 */
    private val sessionAllowed = mutableSetOf<String>()

    /** 永久允许的工具名集合（由外部在启动时从偏好回填）。 */
    private val alwaysAllowed = mutableSetOf<String>()

    fun isAllowed(toolName: String): Boolean =
        toolName in sessionAllowed || toolName in alwaysAllowed

    fun remember(toolName: String, scope: AllowScope) {
        when (scope) {
            AllowScope.ONCE -> Unit
            AllowScope.SESSION -> sessionAllowed += toolName
            AllowScope.ALWAYS -> alwaysAllowed += toolName
        }
    }

    /** 外部回填永久允许集（偏好加载）。 */
    fun seedAlways(allowed: Collection<String>) {
        alwaysAllowed += allowed
    }

    /** 供外部读取「总是允许」集合并持久化。 */
    fun alwaysAllowedSnapshot(): Set<String> = alwaysAllowed.toSet()

    fun clearSession() { sessionAllowed.clear() }
}

/**
 * 「总是允许」的**持久化**落点。
 *
 * 🔴 对接缺口修复（2026-11-04）：本文件原注释写「ALWAYS 粒度交给外部持久化（由调用方
 * 在 resolve 后写入偏好）」，但全仓**没有任何调用方**真的存取它 —— 用户在审批卡点了
 * 「总是允许」，重启 App 后全部作废，同一个工具问一遍又一遍。现在：
 *   · [load] App 启动时把持久化集合回填 [ApprovalMemory]（QuroApplication.onCreate）；
 *   · [save] 每次拍板「总是允许」后全量落盘（QuroChatViewModel.resolveApproval）。
 * 同时「总是允许」会联动权限面板的 CMS / 特权通道开关（见 [ApprovalPolicy.channelFor]），
 * 把「审批卡 + 通道策略」两道关卡并成一道，不再「刚点了允许又被另一个确认框拦住」。
 */
object ApprovalPersist {
    private const val PREFS = "quro_approval"
    private const val KEY_ALWAYS = "always_tools"

    @Volatile private var seeded = false

    /** 启动时回填（幂等；重复调用只在首次生效）。 */
    @Synchronized
    fun load(ctx: Context) {
        if (seeded) return
        seeded = true
        runCatching {
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(KEY_ALWAYS, emptySet()) ?: emptySet()
            ApprovalMemory.seedAlways(saved)
        }
    }

    /** 「总是允许」集合变化后全量落盘。 */
    fun save(ctx: Context) {
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_ALWAYS, ApprovalMemory.alwaysAllowedSnapshot()).apply()
        }
    }
}
