package com.ai.assistance.quro.core.tools

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/**
 * QuroAI TTS 朗读工具（v37 — 引擎诊断 + 简化重试）。
 *
 * v36 Bug 日志证据：
 *   OnInit status=-1 → engine=null → available=0 voices
 *   重试 2 次均失败。TTS 服务无法绑定任何系统引擎。
 *
 * v37：加引擎列表诊断 + 更长重试间隔 + 详细错误输出。
 */
object QuroTtsHolder {
    private const val TAG = "QuroTts"
    private const val INIT_TIMEOUT_MS = 10_000L
    private const val MAX_RETRIES = 3

    // 本地 TTS 分块参数：小米/部分 OEM 引擎对单次 speak() 文本长度有上限，超长会静默截断，
    // 故按句分块入队。MIN 避免碎片过多，MAX 规避单段过大被引擎截断。
    private const val TTS_MIN_CHUNK = 30
    private const val TTS_MAX_CHUNK = 160

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initializing = false
    private var appCtx: Context? = null

    // onDone 完成回调表（ConcurrentHashMap 防 binder 线程与调用线程竞争）
    private val doneCallbacks = java.util.concurrent.ConcurrentHashMap<String, (() -> Unit)?>()

    private val holderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 语音球正在播报（保留字段：语音球仍会置位；但对话框不再据此跳过，改为串行入队，实现「两层都播·串行」）。 */
    @Volatile var voiceBallOwnsSpeech = false

    // ── 串行播报队列：杜绝「新语音打断/杀掉旧语音 → 有一个不播」──
    // 新请求到来时若已有语音在播，进入 pending 队列，等当前播完再播（"一个播完再播一个"）。
    private data class SpeechJob(val text: String, val onDone: (() -> Unit)?, val minimal: Boolean)
    private val pendingQueue = ArrayDeque<SpeechJob>()
    private val pumpLock = Mutex()
    @Volatile private var isSpeaking = false

    // ── 参数缓存 ──
    private var currentRate: Float = 1.0f
    private var currentPitch: Float = 1.0f

    // ── 日志回调 ──
    // ⚠️ 关键：TTS 引擎的 OnInitListener / UtteranceProgressListener 与 speak() 的 IO 协程都在
    // 非 UI 线程触发 log()，若直接回调到 Compose 状态会抛
    // "Cannot set value of mutableStateOf from a background thread" 导致 TTS 设置页一进就崩。
    // 因此所有日志回调一律切回主线程再投递。
    private val mainHandler = Handler(Looper.getMainLooper())
    private var logCallback: ((String) -> Unit)? = null

    fun setLogCallback(callback: ((String) -> Unit)?) { logCallback = callback }
    private fun log(msg: String) {
        Log.d(TAG, msg)
        val cb = logCallback ?: return
        mainHandler.post { cb(msg) }
    }

    /** 兼容旧版回调式 ensure。 */
    fun ensure(context: Context, onResult: (Boolean) -> Unit) {
        appCtx = context.applicationContext
        holderScope.launch {
            try {
                onResult(ensureReady(context))
            } catch (e: Exception) {
                log("ensure 异常已被兜底: ${e.message}")
                onResult(false)
            }
        }
    }

    /**
     * 诊断设备上的 TTS 引擎（静态方法）。
     * 注意：TextToSpeech.getEngines() 在某些编译环境下不可用，
     * 此处仅返回基本信息，详细引擎列表由 Bug 日志中的 OnInit 回调提供。
     */
    fun diagnoseEngines(context: Context): String {
        return try {
            // 尝试调用 getEngines，如果不可用则返回基本状态
            val method = TextToSpeech::class.java.getDeclaredMethod("getEngines", Context::class.java)
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val engines = method.invoke(null, context) as? List<*>
            if (engines.isNullOrEmpty()) "⚠️ 无可用 TTS 引擎"
            else "共 ${engines.size} 个引擎可用"
        } catch (e: Exception) {
            // 反射也失败 → 返回当前 TTS 实例状态
            buildString {
                append("engine="); append(tts?.defaultEngine ?: "(null)")
                append("｜voices="); append(tts?.voices?.size ?: "?")
                append("｜ready=$ready")
            }
        }
    }

