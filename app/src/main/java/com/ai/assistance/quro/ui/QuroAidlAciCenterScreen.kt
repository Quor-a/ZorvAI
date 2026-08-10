package com.ai.assistance.quro.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.quro.core.aidlaci.QuroAidlAciManager
import com.ai.assistance.quro.core.aidlaci.AciConsoleModel
import com.ai.assistance.quro.core.aidlaci.AciConsoleScreen
import com.ai.assistance.quro.core.aidlaci.AidlAciScreen
import org.json.JSONObject
import androidx.compose.ui.window.Dialog
import com.ai.assistance.quro.core.tools.QuroDownloadUtil
import com.ai.assistance.quro.ui.theme.Card as PaperCard
import com.ai.assistance.quro.ui.theme.Line
import com.ai.assistance.quro.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * ACI 被控方（第三方 App）开发手册 —— 接 Zorv AI 作为控制方时，被调方应如何开发。
 * 放在 ACI 管理中心内，供第三方 App 开发者直接照做。
 */
/**
 * 被控方「自写部分」依赖模板：ACI 核心接口 + BaseAidlAciService 骨架。
 * 开发者可把它直接保存到本地作为接入起点（替代手写样板）。
 */
private val ACI_STUB_SOURCE = """
package com.example.aidlaci

import ai.aidl.aci.core.*
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * 最小可运行被控端 Service（依赖 aci-core AAR）。
 * 把本文件放进你的模块、改包名与能力即可编译。
 * 注意：BaseAidlAciService / Capability / AidlAciResponse / AidlAciError 都由 AAR 提供，不要自己重写。
 */
class MyAciService : BaseAidlAciService() {

    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        caps.add(
            Capability.create("echo", "回显文本")   // 第 1 参=id，第 2 参=描述（不是版本号！）
                .addParam("text", "string", true, "要回显的内容")
                .addResult("reply", "string", "回显结果")
        )
    }

    override fun onCall(req: AidlAciRequest?): AidlAciResponse {
        val capability = req?.capability ?: return AidlAciResponse.error(AidlAciError.REQUEST_NULL, "null")
        return when (capability) {
            "echo" -> {
                val text = req.params?.getString("text") ?: ""
                AidlAciResponse.success(Bundle()).putResult("reply", "你发了：${'$'}text")
            }
            else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "未知能力：${'$'}capability")
        }
    }
}
"""

