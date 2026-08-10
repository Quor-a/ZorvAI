package com.ai.assistance.quro.core.aidlaci

import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.Capability
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciErrors
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciEvents
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciProtocol
import ai.aidl.aci.core.IAidlAciCallback
import ai.aidl.aci.core.IAidlAciService
import ai.aidl.aci.core.AidlAciLocalSocketTransport
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import org.json.JSONArray
import java.util.UUID
import java.util.Date
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * QuroAidlAciManager —— Zorv AI 作为 ACI 控制方（AI 中枢）的核心管理器。
 * 端口自 aci-aihub 的 ACIManager（Java），改写为 Kotlin 单例。
 *
 * ACI（Agent Capability Interface）协议层由 aci-core AAR 提供
 * （ai.aci.core.*：IAidlAciService / IAidlAciCallback AIDL、AidlAciRequest / AidlAciResponse / Capability）。
 * 本类负责：① 发现已安装 ACI 服务 → ② 绑定 → ③ 拉取能力 → ④ 同步/异步调用 →
 * ⑤ 生成能力清单（拼进系统提示词，让 LLM 知道能调什么）。
 *
 * 安全：调用前自动 setCallerPkg(本包名)，被调用方 BaseAidlAciService.onCheckPermission 据此鉴权；
 * 被调用方用 Binder.getCallingUid() 反查的真实包名覆盖自报 callerPkg（防伪造）。
 */
class QuroAidlAciManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "QuroAidlAciManager"
        const val ACI_ACTION = "ai.aci.core.ACTION_BIND"
        /** 唤醒被控 App 的广播 action（与 aci-core 的 ACIWakeReceiver.ACTION_WAKE 对齐） */
        const val ACI_WAKE_ACTION = "ai.aci.core.ACTION_WAKE"

        @Volatile
        private var sInstance: QuroAidlAciManager? = null

        fun init(context: Context) {
            if (sInstance == null) {
                synchronized(QuroAidlAciManager::class.java) {
                    if (sInstance == null) {
                        sInstance = QuroAidlAciManager(context.applicationContext)
                        sInstance!!.startHealthWatch()
                    }
                }
            }
        }

        fun getInstance(): QuroAidlAciManager =
            sInstance ?: throw IllegalStateException("QuroAidlAciManager 未初始化，请先调用 init(context)")
    }

    private val serviceMap = ConcurrentHashMap<String, IAidlAciService>()
    private val connMap = ConcurrentHashMap<String, ServiceConnection>()
    private val capMap = ConcurrentHashMap<String, List<Capability>>()
    private val nameMap = ConcurrentHashMap<String, String>()
    private val classMap = ConcurrentHashMap<String, String>()   // pkg → service class（用于断线后重绑）
    private val lastSeenMap = ConcurrentHashMap<String, Long>()
    /** 协商后的协议版本（pkg → 协议标识），由 fetchCapabilities 阶段 best-effort 协商写入。 */
    private val protocolMap = ConcurrentHashMap<String, String>()
    /** 每个包的 Binder 死亡监听（DeathRecipient），用于远端进程死亡时即时触发重绑。 */
    private val deathRecipients = ConcurrentHashMap<String, android.os.IBinder.DeathRecipient>()
    /**
     * LocalSocket 高速通道可用性标记（pkg → 状态）：
     *  null = 尚未探测；true = 可用（优先走 socket）；false = 探测失败（回落 AIDL）。
     * 任一调用若 socket 抛异常即置 false 并回落，断开时复位为 null 以便重探。
     */
    private val socketOk = ConcurrentHashMap<String, Boolean?>()
    /** 会话级调用追踪环形缓冲（最近 N 条），支撑 ACI 可观测性：每次调用带 callId、传输路径、延迟、结果。 */
    private val traceQueue = ConcurrentLinkedQueue<AciCallTrace>()
    private val TRACE_MAX = 50
    /** 每个包的重绑尝试计数，用于指数退避（成功绑定后清零）。 */
    private val rebindAttempts = ConcurrentHashMap<String, Int>()
    @Volatile private var healthExecutor: ScheduledExecutorService? = null
    @Volatile private var healthStarted = false

    @Volatile
    private var callTimeoutMs = 15_000L

    // ═══════════════════════════════════
    //  ① 服务发现
    // ═══════════════════════════════════
    fun discover(): List<DiscoveredApp> {
        val result = mutableListOf<DiscoveredApp>()
        val pm = appContext.packageManager
        val intent = Intent(ACI_ACTION)
        val services = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        Log.i(TAG, "🔍 ACI 发现：${services.size} 个服务")
        for (info in services) {
            val si = info.serviceInfo ?: continue
            val pkg = si.packageName
            val cls = si.name
            val label = si.loadLabel(pm).toString()
            Log.d(TAG, "  → $label ($pkg/$cls)")
            result.add(DiscoveredApp(pkg, cls, label))
            nameMap[pkg] = label
            classMap[pkg] = cls
            bindWithWake(pkg, cls)   // 改裸 doBind → 带唤醒，修复停止态绑不上
        }
        return result
    }

    // ═══════════════════════════════════
    //  ② 绑定
    // ═══════════════════════════════════
    /**
     * 真正执行 bindService。onServiceConnected 后写入 serviceMap 并拉取能力；
     * onServiceDisconnected 仅清 serviceMap，保留 capMap 缓存（让 aci_list 持续可用），
     * 并触发一次延迟重绑，使绑定保持温热。
     *
     * @param latch 非空时，连接成功后会 countDown，供 ensureBound 同步等待（最多 3s）。
     */
    private fun doBind(
        packageName: String,
        className: String,
        latch: java.util.concurrent.CountDownLatch? = null
    ): Boolean {
        val intent = Intent(ACI_ACTION).apply { setClassName(packageName, className) }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val service = IAidlAciService.Stub.asInterface(binder)
                serviceMap[packageName] = service
                rebindAttempts[packageName] = 0   // 成功绑定清零退避计数
                lastSeenMap[packageName] = System.currentTimeMillis()
                Log.i(TAG, "✅ 已绑定：$packageName")
                QuroAidlAciEvents.emit(QuroAidlAciEvents.EVT_SERVICE_BOUND, packageName, "")
                // 注册 Binder 死亡监听：远端进程死亡时立即（比 onServiceDisconnected 更早）触发重绑，
                // 把断线感知从「800ms 轮询」升级为「事件驱动」。
                if (binder != null) {
                    val recipient = createDeathRecipient(packageName)
                    deathRecipients[packageName] = recipient
                    try { binder.linkToDeath(recipient, 0) }
                    catch (e: Exception) { Log.w(TAG, "linkToDeath 失败（$packageName）：${e.message}") }
                }
                fetchCapabilities(packageName, service)
                latch?.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                serviceMap.remove(packageName)
                deathRecipients.remove(packageName)   // 断开即弃旧监听，避免悬空引用
                socketOk[packageName] = null          // 复位 socket 探测，便于重连后重探
                Log.w(TAG, "⚠️ 断开：$packageName（已保留能力缓存，待自动重绑）")
                QuroAidlAciEvents.emit(QuroAidlAciEvents.EVT_SERVICE_UNBOUND, packageName, "")
                scheduleRebind(packageName)
            }
        }
        return try {
            val ok = appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            if (ok) connMap[packageName] = conn
            else Log.e(TAG, "❌ 绑定失败：$packageName")
            ok
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ 绑定 SecurityException：${e.message}")
            false
        }
    }

    /** 断开后延迟重试绑定，保持绑定温热（best-effort，不阻塞调用方）。 */
    /**
     * 断开后延迟重试绑定，保持温热。采用指数退避（800ms → 1.6s → 3.2s … 上限 8s），
     * 避免对反复崩溃/不可达的被控端做高频空转绑定；成功（重）绑定后由 onServiceConnected 清零计数。
     */
    private fun scheduleRebind(pkg: String) {
        val cls = classMap[pkg] ?: return
        val attempt = (rebindAttempts[pkg] ?: 0) + 1
        rebindAttempts[pkg] = attempt
        val delay = (800L * (1 shl (attempt - 1))).coerceAtMost(8000L)
        Thread {
            try { Thread.sleep(delay) } catch (ignored: InterruptedException) {}
            if (serviceMap[pkg] == null) {
                Log.i(TAG, "🔄 重绑：$pkg（第 $attempt 次，延迟 ${delay}ms）")
                doBind(pkg, cls)
            }
        }.start()
    }

    /**
     * 带唤醒的绑定（修复「未绑定 / 能力(0)」根因）。
     *
     * 旧逻辑：discover()/rebind() 直接调裸 bindService —— 对「停止态」被控 App
     * （新装 / 重装后从未冷启动）在 ColorOS / Android 11+ 上无法拉起进程，bindService
     * 静默失败，ACI 中心永远显示未绑定、能力(0)。这正是用户「之前能成现在不行」的症结：
     * 反复重装受控端使其长期处于 stopped-state，而控制端从不发唤醒广播。
     *
     * 新逻辑：先尝试直绑（进程已运行 / 非停止态秒连）；失败则发 ACTION_WAKE
     * （FLAG_INCLUDE_STOPPED_PACKAGES）把进程拉起，稍候重试绑定。
     * 全程后台线程，不阻塞调用方（discover/rebind 多由 UI 触发）。
     */
    private fun bindWithWake(packageName: String, className: String) {
        Thread {
            // 1) 直绑（进程已在跑 / 非停止态直接成功）
            doBind(packageName, className)
            try { Thread.sleep(500) } catch (ignored: InterruptedException) {}
            if (serviceMap[packageName] != null) return@Thread

            // 2) 停止态：唤醒广播拉起进程后再绑
            Log.i(TAG, "📡 $packageName 初次绑定未成，发唤醒广播后重试")
            wakeCallee(packageName)
            try { Thread.sleep(900) } catch (ignored: InterruptedException) {}
            if (serviceMap[packageName] == null) doBind(packageName, className)
        }.start()
    }

    /** 创建某包的 Binder 死亡监听：进程死亡即清引用并触发重绑（死亡感知从轮询升级为事件驱动）。 */
    private fun createDeathRecipient(pkg: String): android.os.IBinder.DeathRecipient {
        return android.os.IBinder.DeathRecipient {
            Log.w(TAG, "💀 死亡监听触发：$pkg 远端进程已死，立即重绑")
            deathRecipients.remove(pkg)
            serviceMap.remove(pkg)
            scheduleRebind(pkg)
        }
    }

    /**
     * 获取目标包名的活体 IAidlAciService。
     * 若当前未绑定但曾发现过该 App → 同步（最多 3s）重绑后返回；
     * 若从未发现过 → 先重新 discover() 再重绑。
     * 这解决了「aci_list 能看到能力、但 aci_call 报 503 服务未绑定」的绑定生命周期问题。
     */
    private fun ensureBound(pkg: String): IAidlAciService? {
        serviceMap[pkg]?.let { return it }
        if (classMap[pkg] == null) {
            Log.i(TAG, "🔍 $pkg 未在缓存中，尝试重新发现")
            discover()
            if (classMap[pkg] == null) return null
        }
        // 第一次常规绑定尝试（被控 App 已运行/非停止态时直接成功）
        if (tryBindWithLatch(pkg)) return serviceMap[pkg]

        // 绑定失败：极可能是被控 App 处于 stopped-state（装完/强停后从未启动，无界面壳 App 尤甚）。
        // 发送 ACI 唤醒广播（带 FLAG_INCLUDE_STOPPED_PACKAGES 穿透停止态）把被控进程拉起，
        // 稍候进程就绪后重试绑定——全程无需用户手动打开被控 App。
        Log.i(TAG, "📡 $pkg 首次绑定未成功，发送唤醒广播拉起进程后重试")
        wakeCallee(pkg)
        try { Thread.sleep(600) } catch (ignored: InterruptedException) {}
        if (tryBindWithLatch(pkg)) return serviceMap[pkg]

        // 广播拉起仍失败：直接 startService 把受控 ACI Service 拉起（比广播更直接，
        // 对「无界面壳 App」同样有效），再尝试绑定。无需任何 UI 介入。
        val cls = classMap[pkg]
        if (cls != null) {
            Log.i(TAG, "🚀 $pkg 广播未成，改用 startService 直拉 ACI Service 后重试")
            startControlledService(pkg, cls)
            try { Thread.sleep(700) } catch (ignored: InterruptedException) {}
            if (tryBindWithLatch(pkg)) return serviceMap[pkg]
        }

        Log.e(TAG, "❌ $pkg 唤醒 + 直拉后仍无法绑定")
        return null
    }

    /** 判断目标包是否已安装（用于 call 前的「未安装」引导）。 */
    private fun isInstalled(pkg: String): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(pkg, 0)
    }.isSuccess

    /** 直接 startService 拉起被控 ACI Service（绕过 UI，便于无界面壳 App 自启）。 */
    private fun startControlledService(pkg: String, cls: String) {
        try {
            val i = Intent(ACI_ACTION).apply { setClassName(pkg, cls) }
            // 受控 Service 多为 exported 且无界面；以 START 拉起进程 + Service，便于后续 bindService
            appContext.startService(i)
            Log.i(TAG, "🚀 已 startService 拉起：$pkg/$cls")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ startService 拉起失败（$pkg）：${e.message}")
        }
    }

    /** 带 3s 闩的绑定尝试，成功返回 true。 */
    private fun tryBindWithLatch(pkg: String): Boolean {
        val cls = classMap[pkg] ?: return false
        val latch = java.util.concurrent.CountDownLatch(1)
        doBind(pkg, cls, latch)
        try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (ignored: InterruptedException) {}
        return serviceMap[pkg] != null
    }

    /**
     * 发送 ACI 唤醒广播，把处于停止态的被控 App 进程拉起。
     * 关键：FLAG_INCLUDE_STOPPED_PACKAGES 允许广播投递到「从未启动过」的 App；
     * 被调方 ACIWakeReceiver 收到后以自身身份启动其 ACI Service，使后续 bindService 成功。
     */
    private fun wakeCallee(pkg: String) {
        try {
            val i = Intent(ACI_WAKE_ACTION).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            appContext.sendBroadcast(i)
            Log.i(TAG, "📡 已发送唤醒广播：$pkg (ACTION_WAKE, INCLUDE_STOPPED)")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 唤醒广播发送失败：$pkg → ${e.message}")
        }
    }

    // ═══════════════════════════════════
    //  ③ 拉取能力
    // ═══════════════════════════════════
    private fun fetchCapabilities(pkg: String, service: IAidlAciService) {
        Thread {
            try {
                val raw = service.getCapabilities()   // String[]：每项为单个 Capability 的 JSON
                val list = mutableListOf<Capability>()
                if (raw != null) {
                    for (json in raw) {
                        if (json == null) continue
                        try {
                            val arr = JSONArray("[$json]")
                            list.addAll(Capability.fromJSONArray(arr))
                        } catch (e: Exception) {
                            // 单条能力 JSON 异常不应拖垮整个列表：跳过并告警
                            Log.w(TAG, "⚠️ 跳过无法解析的能力 ($pkg): ${json.take(120)}", e)
                        }
                    }
                }
                capMap[pkg] = list
                QuroAidlAciRegistry.syncFromCapabilities(pkg, list)
                // 绑定后主动探测 LocalSocket 高速通道（无副作用的连通性探针），
                // 使首次 aci_call 直接走最优路径，不必等到第一次调用失败才回落 AIDL。
                val sockUp = runCatching { AidlAciLocalSocketTransport.probe(pkg) }.getOrDefault(false)
                socketOk[pkg] = sockUp
                Log.i(TAG, "🔌 $pkg LocalSocket 探测：${if (sockUp) "可用（优先）" else "不可用（回落 AIDL）"}")
                Log.i(TAG, "📋 $pkg → ${list.size} 项能力（注册表已同步，标签检索可用）")
                for (c in list) Log.d(TAG, "    • ${c.id}: ${c.description}")
                // ACI 2.0 协议协商：对端若暴露 aci_protocol 能力，best-effort 取版本并协商（独立线程，不阻塞绑定）
                if (list.any { it.id == "aci_protocol" }) {
                    Thread {
                        try {
                            val pr = call(pkg, "aci_protocol", android.os.Bundle())
                            if (pr.isSuccess()) {
                                val peer = pr.getResult()?.getString("protocol_version")
                                val neg = QuroAidlAciProtocol.negotiate(peer)
                                protocolMap[pkg] = neg ?: QuroAidlAciProtocol.PROTOCOL_VERSION
                                QuroAidlAciEvents.emit(
                                    QuroAidlAciEvents.EVT_PROTOCOL_NEGOTIATED, pkg,
                                    "peer=$peer negotiated=${neg ?: "null→default"}"
                                )
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "协议协商失败($pkg): ${e.message}")
                        }
                    }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "拉取 $pkg 能力失败", e)
            }
        }.start()
    }

    // ═══════════════════════════════════
    //  ④ 同步调用（带超时）
    // ═══════════════════════════════════
    fun call(targetPackage: String, capability: String, params: android.os.Bundle): AidlAciResponse {
        val t0 = System.currentTimeMillis()
        val callId = UUID.randomUUID().toString()
        // 未安装引导：明确告诉 LLM/用户先安装目标 App，避免「服务未绑定」的迷之错误
        if (!isInstalled(targetPackage)) {
            val resp = AidlAciResponse.error(
                404,
                "目标 App 未安装：$targetPackage。请先安装该 App（或在 ACI 中心用「搜软件名」找到并启动它），再重试 aci_list 触发重新发现。"
            )
            QuroAidlAciCallAudit.log(appContext, targetPackage, capability, resp.getErrorCode(), false, System.currentTimeMillis() - t0)
            pushTrace(callId, targetPackage, capability, "none", resp, t0)
            return resp
        }
        val service = ensureBound(targetPackage)
        if (service == null) {
            val resp = AidlAciResponse.error(
                503,
                "服务未绑定：$targetPackage。请先确认目标 App 已安装且声明了 ACI Service；可重试 aci_list 触发重新发现。"
            )
            QuroAidlAciCallAudit.log(appContext, targetPackage, capability, resp.getErrorCode(), false, System.currentTimeMillis() - t0)
            pushTrace(callId, targetPackage, capability, "none", resp, t0)
            return resp
        }
        val req = AidlAciRequest(capability, params)
        req.setCallerPkg(appContext.packageName)
        req.setCallId(callId)

        // 优先本机 LocalSocket 高速通道（抽象命名空间）；失败/未探测成功则回落 AIDL。
        // 即便 socket 路径有设备侧异常，AIDL 兜底保证调用一定能发出，不影响既有功能。
        if (socketOk[targetPackage] != false) {
            try {
                Log.d(TAG, "⚡ LocalSocket 调用：$targetPackage/$capability")
                val resp = AidlAciLocalSocketTransport.call(targetPackage, req)
                lastSeenMap[targetPackage] = System.currentTimeMillis()
                socketOk[targetPackage] = true
                QuroAidlAciCallAudit.log(appContext, targetPackage, capability, resp.getErrorCode(), resp.isSuccess(), System.currentTimeMillis() - t0)
                if (!resp.isSuccess()) {
                    QuroAidlAciErrors.parse(resp.getErrorMessage())?.let { se ->
                        Log.w(TAG, "🔧 结构化错误 [${se.code}] ${se.layer}: ${se.suggestion}")
                    }
                    QuroAidlAciEvents.emit(QuroAidlAciEvents.EVT_CALL_FAILED, targetPackage, "$capability:${resp.getErrorCode()}")
                }
                resp.setCallId(callId)
                pushTrace(callId, targetPackage, capability, "localsocket", resp, t0)
                return resp
            } catch (e: Exception) {
                Log.w(TAG, "⚡ LocalSocket 调用失败，回落 AIDL：$targetPackage/$capability → ${e.message}")
                socketOk[targetPackage] = false
                // 继续走下方 AIDL 路径
            }
        }

        val holder = TimeoutResult()
        val t = Thread {
            holder.response = doCallWithRetry(service, req, targetPackage, capability)
            synchronized(holder) {
                holder.done = true
                (holder as java.lang.Object).notifyAll()
            }
        }
        t.start()

        synchronized(holder) {
            try {
                val start = System.currentTimeMillis()
                while (!holder.done && (System.currentTimeMillis() - start) < callTimeoutMs) {
                    (holder as java.lang.Object).wait(callTimeoutMs)
                }
            } catch (ignored: InterruptedException) {
            }
        }

        val resp = if (!holder.done) {
            Log.w(TAG, "⏰ 调用超时：$targetPackage/$capability")
            AidlAciResponse.error(504, "超时（>${callTimeoutMs}ms）")
        } else {
            lastSeenMap[targetPackage] = System.currentTimeMillis()
            Log.d(TAG, "call($targetPackage/$capability) → ${holder.response}")
            holder.response ?: AidlAciResponse.error(500, "内部错误：回调为空")
        }
        resp.setCallId(callId)
        QuroAidlAciCallAudit.log(
            appContext, targetPackage, capability, resp.getErrorCode(),
            resp.isSuccess(), System.currentTimeMillis() - t0
        )
        if (!resp.isSuccess()) {
            QuroAidlAciErrors.parse(resp.getErrorMessage())?.let { se ->
                Log.w(TAG, "🔧 结构化错误 [${se.code}] ${se.layer}: ${se.suggestion}")
            }
            QuroAidlAciEvents.emit(QuroAidlAciEvents.EVT_CALL_FAILED, targetPackage, "$capability:${resp.getErrorCode()}")
        }
        pushTrace(callId, targetPackage, capability, "aidl", resp, t0)
        return resp
    }

    /**
     * 带自愈的同步调用：首次调用途中若远端进程死亡（RemoteException），
     * 清掉可能已失效的引用并重绑一次后重试；仍失败则返回明确错误。
     */
    private fun doCallWithRetry(
        service: IAidlAciService,
        req: AidlAciRequest,
        targetPackage: String,
        capability: String
    ): AidlAciResponse {
        return try {
            service.call(req)
        } catch (e: RemoteException) {
            Log.w(TAG, "🔌 调用途中 RemoteException（远端可能已死），尝试重绑重试：$targetPackage/$capability")
            serviceMap.remove(targetPackage)   // 清掉可能已失效的引用
            val rebound = ensureBound(targetPackage)
            if (rebound == null) {
                QuroAidlAciEvents.emit(QuroAidlAciEvents.EVT_CALL_FAILED, targetPackage, "$capability: 重绑失败")
                AidlAciResponse.error(503, "服务在调用途中丢失且重绑失败：$targetPackage")
            } else {
                try {
                    rebound.call(req)
                } catch (e2: RemoteException) {
                    QuroAidlAciEvents.emit(QuroAidlAciEvents.EVT_CALL_FAILED, targetPackage, "$capability: ${e2.message}")
                    AidlAciResponse.error(500, "Remote（重绑重试后仍失败）：${e2.message}")
                }
            }
        }
    }

    // ═══════════════════════════════════
    //  ⑤ 异步调用
    // ═══════════════════════════════════
    fun callAsync(
        targetPackage: String,
        capability: String,
        params: android.os.Bundle,
        cb: Callback?
    ) {
        val service = ensureBound(targetPackage)
        if (service == null) {
            cb?.onResult(AidlAciResponse.error(503, "服务未绑定：$targetPackage。请先确认目标 App 已安装且声明了 ACI Service。"))
            return
        }
        val req = AidlAciRequest(capability, params)
        req.setCallerPkg(appContext.packageName)
        req.setCallId(UUID.randomUUID().toString())

        val callback = object : IAidlAciCallback.Stub() {
            override fun onResult(response: AidlAciResponse?) {
                lastSeenMap[targetPackage] = System.currentTimeMillis()
                Log.d(TAG, "异步结果：$response")
                cb?.onResult(response ?: AidlAciResponse.error(500, "回调为空"))
            }

            override fun onProgress(progress: Int, message: String?) {
                Log.d(TAG, "异步进度：$progress% - $message")
                cb?.onProgress(progress, message ?: "")
            }
        }
        try {
            service.callAsync(req, callback)
        } catch (e: RemoteException) {
            cb?.onResult(AidlAciResponse.error(500, "Remote: ${e.message}"))
        }
    }

    // ═══════════════════════════════════
    //  ⑥ 能力索引
    // ═══════════════════════════════════
    fun getCapabilityIndex(): Map<String, List<Capability>> = HashMap(capMap)

    /** 返回某包协商后的 ACI 协议版本（未协商返回 null）。 */
    fun getNegotiatedProtocol(packageName: String): String? = protocolMap[packageName]

    /**
     * 返回某包的 ACI Adapter（当前为 Binder 实现）；未绑定活体服务返回 null。
     * 这是 SDK 2.0「契约与运行时分离」的入口：调用方面向 [AidlAciAdapter] 编程，
     * 不感知底层是 Binder / WS / HTTP。
     */
    fun adapterFor(packageName: String): AidlAciAdapter? {
        val svc = serviceMap[packageName] ?: return null
        return BinderAciAdapter(
            pkg = packageName,
            service = svc,
            callFunc = { cap, params -> call(packageName, cap, params) },
            capsProvider = { capMap[packageName] ?: emptyList() },
            protocolProvider = { protocolMap[packageName] }
        )
    }

    /**
     * 生成可直接拼进 LLM System Prompt 的能力清单（仿 ACIManager.getCapabilityPrompt）。
     */
    fun getCapabilityPrompt(): String {
        val sb = StringBuilder()
        sb.append("你当前可以通过 ACI 控制第三方 App 的能力如下（用 aci_call 调用）。ACI 是本地无 Root 的 App 间 AIDL 框架，不依赖 Shizuku/dumpsys/ROOT 等任何系统提权：\n\n")
        if (capMap.isEmpty()) {
            sb.append("（尚未发现任何 ACI 能力。应用启动时会自动 discover；若已装第三方 ACI App 仍未出现，可重试或确认其已安装。）\n")
            return sb.toString()
        }
        for ((pkg, caps) in capMap) {
            val appName = nameMap[pkg] ?: pkg
            sb.append("【").append(appName).append("】(").append(pkg).append(")\n")
            for (c in caps) {
                sb.append("  - ").append(c.id).append(": ").append(c.description).append("\n")
                for (p in c.params) {
                    sb.append("      · ").append(p.name).append(" (").append(p.type).append(")")
                        .append(if (p.required) " [必填]" else "").append(" - ").append(p.description).append("\n")
                }
                if (c.isRequireUserConfirm) sb.append("      ⚠️ 需要用户确认\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    // ═══════════════════════════════════
    //  ⑦ 心跳
    // ═══════════════════════════════════
    fun healthCheck() {
        for ((pkg, svc) in serviceMap) {
            try {
                if (svc.ping()) {
                    lastSeenMap[pkg] = System.currentTimeMillis()
                } else {
                    serviceMap.remove(pkg)
                    ensureBound(pkg)   // 尝试重绑，保持温热
                }
            } catch (e: RemoteException) {
                serviceMap.remove(pkg)
                Log.w(TAG, "💀 服务已死：$pkg，尝试重绑")
                ensureBound(pkg)
            }
        }
    }

    // ═══════════════════════════════════
    //  ⑦-bis 健康看护（自愈调度）
    // ═══════════════════════════════════
    /** 启动后台健康看护：定时 ping 所有已绑定服务，死亡/无响应即自动重绑（自愈）。幂等。 */
    fun startHealthWatch(intervalMs: Long = 10_000L) {
        if (healthStarted) return
        healthStarted = true
        healthExecutor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "aci-health-watch") }
        healthExecutor?.scheduleWithFixedDelay({
            try { healthCheck() } catch (e: Throwable) { Log.w(TAG, "💓 健康看护异常：${e.message}") }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS)
        Log.i(TAG, "💓 健康看护已启动（间隔 ${intervalMs}ms）")
    }

    /** 停止健康看护。 */
    fun stopHealthWatch() {
        healthExecutor?.shutdownNow()
        healthExecutor = null
        healthStarted = false
        Log.i(TAG, "💓 健康看护已停止")
    }

    // ═══════════════════════════════════
    //  ⑧-bis 会话追踪（可观测性）
    // ═══════════════════════════════════
    /** 记录一次调用的关联信息，环形保留最近 TRACE_MAX 条，支撑 ACI 可观测性/可解释性。 */
    private fun pushTrace(callId: String, target: String, cap: String, transport: String, resp: AidlAciResponse, t0: Long) {
        val latency = System.currentTimeMillis() - t0
        traceQueue.add(AciCallTrace(System.currentTimeMillis(), callId.take(8), target, cap, transport, resp.getErrorCode(), resp.isSuccess(), latency))
        while (traceQueue.size > TRACE_MAX) traceQueue.poll()
    }

    /** 返回最近调用追踪（人类可读），供 ACI 管理中心「诊断」面板展示。 */
    fun getTrace(): List<String> = traceQueue.map { t ->
        val status = if (t.success) "✓" else "✗${t.code}"
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t.ts))
        "[$time] ${t.transport} ${t.target}/${t.capability} $status ${t.latencyMs}ms #${t.callId}"
    }

    /** 清空调用追踪缓冲。 */
    fun clearTrace() { traceQueue.clear() }

    /** 查询某包 LocalSocket 高速通道当前状态：true=可用 / false=已回落 AIDL / null=未探测。 */
    fun socketStatus(packageName: String): Boolean? = socketOk[packageName]

    // ═══════════════════════════════════
    //  ⑨-bis 语义点击脚手架（感知闭环·能力门控）
    // ═══════════════════════════════════
    /**
     * 语义点击：受控端需同时暴露 ui_snapshot（无障碍服务抓取 UI 树）与 tap（坐标点击）能力。
     * 命中时自动从 UI 树解析锚点坐标并调用 tap；未暴露语义能力则返回明确 412 引导，不静默失败。
     * 这是 元宝 ACI-Vision「视觉锚点」在受控端具备无障碍能力时的落地点。
     */
    fun clickText(targetPackage: String, text: String): AidlAciResponse {
        val caps = capMap[targetPackage] ?: emptyList()
        if (caps.none { it.id == "ui_snapshot" } || caps.none { it.id == "tap" }) {
            return AidlAciResponse.error(412,
                "受控端 $targetPackage 未暴露完整语义能力（需同时具备 ui_snapshot + tap）。" +
                "语义点击需在受控 App 启用无障碍服务并声明 ui_snapshot；当前可用坐标点击 tap 能力代替。")
        }
        val snap = call(targetPackage, "ui_snapshot", android.os.Bundle())
        if (!snap.isSuccess()) return snap
        val nodes = snap.getResult()?.getStringArrayList("nodes") ?: return AidlAciResponse.error(500, "ui_snapshot 未返回 nodes")
        for (n in nodes) {
            val p = n.split("|")
            if (p.firstOrNull()?.contains(text) == true) {
                val b = p.getOrNull(2)?.split(",")?.mapNotNull { it.toIntOrNull() }
                if (b != null && b.size == 4) {
                    val cx = (b[0] + b[2]) / 2
                    val cy = (b[1] + b[3]) / 2
                    return call(targetPackage, "tap", android.os.Bundle().apply { putInt("x", cx); putInt("y", cy) })
                }
            }
        }
        return AidlAciResponse.error(404, "未在 UI 树中找到文本包含「$text」的节点")
    }

    /** 按 resource-id 语义点击（依赖 ui_snapshot + tap）。 */
    fun clickResourceId(targetPackage: String, resId: String): AidlAciResponse {
        val caps = capMap[targetPackage] ?: emptyList()
        if (caps.none { it.id == "ui_snapshot" } || caps.none { it.id == "tap" }) {
            return AidlAciResponse.error(412,
                "受控端 $targetPackage 未暴露完整语义能力（需同时具备 ui_snapshot + tap）。")
        }
        val snap = call(targetPackage, "ui_snapshot", android.os.Bundle())
        if (!snap.isSuccess()) return snap
        val nodes = snap.getResult()?.getStringArrayList("nodes") ?: return AidlAciResponse.error(500, "ui_snapshot 未返回 nodes")
        for (n in nodes) {
            val p = n.split("|")
            if (p.getOrNull(1) == resId) {
                val b = p.getOrNull(2)?.split(",")?.mapNotNull { it.toIntOrNull() }
                if (b != null && b.size == 4) {
                    val cx = (b[0] + b[2]) / 2
                    val cy = (b[1] + b[3]) / 2
                    return call(targetPackage, "tap", android.os.Bundle().apply { putInt("x", cx); putInt("y", cy) })
                }
            }
        }
        return AidlAciResponse.error(404, "未在 UI 树中找到 resource-id「$resId」的节点")
    }

    // ═══════════════════════════════════
    //  ⑩ UI 状态查询（供 ACI 管理中心界面）
    // ═══════════════════════════════════
    /** 某包当前是否已绑定活体 ACI Service。 */
    fun isServiceBound(packageName: String): Boolean = serviceMap[packageName] != null

    /** 当前所有已发现 App 的状态快照（含绑定态 + 能力清单），供 UI 直接展示。 */
    fun getAppStatuses(): List<AciAppStatus> {
        val pkgs = (nameMap.keys + serviceMap.keys + capMap.keys).toSet()
        return pkgs.map { pkg ->
            AciAppStatus(
                packageName = pkg,
                appName = nameMap[pkg] ?: pkg,
                serviceClass = classMap[pkg] ?: "",
                bound = serviceMap[pkg] != null,
                capabilities = capMap[pkg] ?: emptyList(),
                lastSeen = lastSeenMap[pkg] ?: 0L
            )
        }.sortedBy { it.appName }
    }

    /** 重新扫描所有已安装 ACI 服务并触发绑定，返回最新状态快照。 */
    fun refresh(): List<AciAppStatus> {
        discover()
        return getAppStatuses()
    }

    /**
     * 手动注册：按包名直接查询其声明的 ACI Service 并绑定。
     * 成功返回 true；未找到返回 false（该包未安装或未声明 ACI Service）。
     */
    fun registerPackage(packageName: String): Boolean {
        if (classMap[packageName] != null) {
            scheduleRebind(packageName)   // 已发现过：直接触发重绑
            return true
        }
        val pm = appContext.packageManager
        val intent = Intent(ACI_ACTION)
        val services = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        for (info in services) {
            val si = info.serviceInfo ?: continue
            if (si.packageName == packageName) {
                nameMap[packageName] = si.loadLabel(pm).toString()
                classMap[packageName] = si.name
                doBind(packageName, si.name)
                Log.i(TAG, "✅ 手动注册成功：$packageName/${si.name}")
                return true
            }
        }
        Log.w(TAG, "⚠️ 手动注册失败：未找到 $packageName 的 ACI Service")
        return false
    }

    /** 强制对指定包重绑（供 UI「重绑」按钮；类名缓存缺失则返回 false）。 */
    fun rebind(packageName: String): Boolean {
        val cls = classMap[packageName] ?: return false
        bindWithWake(packageName, cls)   // 改裸 doBind → 带唤醒，重绑按钮也能拉起停止态 App
        return true
    }

    // ═══════════════════════════════════
    //  ⑪ 按名搜索 + 手动启动（ACI 管理中心：搜软件名 → 启动并注册）
    // ═══════════════════════════════════

    /** 已安装应用（包名 + 显示名），供「搜软件名」结果展示。 */
    data class InstalledApp(val packageName: String, val appName: String)

    /**
     * 按名称/包名模糊搜索本机已安装应用（不限于 ACI App），用于「搜软件名 → 手动启动并注册」。
     * @param keyword 应用名或包名片段（大小写不敏感）。返回最多 50 条匹配，按显示名排序。
     */
    fun searchInstalledApps(keyword: String): List<InstalledApp> {
        val kw = keyword.trim().lowercase()
        if (kw.isBlank()) return emptyList()
        val pm = appContext.packageManager
        return runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .mapNotNull { ai ->
                    val pkg = ai.packageName
                    val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg)
                    if (pkg.lowercase().contains(kw) || label.lowercase().contains(kw))
                        InstalledApp(pkg, label) else null
                }
                .sortedBy { it.appName }
                .take(50)
        }.getOrDefault(emptyList())
    }

    /** 启动指定包名的主 Activity（即「手动启动」）。失败返回 false。 */
    fun launchApp(packageName: String): Boolean {
        val pm = appContext.packageManager
        val intent = runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull() ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            appContext.startActivity(intent)
            Log.i(TAG, "🚀 已启动：$packageName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 启动 $packageName 失败：${e.message}")
            false
        }
    }

    // ═══════════════════════════════════
    //  ⑧ 配置
    // ═══════════════════════════════════
    fun setCallTimeout(timeoutMs: Long) { callTimeoutMs = timeoutMs }

    // ═══════════════════════════════════
    //  ⑨ 释放
    // ═══════════════════════════════════
    fun shutdown() {
        for ((pkg, conn) in connMap) {
            try { appContext.unbindService(conn) } catch (ignored: Exception) {}
            deathRecipients[pkg]?.let { recv -> serviceMap[pkg]?.asBinder()?.unlinkToDeath(recv, 0) }
        }
        for (pkg in capMap.keys) QuroAidlAciRegistry.clearPackage(pkg)
        connMap.clear()
        serviceMap.clear()
        capMap.clear()
        deathRecipients.clear()
        lastSeenMap.clear()
    }

    // ──────────────────────────────
    // 回调接口
    // ──────────────────────────────
    interface Callback {
        fun onResult(response: AidlAciResponse)
        fun onProgress(progress: Int, message: String) {}
    }

    // ──────────────────────────────
    // 内部类
    // ──────────────────────────────
    private class TimeoutResult {
        @Volatile var done = false
        @Volatile var response: AidlAciResponse? = null
    }

    data class DiscoveredApp(
        val packageName: String,
        val serviceClass: String,
        val appName: String
    )

    /** ACI 管理中心界面用的 App 状态快照。 */
    data class AciAppStatus(
        val packageName: String,
        val appName: String,
        val serviceClass: String,
        val bound: Boolean,
        val capabilities: List<Capability>,
        val lastSeen: Long
    )

    /** 单次 ACI 调用的关联追踪记录（可观测性）。 */
    data class AciCallTrace(
        val ts: Long,
        val callId: String,
        val target: String,
        val capability: String,
        val transport: String,   // localsocket / aidl / none
        val code: Int,
        val success: Boolean,
        val latencyMs: Long
    )
}
