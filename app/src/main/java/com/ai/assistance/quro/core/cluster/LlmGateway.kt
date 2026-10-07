package com.ai.assistance.quro.core.cluster

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 一次角色调用的产出 */
data class LlmOutcome(
    val text: String,
    val modelId: String,
    val tokens: Int
)

/**
 * 统一的 LLM 通道 —— 云端与端侧在此同构，上层只认这个接口。
 *
 * 为什么不用 QuroAssistant.ask()：
 *   QuroAssistant 是有状态的（绑定 QuroConversation store、自动存记忆、自动跑工具），
 *   它设计给"单助手单会话"。多角色集群需要的是**无状态、可按角色指定模型**的通道，
 *   所以这里直连 QuroLlmClient（云端）与 QuroLocalEngine（端侧）——
 *   它们都返回 QuroLlmResult，天然同构。
 *
 * 两个【适配点】集中了所有与仓库实际签名相关的代码，改这里即可，其余部分不需动。
 */
interface LlmGateway {
    suspend fun complete(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>> = emptyList(),
        jsonMode: Boolean = false,
        temperature: Float = 0.7f,
        maxTokens: Int = 4096
    ): LlmOutcome

    /** 流式版本：把回调桥成 Flow，供 UI 多轨道渲染 */
    fun stream(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>> = emptyList(),
        temperature: Float = 0.7f
    ): Flow<String>
}

