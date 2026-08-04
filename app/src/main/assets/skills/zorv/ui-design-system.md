---
name: ui-design-system
description: 专业UI生成与现有界面优化技能。当用户请求生成UI界面、设计页面、优化现有界面、创建组件库、设计系统、App界面、Web界面、Dashboard、Landing Page、或任何涉及视觉交互设计时必须触发此技能。参考Google Material Design、Apple HIG、以及Pinterest级别审美标准，输出大气简约、高级美学的UI代码。即使用户只是说"帮我做个界面"、"优化一下UI"、"设计一个页面"也必须触发。
---

# UI Design System Skill

## 📋 背景与设计哲学

### 核心定位
本 Skill 专注于生成**大厂级别、Pinterest 审美、高级简约**的 UI 界面。参考标准：
- **Google Material Design 3** — 色彩系统、动效原则、组件规范
- **Apple Human Interface Guidelines** — 空间设计、排版节奏、细腻质感
- **Fluent Design (Microsoft)** — Acrylic 材质、深度层次
- **Pinterest / Dribbble 高赞设计** — 视觉张力、留白美学、色彩大胆

### 设计价值观
> "少即是多，但每一处都要有意图"
- **克制的奢华**：不是堆砌，而是精准取舍
- **系统性思维**：每个组件都来自同一设计语言
- **情绪传达**：界面有温度，有性格，不是功能机器
- **细节决定品质**：阴影、圆角、间距都是设计语言

---

## 🎯 触发后的工作流程

### Step 1: 需求分析（必须先做）

分析以下维度，在回复前先梳理清楚：

```
用途分类:
├── 生成新UI    → 执行「UI Generation Protocol」
├── 优化现有UI  → 执行「UI Audit & Optimization Protocol」
└── 组件设计    → 执行「Component Design Protocol」

产品类型:
├── Mobile App (iOS/Android 风格)
├── Web App / Dashboard
├── Landing Page / Marketing
├── Admin / B端系统
└── 创意/作品集页面
```

**必须明确的信息（缺失时主动询问或合理假设并说明）：**
- 产品定位（面向谁？解决什么问题？）
- 品牌调性（科技感/温暖/奢华/年轻/专业？）
- 主色倾向（有偏好色系，或给我自由发挥）
- 交付格式（HTML/CSS、React/JSX、SVG 原型图）

---

## 🎨 色彩系统参考体系

### 参考「谷歌 Material You」调色板生成规则

**Tonal Palette 结构：**
```
Primary   → 品牌主色，用于关键操作和焦点元素
Secondary → 辅助色，用于标签、图标、次要按钮
Tertiary  → 对比色，用于强调差异化内容
Neutral   → 灰阶体系，背景/表面/文字层级
Error     → 错误/警告状态色
```

**推荐高级色板（可直接使用）：**

| 风格 | Primary | Secondary | Accent | Surface |
|------|---------|-----------|--------|---------|
| 极简白 | #1A1A2E | #16213E | #E94560 | #F8F9FA |
| 深空科技 | #6C63FF | #3ECFCF | #FF6584 | #0D0D1A |
| 自然有机 | #2D6A4F | #95D5B2 | #F4A261 | #FEFDF8 |
| 奢华金融 | #1C1C28 | #8B7355 | #C9A84C | #F5F3EF |
| 现代橙红 | #FF4B2B | #FF416C | #FFA07A | #1A1A1A |
| 苹果式灰 | #007AFF | #34C759 | #FF9500 | #F2F2F7 |
| 薰衣草紫 | #7B61FF | #B693FD | #4DC9D9 | #FAFAFF |
| 珊瑚暖调 | #FF6B6B | #FFE66D | #4ECDC4 | #FFFAF0 |

### Apple HIG 间距系统
```css
/* 参考 Apple 8pt 网格系统 */
--space-1: 4px;
--space-2: 8px;
--space-3: 12px;
--space-4: 16px;   /* 标准间距 */
--space-5: 20px;
--space-6: 24px;   /* 组件内间距 */
--space-8: 32px;   /* 区块间距 */
--space-10: 40px;
--space-12: 48px;  /* 大区块间距 */
--space-16: 64px;  /* 页面级间距 */
--space-24: 96px;
```

---

## 📐 排版系统

