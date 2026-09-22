package com.ai.assistance.quro.core.tools

/**
 * 小程序手册 —— 小程序工作室（`miniapp`）的**唯一真相源**。
 *
 * ── 为什么要有这个文件 ────────────────────────────────────────────
 * 「小程序」这个词在本项目里曾经对应两个完全不同的东西，模型极易搞混，于是「哪个都调一遍」
 * （结果：两块画布、或产物落进错误的宿主）。而且自研引擎的能力边界和微信小程序差得很远，
 * 用微信小程序的常识去写，会得到「一片空白 / 点了没反应 / 文字被裁」这类黑盒故障。
 *
 * 手册直接由**引擎源码**反推而成，不是凭微信文档抄的：
 *   · 页面运行时与 native.* 桥：miniapp-sdk（LogicRuntime / MiniAppView）
 *   · 落地校验与打回理由：MiniAppStudioTool（create/run 的真实打回条件）
 *
 * ── 谁在用 ──────────────────────────────────────────────────────
 * · ZorvAI 对话框：MiniAppStudioTool 的 action="manual" 按需返回
 *   （工具 description 里内联的是 [STUDIO_BRIEF] 精简版，模型每轮都会读到，必须短）
 *
 * ── 历史沿革（改这段前先读） ──────────────────────────────────────
 * 旧版还有第二套「小程序」——工具 `genui_native_ui`（旧名 `create_miniapp`）：
 * WXML/WXSS/JS 微信语法 + 28 个 wx.* API + 自研 Canvas 引擎，只存在于已下线的
 * 「生成式 UI 对话画布」里。该工具与那套画布已在 v1.0.96 一并删除，
 * **本手册不再提供它的内容**（[section] 对老 topic 只会返回 [RETIRED] 说明）。
 * 现在要「原生界面」有两条路：`miniapp`（HTML + native.* 桥）或
 * `genui_agent_open`（GenUI JSON DSL → 原生 Compose 组件）。
 *
 * 两条铁律（改动前先读）：
 * 1. 手册里每一条都必须能在源码里找到依据，**不许写"微信支持所以应该也行"**；
 * 2. 引擎改了（新增 API / 打回条件变了）必须同步改本文件，否则手册会变成误导。
 */
object MiniAppManual {

    /** 老 topic（旧 GenUI 原生 UI 引擎）的说明——只讲事实，不再给已删除工具的用法。 */
    val RETIRED: String = """
【这册手册对应的能力已下线】

`genui_native_ui`（旧名 `create_miniapp`，跑 WXML/WXSS/JS + 28 个 wx.* API 的自研原生引擎）
属于已删除的「生成式 UI 对话画布」，v1.0.96 起不再存在：
- 没有 `genui_native_ui` / `create_miniapp` / `genui_manual` 这三个工具了，调了只会报「未知工具」；
- `topic="native" / "traps" / "errors" / "compare"` 没有内容可给。

现在要「界面」时，按需求二选一（不要两个一起用）：
· 网页形态的小程序（HTML + Page() 运行时 + native.* 原生桥：存数据 / 跑 SQL / 加密 / 通知 / 分享 / 定位 / 拉起 App）
  → `miniapp`（本工具），取本手册 `miniapp(action="manual")`。
· 原生控件形态的可交互界面（GenUI JSON DSL → 原生 Compose 组件）
  → `genui_agent_open`（内置 GenUI Agent 独立应用）。
· 只要一个网页成品、不需要系统能力 → 直接写 ```html 围栏。
""".trimIndent()

    // ════════════════════════════════════════════════════════════════
    //  miniapp（小程序工作室）完整手册
    // ════════════════════════════════════════════════════════════════

