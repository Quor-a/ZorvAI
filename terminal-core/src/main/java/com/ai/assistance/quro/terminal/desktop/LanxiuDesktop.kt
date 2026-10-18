package com.ai.assistance.quro.terminal.desktop

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.assistance.quro.terminal.TerminalManager
import com.ai.assistance.quro.terminal.runtime.Distro
import com.ai.assistance.quro.terminal.runtime.GuestEnvStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LANXIU 图形桌面启动器。
 *
 * LANXIU 是 Wayland/Weston 14 桌面发行版，rootfs 里已有 `weston` + `seatd` + `qystore`，
 * 但**没有 VNC/RDP 后端 .so**。要在 ZorvAI 里把桌面呈现出来，提供两种输出模式：
 *
 * 1. [OutputMode.VNC]（默认，in-app 原生渲染）：用 Weston 自带的 **VNC 后端**
 *    （`vnc-backend.so`，随 rootfs 一起烤入）把桌面喂给 Android 侧现成的
 *    [com.ai.assistance.quro.terminal.utils.VncClient]（RFB 3.8，回环直连，零网卡开销）。
 *    App 内 [LanxiuDesktopScreen] 直接原生渲染，触摸当鼠标。
 *
 * 2. [OutputMode.X11]（Termux:X11 备选，最稳）：用 rootfs 自带的 `x11-backend.so`，
 *    设 `DISPLAY=:0` 把 Weston 接到 Termux:X11 这个 X 服务器，**零 rootfs 改动**，
 *    但桌面渲染在 **Termux:X11 App 的另一个窗口**（脱离 ZorvAI 界面）。
 *
 * 生命周期：在 LANXIU 的 proot 内 `nohup weston --backend=... &` 常驻。VNC 模式由 `seatd`
 * 提供 seat；X11 模式由 Termux:X11 提供输入，不需要 seatd。
 *
 * ⚠️ 设备侧真机验证项（本机无法验证）：
 *    - VNC 模式：vnc-backend.so 随 rootfs 烤入后，seatd + weston 能否在 proot 内常驻、
 *      VncClient 能否取到帧缓冲。
 *    - X11 模式：Termux:X11 App 安装后，proot 内 weston 能否经 DISPLAY 连上其 X 服务器。
 */
object LanxiuDesktop {
    private const val TAG = "LanxiuDesktop"
    private const val PREFS = "lanxiu_desktop"
    private const val KEY_MODE = "output_mode"

    /** VNC 监听端口（回环，仅供 App 内 VncClient 连接）。 */
    const val VNC_PORT = 5902

    /** VNC 连接密码（App 内直连，短密码足够）。 */
    const val VNC_PASSWORD = "lanxiu"

    /** 桌面输出模式。 */
    enum class OutputMode {
        /** App 内 VNC 原生渲染（默认）。 */
        VNC,

        /** Termux:X11 窗口渲染（最稳、零 rootfs 改动）。 */
        X11,
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读当前输出模式。默认 X11（Termux:X11，零 rootfs 改动、最稳、立即可用）； */
    fun getOutputMode(context: Context): OutputMode {
        val v = prefs(context).getString(KEY_MODE, "x11")
        return if (v == "x11") OutputMode.X11 else OutputMode.VNC
    }

    /** 写当前输出模式（持久化）。 */
    fun setOutputMode(context: Context, mode: OutputMode) {
        prefs(context).edit().putString(KEY_MODE, if (mode == OutputMode.X11) "x11" else "vnc").apply()
    }

    /**
     * 在已就绪的 LANXIU 内启动 Weston（按 [mode] 选后端）。需先切到 LANXIU。
     * 返回 `Pair(退出码, 输出)`：退出码 0=成功。
     */
    suspend fun start(context: Context, mode: OutputMode = getOutputMode(context)): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val mgr = TerminalManager.getInstance(context)
            if (mgr.getDistro() != Distro.LANXIU) {
                return@withContext Pair(-1, "请先在双系统里切换到 LANXIU 再启动桌面")
            }
            if (mgr.guestEnvStatus(Distro.LANXIU) != GuestEnvStatus.READY) {
                return@withContext Pair(-2, "LANXIU 环境未就绪（请先下载/初始化）")
            }
            Log.i(TAG, "启动 LANXIU 图形桌面（${mode.name} 后端，端口 $VNC_PORT）")
            val r = mgr.runInGuest(buildStartScript(mode), 30_000)
            Pair(r.code, r.output)
        }

