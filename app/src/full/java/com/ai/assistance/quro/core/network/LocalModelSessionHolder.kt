package com.ai.assistance.quro.core.network

import com.ai.assistance.llama.LlamaSession
import com.ai.assistance.mnn.MNNLlmSession
import com.ai.assistance.quro.core.model.QuroLocalModel
import com.ai.assistance.quro.core.model.QuroLocalModelType
import com.ai.assistance.quro.util.QuroDiag
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * full 风味专属：常驻内存的本地模型会话持有器（进程级单例）。
 *
 * 对应 PocketPal 的 `ModelStore`：用户经 UI 显式 [load] 后，原生会话
 * （[LlamaSession] / [MNNLlmSession]）常驻内存，**跨多轮对话复用同一个会话对象**，
 * 不再每条消息都重新把 GGUF 模型 load 进内存（那一步在手机上是秒级~十秒级的卡顿）。
 *
 * 注意：原生 `nativeGenerateStream` 每次生成都会清 KV-Cache（见 QuroLocalEngineNative 注释），
 * 所以复用会话对象**不会**把上一条消息的上下文带进下一条——每条消息仍按完整 prompt 重新 prefill，
 * 正确性不变，只是省掉了最贵的"模型加载"步骤。
 *
 * 风味隔离：本类位于 `app/src/full/java`，仅 full 风味编译。main 源码集通过
 * [LocalModelLoaders.get] 反射拿到单例；fdroid 风味反射失败回退 NoOp。
 *
 * ⚠️ 生命周期闸门（修复原生堆损坏崩溃 #native-lifecycle-race）：
 * 原生 `llama_decode` / MNN 推理是阻塞调用，跑在 routeLocal 的 IO 线程上，耗时数秒~数十秒。
 * 若此刻用户在另一线程点「卸载」（QuroModelConfigScreen 已移到 IO 线程）或重新 load
 * （load 内部先 unload），[unload] 会直接 `release()` 把正在被使用的 ctx / 权重缓冲区 free 掉
 * → 在飞生成读已释放内存 → SIGSEGV @ `ggml_vec_dot_q5_K_q8_K`（signal=11）
 * + SIGABRT @ `free` / `ggml_abort`（signal=6）。三者同现正是堆损坏被并发 free 的特征。
 *
 * 解决办法：用 [activeGen] 统计在飞生成数，[unload] 先置 [closing] 并对在飞会话 cancel()，
 * **等 [activeGen] 归零（≤20s）才真正 release**；[borrowLlama]/[borrowMnn] 在 closing 时拒绝新借出；
 * 调用方生成结束必须 returnLlama()/returnMnn() 让计数归零，unload 才能安全 free。
 * 所有方法共用同一把 [gate] 锁，保证计数与状态一致。
 */
object LocalModelSessionHolder : LocalModelLoader {

    private val gate = Any()
    private val activeGen = AtomicInteger(0)
    @Volatile
    private var closing = false

    @Volatile
    private var _state: LocalModelLoader.State = LocalModelLoader.State.None
    private var _llama: LlamaSession? = null
    private var _mnn: MNNLlmSession? = null
    private var _model: QuroLocalModel? = null

    private fun isLlamaLoaded(): Boolean =
        _state is LocalModelLoader.State.Loaded && _model?.type == QuroLocalModelType.LLAMA_CPP

    private fun isMnnLoaded(): Boolean =
        _state is LocalModelLoader.State.Loaded && _model?.type == QuroLocalModelType.MNN

    override fun getState(): LocalModelLoader.State = synchronized(gate) { _state }

    override fun isLoaded(model: QuroLocalModel): Boolean =
        synchronized(gate) { _state is LocalModelLoader.State.Loaded && _model?.id == model.id }

