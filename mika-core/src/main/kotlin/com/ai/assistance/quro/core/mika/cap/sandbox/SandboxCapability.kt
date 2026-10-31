package com.ai.assistance.quro.core.mika.cap.sandbox

import com.ai.assistance.quro.core.mika.capability.Capability
import com.ai.assistance.quro.core.mika.capability.CapabilityKey
import com.ai.assistance.quro.core.mika.capability.CapabilityStatus
import com.ai.assistance.quro.core.mika.capability.PromptEnv
import com.ai.assistance.quro.core.mika.guard.OffScreenGuard
import com.ai.assistance.quro.core.mika.knowledge.ScriptManifest
import com.ai.assistance.quro.core.mika.knowledge.ScriptState
import com.ai.assistance.quro.core.mika.knowledge.BundledScripts
import com.ai.assistance.quro.core.mika.prompt.Prompts
import com.ai.assistance.quro.core.mika.tool.AgentTool
import com.ai.assistance.quro.core.mika.tool.ToolContext
import com.ai.assistance.quro.core.mika.workspace.SandboxPaths

/**
 * Linux 沙箱:命令、技能脚本、工作区文件。设备支持时提供工具(运行环境没准备好时工具调用会等它);
 * 不是屏幕操作,不过锁屏关卡,也不进屏幕守卫,由 [SandboxGuard] 另管。
 */
internal class SandboxCapability(
    private val port: SandboxPort?,
    /** 本次任务的工作区(对话 ID)。 */
    workspace: () -> String,
    visionEnabled: Boolean,
    onCommand: (String) -> Unit,
    onProgress: (String) -> Unit,
    onPreparing: () -> Unit,
    onScript: ((String) -> Unit)?,
) : Capability {
    override val id = SANDBOX

    val runner = port?.let { SandboxTools(it, workspace, visionEnabled, onCommand = onCommand, onProgress = onProgress, onPreparing = onPreparing, onScript = onScript) }

    /** 设备支持沙箱(工具已提供,可能还在准备)。 */
    fun available(): Boolean = runner?.available() == true

    override val folds = true

    override fun status(): CapabilityStatus = when (val s = port?.status()) {
        null -> CapabilityStatus.Unavailable("no sandbox")
        is SandboxStatus.Ready -> CapabilityStatus.Available
        is SandboxStatus.NotInstalled -> CapabilityStatus.Degraded("not installed")
        is SandboxStatus.Installing -> CapabilityStatus.Degraded(s.step)
        is SandboxStatus.Unsupported -> CapabilityStatus.Unavailable(s.reason)
        is SandboxStatus.Broken -> CapabilityStatus.Unavailable(s.reason)
    }

    override fun tools(): List<AgentTool> = SandboxGroup.tools

    /** 这次的话带着工作区里的文件(附件一行写着 /workspace/ 下的路径):开局就展开沙箱一组。 */
    override fun preload(task: String): Boolean = available() && "${SandboxPaths.WORKSPACE}/" in task

    /** 沙箱那一组收着时不给。 */
    override fun sections(env: PromptEnv): List<String> {
        if (!env.isOpen(id) || SandboxGroup.tools.none { it.name in env.offered }) return emptyList()
        val p = port?.takeIf { available() }
        return listOf(env.gate(section(p?.status() ?: SandboxStatus.NotInstalled, p?.networkAllowed() == true)))
    }

    override val scripts = object : BundledScripts {
        override fun offered() = available()

        override suspend fun ready(): Boolean {
            val p = port ?: return false
            if (p.status() !is SandboxStatus.Ready) return false
            p.prepare()
            return true
        }

        override fun scripts(skillDir: String): List<ScriptState> {
            val network = (port?.status() as? SandboxStatus.Ready)?.network == true
            return port?.scriptManifest(skillDir).orEmpty().map { e ->
                val (run, reason) = ScriptManifest.effectiveRunnable(e, network)
                ScriptState(e.file, run, reason)
            }
        }
    }

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    override fun guard(): OffScreenGuard = SandboxGuard()

    companion object {
        val KEY = CapabilityKey<SandboxCapability>(SANDBOX)

        /** 沙箱一段:写明此刻是否联网([network]:用户的联网设置);运行环境还没准备好时多一句,第一次用工具会等它。 */
        fun section(status: SandboxStatus, network: Boolean): String {
            val s = Prompts.capability(SANDBOX).replace("{{network}}", if (network) NETWORK_ON else NETWORK_OFF)
            return if (status is SandboxStatus.Ready) s else "$s\n- $PREPARING"
        }

        const val PREPARING = "The environment is still being set up in the background. The first tool call waits for it (up to about two minutes); if it is still not ready, the tool says so."

        const val NETWORK_ON = "on. The user allowed the sandbox to use the network; anything you send out leaves the phone, so only fetch what the task needs."
        const val NETWORK_OFF = "off. Commands cannot reach the internet (no pip/npm/apk installs, no downloads). If the task really needs the network, tell the user they can turn on \"Allow scripts to go online\" (允许脚本联网) in ZorvAI's settings."
    }
}

/** 本任务的沙箱执行;没有沙箱为 null。 */
internal val ToolContext.sandboxRunner: SandboxTools? get() = this[SandboxCapability.KEY]?.runner

internal fun ToolContext.sandboxAvailable(): Boolean = this[SandboxCapability.KEY]?.available() == true
