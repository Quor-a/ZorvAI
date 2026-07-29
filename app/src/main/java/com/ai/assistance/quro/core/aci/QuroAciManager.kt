package com.ai.assistance.quro.core.aci

import ai.aci.core.ACIRequest
import ai.aci.core.ACIResponse
import ai.aci.core.Capability
import ai.aci.core.IACICallback
import ai.aci.core.IACIService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * QuroAciManager —— Zorv AI 作为 ACI 控制方（AI 中枢）的核心管理器。
 * 端口自 aci-aihub 的 ACIManager（Java），改写为 Kotlin 单例。
 *
 * ACI（Agent Capability Interface）协议层由 aci-core AAR 提供
 * （ai.aci.core.*：IACIService / IACICallback AIDL、ACIRequest / ACIResponse / Capability）。
 * 本类负责：① 发现已安装 ACI 服务 → ② 绑定 → ③ 拉取能力 → ④ 同步/异步调用 →
 * ⑤ 生成能力清单（拼进系统提示词，让 LLM 知道能调什么）。
 *
 * 安全：调用前自动 setCallerPkg(本包名)，被调用方 BaseACIService.onCheckPermission 据此鉴权；
 * 被调用方用 Binder.getCallingUid() 反查的真实包名覆盖自报 callerPkg（防伪造）。
 */
class QuroAciManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "QuroAciManager"
        const val ACI_ACTION = "ai.aci.core.ACTION_BIND"
        /** 唤醒被控 App 的广播 action（与 aci-core 的 ACIWakeReceiver.ACTION_WAKE 对齐） */
        const val ACI_WAKE_ACTION = "ai.aci.core.ACTION_WAKE"

        @Volatile
        private var sInstance: QuroAciManager? = null

        fun init(context: Context) {
            if (sInstance == null) {
                synchronized(QuroAciManager::class.java) {
                    if (sInstance == null) {
                        sInstance = QuroAciManager(context.applicationContext)
                    }
                }
            }
        }

        fun getInstance(): QuroAciManager =
            sInstance ?: throw IllegalStateException("QuroAciManager 未初始化，请先调用 init(context)")
    }

    private val serviceMap = ConcurrentHashMap<String, IACIService>()
    private val connMap = ConcurrentHashMap<String, ServiceConnection>()
    private val capMap = ConcurrentHashMap<String, List<Capability>>()
    private val nameMap = ConcurrentHashMap<String, String>()
    private val classMap = ConcurrentHashMap<String, String>()   // pkg → service class（用于断线后重绑）
    private val lastSeenMap = ConcurrentHashMap<String, Long>()
    /** 每个包的 Binder 死亡监听（DeathRecipient），用于远端进程死亡时即时触发重绑。 */
    private val deathRecipients = ConcurrentHashMap<String, android.os.IBinder.DeathRecipient>()

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
            doBind(pkg, cls)
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
                val service = IACIService.Stub.asInterface(binder)
                serviceMap[packageName] = service
                lastSeenMap[packageName] = System.currentTimeMillis()
                Log.i(TAG, "✅ 已绑定：$packageName")
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
                Log.w(TAG, "⚠️ 断开：$packageName（已保留能力缓存，待自动重绑）")
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
    private fun scheduleRebind(pkg: String) {
        val cls = classMap[pkg] ?: return
        Thread {
            try { Thread.sleep(800) } catch (ignored: InterruptedException) {}
            if (serviceMap[pkg] == null) {
                Log.i(TAG, "🔄 重绑：$pkg")
                doBind(pkg, cls)
            }
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
     * 获取目标包名的活体 IACIService。
     * 若当前未绑定但曾发现过该 App → 同步（最多 3s）重绑后返回；
     * 若从未发现过 → 先重新 discover() 再重绑。
     * 这解决了「aci_list 能看到能力、但 aci_call 报 503 服务未绑定」的绑定生命周期问题。
     */
    private fun ensureBound(pkg: String): IACIService? {
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

        Log.e(TAG, "❌ $pkg 唤醒后仍无法绑定")
        return null
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
    private fun fetchCapabilities(pkg: String, service: IACIService) {
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
                Log.i(TAG, "📋 $pkg → ${list.size} 项能力")
                for (c in list) Log.d(TAG, "    • ${c.id}: ${c.description}")
            } catch (e: Exception) {
                Log.e(TAG, "拉取 $pkg 能力失败", e)
            }
        }.start()
    }

    // ═══════════════════════════════════
    //  ④ 同步调用（带超时）
    // ═══════════════════════════════════
    fun call(targetPackage: String, capability: String, params: android.os.Bundle): ACIResponse {
        val service = ensureBound(targetPackage)
        if (service == null) {
            return ACIResponse.error(503, "服务未绑定：$targetPackage。请先确认目标 App 已安装且声明了 ACI Service；可重试 aci_list 触发重新发现。")
        }
        val req = ACIRequest(capability, params)
        req.setCallerPkg(appContext.packageName)

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

        if (!holder.done) {
            Log.w(TAG, "⏰ 调用超时：$targetPackage/$capability")
            return ACIResponse.error(504, "超时（>${callTimeoutMs}ms）")
        }
        lastSeenMap[targetPackage] = System.currentTimeMillis()
        Log.d(TAG, "call($targetPackage/$capability) → ${holder.response}")
        return holder.response ?: ACIResponse.error(500, "内部错误：回调为空")
    }

    /**
     * 带自愈的同步调用：首次调用途中若远端进程死亡（RemoteException），
     * 清掉可能已失效的引用并重绑一次后重试；仍失败则返回明确错误。
     */
    private fun doCallWithRetry(
        service: IACIService,
        req: ACIRequest,
        targetPackage: String,
        capability: String
    ): ACIResponse {
        return try {
            service.call(req)
        } catch (e: RemoteException) {
            Log.w(TAG, "🔌 调用途中 RemoteException（远端可能已死），尝试重绑重试：$targetPackage/$capability")
            serviceMap.remove(targetPackage)   // 清掉可能已失效的引用
            val rebound = ensureBound(targetPackage)
            if (rebound == null) {
                ACIResponse.error(503, "服务在调用途中丢失且重绑失败：$targetPackage")
            } else {
                try {
                    rebound.call(req)
                } catch (e2: RemoteException) {
                    ACIResponse.error(500, "Remote（重绑重试后仍失败）：${e2.message}")
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
            cb?.onResult(ACIResponse.error(503, "服务未绑定：$targetPackage。请先确认目标 App 已安装且声明了 ACI Service。"))
            return
        }
        val req = ACIRequest(capability, params)
        req.setCallerPkg(appContext.packageName)

        val callback = object : IACICallback.Stub() {
            override fun onResult(response: ACIResponse?) {
                lastSeenMap[targetPackage] = System.currentTimeMillis()
                Log.d(TAG, "异步结果：$response")
                cb?.onResult(response ?: ACIResponse.error(500, "回调为空"))
            }

            override fun onProgress(progress: Int, message: String?) {
                Log.d(TAG, "异步进度：$progress% - $message")
                cb?.onProgress(progress, message ?: "")
            }
        }
        try {
            service.callAsync(req, callback)
        } catch (e: RemoteException) {
            cb?.onResult(ACIResponse.error(500, "Remote: ${e.message}"))
        }
    }

    // ═══════════════════════════════════
    //  ⑥ 能力索引
    // ═══════════════════════════════════
    fun getCapabilityIndex(): Map<String, List<Capability>> = HashMap(capMap)

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
        doBind(packageName, cls)
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
        fun onResult(response: ACIResponse)
        fun onProgress(progress: Int, message: String) {}
    }

    // ──────────────────────────────
    // 内部类
    // ──────────────────────────────
    private class TimeoutResult {
        @Volatile var done = false
        @Volatile var response: ACIResponse? = null
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
}