### 字体层级（参考 Apple SF / Google 字体）
```css
/* 优先使用有个性的字体组合 */

/* 选项 A: 科技感 */
--font-display: 'Space Grotesk', 'DM Sans';
--font-body: 'Inter', system-ui;

/* 选项 B: 人文优雅 */
--font-display: 'Playfair Display', 'Cormorant';
--font-body: 'Source Serif 4', Georgia;

/* 选项 C: 现代极简 */
--font-display: 'Syne', 'Outfit';
--font-body: 'Manrope', sans-serif;

/* 选项 D: 中文友好 */
--font-display: 'Noto Serif SC', serif;
--font-body: 'Noto Sans SC', sans-serif;

/* Type Scale (参考 Material 3) */
--type-display-large: clamp(40px, 5vw, 57px) / 1.12;
--type-display-medium: clamp(32px, 4vw, 45px) / 1.16;
--type-headline-large: clamp(24px, 3vw, 32px) / 1.25;
--type-headline-medium: clamp(20px, 2.5vw, 28px) / 1.29;
--type-title-large: 22px / 1.27;
--type-title-medium: 16px / 1.5;
--type-body-large: 16px / 1.5;
--type-body-medium: 14px / 1.43;
--type-label-large: 14px / 1.43;
--type-label-small: 11px / 1.45;
```

---

## 🧩 组件设计规范

### 卡片组件（Card）
```css
/* 参考 Material 3 Card Variants */
.card-elevated {
  background: var(--surface);
  border-radius: 12px;
  box-shadow: 0px 1px 2px rgba(0,0,0,.06), 
              0px 4px 16px rgba(0,0,0,.08);
  transition: box-shadow 200ms ease, transform 200ms ease;
}
.card-elevated:hover {
  box-shadow: 0px 4px 8px rgba(0,0,0,.08),
              0px 12px 32px rgba(0,0,0,.12);
  transform: translateY(-2px);
}

.card-filled {
  background: var(--surface-variant);
  border-radius: 12px;
  border: none;
}

.card-outlined {
  background: var(--surface);
  border-radius: 12px;
  border: 1px solid var(--outline-variant);
}
```

### 按钮系统（Button）
```css
/* Apple + Material 融合规范 */
.btn-primary {
  height: 44px;              /* Apple HIG 最小触控尺寸 */
  padding: 0 24px;
  border-radius: 10px;       /* Apple 风格圆角 */
  font-weight: 600;
  font-size: 15px;
  letter-spacing: -0.01em;
  transition: all 150ms ease;
}

/* 按钮层级: Filled > Filled Tonal > Outlined > Text > Icon */

.btn-primary:hover { filter: brightness(1.08); transform: translateY(-1px); }
.btn-primary:active { transform: translateY(0); filter: brightness(0.95); }
```

### 输入框（Input）
```css
/* Material 3 风格输入框 */
.input-outlined {
  height: 56px;
  border: 1.5px solid var(--outline);
  border-radius: 4px;
  padding: 16px;
  transition: border-color 200ms;
}
.input-outlined:focus {
  border-color: var(--primary);
  border-width: 2px;
}

/* Apple 风格输入框 */
.input-apple {
  height: 44px;
  background: rgba(120,120,128,0.12);
  border: none;
  border-radius: 10px;
  padding: 0 16px;
}
```

---

## ✨ 动效与交互规范

### 动效曲线（参考 Apple Spring 和 Material Motion）
```css
/* Apple 弹性曲线 */
--ease-spring: cubic-bezier(0.34, 1.56, 0.64, 1);
/* Material 标准曲线 */
--ease-standard: cubic-bezier(0.2, 0, 0, 1);
/* 强调曲线 */
--ease-emphasized: cubic-bezier(0.2, 0, 0, 1);
/* 快入慢出 */  
--ease-decelerate: cubic-bezier(0, 0, 0.2, 1);
/* 慢入快出 */
--ease-accelerate: cubic-bezier(0.3, 0, 1, 1);

/* 时长规范 */
--duration-short1: 50ms;
--duration-short2: 100ms;
--duration-short3: 150ms;
--duration-short4: 200ms;   /* 微交互 */
--duration-medium1: 250ms;
--duration-medium2: 300ms;  /* 标准转场 */
--duration-long1: 350ms;
--duration-long2: 400ms;    /* 复杂动画 */
--duration-extra-long: 600ms+; /* 页面级切换 */
```

### 必须实现的微交互
- **Hover 状态**：背景色变化 + 轻微位移
- **Active 状态**：轻压效果 (scale 0.97)
- **Focus 状态**：清晰的焦点环（无障碍必须）
- **Loading 状态**：骨架屏或脉冲动画
- **页面加载**：元素交错淡入 (stagger delay)

---

## 🏗️ UI Generation Protocol（生成新 UI）

