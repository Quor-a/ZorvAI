package com.ai.assistance.quro.core.tools

import android.content.Context
import com.yuanbao.miniapp.core.MiniAppEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * miniapp_sdk 工具：把上游 Quor-a/GenUI 的**原生小程序引擎**（自研 C++ JS 引擎 + Flex 布局 +
 * Canvas/GLES 自绘渲染 + wx.* 原生能力）接入对话框，让 AI 能直接生成 WXML/WXSS/JS 小程序工程、
 * 列出/删除，落盘到 filesDir/miniapps/<appId>/，再由工具中心「小程序（原生引擎）」面板用 MiniAppView 渲染。
 *
 * 这是与「小程序」（MiniAppTool，WebView + native.* 桥 HTML 运行时）并列的**另一套小程序引擎**，
 * 走的是微信标准 WXML/WXSS/JS 范式，不是 HTML。
 *
 * 用法：
 * - miniapp_sdk(action="list")
 * - miniapp_sdk(action="create", appId="demo", files={ "app.json":"...", "pages/index/index.wxml":"...", "pages/index/index.wxss":"...", "pages/index/index.js":"..." })
 * - miniapp_sdk(action="delete", appId="demo")
 */
class MiniAppSdkTool : QuroTool {
    override val name = "miniapp_sdk"
    override val description = """miniapp_sdk：AI 直接创建 / 列出 / 删除**原生小程序**工程（WXML + WXSS + JS，走微信标准范式）。

这是移植自上游 Quor-a/GenUI 的**自研原生引擎**（C++ JS 引擎 + Flex 布局 + Canvas/GLES 自绘渲染），
与「小程序」工具（WebView + HTML 运行时）是两套并列的小程序引擎。本工具负责**生成与治理工程文件**，
渲染由工具中心「小程序（原生引擎）」面板的 MiniAppView 完成（对话框里调用本工具写好工程后，去工具中心打开即可看到画面）。

工程结构（存放在手机私有目录 filesDir/miniapps/<appId>/）：
- app.json：全局配置，必须含 pages 路由表，如 {"pages":["pages/index/index"],"window":{...}}
- pages/<page>/<page>.wxml：页面结构（view/text/button/image + {{data}} 绑定 + bindtap 等事件）
- pages/<page>/<page>.wxss：页面样式（rpx 自适应单位）
- pages/<page>/<page>.js：页面逻辑（Page({ data, onPlus:function(){ this.setData({...}) } })）
- pages/<page>/<page>.json：页面级配置（可选）
- app.js / app.wxss：全局逻辑 / 全局样式（可选）

原生能力（wx.*，由引擎内置实现）：
- wx.request：网络请求
- wx.getSystemInfo / wx.getSystemInfoSync：系统信息
- wx.showToast：轻提示
- wx.setStorage / getStorage / removeStorage：本地存储
- wx.navigateTo / redirectTo / navigateBack：页面路由
- wx.setNavigationBarTitle：设置标题
- console.log：日志

操作：
- list：列出所有可用小程序 appId（内置 assets 演示 + 用户目录工程），返回 appIds 数组
- create：创建/覆盖工程，files 为「相对路径 -> 内容」的对象（也可传数组 [{path,content}]）；
  至少要有 app.json 和一个页面 wxml。返回落盘路径与文件数
- delete：删除整个工程目录 filesDir/miniapps/<appId>/

注意：写好的工程**去工具中心「小程序（原生引擎）」面板打开才会渲染**——本工具只负责把文件落到磁盘。
"""

    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：list|create|delete"},
            "appId":{"type":"string","description":"小程序 id（create/delete 时必填；仅允许字母数字 _ - .）"},
            "files":{"type":"object","description":"文件映射（仅 create 时需要）：相对工程根的路径 -> 文件内容。也可传数组 [{path,content}]"}
        },
        "required":["action"]
    }"""

    override fun run(context: Context, arg: String): String {
        return runCatching {
            val obj = JSONObject(arg)
            val action = obj.optString("action", "")
            when (action) {
                "list" -> doList(context)
                "create" -> doCreate(context, obj)
                "delete" -> doDelete(context, obj)
                else -> err("未知 action：$action（支持 list|create|delete）")
            }
        }.getOrElse { err("执行失败：${it.message}") }
    }

    private fun doList(context: Context): String {
        val ids = MiniAppEngine.listAppIds(context)
        return JSONObject().put("ok", true).put("count", ids.size)
            .put("appIds", JSONArray(ids)).toString()
    }

    private fun doCreate(context: Context, obj: JSONObject): String {
        val appId = sanitize(obj.optString("appId", ""))
        if (appId.isEmpty()) return err("create 需要 appId")
        val files = collectFiles(obj.opt("files"))
        if (files.isEmpty()) return err("create 需要 files（至少一个 app.json + 一个页面 wxml）")
        val root = File(MiniAppEngine.userAppsRoot(context), appId).apply { mkdirs() }
        var written = 0
        val errors = ArrayList<String>()
        files.forEach { (rel, content) ->
            runCatching {
                val safe = rel.trim().trimStart('/').replace(Regex("""\.{2,}"""), "_")
                val f = File(root, safe)
                f.parentFile?.mkdirs()
                f.writeText(content ?: "")
                written++
            }.onFailure { errors.add("$rel: ${it.message}") }
        }
        val res = JSONObject().put("ok", errors.isEmpty())
            .put("appId", appId)
            .put("path", root.absolutePath)
            .put("written", written)
        if (errors.isNotEmpty()) res.put("errors", JSONArray(errors))
        return res.toString()
    }

    private fun doDelete(context: Context, obj: JSONObject): String {
        val appId = sanitize(obj.optString("appId", ""))
        if (appId.isEmpty()) return err("delete 需要 appId")
        val dir = File(MiniAppEngine.userAppsRoot(context), appId)
        val ok = if (dir.exists()) dir.deleteRecursively() else true
        return JSONObject().put("ok", ok).put("appId", appId)
            .put("removed", dir.absolutePath).toString()
    }

    private fun collectFiles(files: Any?): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        when (files) {
            is JSONObject -> files.keys().forEach { out[it] = files.optString(it, "") }
            is JSONArray -> for (i in 0 until files.length()) {
                val o = files.optJSONObject(i) ?: continue
                out[o.optString("path", "")] = o.optString("content", "")
            }
        }
        return out
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.\\-]"), "_").replace("..", "_").take(64)

    private fun err(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()
}