    /**
     * 初始化 TTS 并等待就绪。
     */
    suspend fun ensureReady(context: Context): Boolean {
        val ctx = context.applicationContext
        appCtx = ctx

        if (ready && tts != null) { log("ensureReady: 已就绪 ✅"); return true }
        if (initializing) {
            log("ensureReady: 正在初始化...")
            val deadline = System.currentTimeMillis() + INIT_TIMEOUT_MS
            while (initializing && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(100); if (ready) return true
            }
            return ready
        }

        // ★ 诊断：列出所有可用引擎
        log("=== TTS 引擎诊断 ===")
        val diag = diagnoseEngines(ctx)
        log(diag)

        initializing = true; ready = false
        var lastStatus = -999

        for (attempt in 1..MAX_RETRIES) {
            if (attempt > 1) {
                log("--- 重试 #$attempt/$MAX_RETRIES (上次=$lastStatus) ---")
                runCatching { tts?.shutdown() }; tts = null; ready = false
                kotlinx.coroutines.delay(800) // 更长间隔
            } else {
                log("--- 初始化 (#$attempt/$MAX_RETRIES) ---")
            }

            lastStatus = tryCreate(ctx, attempt)

            if (ready && lastStatus == TextToSpeech.SUCCESS) return true
            // 失败则继续下一次重试
        }

        log("=== $MAX_RETRIES 次均失败 ❌ 最后 status=$lastStatus ===")
        log("engine=${tts?.defaultEngine ?: "null"}, voices=${tts?.voices?.size ?: "?"}")
        initializing = false
        return false
    }

    /** 创建 TextToSpeech 实例并等待 OnInit。返回 OnInit 的 status 值。 */
    private suspend fun tryCreate(ctx: Context, attempt: Int): Int {
        return try {
            suspendCancellableCoroutine { cont ->
                var instance: TextToSpeech? = null

                val listener = TextToSpeech.OnInitListener { status ->
                    log("【OnInit#$attempt】status=$status (0=OK / -1=ERROR)")

                    // ★ 赋值只在回调内部（处理同步 OnInit）
                    tts = instance
                    val eng = runCatching { instance?.defaultEngine }.getOrNull()
                    val vc = try { instance?.voices?.size ?: 0 } catch (e: Exception) { -1 }
                    log("【OnInit#$attempt】tts已赋值, engine=$eng, voices=$vc")

                    if (status == TextToSpeech.SUCCESS) {
                        ready = true; initializing = false
                        postInitSetup(instance)
                        log("【OnInit#$attempt】✅ SUCCESS — 就绪")
                        if (cont.isActive) cont.resume(status)
                    } else {
                        ready = false; initializing = false
                        Log.e(TAG, "【OnInit#$attempt】❌ FAILED")
                        if (cont.isActive) cont.resume(status)
                    }
                }

                // 第 2 次起尝试用系统默认引擎显式绑定，规避默认引擎解析偶发失败
                instance = if (attempt >= 2) {
                    val eng = runCatching {
                        val m = TextToSpeech::class.java.getDeclaredMethod("getDefaultEngine")
                        m.invoke(null) as? String
                    }.getOrNull()
                    if (!eng.isNullOrBlank()) {
                        log("【OnInit#$attempt】尝试显式引擎: $eng")
                        TextToSpeech(ctx, listener, eng)
                    } else {
                        TextToSpeech(ctx, listener)
                    }
                } else {
                    TextToSpeech(ctx, listener)
                }

                log("TextToSpeech(ctx) 构造器已返回 (#$attempt)")

                cont.invokeOnCancellation {
                    runCatching { instance?.shutdown() }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryCreate 异常 #$attempt: ${e.message}")
            initializing = false
            -1
        }
    }

    /** 初始化成功后的设置。 */
    private fun postInitSetup(instance: TextToSpeech?) {
        try {
            instance?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(u: String?) { log("onStart: $u") }
                override fun onDone(u: String?) { u?.let { doneCallbacks.remove(it)?.invoke() }; log("onDone: $u") }
                @Deprecated("Deprecated in Java")
                override fun onError(u: String?) { Log.w(TAG, "onError: $u") }
            })
            instance?.setSpeechRate(currentRate)
            instance?.setPitch(currentPitch)
            log("postInit: rate=$currentRate pitch=$currentPitch ✅")
        } catch (e: Exception) {
            Log.e(TAG, "postInitSetup: ${e.message}")
        }
    }

