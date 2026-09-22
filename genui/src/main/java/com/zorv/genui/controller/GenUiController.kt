package com.zorv.genui.controller

import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import com.zorv.genui.heal.GenUiSelfHeal
import com.zorv.genui.host.GenUiEvent
import com.zorv.genui.host.GenUiHost
import com.zorv.genui.protocol.Artifact
import com.zorv.genui.protocol.GenUiProtocolParser
import com.zorv.genui.protocol.StreamChunk
import com.zorv.genui.store.GenUiStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 编排器（#632）：把分散的模块粘成一条可端到端跑的链路。
 *
 *   LLM 流 ──ingest──▶ ProtocolParser ──▶ Store ──▶ Host(WebView 池) ──▶ shell.html
 *                                                          │
 *                                            Runtime/Intent/Emit 事件回流
 *                                                          │
 *                                                    SelfHeal 注入系统轮次
 *
 * 对外暴露给对话框（Compose）的只读状态：
 *  - [streamText]   当前轮非围栏正文（用于普通气泡展示）
 *  - [cards]        本次对话产出的卡片引用列表（聊天列表据此渲染 [GenUiCard]）
 *  - [cardStatus]   每卡片渲染状态（Loading/Ready/Error/Degraded），驱动骨架/错误/降级覆盖层
 *  - [events]       宿主事件流（emit/intent/错误），供上层订阅
 *
 * 调用方职责：
 *  - 把 LLM SSE delta 喂给 [ingest]，流结束时调 [finish]
 *  - 在聊天列表为每个 [ArtifactRef] 放一个 [com.zorv.genui.ui.GenUiCard]
 *  - 设置 [onRepairRequest]（把 feedback 发回模型触发重写）与 [onIntent]（组件主动接话）
 *  - 滚动时调用 [onViewportChanged] 做视口回收
 */
data class ArtifactRef(val id: String, val rev: Int, val lang: String)

sealed interface CardStatus {
    object Loading : CardStatus
    object Ready : CardStatus
    data class Error(val message: String, val attempt: Int) : CardStatus
    data class Degraded(val reason: String) : CardStatus
}

