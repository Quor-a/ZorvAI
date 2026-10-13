package com.ai.assistance.quro.ui.chat

import android.content.Context
import com.ai.assistance.quro.util.QuroDiag
import org.json.JSONObject

/**
 * zxwidget 运行时（动态交互界面）的宿主侧装配。
 *
 * 术语先说清（这三个词在本仓经常被混着用）：
 *   · **zxwidget** = 动态交互界面。一套独立的组件编译器（477 个 zx 组件），
 *     既不是 ZorvAI 也不是 Web UI，只是**被这两个对话框一起支持**。
 *     运行时在 `assets/zx/zxwidget.js`，单卡片页壳在 `zx_card_runtime.html`。
 *   · **ZorvAI 原生对话框** = 聊天主界面。zxwidget **直接长在它的消息流里**。
 *   · **Web UI 对话框** = ZorvAI 对话框的 Web 复制品。同样长在消息流里。
 *   两边同一个来源（zxwidget），区别只在「谁把它画出来」。
 *
 * 🔴 交付形态（用户 2026-10-13 拍板）：**不是卡片、不是弹窗，直接渲染在对话框**。
 *   历史上一度是「整框弹窗装一张卡」——那正是被否掉的形态，已下线；
 *   本文件旧名 `ZxWebCardDialog.kt`，现在只剩装配，没有 Dialog，也不再叫卡片。
 *
 * 触发路径：模型调 `zx_web_card` 工具 → 工具调用落在 assistant 消息上
 *   → 两个对话框各自从**消息上的工具调用**读出 component/dsl，就地内联渲染
 *   （Web UI 走 web-chat 的 toolCall 渲染，原生走 [ZxWidgetInline]）。
 *   用户在界面上操作 → 交互作为**该工具的结果**回传，本轮对话继续。
 *
 * 为什么走 WebView 而不是原生 Compose：
 *   zx 组件族有 477 个，原生逐个实现要写几十万行；而它们的 DSL 是同构 JSON。
 *   Web 侧一个运行时 + 一层形状推断就能全覆盖，改样式也不用发版。
 *   宿主只保留三件事：把 DSL 送进去、把交互接出来、决定交互之后干什么。
 *
 * 注意：本页（`zx_card_runtime.html`）走 `loadDataWithBaseURL` + 构建期内联，
 *   `file://` 下加载不到外链脚本，所以它内联了一份编译器拷贝。
 *   那份拷贝与新模块 `zx/zxwidget.js` **目前并不完全同源**——见
 *   `scripts/zx/zxwidget_parity_check.js` 的 KNOWN-DRIFT 清单。
 */
object ZxWebCard {

    const val ASSET_NAME = "zx_card_runtime.html"

    /** zx 组件字段契约（从 zxwidget 的 ZxDsl* 提取），Web 运行时按它编译布局。 */
    const val CONTRACTS_NAME = "zx_contracts.json"

    /**
     * 内联形态的样式修正：zxwidget **直接长在对话框的消息流里**时，
     * 页内那套「标题栏 + DSL 按钮 + 页脚」是多余的（它是整框弹窗时代的产物，
     * 标题与来源由气泡本身交代），而且会让内联块看起来像嵌了一张卡片。
     *
     * 刻意**不隐藏 overflow**：内联块高度有上限（见 `ZxWidgetInline`），
     * 内容超高时要能在块内滚动，否则尾部内容永远够不着。
     */
    private val INLINE_CSS = """
        <style>
          .hd,.foot{display:none !important}
          html,body{height:auto !important;min-height:0 !important;background:transparent !important}
          #app{min-height:0 !important}
        </style>
    """.trimIndent()

    /**
     * 内联形态的高度回报。
     *
     * 内联 WebView 在 LazyColumn 里没有固定高度，必须让页面把真实内容高度报回来，
     * 宿主再据此设高度——否则要么被压成一条缝，要么内部滚动（与「直接长在对话框里」
     * 的观感冲突）。交互会改变高度（展开/折叠、翻页、切 tab），所以不只报一次：
     * 加载后补报几次 + 监听尺寸变化与点击。**只在数值变化时上报**，
     * 否则「宿主改高度 → 页面尺寸变 → 再上报」会自激成死循环。
     */
    private val HEIGHT_REPORTER = """
        <script>
        (function(){
          var last = 0;
          function rep(){
            try{
              var h = Math.ceil((document.body && document.body.scrollHeight) || 0);
              if (h > 0 && h !== last && window.ZorvAIBridge && window.ZorvAIBridge.height) {
                last = h;
                window.ZorvAIBridge.height(h);
              }
            }catch(e){}
          }
          window.__zxReportHeight = rep;
          setTimeout(rep, 60); setTimeout(rep, 320); setTimeout(rep, 900);
          window.addEventListener("load", rep);
          document.addEventListener("click", function(){ setTimeout(rep, 90); }, true);
          document.addEventListener("transitionend", function(){ setTimeout(rep, 80); }, true);
          try{ if(window.ResizeObserver && document.body){ new ResizeObserver(rep).observe(document.body); } }catch(e){}
        })();
        </script>
    """.trimIndent()

