package com.ai.assistance.quro.browser

import ai.aidl.aci.core.AidlAciError
import ai.aidl.aci.core.AidlAciRequest
import ai.aidl.aci.core.AidlAciResponse
import ai.aidl.aci.core.BaseAidlAciService
import ai.aidl.aci.core.Capability
import android.content.Intent
import android.os.Bundle
import org.json.JSONObject
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File
import android.util.Base64
import org.json.JSONArray
import java.util.ArrayList

/**
 * ACI 受控端 Service（v7 · 新增 browser_crawl / browser_search / browser_script 三类能力）。
 *
 * 核心改进（针对 v4 诊断结果：Activity 正常但 Service 可能 onCreate 崩溃导致绑不上）：
 * 1. super.onCreate() 也包 try-catch —— 基类内部调 onCreateCapabilities，任何异常都会炸掉整个 Service
 * 2. 所有诊断写入 DiagBuffer（不依赖文件），Activity 启动后渲染到屏幕
 * 3. 能力注册用最简 API 先验证通路（后续再加复杂参数）
 */
class QuroControlledAidlAciService : BaseAidlAciService() {

    companion object {
        private const val TAG = "Service"
        private const val ZORV_PKG = "com.ai.assistance.quro"
        /** 控制器 QuroAidlAciManager.callTimeoutMs = 15_000L；handler 硬上限留 1s 余量 */
        private const val HARD_TIMEOUT_S = 14L
    }

    override fun onCreate() {
        DiagBuffer.append(TAG, "═ onCreate 开始 ═")
        try {
            // 关键：super.onCreate() 内部会调用 onCreateCapabilities()
            // 如果后者抛异常，整个 Service 创建失败 → bindService 永远不会成功
            super.onCreate()
            DiagBuffer.append(TAG, "✅ super.onCreate() 完成（含 onCreateCapabilities）")
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "❌ super.onCreate() 崩溃: ${e.javaClass.simpleName}: ${e.message}")
            // 不要 rethrow —— 让 Service 尽量存活，至少 onBind 能返回 binder
        }

