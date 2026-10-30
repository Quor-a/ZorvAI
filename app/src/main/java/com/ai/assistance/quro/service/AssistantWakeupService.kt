package com.ai.assistance.quro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import com.ai.assistance.quro.core.assistant.BreenoWakeup
import com.ai.assistance.quro.core.assistant.AssistantServiceState
import com.ai.assistance.quro.core.assistant.AssistantChannel
import com.ai.assistance.quro.util.QuroDiag
import com.oplus.bot.speech.sdk.wakeup.BwvSdk
import com.oplus.bot.speech.sdk.wakeup.BwvSdkListener
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 语音唤醒常驻监听 —— 驱动**从小布包里搬进来的唤醒引擎**（`libbwvsdkjni` + `libwakeup` + `free_wakeup.jet`）。
 *
 * ## 这是「语音唤醒」那一项缺少的最后一段
 * 之前只把 `.so` 与模型搬进来了，但**没有人喂音频**，所以那个开关是空的。
 * 本服务做的是：麦克风 → PCM → `BwvSdk.nFeedData` → 命中唤醒词 → 通知主进程唤起语音球。
 *
 * ## 🔴 为什么必须跑在独立进程（清单里 `android:process=":wakeup"`）
 * JNI 有两种失败方式：符号没绑上是 Java 异常（能 catch）；native 内部出错是 **SIGSEGV**，
 * 进程当场死、捕获不到。唤醒引擎的 `nInitInstance` 两个路径参数语义是静态分析猜的，
 * 一旦猜错很可能崩 —— 放独立进程后，**崩的只是 `:wakeup`，主程序与对话完全不受影响**，
 * 主进程日志里会看到"唤醒进程已退出"，用户重开即可。
 *
 * ## 参数是怎么定的（不装作确知）
 * `nInitInstance(J, String, String)` 两个 String 的真名从静态分析拿不到（值埋在 code item 里）。
 * 所以按最可能的几种组合**依次尝试**，第一个 `nInstanceStatus` 看起来正常的就采用：
 *   ① (唤醒词模型路径, 模型目录)   ② (模型目录, 模型目录)
 *   ③ (唤醒词模型路径, "")        ④ (模型目录, "")
 * 全部失败就停服务并落日志 —— 不重试轰炸、不假装在工作。
 *
 * ## 电量与合规
 * - 只在用户**显式打开**「语音唤醒」通道且总服务开启时才跑（两层闸门都在 [onStartCommand] 里查）；
 * - 前台服务 + 通知常显，用户随时能看到它在听；
 * - 通道一关立刻释放 AudioRecord 与引擎句柄。
 */
class AssistantWakeupService : Service() {

    companion object {
        private const val TAG = "assistant-wakeup"

        const val ACTION_START = "com.ai.assistance.quro.action.WAKEUP_START"
        const val ACTION_STOP = "com.ai.assistance.quro.action.WAKEUP_STOP"

        /** 命中唤醒词后发给主进程的广播（跨进程用广播最稳，不涉及服务启动限制）。 */
        const val ACTION_HIT = "com.ai.assistance.quro.action.WAKEUP_HIT"

        private const val CHANNEL_ID = "assistant-wakeup"
        private const val NOTIF_ID = 0x4A52

        private const val SAMPLE_RATE = 16000
        /** 每次喂 20ms（16000 * 0.02 * 2 字节）—— 唤醒引擎的常见帧长。 */
        private const val CHUNK_BYTES = 640
    }

