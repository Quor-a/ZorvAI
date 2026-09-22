package com.ai.assistance.quro.core.tools

/**
 * 小程序手册 —— 两个「小程序」工具的**唯一真相源**。
 *
 * ── 为什么要有这个文件 ────────────────────────────────────────────
 * 项目里有两个完全不同的东西都被叫「小程序」，模型极易搞混，于是「哪个都调一遍」
 * （结果：两块画布、或产物落进错误的宿主）。而且两边的引擎能力边界差得很远，
 * 用微信小程序的常识去写自研引擎，会得到「一片空白/点了没反应/文字被裁」这类黑盒故障。
 *
 * 手册直接由**引擎源码**反推而成，不是凭微信文档抄的：
 *   · 组件标签 → 渲染类型：miniapp-sdk/.../view/VDomBuilder.kt（第 62-68 行的 when(tag)）
 *   · 事件载荷与冒泡：miniapp-sdk/.../core/MiniAppView.kt（handleTap / fireInput / fireBlur）
 *   · 可绘制性（背景/边框/裁剪）：miniapp-sdk/.../render/CanvasPainter.kt（hasBox 分支）
 *   · wx.* 可用清单：miniapp-sdk/.../nativeapi/WxApi.kt（API_NAMES）
 *   · 落地校验与打回理由：genui/app/agent/tools/ZorvToolAdapter.kt（createMiniApp）
 *
 * ── 谁在用 ──────────────────────────────────────────────────────
 * · GenUI 生成式 UI 对话：app/.../genui/app/llm/Prompts.kt 第二/三章直接内联（形态 3 / 形态 4）
 * · ZorvAI 对话框：MiniAppStudioTool 的 action="manual" 按需返回，工具描述里放精简版
 *
 * 两条铁律（改动前先读）：
 * 1. 手册里每一条都必须能在源码里找到依据，**不许写"微信支持所以应该也行"**；
 * 2. 引擎改了（新增标签/API）必须同步改本文件，否则手册会变成误导。
 */
object MiniAppManual {

    // ════════════════════════════════════════════════════════════════
    //  〇、两个「小程序」到底哪个是哪个（先看这张表，别再调错）
    // ════════════════════════════════════════════════════════════════

    val COMPARE: String = """
【两个「小程序」工具的对照表 —— 先分清，再动手】

        ┌──────────────┬────────────────────────────────┬────────────────────────────────┐
        │              │ A. genui_native_ui             │ B. miniapp（小程序工作室）      │
        │              │（旧名 create_miniapp，别名同义）│                                │
        ├──────────────┼────────────────────────────────┼────────────────────────────────┤
        │ 技术栈        │ WXML + WXSS + JS（微信小程序语法）│ HTML + CSS + JS（网页语法）     │
        │ 渲染引擎      │ 自研原生引擎（Canvas 直接画）    │ WebView 跑真实网页              │
        │ 页面文件      │ pages/x/x.wxml/.wxss/.js       │ pages/x/x.html（完整 HTML）     │
        │ 能不能用 HTML │ ❌ 绝对不行（写了也是死文本）    │ ✅ 就是 HTML                    │
        │ 原生能力      │ 只有 28 个 wx.* API（见 A 手册） │ native.* 桥：storage/db/crypto/ │
        │              │                                │ aci/kotlin/location…（见 B 手册）│
        │ 落地位置      │ filesDir/miniapps/<app_id>/    │ filesDir/studio/miniapp/<name>/ │
        │ 交付方式      │ 建完自动内嵌对话流（可玩）       │ 建完还要 run 一次，run 的结果才内嵌│
        │ 谁在用        │ GenUI 生成式 UI 对话内         │ ZorvAI 对话框内 + GenUI 内      │
        │ 一句话定位    │ 「有状态、好看、自娱自乐」的小应用│ 「要碰真实系统能力」的小程序     │
        └──────────────┴────────────────────────────────┴────────────────────────────────┘

选择规则（30 秒版）：
· 只要交互和界面，不碰系统能力  → A（genui_native_ui）。更快、更稳、无 WebView 开销。
· 要存数据 / 跑 SQL / 加密 / 震动 / 通知 / 分享 / 定位 / 拉起别的 App → B（miniapp）。
· 用户说「小程序 / 原生界面 / 原生 UI」 → A。用户说「网页 / HTML」 → 直接写 HTML。
· 用户说「带原生能力的小程序」 → B。

**互斥铁律**：同一个交付里，A 和 B **只能用其中一个**。两个都用 = 两块画布（用户看到界面被画了两遍）。
交付成功后**不要再补一份 HTML** —— 工具产物本身已经内嵌进对话流了，再补一份就是盖上去的第二块画布。
""".trimIndent()