    override fun load(model: QuroLocalModel): LocalModelLoader.LoadResult {
        QuroDiag.log("LocalModel", "▶ load 请求 | type=${model.type} | name=${model.name} | id=${model.id}")
        // 已加载同一个模型 → 直接视为成功（幂等）。
        synchronized(gate) {
            if (_state is LocalModelLoader.State.Loaded && _model?.id == model.id) {
                return LocalModelLoader.LoadResult.Success
            }
        }
        // 先卸掉旧会话（会等待在飞生成退出，见 unload）。
        unload()
        synchronized(gate) { _state = LocalModelLoader.State.Loading }
        try {
            when (model.type) {
                QuroLocalModelType.MNN -> {
                    val dir = QuroLocalEngineNative.resolveMnnDirStatic(model.path)
                    if (dir == null) {
                        synchronized(gate) { _state = LocalModelLoader.State.Failed("MNN 模型路径无效：${model.path}") }
                        return LocalModelLoader.LoadResult.Failure((_state as LocalModelLoader.State.Failed).message)
                    }
                    val configFile = File(dir, "llm_config.json")
                    if (!configFile.isFile || configFile.length() <= 0L) {
                        synchronized(gate) { _state = LocalModelLoader.State.Failed("MNN 模型目录缺少 llm_config.json：${dir.absolutePath}") }
                        return LocalModelLoader.LoadResult.Failure((_state as LocalModelLoader.State.Failed).message)
                    }
                    // ⚠️ 必须走 createMnnSessionStatic：它会传入用户配置的后端/线程/精度，并把
                    // 算子缓存指向应用私有可写目录。以前这里是无参 create → 缓存写不进模型目录
                    // → 每次加载重编译算子（"MNN 能回复但很慢"的直接原因）。
                    val session = QuroLocalEngineNative.createMnnSessionStatic(dir, model)
                    if (session == null) {
                        synchronized(gate) { _state = LocalModelLoader.State.Failed("MNN 会话创建失败：${dir.absolutePath}") }
                        return LocalModelLoader.LoadResult.Failure((_state as LocalModelLoader.State.Failed).message)
                    }
                    _mnn = session
                }

                QuroLocalModelType.LLAMA_CPP -> {
                    val file = QuroLocalEngineNative.resolveLlamaModelFileStatic(
                        model.path, model.modelNames.firstOrNull() ?: model.name
                    )
                    if (file == null) {
                        synchronized(gate) { _state = LocalModelLoader.State.Failed("llama.cpp 模型文件未找到：${model.path}") }
                        return LocalModelLoader.LoadResult.Failure((_state as LocalModelLoader.State.Failed).message)
                    }
                    val pre = QuroLocalEngineNative.precheckLlamaFileStatic(file)
                    if (pre != null) {
                        synchronized(gate) { _state = LocalModelLoader.State.Failed(pre) }
                        return LocalModelLoader.LoadResult.Failure(pre)
                    }
                    // 常驻会话窗口：用户没配就开 4096（8192 的 KV-Cache 在手机上动辄数百 MB，
                    // 分配本身就要好几秒，是"点加载后一直转圈"的推手之一）。
                    val nThreads = model.resolveThreads()
                    val nCtx = if (model.contextSize > 0) model.contextSize.coerceIn(512, 32768) else 4096
                    val cfg = LlamaSession.Config(
                        nThreads = nThreads,
                        nCtx = nCtx,
                        // 线层数：对齐 PocketPal 默认 offload 全部层到 GPU（n_gpu_layers=99）。
                        // 原生层在 !gpuOffloadSupported 时会把请求值钳为 0，所以这里给大值安全。
                        // 用户若手动在参数面板设了具体层数（>0）则尊重其设置。
                        nGpuLayers = if (model.gpuLayers > 0) model.gpuLayers else 99,
                        useMmap = model.useMmap,     // 默认 false —— 外部存储上的 GGUF 用 mmap 会卡死加载
                        kvUnified = model.kvUnified, // 默认 true  —— 单序列统一 KV，少分配、加载快
                    )
                    QuroDiag.log(
                        "LocalModel",
                        "▶ llama 加载 | file=${file.absolutePath} | ${file.length() / 1024 / 1024}MB | " +
                            "nCtx=$nCtx | nThreads=$nThreads | gpuLayers=${cfg.nGpuLayers} | " +
                            "useMmap=${cfg.useMmap} | kvUnified=${cfg.kvUnified}"
                    )
                    val t0 = System.nanoTime()
                    val session = LlamaSession.create(file.absolutePath, cfg)
                    QuroDiag.log(
                        "LocalModel",
                        "· llama 加载返回 | ${(System.nanoTime() - t0) / 1_000_000}ms | ok=${session != null}"
                    )
                    if (session == null) {
                        synchronized(gate) {
                            _state = LocalModelLoader.State.Failed(
                                "llama.cpp 会话创建失败：${file.absolutePath}（${LlamaSession.getUnavailableReason()}）"
                            )
                        }
                        return LocalModelLoader.LoadResult.Failure((_state as LocalModelLoader.State.Failed).message)
                    }
                    _llama = session
                }
            }
            _model = model
            synchronized(gate) { _state = LocalModelLoader.State.Loaded(model) }
            QuroDiag.log(
                "LocalModel",
                "✓ 模型已加载并常驻 | type=${model.type} | name=${model.name} | path=${model.path}"
            )
            return LocalModelLoader.LoadResult.Success
        } catch (e: Throwable) {
            QuroDiag.log("LocalModel", "✗ load 异常 | ${e.message}\n${e.stackTraceToString()}")
            unload()
            synchronized(gate) { _state = LocalModelLoader.State.Failed(e.message ?: e.javaClass.simpleName) }
            return LocalModelLoader.LoadResult.Failure((_state as LocalModelLoader.State.Failed).message)
        }
    }

