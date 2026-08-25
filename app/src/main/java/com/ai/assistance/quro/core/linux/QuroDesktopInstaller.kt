package com.ai.assistance.quro.core.linux

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import com.ai.assistance.quro.util.QuroDiag

/**
 * Linux桌面环境安装器
 * 
 * 安装XFCE桌面环境和VNC服务器，提供完整的图形界面
 */
object QuroDesktopInstaller {
    
    private const val TAG = "QuroDesktopInstaller"
    
    sealed interface DesktopState {
        data object NotInstalled : DesktopState
        data class Installing(val detail: String = "") : DesktopState
        data object Ready : DesktopState
        data class Error(val message: String) : DesktopState
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DesktopState>(DesktopState.NotInstalled)
    val state: StateFlow<DesktopState> = _state
    
    private val installMutex = Mutex()
    private var installJob: Job? = null
    
    /**
     * 检查桌面环境是否已安装
     */
    fun probe(context: Context): Boolean {
        val result = QuroLinuxEnv.run(context, "which Xvfb && which x11vnc")
        return result.first == 0 && result.second.contains("Xvfb") && result.second.contains("x11vnc")
    }
    
    /**
     * 安装桌面环境
     */
    fun install(context: Context) {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            if (!installMutex.tryLock()) return@launch
            try {
                installInternal(context)
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                _state.value = DesktopState.Error(e.message ?: "安装失败")
            } finally {
                installMutex.unlock()
            }
        }
    }
    
    /**
     * 取消安装
     */
    fun cancelInstall() {
        installJob?.cancel()
        installJob = null
    }
    
    private suspend fun installInternal(context: Context) {
        // 检查Linux环境是否就绪
        val envStatus = QuroLinuxEnv.probe(context)
        if (!envStatus.available) {
            throw IllegalStateException("Linux环境未就绪，请先安装Linux环境")
        }
        
        _state.value = DesktopState.Installing("更新软件包列表…")
        
        // 更新软件包列表
        val updateResult = QuroLinuxEnv.run(context, "apt-get update", timeoutMs = 60_000)
        if (updateResult.first != 0) {
            throw IllegalStateException("更新软件包列表失败：${updateResult.second}")
        }
        
        _state.value = DesktopState.Installing("安装XFCE桌面环境…")
        
        // 安装XFCE桌面环境
        val xfceResult = QuroLinuxEnv.run(
            context,
            "apt-get install -y --no-install-recommends xfce4 xfce4-terminal thunar",
            timeoutMs = 300_000
        )
        if (xfceResult.first != 0) {
            throw IllegalStateException("安装XFCE失败：${xfceResult.second}")
        }
        
        _state.value = DesktopState.Installing("安装虚拟显示和VNC服务器…")
        
        // 安装Xvfb虚拟显示和x11vnc服务器
        val vncResult = QuroLinuxEnv.run(
            context,
            "apt-get install -y --no-install-recommends xvfb x11vnc",
            timeoutMs = 120_000
        )
        if (vncResult.first != 0) {
            throw IllegalStateException("安装虚拟显示和VNC服务器失败：${vncResult.second}")
        }
        
        _state.value = DesktopState.Installing("安装额外工具…")
        
        // 安装额外工具
        val toolsResult = QuroLinuxEnv.run(
            context,
            "apt-get install -y --no-install-recommends dbus dbus-x11 xorg-server xorg-applications",
            timeoutMs = 120_000)
        if (toolsResult.first != 0) {
            QuroDiag.log(TAG, "⚠ 安装额外工具失败，但不影响基本功能")
        }
        
        _state.value = DesktopState.Installing("安装noVNC和websockify…")
        
        // 安装noVNC和websockify
        val novncResult = QuroLinuxEnv.run(
            context,
            "apt-get install -y --no-install-recommends novnc websockify",
            timeoutMs = 120_000
        )
        if (novncResult.first != 0) {
            QuroDiag.log(TAG, "⚠ 安装noVNC失败，但不影响基本功能")
        }
        
        _state.value = DesktopState.Installing("配置桌面环境…")
        
        // 创建启动脚本
        val startScript = "#!/bin/sh\n" +
            "export DISPLAY=:99\n" +
            "export XDG_RUNTIME_DIR=/tmp/runtime-root\n" +
            "export TMPDIR=/tmp/custom-tmp\n" +
            "mkdir -p \$XDG_RUNTIME_DIR\n" +
            "mkdir -p \$TMPDIR\n" +
            "chmod 700 \$XDG_RUNTIME_DIR\n" +
            "chmod 700 \$TMPDIR\n" +
            "\n" +
            "# 清理残留锁文件\n" +
            "rm -f /tmp/.X99-lock\n" +
            "rm -f /tmp/custom-tmp/.X99-lock\n" +
            "\n" +
            "# 启动Xvfb虚拟显示\n" +
            "Xvfb :99 -screen 0 1280x720x24 &\n" +
            "\n" +
            "# 等待Xvfb启动\n" +
            "sleep 2\n" +
            "\n" +
            "# 启动x11vnc服务器\n" +
            "x11vnc -display :99 -forever -shared -rfbport 5900 &\n" +
            "\n" +
            "# 启动websockify和noVNC\n" +
            "websockify --web /usr/share/novnc 6080 localhost:5900 &\n" +
            "\n" +
            "echo \"桌面环境已启动\"\n" +
            "echo \"VNC地址: localhost:5900\"\n" +
            "echo \"noVNC地址: http://localhost:6080/vnc.html\"\n" +
            "echo \"密码: 无密码（首次启动时设置）\"\n"
        
        val scriptResult = QuroLinuxEnv.run(
            context,
            "echo '$startScript' > /root/start-desktop.sh && chmod +x /root/start-desktop.sh"
        )
        if (scriptResult.first != 0) {
            QuroDiag.log(TAG, "⚠ 创建启动脚本失败")
        }
        
        // 创建VNC配置
        val vncConfig = """
            geometry=1280x720
            depth=24
            alwaysshared
        """.trimIndent()
        
        val configResult = QuroLinuxEnv.run(
            context,
            "mkdir -p /root/.vnc && echo '$vncConfig' > /root/.vnc/config"
        )
        if (configResult.first != 0) {
            QuroDiag.log(TAG, "⚠ 创建VNC配置失败")
        }
        
        _state.value = DesktopState.Ready
        QuroDiag.log(TAG, "✅ 桌面环境安装完成")
    }
    
    /**
     * 启动桌面环境
     */
    fun startDesktop(context: Context): Pair<Int, String> {
        return QuroLinuxEnv.run(context, "/root/start-desktop.sh")
    }
    
    /**
     * 停止桌面环境
     */
    fun stopDesktop(context: Context): Pair<Int, String> {
        return QuroLinuxEnv.run(context, "killall x11vnc; killall Xvfb")
    }
    
    /**
     * 获取VNC连接信息
     */
    fun getVncInfo(context: Context): String {
        val result = QuroLinuxEnv.run(context, "ps aux | grep -E '(Xvfb|x11vnc)' | grep -v grep")
        return if (result.first == 0 && result.second.isNotEmpty()) {
            "VNC服务器状态：运行中\n${result.second}"
        } else {
            "VNC服务器未运行"
        }
    }
}