    // ════════════════════════════════════════════════════════════════
    //  A. genui_native_ui 完整手册
    // ════════════════════════════════════════════════════════════════

    /** 从引擎源码反推的、模型最容易踩的坑（单独拎出来，放在手册最前面）。 */
    val NATIVE_UI_TRAPS: String = """
【A. genui_native_ui 的 9 个致命陷阱 —— 这些不是"建议"，是引擎事实，违反必出黑盒故障】

1. 只有 scroll-view 能滚动。
   引擎只给 NodeType.SCROLL（scroll-view/swiper/movable-*）设 overflow=scroll，其他节点的样式里
   写 overflow:scroll 也没用。最外层不用 scroll-view 包住 → 超出卡片视口的内容会被**直接裁掉**，
   用户既看不到也滚不到（表现就是"文字被截断在画布里"）。
   ✅ 正确：第一行就是 <scroll-view scroll-y style="height:100%">，其余内容全在里面。

2. switch / checkbox / radio / slider 不是控件，是**纯文本**。
   引擎把这五个标签都当 BUTTON→按文本绘制。它们没有开关圆点、没有勾选、没有滑块轨道；
   自闭合写法（<switch/>）宽高为 0，**屏幕上啥都没有**，用户点也点不到东西。
   ✅ 正确：自己用 <view> 画开关——用 data 里的布尔值决定背景色，配 bindtap 切换，例如
      <view class="{{on ? 'sw on' : 'sw'}}" data-k="notify" bindtap="toggle"></view>
      .sw{width:88rpx;height:48rpx;border-radius:24rpx;background:#3a3a44}
      .sw.on{background:#d9a05b}

3. input 只有单行，且 bindinput 在"键盘点完成"时才触发一次。
   引擎的输入是弹出系统 Dialog 里的单行 EditText，setSingleLine(true)。
   · <textarea> 会被同样当单行处理 → **多行输入做不到**，别设计长文本输入框。
   · 不是每敲一个字就回调（没有"实时搜索联调"），bindinput 只在 IME 完成动作时触发。
   · 想拿到值：用 e.detail.value（见事件表）。
   ✅ 正确：搜索/筛选这类需求写成"输入完点搜索按钮再筛"，别做输入即筛。

4. 尺寸必须用 rpx，px 会被当成极小值。
   引擎按 rpx 比例换算（750rpx = 整屏宽）。写 20px 在手机上会小到看不清。
   ✅ 全部尺寸用 rpx：字号 28rpx~36rpx，间距 24rpx，圆角 16rpx。

5. 样式只在 WXSS 里生效，且必须能匹配到 class/标签。
   引擎自己解析 WXSS（不认 <style> 内联块、不认 style 标签写在 WXML 里）。
   想改某个元素 → 给它 class，规则写在 .wxss 文件里。
   支持常用属性：color / background-color / font-size / font-weight / text-align / padding /
   margin / border / border-radius / width / height / display:flex / flex-direction / flex-wrap /
   justify-content / align-items / line-height / max-lines / z-index / position(absolute)。
   ❌ 不支持：grid、transform、transition、animation、伪类(:hover/:nth-child)、媒体查询、CSS 变量。

6. 文字不会被自动换行到"撑高"卡片，宽高要自己给或靠 flex。
   view 里的纯文本会被当作隐式子文本；容器高度按内容算。
   但**滚动容器必须显式 height:100%**，否则它自己不知道有多高。

7. JS 语法边界（自研引擎，越界直接被打回，连包都不落地）。
   ✅ 支持：var/let/const、function、箭头函数、闭包、对象/数组字面量、字符串 + 拼接、
            if/else/for/while、JSON、Page({data:{…}})、this.setData({…})、App({})、wx.*
   ❌ 禁用：模板字符串（反引号）、解构、展开 ...、默认参数、对象方法简写、class、async/await、
            可选链 ?.、空值合并 ??
   ✅ 写法样例（注意没有一处越界）：
      Page({ data: { list: [], text: '' },
        onLoad: function () { this.setData({ list: [{id:1,title:'第一条',done:false}] }) },
        add: function () {
          var t = this.data.text;
          if (t.length === 0) { wx.showToast({ title: '先输入内容' }); return; }
          var next = this.data.list.concat([{ id: Date.now(), title: t, done: false }]);
          this.setData({ list: next, text: '' });
        },
        toggle: function (e) {
          var id = e.currentTarget.dataset.id;
          var next = this.data.list.map(function (it) {
            if (it.id === id) { it.done = !it.done; }
            return it;
          });
          this.setData({ list: next });
        }
      })

8. 视口宽高是**宿主给的**，页面自己要适配，别假设"卡片会长高/变宽来容纳内容"。
   引擎把根节点（ROOT）按**宿主给的视口**布局，`vp` 就是宿主给你的尺寸：
   · GenUI 画布内：视口较高（实测 1008x1296 这类整屏比例）；
   · ZorvAI 对话框内的小程序卡：视口**明显更矮**（实测 628x360，约屏高 62%，用户可拖拽改高度）。
   后果：**内容超出视口的部分直接看不见**，而且横向**没有滚动**——两列卡片宽度算多了右边那张就被切掉。
   ✅ 做法：
     · 竖向：内容超过一屏就必须由 `<scroll-view scroll-y style="height:100%">` 承载（陷阱 1）。
     · 横向：**永远不要**写死两列 50%。用 `display:flex` + 每列 `flex:1`（引擎支持 flex 分配），
       或者两列各给一个**留了余量的固定宽**（如容器 padding 24rpx 时每列 ≤ 340rpx）。
       容器有 `padding` 时，子元素 50%+50% 会超出容器内宽 → 右侧被裁（实测就是"第二个卡片被切一半"）。
     · 别把关键信息放最底下：矮视口里用户可能第一眼看不到。

9. 交付后**不要再回头补东西**。
   产物一旦内嵌进对话流就已是完整交付。再补一份 HTML / 再调另一个小程序工具 =
   对话流里并排两块画布（同一件东西画两遍），用户看到的就是重复界面。
""".trimIndent()

