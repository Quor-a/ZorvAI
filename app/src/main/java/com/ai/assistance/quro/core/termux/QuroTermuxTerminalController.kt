package com.ai.assistance.quro.core.termux

import android.content.Context
import android.util.Log
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import java.io.File
import com.ai.assistance.quro.core.termux.terminal.TerminalSession
import com.ai.assistance.quro.core.termux.view.TerminalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Termux 终端全局控制器。
 *
 * 100% 使用 Termux 原版 TerminalSession：JNI 创建 PTY → wrapFileDescriptor → FileInputStream/OutputStream。
 * 本类仅负责 Linux 环境预检，TerminalSession 自行管理所有 I/O。
 */
object QuroTermuxTerminalController {

    private const val TAG = "QuroTerminalCtrl"

    /**
     * ULFA 可选终端后端开关。默认 false（生产终端仍是 termux PTY + proot 直启）。
     * 置 true 时，Linux 终端改为经 libfusiond.so 拉起（fusiond 内部 openpty + execv proot），
     * 复用 Termux 同一套 PTY 机制，仅替换被拉起的二进制与环境。
     * 仅在真机验证过 fusiond 路径后再开启，未验证前保持 false。
     */
    private const val ULFA_FUSIOND_TERMINAL = true

    var session: TerminalSession? = null
        private set

    /** 进入终端时可选执行的一条初始命令。 */
    var initialCommand: String? = null

    private val _modeLabel = MutableStateFlow("")
    val modeLabel: StateFlow<String> = _modeLabel.asStateFlow()

    private val _cwd = MutableStateFlow("/root")
    val cwd: StateFlow<String> = _cwd.asStateFlow()