    override fun unload() {
        // 在 gate 锁内：置 closing、对在飞会话 cancel、等所有在飞生成退出，再取出待释放引用。
        val toRelease = synchronized(gate) {
            closing = true
            // 让在飞生成立即收到 cancel 信号，尽快退出循环（generate 循环顶部 / prefill 循环已检查 cancel）。
            runCatching { _llama?.cancel() }
            runCatching { _mnn?.cancel() }
            // 等待在飞生成退出（最多 20s）。Object.wait 会临时释放 gate，生成结束 returnXxx() 会 notifyAll 唤醒。
            val deadline = System.currentTimeMillis() + 20_000L
            while (activeGen.get() > 0 && System.currentTimeMillis() < deadline) {
                (gate as java.lang.Object).wait(deadline - System.currentTimeMillis())
            }
            val l = _llama
            val m = _mnn
            _llama = null
            _mnn = null
            _model = null
            _state = LocalModelLoader.State.None
            closing = false
            l to m
        }
        // 在锁外真正释放原生资源：此刻 activeGen 已归零，没有任何线程还在用这些 ctx。
        runCatching { toRelease.first?.release() }
        runCatching { toRelease.second?.release() }
        QuroDiag.log("LocalModel", "✓ 会话已卸载释放（在飞生成已退出）")
    }

    /**
     * 借出当前已加载的 llama 会话（不释放，调用方生成结束必须调 [returnLlama]）。无则返回 null。
     * closing 期间拒绝新借出，避免借到即将被释放的会话。
     */
    fun borrowLlama(): LlamaSession? = synchronized(gate) {
        if (closing || !isLlamaLoaded()) return null
        activeGen.incrementAndGet()
        _llama
    }

    /** 生成结束必须调用，让在飞计数归零；归零时唤醒等待中的 unload。 */
    fun returnLlama() = synchronized(gate) {
        val v = activeGen.decrementAndGet()
        if (v <= 0) {
            if (v < 0) activeGen.set(0)
            (gate as java.lang.Object).notifyAll()
        }
    }

    /**
     * 借出当前已加载的 MNN 会话（不释放，调用方生成结束必须调 [returnMnn]）。无则返回 null。
     * closing 期间拒绝新借出，避免借到即将被释放的会话。
     */
    fun borrowMnn(): MNNLlmSession? = synchronized(gate) {
        if (closing || !isMnnLoaded()) return null
        activeGen.incrementAndGet()
        _mnn
    }

    /** 生成结束必须调用，让在飞计数归零；归零时唤醒等待中的 unload。 */
    fun returnMnn() = synchronized(gate) {
        val v = activeGen.decrementAndGet()
        if (v <= 0) {
            if (v < 0) activeGen.set(0)
            (gate as java.lang.Object).notifyAll()
        }
    }
}
