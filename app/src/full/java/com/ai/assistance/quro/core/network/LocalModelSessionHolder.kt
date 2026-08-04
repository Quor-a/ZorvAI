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
 * 用户经 UI 显式 [load] 后，原生会话
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
    @Volatile
    private var _nCtx: Int = 0

    private fun isLlamaLoaded(): Boolean =
        _state is LocalModelLoader.State.Loaded && _model?.type == QuroLocalModelType.LLAMA_CPP

    private fun isMnnLoaded(): Boolean =
        _state is LocalModelLoader.State.Loaded && _model?.type == QuroLocalModelType.MNN

    override fun getState(): LocalModelLoader.State = synchronized(gate) { _state }

    /**
     * 身份判定（门禁的判据）。
     *
     * **ID 才是模型的唯一身份**：`id` 是导入时生成的持久化 UUID（MNN / llama.cpp 共用同一生成
     * 逻辑，写进 `filesDir/quro_local_models.json`）。两条记录只要 `id` 相同，就是同一个模型。
     *
     * ⚠️ 为何不再用「目录路径」做回退（第二轮回归收紧，F2）：
     * 此前为兼容「记录删了重导 / 配置重建」场景，加了「id 不等但类型 + 规范化路径相等 → 视为同一模型」。
     * QA 证伪该回退并定位两处问题：
     *  1. 会让「同目录两个不同 id 的权重记录」被判成同一模型，重开「加载 A 拿 B」的口子
     *     （本应被门禁拦下，却静默走到 A 的常驻权重上去答 B 的对话）；
     *  2. 声称要解决的「删了重导 → 旧 id 匹配不上」其实不成立——重导会生成新 uuid **且** 新 dstDir，
     *     路径也不同，回退同样命中不了，是条无效分支。
     * 因此收紧为「仅比 id」。代价：删了重导的旧记录确实需要重新点一次加载，但这正确且安全；
     * 而用户正常导入流程（每条记录独立 uuid + 独立 dstDir）构造不出「同目录多 id」记录，
     * 门禁语义（拒绝张冠李戴）完整保留。
     *
     * 空白 id 仍正确拦截：两边 id 都为空时 `held.id.isNotBlank()` 为 false → 返回 false → 走"未加载"，
     * 不会因 `"" == ""` 误放行。
     */
    private fun sameModel(held: QuroLocalModel?, incoming: QuroLocalModel): Boolean {
        if (held == null) return false
        // ID 才是唯一身份：已保存配置（持久化 UUID）不受影响，无需迁移。
        return held.id.isNotBlank() && held.id == incoming.id
    }

    override fun isLoaded(model: QuroLocalModel): Boolean =
        synchronized(gate) { _state is LocalModelLoader.State.Loaded && sameModel(_model, model) }

    override fun residentCtxTokens(): Int =
        if (_state is LocalModelLoader.State.Loaded) _nCtx else 0

    /**
     * 门禁取证快照（**设备侧无 adb**，这是唯一能判读"到底为什么被拦"的途径）。
     *
     * 门禁以前只记了"被拦截的 model 是谁"，完全没记"holder 当时持有的是什么"，
     * 导致日志里只能看到"未加载"四个字，压根分不清：
     *   - 压根没加载（State.None）
     *   - 加载过但失败了（State.Failed，且带着真实失败原因）
     *   - 正在加载（State.Loading）
     *   - 加载了但不是这个模型（State.Loaded 但 id/path 对不上）
     *   - 进程重启过（pid 与加载成功那行日志里的 pid 不同 → 原生崩溃/被系统回收）
     */
    data class Snapshot(
        val state: LocalModelLoader.State,
        val heldModel: QuroLocalModel?,
        val nCtx: Int,
        val activeGen: Int,
        val closing: Boolean,
        val pid: Int,
    ) {
        /** 状态短名（含 Failed 的真实原因）。 */
        fun stateText(): String = when (val s = state) {
            is LocalModelLoader.State.None -> "None(从未加载/已卸载)"
            is LocalModelLoader.State.Loading -> "Loading(加载中)"
            is LocalModelLoader.State.Loaded -> "Loaded"
            is LocalModelLoader.State.Failed -> "Failed(${s.message})"
        }

        /** 单行日志文本，直接拼进 QuroDiag。 */
        fun describe(): String =
            "state=${stateText()} | held.id=${heldModel?.id ?: "-"} | held.type=${heldModel?.type ?: "-"} | " +
                "held.name=${heldModel?.name ?: "-"} | held.path=${heldModel?.path ?: "-"} | " +
                "nCtx=$nCtx | activeGen=$activeGen | closing=$closing | pid=$pid"
    }

    /** 取当前持有状态的一致性快照（加锁读，字段之间不会撕裂）。 */
    fun snapshot(): Snapshot = synchronized(gate) {
        Snapshot(
            state = _state,
            heldModel = _model,
            nCtx = _nCtx,
            activeGen = activeGen.get(),
            closing = closing,
            pid = android.os.Process.myPid(),
        )
    }

    /** 统一的失败出口：置 Failed 态 + 落盘取证 + 返回 Failure。 */
    private fun fail(msg: String): LocalModelLoader.LoadResult.Failure {
        synchronized(gate) { _state = LocalModelLoader.State.Failed(msg) }
        QuroDiag.log("LocalModel", "✗ load 失败 | $msg | pid=${android.os.Process.myPid()}")
        return LocalModelLoader.LoadResult.Failure(msg)
    }

    override fun load(model: QuroLocalModel): LocalModelLoader.LoadResult {
        QuroDiag.log(
            "LocalModel",
            "▶ load 请求 | type=${model.type} | name=${model.name} | id=${model.id} | path=${model.path} | " +
                "modelNames=${model.modelNames} | pid=${android.os.Process.myPid()}"
        )
        // 已加载同一个模型 → 直接视为成功（幂等）。判据与门禁 [isLoaded] 完全一致，
        // 避免"门禁认为没加载、load 却认为已加载"这种两边打架的死角。
        synchronized(gate) {
            if (_state is LocalModelLoader.State.Loaded && sameModel(_model, model)) {
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
                        ?: return fail("MNN 模型路径无效：${model.path}")
                    val configFile = File(dir, "llm_config.json")
                    if (!configFile.isFile || configFile.length() <= 0L) {
                        return fail("MNN 模型目录缺少 llm_config.json：${dir.absolutePath}")
                    }
                    // ⚠️ 必须走 createMnnSessionStatic：它会传入用户配置的后端/线程/精度，并把
                    // 算子缓存指向应用私有可写目录。以前这里是无参 create → 缓存写不进模型目录
                    // → 每次加载重编译算子（"MNN 能回复但很慢"的直接原因）。
                    val session = QuroLocalEngineNative.createMnnSessionStatic(dir, model)
                        ?: return fail("MNN 会话创建失败：${dir.absolutePath}")
                    _mnn = session
                }

                QuroLocalModelType.LLAMA_CPP -> {
                    val wanted = model.modelNames.firstOrNull() ?: model.name
                    val file = QuroLocalEngineNative.resolveLlamaModelFileStatic(model.path, wanted)
                        ?: return fail(
                            "llama.cpp 模型文件未找到：目录 ${model.path} 下找不到 \"$wanted\" 对应的 .gguf" +
                                "（已登记 modelNames=${model.modelNames}）。请重新导入该模型。"
                        )
                    val pre = QuroLocalEngineNative.precheckLlamaFileStatic(file)
                    if (pre != null) return fail(pre)
                    // 常驻会话窗口：用户没配就开 6144（4096 下 maxSystemChars 只能给 ~1600，
                    // 而极简 system prompt 已占 838 字符，人格卡被腰斩 → 第二轮起失忆。
                    // 6144 是 4096 和 8192 的折中：够装身份+人格+最近几轮，KV-Cache 仍可控）。
                    val nThreads = model.resolveThreads()
                    val nCtx = if (model.contextSize > 0) model.contextSize.coerceIn(512, 32768) else 6144
                    _nCtx = nCtx
                    val cfg = LlamaSession.Config(
                        nThreads = nThreads,
                        nCtx = nCtx,
                        // GPU 层数：默认 offload 全部层（n_gpu_layers=99）。
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
                        return fail(
                            "llama.cpp 会话创建失败：${file.absolutePath}（${LlamaSession.getUnavailableReason()}）"
                        )
                    }
                    _llama = session
                }
            }
            // ⚠️ _model 必须在 _state 之前写：_state 是 volatile，其写入构成 happens-before 屏障，
            // 保证任何读到 Loaded 的线程都能看到已写好的 _model（门禁读的就是这一对）。
            _model = model
            synchronized(gate) { _state = LocalModelLoader.State.Loaded(model) }
            QuroDiag.log(
                "LocalModel",
                "✓ 模型已加载并常驻 | type=${model.type} | name=${model.name} | id=${model.id} | " +
                    "path=${model.path} | nCtx=$_nCtx | pid=${android.os.Process.myPid()}"
            )
            return LocalModelLoader.LoadResult.Success
        } catch (e: Throwable) {
            QuroDiag.log("LocalModel", "✗ load 异常 | ${e.message}\n${e.stackTraceToString()}")
            unload()
            return fail(e.message ?: e.javaClass.simpleName)
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
            _nCtx = 0
            _state = LocalModelLoader.State.None
            closing = false
            l to m
        }
        // 在锁外真正释放原生资源：此刻 activeGen 已归零，没有任何线程还在用这些 ctx。
        runCatching { toRelease.first?.release() }
        runCatching { toRelease.second?.release() }
        QuroDiag.log("LocalModel", "✓ 会话已卸载释放（在飞生成已退出） | pid=${android.os.Process.myPid()}")
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