    /**
     * 创建终端会话。
     *
     * 流程：
     * 1. 确保 Linux 环境已安装（rootfs 解压 + apt update）
     * 2. 获取 proot 启动参数
     * 3. 创建 Termux 原版 TerminalSession（shellPath=proot, args=proot参数）
     * 4. TerminalView.attachSession() → updateSize() → initializeEmulator()
     *    → JNI.createSubprocess() → wrapFileDescriptor → FileInputStream/OutputStream
     */
    fun start(
        context: Context,
        view: TerminalView,
        onExited: (Int) -> Unit = {},
    ): TerminalSession {
        val appCtx = context.applicationContext

        Log.i(TAG, "========== 终端启动 ==========")

        // 1. 先探测环境状态（不触发安装）
        val envProbe = try {
            QuroLinuxEnv.probe(appCtx)
        } catch (e: Exception) {
            Log.e(TAG, "环境探测异常", e)
            null
        }
        Log.i(TAG, "环境探测结果: available=${envProbe?.available}, reason=${envProbe?.reason}, prootPath=${envProbe?.prootPath}, rootfsPath=${envProbe?.rootfsPath}")

        // 2. 如果未就绪，尝试安装
        val envReady = if (envProbe?.available == true) {
            envProbe
        } else {
            Log.i(TAG, "环境未就绪，尝试安装...")
            try {
                QuroLinuxEnv.ensureInstalledBlocking(appCtx)
            } catch (e: Exception) {
                Log.e(TAG, "Linux 环境安装失败", e)
                null
            }
        }
        Log.i(TAG, "环境安装结果: available=${envReady?.available}, reason=${envReady?.reason}")

        // 3. 获取 proot 启动参数
        val launchSpec = envReady?.let {
            if (it.available) {
                val spec = QuroLinuxEnv.shellLaunch(appCtx)
                Log.i(TAG, "shellLaunch 返回: ${spec != null}, proot=${spec?.first?.takeLast(30)}, args数量=${spec?.second?.size}")
                if (spec == null) {
                    Log.e(TAG, "⚠ shellLaunch 返回 null！环境探测说可用但 launch 失败")
                }
                spec
            } else {
                Log.w(TAG, "环境不可用: ${it.reason}")
                null
            }
        }

        // 3. 确定 shell 路径和参数
        val shellPath: String
        val args: Array<String>
        val env: Array<String>

        val useFusiond = ULFA_FUSIOND_TERMINAL && launchSpec != null
        if (useFusiond) {
            // ULFA 可选后端: 经 fusiond 拉起（内部 openpty + execv proot），复用 Termux PTY 机制
            val fusiondBin = File(appCtx.applicationInfo.nativeLibraryDir, "libfusiond.so").absolutePath
            val ulfaHome = File(QuroLinuxEnv.rootfsPath(appCtx)).parent ?: appCtx.filesDir.absolutePath
            shellPath = fusiondBin
            args = emptyArray()   // fusiond 默认跑 /bin/bash；要指定 shell 可传 argv[1]
            val ulfaEnv = mutableListOf<String>().apply {
                add("ULFA_HOME=$ulfaHome")
                add("ULFA_PROOT=${QuroLinuxEnv.prootPath(appCtx)}")
                // ★ 真机必挂根因修复：PROOT_LOADER 必须注入，proot 靠它定位 ptrace 翻译层
                //   loader。漏掉则 guest 内 execve("/usr/bin/sh") 直接 EFAULT → "Bad address"。
                //   生产 proot 分支走 QuroLinuxEnv.shellEnv() 已含此变量，fusiond 分支手动构造
                //   env 时原先漏了，这里补齐，必须与 shellEnv() 保持一致。
                add("PROOT_LOADER=${QuroLinuxEnv.loaderPath(appCtx)}")
                add("ULFA_BACKEND=proot")
                add("ULFA_ROOT_BIND=${QuroLinuxEnv.homePath(appCtx)}")
                add("ULFA_TMP_BIND=${QuroLinuxEnv.tmpPath(appCtx)}")
                val ws = QuroLinuxEnv.sharedStorageHostDir(appCtx)
                if (ws != null) add("ULFA_WORKSPACE_HOST=${ws.absolutePath}")
                add("TERM=xterm-256color")
                add("HOME=/root")
                add("PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin")
                add("LANG=C.UTF-8")
            }
            env = ulfaEnv.toTypedArray()
            _modeLabel.value = "fusiond/Linux"
            _cwd.value = "/root"
            Log.i(TAG, "✅ 使用 fusiond 启动(ULFA 可选后端): shellPath=$fusiondBin, ULFA_HOME=$ulfaHome")
        } else if (launchSpec != null) {
            val (proot, prootArgs) = launchSpec
            shellPath = proot
            args = prootArgs.toTypedArray()
            env = QuroLinuxEnv.shellEnv(appCtx)
            _modeLabel.value = "proot/Linux"
            _cwd.value = "/root"
            Log.i(TAG, "✅ 使用 proot 启动: shellPath=$shellPath, args=${args.joinToString(" ").take(300)}")
            Log.i(TAG, "环境变量: ${env.joinToString(", ") { it.substringBefore("=") }}")
        } else {
            // 回退到设备 sh
            shellPath = "/system/bin/sh"
            args = emptyArray()
            env = arrayOf(
                "TERM=xterm-256color",
                "HOME=${android.os.Environment.getExternalStorageDirectory().absolutePath}",
                "PATH=/system/bin:/system/xbin:/sbin",
                "LANG=en_US.UTF-8"
            )
            _modeLabel.value = "设备 sh"
            _cwd.value = android.os.Environment.getExternalStorageDirectory().absolutePath
            Log.w(TAG, "⚠ 回退到设备 sh（Ubuntu 命令不可用！）")
            Log.w(TAG, "回退原因: envReady=${envReady?.available}, reason=${envReady?.reason}")
        }

        // 4. 创建 Termux 原版 TerminalSession
        //    initializeEmulator() 会在 TerminalView.onMeasure 时被调用，
        //    届时 JNI.createSubprocess() 会创建 PTY 子进程。
        Log.i(TAG, "创建 TerminalSession: shellPath=$shellPath, cwd=${appCtx.filesDir.absolutePath}")
        val s = TerminalSession(
            shellPath,
            appCtx.filesDir.absolutePath,
            args,
            env,
            null, // transcriptRows
            QuroTermuxSessionClient(appCtx) { view.onScreenUpdated() }
        )
        session = s
        Log.i(TAG, "TerminalSession 已创建，模式: ${_modeLabel.value}")

        return s
    }

    fun destroy() {
        session?.finishIfRunning()
        session = null
    }
}
