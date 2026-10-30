package com.ai.assistance.quro.core.assistant

import android.content.Context
import com.ai.assistance.quro.util.QuroDiag
import java.io.File

/**
 * 小布语音引擎的接入层 —— **引擎 `.so` 与唤醒模型都是从 Breeno.apk 字节级搬来的**。
 *
 * ## 搬了什么
 * `.so`（`app/src/main/jniLibs/arm64-v8a/`，8.7MB，依赖闭包已补齐）：
 * `libbwvsdkjni` → `libBreenoWakeupVprint` → `libnnaec`(神经网络回声消除) + `libMNN` + `libopenblas`
 * + `libkws` + `libc++_shared`，另有独立的 `libwakeup`（思必驰 WTK，1925 个 `wtk_*` C 符号）。
 *
 * 模型（`app/src/main/assets/wakeup/`，1.4MB）：
 * `free_wakeup.jet`（唤醒词）、`wakeup.bin`、`vad.mnn` + `vad.conf` + `cmn.acc`（VAD）。
 *
 * ## 为什么分四层探测
 * JNI 调用有两种失败方式，且**表现完全不同**：
 * 1. 符号没绑上 → `UnsatisfiedLinkError`，Java 异常，能捕获；
 * 2. native 内部崩 → SIGSEGV，**进程直接死，捕获不到**。
 *
 * 所以不能"一把梭"直接 init 引擎。按风险从低到高逐层探：
 * - L1 `loadLibrary` 成功（只是把 so 映射进来）
 * - L2 `nComputeTotalAmplitude(全零PCM, len)` 返回数值（纯计算、无句柄、内部不会解引用空指针）
 * - L3 `nGetInstance()` 拿到非 0 句柄（引擎对象创建成功）
 * - L4 `nVersion(handle)` 返回非空字符串（引擎完全就绪）
 *
 * 停在哪一层，就是哪一层的问题，用户拿到的是一句能照做的话而不是"语音唤醒不работает"。
 */
object BreenoWakeup {
    private const val TAG = "breeno-wakeup"

    /** 模型资产（与 assets/wakeup/ 下的文件名一致）。 */
    private val MODEL_FILES = listOf("free_wakeup.jet", "wakeup.bin", "vad.mnn", "vad.conf", "cmn.acc")

    /** 探测到哪一层。数值越大越接近可用。 */
    enum class Level(val code: String, val desc: String) {
        NOT_LOADED("wakeup-lib-not-loaded", "引擎库没加载（jniLibs 缺文件或被系统拦截）"),
        LOADED("wakeup-lib-loaded", "引擎库已加载，但 native 调用未验证"),
        NATIVE_CALLABLE("wakeup-native-callable", "native 调用可用（前端处理就绪）"),
        ENGINE_READY("wakeup-engine-ready", "引擎实例已创建"),
        VERSION_OK("wakeup-ready", "引擎完全就绪（拿到版本号）"),
    }

    data class Probe(
        val level: Level,
        val amplitude: Double? = null,
        val handle: Long = 0L,
        val version: String? = null,
        val missingModels: List<String> = emptyList(),
        val error: String? = null,
    ) {
        val usable: Boolean get() = level == Level.VERSION_OK || level == Level.ENGINE_READY
        fun wire(): String = buildString {
            append("level=").append(level.code)
            amplitude?.let { append(" amp=").append(String.format("%.3f", it)) }
            if (handle != 0L) append(" handle=").append(handle)
            version?.let { append(" version=").append(it) }
            if (missingModels.isNotEmpty()) append(" missingModels=").append(missingModels.joinToString(","))
            error?.let { append(" err=").append(it.take(160)) }
        }
    }