    val NATIVE_UI: String = """
【A 手册 · genui_native_ui —— WXML/WXSS/JS 原生 UI（自研引擎）】

── A1. 最小可用包结构（六个文件，一个都不能少）
genui_native_ui(app_id="todo", title="待办", files={
  "app.json": '{"pages":["pages/index/index"],"window":{"navigationBarTitleText":"待办"}}',
  "app.js":   'App({})',
  "app.wxss": 'page{background:#141418;color:#e8e8ee;font-size:30rpx}',
  "pages/index/index.wxml": '<scroll-view scroll-y style="height:100%">…</scroll-view>',
  "pages/index/index.wxss": '.card{padding:28rpx;border-radius:20rpx;background:#1e1e24}',
  "pages/index/index.js":   'Page({ data: {}, onLoad: function () {} })'
})
规则：
· app.json 的 pages 是**入口路由表**，首屏放第一个，路径不带扩展名；
· app.json 里声明的每一条路由，都必须有对应的 .wxml + .js（.wxss 可选）——缺一个整包被打回；
· 多页时每页四件套齐全；app.js / app.wxss 可留最小内容但**建议给全**（少给引擎会补默认，样式就会不对）。

── A2. 组件标签 → 引擎实际渲染成什么（照这张表写，别按微信文档想当然）
标签                                    │ 引擎节点类型 │ 实际表现
text / label / span                     │ TEXT        │ 文字
button / navigator / picker             │ BUTTON      │ 文字 + 你给的背景/边框（**没有系统按钮外观**）
switch / checkbox / radio / slider      │ BUTTON      │ ⚠️ 同上——就是文字，没有控件形态（见陷阱 2）
input / textarea                        │ INPUT       │ 单行输入（点它弹系统输入框，见陷阱 3）
image / cover-image / video / camera    │ IMAGE       │ 图片位；给 src，必须给 width/height
  / canvas / map / ad                   │             │
scroll-view / swiper / swiper-item      │ SCROLL      │ 唯一能滚动的节点族（见陷阱 1）
  / movable-area / movable-view         │             │
progress                                │ TEXT        │ 只是个百分比文字，**不会画进度条**
其他任意标签（view / div / section…）    │ VIEW        │ 通用容器（背景/边框/圆角/内边距都生效）

关于"按钮好看"：任何节点只要给了 background-color + border-radius + padding，
引擎就会真的画出背景与圆角（渲染层对宽高非零的节点一律画背景/边框）。所以：
  <view class="btn" bindtap="submit">保存</view>
  .btn{background:#d9a05b;color:#141418;padding:22rpx 0;border-radius:999rpx;text-align:center}
这就够了——**不需要** button 标签。

── A3. 事件表（引擎实际派发的只有这几种）
写法（WXML）        │ 触发时机                        │ 回调里能拿到什么
bindtap="fn"        │ 点击（手指按下抬起）              │ e.type="tap"、e.target.dataset、e.currentTarget.dataset
catchtap="fn"       │ 同上，但**阻止冒泡**             │ 同上
bindclick="fn"      │ 同 bindtap（别名，两者等价）      │ 同上
bindinput="fn"      │ 输入框在键盘"完成"时             │ e.type="input"、e.detail.value
bindblur="fn"       │ 输入框失焦                      │ e.type="blur"、e.detail.value

dataset 用法（给元素带参数的唯一方式）：
  <view class="row" data-id="{{item.id}}" bindtap="del">删除</view>
  del: function (e) { var id = e.currentTarget.dataset.id; … }
  ⚠️ 所有 data-* 到手都是**字符串**。比数字要 Number(id) 或 == 比较。

冒泡规则（引擎行为，与微信一致）：
  命中的最深节点若没绑 tap，就沿 parent 链**往上找第一个绑了 tap 的祖先**。
  所以整行可点：把 bindtap 绑在行的外层 view 上即可，行内按钮想独立响应就给它 catchtap。

── A4. wx.* API 全表（引擎注册了 28 个，**只有这些**）
系统信息   getSystemInfo / getSystemInfoSync
交互反馈   showToast / hideToast / showLoading / hideLoading / showModal / showActionSheet
振动       vibrateShort / vibrateLong
剪贴板     setClipboardData / getClipboardData
网络       getNetworkType / request
电话       makePhoneCall
存储       setStorageSync / getStorageSync / removeStorageSync / clearStorageSync
路由       navigateTo / redirectTo / navigateBack
导航栏     setNavigationBarTitle
时机       nextTick / getCurrentPage
下拉       stopPullDownRefresh

调用形态（两种都支持，回调里自己判断成败）：
  wx.showToast({ title: '已保存' })
  wx.showModal({ title: '确认', content: '要删除吗？', success: function (res) { if (res.confirm) { … } } })
  wx.setStorageSync({ key: 'todo', data: this.data.list })
  var v = wx.getStorageSync({ key: 'todo' })
  wx.request({ url: 'https://…', method: 'GET', success: function (r) { … }, fail: function (e) { … } })
❌ 不存在的（写了必报错或静默无效）：wx.createSelectorQuery、wx.getUserInfo、wx.login、
   wx.chooseImage、wx.getLocation、wx.playBackgroundAudio、wx.createCanvasContext、
   wx.createInnerAudioContext、wx.openLocation、wx.scanCode、wx.setTabBarBadge…
   GenUI 侧还有 run_js（真 Chromium）可以兜底取数据，需要复杂逻辑时先取数再喂给界面。

── A5. Page / setData / 生命周期
  Page({ data: {...},  onLoad: function (options) {},  onReady: function () {},
         onShow: function () {},  onHide: function () {},  onUnload: function () {},
         自定义方法: function (e) { this.setData({…}) } })
· 数据只在 this.data 里；**改数据只能靠 this.setData({...})**，直接改 this.data 不会重绘。
· setData 是"合并补丁"：只写要改的键。数组/对象要**整份换新**（用 concat / map 造新数组），
  就地 push 改同一引用可能不触发重绘。
· onLoad 里可以读 wx.getStorageSync 恢复上次数据；有变化再 setData。
· App({ onLaunch: function () {}, globalData: {} }) 配 getApp() 取全局数据。
· WXML 数据绑定：{{expr}}；支持三元、&&、||、算术、成员访问、数组下标；
  ❌ 不支持在模板里调函数（{{fmt(x)}} 无效）——要在 JS 里先算好塞进 data。
· wx:for：<view wx:for="{{list}}" wx:key="id">…{{item.title}}…</view>（配合 wx:if / wx:else 用）。

── A6. 可交互的三个范式（照抄改内容就能用）

范式 1 · 列表 + 增删改（待办 / 清单 / 记账）
  wxml: <scroll-view scroll-y style="height:100%">
          <view class="hd">共 {{list.length}} 项</view>
          <view class="row" wx:for="{{list}}" wx:key="id" data-id="{{item.id}}" bindtap="toggle">
            <view class="tick {{item.done ? 'on' : ''}}"></view>
            <text class="tt {{item.done ? 'done' : ''}}">{{item.title}}</text>
            <view class="del" data-id="{{item.id}}" catchtap="del">删</view>
          </view>
          <view class="bar">
            <input class="ipt" placeholder="新增一项" value="{{text}}" bindinput="onInput" />
            <view class="btn" bindtap="add">＋ 添加</view>
          </view>
        </scroll-view>
  js:   onInput: function (e) { this.setData({ text: e.detail.value }) },
        add: function () { … this.setData({ list: this.data.list.concat([新项]), text: '' }) },
        del: function (e) { var id = e.currentTarget.dataset.id;
               this.setData({ list: this.data.list.filter(function (x) { return x.id != id; }) }); }

范式 2 · 多页切换（列表 → 详情）
  两页：pages/index/index.* 与 pages/detail/detail.*，app.json 里 pages 两条都列出；
  跳转 wx.navigateTo({ url: '/pages/detail/detail?id=3' })；
  详情页在 onLoad: function (options) 里读 options.id；返回 wx.navigateBack({}）。
  ⚠️ 跨页传复杂对象：JSON.stringify 存 wx.setStorageSync，另一页取出来 parse。

范式 3 · 有状态的小工具（计算器 / 番茄钟 / 单位换算）
  全部状态塞 data，操作 = 改 data + setData；不要用 setTimeout 驱动界面
  （引擎无定时器调度保证），需要计时就靠用户操作驱动重算。

── A7. 交付纪律
· 建完后**自动内嵌对话流**，用户可直接试玩；只有用户明确说「全屏打开」才调 open_miniapp。
· 一轮只做一件事：走了 genui_native_ui 就不要再调 miniapp、也不要再补 HTML。
· app_id 只能用 2-32 位小写英文/数字/-/_（大写或中文会被打回）。
""".trimIndent()

