# 调试复盘：ViewTreeLifecycleOwner not found（parentPanel）崩溃溯源

**日期**：2026-07-28
**场景**：调试复盘（根因溯源 / 非盲改）
**参与成员**：排障手（gstack 编排本环境不可用，主理人直干；代码核查 + 分析报告）

---

## 📌 TL;DR（执行摘要）

- 用户分享元宝（Yuanbao）对 `java.lang.IllegalStateException: ViewTreeLifecycleOwner not found from android.widget.LinearLayout{... #1020464 android:id/parentPanel}` 的分析链接。元宝结论：**经典「传统 AlertDialog / Dialog 内塞 ComposeView，却没给 LifecycleOwner」** 崩溃。
- 经完整代码核查：QuroAI 三个 Service 宿主（IME 键盘 / 语音球 / 粘贴键盘）的 `ComposeView` **已全部 `setViewTreeLifecycleOwner`**（v402 移植上游 operit 的修法，v406 仍在）；全工程 `src/main/java` **不存在任何传统 AlertDialog/Dialog/PopupWindow/setContentView**。
- 矛盾点：`parentPanel`（`com.android.internal.R.id.parentPanel`）**只可能**来自传统 `AlertDialog` 的内容容器，但本工程源码里根本没有这种宿主 → 该签名不应由我们可控的代码路径产生。
- 结论：**当前 v406 代码层面，元宝推荐的修法已经落地，且不存在会触发该签名的传统 Dialog**。若真机仍复现，根因大概率不在 IME（原 v400 诊断可能判错），需真机日志坐实。
- 下一步：**不静态盲改**，请在 v406 上复现并取 `Download/quro_crash.txt`（MediaStore，文件管理器可见）核对真实堆栈与崩溃组件。

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| Go / No-Go | 🟡 条件 Go（代码已合规，待真机日志确认无复发） |
| 严重度分布 | 🔴 0（确认项）/ 🟠 0 / 🟡 1（需日志坐实） / 🟢 已修复项若干 |
| 关键行动项 | 1 条（取真机崩溃日志） |
| 建议负责人 | 主理人 + 用户（真机验收） |

---

## 1. 元宝分析要点（转述）

- `ViewTreeLifecycleOwner not found`：Compose 的 `ComposeView` 在 `onAttachedToWindow` 时必须从父 View 树找到 `LifecycleOwner`，否则直接抛异常。
- `#1020464 android:id/parentPanel`：这是 `AlertDialog`/`Dialog` 用来承载内容的 `LinearLayout`，说明 `ComposeView` 被放进了**传统 Dialog** 的视图层级。
- 推荐修法（按可靠度排序）：
  1. 直接用 Compose 的 `Dialog {}` / `AlertDialog()`（最推荐）；
  2. 用 `ComponentDialog`（实现 `LifecycleOwner`）替代传统 `Dialog`；
  3. 手动 `ViewTreeLifecycleOwner.set(dialog.window.decorView, owner)`；
  4. 用 `DialogFragment`（天然有 `viewLifecycleOwner`）。

---

## 2. 代码核查事实（已上场证据）

| # | 文件 | 位置 | 事实 |
|---|------|------|------|
| 1 | `service/QuroAiKeyboardService.kt` | L72–78 | `ComposeView(this).apply { setViewTreeLifecycleOwner(lo); setViewTreeViewModelStoreOwner(lo); setViewTreeSavedStateRegistryOwner(lo); setContent {…} }` —— owner 在 `setContent`/返回前已设 ✓ |
| 2 | `service/QuroVoiceBallService.kt` | L205–215 | 同上三件套已设 ✓ |
| 3 | `service/QuroPasteKeyboardService.kt` | L88–95 | 同上三件套已设 ✓ |
| 4 | `core/util/QuroServiceLifecycleOwner.kt` | 全文 | 移植自 operit 的 `ServiceLifecycleOwner`，实现 `LifecycleOwner + ViewModelStoreOwner + SavedStateRegistryOwner`；`performRestore` 仅 `init` 一次（v405 已修掉二次 restore 崩溃）✓ |
| 5 | 全工程 `src/main/java` | grep | 无 `AlertDialog.Builder` / `MaterialAlertDialogBuilder` / `PopupWindow` / `setContentView` / 传统 `Dialog(`；所有 `AlertDialog(`/`Dialog(` 均为 Compose 版 ✓ |
| 6 | `AndroidManifest.xml` | L145–263 | 仅 `QuroMainActivity`、`CropImageActivity`，均为正常 Activity，无对话框主题 Activity ✓ |

> 关键判断：**我们早已采用元宝推荐的「给 ComposeView 手动注入 LifecycleOwner」方案**（且是上游验证过的 operit 同款），因此 IME/Service 这一类不再缺 owner。

---

## 3. 矛盾与待解项

- `parentPanel` 是传统 `AlertDialog` 独有资源 id。Compose 版 `AlertDialog`/`Dialog` 不携带它；IME 的 `SoftInputWindow` 也不携带它。
- 既然源码里没有传统 `AlertDialog` 宿主 ComposeView，理论上该签名**不应出现**。两种可能：
  1. 用户贴的是元宝页面里的**示例崩溃文本**（含 `1785249165909` 时间戳），并非本机 v406 真实复现；
  2. 真机仍复现，则根因来自**未被本次核查覆盖的窗口/第三方库宿主**，或用户尚未安装 v402+（仍在 v400/v401 的反射桥旧实现上）。
- 原 v400 把根因判为「IME 键盘 + parentPanel」存在疑点：IME 窗口并非 `parentPanel` 容器，该判定可能当时就偏了。

---

## ✅ 行动清单

| # | 行动 | 负责方 | 紧急度 | 期望完成 |
|---|------|--------|--------|---------|
| 1 | 在**当前 v406** 上复现该崩溃；若复现，从手机 `Download/quro_crash.txt`（MediaStore，文件管理器可见，无需 adb）取完整堆栈回传 | 用户 | P0 | 复现即取 |
| 2 | 拿到真实堆栈后，据其 `at …` 首行与崩溃组件（IME / 语音球 / 粘贴键盘 / 主 App Dialog）定位确切宿主，再决定补丁 | 主理人 | P0 | 日志到手后 |
| 3 | 若确认仍来自某 Service：在其 `onDestroyInputView`/`onDestroy` 补生命周期 `pause/destroy`，并对窗口 decor 兜底 `setViewTreeLifecycleOwner`，做双保险 | 主理人 | P1 | 确认后 |

---

## ⚠️ 待完善 / 已知局限

- 本环境 gstack 团队编排不可用（`Agent` 派发 `gstack-*` 报 not available），本次由主理人直接执行排障手职责并落盘。
- 未做静态盲改：遵循用户既定规则「实测未修复先取 App 自带诊断日志，绝不静态盲改」。本次仅为代码核查 + 溯源分析。
- 未构建新版本（v407 暂缓，待真机日志）。

---

## 📚 成员产出索引

- 排障手（主理人直干）原始产出：见本文件第 1–3 节代码核查与矛盾分析。

---

> 本报告由软件工坊 AI 协作生成，关键决策请由工程负责人复核。
