package com.ai.assistance.quro.core.tools
import com.ai.assistance.quro.R
import com.ai.assistance.quro.util.qstr

import android.content.Context
import com.ai.assistance.quro.core.cards.QuroChatCard
import com.yuanbao.miniapp.core.MiniAppEngine
import com.yuanbao.miniapp.nativeapi.WxApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * miniapp_sdk 工具：把上游 Quor-a/GenUI 的**原生小程序引擎**（自研 C++ JS 引擎 + Flex 布局 +
 * Canvas/GLES 自绘渲染 + wx.* 原生能力）接入对话框，让 AI 能直接生成 WXML/WXSS/JS 小程序工程、
 * 列出/删除，落盘到 filesDir/miniapps/<appId>/，对话框里创建后会**自动作为卡片用 MiniAppView 原生渲染到气泡**，
 * 也可在工具中心「小程序（原生引擎）」面板打开。
 *
 * 这是与「Web 应用」（MiniAppTool，WebView + native.* 桥 HTML 运行时）并列的**另一套小程序引擎**，
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

🔴 **触发边界（先读这个，用错=白干）**：仅当用户明确说「小程序（原生引擎）」「原生小程序」「微信小程序」「用 WXML/WXSS 写个小程序」时才用本工具。用户说「Web 应用」「网页应用」「做个网站」「HTML 应用」时**严禁用本工具**——Web 应用就是 Web 应用（HTML + WebView），不是小程序，那要用 `miniapp` 工具。

这是移植自上游 Quor-a/GenUI 的**自研原生引擎**（C++ JS 引擎 + Flex 布局 + Canvas/GLES 自绘渲染），
与「Web 应用」工具（WebView + HTML 运行时）是两套并列的小程序引擎。本工具负责**生成与治理工程文件**，
渲染由对话框气泡里的 MiniAppView 完成（miniapp_sdk 创建后会自动在对话框渲染出来，也能去工具中心「小程序（原生引擎）」面板打开）。

工程结构（存放在手机私有目录 filesDir/miniapps/<appId>/）：
- app.json：全局配置，必须含 pages 路由表，如 {"pages":["pages/index/index"],"window":{...}}
- pages/<page>/<page>.wxml：页面结构（view/text/button/image + {{data}} 绑定 + bindtap 等事件）
- pages/<page>/<page>.wxss：页面样式（rpx 自适应单位）
- pages/<page>/<page>.js：页面逻辑（Page({ data, onPlus:function(){ this.setData({...}) } })）
- pages/<page>/<page>.json：页面级配置（可选）
- app.js / app.wxss：全局逻辑 / 全局样式（可选）

原生能力（wx.*）——**下面这份清单由引擎的 WxApi.API_NAMES 在运行时现读，不是手抄**，
所以永远与真实实现一致。当前引擎共暴露 ${WxApi.API_NAMES.size} 个接口：
- 网络：wx.request（支持 method / header / data / dataType，成功回调 {statusCode,data,header,errMsg}）
- 系统：wx.getSystemInfo / wx.getSystemInfoSync（platform/system/brand/model/screenWidth/screenHeight/
  windowWidth/windowHeight/pixelRatio/language/SDKVersion）
- 交互：wx.showToast / wx.hideToast、wx.showLoading / wx.hideLoading、wx.showModal（success 回 {confirm,cancel}）、
  wx.showActionSheet（success 回 {tapIndex}）
- 设备：wx.vibrateShort / wx.vibrateLong、wx.setClipboardData / wx.getClipboardData、
  wx.getNetworkType（回 {networkType:"wifi|4g|none|unknown"}）、wx.makePhoneCall
- 存储：wx.setStorage / wx.getStorage / wx.removeStorage / wx.clearStorage（**异步版，回调式**），
  以及 wx.setStorageSync / wx.getStorageSync / wx.removeStorageSync / wx.clearStorageSync（同步版），
  wx.getStorageInfo / wx.getStorageInfoSync（回 {keys,currentSize,limitSize}）
