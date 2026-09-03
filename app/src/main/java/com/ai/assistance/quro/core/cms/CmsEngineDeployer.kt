package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroTerminalBridge
import com.ai.assistance.quro.util.QuroDiag
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 把 Windows CRLF 统一为 LF，防止写入 proot/Ubuntu 的 shell 脚本出现「illegal option -」等诡异解析错误。 */
private fun String.normalizeLineEndings(): String = this.replace("\r\n", "\n").replace("\r", "\n")

/**
 * CMS v2 CMS引擎部署器（一级部署系统）。
 *
 * 把 [CmsEnginePackage] 推到 proot/Ubuntu 的 /root/cms/_engine（区别于模块 /root/cms/<moduleId>）：
 * ① 校验完整性(sha256, P0) ② 写 cms-engine.json + bootstrap.sh + provision/ + services/ ③ 跑 bootstrap
 * ④ 跑 provisioner ⑤ 拉起共享服务 ⑥ 写就绪标记 + [CmsEngineStore]。
 *
 * 注：引擎不再装配开发环境栈（NODE/PYTHON/JAVA/RUST/GO）——开发环境部署由终端侧「环境配置」
 * 统一负责，避免两套重复装配。
 *
 * D1 约束：终端后端唯一化 = proot；环境未就绪**直接拒绝**，绝不回退 /system/bin/sh 玩具通道。
 */
object CmsEngineDeployer {

    /** 宿主侧引擎目录（proot 内 = /root/cms/_engine）。 */
    fun engineHostDir(context: Context): File =
        File(QuroLinuxEnv.homePath(context), "cms/_engine").also { it.mkdirs() }

    /** proot 内（guest）引擎路径（无 context，供状态系统探活复用）。 */
    fun engineGuestDir(): String = "/root/cms/_engine"

    /** 导出CMS引擎为 JSON（用于「导出CMS引擎」/ 工具箱 export 动作）。 */
    fun exportPackage(pkg: CmsEnginePackage): String = pkg.toJson()

    /** 解析导入的CMS引擎 JSON（用于「导入CMS引擎」）。异常由调用方捕获。 */
    fun importPackage(json: String): CmsEnginePackage = CmsEnginePackage.fromJson(json)

    // ---------- 共享服务常驻运行时 ----------

    /** 已拉起的引擎共享服务进程句柄（svcId → 常驻 proot 进程）。 */
    private val engineServices = ConcurrentHashMap<String, Process>()

    /**
     * 以常驻模型拉起一个引擎共享服务（与 [CmsResidentRuntime] 同一机制）。
     *
     * 旧实现用 `setsid sh ... &` 在一次性 proot 调用里后台启动：proot 进程退出后，
     * 作为其子进程的服务失去 syscall 翻译层随之被回收 —— 部署时端口探测通过、
     * 部署完成后服务立刻死掉（表现即「引擎装好了但 8080 打不开」）。
     * 现改为 proot 本身常驻（不 waitFor），服务以 `exec` 顶替 sh 成为 proot 的直接子进程。
     */
    private fun startEngineService(context: Context, svc: EngineSvc): Boolean {
        val st = QuroLinuxEnv.probeLenient(context)
        if (!st.available) return false
        // exec 让服务直接顶替 sh，成为 proot 的直接子进程；proot 常驻，服务即常驻。
        val command = "cd ${engineGuestDir()} && exec ${svc.command}"
        val proc = QuroLinuxEnv.spawnPersistent(context, command, emptyMap()) ?: return false

        // 排空 stdout/stderr，避免管道缓冲写满后阻塞服务进程
        val log = File(engineHostDir(context), "services/${svc.id}.log")
        log.parentFile?.mkdirs()
        val reader = proc.inputStream.bufferedReader()
        val drain = Thread {
            try { reader.use { r -> r.forEachLine { line -> runCatching { log.appendText(line + "\n") } } } } catch (_: Throwable) {}
        }
        drain.isDaemon = true
        drain.start()

        engineServices[svc.id] = proc
        return runCatching { Thread.sleep(300); proc.isAlive }.getOrDefault(true)
    }

    /** 停止并注销一个引擎共享服务（先 TERM 后 KILL）。 */
    fun stopEngineService(svcId: String): Boolean {
        val p = engineServices.remove(svcId) ?: return false
        runCatching { p.destroyForcibly() }
        return true
    }

    /** 引擎共享服务的常驻进程是否仍存活。 */
    fun isEngineServiceAlive(svcId: String): Boolean = engineServices[svcId]?.isAlive ?: false