private val ACI_DEV_DOC = """
══════════════════════════════════════════
ACI 被控方（第三方 App）开发手册
══════════════════════════════════════════
📌 本文档是「通用被控方开发手册」，面向任何想接入 ZorvAI 的第三方 App 开发者，并非 ZorvAI 浏览器专属。
   受控浏览器（com.ai.assistance.quro.browser）只是其中一个「官方参考实现示例」；其能力清单（第十节）仅供照抄声明范式，
   你完全可以用自己的业务后端照此接入，不必局限于浏览器。

一、ACI 是什么
• 本地、无 Root 的 App 间 AIDL 调用框架；不依赖 Shizuku / dumpsys / ROOT / 无障碍 / 设备管理员。
• 控制方（AI 中枢，如 Zorv AI）发现并调用第三方 App 暴露的能力；你作为「被控方」按本协议暴露能力。
• 一次调用 = 一个 capability（能力）：带 id、描述、参数清单、是否需用户确认。

二、依赖获取（aci-core AAR）
• AAR 下载（GitHub Release，免登录）：
  https://github.com/Quor-a/ZorvAI/releases/download/v1.0.6/aci-core-release.aar
• Gradle 依赖：implementation(files("libs/aci-core-release.aar"))（把 AAR 放进模块 libs/）
• ACI 核心库独立开源分支：https://github.com/Quor-a/ZorvAI/tree/aci-core
• 完整开发者手册（网页版）：https://github.com/Quor-a/ZorvAI/blob/main/docs/ACI_DEVELOPER_GUIDE.md

三、被控方接入 5 步
1) 依赖 aci-core AAR（提供 ai.aci.core.*：IAidlAciService / AidlAciRequest / AidlAciResponse / Capability / BaseAidlAciService / AidlAciError）。
2) AndroidManifest 声明 3 个 <permission> 定义 + uses-permission + Service（见第四节）。⚠️ 3 个权限定义必须写，否则 Service 的 android:permission 指向不存在的权限 → 绑定必失败。
3) 写一个 Service 继承 ai.aci.core.BaseAidlAciService，重写 onCreateCapabilities(caps) 声明能力、onCall(req) 返回 AidlAciResponse。
4) 在 Application/Activity 里把 Service 跑起来（或被 ACI 唤醒广播拉起，见第五节）。
5) 打包安装 → 在 Zorv AI「ACT 关联启动」点刷新即可发现；或本页「按名称搜索」找到后「注册并启动」。

四、AndroidManifest 配置
<!-- ① 必须声明 3 个权限定义（CALL 普通 / DISCOVER 普通 / CALL_DANGEROUS 危险）。缺任一，绑定会被系统拒绝 -->
<permission android:name="ai.aci.permission.CALL" android:protectionLevel="normal" />
<permission android:name="ai.aci.permission.DISCOVER" android:protectionLevel="normal" />
<permission android:name="ai.aci.permission.CALL_DANGEROUS" android:protectionLevel="dangerous" />

<uses-permission android:name="ai.aci.permission.CALL" />
<uses-permission android:name="ai.aci.permission.DISCOVER" />
<!-- 包可见性（Android 11+）：让控制方能发现你 -->
<queries>
  <intent>
    <action android:name="ai.aci.core.ACTION_BIND" />
  </intent>
</queries>
<service
    android:name=".MyAciService"
    android:exported="true"
    android:permission="ai.aci.permission.CALL">
  <intent-filter>
    <action android:name="ai.aci.core.ACTION_BIND" />
  </intent-filter>
</service>
<!-- 必须有一个可启动的 Activity + 有效图标，否则装了也无桌面入口、启不动。
     图标不要引用已删除的 @android:drawable/* 资源。 -->

五、Kotlin 代码示例
import ai.aidl.aci.core.*
class MyAciService : BaseAidlAciService() {
    override fun onCreateCapabilities(caps: MutableList<Capability>) {
        caps.add(
            Capability.create("echo", "回显文本")   // 第 1 参=id，第 2 参=描述（不是版本号！）
                .addParam("text", "string", true, "要回显的内容")
                .addResult("reply", "string", "回显结果")
        )
        caps.add(
            Capability.create("danger_action", "危险操作示例")
                .setUserConfirm(true)   // 需用户在控制方确认
                .addResult("ok", "boolean", "是否执行成功")
        )
    }
    override fun onCall(req: AidlAciRequest?): AidlAciResponse {
        val capability = req?.capability ?: return AidlAciResponse.error(AidlAciError.REQUEST_NULL, "null")
        return when (capability) {
            "echo" -> {
                val text = req.params?.getString("text") ?: ""
                AidlAciResponse.success(android.os.Bundle())
                    .putResult("reply", "你发了：${'$'}text")
            }
            "danger_action" -> {
                // 服务端兜底：危险能力务必在 onCall 内校验 user_confirmed，防被绕过
                val confirmed = req.params?.getBoolean("user_confirmed", false) ?: false
                if (!confirmed) return AidlAciResponse.error(AidlAciError.BAD_REQUEST, "需要用户确认")
                AidlAciResponse.success(android.os.Bundle()).putResult("ok", true)
            }
            else -> AidlAciResponse.error(AidlAciError.CAPABILITY_NOT_FOUND, "未知能力：${'$'}capability")
        }
    }
}
// 被控 App 处于 stopped 态时，控制方注册会发 ACI 唤醒广播（FLAG_INCLUDE_STOPPED_PACKAGES）拉起进程；
// 你也可在 MainActivity 里直接 startService(MyAciService::class.java) 预热。

六、能力声明规范
• id 用小写蛇形（如 open_door）；Capability.create(id, description) 第 2 参是「描述」，不是版本号。
• 每个参数 addParam(name, type, required, desc)；type ∈ string/int/boolean/float。
• 每个返回 addResult(name, type, desc)。
• 危险能力务必 setUserConfirm(true)，并在 onCall 内校验 user_confirmed（服务端兜底，防被绕过）。

七、控制方如何调用（供你联调）
• Zorv AI 用 aci_call 调用：aci_call(packageName, capability, params)。
• 危险能力调用前控制方会弹确认框；被控方 onCall 仍要查 user_confirmed。

八、打包与测试清单
☐ AAR 依赖正确，BaseAidlAciService 可继承
☐ Manifest 声明 3 个 <permission> + 2 个 uses-permission + Service + intent-filter + queries
☐ 有可启动 Activity 与有效图标
☐ 安装后在 Zorv AI ACT 关联启动「刷新」可见
☐ echo 类能力能正常返回值
☐ 危险能力在两侧都做了确认

九、排障铁律
• aci_list 为空：别用 Shizuku / dumpsys / ROOT 排查，确认你已装且 Service 带 ACTION_BIND。
• 返回 503：绑定生命周期抖动，框架自动重绑，直接重试即可。
• 能力不出现：确认 onCreateCapabilities 用「参数式 caps.add(...)」正确填充，且 Service 已运行（stopped 态会被唤醒广播拉起）。
• 绑定直接失败/秒拒：99% 是 Manifest 漏写 <permission> 定义（CALL / DISCOVER / CALL_DANGEROUS），补上即可。

十、参考实现示例：受控浏览器（com.ai.assistance.quro.browser）已暴露的 30 项能力（29 个 browser_* + http_request，官方参考实现之一，非浏览器专属手册）
（官方参考被控方。控制方 Zorv AI 经 ACI 调用它，第三方开发者可直接照抄这套能力声明范式（注意：这是示例，你的后端可声明任意能力，不必照搬此列表）：
 全部能力均在 onCreateCapabilities 用 Capability.create 参数式声明、onCall 内 when 分发。）

基础能力（13）：
• browser_open(url, title?) —— 打开并导航到指定网址（v1.0.12 回归修复：先登记多标签、再启动 Activity、等待 WebView 注册与页面 onPageFinished 完成后返回 ready/url，约 15s 上限不卡死）；带空格自动转搜索引擎查询。
• browser_info             —— 返回包名 + 版本号。
• browser_list             —— 列出当前打开的浏览器标签页。
• browser_read             —— 读取当前页 URL + 标题 + 完整 HTML（SPA 大页已切片防静默丢弃）；增 `mode` 参数：full(默认) / clean（去脚本样式、可交互元素打 data-ai-id、标视口，供 AI 直接理解页面结构）。
• browser_crawl            —— 返回结构化数据：标题 + 可读正文 + 出站链接（article/main/body 逐级回退）。
• browser_search(kw)       —— 调用搜索引擎检索关键词，返回结果页标题/正文/链接。
• browser_script(code)     —— 在当前页执行任意 JavaScript 并返回结果（核心能力，等价于给 AI 一个完整浏览器控制台）。
• browser_find(text)       —— 页面内查找文本并高亮，返回命中数。
• browser_nav(action)      —— 导航：action ∈ back / forward / reload（WebView 操作已主线程安全封装）。
• browser_screenshot       —— 截当前可视区域存 PNG，返回文件路径（无需存储权限）。
• browser_capture(action)  —— 抓包：action ∈ list / clear / enable / disable，返回请求 URL/方法/请求头/是否主框架。
• console_ui               —— 返回控制台 SDUI 快照（组件 JSON），供控制端通用渲染，与手动控制台共用同一 ConsoleBackend。
• console_action(action, payload) —— 执行控制台动作（点击 / JS / 导航等），与手动控制台单一事实源。

agentic 增强（新增 7 · 元素级操控 + 状态/事件/审计）：
• browser_elements         —— 扫描当前页可交互元素，自动标注稳定ID（data-aci-eid），返回元素树：id/标签/类型/文本/值/链接/位置(x,y,w,h)/可见性。
• browser_action(id, op, arg) —— 按元素稳定ID执行操作：click 点击 / type 输入（兼容 React/Vue 受控输入）/ scroll_to 滚动居中 / select 选择下拉项。
• browser_wait(cond, id?, arg?, timeout_ms?) —— 条件等待引擎：visible / hidden / text_contains（按元素ID）/ network_idle（SPA 加载完成，自动打桩 XHR/fetch 计数）。
• browser_snapshot(action, label?) —— 页面状态快照：save 保存当前 URL/标题/HTML 到快照库（按 label 覆盖）/ list 列出已有快照。
• browser_restore(id)      —— 页面状态回滚：导航回指定快照记录的 URL。
• browser_events(limit?)   —— 页面事件总线：返回 page_started / page_finished / request / load_resource 等事件流。
• browser_audit(limit?)    —— ACI 调用审计：每次外部调用（能力/参数/成败）一条记录。

第二波增强（新增 2 · 资源回传 + 分享）：
• browser_media           —— 扫描当前页 video/audio/source/a[download]/img 资源，返回结构化列表：绝对直链 + 类型 + 文本；video/audio 额外含 current_time/duration/paused/poster；a[download] 含 download。控制方可直接拿直链播放或下载。
• browser_share(type, text?) —— 调起系统分享面板：type ∈ page(分享当前页 URL) / text(分享自定义文本)，返回 launched。

第三波增强（新增 6 · 元宝 TermBrowser「完整方案」落地：控制台捕获 + 选择器操控 + 轻量多标签）+ browser_action selector 增强：
• browser_console(action?, limit?, filter?) —— 抓取当前页 console.* 输出（log/warn/error/info）：action ∈ list(默认)/clear/enable/disable，返回 entries[{level,text,source,line,time}] + count + enabled。原生 WebChromeClient.onConsoleMessage 钩取（默认开启）。
• browser_query(selector)  —— 按 CSS 选择器查询当前页 DOM，返回匹配列表：count + matches[{index,tag,text,value,href,id,cls,x,y,w,h,visible}]。供 AI 直接按选择器定位元素。
• browser_action 增强 —— 原按 data-aci-eid 操作；现新增 `selector`(CSS 选择器) 参数（与 id 二选一，优先级低于 id），可直接用 "#main button" 之类定位操作，免去先 browser_elements 注入。
• browser_tabnew(url, title?) —— 轻量多标签·新建并打开（单引擎，标签记录 URL + 切换重载）。
• browser_tabs             —— 轻量多标签·列出（含 active 标记）+ active_id。
• browser_tab(id)         —— 轻量多标签·切换到指定标签（重载其 URL）。
• browser_tabclose(id)    —— 轻量多标签·关闭（激活标签关闭后自动回退最近一个）。

第四波增强（新增 1 · 虚拟鼠标，回应元宝「完整虚拟鼠标」）：
• browser_mouse(action, x, y, dx?, dy?, button?) —— 在页面「屏幕坐标」模拟鼠标：action ∈ move(悬停)/click/dblclick/right/down/up/drag/scroll；后端按 WebView 在屏位置自动换算视图坐标后派发 MotionEvent（主线程 dispatchTouchEvent / dispatchGenericMotionEvent）。覆盖无稳定ID、无 CSS 选择器的元素与画布交互，与 browser_action(id/selector) 形成「坐标 + 语义」双通道。注：系统 WebView 将触摸事件按触摸处理，右键为尽力而为。
• http_request(url, method?, headers?, body?) —— HTTP 传输：经 ACI 让受控浏览器代为发起任意 HTTP 请求（GET/POST/PUT/DELETE/PATCH/HEAD 等），返回 status_code / response_headers / response_body。用于调用 Web API、抓取网页、对接第三方服务；**重点支持同网段 LAN 明文**（http://192.168.x.x、http://10.x、*.local mDNS），访问路由器/NAS/智能家居/私有 API 等局域网设备——受控浏览器已放开局域网明文，无需因公网明文限制而犹豫；公网请求仍走 HTTPS。响应体 >15 万字符自动 gzip（response_body_gz），控制端解压还原。

⚠️ 调用约束（与上文一致）：所有 WebView 操作（goBack/reload/canGoBack/canGoForward/evaluateJavascript 等）必须由被控方
在主线程执行（mainHandler.post + CountDownLatch 同步等待），禁止在 ACI Binder 工作线程直接调用 WebView，
否则抛 "A WebView method was called on thread 'binder'"。

【HTTP 传输（http_request · 局域网/本地组网）】
受控浏览器新增 http_request 能力，让 AI 能经 ACI 让浏览器发起任意 HTTP 请求，重点是「本地组网（相同网络下）」：
• LAN 明文支持：Android 9+ 默认禁明文 HTTP（targetSdk≥28），但受控浏览器通过 networkSecurityConfig 把 base-config 的 cleartextTrafficPermitted 设为 true，并对 localhost/127.0.0.1/10.0.2.2/local 放开，因此可直接访问 http://192.168.x.x、http://10.x、*.local（mDNS）等局域网 HTTP 服务。
• 平台限制：Android NSC 只能按域名或整体 base-config 放开明文，无法按「私有网段（如 192.168.0.0/16）」写白名单——这是平台限制，非缺陷。
• 参数：url（必填）、method（默认 GET）、headers（JSON 字符串）、body（原样发送字符串）。
• 返回：status_code(int) / response_headers(JSON) / response_body(字符串，>15 万字符截断并附 response_body_gz gzip) / truncated(boolean)。
• 控制端：主程序 QuroAidlAciTools.renderHttpResult 自动解压 gzip，并把状态码/响应头/响应体喂给 LLM。
• 安全权衡：明文放开后，公网明文 HTTP（http:// 公网域名）也会一并放行；请仅在可信局域网内用 http_request 访问内网地址，不要经它请求公网明文站点；远程生产通信（HTTPS）不受影响。

十一、规划方向（尚未实现，供参考）
元宝「TermBrowser」方案其余尚未落地的增强方向（轻量多标签 tabnew/tabclose/tabs/tab 已在第三波以「单引擎轻量版」落地；真·并行隔离仍属架构级改造）：
• 隔离 Profile 沙盒 / 敏感操作人工接管浮窗 / 后台持续渲染 / 多实例并行隔离 / 速率限制熔断。
• console.log 实时捕获（hook console，供 AI 读取页面运行日志；当前已有 page 级事件 browser_events，尚未 hook console）。
• 自编译 Chromium / CDP 深度控制（拦截响应体、改写响应头、自定义协议）。
• 命令录制 / 重放（自动化操作序列）。
• 隔离 Profile 沙盒 / 敏感操作拦截 + 人工接管 / 后台持续渲染 / 多实例（架构级改造，暂未实装）。
• 速率限制与熔断（防止 AI 高频调用压垮页面）。

十二、控制台后台（ConsoleBackend）开发接入 ZorvAI
（本文档此前缺失的部分：你的 App 如何提供一个「后端驱动 UI」的控制台，让 ZorvAI 主程序 / 手动控制台无需为每个 App 重写 UI 就能驱动它。）

【概念】控制台后台是一个「业务状态机」：
• buildUiSnapshot() 生成一份 UI 描述 JSON（SDUI，组件化），前端（ZorvAI / 手动控制台）拿到后通用渲染；
  后端想改 UI 不用发版前端，只要改快照 JSON 即可。
• applyAction(action, payload) 处理前端回传的动作，真正驱动你的业务。
• 经两个 ACI 能力暴露给通用前端：console_ui（返回快照 JSON）/ console_action（处理动作）。
  手动控制台与 AI 走的是同一个后端 → 一份真相、统一通道。

【接入方式 A：实现契约 + 暴露两个能力（推荐）】
1) 实现通用契约 AciConsoleContract（或直接写两个 ACI 能力）：
   - getSnapshot(): JSONObject          // 返回 SDUI 快照 JSON（必须在非 UI 线程调用）
   - sendAction(action: String, payload: Map<String,String>): JSONObject
2) 在 onCreateCapabilities 注册：
   - console_ui：result "snapshot"（String，快照 JSON 字符串）
   - console_action：param "action"（String）+ "payload"（String，JSON 字符串）；result "ok"（boolean）+ "action"（String）
3) 在 onCall 分发：
   - console_ui  → 返回 buildUiSnapshot().toString()
   - console_action → 解析 payload JSON，调用 applyAction，返回 {ok, action, message}

【快照 JSON Schema】
{
  "title": "我的控制台",
  "subtitle": "后端驱动渲染 · 免发版",
  "updatedAt": 1700000000000,
  "components": [ ... ]
}
组件类型（与 ZorvAI 渲染器一一对应，任意通用前端都能渲染）：
• heading{text}            标题
• text{text}               说明文字
• card{title, body}        卡片
• button{action, label}    按钮（点击 → 回传 action）
• divider{}                分隔线
• spacer{}                 间距
• input{key, label, placeholder, value, action}   输入框（提交 → 回传 action + 输入值）
• listitem{text}           列表项

【动作契约】
• 按钮：回传 payload 为空 Map，后端按 action 字符串区分（如 "nav_back" / "read" / "screenshot"）。
• 输入框：提交时回传 { <input.key>: <输入文本> }。⚠️ 兼容注意：ZorvAI 主程序 Compose 控制台当前额外回传 {"value":..., "key":...}，
  建议后端取值用 payload["value"] ?: payload[inputKey] 兜底，避免取不到。
• applyAction 返回 {ok:true, action:"<原action>", message:"<可读结果>"}，前端据此刷新快照 / 显示结果。

【最小 Kotlin 示例】
class MyConsoleBackend : AciConsoleContract {
    override fun getSnapshot(): JSONObject {
        val comps = JSONArray()
        comps.put(JSONObject().put("type","heading").put("text","我的 App 控制台"))
        comps.put(JSONObject().put("type","button").put("action","do_thing").put("label","执行操作"))
        comps.put(JSONObject().put("type","input").put("key","name").put("label","名字")
            .put("placeholder","输入").put("value","").put("action","greet"))
        return JSONObject().put("title","我的控制台").put("components",comps)
    }
    override fun sendAction(action: String, payload: Map<String,String>): JSONObject {
        val msg = when(action) {
            "do_thing" -> "已执行"
            "greet" -> "你好, " + (payload["value"] ?: payload["name"] ?: "?")
            else -> "未知 action"
        }
        return JSONObject().put("ok",true).put("action",action).put("message",msg)
    }
}
// 在 MyAciService 中：console_ui → MyConsoleBackend.getSnapshot().toString()；
// console_action → 解析 payload 后 MyConsoleBackend.sendAction(...)。

【复用 consolekit（可选：给受控 App 自带一个手动控制台，无需自己写 UI）】
你的受控 App 想自带一个「手动控制台」面板，直接用通用组件即可，不用写业务 UI：
• AciConsoleContract：通用契约（前端只认它，不认你的业务）；
• LocalConsoleEndpoint(backend)：同进程直接委派（手动控制台与 AI 共用同一后端）；
• RemoteConsoleEndpoint(ctx, targetPackage)：跨进程 bind 第 2 / 3 … 个受控 App 的 console_ui / console_action，
  换 targetPackage 即复用同一套控制台 UI，零改动；
• AciConsoleRenderer：纯 View 的 SDUI 渲染器（无 Compose 依赖），把快照渲染成原生控件；
• ManualConsolePanel：单线程 worker 编排（避开 WebView 主线程死锁），接 AciConsoleContract 到 UI。
开发第 2、第 3 个受控软件时，控制台 UI 完全不用重写 —— 只要它们暴露 console_ui / console_action。

【ZorvAI 如何驱动你】
ZorvAI「设置 → ACI 管理中心」列出已发现的受控 App；点「打开控制台」即经 Binder 拉取你的 console_ui 快照，
用 AciConsoleScreen（Compose）渲染；按钮 / 输入回传 console_action。你无需为 ZorvAI 写任何前端代码。
"""