        try {
            BrowserCore.init(applicationContext)
            DiagBuffer.append(TAG, "✅ BrowserCore.init()")
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "⚠️ BrowserCore.init 失败: ${e.message}")
        }

        DiagBuffer.append(TAG, "═ onCreate 结束 ═")
    }

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        DiagBuffer.append(TAG, "onCreateCapabilities 开始 (caps列表已传入)")

        var ok = 0
        var fail = 0

        // browser_open
        try {
            caps.add(
                Capability.create("browser_open", "打开指定网址并导航到该页面（等待页面加载完成后再返回）")
                    .addParam("url", "string", true, "要打开的网址")
                    .addParam("title", "string", false, "标签标题（可选，登记到多标签列表）")
                    .addResult("launched", "boolean", "是否已启动")
                    .addResult("ready", "boolean", "页面是否已完成加载（onPageFinished）")
                    .addResult("url", "string", "实际打开的网址")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_open")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_open: ${e.message}")
        }

        // browser_read
        try {
            caps.add(
                Capability.create("browser_read", "读取当前页的 URL、标题与 HTML（支持精简 DOM 模式）")
                    .addParam("mode", "string", false, "HTML 模式：full(默认,完整HTML) / clean(精简DOM,去脚本样式+打 data-ai-id+标视口)")
                    .addResult("url", "string", "当前网址")
                    .addResult("title", "string", "页面标题")
                    .addResult("html", "string", "页面 HTML")
                    .addResult("mode", "string", "实际使用的模式")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_read")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_read: ${e.message}")
        }

        // browser_crawl（v7 新增：爬虫）
        try {
            caps.add(
                Capability.create("browser_crawl", "抓取当前页结构化数据：标题 + 可读正文 + 出站链接（AI 检索/爬虫用）")
                    .addResult("url", "string", "当前网址")
                    .addResult("title", "string", "页面标题")
                    .addResult("text", "string", "页面正文（截断到约15万字符）")
                    .addResult("links", "string", "出站链接 JSON 数组 [{text,href}]")
                    .addResult("link_count", "string", "页面链接总数")
                    .addResult("truncated", "boolean", "正文是否被截断")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_crawl")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_crawl: ${e.message}")
        }

        // browser_search（v7 新增：检索/搜索）
        try {
            caps.add(
                Capability.create("browser_search", "用搜索引擎检索关键词，返回结果页的标题/正文/链接")
                    .addParam("query", "string", true, "检索词")
                    .addParam("engine", "string", false, "搜索引擎：bing/google/baidu/ddg，默认 bing")
                    .addResult("query", "string", "检索词")
                    .addResult("engine", "string", "实际使用的引擎")
                    .addResult("url", "string", "实际打开的检索 URL")
                    .addResult("title", "string", "结果页标题")
                    .addResult("text", "string", "结果页正文（截断）")
                    .addResult("links", "string", "结果链接 JSON 数组")
                    .addResult("truncated", "boolean", "是否截断")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_search")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_search: ${e.message}")
        }

        // browser_script（v7 新增：脚本/执行 JS）
        try {
            caps.add(
                Capability.create("browser_script", "在当前页面执行任意 JavaScript 并返回结果（脚本能力）")
                    .addParam("code", "string", true, "要执行的 JS 代码（表达式或 IIFE 返回结果）")
                    .addResult("result", "string", "JS 执行结果（JSON 字符串形式，已截断）")
                    .addResult("truncated", "boolean", "结果是否被截断")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_script")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_script: ${e.message}")
        }

        // browser_list
        try {
            caps.add(
                Capability.create("browser_list", "列出当前打开的浏览器标签页")
                    .addResult("tabs", "string", "标签页摘要")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_list")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_list: ${e.message}")
        }

        // browser_info
        try {
            caps.add(
                Capability.create("browser_info", "查询受控浏览器的包名与版本信息")
                    .addResult("package", "string", "包名")
                    .addResult("version", "string", "版本名")
                    .addResult("version_code", "string", "版本号")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_info")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_info: ${e.message}")
        }

        // browser_capture（v1.0.12-capture 新增：抓包 / 流量拦截）
        try {
            caps.add(
                Capability.create("browser_capture", "抓包：拦截并列出当前页发出的网络请求（URL/方法/请求头/是否主框架），用于流量分析")
                    .addParam("action", "string", false, "操作：list(默认)/clear/enable/disable")
                    .addParam("limit", "string", false, "返回条数上限，默认200")
                    .addParam("filter", "string", false, "按 url/方法/请求头 关键字过滤")
                    .addResult("requests", "string", "请求记录 JSON 数组")
                    .addResult("count", "string", "命中条数")
                    .addResult("enabled", "boolean", "抓包开关状态")
                    .addResult("note", "string", "版本说明")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_capture")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_capture: ${e.message}")
        }

        // browser_find（完整功能：页面内查找 Ctrl+F）
        try {
            caps.add(
                Capability.create("browser_find", "在页面内查找文本（高亮 + 返回命中数）")
                    .addParam("text", "string", true, "要查找的文本")
                    .addParam("action", "string", false, "find(默认)/next/prev/clear")
                    .addParam("forward", "string", false, "next/prev 方向，默认 true")
                    .addResult("found", "boolean", "是否有命中")
                    .addResult("count", "string", "命中数量")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_find")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_find: ${e.message}")
        }

        // browser_nav（完整功能：前进/后退/刷新）
        try {
            caps.add(
                Capability.create("browser_nav", "浏览器导航：后退 / 前进 / 刷新")
                    .addParam("action", "string", true, "back / forward / reload")
                    .addResult("url", "string", "操作后当前网址")
                    .addResult("can_back", "boolean", "是否可后退")
                    .addResult("can_forward", "boolean", "是否可前进")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_nav")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_nav: ${e.message}")
        }

        // browser_screenshot（完整功能：截图当前可视区域，存 PNG 返回路径）
        try {
            caps.add(
                Capability.create("browser_screenshot", "截取当前页面可视区域，保存 PNG 到应用外部存储 Pictures/QuroAI_screenshots/，返回文件路径")
                    .addResult("path", "string", "截图文件绝对路径（空=失败）")
                    .addResult("url", "string", "截图时网址")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_screenshot")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_screenshot: ${e.message}")
        }

        // console_ui（v1.0.12 新增：SDUI 控制台快照，后端驱动 UI）
        try {
            caps.add(
                Capability.create("console_ui", "获取受控浏览器控制台的 UI 描述 JSON（组件化，前端渲染）")
                    .addResult("snapshot", "string", "UI 描述 JSON 字符串")
                    .addResult("title", "string", "控制台标题")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ console_ui")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ console_ui: ${e.message}")
        }

        // console_action（v1.0.12 新增：处理控制台前端回传的动作）
        try {
            caps.add(
                Capability.create("console_action", "处理控制台前端回传的动作（increment/reset/submit_note）")
                    .addParam("action", "string", true, "动作 id")
                    .addParam("payload", "string", false, "动作参数 JSON 字符串")
                    .addResult("ok", "boolean", "是否成功")
                    .addResult("action", "string", "实际处理的动作")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ console_action")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ console_action: ${e.message}")
        }

        // browser_elements（agentic：可交互元素树 + 稳定ID标注）
        try {
            caps.add(
                Capability.create("browser_elements", "查询当前页可交互元素树（自动标注稳定ID），返回元素列表：id/标签/类型/文本/值/链接/位置/可见性")
                    .addResult("count", "string", "元素数量")
                    .addResult("elements", "string", "元素 JSON 数组 [{id,tag,type,text,value,href,placeholder,name,x,y,w,h,visible}]")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_elements")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_elements: ${e.message}")
        }

        // browser_action（agentic：按元素稳定ID操作）
        try {
            caps.add(
                Capability.create("browser_action", "按元素稳定ID或CSS选择器执行操作：click 点击 / type 输入文本 / scroll_to 滚动到可视 / select 选择下拉项")
                    .addParam("id", "string", false, "目标元素稳定ID（来自 browser_elements）；与 selector 二选一")
                    .addParam("selector", "string", false, "CSS 选择器（如 \"#main button\"）；与 id 二选一，优先级低于 id")
                    .addParam("op", "string", true, "操作：click / type / scroll_to / select")
                    .addParam("arg", "string", false, "type / select 的文本参数")
                    .addResult("ok", "boolean", "是否成功")
                    .addResult("op", "string", "实际执行的操作")
                    .addResult("error", "string", "失败原因")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_action")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_action: ${e.message}")
        }

        // browser_wait（agentic：条件等待引擎）
        try {
            caps.add(
                Capability.create("browser_wait", "条件等待引擎：等待元素可见/隐藏/文本包含，或网络空闲（SPA 加载完成）")
                    .addParam("cond", "string", true, "条件：visible / hidden / text_contains / network_idle")
                    .addParam("id", "string", false, "visible / hidden / text_contains 的目标元素ID")
                    .addParam("arg", "string", false, "text_contains 的子串")
                    .addParam("timeout_ms", "string", false, "超时毫秒，默认 8000，上限 60000")
                    .addResult("ok", "boolean", "是否达成条件")
                    .addResult("waited_ms", "string", "实际等待毫秒")
                    .addResult("reason", "string", "未达成时的原因")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_wait")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_wait: ${e.message}")
        }

        // browser_snapshot（agentic：页面状态快照）
        try {
            caps.add(
                Capability.create("browser_snapshot", "页面状态快照：保存当前 URL/标题/HTML 到快照库（按 label 覆盖），或列出已有快照")
                    .addParam("action", "string", false, "save(默认) / list")
                    .addParam("label", "string", false, "快照标签，默认按时间戳自动生成")
                    .addResult("id", "string", "本次保存的快照 id")
                    .addResult("ok", "boolean", "是否成功")
                    .addResult("snapshots", "string", "list 模式返回快照 JSON 数组")
                    .addResult("count", "string", "list 模式返回快照数量")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_snapshot")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_snapshot: ${e.message}")
        }

        // browser_restore（agentic：页面状态回滚）
        try {
            caps.add(
                Capability.create("browser_restore", "页面状态回滚：导航回指定快照记录的 URL")
                    .addParam("id", "string", true, "目标快照 id（来自 browser_snapshot list）")
                    .addResult("ok", "boolean", "是否成功")
                    .addResult("id", "string", "回滚的快照 id")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_restore")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_restore: ${e.message}")
        }

        // browser_events（agentic：页面事件总线）
        try {
            caps.add(
                Capability.create("browser_events", "查询页面事件流：page_started / page_finished / request / load_resource 等")
                    .addParam("limit", "string", false, "返回条数上限，默认 100")
                    .addResult("events", "string", "事件 JSON 数组 [{type,url,time}]")
                    .addResult("count", "string", "事件数量")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_events")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_events: ${e.message}")
        }

        // browser_audit（agentic：ACI 调用审计日志）
        try {
            caps.add(
                Capability.create("browser_audit", "查询 ACI 调用审计日志：每次外部调用（能力/参数/成败）一条记录")
                    .addParam("limit", "string", false, "返回条数上限，默认 100")
                    .addResult("log", "string", "审计 JSON 数组 [{cap,params,ok,time}]")
                    .addResult("count", "string", "记录数量")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_audit")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_audit: ${e.message}")
        }

        // browser_media（分享/资源回传：扫描页面视频/音频/下载链接，返回绝对 URL + 元数据）
        try {
            caps.add(
                Capability.create("browser_media", "扫描当前页媒体与文件资源（video/audio/source/a[download]/img），返回绝对 URL + 元数据（视频/音频直链供控制方直接播放）")
                    .addResult("count", "string", "资源数量")
                    .addResult("resources", "string", "资源 JSON 数组 [{tag,src,type,text,page_url,current_time?,duration?,paused?,poster?,download?}]")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_media")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_media: ${e.message}")
        }

        // browser_share（分享功能：调起系统 Sharesheet 分享当前页/文本）
        try {
            caps.add(
                Capability.create("browser_share", "调起 Android 系统分享面板（Sharesheet），分享当前页面 URL/标题或自定义文本")
                    .addParam("type", "string", false, "分享类型：page(默认,分享当前页) / text(分享自定义文本)")
                    .addParam("text", "string", false, "type=text 时的自定义文本内容")
                    .addResult("launched", "boolean", "是否成功调起分享面板")
                    .addResult("type", "string", "实际分享类型")
                    .addFlag(Capability.FLAG_BACKGROUND)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_share")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_share: ${e.message}")
        }

        // browser_console（完整方案：console.log 实时捕获，原生 onConsoleMessage 钩取）
        try {
            caps.add(
                Capability.create("browser_console", "抓取当前页 console.* 输出（log/warn/error/info），用于调试与运行时观察")
                    .addParam("action", "string", false, "操作：list(默认)/clear/enable/disable")
                    .addParam("limit", "string", false, "返回条数上限，默认200")
                    .addParam("filter", "string", false, "按文本/级别/来源关键字过滤")
                    .addResult("entries", "string", "日志 JSON 数组 [{level,text,source,line,time}]")
                    .addResult("count", "string", "命中条数")
                    .addResult("enabled", "boolean", "控制台捕获开关状态")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_console")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_console: ${e.message}")
        }

        // browser_query（完整方案：按 CSS 选择器查询 DOM）
        try {
            caps.add(
                Capability.create("browser_query", "按 CSS 选择器查询当前页 DOM 元素，返回匹配列表（索引/标签/文本/值/链接/位置/可见性）")
                    .addParam("selector", "string", true, "CSS 选择器，如 \"a.news-item\" / \"#main input\"")
                    .addResult("count", "string", "匹配数量")
                    .addResult("matches", "string", "匹配元素 JSON 数组 [{index,tag,text,value,href,id,cls,x,y,w,h,visible}]")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_query")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_query: ${e.message}")
        }

        // browser_tabnew（完整方案：轻量多标签·新建并打开）
        try {
            caps.add(
                Capability.create("browser_tabnew", "新建标签页并打开指定网址（轻量多标签：单引擎，标签记录 URL + 切换重载）")
                    .addParam("url", "string", true, "要打开的网址")
                    .addParam("title", "string", false, "标签标题（可选）")
                    .addResult("tab_id", "string", "新建标签 id")
                    .addResult("url", "string", "实际打开的网址")
                    .addResult("active", "boolean", "是否为当前激活标签")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_tabnew")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_tabnew: ${e.message}")
        }

        // browser_tabs（完整方案：轻量多标签·列表）
        try {
            caps.add(
                Capability.create("browser_tabs", "列出所有已打开的标签页（含激活态标记）")
                    .addResult("count", "string", "标签数量")
                    .addResult("tabs", "string", "标签 JSON 数组 [{id,url,title,active}]")
                    .addResult("active_id", "string", "当前激活标签 id")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_tabs")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_tabs: ${e.message}")
        }

        // browser_tab（完整方案：轻量多标签·切换）
        try {
            caps.add(
                Capability.create("browser_tab", "切换到指定标签页（重新加载该标签记录的 URL）")
                    .addParam("id", "string", true, "目标标签 id（来自 browser_tabs）")
                    .addResult("ok", "boolean", "是否成功")
                    .addResult("url", "string", "切换后当前网址")
                    .addResult("id", "string", "切换的标签 id")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_tab")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_tab: ${e.message}")
        }

        // browser_tabclose（完整方案：轻量多标签·关闭）
        try {
            caps.add(
                Capability.create("browser_tabclose", "关闭指定标签页（若为激活标签，自动回退到最近一个）")
                    .addParam("id", "string", true, "目标标签 id（来自 browser_tabs）")
                    .addResult("ok", "boolean", "是否成功关闭")
                    .addResult("remaining", "string", "剩余标签数量")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_tabclose")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_tabclose: ${e.message}")
        }

        // browser_mouse（虚拟鼠标：坐标级 tap/drag/scroll/hover，覆盖无稳定ID/无选择器的元素与画布）
        try {
            caps.add(
                Capability.create("browser_mouse", "虚拟鼠标：在页面指定屏幕坐标模拟鼠标动作（move悬停/click单击/dblclick双击/right右键/down按下/up抬起/drag拖拽/scroll滚动）")
                    .addParam("action", "string", true, "动作：move/click/dblclick/right/down/up/drag/scroll")
                    .addParam("x", "int", true, "屏幕绝对像素 X（相对 WebView 左上角，由后端自动换算视图坐标）")
                    .addParam("y", "int", true, "屏幕绝对像素 Y")
                    .addParam("dx", "int", false, "drag/scroll 的 X 偏移像素（默认 0）")
                    .addParam("dy", "int", false, "drag/scroll 的 Y 偏移像素（默认 0）")
                    .addParam("button", "string", false, "鼠标键：left(默认)/right/middle")
                    .addResult("ok", "boolean", "是否成功派发")
                    .addResult("action", "string", "实际执行的动作")
                    .addResult("x", "int", "使用的 X 坐标")
                    .addResult("y", "int", "使用的 Y 坐标")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ browser_mouse")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ browser_mouse: ${e.message}")
        }

        // HTTP 传输能力：与受控浏览器对称，让浏览器也能代为发起任意 HTTP 请求（既能发也能收）
        try {
            caps.add(
                Capability.create(
                    "http_request",
                    "HTTP 传输：发起 HTTP 请求并取回响应（既能发出请求也能接收响应）。" +
                        "支持自定义方法（GET/POST/PUT/DELETE/PATCH/HEAD 等）、请求头与请求体，" +
                        "返回状态码、响应头与响应体。可用于调用 Web API、抓取网页、对接第三方服务。"
                )
                    .addParam("url", "string", true, "目标 URL")
                    .addParam("method", "string", false, "HTTP 方法，默认 GET")
                    .addParam("headers", "string", false, "请求头 JSON 对象，如 {\"Authorization\":\"Bearer x\"}")
                    .addParam("body", "string", false, "请求体（原样发送，字符串）")
                    .addResult("status_code", "int", "HTTP 响应状态码")
                    .addResult("response_headers", "string", "响应头 JSON 对象")
                    .addResult("response_body", "string", "响应体（>15万字符截断，完整内容见 response_body_gz）")
                    .addResult("truncated", "boolean", "响应体是否被截断")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ http_request")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ http_request: ${e.message}")
        }

        // workspace_*（vACI-ws · 共享工作空间）：主应用与受控浏览器可互读/互写的共享目录。
        // 目录落在 aci-browser 自身沙箱 filesDir/aci_workspace（无需 MANAGE_EXTERNAL_STORAGE），
        // 主应用经 aci_call 调用本组能力读写，实现跨 App 文件/状态共享通道。
        try {
            caps.add(
                Capability.create(
                    "workspace_list",
                    "列出共享工作空间的文件清单（主应用与受控浏览器跨 App 共享目录，用于互传文件与状态）。" +
                        "返回 entries(JSON 数组：[{name,size,is_dir,mtime}]) 与 count。"
                )
                    .addResult("entries", "string", "JSON 数组：[{name,size,is_dir,mtime}]")
                    .addResult("count", "int", "条目数")
                    .addResult("dir", "string", "工作空间目录（仅诊断用）")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ workspace_list")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ workspace_list: ${e.message}")
        }
        try {
            caps.add(
                Capability.create(
                    "workspace_read",
                    "读取共享工作空间中的一个文件。文本文件返回 content；二进制返回 content_b64 与 is_binary。" +
                        "name 不含路径，禁止 ../ 越权。"
                )
                    .addParam("name", "string", true, "文件名（不含路径，禁止 ../ 越权）")
                    .addResult("name", "string", "文件名")
                    .addResult("content", "string", "文本内容（文本文件时返回）")
                    .addResult("content_b64", "string", "Base64 内容（二进制文件时返回）")
                    .addResult("is_binary", "boolean", "是否为二进制")
                    .addResult("size", "int", "字节数")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ workspace_read")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ workspace_read: ${e.message}")
        }
        try {
            caps.add(
                Capability.create(
                    "workspace_write",
                    "向共享工作空间写入一个文件。文本用 content；二进制用 content_b64（与 content 二选一）。" +
                        "name 不含路径，禁止 ../ 越权。"
                )
                    .addParam("name", "string", true, "文件名（不含路径，禁止 ../ 越权）")
                    .addParam("content", "string", false, "文本内容（与 content_b64 二选一）")
                    .addParam("content_b64", "string", false, "Base64 二进制内容（与 content 二选一）")
                    .addResult("written", "boolean", "是否写入成功")
                    .addResult("bytes", "int", "写入字节数")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ workspace_write")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ workspace_write: ${e.message}")
        }
        try {
            caps.add(
                Capability.create(
                    "workspace_delete",
                    "删除共享工作空间中的一个文件。name 不含路径，禁止 ../ 越权。"
                )
                    .addParam("name", "string", true, "文件名（不含路径，禁止 ../ 越权）")
                    .addResult("deleted", "boolean", "是否删除成功")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ workspace_delete")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ workspace_delete: ${e.message}")
        }

        // ui_snapshot（语义点击基座：当前可视区域元素屏幕坐标快照，供 clickText/clickResourceId 解析锚点）
        try {
            caps.add(
                Capability.create(
                    "ui_snapshot",
                    "当前可视区域元素快照（屏幕坐标）：返回节点列表 nodes，每项为 text|resId|left,top,right,bottom，" +
                        "供控制端 clickText/clickResourceId 语义点击解析锚点坐标后调用 tap。配合 tap 形成感知-执行闭环。"
                )
                    .addResult("nodes", "string_array", "节点列表，每项格式 text|resId|left,top,right,bottom（屏幕像素整数）")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ ui_snapshot")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ ui_snapshot: ${e.message}")
        }

        // tap（坐标点击：与 ui_snapshot 同一屏幕坐标空间，受控端无系统特权也能派发视图级触摸）
        try {
            caps.add(
                Capability.create(
                    "tap",
                    "在指定屏幕坐标模拟单击（与 ui_snapshot 同一坐标空间：屏幕绝对像素）。" +
                        "供语义点击闭环 clickText/clickResourceId 在解析出锚点坐标后调用，实现「像人一样点页面」。"
                )
                    .addParam("x", "int", true, "屏幕 x 坐标（像素）")
                    .addParam("y", "int", true, "屏幕 y 坐标（像素）")
                    .addResult("x", "int", "实际点击的 x")
                    .addResult("y", "int", "实际点击的 y")
                    .addFlag(Capability.FLAG_BACKGROUND)
                    .addFlag(Capability.FLAG_NO_UI)
            )
            ok++; DiagBuffer.append(TAG, "✓ tap")
        } catch (e: Throwable) {
            fail++; DiagBuffer.append(TAG, "✗ tap: ${e.message}")
        }

        DiagBuffer.append(TAG, "onCreateCapabilities 完成: $ok 成功 / $fail 失败 / 总计=${caps.size}")

        // 持久化一份到文件（备用）
        DiagBuffer.persist(this)
    }

    override fun onCheckPermission(req: AidlAciRequest?, callerPkg: String?): Boolean {
        val ok = callerPkg == ZORV_PKG || callerPkg == packageName
        DiagBuffer.append(TAG, "onCheckPermission: caller=$callerPkg → ${if(ok)"放行" else "拒绝"}")
        return ok
    }

    override fun onCall(req: AidlAciRequest?): AidlAciResponse {
        if (req == null) {
            DiagBuffer.append(TAG, "onCall: null request")
            return AidlAciResponse.error(AidlAciError.REQUEST_NULL, "null")
        }
        val cap = req.capability
        DiagBuffer.append(TAG, "onCall: capability=$cap")
        // 点亮 AI「眼睛」：通知 Activity 底部指示灯进入「控制中」状态
        BrowserCore.reportAiActivity("ACI 调用能力：$cap")
        // 操作审计：每次外部调用记录一条（能力 + 参数摘要 + 成败）
        val auditParams = try { req.params?.toString()?.take(200) ?: "" } catch (_: Throwable) { "" }
        BrowserCore.audit(cap, auditParams, true)

        return try {
            when (cap) {
                "browser_open" -> handleOpen(req.params)
                "browser_read" -> handleRead(req.params)
                "browser_crawl" -> handleCrawl()
                "browser_search" -> handleSearch(req.params)
                "browser_script" -> handleScript(req.params)
                "browser_list" -> handleList()
                "browser_info" -> handleInfo()
                "browser_capture" -> handleCapture(req.params)
                "browser_find" -> handleFind(req.params)
                "browser_nav" -> handleNav(req.params)
                "browser_screenshot" -> handleScreenshot()
                "console_ui" -> handleConsoleUi()
                "console_action" -> handleConsoleAction(req.params)
                "browser_elements" -> handleElements()
                "browser_action" -> handleAction(req.params)
                "browser_wait" -> handleWait(req.params)
                "browser_snapshot" -> handleSnapshot(req.params)
                "browser_restore" -> handleRestore(req.params)
                "browser_events" -> handleEvents(req.params)
                "browser_audit" -> handleAudit(req.params)
                "browser_media" -> handleMedia()
                "browser_share" -> handleShare(req.params)
                "browser_console" -> handleConsole(req.params)
                "browser_query" -> handleQuery(req.params)
                "browser_tabnew" -> handleTabNew(req.params)
                "browser_tabs" -> handleTabs()
                "browser_tab" -> handleTab(req.params)
                "browser_tabclose" -> handleTabClose(req.params)
                "browser_mouse" -> handleMouse(req.params)
                "http_request" -> handleHttpRequest(req.params)
                "workspace_list" -> handleWorkspaceList()
                "workspace_read" -> handleWorkspaceRead(req.params)
                "workspace_write" -> handleWorkspaceWrite(req.params)
                "workspace_delete" -> handleWorkspaceDelete(req.params)
                "ui_snapshot" -> handleUiSnapshot(req.params)
                "tap" -> handleTap(req.params)
                else -> {
                    DiagBuffer.append(TAG, "onCall: 未知能力 $cap")
                    AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "unknown: $cap")
                }
            }
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "onCall 异常: ${e.message}")
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, e.message ?: "err")
        }
    }

    // ── 能力实现 ──

    /**
     * 打开网址（v1.0.12 回归修复版）：
     * 1. 先登记标签（BrowserCore.openTab），让 browser_tabs 能反映本次打开；
     * 2. 启动 Activity（onCreate / onNewIntent 会自载 intent url）；
     * 3. 等 WebView 注册就绪（冷启动给足 5s）；
     * 4. 武装页面就绪闸门后，确保真正 loadUrl（覆盖 Activity 已存在但未自载的情况）；
     * 5. 等待 onPageFinished（最多 15s），避免「launched=true 但页面没加载」。
     */
    private fun handleOpen(params: Bundle?): AidlAciResponse {
        val url = params?.getString("url") ?: ""
        val title = params?.getString("title") ?: ""
        DiagBuffer.append(TAG, "browser_open: url=$url")
        if (url.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no url")

        // 1. 登记标签（修复 browser_tabs 恒空）
        BrowserCore.openTab(url, title)

        // 2. 启动 Activity（自载 intent url）
        try {
            startActivity(Intent(this, BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("url", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "browser_open: Activity启动失败 ${e.message}")
        }

        // 3. 等 WebView 注册就绪
        val wv = BrowserCore.awaitWebView(5000)
        if (wv == null) {
            DiagBuffer.append(TAG, "browser_open: WebView 5s 内未就绪（Activity 可能未启动）")
            return AidlAciResponse.success(Bundle())
                .putResult("launched", true)
                .putResult("ready", false)
                .putResult("url", url)
                .putResult("warn", "WebView 未就绪，页面可能稍后加载；可重试 browser_open 或等待 Activity 出现")
        }

        // 4+5. 确保真正加载并等待 onPageFinished
        BrowserCore.armPageReady(url)
        if (BrowserCore.getUrl() != url) {
            BrowserCore.loadUrl(url)
        } else {
            DiagBuffer.append(TAG, "browser_open: 页面已由 Activity 自载，跳过重复 loadUrl")
        }
        val ready = BrowserCore.awaitPageReady(15000)
        DiagBuffer.append(TAG, "browser_open: 页面就绪=$ready url=${BrowserCore.getUrl()}")
        return AidlAciResponse.success(Bundle())
            .putResult("launched", true)
            .putResult("ready", ready)
            .putResult("url", BrowserCore.getUrl() ?: url)
    }

    /**
     * 读取当前页 URL/标题/HTML（v6 修复 Binder ~1MB 溢出）。
     * 策略：始终返回「安全截断的 html 字符串」（≤150k 字符，永不过 Binder，向后兼容）；
     * 若原始 HTML 过大，额外 gzip 压成 byte[] 经 html_gz 回传，控制端解压拿到完整内容，
     * 彻底绕开 1MB 事务限制。gzip 仍超 900KB 时放弃 html_gz，仅返回截断预览。
     */
    /**
     * 读取当前页 URL/标题/HTML，支持 mode：
     * - full（默认）：完整 HTML（v6 修复 Binder ~1MB 溢出，截断预览 + html_gz）
     * - clean：精简 DOM（去 script/style/link/meta，可交互元素打 data-ai-id，标 data-in-viewport），AI 友好
     */
    private fun handleRead(params: Bundle?): AidlAciResponse {
        // 【v1.0.11 回归修复】读前先确认 WebView 已就绪，否则给明确错误而非返回空串
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
        val mode = (params?.getString("mode") ?: "full").lowercase()
        val raw = if (mode == "clean") BrowserCore.readCleanDom() else BrowserCore.readHtml()
        DiagBuffer.append(TAG, "browser_read: mode=$mode getUrl=${BrowserCore.getUrl()} rawLen=${raw.length}")
        val url = BrowserCore.getUrl() ?: ""
        val title = BrowserCore.getTitle() ?: ""
        val truncated = raw.length > 150_000
        val safe = if (truncated) {
            raw.take(150_000) + "\n…[内容已截断，完整 HTML 见 html_gz，共 ${raw.length} 字符]"
        } else raw
        val resp = AidlAciResponse.success(Bundle())
            .putResult("url", url)
            .putResult("title", title)
            .putResult("html", safe)
            .putResult("mode", mode)
            .putResult("truncated", truncated)
        if (truncated) {
            val gz = gzip(raw.toByteArray())
            DiagBuffer.append(TAG, "browser_read: gzipLen=${gz.size}")
            if (gz.size <= 900_000) {
                resp.putResult("html_gz", gz)
                resp.putResult("html_len", raw.length)
            } else {
                DiagBuffer.append(TAG, "browser_read: gzip 仍超 Binder(${gz.size})，放弃 html_gz，仅返回截断预览")
            }
        }
        return resp
    }

    /** 资源扫描：返回当前页 video/audio/下载链接/图片 的绝对 URL + 元数据。 */
    private fun handleMedia(): AidlAciResponse {
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
        val raw = BrowserCore.scanResources()
        DiagBuffer.append(TAG, "browser_media: rawLen=${raw.length}")
        return try {
            val o = JSONObject(raw)
            val count = o.optInt("count", 0)
            val res = o.optJSONArray("resources")?.toString() ?: "[]"
            val truncated = res.length > 150_000
            val resp = AidlAciResponse.success(Bundle())
                .putResult("count", "$count")
                .putResult("resources", if (truncated) res.take(150_000) else res)
            if (o.has("error")) resp.putResult("error", o.optString("error"))
            resp
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "media parse: ${e.message}")
        }
    }

    /** 分享：调起系统 Sharesheet 分享当前页（URL/标题）或自定义文本。 */
    private fun handleShare(params: Bundle?): AidlAciResponse {
        val type = (params?.getString("type") ?: "page").lowercase()
        var textToShare = params?.getString("text") ?: ""
        if (type == "page") {
            if (BrowserCore.awaitWebView(2000) == null) {
                return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
            }
            val url = BrowserCore.getUrl() ?: ""
            val title = BrowserCore.getTitle() ?: ""
            textToShare = if (title.isNotEmpty()) "$title\n$url" else url
            if (url.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no page url to share")
        } else if (type == "text") {
            if (textToShare.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no text to share")
        } else {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "unsupported share type: $type (支持 page/text)")
        }
        val launched = try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                this.type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, textToShare)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "browser_share: 启动失败 ${e.message}")
            false
        }
        DiagBuffer.append(TAG, "browser_share: type=$type launched=$launched")
        return AidlAciResponse.success(Bundle())
            .putResult("launched", launched)
            .putResult("type", type)
    }

    /** 控制台日志：list/clear/enable/disable 当前页 console.* 输出（原生 onConsoleMessage 钩取）。 */
    private fun handleConsole(params: Bundle?): AidlAciResponse {
        val action = (params?.getString("action") ?: "list").lowercase()
        when (action) {
            "enable" -> { BrowserCore.setConsoleEnabled(true); DiagBuffer.append(TAG, "browser_console: enabled") }
            "disable" -> { BrowserCore.setConsoleEnabled(false); DiagBuffer.append(TAG, "browser_console: disabled") }
            "clear" -> { BrowserCore.clearConsole(); DiagBuffer.append(TAG, "browser_console: cleared") }
        }
        val limit = (params?.getString("limit")?.toIntOrNull() ?: 200).coerceIn(1, 1000)
        val filter = params?.getString("filter") ?: ""
        val items = BrowserCore.getConsoleSnapshot(limit, filter)
        val arr = org.json.JSONArray()
        for (it in items) {
            val o = org.json.JSONObject()
            o.put("level", it.level)
            o.put("text", it.text)
            o.put("source", it.source)
            o.put("line", it.line)
            o.put("time", it.time)
            arr.put(o)
        }
        DiagBuffer.append(TAG, "browser_console: action=$action count=${items.size} enabled=${BrowserCore.isConsoleEnabled()}")
        return AidlAciResponse.success(Bundle())
            .putResult("entries", arr.toString())
            .putResult("count", "${items.size}")
            .putResult("enabled", BrowserCore.isConsoleEnabled())
    }

    /** 按 CSS 选择器查询 DOM 元素。 */
    private fun handleQuery(params: Bundle?): AidlAciResponse {
        val selector = params?.getString("selector") ?: ""
        DiagBuffer.append(TAG, "browser_query: selector=$selector")
        if (selector.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no selector")
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        val raw = BrowserCore.queryBySelector(selector)
        DiagBuffer.append(TAG, "browser_query: rawLen=${raw.length}")
        return try {
            val o = JSONObject(raw)
            val count = o.optInt("count", 0)
            val matches = o.optJSONArray("matches")?.toString() ?: "[]"
            val truncated = matches.length > 150_000
            val resp = AidlAciResponse.success(Bundle())
                .putResult("count", "$count")
                .putResult("matches", if (truncated) matches.take(150_000) else matches)
            if (o.has("error")) resp.putResult("error", o.optString("error"))
            resp
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "query parse: ${e.message}")
        }
    }

    /** 轻量多标签：新建并打开（载入 + 记录标签）。 */
    private fun handleTabNew(params: Bundle?): AidlAciResponse {
        val url = params?.getString("url") ?: ""
        val title = params?.getString("title") ?: ""
        DiagBuffer.append(TAG, "browser_tabnew: url=$url")
        if (url.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no url")
        val tab = BrowserCore.openTab(url, title)
        BrowserCore.loadUrl(url)
        try {
            startActivity(Intent(this, BrowserActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("url", url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "browser_tabnew: Activity启动失败 ${e.message}")
        }
        BrowserCore.awaitWebView(3000)
        return AidlAciResponse.success(Bundle())
            .putResult("tab_id", tab.id)
            .putResult("url", url)
            .putResult("active", true)
    }

    /** 轻量多标签：列出。 */
    private fun handleTabs(): AidlAciResponse {
        val items = BrowserCore.listTabs()
        val activeId = BrowserCore.activeTab()?.id ?: ""
        val arr = org.json.JSONArray()
        for (t in items) {
            val o = org.json.JSONObject()
            o.put("id", t.id)
            o.put("url", t.url)
            o.put("title", t.title)
            o.put("active", t.id == activeId)
            arr.put(o)
        }
        DiagBuffer.append(TAG, "browser_tabs: count=${items.size} active=$activeId")
        return AidlAciResponse.success(Bundle())
            .putResult("count", "${items.size}")
            .putResult("tabs", arr.toString())
            .putResult("active_id", activeId)
    }

    /** 轻量多标签：切换到指定标签（重载其 URL）。 */
    private fun handleTab(params: Bundle?): AidlAciResponse {
        val id = params?.getString("id") ?: ""
        DiagBuffer.append(TAG, "browser_tab: id=$id")
        if (id.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no id")
        val t = BrowserCore.switchTab(id) ?: return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "tab not found: $id")
        BrowserCore.loadUrl(t.url)
        BrowserCore.awaitWebView(2000)
        return AidlAciResponse.success(Bundle())
            .putResult("ok", true)
            .putResult("url", BrowserCore.getUrl() ?: t.url)
            .putResult("id", id)
    }

    /** 轻量多标签：关闭指定标签。 */
    private fun handleTabClose(params: Bundle?): AidlAciResponse {
        val id = params?.getString("id") ?: ""
        DiagBuffer.append(TAG, "browser_tabclose: id=$id")
        if (id.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no id")
        val closed = BrowserCore.closeTab(id)
        if (closed) {
            BrowserCore.activeTab()?.let { BrowserCore.loadUrl(it.url) }
        }
        val remaining = BrowserCore.listTabs().size
        DiagBuffer.append(TAG, "browser_tabclose: closed=$closed remaining=$remaining")
        return AidlAciResponse.success(Bundle())
            .putResult("ok", closed)
            .putResult("remaining", "$remaining")
    }

    /** 虚拟鼠标：在屏幕坐标派发鼠标动作（tap/drag/scroll/hover）。 */
    private fun handleMouse(params: Bundle?): AidlAciResponse {
        val action = (params?.getString("action") ?: "").lowercase()
        if (action.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no action")
        val x = params?.getString("x")?.toIntOrNull()
            ?: params?.getInt("x", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            ?: return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no x")
        val y = params?.getString("y")?.toIntOrNull()
            ?: params?.getInt("y", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            ?: return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no y")
        val dx = params?.getString("dx")?.toIntOrNull() ?: params?.getInt("dx", 0) ?: 0
        val dy = params?.getString("dy")?.toIntOrNull() ?: params?.getInt("dy", 0) ?: 0
        val button = (params?.getString("button") ?: "left").lowercase()
        DiagBuffer.append(TAG, "browser_mouse: action=$action x=$x y=$y dx=$dx dy=$dy btn=$button")
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        val raw = BrowserCore.mouseAction(action, x, y, dx, dy, button)
        return try {
            val o = JSONObject(raw)
            val okk = o.optBoolean("ok", false)
            val resp = AidlAciResponse.success(Bundle())
                .putResult("ok", okk)
                .putResult("action", action)
                .putResult("x", x)
                .putResult("y", y)
            if (o.has("error")) resp.putResult("error", o.optString("error"))
            resp
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "mouse parse: ${e.message}")
        }
    }

    /**
     * ui_snapshot：返回当前可视区域元素快照（屏幕坐标），供控制端 clickText/clickResourceId 解析锚点。
     * 节点格式固定为 text|resId|left,top,right,bottom（屏幕像素整数），与控制端解析契约一致。
     */
    private fun handleUiSnapshot(params: Bundle?): AidlAciResponse {
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        val raw = BrowserCore.uiSnapshot()
        return try {
            val o = JSONObject(raw)
            if (!o.optBoolean("ok", false)) {
                return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, o.optString("error", "ui_snapshot failed"))
            }
            val arr = o.optJSONArray("elements") ?: org.json.JSONArray()
            val nodes = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val text = e.optString("text", "").replace("|", " ").trim()
                val resId = e.optString("resId", "").replace("|", " ").trim()
                val left = e.optInt("left"); val top = e.optInt("top")
                val right = e.optInt("right"); val bottom = e.optInt("bottom")
                nodes.add("$text|$resId|$left,$top,$right,$bottom")
            }
            DiagBuffer.append(TAG, "ui_snapshot: 返回 ${nodes.size} 个节点")
            AidlAciResponse.success().putResult("nodes", nodes)
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "ui_snapshot parse: ${e.message}")
        }
    }

    /**
     * tap：在屏幕坐标模拟单击（与 ui_snapshot 同一坐标空间）。受控端无系统特权，
     * 由 BrowserCore.mouseAction 在 WebView 视图内派发视图级触摸事件实现点击。
     */
    private fun handleTap(params: Bundle?): AidlAciResponse {
        val x = params?.getString("x")?.toIntOrNull()
            ?: params?.getInt("x", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            ?: return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no x")
        val y = params?.getString("y")?.toIntOrNull()
            ?: params?.getInt("y", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            ?: return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no y")
        DiagBuffer.append(TAG, "tap: x=$x y=$y")
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        val raw = BrowserCore.mouseAction("click", x, y, 0, 0, "left")
        return try {
            val o = JSONObject(raw)
            if (o.optBoolean("ok", false)) {
                AidlAciResponse.success().putResult("x", x).putResult("y", y)
            } else {
                AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, o.optString("error", "tap failed"))
            }
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "tap parse: ${e.message}")
        }
    }

    /** 把 crawlPage 返回的 JSON 拆成结构化字段（容错：缺字段/解析失败给 error）。 */
    private data class CrawlResult(
        val url: String, val title: String, val text: String, val links: String, val linkCount: Int, val err: String?
    )
    private fun parseCrawl(raw: String): CrawlResult {
        if (raw.isEmpty()) return CrawlResult("", "", "", "[]", 0, "empty")
        return try {
            val o = JSONObject(raw)
            if (o.has("error")) return CrawlResult("", "", "", "[]", 0, o.optString("error"))
            val linksArr = o.optJSONArray("links")
            CrawlResult(
                o.optString("url", ""),
                o.optString("title", ""),
                o.optString("text", ""),
                linksArr?.toString() ?: "[]",
                o.optInt("linkCount", 0),
                null
            )
        } catch (e: Throwable) {
            CrawlResult("", "", "", "[]", 0, "parse:${e.message}")
        }
    }

    /** 爬虫：抓取当前页结构化数据。 */
    private fun handleCrawl(): AidlAciResponse {
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
        val raw = BrowserCore.crawlPage()
        DiagBuffer.append(TAG, "browser_crawl: beforeCrawl getUrl=${BrowserCore.getUrl()} rawLen=${raw.length}")
        val c = parseCrawl(raw)
        val resp = AidlAciResponse.success(Bundle())
        if (c.err != null) {
            resp.putResult("error", c.err)
            return resp
        }
        val truncated = c.text.length > 150_000
        val safe = if (truncated) c.text.take(150_000) + "\n…[正文已截断，完整内容见 browser_read]" else c.text
        resp.putResult("url", c.url)
            .putResult("title", c.title)
            .putResult("text", safe)
            .putResult("links", c.links)
            .putResult("link_count", "${c.linkCount}")
            .putResult("truncated", truncated)
        return resp
    }

    /** 检索：用搜索引擎查词，返回结果页结构化数据。 */
    private fun handleSearch(params: Bundle?): AidlAciResponse {
        val q = params?.getString("query") ?: ""
        DiagBuffer.append(TAG, "browser_search: query=$q")
        if (q.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no query")
        val engine = (params?.getString("engine") ?: "bing").lowercase()
        val enc = URLEncoder.encode(q, "UTF-8")
        val url = when (engine) {
            "google" -> "https://www.google.com/search?q=$enc"
            "baidu" -> "https://www.baidu.com/s?wd=$enc"
            "ddg", "duckduckgo" -> "https://duckduckgo.com/?q=$enc"
            else -> "https://www.bing.com/search?q=$enc"
        }
        // 【v1.0.11 回归修复】检索需要先把结果页载入 WebView，先确认就绪
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
        BrowserCore.loadUrl(url)
        val raw = BrowserCore.crawlPage()
        val c = parseCrawl(raw)
        val resp = AidlAciResponse.success(Bundle())
            .putResult("query", q)
            .putResult("engine", engine)
            .putResult("url", if (c.url.isEmpty()) url else c.url)
            .putResult("title", c.title)
        val truncated = c.text.length > 150_000
        resp.putResult("text", if (truncated) c.text.take(150_000) else c.text)
            .putResult("links", c.links)
            .putResult("truncated", truncated)
        if (c.err != null) resp.putResult("error", c.err)
        return resp
    }

    /** 脚本：在当前页执行任意 JS 并返回结果。 */
    private fun handleScript(params: Bundle?): AidlAciResponse {
        val code = params?.getString("code") ?: ""
        DiagBuffer.append(TAG, "browser_script: codeLen=${code.length}")
        if (code.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no code")
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
        val raw = BrowserCore.evalScript(code)
        val truncated = raw.length > 150_000
        val safe = if (truncated) raw.take(150_000) + "\n…[结果已截断]" else raw
        return AidlAciResponse.success(Bundle())
            .putResult("result", safe)
            .putResult("truncated", truncated)
    }

    /** 抓包：list/clear/enable/disable 当前页网络请求拦截记录。 */
    private fun handleCapture(params: Bundle?): AidlAciResponse {
        val action = (params?.getString("action") ?: "list").lowercase()
        when (action) {
            "enable" -> { BrowserCore.setCaptureEnabled(true); DiagBuffer.append(TAG, "browser_capture: enabled") }
            "disable" -> { BrowserCore.setCaptureEnabled(false); DiagBuffer.append(TAG, "browser_capture: disabled") }
            "clear" -> { BrowserCore.clearCapture(); DiagBuffer.append(TAG, "browser_capture: cleared") }
        }
        val limit = (params?.getString("limit")?.toIntOrNull() ?: 200).coerceIn(1, 1000)
        val filter = params?.getString("filter") ?: ""
        val items = BrowserCore.getCaptureSnapshot(limit, filter)
        val arr = org.json.JSONArray()
        for (it in items) {
            val o = org.json.JSONObject()
            o.put("url", it.url)
            o.put("method", it.method)
            o.put("headers", it.headers)
            o.put("is_main_frame", it.isMainFrame)
            o.put("time", it.time)
            arr.put(o)
        }
        DiagBuffer.append(TAG, "browser_capture: action=$action count=${items.size} enabled=${BrowserCore.isCaptureEnabled()}")
        return AidlAciResponse.success(Bundle())
            .putResult("requests", arr.toString())
            .putResult("count", "${items.size}")
            .putResult("enabled", BrowserCore.isCaptureEnabled())
            .putResult("note", "v1 抓包=请求侧拦截(WebView shouldInterceptRequest)，可看 URL/方法/请求头；响应状态码与响应体需 Chrome DevTools 协议，后续支持")
    }

    /** 页面内查找（find / next / prev / clear）。 */
    private fun handleFind(params: Bundle?): AidlAciResponse {
        val text = params?.getString("text") ?: ""
        val action = (params?.getString("action") ?: "find").lowercase()
        val forward = (params?.getString("forward") ?: "true").lowercase() != "false"
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        return when (action) {
            "next" -> {
                BrowserCore.findNext(forward)
                AidlAciResponse.success(Bundle()).putResult("found", true).putResult("count", "-1")
            }
            "prev" -> {
                BrowserCore.findNext(false)
                AidlAciResponse.success(Bundle()).putResult("found", true).putResult("count", "-1")
            }
            "clear" -> {
                BrowserCore.clearFind()
                AidlAciResponse.success(Bundle()).putResult("found", false).putResult("count", "0")
            }
            else -> {
                if (text.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no text")
                val n = BrowserCore.findInPage(text)
                AidlAciResponse.success(Bundle()).putResult("found", n > 0).putResult("count", "$n")
            }
        }
    }

    /** 导航：后退 / 前进 / 刷新。 */
    private fun handleNav(params: Bundle?): AidlAciResponse {
        val action = (params?.getString("action") ?: "reload").lowercase()
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        when (action) {
            "back" -> BrowserCore.navBack()
            "forward" -> BrowserCore.navForward()
            else -> BrowserCore.navReload()
        }
        val url = BrowserCore.getUrl() ?: ""
        return AidlAciResponse.success(Bundle())
            .putResult("url", url)
            .putResult("can_back", BrowserCore.canGoBack())
            .putResult("can_forward", BrowserCore.canGoForward())
    }

    /** 截图当前可视区域，保存到应用外部存储 Pictures/QuroAI_screenshots/，返回路径。 */
    private fun handleScreenshot(): AidlAciResponse {
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        val base = try {
            applicationContext.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)?.absolutePath
                ?: cacheDir.absolutePath
        } catch (_: Throwable) { cacheDir.absolutePath }
        val dir = "$base/QuroAI_screenshots"
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val path = "$dir/screenshot_$stamp.png"
        val got = BrowserCore.screenshot(path)
        return if (got.isNotEmpty()) {
            AidlAciResponse.success(Bundle()).putResult("path", got).putResult("url", BrowserCore.getUrl() ?: "")
        } else {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "截图失败（WebView 尺寸为 0 或无权限）")
        }
    }

    /** gzip 压缩（受控端用，绕过 AIDL ~1MB 限制）。 */
    private fun gzip(data: ByteArray): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        val gz = java.util.zip.GZIPOutputStream(bos)
        gz.write(data)
        gz.finish()
        gz.close()
        return bos.toByteArray()
    }

    /**
     * HTTP 传输能力（与主应用 QuroMainAciService 对称）：
     * onCall 在 Binder 线程被调用，HTTP 网络必须 offload 到后台线程 + CountDownLatch 阻塞等待
     *（硬上限 HARD_TIMEOUT_S=14s，< 控制器 15s 超时），避免 NetworkOnMainThread 与控制器超时。
     */
    private fun handleHttpRequest(params: Bundle?): AidlAciResponse {
        val url = params?.getString("url") ?: ""
        if (url.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no url")
        val method = (params?.getString("method") ?: "GET").uppercase()
        val headersStr = params?.getString("headers") ?: ""
        val body = params?.getString("body") // 可能为 null
        DiagBuffer.append(TAG, "http_request: $method $url")

        val latch = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var result: AidlAciResponse? = null
        executor.execute {
            try {
                result = doHttp(method, url, headersStr, body)
            } catch (e: Throwable) {
                result = AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "http_failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        val done = latch.await(HARD_TIMEOUT_S, TimeUnit.SECONDS)
        executor.shutdownNow()
        return if (done) {
            result ?: AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "no result")
        } else {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "http timeout (>$HARD_TIMEOUT_S s, 控制器上限 15s)")
        }
    }

    private fun doHttp(method: String, url: String, headersStr: String, body: String?): AidlAciResponse {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(14, TimeUnit.SECONDS)
            .writeTimeout(14, TimeUnit.SECONDS)
            .build()

        val reqBuilder = Request.Builder().url(url)
        if (headersStr.isNotEmpty()) {
            try {
                val h = JSONObject(headersStr)
                val it = h.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    reqBuilder.addHeader(k, h.optString(k))
                }
            } catch (ignored: Throwable) {
                DiagBuffer.append(TAG, "http_request: headers 解析失败，忽略: $headersStr")
            }
        }

        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val reqBody: RequestBody? = if (!body.isNullOrEmpty()) {
            body.toByteArray(Charsets.UTF_8).toRequestBody(mediaType)
        } else null

        val okReq: Request = try {
            val builtBuilder: Request.Builder = when (method) {
                "GET" -> reqBuilder.get()
                "HEAD" -> reqBuilder.head()
                "POST" -> reqBuilder.post(bodyOrEmpty(reqBody))
                "PUT" -> reqBuilder.put(bodyOrEmpty(reqBody))
                "PATCH" -> reqBuilder.patch(bodyOrEmpty(reqBody))
                "DELETE" -> reqBuilder.delete(reqBody)
                else -> reqBuilder.method(method, reqBody)
            }
            builtBuilder.build()
        } catch (e: Throwable) {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "bad method/body: ${e.message}")
        }

        val response = client.newCall(okReq).execute()
        try {
            val code = response.code
            val headers = JSONObject()
            for (i in 0 until response.headers.size) {
                headers.put(response.headers.name(i), response.headers.value(i))
            }

            // 大响应体保护：Content-Length > 2MB 不载入内存，直接标记截断
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            if (contentLength > 2_000_000L) {
                return AidlAciResponse.success(Bundle())
                    .putResult("status_code", code)
                    .putResult("response_headers", headers.toString())
                    .putResult("response_body", "")
                    .putResult("truncated", true)
                    .putResult(
                        "truncated_reason",
                        "响应体超过 2MB，未载入内存（如需大文件请改用文件下载能力）"
                    )
            }

            val raw = response.body?.string() ?: ""
            val truncated = raw.length > 150_000
            val safe = if (truncated) {
                raw.take(150_000) + "\n…[响应体已截断，完整内容见 response_body_gz，共 ${raw.length} 字符]"
            } else raw
            val r = AidlAciResponse.success(Bundle())
                .putResult("status_code", code)
                .putResult("response_headers", headers.toString())
                .putResult("response_body", safe)
                .putResult("truncated", truncated)
            if (truncated) {
                val gz = gzip(raw.toByteArray())
                if (gz.size <= 900_000) {
                    r.putResult("response_body_gz", gz)
                    r.putResult("response_body_len", raw.length)
                }
            }
            return r
        } finally {
            response.close()
        }
    }

    private fun bodyOrEmpty(b: RequestBody?): RequestBody =
        b ?: ByteArray(0).toRequestBody()

    private fun handleList(): AidlAciResponse {
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：无活动页面，请先调用 browser_open")
        }
        return AidlAciResponse.success(Bundle())
            .putResult("tabs", "url=${BrowserCore.getUrl()} title=${BrowserCore.getTitle()}")
    }

    private fun handleInfo(): AidlAciResponse {
        return AidlAciResponse.success(Bundle())
            .putResult("package", packageName)
            .putResult("versionName", try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" })
            .putResult("versionCode", try { "${packageManager.getPackageInfo(packageName, 0).longVersionCode}" } catch (_: Throwable) { "0" })
    }

    /** SDUI 控制台：返回后端驱动的 UI 描述 JSON（前端渲染，后端免发版）。 */
    private fun handleConsoleUi(): AidlAciResponse {
        val snap = ConsoleBackend.buildUiSnapshot()
        DiagBuffer.append(TAG, "console_ui: 返回快照（${snap.optJSONArray("components")?.length() ?: 0} 组件）")
        return AidlAciResponse.success(Bundle())
            .putResult("snapshot", snap.toString())
            .putResult("title", snap.optString("title", ""))
    }

    /** SDUI 控制台：处理前端回传的动作（increment/reset/submit_note）。 */
    private fun handleConsoleAction(params: Bundle?): AidlAciResponse {
        val action = params?.getString("action") ?: ""
        DiagBuffer.append(TAG, "console_action: action=$action")
        if (action.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no action")
        val payloadStr = params?.getString("payload") ?: ""
        val payload = try {
            if (payloadStr.isNotEmpty()) JSONObject(payloadStr) else null
        } catch (e: Throwable) {
            DiagBuffer.append(TAG, "console_action: payload 解析失败 ${e.message}")
            null
        }
        val r = ConsoleBackend.applyAction(action, payload)
        return AidlAciResponse.success(Bundle())
            .putResult("ok", r.optBoolean("ok", false))
            .putResult("action", r.optString("action", action))
    }

    /** 元素树：返回当前页可交互元素列表（含自动标注的稳定ID）。 */
    private fun handleElements(): AidlAciResponse {
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        val raw = BrowserCore.queryElements()
        DiagBuffer.append(TAG, "browser_elements: rawLen=${raw.length}")
        return try {
            val o = JSONObject(raw)
            val count = o.optInt("count", 0)
            val els = o.optJSONArray("elements")?.toString() ?: "[]"
            val truncated = els.length > 150_000
            val elsOut = if (truncated) els.take(150_000) else els
            val resp = AidlAciResponse.success(Bundle())
                .putResult("count", "$count")
                .putResult("elements", elsOut)
                .putResult("truncated", truncated)
                .putResult("returned_len", "${elsOut.length}")
                .putResult("total_len", "${els.length}")
            if (o.has("error")) resp.putResult("error", o.optString("error"))
            resp
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "elements parse: ${e.message}")
        }
    }

    /** 按稳定ID执行操作：click / type / scroll_to / select。 */
    private fun handleAction(params: Bundle?): AidlAciResponse {
        val id = params?.getString("id") ?: ""
        val selector = params?.getString("selector") ?: ""
        val op = params?.getString("op") ?: ""
        DiagBuffer.append(TAG, "browser_action: id=$id selector=$selector op=$op")
        if (op.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no op")
        if (id.isEmpty() && selector.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no id or selector")
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        val arg = params?.getString("arg") ?: ""
        val raw = if (selector.isNotEmpty()) BrowserCore.actionBySelector(selector, op, arg) else BrowserCore.actionOnElement(id, op, arg)
        DiagBuffer.append(TAG, "browser_action: r=$raw")
        return try {
            val o = JSONObject(raw)
            val okk = o.optBoolean("ok", false)
            val resp = AidlAciResponse.success(Bundle())
                .putResult("ok", okk)
                .putResult("op", op)
            if (o.has("error")) resp.putResult("error", o.optString("error"))
            else if (!okk) resp.putResult("error", "action failed")
            resp
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "action parse: ${e.message}")
        }
    }

    /** 条件等待引擎：visible / hidden / text_contains / network_idle。 */
    private fun handleWait(params: Bundle?): AidlAciResponse {
        val cond = (params?.getString("cond") ?: "").lowercase()
        if (cond.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no cond")
        val id = params?.getString("id") ?: ""
        val arg = params?.getString("arg") ?: ""
        val timeoutMs = (params?.getString("timeout_ms")?.toLongOrNull() ?: 8000).coerceIn(200, 60000)
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        DiagBuffer.append(TAG, "browser_wait: cond=$cond id=$id timeout=$timeoutMs")
        val raw = BrowserCore.waitFor(cond, id, arg, timeoutMs)
        return try {
            val o = JSONObject(raw)
            val okk = o.optBoolean("ok", false)
            val resp = AidlAciResponse.success(Bundle())
                .putResult("ok", okk)
                .putResult("waited_ms", "${o.optLong("waited_ms", 0)}")
            if (o.has("reason")) resp.putResult("reason", o.optString("reason"))
            resp
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "wait parse: ${e.message}")
        }
    }

    /** 页面快照：save（默认）/ list。 */
    private fun handleSnapshot(params: Bundle?): AidlAciResponse {
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        val action = (params?.getString("action") ?: "save").lowercase()
        if (action == "list") {
            val items = BrowserCore.listSnapshots()
            val arr = org.json.JSONArray()
            for (s in items) {
                val o = org.json.JSONObject()
                o.put("id", s.id)
                o.put("url", s.url)
                o.put("title", s.title)
                o.put("html_len", s.html.length)
                o.put("time", s.time)
                arr.put(o)
            }
            DiagBuffer.append(TAG, "browser_snapshot: list count=${items.size}")
            return AidlAciResponse.success(Bundle())
                .putResult("snapshots", arr.toString())
                .putResult("count", "${items.size}")
        }
        val label = params?.getString("label") ?: ""
        val id = BrowserCore.snapshotPage(label)
        DiagBuffer.append(TAG, "browser_snapshot: save id=$id")
        return AidlAciResponse.success(Bundle()).putResult("id", id).putResult("ok", true)
    }

    /** 页面回滚：导航回指定快照的 URL。 */
    private fun handleRestore(params: Bundle?): AidlAciResponse {
        val id = params?.getString("id") ?: ""
        DiagBuffer.append(TAG, "browser_restore: id=$id")
        if (id.isEmpty()) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "no id")
        if (BrowserCore.awaitWebView(2000) == null) {
            return AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "浏览器尚未就绪：请先调用 browser_open")
        }
        val okk = BrowserCore.restoreSnapshot(id)
        return if (okk) {
            AidlAciResponse.success(Bundle()).putResult("ok", true).putResult("id", id)
        } else {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, "snapshot not found: $id")
        }
    }

    /** 页面事件总线：返回最近页面事件流。 */
    private fun handleEvents(params: Bundle?): AidlAciResponse {
        val limit = (params?.getString("limit")?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val items = BrowserCore.getPageEvents(limit)
        val arr = org.json.JSONArray()
        for (it in items) {
            val o = org.json.JSONObject()
            o.put("type", it.type)
            o.put("url", it.url)
            o.put("time", it.time)
            arr.put(o)
        }
        DiagBuffer.append(TAG, "browser_events: count=${items.size}")
        return AidlAciResponse.success(Bundle())
            .putResult("events", arr.toString())
            .putResult("count", "${items.size}")
    }

    /** ACI 调用审计日志：返回最近调用记录。 */
    private fun handleAudit(params: Bundle?): AidlAciResponse {
        val limit = (params?.getString("limit")?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val items = BrowserCore.getAuditLog(limit)
        val arr = org.json.JSONArray()
        for (it in items) {
            val o = org.json.JSONObject()
            o.put("cap", it.cap)
            o.put("params", it.params)
            o.put("ok", it.ok)
            o.put("time", it.time)
            arr.put(o)
        }
        DiagBuffer.append(TAG, "browser_audit: count=${items.size}")
        return AidlAciResponse.success(Bundle())
            .putResult("log", arr.toString())
            .putResult("count", "${items.size}")
    }

    // ── 共享工作空间（vACI-ws）：主应用与受控浏览器跨 App 互读/互写的共享目录 ──

    /** 工作空间目录：落在 aci-browser 自身沙箱（无需 MANAGE_EXTERNAL_STORAGE），主应用经 ACI 读写。 */
    private fun workspaceDir(): File {
        val d = File(filesDir, "aci_workspace")
        if (!d.exists()) d.mkdirs()
        return d
    }

    /**
     * 把用户传入的 name 映射为工作空间内安全的 File，并做目录穿越防护。
     * 拒绝：空名 / 含路径分隔符 / "." ".." / 规范化后逃出 workspaceDir 的文件。
     */
    private fun safeWorkspaceFile(name: String?): File? {
        val n = name?.trim() ?: return null
        if (n.isEmpty() || n.contains("/") || n.contains("\\") || n == "." || n == "..") return null
        val dir = workspaceDir()
        val f = File(dir, n)
        return try {
            val canon = f.canonicalPath
            val base = dir.canonicalPath
            if (canon == base || canon.startsWith(base + File.separator)) f else null
        } catch (_: Throwable) { null }
    }

    /** 简单二进制探测：前 512 字节含 NUL → 判定为二进制。 */
    private fun isBinaryContent(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val limit = minOf(bytes.size, 512)
        for (i in 0 until limit) {
            if ((bytes[i].toInt() and 0xFF) == 0) return true
        }
        return false
    }

    /** workspace_list：列出共享工作空间条目。 */
    private fun handleWorkspaceList(): AidlAciResponse {
        val dir = workspaceDir()
        val arr = JSONArray()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val o = org.json.JSONObject()
            o.put("name", f.name)
            o.put("size", f.length())
            o.put("is_dir", f.isDirectory)
            o.put("mtime", f.lastModified())
            arr.put(o)
        }
        DiagBuffer.append(TAG, "workspace_list: count=${arr.length()}")
        return AidlAciResponse.success(Bundle())
            .putResult("entries", arr.toString())
            .putResult("count", arr.length())
            .putResult("dir", dir.absolutePath)
    }

    /** workspace_read：读取一个文件（文本→content；二进制→content_b64）。 */
    private fun handleWorkspaceRead(params: Bundle?): AidlAciResponse {
        val f = safeWorkspaceFile(params?.getString("name"))
        if (f == null) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "非法文件名（禁止路径分隔或越权）")
        if (!f.exists() || f.isDirectory) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "文件不存在或为目录")
        return try {
            val bytes = f.readBytes()
            val binary = isBinaryContent(bytes)
            if (binary) {
                AidlAciResponse.success(Bundle())
                    .putResult("name", f.name)
                    .putResult("is_binary", true)
                    .putResult("content_b64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .putResult("size", bytes.size)
            } else {
                AidlAciResponse.success(Bundle())
                    .putResult("name", f.name)
                    .putResult("is_binary", false)
                    .putResult("content", String(bytes, Charsets.UTF_8))
                    .putResult("size", bytes.size)
            }
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, e.message ?: "read fail")
        }
    }

    /** workspace_write：写入一个文件（文本 content 或二进制 content_b64）。 */
    private fun handleWorkspaceWrite(params: Bundle?): AidlAciResponse {
        val f = safeWorkspaceFile(params?.getString("name"))
        if (f == null) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "非法文件名（禁止路径分隔或越权）")
        val b64 = params?.getString("content_b64")
        val text = params?.getString("content")
        val bytes = if (!b64.isNullOrBlank()) {
            try { Base64.decode(b64, Base64.NO_WRAP) } catch (_: Throwable) {
                return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "content_b64 不是合法 Base64")
            }
        } else if (text != null) {
            text.toByteArray(Charsets.UTF_8)
        } else {
            return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "content 与 content_b64 至少提供一个")
        }
        return try {
            f.writeBytes(bytes)
            DiagBuffer.append(TAG, "workspace_write: ${f.name} bytes=${bytes.size}")
            AidlAciResponse.success(Bundle()).putResult("written", true).putResult("bytes", bytes.size)
        } catch (e: Throwable) {
            AidlAciResponse.error(AidlAciError.INTERNAL_ERROR, e.message ?: "write fail")
        }
    }

    /** workspace_delete：删除一个文件。 */
    private fun handleWorkspaceDelete(params: Bundle?): AidlAciResponse {
        val f = safeWorkspaceFile(params?.getString("name"))
        if (f == null) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "非法文件名（禁止路径分隔或越权）")
        val deleted = if (f.exists()) f.delete() else true
        return AidlAciResponse.success(Bundle()).putResult("deleted", deleted)
    }
}
