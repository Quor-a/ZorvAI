package com.ai.assistance.quro.core.mika.uistate

import com.ai.assistance.quro.core.mika.tool.ToolDispatcher
import com.ai.assistance.quro.core.mika.visual.FrameDiff
import com.ai.assistance.quro.core.mika.visual.FrameGrid
import com.ai.assistance.quro.core.mika.visual.Recall
import com.ai.assistance.quro.core.mika.visual.Shots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import com.ai.assistance.quro.core.mika.vision.LocateRegistry
import com.ai.assistance.quro.core.mika.vision.Locator
import com.ai.assistance.quro.core.mika.vision.VisionService
import com.ai.assistance.quro.core.mika.observation.InputHints
import com.ai.assistance.quro.core.mika.observation.Look
import com.ai.assistance.quro.core.mika.observation.ObsKind
import com.ai.assistance.quro.core.mika.observation.Observation
import com.ai.assistance.quro.core.mika.observation.Place
import com.ai.assistance.quro.core.mika.observation.Trust
import com.ai.assistance.quro.core.mika.prompt.Feedback
import com.ai.assistance.quro.core.mika.tool.HINT_PROTECTED
import com.ai.assistance.quro.core.mika.tool.MAX_READ_CHARS
import com.ai.assistance.quro.core.mika.tool.NOTE_CHANGED_DURING_READ
import com.ai.assistance.quro.core.mika.tool.ToolExecution
import com.ai.assistance.quro.core.mika.tool.Verb
import com.ai.assistance.quro.core.mika.tool.okObj
import com.ai.assistance.quro.core.mika.net.UrlHost

internal const val MAX_ELEMENTS = 200
/** 一次全量屏幕之后最多接几次差异。 */
private const val MAX_CHAIN_DIFFS = 5
/** 写操作后读屏前等界面稳定的上限。 */
private const val INLINE_SETTLE_MS = 1500L
/** OCR 是读屏补充，不能无限期挡住下一次模型调用。 */
private const val OCR_WAIT_MS = 2000L
/** 树不够用时屏上文字就是主要材料:多等一会儿(端口要先等画面停稳再认)。 */
private const val OCR_WAIT_POOR_MS = 5000L
/** 不可逆判定要按 ref 所属的那次读屏找元素,留最近几次。 */
private const val RECENT_SNAPSHOTS = 8
/** 记住多少个发出过的 snapshotId。 */
private const val ISSUED_MEMORY = 256
/** 结果里「到上限了」那句话最多留这么长。 */
private const val LIMIT_TEXT_MAX = 120

/** find_visually 找到的一个点:找的是什么、给的原文、视觉模型看到的字;不可逆判定都要看。[unverified]:只靠看图、没有佐证。 */
internal class Located(val target: String, val text: String?, val seen: String?, val unverified: Boolean = false)

/**
 * 一块屏的读屏状态:稳定编号、模型最近拿到的屏幕与差异链、最近几次读屏、看图找到的点。每块屏一份,
 * 那一套屏幕工具只用自己的这份;画面编号与回看几块屏共用([shots])。
 */
