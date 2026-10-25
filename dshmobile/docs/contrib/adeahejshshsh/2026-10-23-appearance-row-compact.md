# 设置「外观」三张主题卡片压成一行（2026-10-23）

来源：用户反馈截图（设置 → 通用设置 → 外观）。浅色 / 深色 / 跟随系统三张卡片各占一整行，
整块约 330px，是设置页里最占纵向空间的一项。

## 现象

- 三张卡片在 360–412 CSS px 竖屏下必然一张一行（卡片 `flex:180px` + `padding:20px 32px`）。
- 每张卡片内部是「大图标 + 文字」的竖排，单张约 90px 高，三张加间距接近半屏。

## 真因

上游 `ui-theme` 的 `AppearanceRow` 给卡片写的是 `flex:180px`（basis 180px、可伸缩）与
`padding:20px 32px`，容器 `flex-wrap:wrap`。宽度不够时每张卡片各占一行——桌面宽度下是三个并排的
大方块，手机宽度下退化成三行。

## 修法

新增 `dsh-client-ui-responsive/src/client/appearance-row.css.ts`，在设置面板里把这一组压成一行：

- 作用域：`html[data-dsh-mobile-form] [data-shortcut-modal='settings']`（只影响手机形态的设置面板）；
- 定位：上游类名是 CSS Module 哈希，改用结构判据——「父容器里恰好三个 `aria-pressed` 按钮」。
  设置页里还有 1–2 个 `aria-pressed` 按钮的其它组（模型、市场等），`nth-last-child(3)` 把它们排除；
- 按钮：`flex:1 1 0`、`padding:8px 6px`、图标 16px、字号 13/18、横向排列；
- 整组一行：`div:has(> div > button…)` 让标题在左、选项在右；不支持 `:has()` 的老内核丢弃该条，
  退化为「标题在上、三个紧凑选项一行」，仍然远小于原样；
- 颜色与圆角只用 `--dsw-*` 语义令牌，选中态沿用上游自己的底色与描边。

## 复验

- `tsc -p . --noEmit` 通过；`dsh-client-ui-responsive` 全量 58 文件 / 855 通过 + 1 跳过（既有跳过项）。
- 新增 `tests/appearance-row-css.spec.ts`（4 项）：作用域必须在手机形态 + 设置面板下；按钮选择器必须带
  三按钮约束、不得出现裸 `aria-pressed`；`:has()` 只允许作用于组容器；颜色/圆角不得硬编码。
- `node scripts/check-code-map.mjs` 通过（新文件由 `glob:dsh-client-ui-responsive/src/client/*.ts` 覆盖）。
- 静态版式对照：用上游 `AppearanceRow` 的 CSS + 本表搭页，`html[data-dsh-mobile-form]` 下三选项并排一行，
  两按钮对照组保持原样（本地核对件，不随仓库提交）。
- **未做设备验证**：只改页面侧 runtime，未在模拟器/真机跑三层验收；进入发版候选前需补验。