    /** genui_native_ui 错误清单：把 ZorvToolAdapter.createMiniApp 的真实打回理由 + 引擎行为写成现象→成因→改法。 */
    val NATIVE_UI_ERRORS: String = """
【A 手册 · 错误清单 —— 引擎与校验会这样打回你，照"怎么改"这一列改】

① 报错：files 缺失：需为 {路径: 内容} 映射
   成因：files 传成了字符串、数组，或者只给了 wxml/wxss/js 单页参数但连文件名都没写。
   怎么改：files 必须是 JSON **对象**，键是相对路径（不以 / 开头），值是文件全文。

② 报错：非法路径：xxx
   成因：路径里带 ".." 或以 "/" 开头。
   怎么改：一律用相对路径，如 pages/index/index.wxml。

③ 报错：缺 app.json（包结构必需）
   成因：files 里没写 app.json。
   怎么改：加 "app.json": {"pages":["pages/index/index"],"window":{"navigationBarTitleText":"标题"}}。

④ 报错：包里没有任何 .wxml 页面，无法确定入口
   成因：只写了 app.json 和 js，没有 .wxml；或页面文件名不在 app.json 的 pages 里。
   怎么改：至少给 pages/index/index.wxml + .js，并在 app.json 的 pages 里写 "pages/index/index"。

⑤ 报错：页面文件缺失：pages/index/index.js（app.json pages 里声明了 pages/index/index）
   成因：**路由表和文件对不上**——声明了页面却没给全 .wxml/.js。整个包会被丢弃。
   怎么改：声明几条路由就给几套文件；不用的路由从 pages 里删掉。

⑥ 报错：JS 语法错误 @pages/index/index.js：…
   成因：用了自研引擎不支持的语法，最常见的是**模板字符串（反引号）**。
   怎么改：字符串拼接改用 +；对象方法用 {key: function(){}}；
           不用解构/展开/默认参数/class/async/await/可选链/空值合并。（见陷阱 7）

⑦ 报错：app_id 需为 2-32 位小写英文/数字/-/_
   成因：用了大写、中文或空格。
   怎么改：如 todo-app、weather_tool。

⑧ 警告（不阻塞）：WXML 解析警告
   成因：标签没闭合、wx:for 写得不对、属性引号不成对。
   怎么改：检查闭合与 {{ }} 配对；警告不致命但界面可能缺块。

⑨ 现象：界面一片空白
   成因 A：包被打了回，什么都没落地（看返回里的 error）。
   成因 B：根节点没有尺寸——没写 <scroll-view style="height:100%">，容器高 0 自然什么都不显示。
   成因 C：文字颜色与背景同色（引擎默认底色偏深，白字给深色背景）。
   怎么改：先确认返回 ok:true，再检查根 scroll-view 与配色。

⑩ 现象：内容被裁断在卡片里，滚不动（"文字泄露在画布"）
   成因：用了几层普通 view 承载超屏内容——只有 scroll-view 能滚。
   怎么改：最外层换 <scroll-view scroll-y style="height:100%">。

⑪ 现象：点了没反应
   成因 A：事件名写错（必须是 bindtap / catchtap / bindclick / bindinput / bindblur，
           写成 @click、onclick、bindTap 都不认）。
   成因 B：处理器名和 JS 里的键名不一致（大小写敏感）。
   成因 C：点的是自闭合的 switch/checkbox/radio/slider（宽高 0，压根没有可点区域）。
   怎么改：统一 bindtap="fn"，确保 Page 对象里有 fn，用有尺寸的 view/button 承载。

⑫ 现象：输入框拿不到值 / 打了字没变化
   成因：input 没有受控绑定（WXML 上没写 value="{{text}}"），或逐字联想依赖 bindinput 连续触发
         （它只在键盘"完成"时触发一次）。
   怎么改：value="{{text}}" + bindinput="onInput" → setData({text:e.detail.value})；
           交互设计改成"输完点按钮"。

⑬ 现象：开关/勾选框/滑杆完全不见
   成因：引擎把 switch/checkbox/radio/slider 渲染为文本，且自闭合时无宽高。
   怎么改：用 view + WXSS 自绘（见陷阱 2）。

⑭ 现象：图片不显示
   成因：image 没给 width/height（IMAGE 节点按给定宽高出图），或 src 是本地相对路径
         （引擎取不到包内文件，用 https 链接或 base64）。
   怎么改：<image src="https://…" style="width:100%;height:360rpx" />。

⑮ 现象：两块画布 / 界面被画了两遍
   成因：同一个交付里既调了 genui_native_ui 又调了 miniapp(create+run)，或工具交付后
         又补写了一份 HTML。
   怎么改：一轮只交付一件（见对照表末尾的互斥铁律）。

⑯ 现象：并排的两张卡片，右边那张被切掉一半（横向溢出）
   成因：横排两列写死 50%，而容器还有 padding/间距 → 两列内宽相加超过视口宽度；
         引擎**没有横向滚动**，超出部分直接裁掉。
   怎么改：`display:flex` + 两列各 `flex:1`；或把每列宽度按"视口宽 - 容器 padding - 间距"分完留余量
          （如容器 padding 24rpx、间距 16rpx 时，每列 ≤ 340rpx）。

⑰ 现象：小程序只显示了最上面一小段，下面完全看不到、也拉不动
   成因：宿主视口是固定高度（ZorvAI 对话框内实测 628x360），引擎不会为内容长高；
         如果页面把全部内容堆在一个普通 view 里，超出视口的部分就被裁掉。
   怎么改：内容用 `<scroll-view scroll-y style="height:100%">` 包住（陷阱 1），
          并把首屏关键信息放在最上方；矮视口下不要把重要内容压在底部。
""".trimIndent()

