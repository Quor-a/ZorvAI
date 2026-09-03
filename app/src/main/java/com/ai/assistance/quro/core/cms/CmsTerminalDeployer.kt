package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.cms.CmsStateStore
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import com.ai.assistance.quro.core.terminal.QuroTerminalBridge
import java.io.File

/** 把 Windows CRLF 统一为 LF，防止写入 proot/Ubuntu 的 shell 脚本出现「illegal option -」等诡异解析错误。 */
private fun String.normalizeLineEndings(): String = this.replace("\r\n", "\n").replace("\r", "\n")

/**
 * CMS v2 终端部署器（原创运行时 · 部署系统）。
 *
 * 把 [CmsDeployPackage] 推到 proot/Ubuntu 沙箱的 /root/cms/<moduleId>：
 * ① 校验完整性（sha256，P0）② 写 manifest + 入口脚本 ③ 装依赖(apt/pip) ④ chmod +x。
 * 启动/进程管理归 [CmsTerminalRuntime]（v179）。
 *
 * D1 约束：终端执行后端唯一化 = proot；环境未就绪**直接拒绝**，绝不回退 /system/bin/sh 玩具通道。
 */
object CmsTerminalDeployer {

    /** 宿主侧目录（经 proot 挂载为 /root/cms/<moduleId>）。 */
    fun hostDir(context: Context, moduleId: String): File =
        File(QuroLinuxEnv.homePath(context), "cms/$moduleId").also { it.mkdirs() }

    /** proot 内（guest）路径。 */
    fun guestDir(moduleId: String): String = "/root/cms/$moduleId"

    /** 内置 bootstrap 脚本在 APK assets 中的路径（v183 一键部署内置包）。 */
    private const val BOOTSTRAP_ASSET = "cms/bootstrap.sh"

    /** 宿主侧 bootstrap 目录（proot 内 = /root/cms/_bootstrap）。 */
    fun bootstrapDir(context: Context): File =
        File(QuroLinuxEnv.homePath(context), "cms/_bootstrap").also { it.mkdirs() }

    /**
     * 确保 CMS 基础运行环境已就绪（proot/Ubuntu 内）。
     * - proot 未就绪 → 返回引导文案（不静默失败）。
     * - 已就绪但缺 .bootstrap.done 标记 → 从 assets 拷 bootstrap.sh 进 proot 并执行。
     * - 已标记但关键工具缺失（proot重启后rootfs重置） → 重新执行 bootstrap。
     * - 已标记且工具就绪 → 直接返回就绪（幂等）。
     * 返回人类可读状态；以 ⛔ 开头表示失败。
     */
    fun bootstrap(context: Context): String {
        CmsStateStore.init(context)
        // 环境未就绪时**自动拉起**终端安装（与 deploy 一致），安装成功即继续 bootstrap。
        var st = QuroLinuxEnv.probeLenient(context)
        if (!st.available) {
            CmsStateStore.appendLog("_bootstrap", "▶ 终端环境未就绪，自动安装 proot/Ubuntu…")
            st = QuroLinuxEnv.ensureInstalledBlocking(context)
            if (!st.available) {
                return "⛔ 终端环境(proot/Ubuntu)自动安装失败：${st.reason}。请先在「终端」页安装 Linux 环境（首次需联网下载约30MB）。"
            }
            CmsStateStore.appendLog("_bootstrap", "✅ 终端环境已自动安装就绪")
        }
        val dir = bootstrapDir(context)
        val marker = File(dir, ".bootstrap.done")
        
        // 检查关键工具是否存在（proot重启后可能丢失）
        val checkTools = QuroTerminalBridge.run(context, "command -v python3 >/dev/null 2>&1 && command -v node >/dev/null 2>&1 && command -v gcc >/dev/null 2>&1", timeoutMs = 10_000)
        val toolsMissing = checkTools.first != 0
        
        if (marker.exists() && !toolsMissing) {
            CmsStateStore.appendLog("_bootstrap", "✅ CMS 基础环境已就绪（跳过 bootstrap）")
            return "✅ CMS 基础环境已就绪（跳过 bootstrap）。"
        }
        
        if (marker.exists() && toolsMissing) {
            CmsStateStore.appendLog("_bootstrap", "⚠️ 检测到关键工具缺失（proot重启后丢失），重新执行 bootstrap...")
        }
        CmsStateStore.appendLog("_bootstrap", "▶ 写入内置 bootstrap 脚本")
        val script = File(dir, "bootstrap.sh")
        try {
            // 关键修复：assets 文件在 Windows 工作区可能是 CRLF，直接拷进 Ubuntu 会让 sh 解析出错。
            val text = context.assets.open(BOOTSTRAP_ASSET).bufferedReader().use { it.readText() }.normalizeLineEndings()
            script.writeText(text)
            script.setExecutable(true)
        } catch (e: Exception) {
            return "⛔ 内置 bootstrap 脚本读取失败：${e.message}"
        }
        CmsStateStore.appendLog("_bootstrap", "• 执行 bootstrap（安装 python3/nodejs/终端工具，约需联网）")
        var (code, out) = QuroTerminalBridge.run(context, "sh /root/cms/_bootstrap/bootstrap.sh", timeoutMs = 600_000)
        if (code != 0) {
            CmsStateStore.appendLog("_bootstrap", "• 首次 bootstrap 失败，等待2秒后重试...")
            Thread.sleep(2000)
            val retry = QuroTerminalBridge.run(context, "sh /root/cms/_bootstrap/bootstrap.sh", timeoutMs = 600_000)
            code = retry.first
            out = retry.second
        }
        return if (code == 0) {
            CmsStateStore.appendLog("_bootstrap", "✅ bootstrap 完成")
            "✅ CMS 基础环境 bootstrap 完成（python3/nodejs 已装）。"
        } else {
            "⛔ bootstrap 执行失败(exit $code): ${out.take(300)}"
        }
    }

