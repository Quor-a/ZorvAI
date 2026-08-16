package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.cms.CmsStateStore
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import java.io.File

/** 把 Windows CRLF 统一为 LF，防止写入 proot/Alpine 的 shell 脚本出现「illegal option -」等诡异解析错误。 */
private fun String.normalizeLineEndings(): String = this.replace("\r\n", "\n").replace("\r", "\n")

/**
 * CMS v2 终端部署器（原创运行时 · 部署系统）。
 *
 * 把 [CmsDeployPackage] 推到 proot/Alpine 沙箱的 /root/cms/<moduleId>：
 * ① 校验完整性（sha256，P0）② 写 manifest + 入口脚本 ③ 装依赖(apk/pip) ④ chmod +x。
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
     * 确保 CMS 基础运行环境已就绪（proot/Alpine 内）。
     * - proot 未就绪 → 返回引导文案（不静默失败）。
     * - 已就绪但缺 .bootstrap.done 标记 → 从 assets 拷 bootstrap.sh 进 proot 并执行。
     * - 已标记 → 直接返回就绪（幂等）。
     * 返回人类可读状态；以 ⛔ 开头表示失败。
     */
    fun bootstrap(context: Context): String {
        CmsStateStore.init(context)
        val st = QuroLinuxEnv.probe(context)
        if (!st.available) {
            return "⛔ 终端环境(proot/Alpine)未就绪：${st.reason}。请先在「终端」页安装 Linux 环境（首次需联网下载约30MB）。"
        }
        val dir = bootstrapDir(context)
        val marker = File(dir, ".bootstrap.done")
        if (marker.exists()) {
            CmsStateStore.appendLog("_bootstrap", "✅ CMS 基础环境已就绪（跳过 bootstrap）")
            return "✅ CMS 基础环境已就绪（跳过 bootstrap）。"
        }
        CmsStateStore.appendLog("_bootstrap", "▶ 写入内置 bootstrap 脚本")
        val script = File(dir, "bootstrap.sh")
        try {
            // 关键修复：assets 文件在 Windows 工作区可能是 CRLF，直接拷进 Alpine 会让 sh 解析出错。
            val text = context.assets.open(BOOTSTRAP_ASSET).bufferedReader().use { it.readText() }.normalizeLineEndings()
            script.writeText(text)
            script.setExecutable(true)
        } catch (e: Exception) {
            return "⛔ 内置 bootstrap 脚本读取失败：${e.message}"
        }
        CmsStateStore.appendLog("_bootstrap", "• 执行 bootstrap（安装 python3/nodejs，约需联网）")
        val (code, out) = QuroLinuxEnv.run(context, "sh /root/cms/_bootstrap/bootstrap.sh", timeoutMs = 300_000)
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
        // D1：终端后端唯一化 = proot；环境未就绪直接拒绝，不回退 device sh。
        val st = QuroLinuxEnv.probe(context)
        if (!st.available) {
            val msg = "⛔ 终端环境(proot/Alpine)未就绪：${st.reason}。请在终端页点「安装 Linux 环境」后再部署。"
            CmsStateStore.markDeployEnd(pkg.moduleId, false, msg)
            return msg
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
            CmsStateStore.markDeployStep(pkg.moduleId, "安装 apk 依赖: ${pkg.apkDeps.joinToString(" ")}", 75)
            val (c, out) = QuroLinuxEnv.run(
                context,
                "apk add --no-cache ${pkg.apkDeps.joinToString(" ")}",
                timeoutMs = 180_000,
            )
            if (c != 0) {
                val msg = sb.appendLine("⛔ apk 依赖安装失败(exit $c): ${out.take(300)}").toString()
                CmsStateStore.markDeployEnd(pkg.moduleId, false, "apk 依赖安装失败(exit $c): ${out.take(300)}")
                return msg
            }
            sb.appendLine("✅ apk 依赖已装: ${pkg.apkDeps.joinToString(" ")}")
        }

        if (pkg.pipDeps.isNotEmpty()) {
            CmsStateStore.markDeployStep(pkg.moduleId, "安装 pip 依赖: ${pkg.pipDeps.joinToString(" ")}", 90)
            val (c, out) = QuroLinuxEnv.run(
                context,
                "pip install --no-cache-dir ${pkg.pipDeps.joinToString(" ")}",
                timeoutMs = 180_000,
            )
            if (c != 0) {
                val msg = sb.appendLine("⛔ pip 依赖安装失败(exit $c): ${out.take(300)}").toString()
                CmsStateStore.markDeployEnd(pkg.moduleId, false, "pip 依赖安装失败(exit $c): ${out.take(300)}")
                return msg
            }
            sb.appendLine("✅ pip 依赖已装: ${pkg.pipDeps.joinToString(" ")}")
        }

        if (pkg.envProfiles.isNotEmpty()) {
            CmsStateStore.markDeployStep(pkg.moduleId, "装配终端环境栈: ${pkg.envProfiles.joinToString(" ")}", 95)
            val results = CmsEnvProvisioner.provisionAll(context, pkg.envProfiles)
            results.forEach { (p, r) -> CmsStateStore.appendLog(pkg.moduleId, "[env:$p] $r") }
            val hardFail = results.filter { it.second.startsWith("⛔") }
            if (hardFail.isNotEmpty()) {
                sb.appendLine("⚠️ 部分终端环境装配失败（非致命，部署继续）：${hardFail.joinToString { it.second }}")
            } else {
                sb.appendLine("✅ 终端环境栈已装配： ${pkg.envProfiles.joinToString(" ")}")
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