    private val running = AtomicBoolean(false)
    @Volatile private var handle: Long = 0L
    @Volatile private var record: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    private val listener = object : BwvSdkListener {
        override fun onInit(info: String?) {
            QuroDiag.log(TAG, "onInit info=$info")
        }

        override fun onResult(result: String?) {
            val word = result.orEmpty()
            QuroDiag.log(TAG, "onResult word=$word")
            if (word.isNotEmpty()) notifyMainProcess(word)
        }

        override fun onError(msg: String?) {
            QuroDiag.log(TAG, "onError msg=$msg")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            QuroDiag.log(TAG, "收到 STOP")
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        // 前台服务必须立刻起通知，否则系统会按 ANR 杀掉（5 秒规则）
        startForegroundCompat()

        // 两层闸门：总服务 / 语音唤醒通道，任一关闭就不听
        val denied = AssistantServiceState.gate(this, AssistantChannel.VOICE_WAKEWORD)
        if (denied != null) {
            QuroDiag.log(TAG, "denied ${denied.wire()}")
            updateNotification("语音唤醒已关闭")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!running.compareAndSet(false, true)) {
            QuroDiag.log(TAG, "已在运行，忽略重复启动")
            return START_STICKY
        }

        // 引擎初始化与采集放在工作线程：AudioRecord 建立、JNI init 都可能耗时
        worker = Thread({ runLoop() }, "assistant-wakeup-worker").apply {
            isDaemon = true
            start()
        }
        return START_STICKY
    }

    // ── 主循环 ────────────────────────────────────────────────────

    private fun runLoop() {
        try {
            // ── ① 先做分层探测：库能不能加载、native 能不能调 ──
            val probe = BreenoWakeup.probe(this)
            QuroDiag.log(TAG, "probe level=${probe.level} version=${probe.version} err=${probe.error}")
            if (!probe.usable) {
                QuroDiag.log(TAG, "引擎不可用（${probe.level.desc}），停止监听：${probe.error}")
                updateNotification("唤醒引擎不可用：" + (probe.error ?: probe.level.desc))
                return
            }

            // ── ② 建引擎实例 ──
            val sdk = BwvSdk()
            val h = sdk.nGetInstance()
            if (h == 0L) {
                QuroDiag.log(TAG, "nGetInstance 返回 0，停止")
                updateNotification("唤醒引擎实例创建失败")
                return
            }
            handle = h
            QuroDiag.log(TAG, "handle=$h version=${sdk.nVersion(h)}")

            // ── ③ 回调与参数 ──
            sdk.nSetListener(h, listener)
            val hp = sdk.nSetHParams(h, BwvSdk.HParams.defaults())
            QuroDiag.log(TAG, "nSetHParams -> $hp")

            // ── ④ 初始化：两个路径参数语义未知，按候选组合依次试 ──
            val modelDir = BreenoWakeup.ensureModelsOnDisk(this)
            val wakeModel = modelDir?.let { File(it, "free_wakeup.jet") }?.takeIf { it.isFile }
            val dirPath = modelDir?.absolutePath.orEmpty()
            val modelPath = wakeModel?.absolutePath ?: ""

            val candidates = listOf(
                modelPath to dirPath,
                dirPath to dirPath,
                modelPath to "",
                dirPath to "",
            ).distinct()

            var inited = false
            for ((a, b) in candidates) {
                val rc = runCatching { sdk.nInitInstance(h, a, b) }.getOrElse {
                    QuroDiag.log(TAG, "nInitInstance($a,$b) 抛错: ${it.javaClass.simpleName}: ${it.message}")
                    -1
                }
                val st = runCatching { sdk.nInstanceStatus(h) }.getOrDefault(-1)
                QuroDiag.log(TAG, "nInitInstance($a,$b) rc=$rc status=$st")
                if (rc == 0 && st >= 0) {
                    inited = true
                    break
                }
            }
            if (!inited) {
                QuroDiag.log(TAG, "全部初始化组合失败 —— 两个路径参数的真实语义需要真机/反汇编确认")
                updateNotification("唤醒引擎初始化失败（参数语义未确认）")
                return
            }

            val qm = runCatching { sdk.nQueryModel(h) }.getOrDefault(-1)
            QuroDiag.log(TAG, "nQueryModel -> $qm")

            // ── ⑤ 开麦克风，循环喂数据 ──
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                QuroDiag.log(TAG, "getMinBufferSize 返回 $minBuf，无法录音")
                updateNotification("麦克风不可用")
                return
            }
            val bufBytes = maxOf(minBuf, CHUNK_BYTES * 8)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                QuroDiag.log(TAG, "AudioRecord 初始化失败 state=${rec.state}")
                runCatching { rec.release() }
                updateNotification("麦克风初始化失败")
                return
            }
            record = rec
            rec.startRecording()
            updateNotification("正在监听唤醒词")
            QuroDiag.log(TAG, "开始采集：${SAMPLE_RATE}Hz mono 16bit，chunk=$CHUNK_BYTES")

            val buf = ByteArray(CHUNK_BYTES)
            val opts = BwvSdk.FeedOptions()
            var fed = 0L
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    // 读失败（被别的应用抢占 / 设备切换）——短暂退避，不狂转
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_DEAD_OBJECT) {
                        QuroDiag.log(TAG, "AudioRecord.read 返回 $n，重建采集")
                        break
                    }
                    Thread.sleep(10)
                    continue
                }
                val rc = runCatching { sdk.nFeedData(h, buf, n, opts) }.getOrElse {
                    QuroDiag.log(TAG, "nFeedData 抛错: ${it.javaClass.simpleName}: ${it.message}")
                    -1
                }
                fed++
                if (fed % 500L == 0L) {
                    // 每 ~10s 记一次心跳，避免日志刷屏但能看出它还活着
                    QuroDiag.log(TAG, "feed 心跳 chunks=$fed lastRc=$rc")
                }
                if (rc < 0) {
                    QuroDiag.log(TAG, "nFeedData 返回 $rc，停止喂数据")
                    break
                }
            }
            QuroDiag.log(TAG, "采集循环结束 chunks=$fed")
        } catch (t: Throwable) {
            QuroDiag.log(TAG, "runLoop 异常终止: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            shutdown()
        }
    }

    /** 命中唤醒词 → 跨进程通知主进程。 */
    private fun notifyMainProcess(word: String) {
        runCatching {
            // 显式发给本应用包（不带 package 的话 Android 8+ 隐式广播收不到）
            sendBroadcast(Intent(ACTION_HIT).setPackage(packageName).putExtra("word", word))
            QuroDiag.log(TAG, "已发出唤醒广播 word=$word")
        }.onFailure { QuroDiag.log(TAG, "sendBroadcast 失败: ${it.message}") }
    }

    private fun shutdown() {
        if (!running.getAndSet(false)) {
            // 仍然做一次幂等清理
        }
        runCatching { record?.stop() }.onFailure { QuroDiag.log(TAG, "record.stop: ${it.message}") }
        runCatching { record?.release() }
        record = null
        if (handle != 0L) {
            runCatching { BwvSdk().nStopFeed(handle) }
            runCatching { BwvSdk().nStopInstance(handle) }
            runCatching { BwvSdk().nReleaseInstance(handle) }
            QuroDiag.log(TAG, "引擎已释放 handle=$handle")
            handle = 0L
        }
        worker = null
    }

    override fun onDestroy() {
        super.onDestroy()
        shutdown()
    }

    // ── 通知 ──────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "语音唤醒", NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻监听唤醒词时显示的提示"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle("ZorvAI 语音唤醒")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification("正在启动…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID, buildNotification(text))
        }
    }
}
