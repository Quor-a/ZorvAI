package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * AI 跑 Python：在 proot Ubuntu 24.04 容器内调用 `python3 -u -c <code>`，捕获 stdout/stderr/退出码。
 *
 * 限制：
 * - 冷启动开销：proot + python3 启动约 0.8-2s（低端机更慢）；单次执行 timeout 默认 20s。
 * - 没装 python3 时自动尝试 `apt-get install -y python3`（容器写入层允许），仍然失败则返回明确错误。
 * - 大段代码 / 大量输出会被截断（stdout 12KB、stderr 4KB），避免 AI 上下文被撑爆。
 */
class PythonRunTool : QuroTool {
    override val name = "python_run"
    override val description = "在 proot Ubuntu 24.04 容器内执行 Python 代码并返回结果。" +
        "参数 {\"code\":\"Python 源码（必填）\",\"timeout_ms\":20000（最大 60000）}。" +
        "适用：AI 自己写 Python 做数据处理/正则/格式化/小型算法/抓取后的二次清洗等。" +
        "对 Web 抓取/搜索/读文章请用 ai_browser.automate/read；对长时任务请把代码拆小循环或加 print 看进度。" +
        "若容器没装 Python：首次调用会自动 apt-get install -y python3（需联网，写入层已挂载）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "code":{"type":"string","description":"Python 源码（必填）"},
            "timeout_ms":{"type":"integer","description":"超时毫秒，默认 20000，最大 60000"}
        },
        "required":["code"]
    }"""

    override fun run(context: Context, arguments: String): String = runBlocking {
        val jo = JSONObject(arguments)
        val code = jo.optString("code", "")
        if (code.isBlank()) return@runBlocking "python_run 缺少 code"
        val timeoutMs = jo.optInt("timeout_ms", 20000).coerceIn(1000, 60000)
        ensurePython(context)
        execPython(context, code, timeoutMs)
    }

    private fun ensurePython(context: Context) {
        val py = File(QuroLinuxEnv.rootfsPath(context), "usr/bin/python3")
        if (py.exists()) return
        // 缺失：触发 apt 安装（写入层已挂载）。静默执行，失败在 execPython 阶段会显式提示。
        try {
            val p = Runtime.getRuntime().exec(arrayOf(
                QuroLinuxEnv.prootPath(context),
                "--link2symlink",
                "--kill-on-exit",
                "--rootfs=${QuroLinuxEnv.rootfsPath(context)}",
                "--bind=${QuroLinuxEnv.sharedStorageHostDir(context)?.absolutePath ?: "/mnt"}:/mnt",
                "/usr/bin/env", "sh", "-c",
                "apt-get update -qq >/dev/null 2>&1 && apt-get install -y python3 python3-pip >/dev/null 2>&1"
            ))
            p.waitFor(60_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) { /* 吞掉，execPython 会以更友好的方式报错 */ }
    }

    private fun execPython(context: Context, code: String, timeoutMs: Int): String {
        val rootfs = QuroLinuxEnv.rootfsPath(context)
        val proot = QuroLinuxEnv.prootPath(context)
        val envArr = QuroLinuxEnv.shellEnv(context) + arrayOf(
            "PYTHONUNBUFFERED=1",
            "PYTHONDONTWRITEBYTECODE=1",
            "LC_ALL=C.UTF-8"
        )
        val args = arrayOf(
            proot,
            "--link2symlink",
            "--kill-on-exit",
            "--rootfs=$rootfs",
            "--bind=${QuroLinuxEnv.sharedStorageHostDir(context)?.absolutePath ?: "/mnt"}:/mnt",
            "/usr/bin/env",
            "python3", "-I", "-u", "-c", code
        )
        val pb = ProcessBuilder(*args)
        pb.environment().clear()
        envArr.forEach { kv ->
            val eq = kv.indexOf('=')
            if (eq > 0) pb.environment()[kv.substring(0, eq)] = kv.substring(eq + 1)
        }
        pb.redirectErrorStream(false)
        return runWithTimeout(pb, timeoutMs)
    }

    private fun runWithTimeout(pb: ProcessBuilder, timeoutMs: Int): String {
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            return "python_run 启动失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
        val outSb = StringBuilder()
        val errSb = StringBuilder()
        val outThread = Thread { proc.inputStream.bufferedReader().forEachLine { if (outSb.length < 12_000) outSb.appendLine(it) } }
        val errThread = Thread { proc.errorStream.bufferedReader().forEachLine { if (errSb.length < 4_000) errSb.appendLine(it) } }
        outThread.isDaemon = true; errThread.isDaemon = true
        outThread.start(); errThread.start()
        val ok = proc.waitFor(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) {
            proc.destroyForcibly()
            outThread.join(500); errThread.join(500)
            return "python_run 超时（>${timeoutMs}ms）。stdout 截断：\n${outSb}\nstderr：\n${errSb}"
        }
        outThread.join(2000); errThread.join(2000)
        val rc = proc.exitValue()
        val out = outSb.toString().trim()
        val err = errSb.toString().trim()
        val sb = StringBuilder()
        sb.append("exit_code=").append(rc).append('\n')
        if (out.isNotEmpty()) sb.append("--- stdout ---\n").append(out).append('\n')
        if (err.isNotEmpty()) sb.append("--- stderr ---\n").append(err).append('\n')
        if (out.isEmpty() && err.isEmpty() && rc == 0) sb.append("(无输出)")
        return sb.toString().trim()
    }
}