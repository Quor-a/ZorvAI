# 视频理解能力可行性排查与起点决策

**日期**：2026-07-19
**场景**：调试复盘 / 可行性核查（多成员协作）
**参与成员**：排障手（investigator，由主理人代行——当前环境 gstack 子代理类型不可用）+ 产品官（product-reviewer，由主理人代行）

---

## 📌 TL;DR（执行摘要）

- 整体结论：🟢 **可行，且比你原方案更省事**——你三层分析方向对，但短期方案里第 1 步、第 3 步的前提都不成立，真正要新写代码的只有「定时多帧截图」。
- 你列的短期方案：
  - 第 1 步「修 list_media 的 READ_MEDIA_VIDEO」→ **权限自 v62 已声明且运行时已请求，无需改代码**；list_media 失败只是运行时弹窗未授权。
  - 第 3 步「测 draw_openai 是否支持图像输入」→ **draw_* 是文生图（prompt:string），不是看图理解**；正确的视觉通道是聊天里已存在的多模态路径（QuroLlmClient 已支持 image_url）。
  - 第 2 步「screenshot_screen 定时截帧」→ **当前仅单帧，需加 frames+interval_ms 参数循环截**。这是唯一要写代码的点。
- 阻塞项数量：0（全部在 App 已有能力范围内）。
- 下一步：先做定时多帧截图工具，把帧作为 image 附件喂进现成的视觉聊天通道；必要的话并用已有的端侧/云端 STT 提音频轨。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟢 Go |
| 严重度分布 | 🔴 0 / 🟠 0 / 🟡 0 / 🟢 3（三项核查均无需紧急修复） |
| 关键行动项 | 1 条（新增定时多帧截图工具并接视觉聊天通道） |
| 建议负责人 | 主理人 / 实现侧 |

---

## 1. 各成员核心结论（每位 1 段）

### 🔧 排障手（代码核查）
- 核心判断：三项前提中两项不成立。`READ_MEDIA_VIDEO` 已在 `AndroidManifest.xml:27` 声明，且 `ListMediaTool.run()`（`QuroToolsMedia.kt:14-26`）运行时已按 `kind=="video"` 请求；`screenshot_screen`（`QuroToolsPerception.kt:94-154`）参数为空 `{}`，仅单帧、无间隔/帧数；`draw_openai/zhipu/qwen`（`QuroCmsRepository.kt:206-212`）入参签名 `prompt:string`，纯文生图。
- 关键建议：第 1、3 步无需改代码；第 2 步需给 `screenshot_screen` 增 `frames`+`interval_ms` 参数并循环调用 `takeScreenshot`。

### 🔍 产品官（计划与起点）
- 核心判断：视频理解的「视觉通道」其实早已存在——聊天层 `QuroLlmClient.kt:101-126` 会把 `image` 类型附件以 `image_url`（base64）发给视觉模型。所以「简化版视频理解」≈ 抽若干帧 + 作为 image 附件进聊天 +（可选）音频轨经 STT 转写。`draw_*` 是生成不是理解，别走错门。
- 关键建议：起点定为第 2 步（定时多帧截图），并立即复用现有视觉聊天通道；把 ASR（端侧 Sherpa-NCNN / 云端 STT，v49/v58 已落地）作为音频轨长期方案。

> 仅实际上场成员列入；gstack-investigator / gstack-product-reviewer 子代理类型在当前运行环境不可用，相关产出由主理人基于对真实代码的核查代行。

---

## 2. 综合审查发现（去重合并后按严重度排序）

| # | 严重度 | 类别 | 位置 | 问题描述 | 建议 | 来源成员 |
|---|--------|------|------|---------|------|---------|
| 1 | 🟢 | 权限 | `AndroidManifest.xml:27` / `QuroToolsMedia.kt:14-26` | 用户误判 list_media 缺 READ_MEDIA_VIDEO；实际已声明且运行时请求 | 无需改；若失败引导用户授权运行时弹窗 | 排障手 |
| 2 | 🟢 | 能力缺口 | `QuroToolsPerception.kt:94-154` | screenshot_screen 仅单帧，无定时多帧 | 新增 `frames`(默认1)、`interval_ms`(默认1000) 参数，循环 takeScreenshot | 排障手 |
| 3 | 🟢 | 误用 | `QuroCmsRepository.kt:206-212` | draw_* 为文生图，非视觉理解，不能作为「喂图给模型」通道 | 改用 QuroLlmClient 多模态聊天通道 | 排障手 / 产品官 |

---

## ✅ 行动清单（具体可执行项）

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 给 `screenshot_screen` 增 `frames`(默认1)、`interval_ms`(默认1000) 参数，循环截取并批量返回帧路径 | 实现侧 | P0 | 下个版本 |
| 2 | 提供「把多帧作为 image 附件喂入视觉聊天」的接线（复用 `QuroLlmClient` 现有 `image_url` 逻辑） | 实现侧 | P0 | 同上 |
| 3 | 长期：视频音频轨经端侧 Sherpa-NCNN / 云端 STT 转写，与帧描述合并成「视频理解」结果 | 实现侧 | P2 | 后续版本 |

---

## ⚠️ 待完善 / 已知局限

- 定时多帧截图的实现需注意：`takeScreenshot` 单次有 8s 回调等待（`QuroToolsPerception.kt:149`），多帧需顺序 await 或并发管理；高帧率+长时长会占满无障碍服务。
- 抽帧是「看屏幕」，视频若在后台/锁屏不可见则截不到；真正的视频文件逐帧解析（FFmpeg / MediaMetadataRetriever）是更稳的长期方案，但属长期项。
- 视觉模型是否真支持多图取决于所配模型；需用户确认在用模型为多模态。

---

## 📚 成员产出索引

- 排障手原始产出：本报告中「各成员核心结论→排障手」及「综合审查发现」三行（由主理人代行，因 gstack-investigator 子代理在当前环境不可用）。
- 产品官原始产出：本报告中「各成员核心结论→产品官」及行动清单（由主理人代行，因 gstack-product-reviewer 子代理不可用）。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
