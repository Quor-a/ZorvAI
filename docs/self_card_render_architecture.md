# 小卡片（自研卡片）渲染架构

> 适用代码：`app/src/main/java/com/ai/assistance/quro/core/ui/card/**`
> 输入协议：AI 回复里的 ` ```quro-card ` 围栏（JSON）
> 与它并列的另一条通道：`core/cards/**`（`ui_card` / `ui_widget` 围栏，103 种组件），两者**不合并**

---

## 1. 分层与数据流

```
AI 输出 ```quro-card {json}
      │
      │ ① 协议层  spec/CardSpecParser.kt      —— 纯 JVM，org.json，无 Compose 依赖
      ▼
   CardSpec(id, type, data, layout, style, title, subtitle, actions, rawJson)
      │
      │ ② 编排层  registry/CardRegistry.kt    —— 三级解析：type → 别名 → 按 data 推断
      ▼
   @Composable 渲染器  compose/*.kt
      │            └── 共用外壳 CardShell（底色/描边/圆角/卡头/入场动画/无障碍）
      ▼
   对话框内联渲染（ChatScreen.SelfCardMount → host/CardSurface）
```

**为什么这样分层：**

| 层 | 只依赖 | 换来什么 |
| --- | --- | --- |
| 协议层 | `org.json` | 可以在**纯 JVM 单测**里跑（不启 Robolectric 也能验解析），解析 bug 不用装机复现 |
| 编排层 | 类型表 + 注册表 | 加一种卡片 = 注册一行；未知 type 有兜底；不会因某个渲染器异常影响其它卡 |
| 表现层 | Compose | 排版/测量/命中测试交给 Compose，手写 Canvas 排版那套 bug 从架构上消失 |

---

## 2. 类型矩阵（type → data → 渲染器）

| 自有 type | data.kind | 子类型来源 | 渲染器 | 备注 |
| --- | --- | --- | --- | --- |
| `metric` | chart(metric) | `series[0]` | MetricCardView | 大数字滚动 + 涨跌胶囊 + 迷你走势 |
| `line_chart` | chart | `chartType=line` | ChartCardView | 折线 + 网格 + 三档刻度 |
| `area_chart` | chart | `area` | ChartCardView | 折线 + 渐变填充 |
| `bar_chart` | chart | `bar/column` | ChartCardView | 支持多序列分组 |
| `pie_chart` | chart | `pie/donut` | ChartCardView | 扇区 + 图例 + 百分比 |
| `sparkline_chart` | chart | `sparkline` | ChartCardView | 无轴迷你线 |
| `table` | media(table) | `headers/rows` | TableCardView | 等宽列 + 表头底色 |
| `media_card` | media | `mediaType` | MediaCardView | 时间线 / 列表 |
| `code_block` | media(code) | `code/lang` | MediaCardView | 等宽 + 横向滚动不折行 |
| `image_group` | media(image_group) | `images/urls` | MediaCardView | Coil 加载，失败有占位 |
| `form_card` | form | `fields/options/min-max` | FormCardView | 输入框 / 选择器 / 滑块 |
| `button_group` | form | `buttons` | ButtonGroupCardView | 支持 filled / outline |
| `status` | status | `progress/error/tool_call` | StatusCardView | 百分比 + 呼吸点 |
| `skeleton` | status(skeleton) | — | SkeletonCardView | 呼吸占位，高度固定防抖 |
| `custom` | — | `layout` 树 | CustomCardView | AI 自写布局，见 §4 |

**别名**（`bar`、`donut`、`spark`、`kpi`、`buttons`、`code`…）定义在 `spec/CardTypes.ALIASES`。

> ⚠️ **铁律**：`CardTypes.ALIASES` 里**绝不能出现** `core.cards.CardSdk` 的 103 个类型名
> （`chart` / `pie` / `stat` / `form` / `media` / `timeline` / `gallery` / `sparkline` / `composite` …）。
> 原因：`SelfCardMount` 先查本通道白名单，一旦重名就会把卡片从富卡片层抢过来，
> 而两层字段名不兼容（CardSdk 用 `chart_type`、`series[].label/value`）→ **本来能渲染的卡变空白**。
> `CardPipelineTest.registry_unknownType_isNotWhitelisted` 与 `CardRenderArchTest` 盯着这条。

---

## 3. 三级解析（「小卡片不完整」的根治点）

```
type 命中注册表?  ── 是 ──▶ 用它的渲染器
      │否
归一 + 别名命中?  ── 是 ──▶ 用别名目标
      │否
按 data 形状推断? ── 是 ──▶ 用推断出的渲染器（bar/pie/table/code/…）
      │否
宿主降级：把标题与 layout 里的文字逐行列出来（绝不吐「不支持的卡片类型：X」）
```

模型写卡片最常见的四种「坏法」分别被谁接住：

| 模型的写法 | 谁接住 |
| --- | --- |
| `type` 写错/自造（`my_chart`） | 第 3 级：按 `data.kind`+`chartType` 推断 |
| 字段平铺、没有 `data` 壳 | 协议层 `wrapByType()` 按 type 包壳 |
| 用了组件库字段名（`chart_type`、`series[].label/value`、`segments`） | 协议层同义字段兼容 |
| 流式输出到一半（JSON 不闭合） | `stream/JsonCompletion.close()` 补齐后渲染当前已知部分 |

### 3.1 流式补全的修复顺序（`JsonCompletion.close`）

补全不是「补右括号」一句话，按下面顺序依次处理，任何一步的顺序错位都会产出非法 JSON：

| 步 | 输入形态 | 原样补括号的结果 | 处理 |
| --- | --- | --- | --- |
| 1 | `{"a":"通` | `{"a":"通"}` ✅ | 扫描时记录 `inString`，收尾补引号 |
| 2 | `{"a":1,` | `{"a":1,}` ❌ | 先砍末尾悬空 `,` |
| 3 | `{"a":` | `{"a":}` ❌ | 砍 `:` **再砍键名**（只剩 `{}`），补 `null` 虽合法但污染渲染 |
| 4 | `{"a":1,"` | `{"a":1,""}` ❌ | 补引号后成了**空键** → `dropDanglingEmptyKey` 砍掉 `,` 和 `""`（每个逗号边界都会撞到） |
| 5 | `{"a":tru` | `{"a":tru}` ❌ | 砍半截字面量（`t/tr/tru/f/…/nul`），前一字符必须是 `: , [ {`，避免误伤 `"data"` |
| 6 | `{"a":1,"}` | `{"a":1,"}}` ❌ | 正则 `,\s*([}\]])` 循环删悬空逗号 |
| 7 | 括号栈未清空 | — | 按 `ArrayDeque` 记录的开括号逆序补齐 |

最后一道闸：`org.json.JSONObject(result)` 解析不通过就返回 `null`，让上层退回骨架/纯文本，
**绝不把半成品 JSON 丢给渲染器**（否则整卡消失）。已被 `CardRenderArchTest` 的 6 个用例覆盖。

---

## 4. `custom` 布局树支持的能力

容器：`card` / `box` / `column` / `row` / `grid(columns)` / `list`（含 `items`）
叶子：`text`（`align/maxLines/lineHeight/italic/countTo`）、`badge|chip|tag|pill`、`button`（按出现顺序映射 `actions`）、`icon|avatar`、`image`（`url` → Coil）、`spacer`、`divider`
几何：`ring`、`bar`（进度）；样式：`bg` / `gradient` / `angle` / `radius` / `padding` / `gap` / `border` / `borderColor` / `shadow`

**百分比两套值域**：`ring` / `bar` 的 `value` 既可能是 `86`（0~100）也可能是 `0.86`（0~1），
`CardExtractors.percent()` 统一判定（给 `max` 就按 max 归一，>1 视为 0~100）。
> 历史坑：旧实现一律 `/100`，模型写 `0.86` 时进度条只剩 0.86% —— 用户看到的是一张「空卡」。

---

## 5. 加一种新卡片：四步

1. `spec/CardTypes.kt`：加常量 → 进 `CANONICAL_TYPES` →（需要的话）加别名 → 在 `inferType` 里加映射；
2. `compose/XxxCardView.kt`：写渲染器，**内容套在 `CardShell` 里**（不要自己画底色/圆角）；
3. `CardModule.init()`：`CardRegistry.register(CardTypes.XXX) { … }`；
4. `CardRenderArchTest` 补一条解析 + 推断断言（纯 JVM，秒级）。

不允许做的事：
- 在渲染器里手算文字宽度 / 手排坐标（连续几何以外）；
- 自己判亮暗主题（用 `ColorToken` + `MaterialTheme`）；
- 直接引用 `core.cards.CardSdk`（两层保持零依赖，路由由 `ChatScreen.SelfCardMount` 决定）。

---

## 6. 依赖

| 依赖 | 用途 | 说明 |
| --- | --- | --- |
| `androidx.compose.*`（BOM 2026.01.01） | 全部排版与几何 | 已存在，无新增 |
| `coil-compose 2.6.0` | `image` / `image_group` 图片 | 已存在，复用磁盘缓存 |
| `org.json` | 协议解析 | 已存在（app 与 unit test 都有），故解析层可纯 JVM 测 |
| — | 图表库 | **不引入**（Vico/MPAndroidChart 等）：饼环/柱/线用局部 Canvas 60 行就能画，引入会显著增大 APK 且难以跟随亮暗主题 |
