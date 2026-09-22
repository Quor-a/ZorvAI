---
name: zorv-mobile-patterns
trigger: 做个,来个,生成,列表页,详情页,设置页,空状态,个人中心,首页,仪表盘,骨架
description: 移动端界面模式库 —— 仪表盘、落地页、列表、详情、表单、空状态、设置、个人中心等 8 类常见界面的标准骨架与推荐组件结构。当用户要求生成任何具体界面（"做个天气卡""做个设置页""做个列表"）时触发，用来确定这一屏该由哪几块组成。
---

# 移动端界面模式（ZorvAI GenUI）

**先确定这是哪一类界面**，再套骨架。骨架错了，配色再好也别扭。

手机一屏的黄金容量：**标题 1 个 + 主体 1~2 块 + 操作 1~2 个**。超过就分层/滚动。

---

## 1. 仪表盘 / 数据看板

```
column
├ heading / h1            标题（如"今日概览"）
├ caption                 时间 / 副标题
├ row                     KPI 区（2~3 个等宽卡片）
│  ├ kpi_card  数值 + 标签
│  ├ kpi_card
│  └ kpi_card
├ card                    图表区
│  └ chart_bar / chart_line / stat_card
└ column                  明细列表（section + list_item）
```
**要点**：KPI 用 `row` 等分；图表给固定高度（160~200）；数字用大字号（24sp+）。

## 2. 落地页 / 介绍页

```
column
├ heading1        大标题（26sp+）
├ body            一句话价值主张
├ row             主操作按钮（1 主 1 次）
├ card            核心卖点（图标 + 标题 + 描述，纵向 3 条）
└ card            社会证明 / 次要信息
```
**要点**：一屏一个主张；按钮最多 2 个；卖点用 `row`（图标+文字）纵向堆 3 条。

## 3. 列表 / 信息流

```
column
├ row             标题栏（heading + 右侧操作图标）
├ list_item × N   每行：左侧头像/图标 + 中间标题+副标题 + 右侧箭头/数值
└ （空时）empty_state
```
**要点**：每行结构必须一致；`list_item` 内部用 `row`，**中间那列给宽度**，右侧图标不给宽度。

## 4. 详情页

```
column
├ row             返回 + 标题
├ image / carousel 主图
├ column          核心信息（heading + 价格/关键数值 + 标签组 chip）
├ divider
├ section         详细说明（body 文本）
└ button          底部主操作（满宽）
```
**要点**：主操作放最后且满宽；说明文字用 `body`，不要用多个 `text` 拼段落。

## 5. 表单 / 录入

```
column
├ heading         表单标题
├ column          字段组（label + input，纵向排列，间距 16）
│  ├ text_field / input
│  ├ dropdown
│  └ switch / checkbox
├ banner / alert  校验提示（有错才出现）
└ button          提交（满宽，放最后）
```
**要点**：一个字段一行；标签在上、输入框在下；提交按钮满宽且置底。

## 6. 空状态

```
column（居中）
├ icon / image    占位图形
├ heading         一句话说明（"还没有记录"）
├ body            引导语（可选）
└ button          引导操作（可选）
```
**要点**：**居中排列**；文案要给人下一步动作；不要放空卡片。

## 7. 设置页

```
column
├ heading         设置
├ section         分组 1（section 标题 + list_item × N，带 switch/箭头）
├ section         分组 2
└ section         分组 3（危险操作用红色文字）
```
**要点**：用 `section` 分组；每行左侧标签+右侧控件（`row`，标签给宽度）；危险项单独一组。

## 8. 个人中心 / 账户

```
column
├ card            头部（avatar + 名称 + 等级/标签，深色或强调色底）
├ row             数据条（关注/粉丝/积分，等分 3 列）
├ section         功能入口列表（图标 + 文字 + 箭头）
└ button          退出登录（描边样式）
```
**要点**：头部卡片用强调色底 + 浅色文字做对比；数据条等分。

---

## 通用规则（所有模式）

1. **一屏一个主要任务**，不要塞两个同等重要的区块。
2. **标题在最上、主操作在最下**（或右下悬浮）。
3. **同类信息用同一种组件**（不要一会儿 card 一会儿 list_item）。
4. **内容不够就让它短**，不要撑高凑满屏。
5. **不确定结构时优先用 `column` 纵向堆**，比强行分栏安全得多。

## 组合用法

```
zorv-mobile-patterns  确定这一屏的骨架
  + zorv-design-systems  选配色 token
  + zorv-visual-art      调质感与装饰
  + zorv-ui-craft        检查布局与命名的坑
  + zorv-ui-critique     生成后自检打分
```