    /**
     * 把运行时 HTML 与初始 payload 合成一份可直接加载的文档。
     *
     * payload 随 HTML 一起下发（而不是等 WebView 起来再 `evaluateJavascript`），
     * 少一次异步握手——卡片打开即渲染，不会闪一下空白。
     *
     * 组件契约走 `<script type="application/json">` 注入：JSON 原文只做
     * `</script` 一类序列结束符转义，不必做整段字符串字面量转义
     * （190 KB 的契约用字符串字面量注进来，转义漏一个就整段白屏）。
     *
     * @param inline zxwidget **直接渲染在对话框消息流里**时传 true：
     *   去掉页内标题栏/页脚（整框弹窗时代的产物），并把滚动交回宿主列表。
     *   同时注入高度回报脚本，让宿主知道该给这块内容留多高。
     *   单卡片弹窗（遗留形态）传 false。
     */
    fun buildHtml(
        assetHtml: String,
        contractsJson: String,
        component: String,
        title: String,
        dsl: String,
        dark: Boolean,
        inline: Boolean = false,
    ): String {
        val payload = JSONObject().apply {
            put("component", component)
            put("title", title)
            put("dark", dark)
            put("dsl", runCatching { JSONObject(dsl) }.getOrElse { JSONObject().put("_raw", dsl) })
        }.toString()
        // 只做 JSON 字符串字面量级别的转义：把 payload 安全嵌进一段 <script> 里。
        val safe = payload
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("<", "\\u003c")
            .replace(">", "\\u003e")
            .replace("&", "\\u0026")
        val inject = """
            <script>
            (function(){
              try{
                var p = JSON.parse("$safe");
                if (window.ZorvAI && window.ZorvAI.render) window.ZorvAI.render(p);
              }catch(e){
                if (window.ZorvAI && window.ZorvAI.render)
                  window.ZorvAI.render({component:"__error__",title:"卡片数据无法解析",
                                        dsl:{message:String(e)},dark:$dark});
              }
            })();
            </script>
        """.trimIndent()

        // 契约注入：只挡序列结束符，其余原文直出
        val contractsSafe = contractsJson
            .replace("</", "<\\/")
            .replace("<!--", "<\\!--")
        val withContracts = assetHtml.replace(
            "/*__ZX_CONTRACTS__*/",
            contractsSafe.ifBlank { "{}" },
        )

        val marker = "</body>"
        val at = withContracts.lastIndexOf(marker)
        val extra = if (inline) INLINE_CSS + "\n" + inject + "\n" + HEIGHT_REPORTER else inject
        return if (at >= 0) {
            withContracts.substring(0, at) + extra + "\n" + withContracts.substring(at)
        } else {
            withContracts + extra
        }
    }

    /**
     * Web 宿主桥（**只在 Web UI 的 iframe 里注入**，原生侧不注入）。
     *
     * 为什么必须有：页内的 [HEIGHT_REPORTER] 与整个运行时都只认
     * `window.ZorvAIBridge` 这一个出口（那是单卡片页壳的历史命名，改了页面就调不到）。
     * Web UI 侧没有 `addJavascriptInterface`，所以在这里**补一个同名实现**，
     * 把两个出口翻译成 `postMessage` 发给父页面。
     *
     * 🔴 这就是「一套运行时、两个宿主」的落点：页面、CSS、编译器、高度回报
     *    **逐字节相同**，差异只在这一个桥。若为 Web UI 另写一套渲染，
     *    两边必然漂移，而漂移的症状是「网页上看得到、手机上画不出」这类最难查的分叉。
     */
    private val WEB_BRIDGE = """
        <script>
        (function(){
          function post(msg){
            try{ if(window.parent && window.parent!==window) window.parent.postMessage(msg,"*"); }catch(e){}
          }
          window.ZorvAIBridge = {
            /* 与原生 @JavascriptInterface height(px) 同名同参 */
            height: function(px){ post({__zx:"h", px: px}); },
            /* 与原生 @JavascriptInterface interact(payload) 同名同参 */
            interact: function(payload){ post({__zx:"i", payload: String(payload==null?"":payload)}); }
          };
        })();
        </script>
    """.trimIndent()