    /** 声音列表。 */
    fun getVoices(): List<Voice> = tts?.voices?.toList() ?: emptyList()

    /** 语言是否可用。 */
    fun isLanguageAvailable(ctx: Context): Boolean {
        val t = tts ?: return false
        return runCatching {
            t.isLanguageAvailable(Locale.forLanguageTag(QuroTtsPrefs.getLanguage(ctx).replace('_', '-'))) >= 0
        }.getOrDefault(false)
    }

    /**
     * 朗读。0=已发起/已入队 / -1=未就绪 / -2=合成或播放失败。
     * onDone 在【整段文本播报完成后】（或失败时）回调一次。
     * 串行队列：若当前已有语音在播，新请求进入 pending 队列，等当前播完再播（"一个播完再播一个"），
     * 不再用 abortAll/tts.stop 打断上一段，杜绝「两次语音有一个不播」。
     */
    suspend fun speak(text: String, onDone: (() -> Unit)? = null): Int = enqueueOrPlay(text, onDone, false)

    /** 安全模式 speak（不设参数）。同样走串行队列。 */
    suspend fun speakMinimal(text: String, onDone: (() -> Unit)? = null): Int = enqueueOrPlay(text, onDone, true)

    private suspend fun enqueueOrPlay(text: String, onDone: (() -> Unit)?, minimal: Boolean): Int {
        val ctx = appCtx ?: return (-1).also { log("enqueueOrPlay: 无 appCtx ❌"); onDone?.invoke() }
        // 串行播报，不再做「跨路径相同文本去重」：该去重会误杀对话框自动朗读（语音球已播同一条 → 对话框再播被丢弃，
        // 表现为「播了 TTS，文本朗读就用不了」），与用户选定的「两层都播·串行」相冲突。
        // 各路径自身已有去重守卫：对话框 lastSpokenId 防同消息重复朗读；语音球 ttsBusy/fired 一次性守卫。
        // 语音球把回复写进对话框后由对话框再播一遍 = 用户选定的「两层都播·串行」，属预期行为，不再拦截。
        // 空闲则立即播；播放中则入队串行，绝不打断当前语音
        if (!isSpeaking) return playOne(text, onDone, minimal)
        pumpLock.withLock { pendingQueue.add(SpeechJob(text, onDone, minimal)) }
        log("enqueueOrPlay: 已入队（当前播放中），稍后串行播报")
        return 0
    }

    /** 真正执行一段播报；播放期间 isSpeaking=true，完成后翻转并泵取下一段。 */
    private suspend fun playOne(text: String, onDone: (() -> Unit)?, minimal: Boolean): Int {
        isSpeaking = true
        val wrapped: (() -> Unit)? = {
            runCatching { onDone?.invoke() }
            isSpeaking = false
            holderScope.launch { pumpNext() }
        }
        val rc = withContext(Dispatchers.IO) {
            val ctx = appCtx ?: return@withContext -1.also { log("playOne: 无 appCtx ❌") }
            val src = QuroTtsPrefs.getSource(ctx)
            val isCloudLike = src == QuroTtsPrefs.SOURCE_CLOUD || src == QuroTtsPrefs.SOURCE_MIMO
            if (isCloudLike) {
                // 语色路由：若文本含 (语色:xxx) 标记且路由开启，则逐段分配音色 + 边播边合成；否则走原单一音色路径
                val routing = QuroVoiceFeaturePrefs.getVoiceColorRoutingEnabled(ctx)
                val segs = if (routing) QuroVoiceStyle.parseVoiceRouting(text) else emptyList()
                return@withContext if (segs.any { it.voiceColor != null }) speakCloudSegments(ctx, segs, wrapped) else speakCloud(text, wrapped)
            }
            if (!ensureReady(ctx)) return@withContext -1.also { log("playOne: 未就绪 ❌") }
            val t = tts ?: return@withContext -1.also { log("playOne: tts=null") }
            runCatching { t.stop() }
            if (!minimal) runCatching { applyParams(t, ctx) }
            // 本地引擎不识别风格标记，先剥离，避免念出「(开心)」；云路径在 QuroCloudTts 内解析
            val spoken = QuroVoiceStyle.strip(text)
            val chunks = splitTextForTts(spoken)
            log("playOne: 分 ${chunks.size} 段")
            enqueueChunks(t, chunks, wrapped)
            0
        }
        if (rc != 0) isSpeaking = false
        return rc
    }

