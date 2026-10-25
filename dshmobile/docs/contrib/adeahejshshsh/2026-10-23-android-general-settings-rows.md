# 通用设置里的安卓自建项与上游行规不一致（2026-10-23）

来源：用户反馈截图（设置 → 通用设置，0.2.1-rc.2 引擎 / 竖屏）。同一张列表里出现两套行规：上游行是
「左标题 14/20 + 说明 12/18 + 右侧控件 + 0.5px 分隔线」，而本插件追加的四项是「13px 描边按钮 +
16px 浏览器原生勾选框 + 13px 提示段」，且语言选中态是整页唯一的绿色块。

## 现象

- 语言三个模式是自绘描边方按钮，选中项填 `state-success`（绿）；360–400 CSS px 下三个标签排不进
  标题旁，`flex-wrap` 把一个按钮（English）挤到第二行并拉满宽度。
- 沉浸式状态栏与左右侧栏滑动用 `<input type="checkbox">`：渲染成系统主题的方形勾选框（未选中灰框、
  选中系统蓝），与同页下方「在使用官方模型 API 时上传 Session Log」的上游开关（黑色胶囊）并排即两套控件。
- 四行都没有行间分隔线与 16px 上下内边距，提示文字紧贴上一行，滚动时与上游行节奏对不上。

## 真因

本分区复用了「开发者选项」的 `dsh-dev-*` 样式表（`dev-section.css.ts`）。那套样式是为**动作行**写的
（重启/关闭一排描边按钮、原生勾选框、提示段），与上游通用设置的行规不是同一套——上游行只在
`ui-settings-general` 的 `DeveloperToolsRow` / `ui-shortcuts` 的 `ShortcutReference` 里定义
（`padding:16px 0`、`border-bottom:.5px`、`gap:24px`、标题 `14/20`、说明 `12/18`）。
两种行规落在同一张列表里，就是从外观上「很突兀」的全部来源：字号、控件、分隔线三处同时不同。

## 修法

- 新增 `general-settings/general-settings.css.ts`：只描述行骨架（与上游同值），颜色/圆角/字号全部走
  `--dsw-*` 语义令牌；`index.ts` 在注册 `settings.general.item` 前注入。
- 控件换成上游 ui-primitives：布尔项用 `Switch`（与 Session Log 行同款），语言用 `SegmentedControl`
  （灰轨道 + 白色滑块，选中态与上游一致，不再有绿色）。
- 语言行改「标题 + 说明」在上、分段控件在下：三个标签在 360px（英文更宽）排不进标题旁，堆叠后不再
  折行拉满，控件按自身宽度左对齐。左右侧栏两项各带一句自己的说明，替代原先两条开关共用一条提示。
- 删掉只服务旧写法、已无引用的 `.dsh-dev-lang-choices` / `.dsh-dev-btn-active`。

## 复验

- 代码层：`dsh-client-ui-responsive` 全量 57 文件 / 854 通过 + 1 跳过（既有跳过项）；`tsc -p . --noEmit`
  通过；`tests/general-settings.spec.tsx` 16 项含新增结构断言（无原生 checkbox、四行都有标题、语言是
  `tablist` + 唯一 `aria-selected`、旧 `data-ui-language-mode` 不得回归）。
- 静态门禁：`node scripts/check-code-map.mjs` 通过（新增文件已进覆盖账本）；`check-dead-tokens.mjs` 因上游
  令牌定义面不在场 SKIP，另按引擎 0.2.1-rc.2 的 `ui-theme` 实测四个引用令牌全部存在（403 个令牌集合）。
- 版式对照：把上游行 CSS + ui-primitives 的 `Switch/SegmentedControl` CSS + 本表拼成静态页，在应用内
  AI 浏览器按 390 / 360 CSS px 截图核对——中文与英文下都单列成行、开关与 Session Log 行同一基线、
  分段控件不折行。该页是本地核对件，不随仓库提交。
- **未做设备验证**：只改了页面侧 runtime（`@dsh-android/dsh-client-ui-responsive`），未在模拟器/真机上
  跑三层验收；按 AGENTS §4.2 在 PR 里显式标注，进入发版候选前需补验。

## 5. 开关四角：全局 `corner-shape` 的坑（2026-10-23 同日补记）

现象：同一版视觉做成「皮肤插件」装机后，开关轨道两端发平、圆点变成小方块——不是圆角值不够大，
是四角被画成了超椭圆。

真因：`ui-theme` 的 `corner-shape.css` 在 `*`、`::before`、`::after` 上统一设了
`corner-shape: superellipse(1.5)`（`@supports` 包住）。自绘胶囊写 `border-radius:999px`、圆点写
`50%` 都仍被这条全局规则接管，渲染成方圆角。上游 `ui-primitives` 的 `Switch.module.css` 正是为此在
轨道与圆点各写了一处 `corner-shape: round`。

本 PR 的内置实现不受影响：开关直接用上游 `Switch`（自带两处 `corner-shape: round`），语言直接用上游
`SegmentedControl`（与上游一致地跟随全局超椭圆），本表也没有任何手绘圆角。把它记在这里是为了下次
有人在本分区手绘胶囊/圆点时不再踩：照上游 Switch 写 `corner-shape: round`，而不是把 `border-radius`
往大里加。

复验：7 倍放大对照（同尺寸、同令牌，仅差 `corner-shape`），未退出的一版轨道两端平直、圆点是圆角方块；
加上 `corner-shape: round` 后与上游 Switch 逐像素同形。
