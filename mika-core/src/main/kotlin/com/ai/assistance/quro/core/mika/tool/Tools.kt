package com.ai.assistance.quro.core.mika.tool

import com.ai.assistance.quro.core.mika.gate.ConfirmationRequest
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.observation.Place
import com.ai.assistance.quro.core.mika.risk.IrrevAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 终止/中断类工具给 Agent 循环的信号。正常办完不经工具:不带调用的回复就是给用户的回答。 */
sealed interface Outcome {
    data class Failure(val message: String, val subtype: String = "interaction_failed", val recoverable: Boolean = false) : Outcome
    /** 付款时 [item]/[amount]/[address] 在接管卡片上单独列出,卡片就是付款的确认。 */
    data class Takeover(
        val reason: String, val message: String,
        val item: String? = null, val amount: String? = null, val address: String? = null,
    ) : Outcome
    data class Input(val prompt: String, val options: List<String>? = null) : Outcome
    /** 模型申报不可逆操作;[foregroundApp] 是申报时读到的前台包名,读不到为 null。 */
    data class Confirm(val request: ConfirmationRequest, val foregroundApp: String?) : Outcome
}

/**
 * [changed]:端口核实到的动作前后界面是否变化;没核实时为 null。
 * [frame]:只给运行记录的读屏画面(不发给模型);[image] 不为空时就用它。
 * [images]:随结果发给模型的图(回看历史截图时可有多张);[image] 是其中第一张。
 * [record]:只进运行记录、不发给模型的字段(例如稳定编号对应的端口编号)。
 * [screenElements]:写操作结果带的屏幕(只发了差异)此刻的完整元素列表;防卡住守卫按它比较画面。
 * [imageIds]:与 [images] 一一对应的画面编号(界面画面才有;读到的图片文件为 null,不足的按 null)。
 * [observations]:这次调用读到的东西(能力给出,见 [Observation]);分发器记进证据账本后换成账本里那几条(带编号)。
 * [place]:这次调用之后任务在哪(由能力一侧按结果给出);没说为 null(沿用之前的)。
 * [waitMs]:这次调用里能力为了节奏有意停下的时间(网页一页没看够就换页时补的停留);运行记录把它算作等待。
 */
class ToolExecution(
    val content: JsonObject,
    image: ByteArray? = null,
    val changed: Boolean? = null,
    val outcome: Outcome? = null,
    val frame: ByteArray? = null,
    val images: List<ByteArray> = listOfNotNull(image),
    val record: JsonObject = JsonObject(emptyMap()),
    val screenElements: JsonArray? = null,
    val imageIds: List<Int?> = emptyList(),
    val observations: List<Observation> = emptyList(),
    val place: Place? = null,
    val waitMs: Long = 0,
) {
    val image: ByteArray? get() = images.firstOrNull()

    /** 换掉其中几样,别的(含读到的观察)照旧。 */
    fun with(
        content: JsonObject = this.content, changed: Boolean? = this.changed, frame: ByteArray? = this.frame, images: List<ByteArray> = this.images,
        record: JsonObject = this.record, screenElements: JsonArray? = this.screenElements, imageIds: List<Int?> = this.imageIds,
        observations: List<Observation> = this.observations, place: Place? = this.place, waitMs: Long = this.waitMs,
    ) = ToolExecution(content, changed = changed, outcome = outcome, frame = frame, images = images, record = record,
        screenElements = screenElements, imageIds = imageIds, observations = observations, place = place, waitMs = waitMs)

    /** 另读到了 [more]。 */
    fun observing(vararg more: Observation?): ToolExecution = more.filterNotNull().let { if (it.isEmpty()) this else with(observations = observations + it) }
}

/** Android 包名:各段以点分隔,首段以字母开头。launch_app、确认申报、跳 App 链接共用。 */
val PACKAGE_NAME_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/** 回看保留最近几次读屏;一次最多回看几张。 */
internal const val RECALL_KEEP = 20
internal const val RECALL_MAX_IDS = 5

val FAILURE_SUBTYPES = listOf("blocked", "login_incomplete", "captcha_incomplete", "route_failed", "device_unavailable", "interaction_failed")
/** request_confirmation 的 target/content 截短到这么长(模型写的,界面要原样显示)。 */
const val CONFIRM_FIELD_MAX = 200
/** request_confirmation 接受的动作;付款走 hand_to_user。 */
val CONFIRM_ACTIONS = IrrevAction.entries.filter { it != IrrevAction.pay }.map { it.name }
val BARRIER_KINDS = listOf("popup", "login", "captcha", "access_blocked", "payment")
val TAKEOVER_REASONS = listOf("captcha", "login", "access_blocked", "payment", "decision", "other")
/** 技能文档里常见的接管原因说法与改名前的原因,照这里换成上面的原因,导入的文档、存下的技能不用改。 */
val TAKEOVER_REASON_ALIASES = mapOf("password_required" to "login", "2fa" to "login", "manual_review" to "other", "risk_control" to "access_blocked")

/** 接管的结局。aborted 是用户取消了整个任务。 */
enum class HandBackOutcome { complete, skipped, timeout, aborted }

/**
 * 接管的结果,作为 hand_to_user 的工具结果交给模型。
 * [userFinished]:用户说已自己办完;[durationMs] 用户手上的时长;[inputCount] 用户在该 App 里的操作次数;
 * [stoppedAt] 交回时该 App 的页面。不知道的为 null。
 */
data class HandBack(
    val outcome: HandBackOutcome, val userFinished: Boolean = false,
    val durationMs: Long? = null, val inputCount: Int? = null, val stoppedAt: String? = null,
    /** 用户交回时写的备注(用户原话,可作记忆的出处)。 */
    val note: String? = null,
)


