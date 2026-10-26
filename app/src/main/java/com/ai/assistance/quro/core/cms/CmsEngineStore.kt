package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroTerminalBridge
import com.ai.assistance.quro.util.QuroDiag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * CMS v2 CMS引擎状态系统（原创运行时 · 引擎态持久化 + 实时刷新）。
 *
 * 记录CMS引擎是否部署就绪、健康、拉起了哪些共享服务，供 UI「CMS引擎状态」卡与 AI 经
 * cms_engine_status 回查。跨重启持久化到 /root/cms/_engine/engine-state.json。
 */
object CmsEngineStore {

    data class EngineSnapshot(
        val engineVersion: String = "",
        val ready: Boolean = false,
        val health: Boolean = false,
        /** 健康明细（缺失工具清单 / 自愈记录）；UI 展示，避免「健康检查异常」零诊断。 */
        val healthDetail: String = "",
        val services: List<String> = emptyList(),
        val lastDeployAt: Long = 0L,
        val lastError: String = "",
        val deploying: Boolean = false,
        val deployStartAt: Long = 0L,
        val deployStep: String = "",
        val logs: List<String> = emptyList(),
    )

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    val snapshot: StateFlow<EngineSnapshot> = _snapshot

    @Volatile private var initialized = false
    private lateinit var appCtx: Context
    private val logBuf = mutableListOf<String>()

    /**
     * 引擎共享服务端口表（svcId → port），持久化到 engine-state.json。
     * 供 [probeHealth] 在运行时按端口探测，把 services 刷新为「真实仍在监听」的服务，
     * 避免部署快照里登记了 cms-static、而 8080 早已随 proot 退出死掉的状态失真。
     */
    private val enginePorts = mutableMapOf<String, Int>()

