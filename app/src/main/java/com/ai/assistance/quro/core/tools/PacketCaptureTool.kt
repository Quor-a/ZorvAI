package com.ai.assistance.quro.core.tools

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File

/**
 * AI 抓包：在 proot Ubuntu 24.04 容器内启动 mitmdump（mitmproxy 的 headless 版本），监听 0.0.0.0:8080，
 * 自动 -w 写入 flow 文件到 /mnt/quro/mitm/flows.har（应用共享存储内，用户可直接取走）。
 *
 * 局限（透明告知，避免用户错以为应用已自动捕获所有流量）：
 * - Android 上**应用流量不强制走应用代理**，要走 mitmproxy 必须用户手动：
 *     方式 A：开启本应用 VpnService 抓包（需要先开发 VPN 模块，本工具仅提供后台 mitmdump）；
 *     方式 B：USB 连电脑 + `adb reverse tcp:8080 tcp:8080`（需 adb）；
 *     方式 C：把设备流量导向同网内另一台跑 mitmdump 的主机（需修改 Wi-Fi 代理到主机 IP:8080）；
 *     方式 D：root 后 iptables -t nat REDIRECT。
 *   在上述任意一者成立时，mitmdump 才能抓到对应流量。
 * - mitmdump 首次启动会自签 CA（/root/.mitmproxy/mitmproxy-ca-cert.cer），抓 HTTPS 必须把 CA 装到设备系统证书；
 *   CA 文件同时导出到 /mnt/quro/mitm/mitmproxy-ca-cert.cer 供用户一键分享安装（系统证书目录需 root 或手动证书）。
 *
 * 容器内若未装 mitmproxy，首次 start 自动 `apt-get install -y mitmproxy`。
 */