    /**
     * Web 宿主的入参引导：**载荷不走 URL 查询串，走 hash**。
     *
     * 理由：dsl 是模型给的任意 JSON，长且含 `&`/`#`/中文；塞进 `?c=..&d=..`
     * 要么被截断、要么要服务端再解一次编码（多一处能错的地方）。
     * hash 不参与请求、不落服务端日志、没有长度限制，父页面 `base64(JSON)` 一次，
     * 子页面解回来即可。
     *
     * 🔴 `dsl` 必须在这里**归一成对象**，因为两侧给的形状不一样：
     *    原生 [buildHtml] 传的是 `JSONObject(dsl)`（对象），
     *    而 Web UI 的消息里 `ui[].dsl` 是**字符串**（投影直接搬工具参数）。
     *    不归一的后果不是报错，而是运行时读不到 `title`/`rows` → 静默走
     *    「暂无表格数据」那条兜底 —— 表现为「界面画出来了、但里面是空的」，
     *    正是用户截图里那一态，且日志里一个字都没有。
     *    这里的兜底语义与 buildHtml 的 `getOrElse { JSONObject().put("_raw", dsl) }`
     *    严格对齐：能解析就解析，不能解析就把原文塞进 `_raw` 交给运行时如实显示。
     */
    private val WEB_HASH_BOOTSTRAP = """
        <script>
        (function(){
          function fail(m){
            try{ if(window.ZorvAI&&window.ZorvAI.render)
              window.ZorvAI.render({component:"__error__",title:"界面数据无法解析",dsl:{message:String(m)}});
            }catch(e){}
          }
          try{
            var h = String(location.hash||"").replace(/^#/,"");
            if(!h) return;
            var json = decodeURIComponent(escape(atob(h)));
            var p = JSON.parse(json);
            p.dark = !!p.dark;
            // dsl 允许是「对象」或「JSON 字符串」两种形状，统一成对象。
            if(typeof p.dsl === "string"){
              try{ p.dsl = JSON.parse(p.dsl || "{}"); }
              catch(e2){ p.dsl = {_raw: String(p.dsl)}; }
            }
            if(!p.dsl || typeof p.dsl !== "object") p.dsl = {};
            if(window.ZorvAI && window.ZorvAI.render) window.ZorvAI.render(p);
            if(window.__zxReportHeight) setTimeout(window.__zxReportHeight, 60);
          }catch(e){ fail(e && e.message ? e.message : e); }
        })();
        </script>
    """.trimIndent()

    /**
     * Web UI 用的一整份页壳：**与原生同一个 `zx_card_runtime.html`**，
     * 只把「原生桥」换成 [WEB_BRIDGE]、把「随 HTML 下发的载荷」换成 [WEB_HASH_BOOTSTRAP]。
     *
     * 服务端把它缓存在内存里（契约 195 KB，每块界面各读一遍纯浪费），
     * 每个 zxwidget 块用 `<iframe src="/zx/shell#<base64>">` 承载。
     */
    fun buildWebShell(assetHtml: String, contractsJson: String): String {
        if (assetHtml.isBlank()) return ""
        val contractsSafe = contractsJson
            .replace("</", "<\\/")
            .replace("<!--", "<\\!--")
        val withContracts = assetHtml.replace(
            "/*__ZX_CONTRACTS__*/",
            contractsSafe.ifBlank { "{}" },
        )
        val extra = INLINE_CSS + "\n" + WEB_BRIDGE + "\n" + WEB_HASH_BOOTSTRAP + "\n" + HEIGHT_REPORTER
        val marker = "</body>"
        val at = withContracts.lastIndexOf(marker)
        return if (at >= 0) {
            withContracts.substring(0, at) + extra + "\n" + withContracts.substring(at)
        } else {
            withContracts + extra
        }
    }

    /**
     * 把一次 Web 交互整理成给模型看的一行结构化描述。
     *
     * 关键：**真实交互内容必须进正文**。只写「用户点了一下」模型无法接着推理，
     * 必须带上组件名、动作、以及被点的对象是什么。
     */
    fun summarize(component: String, json: JSONObject): String {
        val action = json.optString("action").ifBlank { "tap" }
        val summary = json.optString("summary").ifBlank { "$component · $action" }
        val rest = JSONObject()
        json.keys().forEach { k ->
            if (k !in setOf("action", "summary", "component")) rest.put(k, json.opt(k))
        }
        return buildString {
            append("【zxwidget 交互】组件 ")
            append(component)
            append("，动作 ")
            append(action)
            append("。")
            append(summary)
            if (rest.length() > 0) {
                append("\n交互明细：")
                append(rest.toString())
            }
        }
    }

    /**
     * 读取 zxwidget 运行时资源（HTML 骨架 + 组件契约）。
     *
     * **进程级缓存**：契约 195 KB，而消息流里可能一次出现多块 zxwidget。
     * 每个内联块各自 `assets.open` 读一遍是纯浪费（而且是在组合期间做 IO）。
     * 资源随 APK 版本固定，进程内不会变，所以缓存安全。
     */
    @Volatile private var cachedHtml: String? = null
    @Volatile private var cachedContracts: String? = null

    fun assets(context: Context): Pair<String, String> {
        var html = cachedHtml
        if (html == null) {
            html = runCatching {
                context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrElse {
                QuroDiag.log("ZxWebCard", "运行时资源缺失：$ASSET_NAME（${it.message}）")
                ""
            }
            cachedHtml = html
        }
        var contracts = cachedContracts
        if (contracts == null) {
            contracts = runCatching {
                context.assets.open(CONTRACTS_NAME).bufferedReader().use { it.readText() }
            }.getOrElse {
                QuroDiag.log("ZxWebCard", "组件契约缺失：$CONTRACTS_NAME（${it.message}）")
                ""
            }
            cachedContracts = contracts
        }
        return html to contracts
    }
}