    // ════════════════════════════════════════════════════════════════
    //  B. miniapp（小程序工作室）完整手册
    // ════════════════════════════════════════════════════════════════

    val STUDIO: String = """
【B 手册 · miniapp（小程序工作室）—— HTML + Page() 运行时 + native.* 原生桥】

── B1. 它是什么
由 WebView 跑**真实 HTML 页面**的小程序工程，比 genui_native_ui 多一层 native.* 桥，
可以调本机的存储 / SQLite / 加密 / 通知 / 分享 / 定位 / 关联启动等真实能力。
工程落在手机私有目录 filesDir/studio/miniapp/<name>/，与工具中心的「小程序工作室」面板**同一份文件**
——AI 写完，面板里立刻能看到、能跑。

── B2. 工程结构
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

── B3. 两步交付（这是它和 genui_native_ui 最大的操作差别）
1) create 落地工程（可一次写多个文件）
2) run 取回**自包含 HTML**（自动内联同目录 .js/.css），这份 HTML 会内嵌进对话流渲染
   ❌ 只 create 不 run = 用户什么都看不到（界面根本没交付）
   ❌ run 之后自己再写一份 HTML = 两块画布
所以：**create + run 必须成对完成，且本轮不再走其他画型工具。**

── B4. 页面与运行时（Page()）
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
· 这是**网页运行时**，DOM / addEventListener / CSS 全部可用（比 genui_native_ui 自由得多）。
· 事件绑定用标准网页写法：onclick / oninput / addEventListener 都行，
  或 <button data-id="3" onclick="del(this.dataset.id)">。
· 持久化优先用 native.storage / native.db（见 B5），也可以配合 localStorage 但换设备会丢。

── B5. native.* 桥（原生能力全表 —— 这是用工作室的唯一理由）
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

── B6. 什么该选工作室（判断标准）
✅ 要跨启动保存数据、要按条件查历史记录、要算摘要/签名、要发通知、要系统分享、
   要定位、要拉起另一个 App、要跑 SQL —— 选它。
❌ 只是好看的界面 + 本地临时状态（待办、计算器、展示页）—— 用 genui_native_ui 更快更稳。

── B7. 错误清单（B 侧）
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
   怎么改：照 B5 表逐字核对函数名；把失败回调的提示渲染到界面上。

⑤ 数据重启就丢
   成因：只用了内存变量 / localStorage 未持久化。
   怎么改：改用 native.storage.setItem 或 native.db 落库。

⑥ 两块画布
   成因：本轮又调了 genui_native_ui，或 run 之后另写 HTML。
   怎么改：见对照表末尾的互斥铁律。

── B8. 文件操作（同一工具）
  miniapp(action="write", name="todo", path="pages/index/index.html", content="…")   改单个文件
  miniapp(action="read",  name="todo", path="app.json")                             读文件
  miniapp(action="list")                                                            列出全部工程
  miniapp(action="delete",name="todo")          删整个工程
  miniapp(action="delete",name="todo", path="pages/about/about.html")   删单个文件
  miniapp(action="manual")                      取回本手册（函数级细节 + 错误清单）
""".trimIndent()

