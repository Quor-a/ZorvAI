# GenUI Agent/SDK 通道全面排查 + miniapp-sdk 渲染修复（2026-11-04 第三批）

> 用户澄清：GenUI Agent 指 **genuiagent-sdk / miniapp-sdk 原生组件通道**（非 WebView 卡片）。
> 全面排查两模块共 87 文件 / 1.76 万行组件代码 + 自研小程序引擎（WXML/WXSS/FlexLayout/自研 C++ JS），
> 按排查报告实施框架级修复。全部通过 `:genuiagent-sdk` + `:miniapp-sdk` + `:app` 编译。

## 一、GenUI SDK（579 个注册组件）

### 根因修复：「组件超出屏幕」
- `GenUILayoutGuard.LocalPageMaxWidth`（新增 CompositionLocal）：PageCanvas 下发页面实际可用宽（屏宽−页边距，≤720dp）。
- `StyleResolver.baseModifier`：固定宽度统一取 `min(值, 页面可用宽)` —— 旧实现只钳 720dp（平板阅读宽），手机 360dp 屏上 AI 写 `width:700` 被"合法"放行、横向溢出一倍屏宽。超屏值现在收敛为满宽组件。

### 超屏 TOP 渲染器逐个修
| 组件 | 问题 | 修法 |
|---|---|---|
| HourlyForecast | 24 小时无 weight/scroll ≈960dp 溢出 3 屏 | 每行 6 列 + weight 均分 + 省略号 |
| TaskbarDock | N×40dp 图标无滚动 | fillMaxWidth + horizontalScroll |
| Positioned | x/y 裸读 props 绕过守卫，AI 写 400/800 飞出屏 | clamp ±1.5×页面可用宽 |
| ScrollIndicator | 滑块位移硬编码 240dp | BoxWithConstraints 按实际宽度比例 |
| NavDrawer / SideMenu / SideSheet | fillMaxHeight+定宽内联（无界高 + 占版心 80%） | 内联态改满宽纵向列表 |
| PhoneMockup | 内屏固定 400dp 高不可缩放 | aspectRatio(9:19) |
| DataTable | 8+ 列每列 40dp 中文竖排 | 列数>4 套横向滚动 + 省略号 |
| TagCloud | 行无宽度约束、文字无 maxLines 溢出裁切 | weight + Ellipsis |
| GenUIDialogOverlay | 对话框内宽 child 横向出屏 | widthIn(max=页面可用宽) |

### 渲染分支补全（「渲染不完整」）
- **18 个有声明无渲染的 pattern 类型**（action_sheet_pattern / bottom_tabs / chat_bubble / dashboard / data_row / detail_page / feature_grid / form_page / list_page / metric_group / order_card / post_card / price_tag / product_card / profile_page / settings_group / story_ring / timeline_item）：AI 一写就落「⚠ 未知组件」警示卡 → 统一注册 `PatternCardRenderer`（类型名 overline + 卡片容器 + 渲染 children，视觉成立、内容不丢）。
- renderx FlatRenderer 补 `header_bar` / `footer` 分支（A2uiProtocol TYPE_MAP 会产出这两个类型，此前退化成一行普通文字）。

### 排查确认健康（未动）
流式围栏续写（5 级提取 + 只进不退 + 上限 4 次）、未知 type 的 FallbackRenderer 降级、Row 权重分配（ColumnRowRenderer + GenUIParentAxis）。

## 二、miniapp-sdk（自研小程序引擎）

| # | 缺陷 | 修复 | 位置 |
|---|---|---|---|
| 1 | `<image>` **永不渲染**（imageProvider 无人赋值） | MiniAppView.start 接图片解析：filesDir → assets → data URI → http(s)（后台解码+缓存+重绘；失败缓存 null 防每帧重试；绕开 MiniPackage 的纯文本装载——二进制被 readText 读过即损坏） | `core/MiniAppView.kt` |
| 2 | `line-height:1.5` 无单位被当 1.5px → 行距坍缩文字重叠 | Style 记 `lineHeightUnitless`，FlexLayout.resolveFontStyle 统一换算 字号×倍数 | `render/Style.kt`、`render/FlexLayout.kt` |
| 3 | `!important` 后缀让 Length/parsePx 解析失败 → **值静默变 0** | fromDeclarations 全局剥离后缀 | `render/Style.kt` |
| 4 | 伪类/伪元素/`>`/属性选择器把规则静默弄丢（`.card::after` 变 tag "card::after"） | parseSelector 剥伪类段、`>` 当后代分隔、剥属性选择器 | `view/WxssParser.kt` |
| 5 | WXSS 缺右花括号 → 其后所有规则静默丢弃 | 容错：剩余文本按本规则体解析 | `view/WxssParser.kt` |
| 6 | `findMatchingClose` 前缀误匹配（`<text>` 认成 `<textarea>`）→ 树损坏 | 开标签后字符必须是空白/`>`/`/` | `view/WxmlParser.kt` |
| 7 | AI 不知道引擎限制，写出来就坏 | `miniapp_sdk` 工具描述增补**语法限制清单**（禁 template/wxs/Component/require/Promise/async/class/伪类/!important/fixed/em/无单位 line-height；事件只用 bindtap/bindinput；image 给宽高；横向内容套 scroll-view） | `core/tools/MiniAppSdkTool.kt` |

### 记录在案（下一批，改动面大）
- flexShrink 默认 0 偏离 CSS（超宽不收缩只裁切）；white-space:nowrap / text-overflow:ellipsis 解析后无消费；position:fixed/sticky 缺失；Component()/require()/Promise 不支持（提示词已拦截主要踩雷面）；GLRenderer 为 377 行死代码。

## 三、验证

- 真机验证点：AI 写 width:700 的卡片不再溢出；24 小时天气/任务栏/表格/标签云不再横向出屏；导航抽屉内联渲染正常；18 个 pattern 类型显示为卡片而非警示；小程序 `<image>` 显示图片；`line-height:1.5` 行距正常；带 `!important`/伪类的 WXSS 样式生效；节点编辑器反复开关不再黑屏。
