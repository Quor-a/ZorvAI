# UI 重构设计 — 简洁 · 优雅 · 现代(Double-Bezel + teal 点缀 + 双主题)

日期:2026-08-20(设计演进:v1「高端极简」→ v2「简洁优雅现代」,已实现并合入 PR #61)
状态:已实现

## 背景与审计

初版实现(teal token + 单层描边卡片 + 幽灵按钮)被判定为"效果极差"。对照
redesign/high-end 设计规范重新审计,问题清单:

- 卡片是 generic card look(单层 border + 纯色,无层次、无质感)
- 「实心 + 描边幽灵」按钮组合是 AI 模板反模式(按钮区视觉噪音)
- 元素静态挂载,零入场动效,界面"死"
- 背景纯平铺色,无氛围分层
- 字号层级过近(16/13/11),无字重区分,标题无存在感
- 无状态徽章/胶囊等细节,进度条轨道粗糙

## 设计语言(v2 落地)

**Soft Structuralism + Double-Bezel 双边框嵌套架构**:

- 外层 shell:微影/微白半透明底 + hairline 描边 + 大圆角 26dp(浅色 `ds_shell` 12% 黑,深色 6% 白)
- 内层 core:表面色 + 同心圆角 22dp(半径逐层收窄,同心曲线)
- 背景氛围:暖灰 `#F6F6F4` / 近黑 `#0A0A0C` + 顶部 teal 径向微光(`ds_glow` 5-6% 透明度,克制)
- 动效:入场 stagger(品牌/卡片/操作区按 90ms 间隔淡入上移 14dp,420ms,`cubic-bezier(0.32,0.72,0,1)`);按压物理反馈(scale 0.97 + Ripple)

## 设计 Token

中性暖灰单一家族;非纯黑/纯白;全 UI 仅一个高饱和点缀色 teal。

### 颜色(res/values/colors.xml + values-night/colors.xml)

| Token | 浅色 | 深色 |
|---|---|---|
| ds_bg | `#F6F6F4` | `#0A0A0C` |
| ds_shell | `#12000000`(黑 7%) | `#0FFFFFFF`(白 6%) |
| ds_surface | `#FFFFFF` | `#141417` |
| ds_border | `#0F000000`(黑 6%) | `#14FFFFFF`(白 8%) |
| ds_text_primary | `#191919` | `#ECECEC` |
| ds_text_secondary | 主文本 40% | 主文本 55% |
| ds_text_tertiary | 主文本 24% | 主文本 35% |
| ds_text_on_accent | `#FFFFFF` | `#0A0A0C` |
| ds_accent / ds_accent_pressed | `#0D9488` / `#0F766E` | `#2DD4BF` / `#5EEAD4` |
| ds_accent_soft(图标容器底) | teal 8% | teal 12% |
| ds_glow(背景微光) | teal 5% | teal 6% |
| ds_ok / ds_warn / ds_danger | teal / `#B45309` / `#B91C1C` | teal / `#F0B429` / `#F87171` |
| ds_danger_soft(崩溃徽章底) | danger 8% | danger 10% |
| ds_progress_track | 黑 8% | 白 10% |

### 字体与排版

- 中文:系统字体栈 `sans-serif`;标题/状态/按钮 `sans-serif-medium`
- 等宽 `monospace`(日志摘要、控制台)
- 基准字号:状态 17sp(1.15 行高)、标题 22sp、按钮 14sp、次要 13sp、徽章/日志 11-12sp

### 布局(res/values/dimens.xml)

8pt 网格:4/8/12/16/24/32/40/48。圆角:内嵌 6(sm)、卡片 22(card)、外壳 26(shell)、
图标容器 18(icon)、胶囊 999(pill)。进度条 4dp;主按钮高 52dp;logo 容器 48dp(内含 40dp 图标)。

## 原生启动/测试界面(MainActivity.buildGuideView)

三段式结构,根布局带顶部 teal 微光渐变:

1. **品牌区**:squircle 图标容器(48dp,accent_soft 底 + teal 1px 描边,内含 40dp 图标)+
   "DeepCode" 字标(22sp medium),垂直居中,下留白 48dp
2. **状态卡(Double-Bezel)**:外层 shell(26dp 圆角)+ 内层 core(22dp 圆角,28dp 内衬):
   - 状态文本(17sp medium primary,行高 1.15)
   - 崩溃徽章:胶囊(danger_soft 底 + danger 文字 12sp medium),不再纯红刺眼
   - 进度条:4dp 胶囊轨道 + teal 填充(ClipDrawable),更新/解压时可见
   - 进度文本(13sp secondary)、日志摘要(等宽 11sp tertiary)
3. **操作区**:胶囊主按钮「启动引擎」(52dp,teal 实心 + 白字,Ripple 按压 + scale 0.97);
   文字样式次操作「打开控制台」「检查更新」(teal 文字,透明底 Ripple,克制无边框)

入场动画:`animateGuideReveal()` 在三段块上做 stagger(0/90/180ms,420ms 淡入上移)。

## 控制台 console.html

- 背景:近黑 `#0A0A0C` + 顶部 teal 径向微光(radial-gradient,与原生同调)
- 玻璃状态条:垂直渐变分层 + hairline 底边;状态点胶囊化并带同色光晕(ok=teal/err=danger)
- 输入区:双边框输入岛——外层渐变 shell + 内层输入框(14px 圆角,inset 顶部高光);
  focus 时 teal 描边 + 3px teal ring(18% 透明度)
- 发送按钮:胶囊(999px)+ 深色文字,按压 scale 0.96 + 亮一档
- 输出:行高 1.6,细滚动条(6px 胶囊 thumb)
- 过渡统一 `cubic-bezier(0.32,0.72,0,1)`;JS 逻辑(裁剪/滚动/桥调用)零改动

## 验证

- 远程 CI 通过:`:app:compileDebugKotlin` + 冲突标记/敏感信息扫描(PR #61)
- 深/浅色两套资源键集合一致(`ds_*` 键对齐)
- 静态检查:无 `0xFF` UI 魔数残留;HTML 变量定义/引用集合一致