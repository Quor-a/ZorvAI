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
    override val description: String = "控制VNC虚拟桌面环境：安装、启动、停止、状态查询，以及对虚拟桌面执行输入控制（tap/type/key）"
    // 🔧 toolfix-vnc：必须为合法 JSON Schema（type:"object"），否则 DeepSeek 报
    // "schema must be a JSON Schema of 'type: \"object\"', got 'type: null'" (HTTP 400)。
    override val parametersJson: String = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description", "操作类型：install（安装）、start（启动）、stop（停止）、status（状态）、tap（点击坐标）、type（输入文本）、key（发送按键）")
                put("enum", org.json.JSONArray().apply {
                    put("install")
                    put("start")
                    put("stop")
                    put("status")
                    put("tap")
                    put("type")
                    put("key")
                })
            })
            put("x", JSONObject().apply {
                put("type", "integer")
                put("description", "点击横坐标（tap 用）")
            })
            put("y", JSONObject().apply {
                put("type", "integer")
                put("description", "点击纵坐标（tap 用）")
            })
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "要输入的文本（type 用）")
            })
            put("key", JSONObject().apply {
                put("type", "string")
                put("description", "按键序列（key 用，如 Return / ctrl+c / alt+F4）")
            })
        })
        put("required", org.json.JSONArray().apply {
            put("action")
        })
    }.toString()
    
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
                "tap" -> tap(context, json)
                "type" -> type(context, json)
                "key" -> key(context, json)
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

    /**
     * VNC 输入：在虚拟桌面点击坐标 (x,y)
     */
    private fun tap(context: Context, json: JSONObject): String {
        val x = json.optInt("x", -1)
        val y = json.optInt("y", -1)
        if (x < 0 || y < 0) return "❌ tap 需要有效的 x/y 坐标"
        return try {
            val r = QuroDesktopInstaller.input(context, "tap", x = x, y = y)
            if (r.first == 0) "✅ 已在虚拟桌面 ($x,$y) 点击" else "❌ 点击失败：${r.second}"
        } catch (e: Exception) { "点击失败：${e.message}" }
    }

    /**
     * VNC 输入：向虚拟桌面当前焦点窗口输入文本
     */
    private fun type(context: Context, json: JSONObject): String {
        val text = json.optString("text", "")
        if (text.isEmpty()) return "❌ type 需要 text"
        return try {
            val r = QuroDesktopInstaller.input(context, "type", text = text)
            if (r.first == 0) "✅ 已输入文本：${text.take(60)}" else "❌ 输入失败：${r.second}"
        } catch (e: Exception) { "输入失败：${e.message}" }
    }

    /**
     * VNC 输入：向虚拟桌面发送按键序列
     */
    private fun key(context: Context, json: JSONObject): String {
        val key = json.optString("key", "")
        if (key.isEmpty()) return "❌ key 需要 key（如 Return / ctrl+c / alt+F4）"
        return try {
            val r = QuroDesktopInstaller.input(context, "key", key = key)
            if (r.first == 0) "✅ 已发送按键：$key" else "❌ 发送失败：${r.second}"
        } catch (e: Exception) { "发送失败：${e.message}" }
    }
}