    val STUDIO: String = """
【小程序工作室手册 · miniapp —— HTML + Page() 运行时 + native.* 原生桥】

── 1. 它是什么
由 WebView 跑**真实 HTML 页面**的小程序工程，通过 native.* 桥调用本机的
存储 / SQLite / 加密 / 通知 / 分享 / 定位 / 关联启动等真实能力。
工程落在手机私有目录 filesDir/studio/miniapp/<name>/，与工具中心的「小程序工作室」面板**同一份文件**
——AI 写完，面板里立刻能看到、能跑。

── 2. 工程结构
miniapp/
  <name>/
    app.json                     全局配置（appId / version / name / pages 路由表 / window 样式）
    pages/<page>/<page>.html      页面（完整 HTML，用 Page() 运行时组织状态）
    components/<name>/<name>.js   可复用组件（可选）

miniapp(action="create", name="todo", files=[
  {"path":"app.json","content":"{\"pages\":[\"pages/index/index\"],\"window\":{\"navigationBarTitleText\":\"待办\"}}"},
  {"path":"pages/index/index.html","content":"<!DOCTYPE html>…</html>"}
])
miniapp(action="run", name="todo", entry="pages/index/index.html")   ← 必须在 create 之后再调一次，产物才内嵌

── 3. 两步交付（这是它的交付方式，必须成对完成）
1) create 落地工程（可一次写多个文件）
2) run 取回**自包含 HTML**（自动内联同目录 .js/.css），这份 HTML 会内嵌进对话流渲染
   ❌ 只 create 不 run = 用户什么都看不到（界面根本没交付）
   ❌ run 之后自己再写一份 HTML = 两块画布
所以：**create + run 必须成对完成，且本轮不再走其他画型工具。**

── 4. 页面与运行时（Page()）
页面就是完整 HTML，在页面脚本里用 Page() 组织状态与事件：
  <script>
    var state = { list: [], text: '' };
    function render() { /* 把 state 写进 DOM */ document.getElementById('cnt').textContent = state.list.length; }
    var page = Page({
      data: state,
      onLoad: function () { render(); },          // 页面加载
      onShow: function () { },                    // 每次显示
      onHide: function () { }, onUnload: function () { },
      setState: function (patch) { /* 合并进 state 后 render() */ }   // 自定义方法
    });
  </script>
要点：
· 这是**网页运行时**，DOM / addEventListener / CSS 全部可用（写界面比原生引擎自由得多）。
· 事件绑定用标准网页写法：onclick / oninput / addEventListener 都行，
  或 <button data-id="3" onclick="del(this.dataset.id)">。
· 持久化优先用 native.storage / native.db（见第 5 节），也可以配合 localStorage 但换设备会丢。

── 5. native.* 桥（原生能力全表 —— 这是用工作室的唯一理由）
模块      函数                                                   用途
storage   setItem / getItem / removeItem / clear                 键值持久化（跨启动保留）
ui        toast / setNavigationBarTitle                        轻提示、改标题
device    getSystemInfo / vibrate                              设备信息、震动
network   request                                               HTTP 请求
router    navigateTo / navigateBack                            页内跳转与返回
kotlin    getAppInfo / copyText / getClipboard / shareText      取 App 信息、剪贴板、系统分享
          / openUrl / openApp / notify / speak                  开链接、拉别的 App、发通知、朗读
aci       launchApp / launchComponent / canLaunch              关联启动第三方 App（含能否启动预检）
crypto    md5 / sha1 / sha256 / hmacSha256                     摘要与签名
db        execSql / query / insert / update / delete           SQLite 结构化存储
location  getLocation                                          获取定位

（上表"模块"一列都要加 native. 前缀调用，例如 native.storage.setItem、
  native.kotlin.copyText、native.aci.launchApp、native.crypto.sha256、native.db.execSql。）

调用注意：
· 桥是异步的，用回调 / Promise 拿结果；**先判可用性再调**（例如 aci.canLaunch 再 launchApp）。
· 需要授权的模块（通知 / 定位 / 关联启动）由宿主权限门禁拦，失败会回调错误——
  界面必须处理失败分支（给出提示，不要静默）。
· 存结构体要先 JSON.stringify 再 setItem，取出 JSON.parse。
· SQLite 用 db.execSql 建表一次，之后 insert/query/update/delete（表名与字段自己定，别用 SQL 关键字）。

── 6. 什么该选工作室（判断标准）
✅ 要跨启动保存数据、要按条件查历史记录、要算摘要/签名、要发通知、要系统分享、
   要定位、要拉起另一个 App、要跑 SQL —— 选它。
❌ 只是好看的界面 + 本地临时状态（待办、计算器、展示页）—— 直接写 ```html 围栏更快更稳。

── 7. 错误清单
① create 了但对话框里什么都没有
   成因：忘了 run。
   怎么改：create 完必须再调一次 miniapp(action="run", name=…)。

② run 报找不到工程 / 页面
   成因：name 拼错（大小写敏感），或 entry 路径与 app.json 的 pages 对不上。
   怎么改：先 miniapp(action="list") 看真实工程名与文件，再 run。

③ 页面白屏
   成因：HTML 不完整（没有 <!DOCTYPE html> / <body>）；或 JS 报错中断了渲染。
   怎么改：给完整文档；把脚本放在 body 末尾并在关键步骤包 try/catch，
           出错时至少渲染一句可读提示，而不是整页空白。

④ native 调用没反应
   成因：模块/函数名写错（如 native.kotlin.copy 而非 copyText）、或缺权限被门禁拦下。
   怎么改：照第 5 节表逐字核对函数名；把失败回调的提示渲染到界面上。

⑤ 数据重启就丢
   成因：只用了内存变量 / localStorage 未持久化。
   怎么改：改用 native.storage.setItem 或 native.db 落库。

⑥ 两块画布
   成因：本轮 run 之后又写了 HTML 围栏，或同时调了别的画型能力。
   怎么改：本轮只交付一件：run 的产物就是最终交付，不要再补第二份界面。

── 8. 文件操作（同一工具）
  miniapp(action="write", name="todo", path="pages/index/index.html", content="…")   改单个文件
  miniapp(action="read",  name="todo", path="app.json")                             读文件
  miniapp(action="list")                                                            列出全部工程
  miniapp(action="delete",name="todo")          删整个工程
  miniapp(action="delete",name="todo", path="pages/about/about.html")   删单个文件
  miniapp(action="manual")                      取回本手册（函数级细节 + 错误清单）
""".trimIndent()

