# 会话库存储与数据安全

> 对应 GitHub issue #26（1.2.4 会话库在 App 未运行期间被清空）。本文说明会话库的真实路径、保护机制，以及它和 DSH 快照事务的文件边界。

## 真实路径（应用私有目录 `filesDir`，即 `/data/user/0/com.ai.assistance.quro/files/`）

| 路径 | 内容 |
|---|---|
| `quro_conversations.json` | 主会话库，全部会话 + 消息 + 工具调用的 JSON |
| `quro_backups/conv-yyyyMMdd-HHmmss.json` | 轮转备份（加载/保存时触发） |
| `quro_conversations.corrupt-<ts>.json` | 根 JSON 损坏时被隔离的原文件（只读降级，绝不覆盖） |
| `quro_conversations.dropped-<ts>.json` | 逐会话解析失败、被跳过的原始条目（1.2.5+） |
| `quro_conversations.orphan-<ts>.json` | 检测到会话数异常骤降时，写入前保留的现场（1.2.5+） |

`readOnly` 状态保存在 SharedPreferences `quro_conversation_health`，历史会话数高水位保存在 `conversation_high_water`（只增不减，作为防缩水基准）。

## 保护机制（1.2.5+）

1. **损坏隔离**：根 JSON 解析失败 → 改名隔离 + 只读降级，界面弹恢复对话框（从备份恢复 / 放弃隔离），用户选择前不自动覆盖。
2. **逐会话解析不再静默丢弃**：无法解析的条目计数并暴露到 `health.droppedConversations` / `droppedMessages`，原始条目另存 `quro_conversations.dropped-<ts>.json`，不直接丢。
3. **主文件丢失自愈**：若主文件在 App 未运行期间消失（被外部组件清理等），且历史会话数 ≥ 5，则自动从「会话数最多」的备份恢复；无可用备份则进入只读降级保护现场，**绝不把空库当新用户写回**。这堵住了「加载返回空 → ViewModel 新建新对话 → saveAll 覆盖」的二次破坏链。
4. **防缩水**：待写入会话数骤降到历史高水位的 40% 以下时，先把当前主文件另存为 `orphan-<ts>.json` 再写入，并在 `health.shrinkBlocked` 标记；旧数据始终可从 orphan / 备份取回。
5. **备份保留 ≥ 7 天**：备份有效期 8 天、数量上限 128 份（原为 8 份），避免「中间某天没开 App」导致关键节点被冲掉。

## 与 DSH 快照事务的边界

DSH 引擎的快照事务（`SnapshotTransaction` / `SnapshotFs`，`dshmobile` 模块）操作的是**另一片路径**，与会话库完全隔离：

- `filesDir/.snapshot-previous`（`PREVIOUS_NAME`）：换树时置换下去的旧 rootfs 副本；
- `filesDir/<installed-rootfs live tree>`：Linux rootfs 本体；
- `filesDir/**.failed-<ts>`：回滚/补偿时挪开的失败 live 树；
- `filesDir/control-shots`、`filesDir/home/tmp/dsh-tmp` 等引擎工作目录。

快照事务**不读写** `quro_conversations.json`、`quro_backups/`、`quro_conversations.*.json`。issue #25 里的 `snapshot-delete-residue` 报的是 `.snapshot-previous` 下 rootfs 内 `storage/emulated/0`、`sdcard` 等非应用属主条目删不掉，发生在 rootfs 子树，**不会触及会话库文件**。因此 #26 的会话丢失与 #25 的快照失败在文件层面无交集（时间上相关，但因果未证实）。

## 用户可做什么

- 出现「会话数据需要恢复」弹窗时：优先「从最近备份恢复」，不要直接「放弃并重新开始」。
- 怀疑丢数据时：检查 `quro_backups/` 里时间最近的、会话数最多的 `conv-*.json`，用弹窗恢复，或联系开发者提供该文件。
- 应用私有目录普通用户无法直接用 adb 读取；可用 App 内的备份导出功能（`adb backup` 导出的包里含 `quro_backups/`）。