    /** 泵取队列中下一段（当前段播完时由 wrapped 回调触发）。 */
    private suspend fun pumpNext() {
        val job = pumpLock.withLock { pendingQueue.removeFirstOrNull() } ?: return
        log("pumpNext: 取出排队语音，开始串行播报")
        playOne(job.text, job.onDone, job.minimal)
    }

    /**
     * 把长文本切成适合单次 speak() 的片段：优先在句末标点断句，超过最大长度则强制按字符数切。
     * 小米/部分 OEM 引擎对单次 speak() 的文本长度有上限，超长会静默截断，故需分块入队。
     */
    private fun splitTextForTts(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= TTS_MAX_CHUNK) return listOf(trimmed)
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        val breaks = setOf('。', '！', '？', '!', '?', '；', ';', '\n', '”', '』', '、')
        for (ch in trimmed) {
            sb.append(ch)
            if ((breaks.contains(ch) || sb.length >= TTS_MAX_CHUNK) && sb.length >= TTS_MIN_CHUNK) {
                out.add(sb.toString()); sb.setLength(0)
            } else if (sb.length >= TTS_MAX_CHUNK * 2) {
                // 超长无标点（如长 URL/代码）：强制切断，避免单段过大
                out.add(sb.toString()); sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out.ifEmpty { listOf(trimmed) }
    }

    /**
     * 分段入队到系统 TTS：首段 QUEUE_FLUSH 清空旧队列，后续 QUEUE_ADD 顺序播放。
     * onDone 仅挂在【最后一段】的 utterance id 上，整段播完才回调一次；
     * 任一段入队失败则立即回调 onDone 并清理，避免调用方卡在等待。
     */
    private fun enqueueChunks(t: TextToSpeech, chunks: List<String>, onDone: (() -> Unit)?): Int {
        if (chunks.isEmpty()) { onDone?.invoke(); return -2 }
        val ids = chunks.map { UUID.randomUUID().toString() }
        if (onDone != null) doneCallbacks[ids.last()] = onDone
        var ok = true
        chunks.forEachIndexed { i, chunk ->
            val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val r = t.speak(chunk, mode, null, ids[i])
            if (r != TextToSpeech.SUCCESS) ok = false
        }
        if (!ok) {
            ids.forEach { doneCallbacks.remove(it) }
            onDone?.invoke()
            return -2
        }
        return 0
    }

    /** 云模型服务（小米 TTS）朗读。0=成功 / -1=无上下文 / -2=合成或播放失败。 */
    private suspend fun speakCloud(text: String, onDone: (() -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        val ctx = appCtx ?: return@withContext -1.also { log("speakCloud: 无 appCtx ❌"); onDone?.invoke() }
        return@withContext try {
            QuroCloudTts.play(ctx, text)
            log("speakCloud: 播放完成 ✅")
            onDone?.invoke()
            0
        } catch (e: Exception) {
            Log.e(TAG, "speakCloud 失败: ${e.message}")
            onDone?.invoke()
            -2
        }
    }

    /**
     * 语色路由播放：按段分配音色，并「边播边合成」——当前段播放时后台预合成下一段音频字节，
     * 当前段结束立刻播放已就绪的下一段，实现无缝衔接（播放仍顺序，绝不两段同时出声）。
     * 每段：含 (语色:xxx) 则用该语色对应 voice id，否则回落全局音色；情绪标记随文本交给下游引擎。
     */
    private suspend fun speakCloudSegments(ctx: Context, segs: List<QuroVoiceStyle.VoiceSeg>, onDone: (() -> Unit)?): Int {
        val providerId = QuroTtsProviderPrefs.getProvider(ctx)
        val def = QuroTtsProviders.byId(providerId) ?: return (-2).also { log("speakCloudSegments: 未知服务商 ❌"); onDone?.invoke() }
        val isMimo = def.kind == QuroTtsProviderKind.MIMO
        var prefetched: Pair<ByteArray, String>? = null
        return try {
            segs.forEachIndexed { i, seg ->
                val voiceOverride = seg.voiceColor?.let { QuroCloudTtsCatalog.voiceColorToVoice(providerId, it) }
                val segText = if (isMimo && seg.tags.isNotEmpty()) "(${seg.tags.joinToString(" ")}) ${seg.text}" else seg.text
                // 边播边合成：预取下一段音频字节（当前段播放期间后台合成）
                val nextSeg = if (i + 1 < segs.size) segs[i + 1] else null
                val nextVoice = nextSeg?.voiceColor?.let { QuroCloudTtsCatalog.voiceColorToVoice(providerId, it) }
                val nextText = nextSeg?.let { ns -> if (isMimo && ns.tags.isNotEmpty()) "(${ns.tags.joinToString(" ")}) ${ns.text}" else ns.text }
                val prefetchJob = if (nextSeg != null) holderScope.async { runCatching { QuroCloudTts.synthBytes(ctx, nextText!!, nextVoice) } } else null
                if (i == 0) {
                    QuroCloudTts.play(ctx, segText, voiceOverride)
                } else {
                    val p = prefetched ?: QuroCloudTts.synthBytes(ctx, segText, voiceOverride)
                    QuroCloudTts.playBytes(ctx, p.first, p.second)
                }
                prefetched = prefetchJob?.await()?.getOrNull()
            }
            onDone?.invoke()
            0
        } catch (e: Exception) {
            Log.e(TAG, "speakCloudSegments 失败: ${e.message}")
            onDone?.invoke()
            -2
        }
    }

    /**
     * 仅设置应用上下文（供云 TTS 读取 appCtx），不初始化系统 TTS 引擎。
     * 工具调用等「不能阻塞」的上下文里，先 prepare 再 speakAsync，避免任何同步等待。
     */
    fun prepare(context: Context) { appCtx = context.applicationContext }

    /**
     * 异步朗读：立即返回是否已发起，真正的合成+播放在后台 IO 协程进行，绝不阻塞调用线程。
     * 用于 SpeakTool 等工具调用场景——此前提到的「发唱歌就卡了」正是因为 runBlocking 等整首歌
     * 播放完才返回，把 ReAct 循环卡死；改为异步后工具瞬间返回，歌曲后台播放，界面不再冻结。
     */
    fun speakAsync(text: String): Boolean {
        val ctx = appCtx ?: run { log("speakAsync: 无 appCtx ❌"); return false }
        holderScope.launch {
            try {
                speak(text)
            } catch (e: Exception) {
                Log.e(TAG, "speakAsync 异常: ${e.message}")
            }
        }
        return true
    }

    /** 应用参数。 */
    private fun applyParams(t: TextToSpeech, ctx: Context) {
        val voiceName = QuroTtsPrefs.getVoice(ctx)
        currentRate = QuroTtsPrefs.getRate(ctx)
        currentPitch = QuroTtsPrefs.getPitch(ctx)

        if (voiceName.isNotBlank()) {
            t.voices?.firstOrNull { it.name == voiceName }?.let { v ->
                if (t.setVoice(v) == TextToSpeech.SUCCESS) {
                    t.setSpeechRate(currentRate); t.setPitch(currentPitch); return
                }
            }
        }
        resolveBestLocale(t, QuroTtsPrefs.getLanguage(ctx))?.let {
            log("applyParams: setLanguage($it)=${t.setLanguage(it)}")
        }
        t.setSpeechRate(currentRate); t.setPitch(currentPitch)
    }

    private fun resolveBestLocale(t: TextToSpeech, tag: String): Locale? {
        val clean = tag.trim().replace('_', '-').ifBlank { return Locale.getDefault() }
        val req = Locale.forLanguageTag(clean)
        t.voices?.firstOrNull { it.locale.toLanguageTag().equals(clean, ignoreCase = true) }?.let { return it.locale }
        req.language.ifBlank { null }?.let { lang ->
            t.voices?.firstOrNull { it.locale.language.equals(lang, ignoreCase = true) }?.let { return it.locale }
            t.availableLanguages?.firstOrNull { it.language.equals(lang, ignoreCase = true) }?.let { return it }
        }
        return if (t.isLanguageAvailable(req) >= TextToSpeech.LANG_AVAILABLE) req else null
    }

    fun stop() {
        pendingQueue.clear()
        isSpeaking = false
        QuroCloudTts.abortAll()
        tts?.stop()
        log("stop")
    }

    fun reset() {
        runCatching { tts?.shutdown() }; tts = null; ready = false; initializing = false
        log("reset: 已销毁")
    }

    fun audioDiagnostics(ctx: Context): String = runCatching {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val vol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val bt = runCatching { am.isBluetoothA2dpOn }.getOrDefault(false)
        buildString {
            append("音量=$vol/$max"); if (vol == 0) append(" ⚠️静音")
            append("｜${if (bt) "蓝牙" else "喇叭"}")
            append("｜engine="); append(tts?.defaultEngine ?: "null")
            append("｜ready=$ready｜initing=$initializing")
            append("｜voices="); append(tts?.voices?.size ?: "?")
        }
    }.getOrDefault("诊断不可用")
}

/** TTS 参数持久化。 */
object QuroTtsPrefs {
    private const val PREFS = "quro_tts"
    private const val KEY_LANG = "tts_language"; private const val KEY_VOICE = "tts_voice"
    private const val KEY_RATE = "tts_rate"; private const val KEY_PITCH = "tts_pitch"
    private const val KEY_SOURCE = "tts_source"
    const val SOURCE_LOCAL = "local"; const val SOURCE_MODEL = "model"; const val SOURCE_CLOUD = "cloud"; const val SOURCE_MIMO = "mimo"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun getSource(ctx: Context): String {
        return when (val v = prefs(ctx).getString(KEY_SOURCE, SOURCE_LOCAL)) {
            SOURCE_MODEL, SOURCE_CLOUD, SOURCE_MIMO -> v
            else -> SOURCE_LOCAL
        }
    }
    fun setSource(ctx: Context, s: String) =
        prefs(ctx).edit().putString(KEY_SOURCE, when (s) {
            SOURCE_MODEL, SOURCE_CLOUD, SOURCE_MIMO -> s
            else -> SOURCE_LOCAL
        }).apply()
    fun getLanguage(ctx: Context) = prefs(ctx).getString(KEY_LANG, "zh-CN") ?: "zh-CN"
    fun setLanguage(ctx: Context, l: String) = prefs(ctx).edit().putString(KEY_LANG, l).apply()
    fun getVoice(ctx: Context) = prefs(ctx).getString(KEY_VOICE, "") ?: ""
    fun setVoice(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_VOICE, v).apply()
    fun getRate(ctx: Context) = prefs(ctx).getFloat(KEY_RATE, 1.0f)
    fun setRate(ctx: Context, r: Float) = prefs(ctx).edit().putFloat(KEY_RATE, r).apply()
    fun getPitch(ctx: Context) = prefs(ctx).getFloat(KEY_PITCH, 1.0f)
    fun setPitch(ctx: Context, p: Float) = prefs(ctx).edit().putFloat(KEY_PITCH, p).apply()

    fun applyTo(ctx: Context, tts: TextToSpeech, applyLanguage: Boolean = true) {
        if (applyLanguage) runCatching { tts.setLanguage(Locale.forLanguageTag(getLanguage(ctx).replace('_', '-'))) }
        getVoice(ctx).takeIf { it.isNotBlank() }?.let { v ->
            tts.voices?.firstOrNull { it.name == v }?.let { runCatching { tts.setVoice(it) } }
        }
        runCatching { tts.setSpeechRate(getRate(ctx)) }; runCatching { tts.setPitch(getPitch(ctx)) }
    }
}

class SpeakTool : QuroTool {
    override val name = "speak"; override val description = "用系统 TTS 朗读文本"
    override val parametersJson = """{"type":"object","properties":{"text":{"type":"string"},"lang":{"type":"string"}},"required":["text"]}"""
    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments); val text = jo.optString("text", "")
        if (text.isEmpty()) return "缺少 text 参数"
        jo.optString("lang", "").takeIf { it.isNotEmpty() }?.let { QuroTtsPrefs.setLanguage(context, it) }
        // ★ 修复「发唱歌就卡了」：不再 runBlocking 等整首歌合成+播放完（会把 ReAct 循环卡死且无法取消），
        // 改为后台异步发起，工具立即返回，歌曲在后台播放，界面不再冻结。
        QuroTtsHolder.prepare(context)
        if (!QuroTtsHolder.speakAsync(text)) return "TTS 未就绪（缺少上下文）"
        return "已请求朗读（后台播放中）"
    }
}

class StopSpeakTool : QuroTool {
    override val name = "stop_speak"; override val description = "停止 TTS 朗读"
    override val parametersJson = """{"type":"object","properties":{}}"""
    override fun run(context: Context, arguments: String): String = QuroTtsHolder.stop().run { "已停止朗读" }
}