    /** 停止 Weston（+ VNC 模式的 seatd）。返回 `Pair(退出码, 输出)`。 */
    suspend fun stop(context: Context): Pair<Int, String> = withContext(Dispatchers.IO) {
        val mgr = TerminalManager.getInstance(context)
        val r = mgr.runInGuest(
            "killall weston 2>/dev/null; killall seatd 2>/dev/null; sleep 1; echo stopped",
            15_000
        )
        Pair(r.code, r.output)
    }

    /**
     * 查询 Weston/seatd 是否运行。返回 `Pair(退出码, 输出)`：
     * 退出码 0 = 运行中（输出含 RUNNING + 进程行）；非 0 = 未运行（输出 "not running"）。
     *
     * ⚠️ 注意：脚本**不能**用 `pgrep ... || echo` 结尾，否则 echo 会把退出码抹成 0，
     * 导致调用方（LanxiuDesktopScreen 的"是否需要启动"判断）失效。这里明确 exit 1。
     */
    suspend fun status(context: Context): Pair<Int, String> = withContext(Dispatchers.IO) {
        val mgr = TerminalManager.getInstance(context)
        val r = mgr.runInGuest(
            "if pgrep -af 'weston|seatd' >/dev/null 2>&1; then echo RUNNING; pgrep -af 'weston|seatd'; exit 0; else echo 'not running'; exit 1; fi",
            15_000
        )
        Pair(r.code, r.output)
    }

    /**
     * 构造 Weston 启动脚本（按输出模式选后端）。全程不使用 shell 变量展开以外的复杂插值，
     * 所有路径写死，避免 Kotlin 字符串插值冲突。
     */
    private fun buildStartScript(mode: OutputMode): String = if (mode == OutputMode.X11) {
        // X11 后端：连 Termux:X11 的 X 服务器（DISPLAY=:0），不需要 seatd。
        """
        export XDG_RUNTIME_DIR=/tmp/lanxiu-xdg
        mkdir -p /tmp/lanxiu-xdg
        chmod 700 /tmp/lanxiu-xdg
        # 关掉残留 weston（VNC 或 X11 都叫 weston）
        killall weston 2>/dev/null || true
        sleep 1
        # 指向 Termux:X11 提供的 X 服务器
        export DISPLAY=:0
        nohup weston --backend=x11-backend.so >/tmp/weston-x11.log 2>&1 &
        sleep 3
        if pgrep -x weston >/dev/null 2>&1; then
          echo "LANXIU 桌面已启动 · X11 后端 → 请在 Termux:X11 App 查看"
        else
          echo "启动失败，日志："
          tail -n 30 /tmp/weston-x11.log
          exit 7
        fi
        """.trimIndent()
    } else {
        // VNC 后端：seatd 提供 seat + vnc-backend.so 喂 App 内 VncClient。
        """
        export XDG_RUNTIME_DIR=/tmp/lanxiu-xdg
        mkdir -p /tmp/lanxiu-xdg
        chmod 700 /tmp/lanxiu-xdg
        # seatd：Weston 在 proot 内仍需一个 seat（VNC 后端不接真实输入设备）
        pgrep -x seatd >/dev/null 2>&1 || (seatd -g 0 -t /tmp/lanxiu-seatd.sock >/tmp/seatd.log 2>&1 &)
        sleep 1
        export SEATD_SOCK=/tmp/lanxiu-seatd.sock
        # 关掉残留 weston
        killall weston 2>/dev/null || true
        sleep 1
        cat > /tmp/weston-vnc.ini <<'INI'
        [core]
        backend=vnc-backend.so

        [vnc]
        port=5902
        refresh=30

        [shell]
        background-color=0x002b36

        [keyboard]
        keymap_rules=evdev

        [output]
        resolution=1280x800
        INI
        nohup weston --config=/tmp/weston-vnc.ini --backend=vnc-backend.so >/tmp/weston-vnc.log 2>&1 &
        sleep 3
        if pgrep -x weston >/dev/null 2>&1; then
          echo "LANXIU 桌面已启动 · VNC 端口 5902"
        else
          echo "启动失败，日志："
          tail -n 30 /tmp/weston-vnc.log
          exit 7
        fi
        """.trimIndent()
    }
}