    /** 精简版：塞进工具 description（模型每轮都会读到，必须短）。 */
    val STUDIO_BRIEF: String = """
【小程序工作室 = 本工具 miniapp】
它是 **HTML 页面 + Page() 运行时 + native.* 原生桥**（能存数据 / 跑 SQL / 加密 / 通知 / 分享 / 定位 / 拉起 App）。
交付必须两步：create 落地 → run 取回自包含 HTML 内嵌对话流。**只 create 不 run = 用户什么都看不到。**

【最高频的 5 个错】
1. create 完忘了 run → 对话框里什么都没有。
2. name 拼错 / entry 与 app.json 的 pages 对不上 → run 找不到页面（先 list 看真实名字）。
3. 页面白屏 → HTML 不完整，或 JS 报错中断渲染（脚本放 body 末尾 + try/catch，出错也渲染提示）。
4. native 调用没反应 → 函数名写错（是 native.kotlin.copyText 不是 copy 之类）或缺权限被拦。
5. 数据重启就丢 → 没用 native.storage / native.db 持久化。

【完整手册】→ `miniapp(action="manual")`
内容：工程结构 / Page() 运行时 / **native.* 全部函数逐个说明** / 错误清单。
""".trimIndent()

    // ────────────────────────────────────────────────────────────────
    //  按需取用（ZorvAI 的 miniapp(action="manual") 走这里）
    // ────────────────────────────────────────────────────────────────

    /**
     * 取手册正文。
     *
     * ⚠️ 返回刻意压在 **8000 字符以内**：AgentLoop 把工具结果回灌给模型时是
     * `content.take(8000)`（见 AgentLoop 里 "[工具结果]" 那一行），一次取太多会被静默截断，
     * 模型只拿到半本手册反而更容易写错。
     *
     * 老 topic（native / traps / errors / compare）指向的是已删除的旧 GenUI 原生 UI 引擎，
     * 这里统一回 [RETIRED] 说明，避免模型去调已经不存在的工具。
     */
    fun section(topic: String): String {
        val t = topic.trim().lowercase()
        return when (t) {
            "traps", "陷阱", "native", "原生", "原生ui", "errors", "错误", "错误清单",
            "compare", "对比", "对照" -> RETIRED
            // studio / 空 / all / 未知 topic → 工作室手册（也是当前唯一的一册）
            else -> STUDIO
        }
    }
}