    /**
     * 分层探测。**不抛异常**：任何一层失败都转成 [Probe] 里的 level + error。
     *
     * 注意：`loadLibrary` 在一个进程内只会真正执行一次（static 块）。所以第一次探测失败后，
     * 后续探测会直接得到同一个失败结果（NoClassDefFoundError），这是预期行为 ——
     * 缺 so 需要重新装包，不是重试能解决的。
     */
    fun probe(ctx: Context): Probe {
        val missing = missingModels(ctx)
        if (missing.isNotEmpty()) {
            // 模型缺失时仍然探库（先确认引擎在不在），但整体判不可用
            QuroDiag.log(TAG, "probe: 模型缺失 $missing")
        }

        // ── L1：加载 so ──────────────────────────────────
        val fe = runCatching { com.oplus.bot.speech.sdk.frontend.BwvFeSdk() }.getOrElse {
            val err = "${it.javaClass.simpleName}: ${it.message}"
            QuroDiag.log(TAG, "L1 loadLibrary 失败: $err")
            return Probe(Level.NOT_LOADED, missingModels = missing, error = err)
        }

        // ── L2：纯计算调用（最安全，无句柄）───────────────
        val amp = runCatching {
            // 320 字节 = 160 个 16bit 采样，约 10ms@16k；全零输入，算出的总幅度应为 0
            fe.nComputeTotalAmplitude(ByteArray(320), 320)
        }.getOrElse {
            val err = "${it.javaClass.simpleName}: ${it.message}"
            QuroDiag.log(TAG, "L2 nComputeTotalAmplitude 失败: $err")
            return Probe(Level.LOADED, missingModels = missing, error = err)
        }
        QuroDiag.log(TAG, "L2 ok, amplitude=$amp")

        // ── L3：取引擎实例 ───────────────────────────────
        val sdk = runCatching { com.oplus.bot.speech.sdk.wakeup.BwvSdk() }.getOrNull()
            ?: return Probe(Level.NATIVE_CALLABLE, amplitude = amp, missingModels = missing, error = "BwvSdk 构造失败")
        val handle = runCatching { sdk.nGetInstance() }.getOrElse {
            val err = "${it.javaClass.simpleName}: ${it.message}"
            QuroDiag.log(TAG, "L3 nGetInstance 失败: $err")
            return Probe(Level.NATIVE_CALLABLE, amplitude = amp, missingModels = missing, error = err)
        }
        QuroDiag.log(TAG, "L3 ok, handle=$handle")
        if (handle == 0L) {
            return Probe(Level.NATIVE_CALLABLE, amplitude = amp, missingModels = missing, error = "nGetInstance 返回 0（引擎对象未创建）")
        }

        // ── L4：读版本（证明引擎真的活着）─────────────────
        val version = runCatching { sdk.nVersion(handle) }.getOrNull()
        if (version.isNullOrBlank()) {
            return Probe(Level.ENGINE_READY, amplitude = amp, handle = handle, missingModels = missing, error = "nVersion 返回空")
        }
        QuroDiag.log(TAG, "L4 ok, version=$version")

        return Probe(
            level = Level.VERSION_OK,
            amplitude = amp,
            handle = handle,
            version = version,
            missingModels = missing,
        )
    }

    /** 模型是否都在包里（用 AssetManager 列表，不解压）。 */
    fun missingModels(ctx: Context): List<String> = runCatching {
        val present = ctx.assets.list("wakeup")?.toSet().orEmpty()
        MODEL_FILES.filterNot { present.contains(it) }
    }.getOrDefault(MODEL_FILES)

    /** 语音前端加工入口：给一段 16k/16bit 单声道 PCM 做 AGC（失败返回原数据）。 */
    fun agc(pcm: ByteArray, configPath: String? = null): ByteArray {
        return runCatching {
            val fe = com.oplus.bot.speech.sdk.frontend.BwvFeSdk()
            val h = fe.nAgcInit(configPath)
            if (h == 0L) return@runCatching pcm
            try {
                fe.nAgcProcess(h, pcm, pcm.size, true) ?: pcm
            } finally {
                runCatching { fe.nAgcRelease(h) }
            }
        }.getOrDefault(pcm)
    }

    /** 给小布引擎的模型目录（assets 不发路径，需先拷到 filesDir）。返回目录或 null。 */
    fun ensureModelsOnDisk(ctx: Context): File? = runCatching {
        val dir = File(ctx.filesDir, "wakeup")
        if (!dir.isDirectory) dir.mkdirs()
        val present = ctx.assets.list("wakeup")?.toSet().orEmpty()
        MODEL_FILES.forEach { name ->
            if (!present.contains(name)) return@forEach
            val out = File(dir, name)
            if (out.exists() && out.length() > 0) return@forEach
            ctx.assets.open("wakeup/$name").use { input ->
                out.outputStream().use { o -> input.copyTo(o) }
            }
        }
        dir
    }.onFailure { QuroDiag.log(TAG, "ensureModelsOnDisk 失败: ${it.message}") }.getOrNull()
}
