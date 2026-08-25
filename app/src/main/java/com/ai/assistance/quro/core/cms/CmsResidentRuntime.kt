package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 把 Windows CRLF 统一为 LF，防止写入 proot/Ubuntu 的脚本被 sh 解析成非法选项。 */
private fun String.normalizeLineEndings(): String = this.replace("\r\n", "\n").replace("\r", "\n")

/**
 * CMS 常驻服务运行时（原创运行时 · 修复「终端 httpd 被杀」根因）。
 *
 * **根因**：旧实现在一次性 [QuroLinuxEnv.run]（即 `proot /bin/sh -c "nohup server &"`）里后台启动服务。
 * proot 进程退出后，作为 proot 子进程的 server 失去 syscall 翻译层（proot 是其父/监督者），
 * 随之被回收——于是 cms_call 返回后 HTTP 服务立刻死掉，表现为「终端里的服务没法用」。
 * 这正是用户实测得到的结论：在常驻交互终端里直接跑 entry.sh 能活，经 cms_call 一次性调用就死。
 *
 * **修复**：改为**常驻 proot 进程**模型。proot 本身作为后台进程常驻（不 waitFor、不退出），
 * server 以 `exec sh ./entry.sh` 成为 proot 的直接子进程；proot 活着，server 就活着。
 * 进程句柄存入 registry，宿主侧经同一 bind-mount 的 run.log 读输出，并提供 stop / isAlive。
 * 适用对象：需要长期存活的 server 类能力（term_httpd_start / term_python_serve / term_node_serve）。
 */
object CmsResidentRuntime {

    private data class RServer(
        val moduleId: String,
        val process: Process,
        val logFile: File,
        val startedAt: Long,
        val port: Int,
    )

    /** moduleId → 常驻服务进程句柄（进程随 App 进程存活；App 退出则一并回收）。 */
    private val servers = ConcurrentHashMap<String, RServer>()

    /** 宿主侧运行日志路径（与 proot 内 /root/cms/<id>/run.log 同一 bind-mount 文件）。 */
    fun logFile(context: Context, moduleId: String): File =
        File(CmsTerminalDeployer.hostDir(context, moduleId), "run.log")

    /**
     * 确保模块入口脚本已落到宿主目录（从 [QuroCmsModule.terminalEntry] 提取），
     * 保证 proot 内 `exec sh ./entry.sh` 能找到脚本。未部署/入口缺失时即时补写，幂等。
     */
    private fun ensureEntry(context: Context, module: QuroCmsModule): Boolean {
        val entry = module.terminalEntry
        if (entry.isBlank()) return false
        val dir = CmsTerminalDeployer.hostDir(context, module.id)
        dir.mkdirs()
        val f = File(dir, "entry.sh")
        return runCatching {
            f.writeText(entry.normalizeLineEndings())
            f.setExecutable(true)
        }.isSuccess
    }

    /**
     * 启动常驻服务（proot 常驻 + server 直启）。
     * @param args 调用参数（如 port / dir），经 [envSpec] 映射为 proot 环境变量注入 entry.sh。
     * @param envSpec arg 名 → 环境变量名 的映射（如 "port"→"QURO_HTTP_PORT"）。
     */
    fun start(
        context: Context,
        module: QuroCmsModule,
        args: Map<String, String>,
        envSpec: Map<String, String>,
    ): String {
        val st = QuroLinuxEnv.probe(context)
        if (!st.available) return "⛔ 终端环境(proot/Ubuntu)未就绪：${st.reason}。请先在终端页安装 Linux 环境。"
        if (!ensureEntry(context, module)) {
            return "⛔ 模块 ${module.id} 无可部署的终端入口脚本(terminalEntry)，无法常驻启动。"
        }

        val gdir = CmsTerminalDeployer.guestDir(module.id)
        // exec 让 server 直接顶替 sh，成为 proot 的直接子进程；proot 常驻，server 即常驻。
        val command = "cd $gdir && exec sh ./entry.sh"

        val extraEnv = mutableMapOf<String, String>()
        envSpec.forEach { (argName, envName) ->
            args[argName]?.takeIf { it.isNotBlank() }?.let { extraEnv[envName] = it }
        }

        val proc = QuroLinuxEnv.spawnPersistent(context, command, extraEnv)
            ?: return "⛔ 无法在 proot 内启动常驻服务（proot 进程创建失败），环境可能未就绪。"
        val log = logFile(context, module.id)
        if (log.exists()) log.delete()
        val reader = proc.inputStream.bufferedReader()
        val drain = Thread {
            try { reader.use { r -> r.forEachLine { line -> runCatching { log.appendText(line + "\n") } } } } catch (_: Throwable) {}
        }
        drain.isDaemon = true
        drain.start()

        val port = args["port"]?.toIntOrNull() ?: 0
        servers[module.id] = RServer(module.id, proc, log, System.currentTimeMillis(), port)

        // 给 proot/server 一点启动时间，再确认进程仍存活（server 绑定失败会立刻退出）。
        Thread.sleep(800)
        return if (proc.isAlive) {
            "✅ 常驻服务已启动（proot 进程存活，server 随 proot 常驻）。" +
                (if (port > 0) " 监听端口=$port。" else "") +
                " 日志见 /root/cms/${module.id}/run.log，可用 term_*_list / cms_result / cms_status 回查，" +
                "停止用对应 *_stop 能力。"
        } else {
            "⛔ 常驻服务启动后进程已退出，详见 run.log：${runCatching { log.readText().take(300) }.getOrDefault("")}"
        }
    }

    /** 停止常驻服务（先 TERM 后 KILL，进程句柄直接强杀）。 */
    fun stop(context: Context, moduleId: String): String {
        val s = servers[moduleId]
        if (s == null) return "⚠ 没有运行中的 $moduleId 常驻服务（可能已被系统回收或未启动）。"
        runCatching { s.process.destroyForcibly() }
        servers.remove(moduleId)
        return "🗑 已停止 $moduleId 常驻服务（proot 进程已强杀）。"
    }

    /** 健康检查：常驻进程是否仍存活。 */
    fun isAlive(moduleId: String): Boolean = servers[moduleId]?.process?.isAlive ?: false

    /** 读取运行日志（宿主侧直接读 bind-mount 文件）。 */
    fun readLog(context: Context, moduleId: String): String {
        val f = logFile(context, moduleId)
        return if (f.exists()) f.readText().take(8000) else "(无输出)"
    }

    /** App 退出/回收时清理所有常驻进程，避免孤儿 server 残留。 */
    fun stopAll() {
        servers.values.forEach { runCatching { it.process.destroyForcibly() } }
        servers.clear()
    }
}
