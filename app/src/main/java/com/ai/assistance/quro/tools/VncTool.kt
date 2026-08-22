package com.ai.assistance.quro.tools

import android.content.Context
import com.ai.assistance.quro.core.tools.QuroTool
import com.ai.assistance.quro.core.linux.QuroDesktopInstaller
import com.ai.assistance.quro.core.linux.QuroLinuxEnv
import org.json.JSONObject

/**
 * VNC工具 - 让AI能够控制VNC桌面环境
 */
class VncTool : QuroTool {
    override val name: String = "vnc"
    override val description: String = "控制VNC虚拟桌面环境：安装、启动、停止、状态查询"
    override val parametersJson: String = getDescription().toString()
    
    /**
     * 工具描述 - 提供给AI的工具定义
     */
    fun getDescription(): JSONObject {
        return JSONObject().apply {
            put("name", "vnc")
            put("description", "控制VNC虚拟桌面环境：安装、启动、停止、状态查询")
            put("parameters", JSONObject().apply {
                put("action", JSONObject().apply {
                    put("type", "string")
                    put("description", "操作类型：install（安装）、start（启动）、stop（停止）、status（状态）")
                    put("enum", org.json.JSONArray().apply {
                        put("install")
                        put("start")
                        put("stop")
                        put("status")
                    })
                })
            })
            put("required", org.json.JSONArray().apply {
                put("action")
            })
        }
    }
    
    /**
     * 执行工具
     */
    override fun run(context: Context, arguments: String): String {
        return try {
            val json = JSONObject(arguments)
            val action = json.optString("action", "status")
            
            when (action) {
                "install" -> install(context)
                "start" -> start(context)
                "stop" -> stop(context)
                "status" -> status(context)
                else -> "未知操作：$action"
            }
        } catch (e: Exception) {
            "工具执行失败：${e.message}"
        }
    }
    
    /**
     * 安装VNC环境
     */
    private fun install(context: Context): String {
        return try {
            QuroDesktopInstaller.install(context)
            "VNC环境安装已启动，请稍候..."
        } catch (e: Exception) {
            "安装失败：${e.message}"
        }
    }
    
    /**
     * 启动VNC
     */
    private fun start(context: Context): String {
        return try {
            val result = QuroDesktopInstaller.startDesktop(context)
            if (result.first == 0) {
                "VNC服务器已启动\n" +
                "访问地址：http://localhost:6080/vnc.html\n" +
                "VNC端口：localhost:5900"
            } else {
                "启动失败：${result.second}"
            }
        } catch (e: Exception) {
            "启动失败：${e.message}"
        }
    }
    
    /**
     * 停止VNC
     */
    private fun stop(context: Context): String {
        return try {
            val result = QuroDesktopInstaller.stopDesktop(context)
            if (result.first == 0) {
                "VNC服务器已停止"
            } else {
                "停止失败：${result.second}"
            }
        } catch (e: Exception) {
            "停止失败：${e.message}"
        }
    }
    
    /**
     * 查询状态
     */
    private fun status(context: Context): String {
        return try {
            val installed = QuroDesktopInstaller.probe(context)
            val vncInfo = QuroDesktopInstaller.getVncInfo(context)
            
            buildString {
                appendLine("VNC环境状态：")
                appendLine("已安装：${if (installed) "是" else "否"}")
                appendLine(vncInfo)
                if (!installed) {
                    appendLine("\n提示：使用 vnc(action=\"install\") 安装VNC环境")
                }
            }
        } catch (e: Exception) {
            "查询失败：${e.message}"
        }
    }
}