### 执行顺序
1. **定义设计 DNA** — 确定调性、主色、字体组合
2. **建立 CSS 变量体系** — 完整的 token 系统
3. **搭建布局骨架** — Grid/Flex 响应式结构
4. **实现核心组件** — 从最重要的交互元素开始
5. **添加视觉质感** — 渐变、阴影、纹理、背景
6. **注入微交互** — 动效、过渡、Hover 状态
7. **精调细节** — 间距、对齐、字重、行高
8. **响应式适配** — Mobile First

### 代码规范
```html
<!-- 必须包含的 meta -->
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="preconnect" href="https://fonts.googleapis.com">

<!-- 结构原则 -->
<!-- 1. CSS Variables 在 :root 统一定义 -->
<!-- 2. 组件用 BEM 或语义化命名 -->
<!-- 3. 响应式用 clamp() 而非 media query 断点堆叠 -->
<!-- 4. 动画性能优先用 transform/opacity -->
```

---

## 🔍 UI Audit & Optimization Protocol（优化现有 UI）

### 分析维度（拿到现有 UI 后，必须从这 6 个维度审查）

**1. 视觉层级**
- [ ] 是否有清晰的信息优先级？
- [ ] 用户视线路径是否顺畅？
- [ ] 重要信息是否足够突出？

**2. 色彩系统**
- [ ] 色彩对比度是否达标（WCAG AA: 4.5:1 文字）？
- [ ] 颜色使用是否一致、有系统性？
- [ ] 是否存在色彩混乱或过多颜色？

**3. 排版质量**
- [ ] 字体层级是否清晰（最多 3 个层级）？
- [ ] 行高/字间距是否舒适？
- [ ] 字体选择是否与品牌调性匹配？

**4. 间距与对齐**
- [ ] 是否遵循基准网格（4pt 或 8pt）？
- [ ] 组件内外间距是否统一？
- [ ] 元素对齐是否精准？

**5. 组件一致性**
- [ ] 相同功能的组件样式是否统一？
- [ ] 交互状态是否完整（默认/Hover/Active/Disabled）？
- [ ] 圆角、阴影是否系统化？

**6. 现代感与审美**
- [ ] 是否有过时的 UI 模式（阴影过重、渐变廉价）？
- [ ] 是否有 Pinterest 级别的视觉吸引力？
- [ ] 留白是否足够、有设计感？

### 优化输出格式
```
## UI 审查报告

### 🎯 核心问题（3-5个最重要的）
1. [问题描述] → [具体改进建议]

### 🎨 设计升级方案
[输出改进后的完整代码]

### 📊 改进对比
Before: [原方案问题]
After: [改进后效果]
```

---

## 🎪 Component Design Protocol（单组件设计）

适用于：按钮、卡片、导航栏、表单、Modal、Toast、Tab、Chip 等单一组件。

**输出标准：**
- 包含所有状态（Default / Hover / Active / Focus / Disabled / Loading）
- 提供 Dark Mode 变体
- 包含使用示例
- 代码直接可用，不需要额外依赖（除非用户指定框架）

---

## 🌟 高质量 UI 的标志性特征

参考 Pinterest 高赞、Dribbble 精选的共同特征：

### 必须具备
1. **清晰的焦点** — 每个页面只有一个最重要的视觉中心
2. **呼吸感** — 足够的留白，不拥挤
3. **色彩自信** — 不是"安全"的配色，而是有个性的
4. **字体有品位** — 不用默认字体，字重搭配有对比
5. **圆角有度** — 统一的圆角半径，不乱用
6. **阴影克制** — 一两层精准的阴影，不堆叠
7. **动效有意义** — 每个动画都在传达信息

### 绝对避免
- 彩虹色渐变按钮
- 廉价 box-shadow（`0 4px 8px rgba(0,0,0,0.5)` 这种）
- 全大写 + 粗体 + 红色 的"重要提示"
- 16 种以上颜色同时出现
- 响应式断点堆叠（用 clamp 代替）
- 图标和文字对不齐
- 移动端点击区域小于 44px

---

## 📦 参考文件

需要深度参考时，读取以下文件：
- `references/color-systems.md` — 完整色彩系统参考（Material 3, Apple, 自定义）
- `references/component-patterns.md` — 高质量组件代码片段库
- `references/layout-templates.md` — 常见页面布局模板（Dashboard, Landing, App）
- `references/animation-library.md` — 精选动效代码库

---

## ⚡ 快速决策树

```
用户给了设计稿/截图要优化？
  → 执行 UI Audit Protocol，先分析问题，再输出改进版本

用户要从零生成界面？
  → 问清楚用途和调性，选择色板，执行 Generation Protocol

用户只需要某个组件？
  → 执行 Component Protocol，输出所有状态变体

用户没说清楚要什么？
  → 询问：① 是什么产品 ② 大概什么风格 ③ 要 HTML 还是 React
```