- 路由：wx.navigateTo / wx.redirectTo / wx.navigateBack（支持 delta）、wx.setNavigationBarTitle、
  wx.setNavigationBarColor、wx.getCurrentPage、wx.pageScrollTo（回 scrollTop/duration）
- 刷新：wx.startPullDownRefresh / wx.stopPullDownRefresh
- 启动：wx.getLaunchOptionsSync / wx.getEnterOptionsSync（回 {path,scene,query,launchTimer}）
- 其它：wx.nextTick、wx.getRealtimeLogManager、wx.hideHomeButton
- console：console.log / info / warn / error / debug

⚠️ 只能写上面这些名字。**引擎里没有的 wx 接口不要凭微信文档臆造** ——
未实现的接口虽然有"记日志后返回 undefined"的兜底（避免把整页 onLoad 打断导致白屏），
但功能不会生效。需要某个接口时先查这份清单，缺的话它就真的缺。

⚠️ **语法限制（2026-11-04 实测排查，违反必出渲染问题）**：
- WXML：不要用 `<template>/<include>/<import>/<wxs>/<block>`（引擎不支持，渲染为空 view）；
  事件只用 `bindtap` / `bindinput` / `bindblur`，事件名写死字符串、不要 `bindtap="{{fn}}"`；
  `<image>` 必须给明确 `width/height`（无尺寸按 0×0 处理），src 用包内相对路径或 http(s)。
- WXSS：只用 flex / grid 布局 + rpx/px/% 单位；**禁用** em/rem、无单位 line-height（行高要 px/rpx）、
  `!important`、伪类/伪元素（:hover/::after）、`@media`/`@keyframes`、`position:fixed/sticky`
  （底栏/悬浮球用普通流内布局实现）、background-image/linear-gradient/box-shadow；
  文本截断不要用 text-overflow:ellipsis（不生效），直接给容器定宽。
- JS：不要用 Promise/async/await/class/getter/解构/模块(require/module.exports) /
  Component()/Behavior()——引擎是自研轻量解释器，只支持 var/let/const、function、箭头函数、
  模板串、try/switch/for、Date/Math/JSON/RegExp、setTimeout/setInterval；
  页面只用 `Page({ data, onLoad, 方法名:function(){} })`，更新视图只用 `this.setData({...})`。
- 布局：根容器不要写固定 px 宽度（用 100%/rpx 自适屏宽）；横向内容套 `scroll-view`。

操作：
- list：列出所有可用小程序 appId（内置 assets 演示 + 用户目录工程），返回 appIds 数组
- create：创建/覆盖工程。files 为「相对路径 -> 内容」的对象（也可传数组 [{path,content}]，
  或整体 JSON 字符串）；至少要有 app.json 和一个页面 wxml。返回落盘路径与文件数。
  🔴 files 传不进去时的可靠备选：先用文件工具把工程写进工作区（如 NativeMiniApp/<appId>/），
  再调 create 并传 srcDir="NativeMiniApp/<appId>"，整目录拷入。
- delete：删除整个工程目录 filesDir/miniapps/<appId>/