    /** 精简版：塞进工具 description（模型每轮都会读到，必须短）。 */
    val STUDIO_BRIEF: String = """
【两个小程序工具，别搞混】
· 本工具 miniapp = 小程序工作室：**HTML 页面 + Page() 运行时 + native.* 原生桥**（能存数据/跑 SQL/加密/通知/分享/定位/拉起 App）。
  交付要两步：create 落地 → run 取回自包含 HTML 内嵌对话流。只 create 不 run = 用户什么都看不到。
· genui_native_ui（GenUI 内）= **WXML/WXSS/JS 原生界面**，自研引擎直接画，没有 HTML、没有 native.*。
  要碰原生能力就用本工具，不要用 genui_native_ui；两者**同一轮只能用其中一个**（都用 = 两块画布）。

【最高频的 5 个错】
1. create 完忘了 run → 对话框里什么都没有。
2. name 拼错 / entry 与 app.json 的 pages 对不上 → run 找不到页面（先 list 看真实名字）。
3. 页面白屏 → HTML 不完整，或 JS 报错中断渲染（脚本放 body 末尾 + try/catch，出错也渲染提示）。
4. native 调用没反应 → 函数名写错（是 native.kotlin.copyText 不是 copy 之类）或缺权限被拦。
5. 数据重启就丢 → 没用 native.storage / native.db 持久化。

【完整手册 · 两份，都从这个工具的 manual 动作取】
· **① 小程序工作室（本工具自己的手册）** → `miniapp(action="manual")` 或 `topic="studio"`
  内容：工程结构 / Page() 运行时 / **native.* 全部函数逐个说明** / 可交互范式 / 错误清单。
· **② create_miniapp（GenUI 那套原生 UI 的完整手册）** → `topic="native"`
  内容：**组件标签→引擎实际渲染**对照表 / 事件表与 dataset / **wx.* 全表（28 个）** / Page 与 setData /
  三个可交互范式。配套：`topic="traps"`（9 条引擎致命陷阱）、`topic="errors"`（17 条错误清单：真实打回理由 → 成因 → 怎么改）。
· **只看两者区别** → `topic="compare"`。
（两份手册读的是同一份真相源 `MiniAppManual`，GenUI 画布侧用工具 `genui_manual` 取同样内容。）
""".trimIndent()