internal class ScreenSession(
    val surface: Surface,
    stableRefs: Boolean,
    private val visionEnabled: Boolean,
    private val recordFrames: Boolean,
    /** 最近一次读到的界面文字(几块屏共用);动作之后读到的和它一样就不再记一遍。 */
    private val lastRead: () -> String?,
    val shots: Shots,
    vision: VisionService? = null,
    private val adaptiveVision: Boolean = false,
) {
    /** 手机模型结果不按元素数量截断;网页仍沿用自己的输出预算。 */
    private val modelElementsLimit get() = if (surface.id == "phone") Int.MAX_VALUE else MAX_ELEMENTS
    val screen: ScreenPort get() = surface.screen
    private var textOnlyDepth = 0

    /** 复合手机命令内部只留文字与状态,图片需要时再由模型请求。 */
    suspend fun <T> textOnly(block: suspend () -> T): T {
        textOnlyDepth++
        try { return block() } finally { textOnlyDepth-- }
    }

    val stabilizer = if (stableRefs) RefStabilizer(surface.family.snapshotPrefix) else null
    /** 模型最近一次拿到的屏幕(observe 或写操作结果里的 screen),差异的 base。 */
    private var shown: ModelSnapshot? = null
    /** 最近一次全量屏幕及其后各次差异的 snapshotId:差异要靠这整条链才看得懂,缺一条就发全量。 */
    private val chain = mutableListOf<String>()
    /** 模型此刻还看得到的读屏 snapshotId;null 表示循环还没告诉过(都看得到)。 */
    var visible: Set<String>? = null
    var lastSnapshot: ScreenSnapshot? = null
    val recentSnapshots = ArrayDeque<ScreenSnapshot>()
    val readAt = mutableMapOf<String, Long>()
    /** 这块屏发出过的 snapshotId(比 [RECENT_SNAPSHOTS] 长:模型可能拿很早的编号来用)。 */
    private val issued = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > ISSUED_MEMORY
    }

    /** find_visually 在这块屏上找到的点(@vN);这块屏上任何写操作后清空。 */
    val located = LocateRegistry()
    val locatedInfo = mutableMapOf<String, Located>()
    /** 上次写操作之后看过 describe:这期间的坐标点击按看图得来的点对待。 */
    var described = false
    val locator = vision?.let { Locator(screen, it, located, surface.family) }

    fun clearLocated() { located.clear(); locatedInfo.clear(); described = false }

    /** 这个 snapshotId(读屏的,或看图找到的点的)是这块屏发出的。 */
    fun owns(snapshotId: String): Boolean = snapshotId in issued || located.has(snapshotId)

    /** 这块屏读过几次屏(登记编号的读);判效果时据此知道动作之后有没有读过。 */
    var reads = 0
        private set

    /**
     * 模型手里的最近一屏已经过时:动作之后界面变了,而结果里没有带回动作之后的屏幕。下次读屏前一直为 true。
     */
    var stale = false
        private set

    /** ZorvAI 自己动过这块屏(停留里轻轻滚了几下):模型手里那一屏的位置已不准,标成过时。 */
    fun movedByZorvAI() { stale = true }

    /**
     * 读一屏并登记编号。[internal]:不是给模型看的(按描述找目标时),模型手里那屏过没过时照旧;
     * 端口原样的这一屏留作接着那个动作的「动作前」([probe] 用它,不再读一次)。
     */
    suspend fun snapshot(internal: Boolean = false): ScreenSnapshot = screen.snapshot().let { p ->
        (stabilizer?.next(p)?.screen ?: p).also { primed = if (internal) it.snapshotId to p else null }
    }.also {
        reads++
        if (!internal) stale = false
        lastSnapshot = it
        recentSnapshots.addLast(it)
        readAt[it.snapshotId] = System.currentTimeMillis()
        issued[it.snapshotId] = Unit
        while (recentSnapshots.size > RECENT_SNAPSHOTS) recentSnapshots.removeFirst().snapshotId.let { id -> readAt.remove(id) }
    }

    /** 此刻重新读一屏,不登记编号、不改模型手里的读屏;读不到为 null。 */
    suspend fun peek(): ScreenSnapshot? = try { screen.peek() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    suspend fun observe(withImage: Boolean, adaptiveImage: Boolean = false, recordFrame: Boolean = true): ToolExecution {
        val s = snapshot()
        val (tree, ocr, ocrStatus) = readTree(s)
        val imageReason = if (adaptiveImage && tree.poor) tree.reason else null
        val sendImage = withImage || imageReason != null
        stabilizer?.latest?.let { shown = it; chain.clear(); chain += it.snapshotId }
        val seen = interfaceRead(s, ocr.orEmpty())
        val (elements, hidden) = ScreenJson.elements(s, modelElementsLimit)
        val shotId = shots.next()
        val content = okObj {
            val w = s.web
            if (w == null) put("currentApp", s.packageName ?: s.unreadableApp)
            else { put("currentSite", UrlHost.of(w.url)); put("url", w.url); put("title", w.title); put("tabId", w.tabId) }
            w?.dialog?.let { put("dialog", dialogJson(it)) }
            put("snapshotId", s.snapshotId)
            put("screenshotId", shotId)
            imageReason?.let { put("imageReason", it) }
            quality(tree)?.let { put("treeQuality", it) }
            ocrStatus?.let { put("ocr", it) }
            put("screenChangedDuringRead", s.changedDuringRead)
            if (s.changedDuringRead) put("note", NOTE_CHANGED_DURING_READ)
            s.opened?.let { put("opened", openedJson(it)) }
            if (s.protected) { put("protected", true); put("hint", HINT_PROTECTED) }
            else if (unreadable(s) != null && ocr.isNullOrEmpty()) { put("readable", false); put("hint", unreadable(s)) }
            else if (s.packageName == null && s.elements.isEmpty() && !s.hasWindow) put("hint", Feedback[surface.family.emptyHint])
            else treeHint(tree, ocrStatus)?.let { put("hint", it) }
            put("viewport", buildJsonObject { put("width", s.width); put("height", s.height) })
            put("elements", elements)
            if (surface.id == "phone" && s.windows.isNotEmpty()) put("tree", UiTreeJson.windows(s))
            ocr?.let { put("visibleText", VisibleText.json(it)) }
            if (surface.id == "phone") ScreenMap.json(s, ocr.orEmpty())?.let { put("screenMap", it) }
            val omitted = s.web?.omitted ?: 0
            put("truncated", hidden > 0 || omitted > 0)
            if (omitted > 0) put("omitted", hidden + omitted)
        }
        // 防截屏页面截到的是黑图,给模型只会误导
        val png = if ((sendImage && textOnlyDepth == 0 || (recordFrames && recordFrame && textOnlyDepth == 0)) && !s.protected) screen.screenshotPng() else null
        // 只有主模型能看图才留图；付款页和普通页面按同一规则处理
        val kept = png?.takeIf { visionEnabled }
        shots.remember(Recall(shotId, shots.step, s.packageName, kept, observationText(s, ocr.orEmpty())))
        val image = png.takeIf { sendImage }
        return ToolExecution(content, image = image, frame = png, imageIds = listOfNotNull(image?.let { shotId }), observations = listOf(seen))
    }

    /**
     * 主模型能看图时此刻屏幕的一帧(原图)和它的 screenshotId,登记进回看。和读屏同样的规则:
     * 受保护页面不截；屏上还没有 App、截不到时为 null。
     */
    suspend fun currentFrame(): Pair<ByteArray, Int>? {
        if (!visionEnabled) return null
        val s = try { screen.peek() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        if (s.protected) return null
        if (s.packageName == null && s.elements.isEmpty() && !s.hasWindow) return null
        val png = try {
            if (screen.isProtected()) null else screen.screenshotPng()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null } ?: return null
        val shotId = shots.next()
        shots.remember(Recall(shotId, shots.step, s.packageName, png, screenText(s)))
        return png to shotId
    }

    /**
     * 主模型能看图时动作之后发的一屏:画面和元素表必须是同一时刻的,模型按元素表里的 ref/snapshotId 操作,画面只用来看懂。
     * 和 observe 同一套：受保护页面不发画面；屏上还没有 App 时为 null。
     */
    suspend fun currentView(): ToolDispatcher.Glance? {
        if (!visionEnabled && !adaptiveVision) return null
        val peeked = try { screen.peek() } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        if (peeked.packageName == null && peeked.elements.isEmpty() && !peeked.hasWindow) return null
        if (textOnlyDepth > 0) {
            val obs = try { observe(withImage = false, recordFrame = false) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
            val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), null, shotId, obs.observations)
        }
        if (adaptiveVision) {
            val obs = try { observe(withImage = false, adaptiveImage = visionEnabled) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
            val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
            return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), obs.image, shotId, obs.observations)
        }
        // 先读元素表(登记 snapshotId),再按这一屏判能不能截:受保护页面不截
        val obs = try { observe(withImage = false) } catch (e: CancellationException) { throw e } catch (e: Exception) { return null }
        val s = lastSnapshot ?: return null
        val shotId = (obs.content["screenshotId"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return null
        val png = if (s.protected) null else try {
            obs.frame ?: if (screen.isProtected()) null else screen.screenshotPng()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        // 回看里这一张补上画面
        val recalls = shots.recalls
        if (png != null) recalls.indexOfLast { it.id == shotId }.takeIf { it >= 0 }?.let { i ->
            val r = recalls[i]; recalls[i] = Recall(r.id, r.step, r.app, png, r.elementsText)
        }
        return ToolDispatcher.Glance(JsonObject(obs.content - "ok"), png, shotId, obs.observations)
    }

    /** 动作成功后附上此刻的一屏(端口在动作里已等过界面变化):元素表放进 screen 字段,画面另附,结果里写上它的 screenshotId。 */
    suspend fun withFrame(r: ToolExecution): ToolExecution {
        val v = currentView() ?: return r
        return r.with(content = JsonObject(r.content + ("screen" to v.content) + ("screenshotId" to JsonPrimitive(v.imageId))), images = listOfNotNull(v.image),
            screenElements = v.content["elements"] as? JsonArray ?: r.screenElements, imageIds = if (v.image != null) listOf(v.imageId) else emptyList(),
            observations = r.observations + v.observations)
    }

    /**
     * 写操作成功后:等界面稳定(上限 [INLINE_SETTLE_MS])再读一屏,和模型上次拿到的屏幕比,差异放进 screen 字段。
     * base 或最近一次全量屏幕模型已看不到时发全量。读不到屏幕就照原样返回动作结果。
     */
    suspend fun withScreen(r: ToolExecution): ToolExecution {
        val cur = try {
            screen.waitStable(INLINE_SETTLE_MS)
            snapshot()
            stabilizer!!.latest!!
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return r
        }
        val v = visible
        // 连续差异最多 MAX_CHAIN_DIFFS 次就发一次全量:链越长越难读,压缩时要保留的往返也越多
        val seen = chain.isNotEmpty() && chain.size - 1 < MAX_CHAIN_DIFFS && (v == null || chain.all { it in v })
        val s = cur.screen
        val (tree, ocr, ocrStatus) = readTree(s)
        val shotId = shots.next()
        val diff = if (s.protected) {
            // 受保护页面看不到画面和元素；下一次从全量重新开始
            shown = null; chain.clear()
            buildJsonObject {
                put("snapshotId", cur.snapshotId); put("currentApp", s.packageName); put("full", false)
                put("protected", true); put("hint", HINT_PROTECTED)
                put("screenshotId", shotId)
            }
        } else {
            val d = InterfaceUpdate.between(shown, cur, seen, modelElementsLimit)
            shown = cur
            if (d["full"] == JsonPrimitive(true)) chain.clear()
            chain += cur.snapshotId
            JsonObject(d + ("screenshotId" to JsonPrimitive(shotId)) +
                (ocr?.let { mapOf("visibleText" to VisibleText.json(it)) } ?: emptyMap()) +
                (if (surface.id == "phone") ScreenMap.json(s, ocr.orEmpty())?.let { mapOf("screenMap" to it) } ?: emptyMap() else emptyMap()) +
                (if (s.changedDuringRead) mapOf("note" to JsonPrimitive(NOTE_CHANGED_DURING_READ)) else emptyMap()) +
                (s.opened?.let { mapOf("opened" to openedJson(it)) } ?: emptyMap()) +
                (s.web?.dialog?.let { mapOf("dialog" to dialogJson(it)) } ?: emptyMap()) +
                (quality(tree)?.let { mapOf("treeQuality" to JsonPrimitive(it)) } ?: emptyMap()) +
                (ocrStatus?.let { mapOf("ocr" to JsonPrimitive(it)) } ?: emptyMap()) +
                ((unreadable(s)?.takeIf { ocr.isNullOrEmpty() }?.let { mapOf("readable" to JsonPrimitive(false), "hint" to JsonPrimitive(it)) })
                    ?: treeHint(tree, ocrStatus)?.let { mapOf("hint" to JsonPrimitive(it)) } ?: emptyMap()))
        }
        val text = observationText(s, ocr.orEmpty())
        // 和上一次读到的一样就不再记一遍,免得把较早的读屏挤出去
        val read = if (lastRead() != text) interfaceRead(s, ocr.orEmpty()) else null
        // 和 observe 同样的规则:防截屏页面不截;只有主模型能看图才留图回看
        val imageReason = if (adaptiveVision && visionEnabled && tree.poor) tree.reason else null
        val sendImage = visionEnabled && (!adaptiveVision || imageReason != null)
        val png = if ((recordFrames || sendImage) && textOnlyDepth == 0 && !s.protected) {
            try { screen.screenshotPng() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        } else null
        val kept = png?.takeIf { visionEnabled }
        shots.remember(Recall(shotId, shots.step, s.packageName, kept, text))
        // 旧模式总附画面；分级模式只在元素明显不足时附画面。
        val attach = kept?.takeIf { sendImage && r.images.isEmpty() }
        val shownDiff = if (imageReason == null) diff else JsonObject(diff + ("imageReason" to JsonPrimitive(imageReason)))
        return r.with(content = JsonObject(r.content + ("screen" to onSurface(shownDiff))),
            frame = r.frame ?: png?.takeIf { recordFrames }, images = attach?.let { listOf(it) } ?: r.images,
            screenElements = ScreenJson.elements(s, modelElementsLimit).first, imageIds = if (attach != null) listOf(shotId) else r.imageIds).observing(read)
    }

    /** 动作之前的界面状态(不登记编号)和一帧画面,判效果用;读不到的为 null。 */
    class EffectProbe internal constructor(before: ScreenSnapshot?, frame: FrameGrid?) {
        var before: ScreenSnapshot? = before
            internal set
        var frame: FrameGrid? = frame
            internal set
    }

    /** 这次动作前看的那一眼,动作判完效果前留着:动作开始之前 ZorvAI 自己又动过界面时由 [reprobe] 重看。 */
    private var pendingProbe: EffectProbe? = null

    /** 这次动作的效果判完了(或不判了)。 */
    fun probeDone() { pendingProbe = null }

    /**
     * 动作开始之前 ZorvAI 自己动过界面(网页停留里轻轻滚了页面):重看一眼当作动作前的样子,免得把这点滚动算成动作的效果;
     * 模型手里那一屏的位置也已不准,标成过时。没有在等判效果的动作时什么也不做。
     */
    suspend fun reprobe() {
        val p = pendingProbe ?: return
        stale = true
        withTimeoutOrNull(EffectTuning.PROBE_MS) { p.before = peek(); p.frame = grid() }
    }

    /** 刚为按描述找目标读的一屏:给模型的 snapshotId 和端口原样的一屏;被 [probe] 取走或作废前有效。 */
    private var primed: Pair<String, ScreenSnapshot>? = null

    fun clearPrimed() { primed = null }

    /**
     * 动作前看一眼,最多花 [EffectTuning.PROBE_MS];来不及的部分为 null(之后只按端口的事实判)。
     * 动作用的正是刚按描述读的那一屏([snapshotId] 对得上)就用它,不再读一次。
     */
    suspend fun probe(snapshotId: String? = null): EffectProbe {
        val ready = primed?.takeIf { it.first == snapshotId }?.second
        primed = null
        if (ready != null) {
            var frame: FrameGrid? = null
            withTimeoutOrNull(EffectTuning.PROBE_MS) { frame = grid() }
            return EffectProbe(ready, frame).also { pendingProbe = it }
        }
        var before: ScreenSnapshot? = null
        var frame: FrameGrid? = null
        withTimeoutOrNull(EffectTuning.PROBE_MS) { before = peek(); frame = grid() }
        return EffectProbe(before, frame).also { pendingProbe = it }
    }

    private suspend fun grid(): FrameGrid? = try { screen.frameGrid() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }

    /**
     * 动作成功之后判它对界面状态的实际效果(见 [ActionEffect]),写进结果的 effect 字段,并按效果给提示;结果的 changed 跟着效果走。
     * 动作之后已经读过([readsAtAction] 之后又读过,即结果里带了界面)就用那一份。否则:端口在动作里已等过界面(报了变没变或页面静没静)
     * 就直接看一眼,没等过才等界面稳定(上限 [EffectTuning.SETTLE_MS])。看起来没变时再等 [EffectTuning.RECHECK_MS] 重看一次(异步更新)。
     * 整个判断最多多花 [EffectTuning.BUDGET_MS],到了就按端口的事实下结论。只读界面、取画面,不做任何动作。
     * 界面变了而结果里没有新的界面时,标记模型手里的那份已过时([stale])。
     */
    suspend fun withEffect(
        r: ToolExecution, probe: EffectProbe, verb: Verb, readsAtAction: Int, target: UiElement?, region: List<Int>?, typed: String?,
    ): ToolExecution {
        val c = r.content
        val opened = c["opened"] as? JsonObject
        val port = PortFacts(
            changed = r.changed,
            switchedTo = (c["app_switched"] as? JsonPrimitive)?.content,
            verified = c["verification"] == JsonPrimitive("match"),
            loading = c["settled"] == JsonPrimitive(false),
            onUserScreen = opened?.takeIf { it["onUserScreen"] == JsonPrimitive(true) }?.let { (it["package"] as? JsonPrimitive)?.content },
        )
        val portWaited = r.changed != null || "settled" in c
        val fresh = reads > readsAtAction
        fun judge(after: ScreenSnapshot?, frame: FrameGrid?) = ActionEffect.classify(
            verb, probe.before, after, port, FrameDiff.compare(probe.frame, frame, region), target, typed,
            pressedEnter = c["pressedEnter"] == JsonPrimitive(true), secret = c["secret"] == JsonPrimitive(true),
        )
        var effect: ActionEffect? = judge(null, null)
        // 动作之后看到的界面里新出现的「到上限了」的说法(看过两次时任一次有就算:提示条一会儿就消失)
        var limit: String? = null
        withTimeoutOrNull(EffectTuning.BUDGET_MS) {
            if (!fresh && !portWaited) settle(EffectTuning.SETTLE_MS)
            val after = if (fresh) lastSnapshot else peek()
            limit = LimitNotice.find(probe.before, after)
            effect = judge(after, grid())
            if (effect?.kind == EffectKind.NoEffect) {
                // 界面可能是异步更新的:再等一会儿重看一次,仍没变才算没效果
                delay(EffectTuning.RECHECK_MS)
                val again = peek()
                limit = limit ?: LimitNotice.find(probe.before, again)
                effect = judge(again, grid())
            }
        }
        // 树读不出点的那一处(只在手机上判:网页没有树够不够用这回事):看不出变化不等于没点上
        val e = effect?.let { judged ->
            if (surface.id != "phone") return@let judged
            val quality = lastTree?.quality ?: probe.before?.let { TreeAdequacy.ofTree(it).quality }
            if (judged.kind == EffectKind.NoEffect && ActionEffect.treeBlind(verb, quality, probe.before, target, region)) judged.copy(treeBlind = true) else judged
        } ?: return r
        if ("screen" !in c && e.kind != EffectKind.NoEffect) stale = true
        val changed = e.kind != EffectKind.NoEffect
        val hint = listOfNotNull(limit?.let { Feedback.format("effect.limit_reached", "text" to it.take(LIMIT_TEXT_MAX)) },
            EffectFeedback.hint(e, verb, portSwitched = "app_switched" in c || opened != null),
            Feedback["effect.stale"].takeIf { stale && e.kind != EffectKind.SmallChange }, (c["hint"] as? JsonPrimitive)?.content)
        val content = JsonObject(c - "hint" + ("effect" to e.toJson()) +
            (if ("changed" in c) mapOf("changed" to JsonPrimitive(changed)) else emptyMap()) +
            (if (verb == Verb.Scroll && e.kind == EffectKind.NoEffect) mapOf("atEnd" to JsonPrimitive(true)) else emptyMap()) +
            (if (stale) mapOf("interfaceStale" to JsonPrimitive(true)) else emptyMap()) +
            (limit?.let { mapOf("limitReached" to JsonPrimitive(it.take(LIMIT_TEXT_MAX))) } ?: emptyMap()) +
            (if (hint.isNotEmpty()) mapOf("hint" to JsonPrimitive(hint.joinToString(" "))) else emptyMap()))
        return r.with(content = content, changed = changed)
    }

    /** 最近一次读屏判的树够不够用;判效果时据此说「看不出变化」还是「没点上」。 */
    private var lastTree: TreeCheck? = null

    /**
     * 这一屏的树缺了一块或不能用(只在手机上判,和读屏结果里的 treeQuality 同一口径):光看树判不准时按最近一次读屏(读过屏上文字)的判断。
     * 按描述找输入框在树里找不到时,据此改看图找。
     */
    fun treeBlind(s: ScreenSnapshot): Boolean = surface.id == "phone" &&
        (TreeAdequacy.ofTree(s).quality ?: lastTree?.quality).let { it == TreeQuality.Partial || it == TreeQuality.Unusable }

    private suspend fun settle(ms: Long) {
        try { screen.waitStable(ms) } catch (e: CancellationException) { throw e } catch (e: Exception) { }
    }

    /** 读屏前刚从用户主屏挪回来的 App:和动作结果里的 opened 同样的字段,另带那句说明。 */
    private fun openedJson(o: OpenedElsewhere): JsonObject = JsonObject(o.toJson() + ("note" to JsonPrimitive(openedHint(o))))

    /**
     * 前台 App 打开着、窗口却读不到时给模型的说明(不是「没打开」,不要再启动);读得到或不知道前台是谁时为 null。
     */
    private fun unreadable(s: ScreenSnapshot): String? {
        val app = s.unreadableApp ?: return null
        if (s.elements.any { it.meaningful }) return null
        return Feedback.format("unreadable_window", "app" to app)
    }

    /** 这一次读到的界面状态:可读元素的文字、在哪、网页标题、输入框里的字。 */
    private fun interfaceRead(s: ScreenSnapshot, ocr: List<VisibleTextLine> = emptyList()): Observation {
        val f = inputFields(s)
        val place = s.web?.url?.let(Place::page) ?: s.packageName?.let(Place::app)
        return Observation(ObsKind.Read, observationText(s, ocr), source = surface.id, place = place, title = s.web?.title,
            trust = if (s.web != null) Trust.Untrusted else Trust.Trusted, inputs = InputHints(f.names, f.focused))
    }

    /**
     * 这一屏的树够不够用([TreeAdequacy]),和要补上的屏上文字(OCR,只留树里没有的、去掉认错的行)。
     * 树明显够用、受保护时不读字;判不准时读字再定;树不够用时一定读字:看不到图的主模型靠它,
     * 能看图的主模型也拿它的位置去点,证据账本(回答来源、核查)和 phone_collect 也只能从这里拿到屏上写着什么。
     */
    private suspend fun readTree(s: ScreenSnapshot): TreeRead {
        val tree = TreeAdequacy.ofTree(s)
        if (tree.quality == TreeQuality.Adequate || tree.quality == TreeQuality.Protected) return TreeRead(tree, null, null).also { lastTree = it.tree }
        val (ocr, status) = visibleText(s, if (tree.poor) OCR_WAIT_POOR_MS else OCR_WAIT_MS)
        val checked = TreeAdequacy.withText(tree, ocr)
        lastTree = checked
        // 树不够用、屏上文字也一行没有:照实说为什么,模型才知道该看图找,而不是再读一次
        return TreeRead(checked, ocr, status.takeIf { quality(checked) != null && ocr.isNullOrEmpty() })
    }

    /**
     * 屏上树里没有的文字,和一行也没读到时的原因:empty(画面上没认出字)、illegible(认出的都是认错的)、
     * timeout(时限内没认完)、unavailable(端口给不出:没有画面、画面一直在大变)、failed(识别出错)。
     * 不读(网页、受保护、没窗口)时都为 null。树在读的过程中变了也照样读:字的位置来自画面本身,
     * 端口自己会等画面停稳,认的过程中整页变了会重认。
     */
    private suspend fun visibleText(s: ScreenSnapshot, waitMs: Long): Pair<List<VisibleTextLine>?, String?> {
        if (surface.id != "phone" || s.protected || !s.hasWindow) return null to null
        var timedOut = true
        val raw = try {
            withTimeoutOrNull(waitMs) { screen.readVisibleText().also { timedOut = false } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return null to "failed" }
        if (raw == null) return null to if (timedOut) "timeout" else "unavailable"
        if (raw.isEmpty()) return raw to "empty"
        val legible = VisibleText.legible(raw)
        if (legible.isEmpty()) return legible to "illegible"
        return VisibleText.missing(s, legible) to null
    }

    /** 写进读屏结果的 treeQuality:只在手机上、树不够用时写(网页没有屏上文字可补)。 */
    private fun quality(tree: TreeCheck): String? = tree.quality?.takeIf { surface.id == "phone" && tree.poor }?.wire

    /** 树不够用时给模型的说明:屏上文字从哪来、怎么点;配了看图模型时再说没有字的图标怎么找;连字也读不到时说为什么、该怎么找。 */
    private fun treeHint(tree: TreeCheck, ocrStatus: String? = null): String? = when {
        quality(tree) == null -> null
        ocrStatus != null && locator != null -> Feedback.format("tree_poor.no_text.visual", "ocr" to ocrStatus)
        ocrStatus != null -> Feedback.format("tree_poor.no_text", "ocr" to ocrStatus)
        locator != null -> Feedback.format("tree_poor.visual", "tree_poor" to Feedback["tree_poor"])
        else -> Feedback["tree_poor"]
    }

    /**
     * 核查前再看一眼:此刻重新读这块屏(不登记编号,模型手里的 ref 不受影响),[withImage] 时另截一张图(防截屏页面不截);
     * 读到了时再加上能力另给的 [extra](网页正文的开头:读屏只有视口里的元素)。读不到也照样返回(没有观察)。
     */
    suspend fun freshLook(withImage: Boolean, extra: (suspend () -> Observation?)? = null): Look {
        val s = try { screen.snapshot() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val png = if (withImage && s != null && !s.protected) {
            try { screen.screenshotPng() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        } else null
        // 树不够用时照样读屏上文字:核查要看的是屏上写着什么
        val seen = s?.let { interfaceRead(it, readTree(it).ocr.orEmpty()) }?.takeIf { it.text.isNotEmpty() }
        val more = if (s != null) extra?.invoke() else null
        return Look(listOfNotNull(seen, more), png, s?.protected == true)
    }

    /** 「在哪」按这块屏的说法报(网页上是站点):和 observe 一样,手机与网页的「在哪」不混。 */
    private fun onSurface(o: JsonObject): JsonObject {
        val key = surface.family.placeKey
        if (key == ScreenFamily.APP_PLACE || ScreenFamily.APP_PLACE !in o) return o
        return JsonObject(o.filterKeys { it != ScreenFamily.APP_PLACE } + (key to (o[ScreenFamily.APP_PLACE] ?: JsonNull)))
    }
}

/** 这一屏输入框(不是提示语、不是密码框)里的字,和有输入焦点的那个;证据账本据此分辨输入框回显、接管时用户填的字。 */
internal fun inputFields(s: ScreenSnapshot): InputFields {
    val fields = s.elements.filter { it.editable && !it.hint && !it.secret && it.name.isNotBlank() }
    return InputFields(fields.map { it.name.replace('\n', ' ') }, fields.firstOrNull { it.focused }?.name?.replace('\n', ' '))
}

internal class InputFields(val names: List<String>, val focused: String?)

/** 一次读屏的树判断、补上的屏上文字,和树不够用而文字一行也没有时的原因(见 ScreenSession.visibleText)。 */
internal data class TreeRead(val tree: TreeCheck, val ocr: List<VisibleTextLine>?, val ocrStatus: String?)

/** 读屏的元素文字,一行一个;第一行是前台 App。没有文字时为空串。密码框里的字不记。 */
internal fun screenText(s: ScreenSnapshot): String {
    val names = s.elements.filter { it.meaningful && it.name.isNotBlank() && !it.secret }.map { it.name.replace('\n', ' ') }
    // 网页标题不在元素里,也是这一屏的事实(页面给的,和元素文字一样是待核对的材料);没有可读元素的网页也照样留着
    val title = s.web?.title?.takeIf { it.isNotBlank() }?.let { "网页标题:${it.replace('\n', ' ')}" }
    val dialog = s.web?.dialog?.let { "网页弹框(${it.kind}):${it.message.replace('\n', ' ')}" }
    if (names.isEmpty() && title == null && dialog == null) return ""
    val where = s.web?.let { "当前网页:${it.url}" } ?: "当前 App:${s.packageName ?: "未知"}"
    return (listOfNotNull(where, title, dialog) + names).joinToString("\n").take(MAX_READ_CHARS)
}

/** 无障碍文字仍是主证据；本机 OCR 只追加树里缺失的屏幕文字。 */
private fun observationText(s: ScreenSnapshot, ocr: List<VisibleTextLine>): String {
    val tree = screenText(s)
    if (ocr.isEmpty()) return tree
    return listOf(tree, "屏幕可见文字(OCR):", ocr.joinToString("\n") { it.text }).filter(String::isNotEmpty)
        .joinToString("\n").take(MAX_READ_CHARS)
}

internal fun toPx(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt()

/** 读屏结果里的页面弹框:模型要先用 web_interact 的 dialog 回话,页面才会接着跑。 */
internal fun dialogJson(d: PageDialog): JsonObject = buildJsonObject {
    put("kind", d.kind)
    put("message", d.message.take(PageDialog.MAX_MESSAGE))
    d.defaultText?.let { put("defaultText", it.take(PageDialog.MAX_MESSAGE)) }
    put("hint", Feedback["page_dialog"])
}