注意：写好的工程**创建后会自动渲染在对话框气泡**（MiniAppView 原生渲染），也能去工具中心「小程序（原生引擎）」面板打开——本工具负责把文件落到磁盘并触发对话框渲染。
"""

    override val parametersJson = """{
        "type":"object",
        "properties":{
            "action":{"type":"string","description":"操作：list|create|delete"},
            "appId":{"type":"string","description":"小程序 id（create/delete 时必填；仅允许字母数字 _ - .）"},
            "files":{"type":["object","string","array"],"description":"文件映射（仅 create 时需要）：相对工程根的路径 -> 文件内容。也可传数组 [{path,content}] 或整体 JSON 字符串"},
            "srcDir":{"type":"string","description":"（files 备选）工作区内已写好的工程目录，create 时整体拷入"}
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
        }.getOrElse { err(qstr(R.string.qk_02795, (it.message).toString())) }
    }

    private fun doList(context: Context): String {
        val ids = MiniAppEngine.listAppIds(context)
        return JSONObject().put("ok", true).put("count", ids.size)
            .put("appIds", JSONArray(ids)).toString()
    }

    private fun doCreate(context: Context, obj: JSONObject): String {
        val appId = sanitize(obj.optString("appId", ""))
        if (appId.isEmpty()) return err("create 需要 appId")
        var files = collectFiles(obj.opt("files"))
        // 🔴 files 兜底（2026-11-06 排查「AI 写不了小程序」）：
        //    ① files 传成了 JSON 字符串（弱模型常见）→ 解析后复用；
        //    ② files 仍为空 → 接受 srcDir：把工作区里已写好的目录整体拷入
        //       （AI 先用文件工具把 WXML/WXSS/JS 写进工作区，再 import 进原生引擎，
       //        绕开工具调用参数传输的不可靠环节）。
        if (files.isEmpty()) {
            val filesStr = obj.optString("files", "")
            if (filesStr.isNotBlank()) {
                runCatching {
                    files = collectFiles(JSONObject(filesStr))
                }.onFailure {
                    runCatching { files = collectFiles(JSONArray(filesStr)) }
                }
            }
        }
        if (files.isEmpty()) {
            val srcDir = obj.optString("srcDir", "").trim()
            if (srcDir.isNotBlank()) {
                val src = if (File(srcDir).isAbsolute) File(srcDir)
                          else File(com.ai.assistance.quro.core.tools.workspaceRoot(context), srcDir)
                if (src.isDirectory) {
                    files = LinkedHashMap<String, String?>()
                    src.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(src).invariantSeparatorsPath
                        files[rel] = runCatching { f.readText() }.getOrNull()
                    }
                }
            }
        }
        if (files.isEmpty()) {
            // 🔴 诊断：把实际收到的 files 参数头带回给模型，让它能自我纠正
            val head = obj.opt("files")?.toString()?.take(200) ?: "（files 缺失）"
            return err("create 需要 files（至少一个 app.json + 一个页面 wxml）。" +
                "实际收到：" + head + " —— 支持对象 {路径:内容} 或数组 [{path,content}]；" +
                "也可先用文件工具把工程写进工作区，再用 action=create + srcDir=工作区相对路径 导入。")
        }
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
        // 对话框直接渲染：把刚创建的原生小程序作为卡片挂到当前助手消息气泡（原生引擎直接跑）。
        // 桥未连接（非对话场景）时静默跳过，工程仍可在工具中心「小程序（原生引擎）」面板打开。
        runCatching {
            QuroUiActionBridge.onCard?.invoke(
                QuroChatCard.MiniAppCard(
                    id = "miniapp_sdk_" + appId + "_" + System.currentTimeMillis(),
                    title = "小程序（原生引擎）· " + appId,
                    html = "",
                    config = mapOf("app_id" to appId),
                    nativeAppId = appId,
                )
            )
        }
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
                val path = o.optString("path", o.optString("file", ""))
                if (path.isBlank()) continue
                // content 可能是字符串，也可能是嵌套对象（弱模型产物）——统一取文本
                val content = when (val c = o.opt("content")) {
                    is String -> c
                    is JSONObject, is JSONArray -> c.toString()
                    else -> o.optString("text", o.optString("body", ""))
                }
                out[path] = content
            }
            is String -> runCatching {
                // 🔴 files 整体被传成 JSON 字符串（弱模型常见）——解析后按对象处理
                val inner = JSONObject(files)
                inner.keys().forEach { out[it] = inner.optString(it, "") }
            }.onFailure {
                runCatching {
                    val arr = JSONArray(files)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        out[o.optString("path", "")] = o.optString("content", "")
                    }
                }
            }
        }
        return out.filterKeys { it.isNotBlank() }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.\\-]"), "_").replace("..", "_").take(64)

    private fun err(msg: String): String =
        JSONObject().put("ok", false).put("error", msg).toString()
}