class PacketCaptureTool : QuroTool {
    override val name = "packet_capture"
    override val description = "在 proot 容器内启动/停止/查询 mitmdump 抓包服务，flow 实时写入 /mnt/quro/mitm/。" +
        "参数 {\"action\":\"start|stop|status|dump|ca\",\"port\":8080（start 时可选）}。" +
        "返回 stdout/进程 PID/flow 文件路径/最近 N 条请求摘要。" +
        "注意：mitmdump 本身不强制路由流量；Android 应用流量默认不走本机代理，需用户配合（系统代理 / VPN / adb reverse）。"
    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"start/stop/status/dump/ca"},
            "port":{"type":"integer","description":"start 时监听端口，默认 8080"}
        },
        "required":["action"]
    }"""

    private val OUT_DIR = "/mnt/quro/mitm"
    private val FLOW_FILE = "$OUT_DIR/flows.har"   // mitmdump 用 --save-stream-file 可写 mitm 自有格式；这里用 -w 写 mitmdump 原生格式
    private val FLOW_FILE_REAL = "$OUT_DIR/flows.mitm"
    private val CA_FILE = "$OUT_DIR/mitmproxy-ca-cert.cer"

    override fun run(context: Context, arguments: String): String {
        val jo = JSONObject(arguments)
        val action = jo.optString("action", "").trim().lowercase()
        if (action.isEmpty()) return "缺少 action（start/stop/status/dump/ca）"
        return when (action) {
            "start" -> start(context, jo.optInt("port", 8080).coerceIn(1, 65535))
            "stop" -> stop(context)
            "status" -> status(context)
            "dump" -> dump(context)
            "ca" -> caInfo(context)
            else -> "未知 action: $action"
        }
    }

    private fun ensureDirs(context: Context) {
        val host = QuroLinuxEnv.sharedStorageHostDir(context)?.absolutePath ?: return
        File(host, "quro/mitm").mkdirs()
    }

    private fun ensureMitm(context: Context) {
        val rootfs = QuroLinuxEnv.rootfsPath(context)
        val mitmBin = File(rootfs, "usr/bin/mitmdump")
        if (mitmBin.exists()) return
        try {
            val p = Runtime.getRuntime().exec(arrayOf(
                QuroLinuxEnv.prootPath(context),
                "--link2symlink", "--kill-on-exit",
                "--rootfs=$rootfs",
                "--bind=${QuroLinuxEnv.sharedStorageHostDir(context)?.absolutePath ?: "/mnt"}:/mnt",
                "/usr/bin/env", "sh", "-c",
                "apt-get update -qq >/dev/null 2>&1 && apt-get install -y mitmproxy >/dev/null 2>&1"
            ))
            p.waitFor(120_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {}
    }

    private fun start(context: Context, port: Int): String {
        ensureDirs(context); ensureMitm(context)
        if (MitmProcHolder.proc?.isAlive == true) {
            return "mitmdump 已在运行（PID=${MitmProcHolder.pid}, port=${MitmProcHolder.port}）；先 action=stop 再启动新实例。"
        }
        val rootfs = QuroLinuxEnv.rootfsPath(context)
        val proot = QuroLinuxEnv.prootPath(context)
        val args = arrayOf(
            proot, "--link2symlink", "--kill-on-exit",
            "--rootfs=$rootfs",
            "--bind=${QuroLinuxEnv.sharedStorageHostDir(context)?.absolutePath ?: "/mnt"}:/mnt",
            "/usr/bin/env", "mitmdump",
            "--listen-port", port.toString(),
            "--set", "block_global=false",
            "--save-stream-file", FLOW_FILE_REAL,
            "--set", "confdir=/root/.mitmproxy"
        )
        val pb = ProcessBuilder(*args)
        pb.environment().clear()
        QuroLinuxEnv.shellEnv(context).forEach { kv ->
            val eq = kv.indexOf('=')
            if (eq > 0) pb.environment()[kv.substring(0, eq)] = kv.substring(eq + 1)
        }
        pb.redirectErrorStream(true)
        val proc = try { pb.start() } catch (e: Exception) {
            return "启动失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
        MitmProcHolder.proc = proc
        MitmProcHolder.pid = android.os.Process.myPid()  // proot 内 PID 不可见，记宿主 PID 用于诊断
        MitmProcHolder.port = port
        // 异步把 stdout 流写到日志文件（用户可取走排查）
        Thread({
            val log = File(QuroLinuxEnv.sharedStorageHostDir(context) ?: return@Thread, "quro/mitm/mitmdump.log")
            log.parentFile?.mkdirs()
            proc.inputStream.bufferedReader().use { r ->
                log.bufferedWriter().use { w ->
                    var lines = 0
                    r.forEachLine { line -> if (lines++ < 4000) { w.write(line); w.newLine() } }
                }
            }
        }, "mitm-stdout").start()
        Thread.sleep(800)  // 给 mitmdump 启动时间
        val alive = proc.isAlive
        return buildString {
            append("mitmdump 启动 attempt\n")
            append(" alive=").append(alive).append('\n')
            append(" port=").append(port).append('\n')
            append(" flow_file=").append(FLOW_FILE_REAL).append("（mitm 原生格式，含 body 与 TLS 明文）\n")
            append(" log_file=应用沙箱/quro/mitm/mitmdump.log\n")
            append(" ca_cert=/root/.mitmproxy/mitmproxy-ca-cert.cer（容器内）；导出后用 action=ca 拿到设备路径\n")
            if (!alive) append("⚠️ 进程未存活，请先看 mitmdump.log 排查（一般是无 listen 权限或端口占用）")
        }
    }

    private fun stop(context: Context): String {
        val proc = MitmProcHolder.proc
        if (proc == null || !proc.isAlive) return "mitmdump 未在运行"
        proc.destroyForcibly()
        MitmProcHolder.proc = null
        return "mitmdump 已停止。"
    }

    private fun status(context: Context): String {
        val proc = MitmProcHolder.proc
        val alive = proc?.isAlive == true
        val flow = File(QuroLinuxEnv.sharedStorageHostDir(context) ?: return "无共享存储", "quro/mitm/flows.mitm")
        val size = if (flow.exists()) flow.length() else -1L
        return buildString {
            append("running=").append(alive).append('\n')
            append(" port=").append(MitmProcHolder.port).append('\n')
            append(" flow_file=").append(FLOW_FILE_REAL).append(" size=").append(size).append(" bytes\n")
            append(" log_tail_lines=").append(countLines(File(QuroLinuxEnv.sharedStorageHostDir(context)?.absolutePath ?: "/dev/null", "quro/mitm/mitmdump.log"))).append('\n')
        }
    }

    private fun dump(context: Context): String {
        val host = QuroLinuxEnv.sharedStorageHostDir(context)?.absolutePath ?: return "无共享存储"
        val flow = File(host, "quro/mitm/flows.mitm")
        if (!flow.exists() || flow.length() == 0L) return "暂无 flow（mitmdump 启动后才会写）"
        // 同步保存一份可读摘要：调用 mitmdump -r flows.mitm --set hardump=/path 重新导出为 HAR
        val har = File(host, "quro/mitm/flows.har")
        val rootfs = QuroLinuxEnv.rootfsPath(context)
        val proot = QuroLinuxEnv.prootPath(context)
        val pb = ProcessBuilder(
            proot, "--link2symlink", "--kill-on-exit",
            "--rootfs=$rootfs",
            "--bind=$host:/mnt",
            "/usr/bin/env", "mitmdump",
            "-r", FLOW_FILE_REAL,
            "--set", "hardump=$HAR_HOST_PATH",
            "--set", "block_global=false"
        )
        pb.environment().clear()
        QuroLinuxEnv.shellEnv(context).forEach { kv ->
            val eq = kv.indexOf('=')
            if (eq > 0) pb.environment()[kv.substring(0, eq)] = kv.substring(eq + 1)
        }
        val proc = try { pb.start() } catch (e: Exception) {
            return "导出失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
        proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        val rc = try { proc.exitValue() } catch (_: Exception) { -1 }
        return buildString {
            append("HAR 导出 attempt\n")
            append(" exit_code=").append(rc).append('\n')
            append(" path=").append(har.absolutePath).append('\n')
            append(" size=").append(if (har.exists()) har.length() else -1L).append(" bytes\n")
            if (rc != 0) append("⚠️ 导出失败；flow 文件仍可用 mitmdump --flow-detail 等命令手工查看")
        }
    }

    private val HAR_HOST_PATH = "/mnt/quro/mitm/flows.har"

    private fun caInfo(context: Context): String {
        val host = QuroLinuxEnv.sharedStorageHostDir(context)?.absolutePath ?: return "无共享存储"
        val src = File(QuroLinuxEnv.rootfsPath(context), "root/.mitmproxy/mitmproxy-ca-cert.cer")
        val dst = File(host, "quro/mitm/mitmproxy-ca-cert.cer")
        if (src.exists()) {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
        return buildString {
            append("ca_cert 容器内路径=").append("/root/.mitmproxy/mitmproxy-ca-cert.cer").append('\n')
            append("ca_cert 设备路径=").append(dst.absolutePath).append("（exists=").append(dst.exists()).append("）\n")
            append("HTTPS 解密需把此 CA 装到设备系统证书目录（Android 7+ 需要 root 或用户证书）。")
        }
    }

    private fun countLines(f: File): Int = if (!f.exists()) 0 else f.bufferedReader().use { it.readLines().size }

    private object MitmProcHolder {
        @Volatile var proc: Process? = null
        @Volatile var pid: Int = -1
        @Volatile var port: Int = 8080
    }
}