    /** App 退出时清理所有引擎共享服务进程。 */
    fun stopAllEngineServices() {
        engineServices.values.forEach { runCatching { it.destroyForcibly() } }
        engineServices.clear()
    }

    /**
     * 一键部署CMS引擎：bootstrap 基础运行时 + 引擎级环境栈 + 拉起共享服务。
     * 实时写入 [CmsEngineStore]（进度/终态/日志），让 UI「引擎状态」卡与 AI 经 cms_engine_status 回查。
     */
    fun deployEngine(context: Context, pkg: CmsEnginePackage): String {
        CmsEngineStore.init(context)
        CmsEngineStore.markDeployStart("准备部署CMS引擎 ${pkg.engineId}")
        return try {
            deployEngineInner(context, pkg)
        } catch (e: Throwable) {
            // 任何未预期异常都必须复位 deploying，否则 UI 会永远卡在「部署中」（#911 根因）
            val msg = "⛔ CMS引擎部署异常中断：${e.message ?: e.javaClass.simpleName}（已复位部署状态，可重试）"
            CmsEngineStore.markFailed(msg)
            msg
        }
    }

    private fun deployEngineInner(context: Context, pkg: CmsEnginePackage): String {
        // D1：终端后端唯一化 = proot；环境未就绪时**自动拉起**终端安装（下载 rootfs/解压/apt-get update/装 bash），
        // 安装成功即继续部署，失败才明确报错。此前此处直接拒绝，导致用户必须先手动去终端页点安装。
        var st = QuroLinuxEnv.probeLenient(context)
        if (!st.available) {
            CmsEngineStore.markDeployStep("自动安装终端环境(proot/Ubuntu)", 10)
            st = QuroLinuxEnv.ensureInstalledBlocking(context)
            if (!st.available) {
                val msg = "⛔ 终端环境(proot/Ubuntu)自动安装失败：${st.reason}。请先在「终端」页安装 Linux 环境后再部署CMS引擎。"
                CmsEngineStore.markFailed(msg)
                return msg
            }
        }
        // P0：完整性校验（sha256 缺失/不匹配 → 拒部署）。
        if (!pkg.verifyIntegrity()) {
            val msg = "⛔ CMS引擎完整性校验失败（sha256 不匹配或缺失），拒绝部署，疑似被篡改/损坏。"
            CmsEngineStore.markFailed(msg)
            return msg
        }

        val dir = engineHostDir(context)
        dir.mkdirs()
        File(dir, "cms-engine.json").writeText(pkg.toJson())

        val boot = File(dir, "bootstrap.sh")
        // 关键修复：Windows 工作区常见 CRLF，直接写入 Ubuntu 会让 /bin/sh 把 \r 当参数，
        // 出现「set: illegal option -」「apt-get install 包名带 \r」等诡异常；写入前强转 LF。
        boot.writeText(pkg.bootstrapContent.normalizeLineEndings())
        boot.setExecutable(true)

        val provDir = File(dir, "provision").also { it.mkdirs() }
        val prov = File(provDir, "provision.sh")
        prov.writeText(
            (if (pkg.provisionerContent.isBlank()) "#!/bin/sh\necho '[quro-engine] no provisioner'\n"
            else pkg.provisionerContent).normalizeLineEndings(),
        )
        prov.setExecutable(true)

        val svcDir = File(dir, "services").also { it.mkdirs() }
        svcDir.listFiles()?.forEach { if (it.name.endsWith(".sh")) it.delete() }
        pkg.sharedServices.forEach { svc ->
            if (!svc.enabled || svc.command.isBlank()) return@forEach
            val f = File(svcDir, "${svc.id}.sh")
            f.writeText(
                "#!/bin/sh\n# Quro Engine shared service: ${svc.name} (port ${svc.port})\n${svc.command}\n"
                    .normalizeLineEndings()
            )
            f.setExecutable(true)
        }

        val sb = StringBuilder()
        sb.appendLine("✅ CMS引擎文件已写入 ${engineGuestDir()}")

        // 跑 bootstrap（安装 python3/nodejs 基础运行时）
        CmsEngineStore.markDeployStep("执行引擎 bootstrap（安装基础运行时）", 40)
        val (bc, bout) = QuroTerminalBridge.run(context, "sh ${engineGuestDir()}/bootstrap.sh", timeoutMs = 300_000)
        if (bc != 0) {
            // 🔎 诊断闭环：把 bootstrap 完整输出落 QuroDiag，设备侧无需 adb 即可取到
            // 真实失败原因（如 CRLF 导致的「set: illegal option -」、apk 源 404 等）。
            QuroDiag.log("CMS", "⛔ 引擎 bootstrap 失败(exit $bc) 完整输出:\n$bout")
            val msg = "⛔ 引擎 bootstrap 失败(exit $bc): ${bout.take(300)}"
            CmsEngineStore.markFailed(msg)
            return sb.appendLine(msg).toString()
        }
        sb.appendLine("✅ 引擎 bootstrap 完成")

        // provisioner（非致命）
        CmsEngineStore.markDeployStep("装配引擎级环境", 70)
        val (pc, pout) = QuroTerminalBridge.run(context, "sh ${engineGuestDir()}/provision/provision.sh", timeoutMs = 300_000)
        if (pc != 0) sb.appendLine("⚠️ 引擎 provisioner 异常(exit $pc): ${pout.take(200)}（非致命，继续）")
        else sb.appendLine("✅ 引擎 provisioner 完成")

        // 拉起共享服务（常驻模型 + 端口就绪探测）
        // 旧逻辑把服务放进一次性 proot 调用里后台化（setsid ... &）：proot 调用结束即退出，
        // 作为 proot 子进程的服务失去 syscall 翻译层被回收 —— 部署时端口探测刚过、部署完服务就死，
        // 于是引擎快照长期显示 services=[cms-static] 而 8080 实际打不开。
        // 现改为 proot 常驻（不 waitFor），服务以 exec 顶替 sh 成为 proot 直接子进程，proot 活着服务就活着。
        val launched = mutableListOf<String>()
        pkg.sharedServices.filter { it.enabled && it.command.isNotBlank() }.forEach { svc ->
            val started = startEngineService(context, svc)
            if (!started) {
                CmsEngineStore.appendLog("引擎服务 ${svc.name} 常驻启动失败（proot 未就绪或进程创建失败）")
                return@forEach
            }
            // 端口就绪探测：单次 proot 调用内用 python 轮询（40 次×0.2s），可连接即成功，不再依赖退出码。
            val probe = """
                python3 -c "import socket,time,sys
                port=${svc.port}
                ok=False
                for _ in range(40):
                    s=socket.socket(); s.settimeout(0.3)
                    if s.connect_ex(('127.0.0.1',port))==0:
                        ok=True; break
                    s.close(); time.sleep(0.2)
                sys.exit(0 if ok else 1)"
            """.trimIndent()
            val (pc, _) = QuroTerminalBridge.run(context, probe, timeoutMs = 15_000)
            if (pc == 0) {
                launched.add(svc.id)
                CmsEngineStore.appendLog("引擎服务 ${svc.name} 已拉起（常驻 proot 存活，端口 ${svc.port}）")
            } else {
                CmsEngineStore.appendLog("引擎服务 ${svc.name} 端口 ${svc.port} 探测超时，可能未就绪")
            }
        }
        // 登记服务端口表，供 CmsEngineStore.probeHealth 在运行时按端口刷新服务状态
        CmsEngineStore.registerEngineServices(pkg.sharedServices)

        // 健康检查：确认就绪标记 + 核心开发工具确实就绪（避免"标记写了但工具没装"的假成功）
        val (hc, _) = QuroTerminalBridge.run(context, "[ -f ${engineGuestDir()}/.engine.ready ]", timeoutMs = 10_000)
        var health = hc == 0
        if (health) {
            val (tc, tout) = QuroTerminalBridge.run(
                context,
                "miss=; for t in python3 node gcc make cmake git curl; do command -v \$t >/dev/null 2>&1 || miss=\"\$miss \$t\"; done; if [ -n \"\$miss\" ]; then echo \"MISSING:\$miss\"; exit 2; fi; echo OK",
                timeoutMs = 10_000,
            )
            if (tc != 0) {
                health = false
                CmsEngineStore.appendLog("⚠️ 引擎就绪标记存在但缺少核心工具(tout=${tout.take(120)})，判定未就绪")
            }
        }

        CmsEngineStore.markDeployed(pkg.engineVersion, launched, health)
        sb.appendLine("🚀 CMS引擎部署完成（v${pkg.engineVersion}）${if (health) "，健康检查通过" else "，但就绪标记缺失"}。")
        if (launched.isNotEmpty()) sb.appendLine("已拉起共享服务：${launched.joinToString(", ")}")
        return sb.toString()
    }
}