class DefaultLlmGateway(
    private val context: Context,
    private val modelSource: ClusterModelSource
) : LlmGateway {

    /** 每个模型一个并发闸门：端侧 maxConcurrency=1，天然串行 */
    private val gates = HashMap<String, Semaphore>()

    private fun gate(m: ModelProfile): Semaphore = gates.getOrPut(m.id) {
        Semaphore(m.maxConcurrency.coerceAtLeast(1))
    }

    override suspend fun complete(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>>,
        jsonMode: Boolean,
        temperature: Float,
        maxTokens: Int
    ): LlmOutcome {
        val raw = gate(model).withPermit {
            if (model.kind == ModelKind.LOCAL) callLocal(model, systemPrompt, userPrompt, history, temperature, maxTokens, null)
            else callCloud(model, systemPrompt, userPrompt, history, temperature, maxTokens, null)
        }
        return LlmOutcome(raw.first, model.id, raw.first.length / 3)
    }

    override fun stream(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>>,
        temperature: Float
    ): Flow<String> = callbackFlow {
        val sink: (String) -> Unit = { trySend(it) }
        gate(model).withPermit {
            if (model.kind == ModelKind.LOCAL)
                callLocal(model, systemPrompt, userPrompt, history, temperature, 4096, sink)
            else
                callCloud(model, systemPrompt, userPrompt, history, temperature, 4096, sink)
        }
        awaitClose { }
    }.flowOn(Dispatchers.Default)

    // ————————————————— 适配点 A：云端 —————————————————
    /**
     * 直连 QuroLlmClient。它的 chat() 返回 QuroLlmResult（Text / ToolCalls / Error）。
     * QuroLlmClient 需要 QuroModelConfig 才能构造请求，这里从当前配置派生一份副本，
     * 只覆盖模型相关字段，其余沿用用户在配置界面里的设置。
     */
    private suspend fun callCloud(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>>,
        temperature: Float,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Pair<String, Int> {
        val base = com.ai.assistance.quro.core.model.QuroModelConfigRepository(context).load()
        // 通道归属：模型自带 baseUrl/apiKey 优先，为空才回退全局「当前」配置。
        // 少了这一步，角色绑哪个厂商都还是打同一个通道（模型名发到错端点 → 404）。
        val useOwnChannel = model.baseUrl.isNotBlank()
        val cfg = base.copy(
            baseUrl = if (useOwnChannel) model.baseUrl else base.baseUrl,
            apiKey = if (useOwnChannel) model.apiKey.ifBlank { base.apiKey } else base.apiKey,            model = model.displayName,
            provider = model.provider.ifBlank { base.provider },
            temperature = temperature,
            maxTokens = maxTokens,
            // 集群角色自己不发工具：工具由主持统一调度，避免 N 角色 × 259 工具撑爆上下文
            enableTools = false,
            maxToolRounds = 0
        )

        val messages = buildList {
            if (systemPrompt.isNotBlank())
                add(com.ai.assistance.quro.core.QuroChatMessage("system", systemPrompt))
            history.forEach { (u, a) ->
                add(com.ai.assistance.quro.core.QuroChatMessage("user", u))
                add(com.ai.assistance.quro.core.QuroChatMessage("assistant", a))
            }
            add(com.ai.assistance.quro.core.QuroChatMessage("user", userPrompt))
        }

        val client = com.ai.assistance.quro.core.network.QuroLlmClient()
        val result = client.chat(
            baseUrl = cfg.baseUrl,
            apiKey = cfg.apiKey,
            model = cfg.model,
            messages = messages,
            temperature = cfg.temperature,
            maxTokens = cfg.maxTokens,
            tools = emptyList(),
            stream = onToken != null,
            onToken = onToken,
            provider = cfg.provider
        )
        return extract(result)
    }

    // ————————————————— 适配点 B：端侧 —————————————————
    /**
     * 直连 QuroLocalEngine。签名已核实：
     *   run(model, modelName, messages, temperature, maxTokens, contextWindow,
     *       toolSpecsJson, onToken, onThinking, isCanceled): QuroLlmResult
     * 端侧不支持工具，toolSpecsJson 传 null。
     */
    private suspend fun callLocal(
        model: ModelProfile,
        systemPrompt: String,
        userPrompt: String,
        history: List<Pair<String, String>>,
        temperature: Float,
        maxTokens: Int,
        onToken: ((String) -> Unit)?
    ): Pair<String, Int> {
        val localRepo = com.ai.assistance.quro.core.model.QuroLocalModelRepository(context.applicationContext)
        val local = localRepo.findById(model.localModelId)
            ?: throw IllegalStateException("集群端侧角色找不到本地模型：${model.localModelId}")
        val engine = resolveLocalEngine(context)
        val loader = com.ai.assistance.quro.core.network.LocalModelLoaders.get()
        if (!engine.managesOwnLoading && !loader.isLoaded(local)) {
            when (val lr = loader.load(local)) {
                is com.ai.assistance.quro.core.network.LocalModelLoader.LoadResult.Failure ->
                    throw IllegalStateException("集群本地模型加载失败：${lr.message}")
                else -> Unit
            }
        }

        val messages = buildList {
            if (systemPrompt.isNotBlank())
                add(com.ai.assistance.quro.core.QuroChatMessage("system", systemPrompt))
            history.forEach { (u, a) ->
                add(com.ai.assistance.quro.core.QuroChatMessage("user", u))
                add(com.ai.assistance.quro.core.QuroChatMessage("assistant", a))
            }
            add(com.ai.assistance.quro.core.QuroChatMessage("user", userPrompt))
        }

        val result = engine.run(
            model = local,
            modelName = model.displayName,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens,
            contextWindow = model.contextWindow,
            toolSpecsJson = null,
            onToken = onToken,
            onThinking = null,
            isCanceled = { false }
        )
        return extract(result)
    }

    /** 端侧引擎落点：优先跨进程（隔离），否则进程内；均不可用时回退占位实现（明确提示、不崩溃）。 */
    private fun resolveLocalEngine(context: Context): com.ai.assistance.quro.core.network.QuroLocalEngine {
        if (com.ai.assistance.quro.core.network.QuroLocalEnginePrefs.isIsolated(context)) {
            try {
                val clazz = Class.forName("com.ai.assistance.quro.core.network.QuroLocalEngineRemote")
                val ctor = clazz.getDeclaredConstructor(Context::class.java)
                return ctor.newInstance(context.applicationContext) as com.ai.assistance.quro.core.network.QuroLocalEngine
            } catch (_: Throwable) {
                // 跨进程不可用：回退进程内
            }
        }
        return try {
            val clazz = Class.forName("com.ai.assistance.quro.core.network.QuroLocalEngineNative")
            clazz.getDeclaredConstructor().newInstance() as com.ai.assistance.quro.core.network.QuroLocalEngine
        } catch (_: Throwable) {
            com.ai.assistance.quro.core.network.QuroLocalEnginePlaceholder
        }
    }

    /** QuroLlmResult 三态归一：Text 取内容，ToolCalls 取附带文本，Error 抛错由上层降级 */
    private fun extract(result: com.ai.assistance.quro.core.QuroLlmResult): Pair<String, Int> =
        when (result) {
            is com.ai.assistance.quro.core.QuroLlmResult.Text ->
                result.content to (result.meta?.totalTokens ?: 0)
            is com.ai.assistance.quro.core.QuroLlmResult.ToolCalls ->
                result.content.orEmpty() to (result.meta?.totalTokens ?: 0)
            is com.ai.assistance.quro.core.QuroLlmResult.Error ->
                throw IllegalStateException(result.message)
            else -> "" to 0
        }
}

/** 测试用：不联网、不花 token，验证主持能否闭环 */
class ScriptedLlmGateway(private val script: (String) -> String) : LlmGateway {
    override suspend fun complete(
        model: ModelProfile, systemPrompt: String, userPrompt: String,
        history: List<Pair<String, String>>, jsonMode: Boolean,
        temperature: Float, maxTokens: Int
    ): LlmOutcome = LlmOutcome(script(userPrompt), model.id, script(userPrompt).length / 3)

    override fun stream(
        model: ModelProfile, systemPrompt: String, userPrompt: String,
        history: List<Pair<String, String>>, temperature: Float
    ): Flow<String> = kotlinx.coroutines.flow.flow { emit(script(userPrompt)) }
}