    /** 登记引擎共享服务端口（部署时调用），并即时持久化。 */
    fun registerEngineServices(svcs: List<EngineSvc>) {
        enginePorts.clear()
        svcs.filter { it.enabled && it.port > 0 }.forEach { enginePorts[it.id] = it.port }
        persist()
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appCtx = context.applicationContext
        val f = stateFile()
        if (f.exists()) {
            runCatching {
                val o = JSONObject(f.readText())
                val svcs = o.optString("services", "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                // 服务端口表（跨重启恢复，供运行时端口探测刷新 services 状态）
                val portsObj = o.optJSONObject("servicePorts")
                if (portsObj != null) {
                    portsObj.keys().forEach { k ->
                        val p = portsObj.optInt(k, 0)
                        if (p > 0) enginePorts[k] = p
                    }
                }
                _snapshot.value = EngineSnapshot(
                    engineVersion = o.optString("engineVersion", ""),
                    ready = o.optBoolean("ready", false),
                    health = o.optBoolean("health", false),
                    healthDetail = o.optString("healthDetail", ""),
                    services = svcs,
                    lastDeployAt = o.optLong("lastDeployAt", 0L),
                    lastError = o.optString("lastError", ""),
                )
            }
        }
        checkStale() // 进入即检查是否有卡死的部署态（#911 看门狗）
    }

    private fun stateFile(): File =
        File(QuroLinuxEnv.homePath(appCtx), "cms/_engine/engine-state.json").also { it.parentFile?.mkdirs() }

    fun markDeployStart(step: String) {
        _snapshot.value = _snapshot.value.copy(deploying = true, deployStartAt = System.currentTimeMillis(), deployStep = step, lastError = "")
        pushLog("▶ $step")
    }

    /**
     * 看门狗（#911）：若 deploying 已持续超过阈值（默认 11 分钟，覆盖 bootstrap+provision 最坏 ~10.3 分钟），
     * 说明部署极可能已 hang（如终端网络中断、proot 卡死），主动复位为失败，
     * 避免 UI 永久卡在「部署中」。在 init() 与 UI 进入 CMS 页时调用。
     */
    fun checkStale(thresholdMs: Long = 11 * 60_000L) {
        val s = _snapshot.value
        if (s.deploying && s.deployStartAt != 0L && System.currentTimeMillis() - s.deployStartAt > thresholdMs) {
            markFailed("⏱ 引擎部署超时（看门狗自动复位），请检查终端/Linux 环境后重试")
        }
    }

    fun markDeployStep(step: String, pct: Int = 0) {
        _snapshot.value = _snapshot.value.copy(deployStep = step)
        pushLog("• $step")
    }

    fun markDeployed(version: String, services: List<String>, health: Boolean, healthDetail: String = "") {
        _snapshot.value = _snapshot.value.copy(
            engineVersion = version, ready = true, health = health, healthDetail = healthDetail, services = services,
            lastDeployAt = System.currentTimeMillis(), deploying = false, deployStep = "部署完成",
        )
        pushLog("✅ CMS引擎部署完成 v$version（健康=$health${if (healthDetail.isNotBlank()) "，$healthDetail" else ""}）")
        persist()
    }

    fun markFailed(msg: String) {
        _snapshot.value = _snapshot.value.copy(deploying = false, ready = false, lastError = msg)
        pushLog(msg)
        persist()
    }

    fun markHealth(ok: Boolean, detail: String = "") {
        _snapshot.value = _snapshot.value.copy(health = ok, healthDetail = detail)
        persist()
    }

    fun appendLog(msg: String) {
        pushLog(msg)
    }

    private fun pushLog(msg: String) {
        // 🔎 诊断闭环：原 pushLog 只写内存 logBuf，CMS bootstrap 报错从不进
        // Download/QuroAI_logs，用户「找不到日志」。现在同步落 QuroDiag，
        // 设备侧无需 adb 即可取到真机失败原因（bootstrap exit code / 输出截取）。
        QuroDiag.log("CMS", msg)
        synchronized(logBuf) {
            logBuf.add(msg)
            while (logBuf.size > 50) logBuf.removeAt(0)
        }
        _snapshot.value = _snapshot.value.copy(logs = logBuf.toList())
    }

    private fun persist() {
        if (!::appCtx.isInitialized) return
        runCatching {
            val s = _snapshot.value
            val o = JSONObject().apply {
                put("engineVersion", s.engineVersion)
                put("ready", s.ready)
                put("health", s.health)
                put("healthDetail", s.healthDetail)
                put("services", s.services.joinToString(","))
                put("servicePorts", JSONObject().apply { enginePorts.forEach { (k, v) -> put(k, v) } })
                put("lastDeployAt", s.lastDeployAt)
                put("lastError", s.lastError)
            }
            stateFile().writeText(o.toString())
        }
    }

    /**
     * 重查引擎七件套（与 bootstrap verify 同判据）。返回 (是否齐全, 缺失清单字符串)。
     * 供 [probeHealth] 自愈判定与部署器健康检查共用，避免两处判据漂移。
     */
    fun probeCoreTools(context: Context): Pair<Boolean, String> {
        val (_, tout) = QuroTerminalBridge.run(
            context,
            "miss=; for t in python3 node gcc make cmake git curl; do command -v \$t >/dev/null 2>&1 || miss=\"\$miss \$t\"; done; echo \"MISSING:\$miss\"",
            timeoutMs = 15_000,
        )
        val missing = tout.substringAfter("MISSING:", "").trim()
        return (missing.isEmpty()) to missing
    }

    /**
     * 主动探测引擎是否在线（供 UI 进入时刷新健康态 + 服务列表）。
     * 除检查 .engine.ready 标记外，还按 [enginePorts] 做运行时端口探测，
     * 把 services 刷新为真实仍在监听的服务 id 列表（服务可能随 proot 退出/重启而挂）。
     *
     * 标记缺失时不再直接判死：重查工具链，全就绪则**补写 .engine.ready 自愈**
     * （修复双 rootfs 合一前部署失败遗留的化石状态），否则 health=false + 缺失清单明细，
     * 让 UI 能说清「到底缺什么」，而不是干巴巴一个「健康检查异常」。
     */
    fun probeHealth(context: Context) {
        init(context)
        val st = QuroLinuxEnv.probeLenient(context)
        if (!st.available) { markHealth(false, "终端环境(proot/Ubuntu)未就绪：${st.reason}"); return }
        val (c, _) = QuroTerminalBridge.run(context, "[ -f ${CmsEngineDeployer.engineGuestDir()}/.engine.ready ]", timeoutMs = 10_000)
        var healthy = c == 0
        var detail = ""
        if (!healthy) {
            val (ok, missing) = probeCoreTools(context)
            if (ok) {
                val (wc, _) = QuroTerminalBridge.run(
                    context,
                    "mkdir -p ${CmsEngineDeployer.engineGuestDir()} && touch ${CmsEngineDeployer.engineGuestDir()}/.engine.ready",
                    timeoutMs = 10_000,
                )
                if (wc == 0) {
                    healthy = true
                    detail = "就绪标记缺失但核心工具齐全，已自动补写 .engine.ready（自愈）"
                    QuroDiag.log("CMS", "probeHealth 自愈：核心工具齐全，补写 .engine.ready")
                } else {
                    detail = "核心工具齐全但补写就绪标记失败（rootfs 目录不可写？）"
                }
            } else {
                detail = "缺少核心工具：$missing"
            }
        }
        val alive = probePorts(context)
        _snapshot.value = _snapshot.value.copy(health = healthy, healthDetail = detail, services = alive)
        persist()
    }

    /**
     * 运行时端口探测：轮询已在 [enginePorts] 登记的服务端口，返回仍在监听的 id 列表。
     * 未登记端口（如从未部署过引擎）时原样返回当前快照，避免误清空。
     */
    private fun probePorts(context: Context): List<String> {
        if (enginePorts.isEmpty()) return _snapshot.value.services
        val alive = mutableListOf<String>()
        enginePorts.forEach { (id, port) ->
            val probe = "python3 -c \"import socket,sys; s=socket.socket(); s.settimeout(0.5); sys.exit(0 if s.connect_ex(('127.0.0.1',$port))==0 else 1)\""
            val (pc, _) = QuroTerminalBridge.run(context, probe, timeoutMs = 8_000)
            if (pc == 0) alive.add(id)
        }
        return alive
    }
}
