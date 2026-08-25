package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import java.io.File

/**
 * CMS v2 终端模块运行时（原创运行时 · 进程管理）。
 *
 * 在 proot/Ubuntu 沙箱里把已部署的 [CmsDeployPackage] 启动为**后台常驻进程**，
 * 提供：启动（带 P0 资源约束 ulimit -t/-v）、读取输出（经 run.log）、健康检查、停止。
 *
 * 设计取舍：proot 的一次性 [QuroLinuxEnv.run] 模型不适合长连接流式 IO，
 * 故模块进程以 `nohup ... &` 脱离启动 shell 常驻，stdout/stderr 落 run.log，
 * 宿主侧直接读该 bind-mount 文件即可（无需再经 proot 取流）。
 * 资源约束（P0）由 ulimit 继承给子进程：超时(-t) + 虚拟内存(-v)。
 */
data class CmsModuleProcess(
    val moduleId: String,
    val pid: Int,
    val startedAt: Long,
    val guestDir: String,
    val logFile: File,
)

object CmsTerminalRuntime {

    /** 宿主侧运行日志路径（与 proot 内 /root/cms/<id>/run.log 同一文件）。 */
    fun logFile(context: Context, moduleId: String): File =
        File(CmsTerminalDeployer.hostDir(context, moduleId), "run.log")

    /**
     * 启动模块为后台进程。
     * @param timeoutSecs 超时约束（ulimit -t，P0 防死循环/挂起）
     * @param memMb 虚拟内存上限 MB（ulimit -v，P0 防 OOM 拖垮宿主）
     * 返回进程句柄；环境未就绪或启动失败返回 null。
     */
    fun start(
        context: Context,
        pkg: CmsDeployPackage,
        timeoutSecs: Int = 30,
        memMb: Int = 256,
    ): CmsModuleProcess? {
        val st = QuroLinuxEnv.probe(context)
        if (!st.available) return null

        val gdir = CmsTerminalDeployer.guestDir(pkg.moduleId)
        val entry = pkg.entry
        // ulimit 在 shell 内设置后由子进程继承；nohup 使进程忽略 SIGHUP 在启动 shell 退出后继续存活。
        val launch = buildString {
            append("cd $gdir\n")
            append("ulimit -t $timeoutSecs -v ${memMb * 1024}\n")
            append("nohup ./$entry > run.log 2>&1 &\n")
            append("echo \$! > run.pid\n")
            append("cat run.pid\n")
        }
        val (c, out) = QuroLinuxEnv.run(context, launch, timeoutMs = 30_000)
        if (c != 0) return null
        val pid = out.trim().toIntOrNull() ?: return null
        return CmsModuleProcess(
            moduleId = pkg.moduleId,
            pid = pid,
            startedAt = System.currentTimeMillis(),
            guestDir = gdir,
            logFile = logFile(context, pkg.moduleId),
        )
    }

    /** 读取模块运行输出（宿主侧直接读 bind-mount 日志）。 */
    fun readLog(context: Context, moduleId: String): String {
        val f = logFile(context, moduleId)
        return if (f.exists()) f.readText().take(8000) else "(无输出)"
    }

    /** 健康检查：进程是否存活。 */
    fun isAlive(context: Context, pid: Int): Boolean {
        val (c, _) = QuroLinuxEnv.run(context, "kill -0 $pid", timeoutMs = 10_000)
        return c == 0
    }

    /** 停止进程：先 TERM 后 KILL。 */
    fun stop(context: Context, pid: Int): Boolean {
        val (c, _) = QuroLinuxEnv.run(
            context,
            "kill -TERM $pid 2>/dev/null; sleep 1; kill -KILL $pid 2>/dev/null; echo done",
            timeoutMs = 15_000,
        )
        return c == 0
    }
}
