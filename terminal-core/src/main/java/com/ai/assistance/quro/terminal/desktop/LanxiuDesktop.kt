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
 * 但**没有 VNC/RDP 后端 .so**（Debian/Ubuntu 的 weston 包根本不编译 vnc-backend），而且
 * 上游 qiyuan/澜岫同源也只有 drm/headless/wayland/x11 四个后端。所以**不可能**靠"烤入
 * vnc-backend.so"实现 in-app 桌面。
 *
 * 真正的、可自包含运行的方案（本文件实现的就是这个）：
 *
 * 1. [OutputMode.VNC]（默认，in-app 原生渲染）：用 Ubuntu noble 的 **Xtigervnc**（虚拟 X
 *    服务器 + VNC 一体，随 rootfs 烤入 `usr/bin/Xtigervnc` 及其依赖库）在 proot 内起一个
 *    `:2` 显示（VNC 端口 5902 = [VNC_PORT]），监听回环；再用 rootfs 自带的 `weston`
 *    `--backend=x11-backend.so --renderer=pixman` 把桌面渲染进那个 X 窗口（纯软件渲染，
 *    不依赖 GPU/GL 驱动）。App 内 [com.ai.assistance.quro.terminal.utils.VncClient]（RFB
 *    3.8，回环直连）连 127.0.0.1:5902 原生取帧，**真能看见桌面**，触摸当鼠标。
 *    不依赖任何外部 App、不依赖 GPU。
 *
 * 2. [OutputMode.X11]（Termux:X11 备选，最稳）：用 rootfs 自带的 `x11-backend.so`，
 *    设 `DISPLAY=:0` 把 Weston 接到 Termux:X11 这个 X 服务器，**零 rootfs 改动**，
 *    但桌面渲染在 **Termux:X11 App 的另一个窗口**（脱离 ZorvAI 界面）。
 *
 * 生命周期：在 LANXIU 的 proot 内先起 Xtigervnc（VNC 模式）或直连 Termux:X11（X11 模式），
 * 再 `nohup weston ... &` 常驻。VNC 模式由 `seatd` 提供 seat。
 *
 * ⚠️ 设备侧真机验证项（本机无法验证）：
 *    - VNC 模式：Xtigervnc + weston x11-backend(pixman) 能否在 proot 内常驻、VncClient
 *      能否取到帧缓冲（桌面画面）。
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

    /**
     * 🔴 构建标记（每次出包必须改成新的日期时刻）。
     *
     * 为什么需要它：versionName/versionCode 是用户锁定的（1.1.10/1001010，铁律不改），
     * 导致**每一版 APK 的版本号完全一样**；桌面上又常同时躺着几个同尺寸（~823MB）、
     * 文件名只差日期的包。真机上一旦装错包，界面/日志与旧包毫无区别，只能靠猜版本
     * （2026-10-20 已经为此白跑两轮）。
     *
     * 这个标记同时出现在两处：① LANXIU 设置卡片上的「构建」字样（宿主侧常量）；
     * ② 启动脚本第一行的 echo（guest 侧），因此能一句话区分：
     *    - 界面看到新标记、红字里没有       -> 脚本没进 guest（proot/exec 通道问题）
     *    - 界面看到新标记、红字里有新标记   -> 脚本进来了，失败在后面环节
     *    - 界面看不到新标记                 -> 装的不是这个包（安装没生效/装错了旧包）
     */
    const val BUILD_STAMP = "B20261020-1415"

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
     * Weston 后端参数。VNC 模式用 rootfs 自带的 `x11-backend.so` + `--renderer=pixman`
     * （纯软件渲染，不依赖 GPU/GL 驱动），把桌面渲染进 Xtigervnc 提供的虚拟 X 显示；
     * X11 模式同理但连 Termux:X11（DISPLAY=:0）。
     */
    private fun backendArgsFor(mode: OutputMode): String =
        "--backend=x11-backend.so --renderer=pixman"

    /**
     * 构造 Weston 启动脚本（按输出模式选后端）。脚本的 XDG/seatd/pgrep 健壮性逻辑保持
     * 硬编码（铁律：不破坏已跑通链路）。VNC 模式额外先起 Xtigervnc（虚拟 X+VNC 一体）。
     */
    private fun buildStartScript(mode: OutputMode): String {
        val backend = backendArgsFor(mode)
        return if (mode == OutputMode.X11) {
        // X11 后端：连 Termux:X11 的 X 服务器（DISPLAY=:0），不需要 seatd。
        """
        echo "[lanxiu-boot ${BUILD_STAMP}] mode=x11 cwd=$(pwd 2>/dev/null) uid=$(id -u 2>/dev/null)"
        export XDG_RUNTIME_DIR=/tmp/lanxiu-xdg
        mkdir -p /tmp/lanxiu-xdg
        chmod 700 /tmp/lanxiu-xdg
        # 关掉残留 weston（VNC 或 X11 都叫 weston）
        killall weston 2>/dev/null || true
        sleep 1
        # 指向 Termux:X11 提供的 X 服务器
        export DISPLAY=:0
        nohup weston $backend >/tmp/weston-x11.log 2>&1 &
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
        // VNC 模式（in-app 原生渲染）：Xtigervnc 起虚拟 X(:2 → VNC 5902，回环只听 localhost)
        // + weston x11-backend(pixman) 把桌面渲染进该 X；App 内 VncClient 直连 5902。
        """
        # 自证脚本真的进来了：放第一行，且带构建标记。上一版因 proot -w /root 翻译失败，bash 只打
        # 一句 getcwd 告警、脚本体一行没跑，界面看不出区别。这行能立刻区分「没进来」和「进来了但起不来」。
        echo "[lanxiu-boot ${BUILD_STAMP}] mode=vnc cwd=$(pwd 2>/dev/null) uid=$(id -u 2>/dev/null)"
        export XDG_RUNTIME_DIR=/tmp/lanxiu-xdg
        mkdir -p /tmp/lanxiu-xdg
        chmod 700 /tmp/lanxiu-xdg
        # seatd：Weston 在 proot 内仍需一个 seat（x11 后端不接真实输入设备）
        pgrep -x seatd >/dev/null 2>&1 || (seatd -g 0 -t /tmp/lanxiu-seatd.sock >/tmp/seatd.log 2>&1 &)
        sleep 1
        export SEATD_SOCK=/tmp/lanxiu-seatd.sock
        # 重建 ld.so 缓存：rootfs 带 /etc/ld.so.cache，但本次烤入的 15 个新库不在缓存里，
        # 不重建则 Xtigervnc 运行时找不到 libgcrypt/libgnutls 等（glibc 优先用缓存）。
        ldconfig 2>/dev/null || true
        # 关掉残留 weston / Xtigervnc（VNC 或 X11 都叫 weston；VNC 重起要清旧 Xtigervnc）
        killall weston 2>/dev/null || true
        killall Xtigervnc 2>/dev/null || true
        sleep 1
        # 预检：rootfs 必须已真的解压出含 Xtigervnc 的资产版本。否则 execve 只回 ENOENT，界面只能看到
        # "nohup: failed to run command 'Xtigervnc': No such file or directory"，根本看不出是
        # 「rootfs 没升级」还是「loader 找不到」（2026-10-19 真机踩坑）。故先自证文件在不在。
        if [ ! -x /usr/bin/Xtigervnc ]; then
          echo "启动失败：/usr/bin/Xtigervnc 不存在（桌面组件未就位）"
          echo -n "rootfs 资产标记: "; cat /.quro_asset_v 2>/dev/null || echo "(无标记 = 旧 rootfs)"
          echo -n "增量包残留检查: "; ls -d /usr/lib/aarch64-linux-gnu/libgnutls.so.30 2>&1
          ls -l /usr/bin/Xtigervnc 2>&1
          exit 8
        fi
        # 动态链接自检：loader 直接报缺哪些库，比 execve 的裸 ENOENT 可诊断得多。
        # （ENOENT 既可能是「文件不存在」，也可能是「PT_INTERP 解析不到 loader」，两者报错一模一样。）
        /usr/lib/ld-linux-aarch64.so.1 --list /usr/bin/Xtigervnc 2>&1 | grep -i "not found" >/tmp/xt_missing.txt || true
        if [ -s /tmp/xt_missing.txt ]; then
          echo "启动失败：Xtigervnc 缺动态库"
          head -n 8 /tmp/xt_missing.txt
          exit 10
        fi
        if ! command -v weston >/dev/null 2>&1; then
          echo "启动失败：weston 不在 PATH"
          exit 9
        fi
        # X 的 unix socket 目录：Xtigervnc 起 X 时要用，缺失会直接失败。
        mkdir -p /tmp/.X11-unix 2>/dev/null
        chmod 1777 /tmp/.X11-unix 2>/dev/null
        # 🔴 清僵尸锁（2026-10-20）：Xtigervnc 被 killall 后 /tmp/.X2-lock 常残留（/tmp 是宿主
        # filesDir/tmp 的 bind，进程死了文件还在），下次启动会直接报
        # "Server is already active for display 2" 并退出 —— 表现为「怎么点都起不来」。
        rm -f /tmp/.X2-lock /tmp/.X11-unix/X2 2>/dev/null
        # 字体路径：misc 字体目录存在才传 -fp，否则 Xtigervnc 会因找不到默认字体退出。
        FP=""
        [ -d /usr/share/fonts/X11/misc ] && FP="-fp /usr/share/fonts/X11/misc"
        # Xtigervnc：虚拟 X 服务器 + VNC 一体。:2 => TCP 5902（= VNC_PORT）。
        # -ac 关掉 X 访问控制（避免无 XAUTHORITY 时被拒）；-rfbport 显式绑 5902 不靠推算；
        # -SecurityTypes None -localhost 仅回环免密，与 App 内 VncClient（RFB 3.8，
        # 优先 VncAuth、仅服务端给 None 时走免密）兼容。
        export DISPLAY=:2
        nohup /usr/bin/Xtigervnc :2 -geometry 1280x800 -depth 24 \
          -rfbport 5902 -ac ${'$'}FP \
          -SecurityTypes None -localhost \
          >/tmp/xtigervnc.log 2>&1 &
        sleep 3
        if ! pgrep -f Xtigervnc >/dev/null 2>&1; then
          echo "启动失败：Xtigervnc 进程未存活，日志："
          tail -n 20 /tmp/xtigervnc.log
          exit 11
        fi
        # weston 接进 Xtigervnc 的 X 显示，纯软件渲染（pixman），不依赖 GPU/GL。
        export DISPLAY=:2
        nohup weston --backend=x11-backend.so --renderer=pixman \
          >/tmp/weston-vnc.log 2>&1 &
        sleep 3
        if pgrep -x weston >/dev/null 2>&1 && pgrep -x Xtigervnc >/dev/null 2>&1; then
          echo "LANXIU 桌面已启动 · VNC 端口 5902（App 内可见）"
        else
          echo "启动失败，日志："
          echo "---- weston ----"; tail -n 30 /tmp/weston-vnc.log
          echo "---- Xtigervnc ----"; tail -n 30 /tmp/xtigervnc.log
          exit 7
        fi
        """.trimIndent()
    }
}
}
