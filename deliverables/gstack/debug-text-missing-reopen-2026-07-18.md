# QuroAI 对话框「退出重开文本消失」根因定稿报告

**日期**：2026-07-18
**场景**：调试复盘（对话框持久化 / UI 渲染）
**参与成员**：排障手（调试与根因）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 已定位并修复（真凶 = 空 `toolCalls` 数组误导 UI 渲染分支）
- 阻塞项数量：0
- **一句话根因**：`serializeMsg` 给**每条消息**都写了 `"toolCalls": []`；reload 后该空数组被 `uiMessages` 的 `m.toolCalls != null` 判定为"有工具调用" → 走「工具调用块」分支 → 正文 `text` 被强制 `null` → 文本消失。**文本从没丢，是 UI 不显示。**
- 下一步：装最新 APK，旧对话会**自动恢复**文本（无需删数据 / 导出）。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go（已修复） |
| 严重度分布 | 🔴 1（根因）/ 🟢 0 |
| 关键行动项 | 2 条（已落地） |
| 建议负责人 | 主理人（已执行） |

---

## 1. 各成员核心结论

### 🔧 排障手（调试与根因）

- **核心判断**：文本持久化本身完全正常（磁盘 `content` 一直有值），真正的 bug 在 **reload → UI 映射** 这一段。`serializeMsg` 无条件写出 `"toolCalls": []`，导致 reload 后每条消息的 `toolCalls` 字段变成"非 null 空列表"；`uiMessages` 首判定 `m.toolCalls != null` 误判为"有工具调用"，从而走「工具调用块」分支、把 `text` 置空。live 时 `toolCalls` 默认 `null` 走 else 分支正常显示，因此表现为"live 有、reload 无"。
- **关键建议**：① `serializeMsg` 仅在确有工具调用时写 `toolCalls` 字段；② `uiMessages` 改用 `isNotEmpty()` 判定，使已有旧数据（含空数组）也能立即恢复文本。

---

## 2. 综合审查发现

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🔴 | UI / 持久化 | `QuroConversationPersistence.serializeMsg` L141/L174 + `ChatScreen.uiMessages` L235/L254 | `serializeMsg` 无条件 `val calls = JSONArray()` 并对每条消息 `put("toolCalls", calls)`（空数组）；reload 时 `parseMsg` 读空数组 → `toolCalls` 非 null 空列表 → `uiMessages` 的 `m.toolCalls != null` 为真 → 走工具块分支 → `text = null` → 正文消失 | `serializeMsg` 仅当有真实调用时写字段；`uiMessages` 改用 `isNotEmpty()` | 排障手 |

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | `serializeMsg` 仅在 `toolCalls` 非 null 且非空时写字段（attachments 同理） | 主理人 | P0 | 已完成 |
| 2 | `uiMessages` 分支判定 `m.toolCalls?.isNotEmpty() == true`（双保险，旧对话自愈） | 主理人 | P0 | 已完成 |
| 3 | 装最新 APK 验证：旧对话退出重开后文本恢复 | 用户 | P1 | 待验证 |

---

## ⚠️ 待完善 / 已知局限

- 前 11 轮修复（content 兜底 / `migrateAndClean` 自愈）虽精准但治标，未触及本根因；本次修复后那些兜底可保留作防御，不影响正确性。
- `repro_roundtrip.py` 当初用 Python 复刻时**未精确还原"无条件写空数组"行为**，产生假阴性，误导了第 10 轮"文本能存活"的结论。真正的根因定位依赖逐行代码审计，而非脚本复刻。
- 本次未做真机 Logcat 验证（设备未连本机），修复正确性由静态链路推演 + 编译通过保证；建议用户装包后实测旧对话恢复情况。

---

## 📚 成员产出索引

- 排障手（主理人代行）原始产出：逐行审计 `serializeMsg` / `parseMsg` / `uiMessages` / `selectConversation` / `send`，定位"空 `toolCalls` 数组误导 UI 分支"导致 reload 后正文被吞。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
