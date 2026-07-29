# 14 项需求 · 代码定位与执行计划

**日期**：2026-07-27
**场景**：需求侦察 + 执行规划（排障手视角）
**参与成员**：排障手（代码定位 / 现状诊断）

---

## 📌 TL;DR
- 14 项需求已归并为 6 个史诗任务（#803–#808），**全部代码已定位**。
- **Shizuku（#808）现状诊断**：当前代码树的 Manifest 已同时声明 `API` + `API_V23` 两个权限、`dev.rikka.shizuku:api 13.1.5` 已接入 → **可见性前置条件满足**，现行代码不是"看不见"的根因。
- **Operit 上游源码本机缺失**：`D:\Calw OS-project` 下只有 `Calw OS/`、`MoWenApp-src/`、`droid-mcp-src/` 等去品牌化/参考工程，**无 operit 目录**。#805（流式/压缩）与 #808（Shizuku）要求"查看operit解决方案"无法本地对齐。
- 待用户确认：① Operit 源码如何处理；② 先开工哪个史诗。

---

## 1. 代码定位映射（14 项 → 文件）

### #803 语音服务 UI 重做（6 子项）
| 子项 | 文件 |
|------|------|
| 对话框按钮改"长按说话放开结束" | `ui/ChatScreen.kt` L773-791（`startDialogStt`/`onVoiceInput`→`Composer`）+ `core/tools/QuroSttRecorder.kt`（缺 stop 句柄）+ `Composer` 组件（需加 pointer down/up） |
| 语音设置 UI 重做 | `ui/QuroVoiceSettingsScreen.kt`（L27 v139 重构）、`ui/ChatScreen.kt` L1231 内嵌面板 |
| 语音合成-删除已配置模型 | `ui/QuroTtsSettingsScreen.kt`（L446 "已配置模型的语音合成将在后续版本开放"） |
| 语音合成-云模型服务商 UI 重做 | `ui/QuroCloudTtsConfigScreen.kt`（L104）、`core/tools/QuroTtsProvider.kt`（L79-369 服务商枚举）、`core/tools/QuroCloudTtsCatalog.kt` |
| 语音合成 UI 重做 | `ui/QuroTtsSettingsScreen.kt` |
| 语音服务导航中心 | `ui/QuroVoiceServiceScreen.kt`（L32 v338 重写） |

### #804 ACT/ACI 管理中心重做（5 子项）— 注意：代码实为 **ACI**
| 子项 | 文件 |
|------|------|
| 改名 + ACT 关联启动 | `ui/QuroAciCenterScreen.kt`、`core/aci/QuroAciManager.kt` |
| 手动注册 ACI App + 按名称搜索 | `core/aci/QuroAciManager.kt`、`core/aci/QuroAciTools.kt` |
| 合体（合并入口） | `ui/QuroAciCenterScreen.kt` |
| 已发现 App 添加手动启动 | `core/aci/QuroAciManager.kt`（aci_list 发现逻辑，见 `QuroChatViewModel.kt` L1058） |
| UI 重做 | `ui/QuroAciCenterScreen.kt` |

### #805 对话框体验（2 子项，需 Operit 参考）
| 子项 | 文件 |
|------|------|
| 流式改成"慢慢流式" | `ui/ChatScreen.kt` 流式渲染管线、`ui/QuroChatViewModel.kt` 流式输出 |
| 上下文压缩（达阈值自动压缩） | `core/memory/QuroMemoryStore.kt`、`ui/QuroChatViewModel.kt` 上下文管理 |

### #806 机器人 UI 重做
| 文件 |
|------|
| `ui/QuroBotSettingsScreen.kt`、`core/bot/QuroBotManager.kt`、`core/bot/QuroBotReplyEngine.kt`、`core/bot/adapters/*`（Wechat/QQ/Local/Direct/Feishu） |