/**
 * ACI 管理中心：作为 ACI 控制方（AI 中枢），浏览已发现的第三方 ACI App、
 * 查看绑定状态与暴露的能力清单，并支持手动注册 / 搜索 / 启动 / 重绑。
 *
 * 结构设计（对齐 App 既有「纸感」设计系统）：
 * - 01 添加 ACI 应用：包名/名称统一入口，支持「搜索」（模糊匹配本机应用）与「按包名注册并启动」（关联启动）；
 *   搜索结果逐一提供「启动 / 注册并启动」。即把「手动注册」与「按名称搜索」合体为单一流程。
 * - 02 已发现的 ACI 应用：列出已发现 App 的绑定态 + 能力清单，每张卡片可「重绑」与「启动」（手动启动）。
 * - 03 开发者文档：被控方接入手册（可折叠）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuroAidlAciCenterScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val cs = MaterialTheme.colorScheme
    val mgr = remember { QuroAidlAciManager.getInstance() }
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf(mgr.getAppStatuses()) }
    var pkgInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<QuroAidlAciManager.InstalledApp>>(emptyList()) }

    // 受控端「控制台」SDUI 渲染：复用本地 ACI 控制台渲染器（AciConsoleScreen）渲染 console_ui 快照，不新增业务逻辑
    var consolePkg by remember { mutableStateOf<String?>(null) }
    var consoleScreen by remember { mutableStateOf<AidlAciScreen?>(null) }
    var consoleLoading by remember { mutableStateOf(false) }
    var consoleError by remember { mutableStateOf<String?>(null) }

    fun openConsole(pkg: String) {
        consolePkg = pkg
        consoleLoading = true
        consoleError = null
        consoleScreen = null
        scope.launch {
            val resp = withContext(Dispatchers.IO) { mgr.call(pkg, "console_ui", android.os.Bundle()) }
            if (resp.isSuccess) {
                val snap = resp.result?.getString("snapshot") ?: ""
                consoleScreen = runCatching { AciConsoleModel.parse(JSONObject(snap)) }.getOrElse {
                    consoleError = "控制台 JSON 解析失败：${it.message}"
                    null
                }
            } else {
                consoleError = "打开控制台失败（错误码=${resp.errorCode}）：${resp.errorMessage}"
            }
            consoleLoading = false
        }
    }

    fun consoleAction(action: String, payload: Map<String, String>) {
        val pkg = consolePkg ?: return
        scope.launch {
            val b = android.os.Bundle().apply {
                putString("action", action)
                putString("payload", JSONObject(payload).toString())
            }
            withContext(Dispatchers.IO) { mgr.call(pkg, "console_action", b) }
            val r2 = withContext(Dispatchers.IO) { mgr.call(pkg, "console_ui", android.os.Bundle()) }
            if (r2.isSuccess) {
                val snap = r2.result?.getString("snapshot") ?: ""
                consoleScreen = runCatching { AciConsoleModel.parse(JSONObject(snap)) }.getOrNull()
            }
        }
    }

    fun reload() { statuses = mgr.getAppStatuses() }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ACT 关联启动") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = {
                        busy = true
                        mgr.refresh()
                        scope.launch {
                            delay(800)
                            reload()
                            busy = false
                            Toast.makeText(ctx, "已刷新", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, "刷新")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "ACT 关联启动（底层协议 ACI，Agent Capability Interface）是本地无 Root 的 App 间 AIDL 调用框架，可让 AI 控制支持协议的第三方 App。" +
                    "下面可手动添加应用、查看已发现应用的能力，并手动启动或重绑。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant
            )
            HorizontalDivider()

            // ── 01 添加 ACI 应用（手动注册 + 按名称搜索 合体）──────────────────────
            ChapterLabel("01", "添加 ACI 应用")
            SetGroup {
                Column {
                    UnderlineField(
                        label = "包名或应用名",
                        value = pkgInput,
                        onValueChange = { pkgInput = it },
                        placeholder = "如 com.example.chat 或 微信",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryButton(
                            text = "搜索",
                            onClick = {
                                searched = true
                                val kw = pkgInput.trim()
                                // 后台线程执行（getInstalledApplications + loadLabel 在主线程会 ANR）
                                scope.launch {
                                    searchResults = withContext(Dispatchers.IO) {
                                        mgr.searchInstalledApps(kw)
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Line, RoundedCornerShape(12.dp))
                                .clickable {
                                    val pkg = pkgInput.trim()
                                    if (pkg.isEmpty()) {
                                        Toast.makeText(ctx, "请输入包名", Toast.LENGTH_SHORT).show()
                                        return@clickable
                                    }
                                    val ok = mgr.registerPackage(pkg)
                                    mgr.launchApp(pkg)
                                    scope.launch { delay(800); reload() }
                                    Toast.makeText(
                                        ctx,
                                        if (ok) "已注册并启动：$pkg" else "未找到 $pkg 的 ACI 服务",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (ok) pkgInput = ""
                                }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("按包名注册并启动", fontSize = 15.sp, color = cs.onSurface, fontWeight = FontWeight.SemiBold)
                        }
                    }

                    if (searchResults.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        searchResults.forEachIndexed { idx, app ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(app.appName, fontWeight = FontWeight.Medium)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                TextButton(onClick = {
                                    val ok = mgr.launchApp(app.packageName)
                                    Toast.makeText(
                                        ctx,
                                        if (ok) "已启动：${app.appName}" else "启动失败：${app.packageName}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }) { Text("启动") }
                                TextButton(onClick = {
                                    mgr.registerPackage(app.packageName)
                                    val ok = mgr.launchApp(app.packageName)
                                    scope.launch { delay(800); reload() }
                                    Toast.makeText(
                                        ctx,
                                        if (ok) "已注册并启动：${app.appName}" else "启动失败：${app.packageName}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }) { Text("注册并启动") }
                            }
                            if (idx < searchResults.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        }
                    } else if (searched) {
                        Spacer(Modifier.height(4.dp))
                        InfoBox("未找到匹配的应用。可确认名称/包名是否正确，或直接用「按包名注册并启动」。", tone = Muted)
                    }
                }
            }

            // ── 02 已发现的 ACI 应用（含手动启动）────────────────────────────────
            ChapterLabel("02", "已发现的 ACI 应用")
            if (statuses.isEmpty()) {
                InfoBox(
                    "未发现任何 ACI App。安装支持 ACI 协议的第三方 App 后点右上「刷新」；" +
                        "或在上方「添加 ACI 应用」输入包名手动注册。\n\n" +
                        "ACI 是本地无 Root 的 App 间 AIDL 框架，列表为空时【禁止】用 dumpsys / Shizuku / ROOT 排查。"
                )
            } else {
                statuses.forEach { s ->
                    AciAppCard(
                        s,
                        onOpenConsole = { openConsole(s.packageName) },
                        onRebind = {
                            mgr.rebind(s.packageName)
                            scope.launch { delay(600); reload() }
                        },
                        onLaunch = {
                            val ok = mgr.launchApp(s.packageName)
                            Toast.makeText(
                                ctx,
                                if (ok) "已启动：${s.appName}" else "启动失败：${s.packageName}",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                    )
                }
            }

            // ── 03 开发者文档（正确开发姿势 + 全链路踩坑）────────────────────────
            ChapterLabel("03", "开发者文档")
            var showDevDoc by remember { mutableStateOf(false) }
            SetGroup {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { showDevDoc = !showDevDoc }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.MenuBook, null, Modifier.size(20.dp), tint = cs.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text("ACI 被控方接入手册", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Icon(
                            if (showDevDoc) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            null, Modifier.size(16.dp), tint = Muted
                        )
                    }
                    if (showDevDoc) {
                        Box(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                                .heightIn(max = 360.dp).verticalScroll(rememberScrollState())
                        ) {
                            Text(ACI_DEV_DOC, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                val r = QuroDownloadUtil.saveTextToDownloads(ctx, "aci_core_stub.kt", "text/plain", ACI_STUB_SOURCE)
                                Toast.makeText(ctx, if (r.startsWith("OK:")) "已保存依赖模板到 Download/Quro/aci_core_stub.kt" else r, Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("保存依赖模板") }
                        Button(
                            onClick = {
                                val r = QuroDownloadUtil.saveTextToDownloads(ctx, "ACI_被控方接入手册.md", "text/markdown", ACI_DEV_DOC)
                                Toast.makeText(ctx, if (r.startsWith("OK:")) "已保存开发者文档到 Download/Quro/" else r, Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("下载开发者文档") }
                    }
                }
            }
        }
    }
    // 受控端「控制台」SDUI 弹层：复用本地 ACI 控制台渲染器（AciConsoleScreen）渲染 console_ui 快照
    if (consolePkg != null) {
        Dialog(onDismissRequest = { consolePkg = null; consoleScreen = null; consoleError = null }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.92f).wrapContentHeight()
            ) {
                Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            consoleScreen?.title ?: "控制台",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { consolePkg = null; consoleScreen = null; consoleError = null }) {
                            Icon(Icons.Filled.Close, "关闭")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when {
                        consoleLoading -> CircularProgressIndicator()
                        consoleError != null -> Text(consoleError ?: "", color = MaterialTheme.colorScheme.error)
                        else -> AciConsoleScreen(consoleScreen, onAction = { a, p -> consoleAction(a, p) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AciAppCard(
    s: QuroAidlAciManager.AciAppStatus,
    onRebind: () -> Unit,
    onLaunch: () -> Unit,
    onOpenConsole: (String) -> Unit,
) {
    val boundColor = Color(0xFF34C759)
    val unboundColor = Color(0xFFFF3B30)
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PaperCard),
        border = if (s.bound) null else BorderStroke(1.dp, Line),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.appName, fontWeight = FontWeight.Bold)
                    Text(s.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                val (label, color) = if (s.bound) "已绑定" to boundColor else "未绑定" to unboundColor
                Box(
                    Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) { Text(label, color = color, style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.height(10.dp))
            Text("能力（${s.capabilities.size}）", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(2.dp))
            if (s.capabilities.isEmpty()) {
                Text(
                    "（无能力：可能尚未绑定，或该 App 未声明能力）",
                    color = Color.Gray, style = MaterialTheme.typography.bodySmall
                )
            } else {
                s.capabilities.forEach { c ->
                    val danger = if (c.isRequireUserConfirm) "  ⚠️需确认" else ""
                    Text("• ${c.id}：${c.description}$danger", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRebind) { Text("重绑") }
                TextButton(onClick = onLaunch) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("启动")
                }
                if (s.capabilities.any { it.id == "console_ui" }) {
                    TextButton(onClick = { onOpenConsole(s.packageName) }) {
                        Icon(Icons.Filled.Dashboard, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("打开控制台")
                    }
                }
                if (s.lastSeen > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "最近活动 ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(s.lastSeen))}",
                        color = Color.Gray, style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
