# 对话框 HTML / Markdown 渲染能力增强

**日期**：2026-07-18
**场景**：设计 + 前端（UI 富文本渲染增强）
**参与成员**：主理人（直接代行；gstack 子代理本环境不可用，按既定规范由主理人完成代码层工作）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 通过（编译 BUILD SUCCESSFUL，能力补齐）
- 阻塞项数量：0
- 根因：对话框原有 HTML 渲染是「半吊子」——行内标签（`<b>/<i>/<code>/<a>`）可渲染，但**块级 HTML（`<h1>-<h6>`、`<blockquote>`、`<hr>`、`<table>`、`<span style="color">`）在 `parseInlineHtml` 的 switch 里被直接忽略**；同时 **Markdown 块级（`# 标题`、`> 引用`、`- 列表`）从未被识别**。模型输出富文本时看起来「没渲染」。
- 下一步：装最新 APK，发一条含 HTML/Markdown 的回复验证。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 1（CodeBlock 参数顺序 bug）/ 🟢 3（功能增强） |
| 关键行动项 | 1 条（装包验证） |
| 建议负责人 | 主理人（已实现）；用户（验证） |

---

## 1. 各成员核心结论

### 🔧 主理人（直接代行 · 排障/前端实现）
- 核心判断：并非「没有 HTML 能力」，而是**块级结构与 Markdown 块级完全没接上**——`parseInlineHtml` 只处理行内标签，switch 里把 `<h1>-<h6>`/`<span>`/`<pre>` 一律忽略；`parseBlocks` 只切 ```代码块```，不识别 `# 标题`/`> 引用`/`- 列表`。所以模型吐的标题没字号、引用没样式、表格/颜色不生效。
- 关键建议：扩展消息分块管线识别块级 HTML 与 Markdown，并在 `MessageRow` 增加对应渲染分支；给 `parseInlineHtml` 补 `<span style="color">` 支持；顺手修正 `CodeBlock` 调用参数顺序反了的 bug。

> 本环境 gstack 子代理（designer / investigator 等）均不可用，主理人按规范直接完成分析与代码改动，报告格式从 GStack 5-section 收口。

---

## 2. 综合审查发现（改动点）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟢 | 功能缺失 | `ChatScreen.kt` · `parseInlineHtml` switch | `<h1>-<h6>`/`<span>`/`<pre>` 被忽略 | 块级结构交由 `parseRichBlocks` 解析；`<span style="color:#..">` 现可上色 | 主理人 |
| 2 | 🟢 | 功能缺失 | `ChatScreen.kt` · `parseBlocks` | 不识别块级 HTML / Markdown | 新增 `parseRichBlocks`（HTML 块级）+ `parseParagraphs`（Markdown `# > -` 识别） | 主理人 |
| 3 | 🟢 | 功能缺失 | `ChatScreen.kt` · `MessageRow` | 无 Heading/Quote/Rule/Table 渲染分支 | 新增分支 + `RenderTable` 组合（横向滚动防溢出） | 主理人 |
| 4 | 🟡 | Bug | `ChatScreen.kt` · `MessageRow` L957 | `CodeBlock` 调用写成 `(blk.lang, blk.code)`，与定义 `(code, lang)` 相反 | 改为 `(blk.code, blk.lang)` | 主理人 |

### 支持的渲染矩阵

| 元素 | 语法示例 | 状态 |
|------|---------|------|
| 标题 | `<h1>…</h1>` 或 Markdown `# 标题` | ✅ 字号层级 22/19/17/16/15/14 sp |
| 引用 | `<blockquote>…</blockquote>` 或 `> 引用` | ✅ 浅底 + 斜体 |
| 分割线 | `<hr>` / `<hr/>` | ✅ |
| 表格 | `<table><tr><td>…` | ✅ 基础表格（首行表头，横向滚动） |
| 行内颜色 | `<span style="color:#ff0000">` | ✅ 仅 `#rrggbb`/`#rgb` |
| 粗体/斜体/删除/下划线 | `<b>/<i>/<s>/<u>`、`<strong>/<em>/<del>` | ✅（原已支持） |
| 行内代码 | `<code>…</code>` | ✅（原已支持） |
| 链接 | `<a href="…">` | ✅ 可点击（原已支持，走内置浏览器） |
| 代码块 | ```` ```lang … ``` ```` | ✅（原已支持） |
| 列表 | `<ul><li>` 或 Markdown `- 项目` | ✅ `•` 前缀 |

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 装 `QuroAI-debug.apk` 发一条含 HTML/Markdown 的回复，验证标题/引用/表格/颜色渲染 | 用户 | P0 | 立即 |
| 2 | 如需要 `<img>` 图片、嵌套表格、命名颜色（red/green）等再增强 | 主理人 | P2 | 后续 |

---

## ⚠️ 待完善 / 已知局限

- `<img>` 图片标签**未渲染**（模型常给 URL，需网络/本地加载，本次未做）
- `<table>` 为基础表格：首行作表头、单元格横向滚动，**不支持合并单元格 / 嵌套表**
- 命名颜色（`red`/`green`）不生效，仅 `#rrggbb` / `#rgb`
- `<pre>` 代码块内 HTML 未做语法高亮（走普通代码块样式）
- `QuroMarkdown.kt` 里已有独立 `MarkdownText` 渲染器但**全项目从未被调用**；本次选择增强 `ChatScreen` 现有管线而非切换，以最小侵入覆盖 HTML+Markdown 双语法

---

## 📚 成员产出索引

- 主理人（直接代行）原始产出：`ChatScreen.kt` 6 处编辑（import 补充 / `MsgBlock`+`parseBlocks`+`parseRichBlocks`+`parseParagraphs`+`parseTable`+`RenderTable` / `FmtState` 加 color / `<span>` 分支 / `MessageRow` 渲染分支 + `CodeBlock` 修正 / `parseColorOrNull`）；`assembleDebug` BUILD SUCCESSFUL
- 编译中修复的两个衍生问题：① KDoc 注释里 `-/* 列表` 的 `*/` 提前关闭块注释；② 表达式函数体 `parseColorOrNull` 末尾多余 `}`；③ `TextUnit` 无 `+` 运算符（`size + scaled(6)` 改为 `size`）

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