### #807 AI 人格心跳孵化 ANR 检测
| 文件 |
|------|
| `core/QuroPersona.kt`、`ui/QuroPersonaViewModel.kt`、`ui/QuroSoulUi.kt`、`core/soul/QuroSoulPrompt.kt`、`util/AnrMonitor.kt`（v354 已双写 Download 目录） |

### #808 修复 Shizuku 不显示（#807 并列回归类）
| 文件 |
|------|
| `core/shizuku/QuroShizuku.kt`、`core/privilege/QuroShizukuBridge.kt`、`core/privilege/QuroPrivilegeManager.kt`、`core/QuroPlatformManifest.kt`、`app/src/main/AndroidManifest.xml` L88-93 |

---

## 2. Shizuku 诊断（#808，排障手结论）

**核心判断**：当前代码树可见性前置条件**已满足**，不是"看不见"的代码根因。
- `AndroidManifest.xml` L92-93 同时声明 `moe.shizuku.manager.permission.API`（旧版管理器识别）与 `API_V23`（新版识别），注释明确说明只声明 V23 会导致旧版管理器不列出本 App。
- `app/build.gradle.kts:135` `implementation(libs.shizuku.api)`；`libs.versions.toml` shizuku=13.1.5。

**可能根因（需真机确认，非代码侧缺失）**：
1. 当前安装包构建于权限修复之前、且未重装 → Shizuku 管理器未重新扫描到新权限。
2. 设备端 Shizuku 管理器缓存 / 版本差异（用户环境有阿里云无影 `com.aliyun.wuying.enterprise` 占 CPU，可能干扰）。
3. 极少数情况 Manifest 合并 `node="remove"` 剥离权限——已 grep 确认无。

**建议行动**：
- 真机：重装最新 QuroAI 后，在 Shizuku 管理器「已授权应用」列表查看是否出现；告知 Shizuku 管理器版本号。
- 代码侧（低风险加固，实施 #808 时做）：补 `<queries>` 声明 Shizuku 包（防 Android 11+ 包可见性边缘情况，虽非可见性主因，零风险）；并加一条启动期日志打印 `Shizuku.checkSelfPermission()` 便于用户从 `Download/QuroAI_logs/` 取回。

**关键建议**：不要在没有真机确认前"赌"改 Manifest/依赖——当前已正确，赌改只会引入回归。

---

## 3. Operit 参考缺口

- 用户要求 #805、#808 "查看operit解决方案"。
- 本机 `D:\Calw OS-project` 内容：`Calw OS/`、`MoWenApp-src/`、`QuroAI/`、`droid-mcp-src/`、`_aar/`、`_calw_backup/`、`calw_os_port_backup_20260716_211155/` 等。
- **无 operit 目录**；仓库内 "operit" 仅出现在 README/NOTICE/部分文件头（license 归属声明），非源码。
- 即：无法本地对齐 Operit 的流式/压缩/Shizuku 实现。需用户指路，或退而用第一性原理 + 本地 `MoWenApp-src/` 等参考。

---

## 4. 提议执行顺序（待确认）

1. **先修回归/最具体**：#808 Shizuku（低风险加固）+ #807 人格 ANR 检测。
2. **#803 语音服务 UI 重做**（用户首列、子项最具体，含长按说话交互）。
3. **#804 ACI 管理中心重做**（改名/关联启动/手动注册/搜索/UI）。
4. **#806 机器人 UI 重做**。
5. **#805 对话框流式 + 上下文压缩**（依赖 Operit 参考决策）。

---

## ⚠️ 待完善 / 已知局限
- Operit 上游源码本机缺失，影响 #805/#808 的"对齐实现"。
- Shizuku 回归需真机数据定稿（当前代码侧已正确）。
- #803 长按说话需改造 `Composer` + `QuroSttRecorder`（加 stop 句柄），不可盲改。

---

> 本报告由软件工坊 AI 协作生成（侦察阶段），关键决策请由工程负责人复核。
