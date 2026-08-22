package com.ai.assistance.quro.core.tools

import com.ai.assistance.quro.plugin.PluginSetDataCallback
import com.ai.assistance.quro.plugin.QuickJsEngine

/**
 * 离线 JS 执行后端：走 App 已编入的 libquroplugin.so（QuickJS 原生沙箱）。
 *
 * 用途：设备上没有 node 二进制时，run_code 的 node/js 分支与 CMS run_node 改走此处，
 * 在本地原生沙箱里执行 JS（每调用一个独立 JSRuntime，16MB 上限，超时中断），无需安装 Termux。
 *
 * 输出收集：把 console.log/error/warn/info 重定向到 hostSetData("__log", msg)，
 * 由 Kotlin 侧的 [PluginSetDataCallback.onSetData] 取回并拼成结果文本。
 */
object QuroJsExecutor {

    /** 执行 JS 片段，返回合并后的 console 输出；出错返回带 ⚠️ 的错误串。 */
    fun eval(code: String, timeoutMs: Int = 2000): String {
        if (!QuickJsEngine.isAvailable()) {
            return "⚠️ QuickJS 不可用（libquroplugin.so 未编入此构建），JS 无法离线执行。"
        }
        val engine = QuickJsEngine()
        if (!engine.create()) return "⚠️ QuickJS 运行时初始化失败"

        val out = StringBuilder()
        val callback = object : PluginSetDataCallback {
            override fun onSetData(path: String, valueJson: String) {
                if (path == "__log") out.append(unquote(valueJson)).append("\n")
            }

            override fun onHostApi(api: String, paramsJson: String): String = "null"
        }

        // 重定向 console.* 到宿主回传通道；用户代码随后原样执行。
        val wrapped = buildString {
            append(
                "var console={log:function(){var a=[];for(var i=0;i<arguments.length;i++)a.push(String(arguments[i]));" +
                    "hostSetData('__log',a.join(' '));}," +
                    "error:function(){var a=[];for(var i=0;i<arguments.length;i++)a.push(String(arguments[i]));" +
                    "hostSetData('__log',a.join(' '));}," +
                    "warn:function(){var a=[];for(var i=0;i<arguments.length;i++)a.push(String(arguments[i]));" +
                    "hostSetData('__log',a.join(' '));}," +
                    "info:function(){var a=[];for(var i=0;i<arguments.length;i++)a.push(String(arguments[i]));" +
                    "hostSetData('__log',a.join(' '));}};\n"
            )
            append(code)
        }

        val err = engine.evalPlugin(wrapped, callback, timeoutMs)
        engine.destroy()
        return if (err != null) "⚠️ JS 执行错误：$err" else out.toString().trim().ifBlank { "(无输出)" }
    }

    /** hostSetData 回传的是 JSON 编码串（字符串会被包一层双引号），这里剥壳还原。 */
    private fun unquote(json: String): String {
        if (json.length >= 2 && json.startsWith("\"") && json.endsWith("\"")) {
            return json.substring(1, json.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
        }
        return json
    }
}
