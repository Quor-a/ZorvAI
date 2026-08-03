package com.ai.assistance.quro.core.network

import com.ai.assistance.quro.core.model.QuroLocalModel

/**
 * 本地模型的「加载 / 卸载」控制面（main 源码集接口，full 实现）。
 *
 * 解决此前"模型加载没有这个功能"的核心缺陷：让本地模型成为一等公民——
 * 可经 UI 显式 [load] / [unload]、常驻内存跨多轮对话复用、可查询 [getState] / [isLoaded]。
 *
 * 风味隔离：main 仅持接口与反射访问器 [LocalModelLoaders]；真正的会话持有在 full 风味的
 * `LocalModelSessionHolder`（依赖 :llama / :mnn 原生库，main 不可见）。
 * 这与既有 `QuroLocalEngine` / `QuroLocalEngineNative` 的隔离方式一致。
 */
interface LocalModelLoader {
    sealed interface State {
        object None : State
        object Loading : State
        data class Loaded(val model: QuroLocalModel) : State
        data class Failed(val message: String) : State
    }

    /** [load] 的结果：成功 / 失败（含原因）。 */
    sealed interface LoadResult {
        object Success : LoadResult
        data class Failure(val message: String) : LoadResult
    }

    fun getState(): State
    fun isLoaded(model: QuroLocalModel): Boolean
    fun load(model: QuroLocalModel): LoadResult
    fun unload()
}

/** 反射访问器：fdroid 风味无实现类时回退 NoOp，绝不崩溃。 */
object LocalModelLoaders {
    private const val IMPL = "com.ai.assistance.quro.core.network.LocalModelSessionHolder"

    fun get(): LocalModelLoader = try {
        // Kotlin object 编译为带 private 构造 + INSTANCE 静态字段的类
        Class.forName(IMPL).getField("INSTANCE").get(null) as LocalModelLoader
    } catch (_: Throwable) {
        NoOpLocalModelLoader
    }
}

private object NoOpLocalModelLoader : LocalModelLoader {
    override fun getState(): LocalModelLoader.State = LocalModelLoader.State.None
    override fun isLoaded(model: QuroLocalModel) = false
    override fun load(model: QuroLocalModel): LocalModelLoader.LoadResult =
        LocalModelLoader.LoadResult.Failure("本地推理运行时未接入（full 风味专有）。")
    override fun unload() = Unit
}
