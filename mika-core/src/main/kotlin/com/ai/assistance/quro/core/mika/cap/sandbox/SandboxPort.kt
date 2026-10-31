package com.ai.assistance.quro.core.mika.cap.sandbox

import java.io.File
import com.ai.assistance.quro.core.mika.knowledge.ScriptEntry
import com.ai.assistance.quro.core.mika.workspace.WorkspaceFileGate
import com.ai.assistance.quro.core.mika.workspace.SandboxTree
import kotlinx.serialization.json.JsonObject

/** 沙箱命令请求手机工具时,交回本任务的动作执行入口。 */
fun interface PhoneCommandHandler {
    suspend fun call(tool: String, args: JsonObject): JsonObject
}

sealed interface SandboxStatus {
    data class Unsupported(val reason: String) : SandboxStatus
    data object NotInstalled : SandboxStatus
    data class Installing(val percent: Int, val step: String) : SandboxStatus
    data class Ready(val network: Boolean) : SandboxStatus
    data class Broken(val reason: String) : SandboxStatus
}

/** Agent 接触 Linux 沙箱的唯一入口;路径都是沙箱里的路径([SandboxPaths])。 */
interface SandboxPort {
    /** 沙箱目录的树锁:往工作区写的都在它的许可里写。 */
    val tree: SandboxTree

    fun status(): SandboxStatus

    /** 用户是否允许沙箱联网(运行环境还没准备好时也知道);默认按就绪状态里的。 */
    fun networkAllowed(): Boolean = (status() as? SandboxStatus.Ready)?.network == true

    /** [cwd]:`/workspace` 下的相对路径,null 为 `/workspace` 本身。 */
    suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit): CommandResult

    /** 当前任务提供手机工具入口时，命令可通过桥接调用；旧实现仍可只实现 [run]。 */
    suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit, phone: PhoneCommandHandler?): CommandResult =
        run(workspace, argv, cwd, timeoutMs, onProgress)

    /** 沙箱路径对应的宿主文件;越界(含符号链接指到外面)或 [forWrite] 时不在工作区下,返回 null。只用来判断,读写走 [files]。 */
    fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File?

    /** 宿主侧读写沙箱目录里的文件都过这一道:打开一次、在句柄上核实、再在这个句柄上读写。 */
    val files: WorkspaceFileGate

    /** 规范化后的沙箱路径 → (宿主根目录, 根下相对路径),交给 [files];技能目录只读,越界为 null。工作区目录不存在时建上。 */
    fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>?

    /** 存到手机的「下载/ZorvAI」,返回 "Download/ZorvAI/<名字>"。 */
    suspend fun share(file: File, name: String): String

    fun freeBytes(): Long
    fun workspaceBytes(workspace: String): Long

    /**
     * 存完整输出到 /workspace/.quro/out-<n>.txt,返回沙箱路径。经 [files] 新建(.quro 被换成链接等时抛异常,不写任何东西)。
     */
    fun saveOutput(workspace: String, text: String): String

    /** [skillPath]:`/opt/quro/skills` 下的技能目录(相对路径);读随 App 打包的那份清单,没有为空。 */
    fun scriptManifest(skillPath: String): List<ScriptEntry>

    /**
     * 运行环境还没准备好(首次启动解包中、或还没开始)时:开始准备并等它,最多 [timeoutMs];返回等完后的状态。
     * 不支持的设备、准备失败时尽快返回。默认实现不等。
     */
    suspend fun awaitReady(timeoutMs: Long): SandboxStatus = status()

    /** 把 `/opt/quro/skills` 核对成随 App 打包的那份:多出、缺少、改过的都重新同步。失败只记下,不抛。 */
    suspend fun prepare() {}

    /** [skillPath] 下的 [file] 与打包的那份逐字节相同;不同就重新同步一次再核,仍不同为 false。 */
    suspend fun verifyScript(skillPath: String, file: String): Boolean = true
}
