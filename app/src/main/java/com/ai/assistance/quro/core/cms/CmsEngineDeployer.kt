package com.ai.assistance.quro.core.cms

import android.content.Context
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import java.io.File

/**
 * CMS v2 CMS引擎部署器（一级部署系统）。
 *
 * 把 [CmsEnginePackage] 推到 proot/Alpine 的 /root/cms/_engine（区别于模块 /root/cms/<moduleId>）：
 * ① 校验完整性(sha256, P0) ② 写 cms-engine.json + bootstrap.sh + provision/ + services/ ③ 跑 bootstrap
 * ④ provisionAll(envProfiles) ⑤ 拉起共享服务 ⑥ 写就绪标记 + [CmsEngineStore]。
 *
 * D1 约束：终端后端唯一化 = proot；环境未就绪**直接拒绝**，绝不回退 /system/bin/sh 玩具通道。
 */
object CmsEngineDeployer {

    /** 宿主侧引擎目录（proot 内 = /root/cms/_engine）。 */
    fun engineHostDir(context: Context): File =
        File(QuroLinuxEnv.homePath(context), "cms/_engine").also { it.mkdirs() }

    /** proot 内（guest）引擎路径（无 context，供状态系统探活复用）。 */
    fun engineGuestDir(): String = "/root/cms/_engine"

    /** 导出CMS引擎为 JSON 文本（用于「导出CMS引擎」分享/本地留存）。 */
    fun exportPackage(pkg: CmsEnginePackage): String = pkg.toJson()

    /** 解析导入的CMS引擎 JSON（用于「导入CMS引擎」）。异常由调用方捕获。 */
    fun importPackage(json: String): CmsEnginePackage = CmsEnginePackage.fromJson(json)

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
        // D1：终端后端唯一化 = proot；环境未就绪直接拒绝，不回退 device sh。
        val st = QuroLinuxEnv.probe(context)
        if (!st.available) {
            val msg = "⛔ 终端环境(proot/Alpine)未就绪：${st.reason}。请先在「终端」页安装 Linux 环境后再部署CMS引擎。"
            CmsEngineStore.markFailed(msg)
            return msg
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
        boot.writeText(pkg.bootstrapContent)
        boot.setExecutable(true)

        val provDir = File(dir, "provision").also { it.mkdirs() }
        val prov = File(provDir, "provision.sh")
        prov.writeText(
            if (pkg.provisionerContent.isBlank()) "#!/bin/sh\necho '[quro-engine] no provisioner'\n"
            else pkg.provisionerContent,
        )
        prov.setExecutable(true)

        val svcDir = File(dir, "services").also { it.mkdirs() }
        svcDir.listFiles()?.forEach { if (it.name.endsWith(".sh")) it.delete() }
        pkg.sharedServices.forEach { svc ->
            if (!svc.enabled || svc.command.isBlank()) return@forEach
            val f = File(svcDir, "${svc.id}.sh")
            f.writeText("#!/bin/sh\n# Quro Engine shared service: ${svc.name} (port ${svc.port})\n${svc.command}\n")
            f.setExecutable(true)
        }

        val sb = StringBuilder()
        sb.appendLine("✅ CMS引擎文件已写入 ${engineGuestDir()}")

        // 跑 bootstrap（安装 python3/nodejs 基础运行时）
        CmsEngineStore.markDeployStep("执行引擎 bootstrap（安装基础运行时）", 40)
        val (bc, bout) = QuroLinuxEnv.run(context, "sh ${engineGuestDir()}/bootstrap.sh", timeoutMs = 300_000)
        if (bc != 0) {
            val msg = "⛔ 引擎 bootstrap 失败(exit $bc): ${bout.take(300)}"
            CmsEngineStore.markFailed(msg)
            return sb.appendLine(msg).toString()
        }
        sb.appendLine("✅ 引擎 bootstrap 完成")

        // provisioner（非致命）
        CmsEngineStore.markDeployStep("装配引擎级环境", 70)
        val (pc, pout) = QuroLinuxEnv.run(context, "sh ${engineGuestDir()}/provision/provision.sh", timeoutMs = 300_000)
        if (pc != 0) sb.appendLine("⚠️ 引擎 provisioner 异常(exit $pc): ${pout.take(200)}（非致命，继续）")
        else sb.appendLine("✅ 引擎 provisioner 完成")

        // 引擎级环境栈（非致命）
        if (pkg.envProfiles.isNotEmpty()) {
            CmsEngineStore.markDeployStep("装配引擎环境栈: ${pkg.envProfiles.joinToString(" ")}", 85)
            val results = CmsEnvProvisioner.provisionAll(context, pkg.envProfiles)
            val hard = results.filter { it.second.startsWith("⛔") }
            if (hard.isNotEmpty()) sb.appendLine("⚠️ 部分引擎环境装配失败（非致命）：${hard.joinToString { it.second }}")
            else sb.appendLine("✅ 引擎环境栈已装配：${pkg.envProfiles.joinToString(" ")}")
        }

        // 拉起共享服务（每个服务脚本自行后台化）
        val launched = mutableListOf<String>()
        pkg.sharedServices.filter { it.enabled && it.command.isNotBlank() }.forEach { svc ->
            val (sc, sout) = QuroLinuxEnv.run(context, "sh ${engineGuestDir()}/services/${svc.id}.sh", timeoutMs = 20_000)
            if (sc == 0) {
                launched.add(svc.id)
                CmsEngineStore.appendLog("引擎服务 ${svc.name} 已拉起（端口 ${svc.port}）")
            } else {
                CmsEngineStore.appendLog("引擎服务 ${svc.name} 启动返回 $sc：${sout.take(120)}")
            }
        }

        // 健康检查：确认就绪标记
        val (hc, _) = QuroLinuxEnv.run(context, "[ -f ${engineGuestDir()}/.engine.ready ]", timeoutMs = 10_000)
        val health = hc == 0

        CmsEngineStore.markDeployed(pkg.engineVersion, launched, health)
        sb.appendLine("🚀 CMS引擎部署完成（v${pkg.engineVersion}）${if (health) "，健康检查通过" else "，但就绪标记缺失"}。")
        if (launched.isNotEmpty()) sb.appendLine("已拉起共享服务：${launched.joinToString(", ")}")
        return sb.toString()
    }
}