    /** GenUI 形态 4 用的精简版（GenUI 侧另有 A 手册，这里只讲 B 的要点与二者关系）。 */
    val STUDIO_FOR_GENUI_BRIEF: String = """
【形态 4 · miniapp 工作室 一句话要点】
· 它是 **HTML + Page() 运行时 + native.* 原生桥**；形态 3 是 **WXML/WXSS/JS 原生界面**（无 HTML、无原生桥）。
· 交付必须两步：① miniapp(action="create", name=…, files=[{path,content}]) ② miniapp(action="run", name=…)。
  只 create 不 run = 什么都没交付；run 之后再写 HTML = 两块画布。
· 它的存在价值就是 native.*（storage/db/crypto/aci/kotlin/network/router/location）。
  不需要原生能力就别选它，用形态 3。
""".trimIndent()

    /** 供 ZorvAI / GenUI 系统提示词起头引用的「两个工具是谁」短句。 */
    val ONE_LINER: String =
        "两个小程序工具：genui_native_ui = WXML/WXSS/JS 原生界面（自研引擎，无 HTML/无原生桥）；" +
            "miniapp = HTML 工作室（Page() + native.* 原生桥，要 native 能力才用它）。二者不可同时用。"

    // ────────────────────────────────────────────────────────────────
    //  按需取用（ZorvAI 的 miniapp(action="manual") 与 GenUI 的 genui_manual 都走这里）
    // ────────────────────────────────────────────────────────────────