class GenUiController(
    private val host: GenUiHost,
    private val store: GenUiStore,
    private val selfHeal: GenUiSelfHeal,
    private val maxRewrite: Int = 3
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val parser = GenUiProtocolParser()

    /** id → 最新 artifact（内存优先，store 兜底） */
    private val latest = LinkedHashMap<String, Artifact>()
    /** id → 已绑定的 WebView 容器（用于 rev 原地重建） */
    private val containers = HashMap<String, ViewGroup>()
    /** id → 已触发重写次数 */
    private val rewriteCount = HashMap<String, Int>()
    /** id → 累计 UI 状态（emit state/storage 合并） */
    private val stateAcc = HashMap<String, JSONObject>()
    /** id → 围栏未闭合/扫描失败时的原始代码（降级覆盖层展示用） */
    private val failedCode = HashMap<String, String>()

    private val _streamText = MutableStateFlow("")
    val streamText: StateFlow<String> = _streamText.asStateFlow()

    private val _cards = MutableStateFlow<List<ArtifactRef>>(emptyList())
    val cards: StateFlow<List<ArtifactRef>> = _cards.asStateFlow()

    private val _cardStatus = MutableStateFlow<Map<String, CardStatus>>(emptyMap())
    val cardStatus: StateFlow<Map<String, CardStatus>> = _cardStatus.asStateFlow()

    val events: SharedFlow<GenUiEvent> = host.events

    /** 自愈反馈回调：把 <runtime-error> 发回模型触发重写 */
    var onRepairRequest: ((feedback: String) -> Unit)? = null

    /** 组件主动接话（emit intent）：交给对话层处理 */
    var onIntent: ((artifactId: String, type: String, payload: JSONObject?) -> Unit)? = null

    init {
        selfHeal.delegate = object : com.zorv.genui.heal.SelfHealDelegate {
            override fun degrade(artifactId: String, reason: String) {
                setStatus(artifactId, CardStatus.Degraded(reason))
            }
            override fun injectSystemTurn(feedback: String) {
                onRepairRequest?.invoke(feedback)
            }
        }
        scope.launch { host.events.collect { onEvent(it) } }
    }

    // ------------------------------------------------------------ 流入口

    /** 喂入一段增量 token（可在任意线程调用） */
    fun ingest(delta: String) {
        parser.push(delta).forEach { handle(it) }
    }

    /** 流结束：处理未闭合围栏等情况 */
    fun finish() {
        parser.finish()?.let { handle(it) }
    }

    private fun handle(chunk: StreamChunk) {
        when (chunk) {
            is StreamChunk.Text -> _streamText.update { it + chunk.text }

            is StreamChunk.ArtifactCommit -> {
                latest[chunk.artifact.id] = chunk.artifact
                _cards.update { mergeRef(it, chunk.artifact) }
                setStatus(chunk.artifact.id, CardStatus.Loading)
                scope.launch { store.save(chunk.artifact) }
                // 若容器已绑（rev 更新场景），原地重建
                containers[chunk.artifact.id]?.let { c ->
                    mainHandler.post { host.mount(chunk.artifact, c, snapshotOf(chunk.artifact.id)) }
                }
            }

            is StreamChunk.ArtifactFailed -> {
                val id = chunk.id ?: "unknown"
                failedCode[id] = chunk.code
                setStatus(id, CardStatus.Degraded(chunk.reason))
            }
        }
    }

    private fun mergeRef(list: List<ArtifactRef>, a: Artifact): List<ArtifactRef> {
        val ref = ArtifactRef(a.id, a.rev, if (a.lang == com.zorv.genui.protocol.Lang.HTML) "html" else "jsx")
        val others = list.filter { it.id != a.id }
        return others + ref
    }

    // ------------------------------------------------------------ 绑定（Compose 调）

    /** 由 [com.zorv.genui.ui.GenUiCard] 在容器就绪时调用（主线程） */
    fun bind(artifactId: String, container: ViewGroup) {
        containers[artifactId] = container
        val a = latest[artifactId]
        if (a != null) {
            mainHandler.post { host.mount(a, container, snapshotOf(artifactId)) }
        } else {
            // 跨会话恢复：内存无，则从 store 取最新版
            scope.launch {
                val fromStore = store.getLatest(artifactId) ?: return@launch
                latest[artifactId] = fromStore
                mainHandler.post { host.mount(fromStore, container, snapshotOf(artifactId)) }
            }
        }
    }

    /** 容器释放（滚出视口 / 列表项回收）时调用 */
    fun releaseCard(artifactId: String) {
        containers.remove(artifactId)
        host.release(artifactId)
    }

    /** 视口变化：通知宿主回收/复活（Compose 懒列表滚动时调用） */
    fun onViewportChanged(visibleIds: Set<String>) {
        mainHandler.post { host.onViewportChanged(visibleIds) }
    }

    /** 释放本控制器持有的全部 WebView（切换会话时调用，防内存无限增长）。卡片数据仍在各自 Room 库，切回可重建。 */
    fun releaseAll() {
        host.releaseAll()
        containers.clear()
    }

    /**
     * 冷启动预热：提前在池中建好空闲 WebView，消掉首张卡片 200-500ms 的冷启动。
     * 必须在主线程调用（WebViewPool.warmUp 非主线程会 no-op）；调用方负责切到主线程。
     */
    fun warmUp() = host.warmUp()

    // ------------------------------------------------------------ 事件回流

    private fun onEvent(e: GenUiEvent) {
        when (e) {
            is GenUiEvent.Ready -> setStatus(e.artifactId, CardStatus.Ready)

            is GenUiEvent.Emit -> {
                if (e.event == "intent") {
                    onIntent?.invoke(e.artifactId, e.payload?.optString("type") ?: "", e.payload)
                } else {
                    // state / storage：合并进快照并持久化
                    val acc = stateAcc.getOrPut(e.artifactId) { JSONObject() }
                    e.payload?.let { mergeInto(acc, it) }
                    scope.launch { store.saveState(e.artifactId, latest[e.artifactId]?.rev ?: 0, acc) }
                }
            }

            is GenUiEvent.RuntimeError -> {
                setStatus(e.artifactId, CardStatus.Error(e.message, e.attempt))
                val a = latest[e.artifactId] ?: return
                val n = (rewriteCount[e.artifactId] ?: 0) + 1
                if (n >= maxRewrite) {
                    rewriteCount.remove(e.artifactId)
                    setStatus(e.artifactId, CardStatus.Degraded("自愈超限（$maxRewrite）"))
                    return
                }
                rewriteCount[e.artifactId] = n
                selfHeal.onError(a, e.phase, e.message, e.stack, e.attempt)
            }

            is GenUiEvent.Degrade -> setStatus(e.artifactId, CardStatus.Degraded(e.reason))
        }
    }

    // ------------------------------------------------------------ 辅助

    private fun setStatus(id: String, s: CardStatus) {
        _cardStatus.update { it + (id to s) }
    }

    private fun snapshotOf(id: String): JSONObject? = stateAcc[id]

    private fun mergeInto(acc: JSONObject, patch: JSONObject) {
        patch.keys().forEach { k -> acc.put(k, patch.opt(k)) }
    }

    /** 供降级覆盖层取原始代码 */
    fun getCode(id: String): String? = latest[id]?.code ?: failedCode[id]
}