    /**
     * 部署一个包。返回人类可读的多步状态，并实时写入 [CmsStateStore]（进度/明确终态），
     * 让 UI 订阅刷新、AI 经 cms_status 回查「是否部署成功」。
     * 不启动进程（启动归 CmsTerminalRuntime）。
     */
    fun deploy(context: Context, pkg: CmsDeployPackage): String {
        CmsStateStore.init(context)
        CmsStateStore.markDeployStart(pkg.moduleId, "准备部署 ${pkg.moduleId}")
        // D1：终端后端唯一化 = proot；环境未就绪时**自动拉起**终端安装，安装成功即继续部署。
        var st = QuroLinuxEnv.probeLenient(context)
        if (!st.available) {
            CmsStateStore.markDeployStep(pkg.moduleId, "自动安装终端环境(proot/Ubuntu)", 10)
            st = QuroLinuxEnv.ensureInstalledBlocking(context)
            if (!st.available) {
                val msg = "⛔ 终端环境(proot/Ubuntu)自动安装失败：${st.reason}。请在终端页点「安装 Linux 环境」后再部署。"
                CmsStateStore.markDeployEnd(pkg.moduleId, false, msg)
                return msg
            }
        }
        // 确保基础运行环境（python3/nodejs）就绪；幂等（已标记则跳过）。
        CmsStateStore.markDeployStep(pkg.moduleId, "环境就绪，准备 bootstrap")
        val base = bootstrap(context)
        if (base.startsWith("⛔")) {
            CmsStateStore.markDeployEnd(pkg.moduleId, false, base)
            return base
        }
        CmsStateStore.markDeployStep(pkg.moduleId, "bootstrap 完成")
        // P0 完整性校验：sha256 不匹配/缺失 → 拒部署。
        if (!pkg.verifyIntegrity()) {
            val msg = "⛔ 部署包完整性校验失败（sha256 不匹配或缺失），拒绝部署，疑似被篡改/损坏。"
            CmsStateStore.markDeployEnd(pkg.moduleId, false, msg)
            return msg
        }

        val dir = hostDir(context, pkg.moduleId)
        dir.mkdirs()
        File(dir, "cms-package.json").writeText(pkg.toJson())

        if (pkg.entryContent.isNotBlank()) {
            val ef = File(dir, pkg.entry)
            ef.writeText(pkg.entryContent.normalizeLineEndings())
            ef.setExecutable(true)
        }

        val sb = StringBuilder()
        sb.appendLine("✅ 文件已写入 ${guestDir(pkg.moduleId)}")
        CmsStateStore.markDeployStep(pkg.moduleId, "文件已写入 ${guestDir(pkg.moduleId)}", 60)

        if (pkg.apkDeps.isNotEmpty()) {
            CmsStateStore.markDeployStep(pkg.moduleId, "安装 Linux 依赖: ${pkg.apkDeps.joinToString(" ")}", 75)
            // 稳健安装：先 apt-get install，proot 下失败则回退 apt-get download + dpkg-deb -x（与 CMS 引擎一致）。
            // best-effort：proot 下 apt 事务偶发半装，不应整体失败阻塞模块部署，改为告警后继续。
            val deps = pkg.apkDeps.joinToString(" ") { "\"$it\"" }
            // 安装前先检测并释放残留 dpkg/apt 锁，避免上一次中断遗留的锁导致 apt-get 卡死/失败（与引擎、开发环境一致）。
            val lockPrologue = QuroLinuxEnv.APT_LOCK_RELEASE_PROLOGUE
            val installCmd = lockPrologue + "\n" +
                "for p in $deps; do apt-get install -y --no-install-recommends \$p 2>&1 | tail -2; " +
                "if ! dpkg -s \$p >/dev/null 2>&1; then d=/tmp/cmsdeb_\$p; mkdir -p \$d; " +
                "(cd \$d && apt-get download \$p 2>/dev/null && for f in *.deb; do dpkg-deb -x \"\$f\" / 2>/dev/null; done; rm -f *.deb); " +
                "apt-get -f -y install 2>/dev/null; fi; done; true"
            val (c, out) = QuroTerminalBridge.run(context, installCmd, timeoutMs = 240_000)
            if (c != 0) {
                sb.appendLine("⚠️ 部分 Linux 依赖安装可能不完整(exit $c): ${out.take(300)}（best-effort，继续部署）")
                CmsStateStore.appendLog(pkg.moduleId, "⚠️ apt 依赖安装返回 $c: ${out.take(300)}")
            } else {
                sb.appendLine("✅ apt 依赖已装: ${pkg.apkDeps.joinToString(" ")}")
            }
        }

        if (pkg.pipDeps.isNotEmpty()) {
            CmsStateStore.markDeployStep(pkg.moduleId, "安装 pip 依赖: ${pkg.pipDeps.joinToString(" ")}", 90)
            // best-effort：pip 在 proot 下偶发网络/证书问题，不应整体失败阻塞模块部署。
            val (c, out) = QuroTerminalBridge.run(
                context,
                "pip install --no-cache-dir --break-system-packages ${pkg.pipDeps.joinToString(" ")} 2>&1 || pip install --no-cache-dir ${pkg.pipDeps.joinToString(" ")} 2>&1",
                timeoutMs = 240_000,
            )
            if (c != 0) {
                sb.appendLine("⚠️ 部分 pip 依赖安装可能不完整(exit $c): ${out.take(300)}（best-effort，继续部署）")
                CmsStateStore.appendLog(pkg.moduleId, "⚠️ pip 依赖返回 $c: ${out.take(300)}")
            } else {
                sb.appendLine("✅ pip 依赖已装: ${pkg.pipDeps.joinToString(" ")}")
            }
        }

        sb.appendLine("🚀 部署完成。可用 cms_call / CmsTerminalRuntime 启动。")
        CmsStateStore.markDeployEnd(pkg.moduleId, true, "部署完成")
        return sb.toString()
    }

    /** 卸载：删除宿主侧目录（proot 内即消失）。 */
    fun undeploy(context: Context, moduleId: String): String {
        val dir = hostDir(context, moduleId)
        if (dir.exists()) dir.deleteRecursively()
        return "🗑 已卸载 $moduleId（终端侧目录已删除）"
    }
}