    /**
     * 取手册正文。
     *
     * ⚠️ 每个 topic 的返回都刻意压在 **8000 字符以内**：AgentLoop 把工具结果回灌给模型时是
     * `content.take(8000)`（见 AgentLoop 里 "[工具结果]" 那一行），一次取太多会被静默截断，
     * 模型只拿到半本手册反而更容易写错。所以"全册"不作为一个 topic 返回，改成按需分册取。
     */
    fun section(topic: String): String {
        val t = topic.trim().lowercase()
        return when (t) {
            "compare", "对比", "对照" -> COMPARE
            // A 侧：引擎陷阱与 A 手册拆成两册（合起来超 8000）
            "traps", "陷阱" -> NATIVE_UI_TRAPS
            "native", "原生", "原生ui" -> NATIVE_UI
            "errors", "错误", "错误清单" -> NATIVE_UI_ERRORS
            // B 侧：对照表 + 工作室手册（= 5946 字符，安全；也是 ZorvAI 侧的默认全部）
            "studio", "工作室", "" -> COMPARE + "\n\n" + STUDIO
            // 只有明确要"全册"时才给（调试用；交付路径上不要用，会被截断）
            "all", "全部" -> "<A 陷阱>\n" + NATIVE_UI_TRAPS + "\n\n<A 手册>\n" + NATIVE_UI +
                "\n\n<A 错误清单>\n" + NATIVE_UI_ERRORS + "\n\n<B 手册>\n" + STUDIO
            else -> COMPARE + "\n\n" + STUDIO
        }
    }

    /** 分册清单（工具描述里用，告诉模型有哪些册可取）。 */
    val TOPICS: String = "compare（两工具对照）/ traps（A 引擎陷阱）/ native（A 完整手册：组件·事件·wx.*·Page·范式）" +
        "/ errors（A 错误清单）/ studio（B 工作室手册，含错误清单）；不传=compare+studio"

    /**
     * GenUI 提示词里常驻的「手册取用纪律」。
     *
     * 为什么不把整本手册塞进提示词：全册 ≈ 15.5K 字符，而 GenUI 每轮都要发一遍系统提示词；
     * 更关键的是端上模型上下文有限，塞满手册会挤压"用户到底要什么"的判断力。
     * 所以策略是：**最致命的常驻 + 细节按需取**，并且把"取手册"写成硬性前置动作，
     * 避免模型凭微信小程序的经验硬写（那正是"界面空白/点了没反应"的根因）。
     */
    val GENUI_DISCIPLINE: String = """
【手册取用纪律 —— 写小程序类交付之前必须执行，不要凭经验硬写】
本节的陷阱表是常驻的；**细节手册按需取**（工具 `genui_manual`，每个分册都控制在一次可读的体量内）：
· 要写形态 3（`genui_native_ui`）→ 先取 `genui_manual(topic="native")`
  （组件标签实际渲染成什么 / 事件表与 dataset / wx.* 全表 / Page 与 setData / 三个可交互范式）。
· 要写形态 4（`miniapp` 工作室）→ 先取 `genui_manual(topic="studio")`
  （工程结构 / Page() 运行时 / native.* 逐个函数 / 错误清单）。
· **工具被打回时不要盲试第二次** → 取 `genui_manual(topic="errors")`，
  对着"成因 → 怎么改"逐条修（工具返回的 error 就是错误清单里的某一条）。
· 分不清该用哪个工具 → `genui_manual(topic="compare")`。

下面先读常驻的引擎陷阱表——它列出的每一条都是自研引擎的**事实**，违反必出黑盒故障。
""".trimIndent()
}
